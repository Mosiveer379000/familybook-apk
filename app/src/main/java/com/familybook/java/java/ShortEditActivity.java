package com.familybook.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.OptIn;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.ChannelMixingAudioProcessor;
import androidx.media3.common.audio.ChannelMixingMatrix;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.effect.Crop;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * রেকর্ডের পরের এডিট পর্দা (নতুন ডিজাইন):
 * উপরে বামে ← ; মাঝখানে ভিডিও প্রিভিউ ;
 * নিচের বারে: Audio · Edit · Effects · Text · Stickers · Next (নীল গোল)।
 * Audio চাপলে নিচ থেকে "Mix your audio" শিট ওঠে (ShortAudioSheet): মূল অডিও ভলিউম/Mute, Enhance (আওয়াজ পরিষ্কার),
 * গানের লেভেল, Add music, Add voiceover (মাইকে রেকর্ড, Tap/Hold, Undo)। Edit → Volume-ও একই শিট খোলে।
 * Next চাপলে ফিল্টার + গান (মিক্স) ভিডিওতে বসিয়ে ShortSettingsActivity-তে যায়।
 * Text চাপলে টেক্সট এডিটর (রং/স্ট্রোক/ব্যাকগ্রাউন্ড/ফন্ট) ও টাইমলাইন প্যানেল (ShortText.java); Next-এ ভিডিওতে বসে।
 * Stickers এখনো "Coming soon"।
 */
@OptIn(markerClass = UnstableApi.class)
public class ShortEditActivity extends BaseActivity {

    public static final String EXTRA_FILTER = "short_filter";

    private final Handler H = new Handler(Looper.getMainLooper());
    private Uri video;
    private SurfaceView sv;
    private ExoPlayer player;
    private HorizontalScrollView chipScroll;
    private final List<TextView> chips = new ArrayList<>();
    private final List<Integer> chipIdx = new ArrayList<>();
    private TextView tvMusic, tvBusy;
    private int filter = 0;

    // ---- Audio মিক্স
    private ShortAudioSheet sheet;
    private float origVol = 1f;            // মূল অডিও (0 = Mute)
    private float musicLevel = 0.7f;       // গানের লেভেল
    private float enhance = 0f;            // 0 = বন্ধ; >0 = আওয়াজ পরিষ্কার (শুধু এক্সপোর্টে বসে)
    private VoiceOverRecorder voice;       // ভয়েসওভার WAV (ভিডিওর সময়রেখায় মেলানো)
    private ExoPlayer voicePlayer;         // ভয়েসওভারের প্রিভিউ
    private boolean recordingVoice = false;
    private ActivityResultLauncher<String> micLauncher;
    // শিট খোলার সময়ের অবস্থা (Cancel চাপলে ফেরত যেতে)
    private float snapOrig, snapMusic, snapEnh;
    private int snapVoiceCount;
    private String snapTitle, snapArtist, snapCover, snapUrl;
    private int snapStart;
    private String mTitle, mArtist, mCover, mUrl;
    private boolean busy = false;
    private int mStart = 0;
    private ActivityResultLauncher<Intent> trimLauncher;
    private long durMs = 0, trimStart = 0, trimEnd = 0;     // Cut: শুরু থেকে শেষ
    private int cropMode = 0;                               // 0 Original, 1 = 1:1, 2 = 4:5, 3 = 16:9
    private static final String[] CROP_NAMES = {"Original", "1:1 Square", "4:5", "16:9"};
    private static final float[] CROP_ASPECT = {0f, 1f, 0.8f, 16f / 9f};
    private Transformer transformer;

    // ---- Text
    private ShortTextTool textTool;
    private ShortEditTool editTool;
    private ShortStickerSheet stickerSheet;
    private ActivityResultLauncher<String> locLauncher, imgLauncher;
    private Runnable pendingLoc;
    private ImageView backBtn;
    private int lastVw = 0, lastVh = 0;

    private ActivityResultLauncher<Intent> musicLauncher, settingsLauncher;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        video = getIntent().getData();
        if (video == null) { finish(); return; }
        filter = getIntent().getIntExtra(EXTRA_FILTER, 0);
        mTitle = getIntent().getStringExtra(MusicPickerActivity.EXTRA_TITLE);
        mArtist = getIntent().getStringExtra(MusicPickerActivity.EXTRA_ARTIST);
        mCover = getIntent().getStringExtra(MusicPickerActivity.EXTRA_COVER);
        mUrl = getIntent().getStringExtra(MusicPickerActivity.EXTRA_URL);
        mStart = getIntent().getIntExtra(MusicTrimActivity.EXTRA_START, 0);

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
        settingsLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
            if (r.getResultCode() == RESULT_OK) { setResult(RESULT_OK); finish(); }
        });

        locLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), ok -> {
            Runnable r = pendingLoc; pendingLoc = null;
            if (r == null) return;
            if (ok) r.run();
            else Toast.makeText(this, "Location permission denied", Toast.LENGTH_SHORT).show();
        });
        imgLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri == null) return;
            com.bumptech.glide.Glide.with(getApplicationContext()).asBitmap().load(uri).override(1024, 1024)
                    .into(new com.bumptech.glide.request.target.CustomTarget<android.graphics.Bitmap>() {
                        @Override public void onResourceReady(android.graphics.Bitmap b, com.bumptech.glide.request.transition.Transition<? super android.graphics.Bitmap> t) { addGalleryImage(b); }
                        @Override public void onLoadCleared(android.graphics.drawable.Drawable d) { }
                    });
        });
        micLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), ok -> {
            if (ok) openVoicePanel();
            else Toast.makeText(this, "Microphone permission is required for voiceover", Toast.LENGTH_LONG).show();
        });

        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);   // Text এডিটরে কিবোর্ডের ওপরে প্যানেল
        setContentView(buildUi());
        player = new ExoPlayer.Builder(this).build();
        player.setRepeatMode(Player.REPEAT_MODE_ONE);
        player.setVideoSurfaceView(sv);
        player.addListener(new Player.Listener() {
            @Override public void onVideoSizeChanged(VideoSize v) { fitVideo(v.width, v.height); }
            @Override public void onPlaybackStateChanged(int st) { if (st == Player.STATE_ENDED && recordingVoice) stopVoiceRecording(); }
        });
        player.setMediaItem(MediaItem.fromUri(video));
        player.setVideoEffects(previewEffects());      // আসল শেডার ইফেক্ট লাইভ প্রিভিউতে
        player.setVolume(origVol);
        player.prepare();
        player.setPlayWhenReady(true);
        durMs = videoDurationMs();
        trimStart = 0; trimEnd = Math.min(durMs, ShortUploader.MAX_MS);   // গ্যালারির লম্বা ভিডিও ১:২০-তে শুরু থেকে কাটা
        if (durMs > ShortUploader.MAX_MS)
            Toast.makeText(this, "Video is over 1:20; use Edit to choose a part", Toast.LENGTH_LONG).show();
        applyFilterPreview();
        refreshMusic();
        H.postDelayed(loopTrim, 200);
    }

    private final Runnable loopTrim = new Runnable() {
        @Override public void run() {
            if (player != null) {
                long pos = player.getCurrentPosition();
                if (textTool != null) textTool.tick(pos);
                if (editTool != null) editTool.tick(pos);
                if (recordingVoice) {
                    // রেকর্ডের সময় ঘুরবে না — ট্রিমের শেষে পৌঁছালে রেকর্ড বন্ধ
                    if (trimEnd > 0 && pos >= trimEnd - 40) stopVoiceRecording();
                } else if (trimEnd > 0 && (trimEnd < durMs || trimStart > 0) && player.isPlaying() && (pos >= trimEnd || pos < trimStart - 100)) {
                    player.seekTo(trimStart);
                }
                if (sheet != null && sheet.isVoiceShown()) {
                    sheet.setPlayhead(frac(player.getCurrentPosition()));
                }
                syncVoicePreview();
            }
            H.postDelayed(this, 60);
        }
    };

    private float frac(long posMs) {
        long len = trimEnd - trimStart;
        if (len <= 0) return 0f;
        return Math.max(0f, Math.min(1f, (posMs - trimStart) / (float) len));
    }

    // ------------------------------------------------------------ প্রিভিউ

    private List<Effect> previewEffects() {
        List<Effect> l = new ArrayList<>();
        Effect fx = ShortFx.gl(filter);
        if (fx != null) l.add(fx);
        return l;
    }

    private void applyFilterPreview() {
        if (player != null) player.setVideoEffects(previewEffects());
    }

    /** SurfaceView-কে ভিডিওর অনুপাতে ভিডিও-বক্সের ভেতরে বসানো */
    private void fitVideo(final int vw, final int vh) {
        if (vw <= 0 || vh <= 0 || sv == null) return;
        lastVw = vw; lastVh = vh;
        final FrameLayout root = (FrameLayout) sv.getParent();
        if (root.getWidth() == 0 || root.getHeight() == 0) { root.post(() -> fitVideo(vw, vh)); return; }
        float sc = Math.min(root.getWidth() / (float) vw, root.getHeight() / (float) vh);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.round(vw * sc), Math.round(vh * sc));
        lp.gravity = Gravity.CENTER;
        sv.setLayoutParams(lp);
        if (textTool != null) textTool.fit(lp.width, lp.height);
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
        tvMusic.setText(mUrl == null ? "Audio" : (mTitle == null ? "Audio" : mTitle));
        if (mUrl != null && !recordingVoice) playMusic(); else MusicPlayer.stop();
        if (sheet != null) sheet.refreshMusicRow();
    }

    private void playMusic() {
        if (mUrl == null) return;
        MusicPlayer.play(this, "short_edit", mUrl, mStart, 0);
        MusicPlayer.setVolume(musicLevel);
    }

    private void applyOrigVolume() {
        if (player != null) player.setVolume(recordingVoice ? 0f : origVol);
    }

    @Override protected void onPause() {
        super.onPause();
        if (recordingVoice) stopVoiceRecording();
        MusicPlayer.stop();
        if (player != null) player.pause();
        if (voicePlayer != null) voicePlayer.pause();
    }
    @Override protected void onResume() {
        super.onResume();
        boolean inVoice = sheet != null && sheet.isVoiceShown();
        if (player != null && !inVoice) player.play();
        if (mUrl != null && !inVoice) playMusic();
    }

    @Override public void onBackPressed() {
        if (stickerSheet != null && stickerSheet.handleBack()) return;
        if (textTool != null && textTool.handleBack()) return;
        if (editTool != null && editTool.handleBack()) return;
        if (sheet != null && sheet.handleBack()) return;
        ShortDraft.clear(this);   // ইচ্ছা করে বের হলে ড্রাফট মুছে যাবে; কেটে দিলে/চার্জ শেষ হলে থাকবে
        super.onBackPressed();
    }

    // ------------------------------------------------------------ Next → এক্সপোর্ট

    private void onNext() {
        if (busy) return;
        boolean needs = filter >= 3 || mUrl != null || origVol < 0.999f || enhance > 0.01f || hasVoice() || trimmed() || cropMode != 0 || hasText();
        if (!needs) { openSettings(video); return; }
        busy = true;
        tvBusy.setText("Processing…");
        tvBusy.setVisibility(View.VISIBLE);
        MusicPlayer.stop();
        if (mUrl == null) { export(null); return; }
        resolveMusic(f -> {
            if (f == null) {
                busy = false; tvBusy.setVisibility(View.GONE);
                Toast.makeText(this, "Could not load music", Toast.LENGTH_SHORT).show();
            } else export(f);
        });
    }

    private boolean trimmed() { return durMs > 0 && (trimStart > 0 || trimEnd < durMs); }   // লম্বা ভিডিওতে শুরু থেকেই true

    private long outDurMs() { return trimmed() ? trimEnd - trimStart : durMs; }

    /** ঘোরানোর পরের মাপ {w, h} */
    private int[] videoSize() {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(this, video);
            int w = Integer.parseInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int h = Integer.parseInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            String rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            int ro = rot == null ? 0 : Integer.parseInt(rot);
            return (ro == 90 || ro == 270) ? new int[]{h, w} : new int[]{w, h};
        } catch (Exception e) { return new int[]{0, 0}; } finally { try { r.release(); } catch (Exception ignored) { } }
    }

    private Crop cropEffect() {
        if (cropMode == 0) return null;
        int[] sz = videoSize();
        if (sz[0] <= 0 || sz[1] <= 0) return null;
        float a = CROP_ASPECT[cropMode], cur = sz[0] / (float) sz[1];
        if (Math.abs(cur - a) < 0.01f) return null;
        if (cur > a) { float f = a / cur; return new Crop(-f, f, -1f, 1f); }
        float f = cur / a;
        return new Crop(-1f, 1f, -f, f);
    }

    private interface FileCb { void on(File f); }

    private void resolveMusic(final FileCb cb) {
        final android.content.Context app = getApplicationContext();
        if (mUrl.startsWith(MusicApi.PREFIX)) {
            MusicApi.fetchAudio(app, mUrl.substring(MusicApi.PREFIX.length()),
                    (file, err) -> H.post(() -> cb.on(file)));
        } else if (mUrl.startsWith(DeezerApi.PREFIX)) {
            DeezerApi.resolvePreview(mUrl.substring(DeezerApi.PREFIX.length()),
                    preview -> download(preview, cb));
        } else {
            download(mUrl, cb);
        }
    }

    private void download(final String url, final FileCb cb) {
        if (url == null) { cb.on(null); return; }
        new Thread(() -> {
            File out = new File(getCacheDir(), "shorts/music_" + System.currentTimeMillis() + ".mp3");
            //noinspection ResultOfMethodCallIgnored
            out.getParentFile().mkdirs();
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(15000); c.setReadTimeout(20000);
                try (InputStream in = c.getInputStream(); FileOutputStream fo = new FileOutputStream(out)) {
                    byte[] buf = new byte[32 * 1024]; int n;
                    while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
                }
                H.post(() -> cb.on(out));
            } catch (Exception e) {
                H.post(() -> cb.on(null));
            }
        }).start();
    }

    private long videoDurationMs() {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(this, video);
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return d == null ? 0 : Long.parseLong(d);
        } catch (Exception e) { return 0; } finally { try { r.release(); } catch (Exception ignored) { } }
    }

    private void export(File music) {
        final File out = new File(getCacheDir(), "shorts/edit_" + System.currentTimeMillis() + ".mp4");
        //noinspection ResultOfMethodCallIgnored
        out.getParentFile().mkdirs();
        try {
            List<Effect> vfx = new ArrayList<>();
            Crop crop = cropEffect();
            if (crop != null) vfx.add(crop);
            Effect fx = ShortFx.gl(filter);
            if (fx != null) vfx.add(fx);
            if (hasText()) {                                   // টেক্সট সবার শেষে — ফিল্টারে রং বদলাবে না
                int[] vs = videoSize();
                if (vs[0] > 0 && vs[1] > 0) {
                    float[] cw = cropWindow(vs);
                    Effect te = textTool.overlayEffect(Math.round(vs[0] * cw[2]), Math.round(vs[1] * cw[3]), cw, trimmed() ? trimStart : 0);
                    if (te != null) vfx.add(te);
                }
            }
            MediaItem.Builder vmb = new MediaItem.Builder().setUri(video);
            if (trimmed()) vmb.setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(trimStart).setEndPositionMs(trimEnd).build());
            // মূল অডিও: Enhance (আওয়াজ পরিষ্কার) → ভলিউম; Mute হলে একেবারে বাদ
            List<AudioProcessor> vap = new ArrayList<>();
            if (origVol > 0.01f) {
                if (enhance > 0.01f) vap.add(new NoiseCleanAudioProcessor(enhance));
                if (origVol < 0.999f) vap.add(volumeProcessor(origVol));
            }
            EditedMediaItem.Builder vb = new EditedMediaItem.Builder(vmb.build())
                    .setEffects(new Effects(vap, vfx));
            if (origVol <= 0.01f) vb.setRemoveAudio(true);
            List<EditedMediaItemSequence> seqs = new ArrayList<>();
            List<EditedMediaItem> vl = new ArrayList<>();
            vl.add(vb.build());
            seqs.add(new EditedMediaItemSequence(vl));

            if (music != null) {
                long dur = outDurMs();
                MediaItem.Builder mb = new MediaItem.Builder().setUri(Uri.fromFile(music));
                if (dur > 0) mb.setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(mStart).setEndPositionMs(mStart + dur).build());
                List<AudioProcessor> aps = new ArrayList<>();
                aps.add(volumeProcessor(musicLevel));
                List<EditedMediaItem> ml = new ArrayList<>();
                ml.add(new EditedMediaItem.Builder(mb.build())
                        .setRemoveVideo(true)
                        .setEffects(new Effects(aps, new ArrayList<Effect>()))
                        .build());
                seqs.add(new EditedMediaItemSequence(ml, true));   // true = ভিডিওর চেয়ে গান ছোট হলে ঘুরে চলবে
            }

            // ভয়েসওভার: WAV-টা ভিডিওর সময়রেখায় মেলানো, তাই শুধু ট্রিমের অংশটুকু কেটে মিক্স
            File vf = hasVoice() ? voice.getFile() : null;
            if (vf != null) {
                MediaItem.Builder wb = new MediaItem.Builder().setUri(Uri.fromFile(vf));
                long vEnd = Math.min(trimEnd, voice.durationMs());
                if (trimmed() && vEnd > trimStart) wb.setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(trimStart).setEndPositionMs(vEnd).build());
                List<EditedMediaItem> wl = new ArrayList<>();
                wl.add(new EditedMediaItem.Builder(wb.build()).setRemoveVideo(true).build());
                seqs.add(new EditedMediaItemSequence(wl));
            }

            transformer = new Transformer.Builder(this)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(new Transformer.Listener() {
                        @Override public void onCompleted(Composition c, ExportResult r) {
                            busy = false; tvBusy.setVisibility(View.GONE);
                            openSettings(FileProvider.getUriForFile(ShortEditActivity.this,
                                    getPackageName() + ".fileprovider", out));
                        }
                        @Override public void onError(Composition c, ExportResult r, ExportException e) {
                            busy = false; tvBusy.setVisibility(View.GONE);
                            Toast.makeText(ShortEditActivity.this, "Could not apply edits", Toast.LENGTH_LONG).show();
                        }
                    }).build();
            Composition.Builder cb = new Composition.Builder(seqs);
            if (music != null || vf != null) cb.experimentalSetForceAudioTrack(true);   // ভিডিওতে শব্দ না থাকলেও গান বসবে
            transformer.start(cb.build(), out.getAbsolutePath());

            final ProgressHolder ph = new ProgressHolder();
            H.postDelayed(new Runnable() {
                @Override public void run() {
                    if (!busy || transformer == null) return;
                    if (transformer.getProgress(ph) == Transformer.PROGRESS_STATE_AVAILABLE)
                        tvBusy.setText("Processing… " + ph.progress + "%");
                    H.postDelayed(this, 400);
                }
            }, 400);
        } catch (Throwable t) {
            busy = false; tvBusy.setVisibility(View.GONE);
            Toast.makeText(this, "Could not apply edits", Toast.LENGTH_LONG).show();
        }
    }

    private static ChannelMixingAudioProcessor volumeProcessor(float level) {
        ChannelMixingAudioProcessor vol = new ChannelMixingAudioProcessor();
        for (int ch = 1; ch <= 2; ch++)
            for (int o = 1; o <= 2; o++)
                vol.putChannelMixingMatrix(ChannelMixingMatrix.create(ch, o).scaleBy(level));
        return vol;
    }

    private boolean hasText() { return textTool != null && textTool.hasText(); }

    /** ক্রপের পর ফ্রেমের জানালা {x0, y0, প্রস্থ-ভগ্নাংশ, উচ্চতা-ভগ্নাংশ} (ক্রপ না থাকলে পুরোটা) */
    private float[] cropWindow(int[] sz) {
        float[] w = {0f, 0f, 1f, 1f};
        if (cropMode == 0 || sz[0] <= 0 || sz[1] <= 0) return w;
        float a = CROP_ASPECT[cropMode], cur = sz[0] / (float) sz[1];
        if (Math.abs(cur - a) < 0.01f) return w;
        if (cur > a) { w[2] = a / cur; w[0] = (1f - w[2]) / 2f; }
        else { w[3] = cur / a; w[1] = (1f - w[3]) / 2f; }
        return w;
    }

    private boolean hasVoice() { return voice != null && voice.hasAny(); }

    private void openSettings(Uri v) {
        Intent i = new Intent(this, ShortSettingsActivity.class).setData(v);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        settingsLauncher.launch(i);
    }

    // ------------------------------------------------------------ Audio শিট (Mix · Voiceover)

    private void openSheet() {
        if (busy) return;
        snapOrig = origVol; snapMusic = musicLevel; snapEnh = enhance;
        snapVoiceCount = voice == null ? 0 : voice.count();
        snapTitle = mTitle; snapArtist = mArtist; snapCover = mCover; snapUrl = mUrl; snapStart = mStart;
        sheet.setValues(origVol, musicLevel, enhance);
        sheet.open();
        if (player != null && !player.isPlaying()) player.play();
    }

    /** Done: যা বদলানো হয়েছে রেখে শিট বন্ধ */
    private void closeSheetKeep() {
        if (recordingVoice) stopVoiceRecording();
        sheet.close();
        resumePreview();
    }

    /** Cancel: শিট খোলার সময়ের অবস্থায় ফেরত */
    private void closeSheetRevert() {
        if (recordingVoice) stopVoiceRecording();
        origVol = snapOrig; musicLevel = snapMusic; enhance = snapEnh;
        if (voice != null) voice.truncateTo(snapVoiceCount);
        refreshVoicePreview();
        mTitle = snapTitle; mArtist = snapArtist; mCover = snapCover; mUrl = snapUrl; mStart = snapStart;
        sheet.close();
        resumePreview();
    }

    private void resumePreview() {
        applyOrigVolume();
        if (player != null) { player.setRepeatMode(Player.REPEAT_MODE_ONE); player.play(); }
        if (mUrl != null) playMusic(); else MusicPlayer.stop();
        if (tvMusic != null) tvMusic.setText(mUrl == null || mTitle == null ? "Audio" : mTitle);
    }

    private final ShortAudioSheet.Host sheetHost = new ShortAudioSheet.Host() {
        @Override public void onOriginalVolume(float v) { origVol = v; applyOrigVolume(); }
        @Override public void onMusicLevel(float v) { musicLevel = v; MusicPlayer.setVolume(v); }
        @Override public void onEnhance(float s) {
            enhance = s;
            if (s > 0.01f && !enhanceHinted) {
                enhanceHinted = true;
                Toast.makeText(ShortEditActivity.this, "Noise cleaning will be applied after you tap Next", Toast.LENGTH_SHORT).show();
            }
        }
        @Override public boolean hasMusic() { return mUrl != null; }
        @Override public String musicTitle() { return mTitle; }
        @Override public void onAddMusic() {
            if (mUrl != null) openTrim(); else musicLauncher.launch(new Intent(ShortEditActivity.this, MusicPickerActivity.class));
        }
        @Override public void onAddVoice() {
            if (durMs <= 0) { Toast.makeText(ShortEditActivity.this, "Could not read the video", Toast.LENGTH_SHORT).show(); return; }
            if (ContextCompat.checkSelfPermission(ShortEditActivity.this, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) openVoicePanel();
            else micLauncher.launch(Manifest.permission.RECORD_AUDIO);
        }
        @Override public boolean onRecordStart() { return startVoiceRecording(); }
        @Override public void onRecordStop() { stopVoiceRecording(); }
        @Override public void onUndoVoice() {
            if (voice == null || recordingVoice) return;
            long at = voice.undoLast();
            if (at >= 0 && player != null) player.seekTo(Math.max(trimStart, Math.min(trimEnd, at)));
            updateVoiceUi();
            refreshVoicePreview();
        }
        @Override public void onVoiceBack() {
            if (recordingVoice) stopVoiceRecording();
            sheet.showMix();
            resumePreview();
        }
        @Override public void onDone() { closeSheetKeep(); }
        @Override public void onCancel() { closeSheetRevert(); }
    };
    private boolean enhanceHinted = false;

    private void openVoicePanel() {
        sheet.showVoice();
        MusicPlayer.stop();
        if (player != null) {
            player.pause();
            long p = player.getCurrentPosition();
            if (voice == null || !voice.hasAny() || p < trimStart || p >= trimEnd - 300) player.seekTo(trimStart);
        }
        applyOrigVolume();
        updateVoiceUi();
        sheet.setPlayhead(frac(player == null ? trimStart : player.getCurrentPosition()));
    }

    private void updateVoiceUi() {
        List<float[]> segs = new ArrayList<>();
        if (voice != null) for (long[] r : voice.rangesMs()) segs.add(new float[]{frac(r[0]), frac(r[1])});
        sheet.setSegments(segs);
        sheet.setUndoVisible(voice != null && voice.hasAny());
        sheet.setPlayhead(frac(player == null ? trimStart : player.getCurrentPosition()));
    }

    private boolean startVoiceRecording() {
        if (recordingVoice || player == null) return false;
        try {
            if (voice == null)
                voice = new VoiceOverRecorder(new File(getCacheDir(), "shorts/voice_" + System.currentTimeMillis() + ".wav"), durMs);
        } catch (Exception e) {
            Toast.makeText(this, "Could not create the recording file", Toast.LENGTH_SHORT).show();
            return false;
        }
        long pos = player.getCurrentPosition();
        if (pos < trimStart || pos >= trimEnd - 300) { player.seekTo(trimStart); pos = trimStart; }
        MusicPlayer.stop();
        if (voicePlayer != null) voicePlayer.pause();      // আগের রেকর্ড বাজবে না — মাইকে ঢুকে যাবে
        boolean ok = voice.start(pos, () -> { if (recordingVoice) stopVoiceRecording(); });
        if (!ok) {
            Toast.makeText(this, "Could not start the microphone", Toast.LENGTH_SHORT).show();
            return false;
        }
        recordingVoice = true;
        player.setRepeatMode(Player.REPEAT_MODE_OFF);
        applyOrigVolume();                                   // রেকর্ডের সময় ভিডিওর শব্দ বন্ধ
        player.play();
        sheet.setRecording(true);
        sheet.setOpenStart(frac(pos));
        return true;
    }

    private void stopVoiceRecording() {
        if (!recordingVoice) return;
        recordingVoice = false;
        if (voice != null) voice.stop();
        if (player != null) { player.pause(); player.setRepeatMode(Player.REPEAT_MODE_ONE); }
        applyOrigVolume();
        sheet.setRecording(false);
        updateVoiceUi();
        refreshVoicePreview();
    }

    /** রেকর্ড/Undo-র পরে ভয়েসওভার প্রিভিউ নতুন করে বসানো */
    private void refreshVoicePreview() {
        if (!hasVoice()) {
            if (voicePlayer != null) { voicePlayer.release(); voicePlayer = null; }
            return;
        }
        if (voicePlayer == null) voicePlayer = new ExoPlayer.Builder(this).build();
        voicePlayer.setPlayWhenReady(false);
        voicePlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(voice.getFile())));
        voicePlayer.prepare();
    }

    /** ভিডিও চললে ভয়েসওভারও চলবে, সময় মিলিয়ে */
    private void syncVoicePreview() {
        if (voicePlayer == null || player == null) return;
        boolean should = player.isPlaying() && !recordingVoice && hasVoice();
        if (should != voicePlayer.getPlayWhenReady()) voicePlayer.setPlayWhenReady(should);
        if (should && Math.abs(voicePlayer.getCurrentPosition() - player.getCurrentPosition()) > 250)
            voicePlayer.seekTo(player.getCurrentPosition());
    }

    // ------------------------------------------------------------ UI

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private void soon() { Toast.makeText(this, "This feature is coming soon", Toast.LENGTH_SHORT).show(); }

    /**
     * নিচের বারের একটি আইটেম: উপরে আইকন (গোল ব্যাকগ্রাউন্ডসহ), নিচে নাম।
     * circle=false হলে (Next) আইকনের নিজের নীল গোলই দেখাবে।
     */
    private TextView barItem(LinearLayout bar, int iconRes, String label, boolean circle, boolean dim, View.OnClickListener l) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);

        // Short create পেজের মতো ছোট আইকন (30dp) ও ১৪sp বোল্ড লেখা
        ImageView iv = new ImageView(this);
        iv.setImageResource(iconRes);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (dim) iv.setAlpha(0.5f);
        item.addView(iv, new LinearLayout.LayoutParams(dp(22), dp(22)));

        TextView lb = text(label, 11, dim ? 0xFF8A8A8A : Color.WHITE);
        lb.setTypeface(null, Typeface.BOLD);
        lb.setSingleLine(true);
        lb.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dp(2);
        item.addView(lb, lp);

        item.setOnClickListener(l);
        bar.addView(item, new LinearLayout.LayoutParams(0, -2, 1f));
        return lb;
    }

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        root.addView(main, new FrameLayout.LayoutParams(-1, -1));

        // ---- ভিডিও এলাকা (উপরে)
        FrameLayout videoBox = new FrameLayout(this);
        videoBox.setBackgroundColor(0xFF000000);
        main.addView(videoBox, new LinearLayout.LayoutParams(-1, 0, 1f));

        sv = new SurfaceView(this);
        FrameLayout.LayoutParams vp = new FrameLayout.LayoutParams(-1, -1);
        vp.gravity = Gravity.CENTER;
        videoBox.addView(sv, vp);

        // ← পেছনে (উপরে বামে)
        ImageView back = backBtn = new ImageView(this);
        back.setImageResource(R.drawable.back_to_rec);
        back.setScaleType(ImageView.ScaleType.FIT_CENTER);
        back.setPadding(dp(5), dp(5), dp(5), dp(5));
        back.setOnClickListener(v -> onBackPressed());
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(dp(40), dp(40));
        bp.gravity = Gravity.TOP | Gravity.START; bp.topMargin = dp(30); bp.leftMargin = dp(16);
        videoBox.addView(back, bp);

        // Effects স্ট্রিপ: ভিডিওর নিচের দিকে, পাশে স্ক্রল
        chipScroll = new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipScroll.setVisibility(View.GONE);
        LinearLayout chipRow = new LinearLayout(this);
        chipRow.setGravity(Gravity.CENTER_VERTICAL);
        chipRow.setPadding(dp(10), 0, dp(10), 0);
        int sel = filter < 3 ? 0 : filter;
        final int[] order = {0, ShortFx.ULTRA, 3, 4, 5, 6, 7, 8, 9, 10, 11};
        for (int i : order) {
            final int idx = i;
            TextView c = text(i == 0 || i == 4 || i == ShortFx.ULTRA ? "" : ShortFx.NAMES[i], 11, Color.WHITE);
            if (i == 0) c.setTag("nofx");
            if (i == 4) c.setTag("glitch");
            if (i == ShortFx.ULTRA) c.setTag("ultra");
            c.setMaxLines(2);
            c.setPadding(dp(4), 0, dp(4), 0);
            c.setOnClickListener(v -> selectEffect(idx));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(64), dp(64));
            lp.leftMargin = dp(5); lp.rightMargin = dp(5);
            chipRow.addView(c, lp);
            chips.add(c); chipIdx.add(i);
            styleChip(c, i == sel);
        }
        chipScroll.addView(chipRow, new FrameLayout.LayoutParams(-2, -2));
        FrameLayout.LayoutParams csp = new FrameLayout.LayoutParams(-1, dp(72));
        csp.gravity = Gravity.BOTTOM; csp.bottomMargin = dp(12);
        videoBox.addView(chipScroll, csp);

        // ---- নিচের বার: Audio · Edit · Effects · Text · Stickers · Next
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(0xFF222222);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(4), dp(6), dp(4), dp(8));

        tvMusic = barItem(bar, R.drawable.music_cam, "Audio", true, false, v -> openSheet());
        barItem(bar, R.drawable.edit_video, "Edit", true, false, v -> openEdit());
        barItem(bar, R.drawable.effects_cam, "Effects", true, false, v -> toggleStrip());
        barItem(bar, R.drawable.text_on_video, "Text", true, false, v -> openText());
        barItem(bar, R.drawable.sticker_on_video, "Stickers", true, false, v -> openStickers());
        barItem(bar, R.drawable.ic_final_next, "Next", false, false, v -> onNext());
        main.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        textTool = new ShortTextTool(this, textHost, root, videoBox, main, bar);
        editTool = new ShortEditTool(this, editHost, videoBox, main, bar);
        videoBox.addOnLayoutChangeListener((v, l, t, r, b, oL, oT, oR, oB) -> {
            if ((r - l) != (oR - oL) || (b - t) != (oB - oT)) fitVideo(lastVw, lastVh);
        });

        sheet = new ShortAudioSheet(this, sheetHost);
        root.addView(sheet.view(), new FrameLayout.LayoutParams(-1, -1));
        stickerSheet = new ShortStickerSheet(this, stickerHost);
        root.addView(stickerSheet.view(), new FrameLayout.LayoutParams(-1, -1));

        tvBusy = text("Processing…", 16, Color.WHITE);
        tvBusy.setBackgroundColor(0xAA000000);
        tvBusy.setVisibility(View.GONE);
        tvBusy.setClickable(true);
        root.addView(tvBusy, new FrameLayout.LayoutParams(-1, -1));
        return root;
    }

    // ------------------------------------------------------------ Text

    private void openText() {
        if (busy || textTool == null) return;
        textTool.open();
    }

    private final ShortTextTool.Host textHost = new ShortTextTool.Host() {
        @Override public long trimStart() { return trimStart; }
        @Override public long trimEnd() { return trimEnd; }
        @Override public long pos() { return player == null ? 0 : player.getCurrentPosition(); }
        @Override public void seek(long ms) { if (player != null) player.seekTo(ms); }
        @Override public boolean playing() { return player != null && player.isPlaying(); }
        @Override public void setPlaying(boolean play) {
            if (player == null) return;
            if (play) {
                if (player.getCurrentPosition() >= trimEnd - 50) player.seekTo(trimStart);
                player.play();
            } else player.pause();
        }
        @Override public boolean hasAudio() { return mUrl != null; }
        @Override public String audioLabel() { return mTitle; }
        @Override public void openAudio() { openSheet(); }
        @Override public void panelVisible(boolean v) {
            if (backBtn != null) backBtn.setVisibility(v ? View.GONE : View.VISIBLE);
            if (v && chipScroll != null) chipScroll.setVisibility(View.GONE);
        }
    };

    // ------------------------------------------------------------ Stickers (ShortStickerSheet)

    private void openStickers() {
        if (busy || stickerSheet == null) return;
        stickerSheet.show();
    }

    private void addGalleryImage(android.graphics.Bitmap b) {
        TextItem t = new TextItem();
        t.text = "Sticker"; t.bmp = b; t.size = 0.38f;
        textTool.addItem(t);
    }

    private final ShortStickerSheet.Host stickerHost = new ShortStickerSheet.Host() {
        @Override public void addText(String text, int color, int bg, float size) {
            TextItem t = new TextItem();
            t.text = text; t.color = color; t.bg = bg; t.size = size;
            textTool.addItem(t);
        }
        @Override public void addImage(android.graphics.Bitmap b) { addGalleryImage(b); }
        @Override public void pickGallery() { imgLauncher.launch("image/*"); }
        @Override public void pickMusic() { musicLauncher.launch(new Intent(ShortEditActivity.this, MusicPickerActivity.class)); }
        @Override public void withLocationPermission(Runnable granted) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                granted.run();
            } else {
                pendingLoc = granted;
                locLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        }
    };

    // ------------------------------------------------------------ Edit স্ক্রিন (ShortEditTool)

    private void openEdit() {
        if (busy || editTool == null) return;
        if (durMs < ShortUploader.MIN_MS) { Toast.makeText(this, "Video is too short", Toast.LENGTH_SHORT).show(); return; }
        editTool.open();
    }

    private final ShortEditTool.Host editHost = new ShortEditTool.Host() {
        @Override public long durMs() { return durMs; }
        @Override public long trimStart() { return trimStart; }
        @Override public long trimEnd() { return trimEnd; }
        @Override public void setTrim(long s, long e) { trimStart = s; trimEnd = e; }
        @Override public long pos() { return player == null ? 0 : player.getCurrentPosition(); }
        @Override public void seek(long ms) { if (player != null) player.seekTo(ms); }
        @Override public boolean playing() { return player != null && player.isPlaying(); }
        @Override public void setPlaying(boolean play) {
            if (player == null) return;
            if (play) {
                if (player.getCurrentPosition() >= trimEnd - 50 || player.getCurrentPosition() < trimStart) player.seekTo(trimStart);
                player.play();
            } else player.pause();
        }
        @Override public boolean hasAudio() { return mUrl != null; }
        @Override public String audioLabel() { return mTitle; }
        @Override public void openAudio() { openSheet(); }
        @Override public void openEffects() { toggleStrip(); }
        @Override public void openText() { ShortEditActivity.this.openText(); }
        @Override public void soon() { ShortEditActivity.this.soon(); }
        @Override public void openStickers() { ShortEditActivity.this.openStickers(); }
        @Override public List<TextItem> texts() { return textTool.items(); }
        @Override public void panelVisible(boolean v) {
            if (backBtn != null) backBtn.setVisibility(v ? View.GONE : View.VISIBLE);
            if (v && chipScroll != null) chipScroll.setVisibility(View.GONE);
        }
    };

    private static String fmt(long ms) {
        return String.format(java.util.Locale.US, "%d:%02d.%d", ms / 60000, (ms / 1000) % 60, (ms / 100) % 10);
    }

    private void toggleStrip() {
        chipScroll.setVisibility(chipScroll.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
    }

    private void selectEffect(int idx) {
        filter = idx;
        applyFilterPreview();
        for (int i = 0; i < chips.size(); i++) styleChip(chips.get(i), chipIdx.get(i) == idx);
    }

    private void styleChip(TextView c, boolean on) {
        if ("nofx".equals(c.getTag())) {
            c.setBackground(new ShortsIconDrawable(on, getResources().getDisplayMetrics().density));
            return;
        }
        if ("ultra".equals(c.getTag())) {
            c.setBackground(new UltraIconDrawable("4K", on, getResources().getDisplayMetrics().density));
            return;
        }
        if ("glitch".equals(c.getTag())) {
            c.setBackground(new GlitchIconDrawable(on, getResources().getDisplayMetrics().density));
            return;
        }
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(on ? 0xAA555555 : 0xAA222222);
        g.setStroke(dp(on ? 3 : 2), on ? Color.WHITE : 0xFF777777);
        c.setBackground(g);
    }

    @Override
    protected void onDestroy() {
        H.removeCallbacksAndMessages(null);
        MusicPlayer.stop();
        if (player != null) { player.release(); player = null; }
        if (voicePlayer != null) { voicePlayer.release(); voicePlayer = null; }
        if (voice != null) { voice.release(true); voice = null; }
        if (transformer != null && busy) { try { transformer.cancel(); } catch (Exception ignored) { } }
        super.onDestroy();
    }
}
