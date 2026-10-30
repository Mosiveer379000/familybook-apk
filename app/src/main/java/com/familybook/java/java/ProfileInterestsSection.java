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
 * Profile info পেজের "Interests" সেকশন।
 * খুললে ৫টি অপশন: Music, TV shows, Movies, Games, Sports teams and athletes।
 * প্রতিটিতে চাপলে সার্চ বক্সসহ InterestPickActivity খোলে (প্রতিটির আলাদা JSON)।
 * নিচে সাবটাইটেলে বেছে নেওয়া আইটেমের সারাংশ দেখায়।  অন্যের প্রোফাইলে শুধু দেখা যায়।
 *
 * ব্যবহার (ProfileInfoEditActivity.onCreate-এ):
 *   new ProfileInterestsSection(this, findViewById(R.id.rowInterests), uid).attach();
 */
public class ProfileInterestsSection {

    private static final int[] ICON = {
            R.drawable.ic_int_music, R.drawable.ic_int_tv, R.drawable.ic_int_movie,
            R.drawable.ic_int_game, R.drawable.ic_int_sport};

    private final ComponentActivity act;
    private final View row;
    private final String uid;
    private final boolean isMe;

    @SuppressWarnings("unchecked")
    private final ArrayList<String>[] items = new ArrayList[InterestKind.KEY.length];
    private boolean loaded = false;
    private boolean open = false;

    private View section;
    private LinearLayout list;
    private ImageView arrow;

    private final ActivityResultLauncher<Intent> launcher;

    // অবশ্যই Activity-র onCreate-এর ভেতরে বানাতে হবে
    public ProfileInterestsSection(ComponentActivity act, View row, String uid) {
        this.act = act;
        this.row = row;
        String me = Me.uid();
        this.uid = uid != null ? uid : me;
        this.isMe = this.uid != null && this.uid.equals(me);
        for (int i = 0; i < items.length; i++) items[i] = new ArrayList<>();

        this.launcher = act.registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), r -> {
            if (r.getResultCode() != Activity.RESULT_OK) return;
            open = true;
            load();                                      // পিকার নিজেই সেভ করেছে
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
            JSONObject info = r != null ? r.optJSONObject("info") : null;
            for (int k = 0; k < items.length; k++) {
                items[k].clear();
                JSONArray a = info != null ? info.optJSONArray(InterestKind.KEY[k]) : null;
                for (int i = 0; a != null && i < a.length(); i++) {
                    String s = a.optString(i, "").trim();
                    if (!s.isEmpty() && !items[k].contains(s)) items[k].add(s);
                }
            }
            loaded = true;
            render();
        });
    }

    // ---- আঁকা ----
    private void render() {
        list.removeAllViews();
        LayoutInflater inf = LayoutInflater.from(act);
        boolean any = false;

        for (int k = 0; k < InterestKind.KEY.length; k++) {
            final int kind = k;
            ArrayList<String> sel = items[k];
            if (!isMe && sel.isEmpty()) continue;       // অন্যের প্রোফাইলে খালি ক্যাটাগরি দেখাবে না
            any = true;

            View it = inf.inflate(R.layout.item_profile_community_row, list, false);
            ((ImageView) it.findViewById(R.id.imgCommunity)).setImageResource(ICON[k]);
            ((TextView) it.findViewById(R.id.tvCommunityName)).setText(InterestKind.LABEL[k]);
            it.findViewById(R.id.btnRemoveCommunity).setVisibility(View.GONE);

            TextView sub = it.findViewById(R.id.tvCommunitySub);
            if (!sel.isEmpty()) {
                sub.setText(summary(sel));
                sub.setMaxLines(2);
                sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
                sub.setVisibility(View.VISIBLE);
            } else {
                sub.setVisibility(View.GONE);
            }

            if (isMe) {
                View.OnClickListener l = v -> launcher.launch(
                        new Intent(act, InterestPickActivity.class).putExtra(InterestPickActivity.EXTRA_KIND, kind));
                it.setOnClickListener(l);
                it.findViewById(R.id.llCommunityText).setOnClickListener(l);
            }
            list.addView(it);
        }

        if (!any) {
            TextView none = new TextView(act);
            none.setText("No interests to show");
            none.setTextColor(0xFF65676B);
            none.setTextSize(14);
            none.setPadding(dp(2), dp(8), 0, dp(8));
            list.addView(none);
        }

        section.setVisibility(open ? View.VISIBLE : View.GONE);
        if (arrow != null) arrow.animate().rotation(open ? 180f : 0f).setDuration(150).start();
    }

    /** "A, B, C +2" */
    private String summary(ArrayList<String> sel) {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(3, sel.size());
        for (int i = 0; i < n; i++) { if (i > 0) sb.append(", "); sb.append(sel.get(i)); }
        if (sel.size() > n) sb.append("  +").append(sel.size() - n);
        return sb.toString();
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
