package com.familybook.app;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.activity.ComponentActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/**
 * Profile info পেজের "Personal info" সেকশন।
 */
public class PersonalInfoSection {

    private static final String[] MONTHS = {"Jan", "Feb", "Mar", "Apr", "May", "Jun",
            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};

    private final ComponentActivity act;
    private final View rowPersonal;
    private final String uid;
    private final boolean isMe;

    private String dob = "";
    private String gender = "";
    private boolean nidVerified = false;
    private boolean birthdayPublic = true;
    private boolean birthYearPublic = true;
    private boolean cityPublic = true;
    private boolean hometownPublic = true;
    private boolean genderPublic = true;
    private boolean languagesPublic = true;

    private String currentCity = "";
    private String hometown = "";
    private String relationship = "";
    private boolean relationshipVerified = false;
    private boolean relationshipPublic = false;
    private JSONObject familyInfo = null; // profiles.info.familyMembers
    private final ArrayList<String> languages = new ArrayList<>();

    private boolean loaded = false;
    private boolean open = false;

    private View section;
    private LinearLayout items;
    private ImageView arrow;

    public PersonalInfoSection(ComponentActivity act, View rowPersonal, String uid) {
        this.act = act;
        this.rowPersonal = rowPersonal;
        String me = Me.uid();
        this.uid = uid != null ? uid : me;
        this.isMe = this.uid != null && this.uid.equals(me);
    }

    public void attach() {
        ViewGroup parent = (ViewGroup) rowPersonal.getParent();
        int idx = parent.indexOfChild(rowPersonal);
        section = LayoutInflater.from(act).inflate(R.layout.layout_profile_personal_section, parent, false);
        section.setVisibility(View.GONE);
        parent.addView(section, idx + 1);
        items = section.findViewById(R.id.llPersonalItems);
        arrow = lastImageView(rowPersonal);

        bindClick(rowPersonal, v -> toggle());
        load();
    }

    public void refresh() {
        if (loaded) load();
    }

    private void toggle() {
        if (!loaded) {
            TopToast.show(act, "Loading, please wait");
            load();
            return;
        }
        open = !open;
        render();
    }

    private void load() {
        Db.get("profiles", "id", uid, (row, err) -> {
            if (act.isFinishing()) return;
            if (err != null) {
                TopToast.show(act, err);
                return;
            }
            JSONObject info = row != null && row.optJSONObject("info") != null
                    ? row.optJSONObject("info") : new JSONObject();
            dob = info.optString("dob", "").trim();
            gender = info.optString("gender", "").trim();
            nidVerified = info.optBoolean("nidVerified", false);
            JSONObject priv = info.optJSONObject("priv");
            birthdayPublic = priv == null || priv.optBoolean("birthday", true);
            birthYearPublic = priv == null || priv.optBoolean("birthYear", true);
            cityPublic = priv == null || priv.optBoolean("currentCity", true);
            hometownPublic = priv == null || priv.optBoolean("hometown", true);
            genderPublic = priv == null || priv.optBoolean("gender", true);
            languagesPublic = priv == null || priv.optBoolean("languages", true);

            familyInfo = info.optJSONObject("familyMembers");
            relationship = info.optString("relationship", "").trim();
            JSONObject relWith = info.optJSONObject("relationshipWith");
            if (!relationship.isEmpty() && relWith != null && !relWith.optString("name", "").isEmpty()) {
                relationship = relationship + " to " + relWith.optString("name");
            }
            relationshipVerified = info.optBoolean("relationshipVerified", false);
            relationshipPublic = priv != null && priv.optBoolean("relationship", false);
            currentCity = info.optString("currentCity", "").trim();
            hometown = info.optString("hometown", "").trim();
            languages.clear();
            JSONArray la = info.optJSONArray("languages");
            if (la != null) {
                for (int i = 0; i < la.length(); i++) {
                    String s = la.optString(i, "").trim();
                    if (!s.isEmpty()) languages.add(s);
                }
            }

            if (isMe && (!isValidDob(dob) || gender.isEmpty())) {
                loadFromRegistration();
            } else {
                loaded = true;
                render();
            }
        });
    }

    private void loadFromRegistration() {
        SupabaseRest.call("GET", "/auth/v1/user", null, null, true, r -> {
            if (act.isFinishing()) return;
            try {
                JSONObject meta = new JSONObject(r.body).optJSONObject("user_metadata");
                if (meta != null) {
                    if (!isValidDob(dob)) dob = meta.optString("dob", "").trim();
                    if (gender.isEmpty()) gender = meta.optString("gender", "").trim();
                }
            } catch (Exception ignored) { }
            loaded = true;
            render();
        });
    }

    private void render() {
        items.removeAllViews();

        // 1) Current city
        View city = newRow(R.drawable.ic_current_city);
        fill(city, currentCity, "Current city", cityPublic, false);
        doubleTap(city, "city", currentCity, cityPublic);

        // 2) Hometown
        View home = newRow(R.drawable.ic_hometown);
        fill(home, hometown, "Hometown", hometownPublic, false);
        doubleTap(home, "hometown", hometown, hometownPublic);

        // 3) Birthday
        View bday = newRow(R.drawable.ic_birthday_info);
        fill(bday, isValidDob(dob) ? prettyDob(dob) : "", "Birthday", birthdayPublic, nidVerified);
        showEdit(bday, v -> openBirthday());

        // 4) Relationship
        View rel = newRow(R.drawable.ic_relationship);
        fill(rel, relationship, "Add Relationship", relationshipPublic, relationshipVerified);
        showEdit(rel, v -> openRelationship());
        if (isMe) rel.setOnClickListener(v -> openRelationship());

        // 5) Family: সেভ করা থাকলে "Family Members :" ব্লক (পেন্সিলে ফর্ম), না থাকলে "Add Family" সারি
        renderFamily();

        // 6) Gender
        View gen = newRow(R.drawable.ic_gender_info);
        fill(gen, gender, "Gender", genderPublic, false);
        showEdit(gen, v -> openGender());

        // 7) Languages
        View lang = newRow(R.drawable.ic_languages);
        fillChips(lang, languages, "Languages", languagesPublic);
        if (isMe) lang.setOnClickListener(v -> openLanguages());

        section.setVisibility(open ? View.VISIBLE : View.GONE);
        if (arrow != null) arrow.animate().rotation(open ? 180f : 0f).setDuration(150).start();
    }

    private View newRow(int iconRes) {
        View r = LayoutInflater.from(act).inflate(R.layout.item_profile_personal_row, items, false);
        ((ImageView) r.findViewById(R.id.ivRowIcon)).setImageResource(iconRes);
        items.addView(r);
        return r;
    }

    private void fill(View r, String value, String hint, boolean publicPriv, boolean verified) {
        TextView tv = r.findViewById(R.id.tvRowValue);
        View priv = r.findViewById(R.id.llRowPrivacy);
        TextView badge = r.findViewById(R.id.tvRowVerified);
        boolean has = value != null && !value.trim().isEmpty();

        tv.setText(has ? value : hint);
        tv.setTextColor(has ? Color.parseColor("#050505") : Color.parseColor("#65676B"));

        if (has) {
            priv.setVisibility(View.VISIBLE);
            ((ImageView) r.findViewById(R.id.ivRowPrivacy)).setImageResource(
                    publicPriv ? R.drawable.ic_globe : R.drawable.ic_privacy_lock);
        } else {
            priv.setVisibility(View.GONE);
        }

        if (has && verified) {
            badge.setVisibility(View.VISIBLE);
            badge.setBackground(rounded(Color.parseColor("#4CAF50"), 12));
        } else {
            badge.setVisibility(View.GONE);
        }
    }

    private void fillChips(View r, ArrayList<String> list, String hint, boolean publicPriv) {
        TextView tv = r.findViewById(R.id.tvRowValue);
        View priv = r.findViewById(R.id.llRowPrivacy);
        r.findViewById(R.id.tvRowVerified).setVisibility(View.GONE);

        if (list.isEmpty()) {
            tv.setText(hint);
            tv.setTextColor(Color.parseColor("#65676B"));
            priv.setVisibility(View.GONE);
            return;
        }
        tv.setVisibility(View.GONE);
        ViewGroup holder = r.findViewById(R.id.llRowValueBox);
        FlowLayout flow = new FlowLayout(act);
        for (String name : list) {
            TextView chip = new TextView(act);
            chip.setText(name);
            chip.setTextSize(15);
            chip.setTypeface(Typeface.DEFAULT_BOLD);
            chip.setTextColor(Color.parseColor("#65676B"));
            chip.setPadding(dp(14), dp(7), dp(14), dp(7));
            chip.setBackground(rounded(Color.parseColor("#E4E6EB"), 40));
            flow.addView(chip);
        }
        holder.addView(flow, 1);
        priv.setVisibility(View.VISIBLE);
        ((ImageView) r.findViewById(R.id.ivRowPrivacy)).setImageResource(
                publicPriv ? R.drawable.ic_globe : R.drawable.ic_privacy_lock);
    }

    private void showEdit(View r, View.OnClickListener l) {
        View edit = r.findViewById(R.id.btnRowEdit);
        edit.setVisibility(isMe ? View.VISIBLE : View.GONE);
        edit.setOnClickListener(l);
    }

    private void doubleTap(View row, final String type, final String value, final boolean pub) {
        if (!isMe) return;
        final GestureDetector gd = new GestureDetector(act, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onDoubleTap(MotionEvent e) {
                Intent i = new Intent(act, PlaceEditActivity.class);
                i.putExtra("type", type);
                i.putExtra("value", value);
                i.putExtra("pub", pub);
                act.startActivity(i);
                return true;
            }
        });
        row.setOnTouchListener((v, e) -> gd.onTouchEvent(e));
    }

    private void openBirthday() {
        Intent i = new Intent(act, BirthdayEditActivity.class);
        i.putExtra("dob", dob);
        i.putExtra("verified", nidVerified);
        i.putExtra("pubDate", birthdayPublic);
        i.putExtra("pubYear", birthYearPublic);
        act.startActivity(i);
    }

    private void openRelationship() {
        act.startActivity(new Intent(act, RelationshipActivity.class));
    }

    private void openGender() {
        Intent i = new Intent(act, GenderEditActivity.class);
        i.putExtra("gender", gender);
        i.putExtra("pub", genderPublic);
        act.startActivity(i);
    }

    private void openLanguages() {
        Intent i = new Intent(act, LanguagesEditActivity.class);
        i.putExtra("langs", languages.toArray(new String[0]));
        i.putExtra("pub", languagesPublic);
        act.startActivity(i);
    }


    // ---- Family ----
    private static final String[] FAM_KEYS = {"father", "mother", "sister", "brother", "spouse", "son"};
    private static final String[] FAM_LABELS = {"Father", "Mother", "Sister", "Brother", "Spouse", "Son"};

    private boolean famHas(String v) {
        return v != null && !v.trim().isEmpty() && !v.trim().startsWith("(No ");
    }

    private boolean hasFamily() {
        if (familyInfo == null) return false;
        for (String k : FAM_KEYS) if (famHas(familyInfo.optString(k, ""))) return true;
        return false;
    }

    private boolean famPublic(String key) {
        JSONObject p = familyInfo == null ? null : familyInfo.optJSONObject("priv");
        return p != null && p.optBoolean(key, false);
    }

    private void renderFamily() {
        if (!hasFamily()) {
            // এখনো কিছু যোগ হয়নি → আগের মতো "Add Family" (শুধু নিজের প্রোফাইলে)
            if (!isMe) return;
            View fam = newRow(R.drawable.ic_add_family);
            fill(fam, "", "Add Family", true, false);
            if (isMe) fam.setOnClickListener(v -> openAddFamily());
            return;
        }

        View block = LayoutInflater.from(act).inflate(R.layout.item_profile_family_block, items, false);
        LinearLayout rows = block.findViewById(R.id.llFamRows);
        boolean anyPublic = false;
        int shown = 0;
        for (int i = 0; i < FAM_KEYS.length; i++) {
            String key = FAM_KEYS[i];
            boolean pub = famPublic(key);
            if (pub) anyPublic = true;
            if (!isMe && !pub) continue;                       // অন্যের প্রোফাইলে শুধু Public সারি
            String val = familyInfo.optString(key, "").trim();
            if (val.isEmpty()) continue;
            View r = LayoutInflater.from(act).inflate(R.layout.item_family_view_row, rows, false);
            String label = FAM_LABELS[i];
            if (key.equals("spouse")) {
                String sl = familyInfo.optString("spouseLabel", "");
                if (!sl.isEmpty()) label = sl;
            }
            ((TextView) r.findViewById(R.id.tvFamLabel)).setText(label);
            ((TextView) r.findViewById(R.id.tvFamValue)).setText(val);
            rows.addView(r);
            shown++;
        }
        if (shown == 0) return;                                // দেখানোর মতো কিছু নেই

        if (familyInfo.optBoolean("verified", false)) {
            TextView badge = block.findViewById(R.id.tvFamVerified);
            badge.setVisibility(View.VISIBLE);
            badge.setBackground(rounded(Color.parseColor("#4CAF50"), 12));
        }
        if (isMe) {
            block.findViewById(R.id.llFamPrivacy).setVisibility(View.VISIBLE);
            ((ImageView) block.findViewById(R.id.ivFamPrivacy)).setImageResource(
                    anyPublic ? R.drawable.ic_globe : R.drawable.ic_fam_lock);
            View edit = block.findViewById(R.id.btnFamEdit);
            edit.setVisibility(View.VISIBLE);
            edit.setOnClickListener(v -> openAddFamily());
        }
        items.addView(block);
    }

    private void openAddFamily() {
        Intent i = new Intent(act, AddFamilyActivity.class);
        act.startActivity(i);
    }

    private static boolean isValidDob(String d) {
        return d != null && d.matches("\\d{1,2}/\\d{1,2}/\\d{4}");
    }

    private static String prettyDob(String d) {
        try {
            String[] p = d.split("/");
            int day = Integer.parseInt(p[0]);
            int m = Integer.parseInt(p[1]);
            String suf = (day >= 11 && day <= 13) ? "th"
                    : day % 10 == 1 ? "st" : day % 10 == 2 ? "nd" : day % 10 == 3 ? "rd" : "th";
            return day + suf + "-" + MONTHS[m - 1] + "-" + p[2];
        } catch (Exception e) {
            return d;
        }
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

    private GradientDrawable rounded(int fill, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private int dp(int v) {
        return (int) (v * act.getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String msg) {
        TopToast.show(act, msg);
    }
}
