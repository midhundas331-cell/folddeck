package io.folddeck.spike;

/** Motion stays quick, restrained, and accessible. */
public class MotionCheck {
    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    public static void main(String[] args) {
        check(Motion.PRESS_SCALE >= 0.96f && Motion.PRESS_SCALE < 1f, "press scale is tactile, not theatrical");
        check(Motion.PRESS_MS <= 100, "press response is immediate");
        check(Motion.ENTER_MS >= 160 && Motion.ENTER_MS <= 260, "screen entry is smooth but quick");
        check(Motion.COVER_SWITCH_MS >= 220 && Motion.COVER_SWITCH_MS <= 300,
                "cover console transition stays fast");
        check(Motion.COVER_OVERSHOOT >= 1.05f && Motion.COVER_OVERSHOOT <= 1.35f,
                "cover console motion has restrained bounce");
        check(Motion.shouldPulse(HomeView.PHASE_CONNECTING), "connecting status pulses");
        check(Motion.shouldPulse(HomeView.PHASE_FINDING), "route search pulses");
        check(!Motion.shouldPulse(HomeView.PHASE_STREAMING), "stable status does not animate forever");
        check(Motion.pressKeepsTranslationIndependent(),
                "press feedback must not cancel shortcut reorder translation");
        check(Ui.FLOAT_LOW_DP < Ui.FLOAT_MID_DP
                        && Ui.FLOAT_MID_DP < Ui.FLOAT_ELEVATION_DP,
                "depth tokens preserve low, middle and action hierarchy");
        check(Ui.FLOAT_ELEVATION_DP >= 6f && Ui.FLOAT_ELEVATION_DP <= 12f,
                "floating controls have restrained physical depth");
        System.out.println("MotionCheck ok");
    }
}
