package com.familybook.app;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/** জন্মদিনের নোটিফিকেশন শুধু অ্যাপের ভেতরে দেখায়; ফোনের প্যানেলে কিছু পাঠায় না। */
public class BirthdayWorker extends Worker {
    public BirthdayWorker(@NonNull Context context, @NonNull WorkerParameters params) { super(context, params); }

    @NonNull @Override
    public Result doWork() { return Result.success(); }
}
