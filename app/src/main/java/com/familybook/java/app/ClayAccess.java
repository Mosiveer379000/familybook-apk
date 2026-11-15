package com.familybook.app;

import android.app.Activity;
import android.content.Intent;
import android.widget.Toast;

/** কে Clay Store-এর পুরো স্টোরে ঢুকতে পারবে (অ্যাডমিন)। সাধারণ ইউজার শুধু ডাউনলোড পেজ দেখবে। */
final class ClayAccess {
    private ClayAccess() { }

    // শুধু এই ইমেইল দিয়ে লগইন করা অ্যাকাউন্ট পুরো স্টোর খুলতে পারবে (বড়/ছোট হাতের অক্ষর ধর্তব্য নয়)
    private static final String[] ADMIN_EMAILS = {"masudd16@gmail.com"};

    static boolean isAdmin() {
        String me = SupabaseRest.email();
        if (me == null || !SupabaseRest.isLoggedIn()) return false;
        me = me.trim();
        for (String a : ADMIN_EMAILS) if (a.equalsIgnoreCase(me)) return true;
        return false;
    }

    /** মেনু/বাটন থেকে ডাকো: অ্যাডমিন হলে পুরো স্টোর খোলে */
    static void openStore(Activity a) {
        if (!isAdmin()) {
            Toast.makeText(a, "Admins only", Toast.LENGTH_SHORT).show();
            return;
        }
        a.startActivity(new Intent(a, ClayStoreActivity.class));
    }
}
