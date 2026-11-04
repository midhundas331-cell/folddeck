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
 * Omarchy supplies the palette and the material language, copied from its
 * shell (Commons/Style.qml): corner radius 0, 1px borders at 40% foreground,
 * flat fills at 4/8/18/22% foreground (normal/hover/selected/pressed), no
 * shadows, no gradients, JetBrains Mono everywhere.
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
    private static int ACCENT_INK;
    /** The hairline between menu sections: 20% foreground, as Omarchy's menu. */
    static int SEPARATOR;
    /** Ripple tint: Omarchy's pressed fill, 22% foreground. */
    static int PRESS;
    /** The same pressed fill, opaque, for controls drawn on a canvas. */
    static int PRESSED;

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
        TEXT_DIM = mix(bg, fg, 0.82f);
        TEXT_FAINT = mix(bg, fg, 0.74f);
        PRESS = (fg & 0x00ffffff) | 0x38000000;
        PRESSED = mix(bg, fg, 0.22f);

        ACCENT = pick(m, "accent", fg);
        ACCENT_INK = contrastInk(ACCENT);
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

    // Shared type scale; the dashboard adds its own display-size headline.
    static final float T_TITLE = 24f, T_BODY = 15f,
            T_LABEL = 13f, T_MICRO = 11f;
    /** Z-order tiers for views that must sit above the desktop; never a shadow. */
    static final float FLOAT_MID_DP = 2f;
    static final float FLOAT_ELEVATION_DP = 6f;

    /** Omarchy sets every surface in JetBrains Mono, so FONT is the mono face too. */
    static Typeface FONT = Typeface.MONOSPACE, FONT_BOLD = Typeface.MONOSPACE;
    static Typeface MONO = Typeface.MONOSPACE, MONO_BOLD = Typeface.MONOSPACE;
    /** The handful of Nerd Font icons Omarchy's menu uses (assets/fonts/Icons.ttf). */
    static Typeface ICONS = Typeface.MONOSPACE;

    static void loadFonts(Context c) {
        try {
            MONO = FONT = Typeface.createFromAsset(c.getAssets(), "fonts/JetBrainsMono-Regular.ttf");
            MONO_BOLD = FONT_BOLD = Typeface.createFromAsset(c.getAssets(), "fonts/JetBrainsMono-Bold.ttf");
            ICONS = Typeface.createFromAsset(c.getAssets(), "fonts/Icons.ttf");
        } catch (RuntimeException e) {
            // A missing asset should cost the typeface, not the app.
            android.util.Log.w("FoldDeck/Ui", "bundled font missing, using monospace", e);
        }
    }

    static int dp(Context c, float value) {
        return Math.round(value * c.getResources().getDisplayMetrics().density);
    }

    /**
     * Every border in the app, in whole pixels: Omarchy's 1px line at the size it
     * has on the laptop (2px on the Fold's 2.6x panel). A whole number matters:
     * 1dp is 2.625px here and antialiases into a soft two-tone edge.
     */
    static int hair(float density) {
        return Math.max(1, Math.round(density * 0.75f));
    }

    static int hair(Context c) {
        return hair(c.getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------------ //
    // Shapes — square, flat, 1px borders.
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

    /** Omarchy's control chrome: a flat fill inside a 1px border. */
    static GradientDrawable box(int fill, int stroke, Context c) {
        GradientDrawable d = square(fill);
        d.setStroke(hair(c), stroke);
        return d;
    }

    /** Opaque page backdrop, including when a live desktop is underneath. */
    static GradientDrawable backdrop() {
        return square(BG);
    }

    /** Choose legible text for a filled accent in both dark and light themes. */
    static int onAccent() {
        return ACCENT_INK;
    }

    private static int contrastInk(int colour) {
        double luminance = 0;
        for (int i = 0; i < 3; i++) {
            double channel = ((colour >> (16 - i * 8)) & 255) / 255.0;
            channel = channel <= 0.04045 ? channel / 12.92
                    : Math.pow((channel + 0.055) / 1.055, 2.4);
            luminance += channel * (i == 0 ? 0.2126 : i == 1 ? 0.7152 : 0.0722);
        }
        return luminance > 0.179 ? Color.BLACK : Color.WHITE;
    }

    static <T extends View> T elevate(T view, Context c) {
        return elevate(view, c, FLOAT_ELEVATION_DP);
    }

    /** Lift a view above its siblings without a shadow: Omarchy draws none. */
    static <T extends View> T elevate(T view, Context c, float elevationDp) {
        view.setElevation(dp(c, elevationDp));
        view.setOutlineProvider(null);
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
    // Keycaps — the one control look shared by the keyboard, the trackpad's
    // buttons and the shortcut chips, so the deck reads as one object.
    // ------------------------------------------------------------------ //
    /** Square like everything in Omarchy; kept for the views that size by it. */
    static final float CAP_RADIUS_DP = 0f;

    /**
     * A keycap in {@code l,t,r,b}: a flat fill inside a hairline rim, snapped to
     * whole pixels so the rim stays sharp. Callers pick the colours; pressed is
     * {@link #PRESSED} with an {@link #ACCENT} rim. No allocation: the keyboard
     * paints ~75 of these a frame.
     */
    static void drawCap(Canvas c, float l, float t, float r, float b,
                        int fill, int stroke, float density, Paint p) {
        float L = Math.round(l), T = Math.round(t), R = Math.round(r), B = Math.round(b);
        int line = hair(density);
        p.setStyle(Paint.Style.FILL);
        p.setColor(stroke);
        c.drawRect(L, T, R, B, p);
        p.setColor(fill);
        c.drawRect(L + line, T + line, R - line, B - line, p);
    }

    /** The keycap as a Drawable, for View-based controls. */
    static android.graphics.drawable.Drawable cap(int fill, int stroke, Context ctx) {
        final float density = ctx.getResources().getDisplayMetrics().density;
        return new android.graphics.drawable.Drawable() {
            private final Paint p = new Paint();
            private boolean pressed;

            @Override public void draw(Canvas c) {
                android.graphics.Rect b = getBounds();
                drawCap(c, b.left, b.top, b.right, b.bottom, pressed ? PRESSED : fill,
                        pressed ? ACCENT : stroke, density, p);
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
                outline.setRect(getBounds());
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
        TextView t = text(c, content, T_LABEL, TEXT_FAINT);
        t.setTypeface(FONT_BOLD);
        t.setAccessibilityHeading(true);
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
     * Omarchy's selected fill inside an accent border: the accent is the colour
     * of Hyprland's active window border, so it reads as "this one".
     */
    static TextView primaryButton(Context c, String label, Runnable onClick) {
        TextView b = text(c, label, T_BODY, TEXT);
        b.setTypeface(FONT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(c, 24), dp(c, 16), dp(c, 24), dp(c, 16));
        b.setBackground(pressable(box(SELECTED, ACCENT, c)));
        b.setMinimumHeight(dp(c, 56));
        b.setFocusable(true);
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
        b.setBackground(pressable(box(SURFACE, LINE, c)));
        b.setMinimumHeight(dp(c, 52));
        b.setFocusable(true);
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
        r.setMinimumHeight(dp(c, 68));
        r.setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 14));
        if (onClick != null) {
            r.setBackground(pressable(square(Color.TRANSPARENT)));
            r.setClickable(true);
            r.setFocusable(true);
            r.setOnClickListener(v -> onClick.run());
            Motion.press(r);
        }

        LinearLayout stack = new LinearLayout(c);
        stack.setOrientation(LinearLayout.VERTICAL);
        stack.setDuplicateParentStateEnabled(true);
        TextView title = text(c, titleText, 16f, TEXT);
        title.setTypeface(FONT_BOLD);
        title.setTextColor(pressedAccent(TEXT));
        title.setDuplicateParentStateEnabled(true);
        stack.addView(title);
        if (subtitle != null) {
            TextView sub = text(c, subtitle, T_LABEL, TEXT_FAINT);
            sub.setTypeface(FONT);
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
    // Omarchy menu — the look of `omarchy menu`, shared by Home and Settings.
    // ------------------------------------------------------------------ //
    static final String ICON_CHEVRON = "\udb80\udd42";

    /** The menu's bordered square card. */
    static void menuCard(LinearLayout column) {
        Context c = column.getContext();
        column.setBackground(box(SURFACE, LINE, c));
        column.setPadding(dp(c, 12), dp(c, 12), dp(c, 12), dp(c, 20));
        ((LinearLayout.LayoutParams) column.getLayoutParams()).setMargins(
                dp(c, 16), dp(c, 20), dp(c, 16), dp(c, 32));
    }

    /** The dim "Setup…" line at the top of the card. */
    static TextView menuHeading(Context c, String text) {
        TextView t = text(c, text, 28f, TEXT);
        t.setTypeface(FONT_BOLD);
        t.setPadding(dp(c, 16), dp(c, 12), dp(c, 16), dp(c, 18));
        return t;
    }

    /** A bold dim heading over a section of rows, as Omarchy's PanelSectionHeader. */
    static TextView menuSection(Context c, String text) {
        TextView h = text(c, text, T_LABEL, TEXT_FAINT);
        h.setTypeface(FONT_BOLD);
        h.setAccessibilityHeading(true);
        h.setPadding(dp(c, 16), dp(c, 18), dp(c, 16), dp(c, 8));
        return h;
    }

    /** The small note under a section. */
    static TextView menuNote(Context c, String text) {
        TextView f = text(c, text, T_LABEL, TEXT_FAINT);
        f.setTypeface(FONT);
        f.setPadding(dp(c, 16), dp(c, 8), dp(c, 16), dp(c, 16));
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
        t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        t.setPadding(dp(c, 8), 0, 0, 0);
        return t;
    }

    /** A menu row: icon, label, chevron. Child 1 is the label stack. */
    static LinearLayout menuItem(Context c, String icon, String title, Runnable onClick) {
        LinearLayout row = row(c, title, null, chevron(c), onClick);
        TextView glyph = text(c, icon, 22f, ACCENT);
        glyph.setTypeface(ICONS);
        glyph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        glyph.setTextColor(pressedAccent(ACCENT));
        glyph.setDuplicateParentStateEnabled(true);
        glyph.setGravity(Gravity.CENTER);
        glyph.setIncludeFontPadding(false);
        row.addView(glyph, 0, new LinearLayout.LayoutParams(dp(c, 40), dp(c, 40)));
        ((LinearLayout.LayoutParams) row.getChildAt(1).getLayoutParams()).leftMargin = dp(c, 12);
        return row;
    }

    /** A colour that turns accent while its row is pressed. */
    static ColorStateList pressedAccent(int normal) {
        return new ColorStateList(new int[][]{{android.R.attr.state_pressed}, {}},
                new int[]{ACCENT, normal});
    }

    /** Omarchy's square toggle switch with a full 48dp touch target. */
    static View toggle(Context c, boolean on, java.util.function.Consumer<Boolean> onChange) {
        View v = new View(c) {
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            boolean checked = on;
            float position = on ? 1f : 0f;
            // A spring, so tapping again mid-slide turns it around without a jolt.
            final Motion.Anim slide = new Motion.Anim(Motion.SNAPPY, 0.001f, v -> {
                position = v;
                invalidate();
            });

            {
                setClickable(true);
                setFocusable(true);
                setStateDescription(checked ? "On" : "Off");
                setOnClickListener(x -> {
                    checked = !checked;
                    setStateDescription(checked ? "On" : "Off");
                    slide.to(position, checked ? 1f : 0f, null);
                    onChange.accept(checked);
                });
            }

            @Override protected void onDetachedFromWindow() {
                slide.stop();
                position = checked ? 1f : 0f;
                super.onDetachedFromWindow();
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
                // Omarchy's ToggleSwitch on a square theme: off is the normal
                // fill in a 40% border, on is the selected fill with no border.
                float h = dp(c, 24), w = h * 1.9f, x = getWidth() - w, y = (getHeight() - h) / 2f;
                float line = hair(c);
                p.setStyle(Paint.Style.FILL);
                p.setColor(mix(SURFACE, SELECTED, position));
                canvas.drawRect(x, y, x + w, y + h, p);
                if (position < 1f) {
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(line);
                    p.setColor(LINE);
                    canvas.drawRect(x + line / 2, y + line / 2, x + w - line / 2, y + h - line / 2, p);
                    p.setStyle(Paint.Style.FILL);
                }
                float knob = h * 0.72f, inset = (h - knob) / 2f;
                float left = x + inset + position * (w - knob - inset * 2);
                p.setColor(mix(TEXT_FAINT, TEXT, position));
                canvas.drawRect(left, y + inset, left + knob, y + inset + knob, p);
            }
        };
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(c, 56), dp(c, 48));
        lp.leftMargin = dp(c, 12);
        v.setLayoutParams(lp);
        return v;
    }

    /** Segmented selection: Omarchy's ButtonGroup, selected cell in the 18% fill. */
    static View choice(Context c, String[] labels, int selected,
                       java.util.function.IntConsumer onChange) {
        LinearLayout row = new LinearLayout(c);
        row.setBaselineAligned(false);
        TextView[] cells = new TextView[labels.length];
        int[] chosen = {selected};
        Runnable paint = () -> {
            for (int i = 0; i < cells.length; i++) {
                boolean on = i == chosen[0];
                cells[i].setTextColor(on ? TEXT : TEXT_DIM);
                cells[i].setTypeface(on ? FONT_BOLD : FONT);
                cells[i].setBackground(pressable(box(on ? SELECTED : SURFACE,
                        on ? SELECTED : LINE, c)));
                cells[i].setSelected(on);
            }
        };
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView t = text(c, labels[i], T_LABEL + 1, TEXT_DIM);
            t.setGravity(Gravity.CENTER);
            t.setMaxLines(1);
            t.setAutoSizeTextTypeUniformWithConfiguration(9, 14, 1, TypedValue.COMPLEX_UNIT_SP);
            t.setFocusable(true);
            t.setPadding(dp(c, 10), 0, dp(c, 10), 0);
            t.setOnClickListener(v -> {
                chosen[0] = index;
                paint.run();
                Motion.select(t);
                onChange.accept(index);
            });
            cells[i] = t;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(c, 48));
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
                p.setColor(ACCENT);
                canvas.drawRoundRect(r, r - t, x, r + t, t, t, p);
                p.setColor(BG);
                canvas.drawCircle(x, r, knob + ring, p);
                p.setColor(ACCENT);
                canvas.drawCircle(x, r, knob, p);
            }
        };
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c, 112), dp(c, 48)));
        return v;
    }

    /** Single-line inset field with a visible keyboard-focus ring. */
    static android.widget.EditText input(Context c, String text, String hint, int inputType) {
        android.widget.EditText e = new android.widget.EditText(c);
        e.setInputType(inputType);
        e.setSingleLine(true);
        e.setTypeface(MONO);
        e.setTextColor(TEXT);
        e.setHintTextColor(TEXT_FAINT);
        e.setHint(hint);
        android.graphics.drawable.StateListDrawable field = new android.graphics.drawable.StateListDrawable();
        field.addState(new int[]{android.R.attr.state_focused}, box(SURFACE_HI, ACCENT, c));
        field.addState(new int[0], box(SURFACE, LINE, c));
        e.setBackground(field);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, T_BODY);
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
        return readableColumn(c, 640);
    }

    static LinearLayout readableColumn(Context c, int maxWidthDp) {
        LinearLayout col = new LinearLayout(c) {
            @Override
            protected void onMeasure(int widthSpec, int heightSpec) {
                int max = dp(getContext(), maxWidthDp);
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
     * FOLDDECK in block letters, the way Omarchy writes its own name: terminal
     * half-blocks painted as rectangles in the accent, so it scales cleanly.
     */
    static View blockWordmark(Context c, int maxWidthDp) {
        int widest = 0;
        for (String line : WORDMARK) widest = Math.max(widest, line.length());
        final int cols = widest, rows = WORDMARK.length;
        View v = new View(c) {
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
        v.setContentDescription("FoldDeck");
        return v;
    }
}
