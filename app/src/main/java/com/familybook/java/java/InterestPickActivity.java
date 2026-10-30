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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Interests > Music / TV shows / Movies / Games / Sports — সার্চ বক্সসহ পিকার পেজ।
 *  - EXTRA_KIND (InterestKind.*) অনুযায়ী assets/interest_*.json লোড হয় (প্রতিটির আলাদা JSON)
 *  - TV shows ও Movies-এ Asia / Arab / USA / China ফিল্টার চিপ আছে
 *  - তালিকায় না থাকলে নিজের লেখা নামও যোগ করা যায়
 *  - Save → profiles.info.<key>; সফল হলে RESULT_OK
 */
public class InterestPickActivity extends BaseActivity {

    public static final String EXTRA_KIND = "kind";

    private static final int MAX_SHOWN = 40;

    private static final class Item {
        final String name, sub;
        final int year;
        Item(String n, String s, int y) { name = n; sub = s; year = y; }
    }

    private final ArrayList<Item> all = new ArrayList<>();
    private final ArrayList<String> selected = new ArrayList<>();
    private int kind = 0;
    private String region = "All";
    private boolean saving = false;

    private EditText et;
    private LinearLayout llResults, llRegions;
    private FlowLayout flSelected;
    private TextView tvEmpty, btnSave;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Me.isLoggedIn() || Me.uid() == null) { finish(); return; }
        kind = Math.max(0, Math.min(InterestKind.KEY.length - 1, getIntent().getIntExtra(EXTRA_KIND, 0)));
        setContentView(R.layout.activity_interest_pick);

        et = findViewById(R.id.etInSearch);
        llResults = findViewById(R.id.llInResults);
        llRegions = findViewById(R.id.llInRegions);
        flSelected = findViewById(R.id.flInSelected);
        tvEmpty = findViewById(R.id.tvInEmpty);
        btnSave = findViewById(R.id.btnInSave);

        ((TextView) findViewById(R.id.tvInTitle)).setText(InterestKind.TITLE[kind]);
        ((TextView) findViewById(R.id.tvInSelHead)).setText(InterestKind.SEL_HEAD[kind]);
        tvEmpty.setText(InterestKind.EMPTY[kind]);
        et.setHint(InterestKind.HINT[kind]);

        loadAll();
        buildRegionChips();

        findViewById(R.id.btnInClose).setOnClickListener(v -> finish());
        btnSave.setOnClickListener(v -> save());
        et.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { showResults(); }
        });
        renderSelected();

        // আগের সেভ করা তালিকা
        Db.get("profiles", "id", Me.uid(), (row, err) -> {
            if (isFinishing()) return;
            JSONObject info = row != null ? row.optJSONObject("info") : null;
            JSONArray a = info != null ? info.optJSONArray(InterestKind.KEY[kind]) : null;
            for (int i = 0; a != null && i < a.length(); i++) {
                String h = a.optString(i, "").trim();
                if (!h.isEmpty() && !has(h)) selected.add(h);
            }
            renderSelected();
        });
    }

    // ---- সংশ্লিষ্ট ক্যাটাগরির আলাদা JSON লোড ----
    private void loadAll() {
        try (InputStream is = getAssets().open(InterestKind.FILE[kind])) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            JSONArray a = new JSONObject(bos.toString("UTF-8")).optJSONArray(InterestKind.ARRAY[kind]);
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                String name = o.optString("n", "").trim();
                if (name.isEmpty()) continue;
                all.add(new Item(name, o.optString("s", ""), o.optInt("y", 0)));
            }
        } catch (Exception ignored) { }
    }

    private boolean has(String h) {
        for (String s : selected) if (s.equalsIgnoreCase(h)) return true;
        return false;
    }

    // ---- Asia / Arab / USA / China ফিল্টার ----
    private void buildRegionChips() {
        if (!InterestKind.hasRegions(kind)) { findViewById(R.id.scInRegions).setVisibility(View.GONE); return; }
        llRegions.removeAllViews();
        for (final String r : InterestKind.REGIONS) {
            TextView t = new TextView(this);
            t.setText(r);
            t.setTextSize(14);
            t.setTypeface(null, android.graphics.Typeface.BOLD);
            t.setPadding(dp(14), dp(7), dp(14), dp(7));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dp(8));
            t.setLayoutParams(lp);
            t.setTag(r);
            t.setOnClickListener(v -> { region = r; styleChips(); showResults(); });
            llRegions.addView(t);
        }
        styleChips();
    }

    private void styleChips() {
        for (int i = 0; i < llRegions.getChildCount(); i++) {
            TextView t = (TextView) llRegions.getChildAt(i);
            boolean on = region.equals(t.getTag());
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(18));
            bg.setColor(Color.parseColor(on ? "#1877F2" : "#E4E6EB"));
            t.setBackground(bg);
            t.setTextColor(Color.parseColor(on ? "#FFFFFF" : "#050505"));
        }
    }

    // ---- সার্চ ফলাফল ----
    private void showResults() {
        llResults.removeAllViews();
        String q = et.getText().toString().trim();
        boolean filtered = InterestKind.hasRegions(kind) && !"All".equals(region);
        if (q.isEmpty() && !filtered) return;           // না লিখলে ও ফিল্টার না থাকলে কোনো সাজেশন নেই

        String low = q.toLowerCase(Locale.ROOT);
        int shown = 0;
        boolean exact = false;
        for (Item it : all) {
            if (it.name.equalsIgnoreCase(q)) exact = true;
            if (filtered && !region.equalsIgnoreCase(it.sub)) continue;
            if (!low.isEmpty()
                    && !it.name.toLowerCase(Locale.ROOT).contains(low)
                    && !it.sub.toLowerCase(Locale.ROOT).contains(low)) continue;
            if (shown >= MAX_SHOWN) break;
            String sub = it.sub;
            if (it.year > 0) sub = sub.isEmpty() ? String.valueOf(it.year) : sub + " · " + it.year;
            llResults.addView(resultRow(it.name, sub, it.name, has(it.name)));
            shown++;
        }
        if (!exact && q.length() >= 2) llResults.addView(resultRow("Add \"" + q + "\"", "", q, has(q)));
        if (llResults.getChildCount() == 0) {
            TextView none = new TextView(this);
            none.setText("No results");
            none.setTextColor(0xFF65676B);
            none.setPadding(dp(4), dp(12), 0, 0);
            llResults.addView(none);
        }
    }

    /** নাম (বোল্ড) + নিচে ছোট সাবটাইটেল + ডানে ⊕ আইকন; যোগ হয়ে গেলে নীল ✓ */
    private View resultRow(String label, String sub, final String value, boolean done) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(10), dp(2), dp(10));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(17);
        t.setTextColor(0xFF050505);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        col.addView(t);

        if (sub != null && !sub.isEmpty()) {
            TextView s = new TextView(this);
            s.setText(sub);
            s.setTextSize(13);
            s.setTextColor(0xFF65676B);
            col.addView(s);
        }
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ImageView ic = new ImageView(this);
        ic.setImageResource(done ? R.drawable.ic_ct_check_circle : R.drawable.ic_ct_add_circle);
        row.addView(ic, new LinearLayout.LayoutParams(dp(28), dp(28)));

        row.setOnClickListener(v -> {
            if (has(value)) { TopToast.show(this, "Already added"); return; }
            if (selected.size() >= InterestKind.MAX) {
                TopToast.show(this, "You can add up to " + InterestKind.MAX + " items");
                return;
            }
            selected.add(value);
            et.setText("");                              // ফলাফল লুকায় (ফিল্টার থাকলে তালিকা থাকে)
            renderSelected();
            showResults();
        });
        return row;
    }

    // ---- বেছে নেওয়া আইটেম (নীল চিপ, ✕ চাপলে সরে) ----
    private void renderSelected() {
        flSelected.removeAllViews();
        tvEmpty.setVisibility(selected.isEmpty() ? View.VISIBLE : View.GONE);
        for (final String h : new ArrayList<>(selected)) {
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
            x.setOnClickListener(v -> { selected.remove(h); renderSelected(); showResults(); });
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
            Db.mergeMyInfo(new JSONObject().put(InterestKind.KEY[kind], a), err -> {
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
