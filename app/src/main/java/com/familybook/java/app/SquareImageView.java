package com.familybook.app;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

/**
 * ফিডের পোস্ট কার্ডের ছবি-ভিউ।
 *  - ডিফল্ট: চওড়ার সমান উচ্চতা (১:১)
 *  - natural = true (GIF, প্রোফাইল ছবি, কভার ফটো): ছবির নিজের অনুপাতে পুরোটা দেখায় (লম্বা হলে লম্বাই)
 */
public class SquareImageView extends ImageView {

    private boolean natural = false;
    private float natRatio = 1f;      // উচ্চতা / চওড়া (ছবি আসার আগে ১:১)

    public SquareImageView(Context c) {
        super(c);
    }

    public void setNatural(boolean on) {
        if (natural == on) return;
        natural = on;
        setScaleType(on ? ScaleType.FIT_XY : ScaleType.CENTER_CROP);
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = w;
        if (natural) {
            Drawable d = getDrawable();
            if (d != null && d.getIntrinsicWidth() > 0 && d.getIntrinsicHeight() > 0) {
                natRatio = (float) d.getIntrinsicHeight() / d.getIntrinsicWidth();
            }
            h = Math.round(w * natRatio);
        }
        setMeasuredDimension(w, h);
    }
}
