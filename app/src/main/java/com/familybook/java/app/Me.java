package com.familybook.app;

/** Supabase session-এর সহজ wrapper। Firebase user-এর জায়গায় ব্যবহার হবে। */
public final class Me {
    private Me() { }

    public static boolean isLoggedIn() {
        return SupabaseRest.isLoggedIn();
    }

    /** লগইন করা user-এর uid, না থাকলে null */
    public static String uid() {
        return SupabaseRest.uid();
    }

    /** লগইন করা user-এর email, পুরনো session-এ না থাকলে null */
    public static String email() {
        return SupabaseRest.email();
    }
}
