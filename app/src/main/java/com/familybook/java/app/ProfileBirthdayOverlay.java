package com.familybook.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ScrollView;

import org.json.JSONObject;

import java.util.Calendar;

/**
 * কারো জন্মদিনে তার প্রোফাইলের কভার-হেডারের জায়গায় জন্মদিনের অ্যানিমেশন (assets/birthday_anim.html)।
 *
 *  - যে-ই প্রোফাইলে ঢুকুক (মালিক নিজে, ফলোয়ার, নন-ফলোয়ার, ফলোয়িং, পাবলিক) মালিকের সিলেক্ট করা অ্যানিমেশন দেখে
 *    (Settings > Birthday reminder > Select Animation: Tornado / Volcano — Supabase birthday_settings)।
 *  - "Birthday wishes" বা "Animation on profile" বন্ধ থাকলে কিছুই চলে না।
 *  - জন্মদিনের দিন প্রোফাইল খোলা থাকলে প্রথমে সাথে সাথে, তারপর প্রতি 15 মিনিট পর পর একবার করে চলে।
 *    (শেষ কখন চলেছে ফোনে জমা থাকে, তাই প্রোফাইল বারবার খুললেও 15 মিনিটের আগে আবার চলে না।)
 *  - প্রতিবার একটাই অ্যানিমেশন (~12 সেকেন্ড)। ডিসপ্লের নিচে ছোট ✕ বাটন দিলে বন্ধ। স্ক্রল করলেও বন্ধ।
 *  - অ্যানিমেশন চলার সময় ওভারলে WebView হেডারের ওপর বসে; শেষে সরে যায় ও আসল প্রোফাইল দেখা যায়।
 *
 * HTML <-> Java (window.FBBirthdayAnim): ready() / size(cssPx) / done() / close()
 * Java -> HTML: fbAnim.configure(json) + fbAnim.play(mode, sound)
 */
final class ProfileBirthdayOverlay {

    interface Source { String configJson(); }   // নাম / ছবি / কভার / ফলোয়ার সংখ্যা / isMe

    private static final long INTERVAL_MS = 15L * 60 * 1000;
    private static final long FIRST_DELAY_MS = 900;
    private static final String PAGE_URL = "file:///android_asset/birthday_anim.html";
    private static final String PREFS = "fb_bday_anim";

    private final Activity act;
    private final String ownerUid;
    private final View coverFrame;
    private final Source source;
    private final Handler h = new Handler(Looper.getMainLooper());

    private WebView web;
    private boolean allowed;
    private boolean resumed;
    private boolean destroyed;
    private String animation = "Tornado";
    private boolean scrollHooked;

    private final Runnable tick = this::playIfDue;

    ProfileBirthdayOverlay(Activity act, String ownerUid, View coverFrame, Source source) {
        this.act = act;
        this.ownerUid = ownerUid;
        this.coverFrame = coverFrame;
        this.source = source;
    }

    // ---------- Activity থেকে ----------

    /** জন্মতারিখ জানা গেলে ডাকুন (ফরম্যাট "দিন/মাস/সাল")। আজ জন্মদিন না হলে কিছুই করে না। */
    void onBirthDate(String dmy) {
        if (destroyed || !isBirthdayToday(dmy)) { allowed = false; return; }
        BirthdayPrefs.resolve(ownerUid, p -> {
            if (destroyed) return;
            allowed = p.optBoolean("wishes", true) && p.optBoolean("animProfile", true);
            String a = p.optString("animation", "Tornado");
            animation = "Volcano".equalsIgnoreCase(a) ? "Volcano" : "Tornado";
            h.removeCallbacks(tick);
            if (!allowed) { hide(); return; }
            if (resumed && web == null) schedule();
        });
    }

    void onResume() {
        resumed = true;
        if (allowed && web == null) schedule();
    }

    void onPause() {
        resumed = false;
        h.removeCallbacks(tick);
        hide();
    }

    void onDestroy() {
        destroyed = true;
        h.removeCallbacksAndMessages(null);
        hide();
    }

    // ---------- সময়সূচি ----------

    private static boolean isBirthdayToday(String dmy) {
        try {
            String[] p = dmy.trim().split("/");
            int d = Integer.parseInt(p[0].trim());
            int m = Integer.parseInt(p[1].trim());
            Calendar c = Calendar.getInstance();
            return c.get(Calendar.DAY_OF_MONTH) == d && c.get(Calendar.MONTH) + 1 == m;
        } catch (Exception e) {
            return false;
        }
    }

    private SharedPreferences sp() { return act.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    private String lastKey() {
        Calendar c = Calendar.getInstance();
        return ownerUid + "_" + c.get(Calendar.YEAR) + "_" + c.get(Calendar.DAY_OF_YEAR);
    }

    private void schedule() {
        if (destroyed || !resumed || !allowed) return;
        long last = sp().getLong(lastKey(), 0L);
        long due = last == 0L ? FIRST_DELAY_MS
                : Math.max(FIRST_DELAY_MS, INTERVAL_MS - (System.currentTimeMillis() - last));
        h.removeCallbacks(tick);
        h.postDelayed(tick, due);
    }

    private void playIfDue() {
        if (destroyed || !resumed || !allowed) return;
        ScrollView sv = act.findViewById(R.id.profileScroll);
        if (sv != null && sv.getScrollY() > 0) {          // ইউজার নিচে স্ক্রল করা — কভার দেখা যাচ্ছে না
            h.postDelayed(tick, 30_000);
            return;
        }
        sp().edit().putLong(lastKey(), System.currentTimeMillis()).apply();
        show();
        h.postDelayed(tick, INTERVAL_MS);                  // পরের 15 মিনিটের স্লট
    }

    // ---------- ওভারলে ----------

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void show() {
        if (destroyed) return;
        hide();
        final ViewGroup content = act.findViewById(android.R.id.content);
        if (content == null || coverFrame == null) return;

        final float dp = act.getResources().getDisplayMetrics().density;
        int[] a = new int[2], b = new int[2];
        coverFrame.getLocationOnScreen(a);
        content.getLocationOnScreen(b);

        web = new WebView(act);
        web.setBackgroundColor(Color.TRANSPARENT);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setTextZoom(100);
        s.setMediaPlaybackRequiresUserGesture(false);   // তাফ/বজ্র/ব্লাস্টের শব্দ নিজে থেকে বাজে
        web.setVerticalScrollBarEnabled(false);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return !r.getUrl().toString().startsWith("file:///android_asset/");
            }
        });
        web.addJavascriptInterface(new Bridge(), "FBBirthdayAnim");

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(520 * dp));
        lp.gravity = Gravity.TOP;
        lp.topMargin = Math.max(0, a[1] - b[1]);
        content.addView(web, lp);
        hookScroll();
        web.loadUrl(PAGE_URL);
    }

    private void hookScroll() {
        if (scrollHooked) return;
        ScrollView sv = act.findViewById(R.id.profileScroll);
        if (sv == null) return;
        scrollHooked = true;
        sv.getViewTreeObserver().addOnScrollChangedListener(() -> {
            if (web != null && sv.getScrollY() > 0) hide();
        });
    }

    private void hide() {
        if (web == null) return;
        WebView w = web;
        web = null;
        try {
            ViewGroup p = (ViewGroup) w.getParent();
            if (p != null) p.removeView(w);
            w.stopLoading();
            w.destroy();
        } catch (Exception ignored) { }
    }

    private class Bridge {
        /** পেজ তৈরি: ডাটা পাঠিয়ে সিলেক্ট করা অ্যানিমেশন চালু */
        @JavascriptInterface public void ready() {
            h.post(() -> {
                if (web == null || destroyed) return;
                String cfg = source == null ? "{}" : source.configJson();
                web.evaluateJavascript("fbAnim.configure(" + cfg + ");fbAnim.play('"
                        + ("Volcano".equals(animation) ? "volcano" : "tornado") + "',true);", null);
            });
        }

        /** পেজের আসল উচ্চতা (CSS px) → ওভারলে উচ্চতা */
        @JavascriptInterface public void size(final int cssPx) {
            h.post(() -> {
                if (web == null || cssPx <= 0) return;
                ViewGroup.LayoutParams lp = web.getLayoutParams();
                if (lp == null) return;
                lp.height = Math.round(cssPx * act.getResources().getDisplayMetrics().density);
                web.setLayoutParams(lp);
            });
        }

        /** অ্যানিমেশন শেষ */
        @JavascriptInterface public void done() { h.post(ProfileBirthdayOverlay.this::hide); }

        /** ✕ চাপা হয়েছে */
        @JavascriptInterface public void close() { h.post(ProfileBirthdayOverlay.this::hide); }
    }
}
