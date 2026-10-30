package com.familybook.app;

import android.app.Activity;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/**
 * Profile info পেজের "Contact info" সেকশন (সবার শেষে)।
 * - Contact info সারিতে চাপলে খোলে/বন্ধ হয় (তীর ঘোরে)
 * - ভেতরে ৩টি সারি: Social media · Phone · Email
 *     Social media → ContactSocialActivity; Phone/Email → ContactFieldActivity
 * - অন্যের প্রোফাইলে শুধু যা দেখার অনুমতি আছে (Public / Friends) তাই দেখায়, এডিট নেই
 * - ডাটা: profiles.info.contact (ContactStore দেখুন)
 *
 * ব্যবহার (ProfileInfoEditActivity.onCreate-এ):
 *   new ProfileContactSection(this, findViewById(R.id.rowContactInfo), uid).attach();
 */
public class ProfileContactSection {

    private final ComponentActivity act;
    private final View rowContact;
    private final String uid;
    private final boolean isMe;

    private JSONObject contact = new JSONObject();
    private boolean isFriend = false;
    private boolean loaded = false;
    private boolean open = false;

    private View section;
    private LinearLayout items;
    private ImageView arrow;

    private final ActivityResultLauncher<Intent> launcher;

    // অবশ্যই Activity-র onCreate-এর ভেতরে বানাতে হবে
    public ProfileContactSection(ComponentActivity act, View rowContact, String uid) {
        this.act = act;
        this.rowContact = rowContact;
        String me = Me.uid();
        this.uid = uid != null ? uid : me;
        this.isMe = this.uid != null && this.uid.equals(me);

        this.launcher = act.registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), r -> {
            if (r.getResultCode() == Activity.RESULT_OK) {
                open = true;
                load();
            }
        });
    }

    public void attach() {
        ViewGroup parent = (ViewGroup) rowContact.getParent();
        int idx = parent.indexOfChild(rowContact);
        section = LayoutInflater.from(act).inflate(R.layout.layout_profile_contact_section, parent, false);
        section.setVisibility(View.GONE);
        parent.addView(section, idx + 1);
        items = section.findViewById(R.id.llContactItems);
        arrow = lastImageView(rowContact);

        bindClick(rowContact, v -> onRowTap());
        load();
    }

    private void onRowTap() {
        if (!loaded) {
            TopToast.show(act, "Loading, please wait");
            load();
            return;
        }
        open = !open;
        render();
    }

    // ---- সার্ভার থেকে লোড ----
    private void load() {
        ContactStore.load(uid, (c, links, err) -> {
            if (act.isFinishing()) return;
            if (err != null) { TopToast.show(act, err); return; }
            contact = c;
            if (isMe) {
                loaded = true;
                render();
                return;
            }
            // অন্যের প্রোফাইল: বন্ধু কি না জেনে নিই (Friends-মান দেখানোর জন্য)
            Db.list("friendships?user_id=eq." + Me.uid() + "&friend_id=eq." + uid
                    + "&select=friend_id&limit=1", (rows, e2) -> {
                if (act.isFinishing()) return;
                isFriend = rows != null && rows.length() > 0;
                loaded = true;
                render();
            });
        });
    }

    // ---- আঁকা ----
    private void render() {
        items.removeAllViews();

        // Social media: প্রতিটি অ্যাকাউন্ট আলাদা সারি; নামে চাপলে ওই সোশ্যাল সাইটে খোলে
        String socialPriv = ContactStore.norm(contact.optString("social_priv", ""), ContactStore.PUBLIC);
        ArrayList<String[]> accounts = new ArrayList<>();
        JSONArray sa = contact.optJSONArray("social");
        for (int i = 0; sa != null && i < sa.length(); i++) {
            String[] en = ContactStore.entry(sa.optJSONObject(i));
            if (en != null) accounts.add(en);
        }
        final View.OnClickListener openForm = v -> launcher.launch(new Intent(act, ContactSocialActivity.class));
        if (accounts.isEmpty()) {
            if (isMe) {
                View row = row(R.drawable.ic_ct_at, "Social media", null, null, false);
                row.setOnClickListener(openForm);
                items.addView(row);
            }
        } else if (ContactStore.visible(socialPriv, isMe, isFriend)) {
            for (final String[] ac : accounts) {
                String sub = ac[0] + (isMe ? " \u00B7 " + ContactStore.label(socialPriv) : "");
                View row = row("Instagram".equals(ac[0]) ? R.drawable.ic_ct_instagram : R.drawable.ic_ct_at,
                        ContactStore.display(ac[0], ac[1]), sub, null, isMe);
                row.setOnClickListener(v -> ContactStore.openUrl(act, ac[2]));
                if (isMe) row.findViewById(R.id.btnCtEdit).setOnClickListener(openForm);
                items.addView(row);
            }
        }

        // Phone / Email
        addField("phone", R.drawable.ic_ct_phone, "Add Phone", true);
        addField("email", R.drawable.ic_ct_mail, "Add email", false);

        if (items.getChildCount() == 0) {
            View row = row(R.drawable.ic_ct_at, "No contact info to show", null, null, false);
            items.addView(row);
        }

        section.setVisibility(open ? View.VISIBLE : View.GONE);
        if (arrow != null) arrow.animate().rotation(open ? 180f : 0f).setDuration(150).start();
    }

    private void addField(final String key, int icon, String addLabel, boolean alwaysPencil) {
        String value = contact.optString(key, "").trim();
        String priv = ContactStore.norm(contact.optString(key + "_priv", ""), ContactStore.ME);
        boolean has = !value.isEmpty();
        if (!isMe && !(has && ContactStore.visible(priv, false, isFriend))) return;
        View row = row(icon, has ? value : addLabel, null, has && isMe ? ContactStore.label(priv) : null,
                isMe && (alwaysPencil || has));
        if (isMe) {
            View.OnClickListener l = v -> launcher.launch(new Intent(act, ContactFieldActivity.class)
                    .putExtra("type", key));
            row.setOnClickListener(l);
            row.findViewById(R.id.btnCtEdit).setOnClickListener(l);
        }
        items.addView(row);
    }

    private View row(int icon, String title, String sub, String privLabel, boolean pencil) {
        View row = LayoutInflater.from(act).inflate(R.layout.item_contact_row, items, false);
        ((ImageView) row.findViewById(R.id.ivCtIcon)).setImageResource(icon);
        ((TextView) row.findViewById(R.id.tvCtTitle)).setText(title);
        TextView tvSub = row.findViewById(R.id.tvCtSub);
        String line = sub;
        if (privLabel != null) line = (line == null || line.isEmpty()) ? privLabel : line + " · " + privLabel;
        if (line != null && !line.isEmpty()) {
            tvSub.setText(line);
            tvSub.setVisibility(View.VISIBLE);
        }
        row.findViewById(R.id.btnCtEdit).setVisibility(pencil ? View.VISIBLE : View.GONE);
        return row;
    }

    // ---- helpers ----
    private void bindClick(View v, View.OnClickListener l) {
        v.setOnClickListener(l);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) bindClick(g.getChildAt(i), l);
        }
    }

    private ImageView lastImageView(View v) {
        ImageView found = null;
        if (v instanceof ImageView) found = (ImageView) v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                ImageView c = lastImageView(g.getChildAt(i));
                if (c != null) found = c;
            }
        }
        return found;
    }
}
