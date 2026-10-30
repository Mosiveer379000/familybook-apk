package com.familybook.app;

import org.json.JSONObject;

import java.util.Locale;
import java.util.Map;

/** Login, signup, password reset, logout। error == null মানে সফল। */
public final class AuthRepo {

    public interface Callback { void onResult(String error); }

    private AuthRepo() { }

    public static void signUp(String email, String password, Map<String, String> meta, Callback cb) {
        try {
            JSONObject body = new JSONObject()
                    .put("email", email)
                    .put("password", password)
                    .put("data", new JSONObject(meta));
            SupabaseRest.call("POST", "/auth/v1/signup", body.toString(), null, false,
                    r -> handle(r, cb));
        } catch (Exception e) {
            cb.onResult(e.getMessage());
        }
    }

    public static void login(String email, String password, Callback cb) {
        try {
            JSONObject body = new JSONObject()
                    .put("email", email)
                    .put("password", password);
            SupabaseRest.call("POST", "/auth/v1/token?grant_type=password", body.toString(),
                    null, false, r -> handle(r, cb));
        } catch (Exception e) {
            cb.onResult(e.getMessage());
        }
    }

    public static void resetPassword(String email, Callback cb) {
        try {
            JSONObject body = new JSONObject().put("email", email);
            SupabaseRest.call("POST", "/auth/v1/recover", body.toString(), null, false,
                    r -> handle(r, cb));
        } catch (Exception e) {
            cb.onResult(e.getMessage());
        }
    }

    public static void logout(Callback cb) {
        SupabaseRest.call("POST", "/auth/v1/logout", "{}", null, true, r -> {
            SupabaseRest.clearSession();
            cb.onResult(null);
        });
    }

    private static void handle(SupabaseRest.Resp r, Callback cb) {
        if (r.ok()) {
            SupabaseRest.saveSession(r.body);   // Email confirm চালু থাকলে এখানে session থাকবে না
            cb.onResult(null);
        } else {
            cb.onResult(SupabaseRest.errorText(r));
        }
    }

    /** ইমেইল verify না হলে Supabase যে error দেয় */
    public static boolean isUnverified(String error) {
        return error != null && error.toLowerCase(Locale.ROOT).contains("not confirmed");
    }

    /** verify লিংক আবার পাঠায় */
    public static void resendVerification(String email, Callback cb) {
        try {
            JSONObject body = new JSONObject().put("type", "signup").put("email", email);
            SupabaseRest.call("POST", "/auth/v1/resend", body.toString(), null, false,
                    r -> cb.onResult(r.ok() ? null : SupabaseRest.errorText(r)));
        } catch (Exception e) {
            cb.onResult(e.getMessage());
        }
    }
}
