package com.bitxeno.atvloadly;

import android.app.AlertDialog;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Installed-apps page: lists records from GET /api/apps and offers per-app
 * reinstall (POST /api/apps/:id/reinstall) and delete (POST /api/apps/:id/delete).
 *
 * Each record exposes ipa_name / custom_name / version / device / account /
 * expiration_date; the display name falls back from custom_name to ipa_name.
 */
public class AppListPage extends Page implements Nav.Panel {

    private final Ui ui;
    private LinearLayout listBox;
    private LinearLayout stateBox;
    private TextView stateText;
    private View spinner;
    private JSONArray apps = new JSONArray();

    public AppListPage(Ui ui) {
        super(ui);
        this.ui = ui;
    }

    @Override
    protected String title() {
        return "已装应用";
    }

    @Override
    protected String subtitle() {
        return "签名有效期 7 天，过期需重新侧载";
    }

    @Override
    protected View onBuild() {
        LinearLayout col = ui.column();
        col.setBackgroundColor(ui.color(Ui.CARD_BG));

        stateBox = new LinearLayout(ui.ctx);
        stateBox.setOrientation(LinearLayout.VERTICAL);
        stateBox.setGravity(Gravity.CENTER);
        stateBox.setPadding(0, ui.dp(28), 0, ui.dp(28));
        spinner = ui.spinner();
        stateBox.addView(spinner);
        stateText = ui.sub("正在加载已装应用…");
        LinearLayout.LayoutParams sp = ui.wrap();
        sp.topMargin = ui.dp(12);
        stateBox.addView(stateText, sp);
        col.addView(stateBox, ui.match());

        listBox = new LinearLayout(ui.ctx);
        listBox.setOrientation(LinearLayout.VERTICAL);
        col.addView(listBox, ui.match());

        return col;
    }

    @Override
    protected void onShow() {
        super.onShow();
        refresh();
    }

    private void refresh() {
        showState(true, "正在加载已装应用…");
        Task.run(new Task.Job<Json.Result>() {
            public Json.Result run() {
                return Api.apps();
            }
        }, new Task.Done<Json.Result>() {
            public void onDone(Json.Result r) {
                if (r.ok) {
                    apps = Json.dataArray(r);
                    render();
                } else {
                    showState(false, "加载失败：" + r.error);
                }
            }
        });
    }

    private void showState(boolean busy, String text) {
        if (stateBox == null) return;
        stateBox.setVisibility(View.VISIBLE);
        spinner.setVisibility(busy ? View.VISIBLE : View.GONE);
        stateText.setText(text);
    }

    private void render() {
        if (listBox == null) return;
        ui.clear(listBox);
        if (apps.length() == 0) {
            showState(false, "还没有已安装的应用");
            return;
        }
        if (stateBox != null) stateBox.setVisibility(View.GONE);

        LinearLayout group = ui.group();
        group.setPadding(0, 0, 0, 0);
        for (int i = 0; i < apps.length(); i++) {
            final JSONObject a = apps.optJSONObject(i);
            if (a == null) continue;
            if (i > 0) group.addView(ui.divider());
            group.addView(appRow(a), ui.match());
        }
        listBox.addView(group, ui.match());
    }

    private View appRow(final JSONObject a) {
        final long id = Json.lng(a, "id");
        String custom = Json.str(a, "custom_name");
        String ipa = Json.str(a, "ipa_name");
        String name = custom.isEmpty() ? ipa : custom;
        String version = Json.str(a, "version");
        String device = Json.str(a, "device");
        String account = Json.str(a, "account");
        String expires = formatDate(Json.str(a, "expiration_date"));

        LinearLayout row = ui.row();
        row.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(ui.avatar(name, name.isEmpty() ? "?" : name.substring(0, 1), 40));

        LinearLayout textCol = ui.column();
        textCol.setBackgroundColor(ui.color(Ui.CARD_BG));
        LinearLayout.LayoutParams tcp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tcp.leftMargin = ui.dp(12);
        textCol.addView(ui.heading(name), ui.match());

        StringBuilder meta = new StringBuilder();
        if (!version.isEmpty()) meta.append("v").append(version);
        if (!device.isEmpty()) meta.append(meta.length() == 0 ? "" : " · ").append(device);
        TextView sub = ui.sub(meta.toString());
        LinearLayout.LayoutParams sp = ui.match();
        sp.topMargin = ui.dp(2);
        textCol.addView(sub, sp);

        String foot = "";
        if (!account.isEmpty()) foot = account;
        if (!expires.isEmpty()) {
            foot = foot.isEmpty() ? "到期 " + expires : foot + " · 到期 " + expires;
        }
        if (!foot.isEmpty()) {
            TextView f = ui.footnote(foot);
            LinearLayout.LayoutParams fp = ui.match();
            fp.topMargin = ui.dp(2);
            textCol.addView(f, fp);
        }
        row.addView(textCol, tcp);

        TextView reinstall = ui.base("重装", 15, Ui.BLUE, android.graphics.Typeface.BOLD);
        reinstall.setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6));
        ui.clickable(reinstall, new Runnable() {
            public void run() {
                postAppAction(id, "reinstall", "正在重装…");
            }
        });
        row.addView(reinstall);

        TextView del = ui.base("删除", 15, Ui.RED, android.graphics.Typeface.NORMAL);
        del.setPadding(ui.dp(8), ui.dp(6), ui.dp(4), ui.dp(6));
        ui.clickable(del, new Runnable() {
            public void run() {
                confirmDelete(id, name);
            }
        });
        row.addView(del);
        return row;
    }

    private String formatDate(String iso) {
        if (iso == null || iso.isEmpty()) return "";
        try {
            String normalized = iso.replace("Z", "+0000");
            SimpleDateFormat in = new SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US);
            Date d = in.parse(normalized);
            if (d == null) return iso;
            SimpleDateFormat out = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            return out.format(d);
        } catch (Exception e) {
            return iso;
        }
    }

    private void confirmDelete(final long id, final String name) {
        new AlertDialog.Builder(ui.ctx)
                .setTitle("删除应用")
                .setMessage("确定要删除 " + name + " 吗？")
                .setPositiveButton("删除", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) {
                        postAppAction(id, "delete", "已删除");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void postAppAction(final long id, final String action, final String okMsg) {
        Task.run(new Task.Job<Json.Result>() {
            public Json.Result run() {
                return "delete".equals(action)
                        ? Api.deleteApp(id)
                        : Api.reinstallApp(id);
            }
        }, new Task.Done<Json.Result>() {
            public void onDone(Json.Result r) {
                if (r.ok) {
                    toast(okMsg);
                } else {
                    toast("操作失败：" + r.error);
                }
                refresh();
            }
        });
    }

    private void toast(String msg) {
        App.MAIN.post(new Runnable() {
            public void run() {
                android.widget.Toast.makeText(ui.ctx, msg,
                        android.widget.Toast.LENGTH_SHORT).show();
            }
        });
    }
}
