package com.familybook.app;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/**
 * সাউন্ড (মিউজিক) পেজ — Short-এর সাউন্ড বাটনে চাপলে এখানে আসে।
 *  - উপরে: গানের ছবি, নাম, শিল্পী ও Play/Pause (এখানে গানটা বাজানো যায়)
 *  - নিচে: এই গান দিয়ে আর যারা Shorts বানিয়েছে সেগুলোর গ্রিড; চাপলে ফুল-স্ক্রিন ভিউয়ারে খোলে
 */
public class ShortSoundActivity extends AppCompatActivity {

    private static final String X_TITLE = "title", X_ARTIST = "artist", X_COVER = "cover", X_URL = "url", X_SHORT = "short_id", X_ORIG = "orig_id", X_THUMB = "orig_thumb", X_VKEY = "orig_vkey";
    private static final int COLS = 3;

    static void open(Context c, PostMusic.Info m, String currentShortId) {
        Intent i = new Intent(c, ShortSoundActivity.class);
        i.putExtra(X_TITLE, m.title);
        i.putExtra(X_ARTIST, m.artist);
        i.putExtra(X_COVER, m.cover);
        i.putExtra(X_URL, m.url);
        i.putExtra(X_SHORT, currentShortId);
        c.startActivity(i);
    }

    static void openOriginal(Context c, ShortStore.Info src) {
        Intent i = new Intent(c, ShortSoundActivity.class);
        i.putExtra(X_ORIG, src.id);
        i.putExtra(X_THUMB, src.thumbKey);
        i.putExtra(X_VKEY, src.videoKey);
        i.putExtra(X_SHORT, src.id);
        c.startActivity(i);
    }

    private String title, artist, cover, url, key, origId, origThumb, origVideoKey;
    private ImageView coverView, playIcon;
    private TextView countText, emptyText;
    private View emptyBox;
    private RecyclerView grid;
    private final List<ShortStore.Info> shorts = new ArrayList<>();
    private GridAdapter adapter;

    private final MusicPlayer.Listener musicListener = (k, playing) -> runOnUiThread(this::refreshPlay);

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        title = getIntent().getStringExtra(X_TITLE);
        artist = getIntent().getStringExtra(X_ARTIST);
        cover = getIntent().getStringExtra(X_COVER);
        url = getIntent().getStringExtra(X_URL);
        origId = getIntent().getStringExtra(X_ORIG);
        origThumb = getIntent().getStringExtra(X_THUMB);
        origVideoKey = getIntent().getStringExtra(X_VKEY);
        if (origId != null) { title = "Original_sound.mp3"; url = "orig:" + origId; }   // ভিডিওর নিজের (অরিজিনাল) সাউন্ড
        if (url == null || url.isEmpty()) { finish(); return; }
        key = "sound:" + url;

        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        // ---- উপরের বার: ← Sound
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageView back = new ImageView(this);
        back.setImageResource(R.drawable.ic_back_left);
        back.setColorFilter(Color.BLACK);
        back.setPadding(dp(14), dp(14), dp(14), dp(14));
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(dp(52), dp(52)));
        TextView bar = new TextView(this);
        bar.setText("Sound");
        bar.setTextColor(Color.BLACK);
        bar.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        bar.setTypeface(null, Typeface.BOLD);
        top.addView(bar, new LinearLayout.LayoutParams(-2, -2));
        root.addView(top, new LinearLayout.LayoutParams(-1, dp(52)));

        // ---- গানের তথ্য + Play
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(16), dp(8), dp(16), dp(16));

        coverView = new ImageView(this);
        coverView.setClipToOutline(true);
        coverView.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(12)); }
        });
        coverView.setBackgroundColor(0xFFE4E6EB);
        head.addView(coverView, new LinearLayout.LayoutParams(dp(88), dp(88)));
        if (origId != null) {
            coverView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            if (origThumb != null && !origThumb.isEmpty()) AvatarLoader.loadKey(coverView, origThumb);
        } else loadCover();

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(0, -2, 1f);
        il.setMarginStart(dp(14));
        il.setMarginEnd(dp(8));
        head.addView(info, il);

        TextView t = new TextView(this);
        t.setText(title == null || title.isEmpty() ? "Original sound" : title);
        t.setTextColor(Color.BLACK);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        t.setTypeface(null, Typeface.BOLD);
        t.setMaxLines(2);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(t, new LinearLayout.LayoutParams(-1, -2));

        if (artist != null && !artist.isEmpty()) {
            TextView a = new TextView(this);
            a.setText(artist);
            a.setTextColor(0xFF65676B);
            a.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            a.setSingleLine(true);
            a.setEllipsize(android.text.TextUtils.TruncateAt.END);
            info.addView(a, new LinearLayout.LayoutParams(-1, -2));
        }

        FrameLayout play = new FrameLayout(this);
        GradientDrawable pg = new GradientDrawable();
        pg.setShape(GradientDrawable.OVAL);
        pg.setColor(0xFF1877F2);
        play.setBackground(pg);
        playIcon = new ImageView(this);
        playIcon.setColorFilter(Color.WHITE);
        playIcon.setPadding(dp(14), dp(14), dp(14), dp(14));
        play.addView(playIcon, new FrameLayout.LayoutParams(-1, -1));
        play.setOnClickListener(v -> {
            if (!Net.check(this)) return;
            if (origId != null && !key.equals(MusicPlayer.currentKey())) {
                // অরিজিনাল সাউন্ড: ভিডিওর লিংক এনে তার অডিও চালানো
                if (origVideoKey == null) return;
                Media.readUrl(origVideoKey, (u, err) -> {
                    if (isFinishing()) return;
                    if (u == null) { Toast.makeText(this, "সাউন্ড লোড হয়নি", Toast.LENGTH_SHORT).show(); return; }
                    MusicPlayer.play(this, key, u, 0, 0);
                });
                return;
            }
            MusicPlayer.toggle(this, key, url, 0, 0);
        });
        head.addView(play, new LinearLayout.LayoutParams(dp(52), dp(52)));
        root.addView(head, new LinearLayout.LayoutParams(-1, -2));

        View line = new View(this);
        line.setBackgroundColor(0xFFE4E6EB);
        root.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));

        countText = new TextView(this);
        countText.setText("Shorts with this sound");
        countText.setTextColor(0xFF4F5155);
        countText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        countText.setTypeface(null, Typeface.BOLD);
        countText.setPadding(dp(16), dp(12), dp(16), dp(8));
        root.addView(countText, new LinearLayout.LayoutParams(-1, -2));

        // ---- গ্রিড + ফাঁকা অবস্থা
        FrameLayout body = new FrameLayout(this);
        grid = new RecyclerView(this);
        grid.setLayoutManager(new GridLayoutManager(this, COLS));
        grid.setPadding(dp(6), 0, dp(6), dp(12));
        grid.setClipToPadding(false);
        adapter = new GridAdapter();
        grid.setAdapter(adapter);
        body.addView(grid, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout empty = new LinearLayout(this);
        empty.setOrientation(LinearLayout.VERTICAL);
        empty.setGravity(Gravity.CENTER);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_no_shorts);
        logo.setAlpha(0.6f);
        empty.addView(logo, new LinearLayout.LayoutParams(dp(96), dp(96)));
        emptyText = new TextView(this);
        emptyText.setText("Loading…");
        emptyText.setTextColor(0xFF65676B);
        emptyText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        emptyText.setTypeface(null, Typeface.BOLD);
        emptyText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(-2, -2);
        el.topMargin = dp(8);
        empty.addView(emptyText, el);
        emptyBox = empty;
        empty.setVisibility(View.GONE);
        body.addView(empty, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1f));

        setContentView(root);
        MusicPlayer.addListener(musicListener);
        refreshPlay();
        loadShorts();
    }

    @Override
    protected void onDestroy() {
        MusicPlayer.removeListener(musicListener);
        super.onDestroy();
    }

    private void refreshPlay() {
        if (playIcon == null) return;
        playIcon.setImageResource(MusicPlayer.isPlaying(key) ? R.drawable.ic_music_pause : R.drawable.ic_music_play);
    }

    private void loadCover() {
        Bitmap bm = MusicApi.cover(MusicApi.idOf(url));
        if (bm != null) { setCover(bm); return; }
        coverView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        coverView.setPadding(dp(22), dp(22), dp(22), dp(22));
        coverView.setImageResource(R.drawable.ic_music_note);
        coverView.setImageTintList(android.content.res.ColorStateList.valueOf(0xFF8A8D91));
        if (url.startsWith(DeezerApi.PREFIX) && cover != null && !cover.isEmpty()) {
            DeezerApi.loadCover(cover, b -> { if (b != null && !isFinishing()) setCover(b); });
        }
    }

    private void setCover(Bitmap bm) {
        coverView.setPadding(0, 0, 0, 0);
        coverView.setImageTintList(null);
        coverView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        coverView.setImageBitmap(bm);
    }

    // ------------------------------------------------------------ এই গান দিয়ে বানানো Shorts

    private void loadShorts() {
        String filter = origId != null
                ? "or=" + Uri.encode("(id.eq." + origId + ",music_url.eq.orig:" + origId + ")")   // মূল শর্ট + এই সাউন্ড নিয়ে বানানো শর্ট
                : "music_url=eq." + Uri.encode(url);
        SupabaseRest.call("GET", "/rest/v1/shorts?" + filter
                + "&status=neq.deleted&select=" + ShortStore.SELECT
                + "&order=created_at.desc&limit=60", null, null, true, r -> {
            if (isFinishing()) return;
            shorts.clear();
            if (r.ok()) {
                try {
                    JSONArray a = new JSONArray(r.body);
                    for (int i = 0; i < a.length(); i++) {
                        ShortStore.Info s = ShortStore.put(a.getJSONObject(i));
                        if (s != null && s.videoKey != null) shorts.add(s);
                    }
                } catch (Exception ignored) { }
            }
            adapter.notifyDataSetChanged();
            countText.setText(shorts.isEmpty() ? "Shorts with this sound" : shorts.size() + " Shorts with this sound");
            emptyBox.setVisibility(shorts.isEmpty() ? View.VISIBLE : View.GONE);
            emptyText.setText(r.ok() ? "No shorts yet" : "Could not load shorts");
        });
    }

    private void openViewer(int index) {
        ArrayList<String> ids = new ArrayList<>();
        for (ShortStore.Info s : shorts) ids.add(s.id);
        ShortPlayerView.stopAll();
        ShortViewerActivity.open(this, ids, index);
    }

    private final class GridAdapter extends RecyclerView.Adapter<GridAdapter.VH> {
        final class VH extends RecyclerView.ViewHolder {
            final ImageView thumb;
            final TextView views;
            VH(FrameLayout v, ImageView t, TextView tv) { super(v); thumb = t; views = tv; }
        }

        @Override public VH onCreateViewHolder(ViewGroup parent, int type) {
            int w = (getResources().getDisplayMetrics().widthPixels - dp(12)) / COLS - dp(8);
            FrameLayout cell = new FrameLayout(parent.getContext());
            cell.setBackgroundColor(0xFF3A3B3C);
            cell.setClipToOutline(true);
            cell.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(10)); }
            });
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(w, Math.round(w * 1.6f));
            lp.setMargins(dp(4), dp(4), dp(4), dp(4));
            cell.setLayoutParams(lp);
            ImageView th = new ImageView(parent.getContext());
            th.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cell.addView(th, new FrameLayout.LayoutParams(-1, -1));
            TextView tv = new TextView(parent.getContext());
            tv.setTextColor(Color.WHITE);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            tv.setTypeface(null, Typeface.BOLD);
            tv.setShadowLayer(4f, 0f, 1f, 0xAA000000);
            FrameLayout.LayoutParams vp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
            vp.setMargins(dp(8), 0, 0, dp(6));
            cell.addView(tv, vp);
            return new VH(cell, th, tv);
        }

        @Override public void onBindViewHolder(VH h, int pos) {
            ShortStore.Info s = shorts.get(pos);
            h.thumb.setImageDrawable(null);
            if (s.thumbKey != null && !s.thumbKey.isEmpty()) AvatarLoader.loadKey(h.thumb, s.thumbKey);
            h.views.setText("▶ " + PostAdapter.compact((int) s.views));
            h.itemView.setOnClickListener(v -> openViewer(h.getBindingAdapterPosition()));
        }

        @Override public int getItemCount() { return shorts.size(); }
    }
}
