package com.familybook.app;

import android.animation.ObjectAnimator;
import android.view.View;
import android.view.animation.LinearInterpolator;

import org.json.JSONObject;

/**
 * রেজিস্ট্রেশনের সময় দেওয়া তথ্য Supabase থেকে এনে একটি JSONObject-এ দেয়।
 * Profile Edit-এর Loading (sync) আইকন চাপলে এটা চলে, তারপর যেগুলো খালি সেগুলো অটো যোগ হয়।
 *
 * কী: email, phone, recoveryEmail, username, agn, agu (username+agn),
 *     nationality, blood, dob, gender, currentCity, hometown
 * উৎস: /auth/v1/user → user_metadata, তারপর profiles (username, agn, info)
 */
final class RegSync {
    private RegSync() { }

    interface Done { void on(JSONObject data); }

    static void fetch(Done cb) {
        final JSONObject out = new JSONObject();
        SupabaseRest.call("GET", "/auth/v1/user", null, null, true, r -> {
            try {
                if (r.ok()) {
                    JSONObject u = new JSONObject(r.body);
                    out.put("email", u.optString("email", ""));
                    JSONObject m = u.optJSONObject("user_metadata");
                    if (m == null) m = new JSONObject();
                    for (String k : new String[]{"phone", "nationality", "blood", "dob", "gender"}) {
                        out.put(k, m.optString(k, "").trim());
                    }
                    String rec = m.optString("recoveryEmail", "").trim();
                    if (rec.isEmpty()) rec = m.optString("recovery_email", "").trim();
                    out.put("recoveryEmail", rec);
                    out.put("username", m.optString("username", "").trim());
                    if (out.optString("phone").isEmpty()) out.put("phone", u.optString("phone", "").trim());
                }
            } catch (Exception ignored) { }

            Db.getMine("profiles", (p, err) -> {
                try {
                    if (p != null) {
                        String un = Db.s(p, "username").trim();
                        if (!un.isEmpty()) out.put("username", un);
                        out.put("agn", p.optInt("agn", 0));
                        JSONObject info = p.optJSONObject("info");
                        if (info != null) {
                            for (String k : new String[]{"phone", "recoveryEmail", "nationality", "blood",
                                    "dob", "gender", "currentCity", "hometown"}) {
                                if (out.optString(k, "").isEmpty()) out.put(k, info.optString(k, "").trim());
                            }
                        }
                    }
                    String un = out.optString("username", "");
                    int agn = out.optInt("agn", 0);
                    out.put("agu", un.isEmpty() ? "" : un + (agn > 0 ? String.valueOf(agn) : ""));
                } catch (Exception ignored) { }
                cb.on(out);
            });
        });
    }

    /** Loading আইকন ঘুরানো শুরু/বন্ধ */
    static void spin(View v, boolean on) {
        if (v == null) return;
        Object t = v.getTag(R.id.ctRowAction);
        if (t instanceof ObjectAnimator) ((ObjectAnimator) t).cancel();
        v.setRotation(0f);
        if (on) {
            ObjectAnimator a = ObjectAnimator.ofFloat(v, View.ROTATION, 0f, 360f);
            a.setDuration(800);
            a.setRepeatCount(ObjectAnimator.INFINITE);
            a.setInterpolator(new LinearInterpolator());
            a.start();
            v.setTag(R.id.ctRowAction, a);
        } else {
            v.setTag(R.id.ctRowAction, null);
        }
    }
}
