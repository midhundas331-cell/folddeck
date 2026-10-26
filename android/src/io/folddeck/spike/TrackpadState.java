package io.folddeck.spike;

/** Pure state machine behind the dedicated trackpad's buttons and scroll rail. */
final class TrackpadState {
    static final int NONE = 0, DOWN = 1, UP = -1;

    /** A pad touch shorter than this that never moved is a click. */
    static final long TAP_MS = 220;

    private final int[] contacts = new int[3];
    private float scrollRemainder;
    private int padContacts, tapFingers;
    private long tapDownAt;
    private boolean tapSpoiled;

    int press(int button) {
        if (button < 1 || button > 2) return NONE;
        contacts[button]++;
        return contacts[button] == 1 ? DOWN : NONE;
    }

    int release(int button) {
        if (button < 1 || button > 2 || contacts[button] == 0) return NONE;
        contacts[button]--;
        return contacts[button] == 0 ? UP : NONE;
    }

    int cancelMask() {
        int mask = 0;
        for (int button = 1; button <= 2; button++) {
            if (contacts[button] > 0) mask |= 1 << button;
            contacts[button] = 0;
        }
        resetScroll();
        padContacts = 0;
        tapSpoiled = true;
        return mask;
    }

    void padDown(long now) {
        if (padContacts++ == 0) {
            tapDownAt = now;
            tapFingers = 0;
            tapSpoiled = false;
        }
        tapFingers = Math.max(tapFingers, padContacts);
    }

    /** A pad finger travelled past touch slop: this gesture is movement, not a tap. */
    void padMoved() {
        tapSpoiled = true;
    }

    /** The button to click as the last pad finger lifts (1 left, 2 right), or 0. */
    int padUp(long now, boolean buttonHeld) {
        if (padContacts == 0 || --padContacts > 0) return 0;
        if (tapSpoiled || buttonHeld || now - tapDownAt > TAP_MS) return 0;
        return tapFingers >= 2 ? 2 : 1;
    }

    int scroll(float deltaPx, float pxPerNotch, boolean natural) {
        if (pxPerNotch <= 0f) return 0;
        scrollRemainder += natural ? deltaPx : -deltaPx;
        int raw = (int) (scrollRemainder / pxPerNotch);
        int notches = Math.max(-32, Math.min(32, raw));
        scrollRemainder -= notches * pxPerNotch;
        return notches;
    }

    /**
     * The finger left the wheel: it clicks into the nearest detent, as a real
     * notched wheel does. Past half a notch that is the next one, which scrolls.
     */
    int settle(float pxPerNotch) {
        int n = pxPerNotch > 0f && Math.abs(scrollRemainder) >= pxPerNotch / 2f
                ? (scrollRemainder > 0 ? 1 : -1) : 0;
        scrollRemainder = 0f;
        return n;
    }

    void resetScroll() {
        scrollRemainder = 0f;
    }

    float sensitivity(float delta, float multiplier) {
        return delta * Math.max(0.1f, multiplier);
    }

    static int buttonForVisual(int visualButton, boolean leftHanded) {
        if (visualButton < 1 || visualButton > 2) return 0;
        return leftHanded ? 3 - visualButton : visualButton;
    }
}
