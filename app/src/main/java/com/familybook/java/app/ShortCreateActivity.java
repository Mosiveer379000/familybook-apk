package com.familybook.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.graphics.Bitmap;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.OptIn;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraEffect;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.UseCaseGroup;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FallbackStrategy;
import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.Transformer;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Shorts-এর নিজস্ব ক্যামেরা (CameraX): ওপরে প্রোগ্রেস বার, ✕, ফ্ল্যাশ, ⋯, টাইমার (0:28 / 1:20);
 * ডানে Music / Effects / Speed; নিচে None · HD · 4K · iPhone · Glitch; Gallery · Record · Flip।
 * রেকর্ড চাপলে শুরু, আবার চাপলে থামে (একাধিক ক্লিপ); ✓ Next চাপলে জোড়া লেগে ShortEditActivity-তে যায়।
 */
@OptIn(markerClass = UnstableApi.class)
public class ShortCreateActivity extends BaseActivity {

    private static final int REQ_PERM = 71;
    private static final long MAX = ShortUploader.MAX_MS;

    private final Handler H = new Handler(Looper.getMainLooper());
    private PreviewView pv;
    private FxSurfaceProcessor fxProc;
    private boolean fxOn = true;        // ইফেক্ট সবসময় যুক্ত: ইফেক্ট বদলালে রিবাইন্ড/ব্ল্যাক ব্লিংক নেই
    private ImageView freezeView;
    private final Runnable hideFreeze = () -> { if (freezeView != null) freezeView.setVisibility(View.GONE); };
    private ProcessCameraProvider provider;
    private Camera camera;
    private VideoCapture<Recorder> vc;
    private Recording recording;
    private boolean back = true, torch = false, pendingNext = false, busy = false;
    private int filter = 0;
    private Quality quality = Quality.FHD;

    private final List<File> clips = new ArrayList<>();
    private long doneMs = 0, clipStartRt = 0;
    private File curFile;

    private View progress;
    private FrameLayout progressTrack;
    private TextView tvTime, tvMusic, tvNext, tvBusy;
    private LinearLayout chipRow;
    private HorizontalScrollView chipScroll;
    private final List<View> chips = new ArrayList<>();
    private boolean fxOpen = true;
    private ImageView ivFlash, ivCancel, recImg;
    private View moreFx;
    private View recDot;
    /** রেকর্ড চলার সময় যে আইকনগুলো হালকা ধূসর হবে (রেকর্ড বাটন বাদে) */
    private final List<View> dimWhenRec = new ArrayList<>();
    private static final float DIM_ALPHA = 0.3f;

    private String mTitle, mArtist, mCover, mUrl;
    private int mStart = 0;
    private ActivityResultLauncher<Intent> trimLauncher;

    private ActivityResultLauncher<Intent> editLauncher, musicLauncher;
    private ActivityResultLauncher<String> galleryLauncher;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateTime();
            if (recording != null) {
                if (total() >= MAX) stopRecording(false);
                H.postDelayed(this, 80);
            }
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (ShortUploader.isSubmitted()) {
            Toast.makeText(this, "আগের Short আপলোড শেষ হওয়া পর্যন্ত অপেক্ষা করুন", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        fxProc = new FxSurfaceProcessor();
        editLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                r -> { if (r.getResultCode() == RESULT_OK) finish(); });
        musicLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
            if (r.getResultCode() == RESULT_OK && r.getData() != null) {
                Intent d = r.getData();
                mTitle = d.getStringExtra(MusicPickerActivity.EXTRA_TITLE);
                mArtist = d.getStringExtra(MusicPickerActivity.EXTRA_ARTIST);
                mCover = d.getStringExtra(MusicPickerActivity.EXTRA_COVER);
                mUrl = d.getStringExtra(MusicPickerActivity.EXTRA_URL);
                mStart = 0;
                refreshMusicLabel();
                openTrim();                       // গানের কোন অংশ থেকে শুরু হবে বাছুন
            }
        });
        trimLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
            int c = r.getResultCode();
            if (c == RESULT_OK && r.getData() != null) mStart = r.getData().getIntExtra(MusicTrimActivity.EXTRA_START, 0);
            else if (c == MusicTrimActivity.RESULT_CHANGE) musicLauncher.launch(new Intent(this, MusicPickerActivity.class));
            else if (c == MusicTrimActivity.RESULT_DELETE) { mUrl = null; mTitle = null; mStart = 0; }
            refreshMusicLabel();
        });
        galleryLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) openEdit(uri, 0);
        });
        setContentView(buildUi());
        if (hasPerms()) startCamera(); else requestPermissions(
                new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, REQ_PERM);
    }

    private boolean hasPerms() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == REQ_PERM) {
            if (hasPerms()) startCamera();
            else { Toast.makeText(this, "ক্যামেরা ও মাইক্রোফোনের অনুমতি দরকার", Toast.LENGTH_LONG).show(); finish(); }
        }
    }

    // ------------------------------------------------------------ CameraX

    private void startCamera() {
        final com.google.common.util.concurrent.ListenableFuture<ProcessCameraProvider> f =
                ProcessCameraProvider.getInstance(this);
        f.addListener(() -> {
            try { provider = f.get(); bind(); }
            catch (Exception e) { Toast.makeText(this, "ক্যামেরা চালু করা যায়নি", Toast.LENGTH_SHORT).show(); }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bind() {
        if (provider == null) return;
        // রিবাইন্ডের সময় শেষ ফ্রেম ধরে রাখি, যাতে কালো ঝলক না আসে
        try {
            Bitmap bmp = pv.getBitmap();
            if (bmp != null && freezeView != null) {
                freezeView.setImageBitmap(bmp);
                freezeView.setVisibility(View.VISIBLE);
                H.removeCallbacks(hideFreeze);
                H.postDelayed(hideFreeze, 2500);
            }
        } catch (Throwable ignored) { }
        provider.unbindAll();
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(pv.getSurfaceProvider());
        Recorder rc = new Recorder.Builder()
                .setQualitySelector(QualitySelector.from(quality,
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                .build();
        vc = VideoCapture.withOutput(rc);
        CameraSelector sel = back ? CameraSelector.DEFAULT_BACK_CAMERA : CameraSelector.DEFAULT_FRONT_CAMERA;
        try {
            UseCaseGroup.Builder gb = new UseCaseGroup.Builder().addUseCase(preview).addUseCase(vc);
            if (fxOn) {   // লাইভ ইফেক্ট: প্রিভিউ + রেকর্ড দুটোতেই বসে
                gb.addEffect(new FxCameraEffect(fxProc));
            }
            camera = provider.bindToLifecycle(this, sel, gb.build());
            applyTorch();
        } catch (Exception e) {
            Toast.makeText(this, "এই ক্যামেরা চালানো যাচ্ছে না", Toast.LENGTH_SHORT).show();
        }
    }

    private void applyTorch() {
        if (camera != null && camera.getCameraInfo().hasFlashUnit()) camera.getCameraControl().enableTorch(torch);
        ivFlash.setImageResource(torch ? R.drawable.cam_flash_on : R.drawable.cam_flash_off);
    }

    // ------------------------------------------------------------ রেকর্ড

    private long total() { return doneMs + (recording != null ? SystemClock.elapsedRealtime() - clipStartRt : 0); }

    private void toggleRecord() {
        if (busy || vc == null) return;
        if (recording != null) { stopRecording(false); return; }
        if (total() >= MAX) { Toast.makeText(this, "সর্বোচ্চ সময় শেষ", Toast.LENGTH_SHORT).show(); return; }
        File dir = new File(getCacheDir(), "shorts");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        curFile = new File(dir, "cam_" + System.currentTimeMillis() + ".mp4");
        try {
            androidx.camera.video.PendingRecording pr =
                    vc.getOutput().prepareRecording(this, new FileOutputOptions.Builder(curFile).build());
            if (mUrl == null) pr = pr.withAudioEnabled();   // গান থাকলে মাইক বন্ধ: গান আলাদা মিক্স হবে
            recording = pr.start(ContextCompat.getMainExecutor(this), this::onRecEvent);
        } catch (SecurityException e) {
            Toast.makeText(this, "মাইক্রোফোনের অনুমতি দরকার", Toast.LENGTH_SHORT).show();
            return;
        }
        clipStartRt = SystemClock.elapsedRealtime();
        setRecordingUi(true);
        if (mUrl != null) MusicPlayer.play(this, "cam", mUrl, mStart + (int) doneMs, 0);
        recDot.animate().scaleX(REC_SCALE).scaleY(REC_SCALE).setDuration(120).start();
        H.post(tick);
    }

    private void stopRecording(boolean thenNext) {
        pendingNext = thenNext;
        MusicPlayer.stop();
        if (recording != null) { recording.stop(); }
        else if (thenNext) goNext();
    }

    private void onRecEvent(VideoRecordEvent e) {
        if (!(e instanceof VideoRecordEvent.Finalize)) return;
        long len = SystemClock.elapsedRealtime() - clipStartRt;
        recording = null;
        setRecordingUi(false);
        MusicPlayer.stop();
        H.removeCallbacks(tick);
        recDot.animate().scaleX(CENTER_SCALE).scaleY(CENTER_SCALE).setDuration(120).start();
        VideoRecordEvent.Finalize fe = (VideoRecordEvent.Finalize) e;
        if (curFile != null && curFile.exists() && curFile.length() > 0 && (!fe.hasError() || len > 500)) {
            clips.add(curFile);
            doneMs += len;
        } else if (curFile != null) {
            //noinspection ResultOfMethodCallIgnored
            curFile.delete();
        }
        updateTime();
        if (pendingNext) { pendingNext = false; goNext(); }
    }

    private void deleteLastClip() {
        if (recording != null || clips.isEmpty()) return;
        File f = clips.remove(clips.size() - 1);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
        doneMs = clips.isEmpty() ? 0 : Math.max(0, doneMs - lastLen());
        if (clips.isEmpty()) doneMs = 0;
        updateTime();
    }

    private long lastLen() {
        // ডিলিট করা ক্লিপের দৈর্ঘ্য: অবশিষ্ট ক্লিপ থেকে নতুন করে হিসাব
        long sum = 0;
        for (File f : clips) sum += durOf(f);
        return doneMs - sum;
    }

    private static long durOf(File f) {
        android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
        try {
            r.setDataSource(f.getAbsolutePath());
            String d = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
            return d == null ? 0 : Long.parseLong(d);
        } catch (Exception e) { return 0; } finally { try { r.release(); } catch (Exception ignored) { } }
    }

    private void updateTime() {
        long t = Math.min(total(), MAX);
        tvTime.setText(String.format(Locale.US, "%d:%02d / 1:20", t / 60000, (t / 1000) % 60));
        int w = progressTrack.getWidth();
        progress.getLayoutParams().width = (int) (w * (t / (float) MAX));
        progress.requestLayout();
        boolean has = !clips.isEmpty() || recording != null;
        tvNext.setVisibility(!clips.isEmpty() && recording == null ? View.VISIBLE : View.GONE);
        ivCancel.setVisibility(has ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------ Next → ক্লিপ জোড়া → এডিট

    private void refreshMusicLabel() {
        tvMusic.setText(mUrl == null ? "Music" : "♪ " + (mTitle == null ? "Music" : mTitle));
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

    private void goNext() {
        if (clips.isEmpty()) return;
        if (doneMs < ShortUploader.MIN_MS) {
            Toast.makeText(this, "কমপক্ষে ৩ সেকেন্ড রেকর্ড করুন", Toast.LENGTH_SHORT).show();
            return;
        }
        // ইফেক্ট রেকর্ডেই বসে গেছে, তাই এডিটে ফিল্টার 0
        if (clips.size() == 1) { openEdit(uriOf(clips.get(0)), 0); return; }
        busy = true;
        tvBusy.setVisibility(View.VISIBLE);
        final File out = new File(clips.get(0).getParentFile(), "cam_merged_" + System.currentTimeMillis() + ".mp4");
        List<EditedMediaItem> items = new ArrayList<>();
        for (File f : clips) items.add(new EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(f))).build());
        List<EditedMediaItemSequence> seqs = new ArrayList<>();
        seqs.add(new EditedMediaItemSequence(items));
        Transformer t = new Transformer.Builder(this).addListener(new Transformer.Listener() {
            @Override public void onCompleted(Composition c, ExportResult r) {
                busy = false; tvBusy.setVisibility(View.GONE);
                openEdit(uriOf(out), 0);
            }
            @Override public void onError(Composition c, ExportResult r, ExportException e) {
                busy = false; tvBusy.setVisibility(View.GONE);
                Toast.makeText(ShortCreateActivity.this, "ক্লিপ জোড়া লাগানো যায়নি", Toast.LENGTH_SHORT).show();
            }
        }).build();
        t.start(new Composition.Builder(seqs).build(), out.getAbsolutePath());
    }

    private Uri uriOf(File f) { return FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f); }

    private void openEdit(Uri v, int f) {
        Intent i = new Intent(this, ShortEditActivity.class).setData(v);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        i.putExtra(ShortEditActivity.EXTRA_FILTER, f);
        i.putExtra(MusicPickerActivity.EXTRA_TITLE, mTitle);
        i.putExtra(MusicPickerActivity.EXTRA_ARTIST, mArtist);
        i.putExtra(MusicPickerActivity.EXTRA_COVER, mCover);
        i.putExtra(MusicPickerActivity.EXTRA_URL, mUrl);
        i.putExtra(MusicTrimActivity.EXTRA_START, mStart);
        editLauncher.launch(i);
    }

    // ------------------------------------------------------------ ফিল্টার চিপ

    private void selectChip(int idx) {
        if (recording != null) return;     // রেকর্ড চলার সময় ইফেক্ট বদলায় না (আইকন হালকা ধূসর, কোনো টোস্ট নেই)
        if (idx == filter && idx != 0) idx = 0;      // একই ইফেক্টে আবার চাপলে বন্ধ (No Fx)
        Quality q = idx == 2 ? Quality.UHD : idx == 1 ? Quality.HD : Quality.FHD;   // 4K চিপ: UHD রেকর্ডিং (ডিভাইস পারলে) + Ultra HD ইফেক্ট
        boolean needBind = q != quality;
        quality = q;
        filter = idx;
        fxProc.setMode(ShortFx.modeFor(idx));      // শুধু মোড বদলায়, ক্যামেরা রিবাইন্ড হয় না
        if (needBind) bind();
        for (int i = 0; i < chips.size(); i++) styleChip(chips.get(i), i == idx);
        recDot = chips.get(idx) != null ? chips.get(idx) : recDot;   // রেকর্ড চাপলে এই আইকনটাই ছোট হয়
        centerChip(idx, true);
    }

    private static final float CENTER_SCALE = 1.25f;   // 64dp × 1.25 = 80dp = রিংয়ের ভেতরের ব্যাস (কোনো ফাঁক নেই)
    private static final float REC_SCALE = 70f / 64f;   // রেকর্ড চলার সময় আইকন ঠিক 70dp (64dp বেস × 1.09375)

    private static final int RING_BOX = 88;   // রিংয়ের মাপ (dp)। আইকন স্কেল হয়ে 80dp, তাই চারপাশে 4dp করে রিং দেখা যায়
    private android.graphics.drawable.Drawable recRing;   // কোডে আঁকা আধা-স্বচ্ছ রিং

    /** রিং সবসময় দেখা যায় — স্ক্রলে লুকায় না (এই মেথড এখন কিছু করে না, কল রাখা হয়েছে যাতে বাকি কোড না বদলাতে হয়) */
    private void setRingVisible(boolean show) { }

    /** নির্বাচিত আইকন ডান থেকে বামে স্ক্রল করে ঠিক রেকর্ড-রিংয়ের ওপর আনে */
    private void centerChip(int idx, boolean animate) {
        final View c = idx >= 0 && idx < chips.size() ? chips.get(idx) : null;
        if (c == null || chipScroll == null) return;
        chipScroll.post(() -> {
            int target = c.getLeft() + c.getWidth() / 2 - chipScroll.getWidth() / 2;
            if (animate) chipScroll.smoothScrollTo(Math.max(0, target), 0);
            else chipScroll.scrollTo(Math.max(0, target), 0);
            H.removeCallbacks(settle);
            H.postDelayed(settle, 350);   // মাঝে পৌঁছালে রিং দেখাতে
        });
    }

    /** স্ক্রল থামলে রিংয়ের সবচেয়ে কাছের আইকন নির্বাচিত হয় (TikTok/YouTube-এর মতো) */
    private final Runnable settle = () -> {
        int mid = chipScroll.getScrollX() + chipScroll.getWidth() / 2;
        int best = filter, bestD = Integer.MAX_VALUE;
        for (int i = 0; i < chips.size(); i++) {
            View c = chips.get(i);
            if (c == null) continue;
            int d = Math.abs(c.getLeft() + c.getWidth() / 2 - mid);
            if (d < bestD) { bestD = d; best = i; }
        }
        if (best != filter) {
            if (recording != null) { centerChip(filter, true); return; }
            selectChipExact(best);
        } else {
            View cur = chips.get(filter);
            int target = cur == null ? 0 : Math.max(0, cur.getLeft() + cur.getWidth() / 2 - chipScroll.getWidth() / 2);
            if (Math.abs(chipScroll.getScrollX() - target) <= 2) setRingVisible(true);   // আইকন মাঝে → নিয়ন রিং শো
            else centerChip(filter, true);
        }
    };

    /** স্ক্রল থেকে নির্বাচন: একই আইকনে আবার চাপলে বন্ধ হওয়ার নিয়ম এখানে খাটে না */
    private void selectChipExact(int idx) {
        Quality q = idx == 2 ? Quality.UHD : idx == 1 ? Quality.HD : Quality.FHD;
        boolean needBind = q != quality;
        quality = q;
        filter = idx;
        fxProc.setMode(ShortFx.modeFor(idx));
        if (needBind) bind();
        for (int i = 0; i < chips.size(); i++) styleChip(chips.get(i), i == idx);
        recDot = chips.get(idx) != null ? chips.get(idx) : recDot;
        centerChip(idx, true);
    }

    private void styleChip(View c, boolean on) {
        if (c == null) return;
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        if (c instanceof ImageView) {                 // PNG আইকন: নির্বাচিতে সাদা রিং
            // আইকনে কোনো স্ট্রোক নেই — নীল রিংটাই রেকর্ড বাটনের রিং
            if (android.os.Build.VERSION.SDK_INT >= 23) c.setForeground(null);
            c.setBackground(null);
            c.setScaleX(on ? CENTER_SCALE : 1f); c.setScaleY(on ? CENTER_SCALE : 1f);
            return;
        }
        g.setColor(0xFF222222);                 // সব টেক্সট-ইফেক্টের একই চেহারা: গাঢ় ভরাট + সাদা স্ট্রোক (নীল নেই)
        g.setStroke(dp(2), Color.WHITE);
        c.setBackground(g);
        c.setScaleX(on ? CENTER_SCALE : 1f); c.setScaleY(on ? CENTER_SCALE : 1f);
    }

    // ------------------------------------------------------------ UI

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private View toolButton(int iconRes, String label, View.OnClickListener l, TextView[] labelOut) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        TextView lb = text(label, 14, Color.WHITE);
        lb.setShadowLayer(4, 0, 0, Color.BLACK);
        lb.setMaxWidth(dp(150));
        lb.setSingleLine(true);
        ImageView ic = new ImageView(this);
        ic.setImageResource(iconRes);
        ic.setScaleType(ImageView.ScaleType.FIT_CENTER);
        row.addView(lb, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(30), dp(30));
        ip.leftMargin = dp(8);
        row.addView(ic, ip);
        row.setOnClickListener(l);
        if (labelOut != null) labelOut[0] = lb;
        return row;
    }

    private ImageView iconView(int res, View.OnClickListener l) {
        ImageView v = new ImageView(this);
        v.setImageResource(res);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        v.setOnClickListener(l);
        return v;
    }

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        pv = new PreviewView(this);
        pv.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);   // TextureView: ফিল্টার প্রিভিউয়ে কাজ করে
        pv.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        root.addView(pv, new FrameLayout.LayoutParams(-1, -1));
        freezeView = new ImageView(this);
        freezeView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        freezeView.setVisibility(View.GONE);
        root.addView(freezeView, new FrameLayout.LayoutParams(-1, -1));
        pv.getPreviewStreamState().observe(this, st -> {
            if (st == PreviewView.StreamState.STREAMING) { H.removeCallbacks(hideFreeze); H.postDelayed(hideFreeze, 150); }
        });

        // প্রোগ্রেস বার (লাল)
        progressTrack = new FrameLayout(this);
        progressTrack.setBackgroundColor(0x44FFFFFF);
        progress = new View(this);
        progress.setBackgroundColor(0xFFE53935);
        progressTrack.addView(progress, new FrameLayout.LayoutParams(0, -1));
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(-1, dp(4));
        pp.topMargin = dp(6); pp.leftMargin = dp(12); pp.rightMargin = dp(12);
        root.addView(progressTrack, pp);

        // ✕ (ক্যামেরা বন্ধ) — ওপরে বামে
        TextView close = text("✕", 20, Color.WHITE);
        close.setOnClickListener(v -> finish());
        FrameLayout.LayoutParams cxp = new FrameLayout.LayoutParams(dp(40), dp(40));
        cxp.gravity = Gravity.TOP | Gravity.START; cxp.topMargin = dp(22); cxp.leftMargin = dp(10);
        root.addView(close, cxp);

        // ফ্ল্যাশ (ওপরে মাঝে) — ✕ বাটন নেই
        ivFlash = iconView(R.drawable.cam_flash_off, v -> { torch = !torch; applyTorch(); });
        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(dp(30), dp(30));
        fp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL; fp.topMargin = dp(26);
        root.addView(ivFlash, fp);

        // টাইমার
        tvTime = text("0:00 / 1:20", 16, Color.WHITE);
        tvTime.setShadowLayer(4, 0, 0, Color.BLACK);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-2, -2);
        tp.gravity = Gravity.TOP | Gravity.END; tp.topMargin = dp(30); tp.rightMargin = dp(16);
        root.addView(tvTime, tp);

        // ডানের টুল: Music / Speed / Effects
        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.VERTICAL);
        TextView[] lbl = new TextView[1];
        View flipTop = toolButton(R.drawable.cam_rotate, "Rotate", v -> flip(), null);   // Music-এর মতো একই ১৪sp লেখা
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(-2, -2);
        fl.gravity = Gravity.END; fl.rightMargin = dp(0); fl.bottomMargin = dp(16);
        tools.addView(flipTop, fl);
        dimWhenRec.add(flipTop);
        View musicBtn = toolButton(R.drawable.cam_music, "Music", v -> {
            if (recording != null) return;
            if (!clips.isEmpty()) {
                Toast.makeText(this, "ক্লিপ রেকর্ড হওয়ার পর গান বদলানো যায় না", Toast.LENGTH_SHORT).show();
                return;
            }
            if (mUrl != null) openTrim();
            else musicLauncher.launch(new Intent(this, MusicPickerActivity.class));
        }, lbl);
        tools.addView(musicBtn);
        dimWhenRec.add(musicBtn);
        tvMusic = lbl[0];
        View spd = toolButton(R.drawable.cam_speed, "Speed", v ->
                Toast.makeText(this, "Speed সুবিধা পরের ধাপে আসছে", Toast.LENGTH_SHORT).show(), null);
        View eff = toolButton(R.drawable.cam_effects, "Effects", v -> toggleFx(), null);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, dp(30));
        tl.topMargin = dp(16);
        tools.addView(spd, tl);
        tools.addView(eff, tl);
        dimWhenRec.add(spd);
        dimWhenRec.add(eff);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(-2, -2);
        tlp.gravity = Gravity.TOP | Gravity.END; tlp.topMargin = dp(100); tlp.rightMargin = dp(12);
        root.addView(tools, tlp);

        // আধা-স্বচ্ছ সাদা রিং চিপ-স্ট্রিপের পেছনে: আইকন এর ওপর দিয়ে স্ক্রল করে, রিংয়ের কিনারা আইকনের সাদা স্ট্রোকের মতো দেখায়
        // রিং কোড দিয়ে আঁকা (PNG নয়) — চারপাশে সমান পুরু আধা-স্বচ্ছ সাদা রিং, বাম-ডানে কোনো ফারাক নেই
        GradientDrawable ringG = new GradientDrawable();
        ringG.setShape(GradientDrawable.OVAL);
        ringG.setColor(Color.TRANSPARENT);
        ringG.setStroke(dp((RING_BOX - 80) / 2), 0x80FFFFFF);   // 88dp বক্সে 4dp রিং: ভেতরে 80dp আইকন, বাইরে 88dp
        recRing = ringG;
        View ringV = new View(this);
        ringV.setBackground(recRing);
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(dp(RING_BOX), dp(RING_BOX));
        rp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        rp.bottomMargin = dp(112 - RING_BOX / 2);   // মাঝ-রেখা 112dp, চিপ-স্ট্রিপের মাঝ-রেখার সাথে মেলে
        root.addView(ringV, rp);

        // Effects স্ট্রিপ: রেকর্ড বাটনের ডানে (8K, Glitch, ...), পাশে স্ক্রল হয়
        chipScroll = new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setGravity(Gravity.CENTER_VERTICAL);
        // প্রথম ও শেষ আইকনও যেন মাঝে (রিংয়ের ওপর) আসতে পারে — তাই দুই পাশে অর্ধেক-পর্দা প্যাডিং
        int sidePad = getResources().getDisplayMetrics().widthPixels / 2 - dp(32) - dp(6);
        chipRow.setPadding(sidePad, 0, sidePad, 0);
        for (int i = 0; i < ShortFx.NAMES.length; i++) chips.add(null);   // 0 = No Fx (Shorts আইকন)
        int[] order = {0, 2, 4, 1, 3, 5, 6, 7, 8, 9, 10, 11};
        for (int idx : order) {
            if (idx >= ShortFx.NAMES.length) continue;
            View c;
            if (idx == 0 || idx == 2 || idx == 4) {
                ImageView iv = new ImageView(this);
                iv.setImageResource(idx == 0 ? R.drawable.cam_no_fx : idx == 2 ? R.drawable.cam_ultra_8k : R.drawable.cam_glitch_fx);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                iv.setPadding(0, 0, 0, 0);   // আইকন পুরো 64dp ভরবে, স্ট্রোক ঠিক কিনারায়
                c = iv;
            } else {
                TextView t = text(ShortFx.NAMES[idx], 11, Color.WHITE);
                t.setMaxLines(2);
                t.setPadding(dp(4), 0, dp(4), 0);
                c = t;
            }
            styleChip(c, false);
            final int fi = idx;
            c.setOnClickListener(v -> { if (fi == filter) toggleRecord(); else selectChip(fi); });   // মাঝের (নির্বাচিত) আইকনে চাপলে রেকর্ড
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(64), dp(64));
            lp.leftMargin = dp(6); lp.rightMargin = dp(6);
            chipRow.addView(c, lp);
            chips.set(idx, c);
        }
        chipScroll.addView(chipRow, new FrameLayout.LayoutParams(-2, -1));   // উচ্চতা match_parent → CENTER_VERTICAL কাজ করবে, আইকন ঠিক রিংয়ের মাঝে
        FrameLayout.LayoutParams chp = new FrameLayout.LayoutParams(-1, dp(88));
        chp.gravity = Gravity.BOTTOM | Gravity.START;
        chp.bottomMargin = dp(68);                       // রেকর্ড-রিংয়ের (84dp, bottom 70dp) সাথে একই মাঝ-রেখা
        root.addView(chipScroll, chp);
        chipScroll.setClipToPadding(false);
        chipScroll.setOnTouchListener((v, ev) -> recording != null);   // রেকর্ড চলার সময় স্ট্রিপ স্ক্রল/ক্লিক বন্ধ
        chipScroll.setOnScrollChangeListener((v, x, y, ox, oy) -> {
            setRingVisible(false);        // স্ক্রল চলাকালীন রিং গায়েব
            H.removeCallbacks(settle);
            H.postDelayed(settle, 140);
        });
        styleChip(chips.get(0), true);
        centerChip(0, false);

        // "More Effects" বাটন (রেকর্ড বাটনের ওপরে)
        TextView more = text("More Effects", 16, Color.WHITE);
        GradientDrawable mb = new GradientDrawable();
        mb.setCornerRadius(dp(24));
        mb.setColor(0xAA333333);
        more.setBackground(mb);
        more.setPadding(dp(22), dp(10), dp(22), dp(10));
        more.setOnClickListener(v -> toggleFx());
        FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(-2, -2);
        mlp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL; mlp.bottomMargin = dp(168);
        root.addView(more, mlp);
        moreFx = more;
        dimWhenRec.add(more);

        recImg = new ImageView(this);   // শুধু recDot-এর ফলব্যাক, স্ক্রিনে যোগ হয় না
        recDot = chips.get(0) != null ? chips.get(0) : recImg;   // রেকর্ড চাপলে এই আইকনটাই ছোট হয়

        // নিচের সারি: Gallery · ✕ (শেষ ক্লিপ মুছুন) ... Next · Flip
        ImageView gal = iconView(R.drawable.cam_gallery, v -> { if (recording == null) galleryLauncher.launch("video/*"); });
        FrameLayout.LayoutParams gp = new FrameLayout.LayoutParams(dp(32), dp(32));
        gp.gravity = Gravity.BOTTOM | Gravity.START; gp.leftMargin = dp(20); gp.bottomMargin = dp(20);
        root.addView(gal, gp);
        dimWhenRec.add(gal);

        ivCancel = iconView(R.drawable.cam_cancel, v -> deleteLastClip());
        ivCancel.setVisibility(View.GONE);
        FrameLayout.LayoutParams xp = new FrameLayout.LayoutParams(dp(20), dp(20));
        xp.gravity = Gravity.BOTTOM | Gravity.START; xp.leftMargin = dp(100); xp.bottomMargin = dp(26);
        root.addView(ivCancel, xp);
        dimWhenRec.add(ivCancel);

        tvNext = text("Next ›", 14, Color.WHITE);
        tvNext.setTypeface(null, Typeface.BOLD);
        GradientDrawable nb = new GradientDrawable();
        nb.setCornerRadius(dp(18));
        nb.setColor(0xFF0866FF);
        tvNext.setBackground(nb);
        tvNext.setPadding(dp(14), 0, dp(14), 0);
        tvNext.setVisibility(View.GONE);
        tvNext.setOnClickListener(v -> { if (!busy) stopRecording(true); });
        FrameLayout.LayoutParams np = new FrameLayout.LayoutParams(-2, dp(36));
        np.gravity = Gravity.BOTTOM | Gravity.END; np.rightMargin = dp(20); np.bottomMargin = dp(18);
        root.addView(tvNext, np);

        tvBusy = text("প্রসেসিং…", 16, Color.WHITE);
        tvBusy.setBackgroundColor(0xAA000000);
        tvBusy.setVisibility(View.GONE);
        tvBusy.setClickable(true);
        root.addView(tvBusy, new FrameLayout.LayoutParams(-1, -1));
        return root;
    }

    /** রেকর্ড চললে রেকর্ড বাটন (নির্বাচিত আইকন) বাদে বাকি সব আইকন হালকা হয়; থামলে আবার স্বাভাবিক */
    private void setRecordingUi(boolean rec) {
        float a = rec ? DIM_ALPHA : 1f;
        for (int i = 0; i < chips.size(); i++) {
            View c = chips.get(i);
            if (c != null) c.setAlpha(i == filter ? 1f : a);
        }
        for (View v : dimWhenRec) if (v != null) v.setAlpha(a);
    }

    private void toggleFx() {
        // স্ট্রিপ লুকালেও নির্বাচিত আইকন রিংয়ের ভেতরে থাকে (রেকর্ড বাটনের চেহারা)
        fxOpen = !fxOpen;
        for (int i = 0; i < chips.size(); i++) {
            View c = chips.get(i);
            if (c != null && i != filter) c.setVisibility(fxOpen ? View.VISIBLE : View.INVISIBLE);
        }
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipScroll.setOnTouchListener((v, ev) -> !fxOpen || recording != null);   // লুকানো অবস্থায় বা রেকর্ড চলার সময় স্ক্রল বন্ধ
    }

    private void flip() {
        if (recording != null) return;
        back = !back;
        torch = false;
        bind();
    }

    @Override
    public void onBackPressed() {
        if (recording != null) { stopRecording(false); return; }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        H.removeCallbacksAndMessages(null);
        MusicPlayer.stop();
        if (fxProc != null) fxProc.release();
        if (recording != null) { try { recording.stop(); } catch (Exception ignored) { } }
        super.onDestroy();
    }
}
