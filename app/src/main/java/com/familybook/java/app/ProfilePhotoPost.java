package com.familybook.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * প্রোফাইল ছবি / কভার ফটো বদলালে:
 *  ১) নিউজ ফিড ও নিজের প্রোফাইলে একটি পোস্ট ("X updated his profile picture") তৈরি হয়,
 *  ২) ফলোয়ার ও ফ্রেন্ডদের নোটিফিকেশন যায়।
 * পোস্টের হেডার-লাইন PostExtras-এ "@pp:" চিহ্ন দেখে বানানো হয়।
 */
final class ProfilePhotoPost {
    private ProfilePhotoPost() { }

    static final String MARK = "@pp:";
    private static final int MAX_NOTIFY = 300;

    /** target = "avatar" | "cover"; key = আপলোড হওয়া ছবির key; done = শেষে (UI থ্রেডে) চলবে */
    static void announce(final String target, final String key, final Runnable done) {
        final String me = SupabaseRest.uid();
        if (me == null || key == null || key.isEmpty()) return;
        SupabaseRest.call("GET", "/rest/v1/profiles?id=eq." + me + "&select=name,info", null, null, true, r -> {
            String name = "", gender = "";
            if (r.ok()) {
                try {
                    JSONArray a = new JSONArray(r.body);
                    if (a.length() > 0) {
                        JSONObject o = a.getJSONObject(0);
                        name = o.isNull("name") ? "" : o.optString("name", "");
                        JSONObject info = o.optJSONObject("info");
                        if (info != null) gender = info.optString("gender", "");
                    }
                } catch (Exception ignored) { }
            }
            String g = gender.trim().toLowerCase();
            final String pron = g.equals("female") ? "her" : g.equals("male") ? "his" : "their";
            final boolean avatar = "avatar".equals(target);
            final String phrase = "updated " + pron + (avatar ? " profile picture" : " cover photo");
            try {
                JSONObject body = new JSONObject()
                        .put("user_id", me)
                        .put("text", "")
                        .put("privacy", "public")
                        .put("image_key", key)
                        .put("activity", MARK + phrase);
                if (!name.isEmpty()) body.put("author_name", name);
                SupabaseRest.call("POST", "/rest/v1/posts", body.toString(), "return=representation", true, pr -> {
                    String newId = null;
                    if (pr.ok()) {
                        try {
                            JSONArray arr = new JSONArray(pr.body);
                            if (arr.length() > 0) newId = arr.getJSONObject(0).optString("id", null);
                        } catch (Exception ignored) { }
                    }
                    if (newId != null) PostImage.put(newId, key, null);
                    notifyAudience(me, (avatar ? "profile_photo_" : "cover_photo_") + pron, newId);
                    if (done != null) done.run();
                });
            } catch (Exception e) {
                if (done != null) done.run();
            }
        });
    }

    /** ফলোয়ার + ফ্রেন্ড সবাইকে (একজন একবারই) */
    private static void notifyAudience(final String me, final String type, final String postId) {
        final Set<String> ids = new LinkedHashSet<>();
        SupabaseRest.call("GET", "/rest/v1/follows?followee_id=eq." + me + "&select=follower_id&limit=1000",
                null, null, true, r1 -> {
            collect(r1, "follower_id", ids);
            SupabaseRest.call("GET", "/rest/v1/friendships?user_id=eq." + me + "&select=friend_id&limit=1000",
                    null, null, true, r2 -> {
                collect(r2, "friend_id", ids);
                int n = 0;
                for (String uid : ids) {
                    if (n++ >= MAX_NOTIFY) break;
                    NotificationHelper.send(uid, type, postId);
                }
            });
        });
    }

    private static void collect(SupabaseRest.Resp r, String col, Set<String> out) {
        if (r == null || !r.ok()) return;
        try {
            JSONArray a = new JSONArray(r.body);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                String v = o == null ? "" : o.optString(col, "");
                if (!v.isEmpty()) out.add(v);
            }
        } catch (Exception ignored) { }
    }
}
