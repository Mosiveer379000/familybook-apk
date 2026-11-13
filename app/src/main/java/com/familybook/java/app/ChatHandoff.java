package com.familybook.app;

import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import org.json.JSONObject;

/**
 * Family Chat-এ নিজে থেকে লগইন (Messenger-এর মতো)।
 * Family Book-এর সেশন (টোকেন) কখনো অন্য অ্যাপকে দেওয়া হয় না। তার বদলে সার্ভারের "chat-handoff" ফাংশন
 * একবার-ব্যবহারযোগ্য ছোট-মেয়াদি কোড দেয়, Family Chat সেই কোড দিয়ে নিজের আলাদা সেশন বানায়।
 * কোড না পেলে (ইন্টারনেট নেই ইত্যাদি) আগের মতোই Family Chat খোলে।
 */
final class ChatHandoff {

    /** true হলে কোড পাওয়া/না-পাওয়ার কারণ টোস্টে দেখায় (ঠিকঠাক চললে false করে দিন) */
    private static final boolean DEBUG = true;

    interface Ready {
        /** tokenHash == null হলে কোড পাওয়া যায়নি */
        void onReady(String tokenHash);
    }

    private ChatHandoff() { }

    static void fetch(final Context c, final Ready r) {
        if (!SupabaseRest.isLoggedIn()) {
            if (DEBUG) Toast.makeText(c, "Account no longer exist", Toast.LENGTH_LONG).show();
            r.onReady(null);
            return;
        }
        SupabaseRest.call("POST", "/functions/v1/chat-handoff", "{}", null, true, resp -> {
            String th = null;
            if (resp.ok()) {
                try {
                    th = new JSONObject(resp.body).optString("token_hash", null);
                } catch (Exception ignored) { }
            }
            if (DEBUG) {
                String body = resp.body == null ? "" : resp.body;
                if (body.length() > 160) body = body.substring(0, 160);
                Toast.makeText(c, th != null && !th.isEmpty()
                                ? "Connected"
                                : "Offline" + resp.code + " " + body,
                        Toast.LENGTH_LONG).show();
            }
            r.onReady(th);
        });
    }

    static void putExtras(Intent i, String tokenHash) {
        if (tokenHash == null || tokenHash.isEmpty()) return;
        i.putExtra("handoff", tokenHash);
        i.putExtra("fb_uid", SupabaseRest.uid());
    }
}
