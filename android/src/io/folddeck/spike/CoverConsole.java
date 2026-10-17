package io.folddeck.spike;

/** State machine for the cover display's monitor-first console reveal. */
final class CoverConsole {
    private boolean cover;
    private boolean expanded;

    void updatePosture(boolean isCover) {
        if (isCover && !cover) expanded = false;
        cover = isCover;
        if (!cover) expanded = false;
    }

    boolean toggle() {
        if (!cover) return false;
        expanded = !expanded;
        return expanded;
    }

    boolean controlsExpanded() {
        return cover && expanded;
    }
}
