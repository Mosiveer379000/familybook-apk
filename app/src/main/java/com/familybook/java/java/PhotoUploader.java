package com.familybook.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;


import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Random;

/**
 * গ্যালারির ছবি বাছাই → অটো কমপ্রেস (বড় ছবি ছোট, কোয়ালিটি ঠিক) → Cloudflare R2-তে আপলোড (Media + Edge Function r2-sign দিয়ে)।
 * কমপ্রেস: বড় ছবি ছোট মাপে ও বড় ফাইল বেশি কমে; ছোট ফাইল সামান্য — নিয়ম ImageOptimizer-এ।
 */
public class PhotoUploader {

    // মাপ ও লক্ষ্য-সাইজের নিয়ম ImageOptimizer.targetSide / targetBytes-এ (4K→~1800px, 1300-2000px→1000px, বড় ফাইল বেশি কমে)
    private static final int MAX_ORIGINAL_BYTES = 40 * 1024 * 1024;

    public interface Callback {
        /** সফল হলে R2 key থাকে (posts.image_key-এ সেভ হবে), ব্যর্থ হলে err থাকে (মেইন থ্রেডে ডাকা হয়) */
        void done(String url, String err);
    }

    public static class Compressed {
        public byte[] bytes;
        public String mime = "image/jpeg";
        public String ext = "jpg";
        public Bitmap preview;   // স্ক্রিনে দেখানোর জন্য
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    // ------------------------------------------------------------ কমপ্রেস

    /** ব্যাকগ্রাউন্ড থ্রেডে ডাকুন */
    public static Compressed compress(Context c, Uri uri) throws Exception {
        // EXIF ঘোরানোর জন্য আগে ক্যাশ ফাইলে কপি
        File tmp = new File(c.getCacheDir(), "pick_" + System.currentTimeMillis() + ".img");
        try (InputStream in = c.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(tmp)) {
            if (in == null) throw new Exception("ছবি পড়া যায়নি");
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(tmp.getAbsolutePath(), o);
            if (o.outWidth <= 0 || o.outHeight <= 0) throw new Exception("এটা ছবি নয়");

            final int ow = o.outWidth, oh = o.outHeight;
            final String m0 = o.outMimeType;
            final long origLen = tmp.length();
            int rot = 0;
            try {
                int ori = new ExifInterface(tmp.getAbsolutePath())
                        .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                if (ori == ExifInterface.ORIENTATION_ROTATE_90) rot = 90;
                else if (ori == ExifInterface.ORIENTATION_ROTATE_180) rot = 180;
                else if (ori == ExifInterface.ORIENTATION_ROTATE_270) rot = 270;
            } catch (Exception ignored) { }

            byte[] orig = null;
            if (origLen <= MAX_ORIGINAL_BYTES) {
                orig = new byte[(int) origLen];
                try (java.io.FileInputStream fi = new java.io.FileInputStream(tmp)) {
                    int off = 0, n;
                    while (off < orig.length && (n = fi.read(orig, off, orig.length - off)) > 0) off += n;
                }
            }
            final boolean usableFmt = "image/jpeg".equals(m0) || "image/png".equals(m0)
                    || "image/webp".equals(m0) || "image/bmp".equals(m0);

            // গ্রাফিক্স (লেখা/স্ক্রিনশট PNG, স্থির GIF) ও অ্যানিমেটেড GIF: মাপ/কোয়ালিটি অপরিবর্তিত, শুধু lossless
            final boolean animGif = orig != null && "image/gif".equals(m0) && ImageOptimizer.isAnimatedGif(orig);
            final boolean graphic = orig != null && rot == 0 && (animGif
                    || (("image/png".equals(m0) || "image/gif".equals(m0))
                        && origLen * 8.0 / ((double) ow * oh) < 3.0));

            final int longSide = Math.max(ow, oh);
            final int tgtSide = graphic ? longSide : ImageOptimizer.targetSide(longSide);
            int sample = 1;
            while (longSide / (sample * 2) >= tgtSide) sample *= 2;
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample;
            Bitmap bmp = BitmapFactory.decodeFile(tmp.getAbsolutePath(), d);
            if (bmp == null) throw new Exception("ছবি খোলা যায়নি");

            float scale = Math.min(1f, (float) tgtSide / Math.max(bmp.getWidth(), bmp.getHeight()));
            if (scale < 1f || rot != 0) {
                Matrix m = new Matrix();
                if (scale < 1f) m.postScale(scale, scale);
                if (rot != 0) m.postRotate(rot);
                Bitmap b2 = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
                if (b2 != bmp) bmp.recycle();
                bmp = b2;
            }
            final boolean dimsChanged = Math.max(bmp.getWidth(), bmp.getHeight()) != longSide;

            byte[] data;
            String mime, ext;
            if (graphic) {
                ImageOptimizer.Result g = ImageOptimizer.optimize(orig, m0, false);
                data = g.bytes; mime = g.mime; ext = g.ext;
            } else {
                byte[] usable = (rot == 0 && !dimsChanged && usableFmt) ? orig : null;
                ImageOptimizer.Result sr = ImageOptimizer.smart(bmp, origLen, usable, m0);
                data = sr.bytes; mime = sr.mime; ext = sr.ext;
            }
            Compressed r = new Compressed();
            r.bytes = data;
            r.mime = mime;
            r.ext = ext;
            android.util.Log.d("PhotoUploader", "compress: " + ow + "x" + oh + " " + (origLen / 1024) + "KB -> "
                    + bmp.getWidth() + "x" + bmp.getHeight() + " " + (data.length / 1024) + "KB " + mime);
            float ts = 720f / Math.max(bmp.getWidth(), bmp.getHeight());
            if (ts < 1f) {
                Bitmap th = Bitmap.createScaledBitmap(bmp, Math.max(1, Math.round(bmp.getWidth() * ts)),
                        Math.max(1, Math.round(bmp.getHeight() * ts)), true);
                if (th != bmp) bmp.recycle();
                bmp = th;
            }
            r.preview = bmp;   // ছোট প্রিভিউ; আসল ছবি bytes-এ
            return r;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    // ------------------------------------------------------------ আপলোড

    /** কমপ্রেস করা ছবি R2-তে আপলোড; শেষে মেইন থ্রেডে cb (value = R2 key) */
    public static void upload(final Context ctx, final Compressed comp, final Callback cb) {
        upload(ctx, comp, cb, null);
    }

    /** pr != null হলে আপলোডের সাথে সাথে (পাঠানো বাইট, মোট বাইট) জানায় */
    public static void upload(final Context ctx, final Compressed comp, final Callback cb,
                              final Media.Progress pr) {
        if (SupabaseRest.uid() == null) { cb.done(null, "লগইন করা নেই"); return; }
        String name = System.currentTimeMillis() + "_" + (1000 + new Random().nextInt(9000)) + "." + comp.ext;
        Media.upload(comp.bytes, "post", name, comp.mime, (key, err) -> cb.done(key, err), pr);
    }

    /** ব্যাকগ্রাউন্ড থ্রেডে কমপ্রেস করে দেয় */
    public static void compressAsync(final Context ctx, final Uri uri, final CompressCb cb) {
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            Compressed r = null;
            String err = null;
            try {
                r = compress(app, uri);
            } catch (Throwable e) {
                err = e.getMessage() == null ? "ছবি প্রস্তুত করা যায়নি" : e.getMessage();
            }
            final Compressed fr = r;
            final String fe = err;
            MAIN.post(() -> cb.done(fr, fe));
        }).start();
    }

    public interface CompressCb {
        void done(Compressed c, String err);
    }
}
