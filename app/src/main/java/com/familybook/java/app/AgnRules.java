package com.familybook.app;

import java.util.regex.Pattern;

/** Username + AGN (auto generated number) এবং Profile Name পরিবর্তনের নিয়ম। */
final class AgnRules {
    private AgnRules() { }

    static final int MAX_AGN_USERS = 1000;
    static final Pattern USERNAME_AGN  = Pattern.compile("[a-z]{4,8}");        // ১ম–১০০০ নং user
    static final Pattern USERNAME_FREE = Pattern.compile("[a-z0-9._]{3,20}");  // ১০০১ নং থেকে

    /**
     * n = agn পাওয়া user-এর ক্রম (১ থেকে)।
     * n=1..3  -> 157,158,159
     * n>=4    -> ১০টি করে ব্লক: 260-269, 370-379, 480-489, 590-599, 600-609, 710-719, 820-829 ...
     *            সূত্র: (2+k)*100 + ((6+k)%10)*10 + u   (k = ব্লক নং, u = 0..9)
     * SQL-এর agn_for() ফাংশনের সাথে হুবহু এক।
     */
    static int agnFor(int n) {
        if (n <= 3) return 156 + n;
        int m = n - 4, k = m / 10, u = m % 10;
        return (2 + k) * 100 + ((6 + k) % 10) * 10 + u;
    }

    static String handle(String username, int agn) {
        return agn > 0 ? username + agn : username;
    }

    // ---------- Profile Name পরিবর্তনের নিয়ম ----------
    // ১ম পরিবর্তন সরাসরি; ১ম-এর ৩০ দিন, ২য়-এর ৬০ দিন, ৩য়-এর ৯০ দিন পর আবার।
    // ৩য় পরিবর্তনের ৯০ দিন পেরোলে ৪র্থ বার নয়: বক্স লক, অ্যাডমিনকে অনুরোধ।
    static final int MAX_NAME_EDITS = 3;
    private static final int[] WAIT_DAYS = {0, 30, 60, 90};
    private static final long DAY = 24L * 60 * 60 * 1000;

    /** null = এখন পরিবর্তন করা যাবে; "LOCKED" = চিরতরে (আপাতত) লক; নাহলে অপেক্ষার বার্তা */
    static String nameEditBlock(int done, long lastEditMs, long now) {
        if (done <= 0) return null;
        long until = lastEditMs + WAIT_DAYS[Math.min(done, MAX_NAME_EDITS)] * DAY;
        if (now < until) {
            long days = (until - now + DAY - 1) / DAY;
            return "নাম পরিবর্তন করতে আরও " + days + " দিন অপেক্ষা করুন";
        }
        return done >= MAX_NAME_EDITS ? "LOCKED" : null;   // ৯০ দিন শেষ, ৪র্থ বার নয়: অ্যাডমিনকে অনুরোধ
    }

    static boolean isNameLocked(int done, long lastEditMs, long now) {
        return "LOCKED".equals(nameEditBlock(done, lastEditMs, now));
    }
}
