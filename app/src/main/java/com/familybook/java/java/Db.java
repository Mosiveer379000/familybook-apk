package com.familybook.app;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Supabase টেবিল পড়া/লেখার ছোট সহায়ক (Firebase-এর db.child(...).get()/setValue() এর বদলে)।
 * সব callback main thread-এ আসে। error == null মানে সফল।
 */
final class Db {
    private Db() { }

    interface Row { void on(JSONObject row, String error); }      // row == null হলে নেই / ব্যর্থ
    interface Rows { void on(JSONArray rows, String error); }
    interface Res { void on(String error); }

    /** একটি সারি আনে। keyCol: profiles-এ "id", বাকিতে "user_id" */
    static void get(String table, String keyCol, String id, Row cb) {
        SupabaseRest.call("GET", "/rest/v1/" + table + "?" + keyCol + "=eq." + id + "&limit=1",
                null, null, true, r -> {
                    if (!r.ok()) { cb.on(null, SupabaseRest.errorText(r)); return; }
                    try {
                        JSONArray a = new JSONArray(r.body);
                        cb.on(a.length() > 0 ? a.getJSONObject(0) : null, null);
                    } catch (Exception e) { cb.on(null, "পড়া যায়নি"); }
                });
    }

    static void getMine(String table, Row cb) {
        get(table, table.equals("profiles") ? "id" : "user_id", SupabaseRest.uid(), cb);
    }

    static void list(String path, Rows cb) {
        SupabaseRest.call("GET", "/rest/v1/" + path, null, null, true, r -> {
            if (!r.ok()) { cb.on(null, SupabaseRest.errorText(r)); return; }
            try { cb.on(new JSONArray(r.body), null); }
            catch (Exception e) { cb.on(null, "পড়া যায়নি"); }
        });
    }

    /** user_id (বা profiles-এ id) ধরে insert-or-update */
    static void upsert(String table, JSONObject row, Res cb) {
        String key = table.equals("profiles") ? "id" : "user_id";
        SupabaseRest.call("POST", "/rest/v1/" + table + "?on_conflict=" + key, row.toString(),
                "resolution=merge-duplicates,return=minimal", true,
                r -> { if (cb != null) cb.on(r.ok() ? null : SupabaseRest.errorText(r)); });
    }

    /** শর্ত অনুযায়ী আংশিক আপডেট। filter যেমন "id=eq.UID" */
    static void patch(String table, String filter, JSONObject fields, Res cb) {
        SupabaseRest.call("PATCH", "/rest/v1/" + table + "?" + filter, fields.toString(),
                "return=minimal", true,
                r -> { if (cb != null) cb.on(r.ok() ? null : SupabaseRest.errorText(r)); });
    }

    static void patchMyProfile(JSONObject fields, Res cb) {
        patch("profiles", "id=eq." + SupabaseRest.uid(), fields, cb);
    }

    /** profiles.info (jsonb)-এর কয়েকটি key মার্জ করে লেখে: আগে পড়ে, মিলিয়ে, আবার লেখে */
    static void mergeMyInfo(JSONObject changes, Res cb) {
        getMine("profiles", (p, err) -> {
            if (err != null) { cb.on(err); return; }
            try {
                JSONObject info = p != null && p.optJSONObject("info") != null
                        ? p.getJSONObject("info") : new JSONObject();
                java.util.Iterator<String> it = changes.keys();
                while (it.hasNext()) { String k = it.next(); info.put(k, changes.get(k)); }
                patchMyProfile(new JSONObject().put("info", info), cb);
            } catch (Exception e) { cb.on(e.getMessage()); }
        });
    }

    static String s(JSONObject o, String k) {
        if (o == null || o.isNull(k)) return "";
        return o.optString(k, "");
    }
}
