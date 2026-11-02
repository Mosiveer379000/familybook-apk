package com.familybook.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * পোস্টে "AI label" দেওয়া আছে কিনা। post_cards ভিউ না বদলিয়েই কাজ করে:
 * ফিডের পোস্টগুলোর id দিয়ে posts টেবিল থেকে ai_label=true এগুলো একসাথে এনে ক্যাশে রাখে।
 * (posts.ai_label কলাম না থাকলে — অর্থাৎ supabase_post_settings.sql চালানো না হলে — চুপচাপ বন্ধ থাকে।)
 */
public final class PostAi {

    private static final Map<String, Boolean> CACHE = new ConcurrentHashMap<>();
    private static final Set<String> INFLIGHT = new HashSet<>();
    private static final int CHUNK = 60;
    private static volatile boolean unavailable = false;   // কলাম নেই → আর চেষ্টা নয়

    private PostAi() { }

    public static boolean has(String postId) {
        Boolean b = postId == null ? null : CACHE.get(postId);
        return b != null && b;
    }

    /** নতুন পোস্টের জন্য নিজের ফোনে সাথে সাথে দেখাতে */
    public static void put(String postId, boolean on) {
        if (postId != null) CACHE.put(postId, on);
    }

    /** তালিকার যেসব পোস্টের তথ্য নেই সেগুলো এনে শেষে onReady ডাকে (মেইন থ্রেডে) */
    public static void prefetch(List<Post> list, final Runnable onReady) {
        if (unavailable) return;
        final List<String> need = new ArrayList<>();
        synchronized (INFLIGHT) {
            for (Post p : list) {
                if (p.id != null && !CACHE.containsKey(p.id) && INFLIGHT.add(p.id)) need.add(p.id);
            }
        }
        for (int from = 0; from < need.size(); from += CHUNK) {
            final List<String> ids = new ArrayList<>(need.subList(from, Math.min(need.size(), from + CHUNK)));
            StringBuilder in = new StringBuilder();
            for (String id : ids) { if (in.length() > 0) in.append(','); in.append(id); }
            SupabaseRest.call("GET",
                    "/rest/v1/posts?id=in.(" + in + ")&ai_label=eq.true&select=id",
                    null, null, true, r -> {
                synchronized (INFLIGHT) { INFLIGHT.removeAll(ids); }
                if (!r.ok()) {
                    if (r.code == 400) unavailable = true;   // কলাম নেই
                    return;                                  // ক্যাশ করি না — পরের বার আবার চেষ্টা
                }
                Set<String> on = new HashSet<>();
                try {
                    JSONArray rows = new JSONArray(r.body);
                    for (int i = 0; i < rows.length(); i++) {
                        JSONObject o = rows.getJSONObject(i);
                        if (!o.isNull("id")) on.add(o.optString("id"));
                    }
                } catch (Exception ignored) { }
                for (String id : ids) CACHE.put(id, on.contains(id));
                if (!on.isEmpty() && onReady != null) onReady.run();
            });
        }
    }
}
