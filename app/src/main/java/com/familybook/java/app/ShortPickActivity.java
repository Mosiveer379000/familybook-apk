package com.familybook.app;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Short Settings-এর দুটো বাছাই পেজ (একই স্ক্রিন):
 *  MODE_GROUPS — আমার তৈরি করা + জয়েন করা গ্রুপ (একাধিক বাছা যায়)
 *  MODE_COLLAB — আমার Followers + আমি যাদের Follow করি (সর্বোচ্চ 5 জন)
 * ফলাফল: EXTRA_IDS, EXTRA_NAMES (ArrayList<String>)
 */
public class ShortPickActivity extends BaseActivity {

    static final String EXTRA_MODE = "mode";
    static final String MODE_GROUPS = "groups";
    static final String MODE_COLLAB = "collab";
    static final String EXTRA_IDS = "ids";
    static final String EXTRA_NAMES = "names";
    static final int MAX_COLLAB = 5;

    private static final class Row {
        String id, name, sub;
    }

    private boolean groups;
    private final List<Row> all = new ArrayList<>();
    private final List<Row> shown = new ArrayList<>();
    private final Map<String, String> sel = new LinkedHashMap<>();   // id -> name
    private String query = "";

    private ListView list;
    private TextView tvEmpty, btnDone;
    private Adapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_short_pick);

        groups = MODE_GROUPS.equals(getIntent().getStringExtra(EXTRA_MODE));
        ArrayList<String> ids = getIntent().getStringArrayListExtra(EXTRA_IDS);
        ArrayList<String> names = getIntent().getStringArrayListExtra(EXTRA_NAMES);
        if (ids != null && names != null && ids.size() == names.size()) {
            for (int i = 0; i < ids.size(); i++) sel.put(ids.get(i), names.get(i));
        }

        ((TextView) findViewById(R.id.tvTitle)).setText(groups ? "Share to groups" : "Invite collaborator");
        ((TextView) findViewById(R.id.tvHint)).setText(groups
                ? "Choose the groups you created or joined. Your short will be posted in each selected group."
                : "Choose up to 5 people from your followers and the people you follow. They get a request and the short shows on their profile only after they approve.");
        ((EditText) findViewById(R.id.etSearch)).setHint(groups ? "Search groups" : "Search people");

        list = findViewById(R.id.listPick);
        tvEmpty = findViewById(R.id.tvEmpty);
        btnDone = findViewById(R.id.btnDone);
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> {
            if (pos < 0 || pos >= shown.size()) return;
            toggle(shown.get(pos));
        });

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        btnDone.setOnClickListener(v -> finishWithResult());
        ((EditText) findViewById(R.id.etSearch)).addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                query = s.toString().trim().toLowerCase(Locale.ROOT);
                applyFilter();
            }
        });

        updateDone();
        tvEmpty.setText("Loading…");
        tvEmpty.setVisibility(View.VISIBLE);
        if (groups) loadGroups(); else loadPeople();
    }

    // ------------------------------------------------------------ গ্রুপ

    private void loadGroups() {
        String me = SupabaseRest.uid();
        if (me == null) { finish(); return; }
        GroupsRepo.load(me, (items, err) -> {
            if (isFinishing()) return;
            if (err != null) {
                Toast.makeText(this, err, Toast.LENGTH_LONG).show();
            }
            all.clear();
            if (items != null) {
                for (GroupItem g : items) {
                    Row r = new Row();
                    r.id = g.id;
                    r.name = g.name == null || g.name.isEmpty() ? "Group" : g.name;
                    r.sub = g.isAdmin ? "Created by you"
                            : "Joined · " + g.members + (g.members == 1 ? " member" : " members");
                    all.add(r);
                }
            }
            applyFilter();
        });
    }

    // ------------------------------------------------------------ ফলোয়ার + ফলোয়িং

    private void loadPeople() {
        final String me = SupabaseRest.uid();
        if (me == null) { finish(); return; }
        final Set<String> followers = new LinkedHashSet<>();
        final Set<String> following = new LinkedHashSet<>();
        SupabaseRest.call("GET", "/rest/v1/follows?followee_id=eq." + me + "&select=follower_id&limit=300",
                null, null, true, r1 -> {
            collect(r1, "follower_id", followers);
            SupabaseRest.call("GET", "/rest/v1/follows?follower_id=eq." + me + "&select=followee_id&limit=300",
                    null, null, true, r2 -> {
                collect(r2, "followee_id", following);
                loadProfiles(me, followers, following);
            });
        });
    }

    private static void collect(SupabaseRest.Resp r, String key, Set<String> out) {
        if (!r.ok()) return;
        try {
            JSONArray a = new JSONArray(r.body);
            for (int i = 0; i < a.length(); i++) {
                String id = a.getJSONObject(i).optString(key, "");
                if (!id.isEmpty()) out.add(id);
            }
        } catch (Exception ignored) { }
    }

    private void loadProfiles(String me, Set<String> followers, Set<String> following) {
        final Set<String> ids = new LinkedHashSet<>(following);
        ids.addAll(followers);
        ids.remove(me);
        if (ids.isEmpty()) { all.clear(); applyFilter(); return; }
        StringBuilder in = new StringBuilder();
        int n = 0;
        for (String id : ids) {
            if (n++ >= 150) break;
            if (in.length() > 0) in.append(',');
            in.append(id);
        }
        final Set<String> fr = followers, fg = following;
        SupabaseRest.call("GET", "/rest/v1/profiles?id=in.(" + in + ")&select=id,name", null, null, true, r -> {
            if (isFinishing()) return;
            all.clear();
            if (r.ok()) {
                try {
                    JSONArray a = new JSONArray(r.body);
                    for (int i = 0; i < a.length(); i++) {
                        JSONObject o = a.getJSONObject(i);
                        Row row = new Row();
                        row.id = o.optString("id", "");
                        row.name = o.isNull("name") ? "" : o.optString("name", "");
                        if (row.id.isEmpty()) continue;
                        if (row.name.isEmpty()) row.name = "Unknown";
                        boolean a1 = fr.contains(row.id), a2 = fg.contains(row.id);
                        row.sub = a1 && a2 ? "Friends" : a2 ? "Following" : "Follows you";
                        all.add(row);
                    }
                } catch (Exception ignored) { }
            } else {
                Toast.makeText(this, SupabaseRest.errorText(r), Toast.LENGTH_LONG).show();
            }
            applyFilter();
        });
    }

    // ------------------------------------------------------------ বাছাই

    private void applyFilter() {
        shown.clear();
        for (Row r : all) {
            if (query.isEmpty() || r.name.toLowerCase(Locale.ROOT).contains(query)) shown.add(r);
        }
        tvEmpty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
        tvEmpty.setText(all.isEmpty()
                ? (groups ? "You haven't created or joined any groups yet" : "No followers or following yet")
                : "No results found");
        adapter.notifyDataSetChanged();
    }

    private void toggle(Row r) {
        if (sel.containsKey(r.id)) {
            sel.remove(r.id);
        } else {
            if (!groups && sel.size() >= MAX_COLLAB) {
                Toast.makeText(this, "You can invite up to " + MAX_COLLAB + " collaborators", Toast.LENGTH_SHORT).show();
                return;
            }
            sel.put(r.id, r.name);
        }
        updateDone();
        adapter.notifyDataSetChanged();
    }

    private void updateDone() {
        btnDone.setText(sel.isEmpty() ? "Done" : "Done (" + sel.size() + ")");
    }

    private void finishWithResult() {
        Intent d = new Intent();
        d.putStringArrayListExtra(EXTRA_IDS, new ArrayList<>(sel.keySet()));
        d.putStringArrayListExtra(EXTRA_NAMES, new ArrayList<>(sel.values()));
        setResult(RESULT_OK, d);
        finish();
    }

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int i) { return shown.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int pos, View v, ViewGroup parent) {
            if (v == null) v = LayoutInflater.from(ShortPickActivity.this).inflate(R.layout.item_short_pick, parent, false);
            Row r = shown.get(pos);
            ((TextView) v.findViewById(R.id.tvName)).setText(r.name);
            ((TextView) v.findViewById(R.id.tvSub)).setText(r.sub);
            ((CheckBox) v.findViewById(R.id.cbPick)).setChecked(sel.containsKey(r.id));
            ImageView iv = v.findViewById(R.id.ivAvatar);
            TextView ini = v.findViewById(R.id.tvInitial);
            iv.setImageDrawable(null);
            if (groups) {
                ini.setVisibility(View.VISIBLE);
                ini.setText(r.name.substring(0, 1).toUpperCase(Locale.ROOT));
            } else {
                ini.setVisibility(View.GONE);
                AvatarLoader.load(iv, r.id);
            }
            return v;
        }
    }
}
