package io.folddeck.spike;

/** Input deck never overlaps keyboard and dedicated trackpad. */
public class InputDeckLayoutCheck {
    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    public static void main(String[] args) {
        InputDeckLayout.Boxes keyboard = InputDeckLayout.computeBoxes(
                1000, 400, 12, true, false, true, false, false, 1f, 1f);
        check(keyboard.keyboard.w == 1000 && keyboard.keyboard.h == 400,
                "keyboard-only keeps full base");

        InputDeckLayout.Boxes side = InputDeckLayout.computeBoxes(
                1000, 400, 12, true, true, true, false, false, 1f, 1f);
        check(side.keyboard.right() + 12 == side.trackpad.x, "side boxes separated by gap");
        check(side.trackpad.w >= 220 && side.trackpad.w <= 300, "side pad width is useful");
        check(side.keyboard.right() <= 1000 && side.trackpad.right() <= 1000,
                "side boxes stay in bounds");

        InputDeckLayout.Boxes stacked = InputDeckLayout.computeBoxes(
                550, 420, 12, true, true, false, false, false, 1f, 1f);
        check(stacked.keyboard.bottom() + 12 == stacked.trackpad.y, "stacked boxes separated");
        check(stacked.trackpad.h >= 104 && stacked.trackpad.h <= 160,
                "stacked pad height is useful");

        InputDeckLayout.Boxes padOnly = InputDeckLayout.computeBoxes(
                550, 260, 12, false, true, false, false, false, 1f, 1f);
        check(padOnly.trackpad.w == 550 && padOnly.trackpad.h == 260,
                "trackpad-only fills base");

        InputDeckLayout.Boxes portrait = InputDeckLayout.computeBoxes(
                875, 479, 12, true, true, false, true, true, 1f, 1f);
        check(portrait.trackpad.y == 0 && portrait.shortcut.y == 0,
                "portrait control shelf sits above keyboard");
        check(portrait.trackpad.w > 400 && portrait.trackpad.w < 440,
                "portrait trackpad is cut to half the horizontal space");
        check(portrait.trackpad.right() + 12 == portrait.shortcut.x,
                "trackpad and shortcut shelf are separated");
        check(portrait.shortcut.right() == 875,
                "two-row shortcuts consume the remaining shelf width");
        check(portrait.keyboard.y > portrait.trackpad.bottom()
                        && portrait.keyboard.bottom() == 479,
                "reduced keyboard stays at the bottom");
        check(portrait.keyboard.h >= 260 && portrait.keyboard.h <= 290,
                "portrait keyboard is compact but usable");
        InputDeckLayout.Boxes sideLeft = InputDeckLayout.computeBoxes(
                1000, 400, 12, true, true, true, false, true, 1f, 1f);
        check(sideLeft.trackpad.x == 0 && sideLeft.trackpad.right() + 12 == sideLeft.keyboard.x
                        && sideLeft.keyboard.right() == 1000,
                "landscape pad moves to the left of the keyboard");

        InputDeckLayout.Boxes portraitRight = InputDeckLayout.computeBoxes(
                875, 479, 12, true, true, false, true, false, 1f, 1f);
        check(portraitRight.shortcut.x == 0
                        && portraitRight.shortcut.right() + 12 == portraitRight.trackpad.x
                        && portraitRight.trackpad.right() == 875,
                "portrait pad moves to the right of the shortcut shelf");

        InputDeckLayout.Boxes bigPad = InputDeckLayout.computeBoxes(
                1000, 400, 12, true, true, true, false, false, 1.5f, 1f);
        check(bigPad.trackpad.w > side.trackpad.w && bigPad.trackpad.w <= 500,
                "trackpad size widens the pad but never past half the deck");
        InputDeckLayout.Boxes smallShelf = InputDeckLayout.computeBoxes(
                875, 479, 12, true, true, false, true, true, 0.7f, 1f);
        check(smallShelf.trackpad.w < portrait.trackpad.w
                        && smallShelf.trackpad.right() + 12 == smallShelf.shortcut.x,
                "a smaller portrait pad gives its width to the shortcut shelf");

        check(InputDeckLayout.blocksInput(false),
                "transitioning controls intercept touches before children");
        check(!InputDeckLayout.blocksInput(true),
                "settled controls deliver touches normally");
        check(InputDeckLayout.clipsControlSurfaces(),
                "custom canvases cannot paint over sibling control regions");
        for (boolean split : new boolean[]{false, true}) {
            InputDeckLayout.Boxes base = InputDeckLayout.computeBoxes(1000, 220, 8,
                    true, true, true, false, false, 1, 1, split);
            InputDeckLayout.Boxes tall = InputDeckLayout.resizeHeight(base, 480, 8, 480,
                    true, true, true, false);
            check(tall.trackpad.h == 480 && tall.keyboard.h == 220,
                    "pad grows beyond keyboard height in side and split layouts");
            check(tall.keyboard.bottom() == 480 && tall.trackpad.bottom() == 480,
                    "independent columns stay bottom aligned");
        }
        for (boolean flip : new boolean[]{false, true}) {
            WorkspaceGeometry.Top top = WorkspaceGeometry.top(1000, 300, 400, 600, 8, 64, 16f / 9, flip);
            check(flip ? top.monitor.right() <= 400 && top.bar.x >= 600
                    : top.bar.right() <= 400 && top.monitor.x >= 600,
                    "raised pad fits between monitor and custom bar without overlap");
            check(top.monitor.bottom() <= 300 && top.monitor.h > 0 && top.monitor.w > 0,
                    "raised pad preserves a visible uncropped monitor");
            WorkspaceGeometry.Top edge = WorkspaceGeometry.top(1000, 300, 0, 280, 8, 64, 16f / 9, flip);
            check(edge.monitor.x >= 280 && edge.bar.x >= 280 && edge.monitor.right() <= 1000
                    && edge.bar.right() <= 1000, "edge pad leaves both upper controls outside its column");
        }
        for (boolean shelf : new boolean[]{false, true}) {
            InputDeckLayout.Boxes base = InputDeckLayout.computeBoxes(875, 479, 8,
                    true, true, false, shelf, false, 1, 1);
            int required = InputDeckLayout.requiredHeight(base, 8, 500, true, false, shelf);
            InputDeckLayout.Boxes tall = InputDeckLayout.resizeHeight(base, required, 8, 500,
                    true, true, false, shelf);
            check(tall.trackpad.h == 500 && tall.keyboard.h == base.keyboard.h,
                    "stacked and shelf pad resize preserves keyboard height");
            check(tall.shortcut.h == base.shortcut.h, "pad height does not resize the custom bar");
            check(shelf ? tall.trackpad.bottom() + 8 <= tall.keyboard.y
                    : tall.keyboard.bottom() + 8 <= tall.trackpad.y, "independent stacked controls do not overlap");
        }
        for (int width : new int[]{360, 750, 971}) {
            for (int height : new int[]{220, 360, 520}) {
                for (float scale : new float[]{0.75f, 1f, 1.7f, 2.5f}) {
                    InputDeckLayout.Boxes live = InputDeckLayout.computeBoxes(width, height, 8,
                            true, true, false, true, true, 1.5f, 1f, false, scale);
                    check(live.keyboard.bottom() == height && live.shortcut.right() == width,
                            "live bar resize redistributes all remaining space");
                    check(live.trackpad.right() + 8 == live.shortcut.x
                                    && live.shortcut.bottom() + 8 == live.keyboard.y,
                            "live controls never overlap");
                    check(live.keyboard.h >= Math.min(160, height / 2),
                            "bar resize leaves a usable keyboard");
                }
            }
        }

        // Stacked and portrait shelf vertical swap (padTop = true).
        InputDeckLayout.Boxes stackedPadTop = InputDeckLayout.computeBoxes(
                550, 420, 12, true, true, false, false, false, 1f, 1f, false, 1f, true);
        check(stackedPadTop.trackpad.y == 0 && stackedPadTop.trackpad.bottom() + 12 == stackedPadTop.keyboard.y,
                "padTop moves stacked trackpad above keyboard");
        InputDeckLayout.Boxes portraitPadTop = InputDeckLayout.computeBoxes(
                875, 479, 12, true, true, false, true, true, 1f, 1f, false, 1f, true);
        check(portraitPadTop.keyboard.y == 0 && portraitPadTop.keyboard.bottom() + 12 == portraitPadTop.trackpad.y
                && portraitPadTop.trackpad.y == portraitPadTop.shortcut.y,
                "padTop moves portrait keyboard above trackpad/shortcut shelf");

        // Corner hit-testing and target hit-testing across all 4 panes.
        InputDeckLayout.Box[] panes = {
                new InputDeckLayout.Box(260, 0, 740, 500),   // MONITOR
                new InputDeckLayout.Box(0, 508, 700, 360),   // KEYBOARD
                new InputDeckLayout.Box(708, 508, 292, 360), // TRACKPAD
                new InputDeckLayout.Box(0, 0, 252, 500)      // BAR
        };
        check(WorkspaceGeometry.hitTarget(panes, 500, 250) == WorkspaceGeometry.MONITOR, "hit monitor body");
        check(WorkspaceGeometry.hitTarget(panes, 350, 680) == WorkspaceGeometry.KEYBOARD, "hit keyboard body");
        check(WorkspaceGeometry.hitTarget(panes, 850, 680) == WorkspaceGeometry.TRACKPAD, "hit trackpad body");
        check(WorkspaceGeometry.hitTarget(panes, 120, 250) == WorkspaceGeometry.BAR, "hit bar body");

        int[][] expectedCorners = {
                {264, 4, WorkspaceGeometry.CORNER_TL},
                {996, 4, WorkspaceGeometry.CORNER_TR},
                {264, 496, WorkspaceGeometry.CORNER_BL},
                {996, 496, WorkspaceGeometry.CORNER_BR}
        };
        for (int[] c : expectedCorners) {
            int hit = WorkspaceGeometry.hitCorner(panes, WorkspaceGeometry.MONITOR, c[0], c[1], 38f, 20f);
            check((hit >> 4) == WorkspaceGeometry.MONITOR && (hit & 0xF) == c[2],
                    "monitor corner hit " + c[2]);
        }
        check(WorkspaceGeometry.hitCorner(panes, WorkspaceGeometry.MONITOR, 500, 250, 38f, 20f) == -1,
                "pane center does not trigger corner handle");

        // 2D corner resizing grows both width and height when any corner is pulled outward.
        WorkspaceGeometry.ResizeState start = new WorkspaceGeometry.ResizeState(
                0.55f, 1.0f, 1.0f, 220f, 28f, 40f);
        for (int corner = 0; corner < 4; corner++) {
            float pullX = WorkspaceGeometry.cornerSignX(corner) * 80f;
            float pullY = WorkspaceGeometry.cornerSignY(corner) * 80f;
            WorkspaceGeometry.ResizeState grownPad = WorkspaceGeometry.resizeCorner(
                    start, WorkspaceGeometry.TRACKPAD, corner, pullX, pullY,
                    1000f, 900f, 900f, 1f, 500f, true, true, true, true, false);
            check(grownPad.padScale > start.padScale && grownPad.padHeight > start.padHeight,
                    "pulling trackpad corner " + corner + " outward grows both width and height");
            WorkspaceGeometry.ResizeState grownMon = WorkspaceGeometry.resizeCorner(
                    start, WorkspaceGeometry.MONITOR, corner, pullX, pullY,
                    1000f, 900f, 900f, 1f, 500f, true, true, true, true, false);
            check(grownMon.monitorFraction > start.monitorFraction && grownMon.barScale < start.barScale,
                    "pulling monitor corner " + corner + " outward grows monitor height and width");
        }

        // Swapping any pair of {Monitor, Keyboard, Trackpad, Bar} updates arrangement and is reversible.
        WorkspaceGeometry.Arrangement init = new WorkspaceGeometry.Arrangement(false, false, false, false, false);
        WorkspaceGeometry.Arrangement monBar = WorkspaceGeometry.swap(
                init, WorkspaceGeometry.MONITOR, WorkspaceGeometry.BAR, true, false, 100f, panes[WorkspaceGeometry.BAR]);
        check(monBar.barFlip && !monBar.deckFlip, "Monitor <-> Bar swaps horizontal bar side");
        WorkspaceGeometry.Arrangement kbPad = WorkspaceGeometry.swap(
                init, WorkspaceGeometry.KEYBOARD, WorkspaceGeometry.TRACKPAD, true, false, 800f, panes[WorkspaceGeometry.TRACKPAD]);
        check(kbPad.padLeft && !kbPad.deckFlip, "Keyboard <-> Trackpad swaps horizontal pad side");
        WorkspaceGeometry.Arrangement monKb = WorkspaceGeometry.swap(
                init, WorkspaceGeometry.MONITOR, WorkspaceGeometry.KEYBOARD, true, false, 350f, panes[WorkspaceGeometry.KEYBOARD]);
        check(monKb.deckFlip, "Monitor <-> Keyboard flips top and bottom deck bands");
        WorkspaceGeometry.Arrangement kbBarShelf = WorkspaceGeometry.swap(
                init, WorkspaceGeometry.KEYBOARD, WorkspaceGeometry.BAR, false, true, 350f, panes[WorkspaceGeometry.BAR]);
        check(kbBarShelf.padTop, "Keyboard <-> Bar in portrait shelf swaps vertical shelf order");

        System.out.println("InputDeckLayoutCheck ok");
    }
}
