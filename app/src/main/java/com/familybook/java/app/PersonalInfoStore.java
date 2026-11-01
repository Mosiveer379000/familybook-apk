package com.familybook.app;

import android.view.View;
import android.widget.ImageView;

import org.json.JSONObject;

import java.util.Iterator;

/**
 * Personal info পেজগুলোর (City, Hometown, Birthday, Gender, Languages) সেভ করার সাধারণ সহায়ক।
 * profiles.info (jsonb)-তে মান আর info.priv-তে প্রাইভেসি (true = Public, false = Only me) মার্জ করে লেখে।
 */
final class PersonalInfoStore {
    private PersonalInfoStore() { }

    /** infoChanges-এর key গুলো info-তে, privChanges-এর key গুলো info.priv-এ মার্জ হয়। যেকোনোটা null হতে পারে */
    static void save(JSONObject infoChanges, JSONObject privChanges, Db.Res cb) {
        Db.getMine("profiles", (p, err) -> {
            if (err != null) { cb.on(err); return; }
            try {
                JSONObject info = p != null && p.optJSONObject("info") != null
                        ? p.getJSONObject("info") : new JSONObject();
                if (infoChanges != null) {
                    Iterator<String> it = infoChanges.keys();
                    while (it.hasNext()) { String k = it.next(); info.put(k, infoChanges.get(k)); }
                }
                if (privChanges != null) {
                    JSONObject priv = info.optJSONObject("priv") != null
                            ? info.getJSONObject("priv") : new JSONObject();
                    Iterator<String> it = privChanges.keys();
                    while (it.hasNext()) { String k = it.next(); priv.put(k, privChanges.get(k)); }
                    info.put("priv", priv);
                }
                Db.patchMyProfile(new JSONObject().put("info", info), cb);
            } catch (Exception e) {
                cb.on(e.getMessage() == null ? "Could not save" : e.getMessage());
            }
        });
    }

    /** "Pivracy :" এর পাশের গ্লোব আইকন: চাপলে Public (গ্লোব) ↔ Only me (লক) বদলায় */
    static final class PrivToggle {
        boolean pub;
        private final ImageView iv;

        PrivToggle(ImageView iv, boolean pub) {
            this.iv = iv;
            this.pub = pub;
            apply();
            iv.setOnClickListener((View v) -> {
                this.pub = !this.pub;
                apply();
                TopToast.show(v.getContext(), this.pub ? "Public" : "Only me");
            });
        }

        private void apply() {
            iv.setImageResource(pub ? R.drawable.ic_globe : R.drawable.ic_privacy_lock);
        }
    }
}
