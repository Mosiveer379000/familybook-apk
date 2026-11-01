package com.familybook.app;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * গান বাছাইয়ের স্ক্রিন: Search + For you / Trending।
 * For you = Family Song অ্যাপের ডাটাবেস (স্ক্রিন খুললে ও টেনে নামালে নতুন তালিকা আনে)।
 * Trending = Deezer-এর গ্লোবাল চার্ট (ফ্রি API, ৩০ সেকেন্ডের preview)।
 * গানে চাপলে বাছা হয়, ▶ চাপলে প্রিভিউ।
 */
public class MusicPickerActivity extends AppCompatActivity {

    public static final String EXTRA_TITLE = "music_title";
    public static final String EXTRA_ARTIST = "music_artist";
    public static final String EXTRA_COVER = "music_cover";
    public static final String EXTRA_URL = "music_url";

    private static final int TRENDING_LIMIT = 50;

    private EditText etSearch;
    private TextView tabForYou, tabTrending, tvEmpty;
    private ProgressBar progress;
    private RecyclerView rv;
    private PullRefreshLayout swipe;

    private final List<MusicApi.Track> all = new ArrayList<>();        // Family Song, নতুন আগে (For you)
    private final List<MusicApi.Track> trendingList = new ArrayList<>(); // Deezer চার্ট
    private final List<MusicApi.Track> items = new ArrayList<>();      // এখন যা দেখাচ্ছে
    private boolean trendingTab = false;
    private boolean loadFailed = false;        // For you লোড ব্যর্থ
    private boolean trendingFailed = false;    // Trending লোড ব্যর্থ
    private boolean trendingLoaded = false;
    private boolean fsSpin = false, dzSpin = false;   // মাঝখানের ঘুরন্ত চাকা
    private int loadToken = 0;
    private int dzToken = 0;
    private Adapter adapter;

    private final MusicPlayer.Listener playerListener = (key, playing) -> {
        if (adapter != null) adapter.notifyDataSetChanged();
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_music_picker);

        etSearch = findViewById(R.id.etMusicSearch);
        tabForYou = findViewById(R.id.tabForYou);
        tabTrending = findViewById(R.id.tabTrending);
        tvEmpty = findViewById(R.id.tvMusicEmpty);
        progress = findViewById(R.id.progressMusic);
        rv = findViewById(R.id.rvMusic);
        swipe = findViewById(R.id.swipeMusic);

        adapter = new Adapter();
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);

        findViewById(R.id.btnMusicClose).setOnClickListener(v -> finish());
        tabForYou.setOnClickListener(v -> selectTab(false));
        tabTrending.setOnClickListener(v -> selectTab(true));
        swipe.setOnRefreshListener(() -> {
            if (trendingTab) loadTrending(false);
            else load(false);
        });

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int c, int d) { }
            @Override public void onTextChanged(CharSequence s, int a, int c, int d) { }
            @Override public void afterTextChanged(Editable s) { applyFilter(); }
        });
        etSearch.setOnEditorActionListener((v, id, e) -> id == EditorInfo.IME_ACTION_SEARCH);

        MusicPlayer.addListener(playerListener);
        styleTab(tabForYou, true);
        styleTab(tabTrending, false);
        load(true);
    }

    @Override
    protected void onDestroy() {
        MusicPlayer.removeListener(playerListener);
        loadToken++;
        dzToken++;
        super.onDestroy();
    }

    private void selectTab(boolean trending) {
        trendingTab = trending;
        styleTab(tabForYou, !trending);
        styleTab(tabTrending, trending);
        // Trending প্রথমবার (বা আগে ব্যর্থ হলে) খুললে Deezer থেকে আনে
        if (trending && !trendingLoaded && !dzSpin) loadTrending(true);
        updateProgress();
        applyFilter();
    }

    private void styleTab(TextView t, boolean on) {
        t.setBackgroundResource(on ? R.drawable.bg_music_tab_on : 0);
        t.setTextColor(on ? 0xFF1877F2 : 0xFF050505);
    }

    private void updateProgress() {
        progress.setVisibility((trendingTab ? dzSpin : fsSpin) ? View.VISIBLE : View.GONE);
    }

    /** For you: Family Song থেকে সব গানের তালিকা আনে। firstLoad = স্ক্রিন খোলার সময় (মাঝখানে ঘুরন্ত চাকা) */
    private void load(boolean firstLoad) {
        if (!Net.check(this)) {
            swipe.setRefreshing(false);
            fsSpin = false;
            updateProgress();
            loadFailed = true;
            applyFilter();
            return;
        }
        final int my = ++loadToken;
        fsSpin = firstLoad;
        updateProgress();
        MusicApi.loadSongs((list, failed) -> {
            if (my != loadToken || isFinishing() || isDestroyed()) return;
            fsSpin = false;
            updateProgress();
            swipe.setRefreshing(false);
            loadFailed = failed;
            if (failed && !all.isEmpty()) {      // ব্যর্থ হলে আগের তালিকাই থাকুক
                applyFilter();
                return;
            }
            all.clear();
            all.addAll(list);
            applyFilter();
        });
    }

    /** Trending: Deezer-এর গ্লোবাল চার্ট আনে */
    private void loadTrending(boolean firstLoad) {
        if (!Net.check(this)) {
            swipe.setRefreshing(false);
            dzSpin = false;
            updateProgress();
            trendingFailed = true;
            applyFilter();
            return;
        }
        final int my = ++dzToken;
        dzSpin = firstLoad;
        updateProgress();
        DeezerApi.trending(TRENDING_LIMIT, (list, failed) -> {
            if (my != dzToken || isFinishing() || isDestroyed()) return;
            dzSpin = false;
            updateProgress();
            swipe.setRefreshing(false);
            trendingFailed = failed;
            if (!(failed && !trendingList.isEmpty())) {   // ব্যর্থ হলে আগের তালিকাই থাকুক
                trendingList.clear();
                trendingList.addAll(list);
                trendingLoaded = !failed;
            }
            applyFilter();
        });
    }

    private void applyFilter() {
        String q = etSearch.getText().toString().trim().toLowerCase();
        items.clear();
        for (MusicApi.Track t : trendingTab ? trendingList : all) {
            if (q.isEmpty() || t.matches(q)) items.add(t);
        }
        adapter.notifyDataSetChanged();
        boolean failed = trendingTab ? trendingFailed : loadFailed;
        if (items.isEmpty() && progress.getVisibility() != View.VISIBLE) {
            String msg;
            if (failed) {
                msg = "গান লোড হয়নি — টেনে নামিয়ে আবার চেষ্টা করুন";
            } else if (!q.isEmpty()) {
                msg = "কোনো গান পাওয়া যায়নি";
            } else if (trendingTab) {
                msg = "কোনো গান নেই। টেনে নামিয়ে রিফ্রেশ দিন";
            } else {
                msg = "কোনো গান নেই। Family Song অ্যাপে গান আপলোড করুন, তারপর টেনে নামিয়ে রিফ্রেশ দিন";
            }
            tvEmpty.setText(msg);
            tvEmpty.setVisibility(View.VISIBLE);
        } else {
            tvEmpty.setVisibility(View.GONE);
        }
    }

    private void choose(MusicApi.Track t) {
        MusicPlayer.stop();
        Intent d = new Intent();
        d.putExtra(EXTRA_TITLE, t.title);
        d.putExtra(EXTRA_ARTIST, t.artist);
        d.putExtra(EXTRA_COVER, t.cover);
        d.putExtra(EXTRA_URL, t.url);
        setResult(RESULT_OK, d);
        finish();
    }

    // ---------------------------------------------------------------- তালিকা

    private class Adapter extends RecyclerView.Adapter<Adapter.VH> {
        @Override
        public VH onCreateViewHolder(ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_music, parent, false));
        }

        private void setBitmap(ImageView iv, Bitmap bm) {
            iv.setPadding(0, 0, 0, 0);
            iv.setImageTintList(null);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageBitmap(bm);
        }

        private void setPlaceholder(ImageView iv) {
            int pad = Math.round(16 * iv.getResources().getDisplayMetrics().density);
            iv.setPadding(pad, pad, pad, pad);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setImageResource(R.drawable.ic_music_note);
            iv.setImageTintList(ColorStateList.valueOf(0xFF8A8D91));
        }

        @Override
        public void onBindViewHolder(final VH h, int pos) {
            final MusicApi.Track t = items.get(pos);
            h.title.setText(t.title);
            h.cover.setClipToOutline(true);

            final boolean dz = t.url != null && t.url.startsWith(DeezerApi.PREFIX);
            Bitmap cv = dz ? DeezerApi.cachedCover(t.cover) : MusicApi.cover(t.id);
            if (cv != null) {                       // Family Song / Deezer-এর ছোট ছবি
                h.cover.setTag(null);
                if (dz) MusicApi.putCover(t.url, cv);
                setBitmap(h.cover, cv);
            } else {                                // ছবি নেই → গানের নোট আইকন
                setPlaceholder(h.cover);
                if (dz && t.cover != null && !t.cover.isEmpty()) {
                    final String u = t.cover;
                    h.cover.setTag(u);
                    DeezerApi.loadCover(u, bm -> {
                        if (bm == null || !u.equals(h.cover.getTag())) return;   // রিসাইকেল হয়ে গেছে
                        MusicApi.putCover(t.url, bm);
                        setBitmap(h.cover, bm);
                    });
                } else {
                    h.cover.setTag(null);
                }
            }

            h.artist.setText(t.subtitle());
            h.artist.setVisibility(t.subtitle().isEmpty() ? View.GONE : View.VISIBLE);
            boolean on = MusicPlayer.isPlaying("pick:" + t.id);
            h.playIcon.setImageResource(on ? R.drawable.ic_music_pause : R.drawable.ic_music_play);
            h.itemView.setOnClickListener(v -> choose(t));
            h.play.setOnClickListener(v -> {
                if (!Net.check(v.getContext())) return;
                MusicPlayer.toggle(v.getContext(), "pick:" + t.id, t.url, 0, 0);
            });
        }

        @Override
        public int getItemCount() { return items.size(); }

        class VH extends RecyclerView.ViewHolder {
            ImageView playIcon, cover;
            TextView title, artist;
            View play;

            VH(View v) {
                super(v);
                playIcon = v.findViewById(R.id.imgMusicPlay);
                cover = v.findViewById(R.id.imgMusicCover);
                title = v.findViewById(R.id.tvMusicTitle);
                artist = v.findViewById(R.id.tvMusicArtist);
                play = v.findViewById(R.id.btnMusicPlay);
            }
        }
    }
}
