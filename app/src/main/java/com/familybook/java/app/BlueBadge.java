package com.familybook.app;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;

/** Blue badge সহায়ক: কে বিনাশর্তে পাবে + profiles.verified_badge সেভ। */
final class BlueBadge {
    private BlueBadge() { }

    // বিনাশর্তে ব্লু ব্যাজ পাবে (বড়/ছোট হাতের অক্ষর ধর্তব্য নয়)
    private static final String[] FREE_EMAILS = {"masudd16@gmail.com"};

    // নিজের অ্যাকাউন্টের সর্বশেষ জানা অবস্থা (null = জানা নেই)
    private static volatile Boolean myActive;

    static boolean isAdmin() {
        String me = SupabaseRest.email();
        if (me == null || !SupabaseRest.isLoggedIn()) return false;
        me = me.trim();
        for (String a : FREE_EMAILS) if (a.equalsIgnoreCase(me)) return true;
        return false;
    }

    static void remember(String uid, boolean on) {
        if (uid != null && uid.equals(SupabaseRest.uid())) myActive = on;
    }

    /** নিজের প্রোফাইল/পোস্টে ব্যাজ দেখাবে কি? অ্যাডমিন হলে ডিফল্ট হ্যাঁ, Deactivated করলে না। */
    static boolean showForMe() {
        if (!isAdmin()) return false;
        return myActive == null || myActive;
    }

    static void save(String uid, boolean on) {
        if (uid == null) return;
        try {
            Db.patch("profiles", "id=eq." + uid, new JSONObject().put("verified_badge", on), null);
        } catch (Exception ignored) { }
    }

    static long isoToMs(String iso) {
        if (iso == null || iso.length() < 19) return 0L;
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            return f.parse(iso.substring(0, 19)).getTime();
        } catch (Exception e) {
            return 0L;
        }
    }
}
