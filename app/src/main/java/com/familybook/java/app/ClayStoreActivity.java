package com.familybook.app;

import android.app.DownloadManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** ClayStore: HTML স্টোর WebView-তে। ডাউনলোড/ইনস্টল/আপডেট সবই এখানে (পুরোনো আপডেট পপআপ আর নেই) */
public class ClayStoreActivity extends AppCompatActivity {

    public static final String EXTRA_APP = "app";
    public static final String SELF_REPO = "familybook-apk";
    /** ClayStore HTML অ্যাপের assets/claystore/ ফোল্ডারে (index.html, app.js, style.css, clay_logo.png) */
    private static final String STORE_URL = "file:///android_asset/claystore/index.html";
    private static final String ACTION_STATUS = "com.familybook.app.CLAY_INSTALL_STATUS";

    private WebView web;
    private final Handler h = new Handler(Looper.getMainLooper());
    private String name = "", avatar = "", avatarSmall = "", email = "", startRepo;
    private String curPkg, curRepo;
    private boolean updating, started;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        String uid = SupabaseRest.uid();
        if (!SupabaseRest.isLoggedIn() || uid == null) { finish(); return; }
        startRepo = getIntent().getStringExtra(EXTRA_APP);
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        web.setBackgroundColor(0xFF121212);
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(uid), "ClayApp");

        // নাম, ছবি, ইমেইল জোগাড় করে তারপর পেজ খোলে
        PostOps.loadMyName(uid, n -> { name = n == null ? "" : n; loadAvatar(uid, this::loadEmail); });

        registerReceiver();
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                web.evaluateJavascript("window.clayBack?clayBack():false", v -> {
                    if (!"true".equals(v)) finish();
                });
            }
        });
    }

    /**
     * প্রোফাইল ছবি: profiles.avatar_key দিয়ে AvatarLoader-এর মতো R2 থেকে আনে (Media.loadBitmap),
     * তারপর ছোট JPEG data-URI বানিয়ে ওয়েব পেজে দেয়। ছবি না থাকলে বা ৪ সেকেন্ডে না এলে ছবি ছাড়াই পেজ খোলে।
     * avatar = বড় (প্রোফাইল পেজ), avatarSmall = ছোট (রিভিউতে সেভ হয়)।
     */
    private void loadAvatar(String uid, Runnable next) {
        final boolean[] done = {false};
        final Runnable fin = () -> { if (!done[0]) { done[0] = true; next.run(); } };
        h.postDelayed(fin, 4000);
        SupabaseRest.call("GET", "/rest/v1/profiles?id=eq." + uid + "&select=avatar_key", null, null, true, r -> {
            String key = "";
            try {
                if (r.ok()) {
                    JSONArray a = new JSONArray(r.body);
                    if (a.length() > 0 && !a.getJSONObject(0).isNull("avatar_key"))
                        key = a.getJSONObject(0).optString("avatar_key", "").trim();
                }
            } catch (Exception ignored) { }
            if (key.isEmpty()) { fin.run(); return; }
            Bitmap cached = AvatarLoader.cached(key);
            if (cached != null) { encodeAvatar(cached); fin.run(); return; }
            Media.loadBitmap(key, bm -> { encodeAvatar(bm); fin.run(); }, (v, err) -> fin.run());
        });
    }

    private void encodeAvatar(Bitmap bm) {
        try {
            avatar = dataUri(bm, 256, 80);
            avatarSmall = dataUri(bm, 72, 70);
        } catch (Throwable ignored) { }
    }

    private static String dataUri(Bitmap src, int px, int quality) {
        int side = Math.min(src.getWidth(), src.getHeight());
        Bitmap sq = Bitmap.createBitmap(src, (src.getWidth() - side) / 2, (src.getHeight() - side) / 2, side, side);
        Bitmap sm = Bitmap.createScaledBitmap(sq, px, px, true);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        sm.compress(Bitmap.CompressFormat.JPEG, quality, bo);
        return "data:image/jpeg;base64," + Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
    }

    private void loadEmail() {
        SupabaseRest.call("GET", "/auth/v1/user", null, null, true, r -> {
            try { if (r.ok()) email = new JSONObject(r.body).optString("email", ""); } catch (Exception ignored) { }
            startPage();
        });
    }

    private void startPage() {
        if (started || isFinishing()) return;
        started = true;
        web.loadUrl(STORE_URL + (startRepo != null ? "#" + startRepo : ""));
    }

    @Override protected void onResume() {
        super.onResume();
        if (started) web.evaluateJavascript("onClayState('','done')", null);   // ইনস্টল/আনইনস্টল শেষে তালিকা নতুন করে
        // ইনস্টলার ডায়ালগ থেকে ফিরে এলে (ক্যানসেল বা শেষ) "Updating..." অবস্থা আটকে না থাকার জন্য
        if (awaitingInstaller) h.postDelayed(() -> { if (awaitingInstaller && busy) endBusy(curPkg, null); }, 1500);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        h.removeCallbacksAndMessages(null);
        try { unregisterReceiver(rec); } catch (Throwable ignored) { }
        web.destroy();
    }

    private void js(String pkg, String st) {
        h.post(() -> web.evaluateJavascript("onClayState('" + pkg + "','" + st + "')", null));
    }

    // ---------------- ব্রিজ ----------------
    private class Bridge {
        final String uid;
        Bridge(String uid) { this.uid = uid; }

        @JavascriptInterface public String user() {
            try {
                return new JSONObject().put("uid", uid).put("email", email)
                        .put("name", name.isEmpty() ? "Family Member" : name).put("avatar", avatar).put("avatarSmall", avatarSmall).toString();
            } catch (Exception e) { return "{}"; }
        }
        /** লগইন করা ইউজারের access token (SupabaseRest.accessToken) */
        @JavascriptInterface public String token() { return SupabaseRest.accessToken(); }

        @JavascriptInterface public String installedVersion(String pkg) {
            try { String v = getPackageManager().getPackageInfo(pkg, 0).versionName; return v == null ? "" : v; }
            catch (Throwable e) { return ""; }
        }
        /** FamilyBook প্রজেক্টের What's New টেক্সট ফাইল (বিল্ডের সময় অ্যাপের ভেতরেই থাকে, GitHub লাগে না)।
         *  একাধিক সম্ভাব্য জায়গায় খোঁজে; কোথাও না পেলে ফাঁকা (তখন কার্ড দেখায় না) */
        @JavascriptInterface public String whatsNew() {
            String[] assetNames = {"whatsnew.txt", "whats_new.txt", "WhatsNew.txt", "whatsnew", "claystore/whatsnew.txt"};
            for (String n : assetNames) {
                try (InputStream in = getAssets().open(n)) { return readAll(in); } catch (Throwable ignored) { }
            }
            String[] rawNames = {"whatsnew", "whats_new"};   // res/raw/whatsnew.txt হলে
            for (String n : rawNames) {
                try {
                    int id = getResources().getIdentifier(n, "raw", getPackageName());
                    if (id != 0) try (InputStream in = getResources().openRawResource(id)) { return readAll(in); }
                } catch (Throwable ignored) { }
            }
            return "";
        }
        private String readAll(InputStream in) throws Exception {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096]; int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        }
        /** ইনস্টল থাকা অ্যাপের আইকন (ছোট PNG data-URI); GitHub-এ icon.png না থাকলেও লোগো দেখায় */
        @JavascriptInterface public String installedIcon(String pkg) {
            try {
                android.graphics.drawable.Drawable d = getPackageManager().getApplicationIcon(pkg);
                int px = 96;
                Bitmap bm = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
                android.graphics.Canvas c = new android.graphics.Canvas(bm);
                d.setBounds(0, 0, px, px);
                d.draw(c);
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                bm.compress(Bitmap.CompressFormat.PNG, 100, bo);
                return "data:image/png;base64," + Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
            } catch (Throwable e) { return ""; }
        }
        /** অ্যাপ সর্বশেষ কখন ইনস্টল/আপডেট হয়েছে (মিলিসেকেন্ড); ইনস্টল না থাকলে 0 */
        @JavascriptInterface public String installedTime(String pkg) {
            try { return String.valueOf(getPackageManager().getPackageInfo(pkg, 0).lastUpdateTime); }
            catch (Throwable e) { return "0"; }
        }
        @JavascriptInterface public void open(String pkg) {
            h.post(() -> {
                Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
                if (i != null) startActivity(i);
            });
        }
        @JavascriptInterface public void uninstall(String pkg) {
            h.post(() -> startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg))));
        }
        @JavascriptInterface public void install(String url, String pkg, String repo) {
            h.post(() -> startDownload(url, pkg, repo));
        }
        @JavascriptInterface public void logout() {
            h.post(() -> {
                SupabaseRest.clearSession();
                Intent i = new Intent(ClayStoreActivity.this, LoginActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(i);
                finish();
            });
        }
        @JavascriptInterface public void close() { h.post(ClayStoreActivity.this::finish); }
    }

    // ---------------- ডাউনলোড ----------------
    private static final long STALL_MS = 60_000;            // ৬০ সেকেন্ড একটাও বাইট না এলে ব্যর্থ
    private static final long INSTALL_WATCHDOG_MS = 90_000; // কমিটের পর ৯০ সেকেন্ডে কিছু না হলে ব্যর্থ
    private boolean busy, awaitingInstaller;
    private long lastBytes = -1, lastMoveAt;

    private final Runnable installWatchdog = () -> {
        if (busy && !awaitingInstaller) endBusy(curPkg, "Install didn't start. Please try again.");
    };

    /** ওয়েব পেজে প্রোগ্রেস পাঠায়: pct 0-100 (অজানা হলে -1), phase = download | install */
    private void prog(String pkg, int pct, String phase) {
        h.post(() -> web.evaluateJavascript("onClayProgress('" + pkg + "'," + pct + ",'" + phase + "')", null));
    }

    /** ডাউনলোড/ইনস্টল শেষ বা ব্যর্থ: স্টেট পরিষ্কার করে পেজকে জানায় (main thread-এ ডাকতে হবে) */
    private void endBusy(String pkg, String msg) {
        busy = false; awaitingInstaller = false;
        h.removeCallbacks(installWatchdog);
        if (msg != null) Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        js(pkg == null ? "" : pkg, "done");
    }

    private void startDownload(String url, String pkg, String repo) {
        if (busy) return;
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(this, "Allow \"Install unknown apps\" for Family Book, then try again", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            js(pkg, "done");
            return;
        }
        curPkg = pkg; curRepo = repo; busy = true; awaitingInstaller = false;
        updating = !new Bridge("").installedVersion(pkg).isEmpty();
        js(pkg, updating ? "updating" : "installing");
        prog(pkg, 0, "download");
        File f = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "clay-" + pkg + ".apk");
        if (f.exists()) f.delete();
        DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
        r.setMimeType("application/vnd.android.package-archive");
        // লুকানো ডাউনলোডের জন্য DOWNLOAD_WITHOUT_NOTIFICATION অনুমতি লাগে (Manifest-এ দেওয়া আছে); না থাকলে সাধারণ নোটিফিকেশনসহ চলবে
        r.setNotificationVisibility(checkSelfPermission("android.permission.DOWNLOAD_WITHOUT_NOTIFICATION")
                == android.content.pm.PackageManager.PERMISSION_GRANTED
                ? DownloadManager.Request.VISIBILITY_HIDDEN : DownloadManager.Request.VISIBILITY_VISIBLE);
        r.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, f.getName());
        long id;
        try { id = dm.enqueue(r); }
        catch (Throwable e) { endBusy(pkg, "Download failed: " + e.getMessage()); return; }
        lastBytes = -1; lastMoveAt = SystemClock.uptimeMillis();
        poll(dm, id, f, pkg);
    }

    private void poll(DownloadManager dm, long id, File f, String pkg) {
        if (!busy) return;
        int st = -1, reason = 0; long got = 0, total = -1;
        try (Cursor c = dm.query(new DownloadManager.Query().setFilterById(id))) {
            if (c != null && c.moveToFirst()) {
                st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                got = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
            }
        } catch (Throwable ignored) { }
        if (st == DownloadManager.STATUS_SUCCESSFUL) { prog(pkg, 100, "install"); commit(f, pkg); return; }
        if (st == DownloadManager.STATUS_FAILED || st == -1) {
            try { dm.remove(id); } catch (Throwable ignored) { }
            endBusy(pkg, "Download failed (code " + reason + ")");   // কোড: ৪০৮/৪০৪ ইত্যাদি = HTTP স্ট্যাটাস, ১০০০+ = ডাউনলোড ম্যানেজারের এরর
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (got != lastBytes) { lastBytes = got; lastMoveAt = now; }
        if (now - lastMoveAt > STALL_MS) {   // PENDING/PAUSED/WAITING অবস্থায় আটকে থাকলে
            try { dm.remove(id); } catch (Throwable ignored) { }
            endBusy(pkg, "Download stalled. Check your internet and try again.");
            return;
        }
        prog(pkg, total > 0 ? (int) (got * 100 / total) : -1, "download");
        h.postDelayed(() -> poll(dm, id, f, pkg), 400);
    }

    // ---------------- ইনস্টল (PackageInstaller) ----------------
    private void commit(File f, String pkg) {
        new Thread(() -> {
            try {
                PackageInfo pi = getPackageManager().getPackageArchiveInfo(f.getAbsolutePath(), 0);
                if (pi == null || !pkg.equals(pi.packageName)) throw new Exception("bad apk");
                PackageInstaller pin = getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                p.setAppPackageName(pkg);
                if (Build.VERSION.SDK_INT >= 31) p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
                int id = pin.createSession(p);
                try (PackageInstaller.Session s = pin.openSession(id);
                     InputStream in = new FileInputStream(f);
                     OutputStream out = s.openWrite("base.apk", 0, f.length())) {
                    byte[] buf = new byte[65536]; int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    s.fsync(out);
                    Intent cb = new Intent(ACTION_STATUS).setPackage(getPackageName());
                    int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                    s.commit(PendingIntent.getBroadcast(this, id, cb, fl).getIntentSender());
                }
                h.post(() -> h.postDelayed(installWatchdog, INSTALL_WATCHDOG_MS));
            } catch (Throwable e) {
                h.post(() -> endBusy(pkg, "Install failed"));
            }
        }, "clay-install").start();
    }

    private final BroadcastReceiver rec = new BroadcastReceiver() {
        @SuppressWarnings("deprecation")
        @Override public void onReceive(Context c, Intent i) {
            int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1);
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                awaitingInstaller = true;
                h.removeCallbacks(installWatchdog);
                Intent ask = (Intent) i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (ask != null) { ask.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(ask); }
            } else if (st == PackageInstaller.STATUS_SUCCESS) {
                recordInstall();
                endBusy(curPkg, null);
            } else {
                String m = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                boolean aborted = st == PackageInstaller.STATUS_FAILURE_ABORTED;   // ইউজার নিজে ক্যানসেল করলে মেসেজ নয়
                endBusy(curPkg, aborted ? null : "Install failed" + (m != null && !m.isEmpty() ? ": " + m : ""));
            }
        }
    };

    private void registerReceiver() {
        IntentFilter f = new IntentFilter(ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(rec, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(rec, f);
    }

    /** ফোনে ইনস্টল সফল হলে Download সংখ্যার হিসাব (প্রতি ইউজার-অ্যাপে একবার) */
    private void recordInstall() {
        if (curRepo == null) return;
        try {
            String body = new JSONObject().put("app", curRepo).put("user_id", SupabaseRest.uid()).toString();
            SupabaseRest.call("POST", "/rest/v1/clay_installs?on_conflict=app,user_id", body, null, true, r -> { });
        } catch (Exception ignored) { }
    }
}
