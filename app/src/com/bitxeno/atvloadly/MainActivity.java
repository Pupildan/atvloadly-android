package com.bitxeno.atvloadly;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.widget.FrameLayout;

/**
 * Native Android shell for atvloadly.
 *
 * The Go backend (cross-compiled, embedded as libatvloadly.so) is launched as a
 * child process bound to localhost:9000. This activity no longer renders the
 * bundled web UI: it builds a pure-native iOS-styled interface out of
 * android.* views and talks to the backend's JSON API and WebSockets directly.
 *
 * Flow: splash (backend boot) -> home hub -> device list -> pairing -> accounts
 * -> sideload -> installed apps, each a full-screen panel on a manual Nav stack.
 */
public class MainActivity extends Activity {

    private static final int FILE_CHOOSER_REQUEST = 1001;

    private Ui ui;
    private Nav nav;
    private Backend backend;
    private WifiManager.MulticastLock multicastLock;

    // Panels cached so revisiting a tab is instant and preserves state.
    private HomePage homePage;
    private DeviceListPage deviceListPage;
    private AccountsPage accountsPage;
    private AppListPage appListPage;
    private SideloadPage sideloadPage;

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

        ui = new Ui(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);
        setContentView(root);
        nav = new Nav(this, root);

        showSplash();
        bootBackend();
    }

    /** Splash while the Go backend boots; retried on failure. */
    private void showSplash() {
        final SplashPage splash = new SplashPage(ui, new Runnable() {
            public void run() {
                bootBackend();
            }
        });
        nav.setRoot(splash);
    }

    private void bootBackend() {
        if (backend == null) backend = new Backend(this);
        backend.start(new Backend.ReadyCallback() {
            public void onReady(String version) {
                App.MAIN.post(new Runnable() {
                    public void run() {
                        showHome();
                    }
                });
            }

            public void onFailed(final String error) {
                App.MAIN.post(new Runnable() {
                    public void run() {
                        if (nav.current() instanceof SplashPage) {
                            ((SplashPage) nav.current()).showError(error);
                        }
                    }
                });
            }
        });
    }

    // ---- Panel wiring ------------------------------------------------------

    private void showHome() {
        if (homePage == null) {
            homePage = new HomePage(ui, new HomePage.Host() {
                public void openDevices() {
                    nav.push(devicesPage());
                }

                public void openSideload() {
                    nav.push(sideloadPage());
                }

                public void openApps() {
                    nav.push(appsPage());
                }

                public void openAccounts() {
                    nav.push(accountsPage());
                }
            });
        }
        nav.push(homePage);
    }

    private DeviceListPage devicesPage() {
        if (deviceListPage == null) {
            deviceListPage = new DeviceListPage(ui, new DeviceListPage.Host() {
                public void onDevicePicked(org.json.JSONObject device) {
                    openPairingOrSideload(device);
                }
            });
        }
        return deviceListPage;
    }

    private AccountsPage accountsPage() {
        if (accountsPage == null) {
            accountsPage = new AccountsPage(ui, new AccountsPage.Host() {
                public void onAccountPicked(String email) {
                    App.selectedAccount = email;
                    nav.push(sideloadPage());
                }
            });
        }
        return accountsPage;
    }

    private AppListPage appsPage() {
        if (appListPage == null) appListPage = new AppListPage(ui);
        return appListPage;
    }

    private SideloadPage sideloadPage() {
        if (sideloadPage == null) {
            sideloadPage = new SideloadPage(ui, new SideloadPage.Host() {
                public void requestIpaPicker() {
                    openIpaPicker();
                }
            });
        }
        return sideloadPage;
    }

    /** Route a picked device to pairing when needed, otherwise to sideload. */
    private void openPairingOrSideload(org.json.JSONObject device) {
        App.selectedDevice = device;
        String status = Json.str(device, "status");
        if ("paired".equals(status)) {
            nav.push(sideloadPage());
        } else {
            nav.push(new PairPage(ui, device, new PairPage.Host() {
                public void onPaired(org.json.JSONObject d) {
                    App.selectedDevice = d;
                    // Give the backend a beat to register the new pairing, then
                    // advance to sideload.
                    App.MAIN.postDelayed(new Runnable() {
                        public void run() {
                            nav.push(sideloadPage());
                        }
                    }, 600);
                }
            }));
        }
    }

    // ---- IPA file picker ---------------------------------------------------

    private void openIpaPicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // IPAs are zip-based; accept any file so the picker isn't over-restrictive.
        intent.setType("*/*");
        try {
            startActivityForResult(
                    Intent.createChooser(intent, "选择 IPA 文件"), FILE_CHOOSER_REQUEST);
        } catch (Exception e) {
            android.widget.Toast.makeText(this, "无法打开文件选择器",
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_REQUEST) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                Uri uri = data.getData();
                if (sideloadPage != null) sideloadPage.onIpaPicked(uri);
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    // ---- Lifecycle ---------------------------------------------------------

    @Override
    public void onBackPressed() {
        if (!nav.back()) {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (sideloadPage != null) sideloadPage.close();
        if (backend != null) backend.shutdown();
        if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        super.onDestroy();
    }
}
