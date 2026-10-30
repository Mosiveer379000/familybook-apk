package com.familybook.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.Locale;

/**
 * Add Current city / Add Hometown পেজ (৫ ও ৬ নং ছবি)।
 * Intent extra: "type" = "city" | "hometown",  "value" = আগের মান,  "pub" = প্রাইভেসি (Public হলে true)
 * Save → profiles.info.currentCity / info.hometown এবং info.priv-এ সেভ হয়ে আগের পেজে ফেরে।
 * তালিকায় না থাকলেও নিজে লিখে Save দেওয়া যায়।
 */
public class PlaceEditActivity extends BaseActivity {

    private static final String[] PLACES = {
            "Bagerhat", "Bandarban", "Barguna", "Barishal", "Bhola", "Bogura", "Brahmanbaria",
            "Chandpur", "Chapainawabganj", "Chittagong", "Chuadanga", "Cox's Bazar", "Cumilla",
            "Dhaka", "Dinajpur", "Faridpur", "Feni", "Gaibandha", "Gazipur", "Gopalganj",
            "Habiganj", "Jamalpur", "Jashore", "Jhalokathi", "Jhenaidah", "Joypurhat",
            "Khagrachhari", "Khulna", "Kishoreganj", "Kurigram", "Kushtia", "Lakshmipur",
            "Lalmonirhat", "Madaripur", "Magura", "Manikganj", "Meherpur", "Moulvibazar",
            "Munshiganj", "Mymensingh", "Naogaon", "Narail", "Narayanganj", "Narsingdi",
            "Natore", "Netrokona", "Nilphamari", "Noakhali", "Pabna", "Panchagarh", "Patuakhali",
            "Pirojpur", "Rajbari", "Rajshahi", "Rangamati", "Rangpur", "Satkhira", "Shariatpur",
            "Sherpur", "Sirajganj", "Sunamganj", "Sylhet", "Tangail", "Thakurgaon",
            "Kolkata", "Delhi", "Dubai", "Abu Dhabi", "Riyadh", "Jeddah", "Makkah", "Madinah",
            "Doha", "Kuwait City", "Muscat", "Kuala Lumpur", "Singapore", "London", "New York",
            "Toronto", "Sydney", "Tokyo"
    };

    private EditText etPlace;
    private LinearLayout llSuggest;
    private PersonalInfoStore.PrivToggle priv;
    private boolean home;
    private boolean saving = false;
    private boolean ignoreText = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Me.isLoggedIn() || Me.uid() == null) {
            finish();
            return;
        }
        setContentView(R.layout.activity_place_edit);

        home = "hometown".equals(getIntent().getStringExtra("type"));
        ((TextView) findViewById(R.id.tvPlaceTitle)).setText(home ? "Add Hometown" : "Add Current city");

        etPlace = findViewById(R.id.etPlace);
        llSuggest = findViewById(R.id.llPlaceSuggest);

        GradientDrawable pill = new GradientDrawable();
        pill.setColor(Color.parseColor("#F0F2F5"));
        pill.setCornerRadius(dp(30));
        findViewById(R.id.boxPlaceSearch).setBackground(pill);

        priv = new PersonalInfoStore.PrivToggle((ImageView) findViewById(R.id.ivPrivPlace),
                getIntent().getBooleanExtra("pub", true));

        String old = getIntent().getStringExtra("value");
        if (old != null && !old.trim().isEmpty()) {
            ignoreText = true;
            etPlace.setText(old);
            etPlace.setSelection(old.length());
            ignoreText = false;
        }

        findViewById(R.id.btnBackPlace).setOnClickListener(v -> finish());
        findViewById(R.id.btnSavePlace).setOnClickListener(v -> save());

        etPlace.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                if (!ignoreText) showSuggestions(s.toString());
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        etPlace.setOnEditorActionListener((v, actionId, e) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) { hideKeyboard(); return true; }
            return false;
        });

        findViewById(R.id.rootPlace).requestFocus();
    }

    private void showSuggestions(String q) {
        llSuggest.removeAllViews();
        q = q.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return;
        int shown = 0;
        for (String name : PLACES) {
            if (!name.toLowerCase(Locale.ROOT).contains(q)) continue;
            if (name.equalsIgnoreCase(q)) continue;
            TextView tv = new TextView(this);
            tv.setText(name);
            tv.setTextColor(Color.parseColor("#050505"));
            tv.setTextSize(16);
            tv.setPadding(dp(8), dp(12), dp(8), dp(12));
            tv.setBackgroundResource(android.R.drawable.list_selector_background);
            tv.setOnClickListener(v -> {
                ignoreText = true;
                etPlace.setText(name);
                etPlace.setSelection(name.length());
                ignoreText = false;
                llSuggest.removeAllViews();
                hideKeyboard();
            });
            llSuggest.addView(tv);
            if (++shown >= 8) break;
        }
    }

    private void save() {
        if (saving) return;
        saving = true;
        String value = etPlace.getText().toString().trim();
        try {
            JSONObject info = new JSONObject().put(home ? "hometown" : "currentCity", value);
            JSONObject pr = new JSONObject().put(home ? "hometown" : "currentCity", priv.pub);
            PersonalInfoStore.save(info, pr, err -> {
                saving = false;
                if (isFinishing()) return;
                if (err != null) {
                    TopToast.show(this, err);
                    return;
                }
                TopToast.show(this, home ? "Hometown saved!" : "Current city saved!");
                finish();
            });
        } catch (Exception e) {
            saving = false;
            TopToast.show(this, "Could not save");
        }
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(etPlace.getWindowToken(), 0);
        findViewById(R.id.rootPlace).requestFocus();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
