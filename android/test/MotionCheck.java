package io.folddeck.spike;

/** Motion stays quick, restrained, and accessible. */
public class MotionCheck {
    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    public static void main(String[] args) {
        check(Motion.PRESS_SCALE >= 0.96f && Motion.PRESS_SCALE < 1f, "press scale is tactile, not theatrical");
        check(Motion.PRESS[0] <= 0.15f && Motion.PRESS[1] >= 1f, "press response is immediate and flat");
        check(Motion.NAV[1] >= 1f, "screens never overshoot, so no gap shows at the edge");
        check(Motion.FADE[1] >= 1f, "opacity never overshoots into a flash");
        check(Motion.RELEASE[1] >= 0.5f && Motion.RELEASE[1] < 1f, "release bounces, restrained");
        for (float[] feel : new float[][]{Motion.SMOOTH, Motion.NAV, Motion.SNAPPY,
                Motion.FADE, Motion.PRESS, Motion.RELEASE}) {
            check(Spring.seconds(feel[0], feel[1]) < 0.8f, "every shared spring settles quickly");
        }
        check(Motion.shouldPulse(HomeView.PHASE_CONNECTING), "connecting status pulses");
        check(Motion.shouldPulse(HomeView.PHASE_FINDING), "route search pulses");
        check(!Motion.shouldPulse(HomeView.PHASE_STREAMING), "stable status does not animate forever");
        check(Motion.pressKeepsTranslationIndependent(),
                "press feedback must not cancel shortcut reorder translation");
        check(Ui.FLOAT_MID_DP < Ui.FLOAT_ELEVATION_DP,
                "z-order tiers keep floating buttons above the deck");
        check(Ui.FLOAT_ELEVATION_DP >= 6f && Ui.FLOAT_ELEVATION_DP <= 12f,
                "floating controls have restrained physical depth");
        System.out.println("MotionCheck ok");
    }
}
