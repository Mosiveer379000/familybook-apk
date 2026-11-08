package com.familybook.app;

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

import org.json.JSONArray;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * মেসেঞ্জার আইকনের ওপর ডানে-ওপরে লাল গোল সংখ্যা: Family Chat-এ যে অ্যাকাউন্ট এখন লগইন, তার না-পড়া মেসেজ।
 * Family Chat অ্যাপ বন্ধ থাকলেও কাজ করে:
 *   মোট = (Chat ফোনে যা তুলেছে কিন্তু দেখা হয়নি) + (সার্ভারে এখনও অপেক্ষা করছে এমন মেসেজ)
 * সার্ভারের অংশটা Family Book নিজে গোনে — তাই Family Book-এ ও Family Chat-এ একই অ্যাকাউন্টে লগইন থাকতে হবে।
 * অ্যাকাউন্ট আলাদা হলে শুধু Chat-এর ফোনে থাকা সংখ্যাটা দেখায়। লগআউট বা ০ হলে কিছুই দেখায় না।
 *
 * ব্যবহার (হোম অ্যাক্টিভিটিতে):
 *   onCreate: chatBadge = ChatBadge.attach(this, findViewById(R.id.btnMessenger));
 *   onResume: chatBadge.start();   onPause/onDestroy: chatBadge.stop();
 */
public final class ChatBadge {

    private static final Uri URI = Uri.parse("content://com.familychat.app.badge/unread");
    private static final long INTERVAL_MS = 10_000;

    private static final class Info {
        String uid = "", table = "";
        int n = 0;
    }

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
                final Info info = read(anchor.getContext());
                main.post(() -> resolve(info));
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

    /** Family Chat-এর Provider থেকে; Chat ইনস্টল না থাকলে বা লগআউট থাকলে null */
    private static Info read(Context c) {
        try (Cursor q = c.getContentResolver().query(URI, null, null, null, null)) {
            if (q != null && q.moveToFirst()) {
                Info i = new Info();
                i.uid = q.getString(0) == null ? "" : q.getString(0);
                i.n = q.getInt(1);
                i.table = q.getString(2) == null ? "" : q.getString(2);
                return i;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void resolve(final Info info) {
        if (!running) return;
        if (info == null || info.uid.isEmpty()) {   // Chat-এ লগআউট / ইনস্টল নেই
            show(0);
            return;
        }
        String me = SupabaseRest.uid();
        // Book ও Chat-এ একই অ্যাকাউন্ট: Chat বন্ধ থাকলেও সার্ভারে অপেক্ষারত মেসেজ গুনতে পারি
        if (me != null && me.equals(info.uid) && !info.table.isEmpty()) {
            SupabaseRest.call("GET",
                    "/rest/v1/" + info.table + "?senderid=neq." + info.uid + "&select=id&limit=100",
                    null, null, true, r -> {
                int pending = 0;
                if (r.ok()) {
                    try {
                        pending = new JSONArray(r.body).length();
                    } catch (Exception ignored) {
                    }
                }
                final int total = info.n + pending;
                main.post(() -> show(total));
            });
        } else {
            show(info.n);   // অন্য অ্যাকাউন্ট: শুধু Chat-এর ফোনে থাকা সংখ্যা
        }
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
