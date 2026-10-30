package com.familybook.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shorts আপলোডের পুরো ব্যাকএন্ড-লজিক (একসাথে একটাই Short আপলোড চলে)।
 *
 * ধাপ (ইউজার ফর্ম পূরণ করার সময় ব্যাকগ্রাউন্ডে আপনা-আপনি চলে):
 *   ১) ভিডিও কপি → দৈর্ঘ্য যাচাই (৩ সেকেন্ডের কম হলে বাতিল, ১:২০-এর বেশি হলে ১:২০-তে অটো কাট)
 *   ২) থাম্বনেইল বানানো ও আপলোড
 *   ৩) ভিডিও আপলোড (R2)
 *   ৪) ইউজার "Share now" চাপলে ও আপলোড শেষ হলে shorts টেবিলে সারি + "Shorts uploaded successfully" নোটিফিকেশন
 *
 * প্রোগ্রেস: ফর্ম পূরণের সময় সর্বোচ্চ ৫০% দেখায়; Share now-এর পর বাকিটা ১০০% পর্যন্ত।
 */
final class ShortUploader {

    static final long MIN_MS = 3_000L;       // সর্বনিম্ন ৩ সেকেন্ড
    static final long MAX_MS = 80_000L;      // সর্বোচ্চ ১ মিনিট ২০ সেকেন্ড
    static final int WEEKLY_LIMIT = 8;       // শেষ ৭ দিনে সর্বোচ্চ ৮টি
    static final String SHORT_PHRASE = "uploaded a short";   // ফিড পোস্টের হেডার-লাইন

    enum State { IDLE, PREPARING, UPLOADING, UPLOADED, DONE, FAILED }

    interface Listener { void onChanged(); }
    interface IntCb { void on(int value); }
    interface ItemsCb { void on(List<ProfileShorts.Item> items); }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private static volatile State state = State.IDLE;
    private static volatile int gen = 0;            // বাতিল/নতুন কাজ চেনার জন্য
    private static volatile int actual = 0;         // আসল অগ্রগতি 0..100
    private static volatile boolean shared = false; // Share now চাপা হয়েছে
    private static volatile String error;

    private static Context app;
    private static Uri srcUri;
    private static String srcKey;
    private static File file;
    private static Bitmap thumb;
    private static byte[] thumbBytes;
    private static long durationMs;
    private static int width, height;
    private static boolean trimmed;
    private static boolean packed;                  // কমপ্রেস + fast start হয়ে গেছে
    private static String videoKey, thumbKey;
    private static boolean thumbDirty;              // থাম্বনেইল এখনো আপলোড হয়নি (Share now-এর সময় আপলোড হয়)
    private static JSONObject meta;

    private ShortUploader() { }

    // ------------------------------------------------------------ অবস্থা

    static State state() { return state; }
    static String error() { return error; }
    static Bitmap thumb() { return thumb; }
    static long durationMs() { return durationMs; }
    static java.io.File videoFile() { return file; }

    /** ইউজারের বেছে নেওয়া থাম্বনেইল (ভিডিওর ফ্রেম বা গ্যালারির ছবি) — Share now-এর সময় আপলোড হবে */
    static void setThumb(Bitmap b) {
        if (b == null) return;
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.JPEG, 88, bo);
            thumb = b;
            thumbBytes = bo.toByteArray();
            thumbDirty = true;
            changed();
        } catch (Throwable ignored) { }
    }
    static boolean wasTrimmed() { return trimmed; }

    /** Share now চাপা হয়েছে এবং এখনো শেষ হয়নি (ব্যর্থ হলেও true — রিট্রাই করা যাবে) */
    static boolean isSubmitted() { return shared && state != State.IDLE && state != State.DONE; }

    /** ফর্ম পূরণের সময় সর্বোচ্চ ৫০%; Share now-এর পর আসল মান */
    static int displayPercent() {
        int a = Math.max(0, Math.min(100, actual));
        return shared ? a : Math.min(a, 50);
    }

    static void addListener(Listener l) { if (!LISTENERS.contains(l)) LISTENERS.add(l); }
    static void removeListener(Listener l) { LISTENERS.remove(l); }

    private static void changed() {
        MAIN.post(() -> { for (Listener l : LISTENERS) l.onChanged(); });
    }

    // ------------------------------------------------------------ শুরু / বাতিল

    /** নির্বাচিত ভিডিও নিয়ে কাজ শুরু (একই ভিডিও আবার দিলে কিছু করে না)। মেইন থ্রেডে ডাকুন। */
    static void start(Context ctx, Uri uri) {
        if (uri.toString().equals(srcKey) && state != State.IDLE && state != State.DONE) return;
        reset();
        app = ctx.getApplicationContext();
        srcUri = uri;
        srcKey = uri.toString();
        state = State.PREPARING;
        actual = 0;
        changed();
        step(gen);
    }

    /** ইউজার Share না করেই ফিরে গেলে */
    static void cancel() {
        if (shared) return;   // Share করা কাজ ব্যাকগ্রাউন্ডে চলতেই থাকবে
        reset();
        changed();
    }

    private static void reset() {
        gen++;
        VideoCompressor.cancel();
        packed = false;
        state = State.IDLE;
        actual = 0;
        shared = false;
        error = null;
        meta = null;
        videoKey = null;
        thumbKey = null;
        thumbDirty = false;
        thumbBytes = null;
        thumb = null;
        durationMs = 0;
        width = height = 0;
        trimmed = false;
        srcUri = null;
        srcKey = null;
        if (file != null) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            file = null;
        }
    }

    /** ব্যর্থ আপলোড ছেড়ে দেওয়া (Home ব্যানারের ✕) */
    static void dismiss() {
        reset();
        changed();
    }

    /** ব্যর্থ হলে যেখান থেকে থেমেছিল সেখান থেকে আবার */
    static void retry() {
        if (state != State.FAILED) return;
        error = null;
        state = State.UPLOADING;
        changed();
        step(gen);
    }

    /** "Share now" — meta: title, description, hashtags ... (ShortSettingsActivity বানায়) */
    static void submit(JSONObject m) {
        meta = m;
        shared = true;
        changed();
        if (state == State.UPLOADED) insert(gen);
    }

    // ------------------------------------------------------------ ধাপে ধাপে (মেইন থ্রেড)

    private static void step(final int g) {
        if (g != gen) return;

        if (file == null) {
            state = State.PREPARING;
            changed();
            WORK.execute(() -> {
                try {
                    prepare(g);
                    MAIN.post(() -> {
                        if (g != gen) return;
                        actual = 8;
                        changed();
                        step(g);
                    });
                } catch (Throwable e) {
                    final String msg = e.getMessage() == null ? "Couldn't prepare the video" : e.getMessage();
                    MAIN.post(() -> fail(g, msg));
                }
            });
            return;
        }

        if (!packed) {
            // ভিডিও কমপ্রেস (৭২০p, H.264 ২.৫ Mbps) → fast start; ব্যর্থ হলে মূল ফাইলই যায়
            state = State.UPLOADING;
            changed();
            packVideo(g);
            return;
        }

        if (videoKey == null) {
            state = State.UPLOADING;
            changed();
            String name = System.currentTimeMillis() + "_" + (1000 + new java.util.Random().nextInt(9000)) + ".mp4";
            Media.uploadFile(file, "video", name, "video/mp4", (key, err) -> {
                if (g != gen) return;
                if (err != null) { fail(g, err); return; }
                videoKey = key;
                actual = 97;
                changed();
                step(g);
            }, (sent, total) -> {
                if (g != gen) return;
                actual = 40 + (int) (57L * sent / Math.max(1L, total));
                changed();
            });
            return;
        }

        // ভিডিও + থাম্বনেইল আপলোড শেষ
        if (!shared) {
            state = State.UPLOADED;
            changed();
            return;
        }
        insert(g);
    }

    private static void fail(int g, String msg) {
        if (g != gen) return;
        error = msg;
        state = State.FAILED;
        changed();
        toast(msg);
    }

    private static void toast(String m) {
        if (app != null) Toast.makeText(app, m, Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------ প্রস্তুতি (ব্যাকগ্রাউন্ড থ্রেড)

    private static void prepare(int g) throws Exception {
        File dir = new File(app.getCacheDir(), "shorts");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        cleanDir(dir, "src_", "cut_");   // আগের অসমাপ্ত কাজের পুরনো ফাইল (এই মুহূর্তে কোনো ফাইল ব্যবহারে নেই)
        File src = new File(dir, "src_" + System.currentTimeMillis() + ".mp4");
        try (InputStream in = app.getContentResolver().openInputStream(srcUri);
             OutputStream out = new FileOutputStream(src)) {
            if (in == null) throw new Exception("Couldn't read the video");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        cleanDir(dir, "cam_");           // ক্যামেরার আসল ফাইল কপি হয়ে গেছে, আর লাগবে না

        long dur;
        int w, h, rot;
        Bitmap frame = null;
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(src.getAbsolutePath());
            dur = parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            w = (int) parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            h = (int) parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            rot = (int) parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
            frame = r.getFrameAtTime(Math.min(1_000_000L, dur * 500L), MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) frame = r.getFrameAtTime(0);
        } catch (RuntimeException e) {
            //noinspection ResultOfMethodCallIgnored
            src.delete();
            throw new Exception("Not a video file or it couldn't be opened");
        } finally {
            r.release();
        }

        if (dur <= 0 || w <= 0 || h <= 0) {
            //noinspection ResultOfMethodCallIgnored
            src.delete();
            throw new Exception("Couldn't read the video");
        }
        if (dur < MIN_MS) {
            //noinspection ResultOfMethodCallIgnored
            src.delete();
            throw new Exception("Video must be at least 3 seconds");
        }
        if (rot == 90 || rot == 270) { int t = w; w = h; h = t; }

        File use = src;
        boolean cut = false;
        if (dur > MAX_MS) {
            File dst = new File(dir, "cut_" + System.currentTimeMillis() + ".mp4");
            try {
                trim(src, dst, MAX_MS * 1000L, rot);
            } catch (Throwable e) {
                //noinspection ResultOfMethodCallIgnored
                dst.delete();
                //noinspection ResultOfMethodCallIgnored
                src.delete();
                throw new Exception("Couldn't trim the video to 1:20 (unsupported format)");
            }
            //noinspection ResultOfMethodCallIgnored
            src.delete();
            use = dst;
            dur = MAX_MS;
            cut = true;
        }

        if (frame == null) {
            //noinspection ResultOfMethodCallIgnored
            use.delete();
            throw new Exception("Couldn't create the thumbnail");
        }
        // থাম্বনেইল: লম্বা দিক সর্বোচ্চ 720px, JPEG
        int longSide = Math.max(frame.getWidth(), frame.getHeight());
        if (longSide > 720) {
            float s = 720f / longSide;
            Bitmap sc = Bitmap.createScaledBitmap(frame,
                    Math.max(1, Math.round(frame.getWidth() * s)),
                    Math.max(1, Math.round(frame.getHeight() * s)), true);
            if (sc != frame) frame.recycle();
            frame = sc;
        }
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        frame.compress(Bitmap.CompressFormat.JPEG, 85, bo);

        if (g != gen) {   // এর মধ্যে বাতিল হয়ে গেছে
            //noinspection ResultOfMethodCallIgnored
            use.delete();
            throw new Exception("Cancelled");
        }
        file = use;
        thumb = frame;
        thumbBytes = bo.toByteArray();
        thumbDirty = true;
        durationMs = dur;
        width = w;
        height = h;
        trimmed = cut;
    }

    private static void cleanDir(File dir, String... prefixes) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            for (String p : prefixes) {
                if (f.getName().startsWith(p)) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                    break;
                }
            }
        }
    }

    /**
     * কমপ্রেস (12% → 40%) তারপর fast start। ছোট/হালকা ভিডিও হলে শুধু fast start।
     * কমপ্রেস ব্যর্থ হলে মূল ফাইল দিয়েই এগোয়, আপলোড আটকায় না।
     */
    private static void packVideo(final int g) {
        final File in = file;
        if (in == null) { fail(g, "Video not found"); return; }
        if (!VideoCompressor.needsCompress(in, durationMs, width, height)) {
            finishPack(g, in);
            return;
        }
        final File out = new File(in.getParentFile(), "cmp_" + System.currentTimeMillis() + ".mp4");
        VideoCompressor.run(app, in, out, width, height,
                pct -> {
                    if (g != gen) return;
                    actual = Math.max(actual, 12 + (int) (28L * pct / 100L));
                    changed();
                },
                res -> {
                    if (g != gen) {
                        if (res != null) //noinspection ResultOfMethodCallIgnored
                            res.delete();
                        return;
                    }
                    if (res != null && res.length() > 0 && res.length() < in.length()) {
                        //noinspection ResultOfMethodCallIgnored
                        in.delete();
                        finishPack(g, res);
                    } else {
                        if (res != null) //noinspection ResultOfMethodCallIgnored
                            res.delete();
                        finishPack(g, in);
                    }
                });
    }

    private static void finishPack(final int g, final File f) {
        WORK.execute(() -> {
            final File fs = VideoCompressor.fastStart(f);
            MAIN.post(() -> {
                if (g != gen) {
                    //noinspection ResultOfMethodCallIgnored
                    fs.delete();
                    return;
                }
                file = fs;
                packed = true;
                actual = Math.max(actual, 40);
                changed();
                step(g);
            });
        });
    }

    /** re-encode ছাড়াই (কোয়ালিটি অপরিবর্তিত) প্রথম maxUs মাইক্রোসেকেন্ড কেটে নতুন MP4 */
    private static void trim(File src, File dst, long maxUs, int rotation) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        MediaMuxer mux = null;
        boolean started = false;
        try {
            ex.setDataSource(src.getAbsolutePath());
            mux = new MediaMuxer(dst.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int n = ex.getTrackCount();
            int[] map = new int[n];
            int maxIn = 256 * 1024;
            boolean hasVideo = false;
            for (int i = 0; i < n; i++) {
                map[i] = -1;
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime == null) continue;
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    map[i] = mux.addTrack(f);
                    ex.selectTrack(i);
                    if (mime.startsWith("video/")) hasVideo = true;
                    if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        maxIn = Math.max(maxIn, f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
                    }
                }
            }
            if (!hasVideo) throw new Exception("no video track");
            if (rotation == 90 || rotation == 180 || rotation == 270) mux.setOrientationHint(rotation);
            mux.start();
            started = true;

            ByteBuffer buf = ByteBuffer.allocate(Math.max(maxIn, 2 * 1024 * 1024));
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (true) {
                buf.clear();
                int size = ex.readSampleData(buf, 0);
                if (size < 0) break;
                long t = ex.getSampleTime();
                if (t < 0 || t > maxUs) break;
                int ti = ex.getSampleTrackIndex();
                if (ti >= 0 && ti < map.length && map[ti] >= 0) {
                    info.set(0, size, t,
                            (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                                    ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                    mux.writeSampleData(map[ti], buf, info);
                }
                ex.advance();
            }
        } finally {
            try { if (mux != null && started) mux.stop(); } catch (Throwable ignored) { }
            try { if (mux != null) mux.release(); } catch (Throwable ignored) { }
            ex.release();
        }
    }

    private static long parseLong(String s) {
        try { return s == null ? 0 : Long.parseLong(s.trim()); } catch (Exception e) { return 0; }
    }

    // ------------------------------------------------------------ ডেটাবেসে সেভ (মেইন থ্রেড)

    private static void insert(final int g) {
        if (g != gen || meta == null) return;
        if (thumbDirty && thumbBytes != null) {
            // থাম্বনেইল এখানেই আপলোড হয় (শেষ বেছে নেওয়াটা) — তাই R2-তে বাড়তি/পুরনো ফাইল পড়ে থাকে না
            state = State.UPLOADING;
            actual = Math.max(actual, 96);
            changed();
            String tn = System.currentTimeMillis() + "_thumb.jpg";
            Media.upload(thumbBytes, "post", tn, "image/jpeg", (key, err) -> {
                if (g != gen) return;
                if (err != null) { fail(g, err); return; }
                thumbKey = key;
                thumbDirty = false;
                doInsert(g);
            });
            return;
        }
        doInsert(g);
    }

    private static void doInsert(final int g) {
        if (g != gen || meta == null) return;
        final String me = SupabaseRest.uid();
        if (me == null) { fail(g, "Not logged in"); return; }
        state = State.UPLOADING;
        actual = Math.max(actual, 97);
        changed();
        JSONObject row;
        try {
            row = new JSONObject(meta.toString());
            row.put("user_id", me)
               .put("video_key", videoKey)
               .put("thumb_key", thumbKey)
               .put("duration_ms", durationMs)
               .put("width", width)
               .put("height", height);
            row.remove("group_ids");     // এগুলো shorts টেবিলে যায় না — ফিড-পোস্ট বানানোর সময় ব্যবহার হয়
            row.remove("collab_ids");
            String sched = row.optString("scheduled_at", "");
            if (sched.isEmpty()) {
                row.remove("scheduled_at");
                row.put("status", "active");
            } else {
                row.put("status", "scheduled");
            }
        } catch (Exception e) {
            fail(g, "Couldn't submit the form");
            return;
        }
        SupabaseRest.call("POST", "/rest/v1/shorts", row.toString(), "return=minimal", true, r -> {
            if (g != gen) return;
            if (!r.ok()) {
                String body = r.body == null ? "" : r.body;
                if (body.contains("weekly_limit")) {
                    // সার্ভারের সীমা: আর রিট্রাই অর্থহীন — কাজ বন্ধ
                    toast("You've uploaded 8 Shorts this week");
                    reset();
                    changed();
                    return;
                }
                fail(g, SupabaseRest.errorText(r));
                return;
            }
            // ফিড/প্রোফাইলে "X uploaded a short" পোস্ট বানিয়ে তারপর শেষ (যাতে হোম রিফ্রেশে পোস্টটা পাওয়া যায়)
            announce(g, me, () -> finish(me));
        });
    }

    /**
     * আপলোড শেষে নিউজ ফিড ও প্রোফাইলের জন্য একটি পোস্ট: "X uploaded a short." + থাম্বনেইল (+ শিরোনাম)।
     * এটা সাধারণ পোস্টই, তাই পোস্ট/ছবির মতো একই 3-dot menu পায়। Schedule করা Short-এ পোস্ট হয় না।
     */
    private static void announce(final int g, final String me, final Runnable done) {
        final String tk = thumbKey;
        final JSONObject m = meta;
        if (tk == null || m == null || !m.optString("scheduled_at", "").isEmpty()) { done.run(); return; }
        final String aud = m.optString("audience", "public");
        final String privacy = "friends".equals(aud) ? "friends" : "only_me".equals(aud) ? "me" : "public";
        final String title = m.optString("title", "");
        SupabaseRest.call("GET", "/rest/v1/profiles?id=eq." + me + "&select=name", null, null, true, r -> {
            if (g != gen) return;
            String name = "";
            if (r.ok()) {
                try {
                    JSONArray a = new JSONArray(r.body);
                    if (a.length() > 0 && !a.getJSONObject(0).isNull("name")) {
                        name = a.getJSONObject(0).optString("name", "");
                    }
                } catch (Exception ignored) { }
            }
            final String nm = name;
            try {
                JSONObject body = new JSONObject()
                        .put("user_id", me)
                        .put("text", title)
                        .put("privacy", privacy)
                        .put("image_key", tk)
                        .put("activity", ProfilePhotoPost.MARK + SHORT_PHRASE);
                if (!name.isEmpty()) body.put("author_name", name);
                putMusic(body, m);
                SupabaseRest.call("POST", "/rest/v1/posts", body.toString(), "return=representation", true, pr -> {
                    if (g != gen) return;
                    String postId = null;
                    if (pr.ok()) {
                        try {
                            JSONArray arr = new JSONArray(pr.body);
                            if (arr.length() > 0) {
                                postId = arr.getJSONObject(0).optString("id", null);
                                PostImage.put(postId, tk, null);
                                PostMusic.put(postId, musicInfo(m));
                            }
                        } catch (Exception ignored) { }
                    }
                    extras(g, postId, me, nm, title, tk, m, done);   // Collaborator + গ্রুপ পোস্ট
                });
            } catch (Exception e) {
                done.run();
            }
        });
    }

    /** meta-তে গান থাকলে পোস্টের music_* কলামে বসায় (PostMusic / get_post_music এগুলোই পড়ে) */
    private static void putMusic(JSONObject body, JSONObject m) throws Exception {
        String url = m.optString("music_url", "");
        if (url.isEmpty()) return;
        body.put("music_title", m.optString("music_title", ""));
        body.put("music_artist", m.optString("music_artist", ""));
        body.put("music_cover", m.optString("music_cover", ""));
        body.put("music_url", url);
        body.put("music_start", m.optInt("music_start", 0));
        body.put("music_len", m.optInt("music_len", MusicTrimActivity.CLIP_MS));
    }

    private static PostMusic.Info musicInfo(JSONObject m) {
        String url = m.optString("music_url", "");
        if (url.isEmpty()) return null;
        PostMusic.Info i = new PostMusic.Info();
        i.title = m.optString("music_title", "");
        i.artist = m.optString("music_artist", "");
        i.cover = m.optString("music_cover", "");
        i.url = url;
        i.start = m.optInt("music_start", 0);
        i.len = m.optInt("music_len", MusicTrimActivity.CLIP_MS);
        return i;
    }

    /** Short-এর ফিড-পোস্ট হয়ে গেলে: ১) Collaborator রিকোয়েস্ট ২) বাছা প্রতিটি গ্রুপে একটি করে পোস্ট */
    private static void extras(final int g, String postId, String me, String name, String title,
                               final String tk, JSONObject m, final Runnable done) {
        JSONArray cids = m.optJSONArray("collab_ids");
        JSONArray gids = m.optJSONArray("group_ids");
        final int groupCount = gids == null ? 0 : gids.length();
        final int[] left = {1 + groupCount};
        final Runnable one = () -> { if (--left[0] == 0) done.run(); };

        if (postId != null && cids != null && cids.length() > 0) {
            try {
                JSONObject b = new JSONObject().put("p_post_id", postId).put("p_collabs", cids);
                SupabaseRest.call("POST", "/rest/v1/rpc/attach_short_collabs", b.toString(), null, true, r -> {
                    if (g == gen && !r.ok()) toast("Short posted, but collaborator request failed: " + SupabaseRest.errorText(r));
                    one.run();
                });
            } catch (Exception e) { one.run(); }
        } else {
            one.run();
        }

        for (int i = 0; i < groupCount; i++) {
            String gid = gids.optString(i, "");
            if (gid.isEmpty()) { one.run(); continue; }
            try {
                JSONObject body = new JSONObject()
                        .put("user_id", me)
                        .put("text", title)
                        .put("privacy", "public")
                        .put("group_id", gid)
                        .put("image_key", tk)
                        .put("activity", ProfilePhotoPost.MARK + SHORT_PHRASE);
                if (!name.isEmpty()) body.put("author_name", name);
                putMusic(body, m);
                SupabaseRest.call("POST", "/rest/v1/posts", body.toString(), "return=representation", true, pr -> {
                    if (pr.ok()) {
                        try {
                            JSONArray arr = new JSONArray(pr.body);
                            if (arr.length() > 0) PostImage.put(arr.getJSONObject(0).optString("id", null), tk, null);
                        } catch (Exception ignored) { }
                    }
                    one.run();
                });
            } catch (Exception e) { one.run(); }
        }
    }

    private static void finish(String me) {
        actual = 100;
        state = State.DONE;
        shared = false;
        //noinspection ResultOfMethodCallIgnored
        if (file != null) file.delete();
        file = null;
        changed();
        toast("Shorts uploaded successfully");
        try {
            JSONObject n = new JSONObject()
                    .put("user_id", me)
                    .put("actor_id", me)
                    .put("type", "short_uploaded")
                    .put("message", "Shorts uploaded successfully")
                    .put("is_read", false);
            SupabaseRest.call("POST", "/rest/v1/notifications", n.toString(), "return=minimal", true, r -> { });
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------ সার্ভার থেকে পড়া

    /** শেষ ৭ দিনে আমার কয়টি Short; জানা না গেলে -1 (সার্ভার তখনও সীমা মানায়) */
    static void weeklyCount(final IntCb cb) {
        String me = SupabaseRest.uid();
        if (me == null) { cb.on(-1); return; }
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        String since = f.format(new Date(System.currentTimeMillis() - 7L * 24 * 3600 * 1000));
        SupabaseRest.call("GET", "/rest/v1/shorts?user_id=eq." + me + "&created_at=gte." + since
                + "&status=neq.deleted&select=id&limit=50", null, null, true, r -> {
            if (!r.ok()) { cb.on(-1); return; }
            try { cb.on(new JSONArray(r.body).length()); } catch (Exception e) { cb.on(-1); }
        });
    }

    /** Home/Profile-এর "+ Short" থেকে: সীমা ও চলমান আপলোড দেখে তবেই তৈরির পর্দা খোলে */
    static void openCreate(final android.app.Activity a) {
        if (isSubmitted()) {
            Toast.makeText(a, "Previous Short is still uploading, please wait", Toast.LENGTH_SHORT).show();
            return;
        }
        weeklyCount(c -> {
            if (c >= WEEKLY_LIMIT) {
                Toast.makeText(a, "You've uploaded 8 Shorts this week. Delete one to upload again.",
                        Toast.LENGTH_LONG).show();
            } else {
                a.startActivity(new android.content.Intent(a, ShortCreateActivity.class));
            }
        });
    }

    /** নিজের একটি Short মুছে ফেলে; সফল হলে সাপ্তাহিক গণনা থেকে বাদ পড়ে (cb: error == null মানে সফল) */
    static void delete(String id, final String thumbKey, final Res cb) {
        final String me = SupabaseRest.uid();
        if (me == null || id == null || id.isEmpty()) { cb.on("Not logged in"); return; }
        SupabaseRest.call("DELETE", "/rest/v1/shorts?id=eq." + id + "&user_id=eq." + me,
                null, "return=minimal", true, r -> {
            if (!r.ok()) { cb.on(SupabaseRest.errorText(r)); return; }
            if (thumbKey != null && !thumbKey.isEmpty()) {
                // এই Short-এর "uploaded a short" ফিড-পোস্টও মুছে যায়
                try {
                    SupabaseRest.call("DELETE", "/rest/v1/posts?user_id=eq." + me
                            + "&image_key=eq." + java.net.URLEncoder.encode(thumbKey, "UTF-8")
                            + "&activity=eq." + java.net.URLEncoder.encode(ProfilePhotoPost.MARK + SHORT_PHRASE, "UTF-8"),
                            null, "return=minimal", true, pr -> { });
                } catch (Exception ignored) { }
            }
            cb.on(null);
        });
    }

    interface Res { void on(String error); }

    /** কোনো ইউজারের Shorts (নতুন আগে) প্রোফাইলের গ্রিডের জন্য; কে কী দেখবে তা সার্ভারের RLS ঠিক করে */
    static void loadFor(final String uid, final ItemsCb cb0) {
        if (uid == null) { cb0.on(new ArrayList<>()); return; }
        // পিন করা Short (Pin to your reels) সবার আগে
        final ItemsCb cb = list -> PinnedReels.load(uid, pins -> cb0.on(PinnedReels.order(list, pins)));
        final List<String[]> rows = new ArrayList<>();               // {created_at, index}
        final List<ProfileShorts.Item> items = new ArrayList<>();
        SupabaseRest.call("GET", "/rest/v1/shorts?user_id=eq." + uid
                + "&status=neq.deleted&select=" + ShortStore.SELECT + ",created_at&order=created_at.desc&limit=60",
                null, null, true, r -> {
            if (r.ok()) addShortRows(r.body, rows, items);
            // আমি যে Shorts-এ Collaborator হিসেবে Approve করেছি সেগুলোও (সার্ভার ফাংশন get_collab_shorts)
            try {
                JSONObject b = new JSONObject().put("p_uid", uid);
                SupabaseRest.call("POST", "/rest/v1/rpc/get_collab_shorts", b.toString(), null, true, r2 -> {
                    if (r2.ok()) addShortRows(r2.body, rows, items);
                    cb.on(sorted(rows, items));
                });
            } catch (Exception e) {
                cb.on(sorted(rows, items));
            }
        });
    }

    private static void addShortRows(String body, List<String[]> rows, List<ProfileShorts.Item> items) {
        try {
            JSONArray a = new JSONArray(body);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                String id = o.optString("id", "");
                boolean dup = false;
                for (ProfileShorts.Item x : items) if (x.id.equals(id)) { dup = true; break; }
                if (dup) continue;
                ShortStore.put(o);   // ভিউয়ার ও ফিড-কার্ড একই তথ্য ব্যবহার করে
                ProfileShorts.Item it = new ProfileShorts.Item();
                it.id = id;
                it.thumbKey = o.isNull("thumb_key") ? null : o.optString("thumb_key", null);
                it.views = o.optLong("views", 0);
                rows.add(new String[]{o.optString("created_at", ""), String.valueOf(items.size())});
                items.add(it);
            }
        } catch (Exception ignored) { }
    }

    private static List<ProfileShorts.Item> sorted(List<String[]> rows, List<ProfileShorts.Item> items) {
        List<String[]> copy = new ArrayList<>(rows);
        java.util.Collections.sort(copy, (x, y) -> y[0].compareTo(x[0]));   // নতুন আগে
        List<ProfileShorts.Item> out = new ArrayList<>();
        for (String[] r : copy) out.add(items.get(Integer.parseInt(r[1])));
        return out;
    }
}
