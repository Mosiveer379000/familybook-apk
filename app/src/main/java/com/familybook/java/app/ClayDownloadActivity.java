package com.familybook.app;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Play Store-এর মতো ডাউনলোড পেজ: আইকনের চারপাশে প্রগ্রেস রিং, Cancel বোতাম, "Auto-open when ready" সুইচ।
 * সাধারণ ইউজার "Update from Clay Store" চাপলে শুধু এই পেজটাই খোলে; অ্যাডমিনের স্টোরেও Install চাপলে এটাই আসে।
 */
public class ClayDownloadActivity extends AppCompatActivity {

    public static final String EXTRA_URL = "url", EXTRA_NAME = "name", EXTRA_VER = "ver",
            EXTRA_ICON = "icon", EXTRA_SELF = "self";
    private static final String MIME_APK = "application/vnd.android.package-archive";
    private static final int DOWNLOADING = 0, READY = 1, INSTALLED = 2, FAILED = 3;

    private final Handler h = new Handler(Looper.getMainLooper());
    private DownloadManager dm;
    private SharedPreferences prefs;
    private String url, name, ver, pkg;
    private File file;
    private long id = -1;
    private int state = DOWNLOADING;
    private boolean needPerm, receiverOn, resumed, pendingOpen;

    private ImageView icon;
    private RingProgressView ring;
    private TextView status, action;
    private Switch autoOpen;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        url = getIntent().getStringExtra(EXTRA_URL);
        name = getIntent().getStringExtra(EXTRA_NAME);
        ver = getIntent().getStringExtra(EXTRA_VER);
        if (url == null || !url.startsWith("https://")) {
            Toast.makeText(this, "Download link missing", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        if (name == null || name.isEmpty()) name = "App";
        dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        prefs = getSharedPreferences("clay_prefs", MODE_PRIVATE);
        buildUi();
        start();
    }

    // ---------------- UI ----------------

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF121212);
        root.setFitsSystemWindows(true);
        root.setPadding(dp(16), dp(8), dp(16), dp(16));

        TextView back = text("\u2190", 28, Color.WHITE);
        back.setPadding(dp(8), dp(8), dp(8), dp(8));
        back.setOnClickListener(v -> finish());
        root.addView(back, new LinearLayout.LayoutParams(-2, -2));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(0, dp(24), 0, dp(16));

        FrameLayout box = new FrameLayout(this);
        icon = new ImageView(this);
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        icon.setClipToOutline(true);
        icon.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(14));
            }
        });
        box.addView(icon, new FrameLayout.LayoutParams(dp(60), dp(60), Gravity.CENTER));
        ring = new RingProgressView(this);
        ring.setShowText(false);
        ring.setSizing(2.5f, 8f);
        ring.setProgress(0);
        box.addView(ring, new FrameLayout.LayoutParams(-1, -1));
        head.addView(box, new LinearLayout.LayoutParams(dp(82), dp(82)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -2, 1f);
        cp.setMarginStart(dp(14));
        cp.setMarginEnd(dp(8));
        head.addView(col, cp);
        TextView nm = text(name, 22, Color.WHITE);
        nm.setSingleLine(true);
        nm.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(nm);
        col.addView(text("Clay Store" + (ver == null || ver.isEmpty() ? "" : " \u2022 " + ver), 14, 0xFFA8A8A8));

        action = text("Cancel", 16, 0xFF8AB4F8);
        action.setGravity(Gravity.CENTER);
        action.setPadding(dp(26), dp(12), dp(26), dp(12));
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(Color.TRANSPARENT);
        pill.setStroke(dp(1), 0xFF5F6368);
        pill.setCornerRadius(dp(24));
        action.setBackground(pill);
        action.setOnClickListener(v -> onAction());
        head.addView(action);
        root.addView(head);

        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        GradientDrawable cbg = new GradientDrawable();
        cbg.setColor(0xFF1E1E1E);
        cbg.setCornerRadius(dp(16));
        card.setBackground(cbg);
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.addView(text("Auto-open when ready", 17, Color.WHITE));
        t.addView(text("Jump into the app after it installs", 14, 0xFFA8A8A8));
        card.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        autoOpen = new Switch(this);
        autoOpen.setChecked(prefs.getBoolean("auto_open", false));
        autoOpen.setOnCheckedChangeListener((v, on) -> prefs.edit().putBoolean("auto_open", on).apply());
        card.addView(autoOpen);
        root.addView(card, new LinearLayout.LayoutParams(-1, -2));

        status = text("", 15, 0xFFA8A8A8);
        status.setPadding(dp(8), dp(20), dp(8), 0);
        root.addView(status);

        setContentView(root);
        loadIcon();
    }

    private void loadIcon() {
        if (getIntent().getBooleanExtra(EXTRA_SELF, false)) {
            icon.setImageDrawable(getPackageManager().getApplicationIcon(getApplicationInfo()));
            return;
        }
        icon.setImageResource(android.R.drawable.sym_def_app_icon);
        final String u = getIntent().getStringExtra(EXTRA_ICON);
        if (u == null || !u.startsWith("https://raw.githubusercontent.com/")) return;
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(10000);
                final Bitmap bm = BitmapFactory.decodeStream(c.getInputStream());
                if (bm != null) h.post(() -> icon.setImageBitmap(bm));
            } catch (Exception ignored) { }
        }).start();
    }

    private void setState(int s, String msg) {
        state = s;
        status.setText(msg);
        action.setText(s == DOWNLOADING ? "Cancel" : s == READY ? "Install" : s == INSTALLED ? "Open" : "Retry");
    }

    // ---------------- ডাউনলোড ----------------

    private void start() {
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            needPerm = true;
            setState(FAILED, "Allow \"Install unknown apps\", then come back");
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName())));
            } catch (Throwable ignored) { }
            return;
        }
        needPerm = false;
        try {
            String fn = "clay-" + System.currentTimeMillis() + ".apk";
            file = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fn);
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            r.setMimeType(MIME_APK);
            r.setTitle(name);
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE);
            r.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, fn);
            id = dm.enqueue(r);
            setState(DOWNLOADING, "Downloading\u2026 0%");
            h.postDelayed(poll, 300);
        } catch (Throwable e) {
            setState(FAILED, "Could not start download");
        }
    }

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (state != DOWNLOADING) return;
            int st = -1;
            long got = 0, total = -1;
            Cursor c = null;
            try {
                c = dm.query(new DownloadManager.Query().setFilterById(id));
                if (c != null && c.moveToFirst()) {
                    st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    got = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                    total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                }
            } catch (Throwable ignored) {
            } finally {
                if (c != null) c.close();
            }
            if (st == DownloadManager.STATUS_SUCCESSFUL) { downloaded(); return; }
            if (st == DownloadManager.STATUS_FAILED || st == -1) { setState(FAILED, "Download failed"); return; }
            if (total > 0) {
                int p = (int) (got * 100L / total);
                ring.setProgress(p);
                status.setText("Downloading\u2026 " + p + "%");
            }
            h.postDelayed(this, 350);
        }
    };

    private void downloaded() {
        ring.setProgress(100);
        try {
            PackageInfo pi = getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(), 0);
            if (pi != null) pkg = pi.packageName;
        } catch (Throwable ignored) { }
        registerPkgReceiver();
        setState(READY, "Download complete. Tap Install if the installer did not open");
        install();
    }

    private void install() {
        Uri u = dm.getUriForDownloadedFile(id);
        if (u == null) { setState(FAILED, "Download failed"); return; }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(u, MIME_APK)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch (Throwable e) {
            Toast.makeText(this, "Could not open installer", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------- বোতাম ও ইনস্টল-পরবর্তী ----------------

    private void onAction() {
        if (state == DOWNLOADING) {
            h.removeCallbacks(poll);
            try { dm.remove(id); } catch (Throwable ignored) { }
            finish();
        } else if (state == READY) {
            install();
        } else if (state == INSTALLED) {
            openApp();
        } else {
            ring.setProgress(0);
            start();
        }
    }

    private void openApp() {
        Intent li = pkg == null ? null : getPackageManager().getLaunchIntentForPackage(pkg);
        if (li != null) startActivity(li);
    }

    private final BroadcastReceiver pr = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            Uri d = i.getData();
            if (d == null || pkg == null || !pkg.equals(d.getSchemeSpecificPart())) return;
            setState(INSTALLED, "Installed");
            if (autoOpen.isChecked()) {
                if (resumed) openApp(); else pendingOpen = true;   // ইনস্টলার সামনে থাকলে ফিরে এলে খুলবে
            }
        }
    };

    private void registerPkgReceiver() {
        if (receiverOn) return;
        receiverOn = true;
        IntentFilter f = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addDataScheme("package");
        ContextCompat.registerReceiver(this, pr, f, ContextCompat.RECEIVER_EXPORTED);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (needPerm && Build.VERSION.SDK_INT >= 26 && getPackageManager().canRequestPackageInstalls()) start();
        if (pendingOpen) {
            pendingOpen = false;
            openApp();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        h.removeCallbacksAndMessages(null);
        if (receiverOn) unregisterReceiver(pr);
    }
}
