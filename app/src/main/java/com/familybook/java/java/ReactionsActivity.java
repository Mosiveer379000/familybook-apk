package com.familybook.app;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * রিঅ্যাকশন তালিকা: কে কোন রিঅ্যাকশন দিয়েছে।
 * Intent extra: "postId" (আবশ্যক) — শুধু এটা দিলে পোস্টের রিঅ্যাকশন (post_reactions)।
 * সাথে "commentId" দিলে ওই কমেন্ট/রিপ্লাইয়ের রিঅ্যাকশন (comment_reactions + comment_dislikes)।
 */
public class ReactionsActivity extends Activity {
    private static final String[] KEYS = {"all", "like", "love", "check", "stop", "angry", "haha", "wow", "unlike"};
    private static final int FB_BLUE = 0xFF1877F2;
    private static final int FB_GRAY = 0xFF65676B;

    private final List<String[]> entries = new ArrayList<>();
    private final Map<String, String> names = new HashMap<>();
    private final Set<String> requested = new HashSet<>();
    private String selected = "all";
    private String postIdForSound;
    private String commentId;
    private LinearLayout tabs;
    private LinearLayout list;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final String postId = getIntent().getStringExtra("postId");
        postIdForSound = postId;
        String c = getIntent().getStringExtra("commentId");
        commentId = (c == null || c.isEmpty()) ? null : c;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFFFFFFF);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, dp(12), dp(16), dp(12));
        TextView back = new TextView(this);
        back.setText("<");
        back.setTextSize(22.0f);
        back.setPadding(dp(16), 0, dp(16), 0);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ReactionsActivity.this.finish();
            }
        });
        header.addView(back);
        TextView title = new TextView(this);
        title.setText("Reactions");
        title.setTextSize(18.0f);
        title.setTypeface(null, 1);
        title.setTextColor(0xFF1C1E21);
        header.addView(title);
        root.addView(header);

        tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(dp(12), dp(8), dp(12), dp(8));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.addView(tabs);
        root.addView(hs);

        ScrollView sv = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(16), 0, dp(16), dp(16));
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1.0f));
        setContentView(root);

        if (postId == null) {
            finish();
            return;
        }
        loadReactions();
    }

    /** আগে রিঅ্যাকশন, কমেন্ট হলে তারপর dislike — দুটো এলে নাম আনি ও আঁকি */
    private void loadReactions() {
        entries.clear();
        final String path = commentId == null
                ? "/rest/v1/post_reactions?post_id=eq." + postIdForSound + "&select=user_id,reaction"
                : "/rest/v1/comment_reactions?comment_id=eq." + commentId + "&select=user_id,reaction";
        SupabaseRest.call("GET", path, null, null, true, r -> {
            if (isFinishing() || isDestroyed()) return;
            if (!r.ok()) {
                Toast.makeText(this, SupabaseRest.errorText(r), Toast.LENGTH_LONG).show();
                return;
            }
            try {
                JSONArray a = new JSONArray(r.body);
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    String uid = o.optString("user_id", "");
                    String v = o.isNull("reaction") ? null : o.optString("reaction", null);
                    if (!uid.isEmpty()) {
                        entries.add(new String[]{uid, isLikeKey(v) ? "like" : v});
                    }
                }
            } catch (Exception ignored) { }
            if (commentId == null) {
                finishLoading();
            } else {
                loadDislikes();
            }
        });
    }

    private void loadDislikes() {
        SupabaseRest.call("GET",
                "/rest/v1/comment_dislikes?comment_id=eq." + commentId + "&select=user_id",
                null, null, true, r -> {
            if (isFinishing() || isDestroyed()) return;
            if (r.ok()) {
                try {
                    JSONArray a = new JSONArray(r.body);
                    for (int i = 0; i < a.length(); i++) {
                        String uid = a.getJSONObject(i).optString("user_id", "");
                        if (!uid.isEmpty()) entries.add(new String[]{uid, "unlike"});
                    }
                } catch (Exception ignored) { }
            }
            finishLoading();
        });
    }

    private void finishLoading() {
        redraw();
        loadNames();
    }

    /** সবার নাম একটাই অনুরোধে: profiles?id=in.(...) */
    private void loadNames() {
        final List<String> need = new ArrayList<>();
        for (String[] row : entries) {
            String uid = row[0];
            if (uid != null && !uid.isEmpty() && requested.add(uid)) need.add(uid);
        }
        if (need.isEmpty()) return;

        StringBuilder ids = new StringBuilder();
        for (String uid : need) {
            if (ids.length() > 0) ids.append(',');
            ids.append(uid);
        }
        SupabaseRest.call("GET",
                "/rest/v1/profiles?id=in.(" + ids + ")&select=id,name",
                null, null, true, r -> {
            if (isFinishing() || isDestroyed()) return;
            if (r.ok()) {
                try {
                    JSONArray a = new JSONArray(r.body);
                    for (int i = 0; i < a.length(); i++) {
                        JSONObject o = a.getJSONObject(i);
                        String n = o.isNull("name") ? "" : o.optString("name", "").trim();
                        names.put(o.optString("id", ""), n.isEmpty() ? "User" : n);
                    }
                } catch (Exception ignored) { }
            }
            for (String uid : need) {
                if (!names.containsKey(uid)) names.put(uid, "User");
            }
            redraw();
        });
    }

    private void redraw() {
        Map<String, Integer> counts = new HashMap<>();
        for (String[] row : entries) {
            Integer old = counts.get(row[1]);
            counts.put(row[1], old == null ? 1 : old + 1);
        }

        tabs.removeAllViews();
        for (final String key : KEYS) {
            boolean isAll = "all".equals(key);
            Integer c = counts.get(key);
            int count = isAll ? entries.size() : (c == null ? 0 : c);
            if (!isAll && count == 0) {
                continue;
            }
            boolean on = key.equals(selected);
            LinearLayout tab = new LinearLayout(this);
            tab.setOrientation(LinearLayout.HORIZONTAL);
            tab.setGravity(Gravity.CENTER_VERTICAL);
            tab.setPadding(dp(12), dp(6), dp(12), dp(6));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius((float) dp(20));
            bg.setColor(on ? 0xFFE7F3FF : 0xFFF0F2F5);
            tab.setBackground(bg);

            if (isAll) {
                TextView t = new TextView(this);
                t.setText("All " + count);
                t.setTextSize(14.0f);
                t.setTypeface(null, 1);
                t.setTextColor(on ? FB_BLUE : 0xFF1C1E21);
                tab.addView(t);
            } else {
                ImageView iv = new ImageView(this);
                ReactionKit.setIcon(iv, key);
                tab.addView(iv, new LinearLayout.LayoutParams(dp(18), dp(18)));
                TextView t = new TextView(this);
                t.setText(String.valueOf(count));
                t.setTextSize(14.0f);
                t.setPadding(dp(4), 0, 0, 0);
                t.setTextColor(on ? FB_BLUE : 0xFF1C1E21);
                tab.addView(t);
            }
            tab.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    selected = key;
                    // সেকশনে ক্লিক করলে ওই রিঅ্যাকশনের সাউন্ড একবারই বাজবে (যত জনই দিক না কেন)
                    if (!"all".equals(key) && !"unlike".equals(key)) {
                        ReactionKit.playFor(ReactionsActivity.this, postIdForSound, key);
                    }
                    redraw();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = dp(8);
            tabs.addView(tab, lp);
        }

        list.removeAllViews();
        int shown = 0;
        for (String[] row : entries) {
            if (!"all".equals(selected) && !selected.equals(row[1])) {
                continue;
            }
            shown++;
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setPadding(0, dp(10), 0, dp(10));
            TextView name = new TextView(this);
            String n = names.get(row[0]);
            name.setText(n == null ? "..." : n);
            name.setTextSize(15.0f);
            name.setTextColor(0xFF1C1E21);
            line.addView(name, new LinearLayout.LayoutParams(0, -2, 1.0f));
            ImageView icon = new ImageView(this);
            ReactionKit.setIcon(icon, row[1]);
            line.addView(icon, new LinearLayout.LayoutParams(dp(22), dp(22)));
            list.addView(line);
        }
        if (shown == 0) {
            TextView empty = new TextView(this);
            empty.setText("No reactions yet");
            empty.setTextColor(FB_GRAY);
            empty.setPadding(0, dp(24), 0, 0);
            list.addView(empty);
        }
    }

    private static boolean isLikeKey(String key) {
        return key == null || "like".equals(key) || "true".equals(key);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
