package com.familybook.app;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Add Your Hobbies পেজ।
 *  - সার্চ বক্সে লিখলে assets/hobbies.json থেকে মিল থাকা হবি নিচে আসে; চাপলে "Your hobbies"-এ যোগ হয়
 *  - না লিখলে কোনো সাজেশন তালিকা দেখায় না
 *  - তালিকায় না থাকলে নিজের লেখা নামও যোগ করা যায়
 *  - Save → profiles.info.hobbies = ["Reading", ...]; সফল হলে RESULT_OK
 */
public class HobbiesPickActivity extends BaseActivity {

    private static final int MAX = 30;

    private final ArrayList<String> all = new ArrayList<>();
    private final ArrayList<String> selected = new ArrayList<>();
    private boolean saving = false;

    private EditText et;
    private LinearLayout llResults;
    private FlowLayout flSelected;
    private TextView tvEmpty, btnSave;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Me.isLoggedIn() || Me.uid() == null) { finish(); return; }
        setContentView(R.layout.activity_hobbies_pick);

        et = findViewById(R.id.etHbSearch);
        llResults = findViewById(R.id.llHbResults);
        flSelected = findViewById(R.id.flHbSelected);
        tvEmpty = findViewById(R.id.tvHbEmpty);
        btnSave = findViewById(R.id.btnHbSave);

        loadAll();
        findViewById(R.id.btnHbClose).setOnClickListener(v -> finish());
        btnSave.setOnClickListener(v -> save());
        et.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { showResults(s.toString().trim()); }
        });
        renderSelected();

        // আগের সেভ করা হবি
        Db.get("profiles", "id", Me.uid(), (row, err) -> {
            if (isFinishing()) return;
            JSONObject info = row != null ? row.optJSONObject("info") : null;
            JSONArray a = info != null ? info.optJSONArray("hobbies") : null;
            for (int i = 0; a != null && i < a.length(); i++) {
                String h = a.optString(i, "").trim();
                if (!h.isEmpty() && !has(h)) selected.add(h);
            }
            renderSelected();
        });
    }

    private void loadAll() {
        try (InputStream is = getAssets().open("hobbies.json")) {
            byte[] buf = new byte[is.available()];
            int n = is.read(buf);
            JSONArray a = new JSONObject(new String(buf, 0, n, "UTF-8")).optJSONArray("hobbies");
            for (int i = 0; a != null && i < a.length(); i++) all.add(a.getString(i));
        } catch (Exception ignored) { }
    }

    private boolean has(String h) {
        for (String s : selected) if (s.equalsIgnoreCase(h)) return true;
        return false;
    }

    // ---- সার্চ ফলাফল ----
    private void showResults(String q) {
        llResults.removeAllViews();
        if (q.isEmpty()) return;                       // না লিখলে কোনো সাজেশন নেই
        String low = q.toLowerCase(Locale.ROOT);
        int shown = 0;
        boolean exact = false;
        for (String h : all) {
            if (h.equalsIgnoreCase(q)) exact = true;
            if (!h.toLowerCase(Locale.ROOT).contains(low)) continue;
            if (shown >= 30) break;
            llResults.addView(resultRow(h, h, has(h)));
            shown++;
        }
        if (!exact && q.length() >= 2) llResults.addView(resultRow("Add \"" + q + "\"", q, has(q)));
        if (llResults.getChildCount() == 0) {
            TextView none = new TextView(this);
            none.setText("No results");
            none.setTextColor(0xFF65676B);
            none.setPadding(dp(4), dp(12), 0, 0);
            llResults.addView(none);
        }
    }

    /** নাম (বোল্ড) + ডানে ⊕ আইকন; যোগ হয়ে গেলে নীল ✓ */
    private View resultRow(String label, final String value, boolean done) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(12), dp(2), dp(12));

        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(17);
        t.setTextColor(0xFF050505);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        android.widget.ImageView ic = new android.widget.ImageView(this);
        ic.setImageResource(done ? R.drawable.ic_ct_check_circle : R.drawable.ic_ct_add_circle);
        row.addView(ic, new LinearLayout.LayoutParams(dp(28), dp(28)));

        row.setOnClickListener(v -> {
            if (has(value)) { TopToast.show(this, "Already added"); return; }
            if (selected.size() >= MAX) { TopToast.show(this, "You can add up to " + MAX + " hobbies"); return; }
            selected.add(value);
            et.setText("");                            // ফলাফল লুকায়
            renderSelected();
        });
        return row;
    }

    // ---- বেছে নেওয়া হবি (নীল চিপ, ✕ চাপলে সরে) ----
    private void renderSelected() {
        flSelected.removeAllViews();
        tvEmpty.setVisibility(selected.isEmpty() ? View.VISIBLE : View.GONE);
        for (final String h : selected) {
            LinearLayout chip = new LinearLayout(this);
            chip.setOrientation(LinearLayout.HORIZONTAL);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.parseColor("#E7F3FF"));
            bg.setCornerRadius(dp(18));
            chip.setBackground(bg);
            chip.setPadding(dp(12), dp(7), dp(6), dp(7));

            TextView t = new TextView(this);
            t.setText(h);
            t.setTextSize(14);
            t.setTextColor(Color.parseColor("#1877F2"));
            t.setTypeface(null, android.graphics.Typeface.BOLD);
            chip.addView(t);

            TextView x = new TextView(this);
            x.setText("✕");
            x.setTextSize(13);
            x.setTextColor(Color.parseColor("#1877F2"));
            x.setPadding(dp(8), 0, dp(6), 0);
            x.setOnClickListener(v -> { selected.remove(h); renderSelected(); showResults(et.getText().toString().trim()); });
            chip.addView(x);
            flSelected.addView(chip);
        }
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    // ---- সেভ ----
    private void save() {
        if (saving) return;
        saving = true;
        btnSave.setAlpha(0.6f);
        try {
            JSONArray a = new JSONArray();
            for (String h : selected) a.put(h);
            Db.mergeMyInfo(new JSONObject().put("hobbies", a), err -> {
                if (isFinishing()) return;
                saving = false;
                btnSave.setAlpha(1f);
                if (err != null) { TopToast.show(this, err); return; }
                TopToast.show(this, "Saved!");
                setResult(RESULT_OK);
                finish();
            });
        } catch (Exception e) {
            saving = false;
            btnSave.setAlpha(1f);
            TopToast.show(this, "Could not save");
        }
    }
}
