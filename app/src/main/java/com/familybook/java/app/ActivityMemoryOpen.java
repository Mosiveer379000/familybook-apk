package com.familybook.app;

import android.Manifest;
import android.content.ContentUris;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;

/**
 * "Create memory" চাপলে এই পর্দা খোলে (আগের Short-ক্যামেরা বাদ)।
 * উপরে ৪টা অপশন (Coming soon) · নিচে ফোনের গ্যালারি · Gallery ▾ দিয়ে ফোল্ডার বদল · Select multiple।
 * ছবি বাছাই শেষ হলে onPicked() ডাকা হয় — পরের ধাপ (এডিট/শেয়ার) পরে এখানেই জোড়া হবে।
 */
public class ActivityMemoryOpen extends BaseActivity {

    private static final int COLS = 4;
    private static final String PERM = Build.VERSION.SDK_INT >= 33
            ? Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE;
    private static final int BLUE = 0xFF0866FF;

    private static final class Img {
        final Uri uri; final String folder;
        Img(Uri u, String f) { uri = u; folder = f; }
    }

    private final ArrayList<Img> all = new ArrayList<>();
    private final ArrayList<Img> shown = new ArrayList<>();
    private final ArrayList<Uri> picked = new ArrayList<>();   // বাছার ক্রম ধরে
    private final TreeMap<String, Integer> folders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private String folder;          // null = সব ছবি ("Gallery")
    private boolean multi;

    private TextView tvFolder, tvMulti;
    private View selBar, selScroll;
    private LinearLayout selList;
    private Adapter adapter;
    private ActivityResultLauncher<String> permLauncher;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.layout_memory);
        tvFolder = findViewById(R.id.tvGalleryFolder);
        tvMulti = findViewById(R.id.tvSelectMultiple);

        selBar = findViewById(R.id.selBar);
        selScroll = findViewById(R.id.selScroll);
        selList = findViewById(R.id.selList);
        GradientDrawable nb = new GradientDrawable();
        nb.setCornerRadius(dp(12));
        nb.setColor(BLUE);
        findViewById(R.id.btnNext).setBackground(nb);
        findViewById(R.id.btnNext).setOnClickListener(v -> onPicked(new ArrayList<>(picked)));

        findViewById(R.id.ivClose).setOnClickListener(v -> finish());
        findViewById(R.id.ivSetting).setOnClickListener(v -> soon());
        findViewById(R.id.layoutGalleryDropdown).setOnClickListener(this::showFolders);
        findViewById(R.id.btnSelectMultiple).setOnClickListener(v -> onMultiClick());
        buildOptions();

        RecyclerView rv = findViewById(R.id.rvGallery);
        rv.setLayoutManager(new GridLayoutManager(this, COLS));
        rv.setAdapter(adapter = new Adapter());

        permLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), ok -> {
            if (ok) load(); else TopToast.show(this, "Allow photo access to see your gallery");
        });
        if (ContextCompat.checkSelfPermission(this, PERM) == PackageManager.PERMISSION_GRANTED) load();
        else permLauncher.launch(PERM);
    }

    private void soon() { TopToast.show(this, "Coming soon"); }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    /** Text · Music · Collage · Camera — সবই এখন "Coming soon" */
    private void buildOptions() {
        LinearLayout bar = findViewById(R.id.optBar);
        int[] icon = {R.drawable.text_memo, R.drawable.music_memo, R.drawable.coll_memo, R.drawable.cam_memo};
        String[] name = {"Text", "Music", "Collage", "Camera"};
        for (int i = 0; i < 4; i++) {
            LinearLayout c = new LinearLayout(this);
            c.setOrientation(LinearLayout.VERTICAL);
            c.setGravity(Gravity.CENTER);
            c.setPadding(0, dp(8), 0, dp(8));
            c.setBackgroundResource(R.drawable.bg_option_card);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            lp.setMargins(dp(4), 0, dp(4), 0);
            c.setLayoutParams(lp);

            ImageView iv = new ImageView(this);
            iv.setImageResource(icon[i]);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(24), dp(24));
            ilp.gravity = Gravity.CENTER_HORIZONTAL;
            c.addView(iv, ilp);
            TextView tv = new TextView(this);
            tv.setText(name[i]);
            tv.setTextSize(12);
            tv.setTypeface(null, android.graphics.Typeface.BOLD);
            tv.setGravity(Gravity.CENTER);
            tv.setTextColor(Color.BLACK);
            tv.setPadding(0, dp(4), 0, 0);
            c.addView(tv, new LinearLayout.LayoutParams(-2, -2));

            c.setOnClickListener(v -> soon());
            bar.addView(c);
        }
    }

    // ───────────── গ্যালারি লোড ─────────────

    private void load() {
        new Thread(() -> {
            ArrayList<Img> tmp = new ArrayList<>();
            Uri base = Build.VERSION.SDK_INT >= 29
                    ? MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                    : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            String[] cols = {MediaStore.Images.Media._ID, MediaStore.Images.Media.BUCKET_DISPLAY_NAME};
            try (Cursor c = getContentResolver().query(base, cols, null, null,
                    MediaStore.Images.Media.DATE_ADDED + " DESC")) {
                while (c != null && c.moveToNext()) {
                    String f = c.getString(1);
                    tmp.add(new Img(ContentUris.withAppendedId(base, c.getLong(0)), f == null ? "Other" : f));
                }
            } catch (Exception ignored) { }
            runOnUiThread(() -> {
                if (isFinishing()) return;
                all.clear(); all.addAll(tmp);
                folders.clear();
                for (Img i : all) { Integer n = folders.get(i.folder); folders.put(i.folder, n == null ? 1 : n + 1); }
                applyFilter();
            });
        }).start();
    }

    private void applyFilter() {
        shown.clear();
        for (Img i : all) if (folder == null || folder.equals(i.folder)) shown.add(i);
        tvFolder.setText(folder == null ? "Gallery" : folder);
        adapter.notifyDataSetChanged();
    }

    /** Gallery ▾ — ফোনের সব ফোল্ডার */
    private void showFolders(View anchor) {
        if (all.isEmpty()) return;
        PopupMenu pm = new PopupMenu(this, anchor);
        final ArrayList<String> names = new ArrayList<>();
        names.add(null);
        pm.getMenu().add(0, 0, 0, "Gallery (" + all.size() + ")");
        for (Map.Entry<String, Integer> e : folders.entrySet()) {
            names.add(e.getKey());
            pm.getMenu().add(0, names.size() - 1, names.size() - 1, e.getKey() + " (" + e.getValue() + ")");
        }
        pm.setOnMenuItemClickListener(it -> {
            folder = names.get(it.getItemId());
            applyFilter();
            return true;
        });
        pm.show();
    }

    // ───────────── বাছাই ─────────────

    /** Select multiple ↔ Cancel (Cancel চাপলে বাছাই মুছে সাধারণ মোডে ফেরে) */
    private void onMultiClick() {
        multi = !multi;
        if (!multi) picked.clear();
        tvMulti.setText(multi ? "Cancel" : "Select multiple");
        adapter.notifyDataSetChanged();
        updateBar();
    }

    private void tap(Uri u, int pos) {
        if (!multi) { ArrayList<Uri> one = new ArrayList<>(); one.add(u); onPicked(one); return; }
        if (!picked.remove(u)) picked.add(u);
        adapter.notifyDataSetChanged();   // বাছা ছবির নম্বর (১,২,৩…) ঠিক রাখতে
        updateBar();
    }

    /** নিচের বার: বাছাই থাকলে ছোট ছবির সারি + Next, না থাকলে লুকানো */
    private void updateBar() {
        selList.removeAllViews();
        selBar.setVisibility(picked.isEmpty() ? View.GONE : View.VISIBLE);
        for (Uri u : new ArrayList<>(picked)) {
            FrameLayout f = new FrameLayout(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(60), dp(60));
            lp.setMarginEnd(dp(8));
            f.setLayoutParams(lp);
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            Glide.with(iv).load(u).into(iv);
            f.addView(iv, new FrameLayout.LayoutParams(-1, -1));
            TextView x = new TextView(this);
            x.setText("\u2715");
            x.setTextSize(10);
            x.setTextColor(Color.WHITE);
            x.setGravity(Gravity.CENTER);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(0xAA000000);
            x.setBackground(g);
            FrameLayout.LayoutParams xl = new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.TOP | Gravity.END);
            xl.setMargins(0, dp(2), dp(2), 0);
            f.addView(x, xl);
            x.setOnClickListener(v -> { picked.remove(u); adapter.notifyDataSetChanged(); updateBar(); });
            selList.addView(f);
        }
        selScroll.post(() -> ((android.widget.HorizontalScrollView) selScroll).fullScroll(View.FOCUS_RIGHT));
    }

    /** TODO: পরের ধাপ (এডিট/শেয়ার) এখানে জোড়া হবে */
    private void onPicked(ArrayList<Uri> uris) {
        TopToast.show(this, uris.size() + (uris.size() == 1 ? " photo" : " photos") + " selected");
    }

    // ───────────── গ্রিড ─────────────

    private final class Adapter extends RecyclerView.Adapter<Adapter.VH> {
        final class VH extends RecyclerView.ViewHolder {
            final ImageView iv; final TextView badge;
            VH(FrameLayout f, ImageView i, TextView t) { super(f); iv = i; badge = t; }
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup p, int type) {
            int s = getResources().getDisplayMetrics().widthPixels / COLS;
            FrameLayout f = new FrameLayout(ActivityMemoryOpen.this);
            f.setLayoutParams(new RecyclerView.LayoutParams(s, s));
            f.setPadding(1, 1, 1, 1);
            ImageView iv = new ImageView(ActivityMemoryOpen.this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            f.addView(iv, new FrameLayout.LayoutParams(-1, -1));
            TextView t = new TextView(ActivityMemoryOpen.this);
            t.setGravity(Gravity.CENTER);
            t.setTextColor(Color.WHITE);
            t.setTextSize(12);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP | Gravity.END);
            lp.setMargins(0, dp(6), dp(6), 0);
            f.addView(t, lp);
            return new VH(f, iv, t);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            Uri u = shown.get(pos).uri;
            Glide.with(h.iv).load(u).placeholder(new ColorDrawable(0xFFEEEEEE)).into(h.iv);
            int idx = picked.indexOf(u);
            h.badge.setVisibility(multi ? View.VISIBLE : View.GONE);
            h.iv.setAlpha(idx >= 0 ? 0.7f : 1f);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(idx >= 0 ? BLUE : 0x33000000);
            g.setStroke(dp(2), Color.WHITE);
            h.badge.setBackground(g);
            h.badge.setText(idx >= 0 ? String.valueOf(idx + 1) : "");
            h.itemView.setOnClickListener(v -> {
                int p = h.getBindingAdapterPosition();
                if (p >= 0) tap(shown.get(p).uri, p);
            });
        }

        @Override public int getItemCount() { return shown.size(); }
    }
}
