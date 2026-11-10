package io.folddeck.spike;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
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

    private static final int MODE_NONE = 0, MODE_EDGE = 1, MODE_PAN_OR_SWAP = 2, MODE_FALLBACK_1D = 3;

    interface Listener {
        float value(int target);
        void change(int target, float fraction);
        boolean horizontal(int target);
        void beginResize();
        /** The dividers that can move in the layout on screen now. */
        WorkspaceGeometry.Edge[] edges();
        /** Moves {@code edge} {@code travelPx} along its axis from where the drag began. */
        void resizeEdge(WorkspaceGeometry.Edge edge, float travelPx);
        /** Moves {@code from} into {@code to}'s place; false when that changes nothing. */
        boolean swap(int from, int to, float dropX, InputDeckLayout.Box toBox);
        /** The bar, held over the monitor it sits beside, at {@code x}: move it to that half. */
        boolean barSide(float x, InputDeckLayout.Box monitor);
        void reset();
        void finish(boolean save);
    }

    private final Listener listener;
    private final View[] targets;
    private final TextView[] tabs = new TextView[NAMES.length];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** The dragged pane's glass sheen, on a unit square scaled to the pane. */
    private final Paint sheen = new Paint();
    {
        sheen.setShader(new android.graphics.LinearGradient(0f, 0f, 1f, 1f,
                new int[]{0x4DFFFFFF, 0x14FFFFFF, 0x00FFFFFF, 0x1FFFFFFF},
                new float[]{0f, 0.35f, 0.6f, 1f}, android.graphics.Shader.TileMode.CLAMP));
    }
    private final RectF box = new RectF();
    private final RectF animBox = new RectF();
    private final RectF tempBox = new RectF();
    private final RectF dragSourceRect = new RectF();
    private final RectF[] badges = {new RectF(), new RectF(), new RectF(), new RectF(), new RectF(), new RectF()};
    private int placed;
    private final int[] origin = new int[2], location = new int[2];
    private final SeekBar slider;
    private final TextView hint;
    private final LinearLayout panel;
    private final int touchSlop;
    private int selected, pointer = -1;
    private int touchMode = MODE_NONE;
    /** The divider held, as it was when grabbed, and how far the finger has taken it. */
    private WorkspaceGeometry.Edge activeEdge;
    private float travel, lastActual, stretchPx;
    private int dragSource = -1, lastUnder = -1, ghostPane = -1;
    private boolean draggingSwap, swapped, landing;
    private int gesture;

    /** Counts pane drags, so a listener can tell one drag from the next. */
    int gesture() { return gesture; }
    private boolean panelAtBottom;
    private float startX, startY, dragX, dragY, startValue;

    /** Follows a finger: no visible lag, no bounce. */
    private static final float[] TRACK = {0.1f, 1f};
    /** The selection frame (l, t, r, b), the dragged pane's ghost, and how lifted it is. */
    private final Spring[] frame = new Spring[4], ghost = new Spring[4];
    private final Spring lift = new Spring(Motion.SNAPPY[0], Motion.SNAPPY[1], 0.002f);
    private boolean framePlaced, moving;
    private long lastFrame;
    /** Whether the finger is past where the layout stops, and that has ticked. */
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

    /** The held edge's side of the frame follows the stretch. */
    private void stretch(RectF r) {
        if (activeEdge == null) return;
        float min = Ui.dp(getContext(), 24);
        switch (activeEdge.side) {
            case WorkspaceGeometry.LEFT: r.left = Math.min(r.left + stretchPx, r.right - min); break;
            case WorkspaceGeometry.RIGHT: r.right = Math.max(r.right + stretchPx, r.left + min); break;
            case WorkspaceGeometry.TOP: r.top = Math.min(r.top + stretchPx, r.bottom - min); break;
            default: r.bottom = Math.max(r.bottom + stretchPx, r.top + min); break;
        }
    }

    /**
     * Where the held edge is drawn: under the finger while the layout follows,
     * then with growing resistance past the point where it truly stops. The
     * difference is measured on the laid-out panes, never predicted.
     */
    private void track(WorkspaceGeometry.Edge[] edges) {
        stretchPx = 0f;
        if (touchMode != MODE_EDGE || activeEdge == null) return;
        WorkspaceGeometry.Edge now = find(edges, activeEdge.id());
        float actual = now != null ? now.pos : activeEdge.pos;
        float over = activeEdge.pos + travel - actual;
        stretchPx = rubber(over, Ui.dp(getContext(), 44));
        // Only a stop counts: while the layout is still catching up a frame
        // behind a fast finger, the edge is moving.
        boolean past = Math.abs(over) > Ui.dp(getContext(), 16) && Math.abs(actual - lastActual) < 0.5f;
        if (past && !pastLimit) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        if (past || Math.abs(over) < Ui.dp(getContext(), 8)) pastLimit = past;
        lastActual = actual;
    }

    private static WorkspaceGeometry.Edge find(WorkspaceGeometry.Edge[] edges, int id) {
        for (WorkspaceGeometry.Edge e : edges) if (e.id() == id) return e;
        return null;
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
                ? "Drag a divider to resize · drag a pane to swap · Done saves"
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
        int primarySelected = selected >= HEIGHT ? TRACKPAD : selected;
        long now = System.nanoTime();
        // After a rest the first step is one frame, not the whole idle gap.
        float dt = moving ? (now - lastFrame) / 1e9f : 1f / 60;
        lastFrame = now;
        boolean jump = !ValueAnimator.areAnimatorsEnabled();
        boolean animating = false;
        WorkspaceGeometry.Edge[] edges = listener.edges();
        track(edges);
        placed = 0;

        // 1. The other panes: a hairline and their name, nothing to grab.
        for (int i = 0; i < 4; i++) {
            if (i == primarySelected || !computeTargetRect(i, tempBox)) continue;
            paint.setColor(Ui.mix(Ui.LINE, Ui.ACCENT, 0.45f));
            paint.setStyle(Paint.Style.FILL);
            paint.setAlpha(10);
            canvas.drawRect(tempBox, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Ui.dp(c, 1f));
            paint.setAlpha(110);
            canvas.drawRect(tempBox, paint);
            drawBadge(canvas, tempBox, NAMES[i], false);
        }

        // 2. The selected pane: a frame on springs, so it glides between panes,
        //    stretches with a held edge past its limit and settles back on release.
        if (tabs[selected].isEnabled() && computeTargetRect(selected, box)) {
            boolean holding = touchMode == MODE_EDGE;
            if (holding) stretch(box);
            animating |= spring(frame, box, animBox, holding ? TRACK : Motion.SNAPPY, dt,
                    jump || !framePlaced);
            framePlaced = true;
            paint.setColor(Ui.ACCENT);
            paint.setAlpha(holding ? 28 : 16);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawRect(animBox, paint);
            paint.setAlpha(220);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Ui.dp(c, 1.75f));
            canvas.drawRect(animBox, paint);
            drawBadge(canvas, animBox, NAMES[selected], true);
        } else {
            framePlaced = false;
        }

        // 3. A grip on every divider that can move; the held one lights its whole line.
        for (WorkspaceGeometry.Edge e : edges) {
            boolean held = touchMode == MODE_EDGE && activeEdge != null && e.id() == activeEdge.id();
            boolean mine = e.target == selected || e.target == HEIGHT && selected >= HEIGHT;
            drawGrip(canvas, e, held ? stretchPx : 0f, held, mine);
        }

        // 4. The dragged pane, lifted under the finger; on release it lands in its slot.
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

            // Glass: a see-through tinted pane with a soft shadow, a diagonal
            // sheen and a bright rim, so the layout stays visible beneath it.
            float a = Math.min(1f, up);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Ui.SURFACE_HI);
            paint.setAlpha(Math.round(95 * a));
            paint.setShadowLayer(Ui.dp(c, 22) * a, 0f, Ui.dp(c, 10) * a, Math.round(110 * a) << 24);
            canvas.drawRect(tempBox, paint);
            paint.clearShadowLayer();
            canvas.save();
            canvas.translate(tempBox.left, tempBox.top);
            canvas.scale(tempBox.width(), tempBox.height());
            sheen.setAlpha(Math.round(255 * a));
            canvas.drawRect(0f, 0f, 1f, 1f, sheen);
            canvas.restore();
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(Ui.ACCENT);
            paint.setAlpha(Math.round(200 * a));
            paint.setStrokeWidth(Ui.dp(c, 1.5f));
            canvas.drawRect(tempBox, paint);
            float rim = Ui.dp(c, 1.5f);
            paint.setColor(0xFFFFFFFF);
            paint.setAlpha(Math.round(120 * a));
            paint.setStrokeWidth(Ui.dp(c, 1f));
            canvas.drawLine(tempBox.left + rim, tempBox.top + rim, tempBox.right - rim, tempBox.top + rim, paint);
            canvas.drawLine(tempBox.left + rim, tempBox.top + rim, tempBox.left + rim, tempBox.bottom - rim, paint);
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

    /**
     * A short solid bar across the middle of the divider, ringed in the ground
     * colour so it reads on any pane. Held, it grows and the whole line shows.
     */
    private void drawGrip(Canvas canvas, WorkspaceGeometry.Edge e, float offset, boolean held, boolean mine) {
        Context c = getContext();
        float across = e.pos + e.nudge + offset, mid = (e.from + e.to) * 0.5f;
        float span = Math.max(0f, e.to - e.from);
        float half = Math.min(Ui.dp(c, held ? 26 : 20), span * 0.3f);
        float thick = Ui.dp(c, held ? 3.5f : 2.5f), ring = Ui.dp(c, 2);
        boolean v = e.vertical();
        if (held) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Ui.ACCENT);
            paint.setAlpha(170);
            float line = Ui.dp(c, 1);
            if (v) canvas.drawRect(across - line, e.from, across + line, e.to, paint);
            else canvas.drawRect(e.from, across - line, e.to, across + line, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Ui.BG);
        paint.setAlpha(230);
        float a = thick + ring, b = half + ring;
        if (v) canvas.drawRect(across - a, mid - b, across + a, mid + b, paint);
        else canvas.drawRect(mid - b, across - a, mid + b, across + a, paint);
        paint.setColor(Ui.ACCENT);
        paint.setAlpha(held || mine ? 255 : 150);
        if (v) canvas.drawRect(across - thick, mid - half, across + thick, mid + half, paint);
        else canvas.drawRect(mid - half, across - thick, mid + half, across + thick, paint);
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
        // A pane nested in another (the split pad in the keys) would put two
        // names on one spot: step down past any already drawn.
        for (int i = 0; i < placed; i++) {
            RectF o = badges[i];
            if (cx - halfW < o.right && cx + halfW > o.left && cy - halfH < o.bottom && cy + halfH > o.top) {
                cy = o.bottom + halfH + Ui.dp(c, 4);
            }
        }
        if (placed < badges.length) badges[placed++].set(cx - halfW, cy - halfH, cx + halfW, cy + halfH);

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
                activeEdge = null;
                travel = 0f;
                dragSource = -1;
                draggingSwap = false;
                swapped = false;
                pastLimit = false;

                InputDeckLayout.Box[] boxes = primaryBoxes();
                WorkspaceGeometry.Edge[] edges = listener.edges();
                int hit = WorkspaceGeometry.hitEdge(edges, startX, startY, Ui.dp(c, 24));
                if (hit >= 0) {
                    activeEdge = edges[hit];
                    lastActual = activeEdge.pos;
                    if (selected != activeEdge.target) selectTarget(activeEdge.target);
                    touchMode = MODE_EDGE;
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

                if (touchMode == MODE_EDGE) {
                    travel = activeEdge.vertical() ? x - startX : y - startY;
                    listener.resizeEdge(activeEdge, travel);
                    slider.setProgress(Math.round(listener.value(selected) * 1000));
                    invalidate();
                    return true;
                }

                if (touchMode == MODE_PAN_OR_SWAP) {
                    if (!draggingSwap && Math.hypot(x - startX, y - startY) > touchSlop) {
                        draggingSwap = true;
                        gesture++;
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
                        } else if (dragSource == BAR && under == MONITOR
                                && listener.barSide(x, boxes[under])) {
                            swapped = true;
                            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
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
                pointer = -1;
                touchMode = MODE_NONE;   // the frame springs back from any stretch
                activeEdge = null;
                travel = 0f;
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
