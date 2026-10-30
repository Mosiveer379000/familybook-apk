package com.familybook.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.format.DateUtils;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;


import org.json.JSONArray;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class NotificationAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public interface OnItemClick { void onClick(NotificationGroup group); }

    private static final int HEADER = 0, ITEM = 1;

    private final List<Object> rows = new ArrayList<>(); // String = header, NotificationGroup = item
    private final OnItemClick listener;
    private final Map<String, String> names = new HashMap<>();
    private final Set<String> requested = new HashSet<>();

    public NotificationAdapter(OnItemClick listener) { this.listener = listener; }

    public void setRows(List<Object> newRows) {
        rows.clear();
        rows.addAll(newRows);
        notifyDataSetChanged();
    }

    /** প্রোফাইল খোলার সময় নাম লাগে */
    public String nameOf(String uid) {
        String n = uid == null ? null : names.get(uid);
        return n == null ? "" : n;
    }

    @Override public int getItemViewType(int position) {
        return rows.get(position) instanceof String ? HEADER : ITEM;
    }

    @NonNull @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        if (type == HEADER) return new HeaderVH(inf.inflate(R.layout.item_notification_header, parent, false));
        return new ItemVH(inf.inflate(R.layout.item_notification, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position) {
        Object row = rows.get(position);
        if (h instanceof HeaderVH) {
            ((HeaderVH) h).title.setText((String) row);
            return;
        }
        NotificationGroup g = (NotificationGroup) row;
        NotificationItem n = g.latest;
        ItemVH vh = (ItemVH) h;
        Context c = vh.itemView.getContext();

        // প্রোফাইল ছবি (সবচেয়ে নতুন যে করেছে তার)
        loadAvatar(g.actors.isEmpty() ? null : g.actors.get(0), vh.avatar);

        // ছোট ব্যাজ (like / comment ...)
        styleBadge(c, vh.badge, n.type);

        // লেখা: নাম বোল্ড
        vh.msg.setText(buildText(g));

        long ms = n.timestamp > 0 ? n.timestamp : System.currentTimeMillis();
        vh.time.setText(DateUtils.getRelativeTimeSpanString(ms, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS));
        vh.time.setTextColor(g.read ? 0xFF65676B : 0xFF1877F2);

        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(0xFF1877F2);
        vh.dot.setBackground(dot);
        vh.dot.setVisibility(g.read ? View.INVISIBLE : View.VISIBLE);

        vh.itemView.setBackgroundColor(g.read ? 0x00000000 : 0x1A1877F2);
        vh.itemView.setOnClickListener(v -> { if (listener != null) listener.onClick(g); });
    }

    // ---------- লেখা বানানো ----------

    private CharSequence buildText(NotificationGroup g) {
        NotificationItem n = g.latest;
        if (n.message != null && !n.message.isEmpty() && g.actors.size() <= 1) return n.message;

        String who;
        int count = g.actors.size();
        if (count == 0) {
            who = "Someone";
        } else if (count == 1) {
            who = nameFor(g.actors.get(0));
        } else if (count == 2) {
            who = nameFor(g.actors.get(0)) + " and " + nameFor(g.actors.get(1));
        } else {
            int others = count - 2;
            who = nameFor(g.actors.get(0)) + ", " + nameFor(g.actors.get(1))
                    + " and " + others + (others == 1 ? " other" : " others");
        }

        String t = n.type == null ? "" : n.type.toLowerCase();
        String action;
        if (t.startsWith("profile_photo_")) action = " updated " + t.substring(14) + " profile picture";
        else if (t.startsWith("cover_photo_")) action = " updated " + t.substring(12) + " cover photo";
        else if (t.equals("tag_request")) action = " tagged you in a post";
        else if (t.equals("collab_request")) action = " added you as a collaborator in a post";
        else if (t.equals("tag_accepted")) action = " approved the tag on your post";
        else if (t.equals("collab_accepted")) action = " accepted your collaboration request";
        else if (t.contains("like")) action = " liked your post";
        else if (t.contains("comment")) action = " commented on your post";
        else if (t.contains("follow")) action = " started following you";
        else if (t.contains("share")) action = " shared your post";
        else if (t.contains("mention")) action = " mentioned you";
        else action = " sent you a notification";

        SpannableStringBuilder sb = new SpannableStringBuilder(who);
        sb.setSpan(new StyleSpan(Typeface.BOLD), 0, who.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append(action);
        return sb;
    }

    private String nameFor(String uid) {
        if (uid == null) return "Someone";
        String cached = names.get(uid);
        if (cached != null) return cached;
        if (requested.add(uid)) {
            SupabaseRest.call("GET", "/rest/v1/profiles?id=eq." + uid + "&select=name",
                    null, null, true, r -> {
                String v = null;
                if (r.ok()) {
                    try {
                        JSONArray arr = new JSONArray(r.body);
                        if (arr.length() > 0 && !arr.getJSONObject(0).isNull("name")) {
                            v = arr.getJSONObject(0).optString("name", null);
                        }
                    } catch (Exception ignored) { }
                }
                names.put(uid, v == null || v.isEmpty() ? "Someone" : v);
                notifyDataSetChanged();
            });
        }
        return "Someone";
    }

    // ---------- ব্যাজ ----------

    private void styleBadge(Context c, TextView badge, String type) {
        String t = type == null ? "" : type.toLowerCase();
        String icon;
        int color;
        if (t.startsWith("profile_photo_") || t.startsWith("cover_photo_")) { icon = "\uD83D\uDDBC"; color = 0xFF1877F2; } // 🖼
        else if (t.startsWith("group")) { icon = "\uD83D\uDC65"; color = 0xFF009688; }                       // 👥
        else if (t.equals("tag_request") || t.equals("tag_accepted")) { icon = "\uD83C\uDFF7"; color = 0xFF1877F2; }       // 🏷
        else if (t.equals("collab_request") || t.equals("collab_accepted")) { icon = "\uD83E\uDD1D"; color = 0xFF31A24C; } // 🤝
        else if (t.contains("like")) { icon = "\uD83D\uDC4D"; color = 0xFF1877F2; }          // 👍 নীল
        else if (t.contains("comment")) { icon = "\uD83D\uDCAC"; color = 0xFF31A24C; }  // 💬 সবুজ
        else if (t.contains("follow")) { icon = "\uD83D\uDC64"; color = 0xFF7B3FE4; }   // 👤
        else if (t.contains("share")) { icon = "\u21AA"; color = 0xFFF7B928; }          // ↪
        else if (t.contains("mention")) { icon = "@"; color = 0xFFE4405F; }
        else { icon = "\uD83D\uDD14"; color = 0xFF8A8D91; }                             // 🔔

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(color);
        bg.setStroke(dp(c, 2), Color.WHITE);
        badge.setBackground(bg);
        badge.setText(icon);
    }

    // ---------- প্রোফাইল ছবি ----------

    private void loadAvatar(final String uid, final ImageView iv) {
        // আসল ছবি R2 যুক্ত হলে আসবে; এখন জেন্ডার অনুযায়ী ডিফল্ট ছবি
        AvatarLoader.load(iv, uid);
    }

    private static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override public int getItemCount() { return rows.size(); }

    static class HeaderVH extends RecyclerView.ViewHolder {
        TextView title;
        HeaderVH(View v) { super(v); title = v.findViewById(R.id.tvHeader); }
    }

    static class ItemVH extends RecyclerView.ViewHolder {
        ImageView avatar;
        TextView badge, msg, time;
        View dot;
        ItemVH(View v) {
            super(v);
            avatar = v.findViewById(R.id.imgAvatar);
            badge = v.findViewById(R.id.tvBadge);
            msg = v.findViewById(R.id.tvMessage);
            time = v.findViewById(R.id.tvTime);
            dot = v.findViewById(R.id.unreadDot);
        }
    }
}
