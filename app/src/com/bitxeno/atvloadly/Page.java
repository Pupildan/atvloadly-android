package com.bitxeno.atvloadly;

import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ScrollView;

/**
 * Shared page scaffold for the native panels.
 *
 * Gives each screen an iOS large-title header (optional back button and right
 * action), a scrollable white body, and an optional pinned bottom action bar.
 * Subclasses implement {@link #onBuild} to fill the body.
 */
public abstract class Page {

    protected final Ui ui;
    private LinearLayout header;
    private LinearLayout headerRow;
    private TextView bigTitle;
    private LinearLayout rightSlot;

    protected Page(Ui ui) {
        this.ui = ui;
    }

    /** Header title text. */
    protected abstract String title();

    /** Build the scrollable body content. */
    protected abstract View onBuild();

    /** Header subtitle shown under the large title; null for none. */
    protected String subtitle() {
        return null;
    }

    /** Whether to show a back chevron (false for root pages). */
    protected boolean showBack() {
        return true;
    }

    /** Called when the hosting panel becomes visible again. */
    protected void onShow() {
    }

    /** Assemble the full page: header + body + optional bottom bar. */
    public View build() {
        LinearLayout page = ui.column();
        page.setBackgroundColor(Color.WHITE);

        header = new LinearLayout(ui.ctx);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(10));
        headerRow = new LinearLayout(ui.ctx);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        if (showBack()) {
            TextView back = ui.base("\u2039", 34, Ui.BLUE, Typeface_normal()); // ‹
            back.setPadding(0, 0, ui.dp(12), 0);
            back.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    onBack();
                }
            });
            headerRow.addView(back);
        }

        bigTitle = ui.bigTitle(title());
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        bigTitle.setLayoutParams(tp);
        headerRow.addView(bigTitle);

        rightSlot = new LinearLayout(ui.ctx);
        rightSlot.setOrientation(LinearLayout.HORIZONTAL);
        rightSlot.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.addView(rightSlot);

        header.addView(headerRow);

        String sub = subtitle();
        if (sub != null) {
            TextView s = ui.sub(sub);
            LinearLayout.LayoutParams sp = ui.match();
            sp.topMargin = ui.dp(2);
            s.setLayoutParams(sp);
            header.addView(s);
        }
        LinearLayout.LayoutParams hp = ui.match();
        page.addView(header, hp);

        ScrollView scroll = new ScrollView(ui.ctx);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        LinearLayout body = new LinearLayout(ui.ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(ui.dp(20), ui.dp(4), ui.dp(20), ui.dp(24));
        scroll.addView(body, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        View content = onBuild();
        body.addView(content, ui.match());
        LinearLayout.LayoutParams scp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        page.addView(scroll, scp);

        View bottom = bottomBar();
        if (bottom != null) {
            LinearLayout bar = new LinearLayout(ui.ctx);
            bar.setOrientation(LinearLayout.VERTICAL);
            bar.setPadding(ui.dp(20), ui.dp(10), ui.dp(20), ui.dp(18));
            bar.setBackground(ui.cardBorder(Ui.CARD_BG));
            bar.addView(bottom, ui.match());
            page.addView(bar, ui.match());
        }
        return page;
    }

    /** Optional pinned bottom action bar content; null for none. */
    protected View bottomBar() {
        return null;
    }

    /** Navigate back; override to intercept (e.g. confirm discard). */
    protected void onBack() {
        // replaced per-instance in Page constructors via setBackAction
        if (defaultBack != null) defaultBack.run();
    }

    private Runnable defaultBack;

    /** Wire the header back button to a custom action (typically Nav.back). */
    public void setBackAction(Runnable r) {
        this.defaultBack = r;
    }

    /** Set the header large title text after build. */
    protected void setTitle(String t) {
        if (bigTitle != null) bigTitle.setText(t);
    }

    /** Replace the header right-side action view (pass null to clear). */
    public void setRightAction(View v) {
        if (rightSlot == null) return;
        rightSlot.removeAllViews();
        if (v != null) rightSlot.addView(v);
    }

    // Keep the import list small by referencing Typeface via a helper.
    private static int Typeface_normal() {
        return android.graphics.Typeface.NORMAL;
    }
}
