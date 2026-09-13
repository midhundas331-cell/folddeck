package io.folddeck.spike;

import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Edit, add and delete the deck's shortcuts.
 *
 * Every row is editable in place and nothing is kept until Save, so a half-typed
 * chord never reaches the bar — and Back is always a way out of a mess. Rebuilt
 * each time it opens, like the address editor, so it never shows a stale list.
 */
class ShortcutsView extends Panel {

    interface Listener {
        void onSaved(List<Shortcuts.Item> items);
    }

    private final LinearLayout rows;
    private final List<Row> list = new ArrayList<>();
    private final TextView status;

    ShortcutsView(Context c, List<Shortcuts.Item> items, Listener listener, Runnable onBack) {
        super(c, "Shortcuts", onBack);
        LinearLayout body = content();

        body.addView(Ui.body(c, "Tap a shortcut on the deck to send it to the laptop; "
                + "long-press the bar to come back here. Join keys with +, the way "
                + "Hyprland writes them: SUPER+SHIFT+B, CTRL+ALT+DELETE, PRINT."), Ui.fillW(c, 12));

        rows = new LinearLayout(c);
        rows.setOrientation(LinearLayout.VERTICAL);
        body.addView(rows, Ui.fillW(c, 8));
        for (Shortcuts.Item it : items) addRow(it.label, it.chord);

        body.addView(Ui.secondaryButton(c, "Add shortcut", () -> addRow("", "").label.requestFocus()),
                Ui.fillW(c, 12));

        status = Ui.text(c, "", Ui.T_LABEL, Ui.WARN);
        status.setVisibility(GONE);
        body.addView(status, Ui.fillW(c, 12));

        body.addView(Ui.primaryButton(c, "Save", () -> {
            List<Shortcuts.Item> out = collect();
            if (out != null) listener.onSaved(out);
        }), Ui.fillW(c, 12));

        body.addView(Ui.dangerButton(c, "Reset to Omarchy defaults", () -> {
            rows.removeAllViews();
            list.clear();
            for (Shortcuts.Item it : Shortcuts.decode(Shortcuts.DEFAULTS)) addRow(it.label, it.chord);
            showStatus("Defaults restored. Save to keep them.");
        }), Ui.fillW(c, 10));
    }

    /** The edited list, or null (with the reason shown) if a chord does not parse. */
    private List<Shortcuts.Item> collect() {
        List<Shortcuts.Item> out = new ArrayList<>();
        for (Row r : list) {
            String label = r.label.getText().toString().trim();
            String chord = r.chord.getText().toString().trim().toUpperCase(Locale.US);
            if (label.isEmpty() && chord.isEmpty()) continue;   // an untouched "Add" row
            if (Shortcuts.parse(chord) == null) {
                r.chord.requestFocus();
                showStatus("Fix the highlighted shortcut before saving.");
                return null;
            }
            out.add(new Shortcuts.Item(label.isEmpty() ? chord : label, chord));
        }
        return out;
    }

    private void showStatus(String text) {
        status.setText(text);
        status.setVisibility(VISIBLE);
    }

    private Row addRow(String label, String chord) {
        Row r = new Row(getContext(), label, chord);
        list.add(r);
        rows.addView(r.view, Ui.fillW(getContext(), 8));
        return r;
    }

    /** One shortcut: name, chord, delete — and a warning naming the key it can't read. */
    private final class Row {
        final LinearLayout view;
        final EditText label, chord;

        Row(Context c, String labelText, String chordText) {
            view = new LinearLayout(c);
            view.setOrientation(LinearLayout.VERTICAL);
            view.setPadding(Ui.dp(c, 10), Ui.dp(c, 10), Ui.dp(c, 10), Ui.dp(c, 10));
            view.setBackground(Ui.outlined(Ui.SURFACE, Ui.LINE, 1, c));

            LinearLayout line = new LinearLayout(c);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);

            label = Ui.input(c, labelText, "Name", InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            chord = Ui.input(c, chordText, "SUPER+KEY", InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            line.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            LinearLayout.LayoutParams chordLp =
                    new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f);
            chordLp.leftMargin = Ui.dp(c, 8);
            line.addView(chord, chordLp);

            TextView delete = Ui.text(c, "✕", Ui.T_TITLE, Ui.ERR);
            delete.setGravity(Gravity.CENTER);
            delete.setBackground(Ui.pressable(Ui.square(Ui.SURFACE)));
            delete.setContentDescription("Delete shortcut");
            delete.setOnClickListener(v -> {
                rows.removeView(view);
                list.remove(this);
            });
            LinearLayout.LayoutParams delLp = new LinearLayout.LayoutParams(Ui.dp(c, 44), Ui.dp(c, 44));
            delLp.leftMargin = Ui.dp(c, 6);
            line.addView(delete, delLp);
            view.addView(line);

            TextView warning = Ui.text(c, "", Ui.T_LABEL, Ui.WARN);
            warning.setPadding(Ui.dp(c, 2), Ui.dp(c, 6), 0, 0);
            warning.setVisibility(GONE);
            view.addView(warning);

            chord.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) { }
                @Override public void onTextChanged(CharSequence s, int a, int b, int d) { }
                @Override public void afterTextChanged(Editable e) {
                    String problem = problem(e.toString());
                    warning.setText(problem == null ? "" : problem);
                    warning.setVisibility(problem == null ? GONE : VISIBLE);
                    chord.setBackground(Ui.outlined(Ui.SURFACE_HI,
                            problem == null ? Ui.LINE : Ui.WARN, 1, c));
                }
            });
        }
    }

    /** What is wrong with a chord, in words, or null if it parses (or is still empty). */
    static String problem(String chord) {
        if (chord.trim().isEmpty() || Shortcuts.parse(chord) != null) return null;
        for (String part : chord.trim().split("\\s*\\+\\s*", -1)) {
            if (part.isEmpty()) return "A + needs a key on both sides.";
            if (Shortcuts.parse(part) == null) return "Unknown key: " + part.toUpperCase(Locale.US);
        }
        return "Not a shortcut.";
    }
}
