package com.familybook.app;

import java.util.regex.Pattern;

/**
 * AGU (Auto Generated Username) number + Username এবং Profile Name পরিবর্তনের নিয়ম।
 *
 * AGU ক্রম (এক একজন নতুন ইউজার পরপর এক একটি নম্বর পায়):
 *   157, 158, 159
 *   260 - 269
 *   370 - 378          (379 বাদ: ওটা শুধু Admin/Founder-এর জন্য fixed)
 *   480 - 489
 *   590 - 609
 *   700 - 709, 800 - 809, 900 - 909
 *   1000
 *   এরপর 1001, 1002, 1003 ... একটার পর একটা (আর কোনো ফাঁক নেই)
 *
 * Admin/Founder (masudd16@gmail.com) = 379, শুধু তার জন্য fixed; সাধারণ ইউজারের ক্রমে গোনা হয় না।
 * 1001 নম্বর থেকে ইউজার নিজের Username সম্পাদনা করে বদলাতে পারে (USERNAME_FREE)।
 * SQL-এর agn_for(n) ফাংশনের সাথে হুবহু এক (supabase_agu_rules.sql)।
 */
final class AgnRules {
    private AgnRules() { }

    static final String ADMIN_EMAIL = "masudd16@gmail.com";
    static final int ADMIN_AGN = 379;

    /** এই নম্বর পর্যন্ত AGU নির্দিষ্ট ছক মেনে চলে; 1001 থেকে সরাসরি ক্রমিক ও Username সম্পাদনযোগ্য */
    static final int LAST_FIXED_AGN = 1000;
    static final int FIRST_FREE_AGN = 1001;

    /** ছকের ব্লকগুলো: {শুরু, শেষ} (শেষসহ)। 379 ইচ্ছা করেই নেই। */
    private static final int[][] BLOCKS = {
            {157, 159}, {260, 269}, {370, 378}, {480, 489}, {590, 609},
            {700, 709}, {800, 809}, {900, 909}, {1000, 1000}
    };

    /** ছকে মোট কতজন (ছক শেষ হতে) : 3+10+9+10+20+10+10+10+1 = 83 */
    static final int FIXED_USERS = countFixed();

    private static int countFixed() {
        int c = 0;
        for (int[] b : BLOCKS) c += b[1] - b[0] + 1;
        return c;
    }

    static final Pattern USERNAME_AGN  = Pattern.compile("[a-z]{4,8}");        // ছকের (157..1000) ইউজার
    static final Pattern USERNAME_FREE = Pattern.compile("[a-z0-9._]{3,20}");  // 1001 নম্বর থেকে

    /**
     * n = AGU পাওয়া সাধারণ ইউজারের ক্রম (১ থেকে; Admin বাদ)।
     * n=1 -> 157, n=4 -> 260, n=14 -> 370, n=83 -> 1000, n=84 -> 1001, n=85 -> 1002 ...
     */
    static int agnFor(int n) {
        if (n < 1) return 0;
        int left = n;
        for (int[] b : BLOCKS) {
            int len = b[1] - b[0] + 1;
            if (left <= len) return b[0] + left - 1;
            left -= len;
        }
        return LAST_FIXED_AGN + left;   // left=1 -> 1001
    }

    /** Admin-এর email হলে 379, নাহলে n-তম ইউজারের নম্বর */
    static int agnForUser(String email, int n) {
        if (email != null && ADMIN_EMAIL.equalsIgnoreCase(email.trim())) return ADMIN_AGN;
        return agnFor(n);
    }

    /** 1001 বা তার পরের নম্বর পেলে ইউজার নিজের Username সম্পাদনা করতে পারে */
    static boolean usernameEditable(int agn) {
        return agn >= FIRST_FREE_AGN;
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

    /** null = এখন পরিবর্তন করা যাবে; "LOCKED" = লক; নাহলে অপেক্ষার বার্তা (ইংরেজি, UI-তে দেখানো হয়) */
    static String nameEditBlock(int done, long lastEditMs, long now) {
        if (done <= 0) return null;
        long until = lastEditMs + WAIT_DAYS[Math.min(done, MAX_NAME_EDITS)] * DAY;
        if (now < until) {
            long days = (until - now + DAY - 1) / DAY;
            return "Wait " + days + " more day" + (days == 1 ? "" : "s") + " to change your name";
        }
        return done >= MAX_NAME_EDITS ? "LOCKED" : null;
    }

    static boolean isNameLocked(int done, long lastEditMs, long now) {
        return "LOCKED".equals(nameEditBlock(done, lastEditMs, now));
    }
}
