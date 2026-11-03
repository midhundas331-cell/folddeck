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
import android.view.animation.PathInterpolator;
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
        void resizeCorner(int target, int corner, float dxPx, float dyPx, float overlayW, float overlayH);
        void swap(int from, int to, float dropX, InputDeckLayout.Box toBox);
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
    private final RectF animStartBox = new RectF();
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
    private int dragSource = -1, swapTarget = -1;
    private boolean draggingSwap;
    private boolean panelAtBottom;
    private float startX, startY, dragX, dragY, startValue;
    private ValueAnimator boxAnimator;

    ResizeOverlay(Context c, View[] targets, Listener listener) {
        super(c);
        this.targets = targets;
        this.listener = listener;
        this.touchSlop = ViewConfiguration.get(c).getScaledTouchSlop();
        setWillNotDraw(false);
        setClickable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        panel = new LinearLayout(c);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(Ui.dp(c, 12), Ui.dp(c, 6), Ui.dp(c, 12), Ui.dp(c, 8));
        panel.setBackground(Ui.floatingPanel(Ui.SURFACE, Ui.mix(Ui.LINE, Ui.ACCENT, 0.35f), 1, c));
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
            animateHighlightToSelected();
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
                selectTarget(index, true);
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
        android.graphics.drawable.GradientDrawable track = Ui.rounded(Ui.LINE, Ui.LINE, 2, c);
        android.graphics.drawable.ClipDrawable progress = new android.graphics.drawable.ClipDrawable(
                Ui.rounded(Ui.ACCENT, Ui.ACCENT, 2, c), Gravity.LEFT,
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
        android.graphics.drawable.GradientDrawable thumb = Ui.rounded(Ui.ACCENT, Ui.SURFACE, 10, c);
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
                    if (boxAnimator != null) boxAnimator.cancel();
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

    private void selectTarget(int index, boolean animate) {
        if (index < 0 || index >= tabs.length) return;
        if (animate) captureCurrentHighlight();
        selected = index;
        refresh();
        if (animate) startHighlightAnimation();
    }

    void animateHighlightToSelected() {
        captureCurrentHighlight();
        post(this::startHighlightAnimation);
    }

    private void captureCurrentHighlight() {
        if (!animBox.isEmpty()) {
            animStartBox.set(animBox);
        } else if (computeTargetRect(selected, tempBox)) {
            animStartBox.set(tempBox);
        }
    }

    private void startHighlightAnimation() {
        if (boxAnimator != null) boxAnimator.cancel();
        if (animStartBox.isEmpty() || !ValueAnimator.areAnimatorsEnabled()) {
            animStartBox.setEmpty();
            invalidate();
            return;
        }
        boxAnimator = ValueAnimator.ofFloat(0f, 1f);
        boxAnimator.setDuration(220);
        boxAnimator.setInterpolator(new PathInterpolator(0.16f, 1f, 0.30f, 1f));
        boxAnimator.addUpdateListener(a -> invalidate());
        boxAnimator.start();
    }

    private TextView action(Context c, String label, Runnable run) {
        TextView button = Ui.text(c, label, 13, Ui.TEXT_DIM);
        button.setGravity(Gravity.CENTER);
        button.setPadding(Ui.dp(c, 12), 0, Ui.dp(c, 12), 0);
        button.setMinimumHeight(Ui.dp(c, 48));
        button.setFocusable(true);
        button.setBackground(Ui.pressable(Ui.rounded(Ui.SURFACE, Ui.SURFACE, 8, c)));
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
            tabs[i].setTextColor(i == selected ? Ui.onAccent() : Ui.TEXT_DIM);
            tabs[i].setBackground(Ui.pressable(Ui.rounded(i == selected ? Ui.ACCENT : Ui.SURFACE_HI,
                    i == selected ? Ui.ACCENT : Ui.SEPARATOR, 8, getContext())));
        }
        slider.setEnabled(tabs[selected].isEnabled());
        slider.setContentDescription(NAMES[selected] + " size");
        slider.setProgress(Math.round(listener.value(selected) * 1000));
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
                int x = Math.round(location[0] - origin[0] + v.getTranslationX());
                int y = Math.round(location[1] - origin[1] + v.getTranslationY());
                boxes[i] = new InputDeckLayout.Box(x, y, v.getWidth(), v.getHeight());
            } else {
                boxes[i] = new InputDeckLayout.Box(0, 0, 0, 0);
            }
        }
        return boxes;
    }

    @Override protected void onDraw(Canvas canvas) {
        Context c = getContext();
        float radius = Ui.dp(c, 10);
        int primarySelected = selected >= HEIGHT ? TRACKPAD : selected;

        // 1. Draw subtle outlines, corner ticks, and labels for unselected primary panes.
        for (int i = 0; i < 4; i++) {
            if (i == primarySelected || !computeTargetRect(i, tempBox)) continue;
            boolean isSwapTarget = draggingSwap && swapTarget == i;
            paint.setColor(isSwapTarget ? Ui.ACCENT : Ui.mix(Ui.LINE, Ui.ACCENT, 0.45f));
            paint.setStyle(Paint.Style.FILL);
            paint.setAlpha(isSwapTarget ? 38 : 10);
            canvas.drawRoundRect(tempBox, radius, radius, paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Ui.dp(c, isSwapTarget ? 2.5f : 1f));
            paint.setAlpha(isSwapTarget ? 235 : 110);
            canvas.drawRoundRect(tempBox, radius, radius, paint);

            drawCornerBrackets(canvas, tempBox, Ui.dp(c, 11), Ui.dp(c, 2f),
                    isSwapTarget ? 235 : 130, WorkspaceGeometry.CORNER_NONE, false);

            if (isSwapTarget) {
                drawBadge(canvas, tempBox, "⇄ Swap with " + NAMES[i], true);
            } else {
                drawBadge(canvas, tempBox, NAMES[i], false);
            }
        }

        // 2. Draw the selected pane with smooth interpolated bounds and 4 interactive corner handles.
        if (tabs[selected].isEnabled() && computeTargetRect(selected, box)) {
            if (boxAnimator != null && boxAnimator.isRunning() && !animStartBox.isEmpty()) {
                float f = (float) boxAnimator.getAnimatedValue();
                animBox.set(
                        animStartBox.left + (box.left - animStartBox.left) * f,
                        animStartBox.top + (box.top - animStartBox.top) * f,
                        animStartBox.right + (box.right - animStartBox.right) * f,
                        animStartBox.bottom + (box.bottom - animStartBox.bottom) * f);
            } else {
                animBox.set(box);
            }

            paint.setColor(Ui.ACCENT);
            paint.setAlpha(touchMode == MODE_CORNER ? 28 : 16);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawRoundRect(animBox, radius, radius, paint);

            paint.setAlpha(220);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Ui.dp(c, 1.75f));
            canvas.drawRoundRect(animBox, radius, radius, paint);

            drawCornerBrackets(canvas, animBox, Ui.dp(c, 20), Ui.dp(c, 3.5f),
                    255, touchMode == MODE_CORNER ? activeCorner : WorkspaceGeometry.CORNER_NONE, true);

            String label = NAMES[selected] + (selected < HEIGHT ? " · ⤡ corners / ⇄ drag"
                    : (listener.horizontal(selected) ? " ↔" : " ↕"));
            drawBadge(canvas, animBox, label, true);
        }

        // 3. Draw floating ghost card when dragging a pane to swap positions.
        if (draggingSwap && dragSource >= 0 && !dragSourceRect.isEmpty()) {
            float dx = dragX - startX, dy = dragY - startY;
            tempBox.set(dragSourceRect);
            tempBox.offset(dx, dy);
            float insetX = -tempBox.width() * 0.015f, insetY = -tempBox.height() * 0.015f;
            tempBox.inset(insetX, insetY);

            paint.setColor(Ui.SURFACE_HI);
            paint.setAlpha(185);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawRoundRect(tempBox, radius, radius, paint);

            paint.setColor(Ui.ACCENT);
            paint.setAlpha(245);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Ui.dp(c, 2.2f));
            canvas.drawRoundRect(tempBox, radius, radius, paint);

            drawBadge(canvas, tempBox, NAMES[dragSource]
                    + (swapTarget >= 0 ? " ⇄ " + NAMES[swapTarget] : " · drag to another pane"), true);
        }
    }

    private void drawCornerBrackets(Canvas canvas, RectF r, float arm, float stroke,
                                    int alpha, int highlightedCorner, boolean nodes) {
        Context c = getContext();
        float ax = Math.min(arm, r.width() * 0.32f);
        float ay = Math.min(arm, r.height() * 0.32f);
        float cr = Math.min(Ui.dp(c, 8), Math.min(ax, ay) * 0.5f);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);

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
                canvas.drawCircle(x, y, Ui.dp(c, 20), paint);
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
                canvas.drawCircle(x, y, nodeR, paint);
                paint.setColor(Ui.onAccent());
                paint.setAlpha(alpha);
                canvas.drawCircle(x, y, nodeR * 0.42f, paint);
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
        canvas.drawRoundRect(cx - halfW, cy - halfH, cx + halfW, cy + halfH, halfH, halfH, paint);

        if (!accent) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Ui.dp(c, 1));
            paint.setColor(Ui.mix(Ui.LINE, Ui.ACCENT, 0.4f));
            paint.setAlpha(180);
            canvas.drawRoundRect(cx - halfW, cy - halfH, cx + halfW, cy + halfH, halfH, halfH, paint);
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
                swapTarget = -1;
                draggingSwap = false;

                InputDeckLayout.Box[] boxes = primaryBoxes();
                int hitCorner = WorkspaceGeometry.hitCorner(boxes, selected, startX, startY,
                        Ui.dp(c, 38), Ui.dp(c, 20));
                if (hitCorner >= 0) {
                    int target = hitCorner >> 4;
                    int corner = hitCorner & 0xF;
                    if (selected != target) {
                        selectTarget(target, false);
                    }
                    if (boxAnimator != null) boxAnimator.cancel();
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
                    listener.resizeCorner(selected, activeCorner,
                            x - startX, y - startY, getWidth(), getHeight());
                    slider.setProgress(Math.round(listener.value(selected) * 1000));
                    invalidate();
                    return true;
                }

                if (touchMode == MODE_PAN_OR_SWAP) {
                    if (!draggingSwap && Math.hypot(x - startX, y - startY) > touchSlop) {
                        draggingSwap = true;
                    }
                    if (draggingSwap) {
                        InputDeckLayout.Box[] boxes = primaryBoxes();
                        int under = WorkspaceGeometry.hitTarget(boxes, x, y);
                        int nextSwap = (under >= 0 && under != dragSource) ? under : -1;
                        if (nextSwap != swapTarget) {
                            swapTarget = nextSwap;
                            if (swapTarget >= 0) {
                                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                            }
                        }
                        if (swapTarget < 0 && selected != dragSource) {
                            boolean horizontal = listener.horizontal(selected);
                            float delta = horizontal ? (x - startX) / Math.max(1f, getWidth())
                                    : (y - startY) / Math.max(1f, getHeight());
                            listener.change(selected, Motion.clamp(startValue + delta, 0f, 1f));
                            slider.setProgress(Math.round(listener.value(selected) * 1000));
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
                        selectTarget(dragSource, true);
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    } else if (draggingSwap && swapTarget >= 0 && swapTarget != dragSource) {
                        InputDeckLayout.Box[] boxes = primaryBoxes();
                        selectTarget(dragSource, false);
                        captureCurrentHighlight();
                        listener.swap(dragSource, swapTarget, dragX, boxes[swapTarget]);
                        performHapticFeedback(HapticFeedbackConstants.CONFIRM);
                        post(() -> {
                            startHighlightAnimation();
                            refresh();
                        });
                    }
                }
                pointer = -1;
                touchMode = MODE_NONE;
                activeCorner = WorkspaceGeometry.CORNER_NONE;
                dragSource = -1;
                swapTarget = -1;
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
