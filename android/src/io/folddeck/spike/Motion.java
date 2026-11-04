package io.folddeck.spike;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.util.Property;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.PathInterpolator;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/** Small motion grammar shared by every screen and control. */
final class Motion {
    static final float PRESS_SCALE = 0.975f;

    // The feels everything shares, as {response seconds, damping ratio}.
    // Apple's model: ratio 1 never overshoots, below 1 lands with a small bounce.
    /** Content arriving, highlights moving. */
    static final float[] SMOOTH = {0.42f, 0.88f};
    /** Whole screens pushing and popping: never overshoots, so no gap shows. */
    static final float[] NAV = {0.48f, 1f};
    /** Things that land somewhere new: swapped panes, a released handle. */
    static final float[] SNAPPY = {0.34f, 0.8f};
    /** Opacity: overshoot would mean a flash. */
    static final float[] FADE = {0.3f, 1f};
    /** Finger down: immediate. */
    static final float[] PRESS = {0.12f, 1f};
    /** Finger up: one small bounce back. */
    static final float[] RELEASE = {0.34f, 0.6f};

    /** Every spring a view has, by property, so a new target keeps the current speed. */
    private static final Map<View, Map<Property<View, Float>, Anim>> SPRINGS = new WeakHashMap<>();

    private Motion() { }

    interface Sink {
        void set(float value);
    }

    /**
     * One {@link Spring} driving one value, frame by frame. Retargeting while it
     * runs keeps its velocity, so any motion can be grabbed mid-flight. With
     * animations off in Android settings it lands at once.
     */
    static final class Anim implements Choreographer.FrameCallback {
        final Spring spring;
        private final Sink sink;
        private Runnable end;
        private boolean running;
        private long last;

        Anim(float[] feel, float precision, Sink sink) {
            spring = new Spring(feel[0], feel[1], precision);
            this.sink = sink;
        }

        Anim feel(float[] feel) {
            spring.tune(feel[0], feel[1]);
            return this;
        }

        /** From {@code from}, keeping any speed it already has, to {@code target}; then {@code end}. */
        Anim to(float from, float target, Runnable end) {
            spring.value = from;
            spring.target = target;
            this.end = end;
            if (!ValueAnimator.areAnimatorsEnabled()) {
                settle();
            } else if (!running) {
                running = true;
                last = System.nanoTime();
                Choreographer.getInstance().postFrameCallback(this);
            }
            return this;
        }

        boolean running() { return running; }

        /** Stops where it is, without running the end action. */
        void stop() {
            Choreographer.getInstance().removeFrameCallback(this);
            running = false;
            spring.velocity = 0f;
            end = null;
        }

        @Override public void doFrame(long now) {
            float dt = now > last ? (now - last) / 1e9f : 1f / 120;
            last = now;
            if (spring.step(dt)) {
                settle();
            } else {
                sink.set(spring.value);
                Choreographer.getInstance().postFrameCallback(this);
            }
        }

        private void settle() {
            Choreographer.getInstance().removeFrameCallback(this);
            running = false;
            spring.value = spring.target;
            spring.velocity = 0f;
            sink.set(spring.target);
            Runnable e = end;
            end = null;
            if (e != null) e.run();
        }
    }

    /** The spring behind one property of a view, made on first use. */
    static Anim spring(View view, Property<View, Float> property, float[] feel) {
        Map<Property<View, Float>, Anim> springs = SPRINGS.computeIfAbsent(view, v -> new HashMap<>());
        Anim a = springs.get(property);
        if (a == null) {
            // Weak, so a spring never keeps a removed view alive.
            WeakReference<View> ref = new WeakReference<>(view);
            boolean px = property == View.TRANSLATION_X || property == View.TRANSLATION_Y
                    || property == View.TRANSLATION_Z || property == View.ROTATION;
            a = new Anim(feel, px ? 0.3f : 0.001f, value -> {
                View v = ref.get();
                if (v != null) property.set(v, value);
            });
            springs.put(property, a);
        }
        return a.feel(feel);
    }

    /** Springs a view property from wherever it is now to {@code target}. */
    static Anim to(View view, Property<View, Float> property, float target, float[] feel, Runnable end) {
        return spring(view, property, feel).to(property.get(view), target, end);
    }

    static Anim to(View view, Property<View, Float> property, float target, float[] feel) {
        return to(view, property, target, feel, null);
    }

    /** Whether any spring on the view is still running. */
    static boolean moving(View view) {
        Map<Property<View, Float>, Anim> springs = SPRINGS.get(view);
        if (springs != null) for (Anim a : springs.values()) if (a.running()) return true;
        return false;
    }

    /** Stops every spring on the view where it stands. */
    static void stop(View view) {
        Map<Property<View, Float>, Anim> springs = SPRINGS.get(view);
        if (springs != null) for (Anim a : springs.values()) a.stop();
    }

    /**
     * A screen's dimming as a property, drawn as a black foreground so the
     * screen stays opaque. A holder class so JVM checks never build it.
     */
    static final class Dim {
        static final Property<View, Float> PROPERTY = new android.util.FloatProperty<View>("dim") {
            @Override public void setValue(View v, float dim) {
                android.graphics.drawable.Drawable d = v.getForeground();
                if (dim <= 0f) {
                    if (d instanceof android.graphics.drawable.ColorDrawable) v.setForeground(null);
                    return;
                }
                if (!(d instanceof android.graphics.drawable.ColorDrawable)) {
                    d = new android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK);
                    v.setForeground(d);
                }
                d.setAlpha(Math.round(255 * Math.min(1f, dim)));
            }

            @Override public Float get(View v) {
                android.graphics.drawable.Drawable d = v.getForeground();
                return d instanceof android.graphics.drawable.ColorDrawable ? d.getAlpha() / 255f : 0f;
            }
        };

        private Dim() { }
    }

    /** The spring as a fixed-duration curve, for framework animators and transitions. */
    static TimeInterpolator curve(float[] feel) {
        float seconds = Spring.seconds(feel[0], feel[1]);
        return f -> f >= 1f ? 1f : Spring.curve(feel[0], feel[1], f * seconds);
    }

    /** The duration to pair with {@link #curve}. */
    static long millis(float[] feel) {
        return Math.round(Spring.seconds(feel[0], feel[1]) * 1000);
    }

    static void press(View view) {
        view.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    animatePressScale(v, true);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    animatePressScale(v, false);
                    break;
                default:
                    break;
            }
            return false; // preserve click, long-click and drag behavior
        });
    }

    interface Drop {
        void at(float translationX, float translationY);
    }

    /**
     * press() plus drag-to-move. A drag past touch slop moves the view by
     * translation, kept {@code margin} px inside its parent, and reports where it
     * was dropped; a tap still clicks.
     */
    static void draggable(View view, int margin, Drop onDrop) {
        final float[] start = new float[4];   // raw x, raw y, translation x, y
        final boolean[] dragging = {false};
        final int slop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
        view.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    start[0] = event.getRawX();
                    start[1] = event.getRawY();
                    start[2] = v.getTranslationX();
                    start[3] = v.getTranslationY();
                    dragging[0] = false;
                    animatePressScale(v, true);
                    return false;
                case MotionEvent.ACTION_MOVE: {
                    float dx = event.getRawX() - start[0], dy = event.getRawY() - start[1];
                    if (!dragging[0] && Math.hypot(dx, dy) > slop) {
                        dragging[0] = true;
                        // Cancel the view's own press so the drop is not a click.
                        MotionEvent cancel = MotionEvent.obtain(event);
                        cancel.setAction(MotionEvent.ACTION_CANCEL);
                        v.onTouchEvent(cancel);
                        cancel.recycle();
                    }
                    if (!dragging[0]) return false;
                    View parent = (View) v.getParent();
                    v.setTranslationX(clamp(start[2] + dx, margin - v.getLeft(),
                            parent.getWidth() - margin - v.getRight()));
                    v.setTranslationY(clamp(start[3] + dy, margin - v.getTop(),
                            parent.getHeight() - margin - v.getBottom()));
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    animatePressScale(v, false);
                    if (!dragging[0]) return false;
                    dragging[0] = false;
                    onDrop.at(v.getTranslationX(), v.getTranslationY());
                    return true;
                default:
                    return false;
            }
        });
    }

    static float clamp(float value, float min, float max) {
        return max < min ? min : Math.max(min, Math.min(max, value));
    }

    static boolean pressKeepsTranslationIndependent() {
        return true;
    }

    private static void animatePressScale(View view, boolean down) {
        if (!ValueAnimator.areAnimatorsEnabled()) {
            stop(view);
            view.setScaleX(1f);
            view.setScaleY(1f);
            view.setTranslationZ(0f);
            return;
        }
        float[] feel = down ? PRESS : RELEASE;
        float scale = down ? PRESS_SCALE : 1f;
        to(view, View.SCALE_X, scale, feel);
        to(view, View.SCALE_Y, scale, feel);
        to(view, View.TRANSLATION_Z, down ? -Ui.dp(view.getContext(), 2) : 0f, feel);
    }

    static void enter(View view) {
        reveal(view, 0, 0, Ui.dp(view.getContext(), 12));
    }

    /** A page arriving from the side it was reached from (1 forward, -1 back, 0 in place). */
    static void page(View view, int direction) {
        reveal(view, 0, Ui.dp(view.getContext(), 32) * direction, 0);
    }

    static void stagger(android.view.ViewGroup group) {
        int visible = 0;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            reveal(child, Math.min(visible++ * 35L, 105L), 0, Ui.dp(group.getContext(), 14));
        }
    }

    private static void reveal(View view, long delay, float x, float y) {
        view.animate().cancel();
        stop(view);
        view.setScaleX(1f);
        view.setScaleY(1f);
        if (!ValueAnimator.areAnimatorsEnabled()) {
            view.setAlpha(1f);
            view.setTranslationX(0f);
            view.setTranslationY(0f);
            return;
        }
        view.setAlpha(0f);
        view.setTranslationX(x);
        view.setTranslationY(y);
        Runnable go = () -> {
            to(view, View.ALPHA, 1f, FADE);
            to(view, View.TRANSLATION_X, 0f, SMOOTH);
            to(view, View.TRANSLATION_Y, 0f, SMOOTH);
        };
        if (delay > 0) view.postDelayed(go, delay);
        else go.run();
    }

    /**
     * Fade a whole screen in or out, then run {@code end}. Only ever over an
     * opaque screen or the deck itself, so the live desktop never shows through
     * a screen it should not.
     */
    static void fade(View view, boolean in, Runnable end) {
        view.animate().cancel();
        to(view, View.ALPHA, in ? 1f : 0f, FADE, end);
    }

    static void select(View view) {
        if (!ValueAnimator.areAnimatorsEnabled()) return;
        view.setScaleX(0.97f);
        view.setScaleY(0.97f);
        animatePressScale(view, false);
    }

    static void shake(View view) {
        if (!ValueAnimator.areAnimatorsEnabled()) return;
        float d = Ui.dp(view.getContext(), 8);
        ObjectAnimator x = ObjectAnimator.ofFloat(view, View.TRANSLATION_X,
                0f, -d, d * 0.72f, -d * 0.46f, d * 0.24f, 0f);
        x.setDuration(360);
        x.setInterpolator(new DecelerateInterpolator());
        x.start();
    }

    static boolean shouldPulse(int phase) {
        return phase == HomeView.PHASE_CONNECTING || phase == HomeView.PHASE_FINDING;
    }

    static void status(View view, int phase) {
        Object tag = view.getTag();
        if (tag instanceof Pulse) ((Pulse) tag).stop();
        view.setAlpha(1f);
        view.setScaleX(1f);
        view.setScaleY(1f);
        if (!shouldPulse(phase) || !ValueAnimator.areAnimatorsEnabled()) return;

        ObjectAnimator alpha = ObjectAnimator.ofFloat(view, View.ALPHA, 1f, 0.38f);
        ObjectAnimator sx = ObjectAnimator.ofFloat(view, View.SCALE_X, 1f, 1.55f);
        ObjectAnimator sy = ObjectAnimator.ofFloat(view, View.SCALE_Y, 1f, 1.55f);
        for (ObjectAnimator a : new ObjectAnimator[]{alpha, sx, sy}) {
            a.setRepeatCount(ObjectAnimator.INFINITE);
            a.setRepeatMode(ObjectAnimator.REVERSE);
        }
        AnimatorSet set = new AnimatorSet();
        set.playTogether(alpha, sx, sy);
        set.setDuration(620);
        set.setInterpolator(new PathInterpolator(0.45f, 0f, 0.55f, 1f));   // breathing, not springing
        Pulse pulse = new Pulse(view, set);
        view.setTag(pulse);
        view.addOnAttachStateChangeListener(pulse);
        set.start();
    }

    /** Owns an infinite status animation and tears it down with its View. */
    private static final class Pulse implements View.OnAttachStateChangeListener {
        private final View view;
        private final Animator animator;

        Pulse(View view, Animator animator) {
            this.view = view;
            this.animator = animator;
        }

        void stop() {
            animator.cancel();
            view.removeOnAttachStateChangeListener(this);
            if (view.getTag() == this) view.setTag(null);
        }

        @Override public void onViewAttachedToWindow(View v) { }
        @Override public void onViewDetachedFromWindow(View v) { stop(); }
    }
}
