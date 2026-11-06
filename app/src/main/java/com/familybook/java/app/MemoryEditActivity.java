package com.familybook.app;

import android.Manifest;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.provider.MediaStore;
import android.widget.PopupWindow;
import androidx.core.content.ContextCompat;
import com.bumptech.glide.Glide;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;
import androidx.annotation.OptIn;
import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.transformer.Effects;
import androidx.media3.common.MimeTypes;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.Transformer;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Memory (story) এডিটর — শুধু Text এডিটর, কোনো ভিডিও-এডিটর নেই।
 *
 * স্টেজ (৯:১৬ ⇄ ১৬:৯) → লেয়ার: ছবি / টেক্সট / ট্যাগ / স্টিকার — সবই TextItem।
 * প্রতিটি লেয়ারে: টানা, দুই আঙুলে বড়-ছোট ও ঘোরানো, কোণায় ✕ (মোছা)।
 * Canvas ব্যাকগ্রাউন্ডও আঙুলে টানা / জুম / ঘোরানো যায়।
 * Share চাপলে স্টেজ → ছবি → ৫ সেকেন্ডের mp4 → MemorySettingsActivity (আগের আপলোড লাইন)।
 *
 * Intent: EXTRA_URIS (ছবির Uri তালিকা), EXTRA_OPEN_TEXT (true হলে সরাসরি টেক্সট এডিটর)।
 */
public class MemoryEditActivity extends BaseActivity {

    static final String EXTRA_URIS = "uris";
    static final String EXTRA_OPEN_TEXT = "open_text";

    private static final int BLUE = 0xFF0866FF;
    private static final long FOREVER = Long.MAX_VALUE / 4;
    private static final int[][] GRADS = {
            {0xFFB0BEC5, 0xFFECEFF1, 0xFF78909C},
            {0xFF8E2DE2, 0xFF00B4DB},
            {0xFF0D47A1, 0xFF1E88E5, 0xFF0B1F6B},
            {0xFF000000, 0xFF000000},
            {0xFFFFC107, 0xFFE91E63},
            {0xFFE05260, 0xFFE05260},
            {0xFF00C9A7, 0xFF005F73},
            {0xFFFF9966, 0xFFFF5E62},
    };

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<TextItem> items = new ArrayList<>();

    private FrameLayout root, stageBox;
    private MemStage stage;
    private TextEditor editor;
    private final ArrayList<String> mentions = new ArrayList<>();
    private int fxIdx = 0;
    private String mUrl, mTitle, mArtist, mCover;
    private int mStart = 0;
    private TextView tvMusicTop;
    private ActivityResultLauncher<Intent> musicLauncher, trimLauncher;
    private LinearLayout btnOrder;
    private TextView tvBusy;
    private boolean portrait = true;
    private boolean busy = false;
    private Transformer transformer;
    private ShortStickerSheet stickers;
    private ActivityResultLauncher<String> picker;
    private ActivityResultLauncher<String> permLauncher, locPermLauncher;
    private Runnable pendingLoc;
    private String memLocation = "", memFeeling = "";
    // মেনুর আইকন (drawable) — নাম আলাদা হলে শুধু এই দুই লাইন বদলান
    private static final int ICON_LOCATION = R.drawable.ic_current_city;
    private static final int ICON_FEELING = R.drawable.ic_feeling_menu;
    private ActivityResultLauncher<Intent> placeLauncher, feelLauncher;
    static final String EXTRA_AI_LEVEL = "ai_level";
    private boolean aiLevel = false;
    private final ArrayList<Uri> galleryUris = new ArrayList<>();
    private RecyclerView.Adapter<?> galleryAdapter;
    private boolean galleryLoaded = false;

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        musicLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
            if (r.getResultCode() == RESULT_OK && r.getData() != null) {
                Intent d = r.getData();
                mTitle = d.getStringExtra(MusicPickerActivity.EXTRA_TITLE);
                mArtist = d.getStringExtra(MusicPickerActivity.EXTRA_ARTIST);
                mCover = d.getStringExtra(MusicPickerActivity.EXTRA_COVER);
                mUrl = d.getStringExtra(MusicPickerActivity.EXTRA_URL);
                mStart = 0;
                refreshMusic();
                openTrim();
            }
        });
        trimLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
            int c = r.getResultCode();
            if (c == RESULT_OK && r.getData() != null) mStart = r.getData().getIntExtra(MusicTrimActivity.EXTRA_START, 0);
            else if (c == MusicTrimActivity.RESULT_CHANGE) musicLauncher.launch(new Intent(this, MusicPickerActivity.class));
            else if (c == MusicTrimActivity.RESULT_DELETE) { mUrl = null; mTitle = null; mStart = 0; }
            refreshMusic();
        });
        picker = registerForActivityResult(new ActivityResultContracts.GetContent(), u -> { if (u != null) addPhoto(u); });
        placeLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
            if (r.getResultCode() != RESULT_OK || r.getData() == null) return;
            String loc = r.getData().getStringExtra(LocationActivity.EXTRA_LOCATION);
            memLocation = loc == null ? "" : loc;
        });
        feelLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
            if (r.getResultCode() != RESULT_OK || r.getData() == null) return;
            String t = r.getData().getStringExtra(FeelingActivity.EXTRA_TEXT);
            memFeeling = t == null ? "" : t;
        });
        locPermLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), ok -> {
            Runnable r = pendingLoc; pendingLoc = null;
            if (r == null) return;
            if (ok) r.run(); else TopToast.show(this, "Location permission denied");
        });
        permLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), ok -> {
            if (ok) loadGallery(); else TopToast.show(this, "Allow photo access to see your gallery");
        });

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF1C1C1E);
        setContentView(root);

        stageBox = new FrameLayout(this);
        root.addView(stageBox, new FrameLayout.LayoutParams(-1, -1));
        stage = new MemStage();
        stageBox.addView(stage);
        stage.setBgGradient(GRADS[2]);
        stageBox.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> fitStage());

        buildTopBar();
        buildBottomBar();

        editor = new TextEditor(this);
        root.addView(editor, new FrameLayout.LayoutParams(-1, -1));

        tvBusy = new TextView(this);
        tvBusy.setTextColor(Color.WHITE);
        tvBusy.setTextSize(15);
        tvBusy.setGravity(Gravity.CENTER);
        tvBusy.setBackgroundColor(0xAA000000);
        tvBusy.setVisibility(View.GONE);
        tvBusy.setClickable(true);
        root.addView(tvBusy, new FrameLayout.LayoutParams(-1, -1));

        stickers = new ShortStickerSheet(this, new ShortStickerSheet.Host() {
            @Override public void addText(String text, int color, int bg, float size) {
                TextItem t = newItem();
                t.text = text; t.color = color; t.bg = bg; t.size = size;
                addLayer(t);
            }
            @Override public void addImage(Bitmap bm) {
                TextItem t = newItem();
                t.bmp = bm; t.size = 0.35f;
                addLayer(t);
            }
            @Override public void pickGallery() { picker.launch("image/*"); }
            @Override public void pickMusic() { onMusic(); }
            @Override public void withLocationPermission(Runnable granted) {
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                        || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    granted.run();
                } else {
                    pendingLoc = granted;
                    locPermLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION);
                }
            }
        });
        root.addView(stickers.view(), new FrameLayout.LayoutParams(-1, -1));   // Shorts Edit-এর মত ওভারলে

        ArrayList<Uri> uris = getIntent().getParcelableArrayListExtra(EXTRA_URIS);
        if (uris != null) for (Uri u : uris) addPhoto(u);
        if (getIntent().getBooleanExtra(EXTRA_OPEN_TEXT, false) || (uris == null || uris.isEmpty()))
            main.postDelayed(() -> openText(-1), 250);
    }

    // ───────────── উপরের বার ─────────────

    private void buildTopBar() {
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(10), dp(30), dp(10), dp(6));

        View back = iconBtn(R.drawable.ic_back_left, 12);   // মেইন স্ক্রিনে ফেরার তীর (কালো PNG → কোডে সাদা)
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(dp(40), dp(40)));

        TextView music = pill("\u266A  Add music");
        tvMusicTop = music;
        music.setSingleLine(true);
        music.setEllipsize(android.text.TextUtils.TruncateAt.END);
        music.setOnClickListener(v -> onMusic());
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, dp(40), 1f);
        mp.leftMargin = dp(8); mp.rightMargin = dp(8);
        top.addView(music, mp);

        ImageView more = iconBtn(R.drawable.ic_more, 11);   // 3-dot মেনু (৯:১৬ / ১৬:৯ বাদ)
        more.setOnClickListener(v -> showMoreMenu(v));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(dp(40), dp(40));
        rp.leftMargin = dp(6);
        top.addView(more, rp);

        root.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // Front / Back — শুধু লেয়ার সিলেক্ট থাকলে দেখায় (ডান পাশে ছোট করে)
        btnOrder = new LinearLayout(this);
        btnOrder.setOrientation(LinearLayout.VERTICAL);
        btnOrder.setVisibility(View.GONE);
        TextView layerUp = pill("Front"), layerDown = pill("Back");
        layerUp.setTextSize(12); layerDown.setTextSize(12);
        layerUp.setOnClickListener(v -> stage.moveSel(true));
        layerDown.setOnClickListener(v -> stage.moveSel(false));
        btnOrder.addView(layerUp, new LinearLayout.LayoutParams(dp(64), dp(32)));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(64), dp(32));
        blp.topMargin = dp(6);
        btnOrder.addView(layerDown, blp);
        FrameLayout.LayoutParams ol = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        ol.topMargin = dp(100); ol.rightMargin = dp(8);
        root.addView(btnOrder, ol);
    }

    // ───────────── নিচের বার: Music · Stickers · Text · Canvas · Effects · Mentions ─────────────

    private void buildBottomBar() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(8), 0, dp(12), dp(10));

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER);
        String[] g = {"\u266A", "\u263A", "Aa", "@", "\u25A3", "\u2726"};
        String[] n = {"Music", "Stickers", "Text", "Mentions", "Canvas", "Effects"};
        // আইকন: drawable থেকে (0 = আইকন নেই, লেখার চিহ্নই থাকবে)
        int[] ic = {R.drawable.music_cam, R.drawable.sticker_on_video, R.drawable.text_on_video, 0, 0,
                R.drawable.effects_cam};
        for (int i = 0; i < 6; i++) {
            final int k = i;
            LinearLayout it = new LinearLayout(this);
            it.setOrientation(LinearLayout.VERTICAL);
            it.setGravity(Gravity.CENTER_HORIZONTAL);
            View c = ic[i] != 0 ? iconBtn(ic[i], 11) : glyph(g[i], 18);
            it.addView(c, new LinearLayout.LayoutParams(dp(44), dp(44)));
            TextView t = new TextView(this);
            t.setText(n[i]);
            t.setTextColor(Color.WHITE);
            t.setTextSize(11);
            t.setGravity(Gravity.CENTER);
            it.addView(t, new LinearLayout.LayoutParams(-2, -2));
            it.setOnClickListener(v -> onTool(k));
            bar.addView(it, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        col.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout row = new FrameLayout(this);
        LinearLayout pub = new LinearLayout(this);
        pub.setGravity(Gravity.CENTER_VERTICAL);
        pub.setPadding(dp(14), 0, dp(14), 0);
        GradientDrawable pg = new GradientDrawable();
        pg.setColor(0xFF262626); pg.setCornerRadius(dp(20));
        pub.setBackground(pg);
        ImageView globe = new ImageView(this);
        globe.setImageResource(R.drawable.ic_menu_pivracy);
        globe.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
        pub.addView(globe, new LinearLayout.LayoutParams(dp(18), dp(18)));
        TextView pubTx = new TextView(this);
        pubTx.setText("Public");
        pubTx.setTextColor(Color.WHITE);
        pubTx.setTextSize(14);
        pubTx.setPadding(dp(8), 0, 0, 0);
        pub.addView(pubTx, new LinearLayout.LayoutParams(-2, -2));
        row.addView(pub, new FrameLayout.LayoutParams(-2, dp(36), Gravity.START));
        TextView share = new TextView(this);
        share.setText("SHARE");
        share.setTextColor(Color.WHITE);
        share.setTextSize(15);
        share.setTypeface(null, Typeface.BOLD);
        share.setGravity(Gravity.CENTER);
        share.setPadding(dp(20), 0, dp(20), 0);
        GradientDrawable sg = new GradientDrawable();
        sg.setColor(BLUE); sg.setCornerRadius(dp(8));
        share.setBackground(sg);
        share.setOnClickListener(v -> exportNow());
        row.addView(share, new FrameLayout.LayoutParams(-2, dp(40), Gravity.END));
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
        rl.topMargin = dp(8);
        col.addView(row, rl);

        root.addView(col, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
    }

    private void onTool(int k) {
        if (editor.isOpen()) return;
        if (stickers != null && stickers.view().getVisibility() == View.VISIBLE) return;
        switch (k) {
            case 0: onMusic(); break;
            case 1: stickers.show(); break;
            case 2: openText(-1); break;
            case 3: openMentions(); break;
            case 4: openCanvas(); break;
            case 5: openEffects(); break;
        }
    }

    private TextView glyph(String s, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(null, Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0xFF2B2B2D);
        t.setBackground(g);
        return t;
    }

    /** গোল ডার্ক বাটনে drawable আইকন — কালো/ধূসর PNG-ও কোডে সাদা হয়ে যায় */
    private ImageView iconBtn(int res, int padDp) {
        ImageView iv = new ImageView(this);
        iv.setImageResource(res);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
        iv.setPadding(dp(padDp), dp(padDp), dp(padDp), dp(padDp));
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0xFF2B2B2D);
        iv.setBackground(g);
        return iv;
    }

    private TextView pill(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Color.WHITE);
        t.setTextSize(14);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), 0, dp(14), 0);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFF262626);
        g.setCornerRadius(dp(20));
        t.setBackground(g);
        return t;
    }

    // ───────────── স্টেজের মাপ ─────────────

    private void fitStage() {
        int W = stageBox.getWidth(), H = stageBox.getHeight();
        if (W <= 0 || H <= 0) return;
        // ক্যানভাস এখন পুরো স্ক্রিন (Full Screen); ছোট-বড়-ঘোরানো আঙুলেই হয়
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -1);
        stage.setLayoutParams(lp);
    }

    /** এক্সপোর্ট/সেভের মাপ — স্ক্রিনের অনুপাত ধরে, প্রস্থ ১০৮০ */
    private int[] outSize() {
        int W = Math.max(1, stageBox.getWidth()), H = Math.max(1, stageBox.getHeight());
        int w = 1080;
        int h = Math.round(w * (float) H / W);
        if (h % 2 == 1) h++;
        return new int[]{w, h};
    }

    // ───────────── লেয়ার ─────────────

    private TextItem newItem() {
        TextItem t = new TextItem();
        t.start = 0; t.end = FOREVER;
        return t;
    }

    private void addLayer(TextItem t) {
        items.add(t);
        stage.select(items.size() - 1);
    }

    private void addPhoto(Uri u) {
        new Thread(() -> {
            final Bitmap bm = decode(u, 1600);
            main.post(() -> {
                if (bm == null) { TopToast.show(this, "Couldn't open the photo"); return; }
                TextItem t = newItem();
                t.bmp = bm;
                boolean first = true;
                for (TextItem o : items) if (o.bmp != null) first = false;
                t.size = first ? 1f : 0.6f;
                if (!first) { t.cx = 0.5f + 0.04f * items.size(); t.cy = 0.5f + 0.04f * items.size(); }
                addLayer(t);
            });
        }).start();
    }

    private Bitmap decode(Uri u, int maxSide) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(u)) { BitmapFactory.decodeStream(in, null, o); }
            int s = 1;
            while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= maxSide) s *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = s;
            try (InputStream in = getContentResolver().openInputStream(u)) { return BitmapFactory.decodeStream(in, null, o); }
        } catch (Throwable e) { return null; }
    }

    private Bitmap decodeAsset(String path, int maxSide) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = getAssets().open(path)) { BitmapFactory.decodeStream(in, null, o); }
            int s = 1;
            while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= maxSide) s *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = s;
            try (InputStream in = getAssets().open(path)) { return BitmapFactory.decodeStream(in, null, o); }
        } catch (Throwable e) { return null; }
    }

    // ───────────── টেক্সট ─────────────

    private void openText(int idx) {
        if (editor.isOpen()) return;
        final boolean existing = idx >= 0 && idx < items.size() && items.get(idx).bmp == null;
        final TextItem src = existing ? items.get(idx) : newItem();
        final int at = existing ? idx : -1;
        editor.show(src, res -> {
            if (res.empty()) {
                if (at >= 0) { items.remove(at); stage.select(-1); }
                return;
            }
            res.start = 0; res.end = FOREVER;
            if (at >= 0) { items.set(at, res); stage.select(at); }
            else addLayer(res);
        });
    }

    // ───────────── Mentions (ফলোয়ার লিস্ট → উপরে নীল বোল্ড নাম) ─────────────

    private void openMentions() {
        final FrameLayout dim = new FrameLayout(this);
        dim.setBackgroundColor(0x99000000);
        dim.setClickable(true);

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable sg = new GradientDrawable();
        sg.setColor(0xFF242526);
        sg.setCornerRadii(new float[]{dp(20), dp(20), dp(20), dp(20), 0, 0, 0, 0});
        sheet.setBackground(sg);
        sheet.setPadding(dp(16), dp(14), dp(16), dp(10));
        sheet.setClickable(true);

        FrameLayout head = new FrameLayout(this);
        TextView title = new TextView(this);
        title.setText("Tag people");
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setTypeface(null, Typeface.BOLD);
        head.addView(title, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        TextView done = new TextView(this);
        done.setText("Done");
        done.setTextColor(0xFF5B9DFF);
        done.setTextSize(16);
        done.setOnClickListener(v -> root.removeView(dim));
        head.addView(done, new FrameLayout.LayoutParams(-2, -2, Gravity.END));
        sheet.addView(head, new LinearLayout.LayoutParams(-1, dp(36)));

        final EditText search = new EditText(this);
        search.setHint("Search");
        search.setHintTextColor(0xFF9A9A9A);
        search.setTextColor(Color.WHITE);
        search.setSingleLine(true);
        search.setPadding(dp(16), 0, dp(16), 0);
        GradientDrawable eg = new GradientDrawable();
        eg.setColor(0xFF3A3B3C); eg.setCornerRadius(dp(22));
        search.setBackground(eg);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, dp(44));
        sp.topMargin = dp(10);
        sheet.addView(search, sp);

        ScrollView sv = new ScrollView(this);
        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        sheet.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        final Runnable[] fill = new Runnable[1];
        fill[0] = () -> {
            list.removeAllViews();
            String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
            List<MentionRepo.Person> ps = MentionRepo.cached();
            if (ps == null) return;
            for (final MentionRepo.Person p : ps) {
                if (p.name == null || (!q.isEmpty() && !p.name.toLowerCase(Locale.ROOT).contains(q))) continue;
                final boolean on = mentions.contains(p.name);
                TextView r = new TextView(this);
                r.setText(on ? p.name + "   \u2713" : p.name);
                r.setTextColor(on ? 0xFF5B9DFF : Color.WHITE);
                r.setTextSize(17);
                r.setPadding(dp(8), dp(14), dp(8), dp(14));
                r.setOnClickListener(v -> {
                    if (!mentions.remove(p.name)) mentions.add(p.name);
                    stage.invalidate();
                    fill[0].run();
                });
                list.addView(r, new LinearLayout.LayoutParams(-1, -2));
            }
        };
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable e) { fill[0].run(); }
        });
        MentionRepo.load(people -> fill[0].run());

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, Math.round(getResources().getDisplayMetrics().heightPixels * 0.7f), Gravity.BOTTOM);
        dim.addView(sheet, lp);
        dim.setOnClickListener(v -> root.removeView(dim));
        root.addView(dim, new FrameLayout.LayoutParams(-1, -1));
    }

    // ───────────── Music (Shorts/Status-এর MusicPicker · MusicTrim · MusicPlayer) ─────────────

    private void onMusic() {
        if (mUrl == null) musicLauncher.launch(new Intent(this, MusicPickerActivity.class));
        else openTrim();
    }

    private void openTrim() {
        Intent t = new Intent(this, MusicTrimActivity.class);
        t.putExtra(MusicPickerActivity.EXTRA_URL, mUrl);
        t.putExtra(MusicPickerActivity.EXTRA_TITLE, mTitle);
        t.putExtra(MusicPickerActivity.EXTRA_ARTIST, mArtist);
        t.putExtra(MusicPickerActivity.EXTRA_COVER, mCover);
        t.putExtra(MusicTrimActivity.EXTRA_START, mStart);
        trimLauncher.launch(t);
    }

    private void refreshMusic() {
        tvMusicTop.setText(mUrl == null ? "\u266A  Add music" : "\u266A  " + (mTitle == null ? "Music" : mTitle));
        if (mUrl != null) {
            MusicPlayer.play(this, "memory_edit", mUrl, mStart, 0);
        } else {
            MusicPlayer.stop();
        }
    }

    private interface FileCb { void on(File f); }

    private void resolveMusic(final FileCb cb) {
        final android.content.Context app = getApplicationContext();
        if (mUrl.startsWith(MusicApi.PREFIX)) {
            MusicApi.fetchAudio(app, mUrl.substring(MusicApi.PREFIX.length()), (file, err) -> main.post(() -> cb.on(file)));
        } else if (mUrl.startsWith(DeezerApi.PREFIX)) {
            DeezerApi.resolvePreview(mUrl.substring(DeezerApi.PREFIX.length()), preview -> download(preview, cb));
        } else {
            download(mUrl, cb);
        }
    }

    private void download(final String url, final FileCb cb) {
        if (url == null) { cb.on(null); return; }
        new Thread(() -> {
            File out = new File(getCacheDir(), "shorts/music_" + System.currentTimeMillis() + ".mp3");
            out.getParentFile().mkdirs();
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(15000); c.setReadTimeout(20000);
                try (InputStream in = c.getInputStream(); FileOutputStream fo = new FileOutputStream(out)) {
                    byte[] buf = new byte[32 * 1024]; int n;
                    while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
                }
                main.post(() -> cb.on(out));
            } catch (Exception e) {
                main.post(() -> cb.on(null));
            }
        }).start();
    }

    // ───────────── Effects (ভিডিওর ইফেক্টগুলোই, ক্যামেরা ছাড়া) ─────────────

    private static final int[] FX_ORDER = {0, 3, 4, 5, 6, 7, 8, 9, 10, 11, ShortFx.ULTRA};

    private void openEffects() {
        final FrameLayout dim = new FrameLayout(this);
        dim.setClickable(true);
        dim.setOnClickListener(v -> root.removeView(dim));
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable sg = new GradientDrawable();
        sg.setColor(0xFF1C1C1E);
        sg.setCornerRadii(new float[]{dp(20), dp(20), dp(20), dp(20), 0, 0, 0, 0});
        sheet.setBackground(sg);
        sheet.setPadding(0, dp(14), 0, dp(24));
        sheet.setClickable(true);
        TextView t = new TextView(this);
        t.setText("Effects");
        t.setTextColor(Color.WHITE);
        t.setTextSize(18);
        t.setTypeface(null, Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        sheet.addView(t, new LinearLayout.LayoutParams(-1, dp(36)));
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        final LinearLayout row = new LinearLayout(this);
        row.setPadding(dp(10), dp(10), dp(10), dp(4));
        final ArrayList<TextView> chips = new ArrayList<>();
        for (final int idx : FX_ORDER) {
            final TextView c = new TextView(this);
            c.setText(idx == ShortFx.ULTRA ? "4K" : ShortFx.NAMES[idx]);
            c.setTextSize(13);
            c.setGravity(Gravity.CENTER);
            c.setPadding(dp(16), 0, dp(16), 0);
            c.setTag(idx);
            c.setOnClickListener(v -> {
                fxIdx = idx;
                stage.invalidate();
                for (TextView o : chips) paintChip(o, (Integer) o.getTag() == fxIdx);
            });
            paintChip(c, idx == fxIdx);
            chips.add(c);
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, dp(38));
            cl.setMargins(dp(4), 0, dp(4), 0);
            row.addView(c, cl);
        }
        hs.addView(row);
        sheet.addView(hs, new LinearLayout.LayoutParams(-1, -2));
        dim.addView(sheet, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        root.addView(dim, new FrameLayout.LayoutParams(-1, -1));
    }

    private void paintChip(TextView c, boolean on) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(19));
        g.setColor(on ? BLUE : 0xFF2B2B2D);
        c.setBackground(g);
        c.setTextColor(Color.WHITE);
    }

    // ───────────── Canvas ─────────────

    private void openCanvas() {
        final FrameLayout dim = new FrameLayout(this);
        dim.setClickable(true);
        dim.setOnClickListener(v -> root.removeView(dim));

        final int screenH = getResources().getDisplayMetrics().heightPixels;
        final int minH = Math.round(screenH * 0.30f);
        final int maxH = Math.round(screenH * 0.92f);
        final int[] curH = {Math.round(screenH * 0.78f)};      // ৩নং ছবির লাল দাগ পর্যন্ত

        final LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable sg = new GradientDrawable();
        sg.setColor(Color.WHITE);
        sg.setCornerRadii(new float[]{dp(24), dp(24), dp(24), dp(24), 0, 0, 0, 0});
        sheet.setBackground(sg);
        sheet.setClickable(true);

        // হেডার চেপে ধরে উপরে-নিচে টানলে শিট ওঠানামা করে
        FrameLayout head = new FrameLayout(this);
        head.setPadding(dp(16), dp(14), dp(16), dp(6));
        TextView x = new TextView(this);
        x.setText("\u2715");
        x.setTextSize(22);
        x.setTextColor(Color.BLACK);
        x.setOnClickListener(v -> root.removeView(dim));
        head.addView(x, new FrameLayout.LayoutParams(-2, -2, Gravity.START));
        TextView title = new TextView(this);
        title.setText("Background");
        title.setTextSize(20);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(Color.BLACK);
        head.addView(title, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        View grab = new View(this);
        GradientDrawable gg = new GradientDrawable();
        gg.setColor(0xFFCCCCCC); gg.setCornerRadius(dp(3));
        grab.setBackground(gg);
        head.addView(grab, new FrameLayout.LayoutParams(dp(40), dp(4), Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        final float[] dragY = {0};
        head.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: dragY[0] = e.getRawY(); return true;
                case MotionEvent.ACTION_MOVE: {
                    float dy = dragY[0] - e.getRawY();       // উপরে টানলে বড়
                    dragY[0] = e.getRawY();
                    curH[0] = Math.max(dp(90), Math.min(maxH, Math.round(curH[0] + dy)));
                    ViewGroup.LayoutParams lp = sheet.getLayoutParams();
                    lp.height = curH[0];
                    sheet.setLayoutParams(lp);
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (curH[0] < minH) root.removeView(dim);   // একেবারে নিচে নামালে বন্ধ
                    return true;
            }
            return false;
        });
        sheet.addView(head, new LinearLayout.LayoutParams(-1, -2));

        // Background-এর নিচে রঙের বৃত্ত: আগে ডিফল্ট রং, তার পর Assets-এর canvas ছবি
        RecyclerView dots = new RecyclerView(this);
        dots.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(this, RecyclerView.HORIZONTAL, false));
        dots.setPadding(dp(10), dp(8), dp(10), dp(8));
        dots.setClipToPadding(false);
        dots.setAdapter(new CanvasDotAdapter());
        sheet.addView(dots, new LinearLayout.LayoutParams(-1, dp(76)));

        // এর নিচে ফোনের মেইন গ্যালারি (Create memory-র নিচের গ্যালারির মতো)
        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new GridLayoutManager(this, 3));
        GalleryAdapter ga = new GalleryAdapter();
        galleryAdapter = ga;
        rv.setAdapter(ga);
        sheet.addView(rv, new LinearLayout.LayoutParams(-1, 0, 1f));

        if (ContextCompat.checkSelfPermission(this, galleryPerm()) == PackageManager.PERMISSION_GRANTED) {
            if (!galleryLoaded) loadGallery();
        } else {
            permLauncher.launch(galleryPerm());
        }

        dim.addView(sheet, new FrameLayout.LayoutParams(-1, curH[0], Gravity.BOTTOM));
        root.addView(dim, new FrameLayout.LayoutParams(-1, -1));
    }

    private String galleryPerm() {
        return Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private void loadGallery() {
        new Thread(() -> {
            ArrayList<Uri> tmp = new ArrayList<>();
            Uri base = Build.VERSION.SDK_INT >= 29
                    ? MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                    : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            try (Cursor c = getContentResolver().query(base, new String[]{MediaStore.Images.Media._ID}, null, null,
                    MediaStore.Images.Media.DATE_ADDED + " DESC")) {
                while (c != null && c.moveToNext()) tmp.add(ContentUris.withAppendedId(base, c.getLong(0)));
            } catch (Exception ignored) { }
            main.post(() -> {
                galleryUris.clear(); galleryUris.addAll(tmp);
                galleryLoaded = true;
                if (galleryAdapter != null) galleryAdapter.notifyDataSetChanged();
            });
        }).start();
    }

    /** ফোনের গ্যালারি গ্রিড — ছবিতে চাপলে সেটাই ক্যানভাসের ব্যাকগ্রাউন্ড */
    private final class GalleryAdapter extends RecyclerView.Adapter<GalleryAdapter.VH> {
        final class VH extends RecyclerView.ViewHolder {
            final ImageView iv;
            VH(ImageView v) { super(v); iv = v; }
        }

        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p, int t) {
            int s = getResources().getDisplayMetrics().widthPixels / 3;
            ImageView iv = new ImageView(MemoryEditActivity.this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setLayoutParams(new RecyclerView.LayoutParams(s, s));
            iv.setPadding(1, 1, 1, 1);
            return new VH(iv);
        }

        @Override public void onBindViewHolder(@NonNull final VH h, int pos) {
            final Uri u = galleryUris.get(pos);
            Glide.with(h.iv).load(u).placeholder(new ColorDrawable(0xFFEEEEEE)).into(h.iv);
            h.iv.setOnClickListener(v -> new Thread(() -> {
                final Bitmap full = decode(u, 1600);
                main.post(() -> { if (full != null) stage.setBgBitmap(full); });
            }).start());
        }

        @Override public int getItemCount() { return galleryUris.size(); }
    }

    /** রঙের বৃত্ত: প্রথমে GRADS (ডিফল্ট রং), তার পর assets/canvas/canvas_N.webp */
    private final class CanvasDotAdapter extends RecyclerView.Adapter<CanvasDotAdapter.VH> {
        final class VH extends RecyclerView.ViewHolder {
            final ImageView iv;
            VH(ImageView v) { super(v); iv = v; }
        }

        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p, int t) {
            ImageView iv = new ImageView(MemoryEditActivity.this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(dp(52), dp(52));
            lp.setMargins(dp(6), dp(4), dp(6), dp(4));
            iv.setLayoutParams(lp);
            return new VH(iv);
        }

        @Override public void onBindViewHolder(@NonNull final VH h, int pos) {
            if (pos < GRADS.length) {
                final int[] gcol = GRADS[pos];
                GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                        gcol.length == 1 ? new int[]{gcol[0], gcol[0]} : gcol);
                g.setShape(GradientDrawable.OVAL);
                h.iv.setTag(null);
                h.iv.setImageDrawable(g);
                h.iv.setBackground(null);
                h.iv.setClipToOutline(false);
                h.iv.setOnClickListener(v -> stage.setBgGradient(gcol));
                return;
            }
            final String path = "canvas/canvas_" + (pos - GRADS.length + 1) + ".webp";
            h.iv.setImageDrawable(null);
            h.iv.setTag(path);
            GradientDrawable oval = new GradientDrawable();
            oval.setShape(GradientDrawable.OVAL);
            oval.setColor(0xFFEEEEEE);
            h.iv.setBackground(oval);
            h.iv.setClipToOutline(true);                       // ছবিও গোল বৃত্তে কাটা
            new Thread(() -> {
                final Bitmap b = decodeAsset(path, 160);
                main.post(() -> { if (path.equals(h.iv.getTag())) h.iv.setImageBitmap(b); });
            }).start();
            h.iv.setOnClickListener(v -> new Thread(() -> {
                final Bitmap full = decodeAsset(path, 1600);
                main.post(() -> { if (full != null) stage.setBgBitmap(full); });
            }).start());
        }

        @Override public int getItemCount() { return GRADS.length + 74; }
    }

    // ───────────── 3-dot মেনু: Save To Gallery · Add A.I Level · Location · Feeling & Activity ─────────────

    private void showMoreMenu(View anchor) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF262626); bg.setCornerRadius(dp(14));
        box.setBackground(bg);
        box.setPadding(dp(6), dp(6), dp(6), dp(6));
        final PopupWindow pw = new PopupWindow(box, dp(220), -2, true);
        pw.setElevation(dp(8));
        pw.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        box.addView(menuRow(R.drawable.ic_rm_download, "Save To Gallery", false, () -> { pw.dismiss(); saveToGallery(); }));
        box.addView(menuRow(R.drawable.ic_ai_label, "Add A.I Level", aiLevel, () -> {
            pw.dismiss();
            aiLevel = !aiLevel;
            TopToast.show(this, aiLevel ? "A.I Level added" : "A.I Level removed");
        }));
        // A.I Level-এর নিচে: Location · Feeling & Activity
        box.addView(menuRow(ICON_LOCATION, memLocation.isEmpty() ? "Location" : memLocation,
                !memLocation.isEmpty(), () -> {
            pw.dismiss();
            Intent i = new Intent(this, LocationActivity.class);
            i.putExtra(LocationActivity.EXTRA_LOCATION, memLocation);
            placeLauncher.launch(i);
        }));
        View feelRow = menuRow(ICON_FEELING, memFeeling.isEmpty() ? "Feeling & Activity" : memFeeling,
                !memFeeling.isEmpty(), () -> {
            pw.dismiss();
            feelLauncher.launch(new Intent(this, FeelingActivity.class));
        });
        box.addView(feelRow);
        pw.showAsDropDown(anchor, -dp(180), dp(4));
    }

    private View menuRow(int icon, String label, boolean on, Runnable click) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(10), 0, dp(10), 0);
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        iv.setColorFilter(on ? 0xFF5B9DFF : Color.WHITE, PorterDuff.Mode.SRC_IN);
        r.addView(iv, new LinearLayout.LayoutParams(dp(20), dp(20)));          // আইকন ছোট, বামে
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(15);
        t.setTextColor(on ? 0xFF5B9DFF : Color.WHITE);
        t.setPadding(dp(12), 0, 0, 0);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        r.setOnClickListener(v -> click.run());
        r.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(46)));
        return r;
    }

    /** Save To Gallery: ক্যানভাস ছবি (image format) হিসেবে ফোনে সেভ */
    private void saveToGallery() {
        if (busy || editor.isOpen()) return;
        stage.select(-1);
        final int[] sz = outSize();
        new Thread(() -> {
            boolean ok = false;
            try {
                Bitmap bm = stage.render(sz[0], sz[1]);
                String name = "FamilyBook_" + System.currentTimeMillis() + ".png";
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                    cv.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                    cv.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FamilyBook");
                    Uri u = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                    if (u != null) {
                        try (java.io.OutputStream os = getContentResolver().openOutputStream(u)) {
                            ok = bm.compress(Bitmap.CompressFormat.PNG, 100, os);
                        }
                    }
                } else {
                    File dir = new File(android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_PICTURES), "FamilyBook");
                    dir.mkdirs();
                    File f = new File(dir, name);
                    try (FileOutputStream fo = new FileOutputStream(f)) { ok = bm.compress(Bitmap.CompressFormat.PNG, 100, fo); }
                    android.media.MediaScannerConnection.scanFile(this, new String[]{f.getAbsolutePath()}, null, null);
                }
                bm.recycle();
            } catch (Throwable ignored) { }
            final boolean done = ok;
            main.post(() -> TopToast.show(this, done ? "Saved to gallery" : "Couldn't save"));
        }).start();
    }

    // ───────────── স্টেজ ভিউ ─────────────

    private final class MemStage extends View {
        private int[] gradColors = GRADS[2];
        private Bitmap bgBmp;
        private float bgScale = 1f, bgRot = 0f, bgCx = 0.5f, bgCy = 0.5f;
        private int sel = -1;
        private final Paint dash = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint xp = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float d = getResources().getDisplayMetrics().density;
        // mode: 0 কিছু না · 1 লেয়ার টানা · 2 লেয়ার পিঞ্চ · 3 ব্যাকগ্রাউন্ড টানা · 4 ব্যাকগ্রাউন্ড পিঞ্চ
        //       5 বাঁ-নিচের ডট (সাইজ) · 6 ডান ডট (প্যাডিং) · 7 নিচের ডট (ধীরে ঘোরানো)
        private int mode = 0;
        private float lx, ly, d0, a0, s0, r0, downX, downY;
        private boolean moved = false, wasSel = false;

        MemStage() {
            super(MemoryEditActivity.this);
            dash.setStyle(Paint.Style.STROKE);
            dash.setStrokeWidth(1.5f * d);
            dash.setColor(0xDDFFFFFF);
            xp.setColor(Color.WHITE);
            xp.setStyle(Paint.Style.STROKE);
            xp.setStrokeWidth(2f * d);
            xp.setStrokeCap(Paint.Cap.ROUND);
        }

        void setBgGradient(int[] c) { gradColors = c; bgBmp = null; bgScale = 1f; bgRot = 0f; bgCx = 0.5f; bgCy = 0.5f; invalidate(); }

        void setBgBitmap(Bitmap b) { bgBmp = b; bgScale = 1f; bgRot = 0f; bgCx = 0.5f; bgCy = 0.5f; invalidate(); }

        void select(int i) {
            sel = i;
            btnOrder.setVisibility(i >= 0 ? View.VISIBLE : View.GONE);
            invalidate();
        }

        /** Front = লেয়ার সামনে (উপরে), Back = পেছনে (নিচে) */
        void moveSel(boolean front) {
            if (sel < 0 || sel >= items.size()) return;
            int to = front ? sel + 1 : sel - 1;
            if (to < 0 || to >= items.size()) return;
            Collections.swap(items, sel, to);
            sel = to;
            invalidate();
        }

        // ---- মাপ: বক্সের অর্ধেক প্রস্থ/উচ্চতা (প্যাডিংসহ)
        private float padPx(TextItem it, float w) {
            if (it.bmp != null) return 8f * d;
            return Math.max(2f * d, it.size * w * 0.22f * it.pad) + 4f * d;
        }

        /** লেয়ারের স্থানীয় বিন্দু (lx,ly) → স্ক্রিন */
        private float[] pt(TextItem it, float lx0, float ly0, float w, float h) {
            double th = Math.toRadians(it.rot);
            return new float[]{
                    it.cx * w + (float) (lx0 * Math.cos(th) - ly0 * Math.sin(th)),
                    it.cy * h + (float) (lx0 * Math.sin(th) + ly0 * Math.cos(th))};
        }

        private float[] handle(int which, float w, float h) {
            TextItem it = items.get(sel);
            TextRender.Lay l = TextRender.layout(getContext(), it, w, 0);
            float pd = padPx(it, w);
            float hw = l.w / 2f + pd, hh = l.h / 2f + pd;
            switch (which) {
                case 0: return pt(it, -hw, -hh, w, h);            // ✕
                case 1: return pt(it, -hw, hh, w, h);             // সাইজ
                case 2: return pt(it, hw, 0, w, h);               // প্যাডিং
                default: return pt(it, 0, hh + 30f * d, w, h);    // ঘোরানো
            }
        }

        private void drawContent(Canvas c, int w, int h) {
            drawBg(c, w, h);
            for (TextItem it : items) TextRender.draw(c, getContext(), it, it.cx * w, it.cy * h, w);
            drawMentions(c, w);
        }

        /**
         * editing = true → প্রিভিউ (রঙের ইফেক্ট + Glitch-এর আভাস এখানেই দেখায়)।
         * editing = false → এক্সপোর্ট ছবি: ইফেক্ট এখানে বসে না, Transformer-এ ShortFx-এর আসল GL ইফেক্ট বসে।
         */
        void drawAll(Canvas c, int w, int h, boolean editing) {
            if (editing && fxIdx == 4) {                       // Glitch প্রিভিউ: লাল/নীল চ্যানেল আলাদা
                float dx = 3f * d;
                c.drawColor(Color.BLACK);
                Paint pr = new Paint();
                pr.setColorFilter(new ColorMatrixColorFilter(new float[]{1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0}));
                c.saveLayer(0, 0, w, h, pr);
                c.translate(dx, 0);
                drawContent(c, w, h);
                c.restore();
                Paint pc = new Paint();
                pc.setColorFilter(new ColorMatrixColorFilter(new float[]{0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1, 0}));
                pc.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
                c.saveLayer(0, 0, w, h, pc);
                c.translate(-dx, 0);
                drawContent(c, w, h);
                c.restore();
            } else if (editing && fxIdx > 0) {                 // রঙ-ম্যাট্রিক্স ইফেক্ট
                float[] m = ShortFx.color16(fxIdx);
                float[] a = new float[20];
                for (int row = 0; row < 4; row++)
                    for (int col = 0; col < 4; col++) a[row * 5 + col] = m[col * 4 + row];
                Paint fxp = new Paint();
                fxp.setColorFilter(new ColorMatrixColorFilter(new ColorMatrix(a)));
                c.saveLayer(0, 0, w, h, fxp);
                drawContent(c, w, h);
                c.restore();
            } else {
                drawContent(c, w, h);
            }
            if (editing && sel >= 0 && sel < items.size()) drawSel(c, w, h);
        }

        /** উপরে বোল্ড ছোট নীল নাম (ছবি ছাড়া) */
        private void drawMentions(Canvas c, int w) {
            if (mentions.isEmpty()) return;
            StringBuilder sb = new StringBuilder();
            for (String n : mentions) { if (sb.length() > 0) sb.append("   "); sb.append('@').append(n); }
            TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            tp.setTypeface(Typeface.DEFAULT_BOLD);
            tp.setColor(0xFF2D88FF);
            tp.setTextSize(w * 0.042f);
            tp.setShadowLayer(3f * d, 0, 0, 0x88000000);
            int tw = (int) (w * 0.9f);
            StaticLayout sl = StaticLayout.Builder.obtain(sb, 0, sb.length(), tp, tw)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER).build();
            c.save();
            c.translate(w * 0.05f, w * 0.05f);
            sl.draw(c);
            c.restore();
        }

        private void drawSel(Canvas c, int w, int h) {
            TextItem it = items.get(sel);
            TextRender.Lay l = TextRender.layout(getContext(), it, w, 0);
            float pd = padPx(it, w);
            float hw = l.w / 2f + pd, hh = l.h / 2f + pd;
            c.save();
            c.translate(it.cx * w, it.cy * h);
            c.rotate(it.rot);
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(0x33FFFFFF);
            c.drawRect(-hw, -hh, hw, hh, fill);
            c.drawRect(-hw, -hh, hw, hh, dash);
            // ঘোরানোর ধনুক (নিচে মাঝে) + ডট
            c.drawArc(-12f * d, hh + 18f * d, 12f * d, hh + 42f * d, 20, 140, false, xp);
            fill.setColor(Color.WHITE);
            if (it.bmp == null) c.drawCircle(hw, 0, 11f * d, fill);          // প্যাডিং
            c.drawCircle(-hw, hh, 11f * d, fill);                             // সাইজ
            // ✕ বাটন (ডিলিট)
            float r = 12f * d;
            fill.setColor(0xFF000000);
            c.drawCircle(-hw, -hh, r, fill);
            c.drawCircle(-hw, -hh, r, xp);
            float q = r * 0.4f;
            c.drawLine(-hw - q, -hh - q, -hw + q, -hh + q, xp);
            c.drawLine(-hw - q, -hh + q, -hw + q, -hh - q, xp);
            c.restore();
        }

        private void drawBg(Canvas c, int w, int h) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            c.drawColor(0xFF0A0A0A);                              // ক্যানভাস ছোট করলে পেছনের অংশ
            // ডিফল্ট ক্যানভাসও আঙুলে ছোট/বড়/৩৬০° ঘোরে
            c.save();
            c.translate(bgCx * w, bgCy * h);
            c.rotate(bgRot);
            c.scale(bgScale, bgScale);
            if (gradColors.length == 1 || (gradColors.length == 2 && gradColors[0] == gradColors[1])) {
                p.setColor(gradColors[0]);
                c.drawRect(-w / 2f, -h / 2f, w / 2f, h / 2f, p);
            } else {
                p.setShader(new LinearGradient(0, -h / 2f, 0, h / 2f, gradColors, null, Shader.TileMode.CLAMP));
                c.drawRect(-w / 2f, -h / 2f, w / 2f, h / 2f, p);
                p.setShader(null);
            }
            c.restore();
            if (bgBmp != null) {
                float cover = Math.max(w / (float) bgBmp.getWidth(), h / (float) bgBmp.getHeight()) * bgScale;
                c.save();
                c.translate(bgCx * w, bgCy * h);
                c.rotate(bgRot);
                c.scale(cover, cover);
                c.drawBitmap(bgBmp, -bgBmp.getWidth() / 2f, -bgBmp.getHeight() / 2f, p);
                c.restore();
            }
        }

        Bitmap render(int w, int h) {
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            drawAll(new Canvas(b), w, h, false);
            return b;
        }

        @Override protected void onDraw(Canvas c) {
            drawAll(c, getWidth(), getHeight(), true);
        }

        private boolean near(float[] p, float x, float y) {
            return Math.hypot(x - p[0], y - p[1]) <= 24f * d;
        }

        private int hit(float x, float y) {
            float w = getWidth(), h = getHeight();
            for (int i = items.size() - 1; i >= 0; i--) {
                TextItem it = items.get(i);
                if (it.empty()) continue;
                TextRender.Lay l = TextRender.layout(getContext(), it, w, 0);
                double th = Math.toRadians(it.rot);
                float dx = x - it.cx * w, dy = y - it.cy * h;
                float px = (float) (dx * Math.cos(th) + dy * Math.sin(th));
                float py = (float) (-dx * Math.sin(th) + dy * Math.cos(th));
                float pd = padPx(it, w) + 4f * d;
                if (Math.abs(px) <= l.w / 2f + pd && Math.abs(py) <= l.h / 2f + pd) return i;
            }
            return -1;
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            float w = Math.max(1, getWidth()), h = Math.max(1, getHeight());
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    getParent().requestDisallowInterceptTouchEvent(true);
                    downX = lx = e.getX(); downY = ly = e.getY();
                    moved = false;
                    if (sel >= 0 && sel < items.size()) {
                        TextItem it = items.get(sel);
                        if (near(handle(0, w, h), downX, downY)) {           // ✕
                            items.remove(sel);
                            select(-1);
                            mode = 0;
                            return true;
                        }
                        if (near(handle(3, w, h), downX, downY)) {           // ধীরে ঘোরানো
                            mode = 7;
                            float[] cpt = {it.cx * w, it.cy * h};
                            a0 = (float) Math.toDegrees(Math.atan2(downY - cpt[1], downX - cpt[0]));
                            r0 = it.rot;
                            return true;
                        }
                        if (near(handle(1, w, h), downX, downY)) { mode = 5; return true; }
                        if (it.bmp == null && near(handle(2, w, h), downX, downY)) { mode = 6; return true; }
                    }
                    int i = hit(e.getX(), e.getY());
                    wasSel = i >= 0 && i == sel;
                    if (i >= 0) { select(i); mode = 1; }
                    else { select(-1); mode = 3; }
                    return true;
                }
                case MotionEvent.ACTION_POINTER_DOWN:
                    if (e.getPointerCount() == 2 && mode <= 4) {
                        d0 = Math.max(1f, dist(e));
                        a0 = ang(e);
                        if (sel >= 0 && sel < items.size()) { mode = 2; s0 = items.get(sel).size; r0 = items.get(sel).rot; }
                        else { mode = 4; s0 = bgScale; r0 = bgRot; }
                        moved = true;
                    }
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    if (Math.hypot(e.getX() - downX, e.getY() - downY) > 8f * d) moved = true;
                    if (mode >= 5 && mode <= 7 && sel >= 0 && sel < items.size()) {
                        TextItem it = items.get(sel);
                        float dx = e.getX() - lx;
                        if (mode == 5) {                // বামে টানলে বড়, ডানে টানলে ছোট
                            it.size = Math.max(0.02f, Math.min(3f, it.size * (float) Math.exp(-dx / (w * 0.35f))));
                        } else if (mode == 6) {         // ডানে = প্যাডিং বাড়ে, বামে = কমে (০ = গা-ঘেঁষা বক্স)
                            it.pad = Math.max(0f, Math.min(5f, it.pad + dx / (w * 0.12f)));
                        } else {                        // ধীরে ঘোরানো; সোজা (০/৯০/১৮০/২৭০) হলে নিজে থামে
                            float cur = (float) Math.toDegrees(Math.atan2(e.getY() - it.cy * h, e.getX() - it.cx * w));
                            float raw = r0 + 0.3f * (cur - a0);
                            float near90 = Math.round(raw / 90f) * 90f;
                            it.rot = Math.abs(raw - near90) < 4f ? near90 : raw;
                        }
                        lx = e.getX(); ly = e.getY();
                        invalidate();
                        return true;
                    }
                    if (mode == 1 && e.getPointerCount() == 1 && sel >= 0 && sel < items.size()) {
                        TextItem it = items.get(sel);
                        it.cx = Math.max(0f, Math.min(1f, it.cx + (e.getX() - lx) / w));
                        it.cy = Math.max(0f, Math.min(1f, it.cy + (e.getY() - ly) / h));
                        lx = e.getX(); ly = e.getY();
                        invalidate();
                    } else if (mode == 3 && e.getPointerCount() == 1) {
                        bgCx += (e.getX() - lx) / w;
                        bgCy += (e.getY() - ly) / h;
                        lx = e.getX(); ly = e.getY();
                        invalidate();
                    } else if (mode == 2 && e.getPointerCount() >= 2 && sel >= 0 && sel < items.size()) {
                        TextItem it = items.get(sel);
                        it.size = Math.max(0.02f, Math.min(3f, s0 * dist(e) / d0));
                        it.rot = r0 + (ang(e) - a0);
                        invalidate();
                    } else if (mode == 4 && e.getPointerCount() >= 2) {
                        bgScale = Math.max(0.05f, Math.min(30f, s0 * dist(e) / d0));
                        bgRot = r0 + (ang(e) - a0);
                        invalidate();
                    }
                    return true;
                }
                case MotionEvent.ACTION_POINTER_UP:
                    if (mode == 2 || mode == 4) {
                        mode = mode == 2 ? 1 : 3;
                        int k = e.getActionIndex() == 0 ? 1 : 0;
                        lx = e.getX(k); ly = e.getY(k);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    // সিলেক্ট করা টেক্সটে সরানো ছাড়া ট্যাপ করলে এডিট
                    if (mode == 1 && !moved && wasSel && sel >= 0 && sel < items.size() && items.get(sel).bmp == null)
                        openText(sel);
                    mode = 0;
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    mode = 0;
                    return true;
                default: return true;
            }
        }

        private float dist(MotionEvent e) {
            float dx = e.getX(0) - e.getX(1), dy = e.getY(0) - e.getY(1);
            return (float) Math.sqrt(dx * dx + dy * dy);
        }

        private float ang(MotionEvent e) {
            return (float) Math.toDegrees(Math.atan2(e.getY(1) - e.getY(0), e.getX(1) - e.getX(0)));
        }
    }

    // ───────────── Share → ছবি → ৫ সেকেন্ডের mp4 → MemorySettingsActivity ─────────────

    private void exportNow() {
        if (busy || editor.isOpen()) return;
        if (MemoryUploader.busy) { TopToast.show(this, "Previous Memory is still uploading"); return; }
        busy = true;
        tvBusy.setText("Processing…");
        tvBusy.setVisibility(View.VISIBLE);
        stage.select(-1);
        MusicPlayer.stop();
        final int[] sz = outSize();
        final int w = sz[0], h = sz[1];
        new Thread(() -> {
            try {
                Bitmap bm = stage.render(w, h);
                File png = new File(getCacheDir(), "memory_edit_" + System.currentTimeMillis() + ".png");
                try (FileOutputStream fo = new FileOutputStream(png)) { bm.compress(Bitmap.CompressFormat.PNG, 100, fo); }
                bm.recycle();
                File dir = new File(getCacheDir(), "shorts");   // FileProvider-এর cache/shorts/ পথ
                dir.mkdirs();
                final File out = new File(dir, "memory_edit_" + System.currentTimeMillis() + ".mp4");
                main.post(() -> {
                    if (mUrl == null) startTransform(png, out, null);
                    else resolveMusic(mf -> startTransform(png, out, mf));
                });
            } catch (Throwable t) {
                main.post(() -> fail());
            }
        }).start();
    }

    @OptIn(markerClass = UnstableApi.class)
    private void startTransform(File png, final File out, File music) {
        try {
            List<Effect> vfx = new ArrayList<>();
            Effect fx = ShortFx.gl(fxIdx);                      // Glitch · 4K · iPhone · B&W … সব ইফেক্ট
            if (fx != null) vfx.add(fx);
            EditedMediaItem em = new EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(png)))
                    .setDurationUs(5_000_000L)
                    .setFrameRate(30)
                    .setEffects(new Effects(new ArrayList<androidx.media3.common.audio.AudioProcessor>(), vfx))
                    .build();
            List<EditedMediaItem> one = new ArrayList<>();
            one.add(em);
            List<EditedMediaItemSequence> seqs = new ArrayList<>();
            seqs.add(new EditedMediaItemSequence(one));
            if (music != null) {
                MediaItem mi = new MediaItem.Builder().setUri(Uri.fromFile(music))
                        .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(mStart).setEndPositionMs(mStart + 5000L).build()).build();
                List<EditedMediaItem> ml = new ArrayList<>();
                ml.add(new EditedMediaItem.Builder(mi).setRemoveVideo(true).build());
                seqs.add(new EditedMediaItemSequence(ml, true));
            }
            transformer = new Transformer.Builder(this)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .addListener(new Transformer.Listener() {
                        @Override public void onCompleted(Composition c, ExportResult r) {
                            busy = false;
                            tvBusy.setVisibility(View.GONE);
                            Uri u = FileProvider.getUriForFile(MemoryEditActivity.this, getPackageName() + ".fileprovider", out);
                            // সেটিংস পর্দা নেই: সরাসরি আপলোড শুরু → Home ফিডে "Sharing…" কার্ড
                            MemoryUploader.postDefault(MemoryEditActivity.this, u, memLocation, memFeeling, aiLevel,
                                    mUrl == null ? null : mTitle);
                            startActivity(new Intent(MemoryEditActivity.this, HomeActivity.class)
                                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                            finish();
                        }
                        @Override public void onError(Composition c, ExportResult r, ExportException e) { fail(); }
                    }).build();
            Composition.Builder cb = new Composition.Builder(seqs);
            if (music != null) cb.experimentalSetForceAudioTrack(true);
            transformer.start(cb.build(), out.getAbsolutePath());
        } catch (Throwable t) {
            fail();
        }
    }

    private void fail() {
        busy = false;
        tvBusy.setVisibility(View.GONE);
        TopToast.show(this, "Couldn't prepare the Memory");
    }

    @Override public void onBackPressed() {
        if (stickers != null && stickers.handleBack()) return;
        if (editor.isOpen()) { editor.finish(); return; }
        super.onBackPressed();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        MusicPlayer.stop();
        if (transformer != null) { try { transformer.cancel(); } catch (Throwable ignored) { } }
    }
}
