package com.familybook.app;

import android.annotation.SuppressLint;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.util.List;

/**
 * 3-dot Menu (TreedotActivity) > "Report a problem": assets/report_problem.html WebView-তে খোলে।
 *
 * পেজে Reports ট্যাব (সমস্যা রিপোর্ট), Inbox ট্যাব এবং Admin-only "Public Reports"
 * (Hate Speech Policy 2026 v16.0: Medium/High Risk queue ও Reports queue) আছে।
 *
 * HTML <-> Java (window.FBReport):
 *   close()  : স্ক্রিন বন্ধ
 *   email()  : এখন লগইন করা অ্যাকাউন্টের ইমেইল (Admin যাচাইয়ে লাগে)
 *   uid()    : এখন লগইন করা ইউজারের id (নিজের রিপোর্ট ফিল্টারে লাগে)
 *   rest(cb, method, path, body, prefer) : Supabase REST (শুধু রিপোর্টের টেবিল/ফাংশন) — ফলাফল window.fbNative(cb, code, body)
 *   uploadShot(cb, dataUrl, name)        : স্ক্রিনশট R2-তে আপলোড — ফলাফল window.fbNative(cb, 200, key)
 *   loadShot(cb, key)                    : R2 key থেকে ছবি data URL-এ আনে
 * টেবিল বানাতে sql/10_report_problem.sql একবার Supabase SQL Editor-এ চালাতে হবে।
 * "Upload From Phone" (input type=file) চলার জন্য WebChromeClient.onShowFileChooser দেওয়া আছে।
 */
public class ReportProblemActivity extends AppCompatActivity {

    private static final String PAGE_URL = "file:///android_asset/report_problem.html";

    /** "inbox" দিলে সরাসরি Inbox ট্যাব খোলে (ওয়ার্নিং নোটিফিকেশন / Account status থেকে) */
    static final String EXTRA_START = "start";

    private static final String PREFS = "fb_report_cache";
    private static final String KEY_CACHED = "cached";

    /** একবার অনলাইনে খুললে/ওয়ার্ম হলে সত্য — তখন থেকে অফলাইনেও পেজ খোলে */
    static void markCached(android.content.Context c) {
        if (c == null) return;
        c.getApplicationContext().getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_CACHED, true).apply();
    }

    static boolean isCached(android.content.Context c) {
        return c != null && c.getApplicationContext().getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_CACHED, false);
    }

    private WebView web;
    private final Handler h = new Handler(Looper.getMainLooper());
    private ValueCallback<Uri[]> chooserCallback;

    private final ActivityResultLauncher<String> pickImages =
            registerForActivityResult(new ActivityResultContracts.GetMultipleContents(), this::deliverPicked);

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!SupabaseRest.isLoggedIn()) {      // লগইন ছাড়া রিপোর্ট/অ্যাডমিন পেজ নয়
            finish();
            return;
        }

        if (Net.isOnline()) markCached(this);

        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setMinimumFontSize(1);
        s.setMinimumLogicalFontSize(1);
        s.setTextZoom(100);

        web.setBackgroundColor(DC.night() ? 0xFF18191A : 0xFFFFFFFF);
        web.setWebViewClient(new WebGuard.Client() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return !r.getUrl().toString().startsWith("file:///android_asset/");
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView v, int progress) {
                WebDark.inject(v);   // অ্যাপ ডার্ক হলে পেজও ডার্ক
            }

            @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (chooserCallback != null) chooserCallback.onReceiveValue(null);
                chooserCallback = cb;
                try {
                    pickImages.launch("image/*");
                } catch (Exception e) {
                    deliverPicked(null);
                }
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "FBReport");
        web.loadUrl(PAGE_URL);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                web.evaluateJavascript("window.fbReportBack?fbReportBack():false", v -> {
                    if (!"true".equals(v)) finish();
                });
            }
        });
    }

    private void deliverPicked(List<Uri> uris) {
        if (chooserCallback == null) return;
        chooserCallback.onReceiveValue(uris == null || uris.isEmpty() ? null : uris.toArray(new Uri[0]));
        chooserCallback = null;
    }

    @Override protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        if (chooserCallback != null) { chooserCallback.onReceiveValue(null); chooserCallback = null; }
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }

    /** পেজ শুধু এই টেবিল/ফাংশনে পৌঁছাতে পারে (বাকি নিরাপত্তা Supabase RLS-এ) */
    private static boolean allowedPath(String path) {
        if (path == null || path.contains("..") || path.contains("\\")) return false;
        String p = path.startsWith("/") ? path.substring(1) : path;
        for (String t : new String[]{"problem_reports", "content_reports", "report_cases", "moderation_queue", "rpc/report_mark_read"}) {
            if (p.equals(t) || p.startsWith(t + "?")) return true;
        }
        return false;
    }

    /** JS-এ ফল ফেরত: window.fbNative(cb, code, body) */
    private void reply(String cb, int code, String body) {
        h.post(() -> {
            if (web == null) return;
            web.evaluateJavascript("window.fbNative&&fbNative(" + org.json.JSONObject.quote(cb) + ","
                    + code + "," + org.json.JSONObject.quote(body == null ? "" : body) + ")", null);
        });
    }

    private class Bridge {
        @JavascriptInterface public void close() { h.post(ReportProblemActivity.this::finish); }

        /** কোন ট্যাবে খুলবে: "inbox" বা খালি */
        @JavascriptInterface public String start() {
            String st = getIntent().getStringExtra(EXTRA_START);
            return st == null ? "" : st;
        }

        @JavascriptInterface public void rest(String cb, String method, String path, String body, String prefer) {
            String m = method == null ? "GET" : method.toUpperCase(java.util.Locale.ROOT);
            if (!SupabaseRest.isLoggedIn() || !allowedPath(path)
                    || !(m.equals("GET") || m.equals("POST") || m.equals("PATCH"))) {
                reply(cb, 400, "{\"message\":\"not allowed\"}");
                return;
            }
            SupabaseRest.call(m, "/rest/v1/" + (path.startsWith("/") ? path.substring(1) : path),
                    body == null || body.isEmpty() ? null : body,
                    prefer == null || prefer.isEmpty() ? null : prefer, true,
                    r -> reply(cb, r.code, r.ok() ? r.body : SupabaseRest.errorText(r)));
        }

        @JavascriptInterface public void uploadShot(String cb, String dataUrl, String name) {
            try {
                int comma = dataUrl.indexOf(',');
                String head = dataUrl.substring(0, Math.max(comma, 0));
                String mime = head.startsWith("data:") && head.contains(";") ? head.substring(5, head.indexOf(';')) : "image/jpeg";
                if (!mime.startsWith("image/")) { reply(cb, 400, "শুধু ছবি দেওয়া যাবে"); return; }
                byte[] bytes = android.util.Base64.decode(dataUrl.substring(comma + 1), android.util.Base64.DEFAULT);
                if (bytes.length > 12 * 1024 * 1024) { reply(cb, 400, "ছবি অনেক বড় (১২ MB-এর বেশি)"); return; }
                String nm = name == null || name.isEmpty() ? "report.jpg" : name.replaceAll("[^A-Za-z0-9._-]", "_");
                h.post(() -> Media.upload(bytes, "post", "report_" + nm, mime,
                        (key, err) -> reply(cb, err == null ? 200 : 500, err == null ? key : err)));
            } catch (Exception e) {
                reply(cb, 400, "ছবি পড়া যায়নি");
            }
        }

        @JavascriptInterface public void loadShot(String cb, String key) {
            h.post(() -> Media.loadBytes(key, (bytes, err) -> {
                if (err != null || bytes == null) { reply(cb, 500, err == null ? "error" : err); return; }
                reply(cb, 200, "data:image/jpeg;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP));
            }));
        }

        @JavascriptInterface public String uid() {
            String u = SupabaseRest.uid();
            return u == null ? "" : u;
        }

        @JavascriptInterface public String email() {
            String e = SupabaseRest.email();
            return e == null ? "" : e;
        }
    }
}
