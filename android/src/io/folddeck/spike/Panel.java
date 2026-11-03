package io.folddeck.spike;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * A full-screen sheet with a title bar and a scrolling body.
 *
 * Settings, the address editor and the shortcut editor all use this one
 * implementation, so they never grow different header heights or back buttons.
 */
class Panel extends FrameLayout {

    private final LinearLayout content;
    private final TextView title;
    private final ScrollView scroll;

    Panel(Context c, String titleText, Runnable onBack) {
        super(c);
        setBackground(Ui.backdrop());
        setClickable(true);   // never let touches through to the deck behind

        // -------- header --------------------------------------------------- //
        LinearLayout header = new LinearLayout(c);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(Ui.dp(c, 16), 0, Ui.dp(c, 24), 0);
        header.setBackground(Ui.floatingPanel(Ui.SURFACE, android.graphics.Color.TRANSPARENT, 0, c));

        TextView back = Ui.text(c, "‹", 34f, Ui.TEXT);
        back.setGravity(Gravity.CENTER);
        back.setBackground(Ui.pressable(Ui.rounded(Ui.SURFACE, Ui.SEPARATOR, 14, c)));
        back.setContentDescription("Back");
        back.setClickable(true);
        Motion.press(back);
        back.setOnClickListener(v -> { if (!up()) onBack.run(); });
        // A 44dp target: the chevron glyph itself is far too small to hit.
        back.setFocusable(true);
        header.addView(back, new LinearLayout.LayoutParams(Ui.dp(c, 48), Ui.dp(c, 48)));

        title = Ui.text(c, titleText, Ui.T_TITLE, Ui.TEXT);
        title.setTypeface(Ui.FONT_BOLD);
        title.setAccessibilityHeading(true);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        titleLp.leftMargin = Ui.dp(c, 16);
        header.addView(title, titleLp);

        View hairline = new View(c);
        hairline.setBackgroundColor(Ui.SEPARATOR);

        // -------- body ----------------------------------------------------- //
        content = Ui.readableColumn(c);
        content.setPadding(Ui.dp(c, 24), Ui.dp(c, 12), Ui.dp(c, 24), Ui.dp(c, 40));

        scroll = new ScrollView(c);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout centre = new LinearLayout(c);
        centre.setOrientation(LinearLayout.VERTICAL);
        centre.setGravity(Gravity.CENTER_HORIZONTAL);
        centre.addView(content, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        scroll.addView(centre);

        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(c, 80)));
        root.addView(hairline, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(c, 0.5f))));
        // weight 1 so the scroll area takes everything below the header.
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        addView(root, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    /** A sheet with sub-pages steps up one level here; true if it did. */
    boolean up() {
        return false;
    }

    /** Retitle the header and jump back to the top, for a sheet that pages. */
    void showPage(String titleText) {
        title.setText(titleText);
        scroll.scrollTo(0, 0);
    }

    void enterPage(boolean returning) {
        Motion.page(content, returning ? -1 : 1);
    }

    /** Where subclasses add their rows. */
    LinearLayout content() {
        return content;
    }
}
