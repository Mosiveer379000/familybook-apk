package com.familybook.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;

import java.io.ByteArrayOutputStream;

/**
 * সব ধরনের ছবির সাইজ কমানোর কেন্দ্রীয় জায়গা। মাপ (width × height) বদলায় না।
 *
 *  JPEG  : আগে lossless (EXIF/থাম্বনেইল/বাড়তি ডাটা বাদ), তারপর WebP lossy 82 — অন্তত 10% ছোট হলে তবেই।
 *  PNG/BMP/lossless-WebP : lossless; ফটো-ধরনের হলে WebP lossy 82-ও চেষ্টা হয়।
 *  GIF / অ্যানিমেটেড / lossy WebP / অচেনা : যেমন আছে তেমন।
 *
 * নিয়ম: ফলাফল কখনোই আসল ফাইলের চেয়ে বড় হবে না।
 */
public final class ImageOptimizer {

    private ImageOptimizer() { }

    public static final class Result {
        public final byte[] bytes;
        public final String mime;
        public final String ext;
        Result(byte[] b, String m, String e) { bytes = b; mime = m; ext = e; }
    }

    /** WebP lossy কোয়ালিটি: 82 এ ছবি চোখে আসলের মতোই দেখায় (ঝাপসা/ফাটা হয় না), অথচ JPEG-এর চেয়ে ছোট */
    public static final int LOSSY_QUALITY = 82;
    /** JPEG আগে থেকেই এর চেয়ে কম bits/pixel হলে আবার lossy করা হয় না (আরেক দফা কোয়ালিটি যেত) */
    private static final double MIN_BPP_TO_RECODE = 1.2;
    /** PNG-lossless এর পরেও bits/pixel এর চেয়ে বেশি মানে ছবিটা ফটো-ধরনের → lossy কাজে লাগবে; কম হলে স্ক্রিনশট/লেখা → lossless-ই থাকবে */
    private static final double PHOTO_LIKE_BPP = 3.0;

    /**
     * bytes + ঘোষিত mime → সবচেয়ে ছোট সঠিক রূপ। ব্যাকগ্রাউন্ড থ্রেডে ডাকুন।
     * মাপ (width × height) কখনো বদলায় না।
     * allowFormatChange=false (ডকুমেন্ট) হলে শুধু lossless।
     */
    public static Result optimize(byte[] src, String declaredMime, boolean allowFormatChange) {
        String mime = sniff(src, declaredMime);
        Result best = new Result(src, mime, extFor(mime));
        try {
            boolean jpeg = "image/jpeg".equals(mime);
            boolean png = "image/png".equals(mime) || "image/bmp".equals(mime)
                    || ("image/gif".equals(mime) && !isAnimatedGif(src));   // স্থির GIF-ও PNG-র মতো
            boolean webp = "image/webp".equals(mime);
            if (webp && isLossyOrAnimatedWebp(src)) return best;      // lossy/অ্যানিমেটেড WebP ছোঁয়া হবে না
            if (!jpeg && !png && !webp) return best;                   // GIF ও বাকি যেমন আছে

            if (jpeg) {
                byte[] st = stripJpeg(src);
                if (st != null && st.length < best.bytes.length) best = new Result(st, mime, best.ext);
            }

            BitmapFactory.Options bo = new BitmapFactory.Options();
            bo.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(src, 0, src.length, bo);
            if (bo.outWidth <= 0 || bo.outHeight <= 0) return best;
            double pixels = (double) bo.outWidth * bo.outHeight;

            Bitmap bm = null;
            try {
                if (!jpeg) {                                           // PNG/BMP/lossless-WebP → lossless
                    bm = BitmapFactory.decodeByteArray(src, 0, src.length);
                    if (bm == null) return best;
                    Result ll = encodeLossless(bm);
                    if (ll.bytes.length < best.bytes.length) best = ll;
                }
                if (!allowFormatChange) return best;

                double bpp = best.bytes.length * 8.0 / pixels;
                boolean tryLossy = jpeg ? bpp >= MIN_BPP_TO_RECODE : bpp >= PHOTO_LIKE_BPP;
                if (tryLossy) {
                    if (bm == null) bm = BitmapFactory.decodeByteArray(src, 0, src.length);
                    if (bm != null && !bm.hasAlpha()) {
                        Result ly = encodeLossyWebp(bm, LOSSY_QUALITY);
                        // JPEG: অন্তত 10% ছোট না হলে আসলটাই (অকারণে কোয়ালিটি নষ্ট নয়)
                        double need = jpeg ? 0.90 : 1.0;
                        if (ly.bytes.length < best.bytes.length * need) best = ly;
                    }
                }
            } finally {
                if (bm != null) bm.recycle();
            }
        } catch (Throwable ignored) { }       // OOM ইত্যাদিতে যা পাওয়া গেছে (অন্তত আসল) তাই
        return best;
    }

    // ------------------------------------------------------------ স্তরভিত্তিক (tiered) কমপ্রেস

    /** আসল ছবির বড় দিক (px) → নতুন বড় দিক। সংখ্যাগুলো এখানেই বদলানো যাবে। */
    public static int targetSide(int longSide) {
        if (longSide > 3500) return 1500;   // 4K → 1.5K
        if (longSide > 2500) return 1000;   // 3K → 1K
        if (longSide > 900) return 500;     // 2K ও 1K → 500px
        return longSide;                    // 900px বা কম → অপরিবর্তিত
    }

    // (আসল ফাইল KB → চাওয়া সাইজ KB); মাঝের মান সরলরেখায় হিসাব হয়
    private static final long[][] SIZE_TABLE = {
            {200, 160},     // 200KB → 160KB
            {1024, 85},     // 1MB → ~85KB
            {2048, 100},    // 2MB → ~100KB
            {3072, 200},    // 3MB → ~200KB
            {5120, 400},    // 5MB → ~400KB
    };

    /** আসল ফাইলের সাইজ (বাইট) → চাওয়া সাইজ (বাইট)। */
    public static long targetBytes(long bytes) {
        final long k = 1024;
        long kb = bytes / k;
        if (kb < SIZE_TABLE[0][0]) return (long) (bytes * 0.80);   // ছোট ফাইল: ২০% কম (100KB → ~80KB)
        long last = SIZE_TABLE.length - 1;
        if (kb >= SIZE_TABLE[(int) last][0]) return SIZE_TABLE[(int) last][1] * k;   // 5MB+ → 400KB
        for (int i = 0; i < last; i++) {
            long x0 = SIZE_TABLE[i][0], y0 = SIZE_TABLE[i][1], x1 = SIZE_TABLE[i + 1][0], y1 = SIZE_TABLE[i + 1][1];
            if (kb >= x0 && kb < x1) return (y0 + (y1 - y0) * (kb - x0) / (x1 - x0)) * k;
        }
        return bytes;
    }

    /**
     * ছবি (মাপ আগেই ঠিক করা) → লক্ষ্য সাইজের মধ্যে সবচেয়ে ভালো কোয়ালিটির WebP।
     * usableOrig != null মানে আসল ফাইলটাও রাখা যায় (মাপ/ঘোরানো বদলায়নি); তখন নতুনটা
     * অন্তত 5% ছোট না হলে কিংবা (ছোট ফাইলে) লক্ষ্যে না পৌঁছালে আসলটাই থাকে।
     */
    public static Result smart(Bitmap bm, long origLen, byte[] usableOrig, String mime) {
        Result keep = usableOrig == null ? null : optimize(usableOrig, mime, false);
        if (bm.hasAlpha()) {                                    // স্বচ্ছ ছবি → lossless
            Result l = encodeLossless(bm);
            return (keep != null && keep.bytes.length <= l.bytes.length) ? keep : l;
        }
        boolean small = origLen < 200 * 1024;
        long target = targetBytes(origLen);
        int qMin = small ? 78 : 65;       // এর নিচে কোয়ালিটি নামবে না (ঝাপসা/ফাটা এড়াতে)
        Result lossy = encodeToTarget(bm, target, 90, qMin);
        if (keep != null) {
            if (small && lossy.bytes.length > target) return keep;
            if (lossy.bytes.length >= keep.bytes.length * 0.95) return keep;
        }
        return lossy;
    }

    /** লক্ষ্য সাইজের ভেতরে সবচেয়ে বেশি কোয়ালিটি (binary search); qMin-এও না হলে qMin-এর ফলাফল। */
    static Result encodeToTarget(Bitmap bm, long target, int qMax, int qMin) {
        Result best = encodeLossyWebp(bm, qMin);
        if (best.bytes.length > target) return best;
        int lo = qMin + 1, hi = qMax;
        while (lo <= hi) {
            int mid = (lo + hi) / 2;
            Result r = encodeLossyWebp(bm, mid);
            if (r.bytes.length <= target) { best = r; lo = mid + 1; } else hi = mid - 1;
        }
        return best;
    }

    /** Bitmap → চোখে-একই কিন্তু ছোট: স্বচ্ছ অংশ থাকলে lossless, নইলে WebP lossy 82 */
    public static Result encodeVisual(Bitmap bm) {
        return bm.hasAlpha() ? encodeLossless(bm) : encodeLossyWebp(bm, LOSSY_QUALITY);
    }

    @SuppressWarnings("deprecation")
    static Result encodeLossyWebp(Bitmap bm, int quality) {
        ByteArrayOutputStream a = new ByteArrayOutputStream();
        bm.compress(Build.VERSION.SDK_INT >= 30 ? Bitmap.CompressFormat.WEBP_LOSSY
                : Bitmap.CompressFormat.WEBP, quality, a);
        return new Result(a.toByteArray(), "image/webp", "webp");
    }

    /** Bitmap → পিক্সেল-হুবহু এক (lossless) বাইট। */
    public static Result encodeLossless(Bitmap bm) {
        ByteArrayOutputStream a = new ByteArrayOutputStream();
        if (Build.VERSION.SDK_INT >= 30) {
            bm.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, a);
            return new Result(a.toByteArray(), "image/webp", "webp");
        }
        bm.compress(Bitmap.CompressFormat.PNG, 100, a);
        return new Result(a.toByteArray(), "image/png", "png");
    }

    // ------------------------------------------------------------ JPEG lossless strip

    /** APP0(JFIF) ও APP2(ICC রঙের প্রোফাইল) রেখে বাকি সব APP/COM সেগমেন্ট এবং EOI-এর পরের ডাটা বাদ। */
    static byte[] stripJpeg(byte[] d) {
        if (d.length < 4 || (d[0] & 0xFF) != 0xFF || (d[1] & 0xFF) != 0xD8) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream(d.length);
        out.write(0xFF); out.write(0xD8);
        int i = 2;
        while (i + 1 < d.length) {
            if ((d[i] & 0xFF) != 0xFF) return null;                  // গোলমাল → আসল ফাইলই থাকুক
            int m = d[i + 1] & 0xFF;
            if (m == 0xFF) { i++; continue; }                        // ফিল বাইট
            if (m == 0xD9) { out.write(0xFF); out.write(0xD9); return out.toByteArray(); }
            if (m == 0x01 || (m >= 0xD0 && m <= 0xD7)) {             // দৈর্ঘ্যহীন মার্কার
                out.write(d, i, 2); i += 2; continue;
            }
            if (i + 3 >= d.length) return null;
            int len = ((d[i + 2] & 0xFF) << 8) | (d[i + 3] & 0xFF);
            if (len < 2 || i + 2 + len > d.length) return null;
            boolean keep;
            if (m == 0xE0) keep = true;                              // JFIF
            else if (m == 0xE2) keep = startsWith(d, i + 4, "ICC_PROFILE");
            else if (m == 0xEE) keep = true;                         // Adobe (রঙের স্পেস)
            else keep = !(m >= 0xE1 && m <= 0xEF) && m != 0xFE;      // বাকি APPn/COM বাদ
            if (keep) out.write(d, i, 2 + len);
            i += 2 + len;
            if (m == 0xDA) {                                         // SOS: এনট্রপি ডাটা কপি
                int s = i;
                while (i + 1 < d.length) {
                    if ((d[i] & 0xFF) == 0xFF) {
                        int n = d[i + 1] & 0xFF;
                        if (n != 0x00 && !(n >= 0xD0 && n <= 0xD7) && n != 0xFF) break;
                    }
                    i++;
                }
                out.write(d, s, i - s);
            }
        }
        return null;   // EOI পাওয়া যায়নি → নিরাপদে আসলটাই
    }

    private static boolean startsWith(byte[] d, int off, String s) {
        if (off + s.length() > d.length) return false;
        for (int k = 0; k < s.length(); k++) if (d[off + k] != (byte) s.charAt(k)) return false;
        return true;
    }

    // ------------------------------------------------------------ ফরম্যাট চেনা

    static String sniff(byte[] d, String declared) {
        if (d.length > 3 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8) return "image/jpeg";
        if (d.length > 8 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') return "image/png";
        if (d.length > 12 && d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F'
                && d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P') return "image/webp";
        if (d.length > 6 && d[0] == 'G' && d[1] == 'I' && d[2] == 'F') return "image/gif";
        if (d.length > 2 && d[0] == 'B' && d[1] == 'M') return "image/bmp";
        return declared == null ? "application/octet-stream" : declared;
    }

    /** GIF-এ একের বেশি ফ্রেম আছে কিনা (ব্লক ধরে ধরে পড়ে)। */
    public static boolean isAnimatedGif(byte[] d) {
        try {
            if (d.length < 14) return false;
            int i = 13;
            if ((d[10] & 0x80) != 0) i += 3 * (1 << ((d[10] & 7) + 1));
            int frames = 0;
            while (i < d.length) {
                int b = d[i++] & 0xFF;
                if (b == 0x3B) break;
                if (b == 0x21) {                                   // এক্সটেনশন
                    i++;
                    while (i < d.length) { int n = d[i++] & 0xFF; if (n == 0) break; i += n; }
                } else if (b == 0x2C) {                            // ফ্রেম
                    if (++frames > 1) return true;
                    int flags = d[i + 8] & 0xFF;
                    i += 9;
                    if ((flags & 0x80) != 0) i += 3 * (1 << ((flags & 7) + 1));
                    i++;                                           // LZW min code size
                    while (i < d.length) { int n = d[i++] & 0xFF; if (n == 0) break; i += n; }
                } else return true;                                // অচেনা ব্লক → নিরাপদে অ্যানিমেটেড ধরি
            }
            return false;
        } catch (Throwable t) { return true; }
    }

    /** WebP: VP8 (lossy) বা VP8X+অ্যানিমেশন হলে ছোঁয়া হবে না। শুধু 'VP8L' (lossless) নিরাপদ। */
    private static boolean isLossyOrAnimatedWebp(byte[] d) {
        return !(d.length > 16 && d[12] == 'V' && d[13] == 'P' && d[14] == '8' && d[15] == 'L');
    }

    static String extFor(String mime) {
        switch (mime) {
            case "image/jpeg": return "jpg";
            case "image/png": return "png";
            case "image/webp": return "webp";
            case "image/gif": return "gif";
            case "image/bmp": return "bmp";
            default: return "bin";
        }
    }
}
