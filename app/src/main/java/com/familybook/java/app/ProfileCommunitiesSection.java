package com.familybook.app;

import android.app.AlertDialog;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.ComponentActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Profile info পেজের "Communities" সেকশন।
 * - Communities সারিতে চাপলে খোলে/বন্ধ হয় (তীর ঘোরে)
 * - খুললে: শুরুতে শুধু দুটি অপশন; গ্রুপ যোগ করার পরেই তার নাম দেখায়। নামগুলো (চাপলে গ্রুপ খোলে, ✕ দিলে সরে), শেষে দুটি অপশন:
 *     "Add group"  → আমার বানানো ও জয়েন করা সব গ্রুপের তালিকা; টিক দিয়ে Save করলে এখানে নাম দেখায়
 *     "Add pages"  → আপাতত Demo
 * - ডাটা: profiles.info.communities = [{id, name}]
 *
 * ব্যবহার (ProfileInfoEditActivity.onCreate-এ):
 *   new ProfileCommunitiesSection(this, findViewById(R.id.rowCommunities), uid).attach();
 */
public class ProfileCommunitiesSection {

    private final ComponentActivity act;
    private final View rowCommunities;
    private final String uid;
    private final boolean isMe;

    private final ArrayList<String[]> items = new ArrayList<>();   // {groupId, name}
    private boolean loaded = false;
    private boolean open = false;

    private View section;
    private LinearLayout list;
    private ImageView arrow;

    public ProfileCommunitiesSection(ComponentActivity act, View rowCommunities, String uid) {
        this.act = act;
        this.rowCommunities = rowCommunities;
        String me = Me.uid();
        this.uid = uid != null ? uid : me;
        this.isMe = this.uid != null && this.uid.equals(me);
    }

    public void attach() {
        ViewGroup parent = (ViewGroup) rowCommunities.getParent();
        int idx = parent.indexOfChild(rowCommunities);
        section = LayoutInflater.from(act).inflate(R.layout.layout_profile_communities_section, parent, false);
        section.setVisibility(View.GONE);
        parent.addView(section, idx + 1);
        list = section.findViewById(R.id.llCommunityItems);
        arrow = lastImageView(rowCommunities);

        bindClick(rowCommunities, v -> onRowTap());
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
        Db.get("profiles", "id", uid, (row, err) -> {
            if (act.isFinishing()) return;
            if (err != null) {
                TopToast.show(act, err);
                return;
            }
            items.clear();
            JSONObject info = row != null ? row.optJSONObject("info") : null;
            JSONArray a = info != null ? info.optJSONArray("communities") : null;
            if (a != null) {
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o == null) continue;
                    String id = o.optString("id", "").trim();
                    if (id.isEmpty()) continue;
                    items.add(new String[]{id, o.optString("name", "").trim()});
                }
            }
            loaded = true;
            render();
        });
    }

    private void save(final Runnable onOk) {
        try {
            JSONArray a = new JSONArray();
            for (String[] it : items) a.put(new JSONObject().put("id", it[0]).put("name", it[1]));
            Db.mergeMyInfo(new JSONObject().put("communities", a), err -> {
                if (act.isFinishing()) return;
                if (err != null) {
                    TopToast.show(act, err);
                    load();                       // সার্ভারের আসল অবস্থা আবার দেখাই
                    return;
                }
                if (onOk != null) onOk.run();
            });
        } catch (Exception e) {
            TopToast.show(act, "Could not save");
        }
    }

    // ---- আঁকা ----
    private void render() {
        list.removeAllViews();
        LayoutInflater inf = LayoutInflater.from(act);

        for (int i = 0; i < items.size(); i++) {
            final String[] it = items.get(i);
            View row = inf.inflate(R.layout.item_profile_community_row, list, false);
            ((TextView) row.findViewById(R.id.tvCommunityName)).setText(it[1].isEmpty() ? "Group" : it[1]);
            TextView sub = row.findViewById(R.id.tvCommunitySub);
            sub.setText("Group");
            sub.setVisibility(View.VISIBLE);
            View.OnClickListener openGroup = v -> GroupDetailActivity.open(act, it[0]);
            row.setOnClickListener(openGroup);
            row.findViewById(R.id.llCommunityText).setOnClickListener(openGroup);

            View rm = row.findViewById(R.id.btnRemoveCommunity);
            rm.setVisibility(isMe ? View.VISIBLE : View.GONE);
            rm.setOnClickListener(v -> {
                items.remove(it);
                save(null);
                render();
            });
            list.addView(row);
        }

        if (isMe) {
            list.addView(actionRow("Add group", v -> showGroupPicker()));
            list.addView(actionRow("Add pages", v -> TopToast.show(act, "Add pages \u2014 coming soon (Demo)")));
        }

        section.setVisibility(open ? View.VISIBLE : View.GONE);
        if (arrow != null) arrow.animate().rotation(open ? 180f : 0f).setDuration(150).start();
    }

    private View actionRow(String label, View.OnClickListener l) {
        View row = LayoutInflater.from(act).inflate(R.layout.item_profile_community_row, list, false);
        ((ImageView) row.findViewById(R.id.imgCommunity)).setImageResource(R.drawable.ic_gp_plus);
        TextView t = row.findViewById(R.id.tvCommunityName);
        t.setText(label);
        t.setTextColor(0xFF1877F2);
        row.setOnClickListener(l);
        row.findViewById(R.id.llCommunityText).setOnClickListener(l);
        return row;
    }

    // ---- Add group: আমার সব গ্রুপের তালিকা ----
    private void showGroupPicker() {
        GroupsRepo.load(uid, (groups, err) -> {
            if (act.isFinishing()) return;
            if (err != null) { TopToast.show(act, err); return; }
            if (groups == null || groups.isEmpty()) {
                TopToast.show(act, "You have no groups yet");
                return;
            }
            final List<GroupItem> gs = new ArrayList<>(groups);
            Collections.sort(gs, new Comparator<GroupItem>() {
                @Override public int compare(GroupItem a, GroupItem b) {
                    if (a.isAdmin != b.isAdmin) return a.isAdmin ? -1 : 1;
                    String an = a.name == null ? "" : a.name, bn = b.name == null ? "" : b.name;
                    return an.compareToIgnoreCase(bn);
                }
            });

            final String[] labels = new String[gs.size()];
            final boolean[] checked = new boolean[gs.size()];
            for (int i = 0; i < gs.size(); i++) {
                GroupItem g = gs.get(i);
                labels[i] = (g.name == null ? "Group" : g.name) + (g.isAdmin ? "  (Admin)" : "");
                checked[i] = contains(g.id);
            }

            new AlertDialog.Builder(act)
                    .setTitle("Add group")
                    .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> checked[which] = isChecked)
                    .setPositiveButton("Save", (d, w) -> {
                        for (int i = 0; i < gs.size(); i++) {
                            GroupItem g = gs.get(i);
                            int at = indexOf(g.id);
                            if (checked[i] && at < 0) items.add(new String[]{g.id, g.name == null ? "" : g.name});
                            else if (!checked[i] && at >= 0) items.remove(at);
                        }
                        open = true;
                        save(null);
                        render();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });
    }

    private boolean contains(String id) { return indexOf(id) >= 0; }

    private int indexOf(String id) {
        for (int i = 0; i < items.size(); i++) if (items.get(i)[0].equals(id)) return i;
        return -1;
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
