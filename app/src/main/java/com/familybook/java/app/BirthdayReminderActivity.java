package com.familybook.app;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

/**
 * 3-dot Menu > Birthday  এবং  Settings > Birthday reminder: assets/birthday_reminder.html WebView-তে খোলে।
 *
 * অফলাইন নিয়ম:
 *  - খোলার আগে SettingsGate দেখে নেয়: ফোনে নেট না থাকলে এই স্ক্রিন খোলেই না, OfflineActivity (assets/offline.html) খোলে।
 *    নেট ফিরলে offline.html-এর "Try again" (refresh) চাপলে বা Back করে আবার খুললে এই পেজ খোলে।
 *  - পেজ খোলা অবস্থায় Tailwind/ফন্ট আনতে না পারলে WebGuard.Client নিজেই offline.html দেখায়;
 *    সেখানকার "Try again" নেট ফিরলে একই WebView-তে birthday_reminder.html আবার লোড করে।
 *
 * HTML <-> Java:
 *   window.FBBirthday.close() : স্ক্রিন বন্ধ (পেজের Back তীর)
 *   window.FBBirthday.dark()  : অ্যাপ এখন ডার্ক কি
 *   window.FBBirthday.load()  : সেভ করা সেটিং আনে → fbBirthdayLoaded(json, name)
 *   window.FBBirthday.save(j) : Apply-এর সেটিং সেভ (Supabase birthday_settings)
 *   window.FBBirthday.today() : আজ যাদের জন্মদিন (ফলো করা মানুষ) আনে → fbBirthdayTodayLoaded(jsonArray) + fbBirthdayAvatar(uid, dataUri)
 *   window.FBBirthday.wish(uid, text) : শুভেচ্ছা পাঠায় → fbWishResult(uid, "ok"|"already"|"error", mode, error)
 *   window.FBBirthday.openProfile(uid, name) : তার প্রোফাইল খোলে
 *   window.FBBirthday.message(uid, name) : Family Chat-এ সরাসরি মেসেজ (তার "Wishes from followers" বন্ধ থাকলে)
 *   Java -> HTML: fbBirthdayBack() (হার্ডওয়্যার Back: Settings ট্যাব খোলা থাকলে আগে সেটা বন্ধ, true ফেরায়),
 *                 fbTheme(isDark)
 *   window.FBOffline : offline.html-এর জন্য (lang / dark / close / retry)
 */
public class BirthdayReminderActivity extends AppCompatActivity {

    private static final String PAGE_URL = "file:///android_asset/birthday_reminder.html";

    private WebView web;
    private final Handler h = new Handler(Looper.getMainLooper());

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!SupabaseRest.isLoggedIn()) {      // লগইন ছাড়া নিজের জন্মদিনের পেজ নয়
            finish();
            return;
        }

        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // পেজের localStorage (নাইট মোড ইত্যাদি)
        s.setAllowFileAccess(false);           // android_asset এতে আটকায় না
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setTextZoom(100);
        s.setMediaPlaybackRequiresUserGesture(false);   // প্রিভিউর শব্দ (Daf / Blast) বাজাতে

        web.setBackgroundColor(DC.night() ? 0xFF18191A : 0xFFFFFFFF);   // লোডের আগের ফ্ল্যাশ এড়াতে
        web.setWebViewClient(new WebGuard.Client() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                // শুধু নিজের asset পেজ; বাইরের লিংকে নেভিগেট করা যাবে না
                return !r.getUrl().toString().startsWith("file:///android_asset/");
            }
        });
        web.addJavascriptInterface(new Bridge(), "FBBirthday");
        web.addJavascriptInterface(new OfflineBridge(), "FBOffline");
        web.loadUrl(PAGE_URL);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (web == null) { finish(); return; }
                web.evaluateJavascript("window.fbBirthdayBack?fbBirthdayBack():false", v -> {
                    if (!"true".equals(v)) finish();
                });
            }
        });
    }

    /** ফোনের ডার্ক/লাইট বদলালে পেজও সাথে সাথে বদলায় */
    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        if (web == null) return;
        web.setBackgroundColor(DC.night() ? 0xFF18191A : 0xFFFFFFFF);
        web.evaluateJavascript("window.fbTheme&&fbTheme(" + DC.night() + ")", null);
    }

    @Override protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }

    /** birthday_reminder.html থেকে ডাকা হয় */
    private class Bridge {
        @JavascriptInterface public void close() { h.post(BirthdayReminderActivity.this::finish); }
        @JavascriptInterface public boolean dark() { return DC.night(); }

        /** সার্ভার থেকে নিজের সেভ করা সেটিং + নাম এনে JS-এর fbBirthdayLoaded(json, name) ডাকে */
        @JavascriptInterface public void load() {
            h.post(() -> BirthdayPrefs.fetch(SupabaseRest.uid(), json ->
                    PostOps.loadMyName(SupabaseRest.uid(), name -> {
                        if (web == null) return;
                        web.evaluateJavascript("window.fbBirthdayLoaded&&fbBirthdayLoaded("
                                + org.json.JSONObject.quote(json == null ? "" : json) + ","
                                + org.json.JSONObject.quote(name == null ? "" : name) + ")", null);
                    })));
        }

        /** Apply চাপলে: পুরো সেটিং JSON সেভ (প্রোফাইলে সবাই এটা পড়ে) */
        @JavascriptInterface public void save(final String json) {
            h.post(() -> BirthdayPrefs.save(json));
        }

        /** আজ যাদের জন্মদিন: তালিকা পাঠায়, তারপর একে একে ছোট প্রোফাইল ছবি (HTML সরাসরি ছবি আনতে পারে না) */
        @JavascriptInterface public void today() {
            h.post(() -> BirthdayToday.load(true, found -> checkSettings(found, list -> {
                if (web == null) return;
                JSONArray a = new JSONArray();
                for (BirthdayToday.Person p : list) {
                    try {
                        a.put(new JSONObject().put("uid", p.uid).put("name", p.name == null ? "" : p.name)
                                .put("mode", p.mode).put("wished", p.wished));
                    } catch (Exception ignored) { }
                }
                web.evaluateJavascript("window.fbBirthdayTodayLoaded&&fbBirthdayTodayLoaded("
                        + JSONObject.quote(a.toString()) + ")", null);
                BirthdayToday.markSeen(BirthdayReminderActivity.this);   // নোটিফিকেশনের নীল ডট চলে যায়
                for (BirthdayToday.Person p : list) sendAvatar(p);
            })));
        }

        /** নিজের নাম, জন্মতারিখ, আসল প্রোফাইল ছবি */
        @JavascriptInterface public void me() {
            final String id = SupabaseRest.uid();
            h.post(() -> SupabaseRest.call("GET", "/rest/v1/profiles?id=eq." + id
                    + "&select=name,avatar_key,info", null, null, true, r -> {
                if (web == null || !r.ok()) return;
                try {
                    JSONObject o = new JSONArray(r.body).getJSONObject(0);
                    JSONObject info = o.optJSONObject("info");
                    String dob = info == null ? "" : info.optString("dob", "");
                    boolean ver = !dob.isEmpty() && dob.equals(info.optString("nidVerDob", ""));
                    JSONObject out = new JSONObject().put("uid", id).put("dob", dob).put("verified", ver)
                            .put("name", o.isNull("name") ? "" : o.optString("name"));
                    web.evaluateJavascript("window.fbMeLoaded&&fbMeLoaded(" + JSONObject.quote(out.toString()) + ")", null);
                    BirthdayToday.Person p = new BirthdayToday.Person();
                    p.uid = id;
                    p.avatarKey = o.isNull("avatar_key") ? null : o.optString("avatar_key");
                    sendAvatar(p);
                } catch (Exception ignored) { }
            }));
        }

        /** Follower + Following সবার জন্মতারিখ (sql/13_birthdays_upcoming.sql) */
        @JavascriptInterface public void upcoming() {
            h.post(() -> SupabaseRest.call("POST", "/rest/v1/rpc/birthdays_upcoming", "{}", null, true, r -> {
                if (web == null || !r.ok()) return;
                web.evaluateJavascript("window.fbUpcomingLoaded&&fbUpcomingLoaded(" + JSONObject.quote(r.body) + ")", null);
                try {
                    JSONArray a = new JSONArray(r.body);
                    for (int i = 0; i < a.length(); i++) {
                        JSONObject o = a.getJSONObject(i);
                        BirthdayToday.Person p = new BirthdayToday.Person();
                        p.uid = o.optString("uid");
                        p.avatarKey = o.isNull("avatar_key") ? null : o.optString("avatar_key");
                        sendAvatar(p);
                    }
                } catch (Exception ignored) { }
            }));
        }

        /** দেখানোর আগে প্রত্যেকের সর্বশেষ সেটিং যাচাই: "Wishes from followers" অফ হলে message */
        private void checkSettings(final java.util.List<BirthdayToday.Person> list, final BirthdayToday.Cb done) {
            if (list.isEmpty()) { done.on(list); return; }
            StringBuilder in = new StringBuilder();
            for (BirthdayToday.Person p : list) in.append(in.length() > 0 ? "," : "").append(p.uid);
            SupabaseRest.call("GET", "/rest/v1/birthday_settings?user_id=in.(" + in + ")&select=user_id,prefs",
                    null, null, true, r -> {
                if (r.ok()) {
                    try {
                        JSONArray a = new JSONArray(r.body);
                        // তালিকার সবাই follower/following; ডিফল্ট wish, অফ করা থাকলে message
                        for (BirthdayToday.Person p : list) p.mode = "wish";
                        for (int i = 0; i < a.length(); i++) {
                            JSONObject o = a.getJSONObject(i);
                            JSONObject pr = o.optJSONObject("prefs");
                            if (pr == null || pr.optBoolean("followersWishes", true)) continue;
                            for (BirthdayToday.Person p : list)
                                if (p.uid.equals(o.optString("user_id"))) p.mode = "message";
                        }
                    } catch (Exception ignored) { }
                }
                done.on(list);
            });
        }

        /** "Your wishes": আমাকে পাঠানো শুভেচ্ছা */
        @JavascriptInterface public void myWishes() {
            h.post(() -> BirthdayWishes.forProfile(SupabaseRest.uid(), true, list -> {
                if (web == null) return;
                JSONArray a = new JSONArray();
                StringBuilder in = new StringBuilder();
                for (BirthdayWishes.Wish w : list) {
                    if (!"wish".equals(w.kind) || w.senderUid == null) continue;
                    try {
                        a.put(new JSONObject().put("uid", w.senderUid).put("name", w.senderName).put("text", w.message));
                    } catch (Exception ignored) { }
                    in.append(in.length() > 0 ? "," : "").append(w.senderUid);
                }
                web.evaluateJavascript("window.fbMyWishes&&fbMyWishes(" + JSONObject.quote(a.toString()) + ")", null);
                if (in.length() == 0) return;
                SupabaseRest.call("GET", "/rest/v1/profiles?id=in.(" + in + ")&select=id,avatar_key",
                        null, null, true, r -> {
                    if (web == null || !r.ok()) return;
                    try {
                        JSONArray rows = new JSONArray(r.body);
                        for (int i = 0; i < rows.length(); i++) {
                            JSONObject o = rows.getJSONObject(i);
                            BirthdayToday.Person p = new BirthdayToday.Person();
                            p.uid = o.optString("id");
                            p.avatarKey = o.isNull("avatar_key") ? null : o.optString("avatar_key");
                            sendAvatar(p);
                        }
                    } catch (Exception ignored) { }
                });
            }));
        }

        private void sendAvatar(final BirthdayToday.Person p) {
            if (p.avatarKey == null || p.avatarKey.isEmpty()) return;
            Media.loadBitmap(p.avatarKey, bm -> {
                if (web == null || bm == null) return;
                try {
                    int side = Math.min(bm.getWidth(), bm.getHeight());
                    Bitmap sq = Bitmap.createBitmap(bm, (bm.getWidth() - side) / 2, (bm.getHeight() - side) / 2, side, side);
                    Bitmap small = Bitmap.createScaledBitmap(sq, 128, 128, true);
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    small.compress(Bitmap.CompressFormat.JPEG, 80, bo);
                    String uri = "data:image/jpeg;base64," + Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
                    web.evaluateJavascript("window.fbBirthdayAvatar&&fbBirthdayAvatar("
                            + JSONObject.quote(p.uid) + "," + JSONObject.quote(uri) + ")", null);
                } catch (Throwable ignored) { }
            }, null);
        }

        /** শুভেচ্ছা পাঠানো (শুধু ফলোয়ার, বছরে একবার — সার্ভারও এটা মানে) */
        @JavascriptInterface public void wish(final String uid, final String text) {
            h.post(() -> BirthdayWishes.send(uid, text, (ok, mode, err) -> {
                if (web == null) return;
                String code = ok ? "ok" : (BirthdayWishes.ERR_ALREADY.equals(err) ? "already" : "error");
                String msg = ok || BirthdayWishes.ERR_ALREADY.equals(err) || err == null ? "" : err;
                web.evaluateJavascript("window.fbWishResult&&fbWishResult(" + JSONObject.quote(uid) + ","
                        + JSONObject.quote(code) + "," + JSONObject.quote(mode == null ? "" : mode) + ","
                        + JSONObject.quote(msg) + ")", null);
            }));
        }

        @JavascriptInterface public void message(final String uid, final String name) {
            h.post(() -> FamilyChatOpen.open(BirthdayReminderActivity.this, uid, name));
        }

        @JavascriptInterface public void openProfile(final String uid, final String name) {
            h.post(() -> ProfileActivity.open(BirthdayReminderActivity.this, uid, name));
        }
    }

    /** offline.html এই WebView-তে দেখালে তার দরকারি ফাংশন */
    private class OfflineBridge {
        @JavascriptInterface public String lang() {
            String t = LanguagePrefs.tag(LanguagePrefs.getApp(BirthdayReminderActivity.this));
            if (t == null) return "en";
            int i = t.indexOf('-');
            return i > 0 ? t.substring(0, i) : t;
        }

        @JavascriptInterface public boolean dark() { return DC.night(); }

        @JavascriptInterface public void close() { h.post(BirthdayReminderActivity.this::finish); }

        /** Try again: নেট থাকলে birthday_reminder.html আবার লোড, নয়তো JS-কে জানায় এখনো নেট নেই */
        @JavascriptInterface public void retry() {
            h.post(() -> {
                if (web == null) return;
                if (Net.isOnline()) {
                    SettingsGate.warm();
                    web.loadUrl(PAGE_URL);
                } else {
                    web.evaluateJavascript("window.fbStillOffline&&fbStillOffline()", null);
                }
            });
        }
    }
}
