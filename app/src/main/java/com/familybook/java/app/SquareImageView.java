package com.familybook.app;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

/**
 * ফিড / প্রোফাইল / গ্রুপ পোস্ট কার্ডের ছবি-ভিউ (তিন জায়গায় একই PostAdapter)।
 *  - natural = true (GIF, প্রোফাইল ছবি, কভার ফটো): সব সময় নিজের অনুপাতে পুরোটা (লম্বা হলেও পুরো)
 *  - অন্য ছবি (auto):
 *      • ৯:১৬ বা তার চেয়েও লম্বা  → কার্ডে ৪:৫ জায়গায় মাঝখান থেকে ক্রপ; ট্যাপ করলে ভিউয়ারে পুরো ছবি
 *      • বাকি সব সাইজ             → ক্রপ ছাড়া, নিজের অনুপাতে পুরোটা
 */
public class SquareImageView extends ImageView {

    /** উচ্চতা / চওড়া এর বেশি হলে ছবিটা "লম্বা" (৯:১৬ = ১.৭৭৭…; একটু সহনশীলতা রাখা হয়েছে) */
    static final float TALL_RATIO = 1.76f;
    /** লম্বা ছবির কার্ড: চওড়া ৪, উচ্চতা ৫ */
    static final float TALL_CARD_RATIO = 1.25f;

    private boolean natural = false;
    private float natRatio = 1f;      // উচ্চতা / চওড়া (ছবি আসার আগে ১:১, বা জানা থাকলে সেটা)
    private float hint = 0f;          // ছবি আসার আগে জানা অনুপাত (লিস্ট লাফানো কমায়)

    public SquareImageView(Context c) {
        super(c);
        setScaleType(ScaleType.CENTER_CROP);
    }

    public void setNatural(boolean on) {
        if (natural == on) return;
        natural = on;
        setScaleType(on ? ScaleType.FIT_XY : ScaleType.CENTER_CROP);
        requestLayout();
    }

    /** ছবি লোড হওয়ার আগেই জানা অনুপাত (উচ্চতা/চওড়া); 0 = জানা নেই */
    public void setRatioHint(float ratio) {
        if (hint == ratio) return;
        hint = ratio;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        float r = hint > 0f ? hint : natRatio;
        Drawable d = getDrawable();
        if (d != null && d.getIntrinsicWidth() > 0 && d.getIntrinsicHeight() > 0) {
            r = (float) d.getIntrinsicHeight() / d.getIntrinsicWidth();
            natRatio = r;
        }
        int h;
        if (natural) {
            h = Math.round(w * r);                       // GIF / প্রোফাইল / কভার: সব সময় পুরো
        } else if (r >= TALL_RATIO) {
            h = Math.round(w * TALL_CARD_RATIO);         // লম্বা ছবি: ৪:৫ ক্রপ (ট্যাপে পুরো)
        } else {
            h = Math.round(w * r);                       // বাকি সাইজ: পুরোটা
        }
        setMeasuredDimension(w, h);
    }
}
