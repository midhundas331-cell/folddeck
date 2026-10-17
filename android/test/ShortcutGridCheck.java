package io.folddeck.spike;

/** Two-row shortcut shelf keeps both rows in one horizontal scrolling grid. */
public class ShortcutGridCheck {
    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    public static void main(String[] args) {
        check(ShortcutGrid.columns(10, true) == 5,
                "ten controls form five simultaneous-scroll columns");
        check(ShortcutGrid.rows(10, true) == 2,
                "portrait shortcut shelf has two rows");
        check(ShortcutGrid.columns(9, true) == 5,
                "odd control count leaves one final two-row slot");
        check(ShortcutGrid.rows(9, true) == 2,
                "odd count still retains the two-row shelf");
        check(ShortcutGrid.rows(9, false) == 1,
                "ordinary horizontal bar remains one row");
        System.out.println("ShortcutGridCheck ok");
    }
}
