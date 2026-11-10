package io.folddeck.spike;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

/** Sizes saved per posture move to the preset that posture has selected; Settings clears every copy. */
public class SizePrefsCheck {
    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    /** A map-backed SharedPreferences whose editor writes straight through. */
    static SharedPreferences prefs(Map<String, Object> m) {
        Object[] self = new Object[1];
        SharedPreferences.Editor ed = (SharedPreferences.Editor) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.Editor.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "putFloat": case "putInt": case "putBoolean": m.put((String) args[0], args[1]); break;
                        case "remove": m.remove((String) args[0]); break;
                        case "apply": return null;
                        case "commit": return true;
                        default: break;
                    }
                    return proxy;
                });
        self[0] = Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "contains": return m.containsKey((String) args[0]);
                        case "edit": return ed;
                        case "getFloat": case "getInt": case "getBoolean":
                            return m.containsKey((String) args[0]) ? m.get((String) args[0]) : args[1];
                        default: return null;
                    }
                });
        return (SharedPreferences) self[0];
    }

    public static void main(String[] args) {
        Map<String, Object> m = new HashMap<>();
        m.put(Prefs.LAYOUT_KEYS[Prefs.POSTURE_PORTRAIT], Prefs.LAYOUT_SPLIT);
        m.put(Prefs.RESIZE_HEIGHT + Prefs.POSTURE_PORTRAIT, 314f);
        m.put(Prefs.RESIZE_BAR + Prefs.POSTURE_PORTRAIT, 0.78f);
        m.put(Prefs.RESIZE_DECK_FLIP + Prefs.POSTURE_PORTRAIT, true);
        SharedPreferences p = prefs(m);
        Prefs.migrateSizes(p);
        int portrait = Prefs.POSTURE_PORTRAIT;
        check(Prefs.resize(p, Prefs.RESIZE_HEIGHT, portrait, Prefs.LAYOUT_SPLIT, -1f) == 314f,
                "the old size belongs to the preset it was tuned in");
        check(Prefs.resize(p, Prefs.RESIZE_HEIGHT, portrait, Prefs.LAYOUT_BOTH, -1f) == -1f,
                "other presets start from their defaults");
        check(!m.containsKey(Prefs.RESIZE_HEIGHT + portrait), "the posture-only key is gone");
        check(m.containsKey(Prefs.RESIZE_DECK_FLIP + portrait), "arrangement flags stay per posture");
        Prefs.migrateSizes(p);
        check(Prefs.resize(p, Prefs.RESIZE_BAR, portrait, Prefs.LAYOUT_SPLIT, 1f) == 0.78f, "migrating twice is harmless");

        SharedPreferences.Editor ed = p.edit();
        Prefs.clearSize(ed, Prefs.RESIZE_BAR);
        ed.apply();
        check(Prefs.resize(p, Prefs.RESIZE_BAR, portrait, Prefs.LAYOUT_SPLIT, 1f) == 1f,
                "a Settings size clears every studio copy");
        check(Prefs.resize(p, Prefs.RESIZE_HEIGHT, portrait, Prefs.LAYOUT_SPLIT, -1f) == 314f,
                "and only that size");
        System.out.println("SizePrefsCheck ok");
    }
}
