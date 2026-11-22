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
    private static RingProgressView pendingRing;

    /** "Create memory" চাপলে: গ্যালারি-পিকার পর্দা (ActivityMemoryOpen) খোলে */
    static void openCreate(Activity a) {
        if (MemoryUploader.busy) {
            android.widget.Toast.makeText(a, "Memory is uploading", android.widget.Toast.LENGTH_SHORT).show();
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
        syncPending(a, row);   // নেটের উত্তরের আগেই "Sharing…" কার্ড দেখাই
        ensureDemo(a, row);    // আসল Memory না থাকলে (নেট নেই/ফাঁকা) ডেমো কার্ড — সারি ফাঁকা দেখাবে না
        String path = "/rest/v1/memories?expires_at=gt." + MemoryViewerActivity.isoNow()
                + "&select=id,user_id,thumb_key,created_at&order=created_at.desc&limit=300";
        SupabaseRest.call("GET", path, null, null, true, r -> {
            if (g != gen || a.isFinishing()) return;
            if (!r.ok()) { ensureDemo(a, row); return; }   // নেট/সার্ভার সমস্যা: ডেমো কার্ডই থাকবে
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
        // প্রথম কার্ড (Create memory) রেখে বাকি সব মুছি; আপলোড চললে "Sharing…" কার্ড তার পরেই
        while (row.getChildCount() > 1) row.removeViewAt(1);
        syncPending(a, row);
        final ArrayList<String> uids = new ArrayList<>();
        for (Entry e : list) uids.add(e.uid);
        for (int i = 0; i < list.size(); i++) {
            final int pos = i;
            View card = card(a, list.get(i), me);
            card.setTag(REAL_TAG);
            card.setOnClickListener(v -> MemoryViewerActivity.open(a, uids, pos));
            row.addView(card);
        }
        ensureDemo(a, row);   // কারো Memory না থাকলে ডেমো কার্ড
    }

    private static final String REAL_TAG = "memory_real";
    private static final String DEMO_TAG = "memory_demo";
    private static final int DEMO_COUNT = 2;

    /** আসল Memory কার্ড বা ডেমো কার্ড কোনোটাই না থাকলে (নেটওয়ার্ক নেই / কোনো Memory নেই) ডেমো কার্ড বসায় */
    private static void ensureDemo(Activity a, LinearLayout row) {
        for (int i = 0; i < row.getChildCount(); i++) {
            Object t = row.getChildAt(i).getTag();
            if (REAL_TAG.equals(t) || DEMO_TAG.equals(t)) return;
        }
        for (int i = 0; i < DEMO_COUNT; i++) row.addView(demoCard(a));
    }

    /** ডেমো কার্ড: ডার্ক/লাইট রঙের ফাঁকা Memory কার্ড (ছবির জায়গা + ছোট অ্যাভাটার + নামের বার) */
    private static View demoCard(Activity a) {
        FrameLayout f = new FrameLayout(a);
        f.setTag(DEMO_TAG);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(a, 104), dp(a, 175));
        lp.rightMargin = dp(a, 8);
        f.setLayoutParams(lp);
        f.setBackgroundResource(R.drawable.bg_mem_card_round);
        f.setClipToOutline(true);

        ImageView photo = new ImageView(a);
        photo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        photo.setImageResource(R.drawable.bg_mem_demo);
        f.addView(photo, new FrameLayout.LayoutParams(-1, -1));

        int bar = androidx.core.content.ContextCompat.getColor(a, R.color.h_demo_bar);
        View av = new View(a);
        GradientDrawable avBg = new GradientDrawable();
        avBg.setShape(GradientDrawable.OVAL);
        avBg.setColor(bar);
        avBg.setStroke(dp(a, 2), androidx.core.content.ContextCompat.getColor(a, R.color.h_ring));
        av.setBackground(avBg);
        FrameLayout.LayoutParams alp = new FrameLayout.LayoutParams(dp(a, 32), dp(a, 32), Gravity.TOP | Gravity.START);
        alp.setMargins(dp(a, 6), dp(a, 6), 0, 0);
        f.addView(av, alp);

        View name = new View(a);
        GradientDrawable nBg = new GradientDrawable();
        nBg.setColor(bar);
        nBg.setCornerRadius(dp(a, 5));
        name.setBackground(nBg);
        FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(dp(a, 62), dp(a, 10), Gravity.BOTTOM | Gravity.START);
        nlp.setMargins(dp(a, 8), 0, 0, dp(a, 12));
        f.addView(name, nlp);
        return f;
    }

    private static final String PENDING_TAG = "memory_pending";

    /** আপলোড চললে Create-কার্ডের পরে "Sharing…" কার্ড বসায়/রাখে; শেষ হলে সরায় */
    static void syncPending(Activity a, LinearLayout row) {
        View old = row.findViewWithTag(PENDING_TAG);
        if (!MemoryUploader.busy) {
            if (old != null) row.removeView(old);
            pendingRing = null;
            return;
        }
        if (old != null) {
            if (pendingRing != null) pendingRing.setProgress(MemoryUploader.progress);
            return;
        }
        View c = pendingCard(a);
        c.setTag(PENDING_TAG);
        row.addView(c, Math.min(1, row.getChildCount()));
    }

    /** Home চালু থাকা অবস্থায় আপলোডের শুরু/শেষ/অগ্রগতি শোনে */
    static void bind(final Activity a, final LinearLayout row) {
        MemoryUploader.setListener(new MemoryUploader.Listener() {
            @Override public void onState() {
                if (a.isFinishing()) return;
                if (MemoryUploader.busy) syncPending(a, row);
                else load(a, row);          // শেষ: কার্ড সরে আসল Memory বসে
            }
            @Override public void onProgress(int percent) {
                if (pendingRing != null) pendingRing.setProgress(percent);
            }
        });
    }

    static void unbind() { MemoryUploader.setListener(null); pendingRing = null; }

    private static View pendingCard(Activity a) {
        FrameLayout f = new FrameLayout(a);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(a, 104), dp(a, 175));
        lp.rightMargin = dp(a, 8);
        f.setLayoutParams(lp);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFF0A5AF);
        bg.setCornerRadius(dp(a, 12));
        f.setBackground(bg);
        f.setClipToOutline(true);

        // উপরে বামে অ্যাভাটার + তার ঘিরে নীল প্রোগ্রেস রিং
        FrameLayout box = new FrameLayout(a);
        ImageView av = new ImageView(a);
        int pad = dp(a, 2);
        av.setPadding(pad, pad, pad, pad);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(Color.WHITE);
        av.setBackground(ring);
        String me = SupabaseRest.uid();
        if (me != null) AvatarLoader.load(av, me);
        FrameLayout.LayoutParams alp = new FrameLayout.LayoutParams(dp(a, 30), dp(a, 30), Gravity.CENTER);
        box.addView(av, alp);
        RingProgressView rp = new RingProgressView(a);
        rp.setShowText(false);
        rp.setSizing(2.5f, 8f);
        rp.setProgress(MemoryUploader.progress);
        box.addView(rp, new FrameLayout.LayoutParams(-1, -1));
        pendingRing = rp;
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(dp(a, 38), dp(a, 38), Gravity.TOP | Gravity.START);
        blp.setMargins(dp(a, 4), dp(a, 4), 0, 0);
        f.addView(box, blp);

        TextView t = new TextView(a);
        t.setText("Sharing…");
        t.setTextColor(Color.WHITE);
        t.setTextSize(12);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setSingleLine(true);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        tlp.setMargins(dp(a, 6), 0, dp(a, 6), dp(a, 8));
        f.addView(t, tlp);
        return f;
    }

    private static int dp(Activity a, int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, a.getResources().getDisplayMetrics());
    }

    private static View card(Activity a, Entry e, String me) {
        FrameLayout f = new FrameLayout(a);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(a, 104), dp(a, 175));
        lp.rightMargin = dp(a, 8);
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
