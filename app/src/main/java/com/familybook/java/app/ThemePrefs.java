package com.familybook.app;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;

/**
 * ডার্ক মোড সেটিং: "on" / "off" / "system" ফোনে সেভ রাখে এবং পুরো অ্যাপে প্রয়োগ করে।
 * ডিফল্ট "off" (Dark_Mode_Settings.html-এর ডিফল্টের সাথে মেলানো)।
 */
public final class ThemePrefs {

    public static final String ON = "on";
    public static final String OFF = "off";
    public static final String SYSTEM = "system";

    private static final String FILE = "fb_theme";
    private static final String KEY = "dark_mode";

    private ThemePrefs() { }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** সেভ করা মোড; ভুল/খালি হলে "off" */
    public static String get(Context c) {
        String m = sp(c).getString(KEY, OFF);
        return (ON.equals(m) || SYSTEM.equals(m)) ? m : OFF;
    }

    /** সেভ করে এবং সাথে সাথে অ্যাপে প্রয়োগ করে */
    public static void set(Context c, String mode) {
        if (!ON.equals(mode) && !OFF.equals(mode) && !SYSTEM.equals(mode)) return;
        sp(c).edit().putString(KEY, mode).apply();
        apply(mode);
    }

    /** অ্যাপ চালুর সময় একবার ডাকতে হবে (FamilyBookApp.onCreate) */
    public static void applySaved(Context c) {
        apply(get(c));
    }

    private static void apply(String mode) {
        int night;
        if (ON.equals(mode)) night = AppCompatDelegate.MODE_NIGHT_YES;
        else if (SYSTEM.equals(mode)) night = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        else night = AppCompatDelegate.MODE_NIGHT_NO;
        AppCompatDelegate.setDefaultNightMode(night);
    }
}
