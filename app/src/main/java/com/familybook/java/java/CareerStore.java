package com.familybook.app;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Work ও Education এন্ট্রির ডাটা সহায়ক।
 *   profiles.info.work_list      = [{name, title, current, sy,sm,sd, ey,em,ed, loc, desc, priv}]
 *   profiles.info.education_list = [{type:"college", name, sy..ed, grad, desc, priv}
 *                                  | {type:"school", name, gy, priv}]
 * (পুরনো info.work / info.school / info.college-এর সাথে মেলে না — আলাদা key)
 */
final class CareerStore {
    private CareerStore() { }

    static final String WORK = "work_list", EDU = "education_list";
    static final int MAX = 20;

    interface Loaded { void on(JSONArray list, String error); }

    /** কারও তালিকা আনে (খালি হলে খালি অ্যারে) */
    static void load(String uid, String key, Loaded cb) {
        Db.get("profiles", "id", uid, (row, err) -> {
            if (err != null) { cb.on(new JSONArray(), err); return; }
            JSONObject info = row != null ? row.optJSONObject("info") : null;
            JSONArray a = info != null ? info.optJSONArray(key) : null;
            cb.on(a != null ? a : new JSONArray(), null);
        });
    }

    /** index >= 0 হলে ওই এন্ট্রি বদলায়, নইলে নতুন যোগ করে (সার্ভারের সর্বশেষ তালিকার ওপর) */
    static void upsert(String key, int index, JSONObject entry, Db.Res cb) {
        Db.getMine("profiles", (p, err) -> {
            if (err != null) { cb.on(err); return; }
            try {
                JSONArray a = copy(p, key);
                if (index >= 0 && index < a.length()) a.put(index, entry);
                else {
                    if (a.length() >= MAX) { cb.on("You can add up to " + MAX + " items"); return; }
                    a.put(entry);
                }
                Db.mergeMyInfo(new JSONObject().put(key, a), cb);
            } catch (Exception e) {
                cb.on(e.getMessage() == null ? "Could not save" : e.getMessage());
            }
        });
    }

    static void remove(String key, int index, Db.Res cb) {
        Db.getMine("profiles", (p, err) -> {
            if (err != null) { cb.on(err); return; }
            try {
                JSONArray old = copy(p, key), a = new JSONArray();
                for (int i = 0; i < old.length(); i++) if (i != index) a.put(old.get(i));
                Db.mergeMyInfo(new JSONObject().put(key, a), cb);
            } catch (Exception e) {
                cb.on(e.getMessage() == null ? "Could not save" : e.getMessage());
            }
        });
    }

    private static JSONArray copy(JSONObject profile, String key) throws Exception {
        JSONObject info = profile != null ? profile.optJSONObject("info") : null;
        JSONArray old = info != null ? info.optJSONArray(key) : null;
        JSONArray a = new JSONArray();
        for (int i = 0; old != null && i < old.length(); i++) a.put(old.get(i));
        return a;
    }

    // ------------------------------------------------------------ দেখানোর লেখা

    static String fmtDate(int y, int m, int d) {
        if (y <= 0) return "";
        if (m <= 0) return String.valueOf(y);
        String mon = DateTriple.MONTHS[m - 1].substring(0, 3);
        return d > 0 ? d + " " + mon + " " + y : mon + " " + y;
    }

    /** "Mar 2024 – Present" */
    static String range(JSONObject e) {
        String s = fmtDate(e.optInt("sy"), e.optInt("sm"), e.optInt("sd"));
        String t = e.optBoolean("current", false) ? "Present"
                : fmtDate(e.optInt("ey"), e.optInt("em"), e.optInt("ed"));
        if (s.isEmpty()) return t.isEmpty() ? "" : "Until " + t;
        return t.isEmpty() ? s : s + " \u2013 " + t;
    }

    static boolean isSchool(JSONObject e) { return "school".equals(e.optString("type")); }

    /** তালিকার প্রথম লাইন */
    static String title(String key, JSONObject e) {
        String name = e.optString("name", "").trim();
        if (WORK.equals(key)) {
            String job = e.optString("title", "").trim();
            return job.isEmpty() ? name : job + " at " + name;
        }
        return name;
    }

    /** তালিকার দ্বিতীয় লাইন (ফাঁকা হতে পারে) */
    static String sub(String key, JSONObject e) {
        StringBuilder sb = new StringBuilder();
        if (WORK.equals(key)) {
            add(sb, range(e));
            add(sb, e.optString("loc", "").trim());
        } else if (isSchool(e)) {
            add(sb, "High school");
            if (e.optInt("gy") > 0) add(sb, "Class of " + e.optInt("gy"));
        } else {
            add(sb, "College");
            add(sb, range(e));
            if (e.optBoolean("grad", false)) add(sb, "Graduated");
        }
        return sb.toString();
    }

    private static void add(StringBuilder sb, String s) {
        if (s == null || s.isEmpty()) return;
        if (sb.length() > 0) sb.append(" \u00B7 ");
        sb.append(s);
    }
}
