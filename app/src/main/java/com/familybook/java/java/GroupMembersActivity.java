package com.familybook.app;

import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * গ্রুপের সব সদস্যের তালিকা ("See all")। সবাই দেখতে পারে;
 * Admin/Moderator প্রতিটি সারির ⋮ দিয়ে Moderator বানানো/সরানো ও গ্রুপ থেকে বাদ দিতে পারে।
 */
public class GroupMembersActivity extends BaseActivity {

    private String gid, gname, myUid, myRole = "";
    private final Map<String, String> roles = new LinkedHashMap<>();
    private final Map<String, String> names = new HashMap<>();
    private LinearLayout list;
    private TextView tvTitle;
    private int cText, cSub, cBg;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        myUid = SupabaseRest.uid();
        gid = getIntent().getStringExtra("gid");
        gname = getIntent().getStringExtra("gname");
        if (!SupabaseRest.isLoggedIn() || myUid == null || gid == null) { finish(); return; }
        cText = ContextCompat.getColor(this, R.color.h_text);
        cSub = ContextCompat.getColor(this, R.color.h_text_sub);
        cBg = ContextCompat.getColor(this, R.color.h_bg);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(cBg);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        ImageView back = new ImageView(this);
        back.setImageResource(R.drawable.ic_gp_back);
        back.setPadding(dp(10), dp(10), dp(10), dp(10));
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(52)));
        tvTitle = new TextView(this);
        tvTitle.setText("Members");
        tvTitle.setTextColor(cText);
        tvTitle.setTextSize(22);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1f);
        tp.setMarginStart(dp(6));
        bar.addView(tvTitle, tp);
        root.addView(bar, new LinearLayout.LayoutParams(-1, dp(56)));
        View line = new View(this);
        line.setBackgroundColor(0xFFE4E6EB);
        root.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));

        ScrollView sv = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(14), dp(6), dp(14), dp(30));
        sv.addView(list, new ViewGroup.LayoutParams(-1, -2));
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
        load();
    }

    private boolean isAdmin() { return "admin".equals(myRole); }
    private boolean isManager() { return "admin".equals(myRole) || "moderator".equals(myRole); }

    private void load() {
        SupabaseRest.call("GET", "/rest/v1/group_members?group_id=eq." + gid
                + "&status=eq.active&select=user_id,role&limit=3000", null, null, true, r -> {
            if (isFinishing()) return;
            if (!r.ok()) { TopToast.show(this, SupabaseRest.errorText(r)); return; }
            List<String> admins = new ArrayList<>(), mods = new ArrayList<>(), mem = new ArrayList<>();
            Map<String, String> tmp = new HashMap<>();
            JSONArray a = arr(r.body);
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                String uid = Db.s(o, "user_id"), role = Db.s(o, "role");
                if (uid.isEmpty()) continue;
                if (role.isEmpty()) role = "member";
                tmp.put(uid, role);
                if ("admin".equals(role)) admins.add(uid);
                else if ("moderator".equals(role)) mods.add(uid);
                else mem.add(uid);
            }
            roles.clear();
            for (String u : admins) roles.put(u, "admin");
            for (String u : mods) roles.put(u, "moderator");
            for (String u : mem) roles.put(u, "member");
            myRole = tmp.containsKey(myUid) ? tmp.get(myUid) : "";
            tvTitle.setText((gname == null || gname.isEmpty() ? "Members" : "Members") + " \u00B7 " + roles.size());
            fetchNames(new ArrayList<>(roles.keySet()), this::render);
        });
    }

    private void render() {
        list.removeAllViews();
        for (Map.Entry<String, String> e : roles.entrySet()) list.addView(memberRow(e.getKey(), e.getValue()));
    }

    private View memberRow(final String uid, final String role) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(9), 0, dp(9));

        ImageView av = new ImageView(this);
        row.addView(av, new LinearLayout.LayoutParams(dp(48), dp(48)));
        AvatarLoader.load(av, uid);

        TextView nm = new TextView(this);
        nm.setText(nameOf(uid) + (uid.equals(myUid) ? " (You)" : ""));
        nm.setTextSize(18);
        nm.setTypeface(Typeface.DEFAULT_BOLD);
        nm.setTextColor(cText);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(0, -2, 1f);
        np.setMarginStart(dp(12));
        row.addView(nm, np);

        if (!"member".equals(role)) {
            TextView chip = new TextView(this);
            boolean adm = "admin".equals(role);
            chip.setText(adm ? "Admin" : "Moderator");
            chip.setTextSize(13);
            chip.setTypeface(Typeface.DEFAULT_BOLD);
            chip.setTextColor(adm ? 0xFF1877F2 : 0xFF1B7F3B);
            GradientDrawable g = new GradientDrawable();
            g.setColor(adm ? 0xFFE1ECFD : 0x261B7F3B);
            g.setCornerRadius(dp(10));
            chip.setBackground(g);
            chip.setPadding(dp(9), dp(2), dp(9), dp(2));
            row.addView(chip);
        }

        // ⋮ — কার ওপর কী করা যাবে
        final List<String> acts = actionsFor(uid, role);
        if (!acts.isEmpty()) {
            TextView menu = new TextView(this);
            menu.setText("\u22EE");
            menu.setTextSize(24);
            menu.setTypeface(Typeface.DEFAULT_BOLD);
            menu.setTextColor(cText);
            menu.setGravity(Gravity.CENTER);
            menu.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle(nameOf(uid))
                    .setItems(acts.toArray(new String[0]), (d, w) -> doAction(uid, acts.get(w))).show());
            row.addView(menu, new LinearLayout.LayoutParams(dp(40), dp(40)));
        }
        row.setOnClickListener(v -> ProfileActivity.open(this, uid, nameOf(uid)));
        return row;
    }

    private List<String> actionsFor(String uid, String role) {
        List<String> a = new ArrayList<>();
        if (!isManager() || uid.equals(myUid) || "admin".equals(role)) return a;
        if (isAdmin()) a.add("moderator".equals(role) ? "Remove as moderator" : "Make moderator");
        if (isAdmin() || "member".equals(role)) { a.add("Remove from group"); a.add("Remove & block"); }
        return a;
    }

    private void doAction(final String uid, String act) {
        if ("Make moderator".equals(act)) setRole(uid, "moderator");
        else if ("Remove as moderator".equals(act)) setRole(uid, "member");
        else if ("Remove from group".equals(act)) {
            new AlertDialog.Builder(this).setTitle("Remove " + nameOf(uid) + "?")
                    .setMessage("This member will be removed from the group.")
                    .setPositiveButton("Remove", (d, w) -> removeMember(uid))
                    .setNegativeButton("Cancel", null).show();
        } else if ("Remove & block".equals(act)) {
            new AlertDialog.Builder(this).setTitle("Remove & block " + nameOf(uid) + "?")
                    .setMessage("This member will be removed and won\'t be able to join the group again.")
                    .setPositiveButton("Block", (d, w) -> blockThenRemove(uid))
                    .setNegativeButton("Cancel", null).show();
        }
    }

    private void blockThenRemove(final String uid) {
        try {
            String me = SupabaseRest.uid();
            org.json.JSONObject b = new org.json.JSONObject()
                    .put("group_id", gid).put("user_id", uid).put("blocked_by", me);
            SupabaseRest.call("POST", "/rest/v1/group_blocks", b.toString(), "return=minimal", true, r -> {
                if (isFinishing()) return;
                if (!r.ok()) { TopToast.show(this, SupabaseRest.errorText(r)); return; }
                removeMember(uid);
            });
        } catch (Exception e) {
            TopToast.show(this, "Could not block");
        }
    }

    private void setRole(final String uid, final String role) {
        SupabaseRest.call("PATCH", "/rest/v1/group_members?group_id=eq." + gid + "&user_id=eq." + uid,
                "{\"role\":\"" + role + "\"}", "return=representation", true, r -> {
                    if (isFinishing()) return;
                    JSONArray a = r.ok() ? arr(r.body) : null;
                    if (a == null || a.length() == 0) { TopToast.show(this, r.ok() ? "Permission denied" : SupabaseRest.errorText(r)); return; }
                    TopToast.show(this, "moderator".equals(role) ? "Made moderator" : "Removed as moderator");
                    load();
                });
    }

    private void removeMember(final String uid) {
        SupabaseRest.call("DELETE", "/rest/v1/group_members?group_id=eq." + gid + "&user_id=eq." + uid,
                null, "return=representation", true, r -> {
                    if (isFinishing()) return;
                    JSONArray a = r.ok() ? arr(r.body) : null;
                    if (a == null || a.length() == 0) { TopToast.show(this, r.ok() ? "Permission denied" : SupabaseRest.errorText(r)); return; }
                    TopToast.show(this, "Member removed");
                    load();
                });
    }

    private String nameOf(String uid) {
        String n = names.get(uid);
        return n == null || n.isEmpty() ? "Member" : n;
    }

    private void fetchNames(List<String> uids, final Runnable done) {
        final List<String> need = new ArrayList<>();
        for (String u : uids) if (!names.containsKey(u)) need.add(u);
        if (need.isEmpty()) { done.run(); return; }
        final int CHUNK = 60;
        final int[] left = {(need.size() + CHUNK - 1) / CHUNK};
        for (int s = 0; s < need.size(); s += CHUNK) {
            StringBuilder in = new StringBuilder();
            for (int i = s; i < Math.min(need.size(), s + CHUNK); i++) {
                if (in.length() > 0) in.append(',');
                in.append(need.get(i));
            }
            SupabaseRest.call("GET", "/rest/v1/profiles?id=in.(" + in + ")&select=id,name", null, null, true, r -> {
                if (r.ok()) {
                    JSONArray a = arr(r.body);
                    for (int i = 0; a != null && i < a.length(); i++) {
                        JSONObject o = a.optJSONObject(i);
                        if (o != null) names.put(Db.s(o, "id"), Db.s(o, "name"));
                    }
                }
                if (--left[0] == 0 && !isFinishing()) done.run();
            });
        }
    }

    private static JSONArray arr(String body) {
        try { return new JSONArray(body); } catch (Exception e) { return null; }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
