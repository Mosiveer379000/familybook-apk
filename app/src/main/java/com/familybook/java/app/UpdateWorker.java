package com.familybook.app;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/** অ্যাপ বন্ধ থাকলেও পর্দার আড়ালে নতুন Release খোঁজে (FamilyBookApp এটা WorkManager-এ নিবন্ধন করে) */
public class UpdateWorker extends Worker {
    public UpdateWorker(@NonNull Context context, @NonNull WorkerParameters params) { super(context, params); }

    @NonNull @Override public Result doWork() {
        UpdateChecker.backgroundCheck(getApplicationContext());
        return Result.success();
    }
}
