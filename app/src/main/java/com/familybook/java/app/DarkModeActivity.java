package com.familybook.app;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Settings > Dark mode: assets/dark_mode_settings.html WebView-তে খোলে।
 * HTML-এর On/Off/System রেডিও চাপলে JS -> FBDarkApp.setMode() -> ThemePrefs (সেভ + পুরো অ্যাপে প্রয়োগ)।
 */
public class DarkModeActivity extends AppCompatActivity {

    private static final String PAGE_URL = "file:///android_asset/dark_mode_settings.html";

    private WebView web;
    private final Handler h = new Handler(Looper.getMainLooper());

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        // লোড হওয়ার আগের ফ্ল্যাশ এড়াতে সেভ করা মোড অনুযায়ী ব্যাকগ্রাউন্ড
        web.setBackgroundColor(isDarkNow() ? 0xFF18191A : 0xFFFFFFFF);
        web.setWebViewClient(new WebGuard.Client() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return !r.getUrl().toString().startsWith("file:///android_asset/");
            }
        });
        web.addJavascriptInterface(new Bridge(), "FBDarkApp");
        web.loadUrl(PAGE_URL);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                web.evaluateJavascript("window.fbDarkBack?fbDarkBack():false", v -> {
                    if (!"true".equals(v)) finish();
                });
            }
        });
    }

    private boolean isDarkNow() {
        String m = ThemePrefs.get(this);
        if (ThemePrefs.ON.equals(m)) return true;
        if (ThemePrefs.OFF.equals(m)) return false;
        return (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    @Override protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        if (web != null) web.destroy();
        web = null;
        super.onDestroy();
    }

    /** HTML থেকে ডাকা হয় */
    private class Bridge {
        @JavascriptInterface public void close() { h.post(DarkModeActivity.this::finish); }

        /** সেভ করা মোড: "on" / "off" / "system" */
        @JavascriptInterface public String getMode() {
            return ThemePrefs.get(DarkModeActivity.this);
        }

        /** রেডিও বদলালে: সেভ + অ্যাপে প্রয়োগ */
        @JavascriptInterface public void setMode(final String mode) {
            h.post(() -> ThemePrefs.set(DarkModeActivity.this, mode));
        }
    }
}
