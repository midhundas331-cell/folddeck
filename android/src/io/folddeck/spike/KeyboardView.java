package io.folddeck.spike;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A ThinkPad-style keyboard that emits evdev scancodes.
 *
 * The ThinkPad cues are the layout (Fn left of Ctrl, Fn-layer legends on the
 * F-row, inverted-T arrows with a navigation column) and the red TrackPoint nub
 * between G, H and B, which here is a working pointing stick. The caps
 * themselves are Omarchy's controls: square, 1px border, flat fill, and every
 * colour read from {@link Ui} at draw time so a theme change is one invalidate().
 *
 * Behaviours that separate this from a toy:
 *  - key-down and key-up sent separately, so chords and held keys work
 *  - pointers tracked independently, because real typists press the next key
 *    before releasing the last one
 *  - modifiers latch: tap = one-shot, tap again = locked, third tap = off
 *  - 400ms/33ms auto-repeat, matching X11 defaults
 *  - sliding off a key cancels it
 */
class KeyboardView extends View {

    interface KeyListener {
        void onKey(int code, boolean down);
    }

    /** Relative movement from the TrackPoint nub, in pixels. */
    interface TrackPointListener {
        void onTrackPoint(float dxPx, float dyPx);
    }

    private static final int MOD_OFF = 0, MOD_ONE_SHOT = 1, MOD_LOCKED = 2;
    private static final long REPEAT_DELAY_MS = 400, REPEAT_INTERVAL_MS = 33;

    private static final class Cap {
        Ev.Key key;
        final RectF rect = new RectF();
    }

    /** One finger's press: which key, and which code we actually put on the wire. */
    private static final class Press {
        Cap cap;
        int sentCode;
        boolean isNub;
        float lastX, lastY;
    }

    private final List<Cap> caps = new ArrayList<>();
    private final SparseArray<Press> byPointer = new SparseArray<>();
    private final Set<Integer> held = new HashSet<>();
    private final Map<Integer, Integer> modState = new HashMap<>();
    private final Map<Integer, Runnable> repeats = new HashMap<>();

    private final Paint fill = new Paint();
    private final Paint edge = new Paint();
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler handler = new Handler(Looper.getMainLooper());

    private KeyListener listener;
    private TrackPointListener trackPoint;

    private float gap, rowHeight;
    private boolean fnActive;

    // TrackPoint nub, positioned between G, H and B once the layout is known.
    private float nubX, nubY, nubRadius;

    KeyboardView(Context context) {
        super(context);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Ui.FONT);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(Math.max(1, Ui.dp(context, 1)));
    }

    void setKeyListener(KeyListener l) {
        this.listener = l;
    }

    void setTrackPointListener(TrackPointListener l) {
        this.trackPoint = l;
    }

    // ------------------------------------------------------------------ //
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        caps.clear();
        if (w <= 0 || h <= 0) return;

        gap = Math.max(2f, w * 0.0022f);
        int rows = Ev.LAYOUT.length;
        rowHeight = (h - gap * (rows + 1)) / rows;
        // Every row sums to ROW_UNITS, so one unit width serves all rows and the
        // columns line up the way they do on a real board.
        float unit = (w - gap * (Ev.ROW_UNITS + 1)) / Ev.ROW_UNITS;

        float y = gap;
        for (Ev.Key[] row : Ev.LAYOUT) {
            float x = gap;
            for (Ev.Key key : row) {
                float kw = key.width * unit + gap * (key.width - 1);
                Cap cap = new Cap();
                cap.key = key;
                cap.rect.set(x, y, x + kw, y + rowHeight);
                caps.add(cap);
                x += kw + gap;
            }
            y += rowHeight + gap;
        }

        text.setTextSize(rowHeight * 0.30f);
        placeNub();
    }

    private void placeNub() {
        Cap g = findCap(Ev.G), h = findCap(Ev.H), b = findCap(Ev.B);
        if (g == null || h == null || b == null) {
            nubRadius = 0f;
            return;
        }
        nubX = (g.rect.right + h.rect.left) / 2f;
        nubY = (g.rect.bottom + b.rect.top) / 2f;
        // Small enough to sit mostly in the gap between rows, as the real one
        // does, so it doesn't steal touches from G, H, B or N.
        nubRadius = Math.min(rowHeight * 0.22f, gap * 6f);
    }

    private Cap findCap(int code) {
        for (Cap c : caps) {
            if (c.key.code == code) return c;
        }
        return null;
    }

    // ------------------------------------------------------------------ //
    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(Ui.BG);
        float inset = edge.getStrokeWidth() / 2f;
        for (Cap cap : caps) {
            fill.setColor(colourFor(cap.key));
            canvas.drawRect(cap.rect, fill);
            edge.setColor(edgeFor(cap.key));
            canvas.drawRect(cap.rect.left + inset, cap.rect.top + inset,
                    cap.rect.right - inset, cap.rect.bottom - inset, edge);
            drawLegends(canvas, cap);
        }
        drawNub(canvas);
    }

    private void drawLegends(Canvas canvas, Cap cap) {
        Ev.Key key = cap.key;
        if (key.label.isEmpty() && key.shift == null) return;

        // Pressed caps fill with the accent, so their legends switch to the ground.
        boolean pressed = held.contains(key.code);
        int ink = pressed ? Ui.BG : Ui.TEXT;
        float cx = cap.rect.centerX(), cy = cap.rect.centerY();

        // Long labels ("PrtSc", "Shift") need to shrink to fit their cap.
        float size = rowHeight * (key.label.length() > 3 ? 0.22f : 0.30f);
        text.setTextSize(size);

        if (key.shift != null) {
            // Shifted legend above the base one, as printed on a real keycap.
            text.setColor(pressed ? Ui.BG : Ui.TEXT_DIM);
            canvas.drawText(key.shift, cx, cy - rowHeight * 0.06f, text);
            text.setColor(ink);
            canvas.drawText(key.label, cx, cy + rowHeight * 0.30f, text);
            return;
        }

        if (key.fnLabel != null) {
            text.setColor(ink);
            canvas.drawText(key.label, cx, cy - rowHeight * 0.04f, text);
            // Fn-layer legend in the accent, lit when Fn is engaged.
            text.setTextSize(rowHeight * 0.22f);
            text.setColor(pressed ? Ui.BG : fnActive ? Ui.ACCENT : Ui.TEXT_FAINT);
            canvas.drawText(key.fnLabel, cx, cy + rowHeight * 0.30f, text);
            return;
        }

        text.setColor(isArmedOrLocked(key.code) && !pressed ? Ui.ACCENT : ink);
        float baseline = cy - (text.descent() + text.ascent()) / 2f;
        canvas.drawText(key.label, cx, baseline, text);
    }

    private void drawNub(Canvas canvas) {
        if (nubRadius <= 0f) return;
        // Still TrackPoint red, but in the theme's red and on sharp corners.
        fill.setColor(Ui.ERR);
        canvas.drawRect(nubX - nubRadius, nubY - nubRadius, nubX + nubRadius, nubY + nubRadius, fill);
    }

    /** One-shot, locked, or Fn engaged: the states that outlast the finger. */
    private boolean isArmedOrLocked(int code) {
        if (code == Ev.LOCAL_FN) return fnActive;
        return state(code) != MOD_OFF;
    }

    /**
     * Omarchy's state fills: normal 4%, selected 18% for an armed modifier, and
     * the accent itself for a key under a finger. Locked sits between the two so
     * it reads as "on" without being mistaken for a press.
     */
    private int colourFor(Ev.Key key) {
        if (held.contains(key.code)) return Ui.ACCENT;
        if (state(key.code) == MOD_LOCKED) return Ui.mix(Ui.BG, Ui.ACCENT, 0.35f);
        if (isArmedOrLocked(key.code)) return Ui.SELECTED;
        boolean chunky = Ev.isModifier(key.code) || key.code == Ev.LOCAL_FN
                || key.code == Ev.CAPSLOCK || key.code == Ev.TAB
                || key.code == Ev.BACKSPACE || key.code == Ev.ENTER;
        return chunky ? Ui.SURFACE_HI : Ui.SURFACE;
    }

    private int edgeFor(Ev.Key key) {
        return held.contains(key.code) || isArmedOrLocked(key.code) ? Ui.ACCENT : Ui.LINE;
    }

    // ------------------------------------------------------------------ //
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int idx = event.getActionIndex();
                press(event.getPointerId(idx), event.getX(idx), event.getY(idx));
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                for (int i = 0; i < event.getPointerCount(); i++) {
                    int id = event.getPointerId(i);
                    Press p = byPointer.get(id);
                    if (p == null) continue;
                    if (p.isNub) {
                        float dx = event.getX(i) - p.lastX, dy = event.getY(i) - p.lastY;
                        p.lastX = event.getX(i);
                        p.lastY = event.getY(i);
                        if (trackPoint != null) trackPoint.onTrackPoint(dx, dy);
                    } else if (!p.cap.rect.contains(event.getX(i), event.getY(i))) {
                        // Sliding off a key cancels it rather than typing it.
                        release(id, false);
                    }
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                release(event.getPointerId(event.getActionIndex()), true);
                break;
            }
            case MotionEvent.ACTION_CANCEL: {
                for (int i = byPointer.size() - 1; i >= 0; i--) {
                    release(byPointer.keyAt(i), false);
                }
                break;
            }
            default:
                return super.onTouchEvent(event);
        }
        return true;
    }

    private void press(int pointerId, float x, float y) {
        if (nubRadius > 0f && Math.hypot(x - nubX, y - nubY) <= nubRadius * 1.3f) {
            Press p = new Press();
            p.isNub = true;
            p.lastX = x;
            p.lastY = y;
            byPointer.put(pointerId, p);
            return;
        }

        Cap cap = capAt(x, y);
        if (cap == null) return;

        Press p = new Press();
        p.cap = cap;
        byPointer.put(pointerId, p);
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);

        int code = cap.key.code;

        // Fn is resolved entirely here. On a real laptop it never reaches the OS
        // either -- the keyboard controller swallows it.
        if (code == Ev.LOCAL_FN) {
            fnActive = !fnActive;
            held.add(code);
            invalidate();
            return;
        }

        if (Ev.isModifier(code)) {
            int next = (state(code) + 1) % 3;
            modState.put(code, next);
            // ONE_SHOT and LOCKED both hold the key physically down on the host;
            // only the rule for releasing it differs.
            p.sentCode = code;
            send(code, next != MOD_OFF);
            invalidate();
            return;
        }

        // Fn layer swaps in the alternate scancode, so the host sees a real
        // media key rather than F5.
        p.sentCode = (fnActive && cap.key.fnCode != 0) ? cap.key.fnCode : code;
        held.add(code);
        send(p.sentCode, true);
        startRepeat(p.sentCode);
        invalidate();
    }

    private void release(int pointerId, boolean committed) {
        Press p = byPointer.get(pointerId);
        if (p == null) return;
        byPointer.remove(pointerId);
        if (p.isNub) return;

        int code = p.cap.key.code;

        if (code == Ev.LOCAL_FN) {
            held.remove(code);
            invalidate();
            return;
        }
        if (Ev.isModifier(code)) {
            invalidate();
            return;  // released by the latch rules, not by lifting the finger
        }

        stopRepeat(p.sentCode);
        held.remove(code);
        send(p.sentCode, false);
        if (committed) clearOneShots();
        invalidate();
    }

    /**
     * Release one-shot modifiers, but only once a real key has completed -- so
     * Shift+A works while Shift on its own stays armed for the next press.
     */
    private void clearOneShots() {
        for (Map.Entry<Integer, Integer> e : modState.entrySet()) {
            if (e.getValue() == MOD_ONE_SHOT) {
                send(e.getKey(), false);
                e.setValue(MOD_OFF);
            }
        }
    }

    private int state(int code) {
        Integer s = modState.get(code);
        return s == null ? MOD_OFF : s;
    }

    private Cap capAt(float x, float y) {
        for (Cap cap : caps) {
            if (cap.rect.contains(x, y)) return cap;
        }
        return null;
    }

    private void send(int code, boolean down) {
        if (listener != null && code != Ev.LOCAL_FN) listener.onKey(code, down);
    }

    // ------------------------------------------------------------------ //
    private void startRepeat(final int code) {
        stopRepeat(code);
        Runnable r = new Runnable() {
            @Override
            public void run() {
                // A repeat is a fresh down; the host's own key-repeat would never
                // fire because it only ever sees one physical press.
                send(code, false);
                send(code, true);
                handler.postDelayed(this, REPEAT_INTERVAL_MS);
            }
        };
        repeats.put(code, r);
        handler.postDelayed(r, REPEAT_DELAY_MS);
    }

    private void stopRepeat(int code) {
        Runnable r = repeats.remove(code);
        if (r != null) handler.removeCallbacks(r);
    }

    /** Release everything. Called when the view goes away or the socket drops. */
    void releaseAll() {
        for (int i = byPointer.size() - 1; i >= 0; i--) {
            Press p = byPointer.valueAt(i);
            if (!p.isNub && p.sentCode != 0) {
                stopRepeat(p.sentCode);
                send(p.sentCode, false);
            }
        }
        byPointer.clear();
        held.clear();
        for (Map.Entry<Integer, Integer> e : modState.entrySet()) {
            if (e.getValue() != MOD_OFF) send(e.getKey(), false);
            e.setValue(MOD_OFF);
        }
        fnActive = false;
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        releaseAll();
    }
}
