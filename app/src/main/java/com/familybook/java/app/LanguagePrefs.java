package com.familybook.app;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

/**
 * Settings > Language: অ্যাপের ভাষা (App language) ও পোস্ট অনুবাদের ভাষা (Translation language) ফোনে সেভ রাখে।
 * অ্যাপের ভাষা বদলালে AppCompatDelegate.setApplicationLocales() দিয়ে পুরো অ্যাপে প্রয়োগ হয়
 * (res/values-bn, values-hi ... ফোল্ডারের strings.xml থেকে লেখা বদলায়)।
 */
public final class LanguagePrefs {

    public static final String DEFAULT_APP = "English (US)";
    public static final String DEFAULT_TRANS = "Bengali";

    private static final String FILE = "fb_language";
    private static final String KEY_APP = "app_language";
    private static final String KEY_TRANS = "translation_language";

    private LanguagePrefs() { }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static String getApp(Context c) { return sp(c).getString(KEY_APP, DEFAULT_APP); }

    public static String getTrans(Context c) { return sp(c).getString(KEY_TRANS, DEFAULT_TRANS); }

    /** সেভ করে এবং সাথে সাথে পুরো অ্যাপে প্রয়োগ করে */
    public static void setApp(Context c, String name) {
        if (tag(name) == null) return;
        sp(c).edit().putString(KEY_APP, name).apply();
        apply(name);
    }

    public static void setTrans(Context c, String name) {
        if (tag(name) == null) return;
        sp(c).edit().putString(KEY_TRANS, name).apply();
    }

    /** অনুবাদের ভাষার কোড (যেমন "bn") — পোস্ট ট্রান্সলেট ফিচারে ব্যবহার হবে */
    public static String transTag(Context c) {
        String t = tag(getTrans(c));
        return t == null ? "bn" : t;
    }

    /** অ্যাপ চালুর সময় একবার ডাকতে হবে (FamilyBookApp.onCreate); আগে কিছু বাছা না হলে ফোনের ভাষাই থাকে */
    public static void applySaved(Context c) {
        String saved = sp(c).getString(KEY_APP, null);
        if (saved != null) apply(saved);
    }

    private static void apply(String name) {
        String t = tag(name);
        if (t == null) return;
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(t));
    }

    /** HTML-এর ভাষার নাম -> ভাষা কোড */
    static String tag(String name) {
        if (name == null) return null;
        switch (name) {
            case "English (US)": return "en-US";
            case "English (UK)": return "en-GB";
            case "Bengali": return "bn";
            case "Hindi": return "hi";
            case "Urdu": return "ur";
            case "Arabic": return "ar";
            case "Spanish": return "es";
            case "Mandarin Chinese": return "zh-CN";
            case "French": return "fr";
            case "Portuguese": return "pt";
            case "Indonesian": return "in";   // Android-এ Indonesian = "in"
            case "Turkish": return "tr";
            default: return null;
        }
    }
}
