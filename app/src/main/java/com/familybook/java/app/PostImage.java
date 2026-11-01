package com.familybook.app;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;


import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * পোস্টের ছবি। PostMusic-এর মতোই: ফিডের পোস্টগুলোর id দিয়ে একসাথে RPC get_post_images ডেকে
 * ছবির R2 key এনে ক্যাশে রাখে (post_cards ভিউ বদলাতে হয় না)।
 */
public class PostImage {

    private static final String NONE = "";
    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, String[]> CAPS = new ConcurrentHashMap<>();
    private static final Set<String> INFLIGHT = new HashSet<>();
    private static final int CHUNK = 60;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static final LruCache<String, Bitmap> BITMAPS = new LruCache<String, Bitmap>(
            (int) (Runtime.getRuntime().maxMemory() / 1024 / 8)) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount() / 1024; }
    };

    private static java.io.File store;
    private static final java.util.concurrent.ExecutorService DISK = java.util.concurrent.Executors.newSingleThreadExecutor();

    /** FamilyBookApp.onCreate-এ একবার: আগের (post -> ছবির key) তালিকা ফিরিয়ে আনে, তাই অফলাইনেও ছবি চেনা যায় */
    public static void init(Context app) {
        store = new java.io.File(app.getApplicationContext().getFilesDir(), "postimg_cache.json");
        if (!store.exists()) return;
        try (java.io.FileInputStream in = new java.io.FileInputStream(store)) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            JSONObject root = new JSONObject(bo.toString("UTF-8"));
            JSONObject k = root.optJSONObject("k");
            JSONObject c = root.optJSONObject("c");
            if (k != null) {
                java.util.Iterator<String> it = k.keys();
                while (it.hasNext()) { String id = it.next(); CACHE.put(id, k.optString(id, NONE)); }
            }
            if (c != null) {
                java.util.Iterator<String> it = c.keys();
                while (it.hasNext()) {
                    String id = it.next();
                    JSONArray ca = c.optJSONArray(id);
                    if (ca == null) continue;
                    String[] arr = new String[ca.length()];
                    for (int i = 0; i < arr.length; i++) arr[i] = ca.optString(i, "");
                    CAPS.put(id, arr);
                }
            }
        } catch (Exception ignored) { }
    }

    private static void saveAsync() {
        if (store == null) return;
        final JSONObject k = new JSONObject();
        final JSONObject c = new JSONObject();
        try {
            int n = 0;
            for (Map.Entry<String, String> e : CACHE.entrySet()) {
                if (n++ >= 1500) break;
                k.put(e.getKey(), e.getValue());
            }
            for (Map.Entry<String, String[]> e : CAPS.entrySet()) {
                if (!k.has(e.getKey())) continue;
                JSONArray a = new JSONArray();
                for (String s : e.getValue()) a.put(s == null ? "" : s);
                c.put(e.getKey(), a);
            }
        } catch (Exception e) { return; }
        DISK.execute(() -> {
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(store)) {
                o.write(new JSONObject().put("k", k).put("c", c).toString().getBytes("UTF-8"));
            } catch (Exception ignored) { }
        });
    }

    /** এই পোস্টের ছবির খবর (থাকা/না-থাকা) আনা হয়ে গেছে কি */
    public static boolean known(String postId) {
        return postId != null && CACHE.containsKey(postId);
    }

    public static String get(String postId) {
        String u = postId == null ? null : CACHE.get(postId);
        return (u == null || u.isEmpty()) ? null : u;
    }

    /** নতুন পোস্টের ছবি নিজের ফোনে সাথে সাথে দেখাতে */
    public static void put(String postId, String url) {
        put(postId, url, null);
    }

    public static void put(String postId, String url, String[] captions) {
        if (postId == null || url == null) return;
        CACHE.put(postId, url);
        if (captions != null) CAPS.put(postId, captions);
    }

    /** পোস্ট এডিটের পর ক্যাশ বদলানো; val == null হলে পোস্টে আর কোনো ছবি নেই */
    public static void set(String postId, String val, String[] captions) {
        if (postId == null) return;
        CACHE.put(postId, val == null ? NONE : val);
        if (captions != null) CAPS.put(postId, captions); else CAPS.remove(postId);
    }

    /** ক্যাশের মূল মান ("#B|" সহ) — এডিটর ভ্যারিয়েন্ট বুঝতে ব্যবহার করে */
    public static String raw(String postId) {
        return get(postId);
    }

    /** ছবিগুলোর ক্যাপশন (ছবির ক্রমে); না থাকলে null */
    public static String[] captions(String postId) {
        return postId == null ? null : CAPS.get(postId);
    }

    public static void prefetch(List<Post> list, final Runnable onReady) {
        final List<String> need = new ArrayList<>();
        synchronized (INFLIGHT) {
            for (Post p : list) {
                if (p.id != null && !CACHE.containsKey(p.id) && INFLIGHT.add(p.id)) need.add(p.id);
            }
        }
        for (int from = 0; from < need.size(); from += CHUNK) {
            final List<String> ids = new ArrayList<>(need.subList(from, Math.min(need.size(), from + CHUNK)));
            try {
                JSONArray arr = new JSONArray();
                for (String id : ids) arr.put(id);
                String body = new JSONObject().put("p_ids", arr).toString();
                SupabaseRest.call("POST", "/rest/v1/rpc/get_post_images", body, null, true, r -> {
                    synchronized (INFLIGHT) { INFLIGHT.removeAll(ids); }
                    if (!r.ok()) return;      // ব্যর্থ হলে ক্যাশ করি না — পরের বার আবার চেষ্টা
                    Set<String> found = new HashSet<>();
                    try {
                        JSONArray rows = new JSONArray(r.body);
                        for (int i = 0; i < rows.length(); i++) {
                            JSONObject o = rows.getJSONObject(i);
                            String id = o.isNull("id") ? null : o.optString("id", null);
                            String url = o.isNull("image_key") ? null : o.optString("image_key", null);
                            if (id != null && url != null && !url.isEmpty()) {
                                CACHE.put(id, url);
                                found.add(id);
                                String cs = o.isNull("image_captions") ? null : o.optString("image_captions", null);
                                if (cs != null && !cs.isEmpty()) {
                                    try {
                                        JSONArray ca = new JSONArray(cs);
                                        String[] capArr = new String[ca.length()];
                                        for (int k = 0; k < capArr.length; k++) capArr[k] = ca.optString(k, "");
                                        CAPS.put(id, capArr);
                                    } catch (Exception ignored2) { }
                                }
                            }
                        }
                    } catch (Exception ignored) { }
                    for (String id : ids) if (!found.contains(id)) CACHE.put(id, NONE);
                    saveAsync();
                    if (onReady != null) onReady.run();
                });
            } catch (Exception e) {
                synchronized (INFLIGHT) { INFLIGHT.removeAll(ids); }
            }
        }
    }

    interface BmCb { void on(Bitmap bm); }

    /** key R2 key হলে Media দিয়ে, পুরনো http লিংক হলে সরাসরি; ব্যর্থ হলে bm = null */
    static void loadAny(final String key, final BmCb cb) {
        if (key.startsWith("http")) {
            new Thread(() -> {
                final Bitmap b = download(key, 1400);
                MAIN.post(() -> cb.on(b));
            }).start();
        } else {
            Media.loadBitmap(key, bm -> cb.on(bm), (v, e) -> cb.on(null));
        }
    }

    /**
     * কার্ডের ছবি-ভিউ সাজানো: ছবি না থাকলে GONE। একটা ছবি = পুরো; একাধিক = ফেসবুকের মতো গ্রিড।
     * ক্যাশের মান "key1|key2|..."।
     */
    public static void bind(final ImageView iv, final Post p) {
        final String val = get(p.id);
        iv.setTag(val);
        if (val == null) {
            iv.setImageDrawable(null);
            iv.setVisibility(ImageView.GONE);
            iv.setOnClickListener(null);
            iv.setOnTouchListener(null);
            return;
        }
        iv.setVisibility(ImageView.VISIBLE);
        final int variant = val.startsWith("#B|") ? 1 : 0;
        final String[] keys = (variant == 1 ? val.substring(3) : val).split("\\|");
        // GIF / প্রোফাইল ছবি / কভার ফটো: ১:১ নয়, নিজের সাইজে পুরোটা (বাকি ছবি-ভিডিও ফিডে ১:১)
        final boolean gif = keys.length == 1 && isGif(keys[0]);
        final boolean natural = keys.length == 1 && (gif || PostExtras.isProfilePhoto(p.id));
        if (iv instanceof SquareImageView) ((SquareImageView) iv).setNatural(natural);
        try { com.bumptech.glide.Glide.with(iv).clear(iv); } catch (Exception ignored) { }
        final float[] tp = {0f, 0f};
        iv.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                tp[0] = e.getX() / Math.max(1, v.getWidth());
                tp[1] = e.getY() / Math.max(1, v.getHeight());
            }
            return false;
        });
        iv.setOnClickListener(v -> {
            // ভিডিও (Short) হলে ছবির ভিউয়ার নয় — Shorts-এর ব্ল্যাক ইনসাইড ভিউ (লাইক/কমেন্ট/শেয়ার/সেভ/⋯ ডানে উপর থেকে নিচে)
            ShortStore.Info si = keys.length == 1 ? ShortStore.byThumb(keys[0]) : null;
            if (si != null && PostOps.isShortPost(p)) {
                ShortPlayerView.stopAll();
                java.util.ArrayList<String> ids = new java.util.ArrayList<>();
                ids.add(si.id);
                ShortViewerActivity.open(iv.getContext(), ids, 0);
                return;
            }
            showViewer(iv.getContext(), keys, captions(p.id),
                    keys.length == 1 ? 0 : PhotoGrid.hit(keys.length, variant, tp[0], tp[1]), p);
        });

        // অ্যানিমেটেড GIF: Glide দিয়ে (চলতে থাকে)
        if (gif) {
            iv.setImageDrawable(new ColorDrawable(0xFFE4E6EB));
            showGif(iv, keys[0], val);
            return;
        }
        Bitmap b = BITMAPS.get(val);
        if (b != null) {
            iv.setImageBitmap(b);
            return;
        }
        iv.setImageDrawable(new ColorDrawable(0xFFE4E6EB));
        if (keys.length == 1) {
            loadAny(keys[0], bm -> {
                if (bm == null) return;
                if (Media.isGifKey(keys[0]) && val.equals(iv.getTag())) {   // ফাইল আসলে GIF → অ্যানিমেটেড দেখাই
                    if (iv instanceof SquareImageView) ((SquareImageView) iv).setNatural(true);
                    iv.setImageBitmap(bm);
                    showGif(iv, keys[0], val);
                    return;
                }
                BITMAPS.put(val, bm);
                if (val.equals(iv.getTag())) iv.setImageBitmap(bm);
            });
            return;
        }
        // একাধিক: প্রথম ৫টি এনে গ্রিডে জোড়া
        final int shown = Math.min(keys.length, PhotoGrid.MAX_TILES);
        final Bitmap[] got = new Bitmap[shown];
        final int[] left = {0};
        for (int i = 0; i < shown; i++) {
            Bitmap c = BITMAPS.get(keys[i]);
            if (c != null) got[i] = c; else left[0]++;
        }
        final Runnable finish = () -> {
            Bitmap grid = PhotoGrid.compose(got, keys.length, variant);
            BITMAPS.put(val, grid);
            if (val.equals(iv.getTag())) iv.setImageBitmap(grid);
        };
        if (left[0] == 0) { finish.run(); return; }
        for (int i = 0; i < shown; i++) {
            if (got[i] != null) continue;
            final int idx = i;
            loadAny(keys[i], bm -> {
                got[idx] = bm;
                if (bm != null) BITMAPS.put(keys[idx], bm);
                if (--left[0] == 0) finish.run();
            });
        }
    }

    /** GIF-কে Glide দিয়ে iv-তে চালায় (অ্যানিমেটেড); val ট্যাগ মিললে তবেই বসে */
    static void showGif(final ImageView iv, final String key, final String tag) {
        if (key.startsWith("http")) {
            com.bumptech.glide.Glide.with(iv).load(key).into(iv);
            return;
        }
        Media.readUrl(key, (url, err) -> {
            if (url == null) { android.util.Log.w("PostImage", "gif url failed: " + err); return; }
            if (tag.equals(iv.getTag())) com.bumptech.glide.Glide.with(iv).load(url).into(iv);
        });
    }

    private static boolean isGif(String key) {
        if (Media.isGifKey(key)) return true;      // ফাইলের হেডার দেখে চেনা GIF
        if (key == null) return false;
        int q = key.indexOf('?');
        String k = (q > 0 ? key.substring(0, q) : key).toLowerCase(java.util.Locale.ROOT);
        return k.endsWith(".gif");
    }

    /** পোস্টের প্রথম ছবি iv-তে দেখায় (ক্যাশে থাকলে); না থাকলে iv GONE। From your groups কার্ডের জন্য */
    public static void bindFirst(final ImageView iv, final String postId) {
        final String val = get(postId);
        iv.setTag(val);
        if (val == null) {
            iv.setImageDrawable(null);
            iv.setVisibility(View.GONE);
            return;
        }
        final String[] ks = (val.startsWith("#B|") ? val.substring(3) : val).split("\\|");
        final String key = ks[0];
        iv.setVisibility(View.VISIBLE);
        // গ্রুপ কার্ড: GIF / প্রোফাইল ছবি / কভার ফটো নিজের সাইজে পুরোটা; বাকিরা আগের মতো ২২০dp ক্রপ
        final boolean natural = ks.length == 1 && (isGif(key) || PostExtras.isProfilePhoto(postId));
        try { com.bumptech.glide.Glide.with(iv).clear(iv); } catch (Exception ignored) { }
        ViewGroup.LayoutParams lp = iv.getLayoutParams();
        if (lp != null) {
            int want = natural ? ViewGroup.LayoutParams.WRAP_CONTENT
                    : Math.round(220 * iv.getResources().getDisplayMetrics().density);
            if (lp.height != want) { lp.height = want; iv.setLayoutParams(lp); }
        }
        iv.setAdjustViewBounds(natural);
        iv.setScaleType(natural ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_CROP);
        if (natural && isGif(key)) {
            iv.setImageDrawable(new ColorDrawable(0xFFE4E6EB));
            showGif(iv, key, val);
            return;
        }
        Bitmap b = BITMAPS.get(key);
        if (b != null) { iv.setImageBitmap(b); return; }
        iv.setImageDrawable(new ColorDrawable(0xFFE4E6EB));
        loadAny(key, bm -> {
            if (bm == null) return;
            if (ks.length == 1 && Media.isGifKey(key) && val.equals(iv.getTag())) {   // আসলে GIF → নিজের সাইজে অ্যানিমেটেড
                ViewGroup.LayoutParams l2 = iv.getLayoutParams();
                if (l2 != null) { l2.height = ViewGroup.LayoutParams.WRAP_CONTENT; iv.setLayoutParams(l2); }
                iv.setAdjustViewBounds(true);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                iv.setImageBitmap(bm);
                showGif(iv, key, val);
                return;
            }
            BITMAPS.put(key, bm);
            if (val.equals(iv.getTag())) iv.setImageBitmap(bm);
        });
    }

    private static Bitmap download(String url, int maxSide) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            try (InputStream in = c.getInputStream()) {
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                byte[] data = bo.toByteArray();
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, o);
                int s = 1;
                while (o.outWidth / s > maxSide * 2 || o.outHeight / s > maxSide * 2) s *= 2;
                BitmapFactory.Options d = new BitmapFactory.Options();
                d.inSampleSize = s;
                return BitmapFactory.decodeByteArray(data, 0, data.length, d);
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ---------- ছবির ফুল-স্ক্রিন ভিউয়ার ----------
    private static Dialog viewerDlg;

    /** খোলা ভিউয়ার (থাকলে) বন্ধ করে — পোস্ট মুছলে/আর্কাইভ করলে ইত্যাদিতে PostOps ডাকে */
    public static void dismissViewer() {
        Dialog d = viewerDlg;
        viewerDlg = null;
        try { if (d != null && d.isShowing()) d.dismiss(); } catch (Exception ignored) { }
    }

    private static android.app.Activity actOf(Context c) {
        while (c instanceof android.content.ContextWrapper) {
            if (c instanceof android.app.Activity) return (android.app.Activity) c;
            c = ((android.content.ContextWrapper) c).getBaseContext();
        }
        return null;
    }

    /** সাদা আইকন (drawable থাকলে) নইলে লেখা */
    private static View barIcon(Context ctx, String drawableName, String fallback, int sizeDp) {
        final float dn = ctx.getResources().getDisplayMetrics().density;
        int id = ctx.getResources().getIdentifier(drawableName, "drawable", ctx.getPackageName());
        int pad = Math.round(10 * dn);
        if (id != 0) {
            ImageView iv = new ImageView(ctx);
            iv.setImageResource(id);
            iv.setColorFilter(Color.WHITE);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setPadding(pad, pad, pad, pad);
            return iv;
        }
        TextView tv = new TextView(ctx);
        tv.setText(fallback);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(24);
        tv.setGravity(Gravity.CENTER);
        return tv;
    }

    /**
     * ছবিতে চাপলে: উপরে বাঁয়ে ✕, ডানে ⋮ (পোস্টের পুরো মেনু); মাঝে ছবিগুলো (স্ক্রল করা যায়, প্রতিটার নিচে ক্যাপশন);
     * নিচে স্থির: নাম + লেখা + Like / Comment / Share বাটন (সংখ্যাসহ)।
     */
    private static void showViewer(Context ctx, final String[] keys, final String[] caps, final int start,
                                   final Post p) {
        dismissViewer();
        final android.app.Activity act = actOf(ctx);
        final Dialog d = new Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        viewerDlg = d;
        final float dn = ctx.getResources().getDisplayMetrics().density;
        final String myUid = SupabaseRest.uid();
        final boolean[] dirty = {false};

        android.widget.LinearLayout root = new android.widget.LinearLayout(ctx);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setFitsSystemWindows(true);

        // ---- উপরের বার: ✕ ও ⋮ ----
        android.widget.LinearLayout top = new android.widget.LinearLayout(ctx);
        top.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(Math.round(6 * dn), Math.round(6 * dn), Math.round(6 * dn), Math.round(2 * dn));

        View close = barIcon(ctx, "ic_close", "\u2715", 24);
        close.setOnClickListener(v -> d.dismiss());
        top.addView(close, new android.widget.LinearLayout.LayoutParams(Math.round(48 * dn), Math.round(48 * dn)));
        top.addView(new View(ctx), new android.widget.LinearLayout.LayoutParams(0, 1, 1f));
        View dots = barIcon(ctx, "ic_more", "\u22EF", 24);
        dots.setOnClickListener(v -> {
            if (act == null) return;
            PostOps.showMenu(act, Me.uid(), p, v, act instanceof HomeActivity);
        });
        top.addView(dots, new android.widget.LinearLayout.LayoutParams(Math.round(48 * dn), Math.round(48 * dn)));
        root.addView(top, new android.widget.LinearLayout.LayoutParams(-1, -2));

        // ---- ছবিগুলো ----
        final ScrollView sv = new ScrollView(ctx);
        final android.widget.LinearLayout col = new android.widget.LinearLayout(ctx);
        col.setOrientation(android.widget.LinearLayout.VERTICAL);
        sv.addView(col, new ViewGroup.LayoutParams(-1, -2));
        root.addView(sv, new android.widget.LinearLayout.LayoutParams(-1, 0, 1f));

        for (int i = 0; i < keys.length; i++) {
            final String key = keys[i];
            final ImageView iv = new ImageView(ctx);
            iv.setAdjustViewBounds(true);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setTag(key);
            Bitmap b = BITMAPS.get(key);
            if (isGif(key)) {                                   // GIF: অ্যানিমেটেড চলবে
                iv.setMinimumHeight(Math.round(260 * dn));
                iv.setBackgroundColor(0xFF1C1C1C);
                showGif(iv, key, key);
            } else if (b != null) {
                iv.setImageBitmap(b);
            } else {
                iv.setMinimumHeight(Math.round(260 * dn));
                iv.setBackgroundColor(0xFF1C1C1C);
                loadAny(key, bm -> {
                    if (bm == null) return;
                    if (Media.isGifKey(key)) {                  // ফাইল আসলে GIF
                        iv.setImageBitmap(bm);
                        showGif(iv, key, key);
                        return;
                    }
                    BITMAPS.put(key, bm);
                    iv.setMinimumHeight(0);
                    iv.setBackgroundColor(Color.BLACK);
                    iv.setImageBitmap(bm);
                });
            }
            col.addView(iv, new android.widget.LinearLayout.LayoutParams(-1, -2));

            String cap = (caps != null && i < caps.length && caps[i] != null) ? caps[i].trim() : "";
            if (!cap.isEmpty()) {
                TextView tv = new TextView(ctx);
                tv.setText(cap);
                tv.setTextColor(Color.WHITE);
                tv.setTextSize(17);
                tv.setPadding(Math.round(16 * dn), Math.round(12 * dn), Math.round(16 * dn), Math.round(6 * dn));
                col.addView(tv, new android.widget.LinearLayout.LayoutParams(-1, -2));
            }
            View gap = new View(ctx);
            col.addView(gap, new android.widget.LinearLayout.LayoutParams(-1, Math.round(12 * dn)));
        }

        // ---- নিচে স্থির অংশ: নাম + লেখা ----
        android.widget.LinearLayout bottom = new android.widget.LinearLayout(ctx);
        bottom.setOrientation(android.widget.LinearLayout.VERTICAL);
        bottom.setBackgroundColor(Color.BLACK);
        bottom.setPadding(Math.round(16 * dn), Math.round(8 * dn), Math.round(16 * dn), 0);

        if (p.name != null && !p.name.isEmpty()) {
            TextView an = new TextView(ctx);
            an.setText(p.name);
            an.setTextColor(Color.WHITE);
            an.setTextSize(20);
            an.setTypeface(null, android.graphics.Typeface.BOLD);
            bottom.addView(an);
        }
        String body = p.text == null ? "" : p.text.trim();
        if (!body.isEmpty()) {
            TextView bt = new TextView(ctx);
            bt.setText(body);
            bt.setTextColor(0xFFE4E6EB);
            bt.setTextSize(17);
            bt.setMaxLines(4);
            bt.setEllipsize(android.text.TextUtils.TruncateAt.END);
            bt.setPadding(0, Math.round(8 * dn), 0, 0);
            bottom.addView(bt);
        }

        // ---- Like · Comment · Share ----
        View line = new View(ctx);
        line.setBackgroundColor(0xFF2A2A2A);
        android.widget.LinearLayout.LayoutParams lp1 = new android.widget.LinearLayout.LayoutParams(-1, Math.round(1 * dn));
        lp1.topMargin = Math.round(10 * dn);
        bottom.addView(line, lp1);

        android.widget.LinearLayout bar = new android.widget.LinearLayout(ctx);
        bar.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, Math.round(4 * dn), 0, Math.round(8 * dn));

        final ImageView likeIv = new ImageView(ctx);
        likeIv.setPadding(Math.round(6 * dn), Math.round(6 * dn), Math.round(6 * dn), Math.round(6 * dn));
        final TextView likeTv = new TextView(ctx);
        final TextView cmtTv = new TextView(ctx);
        likeTv.setTextColor(Color.WHITE); likeTv.setTextSize(18);
        cmtTv.setTextColor(Color.WHITE); cmtTv.setTextSize(18);

        final Runnable refresh = () -> {
            String r = p.likedByMe ? p.myReaction : null;
            if (r != null) {
                ReactionKit.setIcon(likeIv, r);
            } else {
                likeIv.setImageResource(R.drawable.ic_like);
                likeIv.setColorFilter(Color.WHITE);
            }
            likeTv.setText(String.valueOf(p.likes));
            cmtTv.setText(String.valueOf(p.comments));
        };
        refresh.run();

        likeIv.setOnClickListener(v -> {
            if (act == null || myUid == null || !Net.check(act)) return;
            if (p.likedByMe) {
                ReactionKit.removeReaction(act, p.id, myUid);
                p.likedByMe = false;
                p.myReaction = null;
                p.likes = Math.max(0, p.likes - 1);
            } else {
                ReactionKit.setReaction(act, p.id, myUid, "like");
                Sfx.like();
                NotificationHelper.send(p.uid, "like", p.id);
                p.likedByMe = true;
                p.myReaction = "like";
                p.likes = p.likes + 1;
            }
            dirty[0] = true;
            refresh.run();
        });
        if (act != null && myUid != null && p.id != null) {
            // চেপে ধরলে রিঅ্যাকশন বার (Love, Check, Stop, Angry, Haha)
            ReactionKit.attach(act, likeIv, p.id, p.uid, myUid, p.likedByMe, key -> {
                if (!p.likedByMe) p.likes = p.likes + 1;
                p.likedByMe = true;
                p.myReaction = key;
                dirty[0] = true;
                refresh.run();
            });
        }
        bar.addView(likeIv, new android.widget.LinearLayout.LayoutParams(Math.round(38 * dn), Math.round(38 * dn)));
        bar.addView(likeTv);

        View cmtIv = barIcon(ctx, "ic_comment", "\uD83D\uDCAC", 22);
        cmtIv.setOnClickListener(v -> {
            if (act == null) return;
            act.startActivity(new android.content.Intent(act, CommentsActivity.class).putExtra("postId", p.id));
        });
        android.widget.LinearLayout.LayoutParams cp = new android.widget.LinearLayout.LayoutParams(Math.round(38 * dn), Math.round(38 * dn));
        cp.leftMargin = Math.round(28 * dn);
        bar.addView(cmtIv, cp);
        bar.addView(cmtTv);

        View shIv = barIcon(ctx, "ic_share", "\u21AA", 22);
        android.widget.LinearLayout.LayoutParams sp = new android.widget.LinearLayout.LayoutParams(Math.round(38 * dn), Math.round(38 * dn));
        sp.leftMargin = Math.round(28 * dn);
        bar.addView(shIv, sp);
        final TextView shTv = new TextView(ctx);
        shTv.setText("Share");
        shTv.setTextColor(Color.WHITE);
        shTv.setTextSize(18);
        bar.addView(shTv);
        View.OnClickListener doShare = v -> { if (act != null) PostOps.share(act, p); };
        shIv.setOnClickListener(doShare);
        shTv.setOnClickListener(doShare);

        bottom.addView(bar, new android.widget.LinearLayout.LayoutParams(-1, -2));
        root.addView(bottom, new android.widget.LinearLayout.LayoutParams(-1, -2));

        d.setContentView(root, new ViewGroup.LayoutParams(-1, -1));
        // লাইক বদলালে ডায়ালগ বন্ধ হলে নিচের ফিডেও সংখ্যা/আইকন ঠিক হবে
        d.setOnDismissListener(x -> {
            if (viewerDlg == d) viewerDlg = null;
            if (dirty[0] && act != null && !act.isFinishing()) PostOps.refreshHost(act);
        });
        d.show();
        // যে ছবিতে চাপা হয়েছে সেখানে নামানো
        sv.post(() -> {
            int seen = -1;
            for (int i = 0; i < col.getChildCount(); i++) {
                if (col.getChildAt(i) instanceof ImageView) {
                    seen++;
                    if (seen == start) { sv.scrollTo(0, col.getChildAt(i).getTop()); break; }
                }
            }
        });
    }
}
