package com.familybook.app;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import androidx.core.app.NotificationCompat;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;
import androidx.core.content.pm.PackageInfoCompat;


import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.lang.ref.WeakReference;

/**
 * অ্যাপের ভেতরে আপডেট চেক + ডাউনলোড (লাইম প্রগ্রেস বারসহ) + ইনস্টল।
 *
 * Supabase টেবিল app_update (একটিই সারি, id = 1):
 *   version_code     (number) : সর্বশেষ ভার্সন কোড
 *   min_version_code (number) : এর চেয়ে পুরনো ভার্সনে আপডেট বাধ্যতামূলক (ঐচ্ছিক)
 *   version_name     (text)   : যেমন "1.1"
 *   apk_url          (text)   : APK-র সরাসরি ডাউনলোড লিংক
 *   message          (text)   : ডায়ালগে দেখানোর বার্তা (ঐচ্ছিক)
 *   force            (bool)   : true হলে "Later" বাটন থাকবে না (ঐচ্ছিক)
 *
 * FamilyBookApp থেকে আপনা-আপনি কল হয়; অন্য কোনো Activity বদলাতে হয় না।
 *
 * Git-এ push করলে GitHub Actions (.github/workflows/release.yml) APK বিল্ড করে Supabase Storage-এ
 * তোলে এবং এই টেবিলের সারিটি নিজে আপডেট করে দেয় — হাতে কিছু করতে হয় না।
 */
public final class UpdateChecker {

    private static final String APK_NAME = "FamilyBook-update.apk";
    private static final String MIME_APK = "application/vnd.android.package-archive";

    private static class Info {
        long code;
        long minCode;
        String name;
        String url;
        String message;
        boolean force;
    }

    private static boolean listening;
    private static boolean later;
    private static boolean downloaded;
    private static boolean receiverRegistered;
    private static long downloadId = -1;
    private static Info pending;
    private static AlertDialog dialog;
    private static WeakReference<Activity> top = new WeakReference<>(null);

    // ---- প্রগ্রেস UI-র অবস্থা ----
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static Context appCtx;
    private static boolean downloading;
    private static boolean celebrated;
    private static int lastProgress;            // 0..1000
    private static LinearLayout box;
    private static ProgressBar bar;
    private static TextView pctText;
    private static TextView statusText;
    private static ObjectAnimator pulse;
    private static ObjectAnimator fill;

    private static final Runnable POLL = new Runnable() {
        @Override
        public void run() {
            pollOnce();
        }
    };

    private UpdateChecker() {
    }

    // ---- Activity lifecycle থেকে কল হয় ----

    public static void onResume(Activity act) {
        top = new WeakReference<>(act);
        appCtx = act.getApplicationContext();
        askNotifPermission(act);
        listen(appCtx);
        if (pending != null) {
            showDialog(act);
        }
    }

    public static void onPause(Activity act) {
        if (top.get() == act) top = new WeakReference<>(null);
    }

    public static void onStop(Activity act) {
        dismissDialog();
    }

    // ---- ভার্সন চেক ----

    private static long currentCode(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return PackageInfoCompat.getLongVersionCode(pi);
        } catch (Throwable e) {
            return Long.MAX_VALUE;
        }
    }

    private static final long CHECK_MS = 60000;
    private static final long RESUME_GAP_MS = 20000;

    private static Context checkCtx;
    private static boolean fetching;
    private static long lastFetch;

    private static final Runnable CHECK = new Runnable() {
        @Override
        public void run() {
            if (checkCtx != null) fetchInfo(checkCtx);
        }
    };

    /**
     * app_update টেবিল প্রতি ১ মিনিটে দেখে এবং অ্যাপ খোলা/ফিরে আসার সময়ও (২০ সেকেন্ডের ব্যবধানে)
     * দেখে: নতুন ভার্সন বসালে ইউজারের স্ক্রিনে আপডেট ডায়ালগ আসবে।
     */
    private static void listen(final Context app) {
        checkCtx = app;
        if (!listening) {
            listening = true;
            fetchInfo(app);
        } else if (!fetching && System.currentTimeMillis() - lastFetch > RESUME_GAP_MS) {
            fetchInfo(app);
        }
    }

    private static void fetchInfo(final Context app) {
        if (fetching) return;
        fetching = true;
        lastFetch = System.currentTimeMillis();
        H.removeCallbacks(CHECK);
        SupabaseRest.call("GET",
                "/rest/v1/app_updates?select=version_code,version_name,apk_url,whats_new&order=version_code.desc&limit=1",
                null, null, false, r -> {
            fetching = false;
            try {
                if (r.ok()) applyInfo(app, r.body);
            } finally {
                H.removeCallbacks(CHECK);
                H.postDelayed(CHECK, CHECK_MS);
            }
        });
    }

    private static String str(JSONObject o, String k) {
        return o.isNull(k) ? null : o.optString(k, null);
    }

    private static void applyInfo(Context app, String body) {
        try {
            JSONArray arr = new JSONArray(body);
            JSONObject s = arr.length() == 0 ? null : arr.getJSONObject(0);
            long cur = currentCode(app);
            long code = s == null ? 0 : s.optLong("version_code", 0);
            String url = s == null ? null : str(s, "apk_url");
            if (s == null || url == null || url.trim().isEmpty() || code <= cur) {
                pending = null;
                dismissDialog();
                return;
            }
            Info i = new Info();
            i.code = code;
            i.minCode = s.optLong("min_version_code", 0);
            i.name = str(s, "version_name");
            i.url = url.trim();
            i.message = str(s, "whats_new");
            i.force = s.optBoolean("force", false) || cur < i.minCode;

            if (pending == null || pending.code != i.code) {
                // নতুন ভার্সন এসেছে: আগের অবস্থা মুছে নতুন করে দেখাও
                later = false;
                downloaded = false;
                dismissDialog();
            }
            pending = i;
            notifyUpdate(app, i);
            Activity a = top.get();
            if (a != null) showDialog(a);
        } catch (Throwable ignore) {
        }
    }

    // ---- ফোনের নোটিফিকেশন প্যানেলে "FamilyBook : New Update vX — Whats new!!" ----

    private static final String CH_ID = "app_update";
    private static final int NOTIF_ID = 7001;
    private static boolean askedNotifPerm;

    /** Android 13+ এ নোটিফিকেশন পারমিশন একবার চায় */
    private static void askNotifPermission(Activity act) {
        if (Build.VERSION.SDK_INT < 33 || askedNotifPerm) return;
        if (act.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            askedNotifPerm = true;
            act.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIF_ID);
        }
    }

    /** প্রতিটি নতুন ভার্সনের জন্য একবারই নোটিফিকেশন দেয় (পারমিশন না থাকলে পরের চেকে আবার চেষ্টা) */
    private static void notifyUpdate(Context app, Info i) {
        try {
            SharedPreferences sp = app.getSharedPreferences("update_prefs", Context.MODE_PRIVATE);
            if (sp.getLong("notified_code", 0) >= i.code) return;
            if (Build.VERSION.SDK_INT >= 33 && app.checkSelfPermission(
                    Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;

            NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel(
                        CH_ID, "App updates", NotificationManager.IMPORTANCE_HIGH));
            }

            Intent open = app.getPackageManager().getLaunchIntentForPackage(app.getPackageName());
            if (open == null) return;
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(app, 0, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            String title = "FamilyBook : New Update" + (i.name != null ? " v" + i.name : "");
            String more = (i.message != null && !i.message.trim().isEmpty())
                    ? "Whats new!!\n" + i.message.trim() : "Whats new!!";

            NotificationCompat.Builder b = new NotificationCompat.Builder(app, CH_ID)
                    .setSmallIcon(R.drawable.ic_family_sett)
                    .setContentTitle(title)
                    .setContentText("Whats new!!")
                    .setStyle(new NotificationCompat.BigTextStyle().setBigContentTitle(title).bigText(more))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .setContentIntent(pi);
            nm.notify(NOTIF_ID, b.build());
            sp.edit().putLong("notified_code", i.code).apply();
        } catch (Throwable ignore) {
        }
    }

    private static void dismissDialog() {
        stopAnimators();
        box = null;
        bar = null;
        pctText = null;
        statusText = null;
        if (dialog != null) {
            try {
                dialog.dismiss();
            } catch (Throwable ignore) {
            }
            dialog = null;
        }
    }

    // ---- ডায়ালগ ----

    private static void showDialog(final Activity act) {
        if (pending == null || act.isFinishing()) return;
        if (dialog != null && dialog.isShowing()) return;
        if (later && !pending.force) return;

        final Info info = pending;
        String title = "Update available" + (info.name != null ? " (v" + info.name + ")" : "");
        String msg = (info.message != null && !info.message.trim().isEmpty())
                ? info.message
                : "A new version of Family Book is available. Please update to get the latest features and fixes.";

        AlertDialog.Builder b = new AlertDialog.Builder(act)
                .setTitle(title)
                .setMessage(msg)
                .setView(buildProgressView(act))
                .setCancelable(!info.force)
                .setPositiveButton(downloaded ? "Install now" : "Update now", null);
        if (!info.force) {
            b.setNegativeButton("Later", new android.content.DialogInterface.OnClickListener() {
                @Override
                public void onClick(android.content.DialogInterface d, int w) {
                    later = true;
                }
            });
        }
        dialog = b.create();
        dialog.show();
        // Update চাপলে ডায়ালগ বন্ধ হবে না (force হলে ব্যবহারকারী অ্যাপ চালাতে পারবে না)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startUpdate(act, info);
            }
        });

        // ডাউনলোড চলাকালে ডায়ালগ আবার খুললে প্রগ্রেস ফিরিয়ে আনো
        if (downloading) {
            showProgressUi();
            H.removeCallbacks(POLL);
            H.post(POLL);
        }
    }

    // ---- প্রগ্রেস UI (ছোট, লাইম, অ্যানিমেটেড) ----

    private static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static Drawable limeDrawable(Context c) {
        float r = dp(c, 5);

        GradientDrawable track = new GradientDrawable();
        track.setColor(0x33808080);
        track.setCornerRadius(r);

        GradientDrawable fillShape = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFF65A30D, 0xFF84CC16, 0xFFBEF264});
        fillShape.setCornerRadius(r);
        ClipDrawable clip = new ClipDrawable(fillShape, Gravity.LEFT, ClipDrawable.HORIZONTAL);

        LayerDrawable ld = new LayerDrawable(new Drawable[]{track, clip});
        ld.setId(0, android.R.id.background);
        ld.setId(1, android.R.id.progress);
        return ld;
    }

    private static View buildProgressView(Context c) {
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(c, 24), dp(c, 8), dp(c, 24), dp(c, 4));
        root.setVisibility(View.GONE);

        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView st = new TextView(c);
        st.setText("Downloading…");
        st.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        st.setAlpha(0.8f);
        row.addView(st, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView pc = new TextView(c);
        pc.setText("0%");
        pc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        pc.setTypeface(pc.getTypeface(), Typeface.BOLD);
        row.addView(pc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ProgressBar pb = new ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal);
        pb.setIndeterminate(false);
        pb.setMax(1000);
        pb.setProgressDrawable(limeDrawable(c));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 10));
        lp.topMargin = dp(c, 8);
        root.addView(pb, lp);

        box = root;
        bar = pb;
        pctText = pc;
        statusText = st;
        celebrated = false;
        return root;
    }

    private static void setButton(String text, boolean enabled) {
        if (dialog == null) return;
        Button b = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (b != null) {
            b.setText(text);
            b.setEnabled(enabled);
        }
    }

    private static void startPulse() {
        if (bar == null) return;
        if (pulse != null) pulse.cancel();
        pulse = ObjectAnimator.ofFloat(bar, "alpha", 0.72f, 1f);
        pulse.setDuration(650);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.setRepeatMode(ValueAnimator.REVERSE);
        pulse.start();
    }

    private static void stopAnimators() {
        H.removeCallbacks(POLL);   // শুধু ডাউনলোড-পোল থামাও; ভার্সন-চেকের টাইমার যেন না মরে
        if (pulse != null) {
            pulse.cancel();
            pulse = null;
        }
        if (fill != null) {
            fill.cancel();
            fill = null;
        }
        if (bar != null) {
            bar.animate().cancel();
            bar.setAlpha(1f);
            bar.setScaleY(1f);
        }
    }

    private static void showProgressUi() {
        if (box == null || bar == null) return;
        box.setVisibility(View.VISIBLE);
        bar.setProgress(lastProgress);
        pctText.setText((lastProgress / 10) + "%");
        statusText.setText("Downloading…");
        startPulse();
        setButton("Downloading…", false);
    }

    /** বারকে নরম গতিতে নতুন মানে (0..1000) নেয়; পিছনে যায় না */
    private static void animateTo(int target) {
        if (target < lastProgress) target = lastProgress;
        lastProgress = target;
        if (bar == null) return;
        if (fill != null) fill.cancel();
        fill = ObjectAnimator.ofInt(bar, "progress", bar.getProgress(), target);
        fill.setDuration(450);
        fill.setInterpolator(new DecelerateInterpolator());
        fill.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                if (pctText != null && bar != null) {
                    pctText.setText((bar.getProgress() / 10) + "%");
                }
            }
        });
        fill.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (bar != null && bar.getProgress() >= 1000 && !celebrated) celebrate();
            }
        });
        fill.start();
    }

    /** ১০০% হলে বারটা একবার লাফ দিয়ে ফোলে, তারপর স্প্রিং করে ফেরে */
    private static void celebrate() {
        celebrated = true;
        if (pulse != null) {
            pulse.cancel();
            pulse = null;
        }
        if (bar == null) return;
        bar.setAlpha(1f);
        if (statusText != null) statusText.setText("Download complete \u2713");
        if (pctText != null) pctText.setText("100%");
        bar.animate().scaleY(1.9f).setDuration(180).withEndAction(new Runnable() {
            @Override
            public void run() {
                if (bar != null) {
                    bar.animate().scaleY(1f).setDuration(380)
                            .setInterpolator(new OvershootInterpolator(3f)).start();
                }
            }
        }).start();
    }

    private static void pollOnce() {
        if (!downloading || appCtx == null) return;
        int status = -1;
        long got = 0;
        long total = -1;
        Cursor c = null;
        try {
            DownloadManager dm = (DownloadManager) appCtx.getSystemService(Context.DOWNLOAD_SERVICE);
            c = dm.query(new DownloadManager.Query().setFilterById(downloadId));
            if (c != null && c.moveToFirst()) {
                status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                got = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
            }
        } catch (Throwable ignore) {
        } finally {
            if (c != null) c.close();
        }

        if (status == DownloadManager.STATUS_SUCCESSFUL) {
            finishProgress();
            return;
        }
        if (status == DownloadManager.STATUS_FAILED || status == -1) {
            failProgress();
            return;
        }
        if (total > 0) {
            int p = (int) (got * 1000L / total);
            animateTo(Math.min(p, 990)); // ১০০% শুধু ডাউনলোড শেষ হলে
        }
        H.postDelayed(POLL, 350);
    }

    private static void finishProgress() {
        H.removeCallbacks(POLL);
        downloading = false;
        animateTo(1000);
    }

    private static void failProgress() {
        H.removeCallbacks(POLL);
        downloading = false;
        lastProgress = 0;
        if (pulse != null) {
            pulse.cancel();
            pulse = null;
        }
        if (bar != null) {
            bar.setAlpha(1f);
            bar.setProgress(0);
        }
        if (pctText != null) pctText.setText("0%");
        if (statusText != null) statusText.setText("Download failed");
        setButton("Retry", true);
    }

    // ---- ডাউনলোড ----

    private static File apkFile(Context c) {
        File dir = c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        return new File(dir, APK_NAME);
    }

    private static void startUpdate(Activity act, Info info) {
        Context app = act.getApplicationContext();
        if (downloading) return;

        if (Build.VERSION.SDK_INT >= 26 && !app.getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(app, "Please allow \"Install unknown apps\" for Family Book, then come back",
                    Toast.LENGTH_LONG).show();
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + app.getPackageName()));
                act.startActivity(i);
            } catch (Throwable ignore) {
            }
            return;
        }

        File f = apkFile(app);
        if (downloaded && f.exists()) {
            install(app);
            return;
        }
        if (f.exists()) f.delete();
        downloaded = false;

        registerReceiverOnce(app);
        try {
            DownloadManager dm = (DownloadManager) app.getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(info.url));
            r.setTitle("Family Book update");
            r.setDescription(info.name != null ? "Version " + info.name : "Downloading update");
            r.setMimeType(MIME_APK);
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            r.setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS, APK_NAME);
            downloadId = dm.enqueue(r);

            appCtx = app;
            downloading = true;
            celebrated = false;
            lastProgress = 0;
            showProgressUi();
            H.removeCallbacks(POLL);
            H.postDelayed(POLL, 300);
        } catch (Throwable e) {
            Toast.makeText(app, "Could not start download", Toast.LENGTH_SHORT).show();
        }
    }

    private static void registerReceiverOnce(Context app) {
        if (receiverRegistered) return;
        receiverRegistered = true;
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        BroadcastReceiver rec = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id != downloadId) return;
                DownloadManager dm = (DownloadManager) ctx.getSystemService(Context.DOWNLOAD_SERVICE);
                int status = -1;
                Cursor c = dm.query(new DownloadManager.Query().setFilterById(id));
                try {
                    if (c != null && c.moveToFirst()) {
                        status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    }
                } finally {
                    if (c != null) c.close();
                }
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    downloaded = true;
                    finishProgress();
                    install(ctx.getApplicationContext());
                } else {
                    downloaded = false;
                    failProgress();
                    Toast.makeText(ctx, "Download failed. Please try again.", Toast.LENGTH_LONG).show();
                }
            }
        };
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(rec, filter, Context.RECEIVER_EXPORTED);
        } else {
            app.registerReceiver(rec, filter);
        }
    }

    // ---- ইনস্টল ----

    private static void install(Context app) {
        try {
            File f = apkFile(app);
            // ডাউনলোড করা ফাইল সত্যিই এই অ্যাপের নতুন ভার্সন কিনা যাচাই
            PackageInfo pi = app.getPackageManager().getPackageArchiveInfo(f.getAbsolutePath(), 0);
            if (pi == null || !app.getPackageName().equals(pi.packageName)
                    || PackageInfoCompat.getLongVersionCode(pi) <= currentCode(app)) {
                downloaded = false;
                f.delete();
                failProgress();
                Toast.makeText(app, "Update file is invalid. Please try again.", Toast.LENGTH_LONG).show();
                return;
            }
            Uri uri = FileProvider.getUriForFile(app, app.getPackageName() + ".fileprovider", f);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, MIME_APK);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            app.startActivity(i);
        } catch (Throwable e) {
            Toast.makeText(app, "Could not open installer", Toast.LENGTH_LONG).show();
        }
    }
}
