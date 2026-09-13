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
 * Settings and the guide are the same object with different contents, so they
 * share one implementation — that is what stops the two screens from slowly
 * growing different header heights and different back buttons.
 */
class Panel extends FrameLayout {

    private final LinearLayout content;

    Panel(Context c, String titleText, Runnable onBack) {
        super(c);
        setBackgroundColor(Ui.BG);
        setClickable(true);   // never let touches through to the deck behind

        // -------- header --------------------------------------------------- //
        LinearLayout header = new LinearLayout(c);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(Ui.dp(c, 8), 0, Ui.dp(c, 20), 0);

        TextView back = Ui.text(c, "‹", 30f, Ui.TEXT);
        back.setGravity(Gravity.CENTER);
        back.setBackground(Ui.pressable(Ui.square(Ui.BG)));
        back.setClickable(true);
        back.setOnClickListener(v -> onBack.run());
        // A 44dp target: the chevron glyph itself is far too small to hit.
        header.addView(back, new LinearLayout.LayoutParams(Ui.dp(c, 44), Ui.dp(c, 44)));

        TextView title = Ui.text(c, titleText, Ui.T_TITLE, Ui.TEXT);
        title.setTypeface(Ui.FONT_BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        titleLp.leftMargin = Ui.dp(c, 6);
        header.addView(title, titleLp);

        View hairline = new View(c);
        hairline.setBackgroundColor(Ui.LINE);

        // -------- body ----------------------------------------------------- //
        content = Ui.readableColumn(c);
        content.setPadding(Ui.dp(c, 20), Ui.dp(c, 4), Ui.dp(c, 20), Ui.dp(c, 40));

        ScrollView scroll = new ScrollView(c);
        scroll.setFillViewport(true);
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
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(c, 56)));
        root.addView(hairline, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(c, 0.5f))));
        // weight 1 so the scroll area takes everything below the header.
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        addView(root, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    /** Where subclasses add their rows. */
    LinearLayout content() {
        return content;
    }
}
