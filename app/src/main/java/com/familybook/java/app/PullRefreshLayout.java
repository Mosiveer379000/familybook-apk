package com.familybook.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.ProgressBar;

/**
 * উপর থেকে টেনে নামালে রিফ্রেশ। কোনো বাইরের লাইব্রেরি লাগে না।
 * ভেতরে প্রথম child (RecyclerView) থাকবে।
 */
public class PullRefreshLayout extends FrameLayout {

    public interface OnRefreshListener { void onRefresh(); }

    private final ProgressBar spinner;
    private final int spinnerSize;
    private final float threshold, maxPull, holdOffset;
    private final int touchSlop;

    private View target;
    private OnRefreshListener listener;
    private boolean refreshing, dragging;
    private float startY, dragStartY, pull;
    private ValueAnimator anim;

    public PullRefreshLayout(Context c) { this(c, null); }

    public PullRefreshLayout(Context c, AttributeSet a) {
        super(c, a);
        float d = c.getResources().getDisplayMetrics().density;
        spinnerSize = (int) (44 * d);
        threshold = 72 * d;
        maxPull = 120 * d;
        holdOffset = 64 * d;
        touchSlop = ViewConfiguration.get(c).getScaledTouchSlop();

        spinner = new ProgressBar(c);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xFFFFFFFF);
        spinner.setBackground(bg);
        int pad = (int) (8 * d);
        spinner.setPadding(pad, pad, pad, pad);
        spinner.setElevation(4 * d);
        spinner.getIndeterminateDrawable().setColorFilter(0xFF00B88A, PorterDuff.Mode.SRC_IN);
        spinner.setAlpha(0f);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        target = getChildAt(0);
        LayoutParams lp = new LayoutParams(spinnerSize, spinnerSize,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        addView(spinner, lp);
        spinner.setTranslationY(-spinnerSize);
    }

    public void setOnRefreshListener(OnRefreshListener l) { listener = l; }

    public void setColorSchemeColors(int... colors) {
        if (colors != null && colors.length > 0)
            spinner.getIndeterminateDrawable().setColorFilter(colors[0], PorterDuff.Mode.SRC_IN);
    }

    public boolean isRefreshing() { return refreshing; }

    public void setRefreshing(boolean r) {
        if (r == refreshing) return;
        refreshing = r;
        animateTo(r ? holdOffset : 0f);
    }

    // ---------- টাচ ----------
    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (target == null || refreshing || !isEnabled()) return false;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                startY = ev.getY();
                dragging = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (!dragging && ev.getY() - startY > touchSlop
                        && !target.canScrollVertically(-1)) {
                    dragging = true;
                    dragStartY = ev.getY();
                    if (anim != null) anim.cancel();
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                break;
        }
        return dragging;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!dragging) return false;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                float p = (ev.getY() - dragStartY) * 0.5f;
                setPull(Math.max(0f, Math.min(p, maxPull)));
                return true;
            case MotionEvent.ACTION_UP:
                dragging = false;
                if (pull >= threshold) {
                    refreshing = true;
                    animateTo(holdOffset);
                    if (listener != null) listener.onRefresh();
                } else {
                    animateTo(0f);
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                animateTo(0f);
                return true;
        }
        return true;
    }

    // ---------- ছবি ----------
    private void setPull(float v) {
        pull = v;
        if (target != null) target.setTranslationY(v);
        spinner.setTranslationY(v - spinnerSize);
        spinner.setAlpha(Math.min(1f, v / threshold));
        spinner.setScaleX(0.6f + 0.4f * Math.min(1f, v / threshold));
        spinner.setScaleY(spinner.getScaleX());
    }

    private void animateTo(float to) {
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(pull, to);
        anim.setDuration(220);
        anim.addUpdateListener(a -> setPull((float) a.getAnimatedValue()));
        anim.start();
    }
}
