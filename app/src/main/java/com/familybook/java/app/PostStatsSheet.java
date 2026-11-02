package com.familybook.app;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * পোস্টের নিচে-থেকে-ওঠা শিট (ফেসবুকের মতো): [৪৫৩ 👍] [৩১ shares]
 *  • রিঅ্যাকশন ট্যাব: "All reactions ▾" ড্রপডাউন (রিঅ্যাকশন + সংখ্যা) + নাম/ছবি/ব্যাজের তালিকা
 *  • শেয়ার ট্যাব: যারা শেয়ার করেছে তাদের তালিকা
 *  • কমেন্ট ট্যাব: সব কমেন্ট (রিপ্লাইসহ) একসাথে; চাপলে কমেন্ট-স্ক্রিন খোলে (reply/like সেখানে)
 * ব্যবহার: PostStatsSheet.show(context, postId, likes, comments, shares, TAB_REACTIONS|TAB_COMMENTS|TAB_SHARES)
 */
final class PostStatsSheet {
    private static final String[] KEYS = {"like", "love", "check", "stop", "angry", "haha", "wow"};
    private static final int BLUE = 0xFF1877F2;
    private static final int GRAY = 0xFF65676B;
    private static final int INK = 0xFF050505;

    private final Activity act;
    private final String postId;
    private final float d;
    private BottomSheetDialog dialog;

    private final List<String[]> reactions = new ArrayList<>();   // {uid, key}
    private final List<String> sharers = new ArrayList<>();       // uid
    private final Map<String, String> names = new HashMap<>();
    private final Set<String> requested = new HashSet<>();
    private boolean reactionsLoaded, sharesLoaded, commentsLoaded;
    static final int TAB_REACTIONS = 0, TAB_COMMENTS = 1, TAB_SHARES = 2;
    private int tab;
    private String filter = "all";
    private int likesHint, commentsHint, sharesHint;
    private final String myUid = SupabaseRest.uid();
    private final Set<String> following = new HashSet<>();
    private boolean followLoaded;
    private int commentSort = 0;   // 0 Most relevant, 1 Newest, 2 All comments (পুরনো আগে)

    private static final class CRow {
        String id, parentId, uid, name, text;
        long ts;
        final List<CRow> replies = new ArrayList<>();
    }
    private final List<CRow> topComments = new ArrayList<>();
    private int commentTotal;

    private LinearLayout tabRow;
    private LinearLayout body;
    private ScrollView scroll;

    static void show(Context c, String postId, int likes, int comments, int shares, int tab) {
        Activity a = unwrap(c);
        if (a == null || a.isFinishing() || postId == null) return;
        new PostStatsSheet(a, postId, likes, comments, shares, tab).open();
    }

    private static Activity unwrap(Context c) {
        while (c instanceof ContextWrapper) {
            if (c instanceof Activity) return (Activity) c;
            c = ((ContextWrapper) c).getBaseContext();
        }
        return null;
    }

    private PostStatsSheet(Activity a, String postId, int likes, int comments, int shares, int tab) {
        this.act = a;
        this.postId = postId;
        this.d = a.getResources().getDisplayMetrics().density;
        this.likesHint = likes;
        this.commentsHint = comments;
        this.sharesHint = shares;
        this.tab = tab;
    }

    private int dp(float v) { return Math.round(v * d); }

    /**
     * কমেন্ট ট্যাব: এখানে আলাদা সরল তালিকা না দেখিয়ে আসল কমেন্ট-শিট (CommentsActivity) খোলে — সেখানে
     * Reply, কমেন্টের লাইক সংখ্যা, লাইক/ডিসলাইক, "View N replies" এবং নিচে "Comment as <নাম>" বক্স সবই আছে।
     * কমেন্ট-শিট থেকেই এই শিট খোলা হলে শুধু এটা বন্ধ হয়ে পেছনের কমেন্ট-শিটে ফিরে যায়।
     */
    private void openCommentsScreen() {
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
        if (act instanceof CommentsActivity || act.isFinishing()) return;
        act.startActivity(new android.content.Intent(act, CommentsActivity.class).putExtra("postId", postId));
    }

    private void open() {
        if (tab == TAB_COMMENTS) { openCommentsScreen(); return; }
        dialog = new BottomSheetDialog(act);
        int sh = act.getResources().getDisplayMetrics().heightPixels;

        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadii(new float[]{dp(18), dp(18), dp(18), dp(18), 0, 0, 0, 0});
        root.setBackground(bg);

        View handle = new View(act);
        GradientDrawable hb = new GradientDrawable();
        hb.setColor(0xFFB0B3B8);
        hb.setCornerRadius(dp(3));
        handle.setBackground(hb);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(40), dp(5));
        hp.gravity = Gravity.CENTER_HORIZONTAL;
        hp.topMargin = dp(10);
        root.addView(handle, hp);

        tabRow = new LinearLayout(act);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setGravity(Gravity.CENTER_VERTICAL);
        tabRow.setPadding(dp(12), dp(12), dp(12), dp(4));
        android.widget.HorizontalScrollView tabScroll = new android.widget.HorizontalScrollView(act);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabScroll.addView(tabRow);
        root.addView(tabScroll, new LinearLayout.LayoutParams(-1, -2));

        scroll = new ScrollView(act);
        body = new LinearLayout(act);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(4), dp(16), dp(24));
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        dialog.setContentView(root, new FrameLayout.LayoutParams(-1, Math.round(sh * 0.82f)));
        dialog.setOnShowListener(x -> {
            View sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (sheet != null) {
                sheet.setBackgroundColor(Color.TRANSPARENT);
                BottomSheetBehavior<View> b = BottomSheetBehavior.from(sheet);
                b.setSkipCollapsed(true);
                b.setState(BottomSheetBehavior.STATE_EXPANDED);
            }
        });
        redraw();
        dialog.show();
        loadFollowing();
        loadReactions();
        loadComments();
        loadShares();
    }

    // ------------------------------------------------------------ ডেটা

    /** আমি কাদের follow করি — তালিকায় Follow / Unfollow ঠিক করতে */
    private void loadFollowing() {
        if (myUid == null) return;
        SupabaseRest.call("GET", "/rest/v1/follows?follower_id=eq." + myUid + "&select=followee_id&limit=5000",
                null, null, true, r -> {
            if (act.isFinishing()) return;
            if (r.ok()) {
                try {
                    JSONArray a = new JSONArray(r.body);
                    for (int i = 0; i < a.length(); i++) {
                        String f = a.getJSONObject(i).optString("followee_id", "");
                        if (!f.isEmpty()) following.add(f);
                    }
                } catch (Exception ignored) { }
                followLoaded = true;
                redraw();
            }
        });
    }

    private void toggleFollow(final String target) {
        if (target == null || myUid == null || !Net.check(act)) return;
        final boolean was = following.contains(target);
        if (was) following.remove(target); else following.add(target);
        redraw();
        if (was) {
            SupabaseRest.call("DELETE", "/rest/v1/follows?follower_id=eq." + myUid + "&followee_id=eq." + target,
                    null, "return=minimal", true, r -> {
                if (act.isFinishing()) return;
                if (!r.ok()) { following.add(target); redraw(); }
            });
        } else {
            try {
                JSONObject row = new JSONObject().put("follower_id", myUid).put("followee_id", target);
                SupabaseRest.call("POST", "/rest/v1/follows?on_conflict=follower_id,followee_id",
                        row.toString(), "resolution=merge-duplicates,return=minimal", true, r -> {
                    if (act.isFinishing()) return;
                    if (!r.ok()) { following.remove(target); redraw(); return; }
                    NotificationHelper.send(target, "follow", null);
                });
            } catch (Exception e) {
                following.remove(target);
                redraw();
            }
        }
    }

    private void loadReactions() {
        SupabaseRest.call("GET", "/rest/v1/post_reactions?post_id=eq." + postId + "&select=user_id,reaction",
                null, null, true, r -> {
            if (act.isFinishing()) return;
            if (r.ok()) {
                try {
                    JSONArray a = new JSONArray(r.body);
                    for (int i = 0; i < a.length(); i++) {
                        JSONObject o = a.getJSONObject(i);
                        String uid = o.optString("user_id", "");
                        String v = o.isNull("reaction") ? null : o.optString("reaction", null);
                        if (uid.isEmpty()) continue;
                        boolean like = v == null || "like".equals(v) || "true".equals(v);
                        reactions.add(new String[]{uid, like ? "like" : v});
                    }
                } catch (Exception ignored) { }
            }
            reactionsLoaded = true;
            loadNames(reactionUids());
            redraw();
        });
    }

    private void loadShares() {
        SupabaseRest.call("GET", "/rest/v1/post_shares?post_id=eq." + postId + "&select=user_id",
                null, null, true, r -> {
            if (act.isFinishing()) return;
            if (r.ok()) {
                try {
                    JSONArray a = new JSONArray(r.body);
                    Set<String> seen = new HashSet<>();
                    for (int i = 0; i < a.length(); i++) {
                        String uid = a.getJSONObject(i).optString("user_id", "");
                        if (!uid.isEmpty() && seen.add(uid)) sharers.add(uid);
                    }
                } catch (Exception ignored) { }
            }
            sharesLoaded = true;
            loadNames(sharers);
            redraw();
        });
    }

    private void loadComments() {
        SupabaseRest.call("GET", "/rest/v1/comments?post_id=eq." + postId
                + "&select=id,parent_id,user_id,author_name,text,hidden,created_at&order=created_at.asc&limit=2000",
                null, null, true, r -> {
            if (act.isFinishing()) return;
            if (r.ok()) {
                try {
                    JSONArray a = new JSONArray(r.body);
                    Map<String, CRow> byId = new LinkedHashMap<>();
                    List<CRow> all = new ArrayList<>();
                    for (int i = 0; i < a.length(); i++) {
                        JSONObject o = a.getJSONObject(i);
                        if (o.optBoolean("hidden", false)) continue;
                        CRow c = new CRow();
                        c.id = o.optString("id", "");
                        c.parentId = o.isNull("parent_id") ? null : o.optString("parent_id", null);
                        c.uid = o.optString("user_id", "");
                        c.name = o.isNull("author_name") ? "" : o.optString("author_name", "").trim();
                        c.text = o.isNull("text") ? "" : o.optString("text", "");
                        c.ts = parseTs(o.isNull("created_at") ? "" : o.optString("created_at", ""));
                        if (c.id.isEmpty()) continue;
                        byId.put(c.id, c);
                        all.add(c);
                    }
                    topComments.clear();
                    for (CRow c : all) {
                        CRow par = c.parentId == null ? null : byId.get(c.parentId);
                        if (par != null) {
                            while (par.parentId != null && byId.get(par.parentId) != null) par = byId.get(par.parentId);
                            par.replies.add(c);
                        } else if (c.parentId == null) {
                            topComments.add(c);
                        }
                    }
                    commentTotal = all.size();
                    List<String> need = new ArrayList<>();
                    for (CRow c : all) if (c.name.isEmpty() && !c.uid.isEmpty()) need.add(c.uid);
                    loadNames(need);
                } catch (Exception ignored) { }
            }
            commentsLoaded = true;
            redraw();
        });
    }

    private static long parseTs(String iso) {
        try {
            if (iso == null || iso.length() < 19) return 0;
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US);
            f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            return f.parse(iso.substring(0, 19)).getTime();
        } catch (Exception e) { return 0; }
    }

    private static String ago(long ts) {
        if (ts <= 0) return "";
        long s = Math.max(0, (System.currentTimeMillis() - ts) / 1000);
        if (s < 60) return "now";
        if (s < 3600) return (s / 60) + "m";
        if (s < 86400) return (s / 3600) + "h";
        if (s < 7 * 86400) return (s / 86400) + "d";
        if (s < 30L * 86400) return (s / (7 * 86400)) + "w";
        if (s < 365L * 86400) return (s / (30L * 86400)) + "mo";
        return (s / (365L * 86400)) + "y";
    }

    private List<String> reactionUids() {
        List<String> l = new ArrayList<>();
        for (String[] e : reactions) l.add(e[0]);
        return l;
    }

    private void loadNames(List<String> uids) {
        final List<String> need = new ArrayList<>();
        for (String u : uids) if (requested.add(u)) need.add(u);
        if (need.isEmpty()) return;
        StringBuilder ids = new StringBuilder();
        for (String u : need) { if (ids.length() > 0) ids.append(','); ids.append(u); }
        SupabaseRest.call("GET", "/rest/v1/profiles?id=in.(" + ids + ")&select=id,name",
                null, null, true, r -> {
            if (act.isFinishing()) return;
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
            for (String u : need) if (!names.containsKey(u)) names.put(u, "User");
            redraw();
        });
    }

    // ------------------------------------------------------------ UI

    private void redraw() {
        int likeCount = reactionsLoaded ? reactions.size() : likesHint;
        int commentCount = commentsLoaded ? commentTotal : commentsHint;
        int shareCount = sharesLoaded ? sharers.size() : sharesHint;

        tabRow.removeAllViews();
        tabRow.addView(tab(String.valueOf(likeCount), topReactionKeys(), tab == TAB_REACTIONS, () -> { tab = TAB_REACTIONS; redraw(); }));
        tabRow.addView(tab(commentCount + (commentCount == 1 ? " comment" : " comments"), null, tab == TAB_COMMENTS,
                () -> openCommentsScreen()));
        tabRow.addView(tab(shareCount + (shareCount == 1 ? " share" : " shares"), null, tab == TAB_SHARES,
                () -> { tab = TAB_SHARES; redraw(); }));

        body.removeAllViews();
        if (tab == TAB_SHARES) drawShares();
        else if (tab == TAB_COMMENTS) drawComments();
        else drawReactions();
    }

    // ---- কমেন্ট ট্যাব
    private void drawComments() {
        final String[] labels = {"Most relevant", "Newest", "All comments"};
        TextView sort = new TextView(act);
        sort.setText(labels[commentSort] + " \u25BE");
        sort.setTextSize(17);
        sort.setTextColor(INK);
        sort.setTypeface(null, android.graphics.Typeface.BOLD);
        sort.setPadding(0, dp(12), 0, dp(12));
        sort.setOnClickListener(v -> {
            android.widget.PopupMenu pm = new android.widget.PopupMenu(act, v);
            for (int i = 0; i < labels.length; i++) pm.getMenu().add(0, i, i, labels[i]);
            pm.setOnMenuItemClickListener(it -> { commentSort = it.getItemId(); redraw(); return true; });
            pm.show();
        });
        body.addView(sort, new LinearLayout.LayoutParams(-2, -2));

        List<CRow> l = new ArrayList<>(topComments);
        Collections.sort(l, new Comparator<CRow>() {
            @Override public int compare(CRow a, CRow b) {
                if (commentSort == 2) return Long.compare(a.ts, b.ts);
                if (commentSort == 1) return Long.compare(b.ts, a.ts);
                if (a.replies.size() != b.replies.size()) return b.replies.size() - a.replies.size();
                return Long.compare(b.ts, a.ts);
            }
        });
        for (CRow c : l) {
            body.addView(commentRow(c, false));
            for (CRow rp : c.replies) body.addView(commentRow(rp, true));
        }
        if (l.isEmpty()) body.addView(emptyText(commentsLoaded ? "No comments yet" : "Loading\u2026"));
    }

    private View commentRow(CRow c, boolean reply) {
        LinearLayout line = new LinearLayout(act);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setPadding(reply ? dp(52) : 0, dp(8), 0, dp(8));
        int sz = reply ? 32 : 40;
        ImageView photo = new ImageView(act);
        AvatarLoader.load(photo, c.uid);
        line.addView(photo, new LinearLayout.LayoutParams(dp(sz), dp(sz)));

        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -2, 1f);
        cl.setMarginStart(dp(12));
        line.addView(col, cl);

        TextView head = new TextView(act);
        String n = !c.name.isEmpty() ? c.name : names.get(c.uid);
        String t = ago(c.ts);
        head.setText((n == null ? "\u2026" : n) + (t.isEmpty() ? "" : "  \u00B7  " + t));
        head.setTextSize(14);
        head.setTextColor(GRAY);
        head.setTypeface(null, android.graphics.Typeface.BOLD);
        col.addView(head);
        TextView tx = new TextView(act);
        tx.setText(c.text == null || c.text.isEmpty() ? "GIF" : c.text);
        tx.setTextSize(16);
        tx.setTextColor(INK);
        col.addView(tx);

        line.setOnClickListener(v -> {
            if (act instanceof CommentsActivity) { dialog.dismiss(); return; }   // কমেন্ট-স্ক্রিনেই আছি
            dialog.dismiss();
            act.startActivity(new android.content.Intent(act, CommentsActivity.class).putExtra("postId", postId));
        });
        return line;
    }

    private View tab(String label, List<String> icons, boolean on, final Runnable click) {
        LinearLayout t = new LinearLayout(act);
        t.setOrientation(LinearLayout.HORIZONTAL);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(14), dp(9), dp(14), dp(9));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(22));
        g.setColor(on ? 0xFFE7F3FF : Color.TRANSPARENT);
        t.setBackground(g);
        TextView tv = new TextView(act);
        tv.setText(label);
        tv.setTextSize(16);
        tv.setTextColor(on ? 0xFF0064D1 : INK);
        if (on) tv.setTypeface(null, android.graphics.Typeface.BOLD);
        t.addView(tv);
        if (icons != null) {
            for (int i = 0; i < icons.size(); i++) {
                ImageView iv = new ImageView(act);
                ReactionKit.setIcon(iv, icons.get(i));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(22), dp(22));
                lp.setMarginStart(i == 0 ? dp(6) : -dp(4));
                t.addView(iv, lp);
            }
        }
        t.setOnClickListener(v -> click.run());
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
        tl.setMarginEnd(dp(6));
        t.setLayoutParams(tl);
        return t;
    }

    /** ট্যাবের গায়ে সবচেয়ে বেশি দেওয়া ≤৩ রিঅ্যাকশনের আইকন (ফেসবুকের মতো) */
    private List<String> topReactionKeys() {
        final Map<String, Integer> counts = new LinkedHashMap<>();
        for (String[] e : reactions) {
            Integer o = counts.get(e[1]);
            counts.put(e[1], o == null ? 1 : o + 1);
        }
        List<String> keys = new ArrayList<>(counts.keySet());
        Collections.sort(keys, new Comparator<String>() {
            @Override public int compare(String a, String b) { return counts.get(b) - counts.get(a); }
        });
        if (keys.isEmpty()) keys.add("like");
        return keys.size() > 3 ? new ArrayList<>(keys.subList(0, 3)) : keys;
    }

    private void drawReactions() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String[] e : reactions) {
            Integer o = counts.get(e[1]);
            counts.put(e[1], o == null ? 1 : o + 1);
        }
        if (!"all".equals(filter) && !counts.containsKey(filter)) filter = "all";

        // "All reactions ▾" — চাপলে রিঅ্যাকশন + সংখ্যার ড্রপডাউন
        LinearLayout fb = new LinearLayout(act);
        fb.setOrientation(LinearLayout.HORIZONTAL);
        fb.setGravity(Gravity.CENTER_VERTICAL);
        fb.setPadding(0, dp(12), 0, dp(12));
        if ("all".equals(filter)) {
            TextView t = new TextView(act);
            t.setText("All reactions");
            t.setTextSize(17);
            t.setTextColor(INK);
            t.setTypeface(null, android.graphics.Typeface.BOLD);
            fb.addView(t);
        } else {
            ImageView iv = new ImageView(act);
            ReactionKit.setIcon(iv, filter);
            fb.addView(iv, new LinearLayout.LayoutParams(dp(22), dp(22)));
            TextView t = new TextView(act);
            Integer c = counts.get(filter);
            t.setText(String.valueOf(c == null ? 0 : c));
            t.setTextSize(17);
            t.setPadding(dp(6), 0, 0, 0);
            t.setTextColor(INK);
            t.setTypeface(null, android.graphics.Typeface.BOLD);
            fb.addView(t);
        }
        TextView arrow = new TextView(act);
        arrow.setText(" \u25BE");
        arrow.setTextSize(17);
        arrow.setTextColor(INK);
        fb.addView(arrow);
        if (!counts.isEmpty()) fb.setOnClickListener(v -> showFilterMenu(v, counts));
        body.addView(fb, new LinearLayout.LayoutParams(-2, -2));

        int shown = 0;
        for (String[] e : reactions) {
            if (!"all".equals(filter) && !filter.equals(e[1])) continue;
            shown++;
            body.addView(personRow(e[0], e[1]));
        }
        if (shown == 0) body.addView(emptyText(reactionsLoaded ? "No reactions yet" : "Loading…"));
    }

    private void showFilterMenu(View anchor, Map<String, Integer> counts) {
        final LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(8), dp(8), dp(8), dp(8));
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.WHITE);
        g.setCornerRadius(dp(16));
        g.setStroke(dp(1), 0xFFE4E6EB);
        box.setBackground(g);
        box.setElevation(dp(8));

        final PopupWindow pw = new PopupWindow(box, dp(220), -2, true);
        pw.setOutsideTouchable(true);
        pw.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));

        box.addView(menuRow(null, "All reactions", "all".equals(filter), () -> { filter = "all"; pw.dismiss(); redraw(); }));
        List<String> keys = new ArrayList<>(counts.keySet());
        Collections.sort(keys, new Comparator<String>() {
            @Override public int compare(String a, String b) { return counts.get(b) - counts.get(a); }
        });
        for (final String k : keys) {
            box.addView(menuRow(k, String.valueOf(counts.get(k)), k.equals(filter),
                    () -> { filter = k; pw.dismiss(); redraw(); }));
        }
        pw.showAsDropDown(anchor, 0, 0);
    }

    private View menuRow(String key, String label, boolean checked, final Runnable click) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(10), dp(10), dp(10));
        if (key != null) {
            ImageView iv = new ImageView(act);
            ReactionKit.setIcon(iv, key);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(30), dp(30));
            lp.setMarginEnd(dp(14));
            row.addView(iv, lp);
        }
        TextView t = new TextView(act);
        t.setText(label);
        t.setTextSize(key == null ? 17 : 18);
        t.setTextColor(INK);
        if (key == null) t.setTypeface(null, android.graphics.Typeface.BOLD);
        row.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        if (checked) {
            TextView ck = new TextView(act);
            ck.setText("\u2713");
            ck.setTextSize(18);
            ck.setTextColor(INK);
            row.addView(ck);
        }
        row.setOnClickListener(v -> click.run());
        return row;
    }

    private void drawShares() {
        for (String u : sharers) body.addView(personRow(u, null));
        if (sharers.isEmpty()) body.addView(emptyText(sharesLoaded ? "No shares yet" : "Loading…"));
        TextView note = new TextView(act);
        note.setText("Some posts may not appear here because of their privacy settings.");
        note.setTextSize(14);
        note.setTextColor(GRAY);
        note.setPadding(0, dp(18), 0, 0);
        body.addView(note);
    }

    /** ছবি (+ নিচে-ডানে রিঅ্যাকশন ব্যাজ) + নাম */
    private View personRow(final String uid, String reactionKey) {
        LinearLayout line = new LinearLayout(act);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, dp(8), 0, dp(8));

        FrameLayout av = new FrameLayout(act);
        ImageView photo = new ImageView(act);
        AvatarLoader.load(photo, uid);
        av.addView(photo, new FrameLayout.LayoutParams(dp(52), dp(52)));
        if (reactionKey != null) {
            FrameLayout badge = new FrameLayout(act);
            GradientDrawable bb = new GradientDrawable();
            bb.setShape(GradientDrawable.OVAL);
            bb.setColor(Color.WHITE);
            badge.setBackground(bb);
            int pad = dp(2);
            badge.setPadding(pad, pad, pad, pad);
            ImageView iv = new ImageView(act);
            ReactionKit.setIcon(iv, reactionKey);
            badge.addView(iv, new FrameLayout.LayoutParams(-1, -1));
            FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.BOTTOM | Gravity.END);
            av.addView(badge, bp);
        }
        line.addView(av, new LinearLayout.LayoutParams(dp(56), dp(56)));

        TextView name = new TextView(act);
        String n = names.get(uid);
        name.setText(n == null ? "…" : n);
        name.setTextSize(17);
        name.setTextColor(INK);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(0, -2, 1f);
        nl.setMarginStart(dp(14));
        line.addView(name, nl);
        if (reactionKey != null && followLoaded && myUid != null && !myUid.equals(uid)) {
            final boolean fol = following.contains(uid);
            TextView btn = new TextView(act);
            btn.setText(fol ? "Unfollow" : "Follow");
            btn.setTextSize(15);
            btn.setTypeface(null, android.graphics.Typeface.BOLD);
            btn.setGravity(Gravity.CENTER);
            btn.setTextColor(fol ? INK : Color.WHITE);
            btn.setPadding(dp(18), dp(9), dp(18), dp(9));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(10));
            bg.setColor(fol ? 0xFFE4E6EB : BLUE);
            btn.setBackground(bg);
            btn.setOnClickListener(v -> toggleFollow(uid));
            line.addView(btn, new LinearLayout.LayoutParams(-2, -2));
        }
        return line;
    }

    private TextView emptyText(String s) {
        TextView e = new TextView(act);
        e.setText(s);
        e.setTextColor(GRAY);
        e.setTextSize(15);
        e.setPadding(0, dp(24), 0, 0);
        return e;
    }
}
