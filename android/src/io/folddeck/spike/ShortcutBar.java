package io.folddeck.spike;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The shortcut deck: Omarchy shortcuts in the space the 16:9 desktop leaves over.
 *
 * An accent-bordered panel with individually raised tabs separated by a restrained
 * 6dp channel. Two shapes, chosen by MainActivity from the pane:
 *
 *  row     under the desktop (upright screens): one line of buttons, swiped sideways
 *  column  left of the desktop (landscape): two columns — one when the deck is
 *          squeezed narrow — scrolled down. Also the band under the desktop when
 *          a real keyboard frees the space, with as many columns as fit
 *
 * Buttons stretch to fill the deck when there are few and keep a minimum size
 * when there are many; the spare space goes to the buttons, the overflow to the
 * scroll.
 *
 * DIRECT/TRACKPAD is always first and + always last; a long-press on either opens
 * the editor. Long-press a shortcut to pick it up: the others slide aside live as
 * it passes over them, it saves where it is dropped, and everything slides back
 * if it is let go outside the deck.
 */
class ShortcutBar extends FrameLayout {

    interface Listener {
        void onResize();
        void onAppSettings();
        void onShortcut(Shortcuts.Item item);
        void onEditShortcuts();
        /** Flip the touch mode; true if it is now TRACKPAD. */
        boolean onToggleTouchMode();
        void onReordered(List<Shortcuts.Item> items);
    }

    /** Row deck height bounds; the narrowest side band worth a column; the width
     *  below which the column goes single and small; all in dp. */
    static final int ROW_MIN_DP = 52, ROW_MAX_DP = 72, COLUMN_MIN_DP = 48, COMPACT_BELOW_DP = 176;
    private static final int CELL_MIN_W_DP = 104, CELL_MIN_H_DP = 52, BORDER_DP = 1;

    private final Listener listener;
    private final HorizontalScrollView row;
    private final ScrollView column;
    private final Grid grid;
    private final LinearLayout tools;
    private final Paint border = new Paint();
    private List<Shortcuts.Item> items;
    private TextView modeCell;
    private boolean vertical, compact, trackpad, twoRows;
    /** Which sides get the accent border: left, top, right, bottom. A side against
     *  the app frame is left open, or the two 2dp lines would read as one fat one. */
    private final boolean[] edges = {true, true, true, true};

    ShortcutBar(Context c, Listener listener) {
        super(c);
        this.listener = listener;
        setWillNotDraw(false);
        // Taps on the deck must never fall through to the pointer pad and click
        // on the laptop.
        setClickable(true);
        setOnLongClickListener(v -> edit());

        row = new HorizontalScrollView(c);
        row.setFillViewport(true);
        row.setHorizontalScrollBarEnabled(false);
        column = new ScrollView(c);
        column.setFillViewport(true);
        column.setVerticalScrollBarEnabled(false);
        addView(row, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        addView(column, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        grid = new Grid(c);
        tools = new LinearLayout(c);
        tools.setGravity(Gravity.CENTER);
        addView(tools);
        styleTools();
        setBackground(Ui.floatingPanel(Ui.SURFACE, Ui.SEPARATOR, 0.5f, c));
        applyEdges();
        attachGrid();
    }

    void setItems(List<Shortcuts.Item> items) {
        this.items = items;
        rebuild();
    }

    void setTrackpadMode(boolean enabled) {
        trackpad = enabled;
        styleModeCell();
    }

    void setVertical(boolean on) {
        if (vertical == on) return;
        vertical = on;
        attachGrid();
    }

    /** Two rows inside one HorizontalScrollView, so both rows move together. */
    void setTwoRows(boolean on) {
        if (twoRows == on) return;
        twoRows = on;
        if (on && vertical) {
            vertical = false;
            attachGrid();
        }
        requestLayout();
    }

    /** A squeezed side deck: one column of smaller labels instead of two. */
    void setCompact(boolean on) {
        if (compact == on) return;
        compact = on;
        // Resizing crosses this threshold repeatedly. Keep the controls and drag state alive.
        for (View v : grid.order) {
            TextView t = (TextView) v;
            t.setTextSize(compact ? Ui.T_MICRO : Ui.T_LABEL);
            int pad = Ui.dp(getContext(), compact ? 3 : 10);
            t.setPadding(pad, 0, pad, 0);
        }
        styleModeCell();
    }

    void setEdges(boolean left, boolean top, boolean right, boolean bottom) {
        boolean[] next = {left, top, right, bottom};
        if (java.util.Arrays.equals(edges, next)) return;
        System.arraycopy(next, 0, edges, 0, 4);
        applyEdges();
    }

    private void applyEdges() {
        int w = Ui.dp(getContext(), 4);
        setPadding(w, w, w, w);
        invalidate();
    }

    private void attachGrid() {
        if (grid.getParent() != null) ((ViewGroup) grid.getParent()).removeView(grid);
        (vertical ? column : row).addView(grid, new LayoutParams(
                vertical ? LayoutParams.MATCH_PARENT : LayoutParams.WRAP_CONTENT,
                vertical ? LayoutParams.WRAP_CONTENT : LayoutParams.MATCH_PARENT));
        row.setVisibility(vertical ? GONE : VISIBLE);
        column.setVisibility(vertical ? VISIBLE : GONE);
    }

    /** Recreate the buttons, e.g. after a theme change: they bake colours in. */
    void rebuild() {
        if (items == null) return;
        setBackground(Ui.floatingPanel(Ui.SURFACE, Ui.SEPARATOR, 0.5f, getContext()));
        styleTools();
        // Kept across the rebuild, so a theme change does not throw the deck back.
        final int sx = row.getScrollX(), sy = column.getScrollY();

        List<View> cells = new ArrayList<>();
        modeCell = cell("", v -> {
            trackpad = listener.onToggleTouchMode();
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            styleModeCell();
        });
        styleModeCell();
        modeCell.setOnLongClickListener(v -> edit());
        cells.add(modeCell);

        for (Shortcuts.Item it : items) {
            boolean system = it.label.equals("Menu") && it.chord.equals("SUPER+SPACE");
            TextView t = cell(system ? "System" : it.label, v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                listener.onShortcut(it);
            });
            String label = system ? "System" : it.label;
            android.text.SpannableString caption = new android.text.SpannableString(
                    label + "\n" + (system ? "LAPTOP" : it.chord.replace("SUPER", "◆").replace("SHIFT", "⇧")));
            caption.setSpan(new android.text.style.RelativeSizeSpan(0.66f), label.length() + 1,
                    caption.length(), 0);
            caption.setSpan(new android.text.style.ForegroundColorSpan(Ui.TEXT_FAINT), label.length() + 1,
                    caption.length(), 0);
            t.setText(caption);
            t.setContentDescription("Laptop: " + label + ", " + it.chord);
            t.setTag(it);
            t.setOnLongClickListener(grid::startDrag);
            cells.add(t);
        }

        TextView add = cell("+", v -> edit());
        add.setTextColor(Ui.ACCENT);
        add.setOnLongClickListener(v -> edit());
        cells.add(add);

        grid.setCells(cells);
        post(() -> {
            row.scrollTo(sx, 0);
            column.scrollTo(0, sy);
        });
    }

    private void styleTools() {
        if (tools == null) return;
        tools.removeAllViews();
        tools.setBackground(Ui.rounded(Ui.mix(Ui.SURFACE, Ui.ACCENT, 0.06f),
                Ui.mix(Ui.LINE, Ui.ACCENT, 0.2f), 9, getContext()));
        tools.addView(tool("↔", "Resize", "Resize workspace", listener::onResize));
        tools.addView(tool("", "App", "Folddeck app settings", listener::onAppSettings));
    }

    private View tool(String icon, String label, String description, Runnable action) {
        Context c = getContext();
        LinearLayout button = new LinearLayout(c);
        button.setOrientation(LinearLayout.VERTICAL);
        button.setGravity(Gravity.CENTER);
        TextView glyph = Ui.text(c, icon, 17, Ui.ACCENT);
        if (label.equals("App")) glyph.setTypeface(Ui.ICONS);
        glyph.setGravity(Gravity.CENTER);
        glyph.setIncludeFontPadding(false);
        button.addView(glyph);
        TextView text = Ui.text(c, label, 10, Ui.TEXT_DIM);
        text.setTypeface(Ui.MONO);
        text.setGravity(Gravity.CENTER);
        text.setIncludeFontPadding(false);
        button.addView(text);
        button.setContentDescription(description);
        button.setFocusable(true);
        button.setBackground(Ui.pressable(Ui.rounded(android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT, 8, c)));
        button.setOnClickListener(v -> action.run());
        Motion.press(button);
        return button;
    }

    @Override protected void onMeasure(int ws, int hs) {
        int w = MeasureSpec.getSize(ws), h = MeasureSpec.getSize(hs);
        boolean stacked = vertical && w < Ui.dp(getContext(), 176);
        tools.setOrientation(stacked ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        for (int i = 0; i < tools.getChildCount(); i++) {
            View child = tools.getChildAt(i);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) child.getLayoutParams();
            int cw = stacked ? LayoutParams.MATCH_PARENT : 0;
            int ch = stacked ? 0 : LayoutParams.MATCH_PARENT;
            if (lp.width != cw || lp.height != ch || lp.weight != 1f) {
                lp.width = cw; lp.height = ch; lp.weight = 1f;
                child.setLayoutParams(lp);
            }
        }
        int gap = Ui.dp(getContext(), 6);
        int inset = getPaddingLeft() + getPaddingRight();
        boolean top = vertical || twoRows;
        int toolW = top ? Math.max(0, w - inset) : Ui.dp(getContext(), 112);
        int toolH = top ? Ui.dp(getContext(), stacked ? 96 : 48) : Math.max(0, h - inset);
        size(tools, toolW, toolH, 0, 0);
        int dx = top ? 0 : toolW + gap, dy = top ? toolH + gap : 0;
        size(row, Math.max(0, w - inset - dx), Math.max(0, h - inset - dy), dx, dy);
        size(column, Math.max(0, w - inset - dx), Math.max(0, h - inset - dy), dx, dy);
        super.onMeasure(ws, hs);
    }

    private void size(View v, int w, int h, int x, int y) {
        LayoutParams lp = (LayoutParams) v.getLayoutParams();
        if (lp.width == w && lp.height == h && lp.leftMargin == x && lp.topMargin == y) return;
        lp.width = w; lp.height = h; lp.leftMargin = x; lp.topMargin = y;
        v.setLayoutParams(lp);
    }

    private TextView cell(String label, View.OnClickListener onClick) {
        Context c = getContext();
        TextView t = Ui.text(c, label, compact ? Ui.T_MICRO : Ui.T_LABEL, Ui.TEXT);
        t.setTypeface(Ui.MONO);
        t.setMaxLines(2);
        t.setFocusable(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setGravity(Gravity.CENTER);
        t.setLineSpacing(0, 1f);
        int pad = Ui.dp(c, compact ? 3 : 10);
        t.setPadding(pad, 0, pad, 0);
        // The same keycap as the keyboard and trackpad buttons: one deck, one look.
        t.setBackground(Ui.pressable(Ui.rounded(Ui.SURFACE_HI, Ui.SEPARATOR, 8, c)));
        Motion.press(t);
        t.setOnClickListener(onClick);
        return t;
    }

    /** Restyled in place rather than rebuilt, so toggling does not lose the scroll. */
    private void styleModeCell() {
        if (modeCell == null) return;
        modeCell.setText(trackpad ? (compact ? "TRACK" : "TRACKPAD") : "DIRECT");
        modeCell.setTypeface(Ui.MONO_BOLD);
        modeCell.setTextColor(trackpad ? Ui.ACCENT : Ui.TEXT_DIM);
        modeCell.setContentDescription("Screen touch mode: " + (trackpad ? "trackpad" : "direct"));
        modeCell.setBackground(Ui.pressable(Ui.rounded(trackpad ? Ui.SELECTED : Ui.SURFACE_HI,
                trackpad ? Ui.ACCENT : Ui.LINE, 8, getContext())));
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        int w = Ui.dp(getContext(), BORDER_DP), W = getWidth(), H = getHeight();
        border.setColor(Ui.mix(Ui.BG, Ui.ACCENT, 0.30f));
        if (edges[0]) canvas.drawRect(0, 0, w, H, border);
        if (edges[1]) canvas.drawRect(0, 0, W, w, border);
        if (edges[2]) canvas.drawRect(W - w, 0, W, H, border);
        if (edges[3]) canvas.drawRect(0, H - w, W, H, border);
    }

    private boolean edit() {
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        listener.onEditShortcuts();
        return true;
    }

    // ------------------------------------------------------------------ //
    /**
     * The buttons, laid out in slots by {@link #order} rather than by child index,
     * so a reorder is a list edit plus a layout pass.
     *
     * Every pass that moves a button animates it from where it was (FLIP: note the
     * old position, lay out at the new, offset back by the difference, animate the
     * offset to zero) with a slight overshoot and a scale pop, which is what makes
     * the neighbours visibly jump aside as a dragged button passes over them.
     */
    private final class Grid extends ViewGroup {

        final List<View> order = new ArrayList<>();
        final Paint paint = new Paint();
        final int line;
        int cols = 1, cellW, cellH;
        boolean animateNext;
        View dragging;
        List<View> before;

        Grid(Context c) {
            super(c);
            line = Ui.dp(c, 6);
            setOnDragListener((v, e) -> onDrag(e));
        }

        void setCells(List<View> cells) {
            removeAllViews();
            order.clear();
            order.addAll(cells);
            for (View v : cells) addView(v);
        }

        int rows() {
            return (order.size() + cols - 1) / cols;
        }

        @Override
        protected void onMeasure(int ws, int hs) {
            int n = Math.max(1, order.size());
            int minW = Ui.dp(getContext(), CELL_MIN_W_DP), minH = Ui.dp(getContext(), CELL_MIN_H_DP);
            int w, h;
            if (!vertical) {
                h = MeasureSpec.getSize(hs);
                boolean doubleRow = twoRows && h >= 2 * Ui.dp(getContext(), 44) + line;
                cols = ShortcutGrid.columns(n, doubleRow);
                int shelfRows = ShortcutGrid.rows(n, doubleRow);
                int natural = cols * minW + (cols - 1) * line;
                w = MeasureSpec.getMode(ws) == MeasureSpec.EXACTLY
                        ? Math.max(MeasureSpec.getSize(ws), natural) : natural;
                cellW = (w - (cols - 1) * line) / cols;
                cellH = (h - (shelfRows - 1) * line) / shelfRows;
            } else {
                // Two columns beside the desktop; a side deck is never wide enough
                // for more. The full-width band under it when a real keyboard has
                // replaced the on-screen one is, and takes as many as fit.
                cols = compact ? 1 : Math.max(2, MeasureSpec.getSize(ws) / (2 * minW));
                w = MeasureSpec.getSize(ws);
                int rows = rows();
                int natural = rows * minH + (rows - 1) * line;
                h = MeasureSpec.getMode(hs) == MeasureSpec.EXACTLY
                        ? Math.max(MeasureSpec.getSize(hs), natural) : natural;
                cellW = (w - (cols - 1) * line) / cols;
                cellH = (h - (rows - 1) * line) / rows;
            }
            for (View v : order) {
                v.measure(MeasureSpec.makeMeasureSpec(cellW, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(cellH, MeasureSpec.EXACTLY));
            }
            setMeasuredDimension(w, h);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int rows = rows();
            for (int slot = 0; slot < order.size(); slot++) {
                View v = order.get(slot);
                int col = slot % cols, rw = slot / cols;
                int x = col * (cellW + line), y = rw * (cellH + line);
                // The last column and row absorb the pixels integer division left over.
                int right = col == cols - 1 ? r - l : x + cellW;
                int bottom = rw == rows - 1 ? b - t : y + cellH;
                int oldX = v.getLeft(), oldY = v.getTop();
                boolean placed = v.getWidth() > 0;
                v.layout(x, y, right, bottom);
                if (animateNext && android.animation.ValueAnimator.areAnimatorsEnabled()
                        && placed && (oldX != x || oldY != y)) {
                    v.setTranslationX(v.getTranslationX() + oldX - x);
                    v.setTranslationY(v.getTranslationY() + oldY - y);
                    v.animate().translationX(0).translationY(0).scaleX(1).scaleY(1)
                            .setDuration(220).setInterpolator(new android.view.animation.PathInterpolator(
                                    0.16f, 1f, 0.3f, 1f)).start();
                }
            }
            animateNext = false;
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            // The 1px board lines are the gaps between cells showing this colour;
            // an empty slot at the end of an odd column is painted back to ground.
            paint.setColor(Ui.SURFACE);
            for (int slot = order.size(); slot < rows() * cols; slot++) {
                int x = (slot % cols) * (cellW + line), y = (slot / cols) * (cellH + line);
                canvas.drawRect(x, y, getWidth(), getHeight(), paint);
            }
            super.dispatchDraw(canvas);
        }

        boolean startDrag(View v) {
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            dragging = v;
            before = new ArrayList<>(order);
            v.startDragAndDrop(null, new View.DragShadowBuilder(v), v, 0);
            v.setAlpha(0.3f);
            return true;
        }

        private boolean onDrag(DragEvent e) {
            switch (e.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED:
                    return dragging != null && e.getLocalState() == dragging;
                case DragEvent.ACTION_DRAG_LOCATION: {
                    // Shortcuts only move between DIRECT (first) and + (last).
                    int slot = Math.max(1, Math.min(slotAt(e.getX(), e.getY()), order.size() - 2));
                    int current = order.indexOf(dragging);
                    if (slot != current) {
                        order.remove(current);
                        order.add(slot, dragging);
                        animateNext = true;
                        requestLayout();
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    }
                    autoScroll(e.getX(), e.getY());
                    return true;
                }
                case DragEvent.ACTION_DROP: {
                    items.clear();
                    for (View v : order) {
                        if (v.getTag() instanceof Shortcuts.Item) items.add((Shortcuts.Item) v.getTag());
                    }
                    listener.onReordered(items);
                    return true;
                }
                case DragEvent.ACTION_DRAG_ENDED:
                    if (dragging == null) return true;
                    dragging.setAlpha(1f);
                    if (!e.getResult()) {       // let go outside the deck: put it all back
                        order.clear();
                        order.addAll(before);
                        animateNext = true;
                        requestLayout();
                    }
                    dragging = null;
                    return true;
                default:
                    return true;
            }
        }

        private int slotAt(float x, float y) {
            int col = Math.min(cols - 1, (int) (x / (cellW + line)));
            int rw = (int) (y / (cellH + line));
            return rw * cols + Math.max(0, col);
        }

        /** Nudge the scroll when a drag hovers near the deck's visible end. */
        private void autoScroll(float x, float y) {
            int edge = Ui.dp(getContext(), 48), step = Ui.dp(getContext(), 24);
            if (vertical) {
                float vy = y - column.getScrollY();
                if (vy < edge) column.scrollBy(0, -step);
                else if (vy > column.getHeight() - edge) column.scrollBy(0, step);
            } else {
                float vx = x - row.getScrollX();
                if (vx < edge) row.scrollBy(-step, 0);
                else if (vx > row.getWidth() - edge) row.scrollBy(step, 0);
            }
        }
    }
}
