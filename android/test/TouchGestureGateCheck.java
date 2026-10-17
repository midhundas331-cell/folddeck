package io.folddeck.spike;

/** Cancelled/mode-switched touch sequences must not click when their final UP arrives. */
public class TouchGestureGateCheck {
    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    public static void main(String[] args) {
        TouchGestureGate gate = new TouchGestureGate();
        check(!gate.ignoreNonDown(), "fresh gate accepts events");
        gate.cancelSequence();
        check(gate.ignoreNonDown(), "cancel suppresses the old sequence");
        check(gate.onDown(), "new down reopens input");
        check(!gate.ignoreNonDown(), "new sequence is accepted");
        System.out.println("TouchGestureGateCheck ok");
    }
}
