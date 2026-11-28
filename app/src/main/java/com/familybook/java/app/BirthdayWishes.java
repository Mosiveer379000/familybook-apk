package com.familybook.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * জন্মদিনের শুভেচ্ছা (Supabase birthday_wishes, sql/12_birthday_wishes.sql)।
 *
 *  - টাইমলাইনে লেখা: শুধু মালিকের ফলোয়ার, বছরে একজনকে একবার, এবং মালিকের "Wishes from followers" চালু থাকলে।
 *    বন্ধ থাকলে সার্ভার আটকায়; তখন Birthday reminder পেজে বক্সের বদলে "Message" বাটন (Family Chat) দেখায়।
 *  - প্রোফাইলে শুভেচ্ছা জন্মদিনের পর VISIBLE_DAYS দিন দেখায়।
 */
final class BirthdayWishes {

    static final int VISIBLE_DAYS = 7;

    static final class Wish {
        String id, senderUid, senderName = "", message, kind;
        long at;
    }

    interface Sent { void on(boolean ok, String mode, String error); }
    interface Listed { void on(List<Wish> wishes); }

    private BirthdayWishes() { }

    static final String ERR_ALREADY = "ALREADY";

    static void send(final String receiverUid, String text, final Sent cb) {
        final String me = SupabaseRest.uid();
        text = text == null ? "" : text.trim();
        if (me == null || receiverUid == null || text.isEmpty()) { cb.on(false, null, null); return; }
        if (text.length() > 500) text = text.substring(0, 500);
        try {
            JSONObject row = new JSONObject()
                    .put("receiver_id", receiverUid)
                    .put("sender_id", me)
                    .put("wish_year", Calendar.getInstance().get(Calendar.YEAR))
                    .put("kind", "wish")                 // সার্ভারের trigger আসল kind বসায়
                    .put("message", text);
            SupabaseRest.call("POST", "/rest/v1/birthday_wishes", row.toString(),
                    "return=representation", true, r -> {
                if (!r.ok()) {
                    String b = r.body == null ? "" : r.body;
                    boolean dup = r.code == 409 || b.contains("23505") || b.contains("duplicate");
                    cb.on(false, null, dup ? ERR_ALREADY : SupabaseRest.errorText(r));
                    return;
                }
                String mode = "wish";
                try {
                    JSONArray a = new JSONArray(r.body);
                    if (a.length() > 0) mode = a.getJSONObject(0).optString("kind", "wish");
                } catch (Exception ignored) { }
                BirthdayToday.markWished(receiverUid);
                // "Message from followers" মোডে (kind = message) নোটিফিকেশন যাবে না
                if ("wish".equals(mode)) NotificationHelper.send(receiverUid, "birthday_wish", null);
                cb.on(true, mode, null);
            });
        } catch (Exception e) {
            cb.on(false, null, null);
        }
    }

    /**
     * কারো প্রোফাইলের শুভেচ্ছা (নতুন আগে)। মালিক নিজে হলে প্রাইভেট মেসেজও আসে (RLS-ই আটকায়)।
     */
    static void forProfile(String ownerUid, boolean isMe, final Listed cb) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        String since = f.format(new java.util.Date(
                System.currentTimeMillis() - VISIBLE_DAYS * 24L * 3600 * 1000));
        String path = "/rest/v1/birthday_wishes?receiver_id=eq." + ownerUid
                + (isMe ? "" : "&kind=eq.wish")
                + "&created_at=gte." + since
                + "&order=created_at.desc&limit=300&select=id,sender_id,message,kind,created_at";
        SupabaseRest.call("GET", path, null, null, true, r -> {
            final List<Wish> out = new ArrayList<>();
            if (r.ok()) {
                try {
                    JSONArray a = new JSONArray(r.body);
                    for (int i = 0; i < a.length(); i++) {
                        JSONObject o = a.optJSONObject(i);
                        if (o == null) continue;
                        Wish w = new Wish();
                        w.id = o.optString("id", null);
                        w.senderUid = o.optString("sender_id", null);
                        w.message = o.optString("message", "");
                        w.kind = o.optString("kind", "wish");
                        w.at = NotificationItem.parseIso(o.optString("created_at", ""));
                        out.add(w);
                    }
                } catch (Exception ignored) { }
            }
            if (out.isEmpty()) { cb.on(out); return; }
            fillNames(out, cb);
        });
    }

    private static void fillNames(final List<Wish> list, final Listed cb) {
        StringBuilder in = new StringBuilder();
        for (Wish w : list) {
            if (w.senderUid == null || in.indexOf(w.senderUid) >= 0) continue;
            if (in.length() > 0) in.append(',');
            in.append(w.senderUid);
        }
        SupabaseRest.call("GET", "/rest/v1/profiles?id=in.(" + in + ")&select=id,name",
                null, null, true, r -> {
            Map<String, String> names = new HashMap<>();
            if (r.ok()) {
                try {
                    JSONArray a = new JSONArray(r.body);
                    for (int i = 0; i < a.length(); i++) {
                        JSONObject o = a.optJSONObject(i);
                        if (o != null && !o.isNull("name")) names.put(o.optString("id"), o.optString("name"));
                    }
                } catch (Exception ignored) { }
            }
            for (Wish w : list) { String n = names.get(w.senderUid); if (n != null) w.senderName = n; }
            cb.on(list);
        });
    }
}
