package com.familybook.app;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Profile info পেজের "Work" ও "Education" সেকশন (একই ক্লাস, দুই রকম)।
 *  WORK      : যোগ করা কাজের তালিকা + "Work experience" সারি → CareerFormActivity (Work ফর্ম)
 *  EDUCATION : যোগ করা তালিকা + "High school or college" সারি →
 *              নিচ থেকে ওঠা মেনু: "Add college" / "Add high school" → CareerFormActivity
 * এন্ট্রিতে চাপলে এডিট; ✕ চাপলে (নিশ্চিত করে) মোছা। অন্যের প্রোফাইলে শুধু Public/Friends দেখায়, এডিট নেই।
 *
 * ব্যবহার (ProfileInfoEditActivity.onCreate-এ):
 *   new ProfileCareerSection(this, findViewById(R.id.rowWork), uid, ProfileCareerSection.WORK).attach();
 *   new ProfileCareerSection(this, findViewById(R.id.rowEducation), uid, ProfileCareerSection.EDUCATION).attach();
 */
public class ProfileCareerSection {

    public static final int WORK = 0, EDUCATION = 1;

    private final ComponentActivity act;
    private final View row;
    private final String uid;
    private final boolean isMe;
    private final int kind;
    private final String key;

    private JSONArray list = new JSONArray();
    private boolean isFriend = false;
    private boolean loaded = false;
    private boolean open = false;

    private View section;
    private LinearLayout box;
    private ImageView arrow;

    private final ActivityResultLauncher<Intent> launcher;

    // অবশ্যই Activity-র onCreate-এর ভেতরে বানাতে হবে
    public ProfileCareerSection(ComponentActivity act, View row, String uid, int kind) {
        this.act = act;
        this.row = row;
        this.kind = kind;
        this.key = kind == WORK ? CareerStore.WORK : CareerStore.EDU;
        String me = Me.uid();
        this.uid = uid != null ? uid : me;
        this.isMe = this.uid != null && this.uid.equals(me);

        this.launcher = act.registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), r -> {
            if (r.getResultCode() != android.app.Activity.RESULT_OK) return;
            open = true;
            load();
        });
    }

    public void attach() {
        ViewGroup parent = (ViewGroup) row.getParent();
        int idx = parent.indexOfChild(row);
        section = LayoutInflater.from(act).inflate(R.layout.layout_profile_communities_section, parent, false);
        section.setVisibility(View.GONE);
        parent.addView(section, idx + 1);
        box = section.findViewById(R.id.llCommunityItems);
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
        CareerStore.load(uid, key, (a, err) -> {
            if (act.isFinishing()) return;
            if (err != null) { TopToast.show(act, err); return; }
            list = a;
            if (isMe) { loaded = true; render(); return; }
            // অন্যের প্রোফাইল: বন্ধু কি না জেনে নিই (Friends-মান দেখানোর জন্য)
            Db.list("friendships?user_id=eq." + Me.uid() + "&friend_id=eq." + uid
                    + "&select=friend_id&limit=1", (rows, e2) -> {
                if (act.isFinishing()) return;
                isFriend = rows != null && rows.length() > 0;
                loaded = true;
                render();
            });
        });
    }

    // ---- আঁকা ----
    private void render() {
        box.removeAllViews();
        LayoutInflater inf = LayoutInflater.from(act);
        int shown = 0;

        for (int i = 0; i < list.length(); i++) {
            final int pos = i;
            final JSONObject e = list.optJSONObject(i);
            if (e == null || e.optString("name", "").trim().isEmpty()) continue;
            String priv = ContactStore.norm(e.optString("priv", ""), ContactStore.PUBLIC);
            if (!ContactStore.visible(priv, isMe, isFriend)) continue;
            shown++;

            View it = inf.inflate(R.layout.item_profile_community_row, box, false);
            ((ImageView) it.findViewById(R.id.imgCommunity)).setImageResource(iconFor(e));
            ((TextView) it.findViewById(R.id.tvCommunityName)).setText(CareerStore.title(key, e));

            String sub = CareerStore.sub(key, e);
            if (isMe) sub = sub.isEmpty() ? ContactStore.label(priv) : sub + " \u00B7 " + ContactStore.label(priv);
            TextView st = it.findViewById(R.id.tvCommunitySub);
            if (!sub.isEmpty()) { st.setText(sub); st.setVisibility(View.VISIBLE); }
            else st.setVisibility(View.GONE);

            View rm = it.findViewById(R.id.btnRemoveCommunity);
            rm.setVisibility(isMe ? View.VISIBLE : View.GONE);
            if (isMe) {
                rm.setOnClickListener(v -> confirmRemove(pos, CareerStore.title(key, e)));
                View.OnClickListener edit = v -> openForm(modeOf(e), pos, e.toString());
                it.setOnClickListener(edit);
                it.findViewById(R.id.llCommunityText).setOnClickListener(edit);
            }
            box.addView(it);
        }

        if (isMe) {
            box.addView(actionRow());
        } else if (shown == 0) {
            TextView none = new TextView(act);
            none.setText(kind == WORK ? "No workplaces to show" : "No schools to show");
            none.setTextColor(0xFF65676B);
            none.setTextSize(14);
            none.setPadding(dp(2), dp(8), 0, dp(8));
            box.addView(none);
        }

        section.setVisibility(open ? View.VISIBLE : View.GONE);
        if (arrow != null) arrow.animate().rotation(open ? 180f : 0f).setDuration(150).start();
    }

    private int iconFor(JSONObject e) {
        if (kind == WORK) return R.drawable.ic_cr_work;
        return CareerStore.isSchool(e) ? R.drawable.ic_cr_school : R.drawable.ic_cr_cap;
    }

    private int modeOf(JSONObject e) {
        if (kind == WORK) return CareerFormActivity.WORK;
        return CareerStore.isSchool(e) ? CareerFormActivity.SCHOOL : CareerFormActivity.COLLEGE;
    }

    /** "Work experience" / "High school or college" */
    private View actionRow() {
        View r = LayoutInflater.from(act).inflate(R.layout.item_profile_community_row, box, false);
        ((ImageView) r.findViewById(R.id.imgCommunity))
                .setImageResource(kind == WORK ? R.drawable.ic_cr_work : R.drawable.ic_cr_school);
        ((TextView) r.findViewById(R.id.tvCommunityName))
                .setText(kind == WORK ? "Work experience" : "High school or college");
        r.findViewById(R.id.tvCommunitySub).setVisibility(View.GONE);
        r.findViewById(R.id.btnRemoveCommunity).setVisibility(View.GONE);
        View.OnClickListener l = v -> {
            if (kind == WORK) openForm(CareerFormActivity.WORK, -1, null);
            else chooser();
        };
        r.setOnClickListener(l);
        r.findViewById(R.id.llCommunityText).setOnClickListener(l);
        return r;
    }

    private void openForm(int mode, int index, String json) {
        Intent i = new Intent(act, CareerFormActivity.class)
                .putExtra(CareerFormActivity.EXTRA_MODE, mode)
                .putExtra(CareerFormActivity.EXTRA_INDEX, index);
        if (json != null) i.putExtra(CareerFormActivity.EXTRA_JSON, json);
        launcher.launch(i);
    }

    private void confirmRemove(int pos, String name) {
        new AlertDialog.Builder(act)
                .setTitle("Remove this?")
                .setMessage(name)
                .setPositiveButton("Remove", (d, w) -> CareerStore.remove(key, pos, err -> {
                    if (act.isFinishing()) return;
                    if (err != null) TopToast.show(act, err);
                    load();
                }))
                .setNegativeButton("Cancel", null).show();
    }

    // ---- Education: নিচ থেকে ওঠা মেনু (Add college / Add high school) ----
    private void chooser() {
        final Dialog dlg = new Dialog(act);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout sheet = new LinearLayout(act);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(16), dp(12), dp(16), dp(20));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        float r = dp(18);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        sheet.setBackground(bg);

        sheet.addView(sheetRow(R.drawable.ic_cr_cap, "Add college", v -> {
            dlg.dismiss();
            openForm(CareerFormActivity.COLLEGE, -1, null);
        }));
        sheet.addView(sheetRow(R.drawable.ic_cr_school, "Add high school", v -> {
            dlg.dismiss();
            openForm(CareerFormActivity.SCHOOL, -1, null);
        }));

        dlg.setContentView(sheet);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
        }
        dlg.show();
    }

    /** গোল ধূসর বৃত্তে আইকন + বোল্ড নাম */
    private View sheetRow(int icon, String label, View.OnClickListener l) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));
        row.setClickable(true);

        FrameLayout circle = new FrameLayout(act);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(Color.parseColor("#E4E6EB"));
        circle.setBackground(g);
        ImageView iv = new ImageView(act);
        iv.setImageResource(icon);
        iv.setPadding(dp(14), dp(14), dp(14), dp(14));
        circle.addView(iv, new FrameLayout.LayoutParams(dp(56), dp(56)));
        row.addView(circle, new LinearLayout.LayoutParams(dp(56), dp(56)));

        TextView t = new TextView(act);
        t.setText(label);
        t.setTextSize(20);
        t.setTextColor(0xFF050505);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(dp(16));
        row.addView(t, lp);

        row.setOnClickListener(l);
        return row;
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
