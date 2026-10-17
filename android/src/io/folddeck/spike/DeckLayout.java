package io.folddeck.spike;

/** Pure geometry policy for the video lid and input base. Values are dp. */
final class DeckLayout {
    static final float SHORTCUT_ROW_DP = 56f;
    static final float INNER_LID_SIDE_DP = 0f;
    static final float INNER_LID_TOP_DP = 2f;
    static final float INNER_LID_BOTTOM_DP = 2f;
    static final float INNER_KEYBOARD_MIN_DP = 220f;
    /** Six 44dp key caps plus seven ~1.6dp row gaps on the cover keyboard. */
    static final float COVER_LANDSCAPE_BASE_MIN_DP = 276f;
    static final float TRACKPAD_ONLY_DP = 220f;

    private DeckLayout() { }

    static final class Result {
        final float lidDp;
        final float baseDp;
        final boolean sideBySide;
        final boolean videoFirst;
        final boolean portraitConsole;

        Result(float lidDp, float baseDp, boolean sideBySide, boolean videoFirst,
               boolean portraitConsole) {
            this.lidDp = lidDp;
            this.baseDp = baseDp;
            this.sideBySide = sideBySide;
            this.videoFirst = videoFirst;
            this.portraitConsole = portraitConsole;
        }
    }

    static Result solve(float widthDp, float heightDp, int smallestWidthDp,
                        boolean landscape, boolean keyboard, boolean trackpad,
                        float aspect, float controlScale) {
        widthDp = Math.max(widthDp, 1f);
        heightDp = Math.max(heightDp, 1f);
        aspect = aspect > 0.2f ? aspect : 16f / 9f;
        controlScale = clamp(controlScale, 0.85f, 1.15f);

        if (!keyboard && !trackpad) {
            return new Result(heightDp, 0f, false, true, false);
        }

        boolean innerLandscape = smallestWidthDp >= 600 && landscape;
        boolean innerPortrait = smallestWidthDp >= 600 && !landscape;
        boolean sideBySide = keyboard && trackpad && landscape && widthDp >= 800f;
        float base;
        boolean videoFirst = false;
        boolean portraitConsole = false;

        if (!keyboard) {
            base = clamp(TRACKPAD_ONLY_DP * controlScale, 180f, 280f);
        } else if (innerLandscape) {
            // Full-width video first; the shortcut row sits directly under it and
            // every remaining pixel becomes the keyboard/control base.
            float idealLid = widthDp / aspect + SHORTCUT_ROW_DP;
            float spare = Math.max(INNER_KEYBOARD_MIN_DP, heightDp - idealLid);
            base = clamp(spare * controlScale,
                    INNER_KEYBOARD_MIN_DP * controlScale, heightDp * 0.42f);
            videoFirst = true;
        } else if (innerPortrait) {
            // The largest full-width uncropped desktop owns the top. Everything
            // below becomes a compact console shelf plus the keyboard.
            float idealLid = widthDp / aspect;
            float shelfMin = trackpad ? 152f : 112f;
            float baseMin = (260f + shelfMin + 8f) * controlScale;
            base = Math.max(heightDp - idealLid, baseMin);
            base = Math.min(base, heightDp * 0.58f);
            videoFirst = true;
            portraitConsole = true;
        } else if (landscape) {
            // Cover input mode deliberately makes the monitor small: six rows of
            // touch keys need real finger targets, with the pad beside them.
            float fraction = trackpad && !sideBySide ? 0.70f : 0.66f;
            base = Math.max(COVER_LANDSCAPE_BASE_MIN_DP, heightDp * fraction);
            base = clamp(base * controlScale, COVER_LANDSCAPE_BASE_MIN_DP,
                    heightDp * 0.72f);
        } else {
            // Portrait can stack the pad under the keyboard. Without it, retain
            // the familiar compact 58/42 split.
            float fraction = trackpad ? 0.52f : 0.42f;
            base = clamp(heightDp * fraction * controlScale, 220f, heightDp * 0.65f);
        }

        base = Math.min(base, Math.max(0f, heightDp - 80f));
        return new Result(heightDp - base, base, sideBySide, videoFirst, portraitConsole);
    }

    static boolean isCover(int smallestWidthDp) {
        return smallestWidthDp < 600;
    }

    static boolean requiresDedicatedTrackpad(int smallestWidthDp, boolean landscape) {
        return smallestWidthDp >= 600 && !landscape;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
