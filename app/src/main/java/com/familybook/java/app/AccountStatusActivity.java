package com.familybook.app;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;

/**
 * Settings > Account status  (আর Profile > Profile Status): assets/account_status.html WebView-তে খোলে।
 *
 * তিনটা পেজ HTML-এর ভেতরেই: Account status -> Everything you manage -> Profile status।
 * ডাটা আসল (SettingsCache মারফত, তাই অফলাইনে সর্বশেষ কপি দেখায়):
 *   - profiles            : নাম, ছবি, ব্লু ব্যাজ
 *   - user_warnings       : Community Standards ভাঙার ওয়ার্নিং (Content Moderation.sql)
 *   - group_members/groups: যেসব গ্রুপের তুমি অ্যাডমিন
 * গত ৩০ দিনের ওয়ার্নিং গুনে HTML নিজে Good / Warned / Final warning ও Health % বের করে।
 *
 * HTML <-> Java (window.FBStatus):
 *   start()     : "account" বা "profile" (কোন পেজ আগে খুলবে)
 *   load()      : ডাটা আনে -> window.fbStatusData(json)
 *   openInbox() : Report a Problem > Inbox খোলে
 *   open(name)  : "terms" | "community" | "privacy" | "blue_badge"
 *   close()     : স্ক্রিন বন্ধ
 *   Back বাটন   : window.fbStatusBack() (true = HTML নিজে সামলেছে)
 */
public class AccountStatusActivity extends AppCompatActivity {

    /** "profile" দিলে সরাসরি Profile status পেজ খোলে (ProfileSettingsActivity এটা ব্যবহার করে) */
    static final String EXTRA_START = "start";

    private static final String PAGE_URL = "file:///android_asset/account_status.html";

    private WebView web;
    private String startPage = "account";
    private boolean destroyed;
    private int loadGen;
    private final Handler h = new Handler(Looper.getMainLooper());

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!SupabaseRest.isLoggedIn() || SupabaseRest.uid() == null) { finish(); return; }

        startPage = "profile".equals(getIntent().getStringExtra(EXTRA_START)) ? "profile" : "account";

        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setTextZoom(100);

        web.setBackgroundColor(DC.bg());   // লোডের আগের ফ্ল্যাশ এড়াতে
        web.setWebViewClient(new WebGuard.Client() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return !r.getUrl().toString().startsWith("file:///android_asset/");
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView v, int progress) {
                WebDark.inject(v);   // অ্যাপ ডার্ক হলে পেজও ডার্ক
            }
        });
        web.addJavascriptInterface(new Bridge(), "FBStatus");
        web.loadUrl(PAGE_URL);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                web.evaluateJavascript("window.fbStatusBack?fbStatusBack():false", v -> {
                    if (!"true".equals(v)) finish();
                });
            }
        });
    }

    /** ফোনের থিম বদলালে পেজ নতুন করে আঁকে (ডার্ক CSS ঠিক বসতে) */
    @Override public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        if (web == null) return;
        web.setBackgroundColor(DC.bg());
        web.reload();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        h.removeCallbacksAndMessages(null);
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }

    // ------------------------------------------------------------------ ডাটা আনা

    private static final class State {
        int got;
        boolean profileOk, warnOk, sent;
        String name = "", avatarKey = "";
        boolean badge;
        JSONArray warnings = new JSONArray();
        JSONArray groups = new JSONArray();
    }

    private void load() {
        final String me = SupabaseRest.uid();
        if (me == null) { send(errorJson()); return; }
        final int gen = ++loadGen;
        final State st = new State();

        SettingsCache.get(SettingsCache.statusProfilePath(me), r -> {
            if (r.ok()) {
                try {
                    JSONArray a = new JSONArray(r.body);
                    if (a.length() > 0) {
                        JSONObject o = a.getJSONObject(0);
                        st.name = o.isNull("name") ? "" : o.optString("name", "");
                        st.avatarKey = o.isNull("avatar_key") ? "" : o.optString("avatar_key", "");
                        st.badge = o.optBoolean("verified_badge", false);
                        st.profileOk = true;
                    }
                } catch (Exception ignored) { }
            }
            step(st, gen);
        });

        SettingsCache.get(SettingsCache.warningsPath(me), r -> {
            if (r.ok()) {
                try { st.warnings = new JSONArray(r.body); st.warnOk = true; } catch (Exception ignored) { }
            } else if (r.code == 400 || r.code == 404) {
                // user_warnings টেবিল/কলাম এখনো সার্ভারে নেই (Content Moderation.sql চালানো হয়নি) →
                // পেজ আটকে না রেখে "কোনো ওয়ার্নিং নেই" ধরে খুলি। নেট/অথ সমস্যায় আগের মতোই Try again।
                st.warnings = new JSONArray();
                st.warnOk = true;
            }
            step(st, gen);
        });

        // যেসব গ্রুপের অ্যাডমিন তুমি (না পেলে খালি তালিকা — পেজ ভাঙে না)
        SettingsCache.get(SettingsCache.statusGroupsPath(me), r -> {
            if (r.ok()) {
                try {
                    JSONArray a = new JSONArray(r.body);
                    for (int i = 0; i < a.length(); i++) {
                        JSONObject g = a.getJSONObject(i).optJSONObject("groups");
                        if (g == null) continue;
                        String id = g.optString("id", "");
                        String name = g.optString("name", "");
                        if (id.isEmpty() || name.isEmpty()) continue;
                        st.groups.put(new JSONObject().put("id", id).put("name", name));
                    }
                } catch (Exception ignored) { }
            }
            step(st, gen);
        });
    }

    private void step(State st, int gen) {
        if (destroyed || gen != loadGen) return;
        if (++st.got < 3) return;

        if (!st.profileOk || !st.warnOk) { send(errorJson()); return; }

        if (st.avatarKey.isEmpty()) { deliver(st, ""); return; }
        // ছবি না আসলেও ৬ সেকেন্ড পরে ছবি ছাড়াই পেজ দেখাই
        h.postDelayed(() -> deliver(st, ""), 6000);
        Media.loadBitmap(st.avatarKey, bm -> deliver(st, toDataUrl(bm)), (x, err) -> deliver(st, ""));
    }

    private void deliver(State st, String avatar) {
        if (destroyed || st.sent) return;
        st.sent = true;
        try {
            send(new JSONObject()
                    .put("name", st.name)
                    .put("avatar", avatar)
                    .put("badge", st.badge)
                    .put("warnings", st.warnings)
                    .put("groups", st.groups)
                    .toString());
        } catch (Exception e) {
            send(errorJson());
        }
    }

    private static String errorJson() { return "{\"error\":true}"; }

    /** গোল ছবির জন্য মাঝখান থেকে বর্গ করে ছোট JPEG data URL */
    private static String toDataUrl(Bitmap bm) {
        if (bm == null) return "";
        try {
            int side = Math.min(bm.getWidth(), bm.getHeight());
            Bitmap sq = Bitmap.createBitmap(bm, (bm.getWidth() - side) / 2, (bm.getHeight() - side) / 2, side, side);
            Bitmap small = Bitmap.createScaledBitmap(sq, 192, 192, true);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            small.compress(Bitmap.CompressFormat.JPEG, 85, bos);
            return "data:image/jpeg;base64," + android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }

    private void send(String json) {
        if (destroyed || web == null) return;
        web.evaluateJavascript("window.fbStatusData&&fbStatusData(" + JSONObject.quote(json) + ")", null);
    }

    // ------------------------------------------------------------------ HTML <-> Java

    private class Bridge {
        @JavascriptInterface public String start() { return startPage; }

        @JavascriptInterface public void close() { h.post(AccountStatusActivity.this::finish); }

        @JavascriptInterface public void load() { h.post(AccountStatusActivity.this::load); }

        /** Report a Problem > Inbox (ওয়ার্নিংয়ের বিস্তারিত ওখানে) */
        @JavascriptInterface public void openInbox() {
            h.post(() -> SettingsGate.open(AccountStatusActivity.this,
                    new Intent(AccountStatusActivity.this, ReportProblemActivity.class)
                            .putExtra(ReportProblemActivity.EXTRA_START, "inbox")));
        }

        @JavascriptInterface public void open(final String what) {
            h.post(() -> {
                if ("blue_badge".equals(what)) {
                    SettingsGate.open(AccountStatusActivity.this, new Intent(AccountStatusActivity.this, BlueBadgeActivity.class));
                } else if ("terms".equals(what) || "community".equals(what) || "privacy".equals(what)) {
                    startActivity(new Intent(AccountStatusActivity.this, TermsActivity.class)
                            .putExtra(TermsActivity.EXTRA_PAGE, what));
                }
            });
        }
    }
}
