package com.familybook.app;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Top banner message (replaces Toast). Black background, white English text,
 * shown at the TOP of the screen (below the status bar) so the keyboard never covers it.
 * Usage: TopToast.show(context, "Saved!");
 */
final class TopToast {
    private TopToast() { }

    private static final int SHOW_MS = 2500;

    static void show(Context ctx, String msg) { show(ctx, msg, false); }

    /** White background, black text */
    static void showLight(Context ctx, String msg) { show(ctx, msg, true); }

    private static void show(Context ctx, String msg, boolean light) {
        if (!(ctx instanceof Activity) || msg == null) return;
        final Activity act = (Activity) ctx;
        if (act.isFinishing()) return;
        final ViewGroup root = act.findViewById(android.R.id.content);
        if (root == null) return;

        final float d = act.getResources().getDisplayMetrics().density;

        // remove previous banner so messages never stack
        View old = root.findViewWithTag("top_toast");
        if (old != null) root.removeView(old);

        TextView tv = new TextView(act);
        tv.setTag("top_toast");
        tv.setText(msg);
        tv.setTextColor(light ? Color.BLACK : Color.WHITE);
        tv.setTextSize(15);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding((int) (18 * d), (int) (12 * d), (int) (18 * d), (int) (12 * d));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(light ? Color.WHITE : Color.BLACK);
        if (light) bg.setStroke((int) Math.max(1, d), 0xFFD0D0D0);
        bg.setCornerRadius(14 * d);
        tv.setBackground(bg);
        tv.setElevation(24 * d);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.topMargin = (int) (statusBar(act) + 10 * d);
        lp.leftMargin = (int) (16 * d);
        lp.rightMargin = (int) (16 * d);
        tv.setAlpha(0f);
        root.addView(tv, lp);
        tv.animate().alpha(1f).setDuration(150).start();

        tv.postDelayed(() -> {
            if (tv.getParent() == null) return;
            tv.animate().alpha(0f).setDuration(200)
                    .withEndAction(() -> {
                        if (tv.getParent() instanceof ViewGroup) ((ViewGroup) tv.getParent()).removeView(tv);
                    }).start();
        }, SHOW_MS);
    }

    private static int statusBar(Activity act) {
        int id = act.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? act.getResources().getDimensionPixelSize(id) : 0;
    }
}
