package com.familybook.app;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * একসাথে শুধু একটা গান চলে। পোস্ট কার্ড, গান-বাছাই ও ক্লিপ-কাটার স্ক্রিন সবাই এটাই ব্যবহার করে।
 * কোনো Activity pause হলেই গান নিজে থেকে বন্ধ হয়ে যায়।
 */
public class MusicPlayer {

    public interface Listener {
        /** key = কোন গান; playing = এখন বাজছে কি না */
        void onState(String key, boolean playing);
    }

    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private static MediaPlayer mp;
    private static String curKey;
    private static boolean prepared;
    private static boolean playing;
    private static int clipStart;
    private static int clipEnd;          // 0 = পুরো গান
    private static boolean hooked;
    private static float vol = 1f;      // গানের ভলিউম (0..1); stop() হলে ১-এ ফেরে

    private static final Runnable TICK = new Runnable() {
        @Override public void run() {
            if (curKey == null) return;
            try {
                if (mp != null && prepared && playing && clipEnd > 0 && mp.getCurrentPosition() >= clipEnd) {
                    mp.seekTo(clipStart);
                }
            } catch (Exception ignored) { }
            H.postDelayed(this, 120);
        }
    };

    /** চলমান/লোড হওয়া গানের ভলিউম। play() ডাকার পরে ডাকতে হবে (stop() এটা ১-এ ফেরায়)। */
    public static void setVolume(float v) {
        vol = Math.max(0f, Math.min(1f, v));
        MediaPlayer m = mp;
        if (m != null && prepared) { try { m.setVolume(vol, vol); } catch (Exception ignored) { } }
    }

    public static void addListener(Listener l) { if (!LISTENERS.contains(l)) LISTENERS.add(l); }

    public static void removeListener(Listener l) { LISTENERS.remove(l); }

    private static void notifyState(String key, boolean p) {
        if (key == null) return;
        for (Listener l : LISTENERS) l.onState(key, p);
    }

    private static void hook(Context c) {
        if (hooked) return;
        hooked = true;
        Application app = (Application) c.getApplicationContext();
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityPaused(Activity a) { stop(); }
            @Override public void onActivityCreated(Activity a, Bundle b) { }
            @Override public void onActivityStarted(Activity a) { }
            @Override public void onActivityResumed(Activity a) { }
            @Override public void onActivityStopped(Activity a) { }
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
            @Override public void onActivityDestroyed(Activity a) { }
        });
    }

    // ------------------------------------------------------------ নিয়ন্ত্রণ

    private static int gen = 0;    // পুরোনো (বাতিল) লোড চিনতে

    /** lenMs <= 0 হলে পুরো গান চলে; নইলে startMs থেকে lenMs পর্যন্ত বারবার (loop)।
     *  url "fs:{id}" হলে Family Song থেকে ফাইল নামিয়ে (ক্যাশসহ) চালায়।
     *  url "dz:{id}" হলে Deezer থেকে নতুন preview লিংক এনে চালায়। */
    public static void play(Context c, String key, String url, int startMs, int lenMs) {
        hook(c);
        stop();
        if (url == null || url.isEmpty()) return;
        final int my = ++gen;
        curKey = key;
        clipStart = Math.max(0, startMs);
        clipEnd = lenMs > 0 ? clipStart + lenMs : 0;
        prepared = false;
        playing = true;
        final Context app = c.getApplicationContext();
        H.removeCallbacks(TICK);
        H.postDelayed(TICK, 120);
        notifyState(curKey, true);

        if (url.startsWith(MusicApi.PREFIX)) {
            String id = url.substring(MusicApi.PREFIX.length());
            if (!MusicApi.isCached(app, id)) {
                Toast.makeText(app, "গান লোড হচ্ছে...", Toast.LENGTH_SHORT).show();
            }
            MusicApi.fetchAudio(app, id, (file, err) -> {
                if (my != gen) return;            // এর মধ্যে বন্ধ/বদলে গেছে
                if (file == null) {
                    Toast.makeText(app, "গান লোড হয়নি", Toast.LENGTH_SHORT).show();
                    stop();
                    return;
                }
                startPlayer(app, file.getAbsolutePath(), my);
            });
        } else if (url.startsWith(DeezerApi.PREFIX)) {
            String id = url.substring(DeezerApi.PREFIX.length());
            DeezerApi.resolvePreview(id, preview -> {
                if (my != gen) return;            // এর মধ্যে বন্ধ/বদলে গেছে
                if (preview == null) {
                    Toast.makeText(app, "গান লোড হয়নি", Toast.LENGTH_SHORT).show();
                    stop();
                    return;
                }
                startPlayer(app, preview, my);
            });
        } else {
            startPlayer(app, url, my);
        }
    }

    private static void startPlayer(final Context app, String source, final int my) {
        if (my != gen) return;
        final MediaPlayer m = new MediaPlayer();
        mp = m;
        try {
            m.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            m.setDataSource(source);
            m.setOnPreparedListener(x -> {
                if (x != mp) return;
                prepared = true;
                try {
                    x.setVolume(vol, vol);
                    if (clipStart > 0) x.seekTo(clipStart);
                    if (playing) x.start();
                } catch (Exception ignored) { }
            });
            m.setOnCompletionListener(x -> {
                if (x != mp) return;
                try {
                    x.seekTo(clipStart);
                    x.start();
                } catch (Exception ignored) { }
            });
            m.setOnErrorListener((x, what, extra) -> {
                if (x == mp) {
                    Toast.makeText(app, "গান চালানো যাচ্ছে না", Toast.LENGTH_SHORT).show();
                    stop();
                }
                return true;
            });
            m.prepareAsync();
        } catch (Exception e) {
            stop();
        }
    }

    /** একই গানে চাপলে pause/resume, অন্য গানে চাপলে নতুন করে চালু */
    public static void toggle(Context c, String key, String url, int startMs, int lenMs) {
        if (key.equals(curKey)) {
            togglePause();
        } else {
            play(c, key, url, startMs, lenMs);
        }
    }

    public static void togglePause() {
        if (curKey == null) return;
        try {
            if (playing) {
                playing = false;
                if (prepared && mp != null) mp.pause();
            } else {
                playing = true;
                if (prepared && mp != null) mp.start();
            }
        } catch (Exception ignored) { }
        notifyState(curKey, playing);
    }

    /** ক্লিপের শুরু/দৈর্ঘ্য বদলে সেখান থেকে আবার চালানো */
    public static void setClip(int startMs, int lenMs) {
        clipStart = Math.max(0, startMs);
        clipEnd = lenMs > 0 ? clipStart + lenMs : 0;
        if (mp != null && prepared) {
            try {
                mp.seekTo(clipStart);
                if (playing) mp.start();
            } catch (Exception ignored) { }
        }
    }

    public static void stop() {
        gen++;
        H.removeCallbacks(TICK);
        MediaPlayer m = mp;
        String k = curKey;
        mp = null;
        curKey = null;
        prepared = false;
        playing = false;
        vol = 1f;
        if (m != null) {
            try { m.setOnPreparedListener(null); m.setOnCompletionListener(null); m.setOnErrorListener(null); } catch (Exception ignored) { }
            try { m.stop(); } catch (Exception ignored) { }
            try { m.release(); } catch (Exception ignored) { }
        }
        if (k != null) notifyState(k, false);
    }

    // ------------------------------------------------------------ অবস্থা

    public static boolean isPlaying(String key) {
        return playing && key != null && key.equals(curKey);
    }

    /** এই key-র গান লোড/চালু আছে (pause অবস্থাতেও true) */
    public static boolean isActive(String key) {
        return key != null && key.equals(curKey);
    }

    /** এখন কোন key-র গান চালু/লোড আছে (কিছু না থাকলে null) */
    public static String currentKey() { return curKey; }

    /** শুধু সেই key-র গানই চললে বন্ধ করে; অন্য গান চললে কিছু করে না */
    public static void stopIfKey(String key) {
        if (key != null && key.equals(curKey)) stop();
    }

    /** পোস্ট ডিলিট/ট্র্যাশ হলে ওই পোস্টের গান থামাতে এটা ডাকুন */
    public static void stopPost(String postId) {
        if (postId != null) stopIfKey("post:" + postId);
    }

    public static int position() {
        try { return (mp != null && prepared) ? mp.getCurrentPosition() : 0; } catch (Exception e) { return 0; }
    }

    public static int duration() {
        try { return (mp != null && prepared) ? mp.getDuration() : 0; } catch (Exception e) { return 0; }
    }
}
