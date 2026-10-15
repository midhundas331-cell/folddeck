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
        check(Motion.shouldPulse(HomeView.PHASE_CONNECTING), "connecting status pulses");
        check(Motion.shouldPulse(HomeView.PHASE_FINDING), "route search pulses");
        check(!Motion.shouldPulse(HomeView.PHASE_STREAMING), "stable status does not animate forever");
        check(Motion.pressKeepsTranslationIndependent(),
                "press feedback must not cancel shortcut reorder translation");
        System.out.println("MotionCheck ok");
    }
}
