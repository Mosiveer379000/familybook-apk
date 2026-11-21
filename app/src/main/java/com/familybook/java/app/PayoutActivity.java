package com.familybook.app;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

/** Payout (আয় ও উত্তোলন): assets/payout/index.html (+ script.js, style.css) WebView-তে খোলে। Ads payments-এর PaymentActivity থেকে আলাদা। */
public class PayoutActivity extends AppCompatActivity {

    private static final String PAGE = "file:///android_asset/payout/index.html";
    private WebView web;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!SupabaseRest.isLoggedIn() || SupabaseRest.uid() == null) { finish(); return; }

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);                   // android_asset এতে আটকায় না
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);

        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                // নিজের পেজের বাইরে কোনো নেভিগেশন নয়
                return !r.getUrl().toString().startsWith("file:///android_asset/");
            }
        });
        web.loadUrl(PAGE);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                web.evaluateJavascript(
                        "(window.nativeBack && window.nativeBack()) === true",
                        v -> { if (!"true".equals(v)) finish(); });
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.removeJavascriptInterface("Android");
            web.destroy();
        }
        super.onDestroy();
    }

    private class Bridge {
        /** {"token":"...","uid":"..."} — ব্যাকগ্রাউন্ড থ্রেডে চলে, দরকারে টোকেন রিফ্রেশ করে */
        @JavascriptInterface
        public String getSession() {
            try {
                String token = SupabaseRest.accessToken();
                String uid = SupabaseRest.uid();
                if (token == null || uid == null) return "{}";
                return new JSONObject().put("token", token).put("uid", uid).toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        /** মূল আইডির (নিজের) প্রোফাইলে যাবে */
        @JavascriptInterface
        public void openProfile() {
            runOnUiThread(() -> {
                ProfileActivity.open(PayoutActivity.this, SupabaseRest.uid(), null);
            });
        }

        /** avatar_key থেকে ছবি এনে data-URL হিসেবে JS-এ পাঠায় (window.onAvatar) */
        @JavascriptInterface
        public void requestAvatar(final String key) {
            runOnUiThread(() -> Media.loadBitmap(key, bm -> {
                if (isFinishing() || web == null) return;
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                bm.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, bo);
                String url = "data:image/jpeg;base64,"
                        + android.util.Base64.encodeToString(bo.toByteArray(), android.util.Base64.NO_WRAP);
                web.evaluateJavascript("window.onAvatar&&window.onAvatar('" + url + "')", null);
            }, (v, err) -> { }));
        }

        @JavascriptInterface
        public void close() {
            runOnUiThread(PayoutActivity.this::finish);
        }
    }
}
