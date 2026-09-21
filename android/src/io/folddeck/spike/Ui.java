package io.folddeck.spike;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;

/**
 * The design system: one palette, one type scale, one set of shapes.
 *
 * Every screen builds itself from these factories rather than setting its own
 * colours and sizes inline. That is the whole reason the app looks deliberate
 * instead of assembled — a card in Settings and a card on Home are the same
 * object, so they cannot drift apart as either screen is edited.
 *
 * The look is Omarchy's shell, copied rather than approximated: square corners
 * (Omarchy's Style.cornerRadius follows Hyprland's rounding, which is 0), 1px
 * borders at 40% foreground, fills at 4% / 8% / 18% / 22% foreground for
 * normal / hover / selected / pressed, and JetBrains Mono throughout.
 *
 * There is no res/values/styles.xml because there is no Gradle: aapt2 is only
 * fed the launcher icon, and everything else is built in Java. This class is
 * what a theme would have been.
 */
final class Ui {

    private Ui() { }

    // ------------------------------------------------------------------ //
    // Palette
    //
    // Not final: the laptop sends its Omarchy colors.toml down the stream and
    // apply() swaps it in (see MainActivity.applyTheme). Until the first
    // connection it is Omarchy's stock theme, Tokyo Night; after that it is
    // whatever the laptop last sent, cached in prefs.
    // ------------------------------------------------------------------ //
    static final String DEFAULT_PALETTE =
            "background=#1a1b26\nforeground=#a9b1d6\naccent=#7aa2f7\n"
                    + "red=#f7768e\nyellow=#e0af68\ngreen=#9ece6a\n";

    static int BG, SURFACE, SURFACE_HI, SELECTED, LINE;
    static int TEXT, TEXT_DIM, TEXT_FAINT;
    static int ACCENT, OK, WARN, ERR;
    /** Ripple tint: Omarchy's pressed fill, 22% foreground. */
    static int PRESS;

    static {
        apply(DEFAULT_PALETTE);
    }

    /** Swap in a palette of "key=#rrggbb" lines; missing keys keep a sane fallback. */
    static void apply(String palette) {
        Map<String, Integer> m = new HashMap<>();
        for (String line : palette.split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            try {
                m.put(line.substring(0, eq).trim(), Color.parseColor(line.substring(eq + 1).trim()));
            } catch (IllegalArgumentException ignored) {
                // one bad value should not cost the whole theme
            }
        }
        int bg = pick(m, "background", Color.BLACK);
        int fg = pick(m, "foreground", Color.WHITE);

        BG = bg;
        TEXT = fg;
        SURFACE = mix(bg, fg, 0.04f);
        SURFACE_HI = mix(bg, fg, 0.08f);
        SELECTED = mix(bg, fg, 0.18f);
        LINE = mix(bg, fg, 0.40f);
        TEXT_DIM = mix(bg, fg, 0.72f);
        TEXT_FAINT = mix(bg, fg, 0.48f);
        PRESS = (fg & 0x00ffffff) | 0x38000000;

        ACCENT = pick(m, "accent", fg);
        OK = pick(m, "green", ACCENT);
        WARN = pick(m, "yellow", ACCENT);
        ERR = pick(m, "red", ACCENT);
    }

    private static int pick(Map<String, Integer> m, String key, int fallback) {
        Integer v = m.get(key);
        return v == null ? fallback : v;
    }

    /** {@code t} of the way from {@code a} to {@code b}, opaque. */
    static int mix(int a, int b, float t) {
        return Color.rgb(
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    // Type scale. Four sizes, not fifteen — the restraint is the point.
    static final float T_TITLE = 19f, T_BODY = 15f,
            T_LABEL = 13f, T_MICRO = 11f;

    /** Omarchy's default font. Bundled, since the phone has no JetBrains Mono. */
    static Typeface FONT = Typeface.MONOSPACE, FONT_BOLD = Typeface.MONOSPACE;

    static void loadFonts(Context c) {
        try {
            FONT = Typeface.createFromAsset(c.getAssets(), "fonts/JetBrainsMono-Regular.ttf");
            FONT_BOLD = Typeface.createFromAsset(c.getAssets(), "fonts/JetBrainsMono-Bold.ttf");
        } catch (RuntimeException e) {
            // A missing asset should cost the typeface, not the app.
            android.util.Log.w("FoldDeck/Ui", "bundled font missing, using monospace", e);
        }
    }

    static int dp(Context c, float value) {
        return Math.round(value * c.getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------------ //
    // Shapes — all square, as Omarchy's are.
    // ------------------------------------------------------------------ //
    static GradientDrawable square(int fill) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fill);
        return d;
    }

    static GradientDrawable outlined(int fill, int stroke, float widthDp, Context c) {
        GradientDrawable d = square(fill);
        d.setStroke(Math.max(1, dp(c, widthDp)), stroke);
        return d;
    }

    /**
     * Wrap a shape in a ripple.
     *
     * Touch feedback is not decoration here: every control in this app is driven
     * by a thumb on glass with no cursor to show hover, so the ripple is the only
     * confirmation that a press landed at all.
     */
    static RippleDrawable pressable(GradientDrawable shape) {
        return new RippleDrawable(ColorStateList.valueOf(PRESS), shape, null);
    }

    // ------------------------------------------------------------------ //
    // Text
    // ------------------------------------------------------------------ //
    static TextView text(Context c, String content, float sizeSp, int colour) {
        TextView t = new TextView(c);
        t.setText(content);
        t.setTextColor(colour);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        t.setTypeface(FONT);
        t.setLineSpacing(dp(c, 3), 1f);
        return t;
    }

    static TextView title(Context c, String content) {
        TextView t = text(c, content, T_TITLE, TEXT);
        t.setTypeface(FONT_BOLD);
        return t;
    }

    /** A small all-caps rubric that separates one group of controls from the next. */
    static TextView sectionLabel(Context c, String content) {
        TextView t = text(c, content.toUpperCase(java.util.Locale.US), T_MICRO, TEXT_FAINT);
        t.setLetterSpacing(0.14f);
        t.setTypeface(FONT_BOLD);
        t.setPadding(dp(c, 4), dp(c, 20), 0, dp(c, 8));
        return t;
    }

    static TextView body(Context c, String content) {
        return text(c, content, T_BODY, TEXT_DIM);
    }

    // ------------------------------------------------------------------ //
    // Controls
    // ------------------------------------------------------------------ //
    /**
     * The one loud button on a screen. There should never be two.
     *
     * Omarchy has no filled buttons; its loudest control is the lock screen's
     * field, outlined in the accent. This is that outline.
     */
    static TextView primaryButton(Context c, String label, Runnable onClick) {
        TextView b = text(c, label, T_BODY, ACCENT);
        b.setTypeface(FONT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(c, 24), dp(c, 16), dp(c, 24), dp(c, 16));
        b.setBackground(pressable(outlined(mix(BG, ACCENT, 0.08f), ACCENT, 2, c)));
        b.setClickable(true);
        b.setOnClickListener(v -> onClick.run());
        return b;
    }

    /** Everything else: Omarchy's normal control chrome, same footprint as the primary. */
    static TextView secondaryButton(Context c, String label, Runnable onClick) {
        TextView b = text(c, label, T_BODY, TEXT);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(c, 24), dp(c, 16), dp(c, 24), dp(c, 16));
        b.setBackground(pressable(outlined(SURFACE, LINE, 1, c)));
        b.setClickable(true);
        b.setOnClickListener(v -> onClick.run());
        return b;
    }

    /** Destructive actions are red-on-quiet, never red-filled — they are rare. */
    static TextView dangerButton(Context c, String label, Runnable onClick) {
        TextView b = secondaryButton(c, label, onClick);
        b.setTextColor(ERR);
        return b;
    }

    /**
     * A tappable settings row: title, explanatory line, and an optional value on
     * the right. The explanation is not optional in spirit — a control whose
     * effect needs guessing is a control that gets left alone.
     */
    static LinearLayout row(Context c, String titleText, String subtitle,
                            View accessory, Runnable onClick) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 14));
        GradientDrawable shape = outlined(SURFACE, LINE, 1, c);
        r.setBackground(onClick == null ? shape : pressable(shape));
        if (onClick != null) {
            r.setClickable(true);
            r.setOnClickListener(v -> onClick.run());
        }

        LinearLayout stack = new LinearLayout(c);
        stack.setOrientation(LinearLayout.VERTICAL);
        stack.addView(text(c, titleText, T_BODY, TEXT));
        if (subtitle != null) {
            TextView sub = text(c, subtitle, T_LABEL, TEXT_DIM);
            sub.setPadding(0, dp(c, 3), 0, 0);
            stack.addView(sub);
        }
        // weight 1 so the accessory is pinned right however long the text runs.
        r.addView(stack, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (accessory != null) r.addView(accessory);
        return r;
    }

    /**
     * Omarchy's ToggleSwitch on sharp corners: a square track with a square knob.
     * Off is the normal fill with a 1px border and a dimmed knob; on is the
     * selected fill, no border, and a foreground knob at the right.
     */
    static View toggle(Context c, boolean on, java.util.function.Consumer<Boolean> onChange) {
        View v = new View(c) {
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            boolean checked = on;

            {
                setClickable(true);
                setOnClickListener(x -> {
                    checked = !checked;
                    invalidate();
                    onChange.accept(checked);
                });
            }

            @Override
            protected void onDraw(Canvas canvas) {
                float w = getWidth(), h = getHeight(), border = Math.max(1, dp(c, 1));
                p.setStyle(Paint.Style.FILL);
                p.setColor(checked ? SELECTED : SURFACE);
                canvas.drawRect(0, 0, w, h, p);
                if (!checked) {
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(border);
                    p.setColor(LINE);
                    canvas.drawRect(border / 2, border / 2, w - border / 2, h - border / 2, p);
                }
                float knob = h * 0.72f, inset = (h - knob) / 2f;
                float left = checked ? w - knob - inset : inset;
                p.setStyle(Paint.Style.FILL);
                p.setColor(checked ? TEXT : TEXT_FAINT);
                canvas.drawRect(left, inset, left + knob, inset + knob, p);
            }
        };
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(c, 46), dp(c, 24));
        lp.leftMargin = dp(c, 12);
        v.setLayoutParams(lp);
        return v;
    }

    /**
     * A square-knobbed slider over a 1px track, matching {@link #toggle}.
     *
     * Hand-drawn rather than a themed SeekBar for the same reason the toggle is:
     * SeekBar's knob is a round drawable inside a ripple halo, and the work to
     * beat that into square corners is more than the work to draw a line and a
     * rectangle.
     */
    static View slider(Context c, float value, float min, float max,
                       java.util.function.Consumer<Float> onChange) {
        View v = new View(c) {
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            float frac = (value - min) / (max - min);

            @Override
            public boolean onTouchEvent(android.view.MotionEvent e) {
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                    case android.view.MotionEvent.ACTION_MOVE:
                    case android.view.MotionEvent.ACTION_UP:
                        // The knob has width, so the usable travel is inset by
                        // half of it at each end; without that the value can
                        // never quite reach min or max.
                        float knob = getHeight() * 0.55f;
                        float span = Math.max(getWidth() - knob, 1);
                        frac = Math.max(0f, Math.min(1f, (e.getX() - knob / 2f) / span));
                        invalidate();
                        onChange.accept(min + frac * (max - min));
                        // Claim the gesture or the scrolling settings list
                        // steals it the moment the finger drifts vertically.
                        getParent().requestDisallowInterceptTouchEvent(
                                e.getActionMasked() != android.view.MotionEvent.ACTION_UP);
                        return true;
                    default:
                        return super.onTouchEvent(e);
                }
            }

            @Override
            protected void onDraw(Canvas canvas) {
                float w = getWidth(), h = getHeight();
                float knob = h * 0.55f, span = Math.max(w - knob, 1);
                float x = knob / 2f + frac * span, mid = h / 2f;

                p.setStyle(Paint.Style.FILL);
                p.setColor(LINE);
                canvas.drawRect(0, mid - 0.5f, w, mid + 0.5f, p);
                p.setColor(ACCENT);
                canvas.drawRect(0, mid - 0.5f, x, mid + 0.5f, p);
                canvas.drawRect(x - knob / 2f, mid - h / 2f, x + knob / 2f, mid + h / 2f, p);
            }
        };
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c, 112), dp(c, 24)));
        return v;
    }

    /** A single-line text field in Omarchy's input chrome. */
    static android.widget.EditText input(Context c, String text, String hint, int inputType) {
        android.widget.EditText e = new android.widget.EditText(c);
        e.setInputType(inputType);
        e.setSingleLine(true);
        e.setTypeface(FONT);
        e.setTextColor(TEXT);
        e.setHintTextColor(TEXT_FAINT);
        e.setHint(hint);
        e.setBackground(outlined(SURFACE_HI, LINE, 1, c));
        e.setPadding(dp(c, 14), dp(c, 14), dp(c, 14), dp(c, 14));
        e.setText(text);
        return e;
    }

    /** Vertical spacer, in dp. */
    static View gap(Context c, int heightDp) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(c, heightDp)));
        return v;
    }

    static LinearLayout.LayoutParams fillW(Context c, int marginTopDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, marginTopDp);
        return lp;
    }

    /**
     * Cap a column's width and centre it.
     *
     * The inner display is nearly square and very wide in landscape; text run
     * edge to edge across it is unreadable, and a settings row a foot wide looks
     * like a mistake. Every screen puts its content in one of these.
     */
    static LinearLayout readableColumn(Context c) {
        LinearLayout col = new LinearLayout(c) {
            @Override
            protected void onMeasure(int widthSpec, int heightSpec) {
                int max = dp(getContext(), 560);
                int w = MeasureSpec.getSize(widthSpec);
                if (w > max) {
                    widthSpec = MeasureSpec.makeMeasureSpec(max, MeasureSpec.EXACTLY);
                }
                super.onMeasure(widthSpec, heightSpec);
            }
        };
        col.setOrientation(LinearLayout.VERTICAL);
        return col;
    }

    // ------------------------------------------------------------------ //
    // Wordmark
    // ------------------------------------------------------------------ //
    /** "FOLDDECK" in the block letters of Omarchy's own branding art. */
    private static final String[] WORDMARK = {
            "▄████████  ▄███████▄  ▄██        ████████▄  ████████▄  ▄████████  ▄████████  ███   ▄██",
            "███   ███  ███   ███  ███        ███   ███  ███   ███  ███   ███  ███   ███  ███  ▄██▀",
            "███   █▀   ███   ███  ███        ███   ███  ███   ███  ███   █▀   ███   █▀   ███ ▄██▀ ",
            "███▄▄▄     ███   ███  ███        ███   ███  ███   ███  ███▄▄▄     ███        ███▄██▀  ",
            "███▀▀▀     ███   ███  ███        ███   ███  ███   ███  ███▀▀▀     ███        ███▀██▄  ",
            "███        ███   ███  ███        ███   ███  ███   ███  ███   █▄   ███   █▄   ███ ▀██▄ ",
            "███        ███   ███  ███        ███   ███  ███   ███  ███   ███  ███   ███  ███  ▀██▄",
            "███        ▀███████▀  █████████  ████████▀  ████████▀  █████████  ▀████████  ███   ▀██",
    };

    /**
     * Drawn as rectangles on a character grid rather than set as text: block
     * glyphs only meet edge to edge at a terminal's exact cell height, and a
     * TextView's line metrics leave hairline gaps between the rows.
     */
    static View wordmark(Context c, int maxWidthDp) {
        int widest = 0;
        for (String line : WORDMARK) widest = Math.max(widest, line.length());
        final int cols = widest, rows = WORDMARK.length;
        return new View(c) {
            final Paint p = new Paint();

            @Override
            protected void onMeasure(int ws, int hs) {
                int w = Math.min(MeasureSpec.getSize(ws), dp(c, maxWidthDp));
                // Terminal cells are about twice as tall as wide.
                setMeasuredDimension(w, Math.round(w / (float) cols * 2.1f * rows));
            }

            @Override
            protected void onDraw(Canvas canvas) {
                float cw = getWidth() / (float) cols, ch = getHeight() / (float) rows;
                p.setColor(ACCENT);
                for (int y = 0; y < rows; y++) {
                    for (int x = 0; x < WORDMARK[y].length(); x++) {
                        char g = WORDMARK[y].charAt(x);
                        float top = y * ch, left = x * cw;
                        if (g == '█') canvas.drawRect(left, top, left + cw + 0.5f, top + ch + 0.5f, p);
                        else if (g == '▀') canvas.drawRect(left, top, left + cw + 0.5f, top + ch / 2, p);
                        else if (g == '▄') canvas.drawRect(left, top + ch / 2, left + cw + 0.5f, top + ch + 0.5f, p);
                    }
                }
            }
        };
    }
}
