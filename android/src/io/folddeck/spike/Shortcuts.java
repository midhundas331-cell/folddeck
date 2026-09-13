package io.folddeck.spike;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The shortcut bar's contents: named key chords, written the way Hyprland binds
 * are ("SUPER+SHIFT+B"), so a line copied out of `hyprctl binds` works as-is.
 *
 * Stored in prefs as "label\tchord" lines. Everything here except load/save is
 * plain Java, so the parser is checked on the JVM (test/ShortcutsCheck.java).
 */
final class Shortcuts {

    private Shortcuts() { }

    static final class Item {
        final String label, chord;

        Item(String label, String chord) {
            this.label = label;
            this.chord = chord;
        }
    }

    /** Omarchy's stock binds, as `hyprctl binds` reported them on 2026-09-13. */
    static final String DEFAULTS =
            "Menu\tSUPER+SPACE\n"
                    + "Apps\tSUPER+ALT+SPACE\n"
                    + "Terminal\tSUPER+RETURN\n"
                    + "Browser\tSUPER+SHIFT+B\n"
                    + "Files\tSUPER+SHIFT+F\n"
                    + "Close\tSUPER+W\n"
                    + "Fullscreen\tSUPER+F\n"
                    + "Next space\tSUPER+TAB\n"
                    + "Screenshot\tPRINT\n"
                    + "Themes\tSUPER+CTRL+SHIFT+SPACE\n";

    /** Hyprland's and everyday names for keys whose evdev constant is spelled otherwise. */
    private static final String[][] ALIASES = {
            {"SUPER", "LEFTMETA"}, {"META", "LEFTMETA"}, {"WIN", "LEFTMETA"},
            {"CTRL", "LEFTCTRL"}, {"CONTROL", "LEFTCTRL"},
            {"ALT", "LEFTALT"}, {"SHIFT", "LEFTSHIFT"},
            {"RETURN", "ENTER"}, {"ESCAPE", "ESC"}, {"PRINT", "SYSRQ"}, {"PRTSC", "SYSRQ"},
            {"DEL", "DELETE"}, {"INS", "INSERT"}, {"PERIOD", "DOT"},
            {"PGUP", "PAGEUP"}, {"PGDN", "PAGEDOWN"},
    };

    private static Map<String, Integer> names;

    /** Every Ev key constant by name, plus the aliases and bare digits. */
    private static synchronized Map<String, Integer> names() {
        if (names != null) return names;
        Map<String, Integer> m = new HashMap<>();
        for (Field f : Ev.class.getDeclaredFields()) {
            int mod = f.getModifiers();
            if (f.getType() != int.class || !Modifier.isStatic(mod)) continue;
            if (f.getName().equals("MAX_X11_CODE") || f.getName().equals("LOCAL_FN")) continue;
            try {
                m.put(f.getName(), f.getInt(null));
            } catch (IllegalAccessException ignored) {
                // Ev's fields are package-private statics; this cannot happen
            }
        }
        for (String[] a : ALIASES) m.put(a[0], m.get(a[1]));
        for (int d = 0; d <= 9; d++) m.put(String.valueOf(d), m.get("K" + d));
        return names = m;
    }

    /** The evdev codes of a chord, in press order, or null if any key is unknown. */
    static int[] parse(String chord) {
        String[] parts = chord.trim().toUpperCase(Locale.US).split("\\s*\\+\\s*", -1);
        if (parts.length == 0 || parts[0].isEmpty()) return null;
        int[] codes = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            Integer code = names().get(parts[i]);
            if (code == null || code <= 0 || code > Ev.MAX_X11_CODE) return null;
            codes[i] = code;
        }
        return codes;
    }

    static List<Item> decode(String stored) {
        List<Item> items = new ArrayList<>();
        for (String line : stored.split("\n")) {
            int tab = line.indexOf('\t');
            if (tab <= 0) continue;
            items.add(new Item(line.substring(0, tab), line.substring(tab + 1)));
        }
        return items;
    }

    static String encode(List<Item> items) {
        StringBuilder sb = new StringBuilder();
        for (Item it : items) {
            // Tabs and newlines are the record separators; nothing typed may contain them.
            sb.append(it.label.replaceAll("[\t\n]", " ")).append('\t')
                    .append(it.chord.replaceAll("[\t\n]", " ")).append('\n');
        }
        return sb.toString();
    }

    static List<Item> load(android.content.SharedPreferences prefs) {
        return decode(prefs.getString(Prefs.SHORTCUTS, DEFAULTS));
    }

    static void save(android.content.SharedPreferences prefs, List<Item> items) {
        prefs.edit().putString(Prefs.SHORTCUTS, encode(items)).apply();
    }
}
