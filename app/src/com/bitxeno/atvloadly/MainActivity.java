package com.bitxeno.atvloadly;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Window;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.graphics.Color;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;

/**
 * Native Android shell for atvloadly.
 *
 * The Go backend (cross-compiled, embedded as libatvloadly.so) is launched as a
 * child process bound to localhost. The bundled Vue frontend is embedded inside
 * that same Go binary and served over HTTP, so the WebView simply points at
 * 127.0.0.1. This reuses the original atvloadly UI (already mobile-tuned and
 * Apple-styled) instead of reimplementing it.
 */
public class MainActivity extends Activity {
    private static final int PORT = 9000;
    private static final String URL = "http://127.0.0.1:" + PORT + "/";
    private static final int FILE_CHOOSER_REQUEST = 1001;

    private Process serverProcess;
    private WifiManager.MulticastLock multicastLock;
    private WebView webView;
    private View loadingView;
    // Pending callback from the WebView's <input type="file"> so we can hand the
    // picked IPA back to the page. Android WebView needs onShowFileChooser wired
    // up or the file button silently does nothing.
    private ValueCallback<Uri[]> fileUploadCallback;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // iOS-like edge-to-edge light status bar
        Window w = getWindow();
        w.setStatusBarColor(Color.TRANSPARENT);
        w.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

        // Hold a multicast lock so mDNS/zeroconf can discover Apple TV.
        try {
            WifiManager wm = (WifiManager) getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                multicastLock = wm.createMulticastLock("atvloadly-mdns");
                multicastLock.setReferenceCounted(true);
                multicastLock.acquire();
            }
        } catch (Exception ignored) {
        }

        buildUi();
        startServer();
    }

    private void buildUi() {
        // Root: loading splash overlaid on the web view.
        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);

        webView = new WebView(this);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                hideLoading();
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view,
                                             ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                // Cancel any previous picker still pending.
                if (fileUploadCallback != null) {
                    fileUploadCallback.onReceiveValue(null);
                }
                fileUploadCallback = callback;
                try {
                    Intent intent = params.createIntent();
                    // IPAs are zip-based; accept any file so the picker isn't
                    // over-restrictive, the page filters by extension itself.
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                } catch (Exception e) {
                    fileUploadCallback = null;
                    return false;
                }
                return true;
            }
        });

        root.addView(webView, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));

        loadingView = makeLoadingView();
        root.addView(loadingView, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));

        // Floating "Logs" pill so every backend operation/error is viewable
        // in-app (the WebView has no console). Reads the backend's app.log.
        root.addView(makeLogsButton(), new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.BOTTOM | android.view.Gravity.END));

        setContentView(root);
    }

    /** Small translucent "Logs" pill anchored bottom-right. */
    private View makeLogsButton() {
        android.widget.Button b = new android.widget.Button(this);
        b.setText("日志");
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTextColor(Color.WHITE);
        b.getBackground().setAlpha(150);
        int pad = (int) (10 * getResources().getDisplayMetrics().density);
        b.setPadding(pad, pad / 2, pad, pad / 2);
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
        int m = (int) (16 * getResources().getDisplayMetrics().density);
        lp.setMargins(0, 0, m, m);
        b.setLayoutParams(lp);
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showLogs(); }
        });
        return b;
    }

    /** Bottom-sheet style log viewer showing the tail of app.log, auto-refreshing. */
    private void showLogs() {
        final android.widget.LinearLayout panel = new android.widget.LinearLayout(this);
        panel.setOrientation(android.widget.LinearLayout.VERTICAL);
        panel.setBackgroundColor(Color.parseColor("#111114"));

        // Header: title + close + auto-refresh toggle.
        android.widget.LinearLayout header = new android.widget.LinearLayout(this);
        header.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        header.setPadding((int) (16 * getResources().getDisplayMetrics().density),
                (int) (12 * getResources().getDisplayMetrics().density),
                (int) (16 * getResources().getDisplayMetrics().density),
                (int) (12 * getResources().getDisplayMetrics().density));
        android.widget.TextView title = new android.widget.TextView(this);
        title.setText("运行日志");
        title.setTextSize(16);
        title.setTextColor(Color.WHITE);
        android.widget.LinearLayout.LayoutParams tp = new android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        header.addView(title, tp);
        android.widget.CheckBox auto = new android.widget.CheckBox(this);
        auto.setText("自动刷新");
        auto.setTextColor(Color.parseColor("#AEAEB2"));
        header.addView(auto);
        android.widget.TextView close = new android.widget.TextView(this);
        close.setText("关闭");
        close.setTextSize(15);
        close.setTextColor(Color.parseColor("#0A84FF"));
        close.setPadding((int) (12 * getResources().getDisplayMetrics().density), 0, 0, 0);
        header.addView(close);
        panel.addView(header, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));

        final android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        final android.widget.TextView logView = new android.widget.TextView(this);
        logView.setTextIsSelectable(true);
        logView.setTextSize(11);
        logView.setTextColor(Color.parseColor("#E5E5EA"));
        logView.setFontFeatureSettings("monospace");
        int p = (int) (12 * getResources().getDisplayMetrics().density);
        logView.setPadding(p, p, p, p);
        scroll.addView(logView, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT));
        panel.addView(scroll, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        final android.app.Dialog dialog = new android.app.Dialog(this,
                android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.setContentView(panel);
        dialog.getWindow().setLayout(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                (int) (getResources().getDisplayMetrics().heightPixels * 0.75));
        dialog.getWindow().setGravity(android.view.Gravity.BOTTOM);
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { dialog.dismiss(); }
        });

        // Load immediately, then auto-refresh every second while open.
        final Handler h = new Handler(Looper.getMainLooper());
        final java.util.concurrent.atomic.AtomicBoolean open =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        final java.io.File logFile = new File(
                new File(getFilesDir(), "data"), "app.log");
        final StringBuilder last = new StringBuilder();
        Runnable tick = new Runnable() {
            public void run() {
                if (!open.get() || !dialog.isShowing()) { open.set(false); return; }
                String text = readLogTail(logFile);
                if (!text.equals(last.toString())) {
                    last.setLength(0);
                    last.append(text);
                    logView.setText(text.length() == 0 ? "(暂无日志)" : text);
                    scroll.post(new Runnable() {
                        public void run() { scroll.fullScroll(View.FOCUS_DOWN); }
                    });
                }
                if (auto.isChecked()) h.postDelayed(this, 1000);
            }
        };
        h.post(tick);
        dialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            public void onDismiss(android.content.DialogInterface d) { open.set(false); }
        });
        dialog.show();
    }

    /** Read the last ~32KB of a log file; returns "" if missing. */
    private String readLogTail(File f) {
        try {
            if (!f.exists()) return "";
            java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r");
            long len = raf.length();
            int want = 32 * 1024;
            long start = Math.max(0, len - want);
            raf.seek(start);
            byte[] buf = new byte[(int) (len - start)];
            raf.readFully(buf);
            raf.close();
            String s = new String(buf, java.nio.charset.StandardCharsets.UTF_8);
            // Drop a possibly-partial first line when we truncated the head.
            if (start > 0) {
                int nl = s.indexOf('\n');
                if (nl >= 0) s = s.substring(nl + 1);
            }
            return s;
        } catch (Exception e) {
            return "(读取日志失败: " + e + ")";
        }
    }

    /** Minimal iOS-style splash: centered wordmark + activity spinner. */
    private View makeLoadingView() {
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setGravity(android.view.Gravity.CENTER);
        box.setBackgroundColor(Color.WHITE);

        android.widget.TextView title = new android.widget.TextView(this);
        title.setText("atvloadly");
        title.setTextSize(30);
        title.setLetterSpacing(-0.02f);
        title.setTextColor(Color.parseColor("#1C1C1E"));
        box.addView(title);

        android.widget.ProgressBar pb = new android.widget.ProgressBar(this);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (28 * getResources().getDisplayMetrics().density);
        box.addView(pb, lp);

        android.widget.TextView hint = new android.widget.TextView(this);
        hint.setText("Starting local server…");
        hint.setTextSize(14);
        hint.setTextColor(Color.parseColor("#8E8E93"));
        android.widget.LinearLayout.LayoutParams hp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        hp.topMargin = (int) (14 * getResources().getDisplayMetrics().density);
        box.addView(hint, hp);

        return box;
    }

    private void hideLoading() {
        if (loadingView != null) {
            loadingView.animate().alpha(0f).setDuration(250)
                    .withEndAction(new Runnable() {
                        public void run() { loadingView.setVisibility(View.GONE); }
                    }).start();
        }
    }

    private void startServer() {
        new Thread(new Runnable() {
            public void run() {
                try {
                    File home = getFilesDir();
                    File bin = new File(getApplicationInfo().nativeLibraryDir, "libatvloadly.so");
                    if (!bin.exists()) {
                        // Fallback: some devices expose it via applicationInfo.sourceDir lib dir.
                        bin = new File("/data/data/" + getPackageName() + "/lib/libatvloadly.so");
                    }
                    File cfg = writeConfig(home);
                    File work = new File(home, "data");
                    if (!work.exists()) work.mkdirs();
                    seedSettings(work);

                    // Android 10+ (W^X) forbids exec from app-writable dirs
                    // (filesDir), so we cannot copy the engine there. The engine
                    // stays in nativeLibraryDir (exec-allowed) as
                    // libplumesign.so. The backend resolves the bare name
                    // "plumesign" to PLUMESIGN_BIN when set, so point it there.
                    File engine = findNative("libplumesign.so");

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
                    serverProcess = pb.start();

                    // Drain stdout/stderr so the child never blocks on a full pipe.
                    final InputStream is = serverProcess.getInputStream();
                    new Thread(new Runnable() {
                        public void run() {
                            BufferedReader r = new BufferedReader(new InputStreamReader(is));
                            try { while (r.readLine() != null) { } } catch (Exception ignored) { }
                        }
                    }).start();

                    waitForServerThenLoad();
                } catch (Exception e) {
                    showFatal(String.valueOf(e));
                }
            }
        }).start();
    }

    /**
     * Locate a native binary shipped as a "lib" (e.g. libplumesign.so) inside
     * the exec-allowed nativeLibraryDir. Returns null if not found.
     */
    private File findNative(String libName) {
        File f = new File(getApplicationInfo().nativeLibraryDir, libName);
        if (f.exists()) return f;
        // Fallback path used by some devices/Android versions.
        File alt = new File("/data/data/" + getPackageName() + "/lib/" + libName);
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
     * The background auto-refresh toggle lives in settings.json (loaded from the
     * work dir), not in config.yaml. Per product decision Android does no
     * background auto-refresh — manual refresh only — so we seed settings.json
     * with the refresh task disabled on first launch. Manual refresh endpoints
     * stay fully functional.
     */
    private void seedSettings(File work) {
        File sf = new File(work, "settings.json");
        if (sf.exists()) return; // respect choices the user made in the UI
        try {
            OutputStreamWriter osw = new OutputStreamWriter(new java.io.FileOutputStream(sf));
            osw.write("{\"task\":{\"enabled\":false,\"iphone_enabled\":false}}");
            osw.close();
        } catch (Exception ignored) {
        }
    }

    private void waitForServerThenLoad() {
        // Poll until the embedded HTTP server accepts connections, then load UI.
        int waited = 0;
        while (waited < 20000) {
            if (isPortOpen()) break;
            try { Thread.sleep(300); } catch (InterruptedException ignored) { }
            waited += 300;
        }
        handler.post(new Runnable() {
            public void run() { webView.loadUrl(URL); }
        });
    }

    private boolean isPortOpen() {
        java.net.Socket sock = new java.net.Socket();
        try {
            sock.connect(new java.net.InetSocketAddress("127.0.0.1", PORT), 250);
            sock.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void showFatal(final String msg) {
        handler.post(new Runnable() {
            public void run() {
                if (loadingView instanceof android.view.ViewGroup) {
                    android.view.ViewGroup g = (android.view.ViewGroup) loadingView;
                    g.removeAllViews();
                    android.widget.TextView t = new android.widget.TextView(MainActivity.this);
                    t.setText("Failed to start:\n" + msg);
                    t.setTextColor(Color.parseColor("#FF3B30"));
                    g.addView(t);
                }
            }
        });
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_REQUEST) {
            ValueCallback<Uri[]> callback = fileUploadCallback;
            fileUploadCallback = null;
            if (callback == null) return;
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                results = new Uri[]{ data.getData() };
            }
            callback.onReceiveValue(results);
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) webView.destroy();
        if (serverProcess != null) serverProcess.destroy();
        if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        super.onDestroy();
    }
}