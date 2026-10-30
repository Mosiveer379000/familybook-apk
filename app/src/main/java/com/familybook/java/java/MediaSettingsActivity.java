package com.familybook.app;

import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/** Settings → Media: "Stop Auto play in Profile, Shorts & Feeds" */
public class MediaSettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        float d = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        // হেডার: ← Media
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(Math.round(8 * d), Math.round(8 * d), Math.round(16 * d), Math.round(8 * d));
        ImageView back = new ImageView(this);
        back.setImageResource(R.drawable.ic_back);
        back.setColorFilter(Color.BLACK);
        int p = Math.round(10 * d);
        back.setPadding(p, p, p, p);
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(Math.round(46 * d), Math.round(46 * d)));
        TextView title = new TextView(this);
        title.setText("Media");
        title.setTextColor(Color.BLACK);
        title.setTextSize(20);
        title.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
        tl.setMarginStart(Math.round(8 * d));
        top.addView(title, tl);
        root.addView(top, new LinearLayout.LayoutParams(-1, -2));

        // সারি: লেখা + সুইচ
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Math.round(16 * d), Math.round(14 * d), Math.round(16 * d), Math.round(14 * d));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t1 = new TextView(this);
        t1.setText("Stop Auto play in Profile, Shorts & Feeds");
        t1.setTextColor(Color.BLACK);
        t1.setTextSize(16);
        t1.setTypeface(null, Typeface.BOLD);
        col.addView(t1);
        TextView t2 = new TextView(this);
        t2.setText("Videos will not play automatically. Tap a video to play it.");
        t2.setTextColor(0xFF777777);
        t2.setTextSize(13);
        LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(-2, -2);
        l2.topMargin = Math.round(2 * d);
        col.addView(t2, l2);
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));

        Switch sw = new Switch(this);
        sw.setChecked(!MediaPrefs.autoplay(this));
        sw.setOnCheckedChangeListener((CompoundButton v, boolean on) -> MediaPrefs.setStopAutoplay(this, on));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2);
        sl.setMarginStart(Math.round(12 * d));
        row.addView(sw, sl);
        root.addView(row, new LinearLayout.LayoutParams(-1, -2));

        setContentView(root);
    }
}
