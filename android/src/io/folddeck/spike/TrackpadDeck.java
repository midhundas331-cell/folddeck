package io.folddeck.spike;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.SparseArray;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;

/**
 * Dedicated ThinkPad-style touch surface: relative pad, held buttons, and a
 * scroll wheel that behaves like a notched one -- it turns under the finger,
 * ticks at every detent, coasts after a flick and clicks into place.
 */
final class TrackpadDeck extends View {
    interface Listener {
        void onMove(float dx, float dy);
        void onButton(int button, boolean down);
        void onScroll(int vertical);
    }

    private static final int ZONE_PAD = 0, ZONE_LEFT = 1, ZONE_RIGHT = 2,
            ZONE_SCROLL = 3;

    private static final class Contact {
        int zone;
        int button;
        float x, y, startX, startY;
    }

    private final SparseArray<Contact> contacts = new SparseArray<>();
    private final TrackpadState state = new TrackpadState();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF pad = new RectF(), scroll = new RectF(), left = new RectF(), right = new RectF();
    private final RectF wheel = new RectF();
    private final float density;
    private final Bounce bounce = new Bounce();

    // ---- scroll wheel ----
    /** Finger travel per detent. A real wheel clicks every ~4-5 mm. */
    static final float WHEEL_NOTCH_DP = 32f;
    /** Coasting after a flick: velocity e-folds this fast, and stops below WHEEL_STOP_DP/s. */
    private static final float WHEEL_FRICTION_S = 0.22f, WHEEL_FLICK_DP = 650f, WHEEL_STOP_DP = 90f;
    private final Paint ridge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint drum = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float wheelPx;           // how far the wheel surface has turned, in finger px
    private int wheelPointer = -1;
    private float flingV;            // px/s while coasting
    private long flingAt;
    private VelocityTracker velocity;
    private ValueAnimator snap;
    private float inset;

    private Listener listener;
    private boolean showScroll = true, leftHanded, naturalScroll = true, haptics = true;
    private boolean tapToClick = true;
    /** Just the wheel, filling the view: the slim one beside the cover desktop. */
    private boolean wheelOnly;
    private final int touchSlop;
    private float sensitivity = 1f;
    private int pressedMask;
    private int motionPointer = -1;

    TrackpadDeck(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        touchSlop = android.view.ViewConfiguration.get(context).getScaledTouchSlop();
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Math.max(1f, density));
        text.setTypeface(Ui.MONO_BOLD);
        text.setTextAlign(Paint.Align.CENTER);
        ridge.setStrokeCap(Paint.Cap.ROUND);
        inset = Ui.dp(context, 6);
        setContentDescription("Dedicated trackpad with held left and right buttons");
    }

    void setWheelOnly() {
        wheelOnly = true;
        setContentDescription("Scroll wheel");
    }

    void setListener(Listener value) {
        listener = value;
    }

    void configure(boolean scrollVisible, boolean buttonsLeftHanded,
                   boolean natural, float speed, boolean tactile, boolean tap) {
        tapToClick = tap;
        if (showScroll != scrollVisible || leftHanded != buttonsLeftHanded) {
            cancelInput();
        }
        showScroll = scrollVisible;
        leftHanded = buttonsLeftHanded;
        naturalScroll = natural;
        sensitivity = Prefs.clampTrackpadSens(speed);
        haptics = tactile;
        // Zones depend on these settings, not only on size.
        if (getWidth() > 0) onSizeChanged(getWidth(), getHeight(), getWidth(), getHeight());
        invalidate();
    }

    void cancelInput() {
        int mask = state.cancelMask();
        for (int button = 1; button <= 2; button++) {
            if ((mask & (1 << button)) != 0 && listener != null) {
                listener.onButton(button, false);
            }
        }
        contacts.clear();
        pressedMask = 0;
        motionPointer = -1;
        wheelPointer = -1;
        stopWheel();
        wheelPx = 0f;
        bounce.clear();
        invalidate();
    }

    /** Space between the zones and the edge; the split keyboard matches its key gap. */
    void setInset(float px) {
        if (px == inset) return;
        inset = px;
        if (getWidth() > 0) onSizeChanged(getWidth(), getHeight(), getWidth(), getHeight());
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        // Zones are inset from the panel so its accent outline stays visible,
        // the same frame the keyboard and shortcut deck wear.
        layoutZones(w - 2 * inset, h - 2 * inset);
        for (RectF r : new RectF[]{pad, scroll, left, right}) r.offset(inset, inset);
        float d = Ui.CAP_DEPTH_DP * density;
        wheel.set(scroll.left + Ui.dp(getContext(), 5), scroll.top + Ui.dp(getContext(), 7) + d,
                scroll.right - Ui.dp(getContext(), 5), scroll.bottom - Ui.dp(getContext(), 7));
        shadeDrum();
    }

    /** The drum's light: bright where it faces you, dark where it turns away. */
    private void shadeDrum() {
        if (wheel.isEmpty()) return;
        int lit = Ui.mix(Ui.SURFACE_HI, Ui.TEXT, 0.10f), dark = Ui.mix(Ui.BG, Ui.SURFACE, 0.3f);
        drum.setShader(new LinearGradient(0, wheel.top, 0, wheel.bottom,
                new int[]{dark, lit, lit, dark}, new float[]{0f, 0.38f, 0.62f, 1f},
                Shader.TileMode.CLAMP));
    }

    private void layoutZones(float w, float h) {
        if (wheelOnly) {
            scroll.set(0, 0, w, h);
            pad.setEmpty();
            left.setEmpty();
            right.setEmpty();
            return;
        }
        float gap = Math.min(Ui.dp(getContext(), 7), Math.max(inset, Ui.dp(getContext(), 3)));
        float buttonH = Math.max(Ui.dp(getContext(), 44), h * 0.22f);
        float railW = showScroll ? Math.max(Ui.dp(getContext(), 40), w * 0.16f) : 0f;
        float usableBottom = Math.max(0f, h - buttonH - gap);
        if (showScroll && leftHanded) {
            scroll.set(0, 0, railW, usableBottom);
            pad.set(railW + gap, 0, w, usableBottom);
        } else {
            pad.set(0, 0, w - railW - (showScroll ? gap : 0), usableBottom);
            scroll.set(showScroll ? w - railW : w, 0, w, usableBottom);
        }
        float half = (w - gap) / 2f;
        left.set(0, h - buttonH, half, h);
        right.set(half + gap, h - buttonH, w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(Ui.BG);
        long now = SystemClock.uptimeMillis();
        // The pad is flush, like glass set into the palm rest; it lights its rim
        // while a finger is on it.
        if (!wheelOnly) {
            Ui.drawCap(canvas, pad.left, pad.top, pad.right, pad.bottom, Ui.SURFACE,
                    motionPointer != -1 ? Ui.ACCENT : Ui.LINE, true, density, fill);
            text.setTextSize(Ui.dp(getContext(), 10));
            text.setColor(Ui.TEXT_FAINT);
            canvas.drawText("TRACKPAD", pad.centerX(), pad.top + Ui.dp(getContext(), 20), text);
        }
        if (showScroll) drawWheel(canvas);
        int leftButton = leftHanded ? 2 : 1;
        int rightButton = leftHanded ? 1 : 2;
        drawButton(canvas, left, 1, held(leftButton), leftButton == 1 ? "LEFT" : "RIGHT", now);
        drawButton(canvas, right, 2, held(rightButton), rightButton == 1 ? "LEFT" : "RIGHT", now);
        if (bounce.animating(now)) postInvalidateOnAnimation();
    }

    private void drawButton(Canvas canvas, RectF box, int visual, boolean down, String label, long now) {
        if (box.isEmpty()) return;
        float s = bounce.scale(visual, now);
        canvas.save();
        canvas.scale(s, s, box.centerX(), box.centerY());
        Ui.drawCap(canvas, box.left, box.top, box.right, box.bottom,
                down ? Ui.ACCENT : Ui.SURFACE_HI, down ? Ui.ACCENT : Ui.LINE, down, density, fill);
        text.setTextSize(Ui.dp(getContext(), 11));
        text.setColor(down ? Ui.BG : Ui.TEXT_DIM);
        float sink = down ? Ui.CAP_DEPTH_DP * density / 2 : -Ui.CAP_DEPTH_DP * density / 2;
        canvas.drawText(label, box.centerX(),
                box.centerY() + sink - (text.ascent() + text.descent()) / 2f, text);
        canvas.restore();
    }

    /**
     * The wheel seen edge-on: a drum whose ridges ride round it. Each ridge sits
     * at an angle on the drum; it is drawn where that angle projects onto the
     * screen (sin) and thins and fades as it turns away (cos). Rolling without
     * slip, a finger moving d px turns the drum d/R radians.
     */
    private void drawWheel(Canvas canvas) {
        boolean active = wheelPointer != -1 || flingV != 0f;
        Ui.drawCap(canvas, scroll.left, scroll.top, scroll.right, scroll.bottom,
                Ui.mix(Ui.BG, Ui.SURFACE, 0.5f), active ? Ui.ACCENT : Ui.LINE, true, density, fill);
        if (wheel.isEmpty()) return;

        float r = wheel.height() / 2f, cy = wheel.centerY();
        float notch = WHEEL_NOTCH_DP * density;
        float step = notch / r;
        float phase = wheelPx / r;
        float rad = Ui.CAP_RADIUS_DP * density;
        canvas.save();
        canvas.clipRect(wheel);
        canvas.drawRoundRect(wheel, rad, rad, drum);
        int ink = active ? Ui.ACCENT : Ui.mix(Ui.TEXT_DIM, Ui.TEXT, 0.25f);
        float half = (float) (Math.PI / 2);
        int kMin = (int) Math.ceil((-half - phase) / step), kMax = (int) Math.floor((half - phase) / step);
        float margin = Ui.dp(getContext(), 4);
        for (int k = kMin; k <= kMax; k++) {
            double a = phase + k * step;
            float facing = (float) Math.cos(a);
            if (facing <= 0.04f) continue;
            float y = cy + r * (float) Math.sin(a);
            ridge.setStrokeWidth(Math.max(1f, 3f * density * facing));
            ridge.setColor(ink);
            ridge.setAlpha(Math.round(40 + 215 * facing * facing));
            canvas.drawLine(wheel.left + margin, y, wheel.right - margin, y, ridge);
        }
        canvas.restore();

        // Detent pointers either side of the drum.
        fill.setColor(active ? Ui.ACCENT : Ui.TEXT_FAINT);
        float t = Ui.dp(getContext(), 2.5f);
        canvas.drawRect(scroll.left + Ui.dp(getContext(), 1.5f), cy - t / 2,
                wheel.left - Ui.dp(getContext(), 1), cy + t / 2, fill);
        canvas.drawRect(wheel.right + Ui.dp(getContext(), 1), cy - t / 2,
                scroll.right - Ui.dp(getContext(), 1.5f), cy + t / 2, fill);
    }

    // ------------------------------------------------------------------ //
    // Wheel motion
    // ------------------------------------------------------------------ //
    private float notchPx() {
        return WHEEL_NOTCH_DP * density;
    }

    /** Turn the wheel by a finger delta; every detent crossed scrolls and ticks. */
    private void turnWheel(float dy) {
        wheelPx += dy;
        int notches = state.scroll(dy, notchPx(), naturalScroll);
        if (notches != 0) {
            if (listener != null) listener.onScroll(notches);
            tick();
        }
        invalidate();
    }

    private void tick() {
        if (!haptics) return;
        // A detent is a tiny, crisp click, not a buzz: the frequent-tick
        // primitive is made for exactly this and is lighter than CLOCK_TICK.
        performHapticFeedback(android.os.Build.VERSION.SDK_INT >= 34
                ? HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
                : HapticFeedbackConstants.CLOCK_TICK);
    }

    private final Runnable coast = new Runnable() {
        @Override
        public void run() {
            long now = System.nanoTime();
            float dt = Math.min(0.05f, (now - flingAt) / 1e9f);
            flingAt = now;
            float decay = (float) Math.exp(-dt / WHEEL_FRICTION_S);
            // Distance is the integral of v(t) = v0 e^(-t/tau) over this frame.
            float dy = flingV * WHEEL_FRICTION_S * (1f - decay);
            flingV *= decay;
            turnWheel(dy);
            if (Math.abs(flingV) < WHEEL_STOP_DP * density) {
                flingV = 0f;
                settleWheel();
            } else {
                postOnAnimation(this);
            }
        }
    };

    /** Click into the nearest detent, with the same small bounce as the buttons. */
    private void settleWheel() {
        int n = state.settle(notchPx());
        if (n != 0) {
            if (listener != null) listener.onScroll(n);
            tick();
        }
        float target = Math.round(wheelPx / notchPx()) * notchPx();
        if (snap != null) snap.cancel();
        snap = ValueAnimator.ofFloat(wheelPx, target);
        snap.setDuration(220);
        snap.setInterpolator(Bounce::backOut);
        snap.addUpdateListener(a -> {
            wheelPx = (float) a.getAnimatedValue();
            invalidate();
        });
        snap.start();
    }

    private void stopWheel() {
        removeCallbacks(coast);
        flingV = 0f;
        if (snap != null) {
            snap.end();
            snap = null;
        }
        if (velocity != null) {
            velocity.recycle();
            velocity = null;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Deliver touches as the panel reports them, not batched to the next
        // frame: up to one frame less input latency, and more samples.
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) requestUnbufferedDispatch(event);
        if (velocity != null) velocity.addMovement(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                begin(event, event.getActionIndex());
                return true;
            case MotionEvent.ACTION_MOVE:
                move(event);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                end(event.getPointerId(event.getActionIndex()));
                return true;
            case MotionEvent.ACTION_CANCEL:
                cancelInput();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void begin(MotionEvent event, int index) {
        Contact contact = new Contact();
        contact.x = contact.startX = event.getX(index);
        contact.y = contact.startY = event.getY(index);
        contact.zone = zoneAt(contact.x, contact.y);
        int pointerId = event.getPointerId(index);
        contacts.put(pointerId, contact);
        if (contact.zone == ZONE_LEFT || contact.zone == ZONE_RIGHT) {
            int visual = contact.zone == ZONE_LEFT ? 1 : 2;
            int button = TrackpadState.buttonForVisual(visual, leftHanded);
            contact.button = button;
            if (state.press(button) == TrackpadState.DOWN && listener != null) {
                listener.onButton(button, true);
            }
            pressedMask |= 1 << button;
            bounce.press(contact.zone == ZONE_LEFT ? 1 : 2, SystemClock.uptimeMillis());
            if (haptics) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            invalidate();
        } else if (contact.zone == ZONE_SCROLL) {
            if (wheelPointer != -1) {
                contact.zone = ZONE_PAD;   // one finger turns the wheel at a time
                state.padDown(event.getEventTime());
                state.padMoved();
                return;
            }
            stopWheel();                   // catching a coasting wheel stops it
            state.resetScroll();
            wheelPointer = pointerId;
            velocity = VelocityTracker.obtain();
            velocity.addMovement(event);
            invalidate();
        } else {
            state.padDown(event.getEventTime());
            if (motionPointer == -1) motionPointer = pointerId;
            invalidate();
        }
    }

    private void move(MotionEvent event) {
        for (int i = 0; i < event.getPointerCount(); i++) {
            Contact contact = contacts.get(event.getPointerId(i));
            if (contact == null) continue;
            float x = event.getX(i), y = event.getY(i);
            if (contact.zone == ZONE_PAD
                    && Math.hypot(x - contact.startX, y - contact.startY) > touchSlop) {
                state.padMoved();
            }
            if (contact.zone == ZONE_PAD && event.getPointerId(i) == motionPointer
                    && listener != null) {
                listener.onMove(state.sensitivity(x - contact.x, sensitivity),
                        state.sensitivity(y - contact.y, sensitivity));
            } else if (contact.zone == ZONE_SCROLL) {
                turnWheel(y - contact.y);
            }
            contact.x = x;
            contact.y = y;
        }
    }

    private void end(int pointerId) {
        Contact contact = contacts.get(pointerId);
        if (contact == null) return;
        contacts.remove(pointerId);
        if (contact.zone == ZONE_LEFT || contact.zone == ZONE_RIGHT) {
            int button = contact.button;
            bounce.release(contact.zone == ZONE_LEFT ? 1 : 2, SystemClock.uptimeMillis());
            if (state.release(button) == TrackpadState.UP) {
                pressedMask &= ~(1 << button);
                if (listener != null) listener.onButton(button, false);
                invalidate();
            }
        } else if (contact.zone == ZONE_SCROLL) {
            wheelPointer = -1;
            float v = 0f;
            if (velocity != null) {
                velocity.computeCurrentVelocity(1000);
                v = velocity.getYVelocity(pointerId);
                velocity.recycle();
                velocity = null;
            }
            if (Math.abs(v) > WHEEL_FLICK_DP * density) {
                flingV = v;
                flingAt = System.nanoTime();
                postOnAnimation(coast);
            } else {
                settleWheel();
            }
            invalidate();
        } else {
            if (pointerId == motionPointer) motionPointer = nextPadPointer();
            invalidate();
            int click = state.padUp(android.os.SystemClock.uptimeMillis(), pressedMask != 0);
            if (click != 0 && tapToClick && listener != null) {
                listener.onButton(click, true);
                listener.onButton(click, false);
                if (haptics) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            }
        }
    }

    private int nextPadPointer() {
        for (int i = 0; i < contacts.size(); i++) {
            if (contacts.valueAt(i).zone == ZONE_PAD) return contacts.keyAt(i);
        }
        return -1;
    }

    private int zoneAt(float x, float y) {
        if (left.contains(x, y)) return ZONE_LEFT;
        if (right.contains(x, y)) return ZONE_RIGHT;
        if (showScroll && scroll.contains(x, y)) return ZONE_SCROLL;
        return ZONE_PAD;
    }

    private boolean held(int button) {
        return (pressedMask & (1 << button)) != 0;
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelInput();
        super.onDetachedFromWindow();
    }
}
