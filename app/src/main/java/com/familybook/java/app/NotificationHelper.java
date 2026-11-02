package com.familybook.app;

import org.json.JSONArray;
import org.json.JSONObject;

public class NotificationHelper {

    /** রিঅ্যাকশনের ধরন (notifications.type-এ এই মানই বসে) */
    static final String[] REACTIONS = {"like", "love", "haha", "angry", "wow", "check", "stop"};

    public static boolean isReaction(String t) {
        if (t == null) return false;
        for (String r : REACTIONS) if (r.equals(t)) return true;
        return false;
    }

    private static String reactionList() {
        StringBuilder b = new StringBuilder();
        for (String r : REACTIONS) { if (b.length() > 0) b.append(','); b.append(r); }
        return b.toString();
    }

    private interface KindCb { void on(String kind); }

    /**
     * NotificationHelper.send(receiverUid, "like", postId);
     * পোস্ট মালিক ওই পোস্টের নোটিফিকেশন মিউট করে থাকলে যাবে না।
     * রিঅ্যাকশন (like/love/haha/angry/wow/check/stop) ও follow: একই ব্যক্তির জন্য একটাই নোটিফিকেশন (আগেরটা মুছে নতুন বসে)।
     * রিঅ্যাকশন ও কমেন্টে message কলামে মিডিয়ার ধরন যায় (photo / video / gif / post)।
     */
    public static void send(final String toUid, final String type, final String postId) {
        final String me = SupabaseRest.uid();
        if (me == null || toUid == null || toUid.equals(me)) return;

        if (postId == null) {
            write(me, toUid, type, null, null);
            return;
        }
        String body = "{\"pid\":\"" + postId + "\",\"uid\":\"" + toUid + "\"}";
        SupabaseRest.call("POST", "/rest/v1/rpc/post_muted_for", body, null, true, r -> {
            boolean muted = r.ok() && "true".equals(r.body.trim());
            if (muted) return;
            if (isReaction(type) || "comment".equals(type)) {
                mediaKind(postId, kind -> write(me, toUid, type, postId, kind));
            } else {
                write(me, toUid, type, postId, null);
            }
        });
    }

    /** পোস্টটা ফটো / ভিডিও (Short) / GIF / সাধারণ পোস্ট — কোনটা */
    private static void mediaKind(String postId, final KindCb cb) {
        SupabaseRest.call("GET", "/rest/v1/posts?id=eq." + postId + "&select=image_key,activity",
                null, null, true, r -> {
            String kind = "post";
            if (r.ok()) {
                try {
                    JSONArray a = new JSONArray(r.body);
                    if (a.length() > 0) {
                        JSONObject o = a.getJSONObject(0);
                        String act = o.isNull("activity") ? "" : o.optString("activity", "");
                        String key = o.isNull("image_key") ? "" : o.optString("image_key", "").trim();
                        if (act.contains(ShortUploader.SHORT_PHRASE)) {
                            kind = "video";
                        } else if (!key.isEmpty()) {
                            boolean single = key.indexOf(',') < 0;
                            boolean gif = single && (Media.isGifKey(key) || key.toLowerCase().endsWith(".gif"));
                            kind = gif ? "gif" : "photo";
                        }
                    }
                } catch (Exception ignored) { }
            }
            cb.on(kind);
        });
    }

    private static void write(final String me, final String toUid, final String type,
                              final String postId, final String kind) {
        boolean unique = isReaction(type) || "follow".equals(type);
        if (!unique) {
            insert(me, toUid, type, postId, kind);
            return;
        }
        String path = "/rest/v1/notifications?user_id=eq." + toUid + "&actor_id=eq." + me;
        path += isReaction(type) ? "&type=in.(" + reactionList() + ")" : "&type=eq." + type;
        if (postId != null) path += "&post_id=eq." + postId;

        SupabaseRest.call("DELETE", path, null, null, true, r -> insert(me, toUid, type, postId, kind));
    }

    private static void insert(String me, String toUid, String type, String postId, String kind) {
        try {
            JSONObject row = new JSONObject()
                    .put("user_id", toUid)
                    .put("actor_id", me)
                    .put("type", type)
                    .put("is_read", false);
            if (postId != null) row.put("post_id", postId);
            if (kind != null) row.put("message", kind);
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
