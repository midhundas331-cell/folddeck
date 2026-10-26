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
        state.resetScroll();
        state.scroll(12f, 32f, true);
        check(state.settle(32f) == 0, "under half a notch falls back into its detent");
        state.scroll(17f, 32f, true);
        check(state.settle(32f) == 1, "past half a notch clicks into the next detent");
        state.scroll(-50f, 32f, true);
        check(state.settle(32f) == -1, "detent pull follows direction");
        check(state.settle(32f) == 0, "settle consumes the remainder");
        check(Math.abs(state.sensitivity(12f, 1.5f) - 18f) < 0.001f,
                "trackpad sensitivity scales movement");

        int pressedButton = TrackpadState.buttonForVisual(1, false);
        check(pressedButton == 1, "normal left zone maps left");
        check(TrackpadState.buttonForVisual(1, true) == 2, "left-handed swaps visual zone");
        check(state.press(pressedButton) == TrackpadState.DOWN,
                "press stores resolved button before handedness change");
        check(state.release(pressedButton) == TrackpadState.UP,
                "release of stored button cannot stick after handedness change");
        TrackpadState tap = new TrackpadState();
        tap.padDown(1000);
        check(tap.padUp(1100, false) == 1, "quick still tap left-clicks");
        tap.padDown(2000);
        tap.padDown(2010);
        check(tap.padUp(2090, false) == 0, "first finger up waits for the second");
        check(tap.padUp(2100, false) == 2, "two-finger tap right-clicks");
        tap.padDown(3000);
        tap.padMoved();
        check(tap.padUp(3050, false) == 0, "a moved finger is not a tap");
        tap.padDown(4000);
        check(tap.padUp(4000 + TrackpadState.TAP_MS + 1, false) == 0, "a long rest is not a tap");
        tap.padDown(5000);
        check(tap.padUp(5050, true) == 0, "no tap click while a button zone is held");
        tap.padDown(6000);
        tap.cancelMask();
        check(tap.padUp(6050, false) == 0, "a cancelled gesture never clicks");
        System.out.println("TrackpadStateCheck ok");
    }
}
