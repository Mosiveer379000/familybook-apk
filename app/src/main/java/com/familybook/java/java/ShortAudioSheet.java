package com.familybook.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * ShortEditActivity-র "Audio" প্যানেল (নিচ থেকে ওঠা শিট) — স্ক্রিনশটের ডিজাইন:
 *  ১) Mix your audio   : Original ও Enhance (আর গান থাকলে Music) স্লাইডার, Add music, Add voiceover, ⓘ
 *  ২) How to mix audio : ⓘ চাপলে ব্যাখ্যা
 *  ৩) Voiceover        : টাইমলাইন + Tap/Hold রেকর্ড বাটন + Undo
 * উপরে Cancel / Done। এই ক্লাস শুধু UI; আসল কাজ Host (ShortEditActivity) করে।
 */
public class ShortAudioSheet {

    public interface Host {
        void onOriginalVolume(float v);
        void onMusicLevel(float v);
        void onEnhance(float s);
        boolean hasMusic();
        String musicTitle();
        void onAddMusic();
        void onAddVoice();            // Add voiceover চাপলে (পারমিশন দেখে showVoice() ডাকবে)
        boolean onRecordStart();      // true = রেকর্ড শুরু হয়েছে
        void onRecordStop();
        void onUndoVoice();
        void onVoiceBack();           // Voiceover থেকে ←
        void onDone();
        void onCancel();
    }

    private static final int BG_MIX = 0xFF242526, BG_VOICE = 0xFF3A3B3C;
    private static final int BLUE = 0xFF0866FF, LINK = 0xFF4C9AFF, CIRCLE = 0xFF3A3B3C, RED = 0xFFE41E3F;

    private final Context ctx;
    private final Host host;
    private final float dens;

    private final FrameLayout overlay;
    private final LinearLayout sheet;
    private final GradientDrawable sheetBg = new GradientDrawable();
    private LinearLayout panelMix, panelInfo, panelVoice;

    private WedgeSlider slOrig, slEnh, slMusic;
    private ImageView icOrig, icMusic;
    private TextView actOrig, actEnh, actMusic, tvMusicBtn;
    private LinearLayout rowMusic;

    private VoiceTimelineView timeline;
    private FrameLayout btnRecord;
    private ImageView icRecord;
    private View btnUndo;

    private float orig = 1f, music = 0.7f, enh = 0f;
    private float lastOrig = 1f, lastMusic = 0.7f, lastEnh = 1f;
    private boolean recording = false, stoppedOnDown = false;
    private long downTime;
    private boolean open = false;

    public ShortAudioSheet(Context c, Host h) {
        ctx = c; host = h;
        dens = c.getResources().getDisplayMetrics().density;

        overlay = new FrameLayout(c);
        overlay.setVisibility(View.GONE);

        TextView cancel = topBtn("Cancel");
        cancel.setOnClickListener(v -> host.onCancel());
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-2, -2);
        cp.gravity = Gravity.TOP | Gravity.START; cp.topMargin = dp(30); cp.leftMargin = dp(12);
        overlay.addView(cancel, cp);

        TextView done = topBtn("Done");
        done.setOnClickListener(v -> host.onDone());
        FrameLayout.LayoutParams dp2 = new FrameLayout.LayoutParams(-2, -2);
        dp2.gravity = Gravity.TOP | Gravity.END; dp2.topMargin = dp(30); dp2.rightMargin = dp(12);
        overlay.addView(done, dp2);

        sheet = new LinearLayout(c);
        sheet.setOrientation(LinearLayout.VERTICAL);
        float r = dp(28);
        sheetBg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        sheetBg.setColor(BG_MIX);
        sheet.setBackground(sheetBg);
        sheet.setClickable(true);                 // পেছনের বারে টাচ যেন না যায়
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-1, -2);
        sp.gravity = Gravity.BOTTOM;
        overlay.addView(sheet, sp);

        buildMix();
        buildInfo();
        buildVoice();
        sheet.addView(panelMix); sheet.addView(panelInfo); sheet.addView(panelVoice);
        refreshAll();
        showMix();
    }

    // ------------------------------------------------------------ public API

    public View view() { return overlay; }
    public boolean isOpen() { return open; }
    public boolean isRecording() { return recording; }
    public boolean isVoiceShown() { return open && panelVoice.getVisibility() == View.VISIBLE; }
    public float getOriginal() { return orig; }
    public float getMusic() { return music; }
    public float getEnhance() { return enh; }

    public void open() { open = true; overlay.setVisibility(View.VISIBLE); showMix(); }
    public void close() { open = false; overlay.setVisibility(View.GONE); }

    public void setValues(float o, float m, float e) {
        orig = o; music = m; enh = e;
        if (o > 0.01f) lastOrig = o;
        if (m > 0.01f) lastMusic = m;
        if (e > 0.01f) lastEnh = e;
        refreshAll();
    }

    public void refreshMusicRow() {
        boolean has = host.hasMusic();
        rowMusic.setVisibility(has ? View.VISIBLE : View.GONE);
        String t = host.musicTitle();
        tvMusicBtn.setText(has ? (t == null || t.isEmpty() ? "Music" : t) : "Add music");
        refreshMusic();
    }

    public void showMix() { show(panelMix, BG_MIX); }
    public void showInfo() { show(panelInfo, BG_MIX); }
    public void showVoice() { show(panelVoice, BG_VOICE); }

    public void setRecording(boolean r) {
        recording = r;
        if (r) {
            GradientDrawable ring = new GradientDrawable();
            ring.setShape(GradientDrawable.OVAL); ring.setColor(Color.TRANSPARENT); ring.setStroke(dp(3), RED);
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL); dot.setColor(RED);
            LayerDrawable ld = new LayerDrawable(new Drawable[]{ring, dot});
            ld.setLayerInset(1, dp(10), dp(10), dp(10), dp(10));
            btnRecord.setBackground(ld);
            icRecord.setImageResource(R.drawable.audmix_stop);
            btnUndo.setVisibility(View.INVISIBLE);
        } else {
            btnRecord.setBackground(circle(0xFF5F6368));
            icRecord.setImageResource(R.drawable.audmix_mic);
            timeline.setOpenStart(-1f);
        }
    }

    public void setPlayhead(float f) { timeline.setPlayhead(f); }
    public void setOpenStart(float f) { timeline.setOpenStart(f); }
    public void setSegments(List<float[]> s) { timeline.setSegments(s); }
    public void setUndoVisible(boolean v) { btnUndo.setVisibility(v && !recording ? View.VISIBLE : View.INVISIBLE); }

    /** Back চাপলে: খোলা থাকলে নিজে সামলায় (true)। */
    public boolean handleBack() {
        if (!open) return false;
        if (panelVoice.getVisibility() == View.VISIBLE) host.onVoiceBack();
        else if (panelInfo.getVisibility() == View.VISIBLE) showMix();
        else host.onCancel();
        return true;
    }

    // ------------------------------------------------------------ প্যানেল ১: Mix

    private void buildMix() {
        panelMix = vbox();
        panelMix.addView(header("Mix your audio", false, true));
        panelMix.addView(divider(0xFF3E4042));

        slOrig = new WedgeSlider(ctx);
        icOrig = circleIcon(R.drawable.audmix_volume_on);
        actOrig = action();
        panelMix.addView(row(icOrig, slOrig, actOrig));
        slOrig.setOnChange((v, user) -> {
            if (!user) return;
            orig = v; if (v > 0.01f) lastOrig = v;
            refreshOrig(); host.onOriginalVolume(v);
        });
        View.OnClickListener tOrig = v -> {
            orig = orig > 0.01f ? 0f : Math.max(0.05f, lastOrig);
            refreshOrig(); host.onOriginalVolume(orig);
        };
        icOrig.setOnClickListener(tOrig); actOrig.setOnClickListener(tOrig);

        slEnh = new WedgeSlider(ctx);
        ImageView icEnh = circleIcon(R.drawable.audmix_enhance);
        actEnh = action();
        panelMix.addView(row(icEnh, slEnh, actEnh));
        slEnh.setOnChange((v, user) -> {
            if (!user) return;
            enh = v; if (v > 0.01f) lastEnh = v;
            refreshEnh(); host.onEnhance(v);
        });
        View.OnClickListener tEnh = v -> {
            enh = enh > 0.01f ? 0f : Math.max(0.05f, lastEnh);
            refreshEnh(); host.onEnhance(enh);
        };
        icEnh.setOnClickListener(tEnh); actEnh.setOnClickListener(tEnh);

        // Music level (শুধু গান বাছা থাকলে দেখা যায়)
        slMusic = new WedgeSlider(ctx);
        icMusic = circleIcon(R.drawable.audmix_music);
        actMusic = action();
        rowMusic = row(icMusic, slMusic, actMusic);
        rowMusic.setVisibility(View.GONE);
        panelMix.addView(rowMusic);
        slMusic.setOnChange((v, user) -> {
            if (!user) return;
            music = v; if (v > 0.01f) lastMusic = v;
            refreshMusic(); host.onMusicLevel(v);
        });
        View.OnClickListener tMus = v -> {
            music = music > 0.01f ? 0f : Math.max(0.05f, lastMusic);
            refreshMusic(); host.onMusicLevel(music);
        };
        icMusic.setOnClickListener(tMus); actMusic.setOnClickListener(tMus);

        LinearLayout bm = pill(R.drawable.audmix_music, "Add music");
        tvMusicBtn = (TextView) bm.getChildAt(1);
        tvMusicBtn.setEllipsize(TextUtils.TruncateAt.END);
        tvMusicBtn.setSingleLine(true);
        tvMusicBtn.setMaxWidth(dp(220));
        bm.setOnClickListener(v -> host.onAddMusic());
        panelMix.addView(bm, pillLp(12));

        LinearLayout bv = pill(R.drawable.audmix_mic, "Add voiceover");
        bv.setOnClickListener(v -> host.onAddVoice());
        panelMix.addView(bv, pillLp(12));
    }

    // ------------------------------------------------------------ প্যানেল ২: Info

    private void buildInfo() {
        panelInfo = vbox();
        FrameLayout h = header("How to mix audio", true, false);
        h.getChildAt(0).setOnClickListener(v -> showMix());
        panelInfo.addView(h);
        panelInfo.addView(divider(0xFF3E4042));
        infoRow(R.drawable.audmix_volume_on, "Your original audio is recorded by your microphone during filming or is part of a video you uploaded.");
        infoRow(R.drawable.audmix_enhance, "You can adjust to improve the audio quality in your video and clean up any background noise.");
        infoRow(R.drawable.audmix_music, "You can mix your original audio with a pre-selected audio track, or add music after you record a video.");
        infoRow(R.drawable.audmix_headphones, "For best results, wear headphones when filming a video with pre-selected audio. Otherwise, your microphone will record the audio from your phone's speaker.");
    }

    private void infoRow(int icon, String text) {
        LinearLayout r = new LinearLayout(ctx);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        ImageView iv = new ImageView(ctx);
        iv.setImageResource(icon);
        iv.setAlpha(0.85f);
        r.addView(iv, new LinearLayout.LayoutParams(dp(28), dp(28)));
        TextView t = tv(text, 16, 0xFFE4E6EB, false);
        t.setGravity(Gravity.START);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1f);
        tp.leftMargin = dp(16);
        r.addView(t, tp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(14);
        panelInfo.addView(r, lp);
    }

    // ------------------------------------------------------------ প্যানেল ৩: Voiceover

    private void buildVoice() {
        panelVoice = vbox();
        FrameLayout h = header("Voiceover", true, false);
        h.getChildAt(0).setOnClickListener(v -> host.onVoiceBack());
        panelVoice.addView(h);
        panelVoice.addView(divider(0xFF4A4B4D));

        timeline = new VoiceTimelineView(ctx);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, dp(56));
        tl.topMargin = dp(16);
        panelVoice.addView(timeline, tl);

        TextView hint = tv("Tap or hold to record audio over the video.", 16, 0xFFB0B3B8, false);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
        hp.topMargin = dp(16);
        panelVoice.addView(hint, hp);

        FrameLayout bottom = new FrameLayout(ctx);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, dp(112));
        bp.topMargin = dp(12);
        panelVoice.addView(bottom, bp);

        LinearLayout undo = new LinearLayout(ctx);
        undo.setOrientation(LinearLayout.HORIZONTAL);
        undo.setGravity(Gravity.CENTER_VERTICAL);
        undo.setPadding(dp(8), dp(8), dp(8), dp(8));
        ImageView ui = new ImageView(ctx);
        ui.setImageResource(R.drawable.audmix_backspace);
        undo.addView(ui, new LinearLayout.LayoutParams(dp(30), dp(30)));
        TextView ut = tv("Undo", 18, Color.WHITE, false);
        LinearLayout.LayoutParams up = new LinearLayout.LayoutParams(-2, -2);
        up.leftMargin = dp(8);
        undo.addView(ut, up);
        undo.setOnClickListener(v -> host.onUndoVoice());
        undo.setVisibility(View.INVISIBLE);
        FrameLayout.LayoutParams ulp = new FrameLayout.LayoutParams(-2, -2);
        ulp.gravity = Gravity.START | Gravity.CENTER_VERTICAL; ulp.leftMargin = dp(20);
        bottom.addView(undo, ulp);
        btnUndo = undo;

        btnRecord = new FrameLayout(ctx);
        btnRecord.setBackground(circle(0xFF5F6368));
        icRecord = new ImageView(ctx);
        icRecord.setImageResource(R.drawable.audmix_mic);
        btnRecord.addView(icRecord, new FrameLayout.LayoutParams(dp(38), dp(38), Gravity.CENTER));
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(dp(100), dp(100));
        rp.gravity = Gravity.CENTER;
        bottom.addView(btnRecord, rp);

        // Tap: শুরু → আবার Tap: বন্ধ.  Hold (>0.4s): চেপে ধরলে রেকর্ড, ছাড়লে বন্ধ.
        btnRecord.setOnTouchListener((v, e) -> {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    downTime = System.currentTimeMillis();
                    if (recording) { stoppedOnDown = true; host.onRecordStop(); }
                    else { stoppedOnDown = false; host.onRecordStart(); }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (!stoppedOnDown && recording && System.currentTimeMillis() - downTime > 400) host.onRecordStop();
                    return true;
            }
            return false;
        });
    }

    // ------------------------------------------------------------ রিফ্রেশ

    private void refreshAll() {
        slOrig.setValue(orig); slEnh.setValue(enh); slMusic.setValue(music);
        refreshOrig(); refreshEnh(); refreshMusic();
    }

    private void refreshOrig() {
        boolean on = orig > 0.01f;
        slOrig.setValue(orig);
        icOrig.setImageResource(on ? R.drawable.audmix_volume_on : R.drawable.audmix_volume_off);
        actOrig.setText(on ? "Mute" : "Unmute");
    }

    // স্ক্রিনশট অনুযায়ী: চালু থাকলে লেখা "Off" (বন্ধ করতে), বন্ধ থাকলে "On"
    private void refreshEnh() {
        boolean on = enh > 0.01f;
        slEnh.setValue(enh);
        actEnh.setText(on ? "Off" : "On");
    }

    private void refreshMusic() {
        boolean on = music > 0.01f;
        slMusic.setValue(music);
        icMusic.setImageResource(R.drawable.audmix_music);
        icMusic.setAlpha(on ? 1f : 0.5f);
        actMusic.setText(on ? "Mute" : "Unmute");
    }

    private void show(View p, int bg) {
        panelMix.setVisibility(p == panelMix ? View.VISIBLE : View.GONE);
        panelInfo.setVisibility(p == panelInfo ? View.VISIBLE : View.GONE);
        panelVoice.setVisibility(p == panelVoice ? View.VISIBLE : View.GONE);
        sheetBg.setColor(bg);
        if (p == panelMix) refreshMusicRow();
    }

    // ------------------------------------------------------------ ছোট ছোট UI সাহায্যকারী

    private int dp(int v) { return Math.round(v * dens * 0.8f); }   // সব সাইজ ২০% ছোট

    private TextView tv(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(ctx);
        t.setText(s); t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * 0.85f);
        t.setGravity(Gravity.CENTER);
        if (bold) t.setTypeface(null, Typeface.BOLD);
        return t;
    }

    private TextView topBtn(String s) {
        TextView t = tv(s, 18, Color.WHITE, true);
        t.setPadding(dp(18), dp(10), dp(18), dp(10));
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFF202020); g.setCornerRadius(dp(10));
        t.setBackground(g);
        return t;
    }

    private GradientDrawable circle(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL); g.setColor(color);
        return g;
    }

    private LinearLayout vbox() {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(16), dp(14), dp(16), dp(18));
        return l;
    }

    /** শিরোনাম সারি; child 0 = বাম ← (back হলে), child 1 = শিরোনাম, child 2 = ⓘ (info হলে) */
    private FrameLayout header(String title, boolean back, boolean info) {
        FrameLayout h = new FrameLayout(ctx);
        ImageView b = new ImageView(ctx);
        b.setImageResource(R.drawable.audmix_back);
        b.setPadding(dp(4), dp(4), dp(4), dp(4));
        b.setVisibility(back ? View.VISIBLE : View.INVISIBLE);
        h.addView(b, new FrameLayout.LayoutParams(dp(36), dp(36), Gravity.START | Gravity.CENTER_VERTICAL));
        h.addView(tv(title, 20, Color.WHITE, true), new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        if (info) {
            ImageView i = new ImageView(ctx);
            i.setImageResource(R.drawable.audmix_info);
            i.setOnClickListener(v -> showInfo());
            h.addView(i, new FrameLayout.LayoutParams(dp(34), dp(34), Gravity.END | Gravity.CENTER_VERTICAL));
        }
        h.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(48)));
        return h;
    }

    private View divider(int color) {
        View v = new View(ctx);
        v.setBackgroundColor(color);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(1));
        lp.topMargin = dp(8);
        v.setLayoutParams(lp);
        return v;
    }

    private ImageView circleIcon(int res) {
        ImageView iv = new ImageView(ctx);
        iv.setImageResource(res);
        iv.setPadding(dp(12), dp(12), dp(12), dp(12));
        iv.setBackground(circle(CIRCLE));
        return iv;
    }

    private TextView action() {
        TextView t = tv("", 18, LINK, false);
        t.setMinWidth(dp(84));
        return t;
    }

    private LinearLayout row(View icon, View slider, View act) {
        LinearLayout r = new LinearLayout(ctx);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        sp.leftMargin = dp(10);
        r.addView(slider, sp);
        r.addView(act, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(10);
        r.setLayoutParams(lp);
        return r;
    }

    private LinearLayout pill(int icon, String label) {
        LinearLayout p = new LinearLayout(ctx);
        p.setOrientation(LinearLayout.HORIZONTAL);
        p.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setColor(BLUE); g.setCornerRadius(dp(28));
        p.setBackground(g);
        p.setClickable(true);
        ImageView iv = new ImageView(ctx);
        iv.setImageResource(icon);
        p.addView(iv, new LinearLayout.LayoutParams(dp(26), dp(26)));
        TextView t = tv(label, 20, Color.WHITE, true);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.leftMargin = dp(8);
        p.addView(t, tp);
        return p;
    }

    private LinearLayout.LayoutParams pillLp(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(56));
        lp.topMargin = dp(topDp);
        return lp;
    }

    // ------------------------------------------------------------ কাস্টম স্লাইডার (ত্রিভুজাকার ট্র্যাক)

    public static class WedgeSlider extends View {
        public interface OnChange { void on(float v, boolean fromUser); }

        private final Paint pOff = new Paint(Paint.ANTI_ALIAS_FLAG), pOn = new Paint(Paint.ANTI_ALIAS_FLAG),
                pThumb = new Paint(Paint.ANTI_ALIAS_FLAG), pGrip = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF rect = new RectF();
        private final float d;
        private float value = 0f;
        private OnChange cb;

        public WedgeSlider(Context c) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
            pThumb.setColor(Color.WHITE);
            pGrip.setColor(0xFFB8BABD);
        }

        public void setOnChange(OnChange c) { cb = c; }
        public void setValue(float v) { value = Math.max(0f, Math.min(1f, v)); invalidate(); }

        @Override protected void onDraw(Canvas c) {
            float pad = 12 * d, l = pad, r = getWidth() - pad, cy = getHeight() / 2f;
            float hl = 5 * d, hr = 26 * d;
            boolean on = value > 0.01f;
            pOff.setColor(on ? 0xFF4A4D52 : 0xFF868A8F);
            path.reset();
            path.moveTo(l, cy - hl / 2); path.lineTo(r, cy - hr / 2); path.lineTo(r, cy + hr / 2); path.lineTo(l, cy + hl / 2); path.close();
            c.drawPath(path, pOff);

            float x = l + (r - l) * value;
            if (on) {
                float hx = hl + (hr - hl) * value;
                pOn.setColor(0xFF0866FF);
                path.reset();
                path.moveTo(l, cy - hl / 2); path.lineTo(x, cy - hx / 2); path.lineTo(x, cy + hx / 2); path.lineTo(l, cy + hl / 2); path.close();
                c.drawPath(path, pOn);
            }
            float tw = 18 * d, th = 44 * d;
            rect.set(x - tw / 2, cy - th / 2, x + tw / 2, cy + th / 2);
            c.drawRoundRect(rect, 6 * d, 6 * d, pThumb);
            rect.set(x - 1.5f * d, cy - 9 * d, x + 1.5f * d, cy + 9 * d);
            c.drawRoundRect(rect, 2 * d, 2 * d, pGrip);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_MOVE:
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    float pad = 12 * d;
                    float v = (e.getX() - pad) / Math.max(1f, getWidth() - 2 * pad);
                    value = Math.max(0f, Math.min(1f, v));
                    invalidate();
                    if (cb != null) cb.on(value, true);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    return true;
            }
            return super.onTouchEvent(e);
        }
    }
}
