package io.folddeck.spike;

/** Preference ranges stay safe even when old/corrupt values are loaded. */
public class CustomizationCheck {
    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    private static void near(float got, float want, String what) {
        check(Math.abs(got - want) < 0.0001f, what + ": " + got);
    }

    public static void main(String[] args) {
        near(Prefs.clampTrackpadSens(0.1f), 0.5f, "trackpad minimum");
        near(Prefs.clampTrackpadSens(3f), 2f, "trackpad maximum");
        near(Prefs.clampTrackpointSens(9f), 3f, "TrackPoint maximum");
        near(Prefs.clampControlScale(0.1f), 0.85f, "control scale minimum");
        near(Prefs.clampControlScale(2f), 1.15f, "control scale maximum");
        check(Prefs.clampPointerMode(-1) == PointerPad.MODE_DIRECT, "bad pointer mode falls back");
        check(Prefs.clampPointerMode(PointerPad.MODE_TRACKPAD) == PointerPad.MODE_TRACKPAD,
                "trackpad pointer mode persists");
        System.out.println("CustomizationCheck ok");
    }
}
