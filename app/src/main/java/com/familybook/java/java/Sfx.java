package com.familybook.app;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;

import java.util.HashSet;
import java.util.Set;

/**
 * ছোট সাউন্ড ইফেক্ট: রিফ্রেশ, লাইক, পোস্ট/কমেন্ট পাঠানো।
 * সাউন্ড ফাইল: res/raw/refresh.wav, like.wav, send.wav
 * (নিজের সাউন্ড চাইলে একই নামে ফাইল বদলে দিন)
 */
public final class Sfx {
    private static SoundPool pool;
    private static int idRefresh, idLike, idSend;
    private static int idStLow, idStMid, idStLime, idStGreen, idStFull;
    private static final Set<Integer> loaded = new HashSet<>();

    private Sfx() { }

    public static synchronized void init(Context ctx) {
        if (pool != null) return;
        Context app = ctx.getApplicationContext();
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        pool = new SoundPool.Builder().setMaxStreams(3).setAudioAttributes(attrs).build();
        pool.setOnLoadCompleteListener((sp, id, status) -> {
            if (status == 0) synchronized (Sfx.class) { loaded.add(id); }
        });
        idRefresh = pool.load(app, R.raw.refresh, 1);
        idLike = pool.load(app, R.raw.like, 1);
        idSend = pool.load(app, R.raw.send, 1);
        idStLow = pool.load(app, R.raw.st_low, 1);
        idStMid = pool.load(app, R.raw.st_mid, 1);
        idStLime = pool.load(app, R.raw.st_lime, 1);
        idStGreen = pool.load(app, R.raw.st_green, 1);
        idStFull = pool.load(app, R.raw.st_full, 1);
    }

    private static synchronized void play(int id) {
        if (pool != null && loaded.contains(id)) pool.play(id, 1f, 1f, 1, 0, 1f);
    }

    public static void refresh() { play(idRefresh); }
    public static void like()    { play(idLike); }
    /** নতুন পোস্ট বা কমেন্ট পাঠানোর সাউন্ড */
    public static void send()    { play(idSend); }

    /** Profile status টোস্টের সাউন্ড: pct অনুযায়ী ৫টি আলাদা সাউন্ড */
    public static void status(int pct) {
        if (pct >= 100) play(idStFull);
        else if (pct >= 91) play(idStGreen);
        else if (pct >= 76) play(idStLime);
        else if (pct >= 50) play(idStMid);
        else play(idStLow);
    }
}
