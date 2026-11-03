package io.folddeck.spike;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
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
    static final float PRESS_SCALE = 0.975f;
    static final long PRESS_MS = 75;
    static final long RELEASE_MS = 300;
    static final long ENTER_MS = 260;
    static final long FADE_MS = 200;
    static final long THEATRE_MS = 340;
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
                    animatePressScale(v, 1f, RELEASE_MS, Bounce::backOut);
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
                    animatePressScale(v, 1f, RELEASE_MS, Bounce::backOut);
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
        if (!ValueAnimator.areAnimatorsEnabled()) {
            view.setScaleX(1f);
            view.setScaleY(1f);
            view.setTranslationZ(0f);
            return;
        }

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
        reveal(view, 0, 0, Ui.dp(view.getContext(), 12));
    }

    /** Shared-axis entry, confined to content above an opaque page background. */
    static void page(View view, int direction) {
        reveal(view, 0, Ui.dp(view.getContext(), 20) * direction, 0);
        // A brief optical settle, only on page content. Never blur the video decoder surface.
        if (!ValueAnimator.areAnimatorsEnabled()) return;
        android.graphics.RenderEffect[] blur = new android.graphics.RenderEffect[5];
        for (int i = 1; i < blur.length; i++) {
            float radius = i * view.getResources().getDisplayMetrics().density * 0.6f;
            blur[i] = android.graphics.RenderEffect.createBlurEffect(radius, radius,
                    android.graphics.Shader.TileMode.CLAMP);
        }
        view.setRenderEffect(blur[4]);
        view.animate().setUpdateListener(a -> {
            int step = Math.max(0, Math.round(4 * (1f - a.getAnimatedFraction() * 1.5f)));
            view.setRenderEffect(blur[step]);
        }).withEndAction(() -> {
            view.setRenderEffect(null);
            view.animate().setUpdateListener(null);
        });
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
        view.animate().setUpdateListener(null);
        view.setRenderEffect(null);
        view.animate().setListener(null).withEndAction(null);
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
        view.animate().alpha(1f).translationX(0f).translationY(0f).setStartDelay(delay)
                .setDuration(ENTER_MS).setInterpolator(spring()).start();
    }

    /**
     * Fade a whole screen in or out, then run {@code end}. Only ever over an
     * opaque screen or the deck itself, so the live desktop never shows through
     * a screen it should not.
     */
    static void fade(View view, boolean in, Runnable end) {
        view.animate().cancel();
        view.animate().setListener(null).setStartDelay(0);
        if (!ValueAnimator.areAnimatorsEnabled()) {
            view.setAlpha(in ? 1f : 0f);
            if (end != null) end.run();
            return;
        }
        view.animate().alpha(in ? 1f : 0f).setDuration(FADE_MS)
                .setInterpolator(in ? new DecelerateInterpolator(1.5f)
                        : new PathInterpolator(0.4f, 0f, 1f, 1f))
                .withEndAction(end).start();
    }

    static void select(View view) {
        if (!ValueAnimator.areAnimatorsEnabled()) return;
        view.setScaleX(0.97f);
        view.setScaleY(0.97f);
        animatePressScale(view, 1f, 200, spring());
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
