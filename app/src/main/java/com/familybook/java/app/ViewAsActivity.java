package com.familybook.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;

/** "View as": অন্য (ফলো-না-করা) ইউজার আপনার প্রোফাইল কেমন দেখে তার প্রিভিউ। Exit দিলে Profile Settings-এ ফেরে। */
public class ViewAsActivity extends Activity {

    static void open(Context c, String name, String avatarKey, String coverKey, boolean locked) {
        c.startActivity(new Intent(c, ViewAsActivity.class)
                .putExtra("name", name).putExtra("avatarKey", avatarKey)
                .putExtra("coverKey", coverKey).putExtra("locked", locked));
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        String name = getIntent().getStringExtra("name");
        String avatarKey = getIntent().getStringExtra("avatarKey");
        String coverKey = getIntent().getStringExtra("coverKey");
        boolean locked = getIntent().getBooleanExtra("locked", false);
        if (name == null || name.isEmpty()) name = "Family Member";

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Color.parseColor("#242526"));
        bar.setPadding(dp(16), dp(10), dp(12), dp(10));
        TextView info = new TextView(this);
        info.setText("Viewing as Public");
        info.setTextColor(Color.WHITE);
        info.setTextSize(16);
        info.setTypeface(null, Typeface.BOLD);
        bar.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        Button exit = new Button(this);
        exit.setText("Exit");
        exit.setAllCaps(false);
        exit.setOnClickListener(v -> finish());
        bar.addView(exit);
        root.addView(bar);

        ScrollView sv = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        int w = getResources().getDisplayMetrics().widthPixels;
        int coverH = (w * 319) / 840;
        int av = dp(96);
        FrameLayout head = new FrameLayout(this);
        final ImageView cover = new ImageView(this);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setBackgroundColor(Color.parseColor("#DADDE1"));
        head.addView(cover, new FrameLayout.LayoutParams(-1, coverH));
        final ImageView avatar = new ImageView(this);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        FrameLayout.LayoutParams ap = new FrameLayout.LayoutParams(av, av);
        ap.topMargin = coverH - av / 2;
        ap.setMarginStart(dp(16));
        head.addView(avatar, ap);
        body.addView(head, new LinearLayout.LayoutParams(-1, coverH + av / 2));

        if (coverKey != null) {
            Media.loadBitmap(coverKey, bm -> { if (!isFinishing()) cover.setImageBitmap(bm); });
        }
        if (avatarKey != null) {
            Media.loadBitmap(avatarKey, bm -> {
                if (isFinishing()) return;
                RoundedBitmapDrawable d = RoundedBitmapDrawableFactory.create(getResources(), bm);
                d.setCircular(true);
                avatar.setImageDrawable(d);
            });
        }

        TextView nm = new TextView(this);
        nm.setText(name);
        nm.setTextSize(24);
        nm.setTypeface(null, Typeface.BOLD);
        nm.setTextColor(Color.parseColor("#1C1E21"));
        nm.setPadding(dp(16), dp(10), dp(16), dp(10));
        body.addView(nm);

        // লক থাকলে শুধু Follow বাটন; ফলোয়ার হলে লক দেখাবে না (পরের ধাপে)
        Button follow = new Button(this);
        follow.setText("Follow");
        follow.setAllCaps(false);
        follow.setTextColor(Color.WHITE);
        follow.setBackgroundColor(Color.parseColor("#0866FF"));
        follow.setOnClickListener(v -> TopToast.show(this, "Preview only"));
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(-1, -2);
        fl.setMargins(dp(16), 0, dp(16), dp(12));
        body.addView(follow, fl);

        TextView msg = new TextView(this);
        msg.setTextSize(16);
        msg.setTextColor(Color.parseColor("#65676B"));
        msg.setPadding(dp(16), dp(14), dp(16), dp(14));
        msg.setText(locked
                ? "🔒  " + name + " locked their profile\nOnly followers can see photos, posts and the full-size profile picture."
                : "Public posts, photos and shorts appear here.");
        body.addView(msg);

        setContentView(root);
    }
}
