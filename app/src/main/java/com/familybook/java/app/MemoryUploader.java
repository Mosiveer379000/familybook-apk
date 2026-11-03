package com.familybook.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Memory আপলোড (ব্যাকগ্রাউন্ডে): ভিডিও কপি → থাম্বনেইল → ভিডিও আপলোড → থাম্বনেইল আপলোড → memories টেবিলে insert।
 * মেয়াদ ২৪ ঘণ্টা (expires_at ডাটাবেসের ডিফল্ট)। বাকি সবকিছু MemorySettingsActivity থেকে meta-তে আসে।
 */
final class MemoryUploader {
    private MemoryUploader() { }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();

    /** আপলোড চলছে কি না (একসাথে একটাই) */
    static volatile boolean busy = false;

    static void post(final Context ctx, final Uri video, final JSONObject meta) {
        final Context app = ctx.getApplicationContext();
        final String me = SupabaseRest.uid();
        if (me == null) { toast(app, "Not logged in"); return; }
        if (busy) { toast(app, "Previous Memory is still uploading"); return; }
        busy = true;
        toast(app, "Sharing your Memory…");

        WORK.execute(() -> {
            File copy = null;
            try {
                copy = new File(app.getCacheDir(), "memory_" + System.currentTimeMillis() + ".mp4");
                try (InputStream in = app.getContentResolver().openInputStream(video);
                     OutputStream out = new FileOutputStream(copy)) {
                    if (in == null) throw new Exception("Couldn't read the video");
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }

                long dur = 0;
                byte[] thumb = null;
                try {
                    MediaMetadataRetriever mr = new MediaMetadataRetriever();
                    mr.setDataSource(copy.getAbsolutePath());
                    String d = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                    if (d != null) dur = Long.parseLong(d);
                    Bitmap f = mr.getFrameAtTime(300_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    if (f == null) f = mr.getFrameAtTime(0L);
                    mr.release();
                    if (f != null) {
                        int max = 540;
                        float s = Math.min(1f, max / (float) Math.max(f.getWidth(), f.getHeight()));
                        Bitmap sc = s < 1f ? Bitmap.createScaledBitmap(f,
                                Math.max(1, Math.round(f.getWidth() * s)),
                                Math.max(1, Math.round(f.getHeight() * s)), true) : f;
                        ByteArrayOutputStream bo = new ByteArrayOutputStream();
                        sc.compress(Bitmap.CompressFormat.JPEG, 82, bo);
                        thumb = bo.toByteArray();
                    }
                } catch (Throwable ignored) { }

                final File vf = copy;
                final long fdur = dur;
                final byte[] fth = thumb;
                MAIN.post(() -> uploadVideo(app, me, vf, fth, fdur, meta));
            } catch (Throwable e) {
                final String m = e.getMessage() == null ? "Couldn't prepare the Memory" : e.getMessage();
                finish(app, copy, m);
            }
        });
    }

    private static void uploadVideo(final Context app, final String me, final File vf, final byte[] thumb,
                                    final long dur, final JSONObject meta) {
        String name = System.currentTimeMillis() + "_" + (1000 + new java.util.Random().nextInt(9000)) + ".mp4";
        Media.uploadFile(vf, "video", name, "video/mp4", (vkey, err) -> {
            if (err != null || vkey == null) { finish(app, vf, err == null ? "Upload failed" : err); return; }
            if (thumb == null) { insert(app, me, vf, vkey, null, dur, meta); return; }
            String tn = System.currentTimeMillis() + "_mem.jpg";
            Media.upload(thumb, "post", tn, "image/jpeg", (tkey, terr) ->
                    insert(app, me, vf, vkey, terr == null ? tkey : null, dur, meta));
        }, null);
    }

    private static void insert(final Context app, String me, final File vf, String vkey, String tkey,
                               long dur, JSONObject meta) {
        try {
            JSONObject row = new JSONObject();
            row.put("user_id", me)
               .put("video_key", vkey)
               .put("duration_ms", dur)
               .put("location", meta.optString("location", ""))
               .put("feeling", meta.optString("feeling", ""))
               .put("tag_ids", meta.optJSONArray("tag_ids") == null ? new JSONArray() : meta.optJSONArray("tag_ids"))
               .put("tag_names", meta.optJSONArray("tag_names") == null ? new JSONArray() : meta.optJSONArray("tag_names"))
               .put("privacy", meta.optString("privacy", "public"));
            if (tkey != null) row.put("thumb_key", tkey);
            SupabaseRest.call("POST", "/rest/v1/memories", row.toString(), "return=minimal", true, r -> {
                if (r.ok()) finish(app, vf, null);
                else finish(app, vf, SupabaseRest.errorText(r));
            });
        } catch (Exception e) {
            finish(app, vf, "Couldn't share the Memory");
        }
    }

    private static void finish(final Context app, final File tmp, final String error) {
        MAIN.post(() -> {
            busy = false;
            try { if (tmp != null) tmp.delete(); } catch (Exception ignored) { }
            toast(app, error == null ? "Memory shared" : error);
        });
    }

    private static void toast(final Context app, final String m) {
        MAIN.post(() -> Toast.makeText(app, m, Toast.LENGTH_SHORT).show());
    }
}
