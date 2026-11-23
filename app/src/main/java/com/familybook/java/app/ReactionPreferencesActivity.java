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
 * Settings > Reaction preferences: assets/reaction_preferences.html WebView-তে খোলে।
 * সেটিং Supabase-এ (reaction_settings) সাথে সাথে সেভ হয়; টোকেন Java-তেই থাকে, WebView-তে যায় না।
 */
public class ReactionPreferencesActivity extends AppCompatActivity {

    private static final String PAGE_URL = "file:///android_asset/reaction_preferences.html";

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
        s.setDomStorageEnabled(true);          // সেভ করা সেটিং (localStorage) এর জন্য
        s.setAllowFileAccess(false);           // android_asset এতে আটকায় না
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        web.setBackgroundColor(0xFFFFFFFF);
        web.setWebViewClient(new WebGuard.Client() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                // শুধু নিজের asset পেজ; বাইরের লিংকে নেভিগেট করা যাবে না
                return !r.getUrl().toString().startsWith("file:///android_asset/");
            }
        });
        web.addJavascriptInterface(new Bridge(), "FBReactApp");
        WebDark.attach(web);
        web.loadUrl(PAGE_URL);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                web.evaluateJavascript("window.fbReactBack?fbReactBack():false", v -> {
                    if (!"true".equals(v)) finish();
                });
            }
        });
    }

    @Override protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        if (web != null) web.destroy();
        super.onDestroy();
    }

    /** HTML থেকে ডাকা হয় */
    private class Bridge {
        @JavascriptInterface public void close() { h.post(ReactionPreferencesActivity.this::finish); }

        /** সার্ভার থেকে সেটিং এনে JS-এর fbReactLoaded(json) ডাকে */
        @JavascriptInterface public void load() {
            h.post(() -> ReactionPrefs.fetch(json -> {
                if (web == null) return;
                web.evaluateJavascript("window.fbReactLoaded&&fbReactLoaded("
                        + org.json.JSONObject.quote(json) + ")", null);
            }));
        }

        /** JS থেকে পুরো সেটিং JSON সেভ */
        @JavascriptInterface public void save(final String json) {
            h.post(() -> ReactionPrefs.save(json));
        }

        /** সেটিং অ্যাকাউন্ট অনুযায়ী আলাদা রাখতে */
        @JavascriptInterface public String uid() {
            String u = SupabaseRest.uid();
            return u == null ? "" : u;
        }
    }
}
