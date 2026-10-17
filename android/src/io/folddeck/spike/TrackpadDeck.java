package io.folddeck.spike;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.SparseArray;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/** Dedicated ThinkPad-style touch surface with physical held buttons and scroll rail. */
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
        float x, y;
    }

    private final SparseArray<Contact> contacts = new SparseArray<>();
    private final TrackpadState state = new TrackpadState();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF pad = new RectF(), scroll = new RectF(), left = new RectF(), right = new RectF();
    private final float density;

    private Listener listener;
    private boolean showScroll = true, leftHanded, naturalScroll = true, haptics = true;
    private float sensitivity = 1f;
    private int pressedMask;
    private int motionPointer = -1;

    TrackpadDeck(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Math.max(1f, density));
        text.setTypeface(Ui.MONO_BOLD);
        text.setTextAlign(Paint.Align.CENTER);
        setBackground(Ui.floatingPanel(Ui.SURFACE, Ui.ACCENT, 1, context));
        Ui.elevate(this, context);
        setContentDescription("Dedicated trackpad with held left and right buttons");
    }

    void setListener(Listener value) {
        listener = value;
    }

    void configure(boolean scrollVisible, boolean buttonsLeftHanded,
                   boolean natural, float speed, boolean tactile) {
        if (showScroll != scrollVisible || leftHanded != buttonsLeftHanded) {
            cancelInput();
        }
        showScroll = scrollVisible;
        leftHanded = buttonsLeftHanded;
        naturalScroll = natural;
        sensitivity = Prefs.clampTrackpadSens(speed);
        haptics = tactile;
        setBackground(Ui.floatingPanel(Ui.SURFACE, Ui.ACCENT, 1, getContext()));
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
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        // Zones are inset from the panel so its accent outline stays visible,
        // the same frame the keyboard and shortcut deck wear.
        float inset = Ui.dp(getContext(), 6);
        layoutZones(w - 2 * inset, h - 2 * inset);
        for (RectF r : new RectF[]{pad, scroll, left, right}) r.offset(inset, inset);
    }

    private void layoutZones(float w, float h) {
        float gap = Ui.dp(getContext(), 7);
        float buttonH = Math.max(Ui.dp(getContext(), 48), h * 0.24f);
        float railW = showScroll ? Math.max(Ui.dp(getContext(), 42), w * 0.14f) : 0f;
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
        drawZone(canvas, pad, false, "TRACKPAD", Ui.SURFACE_HI);
        if (showScroll) drawZone(canvas, scroll, false, "SCROLL", Ui.mix(Ui.BG, Ui.ACCENT, 0.12f));
        int leftButton = leftHanded ? 2 : 1;
        int rightButton = leftHanded ? 1 : 2;
        drawZone(canvas, left, held(leftButton), leftButton == 1 ? "LEFT" : "RIGHT", Ui.SURFACE_HI);
        drawZone(canvas, right, held(rightButton), rightButton == 1 ? "LEFT" : "RIGHT", Ui.SURFACE_HI);

        // Quiet directional ticks make the scroll rail legible without looking like a scrollbar.
        if (showScroll) {
            stroke.setColor(Ui.ACCENT);
            stroke.setStrokeWidth(Math.max(1f, density));
            float cx = scroll.centerX();
            for (int i = -2; i <= 2; i++) {
                float y = scroll.centerY() + i * Ui.dp(getContext(), 12);
                canvas.drawLine(cx - Ui.dp(getContext(), 5), y,
                        cx + Ui.dp(getContext(), 5), y, stroke);
            }
        }
    }

    private void drawZone(Canvas canvas, RectF box, boolean down, String label, int normal) {
        if (box.isEmpty()) return;
        fill.setColor(down ? Ui.ACCENT : normal);
        canvas.drawRoundRect(box, Ui.dp(getContext(), 6), Ui.dp(getContext(), 6), fill);
        stroke.setColor(down ? Ui.mix(Ui.ACCENT, Ui.TEXT, 0.35f) : Ui.LINE);
        canvas.drawRoundRect(box, Ui.dp(getContext(), 6), Ui.dp(getContext(), 6), stroke);
        if ("TRACKPAD".equals(label)) {
            text.setTextSize(Ui.dp(getContext(), 10));
            text.setColor(Ui.TEXT_FAINT);
            canvas.drawText(label, box.centerX(), box.top + Ui.dp(getContext(), 18), text);
        } else if (!"SCROLL".equals(label)) {
            text.setTextSize(Ui.dp(getContext(), 11));
            text.setColor(down ? Ui.BG : Ui.TEXT_DIM);
            canvas.drawText(label, box.centerX(), box.centerY() - (text.ascent() + text.descent()) / 2f, text);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
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
        contact.x = event.getX(index);
        contact.y = event.getY(index);
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
            if (haptics) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            invalidate();
        } else if (contact.zone == ZONE_SCROLL) {
            state.resetScroll();
        } else if (motionPointer == -1) {
            motionPointer = pointerId;
        }
    }

    private void move(MotionEvent event) {
        for (int i = 0; i < event.getPointerCount(); i++) {
            Contact contact = contacts.get(event.getPointerId(i));
            if (contact == null) continue;
            float x = event.getX(i), y = event.getY(i);
            if (contact.zone == ZONE_PAD && event.getPointerId(i) == motionPointer
                    && listener != null) {
                listener.onMove(state.sensitivity(x - contact.x, sensitivity),
                        state.sensitivity(y - contact.y, sensitivity));
            } else if (contact.zone == ZONE_SCROLL && listener != null) {
                int notches = state.scroll(y - contact.y, 42f * density, naturalScroll);
                if (notches != 0) listener.onScroll(notches);
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
            if (state.release(button) == TrackpadState.UP) {
                pressedMask &= ~(1 << button);
                if (listener != null) listener.onButton(button, false);
                invalidate();
            }
        } else if (contact.zone == ZONE_SCROLL) {
            state.resetScroll();
        } else if (pointerId == motionPointer) {
            motionPointer = nextPadPointer();
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
