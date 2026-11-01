package com.familybook.app;

import android.content.Context;
import android.content.SharedPreferences;

/** Settings → Media: ভিডিও অটো-প্লে চালু/বন্ধ (Profile, Shorts ও Feeds) */
final class MediaPrefs {
    private static final String FILE = "media_prefs";
    private static final String KEY_STOP_AUTOPLAY = "stop_autoplay";

    private MediaPrefs() { }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** true = অটো-প্লে চলবে (ডিফল্ট) */
    static boolean autoplay(Context c) {
        return !sp(c).getBoolean(KEY_STOP_AUTOPLAY, false);
    }

    /** "Scroll to next reel automatically": রিল শেষ হলে নিজে থেকে পরের রিলে নামে (ডিফল্ট বন্ধ) */
    static boolean autoNext(Context c) {
        return sp(c).getBoolean("auto_next_reel", false);
    }

    static void setAutoNext(Context c, boolean on) {
        sp(c).edit().putBoolean("auto_next_reel", on).apply();
        ShortPlayerView.setAutoNext(on);
    }

    static void setStopAutoplay(Context c, boolean stop) {
        sp(c).edit().putBoolean(KEY_STOP_AUTOPLAY, stop).apply();
    }
}
