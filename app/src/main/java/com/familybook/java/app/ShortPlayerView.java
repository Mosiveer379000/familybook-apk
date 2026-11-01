package com.familybook.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.media.audiofx.LoudnessEnhancer;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * Short ভিডিও প্লেয়ার ভিউ (MediaPlayer + TextureView; বাড়তি লাইব্রেরি লাগে না)।
 *  - full = false : ফিড/প্রোফাইল-টাইমলাইন কার্ড — ভিডিও যে সাইজেরই হোক, কার্ড হয় 16:9 (চওড়া ভিডিও) নয়তো 1:1 (বাকি সব,
 *                   লম্বা ভিডিও ক্রপ হয়ে 1:1) — ছবির নিয়মের মতোই (autoplay, লুপ)
 *  - full = true  : ফুল-স্ক্রিন ভিউয়ার — 16:9 / 1:1 ভিডিও মাঝখানে পুরোটা (ব্ল্যাক ব্যাকগ্রাউন্ড);
 *                   লম্বা (9:16, 4:5, 3:4 …) ভিডিও পুরো জায়গা জুড়ে ভরে
 * একসাথে একটাই ভিডিও চলে (নতুন চালু হলে আগেরটা থামে)। মিউট বাটন ডান-নিচে।
 */
final class ShortPlayerView extends FrameLayout implements TextureView.SurfaceTextureListener {

    private static ShortPlayerView active;
    static boolean feedMuted = true;       // ফিডে ডিফল্ট মিউট (অটো-প্লে)
    static boolean viewerMuted = false;    // ফুল-স্ক্রিনে সাউন্ড চালু
    static boolean autoNext = false;       // রিল শেষ হলে পরেরটায় যাও (MediaPrefs.autoNext)
    Runnable onEnded;                      // ফুল-স্ক্রিন ভিউয়ার বসায়: শেষ হলে পরের রিলে স্ক্রল
    static float speed = 1f;               // প্লেব্যাক স্পিড (মেনু → Playback speed)

    private final boolean full;
    private final TextureView tv;
    private final ImageView poster;
    private final TextView muteBtn;
    private final TextView playIcon;

    /** ভিডিওর সাউন্ড বুস্ট (মিলিবেল): 800 = +8dB। কম/বেশি লাগলে এই সংখ্যা বদলান (লিমিটার আছে, তাই ফাটবে না) */
    private static final int BOOST_MB = 800;

    private MediaPlayer mp;
    private LoudnessEnhancer enhancer;
    private Surface surface;
    private ShortStore.Info info;
    private boolean wantPlay, prepared, retried;
    private int vw, vh, gen;
    private int plays = 0;                      // অটো-নেক্সট চালু থাকলে একই ভিডিও কতবার চলল

    /** অটো-স্ক্রল চালু থাকলে নিচে না নেমে একই ভিডিও কমপক্ষে এতবার চলবে, তারপর পরেরটায় নামবে */
    static final int AUTO_REPLAYS = 5;

    // ------------------------------------------------------------ আগে থেকে তৈরি (preload) প্লেয়ার
    // ফুল-স্ক্রিন ভিউয়ারে চলতি ভিডিওর উপরে-নিচে ২টা করে ভিডিও prepareAsync করে রাখা হয়,
    // তাই স্ক্রল করলে লোডিং ছাড়াই চলে। শুধু main thread-এ ব্যবহার হয়।
    private static final class Pre { MediaPlayer mp; boolean prepared; }
    private static final java.util.LinkedHashMap<String, Pre> POOL = new java.util.LinkedHashMap<>();

    /** keys-এর ভিডিওগুলো রেডি রাখে; keys-এ নেই এমন আগের preload ছেড়ে দেয় */
    static void preload(final java.util.Collection<String> keys) {
        java.util.Iterator<java.util.Map.Entry<String, Pre>> it = POOL.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<String, Pre> e = it.next();
            if (!keys.contains(e.getKey())) { releasePre(e.getValue()); it.remove(); }
        }
        for (final String k : keys) {
            if (k == null || POOL.containsKey(k)) continue;
            final Pre pre = new Pre();
            POOL.put(k, pre);
            Media.readUrl(k, (url, err) -> {
                if (POOL.get(k) != pre) return;                 // এর মধ্যে বাদ পড়েছে
                if (url == null) { POOL.remove(k); return; }
                try {
                    MediaPlayer m = new MediaPlayer();
                    pre.mp = m;
                    m.setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build());
                    m.setVolume(0f, 0f);
                    m.setDataSource(url);
                    m.setOnPreparedListener(x -> pre.prepared = true);
                    m.setOnErrorListener((x, w, ex) -> {
                        if (POOL.get(k) == pre) POOL.remove(k);
                        releasePre(pre);
                        return true;
                    });
                    m.prepareAsync();
                } catch (Exception e) {
                    if (POOL.get(k) == pre) POOL.remove(k);
                    releasePre(pre);
                }
            });
        }
    }

    /** সব preload ছেড়ে দেয় (ভিউয়ার বন্ধ/পজ হলে) */
    static void releasePreloaded() {
        for (Pre p : POOL.values()) releasePre(p);
        POOL.clear();
    }

    private static void releasePre(Pre p) {
        if (p == null || p.mp == null) return;
        try { p.mp.setOnPreparedListener(null); p.mp.setOnErrorListener(null); } catch (Exception ignored) { }
        try { p.mp.release(); } catch (Exception ignored) { }
        p.mp = null;
    }

    ShortPlayerView(Context c, boolean full) {
        super(c);
        this.full = full;
        float d = c.getResources().getDisplayMetrics().density;
        setBackgroundColor(full ? Color.BLACK : 0xFF1C1E21);
        setClipChildren(true);

        tv = new TextureView(c);
        tv.setSurfaceTextureListener(this);
        addView(tv, new LayoutParams(-1, -1));

        poster = new ImageView(c);
        poster.setScaleType(full ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_CROP);
        addView(poster, new LayoutParams(-1, -1));

        playIcon = new TextView(c);
        playIcon.setText("▶");
        playIcon.setTextColor(Color.WHITE);
        playIcon.setTextSize(26);
        playIcon.setGravity(Gravity.CENTER);
        GradientDrawable pb = new GradientDrawable();
        pb.setShape(GradientDrawable.OVAL);
        pb.setColor(0x66000000);
        playIcon.setBackground(pb);
        LayoutParams pp = new LayoutParams(Math.round(64 * d), Math.round(64 * d), Gravity.CENTER);
        addView(playIcon, pp);

        muteBtn = new TextView(c);
        muteBtn.setTextSize(16);
        muteBtn.setGravity(Gravity.CENTER);
        muteBtn.setTextColor(Color.WHITE);
        GradientDrawable mb = new GradientDrawable();
        mb.setShape(GradientDrawable.OVAL);
        mb.setColor(0x80000000);
        muteBtn.setBackground(mb);
        LayoutParams mp2 = new LayoutParams(Math.round(36 * d), Math.round(36 * d), Gravity.BOTTOM | Gravity.END);
        mp2.setMargins(0, 0, Math.round(10 * d), Math.round(10 * d));
        addView(muteBtn, mp2);
        muteBtn.setOnClickListener(v -> setMuted(!muted()));
        refreshIcons();
    }

    // ------------------------------------------------------------ মাপ (কার্ডে 16:9 বা 1:1)

    /** কার্ডের অনুপাত: চওড়া ভিডিও (≈16:9 বা তার চেয়ে চওড়া) = 16:9, বাকি সব (1:1, লম্বা, 4:3 …) = 1:1 */
    static float cardRatio(ShortStore.Info i) {
        if (i != null && i.width > 0 && i.height > 0) {
            float r = (float) i.width / i.height;
            if (r >= 1.4f) return 16f / 9f;
        }
        return 1f;
    }

    /** লম্বা ভিডিও (চওড়ার চেয়ে উচ্চতা বেশি) কি না — ফুল-স্ক্রিনে এগুলো পুরো জায়গা জুড়ে দেখায় */
    static boolean isVertical(int w, int h) {
        return w > 0 && h > 0 && (float) w / h < 0.95f;
    }

    private boolean fillScreen() {
        int w = vw > 0 ? vw : (info != null ? info.width : 0);
        int h = vh > 0 ? vh : (info != null ? info.height : 0);
        return isVertical(w, h);
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        if (!full) {
            int w = MeasureSpec.getSize(wSpec);
            int h = Math.round(w / cardRatio(info));
            super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
        } else {
            super.onMeasure(wSpec, hSpec);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        transform();
    }

    // ------------------------------------------------------------ বাইন্ড / নিয়ন্ত্রণ

    void bind(ShortStore.Info i) {
        if (i == info) return;
        stop();
        info = i;
        retried = false;
        vw = 0;
        vh = 0;
        if (full) {
            poster.setScaleType(fillScreen() ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
        }
        requestLayout();
        poster.setImageDrawable(null);
        poster.setVisibility(VISIBLE);
        if (i != null && i.thumbKey != null) AvatarLoader.loadKey(poster, i.thumbKey);
        refreshIcons();
    }

    ShortStore.Info info() { return info; }

    boolean isWanted() { return wantPlay; }

    void play() {
        if (info == null) return;
        if (active != null && active != this) active.stop();
        active = this;
        wantPlay = true;
        start();
        refreshIcons();
    }

    /** থামায়, কিন্তু প্লেয়ার ধরে রাখে (ফুল-স্ক্রিনে ট্যাপে pause/resume) */
    void pause() {
        wantPlay = false;
        if (mp != null && prepared) {
            try { mp.pause(); } catch (Exception ignored) { }
        }
        refreshIcons();
    }

    /** থামায় ও প্লেয়ার ছেড়ে দেয় (ডিকোডার সীমিত, তাই কার্ড স্ক্রিন ছাড়লেই) */
    void stop() {
        wantPlay = false;
        releasePlayer();
        if (active == this) active = null;
        refreshIcons();
    }

    /** এখন যে ভিডিওটা চালু (নেই হলে null) */
    static ShortPlayerView current() { return active; }

    static void setAutoNext(boolean on) {
        autoNext = on;
        if (active != null) active.applyLoop();
    }

    /** অটো-নেক্সট চালু + ভিউয়ারে থাকলে লুপ বন্ধ (শেষ হলে onEnded); নইলে আগের মতো লুপ */
    private void applyLoop() {
        if (mp == null) return;
        try { mp.setLooping(!(full && autoNext && onEnded != null)); } catch (Exception ignored) { }
    }

    /** প্লেব্যাক স্পিড বদলায়; চলতে থাকা ভিডিওতে সাথে সাথে, পরের ভিডিওগুলোতেও */
    static void setSpeedAll(float s) {
        speed = s;
        if (active != null) active.applySpeed();
    }

    private void applySpeed() {
        if (mp == null || !prepared || !wantPlay) return;
        try {
            mp.setPlaybackParams(new PlaybackParams().setSpeed(speed));
        } catch (Throwable ignored) { }
    }

    /** টাইমার/সিক-বারের জন্য: বর্তমান জায়গা ও মোট সময় (মিলিসেকেন্ড) */
    int position() {
        try { return mp != null && prepared ? mp.getCurrentPosition() : 0; } catch (Exception e) { return 0; }
    }

    int duration() {
        try { return mp != null && prepared ? Math.max(0, mp.getDuration()) : 0; } catch (Exception e) { return 0; }
    }

    void seekTo(int ms) {
        if (mp == null || !prepared) return;
        try { mp.seekTo(Math.max(0, ms)); } catch (Exception ignored) { }
    }

    void togglePlay() {
        if (wantPlay) pause(); else play();
    }

    static void stopAll() {
        if (active != null) active.stop();
        active = null;
    }

    void setControlsVisible(boolean show) {
        muteBtn.setVisibility(show ? VISIBLE : GONE);
    }

    private boolean muted() { return full ? viewerMuted : feedMuted; }

    boolean isMuted() { return muted(); }

    void setMuted(boolean m) {
        if (full) viewerMuted = m; else feedMuted = m;
        applyVolume();
        refreshIcons();
    }

    private void applyVolume() {
        if (mp == null) return;
        float v = muted() ? 0f : 1f;
        try { mp.setVolume(v, v); } catch (Exception ignored) { }
    }

    private void refreshIcons() {
        if (muteBtn == null || playIcon == null) return;
        muteBtn.setText(muted() ? "🔇" : "🔊");
        playIcon.setVisibility(info != null && !wantPlay ? VISIBLE : GONE);
    }

    // ------------------------------------------------------------ প্লেয়ার

    private void start() {
        if (info == null || surface == null) return;
        if (mp != null) {
            if (prepared) {
                try { mp.start(); } catch (Exception ignored) { }
                applySpeed();
            }
            return;
        }
        if (info.videoKey == null) return;
        final int g = ++gen;
        // আগে থেকে রেডি করা প্লেয়ার থাকলে সেটাই নিই — লোডিং লাগে না
        final Pre pre = POOL.remove(info.videoKey);
        if (pre != null && pre.mp != null) {
            final MediaPlayer m = pre.mp;
            pre.mp = null;
            setupPlayer(m, g, pre.prepared);
            return;
        }
        Media.readUrl(info.videoKey, (url, err) -> {
            if (g != gen || url == null || surface == null) return;
            try {
                final MediaPlayer m = new MediaPlayer();
                m.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build());
                m.setDataSource(url);
                setupPlayer(m, g, false);
                m.prepareAsync();
            } catch (Exception e) {
                releasePlayer();
            }
        });
    }

    /** নতুন বা preload করা MediaPlayer-এ সব listener বসায়; ready = আগেই prepared */
    private void setupPlayer(final MediaPlayer m, final int g, boolean ready) {
        mp = m;
        plays = 0;
        try {
            m.setSurface(surface);
            m.setLooping(!(full && autoNext && onEnded != null));
            m.setOnCompletionListener(x -> {
                if (g != gen || !(full && autoNext && onEnded != null)) return;
                // একই ভিডিও কমপক্ষে AUTO_REPLAYS বার চলে; তারপর পরের রিলে
                plays++;
                if (plays < AUTO_REPLAYS) {
                    try { x.seekTo(0); x.start(); } catch (Exception ignored) { }
                    applySpeed();
                } else {
                    plays = 0;
                    onEnded.run();
                }
            });
            float v = muted() ? 0f : 1f;
            m.setVolume(v, v);
            m.setOnVideoSizeChangedListener((x, w, h) -> {
                if (g != gen) return;
                vw = w;
                vh = h;
                if (full) poster.setScaleType(fillScreen() ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
                transform();
            });
            m.setOnInfoListener((x, what, extra) -> {
                if (g == gen && what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) poster.setVisibility(GONE);
                return false;
            });
            m.setOnErrorListener((x, what, extra) -> {
                if (g == gen) {
                    releasePlayer();
                    if (!retried && wantPlay && info != null) {   // মেয়াদ-শেষ/নেটওয়ার্ক ঝামেলায় একবার নতুন লিংকে আবার
                        retried = true;
                        Media.forgetUrl(info.videoKey);
                        start();
                    }
                }
                return true;
            });
            m.setOnPreparedListener(x -> onPlayerPrepared(x, g));
            if (ready) onPlayerPrepared(m, g);
        } catch (Exception e) {
            releasePlayer();
        }
    }

    private void onPlayerPrepared(MediaPlayer x, int g) {
        if (g != gen) return;
        prepared = true;
        boostLoudness(x);
        vw = x.getVideoWidth();
        vh = x.getVideoHeight();
        if (full) poster.setScaleType(fillScreen() ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
        transform();
        if (wantPlay) {
            try { x.start(); } catch (Exception ignored) { }
            applySpeed();
        }
    }

    /** আপলোড করা ভিডিওর শব্দ কম শোনালে প্লেব্যাকে জোরালো করে (পুরোনো ভিডিওতেও কাজ করে) */
    private void boostLoudness(MediaPlayer m) {
        releaseEnhancer();
        try {
            LoudnessEnhancer e = new LoudnessEnhancer(m.getAudioSessionId());
            e.setTargetGain(BOOST_MB);
            e.setEnabled(true);
            enhancer = e;
        } catch (Throwable ignored) { }
    }

    private void releaseEnhancer() {
        if (enhancer != null) {
            try { enhancer.release(); } catch (Throwable ignored) { }
            enhancer = null;
        }
    }

    private void releasePlayer() {
        gen++;
        prepared = false;
        releaseEnhancer();
        if (mp != null) {
            try { mp.setOnPreparedListener(null); mp.setOnErrorListener(null); mp.setOnInfoListener(null); } catch (Exception ignored) { }
            try { mp.release(); } catch (Exception ignored) { }
            mp = null;
        }
        if (poster != null) poster.setVisibility(VISIBLE);
    }

    /** TextureView ডিফল্টে ভিডিও টেনে ভরে; তাই অনুপাত ঠিক রাখতে স্কেল (কার্ড = ক্রপ, ফুল = পুরোটা) */
    private void transform() {
        int W = getWidth(), H = getHeight();
        if (vw <= 0 || vh <= 0 || W <= 0 || H <= 0) return;
        // কার্ড ও লম্বা ভিডিও (ফুল-স্ক্রিনে) = ভরে (ক্রপ); 16:9 / 1:1 ভিডিও ফুল-স্ক্রিনে = পুরোটা মাঝখানে
        boolean cover = !full || fillScreen();
        float s = cover ? Math.max((float) W / vw, (float) H / vh) : Math.min((float) W / vw, (float) H / vh);
        Matrix m = new Matrix();
        m.setScale(vw * s / W, vh * s / H, W / 2f, H / 2f);
        tv.setTransform(m);
    }

    // ------------------------------------------------------------ Surface / lifecycle

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        surface = new Surface(st);
        if (mp != null) {
            try { mp.setSurface(surface); } catch (Exception ignored) { }
        } else if (wantPlay) {
            start();
        }
    }

    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { transform(); }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        releasePlayer();
        if (surface != null) { surface.release(); surface = null; }
        return true;
    }

    @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stop();
    }
}
