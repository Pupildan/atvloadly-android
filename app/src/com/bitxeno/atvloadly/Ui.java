package com.bitxeno.atvloadly;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Small factory for iOS-styled widgets shared by every panel.
 *
 * Centralizes the design tokens (SF blue accent, grouped-card radii, type scale)
 * and the few composites the screens reuse (cards, pills, segmented control,
 * switch-style rows, progress bars) so each panel stays focused on its content.
 */
public final class Ui {

    // ---- Design tokens -----------------------------------------------------
    public static final String BLUE = "#0A84FF";
    public static final String TEXT = "#1C1C1E";
    public static final String SECONDARY = "#8E8E93";
    public static final String TERTIARY = "#C7C7CC";
    public static final String SEPARATOR = "#E5E5EA";
    public static final String GROUPED_BG = "#F2F2F7";
    public static final String CARD_BG = "#FFFFFF";
    public static final String GREEN = "#34C759";
    public static final String RED = "#FF3B30";
    public static final String ORANGE = "#FF9500";

    /** Host context; package-visible so panels can build their own views. */
    final android.content.Context ctx;
    private final float density;

    public Ui(android.content.Context ctx) {
        this.ctx = ctx;
        this.density = ctx.getResources().getDisplayMetrics().density;
    }

    public int dp(float v) {
        return Math.round(v * density);
    }

    public int color(String hex) {
        return Color.parseColor(hex);
    }

    // ---- Text --------------------------------------------------------------

    /** Large iOS title. */
    public TextView bigTitle(String text) {
        TextView t = base(text, 30, TEXT, Typeface.BOLD);
        t.setLetterSpacing(-0.02f);
        return t;
    }

    /** Nav-bar / section heading. */
    public TextView heading(String text) {
        return base(text, 17, TEXT, Typeface.BOLD);
    }

    /** Standard body label. */
    public TextView body(String text) {
        return base(text, 16, TEXT, Typeface.NORMAL);
    }

    /** Secondary / placeholder label. */
    public TextView sub(String text) {
        return base(text, 14, SECONDARY, Typeface.NORMAL);
    }

    /** Small footnote. */
    public TextView footnote(String text) {
        return base(text, 12, SECONDARY, Typeface.NORMAL);
    }

    /** Monospaced log line for progress consoles. */
    public TextView mono(String text) {
        TextView t = base(text, 12, SECONDARY, Typeface.NORMAL, true);
        return t;
    }

    /** Public alias so Page can build the oversized back chevron. */
    public TextView base(String text, int sp, String color, int style) {
        return base(text, sp, color, style, false);
    }

    /** Variant accepting a resolved color int (e.g. Color.WHITE for avatars). */
    public TextView base(String text, int sp, int colorInt, int style, boolean monoFont) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(colorInt);
        if (monoFont) {
            t.setTypeface(Typeface.MONOSPACE, style);
        } else {
            t.setTypeface(Typeface.DEFAULT, style);
        }
        t.setLineSpacing(dp(2), 1.0f);
        return t;
    }

    private TextView base(String text, int sp, String color, int style, boolean monoFont) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(color(color));
        if (monoFont) {
            t.setTypeface(Typeface.MONOSPACE, style);
        } else {
            t.setTypeface(Typeface.DEFAULT, style);
        }
        t.setLineSpacing(dp(2), 1.0f);
        return t;
    }

    /** White card background with a subtle hairline border (bottom bar). */
    public GradientDrawable cardBorder(String hex) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color(hex));
        bg.setStroke(Math.max(1, dp(0.5f)), color(SEPARATOR));
        return bg;
    }

    // ---- Layout ------------------------------------------------------------

    /** Vertical container with default no background. */
    public LinearLayout column() {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackgroundColor(color(CARD_BG));
        l.setClipToPadding(false);
        return l;
    }

    /** Horizontal container. */
    public LinearLayout row() {
        LinearLayout l = column();
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    /** Vertical spacing spacer. */
    public View gap(float dp) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(dp)));
        return v;
    }

    /** Thin iOS grouped-list separator. */
    public View divider() {
        View v = new View(ctx);
        v.setBackgroundColor(color(SEPARATOR));
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.5f))));
        return v;
    }

    /** Layout params shorthand for a match-parent width child. */
    public LinearLayout.LayoutParams match() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    /** Layout params shorthand for a wrap-content child. */
    public LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    /** Weighted width params so siblings share horizontal space. */
    public LinearLayout.LayoutParams weighted(float weight) {
        return new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, weight);
    }

    /** Weighted params that also stretch to the parent height. */
    public LinearLayout.LayoutParams weightedBoth(float weight) {
        return new LinearLayout.LayoutParams(0, 0, weight);
    }

    /** Remove every child of a ViewGroup, null-safe. */
    public void clear(android.view.ViewGroup g) {
        if (g != null) g.removeAllViews();
    }

    /** Recursively set a click handler on a view tree (used for full-row taps). */
    public void clickable(View v, final Runnable action) {
        v.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
                if (action != null) action.run();
            }
        });
    }

    // ---- Composites --------------------------------------------------------

    /** White rounded card used as a grouped-list container. */
    public LinearLayout card(float radiusDp) {
        LinearLayout c = column();
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color(CARD_BG));
        bg.setCornerRadius(dp(radiusDp));
        c.setBackground(bg);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        return c;
    }

    /** Card pre-set for a grouped settings list (tighter radius). */
    public LinearLayout group() {
        return card(14);
    }

    /**
     * Rounded filled button. style 0 = primary (blue), 1 = tinted,
     * 2 = plain text, 3 = destructive (red).
     */
    public Button button(String label, int style) {
        Button b = new Button(ctx, null, android.R.attr.borderlessButtonStyle);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setPadding(dp(20), dp(12), dp(20), dp(12));

        GradientDrawable bg = new GradientDrawable();
        if (style == 0) {
            bg.setColor(color(BLUE));
            bg.setCornerRadius(dp(14));
            b.setTextColor(Color.WHITE);
        } else if (style == 1) {
            bg.setColor(color("#E8F1FF"));
            bg.setCornerRadius(dp(14));
            b.setTextColor(color(BLUE));
        } else if (style == 3) {
            bg.setColor(color("#FFECEB"));
            bg.setCornerRadius(dp(14));
            b.setTextColor(color(RED));
        } else {
            bg.setColor(Color.TRANSPARENT);
            b.setTextColor(color(BLUE));
        }
        b.setBackground(bg);
        b.setText(label);
        return b;
    }

    /** Primary full-width action button with vertical padding. */
    public Button primaryAction(String label) {
        Button b = button(label, 0);
        b.setTextSize(17);
        b.setPadding(0, dp(14), 0, dp(14));
        return b;
    }

    /** A chevron used to hint a tappable row (iOS detail disclosure). */
    public TextView chevron() {
        TextView t = base("\u203A", 24, TERTIARY, Typeface.NORMAL); // ›
        t.setPadding(dp(6), 0, 0, 0);
        return t;
    }

    /** Deterministic pastel background color derived from a seed string. */
    public int seedColor(String seed) {
        if (seed == null || seed.isEmpty()) return color(TERTIARY);
        int h = 0;
        for (int i = 0; i < seed.length(); i++) h = h * 31 + seed.charAt(i);
        float hue = (h & 0xFFFF) % 360f;
        return android.graphics.Color.HSVToColor(new float[]{hue, 0.45f, 0.85f});
    }

    /** Rounded square app/device avatar tile with an initial. */
    public View avatar(String seed, String initial, float sizeDp) {
        TextView t = base(initial == null || initial.isEmpty() ? "?" : initial, 18,
                Color.WHITE, Typeface.BOLD, false);
        t.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(seedColor(seed));
        bg.setCornerRadius(dp(sizeDp * 0.28f));
        t.setBackground(bg);
        int s = dp(sizeDp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s, s);
        t.setLayoutParams(lp);
        return t;
    }

    /**
     * iOS segmented control. Returns the container; call {@link #styleSegment}
     * to restyle segments after a selection change. listener gets the index.
     */
    public LinearLayout segmented(String[] options, int selected,
                                  final SegmentListener listener) {
        LinearLayout seg = row();
        seg.setPadding(dp(3), dp(3), dp(3), dp(3));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color("#E9E9EB"));
        bg.setCornerRadius(dp(10));
        seg.setBackground(bg);
        seg.setGravity(Gravity.CENTER);

        for (int i = 0; i < options.length; i++) {
            final int idx = i;
            TextView t = base(options[i], 14, TEXT, Typeface.NORMAL);
            t.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            t.setPadding(0, dp(7), 0, dp(7));
            t.setLayoutParams(lp);
            styleSegment(t, i == selected);
            t.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (listener != null) listener.onSelect(idx);
                }
            });
            seg.addView(t);
        }
        return seg;
    }

    /** Interface for segmented-control selection. */
    public interface SegmentListener {
        void onSelect(int index);
    }

    /** Restyle a single segment after a selection change. */
    public void styleSegment(TextView t, boolean selected) {
        GradientDrawable bg = new GradientDrawable();
        if (selected) {
            bg.setColor(Color.WHITE);
            bg.setCornerRadius(dp(8));
            t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            t.setTextColor(color(TEXT));
        } else {
            bg.setColor(Color.TRANSPARENT);
            t.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
            t.setTextColor(color(SECONDARY));
        }
        t.setBackground(bg);
        t.setElevation(selected ? dp(1) : 0);
    }

    /** iOS-style spinner wrapper sized for inline use. */
    public View spinner() {
        android.widget.ProgressBar pb = new android.widget.ProgressBar(ctx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(24), dp(24));
        pb.setLayoutParams(lp);
        android.graphics.drawable.Drawable d = pb.getIndeterminateDrawable();
        if (d != null) {
            d.setColorFilter(color(BLUE), android.graphics.PorterDuff.Mode.SRC_IN);
        }
        return pb;
    }

    /** A full-width horizontal determinate progress bar in the accent color. */
    public android.widget.ProgressBar bar() {
        android.widget.ProgressBar pb = new android.widget.ProgressBar(
                ctx, null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(100);
        pb.getProgressDrawable().setColorFilter(color(BLUE),
                android.graphics.PorterDuff.Mode.SRC_IN);
        return pb;
    }

    /** Small colored status pill with text (used for paired / unpaired). */
    public View pill(String text, String colorHex) {
        TextView t = base(text, 12, colorHex, Typeface.BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(withAlpha(colorHex, 0.16f));
        bg.setCornerRadius(dp(20));
        t.setBackground(bg);
        t.setPadding(dp(10), dp(3), dp(10), dp(3));
        return t;
    }

    /** Parse a hex color and apply an alpha fraction (0..1). */
    public int withAlpha(int color, float alpha) {
        int a = Math.round(255 * alpha);
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color));
    }

    public int withAlpha(String hex, float alpha) {
        return withAlpha(color(hex), alpha);
    }
}
