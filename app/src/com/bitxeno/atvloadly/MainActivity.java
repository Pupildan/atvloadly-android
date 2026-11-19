package com.bitxeno.atvloadly;

import android.app.Activity;
import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Window;
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

    private Process serverProcess;
    private WifiManager.MulticastLock multicastLock;
    private WebView webView;
    private View loadingView;
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
        webView.setWebChromeClient(new WebChromeClient());

        root.addView(webView, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));

        loadingView = makeLoadingView();
        root.addView(loadingView, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));

        setContentView(root);
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

                    ProcessBuilder pb = new ProcessBuilder(
                            bin.getAbsolutePath(), "server",
                            "--config", cfg.getAbsolutePath(),
                            "--port", String.valueOf(PORT));
                    pb.environment().put("HOME", home.getAbsolutePath());
                    pb.environment().put("TMPDIR", home.getAbsolutePath());
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
    protected void onDestroy() {
        if (webView != null) webView.destroy();
        if (serverProcess != null) serverProcess.destroy();
        if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        super.onDestroy();
    }
}