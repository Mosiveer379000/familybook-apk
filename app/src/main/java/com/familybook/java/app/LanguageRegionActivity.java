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
 * Settings > Language: assets/language_region_settings.html WebView-তে খোলে।
 * HTML-এ ভাষা বাছলে JS -> FBLangApp.setApp()/setTrans() -> LanguagePrefs (সেভ + অ্যাপে প্রয়োগ)।
 */
public class LanguageRegionActivity extends AppCompatActivity {

    private static final String PAGE_URL = "file:///android_asset/language_region_settings.html";

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
        web.setBackgroundColor(isDarkNow() ? 0xFF18191A : 0xFFFFFFFF);   // লোডের আগের ফ্ল্যাশ এড়াতে
        web.setWebViewClient(new WebGuard.Client() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return !r.getUrl().toString().startsWith("file:///android_asset/");
            }
        });
        web.addJavascriptInterface(new Bridge(), "FBLangApp");
        web.loadUrl(PAGE_URL);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                web.evaluateJavascript("window.fbLangBack?fbLangBack():false", v -> {
                    if (!"true".equals(v)) finish();
                });
            }
        });
    }

    private boolean isDarkNow() {
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
        @JavascriptInterface public void close() { h.post(LanguageRegionActivity.this::finish); }

        @JavascriptInterface public boolean isDark() { return isDarkNow(); }

        /** সেভ করা অ্যাপ ভাষা, যেমন "English (US)" */
        @JavascriptInterface public String getApp() { return LanguagePrefs.getApp(LanguageRegionActivity.this); }

        /** সেভ করা অনুবাদের ভাষা, যেমন "Bengali" */
        @JavascriptInterface public String getTrans() { return LanguagePrefs.getTrans(LanguageRegionActivity.this); }

        /** অ্যাপের ভাষা বদলালে: সেভ + পুরো অ্যাপে প্রয়োগ */
        @JavascriptInterface public void setApp(final String name) {
            h.post(() -> LanguagePrefs.setApp(LanguageRegionActivity.this, name));
        }

        @JavascriptInterface public void setTrans(final String name) {
            h.post(() -> LanguagePrefs.setTrans(LanguageRegionActivity.this, name));
        }
    }
}
