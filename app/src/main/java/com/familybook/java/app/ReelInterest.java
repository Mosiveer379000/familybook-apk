package com.familybook.app;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Shorts মেনুর "Interested" / "Not interested": প্রতিটি ভিডিও-নির্মাতার জন্য আমার পছন্দ।
 *  - Interested     → ওই নির্মাতার ভিডিও (নতুন আপলোড সহ) আমার Shorts সেকশনে সবার আগে আসে
 *  - Not interested → ওই নির্মাতার ভিডিও আমার Shorts সেকশনে আর আসে না
 * সংরক্ষণ: profiles.info.reelInterest = { authorUid: 1 | -1 }
 */
final class ReelInterest {
    private static final Map<String, Integer> MAP = new HashMap<>();
    static boolean loaded = false;

    private ReelInterest() { }

    static void load(final Runnable done) {
        String me = SupabaseRest.uid();
        if (me == null) { loaded = true; done.run(); return; }
        Db.get("profiles", "id", me, (row, err) -> {
            if (err == null) {
                MAP.clear();
                JSONObject info = row == null ? null : row.optJSONObject("info");
                JSONObject m = info == null ? null : info.optJSONObject("reelInterest");
                if (m != null) {
                    Iterator<String> it = m.keys();
                    while (it.hasNext()) { String k = it.next(); MAP.put(k, m.optInt(k, 0)); }
                }
                loaded = true;
            }
            done.run();
        });
    }

    static int of(String uid) {
        Integer v = uid == null ? null : MAP.get(uid);
        return v == null ? 0 : v;
    }

    static Set<String> with(int value) {
        Set<String> s = new HashSet<>();
        for (Map.Entry<String, Integer> e : MAP.entrySet()) if (e.getValue() == value) s.add(e.getKey());
        return s;
    }

    /** value: 1 = Interested, -1 = Not interested, 0 = সরাও */
    static void set(String authorUid, int value) {
        if (authorUid == null) return;
        if (value == 0) MAP.remove(authorUid); else MAP.put(authorUid, value);
        try {
            JSONObject m = new JSONObject();
            for (Map.Entry<String, Integer> e : MAP.entrySet()) m.put(e.getKey(), e.getValue());
            Db.mergeMyInfo(new JSONObject().put("reelInterest", m), e -> { });
        } catch (Exception ignored) { }
    }
}
