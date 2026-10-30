package com.familybook.app;

import android.graphics.Bitmap;
import android.util.LruCache;
import android.widget.ImageView;

import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * প্রোফাইল ছবি লোডার (সব জায়গায় একই সোর্স)।
 * ১) profiles থেকে avatar_key ও gender আনে
 * ২) avatar_key থাকলে R2 থেকে আসল ছবি আনে (Media.loadBitmap), না থাকলে জেন্ডার অনুযায়ী ডিফল্ট
 * ৩) key ও Bitmap ক্যাশ করে, তাই বারবার নেটওয়ার্ক লাগে না
 */
public class AvatarLoader {

    private static final LruCache<String, String> KEYS = new LruCache<>(300);   // uid -> avatar_key ("" = নেই)
    private static final LruCache<String, Bitmap> BITMAPS = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };

    private static java.io.File keyStore;
    private static final java.util.concurrent.ExecutorService DISK = java.util.concurrent.Executors.newSingleThreadExecutor();

    /** FamilyBookApp.onCreate-এ একবার: আগের (uid -> ছবির key) ফিরিয়ে আনে — অফলাইনেও প্রোফাইল ছবি আসে */
    public static void init(android.content.Context app) {
        keyStore = new java.io.File(app.getApplicationContext().getFilesDir(), "avatar_keys.json");
        if (!keyStore.exists()) return;
        try (java.io.FileInputStream in = new java.io.FileInputStream(keyStore)) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            JSONObject o = new JSONObject(bo.toString("UTF-8"));
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) { String u = it.next(); KEYS.put(u, o.optString(u, "")); }
        } catch (Exception ignored) { }
    }

    private static void saveKeys() {
        if (keyStore == null) return;
        final JSONObject o = new JSONObject();
        try {
            for (java.util.Map.Entry<String, String> e : KEYS.snapshot().entrySet()) o.put(e.getKey(), e.getValue());
        } catch (Exception e) { return; }
        DISK.execute(() -> {
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(keyStore)) {
                out.write(o.toString().getBytes("UTF-8"));
            } catch (Exception ignored) { }
        });
    }

    public static void load(final ImageView iv, final String uid) {
        load(iv, uid, true);
    }

    /** circular=false হলে বর্গ ছবি (কার্ডের বড় ছবির জন্য) */
    public static void load(final ImageView iv, final String uid, final boolean circular) {
        iv.setTag(uid);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setImageResource(R.drawable.ic_profile);
        if (uid == null) return;

        String key = KEYS.get(uid);
        if (key != null) {
            resolve(iv, uid, key, circular);
            return;
        }

        SupabaseRest.call("GET", "/rest/v1/profiles?id=eq." + uid + "&select=avatar_key,info",
                null, null, true, r -> {
            if (!uid.equals(iv.getTag())) return;       // রিসাইকেল হলে ভুল ছবি নয়
            if (!r.ok()) {                              // ক্যাশ করব না, পরের বার আবার চেষ্টা হবে
                DefaultAvatar.apply(iv, "", circular);
                return;
            }
            String avatarKey = "", gender = "";
            boolean found = false;
            try {
                JSONArray a = new JSONArray(r.body);
                if (a.length() > 0) {
                    found = true;
                    JSONObject row = a.getJSONObject(0);
                    avatarKey = row.isNull("avatar_key") ? "" : row.optString("avatar_key", "").trim();
                    JSONObject info = row.optJSONObject("info");
                    if (info != null) gender = info.optString("gender", "").trim();
                }
            } catch (Exception ignored) { }
            if (!found) {                               // সারি পাওয়া যায়নি (লোড/অনুমতির সমস্যা): ক্যাশ নয়, পরের বার আবার চেষ্টা
                DefaultAvatar.apply(iv, "", circular);
                return;
            }
            DefaultAvatar.remember(uid, gender);
            KEYS.put(uid, avatarKey);
            saveKeys();
            if (avatarKey.isEmpty()) DefaultAvatar.apply(iv, gender, circular);
            else resolve(iv, uid, avatarKey, circular);
        });
    }

    /** ছবির key জানা আছে: ক্যাশে থাকলে সরাসরি, নইলে R2 থেকে আনে */
    private static void resolve(final ImageView iv, final String uid, final String key, final boolean circular) {
        if (key.isEmpty()) {
            DefaultAvatar.loadForUid(iv, uid, circular);
            return;
        }
        Bitmap cached = BITMAPS.get(key);
        if (cached != null) { show(iv, cached, circular); return; }

        // আসল ছবি আসার আগে সাধারণ প্লেসহোল্ডার (ic_profile) থাকবে।
        // এখানে আর DefaultAvatar চালাচ্ছি না, কারণ সেটা পরে এসে আসল ছবি ঢেকে দিতে পারত।
        Media.loadBitmap(key, bm -> {
            BITMAPS.put(key, bm);
            if (uid.equals(iv.getTag())) show(iv, bm, circular);
        }, (v, err) -> {
            // ছবি আনা ব্যর্থ (নেটওয়ার্ক/লিংক সমস্যা): জেন্ডার অনুযায়ী ডিফল্ট দেখাও; Bitmap ক্যাশ হয়নি, তাই পরের বার আবার চেষ্টা হবে
            if (uid.equals(iv.getTag())) DefaultAvatar.loadForUid(iv, uid, circular);
        });
    }

    private static void show(ImageView iv, Bitmap bm, boolean circular) {
        if (!circular) { iv.setImageBitmap(bm); return; }
        RoundedBitmapDrawable d = RoundedBitmapDrawableFactory.create(iv.getResources(), bm);
        d.setCircular(true);
        iv.setImageDrawable(d);
    }

    public static Bitmap cached(String key) { return key == null ? null : BITMAPS.get(key); }

    /** R2 key থেকে সরাসরি ছবি দেখায় (গ্যালারি/ফোল্ডারের জন্য), ক্যাশসহ */
    public static void loadKey(final ImageView iv, final String key) {
        iv.setTag(key);
        if (key == null || key.isEmpty()) return;
        Bitmap cached = BITMAPS.get(key);
        if (cached != null) { iv.setImageBitmap(cached); return; }
        Media.loadBitmap(key, bm -> {
            BITMAPS.put(key, bm);
            if (key.equals(iv.getTag())) iv.setImageBitmap(bm);
        });
    }

    /** নতুন ছবি আপলোডের পর key সরাসরি বসিয়ে দেয় */
    public static void setKey(String uid, String key) {
        if (uid != null && key != null) KEYS.put(uid, key);
    }

    /** ছবি বদলালে (নিজের প্রোফাইলে আপলোডের পর) ক্যাশ মুছতে */
    public static void invalidate(String uid) {
        if (uid != null) KEYS.remove(uid);
    }
}
