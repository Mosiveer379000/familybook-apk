package com.familybook.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

/** ছোট আইকনগুলো কোডেই আঁকা (আলাদা drawable ফাইল লাগে না) */
public final class Icons {
    private Icons() { }

    // res/drawable-এ এই নামে ফাইল রাখলে অ্যাপ সেটাই ব্যবহার করবে (রঙ কোড থেকে বসে যায়)।
    // ফাইল না থাকলে কোডে আঁকা সাধারণ আইকন/চিহ্ন দেখায়।
    public static final String NAME_ADD_MEDIA = "ic_add_media";   // "Add media" বাটনের আইকন
    public static final String NAME_GRID      = "ic_grid_layout"; // গ্রিড/সাজানো বদলের আইকন
    public static final String NAME_EDIT      = "ic_edit";        // Edit (পেন্সিল/ব্রাশ)
    public static final String NAME_EFFECTS   = "ic_effects";     // Effects ট্যাব
    public static final String NAME_CROP      = "ic_crop";        // Crop ট্যাব
    public static final String NAME_CLOSE     = "ic_close";       // ✕ (ছবি বাদ দেওয়া)
    public static final String NAME_BACK      = "ic_back";        // ← (পেছনে যাওয়া)
    public static final String NAME_MORE      = "ic_more";        // ••• (আরও অপশন)

    /** drawable পেলে tint করে দেয়, না পেলে null */
    private static Drawable named(Context c, String name, int tint) {
        int id = c.getResources().getIdentifier(name, "drawable", c.getPackageName());
        if (id == 0) return null;
        Drawable d = c.getResources().getDrawable(id, c.getTheme());
        if (d == null) return null;
        d = d.mutate();
        d.setTint(tint);
        return d;
    }

    /** আইকন থাকলে ImageView, না থাকলে fallback লেখার TextView */
    public static View view(Context c, String name, String fallback, float sp, int tint) {
        Drawable d = named(c, name, tint);
        if (d != null) {
            ImageView iv = new ImageView(c);
            iv.setImageDrawable(d);
            iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            return iv;
        }
        TextView t = new TextView(c);
        t.setText(fallback);
        t.setTextColor(tint);
        t.setTextSize(sp);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private static Paint stroke(int color, float w) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setColor(color);
        p.setStrokeWidth(w);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        return p;
    }

    private static Drawable wrap(Context c, Bitmap b) {
        return new BitmapDrawable(c.getResources(), b);
    }

    /** গ্রিড/কোলাজ: বামে লম্বা ঘর, ডানে দুটি */
    public static Drawable grid(Context c, int px, int color) {
        Drawable ext = named(c, NAME_GRID, color);
        if (ext != null) return ext;
        Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        Paint p = stroke(color, px * 0.09f);
        float m = px * 0.16f;
        cv.drawRoundRect(new RectF(m, m, px - m, px - m), px * 0.1f, px * 0.1f, p);
        cv.drawLine(px * 0.46f, m, px * 0.46f, px - m, p);
        cv.drawLine(px * 0.46f, px * 0.5f, px - m, px * 0.5f, p);
        return wrap(c, b);
    }

    /** পেন্সিল / ব্রাশ */
    public static Drawable pencil(Context c, int px, int color) {
        Drawable ext = named(c, NAME_EDIT, color);
        if (ext != null) return ext;
        Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        Paint p = stroke(color, px * 0.17f);
        cv.drawLine(px * 0.36f, px * 0.64f, px * 0.76f, px * 0.24f, p);
        Paint f = new Paint(Paint.ANTI_ALIAS_FLAG);
        f.setColor(color);
        Path t = new Path();
        t.moveTo(px * 0.14f, px * 0.86f);
        t.lineTo(px * 0.20f, px * 0.62f);
        t.lineTo(px * 0.38f, px * 0.80f);
        t.close();
        cv.drawPath(t, f);
        return wrap(c, b);
    }

    /** Effects: তিনটি জোড়া বৃত্ত */
    public static Drawable effects(Context c, int px, int color) {
        Drawable ext = named(c, NAME_EFFECTS, color);
        if (ext != null) return ext;
        Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        Paint p = stroke(color, px * 0.07f);
        float r = px * 0.22f;
        cv.drawCircle(px * 0.5f, px * 0.34f, r, p);
        cv.drawCircle(px * 0.34f, px * 0.62f, r, p);
        cv.drawCircle(px * 0.66f, px * 0.62f, r, p);
        return wrap(c, b);
    }

    /** Crop */
    public static Drawable crop(Context c, int px, int color) {
        Drawable ext = named(c, NAME_CROP, color);
        if (ext != null) return ext;
        Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        Paint p = stroke(color, px * 0.08f);
        Path a = new Path();
        a.moveTo(px * 0.30f, px * 0.08f);
        a.lineTo(px * 0.30f, px * 0.70f);
        a.lineTo(px * 0.92f, px * 0.70f);
        Path d = new Path();
        d.moveTo(px * 0.08f, px * 0.30f);
        d.lineTo(px * 0.70f, px * 0.30f);
        d.lineTo(px * 0.70f, px * 0.92f);
        cv.drawPath(a, p);
        cv.drawPath(d, p);
        return wrap(c, b);
    }

    /** Add media: ছবির ঘরে + চিহ্ন */
    public static Drawable addMedia(Context c, int px, int color) {
        Drawable ext = named(c, NAME_ADD_MEDIA, color);
        if (ext != null) return ext;
        Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        Paint p = stroke(color, px * 0.09f);
        float m = px * 0.14f;
        cv.drawRoundRect(new RectF(m, m, px - m, px - m), px * 0.14f, px * 0.14f, p);
        cv.drawLine(px * 0.5f, px * 0.3f, px * 0.5f, px * 0.7f, p);
        cv.drawLine(px * 0.3f, px * 0.5f, px * 0.7f, px * 0.5f, p);
        return wrap(c, b);
    }
}
