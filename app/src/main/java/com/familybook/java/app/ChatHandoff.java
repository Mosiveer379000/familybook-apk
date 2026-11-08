package com.familybook.app;

import android.content.Intent;

import org.json.JSONObject;

/**
 * Family Chat-এ নিজে থেকে লগইন (Messenger-এর মতো)।
 * Family Book-এর সেশন (টোকেন) কখনো অন্য অ্যাপকে দেওয়া হয় না। তার বদলে সার্ভারের "chat-handoff" ফাংশন
 * একবার-ব্যবহারযোগ্য ছোট-মেয়াদি কোড দেয়, Family Chat সেই কোড দিয়ে নিজের আলাদা সেশন বানায়।
 * কোড না পেলে (ইন্টারনেট নেই ইত্যাদি) আগের মতোই Family Chat খোলে।
 */
final class ChatHandoff {

    interface Ready {
        /** tokenHash == null হলে কোড পাওয়া যায়নি */
        void onReady(String tokenHash);
    }

    private ChatHandoff() { }

    static void fetch(final Ready r) {
        if (!SupabaseRest.isLoggedIn()) {
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
            r.onReady(th);
        });
    }

    static void putExtras(Intent i, String tokenHash) {
        if (tokenHash == null || tokenHash.isEmpty()) return;
        i.putExtra("handoff", tokenHash);
        i.putExtra("fb_uid", SupabaseRest.uid());
    }
}
