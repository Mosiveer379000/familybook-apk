package com.familybook.app;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short-এর তথ্যের ক্যাশ (ফিড কার্ড, প্রোফাইল গ্রিড ও ফুল-স্ক্রিন ভিউয়ার একই তথ্য ব্যবহার করে)।
 * ফিডের "uploaded a short" পোস্ট ↔ Short মেলানো হয় থাম্বনেইল key দিয়ে (পোস্টের image_key = shorts.thumb_key)।
 */
final class ShortStore {

    static final class Info {
        String id, uid, thumbKey, videoKey, title;
        String musicTitle, musicArtist, musicCover, musicUrl;   // আলাদা গান ব্যবহার হলে (না হলে null = অরিজিনাল সাউন্ড)
        int musicStart, musicLen;
        boolean aiLabel;         // AI দিয়ে বানানো ভিডিও (shorts.ai_label)
        long views;
        int width, height;
        Post post;               // এই Short-এর ফিড-পোস্ট (লাইক/কমেন্ট/মেনুর জন্য), লোড হলে বসে
    }

    static final String SELECT = "id,user_id,thumb_key,video_key,title,views,width,height,"
            + "music_title,music_artist,music_cover,music_url,music_start,music_len,ai_label";

    private static final Map<String, Info> BY_ID = new ConcurrentHashMap<>();
    private static final Map<String, Info> BY_THUMB = new ConcurrentHashMap<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private ShortStore() { }

    /** এডিটের পর ক্যাশ হালনাগাদ (টাইটেল / থাম্বনেইল বদলালে) */
    static void updateLocal(String id, String title, String newThumbKey) {
        Info i = byId(id);
        if (i == null) return;
        if (title != null) i.title = title;
        if (newThumbKey != null && !newThumbKey.isEmpty()) {
            if (i.thumbKey != null) BY_THUMB.remove(i.thumbKey);
            i.thumbKey = newThumbKey;
            BY_THUMB.put(newThumbKey, i);
        }
    }

    static Info byId(String id) { return id == null ? null : BY_ID.get(id); }

    static Info byThumb(String key) { return key == null ? null : BY_THUMB.get(key); }

    /** shorts সারি → Info (ক্যাশে বসায়; আগের Info থাকলে সেটাই হালনাগাদ হয়, যাতে post হারায় না) */
    static Info put(JSONObject o) {
        String id = o.optString("id", "");
        if (id.isEmpty()) return null;
        Info i = BY_ID.get(id);
        if (i == null) i = new Info();
        i.id = id;
        i.uid = o.optString("user_id", i.uid);
        i.thumbKey = o.isNull("thumb_key") ? i.thumbKey : o.optString("thumb_key", null);
        i.videoKey = o.isNull("video_key") ? i.videoKey : o.optString("video_key", null);
        i.title = o.isNull("title") ? "" : o.optString("title", "");
        i.views = o.optLong("views", 0);
        i.width = o.optInt("width", 0);
        i.height = o.optInt("height", 0);
        i.musicTitle = o.isNull("music_title") ? null : o.optString("music_title", null);
        i.musicArtist = o.isNull("music_artist") ? null : o.optString("music_artist", null);
        i.musicCover = o.isNull("music_cover") ? null : o.optString("music_cover", null);
        i.musicUrl = o.isNull("music_url") ? null : o.optString("music_url", null);
        i.musicStart = o.optInt("music_start", 0);
        i.musicLen = o.optInt("music_len", 0);
        if (o.has("ai_label")) i.aiLabel = o.optBoolean("ai_label", false);
        BY_ID.put(id, i);
        if (i.thumbKey != null && !i.thumbKey.isEmpty()) BY_THUMB.put(i.thumbKey, i);
        return i;
    }

    // ------------------------------------------------------------ থাম্বনেইল key ধরে ব্যাচে আনা

    private static final Set<String> pending = new HashSet<>();
    private static final Set<String> asked = new HashSet<>();     // একবার জিজ্ঞেস করা হয়ে গেছে (না পেলেও আর নয়)
    private static final List<Runnable> waiters = new ArrayList<>();
    private static boolean scheduled = false;

    /** key-র Short এখনো ক্যাশে না থাকলে সার্ভার থেকে আনে (অনেকগুলো একসাথে এক রিকোয়েস্টে); আনা হলে done চলে */
    static void request(final String thumbKey, final Runnable done) {
        if (thumbKey == null || thumbKey.isEmpty() || BY_THUMB.containsKey(thumbKey) || asked.contains(thumbKey)) return;
        pending.add(thumbKey);
        if (done != null) waiters.add(done);
        if (scheduled) return;
        scheduled = true;
        MAIN.postDelayed(ShortStore::flush, 60);
    }

    private static void flush() {
        scheduled = false;
        if (pending.isEmpty()) return;
        final List<String> keys = new ArrayList<>(pending);
        pending.clear();
        asked.addAll(keys);
        final List<Runnable> cbs = new ArrayList<>(waiters);
        waiters.clear();
        StringBuilder in = new StringBuilder();
        try {
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) in.append(',');
                in.append('"').append(keys.get(i).replace("\"", "")).append('"');
            }
            SupabaseRest.call("GET", "/rest/v1/shorts?thumb_key=in.(" + URLEncoder.encode(in.toString(), "UTF-8")
                    + ")&status=neq.deleted&select=" + SELECT, null, null, true, r -> {
                if (r.ok()) {
                    try {
                        JSONArray a = new JSONArray(r.body);
                        for (int i = 0; i < a.length(); i++) put(a.getJSONObject(i));
                    } catch (Exception ignored) { }
                }
                for (Runnable c : cbs) c.run();
            });
        } catch (Exception e) {
            for (Runnable c : cbs) c.run();
        }
    }

    // ------------------------------------------------------------ Short → ফিড-পোস্ট (লাইক/কমেন্ট/শেয়ার/মেনু)

    interface PostCb { void on(Post post); }

    /** Short-এর ফিড-পোস্ট আনে (না থাকলে null) */
    static void loadPost(final Info info, final PostCb cb) {
        if (info == null) { cb.on(null); return; }
        if (info.post != null) { cb.on(info.post); return; }
        if (info.thumbKey == null) { cb.on(null); return; }
        try {
            String path = "/rest/v1/posts?image_key=eq." + URLEncoder.encode(info.thumbKey, "UTF-8")
                    + "&activity=eq." + URLEncoder.encode(ProfilePhotoPost.MARK + ShortUploader.SHORT_PHRASE, "UTF-8")
                    + "&select=id&limit=1";
            SupabaseRest.call("GET", path, null, null, true, r -> {
                String pid = null;
                if (r.ok()) {
                    try {
                        JSONArray a = new JSONArray(r.body);
                        if (a.length() > 0) pid = a.getJSONObject(0).optString("id", null);
                    } catch (Exception ignored) { }
                }
                if (pid == null) { cb.on(null); return; }
                SupabaseRest.call("GET", "/rest/v1/post_cards_g?id=eq." + pid, null, null, true, r2 -> {
                    Post p = null;
                    if (r2.ok()) {
                        try {
                            JSONArray a = new JSONArray(r2.body);
                            if (a.length() > 0) p = Post.from(a.getJSONObject(0), SupabaseRest.uid());
                        } catch (Exception ignored) { }
                    }
                    if (p != null) info.post = p;
                    cb.on(p);
                });
            });
        } catch (Exception e) {
            cb.on(null);
        }
    }

    // ------------------------------------------------------------ Trending (র‍্যাংক অনুযায়ী সর্বোচ্চ ৩০টি)

    interface ListCb { void on(List<Info> list); }

    /** র‍্যাংকের স্কোর: শেয়ার ×৪, কমেন্ট ×৩, লাইক ×২, ভিউ ×১ */
    static long score(Post p) {
        return p.shares * 4L + p.comments * 3L + p.likes * 2L + p.views;
    }

    /**
     * সবচেয়ে বেশি লাইক/কমেন্ট/শেয়ার/ভিউ পাওয়া public Short-গুলো (সর্বোচ্চ max টি), স্কোর অনুযায়ী বড় থেকে ছোট।
     * সার্ভার ব্যর্থ হলে cb-তে null আসে; Short না থাকলে ফাঁকা তালিকা।
     */
    static void loadTrending(final int max, final ListCb cb) {
        final String act;
        try {
            act = URLEncoder.encode(ProfilePhotoPost.MARK + ShortUploader.SHORT_PHRASE, "UTF-8");
        } catch (Exception e) { cb.on(null); return; }
        // ১) সাম্প্রতিক Short-পোস্টের id + থাম্বনেইল key
        SupabaseRest.call("GET", "/rest/v1/posts?activity=eq." + act
                + "&select=id,image_key&order=created_at.desc&limit=200", null, null, true, r -> {
            if (!r.ok()) { cb.on(null); return; }
            final List<String> ids = new ArrayList<>();
            final Map<String, String> thumbOf = new HashMap<>();
            try {
                JSONArray a = new JSONArray(r.body);
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    String id = o.optString("id", "");
                    String key = o.isNull("image_key") ? "" : o.optString("image_key", "");
                    if (id.isEmpty() || key.isEmpty()) continue;
                    ids.add(id);
                    thumbOf.put(id, key);
                }
            } catch (Exception e) { cb.on(null); return; }
            if (ids.isEmpty()) { cb.on(new ArrayList<>()); return; }

            // ২) লাইক/কমেন্ট/শেয়ার/ভিউ-র সংখ্যা (৫০টি করে)
            final List<Post> posts = new ArrayList<>();
            final int chunks = (ids.size() + 49) / 50;
            final int[] left = {chunks};
            for (int c = 0; c < chunks; c++) {
                StringBuilder in = new StringBuilder();
                for (int i = c * 50; i < Math.min(ids.size(), c * 50 + 50); i++) {
                    if (in.length() > 0) in.append(',');
                    in.append(ids.get(i));
                }
                SupabaseRest.call("GET", "/rest/v1/post_cards_g?id=in.(" + in + ")&select=*", null, null, true, r2 -> {
                    if (r2.ok()) {
                        try {
                            JSONArray a = new JSONArray(r2.body);
                            for (int i = 0; i < a.length(); i++) {
                                Post p = Post.from(a.getJSONObject(i), SupabaseRest.uid());
                                if (p != null && p.id != null) posts.add(p);
                            }
                        } catch (Exception ignored) { }
                    }
                    if (--left[0] == 0) rankTrending(posts, thumbOf, max, cb);
                });
            }
        });
    }

    private static void rankTrending(List<Post> posts, final Map<String, String> thumbOf, int max, final ListCb cb) {
        final List<Post> ok = new ArrayList<>();
        for (Post p : posts) {
            if (!"public".equals(p.privacy) || !"active".equals(p.status) || p.isGroupPost()) continue;
            if (ReelInterest.loaded && ReelInterest.with(-1).contains(p.uid)) continue;   // Not interested দেওয়া নির্মাতা বাদ
            ok.add(p);
        }
        Collections.sort(ok, new Comparator<Post>() {
            @Override public int compare(Post a, Post b) {
                int c = Long.compare(score(b), score(a));
                return c != 0 ? c : Long.compare(b.timestamp, a.timestamp);
            }
        });
        // এক Short-এর একাধিক পোস্ট থাকলে (একই থাম্বনেইল) একবারই আসে
        final List<Post> top = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        for (Post p : ok) {
            String k = thumbOf.get(p.id);
            if (k == null || !seen.add(k)) continue;
            top.add(p);
            if (top.size() >= max) break;
        }
        if (top.isEmpty()) { cb.on(new ArrayList<>()); return; }

        // ৩) ঐ পোস্টগুলোর Short (ভিডিও) আনি
        try {
            StringBuilder in = new StringBuilder();
            for (int i = 0; i < top.size(); i++) {
                if (i > 0) in.append(',');
                in.append('"').append(thumbOf.get(top.get(i).id).replace("\"", "")).append('"');
            }
            SupabaseRest.call("GET", "/rest/v1/shorts?thumb_key=in.(" + URLEncoder.encode(in.toString(), "UTF-8")
                    + ")&status=neq.deleted&select=" + SELECT, null, null, true, r -> {
                if (!r.ok()) { cb.on(null); return; }
                try {
                    JSONArray a = new JSONArray(r.body);
                    for (int i = 0; i < a.length(); i++) put(a.getJSONObject(i));
                } catch (Exception ignored) { }
                List<Info> out = new ArrayList<>();
                for (Post p : top) {                       // স্কোরের ক্রম ঠিক রেখে
                    Info i = byThumb(thumbOf.get(p.id));
                    if (i == null || i.videoKey == null) continue;
                    i.post = p;
                    out.add(i);
                }
                cb.on(out);
            });
        } catch (Exception e) {
            cb.on(null);
        }
    }
}
