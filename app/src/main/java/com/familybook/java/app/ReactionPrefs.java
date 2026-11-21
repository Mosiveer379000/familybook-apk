package com.familybook.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Settings > Reaction preferences (Supabase: reaction_settings)।
 *  - hide_others : অন্যের পোস্টে রিঅ্যাকশনের সংখ্যা আমি দেখব না
 *  - hide_yours  : আমার পোস্টে অন্যেরা সংখ্যা দেখবে না (আইকন দেখবে)
 *  - sound_*     : আমার সব পোস্টের মিম সাউন্ড (posts.sound_* কলামে সাথে সাথে বসে)
 * নিজের পোস্টে নিজে সবসময় সংখ্যা দেখি।
 */
public final class ReactionPrefs {

    public interface Json { void on(String json); }
    public interface Flag { void on(boolean v); }

    private static boolean hideOthers = false, hideYours = false;
    private static boolean soundHaha = true, soundCheck = true, soundWow = true;
    private static long mineAt = 0;
    /** যাদের hide_yours চালু */
    private static final Set<String> HIDDEN_OWNERS = new HashSet<>();

    private ReactionPrefs() { }

    /** এই পোস্টের (মালিক ownerUid) রিঅ্যাকশন সংখ্যা লুকাতে হবে কি না */
    public static synchronized boolean hideCount(String ownerUid) {
        String me = SupabaseRest.uid();
        if (ownerUid == null || ownerUid.equals(me)) return false;   // নিজের পোস্টে সবসময় দেখি
        return hideOthers || HIDDEN_OWNERS.contains(ownerUid);
    }

    // ---------------- ফিডের জন্য ----------------

    /** তালিকার পোস্টগুলোর মালিকদের সেটিং আনে; শেষে done (main thread) */
    public static void prefetch(List<Post> posts, final Runnable done) {
        final Set<String> ids = new LinkedHashSet<>();
        String me = SupabaseRest.uid();
        for (Post p : posts) {
            if (p.uid != null && !p.uid.equals(me) && ids.size() < 60) ids.add(p.uid);
        }
        refreshMine(false, () -> fetchOwners(ids, done));
    }

    /** একটা পোস্টের মালিক খুঁজে সংখ্যা লুকাতে হবে কি না জানায় (ReactionsActivity-র জন্য) */
    public static void loadForPost(String postId, final Flag cb) {
        SupabaseRest.call("GET", "/rest/v1/posts?id=eq." + postId + "&select=user_id",
                null, null, true, r -> {
            String owner = null;
            if (r.ok()) {
                try {
                    JSONArray a = new JSONArray(r.body);
                    if (a.length() > 0) owner = a.getJSONObject(0).optString("user_id", null);
                } catch (Exception ignored) { }
            }
            final String o = owner;
            Set<String> ids = new LinkedHashSet<>();
            if (o != null) ids.add(o);
            refreshMine(false, () -> fetchOwners(ids, () -> cb.on(hideCount(o))));
        });
    }

    private static void fetchOwners(final Set<String> ids, final Runnable done) {
        if (ids.isEmpty()) { if (done != null) done.run(); return; }
        StringBuilder in = new StringBuilder();
        for (String id : ids) { if (in.length() > 0) in.append(','); in.append(id); }
        SupabaseRest.call("GET", "/rest/v1/reaction_settings?user_id=in.(" + in
                + ")&hide_yours=eq.true&select=user_id", null, null, true, r -> {
            if (r.ok()) {
                try {
                    JSONArray a = new JSONArray(r.body);
                    synchronized (ReactionPrefs.class) {
                        HIDDEN_OWNERS.removeAll(ids);
                        for (int i = 0; i < a.length(); i++) {
                            HIDDEN_OWNERS.add(a.getJSONObject(i).optString("user_id", ""));
                        }
                    }
                } catch (Exception ignored) { }
            }
            if (done != null) done.run();
        });
    }

    // ---------------- আমার সেটিং ----------------

    private static void refreshMine(boolean force, final Runnable done) {
        final String me = SupabaseRest.uid();
        if (me == null) { if (done != null) done.run(); return; }
        if (!force && System.currentTimeMillis() - mineAt < 15_000L) {
            if (done != null) done.run();
            return;
        }
        SupabaseRest.call("GET", "/rest/v1/reaction_settings?user_id=eq." + me
                + "&select=hide_others,hide_yours,sound_haha,sound_check,sound_wow",
                null, null, true, r -> {
            if (r.ok()) {
                try {
                    JSONArray a = new JSONArray(r.body);
                    if (a.length() > 0) {
                        JSONObject o = a.getJSONObject(0);
                        synchronized (ReactionPrefs.class) {
                            hideOthers = o.optBoolean("hide_others", false);
                            hideYours = o.optBoolean("hide_yours", false);
                            soundHaha = o.optBoolean("sound_haha", true);
                            soundCheck = o.optBoolean("sound_check", true);
                            soundWow = o.optBoolean("sound_wow", true);
                        }
                    }
                    mineAt = System.currentTimeMillis();
                } catch (Exception ignored) { }
            }
            if (done != null) done.run();
        });
    }

    /** HTML পেজের জন্য: সার্ভার থেকে আনা সেটিং JSON */
    public static void fetch(final Json cb) {
        refreshMine(true, () -> {
            JSONObject o = new JSONObject();
            try {
                synchronized (ReactionPrefs.class) {
                    o.put("hideOthers", hideOthers).put("hideYours", hideYours)
                     .put("soundHaha", soundHaha).put("soundCheck", soundCheck).put("soundWow", soundWow);
                }
            } catch (Exception ignored) { }
            cb.on(o.toString());
        });
    }

    /** HTML থেকে আসা সেটিং সাথে সাথে সেভ (upsert) + বদলানো সাউন্ড আমার সব পোস্টে বসানো */
    public static void save(String json) {
        final String me = SupabaseRest.uid();
        if (me == null || json == null) return;
        try {
            JSONObject in = new JSONObject(json);
            boolean nHideOthers = in.optBoolean("hideOthers", false);
            boolean nHideYours = in.optBoolean("hideYours", false);
            boolean nHaha = in.optBoolean("soundHaha", true);
            boolean nCheck = in.optBoolean("soundCheck", true);
            boolean nWow = in.optBoolean("soundWow", true);

            // শুধু যেগুলো বদলেছে সেই সাউন্ড পোস্টে পাঠাই (পোস্ট-ভিত্তিক আলাদা সেটিং মুছে না যায়)
            JSONObject postPatch = new JSONObject();
            synchronized (ReactionPrefs.class) {
                if (nHaha != soundHaha) postPatch.put("sound_haha", nHaha);
                if (nCheck != soundCheck) postPatch.put("sound_check", nCheck);
                if (nWow != soundWow) postPatch.put("sound_wow", nWow);
                hideOthers = nHideOthers; hideYours = nHideYours;
                soundHaha = nHaha; soundCheck = nCheck; soundWow = nWow;
                mineAt = System.currentTimeMillis();
            }

            JSONObject row = new JSONObject()
                    .put("user_id", me)
                    .put("hide_others", nHideOthers).put("hide_yours", nHideYours)
                    .put("sound_haha", nHaha).put("sound_check", nCheck).put("sound_wow", nWow);
            SupabaseRest.call("POST", "/rest/v1/reaction_settings?on_conflict=user_id",
                    row.toString(), "resolution=merge-duplicates,return=minimal", true, r -> { });

            if (postPatch.length() > 0) {
                SupabaseRest.call("PATCH", "/rest/v1/posts?user_id=eq." + me,
                        postPatch.toString(), "return=minimal", true, r -> { });
            }
        } catch (Exception ignored) { }
    }
}
