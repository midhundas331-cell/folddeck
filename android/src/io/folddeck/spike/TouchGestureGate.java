package io.folddeck.spike;

/** Gates the tail of a cancelled touch sequence until the next real ACTION_DOWN. */
final class TouchGestureGate {
    private boolean suppress;

    void cancelSequence() {
        suppress = true;
    }

    boolean onDown() {
        suppress = false;
        return true;
    }

    boolean ignoreNonDown() {
        return suppress;
    }
}
