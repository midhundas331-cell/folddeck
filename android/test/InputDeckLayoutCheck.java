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
        System.out.println("InputDeckLayoutCheck ok");
    }
}
