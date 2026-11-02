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
import android.widget.PopupMenu;
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
    public interface OnDelete { void onDelete(NotificationGroup group); }

    private static final int HEADER = 0, ITEM = 1;

    private final List<Object> rows = new ArrayList<>(); // String = header, NotificationGroup = item
    private final OnItemClick listener;
    private final Map<String, String> names = new HashMap<>();
    private final Set<String> requested = new HashSet<>();

    private OnDelete deleteListener;

    public NotificationAdapter(OnItemClick listener) { this.listener = listener; }

    public void setOnDelete(OnDelete l) { deleteListener = l; }

    /** ডিলিট করা নোটিফিকেশন তালিকা থেকে সাথে সাথে সরাও */
    public void removeGroup(NotificationGroup g) {
        int i = rows.indexOf(g);
        if (i < 0) return;
        rows.remove(i);
        notifyDataSetChanged();
    }

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

        // ছোট 3-dot: Delete
        vh.more.setOnClickListener(v -> {
            PopupMenu pm = new PopupMenu(v.getContext(), v);
            pm.getMenu().add(0, 1, 0, "Delete");
            pm.setOnMenuItemClickListener(it -> {
                if (deleteListener != null) deleteListener.onDelete(g);
                return true;
            });
            pm.show();
        });
    }

    // ---------- লেখা বানানো ----------

    private CharSequence buildText(NotificationGroup g) {
        NotificationItem n = g.latest;
        String t = n.type == null ? "" : n.type.toLowerCase();
        boolean mediaType = NotificationHelper.isReaction(t) || t.contains("comment") || t.contains("like");
        if (!mediaType && n.message != null && !n.message.isEmpty() && g.actors.size() <= 1) return n.message;

        String who;
        int count = g.actors.size();
        if (count == 0) {
            who = "Someone";
        } else if (count == 1) {
            who = nameFor(g.actors.get(0));
        } else if (count == 2) {
            who = nameFor(g.actors.get(0)) + " & " + nameFor(g.actors.get(1));
        } else {
            int others = count - 2;
            who = nameFor(g.actors.get(0)) + ", " + nameFor(g.actors.get(1))
                    + " & " + PostAdapter.compact(others) + " other";
        }

        String kind = "post";
        if (n.message != null && (n.message.equals("photo") || n.message.equals("video") || n.message.equals("gif"))) {
            kind = n.message;
        }
        String action;
        if (t.startsWith("profile_photo_")) action = " updated " + t.substring(14) + " profile picture";
        else if (t.startsWith("cover_photo_")) action = " updated " + t.substring(12) + " cover photo";
        else if (t.equals("tag_request")) action = " tagged you in a post";
        else if (t.equals("collab_request")) action = " added you as a collaborator in a post";
        else if (t.equals("tag_accepted")) action = " approved the tag on your post";
        else if (t.equals("collab_accepted")) action = " accepted your collaboration request";
        else if (t.equals("short_new")) action = " uploaded a new short";
        else if (t.equals("photo_new")) action = " uploaded a new photo";
        else if (NotificationHelper.isReaction(t) || t.contains("like")) action = " " + reactionVerb(t) + " your " + kind;
        else if (t.contains("comment")) action = " commented on your " + kind;
        else if (t.contains("follow")) action = " started following you";
        else if (t.contains("share")) action = " shared your post";
        else if (t.contains("mention")) action = " mentioned you in their comment";
        else action = " sent you a notification";

        SpannableStringBuilder sb = new SpannableStringBuilder(who);
        sb.setSpan(new StyleSpan(Typeface.BOLD), 0, who.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append(action);
        return sb;
    }

    /** Liked · Loved · Haha'd · Angered ... */
    private static String reactionVerb(String t) {
        switch (t) {
            case "love": return "Loved";
            case "haha": return "Haha'd";
            case "angry": return "Angered";
            case "wow": return "Wowed";
            case "check": return "Checked";
            case "stop": return "Stopped";
            default: return "Liked";
        }
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
        else if (t.equals("short_new")) { icon = "\uD83C\uDFAC"; color = 0xFFE41E3F; }             // 🎬
        else if (t.equals("photo_new")) { icon = "\uD83D\uDDBC"; color = 0xFF1877F2; }             // 🖼
        else if (t.equals("love")) { icon = "\u2764"; color = 0xFFE41E3F; }                         // ❤
        else if (t.equals("haha")) { icon = "\uD83D\uDE06"; color = 0xFFF7B928; }                  // 😆
        else if (t.equals("angry")) { icon = "\uD83D\uDE21"; color = 0xFFE9710F; }                 // 😡
        else if (t.equals("wow")) { icon = "\uD83D\uDE2E"; color = 0xFFF7B928; }                   // 😮
        else if (t.equals("check")) { icon = "\u2714"; color = 0xFF31A24C; }                       // ✔
        else if (t.equals("stop")) { icon = "\u270B"; color = 0xFFE41E3F; }                         // ✋
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
        View dot, more;
        ItemVH(View v) {
            super(v);
            avatar = v.findViewById(R.id.imgAvatar);
            badge = v.findViewById(R.id.tvBadge);
            msg = v.findViewById(R.id.tvMessage);
            time = v.findViewById(R.id.tvTime);
            dot = v.findViewById(R.id.unreadDot);
            more = v.findViewById(R.id.btnNotifMore);
        }
    }
}
