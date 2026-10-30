package com.familybook.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;

/**
 * Makes a blurry / low-contrast NID photo easier for ML Kit.
 * Run it BEFORE InputImage.fromBitmap(...):
 *   Bitmap prepared = NidImagePrep.forOcr(original);
 *   recognizer.process(InputImage.fromBitmap(prepared, 0)) ...
 * Tip: also run OCR on the original and keep whichever text gives the better NidTextParser score.
 */
public final class NidImagePrep {
    private NidImagePrep() { }

    private static final int TARGET_LONG_SIDE = 2000;

    public static Bitmap forOcr(Bitmap src) {
        if (src == null) return null;
        // 1) scale so the long side is ~2000px (ML Kit reads small blurry text badly)
        int w = src.getWidth(), h = src.getHeight();
        float k = (float) TARGET_LONG_SIDE / Math.max(w, h);
        int nw = Math.max(1, Math.round(w * k)), nh = Math.max(1, Math.round(h * k));
        Bitmap scaled = Bitmap.createScaledBitmap(src, nw, nh, true);

        // 2) grayscale + strong contrast (washes out the green/pink background pattern)
        Bitmap out = Bitmap.createBitmap(nw, nh, Bitmap.Config.ARGB_8888);
        ColorMatrix gray = new ColorMatrix();
        gray.setSaturation(0f);
        float c = 1.6f;                       // contrast
        float t = (-0.5f * c + 0.5f) * 255f;  // keep mid-gray fixed
        ColorMatrix contrast = new ColorMatrix(new float[]{
                c, 0, 0, 0, t,
                0, c, 0, 0, t,
                0, 0, c, 0, t,
                0, 0, 0, 1, 0});
        gray.postConcat(contrast);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        p.setColorFilter(new ColorMatrixColorFilter(gray));
        new Canvas(out).drawBitmap(scaled, 0, 0, p);
        if (scaled != src) scaled.recycle();
        return out;
    }
}
