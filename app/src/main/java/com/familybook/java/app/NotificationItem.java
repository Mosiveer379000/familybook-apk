package com.familybook.app;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;

/** notifications টেবিলের একটি সারি। */
public class NotificationItem {
    public String id;
    public String actorUid;   // যে কাজ করেছে
    public String type;       // like, comment, follow ...
    public long timestamp;    // millis
    public boolean read;
    public String postId;
    public String message;
    public String groupId;    // গ্রুপ সংক্রান্ত নোটিফিকেশনে (group_invite ইত্যাদি)

    public static NotificationItem fromJson(JSONObject o) {
        NotificationItem n = new NotificationItem();
        n.id = o.optString("id", null);
        n.actorUid = o.optString("actor_id", null);
        n.type = o.optString("type", null);
        n.timestamp = parseIso(o.optString("created_at", ""));
        n.read = o.optBoolean("is_read", false);
        n.postId = o.isNull("post_id") ? null : o.optString("post_id", null);
        n.message = o.isNull("message") ? null : o.optString("message", null);
        n.groupId = o.isNull("group_id") ? null : o.optString("group_id", null);
        return n;
    }

    /** "2026-10-12T19:50:00.123+00:00" → millis (UTC) */
    static long parseIso(String s) {
        try {
            String base = s.length() >= 19 ? s.substring(0, 19) : s;
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            return f.parse(base).getTime();
        } catch (Exception e) {
            return 0;
        }
    }
}