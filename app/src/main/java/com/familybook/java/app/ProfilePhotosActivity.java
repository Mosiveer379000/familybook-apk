package com.familybook.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Photos — পুরো পেজ। উপরে বামে Back তীর, মাঝখানে "Photos"।
 * নিচে ফোল্ডার বাটন: Profile Photos | Cover Photos | Timeline Photos | Albums
 * ডিফল্টে Timeline Photos সিলেক্টেড; সব ছবি উপর-নিচে স্ক্রলে দেখায়।
 */
public class ProfilePhotosActivity extends Activity {

    private static final String[] NAMES = {"Profile Photos", "Cover Photos", "Timeline Photos", "Albums"};
    private static final int[] FOLDERS = {
            ProfileMedia.FOLDER_PROFILE, ProfileMedia.FOLDER_COVER,
            ProfileMedia.FOLDER_TIMELINE, ProfileMedia.FOLDER_ALBUMS};

    private ProfileMedia media;
    private final TextView[] btns = new TextView[4];
    private String uid;
    private boolean isMe;
    private float density;

    private int dp(int v) { return Math.round(v * density); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        density = getResources().getDisplayMetrics().density;
        uid = getIntent().getStringExtra("uid");
        isMe = getIntent().getBooleanExtra("isMe", false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        // ---- হেডার: বামে Back, মাঝখানে Photos ----
        FrameLayout head = new FrameLayout(this);
        ImageView back = new ImageView(this);
        back.setImageResource(R.drawable.ic_back);
        back.setColorFilter(Color.parseColor("#050505"), android.graphics.PorterDuff.Mode.SRC_IN);
        back.setPadding(dp(12), dp(12), dp(12), dp(12));
        back.setContentDescription("Back");
        back.setOnClickListener(v -> finish());
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(dp(48), dp(48));
        bp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        bp.leftMargin = dp(4);
        head.addView(back, bp);
        TextView title = new TextView(this);
        title.setText("Photos");
        title.setTextSize(20);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(Color.parseColor("#050505"));
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-2, -2);
        tp.gravity = Gravity.CENTER;
        head.addView(title, tp);
        root.addView(head, new LinearLayout.LayoutParams(-1, dp(56)));

        // ---- ফোল্ডার বাটন ----
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(4), dp(12), dp(8));
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            TextView t = new TextView(this);
            t.setText(NAMES[i]);
            t.setTextSize(15);
            t.setTypeface(null, Typeface.BOLD);
            t.setSingleLine(true);
            t.setPadding(dp(16), dp(8), dp(16), dp(8));
            t.setOnClickListener(v -> select(idx));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMarginEnd(dp(6));
            row.addView(t, lp);
            btns[i] = t;
        }
        hs.addView(row);
        root.addView(hs, new LinearLayout.LayoutParams(-1, -2));

        View line = new View(this);
        line.setBackgroundColor(Color.parseColor("#E4E6EB"));
        root.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));

        // ---- ছবির এলাকা (উপর-নিচে স্ক্রল) ----
        ScrollView sv = new ScrollView(this);
        LinearLayout holder = new LinearLayout(this);
        holder.setOrientation(LinearLayout.VERTICAL);
        sv.addView(holder, new FrameLayout.LayoutParams(-1, -2));
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        // নিচের নেভিগেশন বার (Photos পেজেও থাকবে)
        root.addView(BottomNav.inflate(this, root));
        setContentView(root);
        BottomNav.attach(this);

        media = new ProfileMedia(this, holder, 0);
        media.setOwner(isMe, uid, this::loadData);
        select(2);          // ডিফল্ট: Timeline Photos
        loadData();
    }

    private void select(int idx) {
        for (int i = 0; i < 4; i++) {
            TextView t = btns[i];
            if (i == idx) {
                GradientDrawable g = new GradientDrawable();
                g.setCornerRadius(dp(30));
                g.setColor(Color.parseColor("#E4E6EB"));
                t.setBackground(g);
                t.setTextColor(Color.parseColor("#0866FF"));
            } else {
                t.setBackground(null);
                t.setTextColor(Color.parseColor("#65676B"));
            }
        }
        media.showFolder(FOLDERS[idx]);
    }

    /** প্রোফাইল/কভার ইতিহাস + পোস্টের ছবি (Timeline) আনে */
    private void loadData() {
        if (uid == null) return;
        Db.get("profiles", "id", uid, (row, err) -> {
            if (isFinishing()) return;
            media.setData(row);
        });
        Db.list("profile_posts?profile_user_id=eq." + uid + "&order=created_at.desc&limit=500", (rows, err) -> {
            if (isFinishing() || rows == null) return;
            final List<Post> list = new ArrayList<>();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject o = rows.optJSONObject(i);
                if (o == null) continue;
                Post p = Post.from(o, Me.uid());
                if ("active".equals(p.status) && (isMe || "public".equals(p.privacy))) list.add(p);
            }
            buildTimeline(list);
            PostImage.prefetch(list, () -> { if (!isFinishing()) buildTimeline(list); });
        });
    }

    private void buildTimeline(List<Post> posts) {
        List<String> keys = new ArrayList<>();
        for (Post p : posts) {
            String val = PostImage.get(p.id);
            if (val == null) continue;
            String s = val.startsWith("#B|") ? val.substring(3) : val;
            for (String k : s.split("\\|")) if (!k.isEmpty()) keys.add(k);
        }
        media.setTimeline(keys);
    }
}
