package com.familybook.app;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ClayStore আপডেট গণনা। GitHub-এর সর্বশেষ Release-এর সাথে ফোনে ইনস্টল থাকা ভার্সন মেলায়।
 * - ইনস্টল করা অ্যাপের নতুন ভার্সন থাকলে আপডেট সংখ্যা (Home-এর ClayStore আইকনে লাল ডট)
 * - ফোনের নোটিফিকেশন প্যানেলে আপডেটের নোটিফিকেশন
 * - ক্লিক করলে ClayStore খোলে; আপডেট হয়ে গেলে ডট ও নোটিফিকেশন নিজে চলে যায়
 *
 * এখন শুধু FamilyBook। নতুন অ্যাপ যোগ করার সময় APK_NAME/APK_PKG-কে তালিকায় বদলাতে হবে
 * (assets/claystore/app.js-এর PKG_BY_APK-এও একই লাইন)।
 */
public final class UpdateChecker {

    public interface Listener {
        /** মেইন থ্রেডে ডাকা হয় */
        void onUpdates(int count, List<String> names);
    }

    private static final String OWNER = "Mosiveer379000";
    /** Release-এর APK ফাইলের নাম -> Android package (repo-র নাম যাই হোক, এই APK দেখেই অ্যাপ চেনা হয়) */
    private static final String APK_NAME = "FamilyBook.apk";
    private static final String APK_PKG = "com.familybook.app";

    private static final String PREFS = "update_prefs", CH_ID = "app_update";
    private static final int NOTIF_ID = 7001;
    private static final long FETCH_GAP_MS = 5 * 60 * 1000;   // GitHub রেট-লিমিট বাঁচাতে

    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final List<String[]> rels = new ArrayList<>();   // {repo, package, tag, apk_url, published_at}
    private static Listener listener;
    private static long lastFetch;
    private static boolean fetching, askedPerm, hasData;
    private static String shownKey = "";
    private static Context appCtx;
    /** অ্যাপ খোলা থাকলে নিজে থেকেই প্রতি ~৫ মিনিটে নতুন Release খোঁজে (আগে শুধু স্ক্রিনে ফিরলে খুঁজত) */
    private static final Runnable POLL = new Runnable() {
        @Override public void run() {
            if (appCtx == null) return;
            refresh(appCtx);
            H.postDelayed(this, FETCH_GAP_MS + 2000);
        }
    };

    private UpdateChecker() { }

    public static void setListener(Listener l) { listener = l; }

    /** FamilyBookApp প্রতিটি Activity-র onResume-এ ডাকে */
    public static void onResume(Activity act) {
        if (Build.VERSION.SDK_INT >= 33 && !askedPerm && act.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            askedPerm = true;
            act.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIF_ID);
        }
        appCtx = act.getApplicationContext();
        H.removeCallbacks(POLL);
        H.postDelayed(POLL, FETCH_GAP_MS + 2000);
        if (SupabaseRest.isLoggedIn()) refresh(act.getApplicationContext());
    }

    public static void onPause(Activity act) { H.removeCallbacks(POLL); }

    /** অ্যাপ বন্ধ থাকলেও UpdateWorker (WorkManager) এটা ডাকে: নতুন Release থাকলে ফোনের নোটিফিকেশন বানায় */
    public static void backgroundCheck(Context app) {
        try {
            if (!SupabaseRest.isLoggedIn()) return;
            String[] r = findSelf(app);
            final List<String[]> out = new ArrayList<>();
            if (r != null) out.add(r);
            List<String> names = new ArrayList<>();
            StringBuilder key = new StringBuilder();
            for (String[] x : out) {
                if (needsUpdate(app, x)) { names.add(pretty(x[0])); key.append(x[1]).append(':').append(x[2]).append(';'); }
            }
            notifyPanel(app, names, key.toString());
            H.post(() -> { rels.clear(); rels.addAll(out); hasData = true; publish(app); });
        } catch (Throwable ignored) { }
    }
    public static void onStop(Activity act) { }

    /** ইনস্টল থাকা ভার্সন আবার মিলিয়ে (নেট ছাড়াই) listener-কে জানায়; দরকার হলে GitHub থেকে নতুন Release আনে */
    public static void refresh(Context app) {
        publish(app);
        if (fetching || System.currentTimeMillis() - lastFetch < FETCH_GAP_MS) return;
        fetching = true;
        lastFetch = System.currentTimeMillis();
        new Thread(() -> {
            List<String[]> out = new ArrayList<>();
            boolean ok = false;
            try {
                String[] r = findSelf(app);
                ok = true;
                if (r != null) out.add(r);
            } catch (Throwable ignored) { }
            final boolean good = ok;
            H.post(() -> {
                fetching = false;
                if (good) { rels.clear(); rels.addAll(out); hasData = true; }
                else lastFetch = 0;               // ব্যর্থ হলে পরের বার আবার চেষ্টা
                publish(app);
            });
        }, "clay-upd").start();
    }

    /** FamilyBook.apk আছে এমন repo-র সর্বশেষ Release। প্রথমবার খুঁজে repo-র নাম ফোনে মনে রাখে (রেট-লিমিট বাঁচাতে) */
    private static String[] findSelf(Context app) throws Exception {
        SharedPreferences sp = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String saved = sp.getString("repo", "");
        if (!saved.isEmpty()) {
            String[] r = release(saved);
            if (r != null) return r;
        }
        String list = get("https://api.github.com/users/" + OWNER + "/repos?per_page=100");
        if (list == null) throw new Exception("repo list");
        JSONArray ra = new JSONArray(list);
        for (int i = 0; i < ra.length(); i++) {
            JSONObject o = ra.getJSONObject(i);
            if (o.optBoolean("fork")) continue;
            String[] r = release(o.optString("name"));
            if (r != null) { sp.edit().putString("repo", o.optString("name")).apply(); return r; }
        }
        return null;
    }

    private static String[] release(String repo) throws Exception {
        String j = get("https://api.github.com/repos/" + OWNER + "/" + repo + "/releases/latest");
        if (j == null) return null;      // 404: Release নেই
        JSONObject r = new JSONObject(j);
        JSONArray as = r.optJSONArray("assets");
        for (int i = 0; as != null && i < as.length(); i++) {
            JSONObject a = as.getJSONObject(i);
            if (APK_NAME.equals(a.optString("name")))
                return new String[]{repo, APK_PKG, r.optString("tag_name"), a.optString("browser_download_url"), r.optString("published_at")};
        }
        return null;
    }

    private static String get(String u) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(10000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "ClayStore");
        if (c.getResponseCode() != 200) return null;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream()))) {
            StringBuilder sb = new StringBuilder(); String l;
            while ((l = br.readLine()) != null) sb.append(l);
            return sb.toString();
        }
    }

    /** Release প্রকাশের পর অ্যাপ ফোনে ইনস্টল/আপডেট না হয়ে থাকলে আপডেট আছে (build.gradle-এর versionName লাগে না) */
    private static final long SKEW_MS = 5 * 60 * 1000;
    private static boolean needsUpdate(Context app, String[] r) {
        try {
            long installed = app.getPackageManager().getPackageInfo(r[1], 0).lastUpdateTime;
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US);
            f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            long published = f.parse(r[4]).getTime();
            return published - installed > SKEW_MS;
        } catch (Throwable notInstalledOrBadDate) { return false; }
    }

    private static String norm(String v) { return v == null ? "" : v.trim().replaceFirst("^[vV]", ""); }

    private static String pretty(String repo) {
        String s = repo.replaceAll("[-_]+apk$", "").replaceAll("[-_]+", " ").trim();
        return s.isEmpty() ? repo : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** ইনস্টল আছে + Release-এর ভার্সন আলাদা = আপডেট আছে */
    private static void publish(Context app) {
        if (!hasData) return;
        List<String> names = new ArrayList<>();
        StringBuilder key = new StringBuilder();
        for (String[] r : rels) {
            if (needsUpdate(app, r)) { names.add(pretty(r[0])); key.append(r[1]).append(':').append(r[2]).append(';'); }
        }
        notifyPanel(app, names, key.toString());
        if (listener != null) listener.onUpdates(names.size(), names);
    }

    /** একই আপডেট-সেটের জন্য পপআপ একবারই (অ্যাপ খোলার সময়) */
    public static String updateKey(List<String> names) { return String.join(",", names); }
    public static boolean popupNeeded(List<String> names) { return !names.isEmpty() && !updateKey(names).equals(shownKey); }
    public static void popupShown(List<String> names) { shownKey = updateKey(names); }

    // ---------- ফোনের নোটিফিকেশন প্যানেল ----------
    private static void notifyPanel(Context app, List<String> names, String key) {
        try {
            NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            SharedPreferences sp = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (names.isEmpty()) {                       // সব আপডেট হয়ে গেছে: নোটিফিকেশন চলে যাবে
                nm.cancel(NOTIF_ID);
                sp.edit().remove("notified_key").apply();
                return;
            }
            if (key.equals(sp.getString("notified_key", ""))) return;   // একই আপডেটের জন্য আবার নয়
            if (Build.VERSION.SDK_INT >= 33 && app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) return;
            if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(
                    new NotificationChannel(CH_ID, "App updates", NotificationManager.IMPORTANCE_HIGH));
            Intent open = new Intent(app, ClayStoreActivity.class);
            if (names.size() == 1) open.putExtra(ClayStoreActivity.EXTRA_APP, firstRepo(app));
            PendingIntent pend = PendingIntent.getActivity(app, 0, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            String title = names.size() == 1 ? names.get(0) + " : New Update" : "ClayStore : " + names.size() + " updates available";
            nm.notify(NOTIF_ID, new NotificationCompat.Builder(app, CH_ID)
                    .setSmallIcon(R.drawable.ic_family_header)
                    .setContentTitle(title)
                    .setContentText("Open ClayStore to update")
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(String.join(", ", names)))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true).setContentIntent(pend).build());
            sp.edit().putString("notified_key", key).apply();
        } catch (Throwable ignored) { }
    }

    private static String firstRepo(Context app) {
        for (String[] r : rels) {
            if (needsUpdate(app, r)) return r[0];
        }
        return ClayStoreActivity.SELF_REPO;
    }
}
