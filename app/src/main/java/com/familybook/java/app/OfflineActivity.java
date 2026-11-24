package com.familybook.app;

import android.annotation.SuppressLint;
import android.content.Intent;
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
 * Settings & Privacy-এর কোনো পেজ নেট ছাড়া ও ক্যাশ ছাড়া খুললে assets/offline.html দেখায়।
 * (SettingsGate এখানে পাঠায়।) "Try again" চাপলে নেট থাকলে আগের পেজটাই খোলে।
 * পেজের ডার্ক/লাইট ও ভাষা অ্যাপের সেটিং থেকে আসে (ThemePrefs + LanguagePrefs)।
 */
public class OfflineActivity extends AppCompatActivity {

    static final String EXTRA_TARGET = "target";               // গন্তব্য Activity-র simple name
    static final String EXTRA_TARGET_EXTRAS = "target_extras"; // গন্তব্যের Intent extras

    private static final String PAGE_URL = "file:///android_asset/offline.html";

    private WebView web;
    private String target;
    private android.os.Bundle targetExtras;
    private final Handler h = new Handler(Looper.getMainLooper());

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        target = getIntent().getStringExtra(EXTRA_TARGET);
        targetExtras = getIntent().getBundleExtra(EXTRA_TARGET_EXTRAS);

        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        web.setBackgroundColor(DC.night() ? 0xFF18191A : 0xFFFFFFFF);   // লোডের আগের ফ্ল্যাশ এড়াতে
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return !r.getUrl().toString().startsWith("file:///android_asset/");
            }
        });
        web.addJavascriptInterface(new Bridge(), "FBOffline");
        web.loadUrl(PAGE_URL);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { finish(); }
        });
    }

    @Override protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        if (web != null) web.destroy();
        web = null;
        super.onDestroy();
    }

    private void launchTarget() {
        if (target != null && !target.isEmpty()) {
            try {
                Class<?> c = Class.forName(getPackageName() + "." + target);
                Intent i = new Intent(this, c);
                if (targetExtras != null) i.putExtras(targetExtras);
                startActivity(i);
            } catch (Exception ignored) { }
        }
        finish();
    }

    private class Bridge {
        /** অ্যাপের ভাষার কোড, যেমন "bn" / "en" / "zh" */
        @JavascriptInterface public String lang() {
            String t = LanguagePrefs.tag(LanguagePrefs.getApp(OfflineActivity.this));
            if (t == null) return "en";
            int i = t.indexOf('-');
            return i > 0 ? t.substring(0, i) : t;
        }

        /** ডার্ক মোড চালু কি (On / System ফোন ডার্ক হলে) */
        @JavascriptInterface public boolean dark() { return DC.night(); }

        @JavascriptInterface public void close() { h.post(OfflineActivity.this::finish); }

        /** Try again: নেট থাকলে আগের পেজ খোলে, নয়তো JS-কে জানায় এখনো নেট নেই */
        @JavascriptInterface public void retry() {
            h.post(() -> {
                SettingsGate.warm();
                if (Net.isOnline() && SettingsGate.canOpen(target == null ? "" : target)) {
                    launchTarget();
                } else if (web != null) {
                    web.evaluateJavascript("window.fbStillOffline&&fbStillOffline()", null);
                }
            });
        }
    }
}
