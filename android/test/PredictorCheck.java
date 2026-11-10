package io.folddeck.spike;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.Arrays;

/** Word tracking, letter weights and suggestions. Run like the other checks. */
public class PredictorCheck {
    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    private static void type(Predictor p, String s) {
        for (char ch : s.toCharArray()) {
            int code = ch == ' ' ? Ev.SPACE : Predictor.code(ch);
            p.key(code, true);
            p.key(code, false);
        }
    }

    public static void main(String[] args) throws Exception {
        Predictor p = new Predictor();
        check(p.weights() == null && p.suggestions().length == 0, "no dictionary, no opinion");
        p.load(new BufferedReader(new StringReader(
                        "the 1000\nthen 300\nthere 200\nthis 400\nto 900\ntea 50\nand 800\nyou 500\nhello 10\n")),
                new BufferedReader(new StringReader("thank you the all\n")));
        check(Predictor.letter(Ev.Q) == 'q' - 'a' && Predictor.letter(Ev.M) == 'm' - 'a'
                && Predictor.letter(Ev.SPACE) == -1 && Predictor.code('h') == Ev.H, "key map");

        float[] first = p.weights();
        check(first['t' - 'a'] > first['a' - 'a'] && first['z' - 'a'] == 0f, "first letters follow the dictionary");

        type(p, "th");
        float[] w = p.weights();
        check(w['e' - 'a'] > w['i' - 'a'] && w['i' - 'a'] > 0f && w['o' - 'a'] == 0f, "after th: e, then i");
        check(Arrays.equals(p.suggestions(), new String[]{"the", "this", "then"}), "top three by count: "
                + Arrays.toString(p.suggestions()));
        check(p.rest("this").equals("is "), "rest of a suggestion");

        type(p, "e");
        check(Arrays.equals(p.suggestions(), new String[]{"then", "there"}), "exact word is not offered");
        p.key(Ev.BACKSPACE, true);
        check(p.rest("this").equals("is "), "backspace steps back one letter");

        type(p, "xq");
        check(p.weights() == null, "nothing continues thxq: no bias");

        p.reset();
        type(p, "thank ");
        check(Arrays.equals(p.suggestions(), new String[]{"you", "the", "all"}), "followers after a space");

        p.key(Ev.LEFT, true);
        check(p.weights() == null && p.suggestions().length == 0, "an arrow key loses the context");
        type(p, "th");
        check(p.weights() == null, "still lost mid-word");
        type(p, " th");
        check(p.weights() != null, "found again after a space");

        p.reset();
        p.key(Ev.LEFTCTRL, true);
        type(p, "t");
        p.key(Ev.LEFTCTRL, false);
        check(p.weights() == null, "a shortcut is not text");
        p.key(Ev.LEFTSHIFT, true);
        type(p, " t");
        check(p.weights() != null && p.rest("the").equals("he "), "shift is still text");
        p.key(Ev.LEFTMETA, true);    // tap: armed
        p.key(Ev.LEFTMETA, true);    // tap again: locked, a second key-down
        p.key(Ev.LEFTMETA, false);   // third tap: released, one key-up
        type(p, " t");
        check(p.weights() != null, "a locked-then-released modifier does not stick");
        System.out.println("PredictorCheck ok");
    }
}
