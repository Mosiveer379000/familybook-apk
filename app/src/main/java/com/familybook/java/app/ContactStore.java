package com.familybook.app;

import android.app.AlertDialog;
import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.Locale;

/**
 * Contact info (Social media / Phone / Email) এর ডাটা ও প্রাইভেসি সহায়ক।
 * ডাটা: profiles.info.contact = {
 *   social: [{platform, handle}], social_priv: "public|friends|me",
 *   phone, phone_priv, email, email_priv }
 */
final class ContactStore {
    private ContactStore() { }

    static final String PUBLIC = "public", FRIENDS = "friends", ME = "me";

    interface Loaded { void on(JSONObject contact, JSONArray profileLinks, String error); }
    interface Pick { void on(String priv); }

    /** কারও profiles.info থেকে contact অবজেক্ট ও Links তালিকা আনে (খালি হলে {} ও null নয়) */
    static void load(String uid, Loaded cb) {
        Db.get("profiles", "id", uid, (row, err) -> {
            if (err != null) { cb.on(new JSONObject(), null, err); return; }
            JSONObject info = row != null ? row.optJSONObject("info") : null;
            JSONObject c = info != null ? info.optJSONObject("contact") : null;
            JSONArray links = info != null ? info.optJSONArray("links") : null;
            cb.on(c != null ? c : new JSONObject(), links, null);
        });
    }

    /** আমার contact অবজেক্টে changes মার্জ করে সেভ করে (বাকি info অক্ষত থাকে) */
    static void update(JSONObject changes, Db.Res cb) {
        Db.getMine("profiles", (p, err) -> {
            if (err != null) { cb.on(err); return; }
            try {
                JSONObject info = p != null && p.optJSONObject("info") != null
                        ? p.getJSONObject("info") : new JSONObject();
                JSONObject c = info.optJSONObject("contact") != null
                        ? info.getJSONObject("contact") : new JSONObject();
                Iterator<String> it = changes.keys();
                while (it.hasNext()) { String k = it.next(); c.put(k, changes.get(k)); }
                info.put("contact", c);
                Db.patchMyProfile(new JSONObject().put("info", info), cb);
            } catch (Exception e) {
                cb.on(e.getMessage() == null ? "Could not save" : e.getMessage());
            }
        });
    }

    static String norm(String priv, String dflt) {
        if (PUBLIC.equals(priv) || FRIENDS.equals(priv) || ME.equals(priv)) return priv;
        return dflt;
    }

    static String label(String priv) {
        if (PUBLIC.equals(priv)) return "Public";
        if (FRIENDS.equals(priv)) return "Friends";
        return "Only me";
    }

    static int icon(String priv) {
        if (PUBLIC.equals(priv)) return R.drawable.ic_globe;
        if (FRIENDS.equals(priv)) return R.drawable.ic_pivracy_friends;
        return R.drawable.ic_privacy_lock;
    }

    /** অন্যের প্রোফাইলে এই মান দেখা যাবে কি না */
    static boolean visible(String priv, boolean isMe, boolean isFriend) {
        if (isMe) return true;
        if (PUBLIC.equals(priv)) return true;
        return FRIENDS.equals(priv) && isFriend;
    }

    static void choose(Context ctx, String current, Pick cb) {
        final String[] vals = {PUBLIC, FRIENDS, ME};
        final String[] names = {"Public", "Friends", "Only me"};
        int sel = 2;
        for (int i = 0; i < vals.length; i++) if (vals[i].equals(current)) sel = i;
        new AlertDialog.Builder(ctx).setTitle("Who can see this?")
                .setSingleChoiceItems(names, sel, (d, which) -> {
                    d.dismiss();
                    cb.on(vals[which]);
                })
                .setNegativeButton("Cancel", null).show();
    }

    // ------------------------------------------------------------ সোশ্যাল লিংক সহায়ক

    private static final java.util.Set<String> JUNK = new java.util.HashSet<>(java.util.Arrays.asList(
            "share", "sharer", "sharer.php", "p", "profile.php", "people", "pages", "groups", "group", "watch",
            "reel", "reels", "stories", "hashtag", "channel", "c", "user", "in", "company", "pub", "s", "home",
            "login", "intent", "i", "web", "www"));

    /** একটি সোশ্যাল অ্যাকাউন্ট: platform, handle (নাম; ফাঁকা হতে পারে), url */
    static String[] parseSocial(String raw) {
        try {
            String t = raw == null ? "" : raw.trim();
            if (t.isEmpty()) return null;
            android.net.Uri u = android.net.Uri.parse(t.matches("(?i)^[a-z][a-z0-9+.-]*://.*") ? t : "https://" + t);
            String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
            String platform = null;
            if (host.contains("instagram.com")) platform = "Instagram";
            else if (host.contains("facebook.com") || host.equals("fb.com") || host.contains("fb.me")) platform = "Facebook";
            else if (host.equals("x.com") || host.endsWith(".x.com") || host.contains("twitter.com")) platform = "X (Twitter)";
            else if (host.contains("youtube.com") || host.equals("youtu.be")) platform = "YouTube";
            else if (host.contains("tiktok.com")) platform = "TikTok";
            else if (host.contains("linkedin.com")) platform = "LinkedIn";
            else if (host.equals("t.me") || host.contains("telegram.me")) platform = "Telegram";
            else if (host.equals("wa.me") || host.contains("whatsapp.com")) platform = "WhatsApp";
            if (platform == null) return null;
            String seg = "";
            for (String s : u.getPathSegments()) {
                if (s.isEmpty() || JUNK.contains(s.toLowerCase(Locale.ROOT))) continue;
                seg = s;
                break;
            }
            seg = cleanHandle(seg);
            if (JUNK.contains(seg.toLowerCase(Locale.ROOT))) seg = "";
            return new String[]{platform, seg, u.toString()};
        } catch (Exception e) {
            return null;
        }
    }

    /** @ ও স্পেস সরায়; লিংক দিলে শেষের নাম নেয় */
    static String cleanHandle(String raw) {
        String h = raw == null ? "" : raw.trim();
        if (h.matches("(?i)^[a-z][a-z0-9+.-]*://.*") || h.contains("/")) {
            String[] ph = parseSocial(h);
            if (ph != null) return ph[1];
            int cut = h.lastIndexOf('/');
            h = cut >= 0 ? h.substring(cut + 1) : h;
        }
        while (h.startsWith("@")) h = h.substring(1);
        return h.replace(" ", "");
    }

    /** শুধু নাম (handle) থেকে প্রোফাইল URL */
    static String buildUrl(String platform, String handle) {
        String h = handle == null ? "" : handle.trim();
        if (h.isEmpty()) return "";
        if ("Instagram".equals(platform)) return "https://instagram.com/" + h;
        if ("Facebook".equals(platform)) return "https://facebook.com/" + h;
        if ("X (Twitter)".equals(platform)) return "https://x.com/" + h;
        if ("YouTube".equals(platform)) return "https://youtube.com/@" + h;
        if ("TikTok".equals(platform)) return "https://tiktok.com/@" + h;
        if ("LinkedIn".equals(platform)) return "https://linkedin.com/in/" + h;
        if ("Telegram".equals(platform)) return "https://t.me/" + h;
        if ("WhatsApp".equals(platform)) return "https://wa.me/" + h.replaceAll("[^0-9]", "");
        return h.contains(".") ? "https://" + h : "";
    }

    /** তালিকায় যে নাম দেখাবে: নাম থাকলে নাম, না থাকলে প্ল্যাটফর্মের নাম */
    static String display(String platform, String handle) {
        String h = handle == null ? "" : handle.trim();
        return h.isEmpty() || JUNK.contains(h.toLowerCase(Locale.ROOT)) ? platform : h;
    }

    /** সেভ করা এন্ট্রি থেকে ব্যবহারযোগ্য {platform, handle, url} (url না থাকলে বানায়) */
    static String[] entry(JSONObject o) {
        if (o == null) return null;
        String platform = o.optString("platform", "Other");
        String handle = o.optString("handle", "").trim();
        String url = o.optString("url", "").trim();
        if (handle.isEmpty() && url.isEmpty()) return null;
        if (url.isEmpty()) url = buildUrl(platform, handle);
        return new String[]{platform, handle, url};
    }

    static void openUrl(Context ctx, String url) {
        if (url == null || url.isEmpty()) { TopToast.show(ctx, "No link to open"); return; }
        try {
            ctx.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)));
        } catch (Exception e) {
            TopToast.show(ctx, "Can't open this link");
        }
    }
}
