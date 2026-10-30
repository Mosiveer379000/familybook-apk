package com.familybook.app;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * Feeling / Activity picker (XML লেআউট ছাড়াই, কোড দিয়ে তৈরি)।
 * ফলাফল: EXTRA_KIND = "feeling" | "activity", EXTRA_TEXT = "😊 happy" ইত্যাদি
 */
public class FeelingActivity extends AppCompatActivity {

    public static final String EXTRA_KIND = "kind";
    public static final String EXTRA_TEXT = "text";

    private static final int BLUE = Color.parseColor("#1877F2");
    private static final int PILL = Color.parseColor("#E7F3FF");

    private static final String[][] FEELINGS = {
            {"😊", "happy"}, {"😇", "blessed"}, {"🥰", "loved"}, {"😢", "sad"},
            {"😍", "lovely"}, {"😃", "thankful"}, {"🤩", "excited"}, {"😘", "in love"},
            {"🤪", "crazy"}, {"😲", "grateful"}, {"😊", "blissful"}, {"🤗", "fantastic"},
            {"😜", "silly"}, {"🎄", "festive"}, {"🙂", "wonderful"}, {"😎", "cool"},
            {"😄", "amused"}, {"😌", "relaxed"}, {"😏", "positive"}, {"😌", "chill"},
            {"🌷", "hopeful"}, {"😊", "joyful"}
    };

    // "..." এর জায়গায় ইউজার নিজে কিছু লিখবে (যেমন Eating pizza)
    private static final String[][] ACTIVITIES = {
            {"🎉", "Celebrating"}, {"👓", "Watching"},
            {"🍩", "Eating"}, {"🥤", "Drinking"},
            {"✈️", "Traveling to"}, {"🎧", "Listening to"},
            {"🔍", "Looking for"}, {"📖", "Reading"},
            {"🎮", "Playing"}, {"🤲", "Supporting"}
    };

    private boolean showFeelings = true;
    private String query = "";

    private TextView tvTitle, tabFeel, tabAct;
    private GridView grid;
    private ItemAdapter adapter;
    private final List<String[]> shown = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        refresh();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        // ---- হেডার ----
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(8), dp(12), dp(8), dp(12));

        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextSize(34);
        back.setTextColor(Color.BLACK);
        back.setGravity(Gravity.CENTER);
        back.setPadding(dp(14), 0, dp(14), dp(4));
        back.setOnClickListener(v -> finish());
        header.addView(back);

        tvTitle = new TextView(this);
        tvTitle.setTextSize(20);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvTitle.setTextColor(Color.BLACK);
        tvTitle.setGravity(Gravity.CENTER);
        header.addView(tvTitle, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // ব্যালেন্সের জন্য ফাঁকা জায়গা
        header.addView(new View(this), new LinearLayout.LayoutParams(dp(54), 1));
        root.addView(header);

        // ---- ট্যাব ----
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(dp(12), dp(8), dp(12), dp(8));

        tabFeel = makeTab("Feelings");
        tabAct = makeTab("Activities");
        tabFeel.setOnClickListener(v -> { showFeelings = true; refresh(); });
        tabAct.setOnClickListener(v -> { showFeelings = false; refresh(); });
        tabs.addView(tabFeel);
        tabs.addView(tabAct);
        root.addView(tabs);

        // ---- সার্চ ----
        EditText search = new EditText(this);
        search.setHint("🔍  Search");
        search.setSingleLine(true);
        search.setBackground(null);
        search.setTextSize(17);
        search.setPadding(dp(18), dp(12), dp(18), dp(12));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                query = s.toString().trim().toLowerCase();
                refresh();
            }
        });
        root.addView(search);

        // ---- গ্রিড (২ কলাম) ----
        grid = new GridView(this);
        grid.setNumColumns(2);
        grid.setHorizontalSpacing(0);
        grid.setVerticalSpacing(0);
        adapter = new ItemAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, pos, id) -> onPick(shown.get(pos)));
        root.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private TextView makeTab(String label) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(17);
        t.setPadding(dp(22), dp(10), dp(22), dp(10));
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private void styleTab(TextView t, boolean on) {
        t.setTextColor(on ? BLUE : Color.BLACK);
        t.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(30));
        bg.setColor(on ? PILL : Color.TRANSPARENT);
        t.setBackground(bg);
    }

    private void refresh() {
        tvTitle.setText(showFeelings ? "How are you feeling?" : "What are you doing?");
        styleTab(tabFeel, showFeelings);
        styleTab(tabAct, !showFeelings);

        shown.clear();
        String[][] src = showFeelings ? FEELINGS : ACTIVITIES;
        for (String[] item : src) {
            if (query.isEmpty() || item[1].toLowerCase().contains(query)) shown.add(item);
        }
        adapter.notifyDataSetChanged();
    }

    private void onPick(String[] item) {
        if (showFeelings) {
            finishWith("feeling", item[0] + " " + item[1]);
            return;
        }
        // Activity: "Eating..." এর পরে কী তা জিজ্ঞেস করা
        final EditText et = new EditText(this);
        et.setHint(item[1] + "...");
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setSingleLine(true);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(et, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle(item[0] + " " + item[1] + "...")
                .setView(box)
                .setPositiveButton("OK", (d, w) -> {
                    String detail = et.getText().toString().trim();
                    String text = item[0] + " " + item[1] + (detail.isEmpty() ? "" : " " + detail);
                    finishWith("activity", text);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void finishWith(String kind, String text) {
        Intent d = new Intent();
        d.putExtra(EXTRA_KIND, kind);
        d.putExtra(EXTRA_TEXT, text);
        setResult(RESULT_OK, d);
        finish();
    }

    // ---------------- Adapter ----------------
    private class ItemAdapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int i) { return shown.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            LinearLayout row = new LinearLayout(FeelingActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(16), dp(8), dp(16));

            GradientDrawable border = new GradientDrawable();
            border.setColor(Color.WHITE);
            border.setStroke(1, Color.parseColor("#DDDDDD"));
            row.setBackground(border);

            String[] item = shown.get(pos);

            TextView emoji = new TextView(FeelingActivity.this);
            emoji.setText(item[0]);
            emoji.setTextSize(30);
            row.addView(emoji);

            TextView label = new TextView(FeelingActivity.this);
            label.setText(showFeelings ? item[1] : item[1] + "...");
            label.setTextSize(18);
            label.setTextColor(Color.BLACK);
            label.setPadding(dp(14), 0, 0, 0);
            row.addView(label);
            return row;
        }
    }
}
