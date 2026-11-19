package com.bitxeno.atvloadly;

import android.app.Activity;
import android.view.View;
import android.view.animation.AlphaAnimation;

import java.util.ArrayList;
import java.util.List;

/**
 * Manual single-Activity navigation: a stack of full-screen panels.
 *
 * Each panel is a plain View subtree created once and cached by its class, so
 * revisiting a tab is instant and preserves its scroll position. show() pushes
 * a panel with a short fade; back() pops. The stack never grows past the panels
 * actually visited, keeping memory bounded.
 */
public final class Nav {

    /** A full-screen panel. Implementations build their view once in build(). */
    public interface Panel {
        View build();
    }

    private final Activity activity;
    private final android.widget.FrameLayout container;
    private final List<Panel> stack = new ArrayList<>();

    public Nav(Activity activity, android.widget.FrameLayout container) {
        this.activity = activity;
        this.container = container;
    }

    /** Show a panel, pushing it onto the stack unless it is already on top. */
    public void push(Panel panel) {
        if (!stack.isEmpty() && stack.get(stack.size() - 1) == panel) return;
        stack.add(panel);
        render(panel, true);
    }

    /** Replace the whole stack with a single root panel (used after splash). */
    public void setRoot(Panel panel) {
        stack.clear();
        stack.add(panel);
        render(panel, false);
    }

    /** Pop to the previous panel; returns false when already at the root. */
    public boolean back() {
        if (stack.size() <= 1) return false;
        stack.remove(stack.size() - 1);
        render(stack.get(stack.size() - 1), false);
        return true;
    }

    /** The panel currently on top, or null. */
    public Panel current() {
        return stack.isEmpty() ? null : stack.get(stack.size() - 1);
    }

    private void render(Panel panel, boolean animate) {
        View v = panel.build();
        container.removeAllViews();
        container.addView(v, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        if (animate) {
            AlphaAnimation a = new AlphaAnimation(0f, 1f);
            a.setDuration(180);
            v.startAnimation(a);
        }
    }
}
