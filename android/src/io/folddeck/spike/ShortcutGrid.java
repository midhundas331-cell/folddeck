package io.folddeck.spike;

/** Pure geometry for a horizontally scrolling shortcut shelf. */
final class ShortcutGrid {
    private ShortcutGrid() { }

    static int columns(int count, boolean twoRows) {
        count = Math.max(1, count);
        return twoRows ? (count + 1) / 2 : count;
    }

    static int rows(int count, boolean twoRows) {
        return twoRows ? Math.min(2, Math.max(1, count)) : 1;
    }
}
