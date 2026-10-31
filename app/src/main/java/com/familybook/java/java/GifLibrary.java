package com.familybook.app;

import android.app.Activity;
import android.app.Dialog;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * "GIF Animated" (Say good / Silence! পোস্ট স্ক্রিন): আপলোড হওয়া GIF-এর লাইব্রেরি।
 *  - সবাই আপলোড হওয়া .gif ফাইলগুলো দেখে বেছে নিতে পারে (পোস্টে ছবি হিসেবে যোগ হয়)।
 *  - শুধু ADMIN_EMAIL ইউজারের কাছে "Upload" বাটন থাকে; বাকিরা বাটন পায় না।
 *  - GIF ফাইলের সর্বোচ্চ সাইজ 1MB (বেশি হলে আপলোড হয় না)।
 * ডেটা: Supabase টেবিল gif_library (supabase_gif_library.sql), ফাইল: R2 (Media)।
 */
final class GifLibrary {

    interface Host {
        void pickFile();          // সিস্টেমের ফাইল-পিকার (image/gif) খোলে; ফলাফল onFilePicked()-এ দিন
        void onPick(Uri gif);     // বাছা GIF-এর লোকাল Uri
        /** true হলে ডাউনলোড ছাড়াই শুধু key (R2 key বা GIPHY লিংক) দেয় — কমেন্টের জন্য */
        default boolean keyOnly() { return false; }
        default void onPickKey(String key) { }
    }

    static final String ADMIN_EMAIL = "masudd16@gmail.com";
    static final int MAX_BYTES = 1024 * 1024;   // 1MB

    private static final class Item { String key, name; }   // GIPHY হলে key = সরাসরি http লিংক

    private boolean giphyTab = false;          // false = FamilyBook ফোল্ডার, true = GIPHY ফোল্ডার
    private TextView tabFb, tabGiphy;
    private int loadToken = 0;
    private static final okhttp3.OkHttpClient HTTP = new okhttp3.OkHttpClient();

    private final Activity a;
    private final Host host;
    private final List<Item> items = new ArrayList<>();
    private final float d;
    private Dialog dlg;
    private TextView status, uploadBtn;
    private RecyclerView rv;
    private boolean uploading = false;

    GifLibrary(Activity a, Host host) {
        this.a = a;
        this.host = host;
        this.d = a.getResources().getDisplayMetrics().density;
    }

    /** শুধু এই ইমেইলের ইউজার আপলোড করতে পারে */
    static boolean canUpload() {
        String e = Me.email();
        return e != null && ADMIN_EMAIL.equalsIgnoreCase(e.trim());
    }

    private int dp(float v) { return Math.round(v * d); }

    // ------------------------------------------------------------ UI

    void show() {
        if (a.isFinishing()) return;
        if (dlg != null && dlg.isShowing()) return;
        dlg = new Dialog(a, android.R.style.Theme_Black_NoTitleBar);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        FrameLayout head = new FrameLayout(a);
        TextView close = new TextView(a);
        close.setText("✕");
        close.setTextColor(0xFF111111);
        close.setTextSize(20);
        close.setGravity(Gravity.CENTER);
        close.setOnClickListener(v -> dlg.dismiss());
        head.addView(close, new FrameLayout.LayoutParams(dp(52), dp(52), Gravity.START | Gravity.CENTER_VERTICAL));
        TextView title = new TextView(a);
        title.setText("GIF Animated");
        title.setTextColor(0xFF111111);
        title.setTextSize(18);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        head.addView(title, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        uploadBtn = new TextView(a);
        uploadBtn.setText("Upload");
        uploadBtn.setTextColor(Color.WHITE);
        uploadBtn.setTextSize(14);
        uploadBtn.setTypeface(null, android.graphics.Typeface.BOLD);
        uploadBtn.setGravity(Gravity.CENTER);
        uploadBtn.setPadding(dp(14), 0, dp(14), 0);
        GradientDrawable ug = new GradientDrawable();
        ug.setColor(0xFF0A6BCF);
        ug.setCornerRadius(dp(16));
        uploadBtn.setBackground(ug);
        uploadBtn.setOnClickListener(v -> {
            if (uploading) return;
            if (!canUpload()) return;      // সুরক্ষা: অন্য কেউ কোনোভাবে ক্লিক করলেও কাজ করবে না
            host.pickFile();
        });
        FrameLayout.LayoutParams ulp = new FrameLayout.LayoutParams(-2, dp(32), Gravity.END | Gravity.CENTER_VERTICAL);
        ulp.setMarginEnd(dp(12));
        head.addView(uploadBtn, ulp);
        uploadBtn.setVisibility(canUpload() ? View.VISIBLE : View.GONE);   // বাকিরা Upload বাটন পাবে না (styleTabs()-এও ঠিক হয়)
        root.addView(head, new LinearLayout.LayoutParams(-1, dp(52)));

        LinearLayout tabs = new LinearLayout(a);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setGravity(Gravity.CENTER);
        tabFb = tab("FamilyBook", false);
        tabGiphy = tab("GIPHY", true);
        tabs.addView(tabFb, new LinearLayout.LayoutParams(dp(110), dp(34)));
        tabs.addView(tabGiphy, new LinearLayout.LayoutParams(dp(110), dp(34)));
        root.addView(tabs, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout body = new FrameLayout(a);
        rv = new RecyclerView(a);
        rv.setLayoutManager(new GridLayoutManager(a, 3));
        rv.setAdapter(new Adapter());
        rv.setPadding(dp(4), dp(4), dp(4), dp(4));
        rv.setClipToPadding(false);
        body.addView(rv, new FrameLayout.LayoutParams(-1, -1));
        status = new TextView(a);
        status.setTextColor(0xFF777777);
        status.setTextSize(15);
        status.setGravity(Gravity.CENTER);
        body.addView(status, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1f));

        dlg.setContentView(root);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.WHITE));
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
        }
        dlg.show();
        load();
    }

    private TextView tab(String label, final boolean giphy) {
        TextView t = new TextView(a);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(14);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setOnClickListener(v -> { giphyTab = giphy; load(); });
        return t;
    }

    private void styleTabs() {
        for (int i = 0; i < 2; i++) {
            TextView t = i == 0 ? tabFb : tabGiphy;
            boolean on = (i == 1) == giphyTab;
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(8));
            g.setColor(on ? 0xFF0A6BCF : 0xFFE4E6EB);
            t.setBackground(g);
            t.setTextColor(on ? Color.WHITE : 0xFF333333);
        }
        // Upload শুধু FamilyBook ফোল্ডারে ও শুধু অ্যাডমিনের জন্য
        if (uploadBtn != null) uploadBtn.setVisibility(canUpload() && !giphyTab ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------ তালিকা

    private void load() {
        styleTabs();
        items.clear();
        rv.getAdapter().notifyDataSetChanged();
        status.setText("Loading…");
        status.setVisibility(View.VISIBLE);
        if (giphyTab) { loadGiphy(); return; }
        SupabaseRest.call("GET", "/rest/v1/gif_library?select=key,name&order=created_at.desc&limit=200",
                null, null, true, r -> {
                    if (a.isFinishing() || dlg == null || !dlg.isShowing() || giphyTab) return;
                    items.clear();
                    if (r.ok()) {
                        try {
                            JSONArray arr = new JSONArray(r.body);
                            for (int i = 0; i < arr.length(); i++) {
                                JSONObject o = arr.getJSONObject(i);
                                Item it = new Item();
                                it.key = o.optString("key", "");
                                it.name = o.optString("name", "example.gif");
                                if (!it.key.isEmpty()) items.add(it);
                            }
                        } catch (Exception ignored) { }
                    } else {
                        status.setText("Could not load GIFs");
                        rv.getAdapter().notifyDataSetChanged();
                        return;
                    }
                    rv.getAdapter().notifyDataSetChanged();
                    status.setText("No GIF uploaded yet");
                    status.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
                });
    }

    /** GIPHY ফোল্ডার: GIPHY API-র ট্রেন্ডিং GIF (StickerConfig-এর কী দিয়ে) */
    private void loadGiphy() {
        final int token = ++loadToken;
        if (!StickerConfig.hasKey()) { status.setText("No GIFs"); return; }
        new Thread(() -> {
            final List<Item> got = new ArrayList<>();
            try {
                String u = "https://api.giphy.com/v1/gifs/trending?api_key=" + StickerConfig.GIPHY_KEY + "&limit=48&rating=g";
                try (okhttp3.Response resp = HTTP.newCall(new okhttp3.Request.Builder().url(u).build()).execute()) {
                    if (resp.isSuccessful() && resp.body() != null) {
                        JSONArray data = new JSONObject(resp.body().string()).getJSONArray("data");
                        for (int i = 0; i < data.length(); i++) {
                            JSONObject im = data.getJSONObject(i).getJSONObject("images");
                            JSONObject o = im.optJSONObject("fixed_width");
                            if (o == null) continue;
                            JSONObject full = im.optJSONObject("downsized");
                            Item it = new Item();
                            it.name = "giphy.gif";
                            it.key = full != null && full.has("url") ? full.getString("url") : o.getString("url");
                            got.add(it);
                        }
                    }
                }
            } catch (Exception ignored) { }
            a.runOnUiThread(() -> {
                if (a.isFinishing() || !giphyTab || token != loadToken) return;
                items.clear();
                items.addAll(got);
                rv.getAdapter().notifyDataSetChanged();
                status.setText("No GIFs");
                status.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
            });
        }).start();
    }

    private void pick(final Item it) {
        if (host.keyOnly()) {
            if (dlg != null) dlg.dismiss();
            host.onPickKey(it.key);
            return;
        }
        if (!Net.check(a)) return;
        toast("Loading…");
        Media.loadBytes(it.key, (bytes, err) -> {
            if (a.isFinishing()) return;
            if (bytes == null) { toast("Could not load this GIF"); return; }
            try {
                File f = new File(a.getCacheDir(), "gif_" + System.currentTimeMillis() + ".gif");
                try (FileOutputStream o = new FileOutputStream(f)) { o.write(bytes); }
                if (dlg != null) dlg.dismiss();
                host.onPick(Uri.fromFile(f));
            } catch (Exception e) {
                toast("Could not load this GIF");
            }
        });
    }

    // ------------------------------------------------------------ আপলোড (শুধু ADMIN_EMAIL)

    /** Host ফাইল-পিকার থেকে Uri পেলে এটা ডাকে */
    void onFilePicked(final Uri uri) {
        if (uri == null || !canUpload() || uploading) return;
        if (!Net.check(a)) return;
        final String name = displayName(uri);
        if (!name.toLowerCase(Locale.ROOT).endsWith(".gif")) { toast("Please choose a .gif file"); return; }
        final byte[] data;
        try {
            data = readLimited(uri, MAX_BYTES);
        } catch (Exception e) {
            toast("Could not read this file");
            return;
        }
        if (data == null) { toast("GIF must be 1MB or smaller"); return; }       // 1MB-এর বেশি
        if (data.length < 6 || data[0] != 'G' || data[1] != 'I' || data[2] != 'F') { toast("This is not a GIF file"); return; }

        uploading = true;
        if (uploadBtn != null) uploadBtn.setAlpha(0.5f);
        toast("Uploading…");
        Media.upload(data, "post", name, "image/gif", (key, err) -> {
            if (key == null) { finishUpload("Upload failed"); return; }
            try {
                JSONObject row = new JSONObject()
                        .put("user_id", Me.uid())
                        .put("key", key)
                        .put("name", name)
                        .put("size_bytes", data.length);
                SupabaseRest.call("POST", "/rest/v1/gif_library", row.toString(), "return=minimal", true, r -> {
                    if (r.ok()) { finishUpload("GIF uploaded"); load(); }
                    else finishUpload(SupabaseRest.errorText(r));
                });
            } catch (Exception e) {
                finishUpload("Upload failed");
            }
        });
    }

    private void finishUpload(String msg) {
        uploading = false;
        if (uploadBtn != null) uploadBtn.setAlpha(1f);
        toast(msg);
    }

    private String displayName(Uri u) {
        String n = null;
        try (Cursor c = a.getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) n = c.getString(0);
        } catch (Exception ignored) { }
        if (n == null || n.isEmpty()) { n = u.getLastPathSegment(); }
        return n == null ? "example.gif" : n;
    }

    /** max বাইটের বেশি হলে null */
    private byte[] readLimited(Uri u, int max) throws Exception {
        try (InputStream in = a.getContentResolver().openInputStream(u)) {
            if (in == null) throw new Exception("no stream");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n, total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > max) return null;
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private void toast(String m) {
        Toast.makeText(a, m, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------ গ্রিড

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @NonNull @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int t) {
            int w = (a.getResources().getDisplayMetrics().widthPixels - dp(8)) / 3;
            FrameLayout f = new FrameLayout(a);
            f.setLayoutParams(new RecyclerView.LayoutParams(w, w));
            f.setPadding(dp(2), dp(2), dp(2), dp(2));
            ImageView iv = new ImageView(a);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackgroundColor(0xFFEEEEEE);
            f.addView(iv, new FrameLayout.LayoutParams(-1, -1));
            return new RecyclerView.ViewHolder(f) { };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos) {
            final Item it = items.get(pos);
            final ImageView iv = (ImageView) ((FrameLayout) h.itemView).getChildAt(0);
            iv.setImageDrawable(null);
            iv.setTag(it.key);
            if (it.key.startsWith("http")) {
                Glide.with(iv).load(it.key).into(iv);
                h.itemView.setOnClickListener(v -> pick(it));
                return;
            }
            Media.readUrl(it.key, (url, err) -> {
                if (url == null || !it.key.equals(iv.getTag()) || a.isFinishing()) return;
                Glide.with(iv).load(url).into(iv);        // অ্যানিমেটেড GIF চলে
            });
            h.itemView.setOnClickListener(v -> pick(it));
        }

        @Override public int getItemCount() { return items.size(); }
    }
}
