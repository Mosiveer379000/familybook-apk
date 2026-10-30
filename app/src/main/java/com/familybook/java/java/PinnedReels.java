package com.familybook.app;

import android.app.Activity;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * "Pin to your reels": সর্বোচ্চ ৩টি Short পিন করা যায়; ৪র্থটা পিন করলে সবচেয়ে পুরোনো পিনটা সরে যায়।
 * পিন করা Short গুলো প্রোফাইলের Shorts সেকশনে সবার আগে (উপরে) দেখায়।
 * সংরক্ষণ: profiles.info.pinnedShorts = [shortId, ...] (পুরোনো → নতুন)
 */
final class PinnedReels {
    static final int MAX = 3;

    private PinnedReels() { }

    private static List<String> parse(JSONObject row) {
        List<String> out = new ArrayList<>();
        JSONObject info = row == null ? null : row.optJSONObject("info");
        JSONArray a = info == null ? null : info.optJSONArray("pinnedShorts");
        if (a != null) for (int i = 0; i < a.length(); i++) {
            String id = a.optString(i, "");
            if (!id.isEmpty()) out.add(id);
        }
        return out;
    }

    interface Cb { void on(List<String> ids); }

    /** কোনো ইউজারের পিন করা Short id (পুরোনো → নতুন) */
    static void load(String uid, final Cb cb) {
        if (uid == null) { cb.on(new ArrayList<>()); return; }
        Db.get("profiles", "id", uid, (row, err) -> cb.on(parse(row)));
    }

    /** পিন করা Short গুলো সামনে (সবচেয়ে নতুন পিন আগে), বাকিগুলো আগের ক্রমে */
    static List<ProfileShorts.Item> order(List<ProfileShorts.Item> items, List<String> pinned) {
        if (items == null || pinned == null || pinned.isEmpty()) return items;
        List<ProfileShorts.Item> out = new ArrayList<>();
        for (int i = pinned.size() - 1; i >= 0; i--) {
            for (ProfileShorts.Item it : items) {
                if (pinned.get(i).equals(it.id)) { out.add(it); break; }
            }
        }
        for (ProfileShorts.Item it : items) if (!out.contains(it)) out.add(it);
        return out;
    }

    static void isPinned(String shortId, final java.util.function.Consumer<Boolean> cb) {
        load(SupabaseRest.uid(), ids -> cb.accept(ids.contains(shortId)));
    }

    /** পিন / আনপিন (মেনু থেকে) */
    static void toggle(final Activity a, final String shortId) {
        final String me = SupabaseRest.uid();
        if (me == null || shortId == null) return;
        load(me, ids -> {
            final boolean unpin = ids.contains(shortId);
            boolean replaced = false;
            if (unpin) ids.remove(shortId);
            else {
                ids.add(shortId);
                if (ids.size() > MAX) { ids.remove(0); replaced = true; }
            }
            final boolean rep = replaced;
            try {
                Db.mergeMyInfo(new JSONObject().put("pinnedShorts", new JSONArray(ids)), e -> {
                    if (e != null) { Toast.makeText(a, e, Toast.LENGTH_SHORT).show(); return; }
                    Toast.makeText(a, unpin ? "Unpinned from your reels"
                            : rep ? "Pinned. Your oldest pinned reel was replaced"
                            : "Pinned to your reels", Toast.LENGTH_SHORT).show();
                    PostOps.refreshHost(a);
                });
            } catch (Exception ex) {
                Toast.makeText(a, "Could not pin", Toast.LENGTH_SHORT).show();
            }
        });
    }
}
