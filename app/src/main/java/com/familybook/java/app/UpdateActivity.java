package com.familybook.app;

import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

/**
 * আপডেট পেজ। UpdateChecker নতুন ভার্সন পেলে অ্যাপ খোলার ৩-৪ সেকেন্ড পর এটা খোলে।
 *
 * State 1 : New Update + Whats New + Later / Update now
 * State 2 : Updating... + নীল প্রগ্রেস বার (আসল ডাউনলোডের ০-১০০%)
 * State 3 : Congratulations (আপডেট ইনস্টল হয়ে অ্যাপ আবার খুললে) + Go to feed (৫→১ কাউন্টডাউন, শেষে অটো হোম)
 */
public class UpdateActivity extends AppCompatActivity implements UpdateChecker.Listener {

    public static final String EXTRA_SUCCESS = "update_success";
    private static final int GO_FEED_SECONDS = 5;

    private View contentUpdate, contentSuccess, layoutUpdateHeader, layoutProgressSection, layoutPlayStore;
    private View layoutActionButtons, layoutInstalling;
    private Button btnLater, btnUpdateNow, btnGoToFeed;
    private ProgressBar progressBarUpdate;
    private TextView tvProgressPercentage, tvUpdatingStatus, tvPlayStoreText, tvCountdown;

    private final Handler h = new Handler(Looper.getMainLooper());
    private ValueAnimator anim;
    private int shownPct = 0;
    private boolean installMode;      // বাটনটা এখন "Install now"
    private boolean installLaunched;  // ইনস্টলার খোলা হয়েছে
    private boolean successMode;
    private boolean going;
    private int countdown;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_update);

        contentUpdate = findViewById(R.id.contentUpdate);
        contentSuccess = findViewById(R.id.contentSuccess);
        layoutUpdateHeader = findViewById(R.id.layoutUpdateHeader);
        layoutProgressSection = findViewById(R.id.layoutProgressSection);
        layoutActionButtons = findViewById(R.id.layoutActionButtons);
        layoutInstalling = findViewById(R.id.layoutInstalling);
        layoutPlayStore = findViewById(R.id.layoutPlayStore);
        btnLater = findViewById(R.id.btnLater);
        btnUpdateNow = findViewById(R.id.btnUpdateNow);
        btnGoToFeed = findViewById(R.id.btnGoToFeed);
        progressBarUpdate = findViewById(R.id.progressBarUpdate);
        tvProgressPercentage = findViewById(R.id.tvProgressPercentage);
        tvUpdatingStatus = findViewById(R.id.tvUpdatingStatus);
        tvPlayStoreText = findViewById(R.id.tvPlayStoreText);
        tvCountdown = findViewById(R.id.tvCountdown);

        applyFonts();
        setupCurrentVersion();
        setupPlayStoreText();
        setupBodyText();
        ((TextView) findViewById(R.id.tvWhatsNew)).setText(UpdateChecker.whatsNewText());

        // Update from playstore (এখন ডেমো)
        layoutPlayStore.setOnClickListener(v ->
                Toast.makeText(this, "Play Store update is coming soon", Toast.LENGTH_SHORT).show());

        btnLater.setOnClickListener(v -> {
            UpdateChecker.later();
            closePage();
        });
        btnUpdateNow.setOnClickListener(v -> onUpdateNowClicked());
        btnGoToFeed.setOnClickListener(v -> goHome());

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (successMode) {
                    goHome();
                } else if (UpdateChecker.isForce()) {
                    // বাধ্যতামূলক আপডেটে পেছনে যাওয়া বন্ধ
                } else {
                    UpdateChecker.later();
                    closePage();
                }
            }
        });

        boolean success = getIntent() != null && getIntent().getBooleanExtra(EXTRA_SUCCESS, false);
        if (success) {
            showSuccess();
        } else if (UpdateChecker.isInstalling()) {
            installLaunched = true;
            showProgress();
            setPctInstant(100);
            showInstalling();
        } else if (UpdateChecker.isDownloading()) {
            showProgress();
            setPctInstant(UpdateChecker.progress1000() / 10);
        } else if (UpdateChecker.isDownloaded()) {
            installLaunched = true;
            showProgress();
            setPctInstant(100);
            showInstallButton();
        } else {
            btnLater.setVisibility(UpdateChecker.isForce() ? View.GONE : View.VISIBLE);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        UpdateChecker.setListener(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        UpdateChecker.clearListener(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // ইনস্টলার থেকে ফিরে এলে (বাতিল করলে) আবার "Install now" দেখাও
        if (installLaunched && !successMode && !installMode && !UpdateChecker.isInstalling()) showInstallButton();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        h.removeCallbacksAndMessages(null);
        if (anim != null) anim.cancel();
    }

    // ---------------- Listener (UpdateChecker থেকে) ----------------

    @Override
    public void onProgress(int p1000) {
        animateTo(p1000 / 10);
    }

    @Override
    public void onDownloadDone() {
        installLaunched = true;
        animateTo(100);
        showInstalling();   // নিচে ছোট spinner, সিস্টেম ইনস্টলার স্ক্রিনে আসে না
    }

    @Override
    public void onInstalled() {
        if (!successMode) showSuccess();
    }

    @Override
    public void onInstallFailed() {
        if (successMode) return;
        showInstallButton();
    }

    @Override
    public void onFailed() {
        if (anim != null) anim.cancel();
        installLaunched = false;
        installMode = false;
        shownPct = 0;
        layoutInstalling.setVisibility(View.GONE);
        layoutProgressSection.setVisibility(View.GONE);
        layoutPlayStore.setVisibility(View.VISIBLE);
        setButtonsTop(80);
        layoutActionButtons.setVisibility(View.VISIBLE);
        btnLater.setVisibility(UpdateChecker.isForce() ? View.GONE : View.VISIBLE);
        btnUpdateNow.setText("Retry");
        Toast.makeText(this, "Download failed. Please try again.", Toast.LENGTH_LONG).show();
    }

    // ---------------- Clicks ----------------

    private void onUpdateNowClicked() {
        if (installMode) {
            installMode = false;
            layoutActionButtons.setVisibility(View.GONE);
            showInstalling();
            if (!UpdateChecker.installNow(this)) showInstallButton();
            return;
        }
        if (UpdateChecker.startUpdate(this)) {
            showProgress();
        }
    }

    // ---------------- States ----------------

    private void showProgress() {
        layoutActionButtons.setVisibility(View.GONE);
        layoutPlayStore.setVisibility(View.GONE);
        layoutProgressSection.setVisibility(View.VISIBLE);
        if (!installLaunched) tvUpdatingStatus.setText("Updating...");
        // নীল কার্ভের গায়ে না লেগে সাদা অংশের ভেতরেই থাকবে
        layoutProgressSection.post(this::fitProgressInsideWhite);
    }

    /** নিচের বাম কোণে ছোট "Installing..." spinner (ব্যাকগ্রাউন্ডে ইনস্টল চলছে) */
    private void showInstalling() {
        layoutActionButtons.setVisibility(View.GONE);
        layoutInstalling.setVisibility(View.VISIBLE);
    }

    private void showInstallButton() {
        installMode = true;
        layoutInstalling.setVisibility(View.GONE);
        tvUpdatingStatus.setText("Download complete");
        btnLater.setVisibility(View.GONE);
        btnUpdateNow.setText("Install now");
        setButtonsTop(16);
        layoutActionButtons.setVisibility(View.VISIBLE);
    }

    private void showSuccess() {
        successMode = true;
        UpdateChecker.markSuccessShown(this);
        layoutInstalling.setVisibility(View.GONE);
        contentUpdate.setVisibility(View.GONE);
        contentSuccess.setVisibility(View.VISIBLE);
        countdown = GO_FEED_SECONDS;
        tvCountdown.setText("(" + countdown + ")");
        h.postDelayed(tick, 1000);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            countdown--;
            if (countdown <= 0) {
                goHome();
            } else {
                tvCountdown.setText("(" + countdown + ")");
                h.postDelayed(this, 1000);
            }
        }
    };

    // ---------------- Navigation ----------------

    private void closePage() {
        finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    private void goHome() {
        if (going) return;
        going = true;
        h.removeCallbacksAndMessages(null);
        boolean in = SupabaseRest.isLoggedIn() && SupabaseRest.uid() != null;
        Intent i = new Intent(this, in ? HomeActivity.class : LoginActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
        finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    // ---------------- Progress animation ----------------

    private void setPctInstant(int pct) {
        shownPct = pct;
        progressBarUpdate.setProgress(pct);
        tvProgressPercentage.setText(pct + "%");
    }

    private void animateTo(int target) {
        if (target < shownPct) target = shownPct;
        if (target > 100) target = 100;
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofInt(shownPct, target);
        anim.setDuration(400);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> {
            int v = (int) a.getAnimatedValue();
            shownPct = v;
            progressBarUpdate.setProgress(v);
            tvProgressPercentage.setText(v + "%");
        });
        anim.start();
    }

    // ---------------- Helpers ----------------

    // bg_blue_wave.xml (viewport 1456 x 2576) এর বাঁকা রেখার cubic segments: x1,y1 ... x4,y4
    private static final float[][] WAVE = {
            {700, 0, 1000, 110, 1222, 480, 1222, 820},
            {1222, 820, 1222, 1130, 1110, 1330, 1030, 1450},
            {1030, 1450, 940, 1590, 910, 1740, 910, 1900},
            {910, 1900, 910, 2200, 1040, 2430, 1190, 2576}
    };

    /**
     * Progress section (Updating... + বার + %) এর ডান প্রান্ত নীল কার্ভের বাঁ দিকে রাখে,
     * যেন বার ও শতকরা সংখ্যা সাদা অংশের ভেতরে থাকে। যেকোনো স্ক্রিন সাইজে কাজ করে।
     */
    private void fitProgressInsideWhite() {
        View root = findViewById(android.R.id.content);
        if (root == null || root.getWidth() == 0 || root.getHeight() == 0) return;
        int[] a = new int[2], b = new int[2];
        root.getLocationInWindow(a);
        layoutProgressSection.getLocationInWindow(b);
        float top = b[1] - a[1];
        float bottom = top + layoutProgressSection.getHeight();
        float vy0 = top / root.getHeight() * 2576f;
        float vy1 = bottom / root.getHeight() * 2576f;

        float minX = Float.MAX_VALUE;
        for (float[] sgm : WAVE) {
            for (int i = 0; i <= 200; i++) {
                float t = i / 200f, u = 1 - t;
                float y = u * u * u * sgm[1] + 3 * u * u * t * sgm[3] + 3 * u * t * t * sgm[5] + t * t * t * sgm[7];
                if (y < vy0 - 20 || y > vy1 + 20) continue;
                float x = u * u * u * sgm[0] + 3 * u * u * t * sgm[2] + 3 * u * t * t * sgm[4] + t * t * t * sgm[6];
                if (x < minX) minX = x;
            }
        }
        if (minX == Float.MAX_VALUE) return;

        float density = getResources().getDisplayMetrics().density;
        float curvePx = minX / 1456f * root.getWidth();
        float gap = 16 * density;                      // কার্ভ থেকে নিরাপদ দূরত্ব
        int marginEnd = (int) Math.max(0, root.getWidth() - (curvePx - gap));
        ViewGroup.LayoutParams lp = layoutProgressSection.getLayoutParams();
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            ((ViewGroup.MarginLayoutParams) lp).setMarginEnd(marginEnd);
            layoutProgressSection.setLayoutParams(lp);
        }
    }

    private void setButtonsTop(int dp) {
        ViewGroup.LayoutParams lp = layoutActionButtons.getLayoutParams();
        if (lp instanceof LinearLayout.LayoutParams) {
            ((LinearLayout.LayoutParams) lp).topMargin = (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
            layoutActionButtons.setLayoutParams(lp);
        }
    }

    private void applyFonts() {
        try {
            Typeface mont = Typeface.createFromAsset(getAssets(), "fonts/montserrat bold.ttf");
            ((TextView) findViewById(R.id.tvNewUpdate)).setTypeface(mont);
            ((TextView) findViewById(R.id.tvWhatsNewTitle)).setTypeface(mont);
            ((TextView) findViewById(R.id.tvUpdatingStatus)).setTypeface(mont);
        } catch (Throwable ignore) {
        }
    }

    private void setupCurrentVersion() {
        String v = "";
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            v = pi.versionName == null ? "" : pi.versionName;
        } catch (Throwable ignore) {
        }
        ((TextView) findViewById(R.id.tvCurrentVersion)).setText("Current version: v" + v);
    }

    /** "There is a new version of FamilyBook." এর FamilyBook নীল + বোল্ড */
    private void setupBodyText() {
        TextView tv = findViewById(R.id.tvNewUpdateBody);
        String full = tv.getText().toString();
        int st = full.indexOf("FamilyBook");
        if (st < 0) return;
        int en = st + "FamilyBook".length();
        SpannableString sp = new SpannableString(full);
        sp.setSpan(new ForegroundColorSpan(Color.parseColor("#0B5CFF")), st, en, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        sp.setSpan(new StyleSpan(Typeface.BOLD), st, en, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        tv.setText(sp);
    }

    private void setupPlayStoreText() {
        String full = "Update from playstore";
        SpannableString sp = new SpannableString(full);
        int s = full.indexOf("playstore");
        int e = s + "playstore".length();
        sp.setSpan(new ForegroundColorSpan(Color.parseColor("#0B5CFF")), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        sp.setSpan(new StyleSpan(Typeface.BOLD), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        tvPlayStoreText.setText(sp);
    }
}
