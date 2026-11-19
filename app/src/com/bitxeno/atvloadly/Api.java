package com.bitxeno.atvloadly;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Random;

/**
 * Minimal HTTP client for the local Go backend bound to 127.0.0.1:9000.
 *
 * Only the small subset the native UI needs is implemented: JSON GET/POST and a
 * multipart file upload. Every method is synchronous and must be called off the
 * main thread; callers marshal results back with a Handler.
 */
public final class Api {

    private static final String TAG = "atvloadly.Api";
    public static final String BASE = "http://127.0.0.1:9000";
    private static final int TIMEOUT_MS = 30000;
    /** The wireless scan can legitimately run for up to 10s server-side. */
    private static final int SCAN_TIMEOUT_MS = 20000;

    private Api() {}

    // ---- Low level helpers -------------------------------------------------

    private interface Op {
        void apply(HttpURLConnection c) throws IOException;
    }

    private static Json.Result request(String url, String method, Op op, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestMethod(method);
            if (op != null) op.apply(conn);
            int status = conn.getResponseCode();
            InputStream is = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String body = readAll(is);
            if (status >= 400) {
                // Backend returns 200 for handled API errors; a transport-level
                // 4xx/5xx means the endpoint itself rejected the call.
                return Json.envelope(body).ok ? Json.failure("HTTP " + status)
                        : Json.envelope(body);
            }
            return Json.envelope(body);
        } catch (Exception e) {
            Log.w(TAG, method + " " + url + " failed: " + e);
            return Json.failure(e.getMessage() == null ? "network error" : e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readAll(InputStream is) throws IOException {
        if (is == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) out.write(buf, 0, n);
        is.close();
        return out.toString("UTF-8");
    }

    private static Json.Result get(String path, int timeoutMs) {
        return request(BASE + path, "GET", null, timeoutMs);
    }

    private static Json.Result postJson(String path, String json) {
        return request(BASE + path, "POST", new Op() {
            public void apply(HttpURLConnection c) throws IOException {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                OutputStream os = c.getOutputStream();
                os.write(json.getBytes("UTF-8"));
                os.close();
            }
        }, TIMEOUT_MS);
    }

    // ---- Endpoints used by the native UI ----------------------------------

    /** GET /api/version — readiness probe; data is the version string. */
    public static Json.Result version() {
        return get("/api/version", 5000);
    }

    /** GET /api/devices — re-reads paired/known devices from the manager. */
    public static Json.Result devices() {
        return get("/api/devices", TIMEOUT_MS);
    }

    /** GET /api/scan/wireless?timeout=N — blocking mDNS scan (bounded server-side). */
    public static Json.Result scanWireless(int timeoutSec) {
        if (timeoutSec < 1) timeoutSec = 1;
        if (timeoutSec > 10) timeoutSec = 10;
        return get("/api/scan/wireless?timeout=" + timeoutSec, SCAN_TIMEOUT_MS);
    }

    /** GET /api/accounts — Apple IDs already signed in on the engine. */
    public static Json.Result accounts() {
        return get("/api/accounts", TIMEOUT_MS);
    }

    /** GET /api/apps — installed-app records, newest first. */
    public static Json.Result apps() {
        return get("/api/apps", TIMEOUT_MS);
    }

    /** GET /api/apps/installing — task snapshots of apps being installed now. */
    public static Json.Result installing() {
        return get("/api/apps/installing", 8000);
    }

    /** POST /api/apps/:id/delete — remove an installed-app record + its IPA. */
    public static Json.Result deleteApp(long id) {
        return postJson("/api/apps/" + id + "/delete", "{}");
    }

    /** POST /api/apps/:id/reinstall — queue a background reinstall of a record. */
    public static Json.Result reinstallApp(long id) {
        return postJson("/api/apps/" + id + "/reinstall", "{}");
    }

    /** POST /api/accounts/logout — sign an Apple ID out of the engine. */
    public static Json.Result logoutAccount(String email) {
        return postJson("/api/accounts/logout",
                Json.object("email", email).toString());
    }

    /**
     * POST /api/upload — stage a local IPA in the server's temp dir and parse
     * it. Returns an IpaFile whose "path" is the server-side path the install
     * WebSocket request expects in ipa_path. Upload must happen before install
     * so the interactive install can resolve a local file, mirroring the web UI.
     */
    public static Json.Result uploadIpa(File file) {
        final File f = file;
        final String boundary = "----atvloadly" + Long.toHexString(new Random().nextLong());
        return request(BASE + "/api/upload", "POST", new Op() {
            public void apply(HttpURLConnection c) throws IOException {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type",
                        "multipart/form-data; boundary=" + boundary);
                // Stream so a large IPA is never buffered whole in memory.
                c.setChunkedStreamingMode(256 * 1024);
                OutputStream os = c.getOutputStream();
                writeMultipart(os, f, boundary);
                os.close();
            }
        }, 120000);
    }

    /**
     * Write one multipart/form-data body with a single "files" part, the field
     * name /api/upload reads. Files may be large, so bytes are streamed.
     */
    private static void writeMultipart(OutputStream os, File f, String boundary)
            throws IOException {
        byte[] nl = "\r\n".getBytes("UTF-8");
        os.write(("--" + boundary + nl).getBytes("UTF-8"));
        os.write(("Content-Disposition: form-data; name=\"files\"; filename=\""
                + f.getName() + "\"" + nl).getBytes("UTF-8"));
        os.write(("Content-Type: application/octet-stream" + nl + nl).getBytes("UTF-8"));
        FileInputStream fis = new FileInputStream(f);
        byte[] buf = new byte[65536];
        int n;
        while ((n = fis.read(buf)) != -1) os.write(buf, 0, n);
        fis.close();
        os.write(nl);
        os.write(("--" + boundary + "--" + nl).getBytes("UTF-8"));
    }
}
