package com.familybook.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.widget.ImageView;

import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * প্রোফাইল ছবি না থাকলে জেন্ডার অনুযায়ী ডিফল্ট ছবি।
 * জেন্ডার আসে profiles.info->>'gender' থেকে।
 */
final class DefaultAvatar {

    private static Bitmap maleBm, femaleBm;
    private static final LruCache<String, String> GENDER = new LruCache<>(200);

    private DefaultAvatar() { }

    /** 1 = male, 2 = female, 0 = অন্য/অজানা */
    static int kind(String gender) {
        if (gender == null) return 0;
        String g = gender.trim();
        if (g.equalsIgnoreCase("male")) return 1;
        if (g.equalsIgnoreCase("female")) return 2;
        return 0;
    }

    private static synchronized Bitmap bitmap(ImageView iv, int kind) {
        if (kind == 1) {
            if (maleBm == null) maleBm = BitmapFactory.decodeResource(iv.getResources(), R.drawable.default_avatar_male);
            return maleBm;
        }
        if (kind == 2) {
            if (femaleBm == null) femaleBm = BitmapFactory.decodeResource(iv.getResources(), R.drawable.default_avatar_female);
            return femaleBm;
        }
        return null;
    }

    static void apply(ImageView iv, String gender) {
        apply(iv, gender, true);
    }

    /** circular=false হলে বর্গ ছবি */
    static void apply(ImageView iv, String gender, boolean circular) {
        Bitmap bm = bitmap(iv, kind(gender));
        if (bm == null) {
            iv.setImageResource(R.drawable.ic_profile);
            return;
        }
        if (!circular) {
            iv.setImageBitmap(bm);
            return;
        }
        RoundedBitmapDrawable rd = RoundedBitmapDrawableFactory.create(iv.getResources(), bm);
        rd.setCircular(true);
        iv.setImageDrawable(rd);
    }

    static void remember(String uid, String gender) {
        if (uid != null) GENDER.put(uid, gender == null ? "" : gender.trim());
    }

    static void loadForUid(final ImageView iv, final String uid) {
        loadForUid(iv, uid, true);
    }

    static void loadForUid(final ImageView iv, final String uid, final boolean circular) {
        if (uid == null) return;
        String cached = GENDER.get(uid);
        if (cached != null) {
            apply(iv, cached, circular);
            return;
        }
        SupabaseRest.call("GET", "/rest/v1/profiles?id=eq." + uid + "&select=info",
                null, null, true, r -> {
            Object t = iv.getTag();
            if (t instanceof String && !uid.equals(t)) return;   // রিসাইকেল হলে ভুল ছবি নয়

            if (!r.ok()) {
                apply(iv, "", circular);   // ক্যাশ করব না, পরের বার আবার চেষ্টা হবে
                return;
            }
            String val = "";
            try {
                JSONArray a = new JSONArray(r.body);
                if (a.length() > 0) {
                    JSONObject info = a.getJSONObject(0).optJSONObject("info");
                    if (info != null) val = info.optString("gender", "").trim();
                }
            } catch (Exception ignored) { }
            GENDER.put(uid, val);
            apply(iv, val, circular);
        });
    }
}