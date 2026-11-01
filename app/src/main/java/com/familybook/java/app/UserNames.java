package com.familybook.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ইউজারের নাম (profiles টেবিল থেকে) ক্যাশ করে রাখে।
 * পোস্ট করার সময় নাম না পাওয়া গেলে (author_name ফাঁকা) এখান থেকে নাম এনে বসানো হয়,
 * তাই GIF সহ কোনো পোস্টেই নাম আর হারায় না।
 */
final class UserNames {

    interface Cb { void on(String name); }

    private static final Map<String, String> CACHE = new HashMap<>();
    private static final Map<String, List<Cb>> WAIT = new HashMap<>();
    private static final String[] KEYS = {"full_name", "name", "display_name", "username"};

    private UserNames() { }

    /** ক্যাশে থাকলে নাম, নইলে null */
    static String cached(String uid) {
        return uid == null ? null : CACHE.get(uid);
    }

    /** নাম আনে (ক্যাশে থাকলে সাথে সাথে); না পেলে cb-তে null আসে */
    static void fetch(final String uid, final Cb cb) {
        if (uid == null || uid.isEmpty()) { cb.on(null); return; }
        String c = CACHE.get(uid);
        if (c != null) { cb.on(c); return; }
        List<Cb> w = WAIT.get(uid);
        if (w != null) { w.add(cb); return; }       // একই ইউজারের রিকোয়েস্ট আগে থেকেই চলছে
        w = new ArrayList<>();
        w.add(cb);
        WAIT.put(uid, w);
        query(uid, "id", true);
    }

    private static void query(final String uid, final String col, final boolean retry) {
        SupabaseRest.call("GET", "/rest/v1/profiles?" + col + "=eq." + uid + "&select=*&limit=1",
                null, null, true, r -> {
                    String name = null;
                    if (r.ok()) {
                        try {
                            JSONArray a = new JSONArray(r.body);
                            if (a.length() > 0) name = pick(a.getJSONObject(0));
                        } catch (Exception ignored) { }
                    }
                    if (name == null && retry) { query(uid, "user_id", false); return; }
                    if (name != null) CACHE.put(uid, name);
                    List<Cb> w = WAIT.remove(uid);
                    if (w != null) for (Cb cb : w) cb.on(name);
                });
    }

    private static String pick(JSONObject p) {
        for (String k : KEYS) {
            if (p.isNull(k)) continue;
            String v = p.optString(k, "").trim();
            if (!v.isEmpty() && !"null".equals(v)) return v;
        }
        return null;
    }
}
