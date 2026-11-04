package io.folddeck.spike;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/** A local-only edit layer. The live deck stays mounted and never receives edit gestures. */
final class ResizeOverlay extends FrameLayout {
    static final int MONITOR = 0, KEYBOARD = 1, TRACKPAD = 2, BAR = 3,
            HEIGHT = 4, WHEEL = 5, BUTTONS = 6;
    private static final String[] NAMES = {"Monitor", "Keyboard", "Trackpad", "Bar", "Pad height", "Wheel", "Buttons"};

    private static final int MODE_NONE = 0, MODE_CORNER = 1, MODE_PAN_OR_SWAP = 2, MODE_FALLBACK_1D = 3;

    interface Listener {
        float value(int target);
        void change(int target, float fraction);
        boolean horizontal(int target);
        void beginResize();
        /** Returns how far past the limits the finger is, in px along x and y. */
        float[] resizeCorner(int target, int corner, float dxPx, float dyPx, float overlayW, float overlayH);
        /** Moves {@code from} into {@code to}'s place; false when that changes nothing. */
        boolean swap(int from, int to, float dropX, InputDeckLayout.Box toBox);
        void reset();
        void finish(boolean save);
    }

    private final Listener listener;
    private final View[] targets;
    private final TextView[] tabs = new TextView[NAMES.length];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path bracketPath = new Path();
    private final RectF box = new RectF();
    private final RectF animBox = new RectF();
    private final RectF tempBox = new RectF();
    private final RectF dragSourceRect = new RectF();
    private final int[] origin = new int[2], location = new int[2];
    private final SeekBar slider;
    private final TextView hint;
    private final LinearLayout panel;
    private final int touchSlop;
    private int selected, pointer = -1;
    private int touchMode = MODE_NONE;
    private int activeCorner = WorkspaceGeometry.CORNER_NONE;
    private int dragSource = -1, lastUnder = -1, ghostPane = -1;
    private boolean draggingSwap, swapped, landing;
    private boolean panelAtBottom;
    private float startX, startY, dragX, dragY, startValue;

    /** Follows a finger: no visible lag, no bounce. */
    private static final float[] TRACK = {0.1f, 1f};
    /** The selection frame (l, t, r, b), the dragged pane's ghost, and how lifted it is. */
    private final Spring[] frame = new Spring[4], ghost = new Spring[4];
    private final Spring lift = new Spring(Motion.SNAPPY[0], Motion.SNAPPY[1], 0.002f);
    private boolean framePlaced, moving;
    private long lastFrame;
    /** Finger travel past the resize limits, px, and whether that has ticked yet. */
    private float[] overshoot = new float[2];
    private boolean pastLimit;

    ResizeOverlay(Context c, View[] targets, Listener listener) {
        super(c);
        this.targets = targets;
        this.listener = listener;
        this.touchSlop = ViewConfiguration.get(c).getScaledTouchSlop();
        setWillNotDraw(false);
        setClickable(true);
        for (int i = 0; i < 4; i++) {
            frame[i] = new Spring(Motion.SNAPPY[0], Motion.SNAPPY[1], 0.3f);
            ghost[i] = new Spring(Motion.SNAPPY[0], Motion.SNAPPY[1], 0.3f);
        }
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        panel = new LinearLayout(c);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(Ui.dp(c, 12), Ui.dp(c, 6), Ui.dp(c, 12), Ui.dp(c, 8));
        panel.setBackground(Ui.box(Ui.SURFACE, Ui.LINE, c));
        Ui.elevate(panel, c);
        panel.setClickable(true);

        LinearLayout actions = new LinearLayout(c);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.text(c, "LAYOUT STUDIO", 11, Ui.ACCENT);
        title.setTypeface(Ui.MONO_BOLD);
        title.setAccessibilityHeading(true);
        actions.addView(title, new LinearLayout.LayoutParams(0, Ui.dp(c, 48), 1));
        title.setGravity(Gravity.CENTER_VERTICAL);
        actions.addView(action(c, "Reset", () -> {
            listener.reset();
            refresh();
        }));
        actions.addView(action(c, "Cancel", () -> listener.finish(false)));
        TextView done = action(c, "Done", () -> listener.finish(true));
        done.setTextColor(Ui.ACCENT);
        actions.addView(done);
        panel.addView(actions);

        LinearLayout choices = new LinearLayout(c);
        LinearLayout padChoices = new LinearLayout(c);
        for (int i = 0; i < tabs.length; i++) {
            final int index = i;
            TextView tab = Ui.text(c, NAMES[i], 13, Ui.TEXT_DIM);
            tab.setGravity(Gravity.CENTER);
            tab.setFocusable(true);
            tab.setOnClickListener(v -> {
                selectTarget(index);
                Motion.select(tab);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(c, 44), 1);
            if (i != 0 && i != HEIGHT) lp.leftMargin = Ui.dp(c, 4);
            if (i >= HEIGHT) lp.topMargin = Ui.dp(c, 4);
            (i < HEIGHT ? choices : padChoices).addView(tab, lp);
            tabs[i] = tab;
        }
        panel.addView(choices);
        panel.addView(padChoices);
        slider = new SeekBar(c);
        slider.setMax(1000);
        android.graphics.drawable.GradientDrawable track = Ui.box(Ui.LINE, Ui.LINE, c);
        android.graphics.drawable.ClipDrawable progress = new android.graphics.drawable.ClipDrawable(
                Ui.box(Ui.ACCENT, Ui.ACCENT, c), Gravity.LEFT,
                android.graphics.drawable.ClipDrawable.HORIZONTAL);
        android.graphics.drawable.LayerDrawable rail = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{track, progress});
        rail.setId(0, android.R.id.background);
        rail.setId(1, android.R.id.progress);
        for (int i = 0; i < 2; i++) {
            rail.setLayerHeight(i, Ui.dp(c, 4));
            rail.setLayerGravity(i, Gravity.CENTER_VERTICAL | Gravity.FILL_HORIZONTAL);
        }
        slider.setProgressDrawable(rail);
        android.graphics.drawable.GradientDrawable thumb = Ui.box(Ui.ACCENT, Ui.SURFACE, c);
        thumb.setSize(Ui.dp(c, 20), Ui.dp(c, 20));
        thumb.setStroke(Ui.dp(c, 3), Ui.SURFACE);
        slider.setThumb(thumb);
        slider.setSplitTrack(false);
        slider.setPadding(Ui.dp(c, 10), 0, Ui.dp(c, 10), 0);
        slider.setProgressTintList(ColorStateList.valueOf(Ui.ACCENT));
        slider.setThumbTintList(ColorStateList.valueOf(Ui.ACCENT));
        slider.setProgressBackgroundTintList(ColorStateList.valueOf(Ui.LINE));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean user) {
                if (user) {
                    listener.change(selected, progress / 1000f);
                    invalidate();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { refresh(); }
        });
        panel.addView(slider, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, Ui.dp(c, 44)));
        hint = Ui.text(c, "", 11, Ui.TEXT_DIM);
        hint.setGravity(Gravity.CENTER);
        panel.addView(hint);
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.setMargins(Ui.dp(c, 12), Ui.dp(c, 12), Ui.dp(c, 12), Ui.dp(c, 12));
        addView(panel, lp);
        refresh();
    }

    /** The frame springs over to the new pane on its own. */
    private void selectTarget(int index) {
        if (index < 0 || index >= tabs.length) return;
        selected = index;
        refresh();
    }

    /** Steps {@code springs} toward {@code to} and writes where they are now into {@code out}. */
    private boolean spring(Spring[] springs, RectF to, RectF out, float[] feel, float dt, boolean jump) {
        float[] t = {to.left, to.top, to.right, to.bottom};
        boolean rest = true;
        for (int i = 0; i < 4; i++) {
            Spring sp = springs[i].tune(feel[0], feel[1]);
            sp.target = t[i];
            if (jump) {
                sp.value = t[i];
                sp.velocity = 0f;
            } else if (!sp.step(dt)) {
                rest = false;
            }
        }
        out.set(springs[0].value, springs[1].value, springs[2].value, springs[3].value);
        return !rest;
    }

    /** Past a limit the frame keeps following the finger, but with growing resistance. */
    private static float rubber(float over, float range) {
        return Math.signum(over) * range * (1f - 1f / (Math.abs(over) * 0.55f / range + 1f));
    }

    private void stretch(RectF r) {
        float range = Ui.dp(getContext(), 44), min = Ui.dp(getContext(), 24);
        float sx = rubber(overshoot[0], range), sy = rubber(overshoot[1], range);
        boolean left = activeCorner == WorkspaceGeometry.CORNER_TL || activeCorner == WorkspaceGeometry.CORNER_BL;
        boolean top = activeCorner == WorkspaceGeometry.CORNER_TL || activeCorner == WorkspaceGeometry.CORNER_TR;
        if (left) r.left = Math.min(r.left + sx, r.right - min);
        else r.right = Math.max(r.right + sx, r.left + min);
        if (top) r.top = Math.min(r.top + sy, r.bottom - min);
        else r.bottom = Math.max(r.bottom + sy, r.top + min);
    }

    private TextView action(Context c, String label, Runnable run) {
        TextView button = Ui.text(c, label, 13, Ui.TEXT_DIM);
        button.setGravity(Gravity.CENTER);
        button.setPadding(Ui.dp(c, 12), 0, Ui.dp(c, 12), 0);
        button.setMinimumHeight(Ui.dp(c, 48));
        button.setFocusable(true);
        button.setBackground(Ui.pressable(Ui.box(Ui.SURFACE, Ui.SURFACE, c)));
        button.setOnClickListener(v -> run.run());
        Motion.press(button);
        return button;
    }

    @Override protected void onMeasure(int ws, int hs) {
        LayoutParams lp = (LayoutParams) panel.getLayoutParams();
        lp.width = Math.min(Ui.dp(getContext(), 560), Math.max(0,
                MeasureSpec.getSize(ws) - lp.leftMargin - lp.rightMargin));
        super.onMeasure(ws, hs);
    }

    private void updatePanelDock() {
        if (getHeight() <= 0 || touchMode != MODE_NONE) return;
        if (!computeTargetRect(selected, tempBox)) return;
        boolean wantBottom = selected == MONITOR && tempBox.centerY() < getHeight() * 0.52f;
        if (wantBottom == panelAtBottom) return;
        panelAtBottom = wantBottom;
        LayoutParams lp = (LayoutParams) panel.getLayoutParams();
        lp.gravity = (wantBottom ? Gravity.BOTTOM : Gravity.TOP) | Gravity.CENTER_HORIZONTAL;
        panel.setLayoutParams(lp);
    }

    void refresh() {
        for (int i = 0; i < tabs.length; i++) {
            boolean available = targets[i].isShown() && targets[i].getHeight() > 0 && targets[i].getWidth() > 0;
            tabs[i].setEnabled(available);
            tabs[i].setAlpha(available ? 1f : 0.35f);
            tabs[i].setSelected(i == selected);
            tabs[i].setTextColor(i == selected ? Ui.TEXT : Ui.TEXT_DIM);
            tabs[i].setBackground(Ui.pressable(Ui.box(i == selected ? Ui.SELECTED : Ui.SURFACE,
                    i == selected ? Ui.SELECTED : Ui.LINE, getContext())));
        }
        slider.setEnabled(tabs[selected].isEnabled());
        slider.setContentDescription(NAMES[selected] + " size");
        slider.setProgress(Math.round(listener.value(selected) * 1000));
        // After a live swap, whatever now lies under the finger is where it already is.
        if (draggingSwap) lastUnder = WorkspaceGeometry.hitTarget(primaryBoxes(), dragX, dragY);
        hint.setText(tabs[selected].isEnabled()
                ? "Grab any corner to resize · drag pane to swap · Done saves"
                : "Enable this control in App settings to resize it");
        updatePanelDock();
        invalidate();
    }

    void enter() { Motion.page(panel, 0); }

    private boolean computeTargetRect(int index, RectF out) {
        if (index < 0 || index >= targets.length) return false;
        View target = targets[index];
        if (!target.isShown() || target.getWidth() <= 0 || target.getHeight() <= 0) return false;
        getLocationInWindow(origin);
        target.getLocationInWindow(location);
        float l = location[0] - origin[0] + target.getTranslationX() + 2f;
        float t = location[1] - origin[1] + target.getTranslationY() + 2f;
        float r = l + target.getWidth() - 4f;
        float b = t + target.getHeight() - 4f;
        if (r <= l || b <= t) return false;
        out.set(l, t, r, b);
        return true;
    }

    private InputDeckLayout.Box[] primaryBoxes() {
        InputDeckLayout.Box[] boxes = new InputDeckLayout.Box[4];
        getLocationInWindow(origin);
        for (int i = 0; i < 4; i++) {
            View v = targets[i];
            if (v != null && v.isShown() && v.getWidth() > 0 && v.getHeight() > 0) {
                v.getLocationInWindow(location);
                // Where the pane is laid out, not where it is sliding through:
                // hit tests stay still while panes animate.
                int x = Math.round(location[0] - origin[0] - v.getTranslationX());
                int y = Math.round(location[1] - origin[1] - v.getTranslationY());
                boxes[i] = new InputDeckLayout.Box(x, y, v.getWidth(), v.getHeight());
            } else {
                boxes[i] = new InputDeckLayout.Box(0, 0, 0, 0);
            }
        }
        return boxes;
    }

    @Override protected void onDraw(Canvas canvas) {
        Context c = getContext();
        float radius = 0;   // Omarchy corners are square
        int primarySelected = selected >= HEIGHT ? TRACKPAD : selected;
        long now = System.nanoTime();
        // After a rest the first step is one frame, not the whole idle gap.
        float dt = moving ? (now - lastFrame) / 1e9f : 1f / 60;
        lastFrame = now;
        boolean jump = !ValueAnimator.areAnimatorsEnabled();
        boolean animating = false;

        // 1. Draw subtle outlines, corner ticks, and labels for unselected primary panes.
        for (int i = 0; i < 4; i++) {
            if (i == primarySelected || !computeTargetRect(i, tempBox)) continue;
            paint.setColor(Ui.mix(Ui.LINE, Ui.ACCENT, 0.45f));
            paint.setStyle(Paint.Style.FILL);
            paint.setAlpha(10);
            canvas.drawRoundRect(tempBox, radius, radius, paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Ui.dp(c, 1f));
            paint.setAlpha(110);
            canvas.drawRoundRect(tempBox, radius, radius, paint);

            drawCornerBrackets(canvas, tempBox, Ui.dp(c, 11), Ui.dp(c, 2f),
                    130, WorkspaceGeometry.CORNER_NONE, false);
            drawBadge(canvas, tempBox, NAMES[i], false);
        }

        // 2. The selected pane: a frame on springs, so it glides between panes,
        //    stretches elastically past a limit and settles back on release.
        if (tabs[selected].isEnabled() && computeTargetRect(selected, box)) {
            boolean cornering = touchMode == MODE_CORNER;
            if (cornering) stretch(box);
            animating |= spring(frame, box, animBox, cornering ? TRACK : Motion.SNAPPY, dt,
                    jump || !framePlaced);
            framePlaced = true;

            paint.setColor(Ui.ACCENT);
            paint.setAlpha(cornering ? 28 : 16);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawRoundRect(animBox, radius, radius, paint);

            paint.setAlpha(220);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Ui.dp(c, 1.75f));
            canvas.drawRoundRect(animBox, radius, radius, paint);

            drawCornerBrackets(canvas, animBox, Ui.dp(c, 20), Ui.dp(c, 3.5f),
                    255, cornering ? activeCorner : WorkspaceGeometry.CORNER_NONE, true);

            String label = NAMES[selected] + (selected < HEIGHT ? " · ⤡ corners / ⇄ drag"
                    : (listener.horizontal(selected) ? " ↔" : " ↕"));
            drawBadge(canvas, animBox, label, true);
        } else {
            framePlaced = false;
        }

        // 3. The dragged pane, lifted under the finger; on release it lands in its slot.
        if (ghostPane >= 0) {
            if (draggingSwap) {
                tempBox.set(dragSourceRect);
                tempBox.offset(dragX - startX, dragY - startY);
            } else if (!computeTargetRect(ghostPane, tempBox)) {
                tempBox.set(animBox);
            }
            lift.target = draggingSwap ? 1f : 0f;
            if (jump) lift.value = lift.target;
            else animating |= !lift.step(dt);
            float up = Math.max(0f, lift.value);
            tempBox.inset(-tempBox.width() * 0.02f * up, -tempBox.height() * 0.02f * up);
            animating |= spring(ghost, tempBox, tempBox, draggingSwap ? TRACK : Motion.SNAPPY, dt,
                    jump || !landing && !draggingSwap);

            paint.setColor(Ui.SURFACE_HI);
            paint.setAlpha(Math.round(185 * Math.min(1f, up)));
            paint.setStyle(Paint.Style.FILL);
            canvas.drawRect(tempBox, paint);
            paint.setColor(Ui.ACCENT);
            paint.setAlpha(Math.round(245 * Math.min(1f, up)));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Ui.dp(c, 2.2f));
            canvas.drawRect(tempBox, paint);
            if (draggingSwap) drawBadge(canvas, tempBox, "⇄ " + NAMES[ghostPane], true);
            if (!draggingSwap && lift.value <= 0.002f) {
                landing = false;
                ghostPane = -1;
            }
        }

        // Panes sliding after a swap move under the frame; keep drawing until they stop.
        for (int i = 0; i < 4 && !animating; i++) {
            View v = targets[i];
            animating = v.getTranslationX() != 0f || v.getTranslationY() != 0f;
        }
        moving = animating;
        if (animating) postInvalidateOnAnimation();
    }

    private void drawCornerBrackets(Canvas canvas, RectF r, float arm, float stroke,
                                    int alpha, int highlightedCorner, boolean nodes) {
        Context c = getContext();
        float ax = Math.min(arm, r.width() * 0.32f);
        float ay = Math.min(arm, r.height() * 0.32f);
        float cr = 0;   // square brackets, as Omarchy

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.SQUARE);
        paint.setStrokeJoin(Paint.Join.MITER);

        float[] xs = {r.left, r.right, r.left, r.right};
        float[] ys = {r.top, r.top, r.bottom, r.bottom};
        float[] sx = {1f, -1f, 1f, -1f};
        float[] sy = {1f, 1f, -1f, -1f};

        for (int i = 0; i < 4; i++) {
            boolean active = i == highlightedCorner;
            float x = xs[i], y = ys[i];
            float dx = sx[i] * ax, dy = sy[i] * ay;
            float rx = sx[i] * cr, ry = sy[i] * cr;

            if (active) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Ui.ACCENT);
                paint.setAlpha(64);
                float halo = Ui.dp(c, 20);
                canvas.drawRect(x - halo, y - halo, x + halo, y + halo, paint);
            }

            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(Ui.ACCENT);
            paint.setAlpha(alpha);
            paint.setStrokeWidth(active ? stroke * 1.35f : stroke);

            bracketPath.reset();
            bracketPath.moveTo(x, y + dy);
            bracketPath.lineTo(x, y + ry);
            bracketPath.quadTo(x, y, x + rx, y);
            bracketPath.lineTo(x + dx, y);
            canvas.drawPath(bracketPath, paint);

            if (nodes) {
                float nodeR = Ui.dp(c, active ? 7.5f : 5.5f);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Ui.ACCENT);
                paint.setAlpha(alpha);
                canvas.drawRect(x - nodeR, y - nodeR, x + nodeR, y + nodeR, paint);
                float dot = nodeR * 0.42f;
                paint.setColor(Ui.onAccent());
                paint.setAlpha(alpha);
                canvas.drawRect(x - dot, y - dot, x + dot, y + dot, paint);
            }
        }
    }

    private void drawBadge(Canvas canvas, RectF r, String text, boolean accent) {
        Context c = getContext();
        paint.setTypeface(Ui.MONO_BOLD);
        paint.setTextSize(Ui.dp(c, accent ? 11 : 10));
        paint.setTextAlign(Paint.Align.CENTER);
        float textW = paint.measureText(text);
        float halfW = Math.min(r.width() * 0.46f, Math.max(Ui.dp(c, 42), textW / 2f + Ui.dp(c, 12)));
        float halfH = Ui.dp(c, accent ? 14 : 11);
        float cx = r.centerX();
        float cy = r.centerY();
        if (panel.getVisibility() == VISIBLE && cx >= panel.getLeft() - halfW && cx <= panel.getRight() + halfW) {
            if (cy + halfH > panel.getTop() && cy - halfH < panel.getBottom()) {
                if (panelAtBottom) {
                    cy = Math.max(r.top + halfH + Ui.dp(c, 6), panel.getTop() - halfH - Ui.dp(c, 8));
                } else {
                    cy = Math.min(r.bottom - halfH - Ui.dp(c, 6), panel.getBottom() + halfH + Ui.dp(c, 8));
                }
            }
        }
        cy = Motion.clamp(cy, halfH + Ui.dp(c, 4), getHeight() - halfH - Ui.dp(c, 4));

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(accent ? Ui.ACCENT : Ui.SURFACE_HI);
        paint.setAlpha(accent ? 240 : 205);
        canvas.drawRect(cx - halfW, cy - halfH, cx + halfW, cy + halfH, paint);

        if (!accent) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Ui.dp(c, 1));
            paint.setColor(Ui.mix(Ui.LINE, Ui.ACCENT, 0.4f));
            paint.setAlpha(180);
            canvas.drawRect(cx - halfW, cy - halfH, cx + halfW, cy + halfH, paint);
        }

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(accent ? Ui.onAccent() : Ui.TEXT);
        paint.setAlpha(255);
        canvas.drawText(text, cx, cy - (paint.ascent() + paint.descent()) / 2f, paint);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (!slider.isEnabled() && e.getActionMasked() != MotionEvent.ACTION_DOWN) return true;
        Context c = getContext();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                pointer = e.getPointerId(0);
                startX = dragX = e.getX();
                startY = dragY = e.getY();
                activeCorner = WorkspaceGeometry.CORNER_NONE;
                dragSource = -1;
                draggingSwap = false;
                swapped = false;
                overshoot = new float[2];
                pastLimit = false;

                InputDeckLayout.Box[] boxes = primaryBoxes();
                int hitCorner = WorkspaceGeometry.hitCorner(boxes, selected, startX, startY,
                        Ui.dp(c, 38), Ui.dp(c, 20));
                if (hitCorner >= 0) {
                    int target = hitCorner >> 4;
                    int corner = hitCorner & 0xF;
                    if (selected != target) selectTarget(target);
                    touchMode = MODE_CORNER;
                    activeCorner = corner;
                    listener.beginResize();
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    startValue = listener.value(selected);
                    invalidate();
                    return true;
                }

                int hitPane = selected < HEIGHT ? WorkspaceGeometry.hitTarget(boxes, startX, startY) : -1;
                if (hitPane >= 0) {
                    touchMode = MODE_PAN_OR_SWAP;
                    dragSource = hitPane;
                    lastUnder = hitPane;
                    computeTargetRect(hitPane, dragSourceRect);
                    listener.beginResize();
                    startValue = listener.value(selected);
                    invalidate();
                    return true;
                }

                touchMode = MODE_FALLBACK_1D;
                listener.beginResize();
                startValue = listener.value(selected);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                int index = e.findPointerIndex(pointer);
                if (index < 0) return true;
                float x = e.getX(index), y = e.getY(index);
                dragX = x;
                dragY = y;

                if (touchMode == MODE_CORNER) {
                    overshoot = listener.resizeCorner(selected, activeCorner,
                            x - startX, y - startY, getWidth(), getHeight());
                    boolean past = Math.max(Math.abs(overshoot[0]), Math.abs(overshoot[1])) > Ui.dp(c, 16);
                    if (past && !pastLimit) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    pastLimit = past;
                    slider.setProgress(Math.round(listener.value(selected) * 1000));
                    invalidate();
                    return true;
                }

                if (touchMode == MODE_PAN_OR_SWAP) {
                    if (!draggingSwap && Math.hypot(x - startX, y - startY) > touchSlop) {
                        draggingSwap = true;
                        ghostPane = dragSource;
                        landing = false;
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    }
                    if (draggingSwap) {
                        // Live: the panes trade places the moment the finger enters
                        // another one, so you see the layout before you let go.
                        InputDeckLayout.Box[] boxes = primaryBoxes();
                        int under = WorkspaceGeometry.hitTarget(boxes, x, y);
                        if (under != lastUnder) {
                            lastUnder = under;
                            if (under >= 0 && under != dragSource
                                    && listener.swap(dragSource, under, x, boxes[under])) {
                                swapped = true;
                                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                            }
                        }
                        invalidate();
                    }
                    return true;
                }

                if (!slider.isEnabled()) return true;
                boolean horizontal = listener.horizontal(selected);
                float delta = horizontal ? (x - startX) / Math.max(1f, getWidth())
                        : (y - startY) / Math.max(1f, getHeight());
                listener.change(selected, Motion.clamp(startValue + delta, 0f, 1f));
                slider.setProgress(Math.round(listener.value(selected) * 1000));
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_POINTER_UP:
                if (e.getPointerId(e.getActionIndex()) == pointer) pointer = -1;
                return true;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP: {
                if (e.getActionMasked() == MotionEvent.ACTION_UP && touchMode == MODE_PAN_OR_SWAP) {
                    if (!draggingSwap && dragSource >= 0) {
                        selectTarget(dragSource);
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    } else if (swapped) {
                        selectTarget(dragSource);
                        performHapticFeedback(HapticFeedbackConstants.CONFIRM);
                    }
                }
                landing = draggingSwap;
                overshoot = new float[2];   // the frame springs back from any stretch
                pointer = -1;
                touchMode = MODE_NONE;
                activeCorner = WorkspaceGeometry.CORNER_NONE;
                dragSource = -1;
                draggingSwap = false;
                performClick();
                refresh();
                return true;
            }
            default:
                return true;
        }
    }

    @Override public boolean performClick() { super.performClick(); return true; }
}
