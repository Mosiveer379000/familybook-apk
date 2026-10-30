package com.familybook.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

/**
 * গোল (ring) প্রোগ্রেস বার: নীল, ছোট, মাঝখানে ১–১০০%।
 * ঘুরতে থাকা (indeterminate) স্পিনারের বদলে — আপলোড কতটুকু হলো সেটাই দেখায়।
 */
public class RingProgressView extends View {

    private static final int BLUE = 0xFF1877F2;
    private static final int TRACK = 0xFFD6E6FD;

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private float shown = 0f;       // এখন যা আঁকা হচ্ছে (০–১০০)
    private int target = 0;
    private boolean showText = true;
    private ValueAnimator anim;

    public RingProgressView(Context c) { this(c, null); }

    public RingProgressView(Context c, AttributeSet a) {
        super(c, a);
        float d = getResources().getDisplayMetrics().density;
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeWidth(3f * d);
        track.setColor(TRACK);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeWidth(3f * d);
        arc.setStrokeCap(Paint.Cap.ROUND);
        arc.setColor(BLUE);
        txt.setColor(BLUE);
        txt.setTextAlign(Paint.Align.CENTER);
        txt.setTypeface(Typeface.DEFAULT_BOLD);
        txt.setTextSize(8.5f * d);
    }

    public void setShowText(boolean v) { showText = v; invalidate(); }

    /** বড় রিং-এর জন্য: দাগের মোটা ও মাঝের % লেখার মাপ (dp) */
    public void setSizing(float strokeDp, float textDp) {
        float d = getResources().getDisplayMetrics().density;
        track.setStrokeWidth(strokeDp * d);
        arc.setStrokeWidth(strokeDp * d);
        txt.setTextSize(textDp * d);
        invalidate();
    }

    /** 0–100; আগের মান থেকে মসৃণভাবে এগোয় */
    public void setProgress(int p) {
        p = Math.max(0, Math.min(100, p));
        if (p == target) return;
        target = p;
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(shown, target);
        anim.setDuration(220);
        anim.addUpdateListener(a -> { shown = (float) a.getAnimatedValue(); invalidate(); });
        anim.start();
    }

    public int getProgress() { return target; }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (anim != null) anim.cancel();
    }

    @Override
    protected void onDraw(Canvas c) {
        float half = arc.getStrokeWidth() / 2f + 0.5f;
        box.set(half, half, getWidth() - half, getHeight() - half);
        c.drawOval(box, track);
        if (shown > 0f) c.drawArc(box, -90f, 360f * shown / 100f, false, arc);
        if (showText) {
            String s = Math.round(shown) + "%";
            float y = getHeight() / 2f - (txt.descent() + txt.ascent()) / 2f;
            c.drawText(s, getWidth() / 2f, y, txt);
        }
    }
}
