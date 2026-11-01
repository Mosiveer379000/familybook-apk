package com.familybook.app;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * ফেসবুকের ৩ নম্বর স্ক্রিন: Cancel · 🗑 Delete · Done, ওয়েভফর্মে টেনে ক্লিপ বাছা,
 * নিচে গানের নাম + "Tap to change" + pause/play।
 */
public class MusicTrimActivity extends AppCompatActivity {

    public static final String EXTRA_START = "music_start";
    public static final int RESULT_CHANGE = 100;   // "Tap to change" চাপা হয়েছে
    public static final int RESULT_DELETE = 101;   // 🗑 চাপা হয়েছে

    /** পোস্টে গানের ক্লিপের দৈর্ঘ্য (ms) — বদলাতে চাইলে এখানে */
    public static final int CLIP_MS = 15000;

    private static final String KEY = "trim";

    private String url;
    private int startMs;
    private int lastDur = 0;
    private WaveformView wave;
    private ImageView imgPlayIcon;
    private TextView tvInfo;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            int dur = MusicPlayer.duration();
            if (dur > 0) {
                if (dur != lastDur) {
                    lastDur = dur;
                    wave.setTotalMs(dur);
                    updateInfo();
                }
            } else {
                tvInfo.setText("গান লোড হচ্ছে…");
            }
            if (MusicPlayer.isActive(KEY)) {
                wave.setProgressMs(MusicPlayer.position() - wave.getStartMs());
            }
            imgPlayIcon.setImageResource(MusicPlayer.isPlaying(KEY)
                    ? R.drawable.ic_music_pause : R.drawable.ic_music_play);
            handler.postDelayed(this, 100);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_music_trim);

        Intent in = getIntent();
        url = in.getStringExtra(MusicPickerActivity.EXTRA_URL);
        startMs = in.getIntExtra(EXTRA_START, 0);
        String title = in.getStringExtra(MusicPickerActivity.EXTRA_TITLE);
        String artist = in.getStringExtra(MusicPickerActivity.EXTRA_ARTIST);
        String cover = in.getStringExtra(MusicPickerActivity.EXTRA_COVER);

        ((TextView) findViewById(R.id.tvTrimTitle)).setText(title);
        ((TextView) findViewById(R.id.tvTrimArtist)).setText(artist);
        imgPlayIcon = findViewById(R.id.imgTrimPlayIcon);
        showCover(MusicApi.cover(MusicApi.idOf(url)));
        tvInfo = findViewById(R.id.tvTrimClipInfo);

        wave = findViewById(R.id.waveTrim);
        wave.setSeed(url);
        wave.setClipMs(CLIP_MS);
        wave.setTotalMs(Math.max(30000, startMs + CLIP_MS));   // আসল দৈর্ঘ্য লোড হলে বদলে যাবে
        wave.setStartMs(startMs);
        updateInfo();
        wave.setOnStartChange((s, end) -> {
            startMs = s;
            updateInfo();
            if (end) MusicPlayer.setClip(s, CLIP_MS);   // আঙুল তুললে নতুন জায়গা থেকে চলবে
        });

        findViewById(R.id.btnTrimCancel).setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });
        findViewById(R.id.btnTrimDelete).setOnClickListener(v -> {
            setResult(RESULT_DELETE);
            finish();
        });
        findViewById(R.id.btnTrimDone).setOnClickListener(v -> {
            Intent d = new Intent();
            d.putExtra(EXTRA_START, wave.getStartMs());
            setResult(RESULT_OK, d);
            finish();
        });
        findViewById(R.id.btnTrimChange).setOnClickListener(v -> change());
        findViewById(R.id.boxTrimTitle).setOnClickListener(v -> change());
        findViewById(R.id.btnTrimPlay).setOnClickListener(v -> {
            if (MusicPlayer.isActive(KEY)) MusicPlayer.togglePause();
            else MusicPlayer.play(this, KEY, url, startMs, CLIP_MS);
        });
    }

    // Family Song-এর ছোট ছবি: মাঝখানে ছোট কার্ড + নিচের কার্ডে থাম্বনেইল; ছবি না থাকলে গানের নোট
    private void showCover(Bitmap bm) {
        ImageView big = findViewById(R.id.imgTrimCover);
        ImageView thumb = findViewById(R.id.imgTrimThumb);
        big.setClipToOutline(true);
        thumb.setClipToOutline(true);
        if (bm == null) return;          // লেআউটের ডিফল্ট নোট আইকনই থাকবে
        for (ImageView iv : new ImageView[]{big, thumb}) {
            iv.setPadding(0, 0, 0, 0);
            iv.setImageTintList(null);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageBitmap(bm);
        }
    }

    private void change() {
        setResult(RESULT_CHANGE);
        finish();
    }

    private void updateInfo() {
        int s = startMs / 1000;
        tvInfo.setText(String.format(java.util.Locale.US, "%d:%02d – %d:%02d",
                s / 60, s % 60, (s + CLIP_MS / 1000) / 60, (s + CLIP_MS / 1000) % 60));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // অন্য স্ক্রিন থেকে ফিরলে (pause-এ গান বন্ধ হয়) আবার চালু
        if (!MusicPlayer.isActive(KEY)) MusicPlayer.play(this, KEY, url, startMs, CLIP_MS);
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(ticker);
        super.onPause();
    }
}
