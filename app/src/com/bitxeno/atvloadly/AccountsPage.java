package com.bitxeno.atvloadly;

import android.app.AlertDialog;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Accounts page: lists Apple IDs already signed in on the engine
 * (GET /api/accounts, data is an array of {email, team_id, status}) and lets the
 * user add one through the /ws/login WebSocket.
 *
 * Login frames: {@code {"t":1,"d":"{\"account\":..,\"password\":..}"}} to start
 * and {@code {"t":2,"d":"<2fa code>"}} to answer a two-factor prompt. The engine
 * streams plain-text lines; "Login Succeeded" / "Login Failed" end the flow and
 * a 2FA prompt opens a code entry dialog, mirroring the web login component.
 */
public class AccountsPage extends Page implements Nav.Panel {

    /** Host used to jump to sideload with a chosen account. */
    public interface Host {
        void onAccountPicked(String email);
    }

    private final Ui ui;
    private final Host host;
    private LinearLayout listBox;
    private LinearLayout stateBox;
    private TextView stateText;
    private View spinner;
    private WebSocketClient ws;
    private AlertDialog loginDialog;
    private boolean loginDone = false;

    public AccountsPage(Ui ui, Host host) {
        super(ui);
        this.ui = ui;
        this.host = host;
    }

    @Override
    protected String title() {
        return "账户";
    }

    @Override
    protected String subtitle() {
        return "侧载需要一个已登录的 Apple ID";
    }

    @Override
    protected View onBuild() {
        LinearLayout col = ui.column();
        col.setBackgroundColor(ui.color(Ui.CARD_BG));

        Button addBtn = ui.primaryAction("添加 Apple ID");
        addBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showLoginDialog();
            }
        });
        col.addView(addBtn, ui.match());

        stateBox = new LinearLayout(ui.ctx);
        stateBox.setOrientation(LinearLayout.VERTICAL);
        stateBox.setGravity(Gravity.CENTER);
        stateBox.setPadding(0, ui.dp(28), 0, ui.dp(28));
        spinner = ui.spinner();
        stateBox.addView(spinner);
        stateText = ui.sub("正在加载账户…");
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
        showState(true, "正在加载账户…");
        Task.run(new Task.Job<Json.Result>() {
            public Json.Result run() {
                return Api.accounts();
            }
        }, new Task.Done<Json.Result>() {
            public void onDone(Json.Result r) {
                if (r.ok) {
                    render(Json.dataArray(r));
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

    /** Render the signed-in account rows. */
    private void render(JSONArray accounts) {
        if (listBox == null) return;
        ui.clear(listBox);
        if (accounts.length() == 0) {
            showState(false, "还没有登录任何 Apple ID");
            return;
        }
        if (stateBox != null) stateBox.setVisibility(View.GONE);

        LinearLayout group = ui.group();
        group.setPadding(0, 0, 0, 0);
        for (int i = 0; i < accounts.length(); i++) {
            final JSONObject a = accounts.optJSONObject(i);
            if (a == null) continue;
            if (i > 0) group.addView(ui.divider());
            group.addView(accountRow(a), ui.match());
        }
        listBox.addView(group, ui.match());
    }

    private View accountRow(final JSONObject a) {
        final String email = Json.str(a, "email");
        String status = Json.str(a, "status");

        LinearLayout row = ui.row();
        row.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(ui.avatar(email, email.isEmpty() ? "?" : email.substring(0, 1), 36));

        LinearLayout textCol = ui.column();
        textCol.setBackgroundColor(ui.color(Ui.CARD_BG));
        LinearLayout.LayoutParams tcp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tcp.leftMargin = ui.dp(12);
        textCol.addView(ui.body(email), ui.match());
        if (!status.isEmpty()) {
            TextView st = ui.footnote(status);
            LinearLayout.LayoutParams sp = ui.match();
            sp.topMargin = ui.dp(2);
            textCol.addView(st, sp);
        }
        row.addView(textCol, tcp);

        // "使用" applies the account to the sideload flow; "退出" signs it out.
        TextView use = ui.base("使用", 15, Ui.BLUE, android.graphics.Typeface.BOLD);
        use.setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6));
        ui.clickable(use, new Runnable() {
            public void run() {
                if (host != null) host.onAccountPicked(email);
            }
        });
        row.addView(use);

        TextView logout = ui.base("退出", 15, Ui.RED, android.graphics.Typeface.NORMAL);
        logout.setPadding(ui.dp(8), ui.dp(6), ui.dp(4), ui.dp(6));
        ui.clickable(logout, new Runnable() {
            public void run() {
                confirmLogout(email);
            }
        });
        row.addView(logout);
        return row;
    }

    private void confirmLogout(final String email) {
        new AlertDialog.Builder(ui.ctx)
                .setTitle("退出登录")
                .setMessage("确定要退出 " + email + " 吗？")
                .setPositiveButton("退出", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) {
                        Task.run(new Task.Job<Json.Result>() {
                            public Json.Result run() {
                                return Api.logoutAccount(email);
                            }
                        }, new Task.Done<Json.Result>() {
                            public void onDone(Json.Result r) {
                                refresh();
                            }
                        });
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---- Login flow over /ws/login ----------------------------------------

    private void showLoginDialog() {
        LinearLayout box = new LinearLayout(ui.ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = ui.dp(20);
        box.setPadding(pad, ui.dp(8), pad, 0);

        final EditText account = new EditText(ui.ctx);
        account.setHint("Apple ID");
        account.setInputType(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        box.addView(account, ui.match());

        final EditText password = new EditText(ui.ctx);
        password.setHint("密码");
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout.LayoutParams pp = ui.match();
        pp.topMargin = ui.dp(10);
        box.addView(password, pp);

        final TextView err = new TextView(ui.ctx);
        err.setTextColor(ui.color(Ui.RED));
        err.setTextSize(13);
        LinearLayout.LayoutParams ep = ui.match();
        ep.topMargin = ui.dp(8);
        box.addView(err, ep);

        loginDone = false;
        loginDialog = new AlertDialog.Builder(ui.ctx)
                .setTitle("登录 Apple ID")
                .setView(box)
                .setPositiveButton("登录", null) // overridden below to validate first
                .setNegativeButton("取消", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) {
                        closeWs();
                    }
                })
                .create();
        loginDialog.show();
        loginDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(
                new View.OnClickListener() {
                    public void onClick(View v) {
                        String a = account.getText().toString().trim();
                        String p = password.getText().toString();
                        if (a.isEmpty() || p.isEmpty()) {
                            err.setText("请输入 Apple ID 和密码");
                            return;
                        }
                        err.setText("");
                        startLogin(a, p, err);
                    }
                });
    }

    private void startLogin(final String account, final String password,
                            final TextView err) {
        loginDone = false;
        err.setText("正在登录…");
        closeWs();
        ws = new WebSocketClient("/ws/login", new WebSocketClient.Listener() {
            public void onOpen() {
                String payload = Json.object("account", account, "password", password)
                        .toString();
                ws.send(Json.object("t", 1, "d", payload).toString());
            }

            public void onMessage(String text) {
                handleLoginLine(text, err);
            }

            public void onClose(String reason) {
                if (!loginDone) err.setText("连接已断开");
            }

            public void onError(String message) {
                App.MAIN.post(new Runnable() {
                    public void run() {
                        err.setText("连接失败");
                    }
                });
            }
        });
        new Thread(new Runnable() {
            public void run() {
                ws.connect();
            }
        }, "atvloadly-login").start();
    }

    /** Watch engine lines for 2FA prompts and success/failure markers. */
    private void handleLoginLine(String line, final TextView err) {
        String lower = line.toLowerCase();
        if (lower.contains("enter 2fa code") || lower.contains("enter the code sent")) {
            App.MAIN.post(new Runnable() {
                public void run() {
                    prompt2fa(err);
                }
            });
            return;
        }
        if (line.contains("Login Succeeded") || line.contains("Successfully logged in")) {
            loginDone = true;
            closeWs();
            App.MAIN.post(new Runnable() {
                public void run() {
                    if (loginDialog != null) loginDialog.dismiss();
                    refresh();
                }
            });
            return;
        }
        if (line.contains("Login Failed") || line.contains("ERROR")) {
            App.MAIN.post(new Runnable() {
                public void run() {
                    err.setText("登录失败，请检查账号或密码");
                }
            });
        }
    }

    /** Ask for the 6-digit two-factor code and submit it as t=2. */
    private void prompt2fa(final TextView err) {
        if (loginDialog == null || !loginDialog.isShowing()) return;
        final EditText code = new EditText(ui.ctx);
        code.setHint("两步验证码");
        code.setInputType(InputType.TYPE_CLASS_NUMBER);
        int pad = ui.dp(20);
        LinearLayout box = new LinearLayout(ui.ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, ui.dp(8), pad, 0);
        box.addView(code, ui.match());
        loginDialog.setTitle("两步验证");
        loginDialog.setView(box);
        loginDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(
                new View.OnClickListener() {
                    public void onClick(View v) {
                        String c = code.getText().toString().trim();
                        if (c.isEmpty()) return;
                        err.setText("正在验证…");
                        if (ws != null) ws.send(Json.object("t", 2, "d", c).toString());
                    }
                });
    }

    private void closeWs() {
        if (ws != null) {
            ws.close();
            ws = null;
        }
    }

    public void close() {
        closeWs();
    }
}
