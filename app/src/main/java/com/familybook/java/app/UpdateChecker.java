package com.familybook.app;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.content.FileProvider;
import androidx.core.content.pm.PackageInfoCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;

/**
 * অ্যাপের আপডেট চেক + ডাউনলোড + ইনস্টল। আপডেট UI এখন {@link UpdateActivity} (ডায়ালগ নয়)।
 *
 * Supabase টেবিল app_updates (GitHub Actions প্রতি build-এ নতুন সারি বসায়):
 *   version_code (number), version_name (text), apk_url (text), whats_new (text),
 *   min_version_code (number, ঐচ্ছিক), force (bool, ঐচ্ছিক)
 *
 * ফ্লো:
 *   1. নতুন ভার্সন পেলে অ্যাপ খোলার ~৩.৫ সেকেন্ড পর UpdateActivity খোলে।
 *   2. Update now -> DownloadManager দিয়ে ডাউনলোড, UpdateActivity-তে ০-১০০% প্রগ্রেস।
 *   3. ১০০% হলে ইনস্টলার খোলে। ইনস্টল শেষে অ্যাপ আবার খুললে "Congratulations" পেজ দেখায়।
 *
 * FamilyBookApp থেকে আপনা-আপনি কল হয়; অন্য Activity বদলাতে হয় না।
 */
public final class UpdateChecker {

    public interface Listener {
        /** 0..1000 */
        void onProgress(int p1000);

        /** ডাউনলোড শেষ, ব্যাকগ্রাউন্ডে ইনস্টল শুরু হয়েছে (নিচে ছোট spinner দেখাও) */
        void onDownloadDone();

        /** ইনস্টল সফল (প্রসেস মরে না গেলে) */
        void onInstalled();

        /** ইনস্টল বাতিল/ব্যর্থ: আবার "Install now" দেখাও */
        void onInstallFailed();

        void onFailed();
    }

    private static final String APK_NAME = "FamilyBook-update.apk";
    private static final String MIME_APK = "application/vnd.android.package-archive";
    private static final String PREFS = "update_prefs";
    private static final String ACTION_INSTALL_STATUS = "com.familybook.app.INSTALL_STATUS";

    /** অ্যাপ খোলার কত ms পর আপডেট পেজ আসবে */
    private static final long SHOW_DELAY_MS = 3500;
    private static final long SESSION_GAP_MS = 30000;

    /** এই স্ক্রিনগুলোতে কাজের মাঝে আপডেট পেজ ঢোকানো হবে না */
    private static final String[] BUSY = {
            "ShortCreateActivity", "ShortEditActivity", "ShortSettingsActivity",
            "MusicTrimActivity", "NewPostActivity", "CropActivity", "RegisterActivity"
    };

    private static class Info {
        long code;
        long minCode;
        String name;
        String url;
        String message;
        boolean force;
    }

    private static final Handler H = new Handler(Looper.getMainLooper());

    private static boolean listening;
    private static boolean later;
    private static boolean downloaded;
    private static boolean downloading;
    private static boolean installing;
    private static boolean installReceiverRegistered;
    private static boolean receiverRegistered;
    private static boolean successChecked;
    private static long downloadId = -1;
    private static int lastProgress;            // 0..1000
    private static Info pending;
    private static Context appCtx;
    private static Listener listener;
    private static long openAt;
    private static long lastPauseAt;
    private static WeakReference<Activity> top = new WeakReference<>(null);

    private static final Runnable POLL = new Runnable() {
        @Override
        public void run() {
            pollOnce();
        }
    };

    private static final Runnable SHOW = new Runnable() {
        @Override
        public void run() {
            tryShow();
        }
    };

    private UpdateChecker() {
    }

    // =====================================================================
    //  Activity lifecycle (FamilyBookApp থেকে কল হয়)
    // =====================================================================

    public static void onResume(Activity act) {
        top = new WeakReference<>(act);
        appCtx = act.getApplicationContext();
        if (act instanceof UpdateActivity) return;

        long now = SystemClock.elapsedRealtime();
        if (openAt == 0 || now - lastPauseAt > SESSION_GAP_MS) {
            // নতুন করে অ্যাপ খোলা হয়েছে: আবার ৩-৪ সেকেন্ড পর দেখানো হবে
            openAt = now;
            if (!downloading) later = false;
        }
        askNotifPermission(act);
        if (checkJustUpdated(act)) return;
        listen(appCtx);
        scheduleShow();
    }

    public static void onPause(Activity act) {
        lastPauseAt = SystemClock.elapsedRealtime();
        if (top.get() == act) top = new WeakReference<>(null);
    }

    public static void onStop(Activity act) {
        // আগে এখানে ডায়ালগ বন্ধ হতো; এখন আর ডায়ালগ নেই
    }

    // =====================================================================
    //  UpdateActivity-র জন্য API
    // =====================================================================

    public static void setListener(Listener l) {
        listener = l;
    }

    public static void clearListener(Listener l) {
        if (listener == l) listener = null;
    }

    public static boolean isDownloading() {
        return downloading;
    }

    public static boolean isInstalling() {
        return installing;
    }

    public static boolean isDownloaded() {
        return downloaded && appCtx != null && apkFile(appCtx).exists();
    }

    public static boolean isForce() {
        return pending != null && pending.force;
    }

    public static int progress1000() {
        return lastProgress;
    }

    /** "Later" চাপলে: এই অ্যাপ-সেশনে আর দেখাবে না (পরের বার অ্যাপ খুললে আবার আসবে) */
    public static void later() {
        later = true;
    }

    /** Whats New তালিকা: প্রতিটি লাইনের আগে ▪ বসিয়ে */
    public static String whatsNewText() {
        String raw = pending == null ? null : pending.message;
        StringBuilder sb = new StringBuilder();
        if (raw != null && !raw.trim().isEmpty()) {
            for (String line : raw.split("\\r?\\n")) {
                // শুরুর ✳️ • - * ইত্যাদি চিহ্ন বাদ দিয়ে শুধু লেখা রাখি
                String t = line.replaceFirst("^[^\\p{L}\\p{N}]+", "").trim();
                if (t.isEmpty()) continue;
                if (sb.length() > 0) sb.append('\n');
                sb.append("\u25AA ").append(t);
            }
        }
        if (sb.length() == 0) {
            sb.append("\u25AA Bugs fixed\n\u25AA Increased System Security\n\u25AA Superfast upload Speed.");
        }
        return sb.toString();
    }

    /**
     * Update now চাপলে। ডাউনলোড শুরু (বা আগের ডাউনলোড করা APK ইনস্টল) হলে true।
     * "Install unknown apps" অনুমতি না থাকলে সেটিংস খুলে false দেয়।
     */
    public static boolean startUpdate(Activity act) {
        Info info = pending;
        if (info == null) return false;
        Context app = act.getApplicationContext();
        appCtx = app;
        if (downloading) return true;

        if (Build.VERSION.SDK_INT >= 26 && !app.getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(app, "Please allow \"Install unknown apps\" for Family Book, then press Update now again",
                    Toast.LENGTH_LONG).show();
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + app.getPackageName()));
                act.startActivity(i);
            } catch (Throwable ignore) {
            }
            return false;
        }

        File f = apkFile(app);
        if (downloaded && f.exists()) {
            downloading = true;           // handleDownloaded() এর শর্ত
            handleDownloaded(app);
            return true;
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

            downloading = true;
            lastProgress = 0;
            H.removeCallbacks(POLL);
            H.postDelayed(POLL, 300);
            return true;
        } catch (Throwable e) {
            Toast.makeText(app, "Could not start download", Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    /** ডাউনলোড শেষ কিন্তু ইনস্টল বাতিল হয়েছিল: আবার ব্যাকগ্রাউন্ডে ইনস্টল শুরু। শুরু হলে true */
    public static boolean installNow(Activity act) {
        Context app = act.getApplicationContext();
        if (Build.VERSION.SDK_INT >= 26 && !app.getPackageManager().canRequestPackageInstalls()) {
            startUpdate(act);
            return false;
        }
        return install(app);
    }

    /** সফলতার পেজ দেখানো হলে ইনস্টলের চিহ্ন + APK মুছে দাও */
    public static void markSuccessShown(Context c) {
        try {
            Context app = c.getApplicationContext();
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("installing_code").apply();
            File f = apkFile(app);
            if (f.exists()) f.delete();
        } catch (Throwable ignore) {
        }
    }

    // =====================================================================
    //  আপডেট ইনস্টল হয়ে অ্যাপ আবার খুললে সফলতার পেজ
    // =====================================================================

    private static boolean checkJustUpdated(Activity act) {
        if (successChecked) return false;
        successChecked = true;
        try {
            Context app = act.getApplicationContext();
            SharedPreferences sp = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            long want = sp.getLong("installing_code", 0);
            if (want > 0 && currentCode(app) >= want) {
                sp.edit().remove("installing_code").apply();
                File f = apkFile(app);
                if (f.exists()) f.delete();
                Intent i = new Intent(act, UpdateActivity.class);
                i.putExtra(UpdateActivity.EXTRA_SUCCESS, true);
                act.startActivity(i);
                act.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
                return true;
            }
        } catch (Throwable ignore) {
        }
        return false;
    }

    // =====================================================================
    //  আপডেট পেজ দেখানো (অ্যাপ খোলার ৩-৪ সেকেন্ড পর)
    // =====================================================================

    private static void scheduleShow() {
        if (pending == null) return;
        H.removeCallbacks(SHOW);
        long wait = openAt + SHOW_DELAY_MS - SystemClock.elapsedRealtime();
        H.postDelayed(SHOW, Math.max(wait, 400));
    }

    private static void tryShow() {
        Activity a = top.get();
        if (pending == null || a == null || a.isFinishing()) return;
        if (a instanceof UpdateActivity) return;
        if (later && !pending.force) return;

        long wait = openAt + SHOW_DELAY_MS - SystemClock.elapsedRealtime();
        if (wait > 100) {
            H.postDelayed(SHOW, wait);
            return;
        }
        String n = a.getClass().getSimpleName();
        for (String b : BUSY) {
            if (b.equals(n)) {
                H.postDelayed(SHOW, 5000);   // কাজ শেষ হলে আবার চেষ্টা
                return;
            }
        }
        try {
            a.startActivity(new Intent(a, UpdateActivity.class));
            a.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        } catch (Throwable ignore) {
        }
    }

    // =====================================================================
    //  ভার্সন চেক
    // =====================================================================

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

    /** app_updates টেবিল প্রতি ১ মিনিটে এবং অ্যাপে ফেরার সময় (২০ সেকেন্ড ব্যবধানে) দেখে */
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
                "/rest/v1/app_updates?select=*&order=version_code.desc&limit=1",
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
                if (!downloading) downloaded = false;
            }
            pending = i;
            notifyUpdate(app, i);
            scheduleShow();
        } catch (Throwable ignore) {
        }
    }

    // =====================================================================
    //  নোটিফিকেশন "FamilyBook : New Update vX — Whats new!!"
    // =====================================================================

    private static final String CH_ID = "app_update";
    private static final int NOTIF_ID = 7001;
    private static boolean askedNotifPerm;

    private static void askNotifPermission(Activity act) {
        if (Build.VERSION.SDK_INT < 33 || askedNotifPerm) return;
        if (act.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            askedNotifPerm = true;
            act.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIF_ID);
        }
    }

    private static void notifyUpdate(Context app, Info i) {
        try {
            SharedPreferences sp = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
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

    // =====================================================================
    //  ডাউনলোড প্রগ্রেস
    // =====================================================================

    private static File apkFile(Context c) {
        File dir = c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        return new File(dir, APK_NAME);
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
            handleDownloaded(appCtx);
            return;
        }
        if (status == DownloadManager.STATUS_FAILED || status == -1) {
            handleFailed();
            return;
        }
        if (total > 0) {
            int p = (int) (got * 1000L / total);
            p = Math.min(p, 990);                  // ১০০% শুধু ডাউনলোড শেষ হলে
            if (p > lastProgress) lastProgress = p;
            if (listener != null) listener.onProgress(lastProgress);
        }
        H.postDelayed(POLL, 350);
    }

    private static void handleDownloaded(Context app) {
        if (!downloading) return;                 // দুইবার (poll + receiver) চললে একবারই
        H.removeCallbacks(POLL);
        downloading = false;
        downloaded = true;
        lastProgress = 1000;
        if (listener != null) listener.onProgress(1000);
        if (install(app) && listener != null) listener.onDownloadDone();
    }

    private static void handleFailed() {
        H.removeCallbacks(POLL);
        downloading = false;
        downloaded = false;
        lastProgress = 0;
        if (listener != null) listener.onFailed();
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
                    handleDownloaded(ctx.getApplicationContext());
                } else if (downloading) {
                    handleFailed();
                }
            }
        };
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(rec, filter, Context.RECEIVER_EXPORTED);
        } else {
            app.registerReceiver(rec, filter);
        }
    }

    // =====================================================================
    //  ইনস্টল
    // =====================================================================

    /**
     * PackageInstaller session দিয়ে ব্যাকগ্রাউন্ডে ইনস্টল শুরু। স্ক্রিনের উপর সিস্টেম ইনস্টলার পেজ আসে না
     * (Android 12+ এ আগের ইনস্টলার এই অ্যাপই হলে সম্পূর্ণ নীরব; নাহলে সিস্টেম শুধু নিশ্চিতকরণ চাইলে তখনই দেখায়)।
     * শুরু হলে true
     */
    private static boolean install(final Context app) {
        final File f = apkFile(app);
        try {
            // ডাউনলোড করা ফাইল সত্যিই এই অ্যাপের নতুন ভার্সন কিনা যাচাই
            PackageInfo pi = app.getPackageManager().getPackageArchiveInfo(f.getAbsolutePath(), 0);
            if (pi == null || !app.getPackageName().equals(pi.packageName)
                    || PackageInfoCompat.getLongVersionCode(pi) <= currentCode(app)) {
                downloaded = false;
                f.delete();
                handleFailed();
                return false;
            }
            // ইনস্টল শেষে অ্যাপ আবার খুললে "Congratulations" পেজ দেখানোর চিহ্ন
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putLong("installing_code", PackageInfoCompat.getLongVersionCode(pi)).apply();
        } catch (Throwable e) {
            Toast.makeText(app, "Could not start install", Toast.LENGTH_LONG).show();
            return false;
        }
        if (installing) return true;
        installing = true;
        registerInstallReceiverOnce(app);
        new Thread(new Runnable() {
            @Override
            public void run() {
                commitSession(app, f);
            }
        }, "fb-install").start();
        return true;
    }

    private static void commitSession(final Context app, File f) {
        PackageInstaller.Session session = null;
        try {
            PackageInstaller pin = app.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams p =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            p.setAppPackageName(app.getPackageName());
            if (Build.VERSION.SDK_INT >= 31) {
                p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
            }
            int id = pin.createSession(p);
            session = pin.openSession(id);

            InputStream in = new FileInputStream(f);
            OutputStream out = session.openWrite("base.apk", 0, f.length());
            try {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                session.fsync(out);
            } finally {
                try { in.close(); } catch (Throwable ignore) { }
                try { out.close(); } catch (Throwable ignore) { }
            }

            Intent cb = new Intent(ACTION_INSTALL_STATUS).setPackage(app.getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent pi = PendingIntent.getBroadcast(app, id, cb, flags);
            session.commit(pi.getIntentSender());
            session.close();
        } catch (Throwable e) {
            try {
                if (session != null) session.abandon();
            } catch (Throwable ignore) {
            }
            H.post(new Runnable() {
                @Override
                public void run() {
                    installFailed();
                }
            });
        }
    }

    private static void installFailed() {
        installing = false;
        if (listener != null) listener.onInstallFailed();
    }

    private static void registerInstallReceiverOnce(Context app) {
        if (installReceiverRegistered) return;
        installReceiverRegistered = true;
        IntentFilter filter = new IntentFilter(ACTION_INSTALL_STATUS);
        BroadcastReceiver rec = new BroadcastReceiver() {
            @SuppressWarnings("deprecation")
            @Override
            public void onReceive(Context ctx, Intent intent) {
                int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1);
                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    // সিস্টেম শুধু নিশ্চিতকরণ চাইছে (প্রথমবার/কিছু ফোনে)। spinner চলতে থাকবে
                    try {
                        Intent confirm = (Intent) intent.getParcelableExtra(Intent.EXTRA_INTENT);
                        if (confirm != null) {
                            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            ctx.getApplicationContext().startActivity(confirm);
                        }
                    } catch (Throwable e) {
                        installFailed();
                    }
                } else if (status == PackageInstaller.STATUS_SUCCESS) {
                    installing = false;
                    markSuccessShown(ctx);
                    if (listener != null) listener.onInstalled();
                } else {
                    installFailed();
                }
            }
        };
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(rec, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            app.registerReceiver(rec, filter);
        }
    }
}
