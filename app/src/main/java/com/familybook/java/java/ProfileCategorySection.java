package com.familybook.app;

import android.app.Activity;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/**
 * Profile info পেজের "Category" সেকশন।
 * - কিছু সেভ করা না থাকলে Category সারিতে চাপলেই Category পেজ (CategoryEditActivity) খোলে
 * - সেভ করা থাকলে সারি খোলে/বন্ধ হয়; সব ক্যাটাগরি এক সারিতে (·দিয়ে আলাদা) ও পাশে একটা পেন্সিল, চাপলে Category পেজ খোলে
 * - Save দিলে এই পেজে ফিরে এসে সেকশন খোলা অবস্থায় (৩নং ছবির মতো) দেখায়
 *
 * ব্যবহার (ProfileInfoEditActivity.onCreate-এ, setContentView-এর পরে):
 *   new ProfileCategorySection(this, findViewById(R.id.rowCategory),
 *           getIntent().getStringExtra("uid")).attach();
 */
public class ProfileCategorySection {

    private final ComponentActivity act;
    private final View rowCategory;
    private final String uid;
    private final boolean isMe;

    private final ArrayList<String> categories = new ArrayList<>();
    private boolean loaded = false;
    private boolean open = false;

    private View section;
    private LinearLayout items;
    private ImageView arrow;

    private final ActivityResultLauncher<Intent> launcher;

    // অবশ্যই Activity-র onCreate-এর ভেতরে বানাতে হবে (launcher রেজিস্টার হয় এখানে)
    public ProfileCategorySection(ComponentActivity act, View rowCategory, String uid) {
        this.act = act;
        this.rowCategory = rowCategory;
        String me = Me.uid();
        this.uid = uid != null ? uid : me;
        this.isMe = this.uid != null && this.uid.equals(me);

        this.launcher = act.registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), r -> {
            if (r.getResultCode() == Activity.RESULT_OK && r.getData() != null) {
                String[] arr = r.getData().getStringArrayExtra("categories");
                categories.clear();
                if (arr != null) for (String s : arr) categories.add(s);
                loaded = true;
                open = !categories.isEmpty();
                render();
            }
        });
    }

    public void attach() {
        ViewGroup parent = (ViewGroup) rowCategory.getParent();
        int idx = parent.indexOfChild(rowCategory);
        section = LayoutInflater.from(act).inflate(R.layout.layout_profile_category_section, parent, false);
        section.setVisibility(View.GONE);
        parent.addView(section, idx + 1);
        items = section.findViewById(R.id.llCategoryItems);
        arrow = lastImageView(rowCategory);

        bindClick(rowCategory, v -> onRowTap());
        load();
    }

    private void onRowTap() {
        if (!isMe) return;
        if (!loaded) {
            Toast.makeText(act, "লোড হচ্ছে, একটু অপেক্ষা করুন", Toast.LENGTH_SHORT).show();
            load();
            return;
        }
        if (categories.isEmpty()) {
            openEditor();
        } else {
            open = !open;
            render();
        }
    }

    private void openEditor() {
        launcher.launch(new Intent(act, CategoryEditActivity.class)
                .putExtra("uid", uid)
                .putExtra("categories", categories.toArray(new String[0])));
    }

    // ---- Supabase থেকে লোড ----
    private void load() {
        SupabaseRest.call("GET",
                "/rest/v1/profile_categories?user_id=eq." + uid + "&select=categories&limit=1",
                null, null, true, r -> {
            if (act.isFinishing() || !r.ok()) return;
            categories.clear();
            try {
                JSONArray a = new JSONArray(r.body);
                if (a.length() > 0) {
                    JSONObject row = a.optJSONObject(0);
                    JSONArray c = row == null ? null : row.optJSONArray("categories");
                    if (c != null) {
                        for (int i = 0; i < c.length(); i++) {
                            String s = c.optString(i, "");
                            if (!s.isEmpty()) categories.add(s);
                        }
                    }
                }
            } catch (Exception ignored) { }
            loaded = true;
            render();
        });
    }

    // ---- সেকশন আঁকা ----
    private void render() {
        items.removeAllViews();
        LayoutInflater inf = LayoutInflater.from(act);
        if (!categories.isEmpty()) {
            // সব ক্যাটাগরি এক সারিতে: Public figure · Musician · Artist
            StringBuilder sb = new StringBuilder();
            for (String name : categories) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(name);
            }
            View row = inf.inflate(R.layout.item_profile_category_row, items, false);
            ((TextView) row.findViewById(R.id.tvCategoryName)).setText(sb.toString());
            View edit = row.findViewById(R.id.btnEditCategory);
            edit.setVisibility(isMe ? View.VISIBLE : View.GONE);
            edit.setOnClickListener(v -> openEditor());
            items.addView(row);
        }
        boolean show = open && !categories.isEmpty();
        section.setVisibility(show ? View.VISIBLE : View.GONE);
        if (arrow != null) arrow.animate().rotation(show ? 180f : 0f).setDuration(150).start();
    }

    // ---- helpers ----
    private void bindClick(View v, View.OnClickListener l) {
        v.setOnClickListener(l);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) bindClick(g.getChildAt(i), l);
        }
    }

    private ImageView lastImageView(View v) {
        ImageView found = null;
        if (v instanceof ImageView) found = (ImageView) v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                ImageView c = lastImageView(g.getChildAt(i));
                if (c != null) found = c;
            }
        }
        return found;
    }
}
