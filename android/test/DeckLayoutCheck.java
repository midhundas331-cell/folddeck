package io.folddeck.spike;

/** Pure responsive-layout contract for the deck's video and control bands. */
public class DeckLayoutCheck {
    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    private static void near(float got, float want, float tolerance, String what) {
        check(Math.abs(got - want) <= tolerance,
                what + ": got " + got + " want " + want);
    }

    public static void main(String[] args) {
        DeckLayout.Result inner = DeckLayout.solve(
                971, 875, 600, true, true, false, 16f / 9f, 1f);
        check(inner.videoFirst, "open landscape is video-first");
        check(DeckLayout.INNER_LID_SIDE_DP == 0f,
                "video-first inner lid does not spend width on a side bezel");
        check(DeckLayout.INNER_LID_TOP_DP + DeckLayout.INNER_LID_BOTTOM_DP <= 4f,
                "inner vertical chassis does not force horizontal video margins");
        near(inner.lidDp, 602f, 2f, "inner lid fits full-width 16:9 video plus tabs");
        check(inner.baseDp >= 220f, "inner keyboard keeps usable height");
        check(!inner.sideBySide, "keyboard-only deck needs no split");

        DeckLayout.Result innerPad = DeckLayout.solve(
                971, 875, 600, true, true, true, 16f / 9f, 1f);
        near(innerPad.lidDp, inner.lidDp, 0.01f, "trackpad does not shrink open-landscape video");
        check(innerPad.sideBySide, "wide landscape puts trackpad beside keyboard");

        DeckLayout.Result coverPad = DeckLayout.solve(
                960, 411, 411, true, true, true, 16f / 9f, 1f);
        check(coverPad.baseDp >= 264f && coverPad.baseDp <= 280f,
                "cover input mode gives six keyboard rows usable height");
        check(coverPad.baseDp / 6f >= 44f,
                "cover landscape key rows meet the compact touch target");
        check(coverPad.lidDp >= 125f,
                "cover input mode retains a useful corner monitor");
        check(coverPad.sideBySide, "wide cover landscape avoids stacking a shallow pad");
        DeckLayout.Result coverPadSmall = DeckLayout.solve(
                960, 411, 411, true, true, true, 16f / 9f, 0.85f);
        check(coverPadSmall.baseDp >= 276f,
                "minimum control scale still preserves 44dp key caps plus row gaps");
        check((coverPadSmall.baseDp - 7f * 1.6f) / 6f >= 44f,
                "cover key-cap height stays touchable after inter-row gaps");

        DeckLayout.Result portraitPad = DeckLayout.solve(
                875, 971, 600, false, true, true, 16f / 9f, 1f);
        check(portraitPad.portraitConsole,
                "open portrait docks trackpad and shortcuts above the keyboard");
        check(portraitPad.videoFirst, "open portrait is video-first");
        near(portraitPad.lidDp, 875f * 9f / 16f, 2f,
                "open portrait gives the top to a full-width desktop");
        check(portraitPad.baseDp >= 470f,
                "open portrait retains a keyboard plus compact control shelf");

        DeckLayout.Result padOnly = DeckLayout.solve(
                971, 875, 600, true, false, true, 16f / 9f, 1f);
        check(padOnly.baseDp >= 180f && padOnly.baseDp <= 280f,
                "trackpad-only base stays compact");

        DeckLayout.Result empty = DeckLayout.solve(
                971, 875, 600, true, false, false, 16f / 9f, 1f);
        check(empty.baseDp == 0f && empty.lidDp == 875f, "no controls gives video all space");
        check(DeckLayout.isCover(411), "closed display uses cover presentation");
        check(!DeckLayout.isCover(600), "open display does not use cover presentation");
        check(DeckLayout.requiresDedicatedTrackpad(600, false),
                "open portrait always provides the requested trackpad");
        check(!DeckLayout.requiresDedicatedTrackpad(600, true),
                "open landscape keeps trackpad preference optional");
        check(!DeckLayout.requiresDedicatedTrackpad(411, false),
                "cover trackpad remains controlled by monitor/input mode");

        DeckLayout.Result scaled = DeckLayout.solve(
                971, 875, 600, true, true, false, 16f / 9f, 1.15f);
        check(scaled.baseDp > inner.baseDp, "control scale increases input space");
        System.out.println("DeckLayoutCheck ok");
    }
}
