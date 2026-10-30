package com.familybook.app;

import android.app.Activity;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/**
 * Profile info পেজের "Hobbies" ও "Travel" সেকশন (একই ক্লাস, দুই রকম)।
 *  HOBBIES: তালিকা + "Add Your Hobbies" → HobbiesPickActivity (সার্চ বক্স)।  ডাটা: info.hobbies = [নাম]
 *  PLACES : "Places" শিরোনাম, জায়গার তালিকা + "Add Places" → LocationActivity (লোকেশন সার্চ বক্স)।
 *           ডাটা: info.travel_places = [নাম]
 * সারিতে চাপলে খোলে/বন্ধ হয়। নিজের প্রোফাইলে ✕ দিয়ে সরানো যায়; অন্যেরটায় শুধু দেখা যায়।
 *
 * ব্যবহার (ProfileInfoEditActivity.onCreate-এ):
 *   new ProfileListSection(this, findViewById(R.id.rowHobbies), uid, ProfileListSection.HOBBIES).attach();
 *   new ProfileListSection(this, findViewById(R.id.rowTravel),  uid, ProfileListSection.PLACES).attach();
 */
public class ProfileListSection {

    public static final int HOBBIES = 0, PLACES = 1;
    private static final int MAX_PLACES = 30;

    private final ComponentActivity act;
    private final View row;
    private final String uid;
    private final boolean isMe;
    private final int kind;
    private final String key;

    private final ArrayList<String> items = new ArrayList<>();
    private boolean loaded = false;
    private boolean open = false;

    private View section;
    private LinearLayout list;
    private ImageView arrow;

    private final ActivityResultLauncher<Intent> launcher;

    // অবশ্যই Activity-র onCreate-এর ভেতরে বানাতে হবে
    public ProfileListSection(ComponentActivity act, View row, String uid, int kind) {
        this.act = act;
        this.row = row;
        this.kind = kind;
        this.key = kind == HOBBIES ? "hobbies" : "travel_places";
        String me = Me.uid();
        this.uid = uid != null ? uid : me;
        this.isMe = this.uid != null && this.uid.equals(me);

        this.launcher = act.registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), r -> {
            if (r.getResultCode() != Activity.RESULT_OK) return;
            open = true;
            if (kind == HOBBIES) {
                load();                                   // পিকার নিজেই সেভ করেছে
            } else if (r.getData() != null) {
                String place = r.getData().getStringExtra(LocationActivity.EXTRA_LOCATION);
                if (place != null && !place.trim().isEmpty()) addPlace(place.trim());
            }
        });
    }

    public void attach() {
        ViewGroup parent = (ViewGroup) row.getParent();
        int idx = parent.indexOfChild(row);
        section = LayoutInflater.from(act).inflate(R.layout.layout_profile_communities_section, parent, false);
        section.setVisibility(View.GONE);
        parent.addView(section, idx + 1);
        list = section.findViewById(R.id.llCommunityItems);
        arrow = lastImageView(row);

        bindClick(row, v -> onRowTap());
        load();
    }

    private void onRowTap() {
        if (!loaded) {
            TopToast.show(act, "Loading, please wait");
            load();
            return;
        }
        open = !open;
        render();
    }

    // ---- সার্ভার থেকে লোড ----
    private void load() {
        Db.get("profiles", "id", uid, (r, err) -> {
            if (act.isFinishing()) return;
            if (err != null) { TopToast.show(act, err); return; }
            items.clear();
            JSONObject info = r != null ? r.optJSONObject("info") : null;
            JSONArray a = info != null ? info.optJSONArray(key) : null;
            for (int i = 0; a != null && i < a.length(); i++) {
                String s = a.optString(i, "").trim();
                if (!s.isEmpty() && !items.contains(s)) items.add(s);
            }
            loaded = true;
            render();
        });
    }

    private void save() {
        try {
            JSONArray a = new JSONArray();
            for (String s : items) a.put(s);
            Db.mergeMyInfo(new JSONObject().put(key, a), err -> {
                if (act.isFinishing()) return;
                if (err != null) { TopToast.show(act, err); load(); }
            });
        } catch (Exception e) {
            TopToast.show(act, "Could not save");
        }
    }

    private void addPlace(String place) {
        for (String s : items) if (s.equalsIgnoreCase(place)) { TopToast.show(act, "Already added"); render(); return; }
        if (items.size() >= MAX_PLACES) { TopToast.show(act, "You can add up to " + MAX_PLACES + " places"); return; }
        items.add(place);
        save();
        render();
    }

    // ---- আঁকা ----
    private void render() {
        list.removeAllViews();
        LayoutInflater inf = LayoutInflater.from(act);

        if (kind == PLACES) {
            TextView head = new TextView(act);
            head.setText("Places");
            head.setTextSize(14);
            head.setTextColor(0xFF65676B);
            head.setTypeface(null, android.graphics.Typeface.BOLD);
            head.setPadding(dp(2), dp(4), 0, dp(2));
            list.addView(head);
        }

        for (final String name : items) {
            View it = inf.inflate(R.layout.item_profile_community_row, list, false);
            ((ImageView) it.findViewById(R.id.imgCommunity))
                    .setImageResource(kind == HOBBIES ? R.drawable.ic_ct_star : R.drawable.ic_ct_pin);
            ((TextView) it.findViewById(R.id.tvCommunityName)).setText(name);
            View rm = it.findViewById(R.id.btnRemoveCommunity);
            rm.setVisibility(isMe ? View.VISIBLE : View.GONE);
            rm.setOnClickListener(v -> { items.remove(name); save(); render(); });
            list.addView(it);
        }

        if (isMe) {
            list.addView(actionRow(kind == HOBBIES ? "Add Your Hobbies" : "Add Places", v -> {
                if (kind == HOBBIES) launcher.launch(new Intent(act, HobbiesPickActivity.class));
                else launcher.launch(new Intent(act, LocationActivity.class));
            }));
        } else if (items.isEmpty()) {
            TextView none = new TextView(act);
            none.setText(kind == HOBBIES ? "No hobbies to show" : "No places to show");
            none.setTextColor(0xFF65676B);
            none.setTextSize(14);
            none.setPadding(dp(2), dp(8), 0, dp(8));
            list.addView(none);
        }

        section.setVisibility(open ? View.VISIBLE : View.GONE);
        if (arrow != null) arrow.animate().rotation(open ? 180f : 0f).setDuration(150).start();
    }

    private View actionRow(String label, View.OnClickListener l) {
        View r = LayoutInflater.from(act).inflate(R.layout.item_profile_community_row, list, false);
        ((ImageView) r.findViewById(R.id.imgCommunity)).setImageResource(R.drawable.ic_gp_plus);
        TextView t = r.findViewById(R.id.tvCommunityName);
        t.setText(label);
        t.setTextColor(0xFF1877F2);
        r.setOnClickListener(l);
        r.findViewById(R.id.llCommunityText).setOnClickListener(l);
        return r;
    }

    // ---- helpers ----
    private int dp(int v) {
        return Math.round(v * act.getResources().getDisplayMetrics().density);
    }

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
