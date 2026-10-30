package com.familybook.app;

import android.content.Context;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.OptIn;
import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.Presentation;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.DefaultEncoderFactory;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;
import androidx.media3.transformer.VideoEncoderSettings;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ভিডিও কমপ্রেস (Media3 Transformer — হার্ডওয়্যার এনকোডার) + "fast start" (moov আগে)।
 *
 *  - এই ফোনে HEVC (H.265) হার্ডওয়্যার এনকোডার থাকলে সেটা (H.264-এর চেয়ে ~৩০-৪০% ছোট), না থাকলে H.264
 *  - বিটরেট ফিক্সড নয়: আউটপুট মাপ × ফ্রেমরেট × bits-per-pixel দিয়ে হিসাব (ছোট ফাইলের জন্য কম bpp)
 *  - আগের ভুল: ≤৭২০p ও ≤৩.৫ Mbps ভিডিও কমপ্রেসই হতো না (ইন্টারনেট থেকে নামানো ভিডিও সাধারণত এমন)।
 *    এখন লক্ষ্য-বিটরেটের চেয়ে বড় হলেই এনকোড হয়
 *  - HEVC এনকোড ব্যর্থ হলে নিজে থেকে H.264-এ আবার চেষ্টা; তাও ব্যর্থ হলে মূল ফাইল যায় (আপলোড আটকায় না)
 *  - fastStart: MP4-এর সূচি (moov) ফাইলের শুরুতে আনে
 */
@OptIn(markerClass = UnstableApi.class)
final class VideoCompressor {

    private static final String TAG = "VideoCompressor";

    /** false করলে সব ফোনে H.264 (সবচেয়ে নিরাপদ প্লেব্যাক); true = ছোট সাইজ */
    static final boolean PREFER_HEVC = false;

    static final int TARGET_SHORT_SIDE = 720;
    static final float ASSUMED_FPS = 30f;
    static final double BPP_H264 = 0.050;             // bits per pixel per frame
    static final double BPP_HEVC = 0.040;
    static final int MIN_BITRATE = 500_000;
    static final int MAX_BITRATE_H264 = 1_500_000;
    static final int MAX_BITRATE_HEVC = 1_400_000;
    static final long TIMEOUT_MS = 5 * 60 * 1000L;    // ৫ মিনিটে শেষ না হলে মূল ফাইল দিয়েই এগোয়

    interface Done { void on(File out); }            // out == null মানে কমপ্রেস হয়নি
    interface Progress { void on(int percent); }     // 0..100

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Transformer current;
    private static Runnable poller, timeout;

    private VideoCompressor() { }

    // ------------------------------------------------------------ হিসাব

    /** আউটপুট মাপ (ছোট দিক সর্বোচ্চ ৭২০, জোড় সংখ্যা) → {width, height} */
    private static int[] outputSize(int w, int h) {
        int ow = w, oh = h;
        if (Math.min(w, h) > TARGET_SHORT_SIDE) {
            float k = (float) TARGET_SHORT_SIDE / Math.min(w, h);
            ow = w >= h ? Math.round(w * k) : TARGET_SHORT_SIDE;
            oh = w >= h ? TARGET_SHORT_SIDE : Math.round(h * k);
        }
        return new int[]{ow & ~1, oh & ~1};
    }

    static int targetBitrate(int w, int h, boolean hevc) {
        int[] s = outputSize(w, h);
        double br = (double) s[0] * s[1] * ASSUMED_FPS * (hevc ? BPP_HEVC : BPP_H264);
        int max = hevc ? MAX_BITRATE_HEVC : MAX_BITRATE_H264;
        return (int) Math.max(MIN_BITRATE, Math.min(max, br));
    }

    /** এই ফোনে (আপলোড যে ফোন থেকে হচ্ছে) নির্দিষ্ট কোডেকের হার্ডওয়্যার এনকোডার আছে কি? */
    static boolean hasHardwareEncoder(String mime) {
        try {
            for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (!info.isEncoder()) continue;
                boolean hw;
                if (Build.VERSION.SDK_INT >= 29) {
                    hw = info.isHardwareAccelerated() && !info.isSoftwareOnly();
                } else {
                    String n = info.getName().toLowerCase();
                    hw = !(n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.contains(".sw."));
                }
                if (!hw) continue;
                for (String t : info.getSupportedTypes()) {
                    if (t.equalsIgnoreCase(mime)) return true;
                }
            }
        } catch (Throwable ignored) { }
        return false;
    }

    private static boolean useHevc() {
        return PREFER_HEVC && hasHardwareEncoder(MimeTypes.VIDEO_H265);
    }

    /** আবার এনকোড করা দরকার কি? (w/h = ঘোরানোর পরের মাপ) — বিটরেট লক্ষ্যের চেয়ে বেশি বা মাপ বড় হলেই */
    static boolean needsCompress(File f, long durMs, int w, int h) {
        long bps = durMs > 0 ? f.length() * 8000L / durMs : Long.MAX_VALUE;
        long target = targetBitrate(w, h, useHevc());
        return Math.min(w, h) > TARGET_SHORT_SIDE + 8 || bps > target * 11L / 10L;
    }

    // ------------------------------------------------------------ কমপ্রেস

    /** মেইন থ্রেড থেকে ডাকুন। */
    static void run(Context ctx, File in, File out, int w, int h, Progress pg, Done done) {
        cancel();
        start(ctx, in, out, w, h, pg, done, useHevc());
    }

    private static void start(final Context ctx, final File in, final File out, final int w, final int h,
                              final Progress pg, final Done done, final boolean hevc) {
        final boolean[] finished = {false};
        try {
            List<Effect> fx = new ArrayList<>();
            int[] size = outputSize(w, h);
            if (Math.min(w, h) > TARGET_SHORT_SIDE) {
                fx.add(Presentation.createForWidthAndHeight(size[0], size[1], Presentation.LAYOUT_SCALE_TO_FIT));
            }
            int bitrate = targetBitrate(w, h, hevc);
            Log.i(TAG, "compress " + w + "x" + h + " -> " + size[0] + "x" + size[1]
                    + (hevc ? " HEVC " : " H264 ") + bitrate / 1000 + "kbps");
            VideoEncoderSettings vs = new VideoEncoderSettings.Builder()
                    .setBitrate(bitrate)
                    .setiFrameIntervalSeconds(2f)      // কম কীফ্রেম = ছোট ফাইল
                    .build();
            DefaultEncoderFactory ef = new DefaultEncoderFactory.Builder(ctx)
                    .setRequestedVideoEncoderSettings(vs).build();

            final Transformer[] holder = new Transformer[1];
            Transformer t = new Transformer.Builder(ctx)
                    .setVideoMimeType(hevc ? MimeTypes.VIDEO_H265 : MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(ef)
                    .addListener(new Transformer.Listener() {
                        @Override
                        public void onCompleted(Composition c, ExportResult r) {
                            if (finished[0]) return;
                            finished[0] = true;
                            stopTimers();
                            current = null;
                            done.on(out.exists() && out.length() > 0 ? out : null);
                        }

                        @Override
                        public void onError(Composition c, ExportResult r, ExportException e) {
                            if (finished[0]) return;
                            finished[0] = true;
                            stopTimers();
                            current = null;
                            Log.w(TAG, "compress failed (" + (hevc ? "HEVC" : "H264") + ")", e);
                            //noinspection ResultOfMethodCallIgnored
                            out.delete();
                            if (hevc) {                    // HEVC না হলে H.264-এ আবার
                                start(ctx, in, out, w, h, pg, done, false);
                                return;
                            }
                            done.on(null);
                        }
                    })
                    .build();
            holder[0] = t;
            current = t;

            EditedMediaItem item = new EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(in)))
                    .setEffects(new Effects(Collections.emptyList(), fx))
                    .build();
            t.start(item, out.getAbsolutePath());

            final ProgressHolder ph = new ProgressHolder();
            poller = new Runnable() {
                @Override public void run() {
                    if (finished[0] || current != holder[0]) return;
                    if (holder[0].getProgress(ph) == Transformer.PROGRESS_STATE_AVAILABLE && pg != null) {
                        pg.on(ph.progress);
                    }
                    MAIN.postDelayed(this, 400);
                }
            };
            MAIN.postDelayed(poller, 400);

            timeout = () -> {
                if (finished[0]) return;
                finished[0] = true;
                cancel();
                //noinspection ResultOfMethodCallIgnored
                out.delete();
                done.on(null);
            };
            MAIN.postDelayed(timeout, TIMEOUT_MS);
        } catch (Throwable e) {
            if (finished[0]) return;
            finished[0] = true;
            stopTimers();
            current = null;
            Log.w(TAG, "compress threw", e);
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            if (hevc) { start(ctx, in, out, w, h, pg, done, false); return; }
            done.on(null);
        }
    }

    /** চলমান কমপ্রেস বাতিল (ইউজার ফিরে গেলে)। */
    static void cancel() {
        stopTimers();
        Transformer t = current;
        current = null;
        if (t != null) {
            try { t.cancel(); } catch (Throwable ignored) { }
        }
    }

    private static void stopTimers() {
        if (poller != null) MAIN.removeCallbacks(poller);
        if (timeout != null) MAIN.removeCallbacks(timeout);
        poller = null;
        timeout = null;
    }

    // ------------------------------------------------------------ fast start (ব্যাকগ্রাউন্ড থ্রেডে ডাকুন)

    /**
     * moov বক্স mdat-এর পরে থাকলে সেটাকে ফাইলের শুরুতে আনে (stco/co64 অফসেট ঠিক করে)।
     * ফাইল বদলায় না যদি আগে থেকেই ঠিক থাকে বা কিছু ভুল হয়। ফেরত: কাজ করার পর ফাইল (একই path)।
     */
    static File fastStart(File f) {
        File tmp = new File(f.getParentFile(), f.getName() + ".fs");
        try {
            if (!rewrite(f, tmp)) { tmp.delete(); return f; }
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            if (!tmp.renameTo(f)) return tmp;
            return f;
        } catch (Throwable e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return f;
        }
    }

    private static boolean rewrite(File src, File dst) throws IOException {
        try (RandomAccessFile r = new RandomAccessFile(src, "r")) {
            long len = r.length();
            List<long[]> boxes = new ArrayList<>();      // {start, size}
            List<String> types = new ArrayList<>();
            long pos = 0;
            byte[] h = new byte[16];
            while (pos + 8 <= len) {
                r.seek(pos);
                r.readFully(h, 0, 8);
                long sz = u32(h, 0);
                String type = new String(h, 4, 4, StandardCharsets.ISO_8859_1);
                if (sz == 1) { r.readFully(h, 8, 8); sz = u64(h, 8); }
                else if (sz == 0) sz = len - pos;
                if (sz < 8 || pos + sz > len) return false;
                boxes.add(new long[]{pos, sz});
                types.add(type);
                pos += sz;
            }
            int moovI = types.indexOf("moov"), mdatI = types.indexOf("mdat");
            if (moovI < 0 || mdatI < 0 || moovI < mdatI) return false;   // নেই, বা আগে থেকেই ঠিক
            long moovStart = boxes.get(moovI)[0], moovSize = boxes.get(moovI)[1];
            if (moovSize > 64L * 1024 * 1024) return false;

            byte[] moov = new byte[(int) moovSize];
            r.seek(moovStart);
            r.readFully(moov);
            patch(moov, 0, moov.length, moovSize, moovStart);

            try (RandomAccessFile w = new RandomAccessFile(dst, "rw")) {
                w.setLength(0);
                int ftypI = types.indexOf("ftyp");
                if (ftypI >= 0) copy(r, w, boxes.get(ftypI)[0], boxes.get(ftypI)[1]);
                w.write(moov);
                for (int i = 0; i < boxes.size(); i++) {
                    if (i == moovI || i == ftypI) continue;
                    copy(r, w, boxes.get(i)[0], boxes.get(i)[1]);
                }
            }
            return true;
        }
    }

    private static void copy(RandomAccessFile r, RandomAccessFile w, long from, long size) throws IOException {
        byte[] buf = new byte[256 * 1024];
        r.seek(from);
        long left = size;
        while (left > 0) {
            int n = r.read(buf, 0, (int) Math.min(buf.length, left));
            if (n < 0) throw new IOException("eof");
            w.write(buf, 0, n);
            left -= n;
        }
    }

    /** moov-এর ভেতরের stco/co64: যে অফসেট পুরনো moov-এর আগের জায়গায় আছে তা moovSize পরিমাণ সরে যায় */
    private static void patch(byte[] b, int start, int end, long moovSize, long limit) throws IOException {
        int p = start;
        while (p + 8 <= end) {
            long sz = u32(b, p);
            String type = new String(b, p + 4, 4, StandardCharsets.ISO_8859_1);
            int hdr = 8;
            if (sz == 1) { sz = u64(b, p + 8); hdr = 16; }
            else if (sz == 0) sz = end - p;
            if (sz < hdr || p + sz > end) return;
            int body = p + hdr, bodyEnd = (int) (p + sz);
            switch (type) {
                case "moov": case "trak": case "mdia": case "minf": case "stbl":
                    patch(b, body, bodyEnd, moovSize, limit);
                    break;
                case "stco": {
                    int n = (int) u32(b, body + 4);
                    int q = body + 8;
                    for (int i = 0; i < n && q + 4 <= bodyEnd; i++, q += 4) {
                        long o = u32(b, q);
                        if (o < limit) {
                            o += moovSize;
                            if (o > 0xFFFFFFFFL) throw new IOException("stco overflow");
                            put32(b, q, o);
                        }
                    }
                    break;
                }
                case "co64": {
                    int n = (int) u32(b, body + 4);
                    int q = body + 8;
                    for (int i = 0; i < n && q + 8 <= bodyEnd; i++, q += 8) {
                        long o = u64(b, q);
                        if (o < limit) put64(b, q, o + moovSize);
                    }
                    break;
                }
                default: break;
            }
            p += (int) sz;
        }
    }

    private static long u32(byte[] b, int o) {
        return ((b[o] & 0xFFL) << 24) | ((b[o + 1] & 0xFFL) << 16) | ((b[o + 2] & 0xFFL) << 8) | (b[o + 3] & 0xFFL);
    }

    private static long u64(byte[] b, int o) { return (u32(b, o) << 32) | u32(b, o + 4); }

    private static void put32(byte[] b, int o, long v) {
        b[o] = (byte) (v >>> 24); b[o + 1] = (byte) (v >>> 16); b[o + 2] = (byte) (v >>> 8); b[o + 3] = (byte) v;
    }

    private static void put64(byte[] b, int o, long v) {
        put32(b, o, v >>> 32);
        put32(b, o + 4, v & 0xFFFFFFFFL);
    }
}
