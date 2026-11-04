package com.familybook.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Home-এর Memory সারি: "Create memory" কার্ডের পাশে সক্রিয় (২৪ ঘণ্টার মধ্যে) Memory-ওয়ালা মানুষদের কার্ড।
 * না-দেখা Memory থাকলে নীল রিং, দেখা হয়ে গেলে ধূসর। নিজেরটা সবার আগে।
 */
final class MemoryRow {
    private MemoryRow() { }

    private static int gen = 0;

    /** "Create memory" চাপলে: গ্যালারি-পিকার পর্দা (ActivityMemoryOpen) খোলে */
    static void openCreate(Activity a) {
        if (MemoryUploader.busy) {
            android.widget.Toast.makeText(a, "Previous Memory is still uploading", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        a.startActivity(new Intent(a, ActivityMemoryOpen.class));
    }

    private static final class Entry {
        String uid, thumbKey;
        boolean unseen;
    }

    static void load(final Activity a, final LinearLayout row) {
        final String me = SupabaseRest.uid();
        if (me == null) return;
        final int g = ++gen;
        String path = "/rest/v1/memories?expires_at=gt." + MemoryViewerActivity.isoNow()
                + "&select=id,user_id,thumb_key,created_at&order=created_at.desc&limit=300";
        SupabaseRest.call("GET", path, null, null, true, r -> {
            if (g != gen || a.isFinishing() || !r.ok()) return;
            LinkedHashMap<String, Entry> map = new LinkedHashMap<>();
            try {
                JSONArray arr = new JSONArray(r.body);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    String uid = o.optString("user_id");
                    Entry e = map.get(uid);
                    if (e == null) {
                        e = new Entry();
                        e.uid = uid;
                        e.thumbKey = o.optString("thumb_key", "");
                        map.put(uid, e);
                    }
                    if (!MemoryViewerActivity.isSeen(a, o.optString("id"))) e.unseen = true;
                }
            } catch (Exception ignored) { }

            // ক্রম: নিজের → না-দেখা → দেখা
            ArrayList<Entry> list = new ArrayList<>();
            Entry mine = map.remove(me);
            if (mine != null) list.add(mine);
            ArrayList<Entry> seen = new ArrayList<>();
            for (Map.Entry<String, Entry> en : map.entrySet()) {
                if (en.getValue().unseen) list.add(en.getValue()); else seen.add(en.getValue());
            }
            list.addAll(seen);
            render(a, row, list, me);
        });
    }

    private static void render(final Activity a, LinearLayout row, final ArrayList<Entry> list, String me) {
        // প্রথম কার্ড (Create memory) রেখে বাকি সব মুছি
        while (row.getChildCount() > 1) row.removeViewAt(1);
        final ArrayList<String> uids = new ArrayList<>();
        for (Entry e : list) uids.add(e.uid);
        for (int i = 0; i < list.size(); i++) {
            final int pos = i;
            View card = card(a, list.get(i), me);
            card.setOnClickListener(v -> MemoryViewerActivity.open(a, uids, pos));
            row.addView(card);
        }
    }

    private static int dp(Activity a, int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, a.getResources().getDisplayMetrics());
    }

    private static View card(Activity a, Entry e, String me) {
        FrameLayout f = new FrameLayout(a);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(a, 80), dp(a, 130));
        lp.rightMargin = dp(a, 6);
        f.setLayoutParams(lp);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF2B2D31);
        bg.setCornerRadius(dp(a, 12));
        f.setBackground(bg);
        f.setClipToOutline(true);
        f.setClickable(true);
        f.setFocusable(true);

        final ImageView thumb = new ImageView(a);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        f.addView(thumb, new FrameLayout.LayoutParams(-1, -1));
        if (e.thumbKey != null && !e.thumbKey.isEmpty()) {
            Media.loadBitmap(e.thumbKey, bm -> { if (bm != null) thumb.setImageBitmap(bm); });
        }

        // নিচে নামের গ্র্যাডিয়েন্ট
        View shade = new View(a);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xCC000000, 0x00000000}));
        f.addView(shade, new FrameLayout.LayoutParams(-1, dp(a, 48), Gravity.BOTTOM));

        // উপরে বামে অ্যাভাটার + রিং
        ImageView av = new ImageView(a);
        int pad = dp(a, 2);
        av.setPadding(pad, pad, pad, pad);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(Color.WHITE);
        ring.setStroke(dp(a, 2), e.unseen ? 0xFF0866FF : 0xFF9A9DA2);
        av.setBackground(ring);
        AvatarLoader.load(av, e.uid);
        FrameLayout.LayoutParams alp = new FrameLayout.LayoutParams(dp(a, 32), dp(a, 32), Gravity.TOP | Gravity.START);
        alp.setMargins(dp(a, 6), dp(a, 6), 0, 0);
        f.addView(av, alp);

        final TextView name = new TextView(a);
        name.setTextColor(Color.WHITE);
        name.setTextSize(12);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        nlp.setMargins(dp(a, 6), 0, dp(a, 6), dp(a, 8));
        f.addView(name, nlp);
        if (e.uid.equals(me)) name.setText("Your Memory");
        else UserNames.fetch(e.uid, n -> { if (n != null) name.setText(n); });
        return f;
    }
}
