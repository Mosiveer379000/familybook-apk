package com.familybook.app;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

public class FamilyBookApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        DC.init(this);
        ThemePrefs.applySaved(this);   // ডার্ক মোড সেটিং (On/Off/System) অ্যাপ চালুতেই প্রয়োগ
        LanguagePrefs.applySaved(this); // সেভ করা অ্যাপের ভাষা অ্যাপ চালুতেই প্রয়োগ
        FbCache.init(this);
        SupabaseRest.init(this);
        Net.start(this);
        SettingsCache.init(this);  // Settings-এর সার্ভার-ডাটার ফোন-কপি
        WebGuard.init(this);       // Settings HTML পেজের Tailwind/ফন্ট ফোনে জমে, অফলাইনেও চলে
        SettingsGate.init(this);   // নেট থাকলে Settings পেজের ক্যাশ নতুন রাখে; না থাকলে offline.html
        Media.init(this);          // দেখা ছবি ফোনে জমে, অফলাইনেও দেখা যায়
        PostImage.init(this);
        AvatarLoader.init(this);
        PostQueue.init(this);      // অসমাপ্ত Draft ফিরিয়ে আনে; নেট থাকা-না-থাকা শোনে

        // অ্যাপ বন্ধ থাকলেও নতুন আপডেট খুঁজবে (সর্বনিম্ন ব্যবধান ১৫ মিনিট; নেট থাকলে চলে)
        androidx.work.PeriodicWorkRequest upd = new androidx.work.PeriodicWorkRequest.Builder(
                UpdateWorker.class, 15, java.util.concurrent.TimeUnit.MINUTES)
                .setConstraints(new androidx.work.Constraints.Builder()
                        .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
                .build();
        androidx.work.WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "update_check", androidx.work.ExistingPeriodicWorkPolicy.KEEP, upd);

        // সকালে ফোনের নোটিফিকেশনে "Today is X's birthday" (দিনে একবার; BirthdayWorker নিজেই দিন/সময় দেখে)
        androidx.work.PeriodicWorkRequest bday = new androidx.work.PeriodicWorkRequest.Builder(
                BirthdayWorker.class, 3, java.util.concurrent.TimeUnit.HOURS)
                .setConstraints(new androidx.work.Constraints.Builder()
                        .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
                .build();
        androidx.work.WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "birthday_check", androidx.work.ExistingPeriodicWorkPolicy.KEEP, bday);

        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityResumed(Activity a) {
                if (a instanceof AppCompatActivity) {
                    UpdateChecker.onResume(a);
                }
            }

            @Override
            public void onActivityStopped(Activity a) {
                UpdateChecker.onStop(a);
                Presence.onActivityStopped();
            }

            @Override public void onActivityCreated(Activity a, Bundle b) { }
            @Override public void onActivityStarted(Activity a) {
                Presence.onActivityStarted();
                DarkPages.apply(a);
            }
            @Override
            public void onActivityPaused(Activity a) {
                UpdateChecker.onPause(a);
            }
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
            @Override public void onActivityDestroyed(Activity a) { }
        });
    }
}
