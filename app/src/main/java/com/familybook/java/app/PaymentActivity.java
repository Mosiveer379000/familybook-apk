package com.familybook.app;

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

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.Locale;

/**
 * Settings > Ads payments: assets/add_payment.html WebView-তে খোলে।
 * Visa/Mastercard যোগ করলে শুধু brand, holder, last4, expiry Supabase-এ সেভ হয়।
 * কার্ডের পুরো নম্বর ও CVV কখনো অ্যাপ বা ডাটাবেসে যায় না (PCI নিরাপত্তা)।
 */
public class PaymentActivity extends AppCompatActivity {

    private static final String PAGE_URL = "file:///android_asset/add_payment.html";

    private WebView web;
    private String uid;
    private boolean destroyed;
    private final Handler h = new Handler(Looper.getMainLooper());

    // সর্বশেষ লোড হওয়া কার্ড (activity-তে card_label বানাতে লাগে)
    private JSONObject card;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        uid = SupabaseRest.uid();
        if (!SupabaseRest.isLoggedIn() || uid == null) { finish(); return; }

        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);          // android_asset এতে আটকায় না
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
        web.addJavascriptInterface(new Bridge(), "FBPayApp");
        WebDark.attach(web);
        web.loadUrl(PAGE_URL);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                web.evaluateJavascript("window.fbPayBack?fbPayBack():false", v -> {
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

    // ---------------- JS-এ পাঠানো ----------------
    private void toJs(String fn, String... args) {
        h.post(() -> {
            if (destroyed || web == null) return;
            StringBuilder sb = new StringBuilder(fn).append("(");
            for (int i = 0; i < args.length; i++) {
                if (i > 0) sb.append(",");
                sb.append(args[i]);
            }
            web.evaluateJavascript(sb.append(")").toString(), null);
        });
    }

    private void result(String op, boolean ok, String msg) {
        toJs("fbPayResult", JSONObject.quote(op), String.valueOf(ok), JSONObject.quote(msg));
    }

    // ---------------- লোড ----------------
    private int pending;
    private JSONObject loaded;

    private void loadAll() {
        loaded = new JSONObject();
        pending = 3;
        SupabaseRest.call("GET", "/rest/v1/payment_methods?user_id=eq." + uid
                + "&select=brand,holder,last4,expiry", null, null, true, r -> {
            try {
                JSONArray a = r.ok() ? new JSONArray(r.body) : new JSONArray();
                card = a.length() > 0 ? a.getJSONObject(0) : null;
                loaded.put("card", card == null ? JSONObject.NULL : card);
            } catch (Exception ignored) { card = null; }
            step();
        });
        SupabaseRest.call("GET", "/rest/v1/payment_profiles?user_id=eq." + uid
                + "&select=data", null, null, true, r -> {
            try {
                JSONArray a = r.ok() ? new JSONArray(r.body) : new JSONArray();
                loaded.put("tax", a.length() > 0 ? a.getJSONObject(0).opt("data") : JSONObject.NULL);
            } catch (Exception ignored) { }
            step();
        });
        SupabaseRest.call("GET", "/rest/v1/payment_activity?user_id=eq." + uid
                + "&select=title,amount,status,card_label,created_at&order=created_at.desc&limit=100",
                null, null, true, r -> {
            try {
                loaded.put("activities", r.ok() ? new JSONArray(r.body) : new JSONArray());
            } catch (Exception ignored) { }
            step();
        });
    }

    private void step() {
        if (--pending > 0) return;
        toJs("fbPayLoaded", "JSON.parse(" + JSONObject.quote(loaded.toString()) + ")");
    }

    // ---------------- ব্রিজ (HTML থেকে ডাকা হয়) ----------------
    private class Bridge {

        @JavascriptInterface public void load() { h.post(PaymentActivity.this::loadAll); }

        @JavascriptInterface public void close() { h.post(PaymentActivity.this::finish); }

        /** শুধু brand/holder/last4/expiry নেয়; অন্য কিছু (নম্বর/CVV) এলেও ফেলে দেয় */
        @JavascriptInterface public void saveCard(String json) {
            h.post(() -> {
                try {
                    JSONObject in = new JSONObject(json);
                    String brand = in.optString("brand", "");
                    String holder = in.optString("holder", "").trim().toUpperCase(Locale.US);
                    String last4 = in.optString("last4", "");
                    String expiry = in.optString("expiry", "").trim();

                    if (!brand.equals("Visa") && !brand.equals("Mastercard")) {
                        result("saveCard", false, "Only Visa or Mastercard is supported."); return;
                    }
                    if (holder.length() < 2 || holder.length() > 60) {
                        result("saveCard", false, "Please enter the name on your card."); return;
                    }
                    if (!last4.matches("\\d{4}")) {
                        result("saveCard", false, "Please enter a valid card number."); return;
                    }
                    if (!expiry.matches("(0[1-9]|1[0-2])/\\d{2}") || expired(expiry)) {
                        result("saveCard", false, "This card has expired or the date is invalid."); return;
                    }

                    JSONObject row = new JSONObject()
                            .put("user_id", uid).put("brand", brand).put("holder", holder)
                            .put("last4", last4).put("expiry", expiry);
                    SupabaseRest.call("POST", "/rest/v1/payment_methods?on_conflict=user_id", row.toString(),
                            "resolution=merge-duplicates,return=minimal", true, r -> {
                        if (r.ok()) { result("saveCard", true, brand + " card saved to your Account"); loadAll(); }
                        else result("saveCard", false, "Could not save the card. Please try again.");
                    });
                } catch (Exception e) {
                    result("saveCard", false, "Could not save the card. Please try again.");
                }
            });
        }

        @JavascriptInterface public void removeCard() {
            h.post(() -> SupabaseRest.call("DELETE", "/rest/v1/payment_methods?user_id=eq." + uid,
                    null, "return=minimal", true, r -> {
                if (r.ok()) { result("removeCard", true, "Card removed"); loadAll(); }
                else result("removeCard", false, "Could not remove the card.");
            }));
        }

        @JavascriptInterface public void saveTax(String json) {
            h.post(() -> {
                try {
                    JSONObject in = new JSONObject(json);
                    JSONObject clean = new JSONObject();
                    String[] keys = {"country", "currency", "timezone", "businessName", "tinAddress", "city", "state", "zip"};
                    for (String k : keys) {
                        String v = in.optString(k, "").trim();
                        if (v.length() > 200) v = v.substring(0, 200);
                        clean.put(k, v);
                    }
                    JSONObject row = new JSONObject().put("user_id", uid).put("data", clean);
                    SupabaseRest.call("POST", "/rest/v1/payment_profiles?on_conflict=user_id", row.toString(),
                            "resolution=merge-duplicates,return=minimal", true, r -> {
                        if (r.ok()) { result("saveTax", true, "Business & tax info saved"); loadAll(); }
                        else result("saveTax", false, "Could not save. Please try again.");
                    });
                } catch (Exception e) {
                    result("saveTax", false, "Could not save. Please try again.");
                }
            });
        }

        @JavascriptInterface public void addActivity(String json) {
            h.post(() -> {
                try {
                    if (card == null) { result("addActivity", false, "Please add a Visa/Mastercard first"); return; }
                    JSONObject in = new JSONObject(json);
                    String title = in.optString("title", "").trim();
                    if (title.length() > 80) title = title.substring(0, 80);
                    double amount = in.optDouble("amount", 0);
                    String status = in.optString("status", "Paid");
                    if (title.isEmpty() || amount <= 0 || amount > 100000) {
                        result("addActivity", false, "Invalid payment."); return;
                    }
                    String label = card.optString("brand") + " •••• " + card.optString("last4");
                    JSONObject row = new JSONObject().put("user_id", uid).put("title", title)
                            .put("amount", amount).put("status", status).put("card_label", label);
                    final String brand = card.optString("brand");
                    final double amt = amount;
                    SupabaseRest.call("POST", "/rest/v1/payment_activity", row.toString(),
                            "return=minimal", true, r -> {
                        if (r.ok()) {
                            result("addActivity", true, String.format(Locale.US, "Paid $%.2f with %s", amt, brand));
                            loadAll();
                        } else result("addActivity", false, "Payment failed. Please try again.");
                    });
                } catch (Exception e) {
                    result("addActivity", false, "Payment failed. Please try again.");
                }
            });
        }

        @JavascriptInterface public void clearActivities() {
            h.post(() -> SupabaseRest.call("DELETE", "/rest/v1/payment_activity?user_id=eq." + uid,
                    null, "return=minimal", true, r -> {
                if (r.ok()) { result("clearActivities", true, "Activity cleared"); loadAll(); }
                else result("clearActivities", false, "Could not clear activity.");
            }));
        }
    }

    /** MM/YY মেয়াদ পার হয়ে গেছে কি */
    private static boolean expired(String mmYY) {
        int mm = Integer.parseInt(mmYY.substring(0, 2));
        int yy = Integer.parseInt(mmYY.substring(3));
        Calendar c = Calendar.getInstance();
        int curYY = c.get(Calendar.YEAR) % 100;
        int curMM = c.get(Calendar.MONTH) + 1;
        return (yy * 12 + mm) < (curYY * 12 + curMM);
    }
}
