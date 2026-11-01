package com.familybook.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * কমেন্টের "@" মেনশন তালিকা: আমার সব ফলোয়ার (নাম + তাদের ফলোয়ার সংখ্যা)।
 * ডেটা: follows (follower_id, followee_id) ও profiles (id, name)। একবার এনে ক্যাশে রাখে।
 */
final class MentionRepo {

    static final class Person {
        String uid, name;
        int followers;
        String sub() { return "Profile \u00B7 " + compact(followers) + " followers"; }
    }

    interface Cb { void on(List<Person> people); }

    private static final List<Person> CACHE = new ArrayList<>();
    private static String cacheFor = null;
    private static boolean loading = false;
    private static final List<Cb> WAIT = new ArrayList<>();

    private MentionRepo() { }

    static boolean loaded() {
        return cacheFor != null && cacheFor.equals(SupabaseRest.uid());
    }

    static List<Person> cached() {
        return loaded() ? new ArrayList<>(CACHE) : new ArrayList<Person>();
    }

    /** আমার সব ফলোয়ারের uid (@followers নোটিফিকেশনের জন্য) */
    static List<String> followerIds() {
        List<String> l = new ArrayList<>();
        for (Person p : cached()) l.add(p.uid);
        return l;
    }

    static String compact(int n) {
        if (n < 1000) return String.valueOf(n);
        if (n < 1000000) {
            double k = n / 1000.0;
            return (k >= 100 ? String.valueOf(Math.round(k)) : String.format(Locale.US, "%.1f", k).replace(".0", "")) + "K";
        }
        double m = n / 1000000.0;
        return String.format(Locale.US, "%.1f", m).replace(".0", "") + "M";
    }

    static void load(final Cb cb) {
        final String me = SupabaseRest.uid();
        if (me == null) { cb.on(new ArrayList<Person>()); return; }
        if (loaded()) { cb.on(cached()); return; }
        synchronized (WAIT) {
            if (cb != null) WAIT.add(cb);
            if (loading) return;
            loading = true;
        }
        SupabaseRest.call("GET", "/rest/v1/follows?followee_id=eq." + me + "&select=follower_id&limit=5000",
                null, null, true, r -> {
            final List<String> ids = new ArrayList<>();
            if (r.ok()) {
                try {
                    JSONArray a = new JSONArray(r.body);
                    for (int i = 0; i < a.length(); i++) {
                        String f = a.getJSONObject(i).optString("follower_id", "");
                        if (!f.isEmpty() && !f.equals(me)) ids.add(f);
                    }
                } catch (Exception ignored) { }
            }
            if (ids.isEmpty()) { finish(me, new ArrayList<Person>()); return; }
            loadNames(me, ids);
        });
    }

    private static void loadNames(final String me, final List<String> ids) {
        final Map<String, Person> byId = new HashMap<>();
        final List<Person> all = new ArrayList<>();
        final List<List<String>> chunks = chunk(ids, 80);
        final int[] left = {chunks.size()};
        for (List<String> ch : chunks) {
            SupabaseRest.call("GET", "/rest/v1/profiles?id=in.(" + android.text.TextUtils.join(",", ch) + ")&select=id,name",
                    null, null, true, r -> {
                if (r.ok()) {
                    try {
                        JSONArray a = new JSONArray(r.body);
                        for (int i = 0; i < a.length(); i++) {
                            JSONObject o = a.getJSONObject(i);
                            Person p = new Person();
                            p.uid = o.optString("id", "");
                            p.name = o.isNull("name") ? "" : o.optString("name", "");
                            if (p.uid.isEmpty() || p.name.trim().isEmpty()) continue;
                            byId.put(p.uid, p);
                            all.add(p);
                        }
                    } catch (Exception ignored) { }
                }
                if (--left[0] > 0) return;
                loadCounts(me, all, byId);
            });
        }
    }

    /** প্রত্যেকের ফলোয়ার সংখ্যা ("Profile · 409 followers" লাইনের জন্য) */
    private static void loadCounts(final String me, final List<Person> all, final Map<String, Person> byId) {
        if (all.isEmpty()) { finish(me, all); return; }
        List<String> ids = new ArrayList<>(byId.keySet());
        final List<List<String>> chunks = chunk(ids, 60);
        final int[] left = {chunks.size()};
        for (List<String> ch : chunks) {
            SupabaseRest.call("GET", "/rest/v1/follows?followee_id=in.(" + android.text.TextUtils.join(",", ch)
                    + ")&select=followee_id&limit=20000", null, null, true, r -> {
                if (r.ok()) {
                    try {
                        JSONArray a = new JSONArray(r.body);
                        for (int i = 0; i < a.length(); i++) {
                            Person p = byId.get(a.getJSONObject(i).optString("followee_id", ""));
                            if (p != null) p.followers++;
                        }
                    } catch (Exception ignored) { }
                }
                if (--left[0] > 0) return;
                finish(me, all);
            });
        }
    }

    private static void finish(String me, List<Person> all) {
        Collections.sort(all, new Comparator<Person>() {
            @Override public int compare(Person a, Person b) {
                return a.name.toLowerCase(Locale.ROOT).compareTo(b.name.toLowerCase(Locale.ROOT));
            }
        });
        List<Cb> cbs;
        synchronized (WAIT) {
            CACHE.clear();
            CACHE.addAll(all);
            cacheFor = me;
            loading = false;
            cbs = new ArrayList<>(WAIT);
            WAIT.clear();
        }
        for (Cb c : cbs) c.on(cached());
    }

    private static List<List<String>> chunk(List<String> ids, int n) {
        List<List<String>> out = new ArrayList<>();
        for (int i = 0; i < ids.size(); i += n) out.add(ids.subList(i, Math.min(ids.size(), i + n)));
        return out;
    }
}
