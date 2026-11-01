package com.familybook.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Pinned details-এর ডাটা সহায়ক।
 * Personal info (City, Hometown, Birthday, Gender, Languages) বাদে বাকি সব যোগ করা তথ্য এখানে পিন করা যায় (সর্বোচ্চ ৫টি)।
 *   ডাটা: profiles.info.pinned = ["work", "hobbies", ...]  (সিলেক্ট করার ক্রম অনুযায়ী)
 */
final class PinnedItems {
    private PinnedItems() { }

    static final int MAX = 5;

    static final class Item {
        final String key, label, icon;
        final List<String> lines = new ArrayList<>();
        final List<String> icons = new ArrayList<>();  // সারি-ভিত্তিক আলাদা আইকন (না থাকলে গ্রুপের আইকন)
        final List<String> subs = new ArrayList<>();   // lines-এর সাথে মিলিয়ে দ্বিতীয় লাইন (না থাকলে ফাঁকা/ছোট)
        Item(String key, String label, String icon) { this.key = key; this.label = label; this.icon = icon; }
    }

    interface Loaded { void on(List<Item> all, List<String> pinned); }

    /** profile info + categories থেকে সব "ডাটা আছে" এমন আইটেম আনে। অন্যের প্রোফাইলে শুধু Public গুলো */
    static void load(String uid, boolean isMe, Loaded cb) {
        Db.get("profiles", "id", uid, (row, err) -> {
            JSONObject info = row != null ? row.optJSONObject("info") : null;
            if (info == null) info = new JSONObject();
            final List<Item> all = new ArrayList<>();
            final List<String> pinned = new ArrayList<>();
            JSONArray p = info.optJSONArray("pinned");
            for (int i = 0; p != null && i < p.length(); i++) {
                String k = p.optString(i, "");
                if (!k.isEmpty() && !pinned.contains(k)) pinned.add(k);
            }
            final JSONObject inf = info;
            SupabaseRest.call("GET", "/rest/v1/profile_categories?user_id=eq." + uid + "&select=categories&limit=1",
                    null, null, true, r -> {
                Item cat = new Item("category", "Category", "ic_category_item");
                if (r.ok()) {
                    try {
                        JSONArray a = new JSONArray(r.body);
                        JSONArray c = a.length() > 0 ? a.getJSONObject(0).optJSONArray("categories") : null;
                        for (int i = 0; c != null && i < c.length(); i++) {
                            String s = c.optString(i, "").trim();
                            if (!s.isEmpty()) cat.lines.add(s);
                        }
                    } catch (Exception ignored) { }
                }
                add(all, cat);
                add(all, career(inf, CareerStore.WORK, "work", "Work", "ic_work_info", isMe));
                add(all, career(inf, CareerStore.EDU, "education", "Education", "ic_college", isMe));
                add(all, strings(inf, "hobbies", "hobbies", "Hobbies", "ic_ct_star"));
                Item in = new Item("interests", "Interests", "ic_ct_star");
                for (int k = 0; k < InterestKind.KEY.length; k++) {
                    in.lines.addAll(strings(inf, InterestKind.KEY[k], "", "", "").lines);
                }
                add(all, in);
                add(all, strings(inf, "travel_places", "travel", "Travel", "ic_ct_pin"));
                add(all, links(inf));
                add(all, communities(inf));
                add(all, contact(inf, isMe));
                cb.on(all, pinned);
            });
        });
    }

    private static void add(List<Item> all, Item it) { if (it != null && !it.lines.isEmpty()) all.add(it); }

    private static boolean visible(String priv, boolean isMe) {
        return isMe || priv == null || priv.isEmpty() || "public".equals(priv);
    }

    private static Item career(JSONObject info, String infoKey, String key, String label, String icon, boolean isMe) {
        Item it = new Item(key, label, icon);
        JSONArray a = info.optJSONArray(infoKey);
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject e = a.optJSONObject(i);
            if (e == null || !visible(e.optString("priv", ""), isMe)) continue;
            String t = CareerStore.title(infoKey, e);
            if (!t.isEmpty()) {
                String sub = CareerStore.sub(infoKey, e);
                it.lines.add(t); it.subs.add(sub);
                // Education: School আর College-এর আলাদা আইকন
                it.icons.add(CareerStore.EDU.equals(infoKey)
                        ? (sub.startsWith("High school") ? "ic_school" : "ic_college") : icon);
            }
        }
        return it;
    }

    private static Item strings(JSONObject info, String infoKey, String key, String label, String icon) {
        Item it = new Item(key, label, icon);
        JSONArray a = info.optJSONArray(infoKey);
        for (int i = 0; a != null && i < a.length(); i++) {
            String s = a.optString(i, "").trim();
            if (!s.isEmpty() && !it.lines.contains(s)) it.lines.add(s);
        }
        return it;
    }

    private static Item links(JSONObject info) {
        Item it = new Item("links", "Links", "ic_link_item");
        JSONArray a = info.optJSONArray("links");
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            String url = o.optString("url", "").trim();
            String title = o.optString("title", "").trim();
            if (!url.isEmpty()) it.lines.add(title.isEmpty() ? url : title);
        }
        return it;
    }

    private static Item communities(JSONObject info) {
        Item it = new Item("communities", "Communities", "ic_profile");
        JSONArray a = info.optJSONArray("communities");
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            String n = o == null ? "" : o.optString("name", "").trim();
            if (!n.isEmpty()) { it.lines.add(n); it.subs.add("Member of group"); }
        }
        return it;
    }

    private static Item contact(JSONObject info, boolean isMe) {
        Item it = new Item("contact", "Contact info", "ic_ct_phone");
        JSONObject c = info.optJSONObject("contact");
        if (c == null) return it;
        if (visible(c.optString("phone_priv", ""), isMe) && !c.optString("phone", "").trim().isEmpty()) {
            it.lines.add(c.optString("phone").trim()); it.icons.add("ic_ct_phone");
        }
        if (visible(c.optString("email_priv", ""), isMe) && !c.optString("email", "").trim().isEmpty()) {
            it.lines.add(c.optString("email").trim()); it.icons.add("ic_email_info");
        }
        JSONArray s = c.optJSONArray("social");
        if (visible(c.optString("social_priv", ""), isMe)) {
            for (int i = 0; s != null && i < s.length(); i++) {
                JSONObject o = s.optJSONObject(i);
                if (o == null) continue;
                String h = o.optString("handle", "").trim();
                if (!h.isEmpty()) {
                    it.lines.add(o.optString("platform", "").trim() + ": " + h);
                    it.icons.add("ic_social_info");
                }
            }
        }
        return it;
    }

    static Item find(List<Item> all, String key) {
        for (Item i : all) if (i.key.equals(key)) return i;
        return null;
    }
}
