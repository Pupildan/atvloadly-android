package com.bitxeno.atvloadly;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

/**
 * Process-wide state shared across the native panels.
 *
 * Holds the main-thread handler every panel uses to marshal background results,
 * plus the device/account selections that flow from the device list through the
 * sideload form. Nothing here is persisted; selections reset each launch.
 */
public final class App {

    /** Main looper handler for posting UI updates from background threads. */
    public static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** Device chosen on the device list, carried into pairing and sideload. */
    public static JSONObject selectedDevice;

    /** Apple ID email chosen on the accounts panel, carried into sideload. */
    public static String selectedAccount = "";

    /** Last version string reported by the backend once ready. */
    public static String serverVersion = "";

    private App() {}

    /** Convenience read of a string field off the selected device. */
    public static String deviceString(String key) {
        return selectedDevice == null ? "" : Json.str(selectedDevice, key);
    }

    /** Convenience read of a string field off a device object. */
    public static String devStr(JSONObject d, String key) {
        return Json.str(d, key);
    }
}
