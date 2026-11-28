package com.familybook.app;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Birthday reminder সেটিং (Supabase: birthday_settings.prefs) — sql/11_birthday_settings.sql
 *
 * prefs = {
 *   "wishes": true,            // Birthday wishes (মাস্টার সুইচ)
 *   "followersWishes": true,   // Wishes from followers
 *   "followersMessage": false, // Message from Followers
 *   "animHome": true,          // Animation on home
 *   "animProfile": true,       // Animation on profile  (অফ হলে প্রোফাইলে অ্যানিমেশন চলে না)
 *   "animation": "Tornado"     // Select Animation: "Tornado" | "Volcano"
 * }
 * প্রোফাইলে যে-ই ঢুকুক (ফলোয়ার, নন-ফলোয়ার, ফলোয়িং, পাবলিক, নিজে) মালিকের prefs পড়ে।
 * সারি না থাকলে ডিফল্ট: সব চালু, Tornado।
 */
public final class BirthdayPrefs {

    public interface Json { void on(String json); }          // null = সারি নেই / ব্যর্থ
    public interface Result { void on(JSONObject prefs); }   // সবসময় ডিফল্টসহ পূর্ণ prefs

    private BirthdayPrefs() { }

    /** কারো সেভ করা prefs JSON স্ট্রিং; নেই হলে null */
    public static void fetch(final String uid, final Json cb) {
        if (uid == null) { cb.on(null); return; }
        // SettingsCache: অনলাইনে সার্ভার থেকে এনে ফোনে জমায়; অফলাইনে জমানো কপি দেয় → অফলাইনেও অ্যানিমেশন চলে
        SettingsCache.get("/rest/v1/birthday_settings?user_id=eq." + uid + "&select=prefs&limit=1",
                r -> {
                    String out = null;
                    if (r.ok()) {
                        try {
                            JSONArray a = new JSONArray(r.body);
                            JSONObject p = a.length() > 0 ? a.getJSONObject(0).optJSONObject("prefs") : null;
                            if (p != null) out = p.toString();
                        } catch (Exception ignored) { }
                    }
                    cb.on(out);
                });
    }

    /** ডিফল্ট মেশানো prefs (প্রোফাইল অ্যানিমেশনের জন্য) */
    public static void resolve(final String uid, final Result cb) {
        fetch(uid, json -> {
            JSONObject p = defaults();
            try {
                if (json != null) {
                    JSONObject s = new JSONObject(json);
                    java.util.Iterator<String> it = s.keys();
                    while (it.hasNext()) { String k = it.next(); p.put(k, s.get(k)); }
                }
            } catch (Exception ignored) { }
            cb.on(p);
        });
    }

    public static JSONObject defaults() {
        try {
            return new JSONObject().put("wishes", true).put("followersWishes", true).put("followersMessage", false)
                    .put("animHome", true).put("animProfile", true).put("animation", "Tornado");
        } catch (Exception e) { return new JSONObject(); }
    }

    /** নিজের prefs সেভ (upsert) */
    public static void save(String json) {
        final String me = SupabaseRest.uid();
        if (me == null || json == null) return;
        try {
            JSONObject p = new JSONObject(json);
            JSONObject row = new JSONObject().put("user_id", me).put("prefs", p);
            SupabaseRest.call("POST", "/rest/v1/birthday_settings?on_conflict=user_id",
                    row.toString(), "resolution=merge-duplicates,return=minimal", true, r -> { });
        } catch (Exception ignored) { }
    }
}
