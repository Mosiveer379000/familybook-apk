package com.familybook.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * ফেসবুকের মতো ওয়েভফর্ম: মাঝের স্থির বক্সটাই বাছাই করা ক্লিপ।
 * আঙুল দিয়ে ডানে-বামে টানলে গানের অন্য অংশ বক্সের ভেতরে আসে।
 */
public class WaveformView extends View {

    public interface OnStartChange {
        /** fromDragEnd = true হলে আঙুল তুলেছে (তখন গান নতুন জায়গা থেকে চালান) */
        void onStart(int startMs, boolean fromDragEnd);
    }

    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint box = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private int totalMs = 30000;
    private int clipMs = 15000;
    private int startMs = 0;
    private int progressMs = 0;
    private int seed = 7;
    private float lastX;
    private OnStartChange listener;

    public WaveformView(Context c) { super(c); init(); }
    public WaveformView(Context c, AttributeSet a) { super(c, a); init(); }
    public WaveformView(Context c, AttributeSet a, int s) { super(c, a, s); init(); }

    private void init() {
        bar.setStrokeCap(Paint.Cap.ROUND);
    }

    public void setOnStartChange(OnStartChange l) { listener = l; }

    public void setSeed(String s) { seed = s == null ? 7 : Math.abs(s.hashCode() % 9973) + 3; invalidate(); }

    public void setTotalMs(int t) {
        if (t < 1000) return;
        totalMs = t;
        clampStart();
        invalidate();
    }

    public void setClipMs(int c) { clipMs = c; clampStart(); invalidate(); }

    public void setStartMs(int s) { startMs = s; clampStart(); invalidate(); }

    public int getStartMs() { return startMs; }

    /** ক্লিপের ভেতরে কতটা বাজা হয়েছে (ms) */
    public void setProgressMs(int p) {
        progressMs = Math.max(0, Math.min(clipMs, p));
        invalidate();
    }

    private void clampStart() {
        int max = Math.max(0, totalMs - clipMs);
        if (startMs > max) startMs = max;
        if (startMs < 0) startMs = 0;
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    private float height(int i) {
        double a = Math.abs(Math.sin(i * 0.53 + seed) * Math.cos(i * 0.17 + seed * 0.7));
        double b = Math.abs(Math.sin(i * 1.7 + seed * 1.3));
        return (float) (0.18 + 0.82 * (0.65 * a + 0.35 * b));
    }

    @Override
    protected void onDraw(Canvas cv) {
        float w = getWidth(), h = getHeight();
        float winW = w * 0.62f;
        float winL = (w - winW) / 2f, winR = winL + winW;
        float pxPerMs = winW / clipMs;
        float scrollX = startMs * pxPerMs;
        float contentLeft = winL - scrollX;
        float contentW = totalMs * pxPerMs;

        // বাছাই বক্স
        box.setColor(0xFF4A4A4A);
        rect.set(winL, h * 0.06f, winR, h * 0.94f);
        cv.drawRoundRect(rect, dp(14), dp(14), box);
        // যতটা বাজা হয়েছে (হালকা রঙ)
        if (progressMs > 0) {
            box.setColor(0xFF7A7A7A);
            rect.set(winL, h * 0.06f, winL + winW * progressMs / (float) clipMs, h * 0.94f);
            cv.drawRoundRect(rect, dp(14), dp(14), box);
        }

        float step = dp(7f);
        bar.setStrokeWidth(dp(3.6f));
        int n = (int) (contentW / step);
        for (int i = 0; i <= n; i++) {
            float x = contentLeft + i * step + step / 2f;
            if (x < -step || x > w + step) continue;
            float bh = height(i) * h * 0.74f;
            boolean inside = x >= winL && x <= winR;
            bar.setColor(inside ? 0xFFFFFFFF : 0xFF7C7C7C);
            cv.drawLine(x, h / 2f - bh / 2f, x, h / 2f + bh / 2f, bar);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float winW = getWidth() * 0.62f;
        float pxPerMs = winW / clipMs;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = e.getX();
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = e.getX() - lastX;
                lastX = e.getX();
                int old = startMs;
                startMs -= Math.round(dx / pxPerMs);   // ডানে টানলে আগের অংশ আসে
                clampStart();
                if (old != startMs) {
                    invalidate();
                    if (listener != null) listener.onStart(startMs, false);
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (listener != null) listener.onStart(startMs, true);
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }
}
