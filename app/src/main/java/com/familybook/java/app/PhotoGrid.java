package com.familybook.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;

/**
 * ফেসবুকের মতো ছবির গ্রিড: ১টি = পুরো, ২টি = পাশাপাশি, ৩টি = একটা বড় + দুটো ছোট,
 * ৪টি = ২x২, ৫+ = উপরে ২ + নিচে ৩ (বেশি থাকলে শেষ ঘরে "+N")।
 * সব ছবি একটা Bitmap-এ জুড়ে দেয়, তাই ফিডের লেআউট বদলাতে হয় না।
 */
public final class PhotoGrid {

    public static final int MAX_TILES = 5;
    private static final int W = 1080, H = 1080, GAP = 6;

    private PhotoGrid() { }

    /** [left, top, right, bottom] — 0..1 অনুপাতে। variant 1 = বিকল্প সাজানো (বড়টা উপরে) */
    public static float[][] rects(int n, int variant) {
        float a = 1f / 3f;
        if (n <= 1) return new float[][]{{0, 0, 1, 1}};
        if (variant == 1) {
            if (n == 2) return new float[][]{{0, 0, 1, .5f}, {0, .5f, 1, 1}};
            if (n == 3) return new float[][]{{0, 0, 1, .6f}, {0, .6f, .5f, 1}, {.5f, .6f, 1, 1}};
            if (n == 4) return new float[][]{{0, 0, 1, .55f}, {0, .55f, a, 1}, {a, .55f, 2 * a, 1}, {2 * a, .55f, 1, 1}};
            return new float[][]{{0, 0, a, .5f}, {a, 0, 2 * a, .5f}, {2 * a, 0, 1, .5f}, {0, .5f, .5f, 1}, {.5f, .5f, 1, 1}};
        }
        if (n == 2) return new float[][]{{0, 0, .5f, 1}, {.5f, 0, 1, 1}};
        if (n == 3) return new float[][]{{0, 0, .5f, 1}, {.5f, 0, 1, .5f}, {.5f, .5f, 1, 1}};
        if (n == 4) return new float[][]{{0, 0, .5f, .5f}, {.5f, 0, 1, .5f}, {0, .5f, .5f, 1}, {.5f, .5f, 1, 1}};
        float t = .55f;
        return new float[][]{{0, 0, .5f, t}, {.5f, 0, 1, t}, {0, t, a, 1}, {a, t, 2 * a, 1}, {2 * a, t, 1, 1}};
    }

    /** ছুঁয়ে দেওয়া জায়গা (0..1) কোন ছবির ঘর, তার index */
    public static int hit(int total, int variant, float nx, float ny) {
        float[][] r = rects(Math.min(total, MAX_TILES), variant);
        for (int i = 0; i < r.length; i++) {
            if (nx >= r[i][0] && nx <= r[i][2] && ny >= r[i][1] && ny <= r[i][3]) return i;
        }
        return 0;
    }

    /** bms: প্রথম min(total,5)টি ছবি (null হলে ধূসর ঘর) */
    public static Bitmap compose(Bitmap[] bms, int total, int variant) {
        int shown = Math.min(total, MAX_TILES);
        float[][] r = rects(shown, variant);
        Bitmap out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(Color.WHITE);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        for (int i = 0; i < shown; i++) {
            RectF d = new RectF(r[i][0] * W + GAP / 2f, r[i][1] * H + GAP / 2f,
                    r[i][2] * W - GAP / 2f, r[i][3] * H - GAP / 2f);
            Bitmap b = i < bms.length ? bms[i] : null;
            if (b == null || b.isRecycled()) {
                p.setColor(0xFFE4E6EB);
                c.drawRect(d, p);
                continue;
            }
            float ta = d.width() / d.height();
            float ba = (float) b.getWidth() / b.getHeight();
            Rect src;
            if (ba > ta) {
                int sw = Math.round(b.getHeight() * ta);
                int l = (b.getWidth() - sw) / 2;
                src = new Rect(l, 0, l + sw, b.getHeight());
            } else {
                int sh = Math.round(b.getWidth() / ta);
                int t = (b.getHeight() - sh) / 2;
                src = new Rect(0, t, b.getWidth(), t + sh);
            }
            c.drawBitmap(b, src, d, p);
            if (i == shown - 1 && total > shown) {
                p.setColor(0x99000000);
                c.drawRect(d, p);
                p.setColor(Color.WHITE);
                p.setTextAlign(Paint.Align.CENTER);
                p.setTypeface(Typeface.DEFAULT_BOLD);
                p.setTextSize(110);
                c.drawText("+" + (total - shown + 1), d.centerX(), d.centerY() + 38, p);
            }
        }
        return out;
    }
}
