package com.bitxeno.atvloadly;

import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;

/**
 * Splash / backend-readiness page.
 *
 * Shown while the embedded Go backend boots. It polls GET /api/version in the
 * background (owned by Backend) and reports state here. On a boot failure it
 * offers a Retry action instead of a dead screen.
 */
public class SplashPage extends Page implements Nav.Panel {

    private LinearLayout statusBox;
    private android.widget.TextView statusText;
    private LinearLayout spinnerSlot;
    private Button retry;
    private final Runnable onReady;

    public SplashPage(Ui ui, Runnable onReady) {
        super(ui);
        this.onReady = onReady;
        setBackAction(null);
    }

    @Override
    protected String title() {
        return "";
    }

    @Override
    protected boolean showBack() {
        return false;
    }

    @Override
    public View build() {
        LinearLayout root = ui.column();
        root.setGravity(Gravity.CENTER);
        root.setPadding(ui.dp(40), 0, ui.dp(40), 0);

        android.widget.TextView wordmark = ui.bigTitle("atvloadly");
        wordmark.setGravity(Gravity.CENTER);
        root.addView(wordmark, ui.wrap());

        android.widget.TextView tag = ui.sub("Apple TV 侧载工具");
        tag.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tp = ui.wrap();
        tp.topMargin = ui.dp(6);
        root.addView(tag, tp);

        spinnerSlot = new LinearLayout(ui.ctx);
        spinnerSlot.setGravity(Gravity.CENTER);
        spinnerSlot.addView(ui.spinner());
        LinearLayout.LayoutParams sp = ui.wrap();
        sp.topMargin = ui.dp(40);
        root.addView(spinnerSlot, sp);

        statusText = ui.sub("正在启动本地服务…");
        statusText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams stp = ui.wrap();
        stp.topMargin = ui.dp(14);
        root.addView(statusText, stp);

        statusBox = new LinearLayout(ui.ctx);
        statusBox.setOrientation(LinearLayout.VERTICAL);
        statusBox.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams bp = ui.match();
        bp.topMargin = ui.dp(24);
        root.addView(statusBox, bp);

        return root;
    }

    /** Update the boot status line. */
    public void setStatus(String text) {
        if (statusText != null) statusText.setText(text);
    }

    /** Swap the spinner for a fatal error and a Retry button. */
    public void showError(String message) {
        if (spinnerSlot != null) spinnerSlot.removeAllViews();
        if (statusText != null) {
            statusText.setText("启动失败");
            statusText.setTextColor(ui.color(Ui.RED));
        }
        if (statusBox != null) {
            statusBox.removeAllViews();
            android.widget.TextView msg = ui.sub(message);
            msg.setGravity(Gravity.CENTER);
            msg.setTextColor(ui.color(Ui.SECONDARY));
            statusBox.addView(msg, ui.wrap());
            Button b = ui.button("重试", 1);
            LinearLayout.LayoutParams lp = ui.wrap();
            lp.topMargin = ui.dp(16);
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (onReady != null) onReady.run();
                }
            });
            statusBox.addView(b, lp);
        }
    }

    /** Transition out once the backend reports ready. */
    public void finishSplash() {
        if (onReady != null) onReady.run();
    }

    // Page hooks unused on splash.
    @Override
    protected View onBuild() {
        return new View(ui.ctx);
    }
}
