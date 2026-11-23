package com.familybook.app;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Settings > Buy blue badge: assets/buy_blue_badge.html WebView-তে খোলে।
 * - অ্যাডমিন (masudd16@gmail.com) বিনাশর্তে ব্লু ব্যাজ পায়; profiles.verified_badge = true হয়।
 * - বাকিরা শর্ত সাপেক্ষে (NID verified + অ্যাকাউন্ট ৩ মাস); পোস্ট/ভিডিওর শর্ত এখনো যাচাই হয় না,
 *   তাই সাধারণ ইউজার আপাতত Not eligible দেখে।
 * - Subscription কেনার বাটন আপাতত ডেমো (HTML-এ টোস্ট)।
 */
public class BlueBadgeActivity extends AppCompatActivity {

    private static final String PAGE_URL = "file:///android_asset/buy_blue_badge.html";
    private static final long THREE_MONTHS_MS = 90L * 24 * 60 * 60 * 1000;

    private WebView web;
    private boolean destroyed;
    private final Handler h = new Handler(Looper.getMainLooper());

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!SupabaseRest.isLoggedIn() || SupabaseRest.uid() == null) { finish(); return; }

        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
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
        web.addJavascriptInterface(new Bridge(), "FBBadgeApp");
        WebDark.attach(web);
        web.loadUrl(PAGE_URL);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                web.evaluateJavascript("window.fbBadgeBack?fbBadgeBack():false", v -> {
                    if (!"true".equals(v)) finish();
                });
            }
        });
    }

    @Override protected void onDestroy() {
        destroyed = true;
        h.removeCallbacksAndMessages(null);
        if (web != null) web.destroy();
        super.onDestroy();
    }

    /** ফলাফল JS-এ পাঠায় */
    private void sendResult(boolean eligible, boolean active, String... reasons) {
        if (destroyed || web == null) return;
        try {
            JSONArray rs = new JSONArray();
            for (String r : reasons) rs.put(r);
            String json = new JSONObject().put("eligible", eligible).put("active", active)
                    .put("reasons", rs).toString();
            web.evaluateJavascript("window.fbBadgeChecked&&fbBadgeChecked("
                    + JSONObject.quote(json) + ")", null);
        } catch (Exception ignored) { }
    }

    private class Bridge {
        @JavascriptInterface public void close() { h.post(BlueBadgeActivity.this::finish); }

        /** Check eligibility */
        @JavascriptInterface public void check() {
            h.post(() -> {
                final String me = SupabaseRest.uid();
                if (me == null) { sendResult(false, false, "Please log in again"); return; }

                if (BlueBadge.isAdmin()) {
                    // বিনাশর্তে: এখনকার ফ্ল্যাগ পড়ে দেখাই; প্রথমবার হলে চালু করে দিই
                    SupabaseRest.call("GET", "/rest/v1/profiles?id=eq." + me + "&select=verified_badge",
                            null, null, true, r -> {
                        boolean on = true;
                        boolean known = false;
                        if (r.ok()) {
                            try {
                                JSONArray a = new JSONArray(r.body);
                                if (a.length() > 0 && !a.getJSONObject(0).isNull("verified_badge")) {
                                    on = a.getJSONObject(0).optBoolean("verified_badge", false);
                                    known = true;
                                }
                            } catch (Exception ignored) { }
                        }
                        if (!known) BlueBadge.save(me, true);   // কখনো সেট না থাকলে ডিফল্ট: Activated
                        BlueBadge.remember(me, on);
                        sendResult(true, on);
                    });
                    return;
                }

                // সাধারণ ইউজার: শর্ত যাচাই
                SupabaseRest.call("GET", "/rest/v1/profiles?id=eq." + me + "&select=nid_verified,joined_at",
                        null, null, true, r -> {
                    if (!r.ok()) { sendResult(false, false, "Could not check. Try again."); return; }
                    boolean nid = false;
                    long joined = 0L;
                    try {
                        JSONArray a = new JSONArray(r.body);
                        if (a.length() > 0) {
                            JSONObject o = a.getJSONObject(0);
                            nid = o.optBoolean("nid_verified", false);
                            joined = BlueBadge.isoToMs(o.isNull("joined_at") ? null : o.optString("joined_at", null));
                        }
                    } catch (Exception ignored) { }
                    java.util.ArrayList<String> why = new java.util.ArrayList<>();
                    if (!nid) why.add("NID not verified");
                    if (joined == 0L || System.currentTimeMillis() - joined < THREE_MONTHS_MS)
                        why.add("Account is under 3 months");
                    // পোস্ট/ভিডিও/স্ট্যাটাসের সাপ্তাহিক শর্ত এখনো যাচাই হয় না — তাই আপাতত কেউ ফ্রি পায় না
                    why.add("Weekly activity not met");
                    sendResult(false, false, why.toArray(new String[0]));
                });
            });
        }

        /** Activated / Deactivated টগল (শুধু অ্যাডমিন; অন্যরা এই স্ক্রিনেই পৌঁছায় না) */
        @JavascriptInterface public void setActive(final boolean on) {
            h.post(() -> {
                String me = SupabaseRest.uid();
                if (me == null || !BlueBadge.isAdmin()) return;
                BlueBadge.remember(me, on);
                BlueBadge.save(me, on);
            });
        }
    }
}
