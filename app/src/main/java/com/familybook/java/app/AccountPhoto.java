package com.familybook.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Switch Account কার্ডের জন্য প্রতিটি অ্যাকাউন্টের নাম ও প্রোফাইল ছবি ফোনে রাখে।
 * লগআউট অবস্থায় সার্ভার থেকে ছবি আনা যায় না, তাই লগইনের পরেই ছবিটা ফাইলে সেভ করে রাখা হয়।
 */
final class AccountPhoto {
    private AccountPhoto() { }

    private static final String PREFS = "fb_auth";
    private static final String KEY_LAST_NAME = "last_name";

    private static File file(Context c, String uid) {
        String safe = uid == null ? "x" : uid.replaceAll("[^A-Za-z0-9_-]", "_");
        return new File(c.getFilesDir(), "saved_avatar_" + safe + ".png");
    }

    static String lastName(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST_NAME, null);
    }

    /** এই uid-র সেভ করা ছবি; না থাকলে null */
    static Bitmap load(Context c, String uid) {
        File f = file(c, uid);
        if (!f.exists()) return null;
        try { return BitmapFactory.decodeFile(f.getAbsolutePath()); } catch (Throwable t) { return null; }
    }

    /** একটি অ্যাকাউন্টের ছবি মোছে (কার্ড রিমুভ করলে) */
    static void remove(Context c, String uid) {
        //noinspection ResultOfMethodCallIgnored
        file(c, uid).delete();
    }

    /** পুরনো একক-অ্যাকাউন্ট সিস্টেমের জন্য (SecureStore.clearSaved ডাকে) */
    static void clear(Context c) {
        //noinspection ResultOfMethodCallIgnored
        new File(c.getFilesDir(), "saved_avatar.png").delete();
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_LAST_NAME).apply();
    }

    private static void save(Context c, String uid, Bitmap b) {
        if (b == null) return;
        try {
            int max = 256;
            float sc = Math.min(1f, (float) max / Math.max(b.getWidth(), b.getHeight()));
            Bitmap out = sc < 1f
                    ? Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * sc), Math.round(b.getHeight() * sc), true) : b;
            try (FileOutputStream o = new FileOutputStream(file(c, uid))) {
                out.compress(Bitmap.CompressFormat.PNG, 100, o);
            }
        } catch (Exception ignored) { }
    }

    /**
     * লগইন/ভেরিফাইয়ের পর ডাকুন: নিজের নাম ও ছবি এনে ফোনে রাখে (ব্যাকগ্রাউন্ডে)।
     * কার্ড তৈরি হয় লগআউটে "Save login information" টিক দিলে; তখন এই ছবি/নাম কার্ডে দেখায়।
     */
    static void refresh(Context ctx) {
        final Context app = ctx.getApplicationContext();
        final String uid = SupabaseRest.uid();
        if (uid == null) return;
        SupabaseRest.call("GET", "/rest/v1/profiles?id=eq." + uid + "&select=name,avatar_key", null, null, true, r -> {
            if (!r.ok()) return;
            try {
                JSONArray a = new JSONArray(r.body);
                if (a.length() == 0) return;
                JSONObject o = a.getJSONObject(0);
                String name = o.isNull("name") ? "" : o.optString("name", "").trim();
                String key = o.isNull("avatar_key") ? "" : o.optString("avatar_key", "").trim();
                if (!name.isEmpty()) {
                    app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LAST_NAME, name).apply();
                    SecureStore.updateCardName(app, uid, name);
                }
                if (key.isEmpty()) {
                    remove(app, uid);
                } else {
                    Media.loadBitmap(key, bm -> save(app, uid, bm));
                }
            } catch (Exception ignored) { }
        });
    }
}
