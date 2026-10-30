package com.familybook.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Profile > More থেকে খোলে: Archive / Trash / Hidden posts।
 * Hide, Archive ও Trash করা পোস্ট Home থেকে সরে এখানে জমা থাকে, এখান থেকেই Restore হয়।
 */
public class PostBinActivity extends Activity implements PostOps.Refreshable {
    public static final String MODE_ARCHIVE = "archive";
    public static final String MODE_TRASH = "trash";
    public static final String MODE_HIDDEN = "hidden";

    private static final long TRASH_TTL_MS = 604800000L;     // 7 দিন
    private static final long ARCHIVE_TTL_MS = 2592000000L;  // 30 দিন

    private String mode = MODE_ARCHIVE;
    private String myUid;
    private LinearLayout list;
    private TextView empty;
    private int generation = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        myUid = SupabaseRest.uid();
        if (!SupabaseRest.isLoggedIn() || myUid == null) {
            finish();
            return;
        }
        String m = getIntent().getStringExtra("mode");
        if (MODE_TRASH.equals(m) || MODE_HIDDEN.equals(m)) {
            mode = m;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(12), dp(12), dp(12), dp(12));
        TextView back = new TextView(this);
        back.setText("\u2190");
        back.setTextSize(24);
        back.setTextColor(0xFF1C1E21);
        back.setPadding(dp(8), 0, dp(16), 0);
        back.setOnClickListener(v -> finish());
        header.addView(back);
        TextView title = new TextView(this);
        title.setText(titleOf());
        title.setTextSize(18);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(0xFF1C1E21);
        header.addView(title);
        root.addView(header);

        TextView note = new TextView(this);
        note.setText(noteOf());
        note.setTextSize(12);
        note.setTextColor(0xFF65676B);
        note.setPadding(dp(20), 0, dp(20), dp(8));
        root.addView(note);

        ScrollView sv = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(14), dp(4), dp(14), dp(20));
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1.0f));

        empty = new TextView(this);
        empty.setText(emptyOf());
        empty.setTextColor(0xFF65676B);
        empty.setTextSize(15);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(20), dp(40), dp(20), dp(40));
        empty.setVisibility(View.GONE);
        list.addView(empty);

        setContentView(root);
        load();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        generation++;   // দেরিতে আসা উত্তর আর আঁকা হবে না
    }

    /** PostOps.Refreshable — unhide-এর পর নিজে থেকে ডাকা হয় */
    @Override
    public void refreshPosts() {
        load();
    }

    private String titleOf() {
        if (MODE_TRASH.equals(mode)) return "Trash";
        if (MODE_HIDDEN.equals(mode)) return "Hidden posts";
        return "Archive";
    }

    private String noteOf() {
        if (MODE_TRASH.equals(mode)) return "Trash posts are deleted automatically after 7 days.";
        if (MODE_HIDDEN.equals(mode)) return "These posts are hidden from your Home feed only.";
        return "Archive posts are deleted automatically after 30 days.";
    }

    private String emptyOf() {
        if (MODE_TRASH.equals(mode)) return "Trash is empty";
        if (MODE_HIDDEN.equals(mode)) return "No hidden posts";
        return "Archive is empty";
    }

    // ---------- সার্ভার থেকে আনা ----------

    private void load() {
        if (MODE_HIDDEN.equals(mode)) {
            loadHidden();
        } else {
            loadByStatus(MODE_TRASH.equals(mode) ? "trashed" : "archived");
        }
    }

    /** Archive / Trash: আমার পোস্টের মধ্যে ওই status-এরগুলো, নতুন status_at আগে */
    private void loadByStatus(String wanted) {
        final int gen = ++generation;
        SupabaseRest.call("GET",
                "/rest/v1/post_cards?user_id=eq." + myUid + "&status=eq." + wanted
                        + "&order=status_at.desc.nullslast&limit=200",
                null, null, true, r -> {
            if (gen != generation || isFinishing() || isDestroyed()) return;
            if (!r.ok()) {
                Toast.makeText(this, SupabaseRest.errorText(r), Toast.LENGTH_LONG).show();
                return;
            }
            List<Post> out = parse(r.body);
            Collections.sort(out, (x, y) -> Long.compare(y.statusAt, x.statusAt));
            render(out);
        });
    }

    /** Hidden: hidden_posts থেকে id নিয়ে তারপর একবারে সব পোস্ট আনে */
    private void loadHidden() {
        final int gen = ++generation;
        SupabaseRest.call("GET",
                "/rest/v1/hidden_posts?user_id=eq." + myUid + "&select=post_id",
                null, null, true, r -> {
            if (gen != generation || isFinishing() || isDestroyed()) return;
            if (!r.ok()) {
                Toast.makeText(this, SupabaseRest.errorText(r), Toast.LENGTH_LONG).show();
                return;
            }
            StringBuilder ids = new StringBuilder();
            try {
                JSONArray a = new JSONArray(r.body);
                for (int i = 0; i < a.length(); i++) {
                    String id = a.getJSONObject(i).optString("post_id", "");
                    if (id.isEmpty()) continue;
                    if (ids.length() > 0) ids.append(',');
                    ids.append(id);
                }
            } catch (Exception ignored) { }
            if (ids.length() == 0) {
                render(new ArrayList<Post>());
                return;
            }
            SupabaseRest.call("GET",
                    "/rest/v1/post_cards?id=in.(" + ids + ")&order=created_at.desc",
                    null, null, true, r2 -> {
                if (gen != generation || isFinishing() || isDestroyed()) return;
                if (!r2.ok()) {
                    Toast.makeText(this, SupabaseRest.errorText(r2), Toast.LENGTH_LONG).show();
                    return;
                }
                List<Post> out = parse(r2.body);
                Collections.sort(out, (x, y) -> Long.compare(y.timestamp, x.timestamp));
                render(out);
            });
        });
    }

    private List<Post> parse(String body) {
        List<Post> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(body);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(Post.from(o, myUid));
            }
        } catch (Exception ignored) { }
        return out;
    }

    // ---------- আঁকা ----------

    private void render(List<Post> items) {
        list.removeAllViews();
        list.addView(empty);
        empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        for (Post p : items) {
            list.addView(card(p));
        }
    }

    private View card(final Post p) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFF0F2F5);
        bg.setCornerRadius(dp(14));
        card.setBackground(bg);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.bottomMargin = dp(10);
        card.setLayoutParams(clp);

        if (p.name != null && !p.name.isEmpty()) {
            TextView who = new TextView(this);
            who.setText(p.name);
            who.setTextSize(14);
            who.setTypeface(null, Typeface.BOLD);
            who.setTextColor(0xFF1C1E21);
            card.addView(who);
        }

        TextView body = new TextView(this);
        String t = p.text == null ? "" : p.text.trim();
        body.setText(t.isEmpty() ? "(no text)" : t);
        body.setTextSize(15);
        body.setTextColor(0xFF1C1E21);
        body.setMaxLines(4);
        body.setEllipsize(TextUtils.TruncateAt.END);
        body.setPadding(0, dp(4), 0, dp(4));
        card.addView(body);

        TextView meta = new TextView(this);
        meta.setText(metaOf(p));
        meta.setTextSize(11);
        meta.setTextColor(0xFF65676B);
        card.addView(meta);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setPadding(0, dp(10), 0, 0);
        btns.addView(pill(MODE_HIDDEN.equals(mode) ? "Unhide" : "Restore", 0xFF1877F2, () -> restore(p)));
        if (!MODE_HIDDEN.equals(mode)) {
            btns.addView(pill("Delete", 0xFFD93025, () -> confirmDelete(p)));
        }
        card.addView(btns);
        return card;
    }

    private String metaOf(Post p) {
        if (MODE_HIDDEN.equals(mode)) {
            return "Posted " + DateUtils.getRelativeTimeSpanString(p.timestamp,
                    System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
        }
        if (p.statusAt <= 0) {
            return "";
        }
        long ttl = MODE_TRASH.equals(mode) ? TRASH_TTL_MS : ARCHIVE_TTL_MS;
        long leftMs = ttl - (System.currentTimeMillis() - p.statusAt);
        long days = Math.max(0, (leftMs + DateUtils.DAY_IN_MILLIS - 1) / DateUtils.DAY_IN_MILLIS);
        return "Auto-deletes in " + days + (days == 1 ? " day" : " days");
    }

    private TextView pill(String label, int color, final Runnable action) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextSize(14);
        b.setTypeface(null, Typeface.BOLD);
        b.setTextColor(Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(18), dp(8), dp(18), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(20));
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = dp(10);
        b.setLayoutParams(lp);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    // ---------- কাজ ----------

    private void restore(Post p) {
        if (!Net.check(this)) {
            return;
        }
        if (MODE_HIDDEN.equals(mode)) {
            PostOps.unhide(this, myUid, p.id);   // সফল হলে refreshPosts() ডাকা হয়
            Toast.makeText(this, "Showing in Home again", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            JSONObject up = new JSONObject()
                    .put("status", "active")
                    .put("status_at", nowIso());
            SupabaseRest.call("PATCH", "/rest/v1/posts?id=eq." + p.id, up.toString(),
                    "return=minimal", true, r -> {
                if (isFinishing() || isDestroyed()) return;
                if (r.ok()) {
                    Toast.makeText(this, "Restored to your timeline", Toast.LENGTH_SHORT).show();
                    load();
                } else {
                    Toast.makeText(this, SupabaseRest.errorText(r), Toast.LENGTH_LONG).show();
                }
            });
        } catch (Exception ignored) { }
    }

    /** Trash/Archive থেকে ডিলিট: Delete চাপা মাত্র গান বন্ধ হবে */
    private void confirmDelete(final Post p) {
        new AlertDialog.Builder(this).setTitle("Delete post?")
                .setMessage("This cannot be undone.")
                .setPositiveButton("Delete", (d, w) -> {
                    MusicPlayer.stopPost(p.id);   // সার্ভারের উত্তরের আগেই গান থামে
                    if (!Net.check(this)) {
                        return;
                    }
                    SupabaseRest.call("DELETE", "/rest/v1/posts?id=eq." + p.id, null,
                            "return=minimal", true, r -> {
                        if (isFinishing() || isDestroyed()) return;
                        if (r.ok()) {
                            load();
                        } else {
                            Toast.makeText(this, SupabaseRest.errorText(r),
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                })
                .setNegativeButton("Cancel", null).show();
    }

    private static String nowIso() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date());
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
