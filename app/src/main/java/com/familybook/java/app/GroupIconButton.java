package com.familybook.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;

import androidx.appcompat.widget.AppCompatTextView;

/**
 * আইকন (drawableStart) + লেখা একসাথে বাটনের ঠিক মাঝখানে বসায়।
 * সাধারণ TextView-তে আইকন বাটনের কিনারায় লেগে থাকে আর লেখা মাঝখানে যায়, ফলে মাঝে বড় ফাঁক পড়ে।
 * XML-এ gravity="start|center_vertical" রাখুন; আইকন না থাকলে শুধু লেখাই মাঝে আসে।
 */
public class GroupIconButton extends AppCompatTextView {

    public GroupIconButton(Context c) { super(c); }
    public GroupIconButton(Context c, AttributeSet a) { super(c, a); }
    public GroupIconButton(Context c, AttributeSet a, int s) { super(c, a, s); }

    @Override
    protected void onDraw(Canvas canvas) {
        Drawable icon = getCompoundDrawablesRelative()[0];
        if (icon == null) icon = getCompoundDrawables()[0];
        float textW = getPaint().measureText(getText().toString());
        float body = textW;
        if (icon != null) body += icon.getIntrinsicWidth() + getCompoundDrawablePadding();
        float dx = (getWidth() - body) / 2f - getPaddingLeft();
        if (dx < 0) dx = 0;
        canvas.save();
        canvas.translate(dx, 0);
        super.onDraw(canvas);
        canvas.restore();
    }
}
