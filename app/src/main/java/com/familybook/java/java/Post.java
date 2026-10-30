package com.familybook.app;

import org.json.JSONObject;

public class Post {
    public int comments;
    public boolean edited;
    public boolean hiddenByMe;   // Home সেট করে (hidden_posts থেকে)
    public String id;
    public boolean likedByMe;
    public boolean soundHaha = true, soundCheck = true, soundWow = true; // মিম সাউন্ড চালু?
    public String myReaction;          // like | love | check | stop | angry | haha (null = নেই)
    public int likes;
    public int views;                  // কতজন দেখেছে
    public boolean viewedByMe;
    public int textColor;              // 0 = সাধারণ লেখা
    public int bgColor;                // 0 = সাধারণ (ব্যাকগ্রাউন্ড ছাড়া)
    public String hashtags;
    public int shares;                 // কতজন শেয়ার করেছে
    public String sharedPostId;        // শেয়ার করা পোস্ট হলে আসল পোস্টের id
    public String sharedUid;           // আসল পোস্টারের uid
    public String sharedName;          // আসল পোস্টারের নাম
    public String sharedText;          // আসল পোস্টের লেখা
    public boolean mutedByMe;
    public String name;
    public long statusAt;
    public String text;
    public long timestamp;
    public String uid;
    public String privacy = "public";       // public | friends | me
    public String commentPerm = "public";   // public | friends | me
    public String status = "active";        // active | archived | trashed
    // গ্রুপ পোস্ট (post_cards_g ভিউ থেকে): সাধারণ পোস্টে সব null
    public String groupId, groupName, groupRole, groupVisibility;
    public boolean isGroupPost() { return groupId != null && !groupId.isEmpty(); }

    /** Supabase post_cards ভিউ-র একটি সারি → Post */
    public static Post from(JSONObject o, String myUid) {
        Post p = new Post();
        p.id = s(o, "id");
        p.uid = s(o, "user_id");
        p.name = s(o, "author_name");
        p.text = s(o, "text");
        p.privacy = def(s(o, "privacy"), "public");
        p.commentPerm = def(s(o, "comment_perm"), "public");
        p.status = def(s(o, "status"), "active");
        p.timestamp = time(s(o, "created_at"));
        p.statusAt = time(s(o, "status_at"));
        p.likes = o.optInt("like_count", 0);
        p.views = o.optInt("view_count", 0);
        p.shares = o.optInt("share_count", 0);
        p.comments = o.optInt("comment_count", 0);
        p.viewedByMe = o.optBoolean("viewed_by_me", false);
        p.mutedByMe = o.optBoolean("muted_by_me", false);
        p.edited = o.optBoolean("edited", false);
        p.textColor = color(s(o, "text_color"));
        p.bgColor = color(s(o, "bg_color"));
        p.hashtags = s(o, "hashtags");
        p.sharedPostId = s(o, "shared_post_id");
        p.sharedUid = s(o, "shared_uid");
        p.sharedName = s(o, "shared_name");
        p.sharedText = s(o, "shared_text");
        p.groupId = s(o, "group_id");
        p.groupName = s(o, "group_name");
        p.groupRole = s(o, "group_role");
        p.groupVisibility = s(o, "group_visibility");
        p.soundHaha = o.optBoolean("sound_haha", true);
        p.soundCheck = o.optBoolean("sound_check", true);
        p.soundWow = o.optBoolean("sound_wow", true);
        ReactionKit.cacheSounds(p.id, p.soundHaha, p.soundCheck, p.soundWow);
        String mine = s(o, "my_reaction");
        p.likedByMe = mine != null;
        p.myReaction = mine == null ? null : ReactionKit.normalize(mine);
        return p;
    }

    /** JSON null হলে null (org.json optString "null" লেখা দেয়, তাই এই সাহায্যকারী) */
    private static String s(JSONObject o, String k) {
        return o.isNull(k) ? null : o.optString(k, null);
    }

    private static String def(String v, String d) {
        return v == null || v.isEmpty() ? d : v;
    }

    private static long time(String iso) {
        return iso == null ? 0 : NotificationItem.parseIso(iso);
    }

    private static int color(String v) {
        if (v == null || v.trim().isEmpty()) return 0;
        try { return (int) Long.parseLong(v.trim()); } catch (Exception e) { return 0; }
    }

    public boolean isMine(String myUid) {
        return myUid != null && myUid.equals(this.uid);
    }
}
