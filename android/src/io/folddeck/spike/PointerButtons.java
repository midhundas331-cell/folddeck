package io.folddeck.spike;

/** Source-aware aggregate state for reliable pointer button transitions. */
final class PointerButtons {
    interface Sink {
        void onButton(int button, boolean down);
    }

    static final int SOURCE_TOUCH = 1;
    static final int SOURCE_MOUSE = 1 << 1;
    static final int SOURCE_TRACKPAD = 1 << 2;

    private final Sink sink;
    private final int[] owners = new int[4];

    PointerButtons(Sink sink) {
        this.sink = sink;
    }

    void set(int source, int button, boolean down) {
        if (button < 1 || button >= owners.length || source == 0) return;
        int before = owners[button];
        int after = down ? before | source : before & ~source;
        if (after == before) return;
        owners[button] = after;
        if (before == 0 && after != 0) sink.onButton(button, true);
        else if (before != 0 && after == 0) sink.onButton(button, false);
    }

    void releaseSource(int source) {
        for (int button = 1; button < owners.length; button++) {
            set(source, button, false);
        }
    }

    void releaseAll() {
        for (int button = 1; button < owners.length; button++) {
            if (owners[button] != 0) {
                owners[button] = 0;
                sink.onButton(button, false);
            }
        }
    }

    int ownersForTest(int button) {
        return button >= 1 && button < owners.length ? owners[button] : 0;
    }
}
