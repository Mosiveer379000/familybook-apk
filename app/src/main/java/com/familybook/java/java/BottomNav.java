package com.familybook.app;

import android.app.Activity;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.Toast;

/**
 * সব স্ক্রিনে একই নিচের নেভিগেশন (Home · Gallery · Shop · Dashboard · Profile)।
 * ব্যবহার:
 *   - লেআউটে  <include layout="@layout/view_bottom_nav" />  থাকলে  BottomNav.attach(this);
 *   - কোডে বানানো স্ক্রিনে  root.addView(BottomNav.inflate(this, root));  তারপর  BottomNav.attach(this);
 * Profile আইকনে চাপলে Menu (TreedotActivity) খোলে — Home-এর মতই।
 */
public final class BottomNav {

    private BottomNav() {}

    /** কোডে বানানো স্ক্রিনের জন্য: নেভিগেশন বার তৈরি করে ফেরত দেয় (parent এ নিজে add করতে হবে) */
    public static View inflate(Activity a, ViewGroup parent) {
        return LayoutInflater.from(a).inflate(R.layout.view_bottom_nav, parent, false);
    }

    /** নেভিগেশন বারের ক্লিক লিসেনার বসায় ও নিজের ছবি লোড করে */
    public static void attach(final Activity a) {
        View home = a.findViewById(R.id.bnHome);
        if (home == null) return;

        home.setOnClickListener(v -> {
            Intent i = new Intent(a, HomeActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            a.startActivity(i);
        });

        a.findViewById(R.id.bnGallery).setOnClickListener(v -> {
            if (a instanceof ShortViewerActivity) return;
            ShortViewerActivity.openFeed(a);   // Shorts আইকন → Shorts ভিডিও ফিড
        });

        a.findViewById(R.id.bnShop).setOnClickListener(v ->
                Toast.makeText(a, "Shop - coming soon", Toast.LENGTH_SHORT).show());

        a.findViewById(R.id.bnDash).setOnClickListener(v ->
                Toast.makeText(a, "Dashboard - coming soon", Toast.LENGTH_SHORT).show());

        a.findViewById(R.id.bnProfile).setOnClickListener(v -> {
            if (a instanceof TreedotActivity) return;
            // Menu আগে থেকে স্ট্যাকে থাকলে সেটাতেই ফিরে যাবে, নইলে নতুন খুলবে
            Intent i = new Intent(a, TreedotActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            a.startActivity(i);
        });

        try {
            ImageView avatar = a.findViewById(R.id.bnAvatar);
            String uid = SupabaseRest.uid();
            if (avatar != null && uid != null) AvatarLoader.load(avatar, uid);
        } catch (Exception ignored) { }
    }
}
