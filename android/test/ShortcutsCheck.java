package io.folddeck.spike;

import java.util.Arrays;
import java.util.List;

/**
 * JVM check for the shortcut parser and storage format. After ./build.sh:
 *
 *   J=~/Android/Sdk/platforms/android-36/android.jar
 *   javac --release 17 -cp build/classes:$J -d build/test test/ShortcutsCheck.java
 *   java -cp build/test:build/classes:$J io.folddeck.spike.ShortcutsCheck
 */
public class ShortcutsCheck {
    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    public static void main(String[] args) {
        check(Arrays.equals(Shortcuts.parse("SUPER+SHIFT+B"),
                new int[]{Ev.LEFTMETA, Ev.LEFTSHIFT, Ev.B}), "SUPER+SHIFT+B");
        check(Arrays.equals(Shortcuts.parse(" super + return "),
                new int[]{Ev.LEFTMETA, Ev.ENTER}), "case and spaces");
        check(Arrays.equals(Shortcuts.parse("SUPER+1"), new int[]{Ev.LEFTMETA, Ev.K1}), "digit");
        check(Arrays.equals(Shortcuts.parse("PRINT"), new int[]{Ev.SYSRQ}), "PRINT");
        check(Arrays.equals(Shortcuts.parse("SUPER+comma"), new int[]{Ev.LEFTMETA, Ev.COMMA}), "comma");
        check(Arrays.equals(Shortcuts.parse("ctrl+alt+delete"),
                new int[]{Ev.LEFTCTRL, Ev.LEFTALT, Ev.DELETE}), "ctrl alt delete");
        check(Shortcuts.parse("SUPER+NOPE") == null, "unknown key rejected");
        check(Shortcuts.parse("") == null, "empty rejected");
        check(Shortcuts.parse("SUPER+") == null, "dangling plus rejected");
        check(Shortcuts.parse("MAX_X11_CODE") == null && Shortcuts.parse("LOCAL_FN") == null,
                "internal constants are not keys");

        List<Shortcuts.Item> defaults = Shortcuts.decode(Shortcuts.DEFAULTS);
        check(defaults.size() == 10, "ten defaults");
        for (Shortcuts.Item it : defaults) check(Shortcuts.parse(it.chord) != null, "default " + it.chord);

        List<Shortcuts.Item> round = Shortcuts.decode(Shortcuts.encode(Arrays.asList(
                new Shortcuts.Item("Tab\there", "SUPER+W"), new Shortcuts.Item("Line\nbreak", "PRINT"))));
        check(round.size() == 2 && round.get(0).label.equals("Tab here")
                && round.get(1).label.equals("Line break"), "separators cannot corrupt storage");

        System.out.println("ShortcutsCheck ok: " + defaults.size() + " defaults parse");
    }
}
