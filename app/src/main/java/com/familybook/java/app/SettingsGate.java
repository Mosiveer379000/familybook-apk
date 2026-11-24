package com.familybook.app;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Settings & Privacy-এর যেকোনো পেজ খোলার একমাত্র দরজা।
 *
 *  - ইন্টারনেট আছে        → পেজ খোলে (আর পেছনে সব ক্যাশ নতুন করে জমায়)
 *  - ইন্টারনেট নেই, কিন্তু ক্যাশ আছে → সেই ক্যাশ থেকেই পেজ খোলে
 *  - ইন্টারনেট নেই, ক্যাশও নেই     → assets/offline.html (OfflineActivity) খোলে
 *
 * "ক্যাশ আছে" মানে:
 *  - HTML পেজ (WebView): পেজ নিজে assets-এ আছেই; লাগে শুধু Tailwind/ফন্ট → WebGuard.ready()
 *  - সার্ভার-নির্ভর পেজ (Account, Password, Blocking, Profile lock/details, Family): SettingsCache.isWarm()
 */
public final class SettingsGate {
    private SettingsGate() { }

    /** WebView-তে assets HTML দেখায় এমন পেজ */
    private static final Set<String> HTML_PAGES = new HashSet<>(Arrays.asList(
            "DarkModeActivity", "LanguageRegionActivity", "MediaSettingsActivity",
            "NotificationSettingsActivity", "ReactionPreferencesActivity",
            "BlueBadgeActivity", "PaymentActivity", "PayoutActivity"
    ));

    /** নেটিভ (XML layout + Java) বা সার্ভার-ডাটা লাগে এমন পেজ */
    private static final Set<String> SERVER_PAGES = new HashSet<>(Arrays.asList(
            "AccountsPrivacyActivity", "PasswordSecurityActivity", "BlockingActivity",
            "LockProfileActivity", "ProfileInfoEditActivity", "AddFamilyActivity"
    ));

    /** Settings থেকে কোনো পেজ খুলতে startActivity(intent)-এর বদলে এটা ডাকুন */
    public static void open(Context ctx, Intent intent) {
        warm();
        String name = simpleName(intent);
        if (canOpen(name)) {
            ctx.startActivity(intent);
            return;
        }
        Intent o = new Intent(ctx, OfflineActivity.class).putExtra(OfflineActivity.EXTRA_TARGET, name);
        Bundle extras = intent.getExtras();
        if (extras != null) o.putExtra(OfflineActivity.EXTRA_TARGET_EXTRAS, extras);
        ctx.startActivity(o);
    }

    public static void open(Context ctx, Class<?> target) {
        open(ctx, new Intent(ctx, target));
    }

    /** এখন এই পেজ খোলা যাবে কি (অনলাইন, নয়তো ক্যাশ তৈরি) */
    public static boolean canOpen(String simpleName) {
        if (Net.isOnline()) return true;
        if (HTML_PAGES.contains(simpleName)) return WebGuard.ready();
        if (SERVER_PAGES.contains(simpleName)) return SettingsCache.isWarm();
        return true;
    }

    /** নেট থাকলে ক্যাশ নতুন রাখে: Tailwind/ফন্ট + Settings-এর সার্ভার-ডাটা */
    public static void warm() {
        if (!Net.isOnline()) return;
        WebGuard.prefetch();
        SettingsCache.warmUp();
    }

    /** অ্যাপ চালুর সময় একবার ডাকুন; নেট ফিরলেও নিজে নিজে ওয়ার্ম করবে */
    public static void init(Context c) {
        Net.addListener(online -> { if (online) warm(); });
        warm();
    }

    private static String simpleName(Intent i) {
        if (i.getComponent() == null) return "";
        String n = i.getComponent().getClassName();
        int dot = n.lastIndexOf('.');
        return dot >= 0 ? n.substring(dot + 1) : n;
    }
}
