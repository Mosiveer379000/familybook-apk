package com.familybook.app;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * Category পেজ (২নং ছবি)।
 * - সার্চ বক্সে চাপলে/লিখলে assets/categories.json-এর সব নাম ড্রপডাউনে আসে
 * - সর্বোচ্চ ৩টা ক্যাটাগরি, নীল চিপে দেখায় (x দিয়ে বাদ)
 * - নিচে Popular categories: সার্ভারে ইউজাররা যেগুলো বেশি নিয়েছে (Public), কম থাকলে ডিফল্ট দিয়ে ভরে
 * - Save → Supabase profile_categories-এ সেভ করে আগের পেজে ফিরে যায়
 */
public class CategoryEditActivity extends BaseActivity {

    private static final int MAX = 3;
    private static final int POPULAR_COUNT = 15;

    private final ArrayList<String> selected = new ArrayList<>();
    private final ArrayList<String> allNames = new ArrayList<>();
    private final ArrayList<String> defaults = new ArrayList<>();
    private final ArrayList<String> popular = new ArrayList<>();

    private View root;
    private FlowLayout flowSelected, flowPopular;
    private EditText etSearch;
    private ScrollView dropScroll;
    private LinearLayout dropList;
    private boolean saving = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Me.isLoggedIn() || Me.uid() == null) {
            finish();
            return;
        }
        setContentView(R.layout.activity_category_edit);

        root = findViewById(R.id.rootCat);
        flowSelected = findViewById(R.id.flowSelected);
        flowPopular = findViewById(R.id.flowPopular);
        etSearch = findViewById(R.id.etSearchCat);
        dropScroll = findViewById(R.id.dropScroll);
        dropList = findViewById(R.id.dropList);

        // বক্সের গ্রে বর্ডার (গোল কোণ)
        GradientDrawable box = new GradientDrawable();
        box.setColor(Color.WHITE);
        box.setCornerRadius(dp(14));
        box.setStroke(dp(2), Color.parseColor("#CED0D4"));
        findViewById(R.id.boxCat).setBackground(box);

        loadJson();

        String[] old = getIntent().getStringArrayExtra("categories");
        if (old != null) {
            for (String s : old) {
                if (s != null && !s.trim().isEmpty() && !containsIgnoreCase(selected, s)
                        && selected.size() < MAX) selected.add(s);
            }
        }

        findViewById(R.id.btnBackCat).setOnClickListener(v -> finish());
        findViewById(R.id.btnSaveCat).setOnClickListener(v -> save());

        // ড্রপডাউনের ভেতরে স্ক্রল করলে বাইরের পেজ স্ক্রল হবে না
        dropScroll.setOnTouchListener((v, e) -> {
            v.getParent().requestDisallowInterceptTouchEvent(true);
            return false;
        });

        etSearch.setOnFocusChangeListener((v, has) -> {
            if (has) refreshDropdown();
        });
        etSearch.setOnClickListener(v -> refreshDropdown());
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                if (etSearch.hasFocus()) refreshDropdown();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        renderSelected();
        rebuildPopular(new ArrayList<>());
        loadPopular();

        // শুরুতে কিবোর্ড/ড্রপডাউন খুলবে না
        root.requestFocus();
    }

    // ---------- JSON ----------
    private void loadJson() {
        TreeSet<String> set = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        try (InputStream is = getAssets().open("categories.json")) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            JSONObject j = new JSONObject(bo.toString("UTF-8"));
            JSONArray p = j.optJSONArray("professions");
            if (p != null) for (int i = 0; i < p.length(); i++) set.add(p.optString(i));
            JSONArray d = j.optJSONArray("popular_default");
            if (d != null) {
                for (int i = 0; i < d.length(); i++) {
                    String s = d.optString(i);
                    if (!s.isEmpty()) {
                        defaults.add(s);
                        set.add(s);
                    }
                }
            }
        } catch (Exception e) {
            toast("ক্যাটাগরি লিস্ট লোড হয়নি");
        }
        set.remove("");
        allNames.addAll(set);
    }

    // ---------- Popular (Public, ইউজাররা যেটা বেশি নিয়েছে) ----------
    private void loadPopular() {
        SupabaseRest.call("POST", "/rest/v1/rpc/popular_categories",
                "{\"max_count\":" + POPULAR_COUNT + "}", null, true, r -> {
            if (isFinishing() || !r.ok()) return;
            ArrayList<String> server = new ArrayList<>();
            try {
                JSONArray a = new JSONArray(r.body);
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o != null) {
                        String n = o.optString("name", "");
                        if (!n.isEmpty()) server.add(n);
                    }
                }
            } catch (Exception ignored) { }
            rebuildPopular(server);
        });
    }

    private void rebuildPopular(List<String> server) {
        popular.clear();
        for (String s : server) {
            if (popular.size() < POPULAR_COUNT && !containsIgnoreCase(popular, s)) popular.add(s);
        }
        for (String s : defaults) {
            if (popular.size() < POPULAR_COUNT && !containsIgnoreCase(popular, s)) popular.add(s);
        }
        renderPopular();
    }

    // ---------- নির্বাচিত চিপ ----------
    private void renderSelected() {
        flowSelected.removeAllViews();
        flowSelected.setVisibility(selected.isEmpty() ? View.GONE : View.VISIBLE);
        for (final String name : new ArrayList<>(selected)) {
            LinearLayout chip = new LinearLayout(this);
            chip.setOrientation(LinearLayout.HORIZONTAL);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setPadding(dp(16), dp(7), dp(9), dp(7));
            chip.setBackground(rounded(Color.parseColor("#DDEBFC"), 40));

            TextView t = new TextView(this);
            t.setText(name);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            t.setTextColor(Color.parseColor("#0866FF"));
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            chip.addView(t);

            TextView x = new TextView(this);
            x.setText("✕");
            x.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            x.setTextColor(Color.WHITE);
            x.setGravity(Gravity.CENTER);
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(Color.parseColor("#0866FF"));
            x.setBackground(circle);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(22), dp(22));
            lp.setMarginStart(dp(10));
            chip.addView(x, lp);

            chip.setOnClickListener(v -> removeCategory(name));
            flowSelected.addView(chip);
        }
    }

    // ---------- Popular চিপ ----------
    private void renderPopular() {
        flowPopular.removeAllViews();
        for (final String name : popular) {
            boolean on = containsIgnoreCase(selected, name);
            TextView t = new TextView(this);
            t.setText(name);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            t.setPadding(dp(16), dp(10), dp(16), dp(10));
            t.setTextColor(on ? Color.parseColor("#0866FF") : Color.parseColor("#050505"));
            t.setBackground(rounded(on ? Color.parseColor("#DDEBFC") : Color.parseColor("#E4E6EB"), 40));
            t.setOnClickListener(v -> {
                closeDropdown();
                if (containsIgnoreCase(selected, name)) removeCategory(name);
                else addCategory(name);
            });
            flowPopular.addView(t);
        }
    }

    // ---------- ড্রপডাউন ----------
    private void refreshDropdown() {
        String q = etSearch.getText().toString().trim().toLowerCase(Locale.ROOT);
        ArrayList<String> starts = new ArrayList<>();
        ArrayList<String> contains = new ArrayList<>();
        for (String n : allNames) {
            if (containsIgnoreCase(selected, n)) continue;
            String l = n.toLowerCase(Locale.ROOT);
            if (q.isEmpty() || l.startsWith(q)) starts.add(n);
            else if (l.contains(q)) contains.add(n);
        }
        starts.addAll(contains);

        dropList.removeAllViews();
        if (starts.isEmpty()) {
            TextView none = new TextView(this);
            none.setText("কিছু পাওয়া যায়নি");
            none.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            none.setTextColor(Color.parseColor("#65676B"));
            none.setPadding(dp(12), dp(14), dp(12), dp(14));
            dropList.addView(none);
        } else {
            for (final String n : starts) {
                TextView t = new TextView(this);
                t.setText(n);
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                t.setTextColor(Color.parseColor("#050505"));
                t.setPadding(dp(12), dp(13), dp(12), dp(13));
                TypedValue tv = new TypedValue();
                getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
                t.setBackgroundResource(tv.resourceId);
                t.setOnClickListener(v -> {
                    if (addCategory(n)) closeDropdown();
                });
                dropList.addView(t);
            }
        }
        ViewGroup.LayoutParams lp = dropScroll.getLayoutParams();
        lp.height = starts.size() > 6 ? dp(264) : ViewGroup.LayoutParams.WRAP_CONTENT;
        dropScroll.setLayoutParams(lp);
        dropScroll.scrollTo(0, 0);
        dropScroll.setVisibility(View.VISIBLE);
    }

    private void closeDropdown() {
        dropScroll.setVisibility(View.GONE);
        etSearch.clearFocus();
        root.requestFocus();
        etSearch.setText("");
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(etSearch.getWindowToken(), 0);
    }

    // ---------- নির্বাচন ----------
    private boolean addCategory(String name) {
        if (containsIgnoreCase(selected, name)) return true;
        if (selected.size() >= MAX) {
            toast("সর্বোচ্চ " + MAX + "টা ক্যাটাগরি নেওয়া যাবে");
            return false;
        }
        selected.add(name);
        renderSelected();
        renderPopular();
        return true;
    }

    private void removeCategory(String name) {
        for (int i = 0; i < selected.size(); i++) {
            if (selected.get(i).equalsIgnoreCase(name)) {
                selected.remove(i);
                break;
            }
        }
        renderSelected();
        renderPopular();
        if (dropScroll.getVisibility() == View.VISIBLE) refreshDropdown();
    }

    // ---------- Supabase-এ সেভ ----------
    private void save() {
        if (saving) return;
        if (!Net.check(this)) return;
        closeDropdown();

        final ArrayList<String> out = new ArrayList<>(selected);
        saving = true;
        try {
            JSONArray arr = new JSONArray();
            for (String s : out) arr.put(s);
            JSONObject f = new JSONObject()
                    .put("user_id", Me.uid())
                    .put("categories", arr)
                    .put("privacy", "public");
            SupabaseRest.call("POST", "/rest/v1/profile_categories?on_conflict=user_id", f.toString(),
                    "resolution=merge-duplicates,return=representation", true, r -> {
                saving = false;
                if (isFinishing()) return;
                if (!r.ok()) {
                    toast(SupabaseRest.errorText(r));
                    return;
                }
                JSONArray a = null;
                try { a = new JSONArray(r.body); } catch (Exception ignored) { }
                if (a == null || a.length() == 0) {
                    toast("সেভ হয়নি, আবার চেষ্টা করুন");
                    return;
                }
                setResult(RESULT_OK, new Intent()
                        .putExtra("categories", out.toArray(new String[0])));
                finish();
            });
        } catch (Exception e) {
            saving = false;
            toast("সেভ হয়নি");
        }
    }

    // ---------- helpers ----------
    private static boolean containsIgnoreCase(List<String> list, String s) {
        for (String x : list) if (x.equalsIgnoreCase(s)) return true;
        return false;
    }

    private GradientDrawable rounded(int fill, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
