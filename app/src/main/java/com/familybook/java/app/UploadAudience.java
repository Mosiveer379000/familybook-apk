package com.familybook.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * ভিডিও / ফটো / প্রোফাইল ছবি / কভার আপলোডের নোটিফিকেশন কাদের কাছে যাবে:
 * শুধু আমার followers এবং followers-of-followers। নিজের কাছে কখনো যায় না।
 */
final class UploadAudience {
    private UploadAudience() { }

    private static final int MAX_NOTIFY = 300;
    private static final int MAX_FIRST_HOP = 150;

    static void notify(final String type, final String postId) {
        final String me = SupabaseRest.uid();
        if (me == null || type == null) return;
        final Set<String> first = new LinkedHashSet<>();
        SupabaseRest.call("GET", "/rest/v1/follows?followee_id=eq." + me
                + "&select=follower_id&limit=" + MAX_FIRST_HOP, null, null, true, r1 -> {
            collect(r1, "follower_id", first);
            first.remove(me);
            if (first.isEmpty()) return;
            StringBuilder in = new StringBuilder();
            for (String f : first) { if (in.length() > 0) in.append(','); in.append(f); }
            SupabaseRest.call("GET", "/rest/v1/follows?followee_id=in.(" + in
                    + ")&select=follower_id&limit=" + (MAX_NOTIFY * 4), null, null, true, r2 -> {
                Set<String> all = new LinkedHashSet<>(first);
                collect(r2, "follower_id", all);
                all.remove(me);
                int n = 0;
                for (String uid : all) {
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
