package com.familybook.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;
import java.util.Map;

/** Login, signup, password reset, logout। error == null মানে সফল। */
public final class AuthRepo {

    public interface Callback { void onResult(String error); }

    /** signUp-এ এই ইমেইলে আগে থেকেই অ্যাকাউন্ট থাকলে error হিসেবে এই মান আসে */
    public static final String EMAIL_EXISTS = "EMAIL_EXISTS";

    private AuthRepo() { }

    /**
     * Supabase-এ "Confirm email" চালু থাকলে, আগে থেকে verified ইমেইল দিয়ে signup করলে error দেয় না;
     * একটা নকল user ফেরত দেয় যার identities খালি ([])। সেটা দিয়েই বোঝা যায় ইমেইলটা ব্যবহৃত।
     */
    private static boolean hasNoIdentities(String body) {
        try {
            JSONObject o = new JSONObject(body);
            JSONObject u = o.has("user") ? o.optJSONObject("user") : o;
            if (u == null) return false;
            JSONArray ids = u.optJSONArray("identities");
            return ids != null && ids.length() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public static void signUp(String email, String password, Map<String, String> meta, Callback cb) {
        try {
            JSONObject body = new JSONObject()
                    .put("email", email)
                    .put("password", password)
                    .put("data", new JSONObject(meta));
            SupabaseRest.call("POST", "/auth/v1/signup", body.toString(), null, false, r -> {
                if (r.ok()) {
                    if (hasNoIdentities(r.body)) { cb.onResult(EMAIL_EXISTS); return; }
                    handle(r, cb);
                } else {
                    String e = SupabaseRest.errorText(r);
                    String low = e == null ? "" : e.toLowerCase(Locale.ROOT);
                    cb.onResult(low.contains("already registered") || low.contains("already been registered")
                            ? EMAIL_EXISTS : e);
                }
            });
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

    public interface EmailCallback { void onResult(String email, String error); }

    /**
     * AGU / username / NID / BRC নম্বর থেকে লগইনের ইমেইল বের করে (সার্ভারের resolve_login_email ফাংশন)।
     * না পেলে email == null এবং error == null; নেটওয়ার্ক/সার্ভারের সমস্যা হলে error থাকে।
     */
    public static void resolveEmail(String ident, EmailCallback cb) {
        try {
            JSONObject body = new JSONObject().put("p_ident", ident);
            SupabaseRest.call("POST", "/rest/v1/rpc/resolve_login_email", body.toString(), null, false, r -> {
                if (!r.ok()) { cb.onResult(null, SupabaseRest.errorText(r)); return; }
                String b = r.body == null ? "" : r.body.trim();
                if (b.isEmpty() || b.equals("null")) { cb.onResult(null, null); return; }
                try {
                    Object v = b.startsWith("\"") ? new org.json.JSONTokener(b).nextValue() : b;
                    String email = v == null ? "" : String.valueOf(v).trim();
                    cb.onResult(email.isEmpty() ? null : email, null);
                } catch (Exception e) {
                    cb.onResult(null, "Unexpected server reply");
                }
            });
        } catch (Exception e) {
            cb.onResult(null, e.getMessage());
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

    /** ইমেইলে পাঠানো কোড (OTP) দিয়ে রেজিস্ট্রেশন verify; সফল হলে session সেভ হয় */
    public static void verifyEmailCode(String email, String code, Callback cb) {
        try {
            JSONObject body = new JSONObject()
                    .put("type", "signup")
                    .put("email", email)
                    .put("token", code);
            SupabaseRest.call("POST", "/auth/v1/verify", body.toString(), null, false, r -> handle(r, cb));
        } catch (Exception e) {
            cb.onResult(e.getMessage());
        }
    }

    /**
     * লগইনের কোড (OTP) ইমেইলে পাঠায় — অ্যাকাউন্ট আগে থেকেই চালু (confirm) থাকলেও কাজ করে।
     * দরকার: Supabase → Authentication → Email Templates → "Magic Link"-এ {{ .Token }} থাকতে হবে।
     */
    public static void sendLoginCode(String email, Callback cb) {
        try {
            JSONObject body = new JSONObject().put("email", email).put("create_user", false);
            SupabaseRest.call("POST", "/auth/v1/otp", body.toString(), null, false,
                    r -> cb.onResult(r.ok() ? null : SupabaseRest.errorText(r)));
        } catch (Exception e) {
            cb.onResult(e.getMessage());
        }
    }

    /** sendLoginCode-এর কোড মেলায়; সফল হলে session সেভ হয় */
    public static void verifyLoginCode(String email, String code, Callback cb) {
        try {
            JSONObject body = new JSONObject()
                    .put("type", "email")
                    .put("email", email)
                    .put("token", code);
            SupabaseRest.call("POST", "/auth/v1/verify", body.toString(), null, false, r -> handle(r, cb));
        } catch (Exception e) {
            cb.onResult(e.getMessage());
        }
    }

    /** verify লিংক/কোড আবার পাঠায় */
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
