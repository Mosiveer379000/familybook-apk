package com.familybook.app;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * কমেন্ট বক্সের নিচের দুই প্যানেল:
 *  - Gallery (ক্যামেরা আইকন চাপলে): ফোনের ছবির গ্রিড
 *  - Stickers / GIF (গোল প্লাস চাপলে): ক্যাটাগরি-ভিত্তিক সার্চ + GIF বাটন (GIPHY)
 */
final class CommentPanels {

    interface Host {
        void onImagePicked(Uri uri);          // গ্যালারি থেকে বাছা ছবি
        void onGifKey(String key);            // স্টিকার/GIF লিংক
        void openGifLibrary();                // FamilyBook + GIPHY GIF লাইব্রেরি
        void takePhoto();                     // ক্যামেরা
        void requestGalleryPermission();      // ছবি দেখার অনুমতি চাও
    }

    static final int PANEL_DP = 250;

    private static final okhttp3.OkHttpClient HTTP = new okhttp3.OkHttpClient();
    private static final int BLUE = 0xFF1877F2, GRAY = 0xFF65676B;

    private final Activity a;
    private final Host host;
    private final float d;

    // গ্যালারি
    private final List<Uri> photos = new ArrayList<>();
    private RecyclerView galleryGrid;
    private TextView galleryMsg;

    // স্টিকার / GIF
    private static final class Item { String thumb, full; }
    private final List<Item> items = new ArrayList<>();
    private RecyclerView stickerGrid;
    private TextView stickerMsg;
    private EditText searchBox;
    private boolean gifMode = false;
    private String query = "";
    private int token = 0;
    private TextView tabSticker, tabGif;
    private LinearLayout chipRow;

    CommentPanels(Activity a, Host host) {
        this.a = a;
        this.host = host;
        this.d = a.getResources().getDisplayMetrics().density;
    }

    private int dp(float v) { return Math.round(v * d); }

    // ------------------------------------------------------------ গ্যালারি

    View buildGallery() {
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        LinearLayout head = new LinearLayout(a);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(14), dp(6), dp(10), dp(6));
        TextView t = new TextView(a);
        t.setText("Gallery \u25BE");
        t.setTextColor(0xFF050505);
        t.setTextSize(19);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        head.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        ImageView cam = new ImageView(a);
        cam.setImageResource(R.drawable.ic_cm_camera);
        cam.setColorFilter(0xFF050505);
        cam.setPadding(dp(8), dp(8), dp(8), dp(8));
        cam.setOnClickListener(v -> host.takePhoto());
        head.addView(cam, new LinearLayout.LayoutParams(dp(40), dp(40)));
        root.addView(head, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout body = new FrameLayout(a);
        galleryGrid = new RecyclerView(a);
        galleryGrid.setLayoutManager(new GridLayoutManager(a, 3));
        galleryGrid.setAdapter(new PhotoAdapter());
        body.addView(galleryGrid, new FrameLayout.LayoutParams(-1, -1));
        galleryMsg = new TextView(a);
        galleryMsg.setTextColor(GRAY);
        galleryMsg.setTextSize(15);
        galleryMsg.setGravity(Gravity.CENTER);
        galleryMsg.setPadding(dp(24), 0, dp(24), 0);
        galleryMsg.setVisibility(View.GONE);
        galleryMsg.setOnClickListener(v -> host.requestGalleryPermission());
        body.addView(galleryMsg, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1f));
        return root;
    }

    private boolean hasPhotoPermission() {
        String p = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE;
        return ContextCompat.checkSelfPermission(a, p) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean granted(android.content.Context c) {
        return ContextCompat.checkSelfPermission(c, photoPermission()) == PackageManager.PERMISSION_GRANTED;
    }

    static String photoPermission() {
        return Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    /** প্যানেল খুললে / অনুমতি পেলে ছবি লোড */
    void refreshGallery() {
        if (galleryGrid == null) return;
        if (!hasPhotoPermission()) {
            galleryMsg.setText("Tap to allow access to your photos");
            galleryMsg.setVisibility(View.VISIBLE);
            return;
        }
        galleryMsg.setVisibility(View.GONE);
        photos.clear();
        try (Cursor c = a.getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Images.Media._ID}, null, null, MediaStore.Images.Media.DATE_ADDED + " DESC")) {
            if (c != null) {
                int col = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
                int n = 0;
                while (c.moveToNext() && n < 120) {
                    photos.add(Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, String.valueOf(c.getLong(col))));
                    n++;
                }
            }
        } catch (Exception ignored) { }
        galleryGrid.getAdapter().notifyDataSetChanged();
        if (photos.isEmpty()) {
            galleryMsg.setText("No photos");
            galleryMsg.setVisibility(View.VISIBLE);
        }
    }

    private final class PhotoAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int t) {
            int w = a.getResources().getDisplayMetrics().widthPixels / 3;
            ImageView iv = new ImageView(a);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackgroundColor(0xFFE4E6EB);
            iv.setLayoutParams(new RecyclerView.LayoutParams(w, w));
            return new RecyclerView.ViewHolder(iv) { };
        }
        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos) {
            final Uri u = photos.get(pos);
            ImageView iv = (ImageView) h.itemView;
            Glide.with(iv).load(u).centerCrop().into(iv);
            iv.setOnClickListener(v -> host.onImagePicked(u));
        }
        @Override public int getItemCount() { return photos.size(); }
    }

    // ------------------------------------------------------------ স্টিকার / GIF

    private static final String[][] CATS = {
            {"Happy", "#FFC400", "#000000"}, {"In love", "#F5448B", "#FFFFFF"}, {"Sad", "#B5AFA3", "#000000"},
            {"Eating", "#F57C00", "#FFFFFF"}, {"Angry", "#E53935", "#FFFFFF"}, {"Cool", "#1E88E5", "#FFFFFF"},
            {"Funny", "#43A047", "#FFFFFF"}, {"Wow", "#8E24AA", "#FFFFFF"}, {"Party", "#00ACC1", "#FFFFFF"}
    };

    View buildStickers() {
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        // উপরের আইকন সারি: সার্চ · স্টিকার · GIF
        LinearLayout tabs = new LinearLayout(a);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setGravity(Gravity.CENTER_VERTICAL);
        tabs.setPadding(dp(8), dp(4), dp(8), dp(2));
        ImageView sIc = new ImageView(a);
        sIc.setImageResource(R.drawable.ic_ct_search);
        sIc.setColorFilter(GRAY);
        sIc.setPadding(dp(9), dp(9), dp(9), dp(9));
        sIc.setOnClickListener(v -> { if (searchBox != null) searchBox.requestFocus(); });
        tabs.addView(sIc, new LinearLayout.LayoutParams(dp(42), dp(42)));
        tabSticker = new TextView(a);
        tabSticker.setText("Stickers");
        tabSticker.setTextSize(14);
        tabSticker.setTypeface(null, android.graphics.Typeface.BOLD);
        tabSticker.setGravity(Gravity.CENTER);
        tabSticker.setPadding(dp(14), dp(6), dp(14), dp(6));
        tabSticker.setOnClickListener(v -> { gifMode = false; styleTabs(); search(query); });
        tabs.addView(tabSticker, new LinearLayout.LayoutParams(-2, -2));
        tabGif = new TextView(a);
        tabGif.setText("GIF");
        tabGif.setTextSize(14);
        tabGif.setTypeface(null, android.graphics.Typeface.BOLD);
        tabGif.setGravity(Gravity.CENTER);
        tabGif.setPadding(dp(14), dp(6), dp(14), dp(6));
        tabGif.setOnClickListener(v -> { gifMode = true; styleTabs(); search(query); });
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-2, -2);
        gl.setMarginStart(dp(6));
        tabs.addView(tabGif, gl);
        View sp = new View(a);
        tabs.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));
        TextView lib = new TextView(a);
        lib.setText("More GIFs");
        lib.setTextColor(BLUE);
        lib.setTextSize(14);
        lib.setTypeface(null, android.graphics.Typeface.BOLD);
        lib.setPadding(dp(10), dp(8), dp(10), dp(8));
        lib.setOnClickListener(v -> host.openGifLibrary());
        tabs.addView(lib);
        root.addView(tabs, new LinearLayout.LayoutParams(-1, -2));

        // সার্চ + ক্যাটাগরি চিপ
        HorizontalScrollView hs = new HorizontalScrollView(a);
        hs.setHorizontalScrollBarEnabled(false);
        chipRow = new LinearLayout(a);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setGravity(Gravity.CENTER_VERTICAL);
        chipRow.setPadding(dp(10), dp(2), dp(10), dp(4));
        searchBox = new EditText(a);
        searchBox.setHint("Search");
        searchBox.setHintTextColor(GRAY);
        searchBox.setTextColor(0xFF050505);
        searchBox.setTextSize(14);
        searchBox.setSingleLine(true);
        searchBox.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchBox.setPadding(dp(14), 0, dp(14), 0);
        GradientDrawable sb = new GradientDrawable();
        sb.setColor(Color.WHITE);
        sb.setStroke(dp(1), 0xFF8A8D91);
        sb.setCornerRadius(dp(20));
        searchBox.setBackground(sb);
        searchBox.setOnEditorActionListener((v, id, e) -> {
            if (id == EditorInfo.IME_ACTION_SEARCH) {
                search(searchBox.getText().toString().trim());
                android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager) a.getSystemService("input_method");
                if (imm != null) imm.hideSoftInputFromWindow(searchBox.getWindowToken(), 0);
                return true;
            }
            return false;
        });
        chipRow.addView(searchBox, new LinearLayout.LayoutParams(dp(120), dp(36)));
        for (final String[] c : CATS) {
            TextView chip = new TextView(a);
            chip.setText(c[0]);
            chip.setTextSize(14);
            chip.setTextColor(Color.parseColor(c[2]));
            chip.setTypeface(null, android.graphics.Typeface.BOLD);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(16), 0, dp(16), 0);
            GradientDrawable cb = new GradientDrawable();
            cb.setColor(Color.parseColor(c[1]));
            cb.setCornerRadius(dp(20));
            chip.setBackground(cb);
            chip.setOnClickListener(v -> { if (searchBox != null) searchBox.setText(c[0]); search(c[0]); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(36));
            lp.setMarginStart(dp(8));
            chipRow.addView(chip, lp);
        }
        hs.addView(chipRow);
        root.addView(hs, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout body = new FrameLayout(a);
        stickerGrid = new RecyclerView(a);
        stickerGrid.setLayoutManager(new GridLayoutManager(a, 4));
        stickerGrid.setAdapter(new StickerAdapter());
        body.addView(stickerGrid, new FrameLayout.LayoutParams(-1, -1));
        stickerMsg = new TextView(a);
        stickerMsg.setTextColor(GRAY);
        stickerMsg.setTextSize(15);
        stickerMsg.setGravity(Gravity.CENTER);
        stickerMsg.setVisibility(View.GONE);
        body.addView(stickerMsg, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1f));
        styleTabs();
        return root;
    }

    private void styleTabs() {
        if (tabSticker == null) return;
        tabSticker.setTextColor(gifMode ? GRAY : BLUE);
        tabGif.setTextColor(gifMode ? BLUE : GRAY);
    }

    /** প্যানেল খুললে ট্রেন্ডিং স্টিকার */
    void refreshStickers() {
        if (items.isEmpty()) search("");
    }

    private void search(final String q) {
        query = q == null ? "" : q;
        final int my = ++token;
        if (!StickerConfig.hasKey()) {
            items.clear();
            stickerGrid.getAdapter().notifyDataSetChanged();
            stickerMsg.setText("No stickers");
            stickerMsg.setVisibility(View.VISIBLE);
            return;
        }
        stickerMsg.setText("Loading\u2026");
        stickerMsg.setVisibility(View.VISIBLE);
        final String kind = gifMode ? "gifs" : "stickers";
        final String url = "https://api.giphy.com/v1/" + kind + (query.isEmpty() ? "/trending?" : "/search?q="
                + Uri.encode(query) + "&") + "api_key=" + StickerConfig.GIPHY_KEY + "&limit=40&rating=g";
        new Thread(() -> {
            final List<Item> got = new ArrayList<>();
            try (okhttp3.Response resp = HTTP.newCall(new okhttp3.Request.Builder().url(url).build()).execute()) {
                if (resp.isSuccessful() && resp.body() != null) {
                    JSONArray data = new JSONObject(resp.body().string()).getJSONArray("data");
                    for (int i = 0; i < data.length(); i++) {
                        JSONObject im = data.getJSONObject(i).optJSONObject("images");
                        if (im == null) continue;
                        JSONObject th = im.optJSONObject("fixed_height_small");
                        JSONObject fu = im.optJSONObject(gifMode ? "downsized" : "fixed_height");
                        if (th == null) th = im.optJSONObject("fixed_height");
                        if (fu == null) fu = th;
                        if (th == null || fu == null || !th.has("url") || !fu.has("url")) continue;
                        Item it = new Item();
                        it.thumb = th.getString("url");
                        it.full = fu.getString("url");
                        got.add(it);
                    }
                }
            } catch (Exception ignored) { }
            a.runOnUiThread(() -> {
                if (a.isFinishing() || my != token) return;
                items.clear();
                items.addAll(got);
                stickerGrid.getAdapter().notifyDataSetChanged();
                stickerMsg.setText("No results");
                stickerMsg.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
            });
        }).start();
    }

    private final class StickerAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int t) {
            int w = a.getResources().getDisplayMetrics().widthPixels / 4;
            ImageView iv = new ImageView(a);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setPadding(dp(6), dp(6), dp(6), dp(6));
            iv.setLayoutParams(new RecyclerView.LayoutParams(w, w));
            return new RecyclerView.ViewHolder(iv) { };
        }
        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos) {
            final Item it = items.get(pos);
            ImageView iv = (ImageView) h.itemView;
            Glide.with(iv).load(it.thumb).placeholder(new ColorDrawable(0xFFF0F2F5)).into(iv);
            iv.setOnClickListener(v -> host.onGifKey(it.full));
        }
        @Override public int getItemCount() { return items.size(); }
    }
}
