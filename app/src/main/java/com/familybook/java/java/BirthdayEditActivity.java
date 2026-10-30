package com.familybook.app;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.StyleSpan;
import android.graphics.Typeface;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * Birthday পেজ (৪ নং ছবি)।
 * জন্মতারিখ রেজিস্ট্রেশনের তথ্য — এখানে বদলানো যায় না; শুধু তারিখ ও সালের প্রাইভেসি (Public/Only me) বদলানো যায়।
 * বদলাতে চাইলে "Edit your birthday" → Settings > Accounts & Pivracy (NID/Birth ID ভেরিফাই)।
 *
 * Intent extra: "dob" (dd/MM/yyyy), "verified" (boolean), "pubDate", "pubYear" (boolean)
 * Save → info.priv.birthday ও info.priv.birthYear
 */
public class BirthdayEditActivity extends BaseActivity {

    private static final String[] MONTHS = {"January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December"};

    private PersonalInfoStore.PrivToggle privDate, privYear;
    private boolean saving = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Me.isLoggedIn() || Me.uid() == null) {
            finish();
            return;
        }
        setContentView(R.layout.activity_birthday_edit);

        String dob = getIntent().getStringExtra("dob");
        boolean verified = getIntent().getBooleanExtra("verified", false);

        // ---- জন্মতারিখ দেখানো ----
        String day = "", month = "", year = "";
        try {
            String[] p = dob.split("/");
            day = String.valueOf(Integer.parseInt(p[0]));
            month = MONTHS[Integer.parseInt(p[1]) - 1];
            year = p[2];
        } catch (Exception ignored) { }
        ((TextView) findViewById(R.id.tvBdayDay)).setText(day);
        ((TextView) findViewById(R.id.tvBdayMonth)).setText(month);
        ((TextView) findViewById(R.id.tvBdayYear)).setText(year);

        // ---- বক্সের গোল বর্ডার ----
        findViewById(R.id.boxBdayDate).setBackground(stroke(14, 2, "#CED0D4", Color.WHITE));
        findViewById(R.id.boxBdayYear).setBackground(stroke(14, 2, "#CED0D4", Color.WHITE));
        findViewById(R.id.boxEditBday).setBackground(stroke(2, 2, "#CED0D4", Color.WHITE));

        // ---- Not verified (ধূসর) / verified (সবুজ) ----
        TextView badge = findViewById(R.id.tvBdayBadge);
        badge.setText(verified ? "verified" : "Not verified");
        badge.setBackground(stroke(12, 0, "#00000000",
                Color.parseColor(verified ? "#4CAF50" : "#8A8D91")));

        // ---- Edit your birthday বক্সের লেখা ----
        TextView hint = findViewById(R.id.tvEditBdayHint);
        SpannableString hs = new SpannableString(verified
                ? "Your birthday is verified."
                : "You can verify your birthday in settings to get 'verified' certificate.");
        int at = hs.toString().indexOf("settings");
        if (at >= 0) hs.setSpan(new StyleSpan(Typeface.BOLD), at, at + 8, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        hint.setText(hs);
        findViewById(R.id.boxEditBday).setOnClickListener(v ->
                startActivity(new Intent(this, AccountsPrivacyActivity.class)));

        // ---- Demo checkbox ----
        CheckBox cb = findViewById(R.id.cbBdayWish);
        SpannableString cs = new SpannableString("Get Special Birthday wishing from the family");
        int f = cs.toString().indexOf("family");
        cs.setSpan(new StyleSpan(Typeface.BOLD), f, f + 6, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        cb.setText(cs);
        cb.setOnClickListener(v -> TopToast.show(this, "This is a demo for now"));

        // ---- প্রাইভেসি ----
        privDate = new PersonalInfoStore.PrivToggle((ImageView) findViewById(R.id.ivPrivBdayDate),
                getIntent().getBooleanExtra("pubDate", true));
        privYear = new PersonalInfoStore.PrivToggle((ImageView) findViewById(R.id.ivPrivBdayYear),
                getIntent().getBooleanExtra("pubYear", true));

        findViewById(R.id.boxBdayYear).setOnClickListener(v -> TopToast.show(this, "Use \"Edit your birthday\" to change the birth year"));

        findViewById(R.id.btnBackBday).setOnClickListener(v -> finish());
        findViewById(R.id.btnSaveBday).setOnClickListener(v -> save());
    }

    private void save() {
        if (saving) return;
        saving = true;
        try {
            JSONObject pr = new JSONObject()
                    .put("birthday", privDate.pub)
                    .put("birthYear", privYear.pub);
            PersonalInfoStore.save(null, pr, err -> {
                saving = false;
                if (isFinishing()) return;
                if (err != null) {
                    TopToast.show(this, err);
                    return;
                }
                TopToast.show(this, "Birthday saved!");
                finish();
            });
        } catch (Exception e) {
            saving = false;
            TopToast.show(this, "Could not save");
        }
    }

    private GradientDrawable stroke(int radiusDp, int strokeDp, String strokeColor, int fill) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) g.setStroke(dp(strokeDp), Color.parseColor(strokeColor));
        return g;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
