package com.familybook.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Settings > Media (Supabase: media_settings)
 * autoplay           : "mobile_and_wifi" | "wifi_only" | "never"   (Newsfeed, Group, Profile videos)
 * scroll_next_short  : fullscreen Shorts scroll to the next one after one finishes
 * shorts_sound       : fullscreen Shorts start with sound (off = muted)
 * external_browser   : post links open in the default browser after asking
 * Server is the source of truth; SharedPreferences is only a per-account cache for instant reads.
 */
final class MediaPrefs {

    static final String AUTO_ALL = "mobile_and_wifi";
    static final String AUTO_WIFI = "wifi_only";
    static final String AUTO_NEVER = "never";

    interface Json { void on(String json); }
    interface Ok { void on(boolean ok); }

    private static final String FILE = "media_prefs_v2";

    private MediaPrefs() { }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    private static String k(String name) {
        String u = SupabaseRest.uid();
        return (u == null ? "" : u) + "_" + name;
    }

    // ---------- readers ----------

    static String autoplayMode(Context c) {
        String v = sp(c).getString(k("autoplay"), AUTO_NEVER);
        return AUTO_ALL.equals(v) || AUTO_WIFI.equals(v) ? v : AUTO_NEVER;
    }

    /** Feed / Group / Profile video autoplay allowed right now (setting + current network) */
    static boolean canAutoplay(Context c) {
        String m = autoplayMode(c);
        if (AUTO_NEVER.equals(m)) return false;
        if (AUTO_ALL.equals(m)) return true;
        return onWifi(c);
    }

    /** Fullscreen: scroll to the next short when one ends */
    static boolean autoNext(Context c) {
        return sp(c).getBoolean(k("scroll_next_short"), true);
    }

    /** Fullscreen: start Shorts with sound */
    static boolean shortsSound(Context c) {
        return sp(c).getBoolean(k("shorts_sound"), false);
    }

    /** Post links: ask, then open in the default browser */
    static boolean externalBrowser(Context c) {
        return sp(c).getBoolean(k("external_browser"), false);
    }

    private static boolean onWifi(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    c.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            Network n = cm.getActiveNetwork();
            if (n == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            return caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
        } catch (Exception e) {
            return false;
        }
    }

    // ---------- JSON for the HTML page ----------

    /** Instant read from the local cache (no network) */
    static String cachedJson(Context c) {
        try {
            return new JSONObject()
                    .put("autoplay", autoplayMode(c))
                    .put("scrollNextShort", autoNext(c))
                    .put("startShortsWithSound", shortsSound(c))
                    .put("externalBrowser", externalBrowser(c))
                    .toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    // ---------- server sync ----------

    /** Pull from Supabase into the cache; done runs on the main thread either way */
    static void refresh(final Context c, final Runnable done) {
        final String me = SupabaseRest.uid();
        if (me == null) { if (done != null) done.run(); return; }
        final Context app = c.getApplicationContext();
        SupabaseRest.call("GET", "/rest/v1/media_settings?user_id=eq." + me
                        + "&select=autoplay,scroll_next_short,shorts_sound,external_browser",
                null, null, true, r -> {
                    if (r.ok()) {
                        try {
                            JSONArray a = new JSONArray(r.body);
                            if (a.length() > 0) {
                                JSONObject o = a.getJSONObject(0);
                                sp(app).edit()
                                        .putString(k("autoplay"), o.optString("autoplay", AUTO_NEVER))
                                        .putBoolean(k("scroll_next_short"), o.optBoolean("scroll_next_short", true))
                                        .putBoolean(k("shorts_sound"), o.optBoolean("shorts_sound", false))
                                        .putBoolean(k("external_browser"), o.optBoolean("external_browser", false))
                                        .apply();
                                ShortPlayerView.setAutoNext(autoNext(app));
                            }
                        } catch (Exception ignored) { }
                    }
                    if (done != null) done.run();
                });
    }

    static void fetch(final Context c, final Json cb) {
        refresh(c, () -> cb.on(cachedJson(c)));
    }

    /** Save the whole state from the HTML page. The cache changes only after the server accepted it. */
    static void save(final Context c, String json, final Ok done) {
        final String me = SupabaseRest.uid();
        if (me == null || json == null) { if (done != null) done.on(false); return; }
        final Context app = c.getApplicationContext();
        try {
            JSONObject in = new JSONObject(json);
            String mode = in.optString("autoplay", AUTO_NEVER);
            if (!AUTO_ALL.equals(mode) && !AUTO_WIFI.equals(mode)) mode = AUTO_NEVER;
            final String fMode = mode;
            final boolean next = in.optBoolean("scrollNextShort", true);
            final boolean sound = in.optBoolean("startShortsWithSound", false);
            final boolean ext = in.optBoolean("externalBrowser", false);
            JSONObject row = new JSONObject()
                    .put("user_id", me)
                    .put("autoplay", fMode)
                    .put("scroll_next_short", next)
                    .put("shorts_sound", sound)
                    .put("external_browser", ext);
            SupabaseRest.call("POST", "/rest/v1/media_settings?on_conflict=user_id",
                    row.toString(), "resolution=merge-duplicates,return=minimal", true, r -> {
                        if (r.ok()) {
                            sp(app).edit()
                                    .putString(k("autoplay"), fMode)
                                    .putBoolean(k("scroll_next_short"), next)
                                    .putBoolean(k("shorts_sound"), sound)
                                    .putBoolean(k("external_browser"), ext)
                                    .apply();
                            ShortPlayerView.setAutoNext(next);
                        }
                        if (done != null) done.on(r.ok());
                    });
        } catch (Exception e) {
            if (done != null) done.on(false);
        }
    }

    /** Used by the "Scroll to next reel automatically" row in the Shorts menu: same setting, same server row */
    static void setAutoNext(final Context c, boolean on) {
        try {
            JSONObject o = new JSONObject(cachedJson(c)).put("scrollNextShort", on);
            save(c, o.toString(), null);
        } catch (Exception ignored) { }
    }
}
