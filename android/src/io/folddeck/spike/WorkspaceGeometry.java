package io.folddeck.spike;

/**
 * Pure geometry for workspace panes: fitting monitor and shortcut bar around a
 * raised trackpad, hit-testing 4-corner resize handles, 2D corner resizing, and
 * drag-to-swap arrangements across Display, Keyboard, Trackpad, and Bar.
 */
final class WorkspaceGeometry {
    static final int MONITOR = 0, KEYBOARD = 1, TRACKPAD = 2, BAR = 3,
            HEIGHT = 4, WHEEL = 5, BUTTONS = 6;
    static final int CORNER_NONE = -1, CORNER_TL = 0, CORNER_TR = 1, CORNER_BL = 2, CORNER_BR = 3;

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

    static int cornerSignX(int corner) {
        return (corner == CORNER_TR || corner == CORNER_BR) ? 1 : -1;
    }

    static int cornerSignY(int corner) {
        return (corner == CORNER_BL || corner == CORNER_BR) ? 1 : -1;
    }

    /**
     * Returns packed {@code (target << 4) | corner}, or -1 when (x, y) is not on a corner handle.
     * Prioritizes {@code selected} so its 4 corners win when adjacent panes touch.
     */
    static int hitCorner(InputDeckLayout.Box[] boxes, int selected, float x, float y,
                         float radiusPx, float minRadiusPx) {
        int primarySelected = selected >= HEIGHT ? TRACKPAD : selected;
        if (primarySelected >= 0 && primarySelected < boxes.length) {
            int c = boxCorner(boxes[primarySelected], x, y, radiusPx, minRadiusPx);
            if (c != CORNER_NONE) return (primarySelected << 4) | c;
        }
        int[] order = {TRACKPAD, BAR, KEYBOARD, MONITOR};
        for (int t : order) {
            if (t == primarySelected || t >= boxes.length) continue;
            int c = boxCorner(boxes[t], x, y, radiusPx, minRadiusPx);
            if (c != CORNER_NONE) return (t << 4) | c;
        }
        return -1;
    }

    static int boxCorner(InputDeckLayout.Box b, float x, float y, float radiusPx, float minRadiusPx) {
        if (b == null || b.w <= 0 || b.h <= 0) return CORNER_NONE;
        float rx = Math.min(radiusPx, Math.max(minRadiusPx, b.w * 0.36f));
        float ry = Math.min(radiusPx, Math.max(minRadiusPx, b.h * 0.36f));
        if (x < b.x - rx || x > b.right() + rx || y < b.y - ry || y > b.bottom() + ry) {
            return CORNER_NONE;
        }
        float[] cx = {b.x, b.right(), b.x, b.right()};
        float[] cy = {b.y, b.y, b.bottom(), b.bottom()};
        int best = CORNER_NONE;
        float bestDist = Float.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            float dx = (x - cx[i]) / Math.max(1f, rx);
            float dy = (y - cy[i]) / Math.max(1f, ry);
            float d2 = dx * dx + dy * dy;
            if (d2 <= 1f && d2 < bestDist) {
                bestDist = d2;
                best = i;
            }
        }
        return best;
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

    /**
     * Resizes {@code target} in 2D by pulling {@code corner} (TL=0, TR=1, BL=2, BR=3)
     * by {@code (dxPx, dyPx)} from the state captured at the start of the gesture.
     */
    static ResizeState resizeCorner(ResizeState start, int target, int corner,
                                    float dxPx, float dyPx, float overlayW, float overlayH,
                                    float deckHeightDp, float density, float padHeightLimit,
                                    boolean controlsShown, boolean keyboardShown,
                                    boolean trackpadHorizontal, boolean barBesideMonitor,
                                    boolean shortcutDocked) {
        float signX = cornerSignX(corner);
        float signY = cornerSignY(corner);
        float growX = (dxPx * signX) / Math.max(1f, overlayW);
        float growY = (dyPx * signY) / Math.max(1f, overlayH);
        float dxDp = (dxPx * signX) / Math.max(0.5f, density);
        float dyDp = (dyPx * signY) / Math.max(0.5f, density);

        float monitorFraction = start.monitorFraction;
        float padScale = start.padScale;
        float barScale = start.barScale;
        float padHeight = start.padHeight;
        float wheelWidth = start.wheelWidth;
        float buttonHeight = start.buttonHeight;

        if (target == WHEEL) {
            wheelWidth = Motion.clamp(start.wheelWidth + dxDp, 18f, 64f);
        } else if (target == BUTTONS) {
            buttonHeight = Motion.clamp(start.buttonHeight + dyDp, 28f, 80f);
        } else if (target == HEIGHT) {
            padHeight = Motion.clamp(start.padHeight + dyDp, 100f, Math.max(100f, padHeightLimit));
        } else if (target == TRACKPAD) {
            if (keyboardShown) {
                padScale = Motion.clamp(start.padScale + growX * 1.8f,
                        Prefs.TRACKPAD_SIZE_MIN, Prefs.TRACKPAD_SIZE_MAX);
            }
            if (start.padHeight > 0f) {
                padHeight = Motion.clamp(start.padHeight + dyDp, 100f, Math.max(100f, padHeightLimit));
            }
        } else if (target == BAR) {
            if (!shortcutDocked && barBesideMonitor) {
                barScale = Motion.clamp(start.barScale + growX * 2.2f,
                        Prefs.BAR_SIZE_MIN, Prefs.BAR_SIZE_MAX);
                if (controlsShown && deckHeightDp > 0f) {
                    float wanted = Motion.clamp(start.monitorFraction + growY * 1.5f, 0f, 1f);
                    monitorFraction = DeckLayout.resizedLid(deckHeightDp, wanted, keyboardShown, false)
                            / Math.max(1f, deckHeightDp);
                }
            } else {
                barScale = Motion.clamp(start.barScale + growY * 2.2f,
                        Prefs.BAR_SIZE_MIN, Prefs.BAR_SIZE_MAX);
                if (shortcutDocked && keyboardShown) {
                    padScale = Motion.clamp(start.padScale - growX * 1.8f,
                            Prefs.TRACKPAD_SIZE_MIN, Prefs.TRACKPAD_SIZE_MAX);
                }
            }
        } else if (target == MONITOR) {
            if (!controlsShown) {
                float delta = barBesideMonitor ? -growX : -growY;
                barScale = Motion.clamp(start.barScale + delta * 2.2f,
                        Prefs.BAR_SIZE_MIN, Prefs.BAR_SIZE_MAX);
            } else {
                float wanted = Motion.clamp(start.monitorFraction + growY * 1.5f, 0f, 1f);
                monitorFraction = DeckLayout.resizedLid(deckHeightDp, wanted, keyboardShown,
                        shortcutDocked) / Math.max(1f, deckHeightDp);
                if (!shortcutDocked && barBesideMonitor) {
                    barScale = Motion.clamp(start.barScale - growX * 2.2f,
                            Prefs.BAR_SIZE_MIN, Prefs.BAR_SIZE_MAX);
                }
            }
        } else if (target == KEYBOARD) {
            if (controlsShown && deckHeightDp > 0f) {
                float wanted = Motion.clamp(start.monitorFraction - growY * 1.5f, 0f, 1f);
                monitorFraction = DeckLayout.resizedLid(deckHeightDp, wanted, keyboardShown,
                        shortcutDocked) / Math.max(1f, deckHeightDp);
            }
            if (trackpadHorizontal) {
                padScale = Motion.clamp(start.padScale - growX * 1.8f,
                        Prefs.TRACKPAD_SIZE_MIN, Prefs.TRACKPAD_SIZE_MAX);
            }
        }
        return new ResizeState(monitorFraction, padScale, barScale, padHeight, wheelWidth, buttonHeight);
    }

    /** One corner resize as a function of the finger's offset. */
    interface Resize {
        ResizeState at(float dxPx, float dyPx);
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
     * How far, in px along x and y, the finger has pulled past the point where
     * the resize stopped answering: a size limit, or an axis this pane cannot
     * grow along. The overlay stretches its frame by this, with resistance.
     * Each value is a clamped straight line in the offset, so once a pull stops
     * changing anything it never starts again further out: bisect for it.
     */
    static float[] overshoot(Resize resize, float dxPx, float dyPx) {
        ResizeState at = resize.at(dxPx, dyPx);
        return new float[]{stall(resize, at, dxPx, dyPx, true), stall(resize, at, dxPx, dyPx, false)};
    }

    private static float stall(Resize resize, ResizeState at, float dx, float dy, boolean xAxis) {
        float d = xAxis ? dx : dy;
        if (Math.abs(d) < 0.5f) return 0f;
        float lo = 0f, hi = 1f;   // the share of d at which nothing changes any more
        if (same(at, xAxis ? resize.at(0f, dy) : resize.at(dx, 0f))) {
            hi = 0f;
        } else {
            for (int i = 0; i < 14 && (hi - lo) * Math.abs(d) > 0.5f; i++) {
                float mid = (lo + hi) * 0.5f;
                if (same(at, xAxis ? resize.at(dx * mid, dy) : resize.at(dx, dy * mid))) hi = mid;
                else lo = mid;
            }
        }
        return d * (1f - hi);
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
