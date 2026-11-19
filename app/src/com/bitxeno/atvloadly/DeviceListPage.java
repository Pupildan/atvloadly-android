package com.bitxeno.atvloadly;

import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Device page: scans the local network for Apple TVs and lists them as iOS-style
 * cards. Pulls already-paired devices from GET /api/devices and freshly
 * discovered ones from GET /api/scan/wireless, de-duplicated by id.
 *
 * Tapping a device hands it to the host, which routes to pairing (unpaired) or
 * straight to sideload (already paired).
 */
public class DeviceListPage extends Page implements Nav.Panel {

    /** Host decides where a chosen device goes next. */
    public interface Host {
        void onDevicePicked(JSONObject device);
    }

    private final Host host;
    private LinearLayout listBox;
    private LinearLayout stateBox;
    private TextView stateText;
    private View spinner;
    private boolean scanning = false;
    private final List<JSONObject> devices = new ArrayList<>();

    public DeviceListPage(Ui ui, Host host) {
        super(ui);
        this.host = host;
    }

    @Override
    protected String title() {
        return "设备";
    }

    @Override
    protected String subtitle() {
        return "确保 Apple TV 与手机在同一 Wi-Fi 网络";
    }

    @Override
    protected View onBuild() {
        LinearLayout col = ui.column();
        col.setBackgroundColor(ui.color(Ui.CARD_BG));

        // Scan control
        android.widget.Button scanBtn = ui.button("重新扫描", scanning ? 1 : 0);
        scanBtn.setGravity(Gravity.CENTER);
        ui.clickable(scanBtn, new Runnable() {
            public void run() {
                startScan();
            }
        });
        col.addView(scanBtn, ui.match());

        stateBox = new LinearLayout(ui.ctx);
        stateBox.setOrientation(LinearLayout.VERTICAL);
        stateBox.setGravity(Gravity.CENTER);
        stateBox.setPadding(0, ui.dp(28), 0, ui.dp(28));
        spinner = ui.spinner();
        stateBox.addView(spinner);
        stateText = ui.sub("正在扫描附近的 Apple TV…");
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
        refreshKnown();
        if (!scanning) startScan();
    }

    /** Load already-known devices (paired or not) from the backend. */
    private void refreshKnown() {
        Task.run(new Task.Job<Json.Result>() {
            public Json.Result run() {
                return Api.devices();
            }
        }, new Task.Done<Json.Result>() {
            public void onDone(Json.Result r) {
                if (!r.ok) return;
                mergeDevices(Json.dataArray(r));
                render();
            }
        });
    }

    /** Kick off a wireless mDNS scan (bounded ~10s server-side). */
    private void startScan() {
        scanning = true;
        showState(true, "正在扫描附近的 Apple TV…");
        Task.run(new Task.Job<Json.Result>() {
            public Json.Result run() {
                return Api.scanWireless(6);
            }
        }, new Task.Done<Json.Result>() {
            public void onDone(Json.Result r) {
                scanning = false;
                if (r.ok) {
                    mergeDevices(Json.dataArray(r));
                    render();
                } else {
                    showState(false, "扫描失败：" + r.error);
                }
            }
        });
    }

    /** Merge a batch of devices into the list, keyed by id. */
    private void mergeDevices(JSONArray arr) {
        for (int i = 0; i < arr.length(); i++) {
            JSONObject d = arr.optJSONObject(i);
            if (d == null) continue;
            String id = Json.str(d, "id");
            if (id.isEmpty()) continue;
            boolean exists = false;
            for (JSONObject e : devices) {
                if (Json.str(e, "id").equals(id)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) devices.add(d);
        }
    }

    private void showState(boolean busy, String text) {
        if (stateBox == null) return;
        stateBox.setVisibility(View.VISIBLE);
        spinner.setVisibility(busy ? View.VISIBLE : View.GONE);
        stateText.setText(text);
    }

    /** Rebuild the device cards from the current list. */
    private void render() {
        if (listBox == null) return;
        ui.clear(listBox);
        if (devices.isEmpty()) {
            if (!scanning) showState(false, "未发现设备，请确认 Apple TV 已开机并在同一网络");
            return;
        }
        if (stateBox != null) stateBox.setVisibility(View.GONE);

        LinearLayout group = ui.group();
        group.setPadding(0, 0, 0, 0);
        for (int i = 0; i < devices.size(); i++) {
            final JSONObject d = devices.get(i);
            if (i > 0) group.addView(ui.divider());
            group.addView(deviceCard(d), ui.match());
        }
        listBox.addView(group, ui.match());
    }

    /** A single device card: avatar, name, status pill, connection line. */
    private View deviceCard(final JSONObject d) {
        String name = Json.str(d, "name");
        if (name.isEmpty()) name = Json.str(d, "service_name", "Apple TV");
        String status = Json.str(d, "status");
        String ip = Json.str(d, "ip");
        String klass = Json.str(d, "device_class");

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
        String line = klass + (ip.isEmpty() ? "" : " · " + ip);
        TextView sub = ui.sub(line);
        LinearLayout.LayoutParams lp = ui.match();
        lp.topMargin = ui.dp(2);
        textCol.addView(sub, lp);
        row.addView(textCol, tcp);

        String pillText;
        String pillColor;
        if ("paired".equals(status)) {
            pillText = "已配对";
            pillColor = Ui.GREEN;
        } else if ("unpaired".equals(status)) {
            pillText = "未配对";
            pillColor = Ui.ORANGE;
        } else {
            pillText = status.isEmpty() ? "发现" : status;
            pillColor = Ui.BLUE;
        }
        row.addView(ui.pill(pillText, pillColor));

        ui.clickable(row, new Runnable() {
            public void run() {
                host.onDevicePicked(d);
            }
        });
        return row;
    }
}
