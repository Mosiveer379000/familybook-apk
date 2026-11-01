package com.familybook.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;

/** পোস্ট এডিটে বদলানো যায় এমন অতিরিক্ত তথ্য: feeling/activity, লোকেশন, গান */
public final class PostEditExtras {
    public String feeling, activity, location;
    public String musicTitle, musicArtist, musicCover, musicUrl;
    public int musicStart;
    // Tag / Collab (get_post_tag_state পাওয়া গেলেই tagsKnown = true)
    public boolean tagsKnown;
    public ArrayList<String> tagIds = new ArrayList<>(), tagNames = new ArrayList<>();
    public String collabId, collabName;

    /** get_post_tag_state-এর সারিগুলো: kind = tag | collab */
    public void loadTags(JSONArray rows) {
        tagIds.clear(); tagNames.clear(); collabId = null; collabName = null;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject o = rows.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("user_id", ""), nm = o.optString("name", "");
            if ("collab".equals(o.optString("kind"))) { collabId = id; collabName = nm; }
            else { tagIds.add(id); tagNames.add(nm); }
        }
        tagsKnown = true;
    }

    public boolean tagsChanged(PostEditExtras o) {
        if (!tagsKnown) return false;
        ArrayList<String> a = new ArrayList<>(tagIds), b = new ArrayList<>(o.tagIds);
        Collections.sort(a); Collections.sort(b);
        return !a.equals(b) || !(collabId == null ? o.collabId == null : collabId.equals(o.collabId));
    }

    /** "A", "A and B", "A and 2 others" (কোলাব আগে) */
    public String tagLabel() {
        ArrayList<String> n = new ArrayList<>();
        if (collabName != null && !collabName.isEmpty()) n.add(collabName);
        for (int i = 0; i < tagNames.size(); i++) {
            String id = i < tagIds.size() ? tagIds.get(i) : null;
            if (id != null && id.equals(collabId)) continue;
            n.add(tagNames.get(i));
        }
        return n.isEmpty() ? null : PostExtras.withText(n);
    }

    /** posts টেবিলের একটি সারি (select=*) থেকে */
    public static PostEditExtras from(JSONObject o) {
        PostEditExtras e = new PostEditExtras();
        e.feeling = s(o, "feeling");
        e.activity = s(o, "activity");
        e.location = s(o, "location");
        e.musicTitle = s(o, "music_title");
        e.musicArtist = s(o, "music_artist");
        e.musicCover = s(o, "music_cover");
        e.musicUrl = s(o, "music_url");
        e.musicStart = o.optInt("music_start", 0);
        return e;
    }

    public PostEditExtras copy() {
        PostEditExtras e = new PostEditExtras();
        e.feeling = feeling; e.activity = activity; e.location = location;
        e.musicTitle = musicTitle; e.musicArtist = musicArtist; e.musicCover = musicCover;
        e.musicUrl = musicUrl; e.musicStart = musicStart;
        e.tagsKnown = tagsKnown; e.tagIds = new ArrayList<>(tagIds); e.tagNames = new ArrayList<>(tagNames);
        e.collabId = collabId; e.collabName = collabName;
        return e;
    }

    private static String s(JSONObject o, String k) {
        if (o.isNull(k)) return null;
        String v = o.optString(k, "").trim();
        return v.isEmpty() || "null".equals(v) ? null : v;
    }
}
