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
            resolve(iv, uid, key, circular, 0);
            // ফোনে আটকে থাকা পুরনো তথ্য ভুল হতে পারে (যেমন আগে ছবি ছিল না, পরে দিয়েছে):
            // খালি key বা এই সেশনে এখনো যাচাই না হওয়া key সার্ভার থেকে আবার মিলিয়ে নিই
            if (key.isEmpty() || !VERIFIED.contains(uid)) enqueue(iv, uid, circular);
            return;
        }

        enqueue(iv, uid, circular);
    }

    private static final java.util.Set<String> VERIFIED = new java.util.HashSet<>();   // এই অ্যাপ-সেশনে সার্ভার থেকে মিলানো uid

    // ---- একসাথে অনেক জনের ছবির তথ্য: তালিকায় প্রতি সারিতে আলাদা রিকোয়েস্ট না গিয়ে একটাই (id=in.(...)) ----
    private static final java.util.Map<String, java.util.List<Object[]>> WAIT = new java.util.HashMap<>();
    private static boolean flushPosted = false;
    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());

    private static void enqueue(ImageView iv, String uid, boolean circular) {
        java.util.List<Object[]> l = WAIT.get(uid);
        if (l == null) { l = new java.util.ArrayList<>(); WAIT.put(uid, l); }
        l.add(new Object[]{iv, circular});
        if (!flushPosted) {
            flushPosted = true;
            MAIN.postDelayed(AvatarLoader::flush, 40);
        }
    }

    private static void flush() {
        flushPosted = false;
        if (WAIT.isEmpty()) return;
        final java.util.List<String> ids = new java.util.ArrayList<>(WAIT.keySet());
        for (int from = 0; from < ids.size(); from += 60) {
            final java.util.List<String> chunk = new java.util.ArrayList<>(ids.subList(from, Math.min(ids.size(), from + 60)));
            final java.util.Map<String, java.util.List<Object[]>> mine = new java.util.HashMap<>();
            StringBuilder in = new StringBuilder();
            for (String id : chunk) {
                mine.put(id, WAIT.remove(id));
                if (in.length() > 0) in.append(',');
                in.append(id);
            }
            SupabaseRest.call("GET", "/rest/v1/profiles?id=in.(" + in + ")&select=id,avatar_key,info",
                    null, null, true, r -> {
                final java.util.Map<String, String[]> rows = new java.util.HashMap<>();   // uid -> {avatarKey, gender}
                if (r.ok()) {
                    try {
                        JSONArray a = new JSONArray(r.body);
                        for (int i = 0; i < a.length(); i++) {
                            JSONObject row = a.getJSONObject(i);
                            String id = row.optString("id", "");
                            String ak = row.isNull("avatar_key") ? "" : row.optString("avatar_key", "").trim();
                            JSONObject info = row.optJSONObject("info");
                            String g = info == null ? "" : info.optString("gender", "").trim();
                            if (!id.isEmpty()) rows.put(id, new String[]{ak, g});
                        }
                    } catch (Exception ignored) { }
                }
                boolean cachedAny = false;
                for (java.util.Map.Entry<String, java.util.List<Object[]>> e : mine.entrySet()) {
                    String uid = e.getKey();
                    String[] row = rows.get(uid);
                    if (row != null) {                                   // সারি পাওয়া গেছে: ক্যাশ করি
                        DefaultAvatar.remember(uid, row[1]);
                        KEYS.put(uid, row[0]);
                        VERIFIED.add(uid);
                        cachedAny = true;
                    }
                    for (Object[] w : e.getValue()) {
                        ImageView iv = (ImageView) w[0];
                        boolean circ = (Boolean) w[1];
                        if (!uid.equals(iv.getTag())) continue;           // রিসাইকেল হলে ভুল ছবি নয়
                        if (row == null) DefaultAvatar.apply(iv, "", circ);   // ক্যাশ নয়, পরের বার আবার চেষ্টা
                        else if (row[0].isEmpty()) DefaultAvatar.apply(iv, row[1], circ);
                        else resolve(iv, uid, row[0], circ, 0);
                    }
                }
                if (cachedAny) saveKeys();
            });
        }
    }

    /** ছবির key জানা আছে: ক্যাশে থাকলে সরাসরি, নইলে R2 থেকে আনে */
    private static void resolve(final ImageView iv, final String uid, final String key, final boolean circular, final int attempt) {
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
            if (!uid.equals(iv.getTag())) return;
            if (attempt < 2) {
                // তালিকায় একসাথে অনেক ছবি চাইলে কিছু অনুরোধ ব্যর্থ হয়: ডিফল্ট না দেখিয়ে একটু পরে আবার চেষ্টা
                MAIN.postDelayed(() -> {
                    if (uid.equals(iv.getTag())) resolve(iv, uid, key, circular, attempt + 1);
                }, 1200L * (attempt + 1));
                return;
            }
            // বারবার ব্যর্থ: জেন্ডার অনুযায়ী ডিফল্ট; Bitmap ক্যাশ হয়নি, তাই পরের বার আবার চেষ্টা হবে
            DefaultAvatar.loadForUid(iv, uid, circular);
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
