package com.familybook.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.MessageDigest;

/**
 * Settings & Privacy-এর পেজগুলো যে সার্ভার-ডাটা পড়ে (নোটিফিকেশন, রিঅ্যাকশন, মিডিয়া, ব্লক লিস্ট,
 * অ্যাকাউন্ট/প্রোফাইল তথ্য ইত্যাদি) তার সর্বশেষ কপি ফোনে রাখে।
 *
 *  - অনলাইনে: সার্ভার থেকে আনে, সফল হলে ফাইলে সেভ করে।
 *  - অফলাইনে/সার্ভারে পৌঁছানো না গেলে: সেভ করা কপি ফেরত দেয় (পেজ আগের মতোই খোলে)।
 *  - কপি একেবারেই না থাকলে আসল ব্যর্থতাই ফেরত যায় → SettingsGate তখন offline.html দেখায়।
 *
 * ব্যবহার: SupabaseRest.call("GET", path, null, null, true, cb)-এর বদলে SettingsCache.get(path, cb)।
 * প্রতি ইউজারের আলাদা ফাইল (FeedCache-এর নিয়মে)।
 */
final class SettingsCache {
    private SettingsCache() { }

    private static Context app;

    static void init(Context c) { app = c.getApplicationContext(); }

    // ---- যেসব পাথ পেজগুলো ব্যবহার করে (ওয়ার্ম-আপ ও পেজ — দুই জায়গায় একই স্ট্রিং লাগবে) ----
    static String notifPath(String me) {
        return "/rest/v1/notification_settings?user_id=eq." + me + "&select=prefs";
    }
    static String reactionPath(String me) {
        return "/rest/v1/reaction_settings?user_id=eq." + me
                + "&select=hide_others,hide_yours,sound_haha,sound_check,sound_wow";
    }
    static final String BLOCKED_PATH =
            "/rest/v1/blocked_users?select=blocked_id,blocked_name,blocked_photo_url&order=created_at.desc";
    static final String AUTH_USER_PATH = "/auth/v1/user";
    static String lockPath(String me) {
        return "/rest/v1/profiles?id=eq." + me + "&select=is_locked,locked_at,avatar_key";
    }
    static String badgePath(String me) {
        return "/rest/v1/profiles?id=eq." + me + "&select=verified_badge";
    }
    static String badgeCheckPath(String me) {
        return "/rest/v1/profiles?id=eq." + me + "&select=nid_verified,joined_at";
    }

    // ---- ফাইল ----
    private static String name(String path) {
        try {
            byte[] b = MessageDigest.getInstance("SHA-1").digest(path.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder("sc_");
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", b[i]));
            return sb.toString();
        } catch (Exception e) {
            return "sc_" + Integer.toHexString(path.hashCode());
        }
    }

    /** সেভ করা কপি; না থাকলে null */
    static String cached(String path) {
        if (app == null) return null;
        return FeedCache.get(app, SupabaseRest.uid(), name(path));
    }

    /** GET + ডিস্ক ক্যাশ। callback main thread-এ। */
    static void get(final String path, final SupabaseRest.Done done) {
        final String uid = SupabaseRest.uid();
        SupabaseRest.call("GET", path, null, null, true, r -> {
            if (r.ok() && r.body != null) {
                if (app != null && uid != null) FeedCache.put(app, uid, name(path), r.body);
                if (done != null) done.run(r);
                return;
            }
            // শুধু "নেট নেই / সার্ভার ডাউন" হলে কপি দিই; ৪০১/৪০৩ ইত্যাদিতে নয় (সেশন/অনুমতির আসল সমস্যা চাপা পড়বে)
            boolean netProblem = r.code == -1 || r.code >= 500 || !Net.isOnline();
            if (netProblem && app != null && uid != null) {
                String c = FeedCache.get(app, uid, name(path));
                if (c != null) {
                    if (done != null) done.run(new SupabaseRest.Resp(200, c));
                    return;
                }
            }
            if (done != null) done.run(r);
        });
    }

    // ---- ওয়ার্ম-আপ: নেট থাকলে সব Settings-ডাটা আগেই ফোনে নামিয়ে রাখে ----
    private static final String PREF = "fb_settings_cache";
    private static final long WARM_EVERY_MS = 6L * 3600 * 1000;
    private static volatile boolean warming;

    private static SharedPreferences sp() {
        return app.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** এই ইউজারের Settings-ডাটা অন্তত একবার ফোনে জমেছে কি */
    static boolean isWarm() {
        String me = SupabaseRest.uid();
        return app != null && me != null && sp().getLong("warm_" + me, 0L) > 0L;
    }

    /** অনলাইনে থাকলে ডাকুন (অ্যাপ চালু / নেট ফেরা / Settings খোলা)। ৬ ঘণ্টার মধ্যে আবার হলে বাদ। */
    static void warmUp() {
        final String me = SupabaseRest.uid();
        if (app == null || me == null || !Net.isOnline() || !SupabaseRest.isLoggedIn()) return;
        if (warming) return;
        long last = sp().getLong("warm_" + me, 0L);
        if (last > 0L && System.currentTimeMillis() - last < WARM_EVERY_MS) return;
        warming = true;

        final int[] left = {7};
        final Runnable one = () -> {
            if (--left[0] == 0) {
                warming = false;
                sp().edit().putLong("warm_" + me, System.currentTimeMillis()).apply();
            }
        };
        final SupabaseRest.Done tick = r -> one.run();

        Db.getMine("profiles", (row, err) -> one.run());      // প্রোফাইল-রো → FbCache (Account/Profile details)
        get(notifPath(me), tick);
        get(reactionPath(me), tick);
        get(BLOCKED_PATH, tick);
        get(AUTH_USER_PATH, tick);
        get(lockPath(me), tick);
        get(badgeCheckPath(me), tick);
        MediaPrefs.refresh(app, null);                        // মিডিয়া সেটিং নিজের SharedPreferences-এ জমে
        NotificationPrefs.refresh(null);
    }

    /** লগআউটে বা ইউজার বদলালে মুছতে চাইলে (ঐচ্ছিক) */
    static void forgetWarm(String uid) {
        if (app != null && uid != null) sp().edit().remove("warm_" + uid).apply();
    }
}
