package io.folddeck.spike;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.PathInterpolator;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** Small motion grammar shared by every screen and control. */
final class Motion {
    static final float PRESS_SCALE = 0.972f;
    static final long PRESS_MS = 75;
    static final long RELEASE_MS = 170;
    static final long ENTER_MS = 220;
    static final long COVER_SWITCH_MS = 260;
    static final float COVER_OVERSHOOT = 1.18f;

    private static final Map<View, WeakReference<Animator>> PRESS_ANIMATORS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private Motion() { }

    private static PathInterpolator spring() {
        // Lazy so pure JVM checks can inspect the motion contract without
        // instantiating Android framework stubs.
        return new PathInterpolator(0.16f, 1f, 0.30f, 1f);
    }

    static void press(View view) {
        view.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    animatePressScale(v, PRESS_SCALE, PRESS_MS,
                            new DecelerateInterpolator());
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    animatePressScale(v, 1f, RELEASE_MS, spring());
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
                    animatePressScale(v, PRESS_SCALE, PRESS_MS, new DecelerateInterpolator());
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
                    animatePressScale(v, 1f, RELEASE_MS, spring());
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

    private static void animatePressScale(View view, float target, long duration,
                                          android.animation.TimeInterpolator interpolator) {
        WeakReference<Animator> priorRef = PRESS_ANIMATORS.get(view);
        Animator prior = priorRef == null ? null : priorRef.get();
        if (prior != null) prior.cancel();

        ObjectAnimator sx = ObjectAnimator.ofFloat(view, View.SCALE_X,
                view.getScaleX(), target);
        ObjectAnimator sy = ObjectAnimator.ofFloat(view, View.SCALE_Y,
                view.getScaleY(), target);
        float targetZ = target < 1f ? -Ui.dp(view.getContext(), 2) : 0f;
        ObjectAnimator z = ObjectAnimator.ofFloat(view, View.TRANSLATION_Z,
                view.getTranslationZ(), targetZ);
        AnimatorSet set = new AnimatorSet();
        set.playTogether(sx, sy, z);
        set.setDuration(duration);
        set.setInterpolator(interpolator);
        PRESS_ANIMATORS.put(view, new WeakReference<>(set));
        set.start();
    }

    static void enter(View view) {
        view.animate().cancel();
        view.setAlpha(0f);
        view.setTranslationY(Ui.dp(view.getContext(), 14));
        view.setScaleX(0.992f);
        view.setScaleY(0.992f);
        view.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                .setDuration(ENTER_MS).setInterpolator(spring()).start();
    }

    static void shake(View view) {
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
        if (!shouldPulse(phase)) return;

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
        set.setInterpolator(spring());
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
