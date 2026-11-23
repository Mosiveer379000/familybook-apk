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
        // আরবি/উর্দু (ডান-থেকে-বাম) হলে ব্যাক তীর উল্টো
        if (getResources().getConfiguration().getLayoutDirection() == android.view.View.LAYOUT_DIRECTION_RTL) {
            ivBack.setScaleX(-1f);
        }

        setupSearch();

        // উপরের ডানের প্রোফাইল ছবি: নিজের ছবি লোড হয়, চাপলে সরাসরি নিজের প্রোফাইলে যায়
        final String myUid = SupabaseRest.uid();
        final ImageView ivProfile = findViewById(R.id.ivProfile);
        if (ivProfile != null && myUid != null) {
            AvatarLoader.load(ivProfile, myUid);
            ivProfile.setOnClickListener(v ->
                    ProfileActivity.open(SettingsActivity.this, myUid, FbCache.getName(myUid)));
        }

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

        // Language: assets/language_region_settings.html (WebView) খোলে
        // (App language + Translation language)
        findViewById(R.id.layoutLanguage).setOnClickListener(v ->
                startActivity(new Intent(SettingsActivity.this, LanguageRegionActivity.class)));

        // Profile locking: প্রোফাইলের ৩-ডট মেনুর "Lock profile" পেজই খোলে (LockProfileActivity)
        findViewById(R.id.layoutProfileLocking).setOnClickListener(v -> openProfileLock());

        // Profile details: প্রোফাইল পেজের এডিট আইকনের মতোই Profile info এডিট মেনু (ProfileInfoEditActivity) খোলে
        findViewById(R.id.layoutProfileDetails).setOnClickListener(v -> {
            String me = SupabaseRest.uid();
            if (me == null) return;
            startActivity(new Intent(SettingsActivity.this, ProfileInfoEditActivity.class)
                    .putExtra("uid", me));
        });

        // Buy blue badge: assets/buy_blue_badge.html (WebView) খোলে
        findViewById(R.id.layoutBuyBadge).setOnClickListener(v ->
                startActivity(new Intent(SettingsActivity.this, BlueBadgeActivity.class)));

        // Payments > Ads payments: assets/add_payment.html (WebView) খোলে
        findViewById(R.id.layoutAdsPayments).setOnClickListener(v ->
                startActivity(new Intent(SettingsActivity.this, PaymentActivity.class)));

        // Payments > Payouts: assets/payout/index.html (WebView) খোলে
        findViewById(R.id.layoutPayouts).setOnClickListener(v ->
                startActivity(new Intent(SettingsActivity.this, PayoutActivity.class)));
    }

    // ---------- Settings সার্চ (টাইপ করতে করতেই ফলাফল) ----------

    private static final class Row {
        final android.view.View view;
        final String haystack;   // বর্তমান ভাষা + ইংরেজি লেখা (দুই ভাষাতেই খোঁজা যাবে)
        Row(android.view.View v, String h) { view = v; haystack = h; }
    }

    private final java.util.ArrayList<Row> searchRows = new java.util.ArrayList<>();

    /** সারির আইডি + তার শিরোনাম/বিবরণের স্ট্রিং */
    private static final int[][] SEARCH_MAP = {
            {R.id.layoutAccount, R.string.st_account, R.string.st_account_sub},
            {R.id.layoutPrivacy, R.string.st_password, R.string.st_password_sub},
            {R.id.layoutFamily, R.string.st_family, R.string.st_family_sub},
            {R.id.layoutReaction, R.string.st_reaction},
            {R.id.layoutNotification, R.string.st_notifications},
            {R.id.layoutDarkMode, R.string.st_dark},
            {R.id.layoutMedia, R.string.st_media},
            {R.id.layoutLanguage, R.string.st_language},
            {R.id.layoutBlocking, R.string.st_blocking},
            {R.id.layoutAdsPayments, R.string.st_ads_payments, R.string.st_payments},
            {R.id.layoutPayouts, R.string.st_payouts, R.string.st_payments},
            {R.id.layoutProfileLocking, R.string.st_profile_locking},
            {R.id.layoutProfileDetails, R.string.st_profile_details},
            {R.id.layoutBuyBadge, R.string.st_blue_badge, R.string.st_blue_badge_sub},
            {R.id.layoutCriteriaAchieve, R.string.st_monetization, R.string.st_monetization_sub},
    };

    private void setupSearch() {
        final android.widget.EditText et = findViewById(R.id.etSearchSettings);
        final android.widget.TextView empty = findViewById(R.id.tvNoResults);
        final android.view.View payTitle = findViewById(R.id.tvPaymentsTitle);
        final android.view.View paySub = findViewById(R.id.tvPaymentsSub);
        if (et == null) return;

        // ইংরেজি লেখাও জমা রাখি, যাতে অ্যাপ বাংলা/অন্য ভাষায় থাকলেও ইংরেজিতে খুঁজলে পাওয়া যায়
        android.content.res.Configuration cfg = new android.content.res.Configuration(getResources().getConfiguration());
        cfg.setLocale(java.util.Locale.ENGLISH);
        final android.content.res.Resources en = createConfigurationContext(cfg).getResources();

        searchRows.clear();
        for (int[] m : SEARCH_MAP) {
            android.view.View v = findViewById(m[0]);
            if (v == null) continue;
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < m.length; i++) {
                sb.append(getString(m[i])).append(' ').append(en.getString(m[i])).append(' ');
            }
            searchRows.add(new Row(v, sb.toString().toLowerCase()));
        }

        et.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                filterSettings(s == null ? "" : s.toString(), empty, payTitle, paySub);
            }
            @Override public void afterTextChanged(android.text.Editable s) { }
        });
        et.setOnEditorActionListener((tv, action, ev) -> {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(tv.getWindowToken(), 0);
            return true;
        });
    }

    /** লেখা যত অক্ষরই টাইপ হোক, প্রতিটা শব্দ যে সারিতে মেলে শুধু সেটাই দেখায় */
    private void filterSettings(String query, android.widget.TextView empty,
                                android.view.View payTitle, android.view.View paySub) {
        String q = query.trim().toLowerCase();
        String[] tokens = q.isEmpty() ? new String[0] : q.split("\\s+");
        int shown = 0;
        boolean payVisible = false;
        for (Row r : searchRows) {
            boolean ok = true;
            for (String t : tokens) {
                if (!r.haystack.contains(t)) { ok = false; break; }
            }
            r.view.setVisibility(ok ? android.view.View.VISIBLE : android.view.View.GONE);
            if (ok) {
                shown++;
                int id = r.view.getId();
                if (id == R.id.layoutAdsPayments || id == R.id.layoutPayouts) payVisible = true;
            }
        }
        // "Payments" শিরোনাম: খোঁজা ফাঁকা থাকলে বা তার নিচের কোনো সারি দেখা গেলে
        int pv = (tokens.length == 0 || payVisible) ? android.view.View.VISIBLE : android.view.View.GONE;
        if (payTitle != null) payTitle.setVisibility(pv);
        if (paySub != null) paySub.setVisibility(pv);
        if (empty != null) empty.setVisibility(shown == 0 ? android.view.View.VISIBLE : android.view.View.GONE);
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
            TopToast.showLight(this, getString(R.string.st_saved));
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
