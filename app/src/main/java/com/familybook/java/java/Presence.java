package com.familybook.app;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * অনলাইন স্ট্যাটাস → Supabase table presence (user_id, is_online, updated_at).
 * সীমাবদ্ধতা: অ্যাপ হঠাৎ বন্ধ হলে is_online=true থেকে যেতে পারে (Firebase onDisconnect ছিল)।
 */
public class Presence {

    private static int started = 0;

    public static void onActivityStarted() {
        started++;
        setOnline(true);
    }

    public static void onActivityStopped() {
        started = Math.max(0, started - 1);
        if (started == 0) setOnline(false);
    }

    private static void setOnline(boolean on) {
        String uid = SupabaseRest.uid();
        if (uid == null) return;
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            JSONObject row = new JSONObject()
                    .put("user_id", uid)
                    .put("is_online", on)
                    .put("updated_at", f.format(new Date()));
            SupabaseRest.call("POST", "/rest/v1/presence?on_conflict=user_id", row.toString(),
                    "resolution=merge-duplicates,return=minimal", true, r -> { });
        } catch (Exception ignored) { }
    }
}
