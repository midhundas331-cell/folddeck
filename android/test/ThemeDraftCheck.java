package io.folddeck.spike;

/** JVM contract: live palette changes must not destroy unsaved editor drafts. */
public class ThemeDraftCheck {
    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    public static void main(String[] args) {
        check(MainActivity.preserveDraftDuringTheme(4), "address draft must survive theme change");
        check(MainActivity.preserveDraftDuringTheme(5), "shortcut draft must survive theme change");
        check(!MainActivity.preserveDraftDuringTheme(0), "home has no editor draft");
        System.out.println("ThemeDraftCheck ok");
    }
}
