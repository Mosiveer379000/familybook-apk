package com.familybook.app;   // <-- আপনার Family Book-এর প্যাকেজ নাম দিন

import android.app.Activity;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * মেসেঞ্জার আইকনের ওপর ডানে-ওপরে লাল গোল সংখ্যা (Family Chat-এ যে অ্যাকাউন্ট এখন লগইন আছে তার না-পড়া মেসেজ)।
 * সংখ্যা 0 বা Chat-এ লগআউট থাকলে কিছুই দেখায় না।
 *
 * ব্যবহার (হোম অ্যাক্টিভিটিতে):
 *   private ChatBadge chatBadge;
 *   onCreate: chatBadge = ChatBadge.attach(this, findViewById(R.id.আপনার_মেসেঞ্জার_আইকনের_id));
 *   onResume: chatBadge.start();
 *   onPause : chatBadge.stop();
 */
public final class ChatBadge {

    private static final Uri URI = Uri.parse("content://com.familychat.app.badge/unread");
    private static final long INTERVAL_MS = 10_000;

    private final View anchor;
    private final TextView badge;
    private final FrameLayout content;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private boolean running;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            io.execute(() -> {
                final int n = read(anchor.getContext());
                main.post(() -> show(n));
            });
            main.postDelayed(this, INTERVAL_MS);
        }
    };

    private ChatBadge(Activity a, View anchor) {
        this.anchor = anchor;
        this.content = a.findViewById(android.R.id.content);

        badge = new TextView(a);
        badge.setTextColor(Color.WHITE);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setGravity(Gravity.CENTER);
        int h = dp(a, 4);
        badge.setPadding(h, 0, h, 0);
        badge.setMinWidth(dp(a, 18));
        badge.setMinHeight(dp(a, 18));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFE41E3F);
        bg.setCornerRadius(dp(a, 9));
        badge.setBackground(bg);
        badge.setVisibility(View.GONE);
        badge.setClickable(false);
        badge.setElevation(dp(a, 8));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        content.addView(badge, lp);
    }

    public static ChatBadge attach(Activity a, View anchor) {
        return new ChatBadge(a, anchor);
    }

    public void start() {
        if (running) return;
        running = true;
        main.post(tick);
    }

    public void stop() {
        running = false;
        main.removeCallbacks(tick);
    }

    /** Family Chat না থাকলে/লগআউট থাকলে 0 */
    private static int read(Context c) {
        try (Cursor q = c.getContentResolver().query(URI, null, null, null, null)) {
            if (q != null && q.moveToFirst()) return q.getInt(0);
        } catch (Exception ignored) {
        }
        return 0;
    }

    private void show(int n) {
        if (n <= 0) {
            badge.setVisibility(View.GONE);
            return;
        }
        badge.setText(n > 9 ? "9+" : String.valueOf(n));
        badge.setVisibility(View.VISIBLE);
        place();
    }

    /** আইকনের ডানে-ওপরের কোণে বসায় */
    private void place() {
        anchor.post(() -> {
            int[] a = new int[2];
            int[] c = new int[2];
            anchor.getLocationInWindow(a);
            content.getLocationInWindow(c);
            badge.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) badge.getLayoutParams();
            lp.leftMargin = a[0] - c[0] + anchor.getWidth() - badge.getMeasuredWidth() + dp(anchor.getContext(), 4);
            lp.topMargin = a[1] - c[1] - dp(anchor.getContext(), 4);
            badge.setLayoutParams(lp);
        });
    }

    private static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }
}
