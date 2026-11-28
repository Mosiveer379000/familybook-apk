package com.familybook.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.view.ContextThemeWrapper;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /** আগে থেকে জানা নাম বসায় (জন্মদিনের সারির জন্য — আলাদা নেট কল লাগে না) */
    public void putName(String uid, String name) {
        if (uid != null && name != null) names.put(uid, name);
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
        if ("warning".equalsIgnoreCase(n.type)) {
            showAppIcon(vh.avatar);   // সিস্টেম ওয়ার্নিং: কোনো ইউজার নেই, অ্যাপের আইকন
        } else {
            loadAvatar(g.actors.isEmpty() ? null : g.actors.get(0),
                    n.type != null && n.type.toLowerCase().startsWith("group") ? n.groupId : null, vh.avatar);
        }

        // ছোট ব্যাজ (like / comment ...)
        styleBadge(c, vh.badge, n.type);

        // লেখা: নাম বোল্ড
        vh.msg.setText(buildText(c, g));

        long ms = n.timestamp > 0 ? n.timestamp : System.currentTimeMillis();
        vh.time.setText(relTime(c, ms));
        vh.time.setTextColor(androidx.core.content.ContextCompat.getColor(c, g.read ? R.color.n_sub : R.color.n_accent));

        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(androidx.core.content.ContextCompat.getColor(c, R.color.n_accent));
        vh.dot.setBackground(dot);
        vh.dot.setVisibility(g.read ? View.INVISIBLE : View.VISIBLE);

        vh.itemView.setBackgroundColor(g.read ? 0x00000000 : androidx.core.content.ContextCompat.getColor(c, R.color.n_unread_bg));
        vh.itemView.setOnClickListener(v -> { if (listener != null) listener.onClick(g); });

        // ছোট 3-dot: Delete (ডার্ক/লাইট থিমে পপআপ, লেখা অনুবাদ করা)
        vh.more.setOnClickListener(v -> {
            androidx.appcompat.widget.PopupMenu pm = new androidx.appcompat.widget.PopupMenu(
                    new ContextThemeWrapper(v.getContext(), R.style.FbPopupTheme), v);
            pm.getMenu().add(0, 1, 0, v.getContext().getString(R.string.notif_delete));
            pm.setOnMenuItemClickListener(it -> {
                if (deleteListener != null) deleteListener.onDelete(g);
                return true;
            });
            pm.show();
        });
    }

    // ---------- লেখা বানানো (সব লেখা strings.xml থেকে, অ্যাপের ভাষায়) ----------

    private static final Pattern P_INVITE = Pattern.compile("^(.*) invited you to join their group \"(.*)\"$", Pattern.DOTALL);
    private static final Pattern P_REQUEST = Pattern.compile("^(.*) wants to join your group \"(.*)\"$", Pattern.DOTALL);
    private static final Pattern P_JOIN_OK = Pattern.compile("^Your request to join \"(.*)\" was approved$", Pattern.DOTALL);
    private static final Pattern P_POST_OK = Pattern.compile("^Your post in \"(.*)\" was approved$", Pattern.DOTALL);
    private static final Pattern P_EDIT_OK = Pattern.compile("^Your edit in \"(.*)\" was approved$", Pattern.DOTALL);

    /**
     * সার্ভারে ইংরেজিতে সেভ হওয়া গ্রুপ নোটিফিকেশনের লেখা অ্যাপের ভাষায় বদলে দেয়।
     * চেনা না গেলে null (তখন আসল লেখাই দেখানো হয়)।
     */
    public static CharSequence localizeGroupMessage(Context c, String msg) {
        if (msg == null || msg.isEmpty()) return null;
        Matcher m = P_INVITE.matcher(msg);
        if (m.matches()) {
            String who = personName(c, m.group(1));
            return boldFirst(c.getString(R.string.notif_g_invite, who, m.group(2)), who);
        }
        m = P_REQUEST.matcher(msg);
        if (m.matches()) {
            String who = personName(c, m.group(1));
            return boldFirst(c.getString(R.string.notif_g_request, who, m.group(2)), who);
        }
        m = P_JOIN_OK.matcher(msg);
        if (m.matches()) return c.getString(R.string.notif_g_join_ok, m.group(1));
        m = P_POST_OK.matcher(msg);
        if (m.matches()) return c.getString(R.string.notif_g_post_ok, m.group(1));
        m = P_EDIT_OK.matcher(msg);
        if (m.matches()) return c.getString(R.string.notif_g_edit_ok, m.group(1));
        return null;
    }

    private static String personName(Context c, String raw) {
        return raw == null || raw.isEmpty() || "Someone".equals(raw) ? c.getString(R.string.notif_someone) : raw;
    }

    private static CharSequence boldFirst(String full, String part) {
        SpannableStringBuilder sb = new SpannableStringBuilder(full);
        int i = part == null || part.isEmpty() ? -1 : full.indexOf(part);
        if (i >= 0) sb.setSpan(new StyleSpan(Typeface.BOLD), i, i + part.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    private static boolean isKnownPlain(String t) {
        return t.startsWith("profile_photo_") || t.startsWith("cover_photo_")
                || t.equals("tag_request") || t.equals("collab_request")
                || t.equals("tag_accepted") || t.equals("collab_accepted")
                || t.equals("short_new") || t.equals("photo_new") || t.equals("birthday_wish")
                || t.contains("follow") || t.contains("share") || t.contains("mention");
    }

    private CharSequence buildText(Context c, NotificationGroup g) {
        NotificationItem n = g.latest;
        String t = n.type == null ? "" : n.type.toLowerCase();
        boolean hasMsg = n.message != null && !n.message.isEmpty();
        boolean mediaType = NotificationHelper.isReaction(t) || t.contains("comment") || t.contains("like");

        // আজ জন্মদিন: "Today is A's birthday" / "Today is A & B's birthday" / "Today is A & 30 others' birthday"
        if (t.equals("birthday_today")) {
            int cnt = g.actors.size();
            String a = nameFor(c, g.actors.isEmpty() ? null : g.actors.get(0));
            return cnt <= 1 ? c.getString(R.string.notif_bday_one, a)
                    : cnt == 2 ? c.getString(R.string.notif_bday_two, a, nameFor(c, g.actors.get(1)))
                    : c.getString(R.string.notif_bday_many, a, cnt - 1);
        }

        // গ্রুপ নোটিফিকেশন: সার্ভারের ইংরেজি লেখা চিনে অনুবাদ; না চিনলে আসল লেখা
        if (t.startsWith("group")) {
            CharSequence loc = localizeGroupMessage(c, n.message);
            if (loc != null) return loc;
            if (hasMsg) return n.message;
        }
        // অচেনা ধরন + নিজস্ব লেখা থাকলে আসল লেখাই
        if (!mediaType && !isKnownPlain(t) && hasMsg && g.actors.size() <= 1) return n.message;

        String who;
        int count = g.actors.size();
        if (count == 0) {
            who = c.getString(R.string.notif_someone);
        } else if (count == 1) {
            who = nameFor(c, g.actors.get(0));
        } else if (count == 2) {
            who = c.getString(R.string.notif_who_two, nameFor(c, g.actors.get(0)), nameFor(c, g.actors.get(1)));
        } else {
            int others = count - 2;
            who = others == 1
                    ? c.getString(R.string.notif_who_many_one, nameFor(c, g.actors.get(0)), nameFor(c, g.actors.get(1)))
                    : c.getString(R.string.notif_who_many, nameFor(c, g.actors.get(0)), nameFor(c, g.actors.get(1)),
                            PostAdapter.compact(others));
        }

        String kindStr = c.getString(R.string.notif_kind_post);
        if (n.message != null) {
            if (n.message.equals("photo")) kindStr = c.getString(R.string.notif_kind_photo);
            else if (n.message.equals("video")) kindStr = c.getString(R.string.notif_kind_video);
            else if (n.message.equals("gif")) kindStr = c.getString(R.string.notif_kind_gif);
        }

        String action;
        if (t.startsWith("profile_photo_")) action = c.getString(pronounRes(t.substring(14), true));
        else if (t.startsWith("cover_photo_")) action = c.getString(pronounRes(t.substring(12), false));
        else if (t.equals("tag_request")) action = c.getString(R.string.notif_tag_request);
        else if (t.equals("collab_request")) action = c.getString(R.string.notif_collab_request);
        else if (t.equals("tag_accepted")) action = c.getString(R.string.notif_tag_accepted);
        else if (t.equals("collab_accepted")) action = c.getString(R.string.notif_collab_accepted);
        else if (t.equals("short_new")) action = c.getString(R.string.notif_short_new);
        else if (t.equals("birthday_wish")) action = "wishes you on your birthday \uD83C\uDF82";
        else if (t.equals("photo_new")) action = c.getString(R.string.notif_photo_new);
        else if (NotificationHelper.isReaction(t) || t.contains("like")) action = c.getString(reactionRes(t), kindStr);
        else if (t.contains("comment")) action = c.getString(R.string.notif_commented, kindStr);
        else if (t.contains("follow")) action = c.getString(R.string.notif_follow);
        else if (t.contains("share")) action = c.getString(R.string.notif_share);
        else if (t.contains("mention")) action = c.getString(R.string.notif_mention);
        else action = c.getString(R.string.notif_other);

        SpannableStringBuilder sb = new SpannableStringBuilder(who);
        sb.setSpan(new StyleSpan(Typeface.BOLD), 0, who.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append(c.getString(R.string.notif_sep)).append(action);
        return sb;
    }

    /** his / her / their -> প্রোফাইল বা কভার ছবির লেখা */
    private static int pronounRes(String p, boolean profile) {
        if ("his".equals(p)) return profile ? R.string.notif_pp_his : R.string.notif_cp_his;
        if ("her".equals(p)) return profile ? R.string.notif_pp_her : R.string.notif_cp_her;
        return profile ? R.string.notif_pp_their : R.string.notif_cp_their;
    }

    /** Liked · Loved · Haha'd · Angered ... (অনুবাদ করা) */
    private static int reactionRes(String t) {
        switch (t) {
            case "love": return R.string.notif_r_love;
            case "haha": return R.string.notif_r_haha;
            case "angry": return R.string.notif_r_angry;
            case "wow": return R.string.notif_r_wow;
            case "check": return R.string.notif_r_check;
            case "stop": return R.string.notif_r_stop;
            default: return R.string.notif_r_like;
        }
    }

    /** "এইমাত্র / ৫ মি. / ২ ঘ. / ৩ দি. / ২ সপ্তাহ" — অ্যাপের ভাষায় (ফোনের ভাষায় নয়) */
    static String relTime(Context c, long ms) {
        long diff = Math.max(0, System.currentTimeMillis() - ms);
        long min = diff / 60000L;
        if (min < 1) return c.getString(R.string.notif_time_now);
        if (min < 60) return c.getString(R.string.notif_time_min, (int) min);
        long hr = min / 60;
        if (hr < 24) return c.getString(R.string.notif_time_hr, (int) hr);
        long day = hr / 24;
        if (day < 7) return c.getString(R.string.notif_time_day, (int) day);
        if (day < 28) return c.getString(R.string.notif_time_wk, (int) (day / 7));
        java.text.DateFormat f = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM,
                c.getResources().getConfiguration().getLocales().get(0));
        return f.format(new java.util.Date(ms));
    }

    private String nameFor(Context c, String uid) {
        if (uid == null) return c.getString(R.string.notif_someone);
        String cached = names.get(uid);
        if (cached != null) return cached.isEmpty() ? c.getString(R.string.notif_someone) : cached;
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
                names.put(uid, v == null ? "" : v);   // খালি = নাম পাওয়া যায়নি (দেখানোর সময় "Someone"-এর অনুবাদ)
                notifyDataSetChanged();
            });
        }
        return c.getString(R.string.notif_someone);
    }

    // ---------- ব্যাজ ----------

    private void styleBadge(Context c, TextView badge, String type) {
        String t = type == null ? "" : type.toLowerCase();
        int ring = androidx.core.content.ContextCompat.getColor(c, R.color.n_bg);

        // রিঅ্যাকশন: আসল ইমোজি, ব্যাকগ্রাউন্ড ছাড়া (যেমন 😮 ❤️ 😆 😡)
        String emoji = null;
        if (t.equals("love")) emoji = "\u2764\uFE0F";
        else if (t.equals("haha")) emoji = "\uD83D\uDE06";
        else if (t.equals("wow")) emoji = "\uD83D\uDE2E";
        else if (t.equals("angry")) emoji = "\uD83D\uDE21";
        if (emoji != null) {
            badge.setBackground(null);
            badge.setTextSize(24);
            badge.setShadowLayer(3f, 0f, 1f, 0x44000000);
            badge.setText(emoji);
            return;
        }
        badge.setShadowLayer(0f, 0f, 0f, 0);
        badge.setTextSize(12);

        // কমেন্ট (সবুজ বৃত্ত + সাদা বাবল) আর লাইক (নীল বৃত্ত + সাদা থাম্বস-আপ)
        boolean isComment = t.contains("comment");
        boolean isLike = t.equals("like") || (t.contains("like") && !isComment);
        if (isComment || isLike) {
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(isComment ? 0xFF2E9E3F : 0xFF1877F2);   // কমেন্ট = সবুজ, লাইক = নীল
            circle.setStroke(dp(c, 2), ring);
            android.graphics.drawable.Drawable glyph = androidx.core.content.ContextCompat.getDrawable(c,
                    isComment ? R.drawable.ic_notif_comment_badge : R.drawable.ic_notif_like_badge);
            android.graphics.drawable.LayerDrawable ld = new android.graphics.drawable.LayerDrawable(
                    new android.graphics.drawable.Drawable[]{circle, glyph});
            int in = dp(c, isComment ? 5 : 7);
            ld.setLayerInset(1, in, in, in, in);
            badge.setBackground(ld);
            badge.setText("");
            return;
        }

        String icon;
        int color;
        if (t.startsWith("profile_photo_") || t.startsWith("cover_photo_")) { icon = "\uD83D\uDDBC"; color = 0xFF1877F2; } // 🖼
        else if (t.equals("warning")) { icon = "!"; color = 0xFFF7931E; }
        else if (t.startsWith("group")) { icon = "\uD83D\uDC65"; color = 0xFF009688; }                       // 👥
        else if (t.equals("tag_request") || t.equals("tag_accepted")) { icon = "\uD83C\uDFF7"; color = 0xFF1877F2; }       // 🏷
        else if (t.equals("collab_request") || t.equals("collab_accepted")) { icon = "\uD83E\uDD1D"; color = 0xFF31A24C; } // 🤝
        else if (t.equals("birthday_today") || t.equals("birthday_self") || t.equals("birthday_wish")) { icon = "\uD83C\uDF82"; color = 0xFFF7931E; }   // 🎂
        else if (t.equals("short_new")) { icon = "\uD83C\uDFAC"; color = 0xFFE41E3F; }             // 🎬
        else if (t.equals("photo_new")) { icon = "\uD83D\uDDBC"; color = 0xFF1877F2; }             // 🖼
        else if (t.equals("check")) { icon = "\u2714"; color = 0xFF31A24C; }                       // ✔
        else if (t.equals("stop")) { icon = "\u270B"; color = 0xFFE41E3F; }                         // ✋
        else if (t.contains("follow")) { icon = "\uD83D\uDC64"; color = 0xFF7B3FE4; }   // 👤
        else if (t.contains("share")) { icon = "\u21AA"; color = 0xFFF7B928; }          // ↪
        else if (t.contains("mention")) { icon = "@"; color = 0xFFE4405F; }
        else { icon = "\uD83D\uDD14"; color = 0xFF8A8D91; }                             // 🔔

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(color);
        bg.setStroke(dp(c, 2), ring);
        badge.setBackground(bg);
        badge.setText(icon);
    }

    // ---------- প্রোফাইল ছবি ----------

    private static final java.util.Map<String, String> GROUP_PHOTO = new java.util.HashMap<>();   // groupId -> cover_key ("" = নেই)

    private void showAppIcon(ImageView iv) {
        iv.setClipToOutline(true);
        iv.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) {
                o.setOval(0, 0, v.getWidth(), v.getHeight());
            }
        });
        iv.setTag(R.id.imgAvatar, "warning");
        iv.setImageResource(R.drawable.ic_familybook_new);
    }

    private void loadAvatar(final String uid, final ImageView iv) {
        loadAvatar(uid, null, iv);
    }

    /** সব ছবিই গোল: ইউজারের প্রোফাইল ছবি; গ্রুপ নোটিফিকেশনে গ্রুপের ছবি */
    private void loadAvatar(final String uid, final String groupId, final ImageView iv) {
        iv.setClipToOutline(true);
        iv.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) {
                o.setOval(0, 0, v.getWidth(), v.getHeight());
            }
        });
        iv.setTag(R.id.imgAvatar, groupId);
        if (groupId == null || groupId.isEmpty()) {
            AvatarLoader.load(iv, uid);
            return;
        }
        iv.setImageResource(R.drawable.ic_profile);
        String known = GROUP_PHOTO.get(groupId);
        if (known != null) { showGroupPhoto(iv, groupId, known, uid); return; }
        SupabaseRest.call("GET", "/rest/v1/groups?id=eq." + groupId + "&select=cover_key&limit=1",
                null, null, true, r -> {
            String key = "";
            if (r.ok()) {
                try {
                    JSONArray a = new JSONArray(r.body);
                    if (a.length() > 0 && !a.getJSONObject(0).isNull("cover_key"))
                        key = a.getJSONObject(0).optString("cover_key", "");
                } catch (Exception ignored) { }
            }
            GROUP_PHOTO.put(groupId, key);
            showGroupPhoto(iv, groupId, key, uid);
        });
    }

    private void showGroupPhoto(final ImageView iv, final String groupId, final String key, final String fallbackUid) {
        if (!groupId.equals(iv.getTag(R.id.imgAvatar))) return;     // রিসাইকল হয়ে গেছে
        if (key == null || key.isEmpty()) { DefaultAvatar.apply(iv, null); return; }   // গ্রুপের ছবি নেই: ডিফল্ট অবতার
        Media.loadBitmap(key, bm -> {
            if (!groupId.equals(iv.getTag(R.id.imgAvatar))) return;
            androidx.core.graphics.drawable.RoundedBitmapDrawable d =
                    androidx.core.graphics.drawable.RoundedBitmapDrawableFactory.create(iv.getResources(), bm);
            d.setCircular(true);
            iv.setImageDrawable(d);
        }, null);
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
