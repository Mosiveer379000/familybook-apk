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
    private View layoutActionButtons;
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
        if (installLaunched && !successMode && !installMode) showInstallButton();
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
        tvUpdatingStatus.setText("Installing...");
    }

    @Override
    public void onFailed() {
        if (anim != null) anim.cancel();
        installLaunched = false;
        installMode = false;
        shownPct = 0;
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
            UpdateChecker.installNow(this);
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
    }

    private void showInstallButton() {
        installMode = true;
        tvUpdatingStatus.setText("Download complete");
        btnLater.setVisibility(View.GONE);
        btnUpdateNow.setText("Install now");
        setButtonsTop(16);
        layoutActionButtons.setVisibility(View.VISIBLE);
    }

    private void showSuccess() {
        successMode = true;
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
