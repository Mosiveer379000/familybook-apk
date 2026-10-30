package com.familybook.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Supabase REST / Auth এর একমাত্র প্রবেশপথ। শুধু URL ও publishable key। */
public final class SupabaseRest {

    public static final String BASE = "https://fnrixuetjyhsacukahna.supabase.co";
    public static final String KEY = "sb_publishable_P7lNqs7iNFe_GzxBxaqwDg_qxVl4mXb";

    public static class Resp {
        public final int code;
        public final String body;
        Resp(int code, String body) { this.code = code; this.body = body; }
        public boolean ok() { return code >= 200 && code < 300; }
    }

    public interface Done { void run(Resp r); }

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build();
    private static final ExecutorService IO = Executors.newFixedThreadPool(4);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static SharedPreferences prefs;

    private SupabaseRest() { }

    /** FamilyBookApp.onCreate-এ একবার ডাকুন। */
    public static void init(Context app) {
        prefs = app.getSharedPreferences("fb_session", Context.MODE_PRIVATE);
    }

    // ---------- session ----------

    public static boolean isLoggedIn() {
        return prefs != null && prefs.contains("refresh");
    }

    public static String uid() {
        return prefs == null ? null : prefs.getString("uid", null);
    }

    /** লগইন করা ইউজারের ইমেইল (পুরনো সেশনে না থাকলে null; পরের লগইন/টোকেন রিফ্রেশে আসবে) */
    public static String email() {
        String e = prefs == null ? null : prefs.getString("email", null);
        return (e == null || e.isEmpty()) ? null : e;
    }

    static void saveSession(String json) {
        try {
            JSONObject j = new JSONObject(json);
            if (!j.has("access_token")) return;
            prefs.edit()
                    .putString("access", j.getString("access_token"))
                    .putString("refresh", j.getString("refresh_token"))
                    .putString("uid", j.getJSONObject("user").getString("id"))
                    .putString("email", j.getJSONObject("user").optString("email", ""))
                    .putLong("exp", System.currentTimeMillis() / 1000 + j.optLong("expires_in", 3600))
                    .apply();
        } catch (Exception ignored) { }
    }

    public static void clearSession() {
        if (prefs != null) prefs.edit().clear().apply();
    }

    /**
     * মেয়াদ শেষ হলে refresh token দিয়ে নতুন access token নেয়।
     * নেটওয়ার্ক লাগতে পারে, তাই শুধু ব্যাকগ্রাউন্ড থ্রেডে ডাকুন (ছবি আপলোডের জন্য public করা হয়েছে)।
     */
    public static synchronized String accessToken() {
        if (!isLoggedIn()) return null;
        long exp = prefs.getLong("exp", 0);
        if (System.currentTimeMillis() / 1000 < exp - 60) {
            return prefs.getString("access", null);
        }
        String refresh = prefs.getString("refresh", "");
        Resp r = send("POST", "/auth/v1/token?grant_type=refresh_token",
                "{\"refresh_token\":\"" + refresh + "\"}", null, null);
        if (!r.ok()) {
            if (r.code == 400 || r.code == 401) clearSession();
            return null;
        }
        saveSession(r.body);
        return prefs.getString("access", null);
    }

    // ---------- HTTP ----------

    static Resp send(String method, String path, String json, String bearer, String prefer) {
        try {
            Request.Builder b = new Request.Builder()
                    .url(BASE + path)
                    .header("apikey", KEY)
                    .header("Authorization", "Bearer " + (bearer != null ? bearer : KEY))
                    .header("Accept", "application/json");
            if (prefer != null) b.header("Prefer", prefer);

            if (method.equals("GET")) {
                b.get();
            } else {
                b.method(method, RequestBody.create(json == null ? "" : json, JSON));
            }

            try (Response res = HTTP.newCall(b.build()).execute()) {
                String text = res.body() == null ? "" : res.body().string();
                return new Resp(res.code(), text);
            }
        } catch (IOException | IllegalArgumentException e) {
            return new Resp(-1, e.getMessage() == null ? "network" : e.getMessage());
        }
    }

    /**
     * সব কাজ background thread-এ চলে, callback main thread-এ আসে।
     * useSession=true হলে login করা user-এর token যায় (RLS এর জন্য দরকার)।
     */
    public static void call(String method, String path, String json, String prefer,
                            boolean useSession, Done done) {
        IO.execute(() -> {
            String token = useSession ? accessToken() : null;
            Resp r = send(method, path, json, token, prefer);
            if (done != null) MAIN.post(() -> done.run(r));
        });
    }

    /** ব্যবহারকারীকে দেখানোর মতো error বার্তা। */
    public static String errorText(Resp r) {
        if (r.code == -1) return "ইন্টারনেট নেই বা সার্ভারে পৌঁছানো যায়নি";
        try {
            JSONObject j = new JSONObject(r.body);
            for (String k : new String[]{"error_description", "msg", "message", "error"}) {
                String v = j.optString(k, "");
                if (!v.isEmpty()) return v;
            }
        } catch (Exception ignored) { }
        return "HTTP " + r.code;
    }
}
