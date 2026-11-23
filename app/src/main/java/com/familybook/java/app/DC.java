package com.familybook.app;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;

import androidx.appcompat.app.AppCompatDelegate;

/**
 * Dark/Light রং সহায়ক: কোডে বানানো (programmatic) ভিউয়ের রং ডার্ক মোডে বদলে দেয়।
 * DC.c("#050505") = লাইটে একই রং, ডার্কে সংশ্লিষ্ট ডার্ক রং। DC.bg() = পেজ/বক্সের সাদা ব্যাকগ্রাউন্ড।
 */
public final class DC {
    private DC() { }

    private static Context app;

    public static void init(Context c) { app = c.getApplicationContext(); }

    public static boolean night() {
        int m = AppCompatDelegate.getDefaultNightMode();
        if (m == AppCompatDelegate.MODE_NIGHT_YES) return true;
        if (m == AppCompatDelegate.MODE_NIGHT_NO) return false;
        if (app == null) return false;
        return (app.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    /** পেজ/কার্ড/ইনপুটের সাদা ব্যাকগ্রাউন্ড */
    public static int bg() { return night() ? 0xFF18191A : Color.WHITE; }

    /** hex স্ট্রিং -> রং (ডার্কে সংশ্লিষ্ট রং) */
    public static int c(String hex) {
        if (night()) {
            String h = hex.toUpperCase();
            switch (h) {
                case "#050505": case "#000000": case "#333333": case "#111111":
                    return 0xFFE4E6EB;
                case "#65676B": case "#444444": case "#505050": case "#555555":
                    return 0xFFB0B3B8;
                case "#F0F2F5": case "#F2F3F5":
                    return 0xFF3A3B3C;
                case "#CED0D4": case "#E4E6EB": case "#E0E0E0": case "#D9D9D9": case "#DDDDDD":
                    return 0xFF3E4042;
                case "#E7F3FF": case "#DDEBFC": case "#D6E9FF":
                    return 0xFF263951;
                default: break;
            }
        }
        return Color.parseColor(hex);
    }
}
