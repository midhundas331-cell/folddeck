package io.folddeck.spike;

/** Closed-Fold monitor/input presentation state. */
public class CoverConsoleCheck {
    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    public static void main(String[] args) {
        CoverConsole state = new CoverConsole();
        state.updatePosture(true);
        check(!state.controlsExpanded(), "cover opens in full-monitor mode");
        check(state.toggle(), "corner button reveals input mode");
        check(state.controlsExpanded(), "input mode stays expanded");
        check(!state.toggle(), "second press returns to full monitor");
        state.toggle();
        state.updatePosture(false);
        state.updatePosture(true);
        check(!state.controlsExpanded(), "refolding returns to full-monitor mode");
        System.out.println("CoverConsoleCheck ok");
    }
}
