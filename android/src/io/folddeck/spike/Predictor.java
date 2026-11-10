package io.folddeck.spike;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

/**
 * What the keyboard guesses comes next, from the keys it has just sent.
 *
 * The phone never sees the laptop's text, only its own keystrokes, so this
 * follows the word being typed and gives up the moment it cannot know where
 * the cursor is (arrows, Tab, a shortcut, backspacing out of the word) until
 * the next space or Enter. Two answers come out: how likely each letter is
 * next (the keyboard leans edge taps towards likely letters, as phone
 * keyboards do) and up to three words to offer.
 *
 * Nothing typed is stored or leaves this object.
 */
final class Predictor {
    private static final String[] NONE = new String[0];
    /** evdev code for 'a' + i, and back. */
    private static final int[] CODE = new int[26];
    private static final int[] LETTER = new int[256];
    static {
        Arrays.fill(LETTER, -1);
        String[] rows = {"qwertyuiop", "asdfghjkl", "zxcvbnm"};
        int[] first = {Ev.Q, Ev.A, Ev.Z};
        for (int r = 0; r < rows.length; r++) {
            for (int i = 0; i < rows[r].length(); i++) {
                int letter = rows[r].charAt(i) - 'a';
                CODE[letter] = first[r] + i;
                LETTER[first[r] + i] = letter;
            }
        }
    }

    /** 0..25 for a letter key, -1 for anything else. */
    static int letter(int code) {
        return code >= 0 && code < LETTER.length ? LETTER[code] : -1;
    }

    static int code(char letter) {
        return letter >= 'a' && letter <= 'z' ? CODE[letter - 'a'] : -1;
    }

    // Sorted by word, so everything sharing a prefix is one run.
    private volatile String[] words = NONE;
    private volatile int[] counts = new int[0];
    private volatile HashMap<String, String[]> followers = new HashMap<>();
    private volatile float[] firstLetters;

    private final StringBuilder word = new StringBuilder();
    private String previous;
    /** False while the cursor may be somewhere this cannot see. */
    private boolean known = true;
    /** Ctrl/Alt/Super held now. A set: locking a modifier sends its key-down twice and key-up once. */
    private final java.util.Set<Integer> chordKeys = new java.util.HashSet<>();

    /** {@code words}: "word count" lines. {@code next}: "word a b c" lines; may be null. */
    void load(BufferedReader wordLines, BufferedReader nextLines) throws IOException {
        ArrayList<String[]> rows = new ArrayList<>();
        for (String line; (line = wordLines.readLine()) != null; ) {
            String[] p = line.split(" ");
            if (p.length == 2) rows.add(p);
        }
        rows.sort((a, b) -> a[0].compareTo(b[0]));
        String[] w = new String[rows.size()];
        int[] c = new int[rows.size()];
        for (int i = 0; i < w.length; i++) {
            w[i] = rows.get(i)[0];
            try { c[i] = Integer.parseInt(rows.get(i)[1]); } catch (NumberFormatException e) { c[i] = 1; }
        }
        HashMap<String, String[]> f = new HashMap<>();
        if (nextLines != null) {
            for (String line; (line = nextLines.readLine()) != null; ) {
                String[] p = line.split(" ");
                if (p.length > 1) f.put(p[0], Arrays.copyOfRange(p, 1, p.length));
            }
        }
        counts = c;
        words = w;
        followers = f;
        firstLetters = null;
    }

    boolean loaded() { return words.length > 0; }

    /** Feed every key event the keyboard sends. True when the guesses may have changed. */
    boolean key(int code, boolean down) {
        if (code == Ev.LEFTSHIFT || code == Ev.RIGHTSHIFT) return false;
        if (Ev.isModifier(code)) {          // Ctrl, Alt, Super: a shortcut, not text
            if (down) chordKeys.add(code); else chordKeys.remove(code);
            lose();
            return true;
        }
        if (!down) return false;
        if (!chordKeys.isEmpty()) { lose(); return true; }
        int letter = letter(code);
        if (letter >= 0) {
            if (known) word.append((char) ('a' + letter));
        } else if (code == Ev.SPACE) {
            previous = known && word.length() > 0 ? word.toString() : null;
            word.setLength(0);
            known = true;
        } else if (code == Ev.ENTER || code == Ev.DOT || code == Ev.COMMA
                || code == Ev.SEMICOLON || code == Ev.SLASH) {
            previous = null;
            word.setLength(0);
            known = true;
        } else if (code == Ev.BACKSPACE && known && word.length() > 0) {
            word.setLength(word.length() - 1);
        } else {
            lose();
        }
        return true;
    }

    /** Forget everything, e.g. when the connection drops. */
    void reset() {
        word.setLength(0);
        previous = null;
        known = true;
        chordKeys.clear();
    }

    /** The cursor moved somewhere unseen (a click): no guesses until the next space or Enter. */
    void lost() { lose(); }

    private void lose() {
        word.setLength(0);
        previous = null;
        known = false;
    }

    /** Probability of each letter coming next, or null when there is no opinion. */
    float[] weights() {
        if (!known || words.length == 0) return null;
        if (word.length() == 0) {
            float[] cached = firstLetters;
            if (cached == null) firstLetters = cached = spread("");
            return cached;
        }
        return spread(word.toString());
    }

    private float[] spread(String prefix) {
        String[] w = words;
        int[] c = counts;
        float[] out = new float[26];
        double total = 0;
        for (int i = start(w, prefix); i < w.length && w[i].startsWith(prefix); i++) {
            if (w[i].length() == prefix.length()) continue;
            int letter = w[i].charAt(prefix.length()) - 'a';
            if (letter < 0 || letter > 25) continue;
            out[letter] += c[i];
            total += c[i];
        }
        if (total <= 0) return null;   // nothing in the dictionary continues this: no bias
        for (int i = 0; i < 26; i++) out[i] /= total;
        return out;
    }

    /** Up to three words: completions of the word in progress, else what tends to follow the last one. */
    String[] suggestions() {
        if (!known || words.length == 0) return NONE;
        if (word.length() == 0) {
            String[] f = previous == null ? null : followers.get(previous);
            return f == null ? NONE : f;
        }
        String prefix = word.toString();
        String[] w = words;
        int[] c = counts;
        int[] best = {-1, -1, -1};
        for (int i = start(w, prefix); i < w.length && w[i].startsWith(prefix); i++) {
            if (w[i].length() == prefix.length()) continue;
            int at = i;
            for (int k = 0; k < 3; k++) {
                if (best[k] < 0 || c[at] > c[best[k]]) {
                    int t = best[k];
                    best[k] = at;
                    at = t;
                    if (at < 0) break;
                }
            }
        }
        int n = best[0] < 0 ? 0 : best[1] < 0 ? 1 : best[2] < 0 ? 2 : 3;
        String[] out = new String[n];
        for (int k = 0; k < n; k++) out[k] = w[best[k]];
        return out;
    }

    /** The letters still to type for a chosen suggestion, then a space. */
    String rest(String suggestion) {
        String typed = word.toString();
        return (suggestion.startsWith(typed) ? suggestion.substring(typed.length()) : suggestion) + " ";
    }

    private static int start(String[] w, String prefix) {
        int at = Arrays.binarySearch(w, prefix);
        return at >= 0 ? at : -at - 1;
    }
}
