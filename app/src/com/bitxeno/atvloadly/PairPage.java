package com.bitxeno.atvloadly;

import android.graphics.Color;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * Pairing page: drives the /ws/pair WebSocket for one device.
 *
 * Outbound frames are {@code {"t":1,"d":<deviceId>}} to start pairing and
 * {@code {"t":2,"d":<pin>}} to submit the on-screen PIN. Inbound frames are the
 * engine's plain-text log lines; this page watches for the "Enter PIN" prompt,
 * "SUCCESS", and "ERROR" markers to drive the UI, mirroring the web pairing page.
 */
public class PairPage extends Page implements Nav.Panel {

    /** Host used to advance to sideload once pairing succeeds. */
    public interface Host {
        void onPaired(JSONObject device);
    }

    private final Ui ui;
    private final JSONObject device;
    private final Host host;
    private WebSocketClient ws;

    private TextView statusText;
    private LinearLayout pinBox;
    private EditText pinInput;
    private Button pinConfirm;
    private TextView logView;
    private boolean pairingDone = false;

    public PairPage(Ui ui, JSONObject device, Host host) {
        super(ui);
        this.ui = ui;
        this.device = device;
        this.host = host;
    }

    @Override
    protected String title() {
        return "配对";
    }

    @Override
    protected String subtitle() {
        String n = Json.str(device, "name");
        return n.isEmpty() ? "与设备配对" : "与 " + n + " 配对";
    }

    @Override
    protected View onBuild() {
        LinearLayout col = ui.column();
        col.setBackgroundColor(ui.color(Ui.CARD_BG));

        // Status card
        LinearLayout statusCard = ui.card(14);
        statusText = ui.body("正在连接设备…");
        statusText.setGravity(Gravity.CENTER);
        statusCard.addView(statusText, ui.match());
        col.addView(statusCard, ui.match());

        // PIN entry (hidden until the engine asks for it)
        pinBox = new LinearLayout(ui.ctx);
        pinBox.setOrientation(LinearLayout.VERTICAL);
        pinBox.setPadding(0, ui.dp(4), 0, 0);
        TextView pinLabel = ui.sub("在 Apple TV 上显示配对码后，输入此处");
        pinBox.addView(pinLabel, ui.match());
        pinInput = new EditText(ui.ctx);
        pinInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        pinInput.setTextSize(24);
        pinInput.setGravity(Gravity.CENTER);
        pinInput.setLetterSpacing(0.2f);
        pinInput.setHint("配对码");
        pinInput.setBackground(ui.cardBorder(Ui.GROUPED_BG));
        pinInput.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12));
        LinearLayout.LayoutParams ip = ui.match();
        ip.topMargin = ui.dp(8);
        pinBox.addView(pinInput, ip);
        pinConfirm = ui.primaryAction("确认配对");
        LinearLayout.LayoutParams cp = ui.match();
        cp.topMargin = ui.dp(10);
        pinConfirm.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                submitPin();
            }
        });
        pinBox.addView(pinConfirm, cp);
        pinBox.setVisibility(View.GONE);
        col.addView(pinBox, ui.match());

        // Live engine log
        LinearLayout logCard = ui.card(14);
        logView = ui.mono("");
        logCard.addView(logView, ui.match());
        LinearLayout.LayoutParams lp = ui.match();
        lp.topMargin = ui.dp(16);
        col.addView(logCard, lp);

        return col;
    }

    @Override
    public View build() {
        View page = super.build();
        connect();
        return page;
    }

    private void connect() {
        ws = new WebSocketClient("/ws/pair", new WebSocketClient.Listener() {
            public void onOpen() {
                // Start pairing for this device id.
                send(1, Json.str(device, "id"));
            }

            public void onMessage(String text) {
                handleLine(text);
            }

            public void onClose(String reason) {
                if (!pairingDone) {
                    setStatus("连接已断开", Ui.SECONDARY);
                }
            }

            public void onError(String message) {
                setStatus("连接失败：" + message, Ui.RED);
            }
        });
        new Thread(new Runnable() {
            public void run() {
                ws.connect();
            }
        }, "atvloadly-pair").start();
    }

    private void send(int t, String data) {
        if (ws == null) return;
        ws.send(Json.object("t", t, "d", data).toString());
    }

    /** Route one engine line to the right UI state. */
    private void handleLine(String line) {
        appendLog(line);
        if (line.indexOf("Enter PIN") != -1) {
            showPin();
        } else if (line.indexOf("Invalid PIN") != -1) {
            setStatus("配对码不正确，请重试", Ui.RED);
        } else if (line.indexOf("SUCCESS") != -1) {
            pairingDone = true;
            setStatus("配对成功", Ui.GREEN);
            hidePin();
            if (host != null) host.onPaired(device);
        } else if (line.indexOf("ERROR") != -1) {
            setStatus("配对出错", Ui.RED);
        }
    }

    private void showPin() {
        if (pinBox == null) return;
        pinBox.setVisibility(View.VISIBLE);
        setStatus("请输入 Apple TV 上显示的配对码", Ui.BLUE);
    }

    private void hidePin() {
        if (pinBox == null) return;
        pinBox.setVisibility(View.GONE);
    }

    private void submitPin() {
        if (pinInput == null) return;
        String code = pinInput.getText().toString().trim();
        if (code.isEmpty()) return;
        setStatus("正在验证配对码…", Ui.SECONDARY);
        pinConfirm.setEnabled(false);
        send(2, code);
    }

    private void setStatus(final String text, final String colorHex) {
        App.MAIN.post(new Runnable() {
            public void run() {
                if (statusText != null) {
                    statusText.setText(text);
                    statusText.setTextColor(ui.color(colorHex));
                }
            }
        });
    }

    private void appendLog(final String line) {
        App.MAIN.post(new Runnable() {
            public void run() {
                if (logView != null) {
                    logView.append(line + "\n");
                }
            }
        });
    }

    public void close() {
        if (ws != null) ws.close();
    }
}
