package com.familybook.app;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * Work / College / High school ফর্ম (একই পেজ, mode অনুযায়ী ফিল্ড বদলায়)।
 *  WORK    : Workplace*, Job title*, Currently work here, Start/End date, Location, Description
 *  COLLEGE : College name*, Start/End date, Graduated, Description
 *  SCHOOL  : High school name*, Graduation year
 *  সবগুলোতে: Who can see this? (Public / Friends / Only me) + Save (* ফাঁকা থাকলে ধূসর)
 *
 * Intent extras: mode (0/1/2), index (এডিটে এন্ট্রির ক্রম; নতুনে -1), json (এডিটে আগের এন্ট্রি)
 * Save → info.work_list / info.education_list; সফল হলে RESULT_OK
 */
public class CareerFormActivity extends BaseActivity {

    public static final String EXTRA_MODE = "mode", EXTRA_INDEX = "index", EXTRA_JSON = "json";
    public static final int WORK = 0, COLLEGE = 1, SCHOOL = 2;

    private int mode = WORK, index = -1, gradYear = 0;
    private String priv = ContactStore.PUBLIC;
    private boolean saving = false;

    private EditText et1, et2, etLoc, etDesc;
    private CheckBox cbCur, cbGrad;
    private DateTriple start, end;
    private TextView btnSave, tvPriv, tvGy, tvGyLabel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Me.isLoggedIn() || Me.uid() == null) { finish(); return; }
        setContentView(R.layout.activity_career_form);

        mode = Math.max(0, Math.min(2, getIntent().getIntExtra(EXTRA_MODE, WORK)));
        index = getIntent().getIntExtra(EXTRA_INDEX, -1);

        et1 = findViewById(R.id.etCr1);
        et2 = findViewById(R.id.etCr2);
        etLoc = findViewById(R.id.etCrLoc);
        etDesc = findViewById(R.id.etCrDesc);
        cbCur = findViewById(R.id.cbCrCurrent);
        cbGrad = findViewById(R.id.cbCrGrad);
        btnSave = findViewById(R.id.btnCrSave);
        tvPriv = findViewById(R.id.tvCrPriv);
        tvGy = findViewById(R.id.tvCrGy);
        tvGyLabel = findViewById(R.id.tvCrGyLabel);

        start = new DateTriple(this, findViewById(R.id.llCrStart), false, null);
        end = new DateTriple(this, findViewById(R.id.llCrEnd), true, null);

        // ---- মোড অনুযায়ী ফিল্ড ----
        boolean work = mode == WORK, college = mode == COLLEGE, school = mode == SCHOOL;
        ((TextView) findViewById(R.id.tvCrTitle)).setText(work ? "Work" : college ? "College" : "High school");
        et1.setHint(work ? "Workplace" : college ? "College name" : "High school name");
        et2.setHint("Job title");
        show(R.id.grpCrTitle, work);
        show(R.id.grpCrCurrent, work);
        show(R.id.grpCrDates, !school);
        show(R.id.grpCrGrad, college);
        show(R.id.boxCrGradYear, school);
        show(R.id.grpCrLoc, work);
        show(R.id.grpCrDesc, !school);
        if (college) cbGrad.setChecked(true);                  // ডিফল্ট: Graduated ✔

        floatLabel(et1, (TextView) findViewById(R.id.tvCrL1), et1.getHint().toString());
        floatLabel(et2, (TextView) findViewById(R.id.tvCrL2), "Job title");
        floatLabel(etLoc, (TextView) findViewById(R.id.tvCrLLoc), "Location");
        floatLabel(etDesc, (TextView) findViewById(R.id.tvCrLDesc), "Description");

        TextWatcher tw = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { updateSave(); }
        };
        et1.addTextChangedListener(tw);
        et2.addTextChangedListener(tw);

        // Currently work here → End date ধূসর
        cbCur.setOnCheckedChangeListener((b, on) -> {
            if (on) end.set(0, 0, 0);
            end.setEnabled(!on);
        });

        findViewById(R.id.boxCrGradYear).setOnClickListener(v ->
                DateTriple.pickYear(this, "Graduation year", gradYear, true, yr -> { gradYear = yr; showGy(); }));

        findViewById(R.id.btnCrClose).setOnClickListener(v -> finish());
        findViewById(R.id.rowCrPriv).setOnClickListener(v ->
                ContactStore.choose(this, priv, p -> { priv = p; showPriv(); }));
        btnSave.setOnClickListener(v -> save());

        prefill();
        showPriv();
        showGy();
        updateSave();
    }

    private void show(int id, boolean on) { findViewById(id).setVisibility(on ? View.VISIBLE : View.GONE); }

    /** লেখা থাকলে ছোট লেবেল ওপরে ভাসে; ফাঁকা হলে হিন্ট দেখায় */
    private void floatLabel(EditText et, TextView label, String text) {
        label.setText(text);
        et.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                label.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
            }
        });
    }

    private void prefill() {
        String js = getIntent().getStringExtra(EXTRA_JSON);
        if (js == null) return;
        try {
            JSONObject e = new JSONObject(js);
            et1.setText(e.optString("name", ""));
            et2.setText(e.optString("title", ""));
            etLoc.setText(e.optString("loc", ""));
            etDesc.setText(e.optString("desc", ""));
            start.set(e.optInt("sy"), e.optInt("sm"), e.optInt("sd"));
            end.set(e.optInt("ey"), e.optInt("em"), e.optInt("ed"));
            if (mode == WORK) {
                cbCur.setChecked(e.optBoolean("current", false));
            }
            if (mode == COLLEGE) cbGrad.setChecked(e.optBoolean("grad", false));
            gradYear = e.optInt("gy", 0);
            priv = ContactStore.norm(e.optString("priv", ""), ContactStore.PUBLIC);
        } catch (Exception ignored) { }
    }

    private void showPriv() { tvPriv.setText(ContactStore.label(priv)); }

    private void showGy() {
        tvGy.setText(gradYear > 0 ? String.valueOf(gradYear) : "Graduation year");
        tvGy.setTextColor(Color.parseColor(gradYear > 0 ? "#050505" : "#65676B"));
        tvGyLabel.setVisibility(gradYear > 0 ? View.VISIBLE : View.GONE);
    }

    // ---- Save বাটন: * ফাঁকা থাকলে ধূসর ও বন্ধ ----
    private boolean valid() {
        if (et1.getText().toString().trim().isEmpty()) return false;
        return mode != WORK || !et2.getText().toString().trim().isEmpty();
    }

    private void updateSave() {
        boolean ok = valid() && !saving;
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(10));
        g.setColor(Color.parseColor(ok ? "#1877F2" : "#E4E6EB"));
        btnSave.setBackground(g);
        btnSave.setTextColor(Color.parseColor(ok ? "#FFFFFF" : "#BCC0C4"));
        btnSave.setEnabled(ok);
    }

    private static int cmp(int a, int b) { return Integer.compare(a, b); }

    /** শেষ তারিখ শুরুর আগে কি না (যতটুকু তথ্য দুটোতেই আছে ততটুকু তুলনা) */
    private boolean endBeforeStart() {
        if (start.y == 0 || end.y == 0) return false;
        int c = cmp(end.y, start.y);
        if (c != 0) return c < 0;
        if (start.m == 0 || end.m == 0) return false;
        c = cmp(end.m, start.m);
        if (c != 0) return c < 0;
        if (start.d == 0 || end.d == 0) return false;
        return end.d < start.d;
    }

    private void save() {
        if (saving || !valid()) return;
        if (mode != SCHOOL && endBeforeStart()) {
            TopToast.show(this, "End date can't be before start date");
            return;
        }
        try {
            JSONObject e = new JSONObject();
            e.put("name", et1.getText().toString().trim());
            if (mode == WORK) {
                boolean cur = cbCur.isChecked();
                e.put("title", et2.getText().toString().trim());
                e.put("current", cur);
                putDates(e, cur);
                e.put("loc", etLoc.getText().toString().trim());
                e.put("desc", etDesc.getText().toString().trim());
            } else if (mode == COLLEGE) {
                e.put("type", "college");
                putDates(e, false);
                e.put("grad", cbGrad.isChecked());
                e.put("desc", etDesc.getText().toString().trim());
            } else {
                e.put("type", "school");
                e.put("gy", gradYear);
            }
            e.put("priv", priv);

            saving = true;
            updateSave();
            String key = mode == WORK ? CareerStore.WORK : CareerStore.EDU;
            CareerStore.upsert(key, index, e, err -> {
                if (isFinishing()) return;
                saving = false;
                updateSave();
                if (err != null) { TopToast.show(this, err); return; }
                TopToast.show(this, "Saved!");
                setResult(RESULT_OK);
                finish();
            });
        } catch (Exception ex) {
            saving = false;
            updateSave();
            TopToast.show(this, "Could not save");
        }
    }

    private void putDates(JSONObject e, boolean noEnd) throws Exception {
        e.put("sy", start.y).put("sm", start.m).put("sd", start.d);
        e.put("ey", noEnd ? 0 : end.y).put("em", noEnd ? 0 : end.m).put("ed", noEnd ? 0 : end.d);
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
