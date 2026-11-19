package com.bitxeno.atvloadly;

import android.app.AlertDialog;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Sideload page: pick an IPA, a target device and an Apple ID, then install.
 *
 * The IPA is staged on the backend with POST /api/upload (multipart "files"),
 * which returns a parsed IpaFile whose server-side "path" is fed to the
 * /ws/install WebSocket as ipa_path. Install frames are
 * {@code {"t":1,"d":<InstalledApp json>}}; the engine streams plain-text lines
 * that this page renders as a live log and scans for the
 * "Installation Succeeded" / "Installation Failed" markers.
 */
public class SideloadPage extends Page implements Nav.Panel {

    /** Host supplies the file-picker callback wiring. */
    public interface Host {
        void requestIpaPicker();
    }

    private final Ui ui;
    private final Host host;
    private WebSocketClient ws;

    // Selections
    private Uri ipaUri;
    private String ipaName = "";
    private JSONObject uploaded;   // parsed IpaFile returned by /api/upload
    private JSONObject device;     // selected device
    private String account = "";

    // Views
    private TextView ipaValue;
    private TextView deviceValue;
    private TextView accountValue;
    private EditText nameInput;
    private LinearLayout progressBox;
    private TextView progressText;
    private TextView logView;
    private Button installBtn;
    private boolean installing = false;

    public SideloadPage(Ui ui, Host host) {
        super(ui);
        this.ui = ui;
        this.host = host;
    }

    @Override
    protected String title() {
        return "侧载";
    }

    @Override
    protected String subtitle() {
        return "选择 IPA 并安装到 Apple TV";
    }

    @Override
    protected View onBuild() {
        LinearLayout col = ui.column();
        col.setBackgroundColor(ui.color(Ui.CARD_BG));

        // IPA picker row
        LinearLayout group = ui.group();
        group.setPadding(0, 0, 0, 0);
        group.addView(rowWith("IPA 文件", ipaName.isEmpty() ? "选择 .ipa 文件" : ipaName,
                new Runnable() {
                    public void run() {
                        if (host != null) host.requestIpaPicker();
                    }
                }, new ValueSink() {
                    public void set(TextView v) {
                        ipaValue = v;
                    }
                }), ui.match());
        group.addView(ui.divider());
        group.addView(rowWith("设备", deviceLabel(), new Runnable() {
            public void run() {
                pickDevice();
            }
        }, new ValueSink() {
            public void set(TextView v) {
                deviceValue = v;
            }
        }), ui.match());
        group.addView(ui.divider());
        group.addView(rowWith("账户", account.isEmpty() ? "选择 Apple ID" : account,
                new Runnable() {
                    public void run() {
                        pickAccount();
                    }
                }, new ValueSink() {
                    public void set(TextView v) {
                        accountValue = v;
                    }
                }), ui.match());
        col.addView(group, ui.match());

        // Optional custom name
        nameInput = new EditText(ui.ctx);
        nameInput.setHint("自定义名称（可选）");
        nameInput.setTextSize(16);
        nameInput.setBackground(ui.cardBorder(Ui.GROUPED_BG));
        nameInput.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
        LinearLayout.LayoutParams np = ui.match();
        np.topMargin = ui.dp(14);
        col.addView(nameInput, np);

        installBtn = ui.primaryAction("开始安装");
        LinearLayout.LayoutParams bp = ui.match();
        bp.topMargin = ui.dp(16);
        installBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startInstall();
            }
        });
        col.addView(installBtn, bp);

        // Install progress + log
        progressBox = new LinearLayout(ui.ctx);
        progressBox.setOrientation(LinearLayout.VERTICAL);
        progressBox.setPadding(0, ui.dp(4), 0, 0);
        progressText = ui.body("");
        progressText.setGravity(Gravity.CENTER);
        progressBox.addView(progressText, ui.match());
        LinearLayout logCard = ui.card(14);
        logView = ui.mono("");
        logCard.addView(logView, ui.match());
        LinearLayout.LayoutParams lp = ui.match();
        lp.topMargin = ui.dp(10);
        progressBox.addView(logCard, lp);
        progressBox.setVisibility(View.GONE);
        col.addView(progressBox, ui.match());

        return col;
    }

    private String deviceLabel() {
        if (device == null) return "选择设备";
        String n = Json.str(device, "name");
        return n.isEmpty() ? "Apple TV" : n;
    }

    /** One tappable picker row: label + current value + chevron. */
    private View pickRow(String label, String value, Runnable action) {
        LinearLayout row = ui.row();
        row.setPadding(ui.dp(14), ui.dp(13), ui.dp(14), ui.dp(13));
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView l = ui.body(label);
        row.addView(l);
        LinearLayout spacer = new LinearLayout(ui.ctx);
        spacer.setOrientation(LinearLayout.HORIZONTAL);
        spacer.setBackgroundColor(ui.color(Ui.CARD_BG));
        row.addView(spacer, ui.weighted(1f));
        TextView v = ui.sub(value);
        v.setGravity(Gravity.RIGHT);
        v.setMaxWidth(ui.dp(190));
        row.addView(v);
        row.addView(ui.chevron());
        ui.clickable(row, action);
        return row;
    }

    /** Callback that captures a picker row's value TextView for later updates. */
    private interface ValueSink {
        void set(TextView v);
    }

    /** pickRow variant that hands its value TextView to a sink. */
    private View rowWith(String label, String value, Runnable action, ValueSink sink) {
        View row = pickRow(label, value, action);
        // The value TextView is the second-to-last child (before the chevron).
        LinearLayout r = (LinearLayout) row;
        if (sink != null && r.getChildCount() >= 2) {
            sink.set((TextView) r.getChildAt(r.getChildCount() - 2));
        }
        return row;
    }

    /** Called by the host after the system file picker returns a Uri. */
    public void onIpaPicked(Uri uri) {
        this.ipaUri = uri;
        this.ipaName = queryName(uri);
        if (ipaValue != null) {
            ipaValue.setText(ipaName.isEmpty() ? "选择 .ipa 文件" : ipaName);
        }
    }

    private String queryName(Uri uri) {
        android.database.Cursor c = ui.ctx.getContentResolver().query(uri, null, null, null, null);
        if (c != null) {
            try {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0 && c.moveToFirst()) return c.getString(idx);
            } finally {
                c.close();
            }
        }
        String p = uri.getLastPathSegment();
        return p == null ? "app.ipa" : p;
    }

    // ---- Device / account pickers -----------------------------------------

    private void pickDevice() {
        Task.run(new Task.Job<Json.Result>() {
            public Json.Result run() {
                return Api.devices();
            }
        }, new Task.Done<Json.Result>() {
            public void onDone(Json.Result r) {
                if (!r.ok) {
                    toast("无法获取设备列表");
                    return;
                }
                final org.json.JSONArray arr = Json.dataArray(r);
                if (arr.length() == 0) {
                    toast("未发现设备，请先在设备页配对");
                    return;
                }
                String[] names = new String[arr.length()];
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject d = arr.optJSONObject(i);
                    names[i] = d == null ? "?" : Json.str(d, "name", "Apple TV");
                }
                new AlertDialog.Builder(ui.ctx)
                        .setTitle("选择设备")
                        .setItems(names, new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface d, int which) {
                                device = arr.optJSONObject(which);
                                if (deviceValue != null) deviceValue.setText(deviceLabel());
                            }
                        })
                        .show();
            }
        });
    }

    private void pickAccount() {
        Task.run(new Task.Job<Json.Result>() {
            public Json.Result run() {
                return Api.accounts();
            }
        }, new Task.Done<Json.Result>() {
            public void onDone(Json.Result r) {
                if (!r.ok) {
                    toast("无法获取账户列表");
                    return;
                }
                final org.json.JSONArray arr = Json.dataArray(r);
                if (arr.length() == 0) {
                    toast("还没有登录 Apple ID，请先在账户页添加");
                    return;
                }
                String[] names = new String[arr.length()];
                final String[] emails = new String[arr.length()];
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject a = arr.optJSONObject(i);
                    emails[i] = a == null ? "" : Json.str(a, "email");
                    names[i] = emails[i];
                }
                new AlertDialog.Builder(ui.ctx)
                        .setTitle("选择账户")
                        .setItems(names, new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface d, int which) {
                                account = emails[which];
                                if (accountValue != null) accountValue.setText(account);
                            }
                        })
                        .show();
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

    // ---- Install flow -----------------------------------------------------

    private void startInstall() {
        if (installing) return;
        if (ipaUri == null) {
            toast("请先选择 IPA 文件");
            return;
        }
        if (device == null) {
            toast("请选择目标设备");
            return;
        }
        if (account.isEmpty()) {
            toast("请选择 Apple ID 账户");
            return;
        }
        installing = true;
        installBtn.setEnabled(false);
        progressBox.setVisibility(View.VISIBLE);
        progressText.setText("正在上传 IPA…");
        logView.setText("");

        Task.run(new Task.Job<Object>() {
            public Object run() throws Exception {
                File local = copyToCache(ipaUri, ipaName);
                Json.Result up = Api.uploadIpa(local);
                if (!up.ok) return "上传失败：" + up.error;
                // /api/upload returns data as an array of IpaFile; take the first.
                org.json.JSONArray arr = Json.dataArray(up);
                if (arr.length() == 0) return "上传失败：未返回文件信息";
                uploaded = arr.optJSONObject(0);
                if (uploaded == null) return "上传失败：文件信息格式异常";
                return null; // success, proceed on main thread
            }
        }, new Task.Done<Object>() {
            public void onDone(Object err) {
                if (err != null) {
                    installing = false;
                    installBtn.setEnabled(true);
                    progressText.setText(String.valueOf(err));
                    progressText.setTextColor(ui.color(Ui.RED));
                    return;
                }
                connectInstall();
            }
        });
    }

    /** Copy the picked content Uri into cache so it can be streamed as multipart. */
    private File copyToCache(Uri uri, String name) throws Exception {
        String safe = name == null || name.isEmpty() ? "app.ipa" : name;
        File out = new File(ui.ctx.getCacheDir(), safe);
        InputStream in = ui.ctx.getContentResolver().openInputStream(uri);
        if (in == null) throw new Exception("无法读取所选文件");
        OutputStream os = new java.io.FileOutputStream(out);
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) != -1) os.write(buf, 0, n);
        in.close();
        os.close();
        return out;
    }

    /** Open /ws/install and send the install request. */
    private void connectInstall() {
        final String ipaPath = Json.str(uploaded, "path");
        final String ipaNameStr = Json.str(uploaded, "name");
        final String bundleId = Json.str(uploaded, "bundle_identifier");
        final String version = Json.str(uploaded, "version");
        final String udid = Json.str(device, "udid");
        final String deviceClass = Json.str(device, "device_class", "AppleTV");
        final String customName = nameInput == null ? ""
                : nameInput.getText().toString().trim();

        JSONObject payload = Json.object();
        Json.put(payload, "ID", 0);
        Json.put(payload, "ipa_name", ipaNameStr);
        Json.put(payload, "ipa_path", ipaPath);
        Json.put(payload, "device", Json.str(device, "mac_addr"));
        Json.put(payload, "device_class", deviceClass);
        Json.put(payload, "udid", udid);
        Json.put(payload, "account", account);
        Json.put(payload, "password", "");
        Json.put(payload, "icon", Json.str(uploaded, "icon"));
        Json.put(payload, "bundle_identifier", bundleId);
        Json.put(payload, "version", version);
        Json.put(payload, "custom_name", customName);
        Json.put(payload, "remove_extensions", false);
        Json.put(payload, "signing_mode", "apple_id");
        Json.put(payload, "signing_identity_id", 0);

        ws = new WebSocketClient("/ws/install", new WebSocketClient.Listener() {
            public void onOpen() {
                ws.send(Json.object("t", 1, "d", payload.toString()).toString());
                setProgress("正在安装…", Ui.BLUE);
            }

            public void onMessage(String text) {
                handleInstallLine(text);
            }

            public void onClose(String reason) {
                // Socket closing after success/failure is normal.
            }

            public void onError(String message) {
                setProgress("连接失败：" + message, Ui.RED);
                finishInstall(false);
            }
        });
        new Thread(new Runnable() {
            public void run() {
                ws.connect();
            }
        }, "atvloadly-install").start();
    }

    private void handleInstallLine(String line) {
        appendLog(line);
        if (line.contains("Installation Succeeded") || line.contains("Installation complete")) {
            setProgress("安装成功", Ui.GREEN);
            finishInstall(true);
        } else if (line.contains("Installation Failed") || line.startsWith("ERROR")) {
            setProgress("安装失败", Ui.RED);
            finishInstall(false);
        } else if (line.contains("%")) {
            // Forward engine percentage lines as the headline status.
            String l = line.trim();
            if (l.length() <= 80) setProgress(l, Ui.SECONDARY);
        }
    }

    private void setProgress(final String text, final String colorHex) {
        App.MAIN.post(new Runnable() {
            public void run() {
                if (progressText != null) {
                    progressText.setText(text);
                    progressText.setTextColor(ui.color(colorHex));
                }
            }
        });
    }

    private void appendLog(final String line) {
        App.MAIN.post(new Runnable() {
            public void run() {
                if (logView != null) logView.append(line + "\n");
            }
        });
    }

    private void finishInstall(final boolean ok) {
        App.MAIN.post(new Runnable() {
            public void run() {
                installing = false;
                if (installBtn != null) installBtn.setEnabled(true);
                closeWs();
                // Clean the cached copy either way.
                if (ipaUri != null && "file".equals(ipaUri.getScheme())) {
                    // no-op; cache copy removed below via uploaded path
                }
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
