package com.familybook.app;

import android.content.Intent;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.LinearLayout;
import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {

    private ImageView ivBack;
    private LinearLayout layoutAccount, layoutPrivacy, layoutBlocking;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        applySettingsTheme();

        ivBack = findViewById(R.id.ivBack);
        layoutAccount = findViewById(R.id.layoutAccount);
        layoutPrivacy = findViewById(R.id.layoutPrivacy);
        layoutBlocking = findViewById(R.id.layoutBlocking);

        ivBack.setOnClickListener(v -> finish());

        // Account verification: আগের মতোই Accounts & Privacy ফর্ম খোলে
        layoutAccount.setOnClickListener(v ->
                startActivity(new Intent(SettingsActivity.this, AccountsPrivacyActivity.class)));

        // Password & Security: assets/password_security.html (WebView) খোলে
        // (Password, Primary Email, Recovery Email, Phone, Forgot password এখানে)
        layoutPrivacy.setOnClickListener(v ->
                startActivity(new Intent(SettingsActivity.this, PasswordSecurityActivity.class)));

        // Blocking
        layoutBlocking.setOnClickListener(v ->
                startActivity(new Intent(SettingsActivity.this, BlockingActivity.class)));

        // Media: ভিডিও অটো-প্লে বন্ধ করার অপশন
        findViewById(R.id.layoutMedia).setOnClickListener(v ->
                startActivity(new Intent(SettingsActivity.this, MediaSettingsActivity.class)));

        // Notifications: assets/notification_settings.html (WebView) খোলে
        findViewById(R.id.layoutNotification).setOnClickListener(v ->
                startActivity(new Intent(SettingsActivity.this, NotificationSettingsActivity.class)));

        // Dark mode: assets/dark_mode_settings.html (WebView) খোলে
        findViewById(R.id.layoutDarkMode).setOnClickListener(v ->
                startActivity(new Intent(SettingsActivity.this, DarkModeActivity.class)));

        // Reaction preferences: assets/reaction_preferences.html (WebView) খোলে
        findViewById(R.id.layoutReaction).setOnClickListener(v ->
                startActivity(new Intent(SettingsActivity.this, ReactionPreferencesActivity.class)));

        // Profile locking: প্রোফাইলের ৩-ডট মেনুর "Lock profile" পেজই খোলে (LockProfileActivity)
        findViewById(R.id.layoutProfileLocking).setOnClickListener(v -> openProfileLock());

        // Profile details: প্রোফাইল পেজের এডিট আইকনের মতোই Profile info এডিট মেনু (ProfileInfoEditActivity) খোলে
        findViewById(R.id.layoutProfileDetails).setOnClickListener(v -> {
            String me = SupabaseRest.uid();
            if (me == null) return;
            startActivity(new Intent(SettingsActivity.this, ProfileInfoEditActivity.class)
                    .putExtra("uid", me));
        });

        // Payments > Ads payments: assets/add_payment.html (WebView) খোলে
        findViewById(R.id.layoutAdsPayments).setOnClickListener(v ->
                startActivity(new Intent(SettingsActivity.this, PaymentActivity.class)));

        // Payments > Payouts: assets/payout/index.html (WebView) খোলে
        findViewById(R.id.layoutPayouts).setOnClickListener(v ->
                startActivity(new Intent(SettingsActivity.this, PayoutActivity.class)));
    }

    private boolean isNight() {
        return (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    /** ডার্ক/লাইট অনুযায়ী উইন্ডো, স্ট্যাটাস বার ও নেভিগেশন বারের রং মেলায় */
    private void applySettingsTheme() {
        int bg = androidx.core.content.ContextCompat.getColor(this, R.color.s_bg);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(bg));
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        try {
            androidx.core.view.WindowInsetsControllerCompat c = androidx.core.view.WindowCompat
                    .getInsetsController(getWindow(), getWindow().getDecorView());
            if (c != null) {
                c.setAppearanceLightStatusBars(!isNight());
                c.setAppearanceLightNavigationBars(!isNight());
            }
        } catch (Exception ignored) { }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (MediaSettingsActivity.savedBanner) {
            MediaSettingsActivity.savedBanner = false;
            TopToast.showLight(this, "Settings saved");
        }
    }

    private boolean lockLoading = false;

    /** নিজের প্রোফাইলের লক অবস্থা ও ছবি Supabase থেকে এনে একই Lock profile পেজ খোলে */
    private void openProfileLock() {
        final String me = SupabaseRest.uid();
        if (me == null || lockLoading) return;
        if (!Net.check(this)) return;
        lockLoading = true;
        SupabaseRest.call("GET", "/rest/v1/profiles?id=eq." + me + "&select=is_locked,locked_at,avatar_key",
                null, null, true, r -> {
            lockLoading = false;
            if (isFinishing()) return;
            if (!r.ok()) { TopToast.show(this, SupabaseRest.errorText(r)); return; }
            boolean locked = false;
            long lockedAt = 0L;
            String avatarKey = null;
            try {
                org.json.JSONArray a = new org.json.JSONArray(r.body);
                if (a.length() > 0) {
                    org.json.JSONObject o = a.getJSONObject(0);
                    locked = o.optBoolean("is_locked", false);
                    lockedAt = isoToMs(o.optString("locked_at", null));
                    String ak = o.isNull("avatar_key") ? "" : o.optString("avatar_key", "").trim();
                    avatarKey = ak.isEmpty() ? null : ak;
                }
            } catch (Exception ignored) { }
            LockProfileActivity.open(this, avatarKey, locked, lockedAt);
        });
    }

    private static long isoToMs(String iso) {
        if (iso == null || iso.length() < 19) return 0L;
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US);
            f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            return f.parse(iso.substring(0, 19)).getTime();
        } catch (Exception e) {
            return 0L;
        }
    }
}
