package io.folddeck.spike;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Bounce bounce = new Bounce();
    private final float density;
    /** Width of the channel down the middle when split; 0 = one block. */
    private int splitGap;
    private final Paint edge = new Paint();
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler handler = new Handler(Looper.getMainLooper());

    private KeyListener listener;
    private TrackPointListener trackPoint;

    private float gap, rowHeight;
    private boolean fnActive;
    private boolean haptics = true;
    private float labelScale = 1f, gapScale = 1f;
    private boolean showNub = true, showFnRow = true;

    // TrackPoint nub, positioned between G, H and B once the layout is known.
    private float nubX, nubY, nubRadius;

    KeyboardView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Ui.MONO);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(Math.max(1, Ui.dp(context, 1)));
    }

    void setKeyListener(KeyListener l) {
        this.listener = l;
    }

    void setTrackPointListener(TrackPointListener l) {
        this.trackPoint = l;
    }

    void setHaptics(boolean enabled) {
        haptics = enabled;
    }

    /** Legend size, gap between caps, the TrackPoint and the Esc/F-key row. */
    void setLook(float labels, float gaps, boolean nub, boolean fnRow) {
        if (labels == labelScale && gaps == gapScale && nub == showNub && fnRow == showFnRow) {
            return;
        }
        releaseAll();   // a held key may be about to disappear
        labelScale = labels;
        gapScale = gaps;
        showNub = nub;
        showFnRow = fnRow;
        onSizeChanged(getWidth(), getHeight(), getWidth(), getHeight());
        invalidate();
    }

    /** Split the board down the middle around a channel this wide (0 = whole). */
    void setSplitGap(int px) {
        if (px == splitGap) return;
        releaseAll();   // keys are about to move under any held finger
        splitGap = px;
        onSizeChanged(getWidth(), getHeight(), getWidth(), getHeight());
        invalidate();
    }

    /** Space between caps for a board this wide; the split trackpad matches it. */
    float keyGap(int width) {
        return Math.max(2f, width * 0.0022f * gapScale) + Ui.CAP_DEPTH_DP * density * 0.5f;
    }

    // ------------------------------------------------------------------ //
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        caps.clear();
        if (w <= 0 || h <= 0) return;

        gap = keyGap(w);
        Ev.Key[][] layout = showFnRow ? Ev.LAYOUT
                : java.util.Arrays.copyOfRange(Ev.LAYOUT, 1, Ev.LAYOUT.length);
        int rows = layout.length;
        rowHeight = (h - gap * (rows + 1)) / rows;
        // Every row sums to ROW_UNITS, so one unit width serves all rows and the
        // columns line up the way they do on a real board.
        float unit = (w - gap * (Ev.ROW_UNITS + 1)) / Ev.ROW_UNITS;

        if (splitGap > 0) {
            layoutSplit(layout, w);
            text.setTextSize(rowHeight * 0.30f * labelScale);
            nubRadius = 0f;   // G and H are apart; the trackpad between them takes its job
            return;
        }

        float y = gap;
        for (Ev.Key[] row : layout) {
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

        text.setTextSize(rowHeight * 0.30f * labelScale);
        placeNub();

    }

    /**
     * Two halves either side of the channel. Rows break where a touch typist's
     * hands do -- 6|7, T|Y, G|H, B|N, F6|F7 -- and the space bar is cut in two.
     * Each half keeps one key unit so its columns line up; a row that is short
     * of the widest one gives the difference to its widest key (Shift, Enter,
     * Backspace, the space halves), ties going to the outer edge.
     */
    private void layoutSplit(Ev.Key[][] layout, int w) {
        List<List<float[]>> sides = new ArrayList<>();   // per side: per row widths
        List<List<List<Ev.Key>>> keys = new ArrayList<>();
        float[] maxUnits = halfUnits(layout, sides, keys);
        // One key unit for both halves, set by the fuller one, so keys are the
        // same size either side. Each half hugs its outer edge; the trackpad
        // takes everything between (see splitEdges), so it grows by whatever
        // the emptier half no longer needs.
        float step = splitStep(w, gap, maxUnits);
        float unit = step - gap;
        for (int side = 0; side < 2; side++) {
            float origin = side == 0 ? 0 : w - (gap + maxUnits[1] * step);
            float y = gap;
            for (int r = 0; r < layout.length; r++) {
                float[] widths = sides.get(side).get(r);
                float sum = 0;
                for (float v : widths) sum += v;
                if (widths.length > 0) {
                    int widest = 0;
                    for (int i = 0; i < widths.length; i++) {
                        boolean wider = widths[i] > widths[widest];
                        boolean tieOuter = widths[i] == widths[widest] && side == 1;
                        if (wider || tieOuter) widest = i;
                    }
                    widths[widest] += maxUnits[side] - sum;
                }
                float x = origin + gap;
                List<Ev.Key> half = keys.get(side).get(r);
                for (int i = 0; i < widths.length; i++) {
                    float kw = widths[i] * unit + gap * (widths[i] - 1);
                    Cap cap = new Cap();
                    cap.key = half.get(i);
                    cap.rect.set(x, y, x + kw, y + rowHeight);
                    caps.add(cap);
                    x += kw + gap;
                }
                y += rowHeight + gap;
            }
        }
    }

    /** Key units per split half, [left, right]; fills per-row widths and keys if given. */
    private static float[] halfUnits(Ev.Key[][] layout, List<List<float[]>> sides,
                                     List<List<List<Ev.Key>>> keys) {
        for (int side = 0; sides != null && side < 2; side++) {
            sides.add(new ArrayList<>());
            keys.add(new ArrayList<>());
        }
        float[] maxUnits = new float[2];
        for (Ev.Key[] row : layout) {
            List<List<Ev.Key>> halves = Ev.splitRow(row);
            for (int side = 0; side < 2; side++) {
                List<Ev.Key> half = halves.get(side);
                float[] widths = new float[half.size()];
                float sum = 0;
                for (int i = 0; i < widths.length; i++) {
                    widths[i] = half.get(i).code == Ev.SPACE ? Ev.SPACE_HALF : half.get(i).width;
                    sum += widths[i];
                }
                maxUnits[side] = Math.max(maxUnits[side], sum);
                if (sides != null) {
                    sides.get(side).add(widths);
                    keys.get(side).add(half);
                }
            }
        }
        return maxUnits;
    }

    /** One unit+gap step for both halves: the fuller half fills its side exactly. */
    private float splitStep(int w, float g, float[] maxUnits) {
        float sideW = (w - splitGap) / 2f;
        return (sideW - g) / Math.max(1f, Math.max(maxUnits[0], maxUnits[1]));
    }

    /**
     * Where the split halves end, in this view's x, for a board w wide: the
     * trackpad fills [left, right]. Wider than the nominal channel by the room
     * the emptier half gives up.
     */
    float[] splitEdges(int w) {
        Ev.Key[][] layout = showFnRow ? Ev.LAYOUT
                : java.util.Arrays.copyOfRange(Ev.LAYOUT, 1, Ev.LAYOUT.length);
        float[] m = halfUnits(layout, null, null);
        float g = keyGap(w);
        float step = splitStep(w, g, m);
        return new float[]{g + m[0] * step, w - (g + m[1] * step)};
    }

    private void placeNub() {
        Cap g = findCap(Ev.G), h = findCap(Ev.H), b = findCap(Ev.B);
        if (!showNub || g == null || h == null || b == null) {
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
        long now = SystemClock.uptimeMillis();
        float lift = Ui.CAP_DEPTH_DP * density / 2f;
        for (Cap cap : caps) {
            float s = bounce.scale(cap.key.code, now);
            boolean scaled = s != 1f;
            if (scaled) {
                canvas.save();
                canvas.scale(s, s, cap.rect.centerX(), cap.rect.centerY());
            }
            boolean down = held.contains(cap.key.code);
            Ui.drawCap(canvas, cap.rect.left, cap.rect.top, cap.rect.right, cap.rect.bottom,
                    colourFor(cap.key), edgeFor(cap.key), down, density, fill);
            // Legends ride the cap: up with the raised body, down when it sinks.
            canvas.save();
            canvas.translate(0, down ? lift : -lift);
            drawLegends(canvas, cap);
            canvas.restore();
            if (scaled) canvas.restore();
        }
        drawNub(canvas);
        if (bounce.animating(now)) postInvalidateOnAnimation();
    }

    private void drawLegends(Canvas canvas, Cap cap) {
        Ev.Key key = cap.key;
        if (key.label.isEmpty() && key.shift == null) return;

        // Pressed caps fill with the accent, so their legends switch to the ground.
        boolean pressed = held.contains(key.code);
        int ink = pressed ? Ui.BG : Ui.TEXT;
        float cx = cap.rect.centerX(), cy = cap.rect.centerY();

        // Long labels ("PrtSc", "Shift") need to shrink to fit their cap.
        float size = rowHeight * labelScale * (key.label.length() > 3 ? 0.22f : 0.30f);
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
            text.setTextSize(rowHeight * 0.22f * labelScale);
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
        boolean pressed = false;
        for (int i = 0; i < byPointer.size(); i++) {
            Press p = byPointer.valueAt(i);
            if (p != null && p.isNub) { pressed = true; break; }
        }
        float r = nubRadius * (pressed ? 0.88f : 1f);
        // The TrackPoint is the one deliberately round object in the product:
        // a tactile red interruption in an otherwise machined square chassis.
        fill.setColor(Ui.mix(Ui.BG, Ui.ERR, 0.34f));
        canvas.drawCircle(nubX, nubY + Ui.dp(getContext(), 1), r * 1.26f, fill);
        fill.setColor(Ui.ERR);
        canvas.drawCircle(nubX, nubY, r, fill);
        edge.setColor(Ui.mix(Ui.ERR, Ui.TEXT, 0.30f));
        edge.setStrokeWidth(Math.max(1, Ui.dp(getContext(), 1)));
        canvas.drawCircle(nubX, nubY, r * 0.67f, edge);
        canvas.drawCircle(nubX, nubY, r * 0.36f, edge);
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
        return held.contains(key.code) || isArmedOrLocked(key.code)
                ? Ui.ACCENT : Ui.LINE;
    }

    // ------------------------------------------------------------------ //
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    requestUnbufferedDispatch(event);   // no wait for the next frame
                }
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
            invalidate();
            return;
        }

        Cap cap = capAt(x, y);
        if (cap == null) return;

        Press p = new Press();
        p.cap = cap;
        byPointer.put(pointerId, p);
        bounce.press(cap.key.code, SystemClock.uptimeMillis());
        if (haptics) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);

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
        if (p.isNub) {
            invalidate();
            return;
        }

        int code = p.cap.key.code;
        bounce.release(code, SystemClock.uptimeMillis());

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
