package com.familybook.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Deezer ফ্রি API (কোনো key লাগে না) — শুধু Trending ট্যাবের জন্য।
 * পোস্টে গানের url হিসেবে "dz:{trackId}" সেভ হয়।
 * Deezer-এর preview লিংকের মেয়াদ থাকে, তাই লিংক সেভ না করে চালানোর সময় নতুন করে আনা হয়
 * (MusicPlayer → resolvePreview)।
 */
public class DeezerApi {

    public static final String PREFIX = "dz:";
    private static final String BASE = "https://api.deezer.com";

    public interface PreviewCb {
        /** মেইন থ্রেডে; ব্যর্থ হলে url == null */
        void done(String url);
    }

    public interface BitmapCb {
        /** মেইন থ্রেডে; ব্যর্থ হলে bm == null */
        void done(Bitmap bm);
    }

    private static final ExecutorService API = Executors.newFixedThreadPool(2);
    private static final ExecutorService IMG = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> COVERS = new LruCache<>(80);

    private static class Preview {
        String url;
        long at;
    }

    private static final Map<String, Preview> PREVIEWS = new ConcurrentHashMap<>();
    private static final long PREVIEW_TTL = 5 * 60 * 1000L;

    // ------------------------------------------------------------ Trending চার্ট

    /** Deezer-এর গ্লোবাল চার্ট। MusicApi.Track আকারে ফেরত (id = "dz{trackId}", url = "dz:{trackId}") */
    public static void trending(final int limit, final MusicApi.Result cb) {
        API.execute(() -> {
            final List<MusicApi.Track> out = new ArrayList<>();
            boolean failed = false;
            try {
                JSONObject root = new JSONObject(get(BASE + "/chart/0/tracks?limit=" + limit));
                if (root.has("error")) throw new Exception("deezer error");
                JSONArray data = root.getJSONArray("data");
                for (int i = 0; i < data.length(); i++) {
                    JSONObject o = data.getJSONObject(i);
                    if (o.optString("preview", "").isEmpty()) continue;   // preview না থাকলে বাদ
                    long id = o.optLong("id");
                    MusicApi.Track t = new MusicApi.Track();
                    t.id = "dz" + id;
                    t.title = o.optString("title", "Unknown");
                    JSONObject ar = o.optJSONObject("artist");
                    t.artist = ar == null ? "" : ar.optString("name", "");
                    JSONObject al = o.optJSONObject("album");
                    t.album = "";
                    t.genre = "";
                    t.cover = al == null ? "" : al.optString("cover_medium", "");
                    t.url = PREFIX + id;
                    t.time = 0;
                    out.add(t);
                }
            } catch (Exception e) {
                failed = true;
            }
            final boolean f = failed;
            MAIN.post(() -> cb.done(out, f));
        });
    }

    // ------------------------------------------------------------ চালানোর সময় নতুন preview লিংক

    /** trackId (শুধু সংখ্যা) থেকে এখনকার কার্যকর ৩০ সেকেন্ডের mp3 লিংক */
    public static void resolvePreview(final String trackId, final PreviewCb cb) {
        Preview p = PREVIEWS.get(trackId);
        if (p != null && System.currentTimeMillis() - p.at < PREVIEW_TTL) {
            cb.done(p.url);
            return;
        }
        API.execute(() -> {
            String url = null;
            try {
                JSONObject o = new JSONObject(get(BASE + "/track/" + trackId));
                if (!o.has("error")) {
                    String pv = o.optString("preview", "");
                    if (!pv.isEmpty()) {
                        url = pv;
                        Preview np = new Preview();
                        np.url = pv;
                        np.at = System.currentTimeMillis();
                        PREVIEWS.put(trackId, np);
                    }
                }
            } catch (Exception ignored) { }
            final String u = url;
            MAIN.post(() -> cb.done(u));
        });
    }

    // ------------------------------------------------------------ কভার ছবি

    public static Bitmap cachedCover(String url) {
        return (url == null || url.isEmpty()) ? null : COVERS.get(url);
    }

    public static void loadCover(final String url, final BitmapCb cb) {
        Bitmap c = cachedCover(url);
        if (c != null) {
            cb.done(c);
            return;
        }
        IMG.execute(() -> {
            Bitmap bm = null;
            try {
                byte[] raw = getBytes(url);
                bm = BitmapFactory.decodeByteArray(raw, 0, raw.length);
                if (bm != null) COVERS.put(url, bm);
            } catch (Exception ignored) { }
            final Bitmap r = bm;
            MAIN.post(() -> cb.done(r));
        });
    }

    // ------------------------------------------------------------ HTTP

    private static String get(String u) throws Exception {
        return new String(getBytes(u), "UTF-8");
    }

    private static byte[] getBytes(String u) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(15000);
        try {
            if (c.getResponseCode() != 200) throw new Exception("http " + c.getResponseCode());
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return bo.toByteArray();
        } finally {
            c.disconnect();
        }
    }
}
