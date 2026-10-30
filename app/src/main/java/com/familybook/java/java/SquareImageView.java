package com.familybook.app;

import android.content.Context;
import android.widget.ImageView;

/** চওড়ার সমান উচ্চতার (১:১) ImageView — ফিডের পোস্ট কার্ডের ছবির জন্য */
public class SquareImageView extends ImageView {

    public SquareImageView(Context c) {
        super(c);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        setMeasuredDimension(w, w);
    }
}
