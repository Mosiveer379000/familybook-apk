package com.familybook.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * "Edit" বাটনের নতুন স্ক্রিন (আগের সাদা ডায়ালগ বাদ):
 *   উপরে Cancel / Done, মাঝে ভিডিও প্রিভিউ,
 *   নিচে  ▶ প্লে + Undo/Redo, সময় (২.০ / ৪.৫),
 *   টাইমলাইন: হলুদ ভিডিও ক্লিপ (দুই পাশ ডানে-বামে টেনে ছোট/বড়), + Audio বক্স, যোগ করা টেক্সটের বার,
 *   সবার নিচে  Audio · Edit · Effects · Text · Stickers
 */
final class ShortEditTool {

    interface Host {
        long durMs();
        long trimStart();
        long trimEnd();
        void setTrim(long s, long e);
        long pos();
        void seek(long ms);
        boolean playing();
        void setPlaying(boolean play);
        boolean hasAudio();
        String audioLabel();
        void openAudio();
        void openEffects();
        void openText();
        void soon();
        void openStickers();
        List<TextItem> texts();
        void panelVisible(boolean v);
    }

    private final Context ctx;
    private final Host host;
    private final View bar;
    private final LinearLayout panel;
    private final FrameLayout topBar;
    private final ClipTimelineView timeline;
    private final TextView tvTime;
    private final TIcon playBtn;
    private boolean panelOn = false;
    private long[] initial = new long[2];
    private final List<long[]> undo = new ArrayList<>(), redo = new ArrayList<>();

    private int dp(int v) { return Math.round(v * ctx.getResources().getDisplayMetrics().density); }

    ShortEditTool(Context c, Host h, FrameLayout videoBox, LinearLayout main, View bar) {
        ctx = c; host = h; this.bar = bar;

        // ---- Cancel / Done
        topBar = new FrameLayout(c);
        topBar.setVisibility(View.GONE);
        TextView cancel = topBtn("Cancel"), done = topBtn("Done");
        cancel.setOnClickListener(v -> close(false));
        done.setOnClickListener(v -> close(true));
        topBar.addView(cancel, new FrameLayout.LayoutParams(-2, -2, Gravity.START));
        topBar.addView(done, new FrameLayout.LayoutParams(-2, -2, Gravity.END));
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        tl.topMargin = dp(28); tl.leftMargin = dp(12); tl.rightMargin = dp(12);
        videoBox.addView(topBar, tl);

        // ---- নিচের প্যানেল
        panel = new LinearLayout(c);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xFF000000);
        panel.setPadding(0, dp(2), 0, dp(6));
        panel.setVisibility(View.GONE);

        FrameLayout ctl = new FrameLayout(c);
        playBtn = new TIcon(c, TIcon.PAUSE);
        playBtn.setOnClickListener(v -> host.setPlaying(!host.playing()));
        ctl.addView(playBtn, new FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER));
        LinearLayout ur = new LinearLayout(c);
        TIcon un = new TIcon(c, TIcon.UNDO), re = new TIcon(c, TIcon.REDO);
        un.setOnClickListener(v -> doUndo());
        re.setOnClickListener(v -> doRedo());
        ur.addView(un, new LinearLayout.LayoutParams(dp(28), dp(28)));
        ur.addView(re, new LinearLayout.LayoutParams(dp(28), dp(28)));
        FrameLayout.LayoutParams url = new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL);
        url.rightMargin = dp(12);
        ctl.addView(ur, url);
        panel.addView(ctl, new LinearLayout.LayoutParams(-1, dp(36)));

        tvTime = new TextView(c);
        tvTime.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tvTime.setGravity(Gravity.CENTER);
        panel.addView(tvTime, new LinearLayout.LayoutParams(-1, -2));

        timeline = new ClipTimelineView(c);
        timeline.listener = new ClipTimelineView.L() {
            @Override public void scrub(long ms) {
                if (host.playing()) host.setPlaying(false);
                host.seek(ms);
                updateTime(ms);
            }
            @Override public void before() {
                pushUndo();
                if (host.playing()) host.setPlaying(false);
            }
            @Override public void trim(long s, long e, boolean left) {
                host.setTrim(s, e);
                host.seek(left ? s : Math.max(s, e - 150));
                updateTime(left ? s : e);
            }
            @Override public void trimDone() {
                host.seek(host.trimStart());
                host.setPlaying(true);
            }
            @Override public void audioTap() { host.openAudio(); }
        };
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.topMargin = dp(2);
        panel.addView(timeline, tlp);

        // ---- Audio · Edit · Effects · Text · Stickers
        LinearLayout acts = new LinearLayout(c);
        acts.setGravity(Gravity.CENTER);
        acts.addView(action(R.drawable.music_cam, "Audio", false, v -> host.openAudio()), new LinearLayout.LayoutParams(0, -2, 1f));
        acts.addView(action(R.drawable.edit_video, "Edit", false, v -> { }), new LinearLayout.LayoutParams(0, -2, 1f));
        acts.addView(action(R.drawable.effects_cam, "Effects", false, v -> host.openEffects()), new LinearLayout.LayoutParams(0, -2, 1f));
        acts.addView(action(R.drawable.text_on_video, "Text", false, v -> { close(true); host.openText(); }), new LinearLayout.LayoutParams(0, -2, 1f));
        acts.addView(action(R.drawable.sticker_on_video, "Stickers", false, v -> { close(true); host.openStickers(); }), new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-1, -2);
        alp.topMargin = dp(4);
        panel.addView(acts, alp);
        main.addView(panel, new LinearLayout.LayoutParams(-1, -2));
    }

    private TextView topBtn(String s) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setTypeface(null, Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), dp(5), dp(12), dp(5));
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFF262626);
        g.setCornerRadius(dp(10));
        t.setBackground(g);
        return t;
    }

    private View action(int iconRes, String label, boolean dim, View.OnClickListener l) {
        LinearLayout it = new LinearLayout(ctx);
        it.setOrientation(LinearLayout.VERTICAL);
        it.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout circle = new FrameLayout(ctx);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0xFF262626);
        circle.setBackground(g);
        ImageView iv = new ImageView(ctx);
        iv.setImageResource(iconRes);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (dim) iv.setAlpha(0.5f);
        circle.addView(iv, new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER));
        it.addView(circle, new LinearLayout.LayoutParams(dp(36), dp(36)));
        TextView t = new TextView(ctx);
        t.setText(label);
        t.setTextColor(dim ? 0xFF8A8A8A : Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dp(2);
        it.addView(t, lp);
        it.setOnClickListener(l);
        return it;
    }

    // ---------------------------------------------------------------- খোলা / বন্ধ

    boolean isOpen() { return panelOn; }

    boolean handleBack() {
        if (panelOn) { close(true); return true; }
        return false;
    }

    void open() {
        if (panelOn) return;
        initial = new long[]{host.trimStart(), host.trimEnd()};
        undo.clear(); redo.clear();
        panelOn = true;
        bar.setVisibility(View.GONE);
        panel.setVisibility(View.VISIBLE);
        topBar.setVisibility(View.VISIBLE);
        host.panelVisible(true);
        syncTimeline();
        timeline.setHead(host.pos());
        tick(host.pos());
    }

    private void close(boolean keep) {
        if (!keep) host.setTrim(initial[0], initial[1]);
        panelOn = false;
        panel.setVisibility(View.GONE);
        topBar.setVisibility(View.GONE);
        bar.setVisibility(View.VISIBLE);
        host.panelVisible(false);
        if (!keep) { host.seek(host.trimStart()); host.setPlaying(true); }
    }

    private void syncTimeline() {
        timeline.dur = Math.max(1000, host.durMs());
        timeline.ts = host.trimStart();
        timeline.te = host.trimEnd();
        timeline.hasAudio = host.hasAudio();
        timeline.audioLabel = host.audioLabel();
        timeline.texts = host.texts();
        timeline.refresh();
    }

    /** প্রতি ৬০ms-এ ডাকা হয় */
    void tick(long pos) {
        if (!panelOn) return;
        if (timeline.ts != host.trimStart() || timeline.te != host.trimEnd()
                || timeline.hasAudio != host.hasAudio()
                || timeline.texts.size() != host.texts().size()) syncTimeline();
        if (!timeline.isTouching()) timeline.setHead(pos);
        updateTime(pos);
        playBtn.type = host.playing() ? TIcon.PAUSE : TIcon.PLAY;
        playBtn.invalidate();
    }

    private void updateTime(long pos) {
        long lo = host.trimStart(), hi = host.trimEnd();
        String a = String.format(Locale.US, "%.1f", Math.max(0, pos - lo) / 1000f);
        String b = String.format(Locale.US, "%.1f", Math.max(0, hi - lo) / 1000f);
        SpannableString sp = new SpannableString(a + "   /   " + b);
        sp.setSpan(new ForegroundColorSpan(Color.WHITE), 0, a.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sp.setSpan(new ForegroundColorSpan(0xFF9A9A9A), a.length(), sp.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        tvTime.setText(sp);
    }

    // ---------------------------------------------------------------- Undo / Redo

    private void pushUndo() {
        undo.add(new long[]{host.trimStart(), host.trimEnd()});
        if (undo.size() > 40) undo.remove(0);
        redo.clear();
    }

    private void apply(long[] s) {
        host.setTrim(s[0], s[1]);
        host.seek(s[0]);
        syncTimeline();
    }

    private void doUndo() {
        if (undo.isEmpty()) return;
        redo.add(new long[]{host.trimStart(), host.trimEnd()});
        apply(undo.remove(undo.size() - 1));
    }

    private void doRedo() {
        if (redo.isEmpty()) return;
        undo.add(new long[]{host.trimStart(), host.trimEnd()});
        apply(redo.remove(redo.size() - 1));
    }
}

// ============================================================ ক্লিপ টাইমলাইন

final class ClipTimelineView extends View {

    interface L { void scrub(long ms); void before(); void trim(long s, long e, boolean left); void trimDone(); void audioTap(); }

    private static final int NONE = 0, SCRUB = 1, TRIM_L = 2, TRIM_R = 3;

    L listener;
    long head = 0, dur = 1000, ts = 0, te = 1000;
    boolean hasAudio = false;
    String audioLabel = "Audio";
    List<TextItem> texts = new ArrayList<>();

    private boolean touching = false, moved = false, pushed = false;
    private int mode = NONE, lanes = 1;
    private int[] lane = new int[0];
    private float downX, downY, ppms;
    private long origS, origE, origHead;
    private final float dpv;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dashP = new Paint(Paint.ANTI_ALIAS_FLAG);

    // উচ্চতার হিসাব (dp)
    private static final float CLIP_T = 14, CLIP_H = 32, AUD_T = 52, AUD_H = 28, TXT_T = 86, LANE = 30;

    ClipTimelineView(Context c) {
        super(c);
        dpv = c.getResources().getDisplayMetrics().density;
        ppms = 32f * dpv / 1000f;            // ১ সেকেন্ড = ৩২dp
        dashP.setStyle(Paint.Style.STROKE);
        dashP.setStrokeWidth(1.5f * dpv);
        dashP.setColor(0xAAFFFFFF);
        dashP.setPathEffect(new DashPathEffect(new float[]{6f * dpv, 4f * dpv}, 0));
    }

    boolean isTouching() { return touching; }

    void setHead(long ms) { if (ms != head) { head = ms; invalidate(); } }

    void refresh() {
        int old = lanes;
        recompute();
        if (old != lanes) requestLayout();
        invalidate();
    }

    private void recompute() {
        lane = new int[texts.size()];
        int mx = 0;
        for (int i = 0; i < texts.size(); i++) {
            int ln = 0;
            while (true) {
                boolean clash = false;
                for (int j = 0; j < i; j++) {
                    if (lane[j] == ln && texts.get(j).start < texts.get(i).end && texts.get(i).start < texts.get(j).end) { clash = true; break; }
                }
                if (!clash) break;
                ln++;
            }
            lane[i] = ln;
            mx = Math.max(mx, ln);
        }
        lanes = Math.min(mx + 1, 3);
    }

    private float x(long t) { return getWidth() / 2f + (t - head) * ppms; }

    @Override protected void onMeasure(int ws, int hs) {
        recompute();
        int h = Math.round((TXT_T + lanes * LANE + 4) * dpv);
        setMeasuredDimension(MeasureSpec.getSize(ws), h);
    }

    private static RectF rf(float l, float t, float r, float b) { return new RectF(l, t, r, b); }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), hgt = getHeight();
        // সেকেন্ডের বিন্দু
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x88FFFFFF);
        for (long s = 0; s <= dur; s += 1000) {
            float xx = x(s);
            if (xx < -4 || xx > w + 4) continue;
            c.drawCircle(xx, 6f * dpv, 1.2f * dpv, p);
        }
        float cT = CLIP_T * dpv, cB = cT + CLIP_H * dpv;
        // পুরো ভিডিও (ধূসর = কাটা অংশ, টেনে আবার বাড়ানো যায়)
        p.setColor(0xFF2E2E2E);
        c.drawRoundRect(rf(x(0), cT, x(dur), cB), 8f * dpv, 8f * dpv, p);
        // হলুদ ক্লিপ
        float l = x(ts), r = x(te);
        p.setColor(0xFFFFD60A);
        c.drawRoundRect(rf(l, cT, r, cB), 10f * dpv, 10f * dpv, p);
        float il = l + 15f * dpv, ir = r - 15f * dpv;
        if (ir > il) {
            p.setColor(0xFF000000);
            c.drawRoundRect(rf(il, cT + 2f * dpv, ir, cB - 2f * dpv), 6f * dpv, 6f * dpv, p);
        }
        p.setColor(0xFF7A2E0E);
        c.drawRoundRect(rf(l + 5.5f * dpv, cT + 8f * dpv, l + 8.5f * dpv, cB - 8f * dpv), 2f * dpv, 2f * dpv, p);
        c.drawRoundRect(rf(r - 8.5f * dpv, cT + 8f * dpv, r - 5.5f * dpv, cB - 8f * dpv), 2f * dpv, 2f * dpv, p);

        // Audio ট্র্যাক
        float aT = AUD_T * dpv, aB = aT + AUD_H * dpv;
        float aL = l, aR = r;
        if (aR > 0 && aL < w) {
            if (hasAudio) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xFF2E7D6B);
                c.drawRoundRect(rf(aL, aT, aR, aB), 10f * dpv, 10f * dpv, p);
            } else {
                c.drawRoundRect(rf(aL, aT, aR, aB), 10f * dpv, 10f * dpv, dashP);
            }
            c.save();
            c.clipRect(Math.max(aL, 0), aT, Math.min(aR, w), aB);
            float tx = Math.max(aL, 0) + 12f * dpv;
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFDDDDDD);
            if (!hasAudio) {
                c.drawRoundRect(rf(tx, aT + 8f * dpv, tx + 14f * dpv, aT + 22f * dpv), 3f * dpv, 3f * dpv, p);
                p.setColor(0xFF111111);
                p.setStrokeWidth(1.6f * dpv);
                c.drawLine(tx + 3.5f * dpv, aT + 15f * dpv, tx + 10.5f * dpv, aT + 15f * dpv, p);
                c.drawLine(tx + 7f * dpv, aT + 11.5f * dpv, tx + 7f * dpv, aT + 18.5f * dpv, p);
                tx += 19f * dpv;
            }
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFDDDDDD);
            p.setTextSize(12f * dpv);
            p.setTypeface(Typeface.DEFAULT);
            c.drawText(hasAudio ? "♪ " + (audioLabel == null ? "Audio" : audioLabel) : "Audio", tx, aT + 19f * dpv, p);
            c.restore();
        }

        // যোগ করা টেক্সট (শুধু দেখানো)
        for (int i = 0; i < texts.size() && i < lane.length; i++) {
            TextItem it = texts.get(i);
            if (it.empty()) continue;
            float tl = x(it.start), tr = x(it.end);
            if (tr < 0 || tl > w) continue;
            float t = (TXT_T + lane[i] * LANE) * dpv, b = t + 26f * dpv;
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF6C63E6);
            c.drawRoundRect(rf(tl, t, tr, b), 8f * dpv, 8f * dpv, p);
            c.save();
            c.clipRect(Math.max(tl, 0), t, Math.min(tr, w), b);
            p.setColor(Color.WHITE);
            p.setTextSize(12f * dpv);
            p.setTypeface(Typeface.DEFAULT);
            String lb = it.text == null ? "" : it.text.replace('\n', ' ');
            c.drawText(lb, Math.max(tl, 0) + 8f * dpv, t + 18f * dpv, p);
            c.restore();
        }

        // প্লেহেড
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.WHITE);
        c.drawRoundRect(rf(w / 2f - 1f * dpv, 0, w / 2f + 1f * dpv, hgt), dpv, dpv, p);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        float ex = e.getX(), ey = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                getParent().requestDisallowInterceptTouchEvent(true);
                downX = ex; downY = ey; moved = false; pushed = false; mode = SCRUB; origHead = head;
                origS = ts; origE = te;
                float cT = CLIP_T * dpv, cB = cT + CLIP_H * dpv;
                if (ey >= cT - 8f * dpv && ey <= cB + 8f * dpv) {
                    float dl = Math.abs(ex - x(ts)), dr = Math.abs(ex - x(te));
                    float hit = 22f * dpv;
                    if (dl <= hit || dr <= hit) mode = dl <= dr ? TRIM_L : TRIM_R;
                }
                touching = true;
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                float dx = ex - downX;
                if (!moved && Math.abs(dx) < 5f * dpv && Math.abs(ey - downY) < 5f * dpv) return true;
                moved = true;
                if (mode == SCRUB) {
                    long nh = origHead - Math.round(dx / ppms);
                    head = Math.max(ts, Math.min(te, nh));
                    if (listener != null) listener.scrub(head);
                    invalidate();
                } else {
                    if (!pushed) { pushed = true; if (listener != null) listener.before(); }
                    long dt = Math.round(dx / ppms);
                    if (mode == TRIM_L) {
                        long lowest = Math.max(0, origE - ShortUploader.MAX_MS);
                        ts = Math.max(lowest, Math.min(origE - ShortUploader.MIN_MS, origS + dt));
                    } else {
                        long highest = Math.min(dur, origS + ShortUploader.MAX_MS);
                        te = Math.min(highest, Math.max(origS + ShortUploader.MIN_MS, origE + dt));
                    }
                    if (listener != null) listener.trim(ts, te, mode == TRIM_L);
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean up = e.getActionMasked() == MotionEvent.ACTION_UP;
                if (up && !moved) {
                    float aT = AUD_T * dpv, aB = aT + AUD_H * dpv;
                    if (ey >= aT && ey <= aB && ex >= x(ts) && ex <= x(te) && listener != null) listener.audioTap();
                }
                if (moved && (mode == TRIM_L || mode == TRIM_R) && listener != null) listener.trimDone();
                touching = false;
                mode = NONE;
                return true;
            }
            default: return true;
        }
    }
}
