package com.familybook.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * গ্রুপের ডেটা (Supabase: groups, group_members) ও হাতে-করা পিন (ফোনে)।
 * টেবিল বানাতে sql/08_groups.sql একবার Supabase SQL Editor-এ চালাতে হবে।
 */
public final class GroupsRepo {

    public static final int MAX_PINS = 3;

    public interface Callback {
        /** error == null মানে সফল */
        void onLoaded(List<GroupItem> groups, String error);
    }

    private GroupsRepo() { }

    // ---------- সার্ভার ----------

    /** আমার তৈরি করা ও জয়েন করা সব গ্রুপ (সদস্য সংখ্যা সহ)। */
    public static void load(String uid, final Callback cb) {
        String path = "group_members?user_id=eq." + uid + "&status=eq.active"
                + "&select=role,groups(id,name,category,location,description,visibility,created_at)";
        Db.list(path, (rows, err) -> {
            if (err != null) { cb.onLoaded(new ArrayList<GroupItem>(), err); return; }

            final List<GroupItem> out = new ArrayList<>();
            final Map<String, GroupItem> byId = new HashMap<>();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) continue;
                JSONObject g = row.optJSONObject("groups");
                if (g == null) continue;
                GroupItem it = new GroupItem();
                it.id = Db.s(g, "id");
                it.name = Db.s(g, "name");
                it.category = Db.s(g, "category");
                it.location = Db.s(g, "location");
                it.description = Db.s(g, "description");
                it.createdAt = Db.s(g, "created_at");
                it.visibility = Db.s(g, "visibility");
                it.isPublic = "public".equals(it.visibility);
                it.isAdmin = "admin".equals(Db.s(row, "role"));
                if (it.id.isEmpty() || byId.containsKey(it.id)) continue;
                byId.put(it.id, it);
                out.add(it);
            }
            if (out.isEmpty()) { cb.onLoaded(out, null); return; }

            // সদস্য সংখ্যা: অ্যাডমিন বাদে member ও moderator (শুধু active)
            StringBuilder ids = new StringBuilder();
            for (GroupItem g : out) {
                if (ids.length() > 0) ids.append(',');
                ids.append(g.id);
            }
            Db.list("group_members?group_id=in.(" + ids + ")&role=in.(member,moderator)&status=eq.active&select=group_id",
                    (mrows, merr) -> {
                        if (mrows != null) {
                            for (int i = 0; i < mrows.length(); i++) {
                                JSONObject m = mrows.optJSONObject(i);
                                if (m == null) continue;
                                GroupItem g = byId.get(Db.s(m, "group_id"));
                                if (g != null) g.members++;
                            }
                        }
                        attachLatestPosts(ids.toString(), byId, out, cb);
                    });
        });
    }

    /** "From your groups": প্রতি গ্রুপের সবচেয়ে নতুন একটি পোস্ট (পোস্টকারীর নামসহ) */
    private static void attachLatestPosts(String ids, final Map<String, GroupItem> byId,
                                          final List<GroupItem> out, final Callback cb) {
        Db.list("post_cards_g?group_id=in.(" + ids + ")&status=eq.active"
                + "&select=id,group_id,author_name,text,created_at&order=created_at.desc&limit=300",
                (prows, perr) -> {
                    if (prows != null) {
                        for (int i = 0; i < prows.length(); i++) {
                            JSONObject o = prows.optJSONObject(i);
                            if (o == null) continue;
                            GroupItem g = byId.get(Db.s(o, "group_id"));
                            if (g == null || g.lastPostTime > 0) continue;   // প্রথমটিই সবচেয়ে নতুন
                            g.lastPostId = Db.s(o, "id");
                            String t = Db.s(o, "text");
                            g.lastPostText = (t == null || t.trim().isEmpty()) ? "\uD83D\uDCF7 Photo" : t.trim();
                            g.lastPostAuthor = Db.s(o, "author_name");
                            g.lastPostTime = NotificationItem.parseIso(Db.s(o, "created_at"));
                        }
                    }
                    cb.onLoaded(out, null);
                });
    }

    /** নতুন গ্রুপ তৈরি। নির্মাতা নিজে থেকেই admin সদস্য হয় (ডেটাবেস trigger)। error == null মানে সফল। */
    public static void create(String uid, String name, String category, String location,
                              String description, String visibility, Db.Res cb) {
        try {
            JSONObject b = new JSONObject();
            b.put("owner_id", uid);
            b.put("name", name);
            b.put("category", category == null || category.isEmpty() ? JSONObject.NULL : category);
            b.put("location", location == null || location.isEmpty() ? JSONObject.NULL : location);
            b.put("description", description == null || description.isEmpty() ? JSONObject.NULL : description);
            b.put("visibility", visibility == null || visibility.isEmpty() ? "public" : visibility);
            SupabaseRest.call("POST", "/rest/v1/groups", b.toString(), "return=minimal", true,
                    r -> cb.on(r.ok() ? null : SupabaseRest.errorText(r)));
        } catch (Exception e) {
            cb.on(e.getMessage() == null ? "তৈরি করা যায়নি" : e.getMessage());
        }
    }

    // ---------- হাতে-করা পিন (ফোনে) ----------

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("groups_pref", Context.MODE_PRIVATE);
    }

    /** হাতে পিন করা গ্রুপের id, যে ক্রমে পিন করা হয়েছে। */
    public static List<String> pinned(Context c, String uid) {
        List<String> out = new ArrayList<>();
        String raw = prefs(c).getString("pins_" + uid, "");
        if (raw == null || raw.isEmpty()) return out;
        for (String s : raw.split(",")) {
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    private static void savePins(Context c, String uid, List<String> ids) {
        StringBuilder sb = new StringBuilder();
        for (String id : ids) {
            if (sb.length() > 0) sb.append(',');
            sb.append(id);
        }
        prefs(c).edit().putString("pins_" + uid, sb.toString()).apply();
    }

    /** পিন চালু/বন্ধ। ৩টির বেশি হলে false (পিন হয়নি)। নিজের (admin) গ্রুপ আগে থেকেই সবসময় পিন। */
    public static boolean togglePin(Context c, String uid, String groupId) {
        List<String> ids = pinned(c, uid);
        if (ids.contains(groupId)) {
            ids.remove(groupId);
            savePins(c, uid, ids);
            return true;
        }
        if (ids.size() >= MAX_PINS) return false;
        ids.add(groupId);
        savePins(c, uid, ids);
        return true;
    }
}
