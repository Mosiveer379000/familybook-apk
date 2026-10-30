package com.familybook.app;

import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Relationship পেজ।
 *
 * প্রোফাইলে (profiles.info) যা সেভ হয়:
 *   relationship          : "Married" / "Unmarried" / "In a relationship" / "Single"
 *   relationshipWith      : { "name": "পার্টনারের নাম", ... }   (Single / Unmarried হলে null)
 *   relationshipSince     : "yyyy" / "yyyy-MM" / "yyyy-MM-dd"
 *   relationshipNote      : নিজের কথা (সর্বোচ্চ ১৫০ অক্ষর)
 *   relationshipVerified  : 'Add family' এ স্পাউস ভেরিফাই হলে সার্ভার true করে (এখান থেকে বদলানো যায় না)
 *   priv.relationship     : true = Public, false = Only me
 *
 * ভেরিফাইড Married হলে Status ও Partner লক থাকে (পাশে ✔ ব্যাজ); Since, Thoughts ও Privacy বদলানো যায়।
 */
public class RelationshipActivity extends BaseActivity {

    private static final String[] STATUSES = {"Married", "Unmarried", "In a relationship", "Single"};
    private static final String[] MONTHS = {"Month", "Jan", "Feb", "Mar", "Apr", "May", "Jun",
            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
    private static final int MIN_YEAR = 1950;
    private static final int MAX_THOUGHTS = 150;

    private Spinner spinnerStatus, spinnerYear, spinnerMonth, spinnerDay;
    private EditText etPartner, etThoughts;
    private TextView tvCharCount, tvPrivacy, btnSave;
    private View groupPartner, boxStatus;
    private ImageView ivStatusVerified, ivPartnerVerified;

    private int currentYear;
    private int dayMax = -1;            // Day স্পিনারে এখন কতদিন দেখানো হচ্ছে
    private boolean loaded = false;
    private boolean saving = false;
    private boolean lockedVerified = false;
    private boolean pub = false;        // false = Only me
    private JSONObject oldWith = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Me.isLoggedIn() || Me.uid() == null) {
            finish();
            return;
        }
        setContentView(R.layout.activity_relationship);

        spinnerStatus = findViewById(R.id.spinnerStatus);
        spinnerYear = findViewById(R.id.spinnerYear);
        spinnerMonth = findViewById(R.id.spinnerMonth);
        spinnerDay = findViewById(R.id.spinnerDay);
        etPartner = findViewById(R.id.etPartner);
        etThoughts = findViewById(R.id.etThoughts);
        tvCharCount = findViewById(R.id.tvCharCount);
        tvPrivacy = findViewById(R.id.tvPrivacy);
        btnSave = findViewById(R.id.btnSave);
        groupPartner = findViewById(R.id.groupPartner);
        boxStatus = findViewById(R.id.boxStatus);
        ivStatusVerified = findViewById(R.id.ivStatusVerified);
        ivPartnerVerified = findViewById(R.id.ivPartnerVerified);

        currentYear = Calendar.getInstance().get(Calendar.YEAR);

        // ---- Status ----
        List<String> st = new ArrayList<>();
        for (String s : STATUSES) st.add(s);
        spinnerStatus.setAdapter(new Ad(st, true));
        spinnerStatus.setOnItemSelectedListener(new SimpleSel(this::applyStatus));

        // ---- Since: Year → Month → Day (আগেরটি না বাছলে পরেরটি বন্ধ) ----
        List<String> years = new ArrayList<>();
        years.add("Year");
        for (int y = currentYear; y >= MIN_YEAR; y--) years.add(String.valueOf(y));
        spinnerYear.setAdapter(new Ad(years, false));

        List<String> months = new ArrayList<>();
        for (String m : MONTHS) months.add(m);
        spinnerMonth.setAdapter(new Ad(months, false));

        SimpleSel dateSel = new SimpleSel(this::refreshDateUi);
        spinnerYear.setOnItemSelectedListener(dateSel);
        spinnerMonth.setOnItemSelectedListener(dateSel);
        refreshDateUi();

        // ---- Thoughts অক্ষর গণনা ----
        etThoughts.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                tvCharCount.setText(s.length() + "/" + MAX_THOUGHTS);
            }
        });

        // ---- Privacy ----
        showPrivacy();
        findViewById(R.id.rowPrivacy).setOnClickListener(v -> pickPrivacy());

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        btnSave.setOnClickListener(v -> save());

        applyStatus();
        load();
    }

    // ------------------------------------------------------------------ লোড

    private void load() {
        btnSave.setAlpha(0.6f);
        Db.getMine("profiles", (p, err) -> {
            if (isFinishing()) return;
            if (err != null) {
                TopToast.show(this, err);
                return;
            }
            JSONObject info = p != null && p.optJSONObject("info") != null
                    ? p.optJSONObject("info") : new JSONObject();

            String rel = info.optString("relationship", "").trim();
            int idx = 0; // কিছু সেভ না থাকলে Married
            for (int i = 0; i < STATUSES.length; i++) {
                if (STATUSES[i].equalsIgnoreCase(rel)
                        || (STATUSES[i].equals("In a relationship") && rel.equalsIgnoreCase("In a relation"))) {
                    idx = i;
                    break;
                }
            }
            spinnerStatus.setSelection(idx);

            oldWith = info.optJSONObject("relationshipWith");
            String name = oldWith != null ? oldWith.optString("name", "").trim() : "";
            etPartner.setText(name);

            lockedVerified = idx == 0 && !name.isEmpty()
                    && info.optBoolean("relationshipVerified", false);

            applySince(info.optString("relationshipSince", ""));

            etThoughts.setText(info.optString("relationshipNote", ""));

            JSONObject priv = info.optJSONObject("priv");
            pub = priv != null && priv.optBoolean("relationship", false);
            showPrivacy();

            applyLock();
            applyStatus();
            loaded = true;
            btnSave.setAlpha(1f);
        });
    }

    /** "yyyy[-MM[-dd]]" থেকে Year/Month/Day স্পিনার সেট */
    private void applySince(String since) {
        try {
            String[] a = since.split("-");
            int y = Integer.parseInt(a[0]);
            if (y < MIN_YEAR || y > currentYear) return;
            spinnerYear.setSelection(currentYear - y + 1);
            int m = a.length > 1 ? Integer.parseInt(a[1]) : 0;
            spinnerMonth.setSelection(m >= 1 && m <= 12 ? m : 0);
            refreshDateUi();
            int d = a.length > 2 ? Integer.parseInt(a[2]) : 0;
            if (d >= 1 && d <= dayMax) spinnerDay.setSelection(d);
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------ UI অবস্থা

    private boolean needsPartner() {
        int i = spinnerStatus.getSelectedItemPosition();
        return i == 0 || i == 2; // Married / In a relationship
    }

    private void applyStatus() {
        groupPartner.setVisibility(needsPartner() ? View.VISIBLE : View.GONE);
    }

    /** ভেরিফাই হলে Status ও Partner লক + ✔ ব্যাজ */
    private void applyLock() {
        ivStatusVerified.setVisibility(lockedVerified ? View.VISIBLE : View.GONE);
        ivPartnerVerified.setVisibility(lockedVerified ? View.VISIBLE : View.GONE);

        spinnerStatus.setEnabled(!lockedVerified);
        spinnerStatus.setClickable(!lockedVerified);
        etPartner.setEnabled(!lockedVerified);
        etPartner.setTextColor(Color.parseColor("#050505"));
        boxStatus.setOnClickListener(lockedVerified
                ? v -> TopToast.show(this, "Verified relationship can't be changed")
                : null);
        boxStatus.setClickable(lockedVerified);
    }

    /** Year → Month → Day এর ধাপে ধাপে চালু/বন্ধ, আর মাস অনুযায়ী দিনের সংখ্যা */
    private void refreshDateUi() {
        int y = spinnerYear.getSelectedItemPosition();
        if (y <= 0 && spinnerMonth.getSelectedItemPosition() != 0) spinnerMonth.setSelection(0);
        int m = y > 0 ? spinnerMonth.getSelectedItemPosition() : 0;
        setEnabledLook(spinnerMonth, y > 0);

        int max = 0;
        if (m > 0) {
            Calendar c = Calendar.getInstance();
            c.clear();
            c.set(currentYear - y + 1, m - 1, 1);
            max = c.getActualMaximum(Calendar.DAY_OF_MONTH);
        }
        if (max != dayMax) {
            int keep = spinnerDay.getAdapter() == null ? 0 : spinnerDay.getSelectedItemPosition();
            List<String> days = new ArrayList<>();
            days.add("Day");
            for (int d = 1; d <= max; d++) days.add(String.valueOf(d));
            spinnerDay.setAdapter(new Ad(days, false));
            spinnerDay.setSelection(keep <= max ? keep : 0);
            dayMax = max;
        }
        setEnabledLook(spinnerDay, m > 0);
    }

    private void setEnabledLook(View v, boolean on) {
        v.setEnabled(on);
        v.setAlpha(on ? 1f : 0.45f);
    }

    private void showPrivacy() {
        tvPrivacy.setText(pub ? "Public" : "Only me");
    }

    private void pickPrivacy() {
        final String[] opts = {"Public", "Only me"};
        new AlertDialog.Builder(this)
                .setTitle("Who can see this?")
                .setSingleChoiceItems(opts, pub ? 0 : 1, (d, which) -> {
                    pub = which == 0;
                    showPrivacy();
                    d.dismiss();
                })
                .show();
    }

    // ------------------------------------------------------------------ সেভ

    private void save() {
        if (saving) return;
        if (!loaded) {
            TopToast.show(this, "Loading, please wait");
            return;
        }

        boolean needs = needsPartner();
        String status = STATUSES[spinnerStatus.getSelectedItemPosition()];
        String partner = etPartner.getText().toString().trim().replaceAll("\\s+", " ");
        String thoughts = etThoughts.getText().toString().trim();

        if (needs && partner.length() < 2) {
            etPartner.setError("Partner name is required");
            etPartner.requestFocus();
            return;
        }

        // Since: Year / Year-Month / Year-Month-Day
        String since = "";
        int yp = spinnerYear.getSelectedItemPosition();
        int mp = spinnerMonth.getSelectedItemPosition();
        int dp = spinnerDay.getSelectedItemPosition();
        if (needs && yp > 0) {
            int year = currentYear - yp + 1;
            since = String.valueOf(year);
            if (mp > 0) since += String.format("-%02d", mp);
            if (mp > 0 && dp > 0) since += String.format("-%02d", dp);

            Calendar now = Calendar.getInstance();
            Calendar picked = Calendar.getInstance();
            picked.clear();
            picked.set(year, mp > 0 ? mp - 1 : 0, dp > 0 ? dp : 1);
            if (picked.after(now)) {
                TopToast.show(this, "Date can't be in the future");
                return;
            }
        }

        saving = true;
        btnSave.setAlpha(0.6f);
        try {
            JSONObject info = new JSONObject();
            if (!lockedVerified) {
                info.put("relationship", status);
                if (needs) {
                    JSONObject with = new JSONObject();
                    // একই নাম থাকলে আগের তথ্য (যেমন লিংক) রেখে দেওয়া
                    if (oldWith != null && partner.equalsIgnoreCase(oldWith.optString("name", ""))) {
                        with = new JSONObject(oldWith.toString());
                    }
                    with.put("name", partner);
                    info.put("relationshipWith", with);
                } else {
                    info.put("relationshipWith", JSONObject.NULL);
                }
                info.put("relationshipVerified", false);
            }
            info.put("relationshipSince", needs ? since : "");
            info.put("relationshipNote", thoughts);

            JSONObject pr = new JSONObject().put("relationship", pub);

            PersonalInfoStore.save(info, pr, e -> {
                saving = false;
                btnSave.setAlpha(1f);
                if (isFinishing()) return;
                if (e != null) {
                    TopToast.show(this, e);
                    return;
                }
                TopToast.show(this, "Relationship saved!");
                finish();
            });
        } catch (Exception ex) {
            saving = false;
            btnSave.setAlpha(1f);
            TopToast.show(this, "Could not save");
        }
    }

    // ------------------------------------------------------------ সহায়ক

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static final class SimpleSel implements AdapterView.OnItemSelectedListener {
        private final Runnable r;
        SimpleSel(Runnable r) { this.r = r; }
        @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { r.run(); }
        @Override public void onNothingSelected(AdapterView<?> p) { }
    }

    /** Status (বড় বোল্ড লেখা) ও Since (ছোট, তীরসহ) স্পিনারের অ্যাডাপ্টার */
    private final class Ad extends ArrayAdapter<String> {
        private final boolean statusStyle;

        Ad(List<String> items, boolean statusStyle) {
            super(RelationshipActivity.this, android.R.layout.simple_spinner_item, items);
            this.statusStyle = statusStyle;
        }

        @Override
        public View getView(int pos, View cv, ViewGroup parent) {
            TextView t = (TextView) super.getView(pos, cv, parent);
            t.setTextColor(Color.parseColor("#050505"));
            t.setTypeface(Typeface.DEFAULT_BOLD);
            if (statusStyle) {
                t.setTextSize(17);
                t.setPadding(0, 0, 0, 0);
                t.setCompoundDrawables(null, null, null, null);
            } else {
                t.setTextSize(14);
                t.setPadding(dp(10), 0, dp(6), 0);
                Drawable d = getDrawable(R.drawable.ic_arrow_down);
                if (d != null) {
                    d.setBounds(0, 0, dp(12), dp(12));
                    t.setCompoundDrawables(null, null, d, null);
                    t.setCompoundDrawablePadding(dp(8));
                }
            }
            return t;
        }

        @Override
        public View getDropDownView(int pos, View cv, ViewGroup parent) {
            TextView t = (TextView) super.getDropDownView(pos, cv, parent);
            t.setCompoundDrawables(null, null, null, null);
            t.setTextColor(Color.parseColor("#050505"));
            t.setTypeface(statusStyle ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            t.setTextSize(statusStyle ? 16 : 15);
            t.setPadding(dp(statusStyle ? 18 : 14), dp(statusStyle ? 13 : 10),
                    dp(14), dp(statusStyle ? 13 : 10));
            return t;
        }
    }
}
