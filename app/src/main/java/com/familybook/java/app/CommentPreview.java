package com.familybook.app;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * পোস্ট কার্ডের নিচে: সর্বশেষ একটি কমেন্ট (হাইলাইট) + Reply + নিজের গোল ছবি সহ "Write a comment…" বক্স + প্লাস।
 * কমেন্ট বক্স বা প্লাসে চাপলে CommentsActivity শিট খোলে (PostAdapter থেকে actions.onComments)।
 */
final class CommentPreview {

    private static final long TTL = 60_000L;

    private static final class Item {
        String name, text, imageKey;
        String uid;
        boolean none;      // এই পোস্টে কোনো কমেন্ট নেই
        long at;
    }

    private static final LruCache<String, Item> CACHE = new LruCache<>(300);
    private static final Set<String> LOADING = new HashSet<>();
    private static final Map<String, WeakReference<CommentPreview>> LIVE = new HashMap<>();

    /** CommentsActivity বন্ধ হলে/কমেন্ট বদলালে ডাকুন: ক্যাশ মুছে দেখা কার্ডটি নতুন করে লোড হবে */
    static void refresh(String postId) {
        if (postId == null) return;
        CACHE.remove(postId);
        WeakReference<CommentPreview> w = LIVE.get(postId);
        CommentPreview cp = w == null ? null : w.get();
        if (cp != null && postId.equals(cp.postId)) cp.load();
    }

    final LinearLayout root;
    private final LinearLayout highlight;
    private final ImageView hlAvatar;
    private final TextView hlName, hlText, hlReply;
    private final ImageView myAvatar;
    private final float d;
    private String postId;
    private String myUid;

    CommentPreview(Context c) {
        d = c.getResources().getDisplayMetrics().density;
        root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(px(12), px(0), px(12), px(6));
        root.setVisibility(View.GONE);

        // ---- হাইলাইট করা সর্বশেষ কমেন্ট
        highlight = new LinearLayout(c);
        highlight.setOrientation(LinearLayout.HORIZONTAL);
        highlight.setVisibility(View.GONE);
        hlAvatar = new ImageView(c);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(px(20), px(20));
        ap.setMarginEnd(px(6));
        highlight.addView(hlAvatar, ap);

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout bubble = new LinearLayout(c);
        bubble.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bb = new GradientDrawable();
        bb.setColor(0xFFF0F2F5);
        bb.setCornerRadius(px(14));
        bubble.setBackground(bb);
        bubble.setPadding(px(10), px(5), px(10), px(5));
        hlName = new TextView(c);
        hlName.setTextSize(12f);
        hlName.setTextColor(0xFF050505);
        hlName.setTypeface(null, Typeface.BOLD);
        hlName.setSingleLine(true);
        hlName.setEllipsize(TextUtils.TruncateAt.END);
        bubble.addView(hlName);
        hlText = new TextView(c);
        hlText.setTextSize(13f);
        hlText.setTextColor(0xFF050505);
        hlText.setMaxLines(3);
        hlText.setEllipsize(TextUtils.TruncateAt.END);
        bubble.addView(hlText);
        col.addView(bubble, new LinearLayout.LayoutParams(-2, -2));
        hlReply = new TextView(c);
        hlReply.setText("Reply");
        hlReply.setTextSize(11f);
        hlReply.setTextColor(0xFF65676B);
        hlReply.setTypeface(null, Typeface.BOLD);
        hlReply.setPadding(px(10), px(3), px(10), px(2));
        col.addView(hlReply, new LinearLayout.LayoutParams(-2, -2));
        highlight.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(highlight, new LinearLayout.LayoutParams(-1, -2));

        // ---- নিচে: নিজের গোল ছবি + "Write a comment…" + প্লাস
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        myAvatar = new ImageView(c);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(px(28), px(28));
        mp.setMarginEnd(px(6));
        box.addView(myAvatar, mp);

        LinearLayout pill = new LinearLayout(c);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable pb = new GradientDrawable();
        pb.setColor(0xFFF0F2F5);
        pb.setCornerRadius(px(24));
        pill.setBackground(pb);
        pill.setPadding(px(12), 0, px(8), 0);
        TextView hint = new TextView(c);
        hint.setText("Write a comment\u2026");
        hint.setTextSize(13f);
        hint.setTextColor(0xFF65676B);
        hint.setMinHeight(px(32));
        hint.setGravity(Gravity.CENTER_VERTICAL);
        pill.addView(hint, new LinearLayout.LayoutParams(0, -2, 1f));
        ImageView plus = new ImageView(c);
        plus.setImageResource(R.drawable.ic_cm_plus_circle);
        plus.setColorFilter(0xFF65676B);
        pill.addView(plus, new LinearLayout.LayoutParams(px(26), px(26)));
        box.addView(pill, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(box, new LinearLayout.LayoutParams(-1, -2));
        // পুরো বক্স/প্লাস/Reply — যেখানেই চাপি একই শিট খুলবে (listener bind()-এ বসে)
        pill.setTag("pill");
    }

    private int px(float v) { return Math.round(v * d); }

    /** কার্ড বাইন্ড হলে ডাকুন। show=false হলে পুরো অংশ লুকায় (hidden পোস্ট / Only-me কমেন্ট) */
    void bind(Post p, String myUid, boolean show, boolean locked, Runnable open) {
        this.postId = p.id;
        this.myUid = myUid;
        if (!MentionRepo.loaded()) MentionRepo.load(people -> { });   // @ লিস্ট আগেই তৈরি থাকবে, কমেন্টে @ লিখলেই সঙ্গে সঙ্গে খুলবে
        if (!show || p.id == null) {
            root.setVisibility(View.GONE);
            return;
        }
        root.setVisibility(View.VISIBLE);
        root.setAlpha(locked ? 0.4f : 1f);
        View.OnClickListener go = v -> open.run();
        root.findViewWithTag("pill").setOnClickListener(go);
        myAvatar.setOnClickListener(go);
        hlReply.setOnClickListener(go);
        highlight.setOnClickListener(go);
        AvatarLoader.load(myAvatar, myUid, true);
        LIVE.put(p.id, new WeakReference<>(this));
        Item it = CACHE.get(p.id);
        if (it != null) {
            show(it);
            if (System.currentTimeMillis() - it.at > TTL) load();
        } else {
            highlight.setVisibility(View.GONE);
            load();
        }
    }

    private void show(Item it) {
        if (it == null || it.none) {
            highlight.setVisibility(View.GONE);
            return;
        }
        highlight.setVisibility(View.VISIBLE);
        AvatarLoader.load(hlAvatar, it.uid, true);
        hlName.setText(it.name == null || it.name.isEmpty() ? "Family Member" : it.name);
        String t = it.text == null ? "" : it.text.trim();
        if (t.isEmpty() && it.imageKey != null && !it.imageKey.isEmpty()) t = "Sent a GIF / photo";
        hlText.setText(t);
        hlText.setVisibility(t.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** পোস্টের সর্বশেষ টপ-লেভেল কমেন্ট (রিপ্লাই নয়) */
    private void load() {
        final String pid = postId;
        if (pid == null || LOADING.contains(pid)) return;
        LOADING.add(pid);
        SupabaseRest.call("GET", "/rest/v1/comments?post_id=eq." + pid
                        + "&parent_id=is.null&hidden=is.false&select=user_id,author_name,text,image_key"
                        + "&order=created_at.desc&limit=1",
                null, null, true, r -> {
            LOADING.remove(pid);
            if (!r.ok()) return;
            Item it = new Item();
            it.at = System.currentTimeMillis();
            try {
                JSONArray a = new JSONArray(r.body);
                if (a.length() == 0) {
                    it.none = true;
                } else {
                    JSONObject o = a.getJSONObject(0);
                    it.uid = o.isNull("user_id") ? null : o.optString("user_id");
                    it.name = o.isNull("author_name") ? null : o.optString("author_name");
                    it.text = o.isNull("text") ? null : o.optString("text");
                    it.imageKey = o.isNull("image_key") ? null : o.optString("image_key");
                }
            } catch (Exception e) {
                return;
            }
            CACHE.put(pid, it);
            if (pid.equals(postId)) show(it);   // ইতিমধ্যে অন্য পোস্টে রিসাইকেল হলে বদলাবে না
        });
    }
}
