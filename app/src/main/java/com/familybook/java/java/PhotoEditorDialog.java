package com.familybook.app;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;

/**
 * একটি ছবির Effects ও Crop এডিটর (ফুল-স্ক্রিন)। DONE চাপলে ছবি আবার কমপ্রেস হয়ে
 * PostPhoto-তে বসে (key = null, তাই পোস্টের সময় নতুন করে আপলোড হবে)।
 */
public final class PhotoEditorDialog {
    private PhotoEditorDialog() { }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final String[] F_NAMES = {"Original", "Vivid", "B&W", "Sepia", "Warm", "Cool", "Fade"};
    private static final String[] A_NAMES = {"Free", "1:1", "4:5", "3:4", "16:9"};
    private static final float[] A_VALUES = {0f, 1f, 0.8f, 0.75f, 16f / 9f};

    private static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    private static ColorMatrix cm(float sat, float contrast, float bright, float r, float g, float b) {
        ColorMatrix m = new ColorMatrix();
        m.setSaturation(sat);
        float t = (1f - contrast) * 128f + bright;
        m.postConcat(new ColorMatrix(new float[]{
                contrast * r, 0, 0, 0, t,
                0, contrast * g, 0, 0, t,
                0, 0, contrast * b, 0, t,
                0, 0, 0, 1, 0}));
        return m;
    }

    private static ColorMatrix[] filters() {
        return new ColorMatrix[]{
                null,
                cm(1.4f, 1.1f, 0f, 1f, 1f, 1f),
                cm(0f, 1.05f, 0f, 1f, 1f, 1f),
                new ColorMatrix(new float[]{
                        0.393f, 0.769f, 0.189f, 0, 0,
                        0.349f, 0.686f, 0.168f, 0, 0,
                        0.272f, 0.534f, 0.131f, 0, 0,
                        0, 0, 0, 1, 0}),
                cm(1.05f, 1.0f, 0f, 1.1f, 1.0f, 0.88f),
                cm(1.05f, 1.0f, 0f, 0.9f, 1.0f, 1.1f),
                cm(0.8f, 0.85f, 22f, 1f, 1f, 1f)
        };
    }

    public static void show(final Context ctx, final PostPhoto ph, final Runnable onDone) {
        final Bitmap src = ph.decode(1600);
        if (src == null) {
            Toast.makeText(ctx, "ছবি খোলা যায়নি", Toast.LENGTH_SHORT).show();
            return;
        }
        final ColorMatrix[] FILTERS = filters();
        final int[] filter = {0};

        final Dialog dlg = new Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        final CropView cv = new CropView(ctx);
        cv.setBitmap(src);
        root.addView(cv, new LinearLayout.LayoutParams(-1, 0, 1f));

        final FrameLayout opts = new FrameLayout(ctx);
        root.addView(opts, new LinearLayout.LayoutParams(-1, dp(ctx, 104)));

        // ---- Effects সারি: ছোট প্রিভিউ সহ ফিল্টার
        float ts = 160f / Math.max(src.getWidth(), src.getHeight());
        final Bitmap thumb = Bitmap.createScaledBitmap(src, Math.max(1, Math.round(src.getWidth() * ts)),
                Math.max(1, Math.round(src.getHeight() * ts)), true);
        final HorizontalScrollView fScroll = new HorizontalScrollView(ctx);
        fScroll.setHorizontalScrollBarEnabled(false);
        final LinearLayout fRow = new LinearLayout(ctx);
        fRow.setOrientation(LinearLayout.HORIZONTAL);
        fRow.setGravity(Gravity.CENTER_VERTICAL);
        fRow.setPadding(dp(ctx, 8), dp(ctx, 6), dp(ctx, 8), dp(ctx, 6));
        fScroll.addView(fRow, new ViewGroup.LayoutParams(-2, -1));
        final View[] fCells = new View[F_NAMES.length];
        for (int i = 0; i < F_NAMES.length; i++) {
            final int idx = i;
            LinearLayout cell = new LinearLayout(ctx);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setPadding(dp(ctx, 4), dp(ctx, 4), dp(ctx, 4), dp(ctx, 4));
            ImageView iv = new ImageView(ctx);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageBitmap(thumb);
            if (FILTERS[i] != null) iv.setColorFilter(new ColorMatrixColorFilter(FILTERS[i]));
            cell.addView(iv, new LinearLayout.LayoutParams(dp(ctx, 62), dp(ctx, 62)));
            TextView tv = new TextView(ctx);
            tv.setText(F_NAMES[i]);
            tv.setTextColor(Color.WHITE);
            tv.setTextSize(11);
            tv.setGravity(Gravity.CENTER);
            cell.addView(tv, new LinearLayout.LayoutParams(-2, -2));
            cell.setOnClickListener(v -> {
                filter[0] = idx;
                cv.setFilter(FILTERS[idx] == null ? null : new ColorMatrixColorFilter(FILTERS[idx]));
                for (int k = 0; k < fCells.length; k++) markCell(ctx, fCells[k], k == idx);
            });
            fCells[i] = cell;
            markCell(ctx, cell, i == 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(dp(ctx, 4), 0, dp(ctx, 4), 0);
            fRow.addView(cell, lp);
        }

        // ---- Crop সারি: aspect বাটন
        final HorizontalScrollView aScroll = new HorizontalScrollView(ctx);
        aScroll.setHorizontalScrollBarEnabled(false);
        final LinearLayout aRow = new LinearLayout(ctx);
        aRow.setOrientation(LinearLayout.HORIZONTAL);
        aRow.setGravity(Gravity.CENTER_VERTICAL);
        aRow.setPadding(dp(ctx, 12), 0, dp(ctx, 12), 0);
        aScroll.addView(aRow, new ViewGroup.LayoutParams(-2, -1));
        final TextView[] chips = new TextView[A_NAMES.length];
        for (int i = 0; i < A_NAMES.length; i++) {
            final int idx = i;
            TextView t = new TextView(ctx);
            t.setText(A_NAMES[i]);
            t.setTextSize(15);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(ctx, 18), dp(ctx, 9), dp(ctx, 18), dp(ctx, 9));
            t.setOnClickListener(v -> {
                cv.setAspect(A_VALUES[idx]);
                for (int k = 0; k < chips.length; k++) styleChip(ctx, chips[k], k == idx);
            });
            chips[i] = t;
            styleChip(ctx, t, i == 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(dp(ctx, 5), 0, dp(ctx, 5), 0);
            aRow.addView(t, lp);
        }

        // ---- নিচের বার: Effects | Crop ....... DONE
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(ctx, 16), dp(ctx, 8), dp(ctx, 16), dp(ctx, 16));
        root.addView(bar, new LinearLayout.LayoutParams(-1, dp(ctx, 90)));

        final LinearLayout tabFx = tab(ctx, Icons.effects(ctx, dp(ctx, 28), Color.WHITE), "Effects");
        final LinearLayout tabCrop = tab(ctx, Icons.crop(ctx, dp(ctx, 28), Color.WHITE), "Crop");
        bar.addView(tabFx, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.leftMargin = dp(ctx, 26);
        bar.addView(tabCrop, tp);
        View spacer = new View(ctx);
        bar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        final TextView done = new TextView(ctx);
        done.setText("DONE");
        done.setTextColor(Color.WHITE);
        done.setTextSize(20);
        done.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        done.setGravity(Gravity.CENTER);
        GradientDrawable db = new GradientDrawable();
        db.setColor(0xFF0866FF);
        db.setCornerRadius(dp(ctx, 10));
        done.setBackground(db);
        bar.addView(done, new LinearLayout.LayoutParams(dp(ctx, 160), dp(ctx, 56)));

        final Runnable showFx = () -> {
            opts.removeAllViews();
            opts.addView(fScroll, new FrameLayout.LayoutParams(-1, -1));
            cv.setCropEnabled(false);
            tabFx.setAlpha(1f);
            tabCrop.setAlpha(0.55f);
        };
        final Runnable showCrop = () -> {
            opts.removeAllViews();
            opts.addView(aScroll, new FrameLayout.LayoutParams(-1, -1));
            cv.setCropEnabled(true);
            tabFx.setAlpha(0.55f);
            tabCrop.setAlpha(1f);
        };
        tabFx.setOnClickListener(v -> showFx.run());
        tabCrop.setOnClickListener(v -> showCrop.run());
        showFx.run();

        done.setOnClickListener(v -> {
            final RectF cr = cv.getCropInBitmap();
            final int fi = filter[0];
            if (fi == 0 && !cv.isCropped()) {     // কিছুই বদলায়নি
                dlg.dismiss();
                return;
            }
            done.setEnabled(false);
            done.setText("...");
            new Thread(() -> {
                byte[] data = null;
                String newMime = "image/jpeg", newExt = "jpg";
                Bitmap prev = null;
                String err = null;
                try {
                    int l = Math.max(0, Math.min(src.getWidth() - 1, Math.round(cr.left)));
                    int t = Math.max(0, Math.min(src.getHeight() - 1, Math.round(cr.top)));
                    int r = Math.max(l + 1, Math.min(src.getWidth(), Math.round(cr.right)));
                    int b = Math.max(t + 1, Math.min(src.getHeight(), Math.round(cr.bottom)));
                    int w = r - l, h = b - t;
                    Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    Canvas c = new Canvas(out);
                    Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
                    if (FILTERS[fi] != null) p.setColorFilter(new ColorMatrixColorFilter(FILTERS[fi]));
                    c.drawBitmap(src, new Rect(l, t, r, b), new Rect(0, 0, w, h), p);
                    // এডিটের পরেও একই নিয়মে কমপ্রেস (মাপ ও সাইজ — ImageOptimizer)
                    int tgtSide = ImageOptimizer.targetSide(Math.max(w, h));
                    Bitmap sized = out;
                    if (Math.max(w, h) > tgtSide) {
                        float sc = (float) tgtSide / Math.max(w, h);
                        sized = Bitmap.createScaledBitmap(out, Math.max(1, Math.round(w * sc)),
                                Math.max(1, Math.round(h * sc)), true);
                    }
                    ImageOptimizer.Result rr = ImageOptimizer.smart(sized,
                            ph.comp.bytes == null ? 0 : ph.comp.bytes.length, null, "image/jpeg");
                    data = rr.bytes;
                    newMime = rr.mime;
                    newExt = rr.ext;
                    if (sized != out) sized.recycle();
                    float s = 720f / Math.max(w, h);
                    prev = s < 1f ? Bitmap.createScaledBitmap(out, Math.max(1, Math.round(w * s)),
                            Math.max(1, Math.round(h * s)), true) : out;
                } catch (Throwable e) {
                    err = "ছবি সেভ করা যায়নি";
                }
                final byte[] fd = data;
                final String fm = newMime, fx = newExt;
                final Bitmap fp = prev;
                final String fe = err;
                MAIN.post(() -> {
                    if (fe != null || fd == null) {
                        Toast.makeText(ctx, fe == null ? "ছবি সেভ করা যায়নি" : fe, Toast.LENGTH_SHORT).show();
                        done.setEnabled(true);
                        done.setText("DONE");
                        return;
                    }
                    ph.comp.bytes = fd;
                    ph.comp.mime = fm;
                    ph.comp.ext = fx;
                    ph.comp.preview = fp;
                    ph.key = null;                 // বদলেছে, তাই আবার আপলোড হবে
                    dlg.dismiss();
                    if (onDone != null) onDone.run();
                });
            }).start();
        });

        dlg.setContentView(root, new ViewGroup.LayoutParams(-1, -1));
        dlg.show();
    }

    private static LinearLayout tab(Context ctx, android.graphics.drawable.Drawable icon, String label) {
        LinearLayout t = new LinearLayout(ctx);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView iv = new ImageView(ctx);
        iv.setImageDrawable(icon);
        t.addView(iv, new LinearLayout.LayoutParams(dp(ctx, 28), dp(ctx, 28)));
        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(17);
        tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dp(ctx, 4);
        t.addView(tv, lp);
        t.setPadding(dp(ctx, 4), dp(ctx, 2), dp(ctx, 4), dp(ctx, 2));
        return t;
    }

    private static void markCell(Context ctx, View cell, boolean on) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(ctx, 8));
        if (on) g.setStroke(dp(ctx, 2), 0xFF1877F2);
        cell.setBackground(g);
    }

    private static void styleChip(Context ctx, TextView t, boolean on) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(ctx, 20));
        g.setColor(on ? Color.WHITE : 0xFF3A3B3C);
        t.setBackground(g);
        t.setTextColor(on ? Color.BLACK : Color.WHITE);
    }
}
