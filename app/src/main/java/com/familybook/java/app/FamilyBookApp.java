package com.familybook.app;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

public class FamilyBookApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        SupabaseRest.init(this);
        Net.start(this);
        Media.init(this);          // দেখা ছবি ফোনে জমে, অফলাইনেও দেখা যায়
        PostImage.init(this);
        AvatarLoader.init(this);
        PostQueue.init(this);      // অসমাপ্ত Draft ফিরিয়ে আনে; নেট থাকা-না-থাকা শোনে

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
            @Override public void onActivityStarted(Activity a) { Presence.onActivityStarted(); }
            @Override
            public void onActivityPaused(Activity a) {
                UpdateChecker.onPause(a);
            }
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
            @Override public void onActivityDestroyed(Activity a) { }
        });
    }
}