package io.folddeck.spike;

import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * Touch input over the video pane.
 *
 * Two modes, toggled by the first chip on the {@link ShortcutBar}:
 *
 *  DIRECT   the cursor goes where you touch. Right for a screen showing the
 *           desktop -- touching a thing should put the cursor on that thing.
 *  TRACKPAD relative movement with acceleration, for precise work. Accumulated
 *           on this side and sent as an absolute position, which keeps the host
 *           to a single uinput device: declaring both ABS_X/Y and REL_X/Y on one
 *           device makes libinput's classification ambiguous.
 *
 * Gestures: tap = left click, long press = right click, double-tap-and-hold =
 * drag, two-finger drag = scroll.
 */
class PointerPad extends View {

    interface Listener {
        void onMove(int xPermille, int yPermille);
        void onButton(int button, boolean down);
        void onScroll(int dv, int dh);
    }

    private static final int BTN_LEFT = 1, BTN_RIGHT = 2;
    private static final long LONG_PRESS_MS = 400, DOUBLE_TAP_MS = 280;
    /** Finger travel per scroll notch. Roughly a trackpad's feel at ~400dpi. */
    private static final float SCROLL_PX_PER_NOTCH = 42f;
    private static final float TRACKPAD_ACCEL = 1.9f;

    static final int MODE_DIRECT = 0, MODE_TRACKPAD = 1;

    private final Handler handler = new Handler(Looper.getMainLooper());

    /** Bounds of the actual video image, which is letterboxed inside this view. */
    private final Rect video = new Rect();

    private Listener listener;
    private int mode = MODE_DIRECT;

    private float downX, downY, lastX, lastY;
    private boolean moved, longPressFired, dragging, scrolling;
    private long lastTapAt;
    private float scrollAccumY, scrollAccumX;
    private int touchSlop;

    // Virtual cursor for trackpad mode, in permille. Starts centred; in direct
    // mode it is overwritten on every touch, so the two modes stay coherent.
    private int cursorX = 500, cursorY = 500;

    private final Runnable longPress = new Runnable() {
        @Override
        public void run() {
            if (moved || scrolling) return;
            longPressFired = true;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            send(BTN_RIGHT, true);
            send(BTN_RIGHT, false);
        }
    };

    PointerPad(Context context) {
        super(context);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    void setListener(Listener l) {
        this.listener = l;
    }

    /** Where the video image actually sits, so touches map to host coordinates. */
    void setVideoBounds(int l, int t, int r, int b) {
        video.set(l, t, r, b);
    }

    int getMode() {
        return mode;
    }

    /** Flip between DIRECT and TRACKPAD; returns the new mode. */
    int toggleMode() {
        mode = (mode == MODE_DIRECT) ? MODE_TRACKPAD : MODE_DIRECT;
        return mode;
    }

    // ------------------------------------------------------------------ //
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (video.isEmpty()) video.set(0, 0, w, h);
    }

    // ------------------------------------------------------------------ //
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                downX = lastX = event.getX();
                downY = lastY = event.getY();

                moved = longPressFired = scrolling = false;
                scrollAccumX = scrollAccumY = 0f;

                // A second tap arriving inside the double-tap window starts a
                // drag, which is the standard touch idiom for click-and-hold.
                long now = System.currentTimeMillis();
                dragging = (now - lastTapAt) < DOUBLE_TAP_MS;
                lastTapAt = now;

                if (mode == MODE_DIRECT) moveTo(downX, downY);
                if (dragging) send(BTN_LEFT, true);
                else handler.postDelayed(longPress, LONG_PRESS_MS);
                return true;
            }

            case MotionEvent.ACTION_POINTER_DOWN: {
                // Second finger: this is a scroll, not a click or a drag.
                scrolling = true;
                handler.removeCallbacks(longPress);
                if (dragging) {
                    send(BTN_LEFT, false);
                    dragging = false;
                }
                // A scroll must not count as the first half of a double-tap, or
                // tapping just after one starts a drag instead of clicking.
                lastTapAt = 0;
                lastY = centroidY(event);
                lastX = centroidX(event);
                scrollAccumX = scrollAccumY = 0f;
                return true;
            }

            case MotionEvent.ACTION_MOVE: {
                if (scrolling) {
                    // Stay in scroll handling until EVERY finger has lifted.
                    //
                    // Fingers never leave together: one goes first, and for the
                    // frames before the other follows, pointerCount is 1. Falling
                    // through to the single-finger branch there teleports the
                    // cursor to the surviving finger in DIRECT mode, and in
                    // TRACKPAD mode applies a delta measured against the old
                    // two-finger centroid -- a large jump either way, at the end
                    // of every single scroll.
                    if (event.getPointerCount() < 2) {
                        lastX = event.getX();
                        lastY = event.getY();
                        return true;
                    }

                    float cx = centroidX(event), cy = centroidY(event);
                    scrollAccumY += cy - lastY;
                    scrollAccumX += cx - lastX;
                    lastX = cx;
                    lastY = cy;

                    int notchesV = (int) (scrollAccumY / SCROLL_PX_PER_NOTCH);
                    int notchesH = (int) (scrollAccumX / SCROLL_PX_PER_NOTCH);
                    if (notchesV != 0 || notchesH != 0) {
                        scrollAccumY -= notchesV * SCROLL_PX_PER_NOTCH;
                        scrollAccumX -= notchesH * SCROLL_PX_PER_NOTCH;
                        // Dragging fingers down moves the content down, i.e.
                        // scrolls up -- the touch convention, not the wheel one.
                        if (listener != null) listener.onScroll(notchesV, -notchesH);
                    }
                    return true;
                }

                float x = event.getX(), y = event.getY();
                if (!moved && Math.hypot(x - downX, y - downY) > touchSlop) {
                    moved = true;
                    handler.removeCallbacks(longPress);
                }

                if (mode == MODE_DIRECT) {
                    moveTo(x, y);
                } else if (moved) {
                    // Trackpad: translate finger travel into cursor travel,
                    // scaled by the video size so sensitivity is resolution
                    // independent.
                    float dx = (x - lastX) * TRACKPAD_ACCEL;
                    float dy = (y - lastY) * TRACKPAD_ACCEL;
                    cursorX = clamp(cursorX + (int) (dx * 1000f / Math.max(video.width(), 1)));
                    cursorY = clamp(cursorY + (int) (dy * 1000f / Math.max(video.height(), 1)));
                    if (listener != null) listener.onMove(cursorX, cursorY);
                }
                lastX = x;
                lastY = y;
                return true;
            }

            case MotionEvent.ACTION_POINTER_UP: {
                // Keep scrolling flagged until the last finger lifts, so the
                // remaining finger doesn't register a stray tap or drag.
                return true;
            }

            case MotionEvent.ACTION_UP: {
                handler.removeCallbacks(longPress);
                if (dragging) {
                    send(BTN_LEFT, false);
                    dragging = false;
                } else if (!moved && !longPressFired && !scrolling) {
                    send(BTN_LEFT, true);
                    send(BTN_LEFT, false);
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                }
                scrolling = false;
                return true;
            }

            case MotionEvent.ACTION_CANCEL: {
                handler.removeCallbacks(longPress);
                releaseAll();
                return true;
            }

            default:
                return super.onTouchEvent(event);
        }
    }

    // ------------------------------------------------------------------ //
    /**
     * Move the cursor by a pixel delta, for the keyboard's TrackPoint nub.
     *
     * Shares the same virtual cursor as trackpad mode, so the nub and the pad
     * never fight over where the pointer is.
     */
    void moveRelative(float dxPx, float dyPx) {
        int w = Math.max(video.width(), 1), h = Math.max(video.height(), 1);
        cursorX = clamp(cursorX + (int) (dxPx * TRACKPAD_ACCEL * 1000f / w));
        cursorY = clamp(cursorY + (int) (dyPx * TRACKPAD_ACCEL * 1000f / h));
        if (listener != null) listener.onMove(cursorX, cursorY);
    }

    /** Map a view-local touch to host coordinates, correcting for letterboxing. */
    private void moveTo(float x, float y) {
        if (video.width() <= 0 || video.height() <= 0) return;
        float fx = (x - video.left) / video.width();
        float fy = (y - video.top) / video.height();
        cursorX = clamp((int) (fx * 1000f));
        cursorY = clamp((int) (fy * 1000f));
        if (listener != null) listener.onMove(cursorX, cursorY);
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 1000 ? 1000 : v);
    }

    private static float centroidX(MotionEvent e) {
        float sum = 0;
        for (int i = 0; i < e.getPointerCount(); i++) sum += e.getX(i);
        return sum / e.getPointerCount();
    }

    private static float centroidY(MotionEvent e) {
        float sum = 0;
        for (int i = 0; i < e.getPointerCount(); i++) sum += e.getY(i);
        return sum / e.getPointerCount();
    }

    private void send(int button, boolean down) {
        if (listener != null) listener.onButton(button, down);
    }

    void releaseAll() {
        if (dragging) {
            send(BTN_LEFT, false);
            dragging = false;
        }
        scrolling = moved = longPressFired = false;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        handler.removeCallbacks(longPress);
        releaseAll();
    }
}
