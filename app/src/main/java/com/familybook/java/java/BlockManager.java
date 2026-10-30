package com.familybook.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * ব্লক লিস্ট Supabase ডেটাবেসের blocked_users টেবিলে সেভ হয়।
 * সব নেটওয়ার্ক কাজ SupabaseRest দিয়ে হয় (background thread, callback main thread-এ)।
 *
 * ব্যবহার:
 *   BlockManager.block(user.getId(), user.getName(), user.getPhotoUrl(), new BlockManager.Result<Void>() {
 *       public void onSuccess(Void v) { ... }
 *       public void onError(String msg) { ... }
 *   });
 */
public class BlockManager {

    public interface Result<T> {
        void onSuccess(T data);
        void onError(String message);
    }

    public static class BlockedUser {
        public String id, name, photoUrl;
        public BlockedUser(String id, String name, String photoUrl) {
            this.id = id;
            this.name = name;
            this.photoUrl = photoUrl;
        }
    }

    private static final String TABLE = "/rest/v1/blocked_users";

    // ---------- ব্লক লিস্ট আনা ----------
    public static void getAll(Result<List<BlockedUser>> cb) {
        SupabaseRest.call("GET",
                TABLE + "?select=blocked_id,blocked_name,blocked_photo_url&order=created_at.desc",
                null, null, true, r -> {
                    if (!r.ok()) {
                        cb.onError(SupabaseRest.errorText(r));
                        return;
                    }
                    try {
                        JSONArray arr = new JSONArray(r.body);
                        List<BlockedUser> list = new ArrayList<>();
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject o = arr.getJSONObject(i);
                            list.add(new BlockedUser(
                                    o.getString("blocked_id"),
                                    o.getString("blocked_name"),
                                    o.isNull("blocked_photo_url") ? "" : o.getString("blocked_photo_url")));
                        }
                        cb.onSuccess(list);
                    } catch (Exception e) {
                        cb.onError("Parse error: " + e.getMessage());
                    }
                });
    }

    // ---------- ব্লক করা ----------
    public static void block(String blockedId, String name, String photoUrl, Result<Void> cb) {
        try {
            JSONObject o = new JSONObject();
            o.put("blocked_id", blockedId);
            o.put("blocked_name", name);
            o.put("blocked_photo_url", photoUrl == null ? JSONObject.NULL : photoUrl);

            SupabaseRest.call("POST",
                    TABLE + "?on_conflict=blocker_id,blocked_id",
                    o.toString(),
                    "resolution=ignore-duplicates,return=minimal",
                    true, r -> done(r, cb));
        } catch (Exception e) {
            cb.onError(e.getMessage());
        }
    }

    // ---------- আনব্লক করা ----------
    public static void unblock(String blockedId, Result<Void> cb) {
        SupabaseRest.call("DELETE",
                TABLE + "?blocked_id=eq." + android.net.Uri.encode(blockedId),
                null, null, true, r -> done(r, cb));
    }

    private static void done(SupabaseRest.Resp r, Result<Void> cb) {
        if (r.ok()) cb.onSuccess(null);
        else cb.onError(SupabaseRest.errorText(r));
    }
}
