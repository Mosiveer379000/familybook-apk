package com.familybook.app;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Tag people: যে কাউকে (একাধিক) — সার্চ সার্ভারে হয়
 * Invite collaborator: শুধু friend = দুজন দুজনকে ফলো করে (একজন)
 */
public class TagCollabActivity extends AppCompatActivity {

    public static final String EXTRA_TAG_IDS = "tag_ids";
    public static final String EXTRA_TAG_NAMES = "tag_names";
    public static final String EXTRA_COLLAB_ID = "collab_id";
    public static final String EXTRA_COLLAB_NAME = "collab_name";
    /** গ্রুপ পোস্ট: এই দুটো দিলে শুধু এই সদস্যদেরই ট্যাগ/কোলাব করা যাবে (নিজের ফলোয়ার/ফ্রেন্ড না) */
    public static final String EXTRA_MEMBER_IDS = "member_ids";
    public static final String EXTRA_MEMBER_NAMES = "member_names";

    private static final String TAG = "TagCollabActivity";
    private static final int TAB_TAG = 0;
    private static final int TAB_COLLAB = 1;

    static final class Person {
        final String id;
        final String name;
        final boolean mutual;
        final String relation; // friend | following | follower | other
        Person(String id, String name, boolean mutual, String relation) {
            this.id = id;
            this.name = name;
            this.mutual = mutual;
            this.relation = relation;
        }
    }

    private ArrayList<String> memberIds = null;     // null = সাধারণ মোড
    private ArrayList<String> memberNames = null;
    private final List<Person> all = new ArrayList<>();
    private final List<Person> shown = new ArrayList<>();
    private final ArrayList<String> tagIds = new ArrayList<>();
    private final ArrayList<String> tagNames = new ArrayList<>();
    private String collabId = null;
    private String collabName = null;
    private int tab = TAB_TAG;
    private float density = 1f;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable searchTask;
    private int requestSeq = 0;

    private TextView tabTag, tabCollab, tvHint, btnDone;
    private EditText etSearch;
    private RecyclerView rvPeople;
    private PeopleAdapter adapter;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_tag_collab);
        density = getResources().getDisplayMetrics().density;

        tabTag = findViewById(R.id.tabTag);
        tabCollab = findViewById(R.id.tabCollab);
        tvHint = findViewById(R.id.tvHint);
        etSearch = findViewById(R.id.etSearch);
        rvPeople = findViewById(R.id.rvPeople);
        btnDone = findViewById(R.id.btnDone);

        memberIds = getIntent().getStringArrayListExtra(EXTRA_MEMBER_IDS);
        memberNames = getIntent().getStringArrayListExtra(EXTRA_MEMBER_NAMES);
        if (memberIds != null && memberNames != null && memberIds.size() != memberNames.size()) memberIds = null;

        ArrayList<String> ids = getIntent().getStringArrayListExtra(EXTRA_TAG_IDS);
        ArrayList<String> names = getIntent().getStringArrayListExtra(EXTRA_TAG_NAMES);
        if (ids != null) tagIds.addAll(ids);
        if (names != null) tagNames.addAll(names);
        collabId = getIntent().getStringExtra(EXTRA_COLLAB_ID);
        collabName = getIntent().getStringExtra(EXTRA_COLLAB_NAME);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        tabTag.setOnClickListener(v -> switchTab(TAB_TAG));
        tabCollab.setOnClickListener(v -> switchTab(TAB_COLLAB));
        btnDone.setOnClickListener(v -> finishWithResult());

        rvPeople.setLayoutManager(new LinearLayoutManager(this));
        adapter = new PeopleAdapter();
        rvPeople.setAdapter(adapter);

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int c, int d) { }
            @Override public void onTextChanged(CharSequence s, int a, int before, int count) { scheduleSearch(); }
            @Override public void afterTextChanged(Editable s) { }
        });

        switchTab(TAB_TAG);   // এর ভেতরেই লিস্ট লোড হয়
        updateDoneText();
    }

    // টাইপ থামার ০.৩ সেকেন্ড পর সার্ভারে সার্চ
    private void scheduleSearch() {
        if (searchTask != null) handler.removeCallbacks(searchTask);
        searchTask = this::loadCandidates;
        handler.postDelayed(searchTask, 300);
    }

    @Override
    protected void onDestroy() {
        if (searchTask != null) handler.removeCallbacks(searchTask);
        super.onDestroy();
    }

    // ---------------------------------------------------------
    // Supabase RPC: get_tag_candidates
    // ---------------------------------------------------------
    private void loadCandidates() {
        if (memberIds != null) {                 // গ্রুপ মোড: সার্ভার নয়, শুধু গ্রুপ সদস্যদের তালিকা
            String q = etSearch.getText().toString().trim().toLowerCase(Locale.ROOT);
            all.clear();
            for (int i = 0; i < memberIds.size(); i++) {
                String name = memberNames.get(i);
                if (name == null || name.isEmpty()) name = "Member";
                if (!q.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(q)) continue;
                all.add(new Person(memberIds.get(i), name, false, "member"));
            }
            applyFilter();
            return;
        }
        final int seq = ++requestSeq;
        JSONObject req = new JSONObject();
        try {
            String q = etSearch.getText().toString().trim();
            req.put("p_query", q.isEmpty() ? JSONObject.NULL : q);
            req.put("p_only_mutual", tab == TAB_COLLAB);   // কোলাব = শুধু friend
        } catch (Exception ignored) { }
        SupabaseRest.call("POST", "/rest/v1/rpc/get_tag_candidates", req.toString(), null, true, r -> {
            if (isFinishing() || isDestroyed()) return;
            if (seq != requestSeq) return;   // পুরনো রেসপন্স বাদ
            if (!r.ok()) {
                Log.w(TAG, "candidates failed: " + r.code + " " + r.body);
                Toast.makeText(this, "লোড হয়নি: " + SupabaseRest.errorText(r), Toast.LENGTH_LONG).show();
                return;
            }
            try {
                JSONArray arr = new JSONArray(r.body);
                all.clear();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    String id = o.optString("user_id", "");
                    String name = o.optString("full_name", "");
                    if (id.isEmpty()) continue;
                    if (name.isEmpty() || "null".equals(name)) name = "Unknown";
                    all.add(new Person(id, name, o.optBoolean("is_mutual", false),
                            o.optString("relation", "other")));
                }
                applyFilter();
            } catch (Exception e) {
                Log.e(TAG, "candidates parse error", e);
            }
        });
    }

    private void switchTab(int t) {
        tab = t;
        styleTab(tabTag, t == TAB_TAG);
        styleTab(tabCollab, t == TAB_COLLAB);
        etSearch.setHint(t == TAB_TAG ? "Who are you with?" : "Search collaborator");
        if (memberIds != null) {
            tvHint.setText("Only members of this group can be tagged or invited as collaborator.");
            loadCandidates();
            return;
        }
        tvHint.setText(t == TAB_TAG
                ? "Anyone can be tagged. Their name shows on the post now; it is added to their profile only if they approve."
                : "Only friends (you follow each other), one person. Their name shows on the post now; it is added to their profile only if they approve.");
        loadCandidates();
    }

    private void styleTab(TextView tv, boolean active) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(40 * density);
        g.setColor(active ? 0xFFE7F3FF : Color.TRANSPARENT);
        tv.setBackground(g);
        tv.setTextColor(active ? 0xFF1877F2 : 0xFF000000);
        tv.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
    }

    private void applyFilter() {
        // ফিল্টার ও সার্চ সার্ভারে হয়ে আসে; এখানে শুধু দেখানো
        shown.clear();
        shown.addAll(all);
        adapter.notifyDataSetChanged();
    }

    private boolean isSelected(Person p) {
        return tab == TAB_TAG ? tagIds.contains(p.id) : p.id.equals(collabId);
    }

    private void toggle(Person p) {
        if (tab == TAB_TAG) {
            int i = tagIds.indexOf(p.id);
            if (i >= 0) {
                tagIds.remove(i);
                tagNames.remove(i);
            } else {
                tagIds.add(p.id);
                tagNames.add(p.name);
            }
        } else {
            if (p.id.equals(collabId)) {
                collabId = null;
                collabName = null;
            } else {
                collabId = p.id;      // একটাই কোলাবরেটর
                collabName = p.name;
            }
        }
        adapter.notifyDataSetChanged();
        updateDoneText();
    }

    private void updateDoneText() {
        int n = tagIds.size() + (collabId != null ? 1 : 0);
        btnDone.setText(n > 0 ? "Done (" + n + ")" : "Done");
    }

    private void finishWithResult() {
        Intent d = new Intent();
        d.putStringArrayListExtra(EXTRA_TAG_IDS, tagIds);
        d.putStringArrayListExtra(EXTRA_TAG_NAMES, tagNames);
        if (collabId != null) {
            d.putExtra(EXTRA_COLLAB_ID, collabId);
            d.putExtra(EXTRA_COLLAB_NAME, collabName);
        }
        setResult(RESULT_OK, d);
        finish();
    }

    private static String cap(String t) {
        if (t == null || t.isEmpty()) return "";
        return Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    private int dp(int v) {
        return Math.round(v * density);
    }

    // ---------------------------------------------------------
    // List adapter (row কোডে বানানো)
    // ---------------------------------------------------------
    private class PeopleAdapter extends RecyclerView.Adapter<VH> {

        @Override
        public VH onCreateViewHolder(ViewGroup parent, int viewType) {
            Context ctx = parent.getContext();

            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int pad = dp(12);
            row.setPadding(pad, dp(10), pad, dp(10));
            row.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            ImageView img = new ImageView(ctx);
            img.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
            img.setBackgroundResource(R.drawable.bg_circle);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            row.addView(img);

            LinearLayout texts = new LinearLayout(ctx);
            texts.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tlp.setMarginStart(dp(12));
            texts.setLayoutParams(tlp);

            TextView name = new TextView(ctx);
            name.setTextSize(16);
            name.setTextColor(Color.BLACK);
            name.setTypeface(null, Typeface.BOLD);

            TextView sub = new TextView(ctx);
            sub.setTextSize(13);
            sub.setTextColor(0xFF65676B);

            texts.addView(name);
            texts.addView(sub);
            row.addView(texts);

            CheckBox cb = new CheckBox(ctx);
            cb.setClickable(false);
            cb.setFocusable(false);
            row.addView(cb);

            return new VH(row, img, name, sub, cb);
        }

        @Override
        public void onBindViewHolder(VH h, int position) {
            final Person p = shown.get(position);
            h.name.setText(p.name);
            String rel = "Friend".equals(cap(p.relation)) ? "Friend"
                    : "Following".equals(cap(p.relation)) ? "Following"
                    : "Follower".equals(cap(p.relation)) ? "Follows you"
                    : "Member".equals(cap(p.relation)) ? "Group member" : "Profile";
            h.sub.setText(rel);
            AvatarLoader.load(h.img, p.id);
            h.cb.setChecked(isSelected(p));
            h.itemView.setOnClickListener(v -> toggle(p));
        }

        @Override
        public int getItemCount() {
            return shown.size();
        }
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView img;
        final TextView name;
        final TextView sub;
        final CheckBox cb;

        VH(View v, ImageView img, TextView name, TextView sub, CheckBox cb) {
            super(v);
            this.img = img;
            this.name = name;
            this.sub = sub;
            this.cb = cb;
        }
    }
}
