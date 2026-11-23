package com.familybook.app;

import android.content.Context;

import org.json.JSONObject;

/**
 * Facebook-এর মতো ফোনে রাখা কপি: প্রোফাইলের নাম ও প্রোফাইল-রো একবার লোড হলেই ফাইলে জমে,
 * ইন্টারনেট না থাকলে বা স্লো হলে সেটাই দেখায় (নাম "Family Member" হয়ে যায় না)।
 */
final class FbCache {
    private FbCache() { }

    private static Context app;

    static void init(Context c) { app = c.getApplicationContext(); }

    static void putName(String uid, String name) {
        if (app == null || uid == null || name == null || name.trim().isEmpty()) return;
        FeedCache.put(app, uid, "pname", name);
    }

    /** না থাকলে null */
    static String getName(String uid) {
        if (app == null || uid == null) return null;
        String n = FeedCache.get(app, uid, "pname");
        return (n == null || n.trim().isEmpty()) ? null : n;
    }

    static void putProfile(String uid, JSONObject row) {
        if (app == null || uid == null || row == null) return;
        FeedCache.put(app, uid, "profile", row.toString());
        String n = row.optString("name", "").trim();
        if (!n.isEmpty()) putName(uid, n);
    }

    /** না থাকলে null */
    static JSONObject getProfile(String uid) {
        if (app == null || uid == null) return null;
        String s = FeedCache.get(app, uid, "profile");
        if (s == null) return null;
        try { return new JSONObject(s); } catch (Exception e) { return null; }
    }
}
