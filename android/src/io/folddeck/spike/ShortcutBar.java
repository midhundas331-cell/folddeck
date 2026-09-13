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
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The shortcut deck: Omarchy shortcuts in the space the 16:9 desktop leaves over.
 *
 * An accent-bordered panel with its buttons flush inside, split by 1px lines like
 * keys on a board. Two shapes, chosen by MainActivity from the pane:
 *
 *  row     under the desktop (upright screens): one line of buttons, swiped sideways
 *  column  left of the desktop (landscape): two columns — one when the deck is
 *          squeezed narrow — scrolled down
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
        void onShortcut(Shortcuts.Item item);
        void onEditShortcuts();
        /** Flip the touch mode; true if it is now TRACKPAD. */
        boolean onToggleTouchMode();
        void onReordered(List<Shortcuts.Item> items);
    }

    /** Row deck height bounds; the narrowest side band worth a column; the width
     *  below which the column goes single and small; all in dp. */
    static final int ROW_MIN_DP = 52, ROW_MAX_DP = 72, COLUMN_MIN_DP = 48, COMPACT_BELOW_DP = 176;
    private static final int CELL_MIN_W_DP = 104, CELL_MIN_H_DP = 52, BORDER_DP = 2;

    private final Listener listener;
    private final HorizontalScrollView row;
    private final ScrollView column;
    private final Grid grid;
    private final Paint border = new Paint();
    private List<Shortcuts.Item> items;
    private TextView modeCell;
    private boolean vertical, compact, trackpad;
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
        applyEdges();
        attachGrid();
    }

    void setItems(List<Shortcuts.Item> items) {
        this.items = items;
        rebuild();
    }

    void setVertical(boolean on) {
        if (vertical == on) return;
        vertical = on;
        attachGrid();
    }

    /** A squeezed side deck: one column of smaller labels instead of two. */
    void setCompact(boolean on) {
        if (compact == on) return;
        compact = on;
        rebuild();
    }

    void setEdges(boolean left, boolean top, boolean right, boolean bottom) {
        boolean[] next = {left, top, right, bottom};
        if (java.util.Arrays.equals(edges, next)) return;
        System.arraycopy(next, 0, edges, 0, 4);
        applyEdges();
    }

    private void applyEdges() {
        int w = Ui.dp(getContext(), BORDER_DP);
        setPadding(edges[0] ? w : 0, edges[1] ? w : 0, edges[2] ? w : 0, edges[3] ? w : 0);
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
            TextView t = cell(it.label, v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                listener.onShortcut(it);
            });
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

    private TextView cell(String label, View.OnClickListener onClick) {
        Context c = getContext();
        TextView t = Ui.text(c, label, compact ? Ui.T_MICRO : Ui.T_LABEL, Ui.TEXT);
        if (compact) {
            t.setMaxLines(2);
        } else {
            t.setSingleLine(true);
        }
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setGravity(Gravity.CENTER);
        t.setLineSpacing(0, 1f);
        int pad = Ui.dp(c, compact ? 3 : 10);
        t.setPadding(pad, 0, pad, 0);
        t.setBackground(Ui.pressable(Ui.square(Ui.SURFACE)));
        t.setOnClickListener(onClick);
        return t;
    }

    /** Restyled in place rather than rebuilt, so toggling does not lose the scroll. */
    private void styleModeCell() {
        modeCell.setText(trackpad ? (compact ? "TRACK" : "TRACKPAD") : "DIRECT");
        modeCell.setTypeface(Ui.FONT_BOLD);
        modeCell.setTextColor(trackpad ? Ui.ACCENT : Ui.TEXT_DIM);
        modeCell.setBackground(Ui.pressable(Ui.square(trackpad ? Ui.SELECTED : Ui.SURFACE)));
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        canvas.drawColor(Ui.BG);
        super.dispatchDraw(canvas);
        int w = Ui.dp(getContext(), BORDER_DP), W = getWidth(), H = getHeight();
        border.setColor(Ui.ACCENT);
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
            line = Math.max(1, Ui.dp(c, 1));
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
                cols = n;
                h = MeasureSpec.getSize(hs);
                int natural = n * minW + (n - 1) * line;
                w = MeasureSpec.getMode(ws) == MeasureSpec.EXACTLY
                        ? Math.max(MeasureSpec.getSize(ws), natural) : natural;
                cellW = (w - (n - 1) * line) / n;
                cellH = h;
            } else {
                cols = compact ? 1 : 2;
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
                if (animateNext && placed && (oldX != x || oldY != y)) {
                    v.setTranslationX(v.getTranslationX() + oldX - x);
                    v.setTranslationY(v.getTranslationY() + oldY - y);
                    v.setScaleX(0.9f);
                    v.setScaleY(0.9f);
                    v.animate().translationX(0).translationY(0).scaleX(1).scaleY(1)
                            .setDuration(220).setInterpolator(new OvershootInterpolator(1.6f)).start();
                }
            }
            animateNext = false;
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            // The 1px board lines are the gaps between cells showing this colour;
            // an empty slot at the end of an odd column is painted back to ground.
            canvas.drawColor(Ui.LINE);
            paint.setColor(Ui.BG);
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
