package com.familybook.app;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

/**
 * সরল Flow layout: চিপগুলো এক লাইনে জায়গা না হলে নিচের লাইনে নেমে যায়।
 * (Category পেজের নীল চিপ ও Popular categories-এ ব্যবহার হয়)
 */
public class FlowLayout extends ViewGroup {

    private final int hGap;
    private final int vGap;

    public FlowLayout(Context c) {
        this(c, null);
    }

    public FlowLayout(Context c, AttributeSet a) {
        super(c, a);
        float d = c.getResources().getDisplayMetrics().density;
        hGap = (int) (8 * d + 0.5f);
        vGap = (int) (8 * d + 0.5f);
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int maxW = MeasureSpec.getSize(wSpec) - getPaddingLeft() - getPaddingRight();
        int x = 0, y = 0, rowH = 0, usedMax = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            if (c.getVisibility() == GONE) continue;
            c.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            int cw = c.getMeasuredWidth();
            int ch = c.getMeasuredHeight();
            if (x > 0 && x + cw > maxW) {
                x = 0;
                y += rowH + vGap;
                rowH = 0;
            }
            x += cw + hGap;
            rowH = Math.max(rowH, ch);
            usedMax = Math.max(usedMax, x - hGap);
        }
        int height = y + rowH + getPaddingTop() + getPaddingBottom();
        int width = usedMax + getPaddingLeft() + getPaddingRight();
        setMeasuredDimension(resolveSize(width, wSpec), resolveSize(height, hSpec));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int maxW = r - l - getPaddingLeft() - getPaddingRight();
        int x = 0, y = 0, rowH = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            if (c.getVisibility() == GONE) continue;
            int cw = c.getMeasuredWidth();
            int ch = c.getMeasuredHeight();
            if (x > 0 && x + cw > maxW) {
                x = 0;
                y += rowH + vGap;
                rowH = 0;
            }
            int left = getPaddingLeft() + x;
            int top = getPaddingTop() + y;
            c.layout(left, top, left + cw, top + ch);
            x += cw + hGap;
            rowH = Math.max(rowH, ch);
        }
    }
}
