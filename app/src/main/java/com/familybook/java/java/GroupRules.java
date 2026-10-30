package com.familybook.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * গ্রুপের নিয়ম (post_privacy / post_mode) — গ্রুপ-ভিত্তিক ক্যাশ।
 * PostAdapter লাইক/কমেন্ট/শেয়ার বন্ধ আছে কিনা এখান থেকে জানে।
 */
public final class GroupRules {
    private GroupRules() { }

    public interface Done { void run(); }

    private static final Map<String, String> privacy = new HashMap<>();   // gid -> open | view_only
    private static final Map<String, String> mode = new HashMap<>();      // gid -> auto | admin_approval | admins_only
    private static final Set<String> loading = new HashSet<>();

    /** জানা থাকলে true/false; না জানলে false (পরে load() শেষে রিফ্রেশ হবে) */
    public static boolean viewOnly(String gid) {
        return gid != null && "view_only".equals(privacy.get(gid));
    }

    public static boolean known(String gid) {
        return gid != null && privacy.containsKey(gid);
    }

    public static void put(String gid, String postPrivacy) {
        if (gid != null) privacy.put(gid, postPrivacy == null || postPrivacy.isEmpty() ? "open" : postPrivacy);
    }

    public static void put(String gid, String postPrivacy, String postMode) {
        put(gid, postPrivacy);
        if (gid != null) mode.put(gid, postMode == null || postMode.isEmpty() ? "auto" : postMode);
    }

    /** auto | admin_approval | admins_only (জানা না থাকলে auto) */
    public static String postMode(String gid) {
        String m = gid == null ? null : mode.get(gid);
        return m == null ? "auto" : m;
    }

    /** একবারই আনে; শেষে done চলে */
    public static void load(final String gid, final Done done) {
        if (gid == null || known(gid) || !loading.add(gid)) return;
        SupabaseRest.call("GET", "/rest/v1/groups?id=eq." + gid + "&select=post_privacy,post_mode&limit=1",
                null, null, true, r -> {
            loading.remove(gid);
            String v = "open";
            if (r.ok()) {
                try {
                    JSONArray a = new JSONArray(r.body);
                    JSONObject o = a.length() > 0 ? a.optJSONObject(0) : null;
                    if (o != null) {
                        String s = Db.s(o, "post_privacy");
                        if (!s.isEmpty()) v = s;
                        String m = Db.s(o, "post_mode");
                        mode.put(gid, m.isEmpty() ? "auto" : m);
                    }
                } catch (Exception ignored) { }
                privacy.put(gid, v);
                if (done != null) done.run();
            }
        });
    }
}
