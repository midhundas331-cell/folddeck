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
 * The look keeps Omarchy's restrained palette, borders and dense control rhythm,
 * then adds a small product-level depth system: 7dp floating-card corners,
 * top-lit gradients, and 3/6/9dp elevation tiers. Keyboard caps remain machined
 * and square. System sans carries product copy while JetBrains Mono is reserved
 * for machine data, keys and terminal-like controls.
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
    /** The hairline between menu sections: 20% foreground, as Omarchy's menu. */
    static int SEPARATOR;
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
        ERR = pick(m, "red", pick(m, "color1", ACCENT));
        OK = pick(m, "green", pick(m, "color2", ACCENT));
        WARN = pick(m, "yellow", pick(m, "color3", ACCENT));
        SEPARATOR = mix(bg, fg, 0.20f);
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
    static final float FLOAT_LOW_DP = 3f;
    static final float FLOAT_MID_DP = 6f;
    static final float FLOAT_ELEVATION_DP = 9f;
    static final float FLOAT_RADIUS_DP = 7f;

    /** Product copy is quiet system sans; machine data and controls retain Omarchy mono. */
    static Typeface FONT = Typeface.create("sans-serif", Typeface.NORMAL);
    static Typeface FONT_BOLD = Typeface.create("sans-serif", Typeface.BOLD);
    static Typeface MONO = Typeface.MONOSPACE, MONO_BOLD = Typeface.MONOSPACE;
    /** The handful of Nerd Font icons Omarchy's menu uses (assets/fonts/Icons.ttf). */
    static Typeface ICONS = Typeface.MONOSPACE;

    static void loadFonts(Context c) {
        try {
            MONO = Typeface.createFromAsset(c.getAssets(), "fonts/JetBrainsMono-Regular.ttf");
            MONO_BOLD = Typeface.createFromAsset(c.getAssets(), "fonts/JetBrainsMono-Bold.ttf");
            ICONS = Typeface.createFromAsset(c.getAssets(), "fonts/Icons.ttf");
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

    /** A restrained raised surface: light catches the top, shadow leaves below. */
    static GradientDrawable floatingPanel(int fill, int stroke, float widthDp, Context c) {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{mix(fill, TEXT, 0.09f), fill, mix(fill, BG, 0.22f)});
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dp(c, FLOAT_RADIUS_DP));
        d.setStroke(Math.max(1, dp(c, widthDp)), stroke);
        return d;
    }

    static <T extends View> T elevate(T view, Context c) {
        return elevate(view, c, FLOAT_ELEVATION_DP);
    }

    static <T extends View> T elevate(T view, Context c, float elevationDp) {
        view.setElevation(dp(c, elevationDp));
        return view;
    }

    /**
     * Wrap a shape in a ripple.
     *
     * Touch feedback is not decoration here: every control in this app is driven
     * by a thumb on glass with no cursor to show hover, so the ripple is the only
     * confirmation that a press landed at all.
     */
    static RippleDrawable pressable(android.graphics.drawable.Drawable shape) {
        return new RippleDrawable(ColorStateList.valueOf(PRESS), shape, null);
    }

    // ------------------------------------------------------------------ //
    // Keycaps — the one raised-control look shared by the keyboard, the
    // trackpad's buttons and the shortcut chips, so the deck reads as one object.
    // ------------------------------------------------------------------ //
    static final float CAP_RADIUS_DP = 5f, CAP_DEPTH_DP = 2f;

    /**
     * A keycap in {@code l,t,r,b}: a darker lip below (its depth), a body with a
     * one-pixel highlight where light catches the top edge, and a hairline rim.
     * Pressed, the lip disappears and the body sinks into it. No shaders and no
     * allocation, because the keyboard paints ~75 of these a frame.
     */
    static void drawCap(Canvas c, float l, float t, float r, float b,
                        int fill, int stroke, boolean pressed, float density, Paint p) {
        float rad = CAP_RADIUS_DP * density, depth = CAP_DEPTH_DP * density;
        float line = Math.max(1f, density * 0.75f);
        float top = pressed ? t + depth : t, bottom = b - depth;
        p.setStyle(Paint.Style.FILL);
        if (!pressed) {
            p.setColor(mix(fill, BG, 0.55f));
            c.drawRoundRect(l, top + depth, r, b, rad, rad, p);
        }
        p.setColor(mix(fill, TEXT, pressed ? 0.06f : 0.16f));
        c.drawRoundRect(l, top, r, bottom, rad, rad, p);
        p.setColor(fill);
        c.drawRoundRect(l, top + line, r, bottom, rad, rad, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(line);
        p.setColor(stroke);
        c.drawRoundRect(l + line / 2, top + line / 2, r - line / 2, bottom - line / 2,
                rad, rad, p);
        p.setStyle(Paint.Style.FILL);
    }

    /** The keycap as a Drawable, for View-based controls; it sinks while pressed. */
    static android.graphics.drawable.Drawable cap(int fill, int stroke, Context ctx) {
        final float density = ctx.getResources().getDisplayMetrics().density;
        return new android.graphics.drawable.Drawable() {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            private boolean pressed;

            @Override public void draw(Canvas c) {
                android.graphics.Rect b = getBounds();
                drawCap(c, b.left, b.top, b.right, b.bottom, pressed ? mix(fill, ACCENT, 0.25f) : fill,
                        pressed ? ACCENT : stroke, pressed, density, p);
            }

            @Override public boolean isStateful() { return true; }

            @Override protected boolean onStateChange(int[] state) {
                boolean now = false;
                for (int s : state) now |= s == android.R.attr.state_pressed;
                if (now == pressed) return false;
                pressed = now;
                invalidateSelf();
                return true;
            }

            @Override public void getOutline(android.graphics.Outline outline) {
                outline.setRoundRect(getBounds(), CAP_RADIUS_DP * density);
            }

            @Override public void setAlpha(int alpha) { p.setAlpha(alpha); }
            @Override public void setColorFilter(android.graphics.ColorFilter f) { p.setColorFilter(f); }
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        };
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

    /** A small all-caps rubric that separates one group of controls from the next. */
    static TextView sectionLabel(Context c, String content) {
        TextView t = text(c, content.toUpperCase(java.util.Locale.US), T_MICRO, TEXT_FAINT);
        t.setLetterSpacing(0.14f);
        t.setTypeface(MONO_BOLD);
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
        b.setBackground(pressable(floatingPanel(mix(BG, ACCENT, 0.10f), ACCENT, 2, c)));
        elevate(b, c);
        b.setClickable(true);
        Motion.press(b);
        b.setOnClickListener(v -> onClick.run());
        return b;
    }

    /** Everything else: Omarchy's normal control chrome, same footprint as the primary. */
    static TextView secondaryButton(Context c, String label, Runnable onClick) {
        TextView b = text(c, label, T_BODY, TEXT);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(c, 24), dp(c, 16), dp(c, 24), dp(c, 16));
        b.setBackground(pressable(floatingPanel(SURFACE, LINE, 1, c)));
        elevate(b, c);
        b.setClickable(true);
        Motion.press(b);
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
        r.setMinimumHeight(dp(c, 60));
        r.setPadding(dp(c, 14), dp(c, 10), dp(c, 14), dp(c, 10));
        if (onClick != null) {
            // Omarchy's menu cursor: the row fills and its label turns accent.
            android.graphics.drawable.StateListDrawable bg =
                    new android.graphics.drawable.StateListDrawable();
            bg.addState(new int[]{android.R.attr.state_pressed}, square(SELECTED));
            bg.addState(new int[0], square(Color.TRANSPARENT));
            r.setBackground(bg);
            r.setClickable(true);
            r.setOnClickListener(v -> onClick.run());
        }

        LinearLayout stack = new LinearLayout(c);
        stack.setOrientation(LinearLayout.VERTICAL);
        stack.setDuplicateParentStateEnabled(true);
        TextView title = text(c, titleText, 17f, TEXT);
        title.setTypeface(MONO);
        title.setTextColor(pressedAccent(TEXT));
        title.setDuplicateParentStateEnabled(true);
        stack.addView(title);
        if (subtitle != null) {
            TextView sub = text(c, subtitle, T_LABEL, TEXT_FAINT);
            sub.setTypeface(MONO);
            sub.setPadding(0, dp(c, 2), 0, 0);
            stack.addView(sub);
        }
        // weight 1 so the accessory is pinned right however long the text runs.
        r.addView(stack, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (accessory != null) {
            if (accessory.getContentDescription() == null) {
                accessory.setContentDescription(titleText);
            }
            r.addView(accessory);
        }
        return r;
    }

    // ------------------------------------------------------------------ //
    // Omarchy menu — the look of `omarchy menu`, shared by Home, Settings
    // and the guide.
    // ------------------------------------------------------------------ //
    static final String ICON_CHEVRON = "\udb80\udd42";

    /** Frame a column as the menu's card: square, 2px foreground border. */
    static void menuCard(LinearLayout column) {
        Context c = column.getContext();
        column.setBackground(outlined(BG, TEXT, 2, c));
        column.setPadding(dp(c, 14), dp(c, 10), dp(c, 14), dp(c, 18));
        ((LinearLayout.LayoutParams) column.getLayoutParams()).setMargins(
                dp(c, 16), dp(c, 20), dp(c, 16), dp(c, 32));
    }

    /** The dim "Setup…" line at the top of the card. */
    static TextView menuHeading(Context c, String text) {
        TextView t = text(c, text, 17f, TEXT_FAINT);
        t.setTypeface(MONO);
        t.setPadding(dp(c, 2), dp(c, 10), 0, dp(c, 10));
        return t;
    }

    /** A bold dim heading over a section of rows. */
    static TextView menuSection(Context c, String text) {
        TextView h = text(c, text, T_LABEL + 1, TEXT_FAINT);
        h.setTypeface(MONO_BOLD);
        h.setPadding(dp(c, 14), dp(c, 6), dp(c, 14), dp(c, 2));
        return h;
    }

    /** The small note under a section. */
    static TextView menuNote(Context c, String text) {
        TextView f = text(c, text, T_LABEL, TEXT_FAINT);
        f.setTypeface(MONO);
        f.setPadding(dp(c, 14), dp(c, 2), dp(c, 14), dp(c, 8));
        return f;
    }

    /** The hairline between sections. */
    static View divider(Context c) {
        View line = new View(c);
        line.setBackgroundColor(SEPARATOR);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 1)));
        lp.setMargins(dp(c, 4), dp(c, 8), dp(c, 4), dp(c, 8));
        line.setLayoutParams(lp);
        return line;
    }

    static TextView chevron(Context c) {
        TextView t = text(c, ICON_CHEVRON, 18f, TEXT_FAINT);
        t.setTypeface(ICONS);
        t.setPadding(dp(c, 8), 0, 0, 0);
        return t;
    }

    /** A menu row: icon, label, chevron. Child 1 is the label stack. */
    static LinearLayout menuItem(Context c, String icon, String title, Runnable onClick) {
        LinearLayout row = row(c, title, null, chevron(c), onClick);
        TextView glyph = text(c, icon, 30f, TEXT);
        glyph.setTypeface(ICONS);
        glyph.setTextColor(pressedAccent(TEXT));
        glyph.setDuplicateParentStateEnabled(true);
        glyph.setGravity(Gravity.CENTER);
        glyph.setIncludeFontPadding(false);
        row.addView(glyph, 0, new LinearLayout.LayoutParams(dp(c, 44),
                LinearLayout.LayoutParams.WRAP_CONTENT));
        ((LinearLayout.LayoutParams) row.getChildAt(1).getLayoutParams()).leftMargin = dp(c, 8);
        return row;
    }

    /** A colour that turns accent while its row is pressed. */
    static ColorStateList pressedAccent(int normal) {
        return new ColorStateList(new int[][]{{android.R.attr.state_pressed}, {}},
                new int[]{ACCENT, normal});
    }

    /**
     * Omarchy's ToggleSwitch: a square track with a square knob. Off is the
     * normal fill with a 1px border and a dimmed knob; on is the selected fill
     * and a foreground knob at the right.
     */
    static View toggle(Context c, boolean on, java.util.function.Consumer<Boolean> onChange) {
        View v = new View(c) {
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            boolean checked = on;

            {
                setClickable(true);
                setFocusable(true);
                setStateDescription(checked ? "On" : "Off");
                setOnClickListener(x -> {
                    checked = !checked;
                    setStateDescription(checked ? "On" : "Off");
                    invalidate();
                    onChange.accept(checked);
                });
            }

            @Override
            @SuppressWarnings("deprecation")
            public void onInitializeAccessibilityNodeInfo(
                    android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(info);
                info.setClassName(android.widget.Switch.class.getName());
                info.setCheckable(true);
                info.setChecked(checked);
            }

            @Override
            protected void onDraw(Canvas canvas) {
                float w = getWidth(), h = getHeight(), border = Math.max(1, dp(c, 1));
                p.setStyle(Paint.Style.FILL);
                p.setColor(checked ? SELECTED : SURFACE);
                canvas.drawRect(0, 0, w, h, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(border);
                p.setColor(checked ? TEXT : LINE);
                canvas.drawRect(border / 2, border / 2, w - border / 2, h - border / 2, p);
                float knob = h * 0.72f, inset = (h - knob) / 2f;
                float left = checked ? w - knob - inset : inset;
                p.setStyle(Paint.Style.FILL);
                p.setColor(checked ? TEXT : TEXT_FAINT);
                canvas.drawRect(left, inset, left + knob, inset + knob, p);
            }
        };
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(c, 54), dp(c, 28));
        lp.leftMargin = dp(c, 12);
        v.setLayoutParams(lp);
        return v;
    }

    /**
     * Omarchy's ButtonGroup: square cells in a row; the chosen one wears the
     * selected fill, a foreground border and the accent label.
     */
    static View choice(Context c, String[] labels, int selected,
                       java.util.function.IntConsumer onChange) {
        LinearLayout row = new LinearLayout(c);
        TextView[] cells = new TextView[labels.length];
        int[] chosen = {selected};
        Runnable paint = () -> {
            for (int i = 0; i < cells.length; i++) {
                boolean on = i == chosen[0];
                cells[i].setTextColor(on ? ACCENT : TEXT_DIM);
                cells[i].setTypeface(on ? MONO_BOLD : MONO);
                cells[i].setBackground(outlined(on ? SELECTED : SURFACE, on ? TEXT : LINE, 1, c));
                cells[i].setSelected(on);
            }
        };
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView t = text(c, labels[i], T_LABEL + 1, TEXT_DIM);
            t.setGravity(Gravity.CENTER);
            t.setMaxLines(1);
            t.setPadding(dp(c, 10), 0, dp(c, 10), 0);
            t.setOnClickListener(v -> {
                chosen[0] = index;
                paint.run();
                onChange.accept(index);
            });
            cells[i] = t;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(c, 42));
            if (i > 0) lp.leftMargin = dp(c, 5);
            row.addView(t, lp);
        }
        paint.run();
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(c, 12);
        row.setLayoutParams(lp);
        return row;
    }

    /** Omarchy's PanelSlider: a rounded track in the selected fill, foreground fill and knob. */
    static View slider(Context c, float value, float min, float max,
                       java.util.function.Consumer<Float> onChange) {
        View v = new View(c) {
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            float frac = (value - min) / (max - min);

            {
                setFocusable(true);
                setClickable(true);
                updateState();
            }

            void setFraction(float value) {
                frac = Math.max(0f, Math.min(1f, value));
                updateState();
                invalidate();
                onChange.accept(min + frac * (max - min));
            }

            void updateState() {
                float current = min + frac * (max - min);
                setStateDescription(String.format(java.util.Locale.US, "%.2f", current));
            }

            @Override
            public boolean onTouchEvent(android.view.MotionEvent e) {
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                    case android.view.MotionEvent.ACTION_MOVE:
                    case android.view.MotionEvent.ACTION_UP:
                        // The knob has width, so the usable travel is inset by
                        // half of it at each end; without that the value can
                        // never quite reach min or max.
                        float knob = getHeight();
                        float span = Math.max(getWidth() - knob, 1);
                        setFraction((e.getX() - knob / 2f) / span);
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
            @SuppressWarnings("deprecation")
            public void onInitializeAccessibilityNodeInfo(
                    android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(info);
                info.setClassName(android.widget.SeekBar.class.getName());
                info.setRangeInfo(android.view.accessibility.AccessibilityNodeInfo.RangeInfo.obtain(
                        android.view.accessibility.AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT,
                        min, max, min + frac * (max - min)));
                info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
                info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
                info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);
            }

            @Override
            public boolean performAccessibilityAction(int action, android.os.Bundle args) {
                int setProgress = android.view.accessibility.AccessibilityNodeInfo
                        .AccessibilityAction.ACTION_SET_PROGRESS.getId();
                if (action == setProgress && args != null) {
                    float requested = args.getFloat(android.view.accessibility
                            .AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE);
                    setFraction((requested - min) / (max - min));
                    return true;
                }
                if (action == android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                        || action == android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
                    setFraction(frac + (action == android.view.accessibility
                            .AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ? 0.1f : -0.1f));
                    return true;
                }
                return super.performAccessibilityAction(action, args);
            }

            @Override
            protected void onDraw(Canvas canvas) {
                float w = getWidth(), h = getHeight(), r = h / 2f;
                float x = r + frac * Math.max(w - h, 1), t = dp(c, 2.5f);
                float knob = dp(c, 10), ring = dp(c, 2);
                p.setStyle(Paint.Style.FILL);
                p.setColor(SELECTED);
                canvas.drawRoundRect(r, r - t, w - r, r + t, t, t, p);
                p.setColor(TEXT);
                canvas.drawRoundRect(r, r - t, x, r + t, t, t, p);
                p.setColor(BG);
                canvas.drawCircle(x, r, knob + ring, p);
                p.setColor(TEXT);
                canvas.drawCircle(x, r, knob, p);
            }
        };
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c, 112), dp(c, 28)));
        return v;
    }

    /** A single-line text field in Omarchy's input chrome. */
    static android.widget.EditText input(Context c, String text, String hint, int inputType) {
        android.widget.EditText e = new android.widget.EditText(c);
        e.setInputType(inputType);
        e.setSingleLine(true);
        e.setTypeface(MONO);
        e.setTextColor(TEXT);
        e.setHintTextColor(TEXT_FAINT);
        e.setHint(hint);
        e.setBackground(outlined(SURFACE_HI, LINE, 1, c));
        e.setPadding(dp(c, 14), dp(c, 14), dp(c, 14), dp(c, 14));
        e.setText(text);
        return e;
    }

    /** Vertical spacer, in dp. */
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
