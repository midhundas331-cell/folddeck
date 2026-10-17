package io.folddeck.spike;

/** Pure held-button and scroll accumulation checks for the dedicated trackpad. */
public class TrackpadStateCheck {
    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    public static void main(String[] args) {
        TrackpadState state = new TrackpadState();
        check(state.press(1) == TrackpadState.DOWN, "first left contact presses");
        check(state.press(1) == TrackpadState.NONE, "second left contact does not repeat down");
        check(state.release(1) == TrackpadState.NONE, "first release keeps left held");
        check(state.release(1) == TrackpadState.UP, "last release lifts left");
        check(state.release(1) == TrackpadState.NONE, "extra release ignored");

        check(state.press(1) == TrackpadState.DOWN, "left can press again");
        check(state.press(2) == TrackpadState.DOWN, "right can be held with left");
        int mask = state.cancelMask();
        check(mask == ((1 << 1) | (1 << 2)), "cancel reports both held buttons");
        check(state.cancelMask() == 0, "second cancel is quiet");

        check(state.scroll(20f, 42f, true) == 0, "small scroll accumulates");
        check(state.scroll(25f, 42f, true) == 1, "accumulated natural scroll emits notch");
        state.resetScroll();
        check(state.scroll(42f, 42f, false) == -1, "reverse scroll changes sign");
        check(Math.abs(state.sensitivity(12f, 1.5f) - 18f) < 0.001f,
                "trackpad sensitivity scales movement");

        int pressedButton = TrackpadState.buttonForVisual(1, false);
        check(pressedButton == 1, "normal left zone maps left");
        check(TrackpadState.buttonForVisual(1, true) == 2, "left-handed swaps visual zone");
        check(state.press(pressedButton) == TrackpadState.DOWN,
                "press stores resolved button before handedness change");
        check(state.release(pressedButton) == TrackpadState.UP,
                "release of stored button cannot stick after handedness change");
        System.out.println("TrackpadStateCheck ok");
    }
}
