package com.familybook.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * গানের লাইব্রেরি = Family Song অ্যাপের Firebase ডাটাবেস (REST)।
 *   /songs/{id}  → নাম, অ্যালবাম, আর্টিস্ট, জনরা, সময়
 *   /audio/{id}  → গানের ফাইল (Base64)
 * Family Song অ্যাপে নতুন গান আপলোড হলে এখানে রিফ্রেশ দিলেই দেখা যাবে।
 * পোস্টে গানের url হিসেবে "fs:{id}" সেভ হয়। (Trending ট্যাবের Deezer গানের জন্য "dz:{id}" — DeezerApi দেখুন)
 */
public class MusicApi {

    // Family Song অ্যাপের একই ডাটাবেস ও key
    private static final String DB_URL = "https://familysong379-default-rtdb.asia-southeast1.firebasedatabase.app";
    private static final String API_KEY = "AIzaSyBeWlSrPaXDh4um4uZvjjoTcfw_zad03_c";

    public static final String PREFIX = "fs:";

    public static class Track {
        public String id, title, album, artist, genre, cover = "", url;
        public long time;

        public boolean matches(String q) {
            return title.toLowerCase().contains(q) || album.toLowerCase().contains(q)
                    || artist.toLowerCase().contains(q) || genre.toLowerCase().contains(q);
        }

        /** তালিকায় দ্বিতীয় লাইনে যা দেখাবে */
        public String subtitle() {
            if (!artist.isEmpty()) return album.isEmpty() ? artist : artist + " · " + album;
            if (!album.isEmpty()) return album;
            return genre;
        }
    }

    public interface Result {
        /** মেইন থ্রেডে ডাকা হয় */
        void done(List<Track> list, boolean failed);
    }

    public interface FileResult {
        /** মেইন থ্রেডে; ব্যর্থ হলে file == null */
        void done(File file, String error);
    }

    // গানের ছোট ছবি (Family Song-এ যোগ করা / Deezer-এর কভার), গানের id দিয়ে ক্যাশ
    private static final Map<String, Bitmap> COVERS = new java.util.concurrent.ConcurrentHashMap<>();

    /** ছবি না থাকলে null */
    public static Bitmap cover(String id) {
        return id == null ? null : COVERS.get(id);
    }

    /** Deezer গানের কভার ক্যাশে রাখতে (key = "dz:123") */
    public static void putCover(String key, Bitmap bm) {
        if (key != null && bm != null) COVERS.put(key, bm);
    }

    /** "fs:123" → "123"; Deezer-এর "dz:123" → "dz:123" (কভার ক্যাশের key হিসেবে) */
    public static String idOf(String url) {
        if (url == null) return null;
        if (url.startsWith(PREFIX)) return url.substring(PREFIX.length());
        if (url.startsWith(DeezerApi.PREFIX)) return url;
        return null;
    }

    private static final ExecutorService EX = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    // ------------------------------------------------------------ Firebase anonymous লগইন

    private static String idToken;
    private static long tokenExpiry;

    private static synchronized String token() throws Exception {
        if (idToken != null && System.currentTimeMillis() < tokenExpiry) return idToken;
        HttpURLConnection c = (HttpURLConnection) new URL(
                "https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=" + API_KEY).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(15000);
        c.setRequestProperty("Content-Type", "application/json");
        try (OutputStream out = c.getOutputStream()) {
            out.write("{\"returnSecureToken\":true}".getBytes("UTF-8"));
        }
        if (c.getResponseCode() != 200) throw new Exception("login " + c.getResponseCode());
        JSONObject o = new JSONObject(body(c));
        idToken = o.getString("idToken");
        long sec = Long.parseLong(o.optString("expiresIn", "3600"));
        tokenExpiry = System.currentTimeMillis() + (sec - 120) * 1000;
        return idToken;
    }

    private static String body(HttpURLConnection c) throws Exception {
        InputStream in;
        try { in = c.getInputStream(); } catch (Exception e) { in = c.getErrorStream(); }
        if (in == null) return "";
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toString("UTF-8");
    }

    // ------------------------------------------------------------ গানের তালিকা

    /** সব গান, নতুন আগে */
    public static void loadSongs(final Result cb) {
        EX.execute(() -> {
            final List<Track> out = new ArrayList<>();
            boolean failed = false;
            try {
                HttpURLConnection c = (HttpURLConnection)
                        new URL(DB_URL + "/songs.json?auth=" + token()).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(20000);
                if (c.getResponseCode() != 200) throw new Exception("songs " + c.getResponseCode());
                String b = body(c).trim();
                if (!b.isEmpty() && !"null".equals(b)) {
                    JSONObject root = new JSONObject(b);
                    Iterator<String> keys = root.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        JSONObject o = root.optJSONObject(key);
                        if (o == null) continue;
                        Track t = new Track();
                        t.id = key;
                        t.title = o.optString("title", "Unknown");
                        t.album = o.optString("album", "");
                        t.artist = o.optString("artist", "");
                        t.genre = o.optString("genre", "");
                        t.time = o.optLong("time", 0);
                        t.url = PREFIX + key;
                        String cov = o.optString("cover", "");
                        if (!cov.isEmpty()) {
                            try {
                                byte[] raw = Base64.decode(cov, Base64.DEFAULT);
                                Bitmap bm = BitmapFactory.decodeByteArray(raw, 0, raw.length);
                                if (bm != null) COVERS.put(key, bm);
                            } catch (Exception ignored) { }
                        } else {
                            COVERS.remove(key);
                        }
                        out.add(t);
                    }
                }
                Collections.sort(out, new Comparator<Track>() {
                    @Override public int compare(Track a, Track b2) { return Long.compare(b2.time, a.time); }
                });
            } catch (Exception e) {
                failed = true;
            }
            final boolean f = failed;
            MAIN.post(() -> cb.done(out, f));
        });
    }

    /** (এখন Trending ট্যাবে ব্যবহার হয় না — Deezer চার্ট দেখানো হয়) ফ্যামিলি বুকের পোস্টে ব্যবহারের হিসেবে সাজানো */
    public static void trending(final List<Track> all, final Result cb) {
        SupabaseRest.call("GET", "/rest/v1/posts?music_url=like.fs:*&select=music_url&limit=1000",
                null, null, true, r -> {
            final Map<String, Integer> count = new HashMap<>();
            if (r.ok()) {
                try {
                    JSONArray arr = new JSONArray(r.body);
                    for (int i = 0; i < arr.length(); i++) {
                        String u = arr.getJSONObject(i).optString("music_url", "");
                        if (u.startsWith(PREFIX)) {
                            String id = u.substring(PREFIX.length());
                            Integer n = count.get(id);
                            count.put(id, n == null ? 1 : n + 1);
                        }
                    }
                } catch (Exception ignored) { }
            }
            List<Track> sorted = new ArrayList<>(all);
            Collections.sort(sorted, new Comparator<Track>() {
                @Override public int compare(Track a, Track b) {
                    int ca = count.containsKey(a.id) ? count.get(a.id) : 0;
                    int cb2 = count.containsKey(b.id) ? count.get(b.id) : 0;
                    if (ca != cb2) return Integer.compare(cb2, ca);
                    return Long.compare(b.time, a.time);
                }
            });
            cb.done(sorted, false);
        });
    }

    // ------------------------------------------------------------ গানের ফাইল (ডাউনলোড + ক্যাশ)

    private static final Map<String, List<FileResult>> WAITING = new HashMap<>();

    public static File cacheFile(Context c, String id) {
        return new File(c.getApplicationContext().getCacheDir(), "song_" + id + ".audio");
    }

    public static boolean isCached(Context c, String id) {
        File f = cacheFile(c, id);
        return f.exists() && f.length() > 100;
    }

    /** গানের ফাইল ফোনে না থাকলে ডাউনলোড করে ক্যাশে রাখে; একই গান একসাথে একবারই নামে */
    public static void fetchAudio(Context c, final String id, FileResult cb) {
        final Context app = c.getApplicationContext();
        final File target = cacheFile(app, id);
        if (isCached(app, id)) {
            cb.done(target, null);
            return;
        }
        synchronized (WAITING) {
            List<FileResult> list = WAITING.get(id);
            if (list != null) {            // আগে থেকেই নামছে
                list.add(cb);
                return;
            }
            list = new ArrayList<>();
            list.add(cb);
            WAITING.put(id, list);
        }
        EX.execute(() -> {
            String err = null;
            try {
                download(app, id, target);
            } catch (Exception e) {
                err = e.getMessage() == null ? "error" : e.getMessage();
            }
            final String error = err;
            final List<FileResult> waiters;
            synchronized (WAITING) { waiters = WAITING.remove(id); }
            MAIN.post(() -> {
                if (waiters == null) return;
                for (FileResult w : waiters) w.done(error == null ? target : null, error);
            });
        });
    }

    private static void download(Context app, String id, File target) throws Exception {
        HttpURLConnection c = (HttpURLConnection)
                new URL(DB_URL + "/audio/" + id + ".json?auth=" + token()).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(180000);
        if (c.getResponseCode() != 200) throw new Exception("download " + c.getResponseCode());

        File tmp = new File(app.getCacheDir(), "tmp_" + id);
        StringBuilder pending = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(c.getInputStream(), "UTF-8"), 65536);
             FileOutputStream out = new FileOutputStream(tmp)) {
            char[] cb = new char[16384];
            int n;
            while ((n = reader.read(cb)) > 0) {
                for (int i = 0; i < n; i++) {
                    char ch = cb[i];
                    boolean ok = (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z')
                            || (ch >= '0' && ch <= '9') || ch == '-' || ch == '_' || ch == '=';
                    if (ok) pending.append(ch);
                }
                int usable = (pending.length() / 4) * 4;
                if (usable >= 16384) {
                    out.write(Base64.decode(pending.substring(0, usable), Base64.URL_SAFE));
                    pending.delete(0, usable);
                }
            }
            if (pending.length() > 0) out.write(Base64.decode(pending.toString(), Base64.URL_SAFE));
        }
        if (tmp.length() < 100) {
            tmp.delete();
            throw new Exception("গানের ফাইল পাওয়া যায়নি");
        }
        if (target.exists()) target.delete();
        if (!tmp.renameTo(target)) throw new Exception("ফাইল সেভ করা যায়নি");
    }
}
