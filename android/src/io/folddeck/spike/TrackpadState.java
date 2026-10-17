package io.folddeck.spike;

/** Pure state machine behind the dedicated trackpad's buttons and scroll rail. */
final class TrackpadState {
    static final int NONE = 0, DOWN = 1, UP = -1;

    private final int[] contacts = new int[3];
    private float scrollRemainder;

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
        return mask;
    }

    int scroll(float deltaPx, float pxPerNotch, boolean natural) {
        if (pxPerNotch <= 0f) return 0;
        scrollRemainder += natural ? deltaPx : -deltaPx;
        int raw = (int) (scrollRemainder / pxPerNotch);
        int notches = Math.max(-32, Math.min(32, raw));
        scrollRemainder -= notches * pxPerNotch;
        return notches;
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
