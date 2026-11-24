package com.familybook.app;

import android.app.Activity;
import android.graphics.drawable.ColorDrawable;

import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Settings-এর ভেতরের পেজগুলো ডার্ক মোডে খুললে উইন্ডো ব্যাকগ্রাউন্ড, স্ট্যাটাস বার ও নেভিগেশন বার ডার্ক করে।
 * (পেজের ভেতরের রং d_* রং-টোকেন ও DC/WebDark থেকে আসে।) FamilyBookApp থেকে ডাকা হয়।
 */
public final class DarkPages {
    private DarkPages() { }

    private static final Set<String> PAGES = new HashSet<>(Arrays.asList(
            "AccountsPrivacyActivity", "BlockingActivity", "LockProfileActivity", "ProfileInfoEditActivity",
            "PasswordSecurityActivity", "AddFamilyActivity", "PinnedDetailsActivity", "PlaceEditActivity",
            "BirthdayEditActivity", "GenderEditActivity", "LinksEditActivity", "HobbiesPickActivity",
            "InterestPickActivity", "CareerFormActivity", "ContactSocialActivity", "ContactFieldActivity",
            "RelationshipActivity", "LanguagesEditActivity", "BioEditActivity", "CategoryEditActivity",
            "NidScanActivity", "MediaSettingsActivity", "NotificationSettingsActivity",
            "ReactionPreferencesActivity", "PaymentActivity", "PayoutActivity", "DarkModeActivity",
            "BlueBadgeActivity", "OfflineActivity"
    ));

    public static void apply(Activity a) {
        if (!PAGES.contains(a.getClass().getSimpleName())) return;
        if (!DC.night()) return;
        int bg = 0xFF18191A;
        a.getWindow().setBackgroundDrawable(new ColorDrawable(bg));
        a.getWindow().setStatusBarColor(bg);
        a.getWindow().setNavigationBarColor(bg);
        try {
            WindowInsetsControllerCompat c = WindowCompat.getInsetsController(a.getWindow(), a.getWindow().getDecorView());
            if (c != null) {
                c.setAppearanceLightStatusBars(false);
                c.setAppearanceLightNavigationBars(false);
            }
        } catch (Exception ignored) { }
    }
}
