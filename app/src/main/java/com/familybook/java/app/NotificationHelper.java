package com.familybook.app;

import org.json.JSONObject;

public class NotificationHelper {

    /**
     * NotificationHelper.send(receiverUid, "like", postId);
     * পোস্ট মালিক ওই পোস্টের নোটিফিকেশন মিউট করে থাকলে যাবে না।
     * like ও follow: একই ব্যক্তির জন্য একটাই নোটিফিকেশন (আগেরটা মুছে নতুন বসে)।
     */
    public static void send(final String toUid, final String type, final String postId) {
        final String me = SupabaseRest.uid();
        if (me == null || toUid == null || toUid.equals(me)) return;

        if (postId == null) {
            write(me, toUid, type, null);
            return;
        }
        String body = "{\"pid\":\"" + postId + "\",\"uid\":\"" + toUid + "\"}";
        SupabaseRest.call("POST", "/rest/v1/rpc/post_muted_for", body, null, true, r -> {
            boolean muted = r.ok() && "true".equals(r.body.trim());
            if (!muted) write(me, toUid, type, postId);
        });
    }

    private static void write(final String me, final String toUid, final String type, final String postId) {
        boolean unique = "like".equals(type) || "follow".equals(type);
        if (!unique) {
            insert(me, toUid, type, postId);
            return;
        }
        String path = "/rest/v1/notifications?user_id=eq." + toUid
                + "&actor_id=eq." + me + "&type=eq." + type;
        if (postId != null) path += "&post_id=eq." + postId;

        SupabaseRest.call("DELETE", path, null, null, true, r -> insert(me, toUid, type, postId));
    }

    private static void insert(String me, String toUid, String type, String postId) {
        try {
            JSONObject row = new JSONObject()
                    .put("user_id", toUid)
                    .put("actor_id", me)
                    .put("type", type)
                    .put("is_read", false);
            if (postId != null) row.put("post_id", postId);
            SupabaseRest.call("POST", "/rest/v1/notifications", row.toString(),
                    "return=minimal", true, r -> { });
        } catch (Exception ignored) { }
    }

    /**
     * গ্রুপ নোটিফিকেশন: group_invite / group_join_request / group_join_approved।
     * message সরাসরি নোটিফিকেশনে দেখায় (যেমন: X invited you to join their group "Name")।
     */
    public static void sendGroup(String toUid, String type, String groupId, String message) {
        String me = SupabaseRest.uid();
        if (me == null || toUid == null || toUid.isEmpty() || toUid.equals(me)) return;
        try {
            JSONObject row = new JSONObject()
                    .put("user_id", toUid)
                    .put("actor_id", me)
                    .put("type", type)
                    .put("group_id", groupId)
                    .put("message", message)
                    .put("is_read", false);
            SupabaseRest.call("POST", "/rest/v1/notifications", row.toString(),
                    "return=minimal", true, r -> { });
        } catch (Exception ignored) { }
    }

    /** একটি নোটিফিকেশন পড়া হয়েছে */
    public static void markRead(String notifId) {
        if (SupabaseRest.uid() == null || notifId == null) return;
        SupabaseRest.call("PATCH", "/rest/v1/notifications?id=eq." + notifId,
                "{\"is_read\":true}", "return=minimal", true, r -> { });
    }

    /** আমার সব নোটিফিকেশন পড়া হয়েছে (একটাই request) */
    public static void markAllRead() {
        String me = SupabaseRest.uid();
        if (me == null) return;
        SupabaseRest.call("PATCH", "/rest/v1/notifications?user_id=eq." + me + "&is_read=eq.false",
                "{\"is_read\":true}", "return=minimal", true, r -> { });
    }
}