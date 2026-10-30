package com.familybook.app;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** লগইন তথ্য এনক্রিপ্টেড করে ফোনে রাখে (Save login information এবং অ্যাকাউন্ট সুইচের জন্য) */
public class SecureStore {

    private static SharedPreferences prefs(Context c) {
        try {
            MasterKey key = new MasterKey.Builder(c)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build();
            return EncryptedSharedPreferences.create(c, "fb_secure", key,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (Exception e) {
            return null;
        }
    }

    // লগইন/রেজিস্ট্রেশনের পর সাময়িক রাখা হয়
    public static void savePending(Context c, String email, String pass) {
        SharedPreferences p = prefs(c);
        if (p != null) p.edit().putString("pending_email", email)
                .putString("pending_pass", pass).apply();
    }

    /** সাময়িক তথ্য মুছে ফেলে; একই ইমেইলের সুইচ-তালিকার এন্ট্রিও মোছে (Save না দিয়ে লগআউট করলে) */
    public static void clearPending(Context c) {
        SharedPreferences p = prefs(c);
        if (p == null) return;
        String pe = p.getString("pending_email", null);
        if (pe != null) {
            List<Account> list = read(p);
            List<Account> keep = new ArrayList<>();
            for (Account a : list) if (!pe.equalsIgnoreCase(a.email)) keep.add(a);
            if (keep.size() != list.size()) write(p, keep);
        }
        p.edit().remove("pending_email").remove("pending_pass").apply();
    }

    // লগআউটের সময় "Save" টিক দিলে স্থায়ীভাবে সেভ
    public static void commitSaved(Context c, String name) {
        SharedPreferences p = prefs(c);
        if (p == null) return;
        String pe = p.getString("pending_email", null);
        String pp = p.getString("pending_pass", null);
        SharedPreferences.Editor e = p.edit();
        if (pe != null && pp != null) {
            e.putString("saved_email", pe).putString("saved_pass", pp);
        }
        if (p.contains("saved_email") || pe != null) e.putString("saved_name", name);
        e.remove("pending_email").remove("pending_pass").apply();
    }

    public static boolean hasSaved(Context c) {
        SharedPreferences p = prefs(c);
        return p != null && p.getString("saved_email", null) != null
                && p.getString("saved_pass", null) != null;
    }

    public static String savedEmail(Context c) {
        SharedPreferences p = prefs(c);
        return p == null ? null : p.getString("saved_email", null);
    }

    public static String savedPass(Context c) {
        SharedPreferences p = prefs(c);
        return p == null ? null : p.getString("saved_pass", null);
    }

    public static String savedName(Context c) {
        SharedPreferences p = prefs(c);
        return p == null ? null : p.getString("saved_name", null);
    }

    public static void clearSaved(Context c) {
        SharedPreferences p = prefs(c);
        if (p != null) p.edit().remove("saved_email").remove("saved_pass")
                .remove("saved_name").apply();
    }

    // ---------- এই ডিভাইসের একাধিক অ্যাকাউন্ট (Profile > Menu > + থেকে সুইচ) ----------

    public static class Account {
        public String uid, email, pass, name;
    }

    private static List<Account> read(SharedPreferences p) {
        List<Account> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(p.getString("accounts", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Account a = new Account();
                a.uid = o.optString("uid", "");
                a.email = o.optString("email", "");
                a.pass = o.optString("pass", "");
                a.name = o.optString("name", "");
                if (!a.uid.isEmpty() && !a.email.isEmpty() && !a.pass.isEmpty()) out.add(a);
            }
        } catch (Exception ignored) { }
        return out;
    }

    private static void write(SharedPreferences p, List<Account> list) {
        try {
            JSONArray arr = new JSONArray();
            for (Account a : list) {
                JSONObject o = new JSONObject();
                o.put("uid", a.uid);
                o.put("email", a.email);
                o.put("pass", a.pass);
                o.put("name", a.name == null ? "" : a.name);
                arr.put(o);
            }
            p.edit().putString("accounts", arr.toString()).apply();
        } catch (Exception ignored) { }
    }

    public static List<Account> accounts(Context c) {
        SharedPreferences p = prefs(c);
        return p == null ? new ArrayList<Account>() : read(p);
    }

    /** আছে হলে আপডেট, না থাকলে যোগ (uid দিয়ে মেলানো) */
    public static void rememberAccount(Context c, String uid, String email, String pass, String name) {
        if (uid == null || email == null || pass == null) return;
        SharedPreferences p = prefs(c);
        if (p == null) return;
        List<Account> list = read(p);
        Account found = null;
        for (Account a : list) if (uid.equals(a.uid)) { found = a; break; }
        if (found == null) {
            found = new Account();
            found.uid = uid;
            found.name = "";
            list.add(found);
        }
        found.email = email;
        found.pass = pass;
        if (name != null && !name.isEmpty()) found.name = name;
        write(p, list);
    }

    /** এই মুহূর্তের লগইনের সাময়িক তথ্য (pending) থাকলে সেটিকে তালিকায় তোলে */
    public static void adoptPendingFor(Context c, String uid, String email) {
        SharedPreferences p = prefs(c);
        if (p == null || uid == null || email == null) return;
        String pe = p.getString("pending_email", null);
        String pp = p.getString("pending_pass", null);
        if (pe != null && pp != null && email.equalsIgnoreCase(pe)) {
            rememberAccount(c, uid, pe, pp, null);
        }
    }

    public static boolean isRemembered(Context c, String uid) {
        for (Account a : accounts(c)) if (a.uid.equals(uid)) return true;
        return false;
    }

    public static void updateAccountName(Context c, String uid, String name) {
        if (name == null || name.isEmpty()) return;
        SharedPreferences p = prefs(c);
        if (p == null) return;
        List<Account> list = read(p);
        boolean changed = false;
        for (Account a : list) {
            if (a.uid.equals(uid) && !name.equals(a.name)) { a.name = name; changed = true; }
        }
        if (changed) write(p, list);
    }

    public static void removeAccount(Context c, String uid) {
        SharedPreferences p = prefs(c);
        if (p == null) return;
        List<Account> keep = new ArrayList<>();
        for (Account a : read(p)) if (!a.uid.equals(uid)) keep.add(a);
        write(p, keep);
    }
}
