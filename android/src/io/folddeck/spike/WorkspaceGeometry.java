package io.folddeck.spike;

/**
 * Pure geometry for workspace panes: fitting monitor and shortcut bar around a
 * raised trackpad, the dividers that resize them, magnetic sizes, and
 * drag-to-swap arrangements across Display, Keyboard, Trackpad, and Bar.
 */
final class WorkspaceGeometry {
    static final int MONITOR = 0, KEYBOARD = 1, TRACKPAD = 2, BAR = 3,
            HEIGHT = 4, WHEEL = 5, BUTTONS = 6;
    static final int LEFT = 0, TOP = 1, RIGHT = 2, BOTTOM = 3;

    /**
     * One edge the user can drag: {@code side} of pane P, the one value it sizes
     * ({@code target}), and where it is now. Dragging it {@code travel} px along
     * its axis grows P by {@link #growth}; a centred pane grows on both sides,
     * so its {@code gain} is 2.
     */
    static final class Edge {
        final int target, side;
        /** Line position on the drag axis, its extent along the line, P's size on the axis. */
        final float pos, from, to, size, gain;
        /** Draw offset toward the neighbour, so the grip sits in the gap. */
        final float nudge;
        Edge(int target, int side, float pos, float from, float to, float size, float gain, float nudge) {
            this.target = target;
            this.side = side;
            this.pos = pos;
            this.from = from;
            this.to = to;
            this.size = size;
            this.gain = gain;
            this.nudge = nudge;
        }
        boolean vertical() { return side == LEFT || side == RIGHT; }
        float growth(float travel) { return travel * (side == RIGHT || side == BOTTOM ? 1f : -1f) * gain; }
        int id() { return target * 4 + side; }
    }

    /** The free {@code side} of pane {@code p}. */
    static Edge side(int target, InputDeckLayout.Box p, int side, float gain) {
        boolean v = side == LEFT || side == RIGHT;
        float pos = side == LEFT ? p.x : side == RIGHT ? p.right() : side == TOP ? p.y : p.bottom();
        return new Edge(target, side, pos, v ? p.y : p.x, v ? p.bottom() : p.right(), v ? p.w : p.h, gain, 0f);
    }

    /**
     * The divider between pane {@code p} and its neighbour {@code q}: a vertical
     * line when they sit side by side (they share more height than width), else
     * horizontal; always on P's side facing Q, since that is the side that moves.
     */
    static Edge between(int target, InputDeckLayout.Box p, InputDeckLayout.Box q, float maxNudge) {
        float ox = overlap(p.x, p.right(), q.x, q.right()), oy = overlap(p.y, p.bottom(), q.y, q.bottom());
        boolean beside = oy > ox;
        int side = beside ? (p.x + p.w * 0.5f < q.x + q.w * 0.5f ? RIGHT : LEFT)
                : (p.y + p.h * 0.5f < q.y + q.h * 0.5f ? BOTTOM : TOP);
        Edge e = side(target, p, side, 1f);
        float facing = side == LEFT ? q.right() : side == RIGHT ? q.x : side == TOP ? q.bottom() : q.y;
        float gap = (facing - e.pos) * (side == RIGHT || side == BOTTOM ? 1f : -1f);
        float nudge = Math.signum(facing - e.pos) * Math.min(Math.max(0f, gap) * 0.5f, maxNudge);
        // Along the line: the stretch the two panes share, or P's own when they share none.
        float from = Math.max(e.from, beside ? q.y : q.x), to = Math.min(e.to, beside ? q.bottom() : q.right());
        if (to - from < 1f) {
            from = e.from;
            to = e.to;
        }
        return new Edge(target, side, e.pos, from, to, e.size, 1f, nudge);
    }

    private static float overlap(float a0, float a1, float b0, float b1) {
        return Math.min(a1, b1) - Math.max(a0, b0);
    }

    /**
     * The edge whose grip is nearest (x, y), among those within {@code reach} of
     * their line; -1 if none. Nearest grip wins, so a pane edge lying on a
     * full-width divider is still reachable over that pane.
     */
    static int hitEdge(Edge[] edges, float x, float y, float reach) {
        int best = -1;
        float bestD = Float.MAX_VALUE;
        for (int i = 0; i < edges.length; i++) {
            Edge e = edges[i];
            float across = (e.vertical() ? x : y) - (e.pos + e.nudge);
            float along = e.vertical() ? y : x;
            if (Math.abs(across) > reach || along < e.from - reach * 0.5f || along > e.to + reach * 0.5f) continue;
            float d = (float) Math.hypot(across, along - (e.from + e.to) * 0.5f);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    static final class Top {
        final InputDeckLayout.Box bar, monitor;
        Top(InputDeckLayout.Box bar, InputDeckLayout.Box monitor) { this.bar = bar; this.monitor = monitor; }
    }

    static final class Arrangement {
        final boolean deckFlip, padLeft, barFlip, padTop, split;
        Arrangement(boolean deckFlip, boolean padLeft, boolean barFlip, boolean padTop, boolean split) {
            this.deckFlip = deckFlip;
            this.padLeft = padLeft;
            this.barFlip = barFlip;
            this.padTop = padTop;
            this.split = split;
        }
    }

    static final class ResizeState {
        final float monitorFraction, padScale, barScale, padHeight, wheelWidth, buttonHeight;
        ResizeState(float monitorFraction, float padScale, float barScale,
                    float padHeight, float wheelWidth, float buttonHeight) {
            this.monitorFraction = monitorFraction;
            this.padScale = padScale;
            this.barScale = barScale;
            this.padHeight = padHeight;
            this.wheelWidth = wheelWidth;
            this.buttonHeight = buttonHeight;
        }
    }

    static Top top(int width, int height, int padLeft, int padRight, int gap, int min,
                   float aspect, boolean flip) {
        int left = Math.max(0, padLeft), right = Math.max(0, width - padRight);
        int barX, barW, videoX, videoW;
        if (left >= min && right >= min) {
            barX = flip ? padRight : 0;
            barW = flip ? right : left;
            videoX = flip ? 0 : padRight;
            videoW = flip ? left : right;
        } else {
            int start = left >= right ? 0 : padRight;
            int space = Math.max(left, right);
            barW = Math.min(Math.max(min, space / 3), Math.max(0, space / 2 - gap));
            videoW = Math.max(1, space - barW - gap);
            barX = flip ? start + videoW + gap : start;
            videoX = flip ? start : start + barW + gap;
        }
        int vw = Math.min(videoW, Math.max(1, Math.round(height * aspect)));
        int vh = Math.min(height, Math.max(1, Math.round(vw / aspect)));
        return new Top(new InputDeckLayout.Box(barX, 0, barW, height),
                new InputDeckLayout.Box(videoX + (videoW - vw) / 2, (height - vh) / 2, vw, vh));
    }

    /**
     * Returns which primary pane (MONITOR=0, KEYBOARD=1, TRACKPAD=2, BAR=3) contains (x, y),
     * checking smaller/foreground panes (Trackpad, Bar) before larger ones (Keyboard, Monitor).
     */
    static int hitTarget(InputDeckLayout.Box[] boxes, float x, float y) {
        int[] order = {TRACKPAD, BAR, KEYBOARD, MONITOR};
        for (int t : order) {
            if (t >= boxes.length) continue;
            InputDeckLayout.Box b = boxes[t];
            if (b != null && b.w > 0 && b.h > 0
                    && x >= b.x && x <= b.right() && y >= b.y && y <= b.bottom()) {
                return t;
            }
        }
        return -1;
    }

    /**
     * Computes the new workspace arrangement when component {@code from} is dragged and
     * dropped onto component {@code to}.
     */
    static final int BAR_STAY = 0, BAR_UNDOCK = 1, BAR_REDOCK = 2, BAR_SIDE = 3;

    /**
     * Where a dragged shortcut bar goes in a posture that can dock it in the
     * keyboard's shelf: onto the monitor takes it out to sit beside the desktop
     * (and, once there, picks the side); back onto the keys or pad docks it.
     */
    static int barMove(int from, int to, boolean docked, boolean canDock) {
        if (from != BAR || !canDock || to == BAR) return BAR_STAY;
        if (docked) return to == MONITOR ? BAR_UNDOCK : BAR_STAY;
        return to == MONITOR ? BAR_SIDE : BAR_REDOCK;
    }

    static final int PAD_STAY = 0, PAD_LIFT = 1, PAD_DROP = 2, PAD_BLOCK = 3;

    /**
     * Where a dragged trackpad goes: onto the monitor lifts it out of the base
     * into a band under the desktop; from there, onto the keys puts it back.
     * While it is up there the base-only swaps that involve it do nothing.
     */
    static int padMove(int from, int to, boolean inLid, boolean canLift) {
        if (!canLift || from == to) return PAD_STAY;
        if (!inLid) return from == TRACKPAD && to == MONITOR ? PAD_LIFT : PAD_STAY;
        if (from == TRACKPAD && to == KEYBOARD) return PAD_DROP;
        return from == TRACKPAD || to == TRACKPAD ? PAD_BLOCK : PAD_STAY;
    }

    static Arrangement swap(Arrangement cur, int from, int to,
                            boolean sideBySide, boolean dockShortcuts,
                            float dropX, InputDeckLayout.Box toBox) {
        if (from == to || from < MONITOR || from > BAR || to < MONITOR || to > BAR) return cur;
        boolean deckFlip = cur.deckFlip;
        boolean padLeft = cur.padLeft;
        boolean barFlip = cur.barFlip;
        boolean padTop = cur.padTop;
        boolean split = cur.split;

        int lo = Math.min(from, to), hi = Math.max(from, to);
        if (lo == MONITOR && hi == BAR) {
            if (dockShortcuts) {
                deckFlip = !deckFlip;
            } else {
                barFlip = !barFlip;
            }
        } else if (lo == KEYBOARD && hi == TRACKPAD) {
            if (split) {
                split = false;
                if (toBox != null && toBox.w > 0) {
                    padLeft = dropX < (toBox.x + toBox.w * 0.5f);
                } else {
                    padLeft = !padLeft;
                }
            } else if (sideBySide) {
                padLeft = !padLeft;
            } else {
                padTop = !padTop;
            }
        } else if (lo == TRACKPAD && hi == BAR) {
            if (dockShortcuts) {
                padLeft = !padLeft;
            } else {
                split = false;
                if (padLeft == barFlip) {
                    padLeft = !padLeft;
                    barFlip = !barFlip;
                } else {
                    deckFlip = !deckFlip;
                }
            }
        } else if (lo == MONITOR && hi == KEYBOARD) {
            deckFlip = !deckFlip;
        } else if (lo == MONITOR && hi == TRACKPAD) {
            deckFlip = !deckFlip;
            if (!dockShortcuts && sideBySide) {
                split = false;
                if (padLeft != barFlip) padLeft = barFlip;
            }
        } else if (lo == KEYBOARD && hi == BAR) {
            if (dockShortcuts) {
                padTop = !padTop;
            } else {
                deckFlip = !deckFlip;
                if (sideBySide && barFlip != padLeft) {
                    barFlip = padLeft;
                }
            }
        }
        return new Arrangement(deckFlip, padLeft, barFlip, padTop, split);
    }

    static boolean same(ResizeState a, ResizeState b) {
        return near(a.monitorFraction, b.monitorFraction) && near(a.padScale, b.padScale)
                && near(a.barScale, b.barScale) && near(a.padHeight, b.padHeight)
                && near(a.wheelWidth, b.wheelWidth) && near(a.buttonHeight, b.buttonHeight);
    }

    private static boolean near(float a, float b) {
        return Math.abs(a - b) <= 1e-4f * Math.max(1f, Math.abs(a));
    }

    /**
     * Magnetic sizes: a value being dragged that comes near its default (or an
     * even monitor/deck split) sticks there. Values the drag is not changing are
     * left alone, so an untouched pane never jumps.
     */
    static ResizeState snap(ResizeState start, ResizeState next, float defaultPadScale) {
        return new ResizeState(
                pull(start.monitorFraction, next.monitorFraction, 0.5f, 0.018f),
                pull(start.padScale, next.padScale, defaultPadScale, 0.035f),
                pull(start.barScale, next.barScale, 1f, 0.04f),
                next.padHeight,
                pull(start.wheelWidth, next.wheelWidth, 28f, 1.5f),
                pull(start.buttonHeight, next.buttonHeight, 40f, 2f));
    }

    private static float pull(float start, float value, float to, float radius) {
        return value != start && value >= 0f && Math.abs(value - to) < radius ? to : value;
    }
}
