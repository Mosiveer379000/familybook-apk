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
    private void startDownload(String url, String pkg, String repo) {
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(this, "Allow \"Install unknown apps\" for Family Book, then try again", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            js(pkg, "done");
            return;
        }
        curPkg = pkg; curRepo = repo;
        updating = !new Bridge("").installedVersion(pkg).isEmpty();
        js(pkg, updating ? "updating" : "installing");
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
        catch (Throwable e) {
            Toast.makeText(this, "Download failed", Toast.LENGTH_SHORT).show();
            js(pkg, "done");
            return;
        }
        poll(dm, id, f, pkg);
    }

    private void poll(DownloadManager dm, long id, File f, String pkg) {
        int st = -1;
        try (Cursor c = dm.query(new DownloadManager.Query().setFilterById(id))) {
            if (c != null && c.moveToFirst()) st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
        }
        if (st == DownloadManager.STATUS_SUCCESSFUL) { commit(f, pkg); return; }
        if (st == DownloadManager.STATUS_FAILED || st == -1) {
            Toast.makeText(this, "Download failed", Toast.LENGTH_SHORT).show();
            js(pkg, "done");
            return;
        }
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
            } catch (Throwable e) {
                js(pkg, "done");
                h.post(() -> Toast.makeText(this, "Install failed", Toast.LENGTH_SHORT).show());
            }
        }, "clay-install").start();
    }

    private final BroadcastReceiver rec = new BroadcastReceiver() {
        @SuppressWarnings("deprecation")
        @Override public void onReceive(Context c, Intent i) {
            int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1);
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent ask = (Intent) i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (ask != null) { ask.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(ask); }
            } else if (st == PackageInstaller.STATUS_SUCCESS) {
                recordInstall();
                js(curPkg == null ? "" : curPkg, "done");
            } else {
                js(curPkg == null ? "" : curPkg, "done");
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
