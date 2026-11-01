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
        commitSaved(c, name, null);
    }

    /** uid দিলে: সাময়িক তথ্য না থাকলেও এই ডিভাইসের অ্যাকাউন্ট-তালিকা থেকে ইমেইল/পাসওয়ার্ড নেয় */
    public static void commitSaved(Context c, String name, String uid) {
        SharedPreferences p = prefs(c);
        if (p == null) return;
        String pe = p.getString("pending_email", null);
        String pp = p.getString("pending_pass", null);
        if ((pe == null || pp == null) && uid != null) {
            for (Account a : read(p)) {
                if (uid.equals(a.uid)) { pe = a.email; pp = a.pass; break; }
            }
        }
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
        AccountPhoto.clear(c);
    }

    /** সেভ করা অ্যাকাউন্ট থাকলে তার নাম আপডেট (প্রোফাইল থেকে আসা নাম) */
    public static void updateSavedName(Context c, String name) {
        SharedPreferences p = prefs(c);
        if (p == null || name == null || name.isEmpty()) return;
        if (p.getString("saved_email", null) != null) p.edit().putString("saved_name", name).apply();
    }

    /** লগইন পেজে দেখানোর অ্যাকাউন্ট: আগে স্থায়ী সেভ, না থাকলে সাময়িক (pending); {email, pass} বা null */
    public static String[] loginCredentials(Context c) {
        SharedPreferences p = prefs(c);
        if (p == null) return null;
        String e = p.getString("saved_email", null), pw = p.getString("saved_pass", null);
        if (e == null || pw == null) { e = p.getString("pending_email", null); pw = p.getString("pending_pass", null); }
        return (e == null || pw == null) ? null : new String[]{e, pw};
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

    // ---------- Switch Account কার্ড (যত অ্যাকাউন্ট লগইন হয়েছে, সবার কার্ড) ----------
    // পাসওয়ার্ড সেভ হয় শুধু Remember me দিলে; না দিলে শুধু কার্ড থাকে (pass খালি)।

    public static class Card {
        public String uid, email, pass, name;
        public boolean hasPass() { return pass != null && !pass.isEmpty(); }
    }

    private static List<Card> readCards(SharedPreferences p) {
        List<Card> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(p.getString("cards", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Card c = new Card();
                c.uid = o.optString("uid", "");
                c.email = o.optString("email", "");
                c.pass = o.optString("pass", "");
                c.name = o.optString("name", "");
                if (!c.uid.isEmpty() && !c.email.isEmpty()) out.add(c);
            }
        } catch (Exception ignored) { }
        return out;
    }

    private static void writeCards(SharedPreferences p, List<Card> list) {
        try {
            JSONArray arr = new JSONArray();
            for (Card c : list) {
                JSONObject o = new JSONObject();
                o.put("uid", c.uid);
                o.put("email", c.email);
                o.put("pass", c.pass == null ? "" : c.pass);
                o.put("name", c.name == null ? "" : c.name);
                arr.put(o);
            }
            p.edit().putString("cards", arr.toString()).apply();
        } catch (Exception ignored) { }
    }

    public static List<Card> cards(Context c) {
        SharedPreferences p = prefs(c);
        return p == null ? new ArrayList<Card>() : readCards(p);
    }

    /** লগইনের পর ডাকুন। pass == null হলে Remember me নেই: শুধু কার্ড থাকবে, পুরনো পাসওয়ার্ড (থাকলে) মুছে যাবে। name == null হলে আগের নামই থাকবে। */
    public static void saveCard(Context c, String uid, String email, String pass, String name) {
        if (uid == null || uid.isEmpty() || email == null || email.isEmpty()) return;
        SharedPreferences p = prefs(c);
        if (p == null) return;
        List<Card> list = readCards(p);
        Card found = null;
        for (Card x : list) if (uid.equals(x.uid)) { found = x; break; }
        if (found == null) {
            found = new Card();
            found.uid = uid;
            found.name = "";
            list.add(found);
        }
        found.email = email;
        found.pass = pass == null ? "" : pass;
        if (name != null && !name.isEmpty()) found.name = name;
        writeCards(p, list);
    }

    /** কার্ড না থাকলে পাসওয়ার্ড ছাড়া তৈরি করে (রেজিস্ট্রেশন/ভেরিফাই/লিংক লগইনের পরও কার্ড পেতে) */
    public static void ensureCard(Context c, String uid, String email) {
        if (uid == null || uid.isEmpty() || email == null || email.isEmpty()) return;
        SharedPreferences p = prefs(c);
        if (p == null) return;
        List<Card> list = readCards(p);
        for (Card x : list) if (uid.equals(x.uid)) return;
        Card n = new Card();
        n.uid = uid; n.email = email; n.pass = ""; n.name = "";
        list.add(n);
        writeCards(p, list);
    }

    public static void updateCardName(Context c, String uid, String name) {
        if (uid == null || name == null || name.isEmpty()) return;
        SharedPreferences p = prefs(c);
        if (p == null) return;
        List<Card> list = readCards(p);
        boolean changed = false;
        for (Card x : list) {
            if (uid.equals(x.uid) && !name.equals(x.name)) { x.name = name; changed = true; }
        }
        if (changed) writeCards(p, list);
    }

    /** কার্ড সম্পূর্ণ মুছে ফেলে (পাসওয়ার্ড ও ছবিসহ) */
    public static void removeCard(Context c, String uid) {
        SharedPreferences p = prefs(c);
        if (p == null || uid == null) return;
        List<Card> keep = new ArrayList<>();
        for (Card x : readCards(p)) if (!uid.equals(x.uid)) keep.add(x);
        writeCards(p, keep);
        removeAccount(c, uid);
        AccountPhoto.remove(c, uid);
    }

    // ---------- লগআউটের "Save login information" এর জন্য ----------
    // লগইনের সময় জেনে রাখা হয় Remember me ছিল কি না; কার্ড বানানো হয় শুধু লগআউটে Save টিক দিলে।

    /** লগইন সফল হলে ডাকুন। passOrNull == null মানে Remember me নেই (পাসওয়ার্ড সেভ হবে না) */
    public static void setLoginInfo(Context c, String uid, String email, String passOrNull) {
        SharedPreferences p = prefs(c);
        if (p == null || uid == null) return;
        p.edit().putString("cp_uid", uid).putString("cp_email", email == null ? "" : email)
                .putString("cp_pass", passOrNull == null ? "" : passOrNull).apply();
    }

    /** লগআউটে Save টিক দিলে: কার্ড সেভ (Remember me ছিল হলে পাসওয়ার্ডসহ) */
    public static void commitCard(Context c, String uid, String fallbackEmail, String name) {
        SharedPreferences p = prefs(c);
        if (p == null || uid == null) return;
        String email = null, pass = "";
        if (uid.equals(p.getString("cp_uid", null))) {
            email = p.getString("cp_email", null);
            pass = p.getString("cp_pass", "");
        } else {
            for (Card x : readCards(p)) {
                if (uid.equals(x.uid)) { email = x.email; pass = x.pass; break; }
            }
        }
        if (email == null || email.isEmpty()) email = fallbackEmail;
        p.edit().remove("cp_uid").remove("cp_email").remove("cp_pass").apply();
        saveCard(c, uid, email, (pass == null || pass.isEmpty()) ? null : pass, name);
    }

    /** লগআউটে Save টিক না দিলে: কার্ড সেভ হবে না (আগের কার্ড থাকলে সেটাও যাবে) */
    public static void discardLogin(Context c, String uid) {
        SharedPreferences p = prefs(c);
        if (p != null) p.edit().remove("cp_uid").remove("cp_email").remove("cp_pass").apply();
        removeCard(c, uid);
    }
}
