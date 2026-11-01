package com.familybook.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;

/**
 * লগইন পেজের "সেভ করা অ্যাকাউন্ট" কার্ডের জন্য নাম ও প্রোফাইল ছবি ফোনে রাখে।
 * লগআউট অবস্থায় সার্ভার থেকে ছবি আনা যায় না, তাই লগইনের পরেই ছবিটা ফাইলে সেভ করে রাখা হয়।
 */
final class AccountPhoto {
    private AccountPhoto() { }

    private static final String PREFS = "fb_auth";
    private static final String KEY_LAST_NAME = "last_name";

    private static File file(Context c) { return new File(c.getFilesDir(), "saved_avatar.png"); }

    static String lastName(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST_NAME, null);
    }

    static Bitmap load(Context c) {
        File f = file(c);
        if (!f.exists()) return null;
        try { return BitmapFactory.decodeFile(f.getAbsolutePath()); } catch (Throwable t) { return null; }
    }

    static void clear(Context c) {
        //noinspection ResultOfMethodCallIgnored
        file(c).delete();
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_LAST_NAME).apply();
    }

    private static void save(Context c, Bitmap b) {
        if (b == null) return;
        try {
            int max = 256;
            float sc = Math.min(1f, (float) max / Math.max(b.getWidth(), b.getHeight()));
            Bitmap out = sc < 1f
                    ? Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * sc), Math.round(b.getHeight() * sc), true) : b;
            try (FileOutputStream o = new FileOutputStream(file(c))) {
                out.compress(Bitmap.CompressFormat.PNG, 100, o);
            }
        } catch (Exception ignored) { }
    }

    /** লগইন/ভেরিফাইয়ের পর ডাকুন: নিজের নাম ও ছবি এনে ফোনে রাখে (ব্যাকগ্রাউন্ডে) */
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
                    SecureStore.updateSavedName(app, name);
                }
                if (key.isEmpty()) {
                    //noinspection ResultOfMethodCallIgnored
                    file(app).delete();
                } else {
                    Media.loadBitmap(key, bm -> save(app, bm));
                }
            } catch (Exception ignored) { }
        });
    }
}
