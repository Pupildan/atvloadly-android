package com.bitxeno.atvloadly;

import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Home hub: large title plus a grouped list of the main flows (devices,
 * sideload, installed apps, accounts). Every row pushes its own panel, keeping
 * this screen static and fast while the heavy work lives in the child pages.
 */
public class HomePage extends Page implements Nav.Panel {

    /** Callback the host uses to open a child panel. */
    public interface Host {
        void openDevices();

        void openSideload();

        void openApps();

        void openAccounts();
    }

    private final Host host;

    public HomePage(Ui ui, Host host) {
        super(ui);
        this.host = host;
        setBackAction(null);
    }

    @Override
    protected String title() {
        return "atvloadly";
    }

    @Override
    protected String subtitle() {
        return "连接 Apple TV 并侧载应用";
    }

    @Override
    protected boolean showBack() {
        return false;
    }

    @Override
    protected View onBuild() {
        LinearLayout wrap = ui.column();
        wrap.setBackgroundColor(ui.color(Ui.CARD_BG));

        LinearLayout.LayoutParams gp = ui.match();
        gp.topMargin = ui.dp(4);

        LinearLayout group = ui.group();
        group.setPadding(0, 0, 0, 0);
        wrap.addView(group, gp);

        addRow(group, "设", "设备", "扫描并配对 Apple TV", new Runnable() {
            public void run() {
                host.openDevices();
            }
        }, true);
        group.addView(ui.divider());
        addRow(group, "载", "侧载", "选择 IPA 安装到设备", new Runnable() {
            public void run() {
                host.openSideload();
            }
        }, true);
        group.addView(ui.divider());
        addRow(group, "应", "已装应用", "查看与管理已安装的应用", new Runnable() {
            public void run() {
                host.openApps();
            }
        }, true);
        group.addView(ui.divider());
        addRow(group, "账", "账户", "管理已登录的 Apple ID", new Runnable() {
            public void run() {
                host.openAccounts();
            }
        }, true);

        android.widget.TextView foot = ui.footnote("本地服务运行在 127.0.0.1:" + Backend.PORT);
        LinearLayout.LayoutParams fp = ui.match();
        fp.topMargin = ui.dp(18);
        wrap.addView(foot, fp);

        return wrap;
    }

    /** One tappable settings-style row with avatar, title, subtitle, chevron. */
    private void addRow(LinearLayout parent, String initial, String title,
                        String subtitle, final Runnable action, boolean chevron) {
        LinearLayout row = ui.row();
        row.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(ui.avatar(title, initial, 36));

        LinearLayout textCol = ui.column();
        textCol.setBackgroundColor(ui.color(Ui.CARD_BG));
        LinearLayout.LayoutParams tcp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tcp.leftMargin = ui.dp(12);
        textCol.addView(ui.heading(title), ui.match());
        TextView sub = ui.sub(subtitle);
        LinearLayout.LayoutParams sp = ui.match();
        sp.topMargin = ui.dp(2);
        textCol.addView(sub, sp);
        row.addView(textCol, tcp);

        if (chevron) row.addView(ui.chevron());

        ui.clickable(row, action);
        parent.addView(row, ui.match());
    }
}
