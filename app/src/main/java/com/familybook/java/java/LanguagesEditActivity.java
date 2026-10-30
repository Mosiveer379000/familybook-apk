package com.familybook.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Add Languages পেজ (৮ নং ছবি)।
 * - ধূসর চিপে ভাষা দেখায়; চিপে চাপলে নীল হয়ে x আসে, x চাপলে বাদ যায়
 * - ইনপুটে লিখলে নিচে সাজেশন আসে; সাজেশনে চাপলে বা কিবোর্ডের Done দিলে যোগ হয়
 * - Save → info.languages (array) ও info.priv.languages
 * Intent extra: "langs" (String[]), "pub" (Public হলে true)
 */
public class LanguagesEditActivity extends BaseActivity {

    private static final int MAX = 10;
    private static final String[] LANGS = {
            "Bengali", "English", "Hindi", "Urdu", "Arabic", "Chittagonian", "Sylheti", "Rohingya",
            "Chakma", "Marma", "Santali", "Persian", "Turkish", "Malay", "Indonesian", "Tamil",
            "Telugu", "Nepali", "Punjabi", "Gujarati", "Marathi", "Assamese", "Odia", "Sanskrit",
            "Pashto", "Sinhala", "Burmese", "Thai", "Vietnamese", "Chinese", "Japanese", "Korean",
            "French", "German", "Spanish", "Portuguese", "Italian", "Russian", "Dutch", "Greek",
            "Hebrew", "Swahili"
    };

    private final ArrayList<String> langs = new ArrayList<>();
    private String openChip = null;   // যে চিপে x দেখা যাচ্ছে

    private FlowLayout flow;
    private EditText et;
    private LinearLayout llSuggest;
    private PersonalInfoStore.PrivToggle priv;
    private boolean saving = false;
    private boolean ignoreText = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Me.isLoggedIn() || Me.uid() == null) {
            finish();
            return;
        }
        setContentView(R.layout.activity_languages_edit);

        flow = findViewById(R.id.flowLang);
        et = findViewById(R.id.etLang);
        llSuggest = findViewById(R.id.llLangSuggest);

        findViewById(R.id.boxLang).setBackground(box());

        String[] old = getIntent().getStringArrayExtra("langs");
        if (old != null) for (String s : old) addLang(s, false);

        priv = new PersonalInfoStore.PrivToggle((ImageView) findViewById(R.id.ivPrivLang),
                getIntent().getBooleanExtra("pub", true));

        findViewById(R.id.btnBackLang).setOnClickListener(v -> finish());
        findViewById(R.id.btnSaveLang).setOnClickListener(v -> save());
        findViewById(R.id.btnLangAdd).setOnClickListener(v -> addTyped());

        et.setOnEditorActionListener((v, actionId, e) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) { addTyped(); return true; }
            return false;
        });
        et.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                if (!ignoreText) showSuggestions(s.toString());
            }
            @Override public void afterTextChanged(Editable s) { }
        });

        renderChips();
        findViewById(R.id.rootLang).requestFocus();
    }

    // ---------- যোগ / বাদ ----------
    private void addTyped() {
        String t = et.getText().toString().trim();
        if (t.isEmpty()) return;
        // তালিকার নামের সাথে মিললে সুন্দর বানানটা নেয়
        for (String n : LANGS) if (n.equalsIgnoreCase(t)) { t = n; break; }
        addLang(t, true);
    }

    private void addLang(String name, boolean fromUser) {
        if (name == null) return;
        name = name.trim();
        if (name.isEmpty()) return;
        for (String x : langs) {
            if (x.equalsIgnoreCase(name)) {
                if (fromUser) clearInput();
                return;
            }
        }
        if (langs.size() >= MAX) {
            if (fromUser) TopToast.show(this, "Maximum " + MAX + " languages");
            return;
        }
        langs.add(name);
        if (fromUser) { clearInput(); renderChips(); }
    }

    private void clearInput() {
        ignoreText = true;
        et.setText("");
        ignoreText = false;
        llSuggest.removeAllViews();
    }

    // ---------- চিপ ----------
    private void renderChips() {
        flow.removeAllViews();
        for (final String name : langs) {
            boolean open = name.equals(openChip);

            LinearLayout chip = new LinearLayout(this);
            chip.setOrientation(LinearLayout.HORIZONTAL);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setPadding(dp(7), dp(3), dp(open ? 4 : 7), dp(3));
            chip.setBackground(round(open ? "#D6E9FF" : "#E4E6EB", 40));

            TextView tv = new TextView(this);
            tv.setText(name);
            tv.setTextSize(13);
            tv.setTypeface(Typeface.DEFAULT_BOLD);
            tv.setTextColor(Color.parseColor(open ? "#1877F2" : "#65676B"));
            chip.addView(tv);

            if (open) {
                TextView x = new TextView(this);
                x.setText("×");
                x.setTextSize(11);
                x.setTypeface(Typeface.DEFAULT_BOLD);
                x.setTextColor(Color.WHITE);
                x.setGravity(Gravity.CENTER);
                x.setBackground(round("#1877F2", 30));
                LinearLayout.LayoutParams xp = new LinearLayout.LayoutParams(dp(16), dp(16));
                xp.setMarginStart(dp(6));
                x.setLayoutParams(xp);
                x.setOnClickListener(v -> {
                    langs.remove(name);
                    openChip = null;
                    renderChips();
                });
                chip.addView(x);
            }
            chip.setOnClickListener(v -> {
                openChip = name.equals(openChip) ? null : name;
                renderChips();
            });
            flow.addView(chip);
        }
    }

    // ---------- সাজেশন ----------
    private void showSuggestions(String q) {
        llSuggest.removeAllViews();
        q = q.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return;
        int shown = 0;
        for (String name : LANGS) {
            if (!name.toLowerCase(Locale.ROOT).contains(q)) continue;
            boolean has = false;
            for (String x : langs) if (x.equalsIgnoreCase(name)) { has = true; break; }
            if (has) continue;
            TextView tv = new TextView(this);
            tv.setText(name);
            tv.setTextSize(16);
            tv.setTextColor(Color.parseColor("#050505"));
            tv.setPadding(dp(14), dp(12), dp(14), dp(12));
            tv.setBackgroundResource(android.R.drawable.list_selector_background);
            tv.setOnClickListener(v -> { addLang(name, true); hideKeyboard(); });
            llSuggest.addView(tv);
            if (++shown >= 6) break;
        }
    }

    // ---------- সেভ ----------
    private void save() {
        if (saving) return;
        // ইনপুটে লেখা থেকে গেলে সেটাও যোগ হবে
        addTyped();
        saving = true;
        try {
            JSONArray arr = new JSONArray();
            for (String s : langs) arr.put(s);
            JSONObject info = new JSONObject().put("languages", arr);
            JSONObject pr = new JSONObject().put("languages", priv.pub);
            PersonalInfoStore.save(info, pr, err -> {
                saving = false;
                if (isFinishing()) return;
                if (err != null) {
                    TopToast.show(this, err);
                    return;
                }
                TopToast.show(this, "Languages saved!");
                finish();
            });
        } catch (Exception e) {
            saving = false;
            TopToast.show(this, "Could not save");
        }
    }

    // ---------- helpers ----------
    private GradientDrawable box() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.WHITE);
        g.setCornerRadius(dp(18));
        g.setStroke(dp(2), Color.parseColor("#E0E0E0"));
        return g;
    }

    private GradientDrawable round(String color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.parseColor(color));
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(et.getWindowToken(), 0);
        findViewById(R.id.rootLang).requestFocus();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
