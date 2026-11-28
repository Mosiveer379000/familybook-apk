package com.familybook.app;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.Calendar;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * অ্যাপ বন্ধ থাকলেও সকালে ফোনের নোটিফিকেশনে "Today is X's birthday" পাঠায় (দিনে একবার)।
 * ক্লিক করলে BirthdayReminderActivity (Birthday reminder পেজ) খোলে।
 * FamilyBookApp প্রতি ৩ ঘণ্টার কাজ হিসেবে নিবন্ধন করে; সকাল ৭টার আগে কিছু পাঠায় না।
 */
public class BirthdayWorker extends Worker {

    private static final String CH_ID = "birthdays";
    private static final int NOTIF_ID = 7300;
    private static final int FIRST_HOUR = 7;

    public BirthdayWorker(@NonNull Context context, @NonNull WorkerParameters params) { super(context, params); }

    @NonNull @Override
    public Result doWork() {
        final Context app = getApplicationContext();
        try {
            if (!SupabaseRest.isLoggedIn()) return Result.success();
            if (Calendar.getInstance().get(Calendar.HOUR_OF_DAY) < FIRST_HOUR) return Result.success();
            if (BirthdayToday.wasPosted(app)) return Result.success();

            final CountDownLatch latch = new CountDownLatch(1);
            final List<?>[] box = new List<?>[1];
            BirthdayToday.load(true, l -> { box[0] = l; latch.countDown(); });
            if (!latch.await(30, TimeUnit.SECONDS) || box[0] == null || box[0].isEmpty()) return Result.success();

            @SuppressWarnings("unchecked") List<BirthdayToday.Person> list = (List<BirthdayToday.Person>) box[0];
            post(app, list);
            BirthdayToday.markPosted(app);
        } catch (Throwable ignore) { }
        return Result.success();
    }

    private static void post(Context app, List<BirthdayToday.Person> list) {
        if (Build.VERSION.SDK_INT >= 33 && app.checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(
                    CH_ID, app.getString(R.string.bday_channel), NotificationManager.IMPORTANCE_DEFAULT));
        }
        Intent open = new Intent(app, BirthdayReminderActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(app, NOTIF_ID, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String title = BirthdayToday.title(app, list);
        NotificationCompat.Builder b = new NotificationCompat.Builder(app, CH_ID)
                .setSmallIcon(R.drawable.ic_birthday_reminder)
                .setContentTitle(title)
                .setContentText(app.getString(R.string.bday_notif_text))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(pi);
        nm.notify(NOTIF_ID, b.build());
    }
}
