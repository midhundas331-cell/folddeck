package io.folddeck.spike;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
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
 *
 * A Bluetooth mouse paired to the phone drives the same cursor -- see the Mouse
 * section below.
 */
class PointerPad extends View {

    interface Listener {
        /** Absolute position on the host screen, each axis in 0..{@link #ABS_MAX}. */
        void onMove(int x, int y);
        void onButton(int button, boolean down);
        void onScroll(int dv, int dh);
    }

    private static final int BTN_LEFT = 1, BTN_RIGHT = 2, BTN_MIDDLE = 3;
    private static final long LONG_PRESS_MS = 400, DOUBLE_TAP_MS = 280;
    /** Finger travel per scroll notch. Roughly a trackpad's feel at ~400dpi. */
    private static final float SCROLL_PX_PER_NOTCH = 42f;
    private static final float TRACKPAD_ACCEL = 1.9f;

    /** Held-button bits that together mean "hand the mouse back to the phone". */
    private static final int CHORD_BITS = (1 << BTN_LEFT) | (1 << BTN_RIGHT);

    /** What a mouse button transition means. See {@link #buttonAction}. */
    static final int ACT_NONE = 0, ACT_PRESS = 1, ACT_RELEASE = 2, ACT_CHORD = 3;

    /**
     * Wire resolution of an absolute position: the full u16 range, not permille.
     *
     * A thousand steps is 1.9px per step across a 1920px desktop. A finger never
     * noticed; a mouse does, and at a low pointer speed a small movement
     * truncates to no movement at all.
     */
    static final int ABS_MAX = 65535;

    /** Cursor arrow height, in dp. */
    private static final float CURSOR_DP = 17f;

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

    // The virtual cursor, as a fraction of the host screen on each axis.
    //
    // Every input that can move it shares it -- touch, the TrackPoint nub and
    // the mouse -- so they never disagree about where the pointer is. Starts
    // centred; in direct mode it is overwritten on every touch.
    //
    // A fraction rather than permille because a mouse delta smaller than one
    // step must accumulate rather than truncate to nothing, which is exactly
    // what a low pointer-speed setting is made of.
    private float cursorX = 0.5f, cursorY = 0.5f;

    // Mouse state: pointer speed, which of our buttons are held, and whether a
    // captured mouse has moved yet -- before that there is no cursor to draw.
    private float mouseSens = 1f;
    private int held;
    private boolean cursorVisible;
    private Runnable onHandBack;
    private final Paint cursorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path cursorPath = new Path();

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
        setWillNotDraw(false);
    }

    void setListener(Listener l) {
        this.listener = l;
    }

    /** Where the video image actually sits, so touches map to host coordinates. */
    void setVideoBounds(int l, int t, int r, int b) {
        video.set(l, t, r, b);
        if (cursorVisible) invalidate();
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
                    advance((x - lastX) * TRACKPAD_ACCEL, (y - lastY) * TRACKPAD_ACCEL);
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
        advance(dxPx * TRACKPAD_ACCEL, dyPx * TRACKPAD_ACCEL);
    }

    /** Move the cursor by a pixel delta measured over the video, and report it. */
    private void advance(float dxPx, float dyPx) {
        cursorX = step(cursorX, dxPx, Math.max(video.width(), 1));
        cursorY = step(cursorY, dyPx, Math.max(video.height(), 1));
        emitMove();
    }

    /**
     * One axis of cursor travel: a pixel delta across an extent that many pixels
     * wide, clamped to the screen.
     *
     * The clamp is what keeps the cursor inside the desktop image. Nothing here
     * is in phone coordinates, so folding or rotating the phone relays the video
     * out underneath a cursor that is still on the same part of the desktop --
     * it cannot be left outside the image, because it was never placed against
     * the phone's screen in the first place.
     *
     * Pure and static so MouseCheck can test it without a device.
     */
    static float step(float pos, float deltaPx, int extentPx) {
        return clamp(pos + deltaPx / extentPx);
    }

    private void emitMove() {
        if (cursorVisible) invalidate();
        if (listener != null) {
            listener.onMove(Math.round(cursorX * ABS_MAX), Math.round(cursorY * ABS_MAX));
        }
    }

    /** Map a view-local touch to host coordinates, correcting for letterboxing. */
    private void moveTo(float x, float y) {
        if (video.width() <= 0 || video.height() <= 0) return;
        float fx = (x - video.left) / video.width();
        float fy = (y - video.top) / video.height();
        cursorX = clamp(fx);
        cursorY = clamp(fy);
        emitMove();
    }

    private static float clamp(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
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
        releaseHeld();
        scrolling = moved = longPressFired = false;
    }

    // ------------------------------------------------------------------ //
    // Mouse
    //
    // A Bluetooth mouse paired to the phone drives the laptop directly. Android
    // pointer capture (View.requestPointerCapture, API 26) hides the phone's own
    // cursor and delivers raw relative deltas here -- which is what makes the
    // mouse an input device for the laptop rather than one for the phone.
    //
    // It moves the same virtual cursor touch uses, so the pad, the nub and the
    // mouse never fight over where the pointer is.
    // ------------------------------------------------------------------ //

    /** Multiplier on the raw mouse delta; 1.0 tracks the video one-to-one. */
    void setMouseSensitivity(float sens) {
        mouseSens = sens > 0 ? sens : 1f;
    }

    /** Called when left and right go down together, to hand the mouse back. */
    void setHandBackListener(Runnable r) {
        onHandBack = r;
    }

    @Override
    public boolean onCapturedPointerEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_HOVER_MOVE:
                cursorVisible = true;
                advance(e.getAxisValue(MotionEvent.AXIS_RELATIVE_X) * mouseSens,
                        e.getAxisValue(MotionEvent.AXIS_RELATIVE_Y) * mouseSens);
                return true;

            case MotionEvent.ACTION_BUTTON_PRESS:
                return mouseButton(e.getActionButton(), true);

            case MotionEvent.ACTION_BUTTON_RELEASE:
                return mouseButton(e.getActionButton(), false);

            case MotionEvent.ACTION_SCROLL: {
                // Signs already agree with evdev: AXIS_VSCROLL positive is away
                // from the user and REL_WHEEL positive is up, likewise HSCROLL
                // and REL_HWHEEL to the right. The touch path inverts its
                // horizontal axis instead, because dragging is not a wheel.
                int dv = Math.round(e.getAxisValue(MotionEvent.AXIS_VSCROLL));
                int dh = Math.round(e.getAxisValue(MotionEvent.AXIS_HSCROLL));
                if ((dv != 0 || dh != 0) && listener != null) listener.onScroll(dv, dh);
                return true;
            }

            default:
                return false;
        }
    }

    private boolean mouseButton(int androidButton, boolean down) {
        int btn = androidButton == MotionEvent.BUTTON_PRIMARY ? BTN_LEFT
                : androidButton == MotionEvent.BUTTON_SECONDARY ? BTN_RIGHT
                : androidButton == MotionEvent.BUTTON_TERTIARY ? BTN_MIDDLE : 0;
        if (btn == 0) return false;

        switch (buttonAction(held, btn, down)) {
            case ACT_PRESS:
                held |= 1 << btn;
                send(btn, true);
                break;
            case ACT_RELEASE:
                held &= ~(1 << btn);
                send(btn, false);
                break;
            case ACT_CHORD:
                // The first of the two buttons has already been pressed on the
                // laptop and has to be let go, or it stays down there forever.
                // So the laptop sees one stray click wherever the chord is made.
                // ponytail: holding the press back until the chord window closed
                // would remove it, at the cost of that delay on every drag.
                releaseHeld();
                if (onHandBack != null) onHandBack.run();
                break;
            default:
                break;
        }
        return true;
    }

    /**
     * What a button transition means, given which buttons are already held.
     *
     * Pure and static so MouseCheck can test it: this is the one piece of the
     * mouse path with state, and getting it wrong either swallows every right
     * click or leaves a button stuck down on the laptop.
     */
    static int buttonAction(int held, int button, boolean down) {
        int bit = 1 << button;
        if (!down) {
            // A release of something we never pressed. The re-capture chord's
            // own buttons are still down at the moment capture is granted, and
            // their releases land here.
            return (held & bit) == 0 ? ACT_NONE : ACT_RELEASE;
        }
        return ((held | bit) & CHORD_BITS) == CHORD_BITS ? ACT_CHORD : ACT_PRESS;
    }

    private void releaseHeld() {
        for (int btn = BTN_LEFT; btn <= BTN_MIDDLE; btn++) {
            if ((held & (1 << btn)) != 0) send(btn, false);
        }
        held = 0;
    }

    @Override
    public void onPointerCaptureChange(boolean hasCapture) {
        super.onPointerCaptureChange(hasCapture);
        if (!hasCapture) {
            releaseHeld();
            cursorVisible = false;
            invalidate();
        }
    }

    /**
     * The cursor, in the desktop's own theme.
     *
     * Drawn here because pointer capture hides the system cursor -- that is what
     * capture is -- so without this there is nothing on screen to aim with. The
     * accent fill sits on a background-coloured outline so it stays visible over
     * a white window and a dark one alike, and both colours come from the
     * palette the laptop streams down, so it re-themes with everything else.
     */
    @Override
    protected void onDraw(Canvas canvas) {
        if (!cursorVisible || video.isEmpty()) return;

        float h = Ui.dp(getContext(), CURSOR_DP), w = h * 0.62f;
        float x = video.left + cursorX * video.width();
        float y = video.top + cursorY * video.height();

        cursorPath.reset();
        cursorPath.moveTo(x, y);                                // tip: the hotspot
        cursorPath.lineTo(x, y + h);
        cursorPath.lineTo(x + w * 0.35f, y + h * 0.78f);
        cursorPath.lineTo(x + w * 0.57f, y + h * 1.19f);
        cursorPath.lineTo(x + w * 0.78f, y + h * 1.13f);
        cursorPath.lineTo(x + w * 0.57f, y + h * 0.72f);
        cursorPath.lineTo(x + w, y + h * 0.72f);
        cursorPath.close();

        cursorPaint.setStyle(Paint.Style.STROKE);
        cursorPaint.setStrokeWidth(Ui.dp(getContext(), 2));
        cursorPaint.setColor(Ui.BG);
        canvas.drawPath(cursorPath, cursorPaint);

        cursorPaint.setStyle(Paint.Style.FILL);
        cursorPaint.setColor(Ui.ACCENT);
        canvas.drawPath(cursorPath, cursorPaint);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        handler.removeCallbacks(longPress);
        releaseAll();
    }
}
