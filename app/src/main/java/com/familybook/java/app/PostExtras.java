package com.familybook.app;

import android.graphics.Typeface;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * পোস্টের Feeling/Activity + কোলাবরেটর/ট্যাগ নাম + লোকেশন।
 * ফলাফল: " — 😊 feeling happy with Farhana Kanom Dina at Patenga, Chittagong, Bangladesh."
 * (feeling-এর শব্দ, নাম ও লোকেশন বোল্ড)
 * with-তালিকা = approve হওয়া কোলাবরেটর (আগে) + ট্যাগ করা সবাই (post_with_names ভিউ)।
 * pending ও accepted দুটোর নামই দেখায়; declined হলে নাম সরে যায়।
 */
public final class PostExtras {

    private static final Map<String, String> FEELINGS = new ConcurrentHashMap<>(); // postId -> "😊 happy"
    private static final Map<String, String> ACTIVITIES = new ConcurrentHashMap<>(); // postId -> "🍽 Eating pizza"
    private static final Map<String, String> LOCATIONS = new ConcurrentHashMap<>(); // postId -> "Patenga, Chittagong, Bangladesh"
    private static final Map<String, String> COLLABS = new ConcurrentHashMap<>();  // postId -> "Farhana and Rahim"

    private PostExtras() { }

    private static long lastLoad = 0;

    /** সম্প্রতি (৩০ সেকেন্ডে) লোড না হয়ে থাকলে load() করে; নইলে সাথে সাথে done। যেকোনো পোস্ট-তালিকা (ফিড/প্রোফাইল/গ্রুপ/সার্চ) থেকে নিরাপদে ডাকা যায় */
    public static void ensureLoaded(final Runnable done) {
        long now = System.currentTimeMillis();
        if (now - lastLoad < 30_000L) { if (done != null) done.run(); return; }
        lastLoad = now;
        load(done);
    }

    /** সর্বশেষ ৫০০ পোস্টের feeling/activity + লোকেশন + কোলাবরেটর আনে। শেষে done চলে (main thread)। */
    public static void load(final Runnable done) {
        SupabaseRest.call("GET",
                "/rest/v1/posts?select=id,feeling,activity"
                        + "&or=(feeling.not.is.null,activity.not.is.null)"
                        + "&order=created_at.desc&limit=500",
                null, null, true, r -> {
                    if (r.ok()) {
                        try {
                            JSONArray a = new JSONArray(r.body);
                            FEELINGS.clear();
                            ACTIVITIES.clear();
                            for (int i = 0; i < a.length(); i++) {
                                JSONObject o = a.getJSONObject(i);
                                String id = o.optString("id", "");
                                String f = clean(o.optString("feeling", ""));
                                String act = clean(o.optString("activity", ""));
                                if (id.isEmpty()) continue;
                                if (!f.isEmpty()) FEELINGS.put(id, f);
                                else if (!act.isEmpty()) ACTIVITIES.put(id, act);
                            }
                        } catch (Exception ignored) { }
                    }
                    loadLocations(done);
                });
    }

    // আলাদা রিকোয়েস্ট: posts.location কলাম এখনো না থাকলেও feeling/with ঠিক থাকে
    private static void loadLocations(final Runnable done) {
        SupabaseRest.call("GET",
                "/rest/v1/posts?select=id,location&location=not.is.null"
                        + "&order=created_at.desc&limit=500",
                null, null, true, r -> {
                    if (r.ok()) {
                        try {
                            JSONArray a = new JSONArray(r.body);
                            LOCATIONS.clear();
                            for (int i = 0; i < a.length(); i++) {
                                JSONObject o = a.getJSONObject(i);
                                String id = o.optString("id", "");
                                String loc = clean(o.optString("location", ""));
                                if (!id.isEmpty() && !loc.isEmpty()) LOCATIONS.put(id, loc);
                            }
                        } catch (Exception ignored) { }
                    }
                    loadCollabs(done);
                });
    }

    private static void loadCollabs(final Runnable done) {
        SupabaseRest.call("GET",
                "/rest/v1/post_with_names?select=post_id,names&limit=500",
                null, null, true, r -> {
                    if (r.ok()) {
                        try {
                            JSONArray a = new JSONArray(r.body);
                            COLLABS.clear();
                            for (int i = 0; i < a.length(); i++) {
                                JSONObject o = a.getJSONObject(i);
                                String id = o.optString("post_id", "");
                                JSONArray arr = o.optJSONArray("names");
                                java.util.ArrayList<String> list = new java.util.ArrayList<>();
                                if (arr != null) {
                                    for (int k = 0; k < arr.length(); k++) {
                                        String nm = clean(arr.optString(k, ""));
                                        if (!nm.isEmpty()) list.add(nm);
                                    }
                                }
                                String n = withText(list);
                                if (!id.isEmpty() && n != null) COLLABS.put(id, n);
                            }
                        } catch (Exception ignored) { }
                    }
                    if (done != null) done.run();
                });
    }

    /** "with" নাম (কোলাবরেটর/ট্যাগ করা), যেমন "Farhana and Rahim"; না থাকলে null */
    public static String collab(String postId) {
        return postId == null ? null : COLLABS.get(postId);
    }

    /** এই পোস্টটা "X uploaded a short" ফিড-পোস্ট কি না */
    public static boolean isShort(String postId) {
        String a = postId == null ? null : ACTIVITIES.get(postId);
        return a != null && a.equals(ProfilePhotoPost.MARK + ShortUploader.SHORT_PHRASE);
    }

    /** "X updated his profile picture / cover photo" পোস্ট কি না (Short পোস্ট নয়) */
    public static boolean isProfilePhoto(String postId) {
        String a = postId == null ? null : ACTIVITIES.get(postId);
        return a != null && a.startsWith(ProfilePhotoPost.MARK) && !isShort(postId);
    }

    /** সাধারণ লেখা (স্টাইল ছাড়া): " — 😊 feeling happy with X at Y." / কিছু না থাকলে null */
    public static String line(String postId) {
        CharSequence s = styled(postId, 0, 0);
        return s == null ? null : s.toString();
    }

    /**
     * ফিডের জন্য স্টাইল করা লাইন। normalColor = সাধারণ শব্দ (— feeling with at),
     * boldColor = বোল্ড অংশ (feeling-এর শব্দ, নাম, লোকেশন)। রং 0 হলে রং বসে না।
     */
    public static CharSequence styled(String postId, int normalColor, int boldColor) {
        if (postId == null) return null;
        return build(FEELINGS.get(postId), ACTIVITIES.get(postId), COLLABS.get(postId),
                LOCATIONS.get(postId), normalColor, boldColor);
    }

    /** New post স্ক্রিনের হেডারের জন্য (সবকিছু কোড থেকে আসে, ডাটাবেস থেকে না) */
    public static SpannableStringBuilder compose(String feeling, String activity,
                                                 java.util.List<String> withNames, String location,
                                                 int normalColor, int boldColor) {
        return build(feeling, activity, withText(withNames), location, normalColor, boldColor);
    }

    private static SpannableStringBuilder build(String feeling, String activity, String withText,
                                                String location, int normalColor, int boldColor) {
        feeling = clean(feeling);
        activity = clean(activity);
        withText = clean(withText);
        location = clean(location);
        if (feeling.isEmpty() && activity.isEmpty() && withText.isEmpty() && location.isEmpty()) {
            return null;
        }
        SpannableStringBuilder sb = new SpannableStringBuilder();
        if (activity.startsWith(ProfilePhotoPost.MARK)) {
            // প্রোফাইল/কভার ছবি আপডেট: "X updated his profile picture."
            String ph = activity.substring(ProfilePhotoPost.MARK.length());
            int sp2 = ph.lastIndexOf(' ');
            // "uploaded a short" → শুধু "short" বোল্ড; বাকিগুলো ("updated his profile picture") শেষ দুই শব্দ বোল্ড
            int cut = ph.equals(ShortUploader.SHORT_PHRASE) ? sp2
                    : (sp2 > 0 ? ph.lastIndexOf(' ', sp2 - 1) : -1);
            if (cut > 0) {
                appendN(sb, " " + ph.substring(0, cut + 1), normalColor);
                appendB(sb, ph.substring(cut + 1), boldColor);
            } else {
                appendN(sb, " " + ph, normalColor);
            }
            appendN(sb, ".", normalColor);
            return sb;
        }
        if (!feeling.isEmpty()) {
            String[] p = split(feeling);
            appendN(sb, " — " + p[0] + " feeling ", normalColor);
            appendB(sb, p[1], boldColor);
        } else if (!activity.isEmpty()) {
            String[] p = split(activity);
            appendN(sb, " — " + p[0] + " ", normalColor);
            appendB(sb, lowerFirst(p[1]), boldColor);
        }
        if (!withText.isEmpty()) {
            appendN(sb, sb.length() == 0 ? " — with " : " with ", normalColor);
            appendB(sb, withText, boldColor);
        }
        if (!location.isEmpty()) {
            appendN(sb, sb.length() == 0 ? " — at " : " at ", normalColor);
            appendB(sb, location, boldColor);
        }
        appendN(sb, ".", normalColor);
        return sb;
    }

    private static void appendN(SpannableStringBuilder sb, String s, int color) {
        int st = sb.length();
        sb.append(s);
        if (color != 0) {
            sb.setSpan(new ForegroundColorSpan(color), st, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private static void appendB(SpannableStringBuilder sb, String s, int color) {
        int st = sb.length();
        sb.append(s);
        sb.setSpan(new StyleSpan(Typeface.BOLD), st, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (color != 0) {
            sb.setSpan(new ForegroundColorSpan(color), st, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    /** ফেসবুকের মতো: "A" / "A and B" / "A and 2 others"; খালি হলে null */
    public static String withText(java.util.List<String> names) {
        if (names == null || names.isEmpty()) return null;
        int n = names.size();
        if (n == 1) return names.get(0);
        if (n == 2) return names.get(0) + " and " + names.get(1);
        int rest = n - 1;
        return names.get(0) + " and " + rest + " others";
    }

    private static String clean(String s) {
        return (s == null || "null".equals(s)) ? "" : s.trim();
    }

    private static String[] split(String t) {
        int i = t.indexOf(' ');
        if (i < 0) return new String[]{"", t};
        return new String[]{t.substring(0, i), t.substring(i + 1)};
    }

    private static String lowerFirst(String t) {
        if (t == null || t.isEmpty()) return t;
        return Character.toLowerCase(t.charAt(0)) + t.substring(1);
    }
}
