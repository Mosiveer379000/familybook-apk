package com.familybook.app;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * নোটিফিকেশন সেটিং (Supabase: notification_settings.prefs)।
 * prefs = { "comments": {"Push":true,"In-app":false,...}, "tags": {...}, ... }
 * কোনো ক্যাটাগরির Push ও In-app দুটোই বন্ধ থাকলে (HTML-এ "Off") সেই ধরনের নোটিফিকেশন ব্লক।
 * নতুন নোটিফিকেশন ব্লক করে Supabase trigger; আগে জমা থাকা গুলো লিস্টে লুকায় isBlocked()।
 * category() অবশ্যই SQL-এর notif_category() এর সাথে মিলে থাকতে হবে।
 */
public final class NotificationPrefs {

    public interface Json { void on(String json); }

    private static JSONObject prefs = new JSONObject();

    private NotificationPrefs() { }

    /** notifications.type -> HTML-এর ক্যাটাগরি id */
    public static String category(String type) {
        String t = type == null ? "" : type.toLowerCase();
        if (NotificationHelper.isReaction(t) || t.contains("like") || t.contains("comment")
                || t.contains("mention")) return "comments";
        if (t.equals("tag_request") || t.equals("tag_accepted")
                || t.equals("collab_request") || t.equals("collab_accepted")) return "tags";
        if (t.contains("follow")) return "friendRequests";
        if (t.startsWith("group")) return "groups";
        if (t.equals("short_new")) return "reels";
        if (t.contains("share") || t.equals("photo_new")
                || t.startsWith("profile_photo_") || t.startsWith("cover_photo_")) return "updates";
        return "other";
    }

    public static synchronized boolean isBlocked(String type) {
        // Community Standards ওয়ার্নিং সিস্টেম-নোটিফিকেশন: ইউজার Settings-এ বন্ধ করলেও লুকাবে না
        if (type != null && type.equalsIgnoreCase("warning")) return false;
        JSONObject c = prefs.optJSONObject(category(type));
        if (c == null) return false;
        return !c.optBoolean("Push", true) && !c.optBoolean("In-app", true);
    }

    /** সার্ভার থেকে আনে; সফল/ব্যর্থ যাই হোক শেষে done চলে (main thread) */
    public static void refresh(final Runnable done) {
        final String me = SupabaseRest.uid();
        if (me == null) { if (done != null) done.run(); return; }
        SupabaseRest.call("GET", "/rest/v1/notification_settings?user_id=eq." + me + "&select=prefs",
                null, null, true, r -> {
            if (r.ok()) {
                try {
                    JSONArray a = new JSONArray(r.body);
                    JSONObject p = a.length() > 0 ? a.getJSONObject(0).optJSONObject("prefs") : null;
                    synchronized (NotificationPrefs.class) { prefs = p == null ? new JSONObject() : p; }
                } catch (Exception ignored) { }
            }
            if (done != null) done.run();
        });
    }

    /** HTML পেজের জন্য: বর্তমান prefs JSON স্ট্রিং */
    public static void fetch(final Json cb) {
        refresh(() -> {
            String s;
            synchronized (NotificationPrefs.class) { s = prefs.toString(); }
            cb.on(s);
        });
    }

    /** HTML থেকে আসা পুরো prefs সেভ (upsert) */
    public static void save(String json) {
        final String me = SupabaseRest.uid();
        if (me == null || json == null) return;
        try {
            JSONObject p = new JSONObject(json);
            synchronized (NotificationPrefs.class) { prefs = p; }
            JSONObject row = new JSONObject().put("user_id", me).put("prefs", p);
            SupabaseRest.call("POST", "/rest/v1/notification_settings?on_conflict=user_id",
                    row.toString(), "resolution=merge-duplicates,return=minimal", true, r -> { });
        } catch (Exception ignored) { }
    }
}
