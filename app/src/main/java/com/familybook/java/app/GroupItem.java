package com.familybook.app;

/** একটি গ্রুপের তথ্য। GroupsRepo.load() সার্ভার থেকে এটি ভরে। */
public class GroupItem {
    public String id;
    public String name;
    public String category;
    public String location;
    public String description;
    public String createdAt = "";   // ISO সময় (যেমন 2026-10-24T13:44:00+00:00), সাজাতে কাজে লাগে
    public boolean isAdmin;         // আমি এই গ্রুপ বানিয়েছি / অ্যাডমিন
    public String visibility = "public";   // public / friends / members
    public boolean isPublic;        // visibility == public হলে true (Public group ট্যাবে আসে)
    public int members;             // সদস্য সংখ্যা (অ্যাডমিন বাদে)
    public int newPosts;            // আমি দেখিনি এমন নতুন পোস্ট

    // গ্রুপের সবচেয়ে নতুন পোস্ট ("From your groups"-এর জন্য); না থাকলে null/0
    public String lastPostId;
    public String lastPostAuthor;
    public String lastPostText;
    public long lastPostTime;

    public boolean hasPost() {
        return lastPostText != null && lastPostText.trim().length() > 0;
    }

    public String initial() {
        if (name == null || name.trim().isEmpty()) return "G";
        return name.trim().substring(0, 1).toUpperCase();
    }
}
