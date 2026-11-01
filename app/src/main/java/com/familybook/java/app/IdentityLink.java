package com.familybook.app;

import org.json.JSONObject;

import java.util.Locale;

/**
 * NID / জন্ম নিবন্ধন (BID) নম্বর দিয়ে স্বামী-স্ত্রীর অ্যাকাউন্ট মেলানোর সহায়ক।
 *
 * - নম্বর কখনো profiles.info-তে রাখা হয় না (অন্যরা পড়তে পারে)। সার্ভারের private টেবিলে
 *   salt-সহ হ্যাশ হয়ে থাকে, মেলানো হয় শুধু সার্ভারের RPC দিয়ে (supabase_family_link.sql দেখুন)।
 * - NID ↔ NID অথবা BID ↔ BID হলেই মিলবে। একজনের NID, অন্যজনের BID হলে মিলবে না → ম্যানুয়ালি বসাতে হবে।
 */
final class IdentityLink {
    private IdentityLink() { }

    interface LinkCb { void on(boolean matched, String name, String message); }

    /** ডকুমেন্টের লেখা দেখে ধরন: "BID" (জন্ম নিবন্ধন) নাকি "NID" */
    static String detectType(String ocrText) {
        String low = ocrText == null ? "" : ocrText.toLowerCase(Locale.US);
        return low.contains("birth registration") ? "BID" : "NID";
    }

    /** OCR থেকে ১০/১৩/১৭ ডিজিটের নম্বর; না পেলে null */
    static String extractNumber(String ocrText) {
        return NidTextParser.extractNidNumber(ocrText);
    }

    /** নিজের (ভেরিফাই করা) NID/BID নম্বর সার্ভারে সেভ। ব্যর্থ হলে চুপচাপ ফেরে, মূল ভেরিফিকেশন আটকায় না */
    static void saveMine(String type, String number) {
        if (number == null || number.isEmpty()) return;
        try {
            JSONObject body = new JSONObject().put("p_doc_type", type).put("p_doc_number", number);
            SupabaseRest.call("POST", "/rest/v1/rpc/save_identity", body.toString(), null, true, r -> { });
        } catch (Exception ignored) { }
    }

    /** স্পাউসের ডকুমেন্টের নম্বর মিললে সার্ভার দু'জনের Relationship-এ Married বসায় */
    static void linkSpouse(String type, String number, LinkCb cb) {
        try {
            JSONObject body = new JSONObject().put("p_doc_type", type).put("p_doc_number", number);
            SupabaseRest.call("POST", "/rest/v1/rpc/link_spouse", body.toString(), null, true, r -> {
                if (!r.ok()) { cb.on(false, "", SupabaseRest.errorText(r)); return; }
                try {
                    JSONObject o = new JSONObject(r.body);
                    cb.on(o.optBoolean("matched", false), o.optString("name", ""), o.optString("reason", ""));
                } catch (Exception e) { cb.on(false, "", "Bad response"); }
            });
        } catch (Exception e) { cb.on(false, "", "Request failed"); }
    }
}
