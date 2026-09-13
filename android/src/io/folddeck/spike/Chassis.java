package io.folddeck.spike;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.widget.FrameLayout;

/**
 * The laptop the Fold turns into when it is unfolded and held in landscape.
 *
 * The inner display's crease runs horizontally across the middle in that
 * posture, and there is nothing to be done about it — so it is made into the
 * hinge rather than fought. {@link Hinge} is drawn centred on the fold line,
 * with the video lid above it and the keyboard base below, each getting exactly
 * half of what is left. Equal halves are not an aesthetic choice: it is the only
 * split that puts the seam between the two panes *on* the crease instead of a
 * centimetre off it, where it reads as a mistake.
 *
 * The bezels come from the same reasoning. A 16:9 desktop letterboxed into a
 * near-square pane leaves black bars whatever you do; framing them deliberately
 * turns dead pixels into a screen surround, which is what the eye expects to see
 * around a display anyway.
 *
 * None of this applies to the cover screen or to portrait — see
 * {@code MainActivity.laptopPosture()}.
 */
final class Chassis {

    private Chassis() { }

    static final int BEZEL_SIDE_DP = 16;
    static final int LID_TOP_DP = 14, LID_BOTTOM_DP = 10;
    static final int BASE_TOP_DP = 10, BASE_BOTTOM_DP = 14;
    /** Roughly the width of the physical crease at this display's density. */
    static final int HINGE_DP = 22;

    // ------------------------------------------------------------------ //
    /**
     * The screen half: chassis around a recessed black well that the video sits
     * in, plus the webcam dot that sells the whole illusion for two pixels.
     */
    static final class Lid extends FrameLayout {

        private final Paint paint = new Paint();
        private final RectF well = new RectF();
        private final float camR;
        private float camY;
        private boolean chassis;

        Lid(Context c) {
            super(c);
            // A ViewGroup does not call onDraw unless told it will draw.
            setWillNotDraw(false);
            camR = Math.max(1.5f, Ui.dp(c, 1.6f));
            setChassis(false);
        }

        /** Bezels on for the unfolded laptop, off everywhere else. */
        void setChassis(boolean on) {
            chassis = on;
            Context c = getContext();
            if (on) {
                // No right bezel: the desktop sits flush against the screen's right
                // edge and the shortcut deck takes the left (see MainActivity).
                setPadding(Ui.dp(c, BEZEL_SIDE_DP), Ui.dp(c, LID_TOP_DP),
                        0, Ui.dp(c, LID_BOTTOM_DP));
            } else {
                setPadding(0, 0, 0, 0);
            }
            invalidate();
        }

        /**
         * Draw the screen around the image, not around the pane.
         *
         * A 16:9 desktop letterboxed into this near-square lid leaves wide bars,
         * and a well spanning the whole pane would put those bars *inside* the
         * drawn screen — a black screen with a smaller picture floating in it.
         * Hugging the image instead turns the bars into chassis, which is what
         * a 16:9 panel in a squarer body actually looks like.
         *
         * Coordinates are this view's own, so the caller adds the padding.
         */
        void setScreen(int l, int t, int r, int b) {
            if (r <= l || b <= t) return;
            if (well.left == l && well.top == t && well.right == r && well.bottom == b) {
                return;   // called on every layout pass; only redraw on a change
            }
            well.set(l, t, r, b);
            camY = well.top / 2f;
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            // Until the first frame's aspect is known, the whole content box.
            well.set(getPaddingLeft(), getPaddingTop(), w - getPaddingRight(),
                    h - getPaddingBottom());
            camY = getPaddingTop() / 2f;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            // Painted here rather than as a background so a theme change only
            // needs an invalidate().
            canvas.drawColor(Ui.BG);
            if (!chassis) return;

            // Pure black, darker than the chassis, so the panel reads as glass
            // rather than as another painted surface.
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.BLACK);
            canvas.drawRect(well, paint);

            // The desktop is the focused window, so it wears Hyprland's active
            // border: the accent, drawn just outside the image.
            float border = Ui.dp(getContext(), 2);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(border);
            paint.setColor(Ui.ACCENT);
            canvas.drawRect(well.left - border / 2, well.top - border / 2,
                    well.right + border / 2, well.bottom + border / 2, paint);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Ui.SURFACE_HI);
            // The webcam belongs over the screen, which is no longer centred.
            canvas.drawRect(well.centerX() - camR, camY - camR,
                    well.centerX() + camR, camY + camR, paint);
        }

        /** Width of the line that stands in for the hinge when there is none. */
        static int separator(Context c) {
            return Ui.dp(c, 2);
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            super.dispatchDraw(canvas);
            if (chassis) return;
            // Without the hinge nothing divides the desktop from the keyboard. An
            // accent line does, drawn over the children so it lands exactly on the
            // deck's bottom border and the two read as one.
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Ui.ACCENT);
            canvas.drawRect(0, getHeight() - separator(getContext()), getWidth(), getHeight(), paint);
        }
    }

    // ------------------------------------------------------------------ //
    /**
     * The keyboard half. Same side inset as the lid, so the two edges line up
     * down the machine — the single cue that makes it one object and not two
     * stacked panels.
     */
    static final class Base extends FrameLayout {

        private final Paint paint = new Paint();
        private final RectF well = new RectF();
        private boolean chassis;

        Base(Context c) {
            super(c);
            setWillNotDraw(false);
            setChassis(false);
        }

        void setChassis(boolean on) {
            chassis = on;
            Context c = getContext();
            if (on) {
                setPadding(Ui.dp(c, BEZEL_SIDE_DP), Ui.dp(c, BASE_TOP_DP),
                        Ui.dp(c, BEZEL_SIDE_DP), Ui.dp(c, BASE_BOTTOM_DP));
            } else {
                setPadding(0, 0, 0, 0);
            }
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            well.set(getPaddingLeft(), getPaddingTop(), w - getPaddingRight(),
                    h - getPaddingBottom());
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawColor(Ui.BG);
            if (!chassis) return;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1, Ui.dp(getContext(), 1)));
            paint.setColor(Ui.LINE);
            canvas.drawRect(well, paint);
        }
    }

    // ------------------------------------------------------------------ //
    /**
     * The hinge itself: a barrel spanning the width with a knuckle at each end.
     *
     * Drawn as a vertical gradient rather than a flat band because a flat band
     * reads as a divider line, and a divider is exactly the wrong metaphor — the
     * point is to suggest a cylinder the lid rotates around.
     */
    static final class Hinge extends View {

        private final Paint paint = new Paint();
        private final RectF barrel = new RectF();
        private final RectF knuckleL = new RectF(), knuckleR = new RectF();

        Hinge(Context c) {
            super(c);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            if (w <= 0 || h <= 0) return;

            float barrelH = h * 0.46f;
            float top = (h - barrelH) / 2f;
            float inset = Ui.dp(getContext(), BEZEL_SIDE_DP);
            barrel.set(inset, top, w - inset, top + barrelH);

            // Knuckles sit a fifth in from each edge, as they do on a real hinge,
            // and are taller than the barrel so they read as separate parts.
            float kw = w * 0.09f, kh = h * 0.72f;
            float ky = (h - kh) / 2f;
            knuckleL.set(w * 0.18f, ky, w * 0.18f + kw, ky + kh);
            knuckleR.set(w * 0.82f - kw, ky, w * 0.82f, ky + kh);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawColor(Ui.BG);
            // Built per draw, not per size change, because the colours can change
            // under it; the hinge only redraws on layout or a theme change.
            paint.setShader(new LinearGradient(0, barrel.top, 0, barrel.bottom,
                    new int[]{Ui.BG, Ui.LINE, Ui.SURFACE, Ui.BG},
                    new float[]{0f, 0.35f, 0.6f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRect(barrel, paint);

            // Knuckles are flat-filled: the gradient belongs to the barrel, and
            // repeating it here would flatten the whole thing back into stripes.
            paint.setShader(null);
            paint.setColor(Ui.SURFACE_HI);
            canvas.drawRect(knuckleL, paint);
            canvas.drawRect(knuckleR, paint);
        }
    }
}
