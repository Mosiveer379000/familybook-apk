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
 * Settings > Media: opens assets/media_settings.html in a WebView.
 * Changes are saved to Supabase when the user presses back (media_settings); the token stays in Java and never reaches the WebView.
 */
public class MediaSettingsActivity extends AppCompatActivity {

    private static final String PAGE_URL = "file:///android_asset/media_settings.html";

    /** Set after a successful save; SettingsActivity shows the banner once and clears it */
    static boolean savedBanner = false;

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
        s.setDomStorageEnabled(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        web.setBackgroundColor(0xFFFFFFFF);
        web.setWebViewClient(new WebGuard.Client() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return !r.getUrl().toString().startsWith("file:///android_asset/");
            }
        });
        web.addJavascriptInterface(new Bridge(), "FBMediaApp");
        WebDark.attach(web);
        web.loadUrl(PAGE_URL);

        // Back: the page saves to Supabase first, then closes itself
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                web.evaluateJavascript("window.fbMediaBack?fbMediaBack():false", v -> {
                    if (!"true".equals(v)) finish();
                });
            }
        });
    }

    @Override protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        if (web != null) web.destroy();
        web = null;
        super.onDestroy();
    }

    private class Bridge {
        @JavascriptInterface public void close() { h.post(MediaSettingsActivity.this::finish); }

        /** Instant: last known settings from this phone's cache */
        @JavascriptInterface public String cached() {
            return MediaPrefs.cachedJson(MediaSettingsActivity.this);
        }

        /** Pull from Supabase, then call fbMediaLoaded(json) */
        @JavascriptInterface public void load() {
            h.post(() -> MediaPrefs.fetch(MediaSettingsActivity.this, json -> {
                if (web == null) return;
                web.evaluateJavascript("window.fbMediaLoaded&&fbMediaLoaded("
                        + org.json.JSONObject.quote(json) + ")", null);
            }));
        }

        /** Save to Supabase, then call fbMediaSaved(true/false) */
        @JavascriptInterface public void save(final String json) {
            h.post(() -> MediaPrefs.save(MediaSettingsActivity.this, json, ok -> {
                if (ok) savedBanner = true;
                if (web == null) return;
                web.evaluateJavascript("window.fbMediaSaved&&fbMediaSaved(" + ok + ")", null);
            }));
        }

        @JavascriptInterface public String uid() {
            String u = SupabaseRest.uid();
            return u == null ? "" : u;
        }
    }
}
