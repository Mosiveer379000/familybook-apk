package com.familybook.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * "আজ যাদের জন্মদিন" — যারা আমাকে ফলো করে (আগে) + আমি যাদের ফলো করি (Supabase RPC birthdays_today, sql/12_birthday_wishes.sql)।
 *
 * ব্যবহার:
 *  - NotificationActivity: "Today is X's birthday" সারি (ডাটাবেসে নোটিফিকেশন সারি বানানো হয় না — প্রতিদিন নিজে তৈরি হয়)
 *  - BirthdayWorker: ফোনের সিস্টেম নোটিফিকেশন (দিনে একবার)
 *  - BirthdayReminderActivity: birthday_reminder.html-এর "Today" তালিকা
 *
 * তারিখ ফোনের লোকাল সময়ে ধরা হয়।
 */
final class BirthdayToday {

    static final class Person {
        String uid, name, avatarKey;
        String mode = "wish";       // wish = টাইমলাইনে লেখার বক্স; message = "Message" বাটন (তার "Wishes from followers" বন্ধ, বা আমি তাকে ফলো করি না)
        boolean followsMe;          // সে আমাকে ফলো করে
        boolean wished;             // এ বছর আমি আগেই লিখেছি
    }

    interface Cb { void on(List<Person> list); }

    private static final String SP = "fb_bday_today";
    private static final long FRESH_MS = 10L * 60 * 1000;

    private static List<Person> cache = new ArrayList<>();
    private static String cacheDay = "";
    private static long cacheAt = 0;

    private BirthdayToday() { }

    static String dayKey() {
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.YEAR) + "-" + (c.get(Calendar.MONTH) + 1) + "-" + c.get(Calendar.DAY_OF_MONTH);
    }

    /** সর্বশেষ আনা তালিকা (আজকের হলে); নেট ছাড়া সাথে সাথে দেখানোর জন্য */
    static List<Person> cached() {
        return dayKey().equals(cacheDay) ? cache : new ArrayList<Person>();
    }

    static void load(boolean force, final Cb cb) {
        if (SupabaseRest.uid() == null) { cb.on(new ArrayList<Person>()); return; }
        final String key = dayKey();
        if (!force && key.equals(cacheDay) && System.currentTimeMillis() - cacheAt < FRESH_MS) {
            cb.on(cache);
            return;
        }
        Calendar c = Calendar.getInstance();
        try {
            JSONObject body = new JSONObject()
                    .put("p_day", c.get(Calendar.DAY_OF_MONTH))
                    .put("p_month", c.get(Calendar.MONTH) + 1)
                    .put("p_year", c.get(Calendar.YEAR));
            SupabaseRest.call("POST", "/rest/v1/rpc/birthdays_today", body.toString(), null, true, r -> {
                if (!r.ok()) { cb.on(cached()); return; }
                List<Person> out = new ArrayList<>();
                try {
                    JSONArray a = new JSONArray(r.body);
                    for (int i = 0; i < a.length(); i++) {
                        JSONObject o = a.optJSONObject(i);
                        if (o == null) continue;
                        Person p = new Person();
                        p.uid = o.optString("id", null);
                        p.name = o.isNull("name") ? "" : o.optString("name", "");
                        p.avatarKey = o.isNull("avatar_key") ? null : o.optString("avatar_key", null);
                        p.mode = "message".equals(o.optString("mode")) ? "message" : "wish";
                        p.wished = o.optBoolean("wished", false);
                        p.followsMe = o.optBoolean("follows_me", false);
                        if (p.uid != null) out.add(p);
                    }
                } catch (Exception e) { cb.on(cached()); return; }
                cache = out;
                cacheDay = key;
                cacheAt = System.currentTimeMillis();
                cb.on(out);
            });
        } catch (Exception e) {
            cb.on(cached());
        }
    }

    /** আমি শুভেচ্ছা পাঠানোর পর ক্যাশেও "wished" বসিয়ে রাখি */
    static void markWished(String uid) {
        for (Person p : cache) if (p.uid.equals(uid)) p.wished = true;
    }

    // ---------- নোটিফিকেশন লিস্টের অবস্থা (আজ দেখা হয়েছে / মুছে ফেলা / সিস্টেম নোটিফিকেশন গেছে) ----------

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(SP, Context.MODE_PRIVATE);
    }

    static boolean isSeen(Context c) { return dayKey().equals(sp(c).getString("seen", "")); }
    static void markSeen(Context c) { sp(c).edit().putString("seen", dayKey()).apply(); }

    static boolean isDismissed(Context c) { return dayKey().equals(sp(c).getString("dismissed", "")); }
    static void dismiss(Context c) { sp(c).edit().putString("dismissed", dayKey()).apply(); }

    static boolean wasPosted(Context c) { return dayKey().equals(sp(c).getString("posted", "")); }
    static void markPosted(Context c) { sp(c).edit().putString("posted", dayKey()).apply(); }

    /** "Today is A's birthday" / "Today is A & B's birthday" / "Today is A & 30 others' birthday" */
    static String title(Context c, List<Person> list) {
        if (list == null || list.isEmpty()) return "";
        String a = nameOf(c, list.get(0));
        if (list.size() == 1) return c.getString(R.string.notif_bday_one, a);
        if (list.size() == 2) return c.getString(R.string.notif_bday_two, a, nameOf(c, list.get(1)));
        return c.getString(R.string.notif_bday_many, a, list.size() - 1);
    }

    private static String nameOf(Context c, Person p) {
        return p.name == null || p.name.isEmpty() ? c.getString(R.string.notif_someone) : p.name;
    }
}
