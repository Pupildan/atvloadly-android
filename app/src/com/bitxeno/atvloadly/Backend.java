package com.bitxeno.atvloadly;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;

/**
 * Owns the embedded Go backend: writes its config, launches the cross-compiled
 * libatvloadly.so as a child process bound to localhost:9000, and polls until
 * the HTTP API answers so the native UI can start calling it.
 *
 * The plumesign signing engine is bundled alongside as libplumesign.so inside
 * nativeLibraryDir, the only app-visible directory Android permits exec from on
 * Android 10+ (W^X). Its absolute path is exported as PLUMESIGN_BIN so the
 * backend resolves the bare name "plumesign" to it.
 */
public class Backend {

    private static final String TAG = "atvloadly.Backend";
    public static final int PORT = 9000;

    private final android.app.Activity activity;
    private Process process;
    private volatile boolean started = false;

    public Backend(android.app.Activity activity) {
        this.activity = activity;
    }

    /** Callback fired once the API answers /api/version on a background thread. */
    public interface ReadyCallback {
        void onReady(String version);

        void onFailed(String error);
    }

    /** Launch the backend and poll for readiness. Call once from onCreate. */
    public void start(final ReadyCallback cb) {
        if (started) return;
        started = true;
        new Thread(new Runnable() {
            public void run() {
                try {
                    File home = activity.getFilesDir();
                    File bin = nativeLib("libatvloadly.so");
                    if (bin == null) {
                        postFail(cb, "backend library not found");
                        return;
                    }
                    File cfg = writeConfig(home);
                    File work = new File(home, "data");
                    if (!work.exists()) work.mkdirs();
                    seedSettings(work);

                    File engine = nativeLib("libplumesign.so");
                    ProcessBuilder pb = new ProcessBuilder(
                            bin.getAbsolutePath(), "server",
                            "--config", cfg.getAbsolutePath(),
                            "--port", String.valueOf(PORT));
                    pb.environment().put("HOME", home.getAbsolutePath());
                    pb.environment().put("TMPDIR", home.getAbsolutePath());
                    if (engine != null) {
                        pb.environment().put("PLUMESIGN_BIN", engine.getAbsolutePath());
                    }
                    pb.redirectErrorStream(true);
                    process = pb.start();

                    // Drain stdout/stderr so the child never blocks on a full pipe.
                    final InputStream is = process.getInputStream();
                    new Thread(new Runnable() {
                        public void run() {
                            BufferedReader r = new BufferedReader(new InputStreamReader(is));
                            try {
                                while (r.readLine() != null) {
                                }
                            } catch (Exception ignored) {
                            }
                        }
                    }).start();

                    String version = waitForReady();
                    if (version != null) {
                        App.serverVersion = version;
                        cb.onReady(version);
                    } else {
                        postFail(cb, "backend did not become ready");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "start failed", e);
                    postFail(cb, String.valueOf(e));
                }
            }
        }).start();
    }

    private void postFail(ReadyCallback cb, String msg) {
        App.MAIN.post(new Runnable() {
            public void run() {
                cb.onFailed(msg);
            }
        });
    }

    /**
     * Poll GET /api/version until the HTTP server accepts connections (bounded
     * ~25s), returning the reported version or null on timeout.
     */
    private String waitForReady() {
        int waited = 0;
        while (waited < 25000) {
            Json.Result r = Api.version();
            if (r.ok) {
                Object d = r.body.opt("data");
                return d == null ? "" : String.valueOf(d);
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
            }
            waited += 300;
        }
        return null;
    }

    /** Locate a bundled native binary by its "lib" name, or null if absent. */
    private File nativeLib(String name) {
        File f = new File(activity.getApplicationInfo().nativeLibraryDir, name);
        if (f.exists()) return f;
        File alt = new File("/data/data/" + activity.getPackageName() + "/lib/" + name);
        return alt.exists() ? alt : null;
    }

    private File writeConfig(File home) throws Exception {
        File cfg = new File(home, "config.yaml");
        File work = new File(home, "data");
        OutputStreamWriter osw = new OutputStreamWriter(
                new java.io.FileOutputStream(cfg));
        osw.write("server:\n");
        osw.write("  work_dir: " + work.getAbsolutePath() + "\n");
        osw.write("log:\n");
        osw.write("  log_file: " + new File(work, "app.log").getAbsolutePath() + "\n");
        osw.write("zeroconf: true\n");
        osw.close();
        return cfg;
    }

    /**
     * Seed settings.json with background auto-refresh disabled on first launch.
     * Android does no background refresh by product decision; manual refresh
     * endpoints stay fully functional. Existing user choices are respected.
     */
    private void seedSettings(File work) {
        File sf = new File(work, "settings.json");
        if (sf.exists()) return;
        try {
            OutputStreamWriter osw = new OutputStreamWriter(new java.io.FileOutputStream(sf));
            osw.write("{\"task\":{\"enabled\":false,\"iphone_enabled\":false}}");
            osw.close();
        } catch (Exception ignored) {
        }
    }

    public void shutdown() {
        try {
            if (process != null) process.destroy();
        } catch (Exception ignored) {
        }
    }
}
