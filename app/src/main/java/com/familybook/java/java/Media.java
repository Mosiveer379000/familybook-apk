package com.familybook.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.Buffer;
import okio.BufferedSink;
import okio.ForwardingSink;
import okio.Okio;
import okio.Sink;

/**
 * Cloudflare R2-তে ছবি আপলোড ও দেখার সহায়ক।
 * R2-এর চাবি app-এ থাকে না; Supabase Edge Function সাময়িক link দেয়।
 * সব callback main thread-এ আসে। error == null মানে সফল।
 */
public final class Media {

    public interface Cb { void on(String value, String error); }

    private static final OkHttpClient HTTP = new OkHttpClient();
    private static final ExecutorService IO = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** Supabase Edge Function-এর আসল slug (Dashboard-এ function-এর URL-এর শেষ অংশ) */
    private static final String FUNCTION_SLUG = "clever-worker";

    private static final ExecutorService DISK = Executors.newSingleThreadExecutor();
    private static File imgDir;
    private static final long IMG_CACHE_MAX = 120L * 1024 * 1024;

    private Media() { }

    /** আপলোডের অগ্রগতি: sent/total বাইট (main thread-এ) */
    public interface Progress { void on(long sent, long total); }

    /** FamilyBookApp.onCreate-এ একবার: দেখা ছবি ফোনে জমিয়ে রাখে, তাই অফলাইনেও পুরনো ছবি দেখা যায় */
    public static void init(Context app) {
        imgDir = new File(app.getApplicationContext().getCacheDir(), "imgcache");
        //noinspection ResultOfMethodCallIgnored
        imgDir.mkdirs();
        DISK.execute(Media::trimDisk);
    }

    private static File diskFile(String key) {
        if (imgDir == null || key == null) return null;
        try {
            byte[] h = MessageDigest.getInstance("SHA-1").digest(key.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return new File(imgDir, sb.toString());
        } catch (Exception e) {
            return null;
        }
    }

    /** যেসব R2 key-র ফাইল আসলে GIF (ফাইলের হেডার দেখে চেনা — key-র নামের এক্সটেনশনের উপর নির্ভর নয়) */
    private static final java.util.Set<String> GIF_KEYS =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());

    public static boolean isGifKey(String key) {
        return key != null && GIF_KEYS.contains(key);
    }

    private static void sniffGif(String key, byte[] d) {
        if (key != null && d != null && d.length > 6 && d[0] == 'G' && d[1] == 'I' && d[2] == 'F') GIF_KEYS.add(key);
    }

    private static Bitmap readDisk(String key) {
        File f = diskFile(key);
        if (f == null || !f.exists()) return null;
        try {
            try (java.io.FileInputStream hi = new java.io.FileInputStream(f)) {
                byte[] head = new byte[8];
                if (hi.read(head) >= 7) sniffGif(key, head);
            } catch (Exception ignored) { }
            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (b != null) {
                //noinspection ResultOfMethodCallIgnored
                f.setLastModified(System.currentTimeMillis());
            }
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void writeDisk(final String key, final byte[] bytes) {
        final File f = diskFile(key);
        if (f == null || bytes == null) return;
        DISK.execute(() -> {
            try (FileOutputStream o = new FileOutputStream(f)) {
                o.write(bytes);
            } catch (Exception e) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        });
    }

    /** ক্যাশ বড় হলে সবচেয়ে পুরনো ফাইল আগে মুছে ফেলে */
    private static void trimDisk() {
        if (imgDir == null) return;
        File[] fs = imgDir.listFiles();
        if (fs == null) return;
        long total = 0;
        for (File f : fs) total += f.length();
        if (total <= IMG_CACHE_MAX) return;
        java.util.Arrays.sort(fs, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File f : fs) {
            if (total <= IMG_CACHE_MAX * 3 / 4) break;
            long len = f.length();
            if (f.delete()) total -= len;
        }
    }

    /** RequestBody-কে মুড়ে কতটা পাঠানো হলো জানায় */
    private static final class ProgressBody extends RequestBody {
        private final RequestBody inner;
        private final Progress pr;

        ProgressBody(RequestBody inner, Progress pr) { this.inner = inner; this.pr = pr; }

        @Override public MediaType contentType() { return inner.contentType(); }

        @Override public long contentLength() throws java.io.IOException { return inner.contentLength(); }

        @Override public void writeTo(BufferedSink sink) throws java.io.IOException {
            final long total = contentLength();
            Sink counting = new ForwardingSink(sink) {
                long sent;
                @Override public void write(Buffer source, long byteCount) throws java.io.IOException {
                    super.write(source, byteCount);
                    sent += byteCount;
                    final long s = sent;
                    MAIN.post(() -> pr.on(s, total));
                }
            };
            BufferedSink buffered = Okio.buffer(counting);
            inner.writeTo(buffered);
            buffered.flush();
        }
    }

    /** kind: post | avatar | cover | chat | doc | nid | birth_cert | gift | video | audio. সফল হলে value = R2 key.
     *  ছবি হলে আপলোডের আগে স্বয়ংক্রিয়ভাবে lossless কমপ্রেস হয় (ImageOptimizer) — সব ফরম্যাটে। */
    public static void upload(final byte[] data, final String kind, final String name,
                              final String contentType, final Cb cb) {
        upload(data, kind, name, contentType, cb, null);
    }

    public static void upload(final byte[] data, final String kind, final String name,
                              final String contentType, final Cb cb, final Progress pr) {
        final String declared = contentType == null ? "application/octet-stream" : contentType;
        if (!declared.startsWith("image/")) { doUpload(data, kind, name, declared, cb, pr); return; }
        IO.execute(() -> {
            byte[] bytes = data;
            String nm = name, type = declared;
            try {
                boolean docKind = "doc".equals(kind) || "birth_cert".equals(kind) || "nid".equals(kind)
                        || "document".equals(kind) || "identity".equals(kind);
                ImageOptimizer.Result r = ImageOptimizer.optimize(data, declared, !docKind);
                bytes = r.bytes;
                type = r.mime;
                if (!"bin".equals(r.ext)) {
                    int dot = nm.lastIndexOf('.');
                    nm = (dot > 0 ? nm.substring(0, dot) : nm) + "." + r.ext;
                }
            } catch (Throwable ignored) { }
            final byte[] fb = bytes;
            final String fn = nm, ft = type;
            MAIN.post(() -> doUpload(fb, kind, fn, ft, cb, pr));
        });
    }

    private static void doUpload(final byte[] data, final String kind, final String name,
                                 final String contentType, final Cb cb, final Progress pr) {
        final String type = contentType == null ? "application/octet-stream" : contentType;
        JSONObject body = new JSONObject();
        try {
            body.put("action", "upload").put("kind", kind).put("name", name).put("contentType", type);
        } catch (Exception e) {
            cb.on(null, "আপলোড শুরু করা যায়নি");
            return;
        }
        callFunction(body, (json, err) -> {
            if (err != null) { cb.on(null, err); return; }
            final String key = json.optString("key", "");
            final String uploadUrl = json.optString("uploadUrl", "");
            if (key.isEmpty() || uploadUrl.isEmpty()) { cb.on(null, "সার্ভার থেকে link পাওয়া যায়নি"); return; }
            IO.execute(() -> {
                try {
                    RequestBody rb = RequestBody.create(data, MediaType.parse(type));
                    if (pr != null) rb = new ProgressBody(rb, pr);
                    Request req = new Request.Builder().url(uploadUrl).put(rb).build();
                    try (Response res = HTTP.newCall(req).execute()) {
                        final boolean ok = res.isSuccessful();
                        final int code = res.code();
                        MAIN.post(() -> {
                            if (ok) cb.on(key, null);
                            else cb.on(null, "আপলোড ব্যর্থ (কোড " + code + ")");
                        });
                    }
                } catch (Exception e) {
                    MAIN.post(() -> cb.on(null, "নেটওয়ার্ক সমস্যা, আবার চেষ্টা করুন"));
                }
            });
        });
    }

    /**
     * বড় ফাইল (ভিডিও) ফাইল থেকে সরাসরি স্ট্রিম করে আপলোড — পুরো ফাইল মেমরিতে তোলে না।
     * kind: "video"। সফল হলে value = R2 key। pr দিলে (পাঠানো বাইট, মোট বাইট) জানায়।
     */
    public static void uploadFile(final File file, final String kind, final String name,
                                  final String contentType, final Cb cb, final Progress pr) {
        final String type = contentType == null ? "application/octet-stream" : contentType;
        JSONObject body = new JSONObject();
        try {
            body.put("action", "upload").put("kind", kind).put("name", name).put("contentType", type);
        } catch (Exception e) {
            cb.on(null, "আপলোড শুরু করা যায়নি");
            return;
        }
        callFunction(body, (json, err) -> {
            if (err != null) { cb.on(null, err); return; }
            final String key = json.optString("key", "");
            final String uploadUrl = json.optString("uploadUrl", "");
            if (key.isEmpty() || uploadUrl.isEmpty()) { cb.on(null, "সার্ভার থেকে link পাওয়া যায়নি"); return; }
            IO.execute(() -> {
                try {
                    RequestBody rb = RequestBody.create(file, MediaType.parse(type));
                    if (pr != null) rb = new ProgressBody(rb, pr);
                    Request req = new Request.Builder().url(uploadUrl).put(rb).build();
                    // বড় ফাইলের জন্য লেখার timeout বাড়ানো client
                    OkHttpClient big = HTTP.newBuilder()
                            .writeTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                            .build();
                    try (Response res = big.newCall(req).execute()) {
                        final boolean ok = res.isSuccessful();
                        final int code = res.code();
                        MAIN.post(() -> {
                            if (ok) cb.on(key, null);
                            else cb.on(null, "আপলোড ব্যর্থ (কোড " + code + ")");
                        });
                    }
                } catch (Exception e) {
                    MAIN.post(() -> cb.on(null, "নেটওয়ার্ক সমস্যা, আবার চেষ্টা করুন"));
                }
            });
        });
    }

    /** একটি R2 key দেখার জন্য সাময়িক link (প্রায় ১০ মিনিট) */
    private static final java.util.concurrent.ConcurrentHashMap<String, Object[]> URL_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();   // key -> {url, মেয়াদ-শেষের সময়}

    /** ক্যাশ করা লিংক বাতিল (প্লে ব্যর্থ হলে নতুন লিংক আনতে) */
    public static void forgetUrl(String key) {
        if (key != null) URL_CACHE.remove(key);
    }

    public static void readUrl(final String key, final Cb cb) {
        Object[] hit = key == null ? null : URL_CACHE.get(key);
        if (hit != null && System.currentTimeMillis() < (Long) hit[1]) {
            cb.on((String) hit[0], null);
            return;
        }
        JSONObject body = new JSONObject();
        try {
            body.put("action", "read").put("key", key);
        } catch (Exception e) {
            cb.on(null, "দেখা যাচ্ছে না");
            return;
        }
        callFunction(body, (json, err) -> {
            if (err != null) { cb.on(null, err); return; }
            String url = json.optString("url", "");
            if (!url.isEmpty()) URL_CACHE.put(key, new Object[]{url, System.currentTimeMillis() + 8 * 60 * 1000L});
            cb.on(url.isEmpty() ? null : url, url.isEmpty() ? "দেখা যাচ্ছে না" : null);
        });
    }

    public interface BitmapCb { void on(Bitmap bm); }

    public static void loadBitmap(String key, BitmapCb cb) {
        loadBitmap(key, cb, null);
    }

    public static void loadBitmap(final String key, final BitmapCb cb, final Cb errCb) {
        // আগে ফোনের ক্যাশ: থাকলে নেট ছাড়াই দেখায়
        DISK.execute(() -> {
            final Bitmap local = readDisk(key);
            MAIN.post(() -> {
                if (local != null) cb.on(local);
                else loadBitmapNet(key, cb, errCb);
            });
        });
    }

    private static void loadBitmapNet(final String key, final BitmapCb cb, final Cb errCb) {
        readUrl(key, (url, err) -> {
            if (err != null || url == null) {
                if (errCb != null) errCb.on(null, err == null ? "ছবির লিংক পাওয়া যায়নি" : err);
                return;
            }
            IO.execute(() -> {
                try (Response res = HTTP.newCall(new Request.Builder().url(url).build()).execute()) {
                    if (!res.isSuccessful() || res.body() == null) {
                        final int code = res.code();
                        if (errCb != null) MAIN.post(() -> errCb.on(null, "ছবি আনা যায়নি (কোড " + code + ")"));
                        return;
                    }
                    byte[] bytes = res.body().bytes();
                    sniffGif(key, bytes);
                    final Bitmap bm = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    if (bm != null) {
                        writeDisk(key, bytes);
                        MAIN.post(() -> cb.on(bm));
                    }
                } catch (Exception e) {
                    if (errCb != null) MAIN.post(() -> errCb.on(null, "নেটওয়ার্ক সমস্যা"));
                }
            });
        });
    }

    public interface BytesCb { void on(byte[] bytes, String error); }

    /** একটি ছবির আসল bytes আনে (R2 key বা পুরনো http লিংক); callback main thread-এ */
    public static void loadBytes(final String key, final BytesCb cb) {
        if (key != null && key.startsWith("http")) {
            fetchBytes(key, cb);
            return;
        }
        readUrl(key, (url, err) -> {
            if (err != null || url == null) {
                cb.on(null, err == null ? "ছবির লিংক পাওয়া যায়নি" : err);
                return;
            }
            fetchBytes(url, cb);
        });
    }

    private static void fetchBytes(final String url, final BytesCb cb) {
        IO.execute(() -> {
            try (Response res = HTTP.newCall(new Request.Builder().url(url).build()).execute()) {
                if (!res.isSuccessful() || res.body() == null) {
                    final int code = res.code();
                    MAIN.post(() -> cb.on(null, "ছবি আনা যায়নি (কোড " + code + ")"));
                    return;
                }
                final byte[] bytes = res.body().bytes();
                MAIN.post(() -> cb.on(bytes, null));
            } catch (Exception e) {
                MAIN.post(() -> cb.on(null, "নেটওয়ার্ক সমস্যা"));
            }
        });
    }

    private interface JsonCb { void on(JSONObject json, String error); }

    private static void callFunction(JSONObject body, JsonCb cb) {
        SupabaseRest.call("POST", "/functions/v1/" + FUNCTION_SLUG, body.toString(), null, true, r -> {
            JSONObject json;
            try {
                json = new JSONObject(r.body);
            } catch (Exception e) {
                cb.on(null, "সার্ভার থেকে বুঝতে পারা যায়নি");
                return;
            }
            if (!r.ok()) {
                cb.on(null, json.optString("error", "সমস্যা হয়েছে (কোড " + r.code + ")"));
                return;
            }
            cb.on(json, null);
        });
    }
}
