package com.familybook.app;

import android.content.Context;
import android.widget.ImageView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * পোস্টের গানের তথ্য। post_cards ভিউ না বদলিয়েই কাজ করে:
 * ফিডের পোস্টগুলোর id দিয়ে একসাথে RPC get_post_music ডেকে তথ্য এনে ক্যাশে রাখে।
 */
public class PostMusic {

    public static class Info {
        public String title, artist, cover, url;
        public int start, len;
    }

    private static final Info NONE = new Info();
    private static final Map<String, Info> CACHE = new ConcurrentHashMap<>();
    private static final Set<String> INFLIGHT = new HashSet<>();
    private static final int CHUNK = 60;

    public static Info get(String postId) {
        Info i = postId == null ? null : CACHE.get(postId);
        return (i == null || i == NONE) ? null : i;
    }

    /** নতুন পোস্টের গান নিজের ফোনে সাথে সাথে দেখাতে */
    public static void put(String postId, Info info) {
        if (postId != null && info != null) CACHE.put(postId, info);
    }

    /** পোস্ট এডিটে গান সরালে ক্যাশ থেকে বাদ (আইকন আর দেখাবে না) */
    public static void remove(String postId) {
        if (postId != null) CACHE.put(postId, NONE);
    }

    /** তালিকার যেসব পোস্টের তথ্য নেই সেগুলো এনে শেষে onReady ডাকে (মেইন থ্রেডে) */
    public static void prefetch(List<Post> list, final Runnable onReady) {
        final List<String> need = new ArrayList<>();
        synchronized (INFLIGHT) {
            for (Post p : list) {
                if (p.id != null && !CACHE.containsKey(p.id) && INFLIGHT.add(p.id)) need.add(p.id);
            }
        }
        for (int from = 0; from < need.size(); from += CHUNK) {
            final List<String> part = need.subList(from, Math.min(need.size(), from + CHUNK));
            final List<String> ids = new ArrayList<>(part);
            try {
                JSONArray arr = new JSONArray();
                for (String id : ids) arr.put(id);
                String body = new JSONObject().put("p_ids", arr).toString();
                SupabaseRest.call("POST", "/rest/v1/rpc/get_post_music", body, null, true, r -> {
                    synchronized (INFLIGHT) { INFLIGHT.removeAll(ids); }
                    if (!r.ok()) return;      // ব্যর্থ হলে ক্যাশ করি না — পরের বার আবার চেষ্টা
                    Set<String> found = new HashSet<>();
                    try {
                        JSONArray rows = new JSONArray(r.body);
                        for (int i = 0; i < rows.length(); i++) {
                            JSONObject o = rows.getJSONObject(i);
                            Info info = new Info();
                            info.title = str(o, "music_title");
                            info.artist = str(o, "music_artist");
                            info.cover = str(o, "music_cover");
                            info.url = str(o, "music_url");
                            info.start = o.optInt("music_start", 0);
                            info.len = o.optInt("music_len", 15000);
                            String id = str(o, "id");
                            if (id != null && info.url != null) {
                                CACHE.put(id, info);
                                found.add(id);
                            }
                        }
                    } catch (Exception ignored) { }
                    for (String id : ids) if (!found.contains(id)) CACHE.put(id, NONE);
                    if (onReady != null) onReady.run();
                });
            } catch (Exception e) {
                synchronized (INFLIGHT) { INFLIGHT.removeAll(ids); }
            }
        }
    }

    private static String str(JSONObject o, String k) {
        return o.isNull(k) ? null : o.optString(k, null);
    }

    /** কার্ডের ডানে-নিচের স্পিকার আইকন: গান থাকলে দেখায় (চালু = নীল স্পিকার, বন্ধ = ধূসর মিউট) */
    public static void bindIcon(final ImageView icon, final Post p) {
        final Info info = get(p.id);
        if (info == null) {
            icon.setVisibility(ImageView.GONE);
            icon.setOnClickListener(null);
            return;
        }
        icon.setVisibility(ImageView.VISIBLE);
        boolean on = MusicPlayer.isPlaying("post:" + p.id);
        icon.setImageResource(on ? R.drawable.ic_music_on : R.drawable.ic_music_off);
        icon.setColorFilter(on ? 0xFF1877F2 : 0xFF65676B);
        icon.setOnClickListener(v -> {
            Context c = v.getContext();
            if (!Net.check(c)) return;
            MusicPlayer.toggle(c, "post:" + p.id, info.url, info.start, info.len);
        });
    }
}
