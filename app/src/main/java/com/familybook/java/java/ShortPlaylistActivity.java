package com.familybook.app;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * "Add to playlist" পেজ: সার্চ বক্স, আগের প্লেলিস্টের তালিকা (N reels সহ), উপরে + দিয়ে নতুন প্লেলিস্ট।
 * বেছে নিলে নামটা EXTRA_NAME-এ ShortSettingsActivity-তে ফেরত যায়।
 */
public class ShortPlaylistActivity extends BaseActivity {

    static final String EXTRA_SELECTED = "selected";
    static final String EXTRA_NAME = "name";

    private static final class Item {
        String name;
        String cover;     // সবচেয়ে নতুন Short-এর থাম্বনেইল key (না থাকলে ডিফল্ট আইকন)
        int count;
    }

    private final Map<String, Item> byKey = new LinkedHashMap<>();
    private final List<Item> shown = new ArrayList<>();
    private String selected = "";
    private String query = "";

    private ListView list;
    private TextView tvEmpty;
    private Adapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_short_playlist);

        String sel = getIntent().getStringExtra(EXTRA_SELECTED);
        selected = sel == null ? "" : sel;

        list = findViewById(R.id.listPlaylists);
        tvEmpty = findViewById(R.id.tvEmpty);
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> {
            if (pos >= 0 && pos < shown.size()) pick(shown.get(pos).name);
        });

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnAdd).setOnClickListener(v -> showCreateDialog());

        ((EditText) findViewById(R.id.etSearch)).addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                query = s.toString().trim().toLowerCase(Locale.ROOT);
                applyFilter();
            }
        });

        loadAvatar();
        loadPlaylists();
    }

    // ------------------------------------------------------------ ডেটা

    private void loadAvatar() {
        final String me = SupabaseRest.uid();
        if (me == null) return;
        SupabaseRest.call("GET", "/rest/v1/profiles?id=eq." + me + "&select=avatar_key", null, null, true, r -> {
            if (!r.ok()) return;
            try {
                JSONArray a = new JSONArray(r.body);
                if (a.length() == 0 || a.getJSONObject(0).isNull("avatar_key")) return;
                String key = a.getJSONObject(0).optString("avatar_key", "");
                if (key.isEmpty()) return;
                Media.loadBitmap(key, bm -> {
                    if (bm == null || isFinishing()) return;
                    RoundedBitmapDrawable d = RoundedBitmapDrawableFactory.create(getResources(), bm);
                    d.setCircular(true);
                    ((ImageView) findViewById(R.id.ivAvatar)).setImageDrawable(d);
                });
            } catch (Exception ignored) { }
        });
    }

    /** ১) short_playlists টেবিল (নাম) ২) shorts থেকে গোনা + কভার; পুরনো Shorts-এ থাকা নামও যোগ হয় */
    private void loadPlaylists() {
        final String me = SupabaseRest.uid();
        if (me == null) { finish(); return; }
        SupabaseRest.call("GET", "/rest/v1/short_playlists?user_id=eq." + me
                + "&select=name&order=created_at.desc", null, null, true, r -> {
            if (r.ok()) {
                try {
                    JSONArray a = new JSONArray(r.body);
                    for (int i = 0; i < a.length(); i++) {
                        String n = a.getJSONObject(i).optString("name", "").trim();
                        if (!n.isEmpty()) ensure(n);
                    }
                } catch (Exception ignored) { }
            }
            loadCounts(me);
        });
    }

    private void loadCounts(String me) {
        SupabaseRest.call("GET", "/rest/v1/shorts?user_id=eq." + me
                + "&status=neq.deleted&select=playlists,thumb_key&order=created_at.desc&limit=1000", null, null, true, r -> {
            if (r.ok()) {
                try {
                    JSONArray a = new JSONArray(r.body);
                    for (int i = 0; i < a.length(); i++) {
                        JSONObject o = a.getJSONObject(i);
                        JSONArray pl = o.optJSONArray("playlists");
                        if (pl == null) continue;
                        String thumb = o.isNull("thumb_key") ? null : o.optString("thumb_key", null);
                        for (int k = 0; k < pl.length(); k++) {
                            String n = pl.optString(k, "").trim();
                            if (n.isEmpty()) continue;
                            Item it = ensure(n);
                            it.count++;
                            if (it.cover == null && thumb != null && !thumb.isEmpty()) it.cover = thumb;
                        }
                    }
                } catch (Exception ignored) { }
            }
            applyFilter();
        });
    }

    private Item ensure(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        Item it = byKey.get(key);
        if (it == null) {
            it = new Item();
            it.name = name;
            byKey.put(key, it);
        }
        return it;
    }

    private void applyFilter() {
        shown.clear();
        for (Item it : byKey.values()) {
            if (query.isEmpty() || it.name.toLowerCase(Locale.ROOT).contains(query)) shown.add(it);
        }
        tvEmpty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
        tvEmpty.setText(byKey.isEmpty() ? "No playlists yet — tap + to create one" : "No results found");
        adapter.notifyDataSetChanged();
    }

    // ------------------------------------------------------------ বাছা / নতুন বানানো

    private void pick(String name) {
        setResult(RESULT_OK, new Intent().putExtra(EXTRA_NAME, name));
        finish();
    }

    private void showCreateDialog() {
        final EditText input = new EditText(this);
        input.setHint("Playlist name");
        input.setSingleLine(true);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(60)});
        new AlertDialog.Builder(this)
                .setTitle("New playlist")
                .setView(input)
                .setPositiveButton("Create", (d, w) -> createPlaylist(input.getText().toString().trim()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void createPlaylist(final String name) {
        if (name.isEmpty()) return;
        Item old = byKey.get(name.toLowerCase(Locale.ROOT));
        if (old != null) {            // একই নামের প্লেলিস্ট আগে থেকেই আছে — সেটাই বেছে নেওয়া হয়
            pick(old.name);
            return;
        }
        final String me = SupabaseRest.uid();
        if (me == null) return;
        try {
            JSONObject body = new JSONObject().put("user_id", me).put("name", name);
            SupabaseRest.call("POST", "/rest/v1/short_playlists", body.toString(), "return=minimal", true, r -> {
                if (!r.ok()) {
                    Toast.makeText(this, SupabaseRest.errorText(r), Toast.LENGTH_LONG).show();
                    return;
                }
                pick(name);
            });
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't create playlist", Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------ অ্যাডাপ্টার

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int i) { return shown.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int pos, View v, ViewGroup parent) {
            if (v == null) v = LayoutInflater.from(ShortPlaylistActivity.this)
                    .inflate(R.layout.item_short_playlist, parent, false);
            final Item it = shown.get(pos);
            ((TextView) v.findViewById(R.id.tvName)).setText(it.name);
            ((TextView) v.findViewById(R.id.tvCount)).setText(it.count + (it.count == 1 ? " reel" : " reels"));
            v.findViewById(R.id.ivCheck).setVisibility(it.name.equalsIgnoreCase(selected) ? View.VISIBLE : View.GONE);

            final ImageView cover = v.findViewById(R.id.ivCover);
            cover.setTag(it.name);
            if (it.cover == null) {
                cover.setVisibility(View.GONE);
            } else {
                cover.setVisibility(View.INVISIBLE);
                Media.loadBitmap(it.cover, (Bitmap bm) -> {
                    if (bm == null || !it.name.equals(cover.getTag())) return;
                    RoundedBitmapDrawable d = RoundedBitmapDrawableFactory.create(getResources(), bm);
                    d.setCornerRadius(dp(10));
                    cover.setImageDrawable(d);
                    cover.setVisibility(View.VISIBLE);
                });
            }
            return v;
        }
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
