package com.familybook.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * পোস্ট ব্যাকগ্রাউন্ডে পাঠায়: ছবিগুলো একটা একটা করে R2-তে আপলোড → posts-এ insert → ট্যাগ/কোলাব।
 *
 * অফলাইন: ইন্টারনেট না থাকলে পোস্ট "Draft" হিসেবে ফোনে সেভ হয় (অবস্থা = waiting for network)।
 * নেট ফিরলেই নিজে থেকে আপলোড শুরু হয়; অ্যাপ বন্ধ করে আবার খুললেও Draft থেকে যায়।
 * অগ্রগতি percent() (১–১০০) — ফিডের উপরের বারে গোল প্রোগ্রেস বারে দেখায়।
 */
public final class PostQueue {
    private static final String TAG = "PostQueue";

    public static class Job {
        public String uid, authorName, text, hashtags, privacy, feeling, activity, location;
        public String musicTitle, musicArtist, musicCover, musicUrl;
        public int musicStart;
        public final List<PostPhoto> photos = new ArrayList<>();
        public int variant;                       // 0 = সাধারণ গ্রিড, 1 = বিকল্প সাজানো
        public final List<String> tagIds = new ArrayList<>();
        public String collabId;
        public String groupId;                    // গ্রুপ পোস্ট হলে গ্রুপের id (সাধারণ পোস্টে null)
    }

    public interface Listener { void onChange(); }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService DISK = Executors.newSingleThreadExecutor();

    private static Context app;
    private static Job job;
    private static boolean failed;       // সার্ভার/অন্য সমস্যায় ব্যর্থ — Tap to retry
    private static boolean waiting;      // ইন্টারনেট নেই — Draft হিসেবে অপেক্ষা
    private static boolean running;      // এখন নেটওয়ার্কের কাজ চলছে
    private static boolean inserting;    // posts-এ insert চলছে (এটা মাঝপথে ছাড়া যাবে না — দুইবার পোস্ট হতে পারে)
    private static int gen;              // পুরনো callback চিনে বাদ দিতে
    private static int percent;          // ০–১০০
    private static String error;
    private static Listener listener;
    private static boolean netHooked;

    private PostQueue() { }

    // ---------- অবস্থা ----------

    public static boolean isActive() { return job != null; }
    public static boolean isFailed() { return job != null && failed; }
    public static boolean isWaiting() { return job != null && waiting && !failed; }
    public static String error() { return error; }
    public static int total() { return job == null ? 0 : job.photos.size(); }

    /** ১–১০০; নেটের অপেক্ষায় থাকলে ০ */
    public static int percent() {
        if (job == null || waiting) return 0;
        return Math.max(1, Math.min(100, percent));
    }

    public static int done() {
        if (job == null) return 0;
        int n = 0;
        for (PostPhoto p : job.photos) if (p.key != null) n++;
        return n;
    }

    public static Bitmap thumb() {
        if (job == null || job.photos.isEmpty()) return null;
        PostPhoto p = job.photos.get(0);
        return p.comp == null ? null : p.comp.preview;
    }

    public static void setListener(Listener l) { listener = l; }

    private static void changed() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (listener != null) listener.onChange();
        } else {
            MAIN.post(() -> { if (listener != null) listener.onChange(); });
        }
    }

    // ---------- শুরু / পুনরায় চালু ----------

    /** FamilyBookApp.onCreate-এ একবার: নেট আসা-যাওয়া শোনে এবং আগের অসমাপ্ত Draft ফিরিয়ে আনে */
    public static void init(Context ctx) {
        app = ctx.getApplicationContext();
        if (!netHooked) {
            netHooked = true;
            Net.addListener(online -> { if (online) onOnline(); else onOffline(); });
        }
        restore();
        if (job != null) kick();
    }

    public static void start(Context ctx, Job j) {
        if (job != null) return;
        app = ctx.getApplicationContext();
        job = j;
        failed = false;
        waiting = false;
        running = false;
        inserting = false;
        error = null;
        percent = 0;
        gen++;
        persist(j);          // Draft হিসেবে ফোনে সেভ (নেট না থাকলে/অ্যাপ বন্ধ হলেও হারাবে না)
        changed();
        kick();
    }

    public static void retry() {
        if (job == null || !failed) return;
        failed = false;
        error = null;
        changed();
        kick();
    }

    public static void dismiss() {
        job = null;
        failed = false;
        waiting = false;
        running = false;
        inserting = false;
        error = null;
        percent = 0;
        gen++;
        wipeFiles();
        changed();
    }

    /** নেট থাকলে চালায়, না থাকলে Draft হিসেবে অপেক্ষায় রাখে */
    private static void kick() {
        if (job == null || running) return;
        if (!Net.isOnline()) {
            waiting = true;
            failed = false;
            changed();
            return;
        }
        waiting = false;
        failed = false;
        changed();
        run();
    }

    private static void onOnline() {
        if (job == null || running) return;
        if (waiting || failed) {
            failed = false;
            error = null;
            kick();
        }
    }

    private static void onOffline() {
        if (job == null) return;
        if (inserting) return;            // insert-এর উত্তর আসুক; সফল হলে শেষ, ব্যর্থ হলে নিচে waiting
        gen++;                            // চলতি আপলোডের পুরনো callback বাদ
        running = false;
        waiting = true;
        failed = false;
        changed();
    }

    private static void fail(String msg) {
        running = false;
        inserting = false;
        if (!Net.isOnline()) {            // নেট না থাকায় ব্যর্থ → ব্যর্থ নয়, Draft হয়ে অপেক্ষা
            waiting = true;
            failed = false;
        } else {
            waiting = false;
            failed = true;
            error = msg == null ? "সমস্যা হয়েছে" : msg;
        }
        changed();
    }

    // ---------- আপলোড ----------

    private static void run() {
        final Job j = job;
        if (j == null) return;
        if (!Net.isOnline()) {
            running = false;
            waiting = true;
            changed();
            return;
        }
        running = true;
        final int g = gen;
        // ১) যেগুলো আপলোড হয়নি সেগুলো পরপর আপলোড
        PostPhoto next = null;
        for (PostPhoto p : j.photos) {
            if (p.key == null) { next = p; break; }
        }
        if (next != null) {
            final PostPhoto cur = next;
            updatePercent(j, cur, 0, 1);
            changed();
            PhotoUploader.upload(app, cur.comp, (key, err) -> {
                if (job != j || g != gen) return;
                if (key == null) { fail(err == null ? "ছবি আপলোড ব্যর্থ" : err); return; }
                cur.key = key;
                updatePercent(j, null, 0, 1);
                persist(j);                 // কোন ছবি আপলোড হয়ে গেছে মনে রাখি
                run();
            }, (sent, tot) -> {
                if (job != j || g != gen) return;
                updatePercent(j, cur, sent, tot);
                changed();
            });
            return;
        }
        insert(j, g);
    }

    /** ছবির আপলোড মোট ০–৯০%; insert শেষে ৯৫, সবশেষে ১০০ */
    private static void updatePercent(Job j, PostPhoto cur, long sent, long tot) {
        if (j.photos.isEmpty()) { percent = Math.max(percent, 5); return; }
        long all = 0, done = 0;
        for (PostPhoto p : j.photos) {
            long len = p.comp == null || p.comp.bytes == null ? 1 : Math.max(1, p.comp.bytes.length);
            all += len;
            if (p.key != null) done += len;
            else if (p == cur && tot > 0) done += (long) (len * Math.min(1.0, (double) sent / tot));
        }
        int v = (int) Math.round(90.0 * done / Math.max(1, all));
        if (v > percent) percent = v;
    }

    private static void insert(final Job j, final int g) {
        String imageValue = null;
        if (!j.photos.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            if (j.variant == 1) sb.append("#B|");
            for (int i = 0; i < j.photos.size(); i++) {
                if (i > 0) sb.append('|');
                sb.append(j.photos.get(i).key);
            }
            imageValue = sb.toString();
        }
        final String img = imageValue;
        // প্রতিটি ছবির ক্যাপশন (ছবির ক্রমে); কোনোটাই না থাকলে পাঠাই না
        final JSONArray caps = new JSONArray();
        boolean anyCaption = false;
        for (PostPhoto p : j.photos) {
            String c = p.caption == null ? "" : p.caption.trim();
            if (!c.isEmpty()) anyCaption = true;
            caps.put(c);
        }
        final String[] capArr = new String[j.photos.size()];
        for (int i = 0; i < capArr.length; i++) capArr[i] = caps.optString(i, "");
        final boolean hasCaps = anyCaption;

        JSONObject body = new JSONObject();
        try {
            body.put("user_id", j.uid);
            if (notEmpty(j.authorName)) body.put("author_name", j.authorName);
            body.put("text", j.text);
            if (notEmpty(j.hashtags)) body.put("hashtags", j.hashtags);
            body.put("privacy", j.privacy);
            if (notEmpty(j.groupId)) body.put("group_id", j.groupId);
            if (notEmpty(img)) body.put("image_key", img);
            if (hasCaps) body.put("image_captions", caps.toString());
            if (notEmpty(j.feeling)) body.put("feeling", j.feeling);
            if (notEmpty(j.activity)) body.put("activity", j.activity);
            if (notEmpty(j.location)) body.put("location", j.location);
            if (notEmpty(j.musicUrl)) {
                body.put("music_title", j.musicTitle);
                body.put("music_artist", j.musicArtist);
                body.put("music_cover", j.musicCover);
                body.put("music_url", j.musicUrl);
                body.put("music_start", j.musicStart);
                body.put("music_len", MusicTrimActivity.CLIP_MS);
            }
        } catch (JSONException e) {
            Log.e(TAG, "JSON build error", e);
            fail("ডেটা তৈরিতে সমস্যা হয়েছে");
            return;
        }

        inserting = true;
        percent = Math.max(percent, j.photos.isEmpty() ? 30 : 92);
        changed();
        SupabaseRest.call("POST", "/rest/v1/posts", body.toString(), "return=representation", true, r -> {
            if (job != j) return;
            if (!r.ok()) {
                Log.w(TAG, "POST failed. HTTP " + r.code + " body: " + r.body);
                fail("পোস্ট ব্যর্থ: " + SupabaseRest.errorText(r));
                return;
            }
            String newId = null;
            try {
                JSONArray arr = new JSONArray(r.body);
                if (arr.length() > 0) newId = arr.getJSONObject(0).optString("id", null);
            } catch (JSONException e) {
                Log.e(TAG, "post id parse error", e);
            }
            if (newId != null && notEmpty(j.musicUrl)) {
                PostMusic.Info mi = new PostMusic.Info();
                mi.title = j.musicTitle;
                mi.artist = j.musicArtist;
                mi.cover = j.musicCover;
                mi.url = j.musicUrl;
                mi.start = j.musicStart;
                mi.len = MusicTrimActivity.CLIP_MS;
                PostMusic.put(newId, mi);
            }
            if (newId != null && notEmpty(img)) PostImage.put(newId, img, hasCaps ? capArr : null);
            boolean hasExtras = !j.tagIds.isEmpty() || j.collabId != null;
            if (newId == null || !hasExtras) {
                finish();
                return;
            }
            percent = Math.max(percent, 96);
            changed();
            attachExtras(j, newId);
        });
    }

    private static void attachExtras(final Job j, String postId) {
        JSONObject b = new JSONObject();
        try {
            b.put("p_post_id", postId);
            JSONArray tags = new JSONArray();
            for (String t : j.tagIds) tags.put(t);
            b.put("p_tags", tags);
            b.put("p_collab", j.collabId == null ? JSONObject.NULL : j.collabId);
        } catch (JSONException e) {
            Log.e(TAG, "extras JSON error", e);
            finish();
            return;
        }
        SupabaseRest.call("POST", "/rest/v1/rpc/attach_post_extras", b.toString(), null, true, r -> {
            if (job != j) return;
            if (!r.ok()) {
                Log.w(TAG, "attach failed: " + r.code + " " + r.body);
                if (app != null) {
                    Toast.makeText(app, "পোস্ট হয়েছে, কিন্তু ট্যাগ/কোলাব যোগ হয়নি: " + SupabaseRest.errorText(r),
                            Toast.LENGTH_LONG).show();
                }
            }
            finish();
        });
    }

    private static void finish() {
        final Job j = job;
        percent = 100;
        inserting = false;
        running = false;
        waiting = false;
        failed = false;
        changed();
        wipeFiles();                       // Draft আর দরকার নেই
        // ১০০% এক ঝলক দেখিয়ে বার সরাই
        MAIN.postDelayed(() -> {
            if (job != j) return;
            job = null;
            percent = 0;
            error = null;
            changed();
        }, 450);
    }

    private static boolean notEmpty(String s) { return s != null && !s.isEmpty(); }

    // ---------- Draft: ফোনে সেভ / ফিরিয়ে আনা ----------

    private static File dir() {
        File d = new File(app.getFilesDir(), "postq");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    private static void wipeFiles() {
        if (app == null) return;
        DISK.execute(() -> {
            File[] fs = dir().listFiles();
            if (fs != null) for (File f : fs) //noinspection ResultOfMethodCallIgnored
                f.delete();
        });
    }

    /** জবের সব তথ্য (ছবির bytes-সহ) ফাইলে লেখে; json সবশেষে লেখা হয় — সেটাই "সম্পূর্ণ" চিহ্ন */
    private static void persist(final Job j) {
        if (app == null) return;
        final JSONObject o = new JSONObject();
        final List<byte[]> blobs = new ArrayList<>();
        try {
            o.put("uid", j.uid).put("authorName", s(j.authorName)).put("text", s(j.text))
                    .put("hashtags", s(j.hashtags)).put("privacy", s(j.privacy))
                    .put("feeling", s(j.feeling)).put("activity", s(j.activity)).put("location", s(j.location))
                    .put("musicTitle", s(j.musicTitle)).put("musicArtist", s(j.musicArtist))
                    .put("musicCover", s(j.musicCover)).put("musicUrl", s(j.musicUrl))
                    .put("musicStart", j.musicStart).put("variant", j.variant)
                    .put("collabId", s(j.collabId)).put("groupId", s(j.groupId));
            JSONArray tags = new JSONArray();
            for (String t : j.tagIds) tags.put(t);
            o.put("tagIds", tags);
            JSONArray ph = new JSONArray();
            for (PostPhoto p : j.photos) {
                JSONObject po = new JSONObject();
                po.put("caption", s(p.caption)).put("key", s(p.key));
                po.put("mime", p.comp == null ? "image/jpeg" : p.comp.mime);
                po.put("ext", p.comp == null ? "jpg" : p.comp.ext);
                ph.put(po);
                blobs.add(p.comp == null ? null : p.comp.bytes);
            }
            o.put("photos", ph);
        } catch (JSONException e) {
            Log.e(TAG, "persist JSON error", e);
            return;
        }
        DISK.execute(() -> {
            try {
                File d = dir();
                for (int i = 0; i < blobs.size(); i++) {
                    byte[] b = blobs.get(i);
                    File f = new File(d, "photo_" + i + ".bin");
                    if (b == null || f.exists()) continue;
                    try (FileOutputStream out = new FileOutputStream(f)) { out.write(b); }
                }
                File tmp = new File(d, "job.json.tmp");
                try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(o.toString().getBytes("UTF-8")); }
                //noinspection ResultOfMethodCallIgnored
                tmp.renameTo(new File(d, "job.json"));
            } catch (Exception e) {
                Log.w(TAG, "persist failed", e);
            }
        });
    }

    private static String s(String v) { return v == null ? "" : v; }

    private static String ne(String v) { return v == null || v.isEmpty() ? null : v; }

    private static byte[] readAll(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        }
    }

    /** অ্যাপ নতুন করে চালু হলে আগের Draft ফিরিয়ে আনে (একই ইউজার হলে) */
    private static void restore() {
        if (job != null || app == null) return;
        File jf = new File(dir(), "job.json");
        if (!jf.exists()) return;
        try {
            JSONObject o = new JSONObject(new String(readAll(jf), "UTF-8"));
            String uid = o.optString("uid", "");
            if (uid.isEmpty() || !SupabaseRest.isLoggedIn() || !uid.equals(SupabaseRest.uid())) {
                wipeFiles();
                return;
            }
            Job j = new Job();
            j.uid = uid;
            j.authorName = ne(o.optString("authorName", ""));
            j.text = o.optString("text", "");
            j.hashtags = ne(o.optString("hashtags", ""));
            j.privacy = o.optString("privacy", "public");
            j.feeling = ne(o.optString("feeling", ""));
            j.activity = ne(o.optString("activity", ""));
            j.location = ne(o.optString("location", ""));
            j.musicTitle = ne(o.optString("musicTitle", ""));
            j.musicArtist = ne(o.optString("musicArtist", ""));
            j.musicCover = ne(o.optString("musicCover", ""));
            j.musicUrl = ne(o.optString("musicUrl", ""));
            j.musicStart = o.optInt("musicStart", 0);
            j.variant = o.optInt("variant", 0);
            j.collabId = ne(o.optString("collabId", ""));
            j.groupId = ne(o.optString("groupId", ""));
            JSONArray tags = o.optJSONArray("tagIds");
            if (tags != null) for (int i = 0; i < tags.length(); i++) j.tagIds.add(tags.getString(i));
            JSONArray ph = o.optJSONArray("photos");
            if (ph != null) {
                for (int i = 0; i < ph.length(); i++) {
                    JSONObject po = ph.getJSONObject(i);
                    File bf = new File(dir(), "photo_" + i + ".bin");
                    if (!bf.exists()) { wipeFiles(); return; }
                    PostPhoto p = new PostPhoto();
                    p.comp = new PhotoUploader.Compressed();
                    p.comp.bytes = readAll(bf);
                    p.comp.mime = po.optString("mime", "image/jpeg");
                    p.comp.ext = po.optString("ext", "jpg");
                    p.caption = po.optString("caption", "");
                    String k = po.optString("key", "");
                    p.key = k.isEmpty() ? null : k;
                    if (i == 0) p.comp.preview = p.decode(720);   // ব্যানারের থাম্বনেইল
                    j.photos.add(p);
                }
            }
            job = j;
            failed = false;
            waiting = false;
            running = false;
            inserting = false;
            percent = 0;
            gen++;
        } catch (Throwable t) {
            Log.w(TAG, "restore failed", t);
            wipeFiles();
        }
    }
}
