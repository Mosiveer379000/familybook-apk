package com.familybook.app;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/**
 * Memory ভিউয়ার (ফেসবুক স্টোরির মতো): উপরে প্রোগ্রেস বার, নাম/সময়, ⋯ ও ✕; ডানে/বামে ট্যাপে পরেরটা/আগেরটা;
 * চেপে ধরলে থামে। নিচে Location · Feeling · Tag লাইন। নিজের Memory হলে নিচে 👁 Views ও 📊 Insight;
 * ⋯ মেনুতে Privacy বদল ও Delete। শেষ হলে queue-র পরের ব্যক্তির Memory চলে।
 */
public class MemoryViewerActivity extends BaseActivity {

    public static final String EXTRA_QUEUE = "mem_queue";        // ArrayList<String> uid
    public static final String EXTRA_START = "mem_start";        // কোন index থেকে শুরু
    static final String SEEN_PREFS = "memory_seen";

    /** Home-এর সারি থেকে ডাকার সুবিধার জন্য */
    static void open(Context c, ArrayList<String> uids, int start) {
        Intent i = new Intent(c, MemoryViewerActivity.class);
        i.putStringArrayListExtra(EXTRA_QUEUE, uids);
        i.putExtra(EXTRA_START, start);
        c.startActivity(i);
    }

    static boolean isSeen(Context c, String memoryId) {
        return c.getSharedPreferences(SEEN_PREFS, Context.MODE_PRIVATE).getStringSet("ids", new HashSet<>()).contains(memoryId);
    }

    static String isoNow() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date());
    }

    static long parseIso(String s) {
        if (s == null) return 0;
        try {
            String t = s.replace("T", " ");
            int dot = t.indexOf('.');
            int end = dot > 0 ? dot : Math.min(t.length(), 19);
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            return f.parse(t.substring(0, end)).getTime();
        } catch (Exception e) { return 0; }
    }

    // ---- ডাটা
    private static final class Mem {
        String id, videoKey, location, feeling, privacy, musicTitle = "";
        boolean ai;
        List<String> tags = new ArrayList<>();
        long created, expires;
        int views = -1;
    }

    private final Handler H = new Handler(Looper.getMainLooper());
    private ArrayList<String> queue;
    private int qIndex = 0;
    private String curUid;
    private boolean mine;
    private final List<Mem> mems = new ArrayList<>();
    private int idx = 0;
    private int loadGen = 0;

    // ---- UI
    private FrameLayout root, videoBox;
    private SurfaceView sv;
    private SegBar bars;
    private ImageView avatar;
    private TextView tvName, tvTime, tvMusic, tvInfo, tvViews, tvInsight, tvBusy;
    private LinearLayout footer;
    private ExoPlayer player;
    private boolean paused = false, retried = false;
    private int vw = 0, vh = 0;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (player != null && !paused) {
                long d = player.getDuration();
                if (d > 0) bars.setFraction(idx, player.getCurrentPosition() / (float) d);
            }
            H.postDelayed(this, 50);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        queue = getIntent().getStringArrayListExtra(EXTRA_QUEUE);
        if (queue == null || queue.isEmpty()) { finish(); return; }
        qIndex = Math.max(0, Math.min(queue.size() - 1, getIntent().getIntExtra(EXTRA_START, 0)));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(buildUi());
        player = new ExoPlayer.Builder(this).build();
        player.setVideoSurfaceView(sv);
        player.setRepeatMode(Player.REPEAT_MODE_OFF);
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int s) {
                tvBusy.setVisibility(s == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
                if (s == Player.STATE_ENDED) next();
            }
            @Override public void onVideoSizeChanged(VideoSize v) {
                vw = v.width; vh = v.height; fitVideo();
            }
            @Override public void onPlayerError(PlaybackException e) {
                if (!retried && idx < mems.size()) {
                    retried = true;
                    Media.forgetUrl(mems.get(idx).videoKey);
                    playCurrent();
                } else {
                    Toast.makeText(MemoryViewerActivity.this, "Couldn't play this Memory", Toast.LENGTH_SHORT).show();
                    next();
                }
            }
        });
        loadUser();
        H.post(tick);
    }

    // ------------------------------------------------------------ UI বানানো

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private View buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        videoBox = new FrameLayout(this);
        sv = new SurfaceView(this);
        videoBox.addView(sv, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        root.addView(videoBox, new FrameLayout.LayoutParams(-1, -1));
        videoBox.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> fitVideo());

        tvBusy = new TextView(this);
        tvBusy.setText("Loading…");
        tvBusy.setTextColor(Color.WHITE);
        tvBusy.setVisibility(View.GONE);
        root.addView(tvBusy, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        // ট্যাপ/হোল্ড স্তর
        View touch = new View(this);
        final float[] down = new float[2];
        final long[] downAt = new long[1];
        touch.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = e.getX(); down[1] = e.getY(); downAt[0] = System.currentTimeMillis();
                    pause(true);
                    return true;
                case MotionEvent.ACTION_UP:
                    pause(false);
                    if (System.currentTimeMillis() - downAt[0] < 250
                            && Math.abs(e.getX() - down[0]) < dp(16) && Math.abs(e.getY() - down[1]) < dp(16)) {
                        if (e.getX() < v.getWidth() * 0.3f) prev(); else next();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    pause(false);
                    return true;
                default:
                    return true;
            }
        });
        root.addView(touch, new FrameLayout.LayoutParams(-1, -1));

        // উপরের অংশ
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(dp(8), dp(10), dp(8), dp(6));
        top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x99000000, 0x00000000}));
        bars = new SegBar(this);
        top.addView(bars, new LinearLayout.LayoutParams(-1, dp(3)));

        LinearLayout hdr = new LinearLayout(this);
        hdr.setOrientation(LinearLayout.HORIZONTAL);
        hdr.setGravity(Gravity.CENTER_VERTICAL);
        hdr.setPadding(0, dp(10), 0, 0);
        avatar = new ImageView(this);
        hdr.addView(avatar, new LinearLayout.LayoutParams(dp(38), dp(38)));
        LinearLayout nameCol = new LinearLayout(this);
        nameCol.setOrientation(LinearLayout.VERTICAL);
        nameCol.setPadding(dp(10), 0, 0, 0);
        tvName = new TextView(this);
        tvName.setTextColor(Color.WHITE);
        tvName.setTextSize(15);
        tvName.setTypeface(Typeface.DEFAULT_BOLD);
        tvTime = new TextView(this);
        tvTime.setTextColor(0xCCFFFFFF);
        tvTime.setTextSize(12);
        nameCol.addView(tvName);
        nameCol.addView(tvTime);
        tvMusic = new TextView(this);
        tvMusic.setTextColor(0xB3FFFFFF);
        tvMusic.setTextSize(12);
        tvMusic.setSingleLine(true);
        tvMusic.setEllipsize(TextUtils.TruncateAt.END);
        tvMusic.setVisibility(View.GONE);
        nameCol.addView(tvMusic);
        hdr.addView(nameCol, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView more = icon("⋯", 24);
        more.setOnClickListener(v -> menu());
        hdr.addView(more, new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView close = icon("✕", 20);
        close.setOnClickListener(v -> finish());
        hdr.addView(close, new LinearLayout.LayoutParams(dp(40), dp(40)));
        top.addView(hdr, new LinearLayout.LayoutParams(-1, -2));
        root.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // নিচের অংশ
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(16), dp(24), dp(16), dp(14));
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xAA000000, 0x00000000}));
        tvInfo = new TextView(this);
        tvInfo.setTextColor(Color.WHITE);
        tvInfo.setTextSize(14);
        tvInfo.setPadding(0, 0, 0, dp(8));
        bottom.addView(tvInfo, new LinearLayout.LayoutParams(-1, -2));

        footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        tvViews = pill("Viewers");
        withIcon(tvViews, R.drawable.ic_short_eye);
        tvViews.setOnClickListener(v -> showViews());
        tvInsight = pill("Insight");
        withIcon(tvInsight, R.drawable.ic_dashboard_white);
        tvInsight.setOnClickListener(v -> showInsight());
        footer.addView(tvViews, new LinearLayout.LayoutParams(-2, dp(38)));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(-2, dp(38));
        ilp.leftMargin = dp(10);
        footer.addView(tvInsight, ilp);
        bottom.addView(footer, new LinearLayout.LayoutParams(-1, -2));
        root.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        return root;
    }

    private TextView icon(String t, int size) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(Color.WHITE);
        v.setTextSize(size);
        v.setGravity(Gravity.CENTER);
        return v;
    }

    private void spanIcon(android.text.SpannableStringBuilder sb, int res, int tint, int sizeDp) {
        android.graphics.drawable.Drawable d = androidx.core.content.ContextCompat.getDrawable(this, res);
        if (d == null) return;
        d = d.mutate();
        d.setTint(tint);
        d.setBounds(0, 0, dp(sizeDp), dp(sizeDp));
        int s = sb.length();
        sb.append("\u200B");
        sb.setSpan(new android.text.style.ImageSpan(d, android.text.style.ImageSpan.ALIGN_BASELINE),
                s, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** পিলের লেখার আগে (বামে) সাদা drawable আইকন — ইমোজি নয় */
    private void withIcon(TextView v, int res) {
        android.graphics.drawable.Drawable d = androidx.core.content.ContextCompat.getDrawable(this, res);
        if (d == null) return;
        d = d.mutate();
        d.setBounds(0, 0, dp(18), dp(18));
        d.setTint(Color.WHITE);
        v.setCompoundDrawables(d, null, null, null);
        v.setCompoundDrawablePadding(dp(6));
    }

    private TextView pill(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(Color.WHITE);
        v.setTextSize(14);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(16), 0, dp(16), 0);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x55FFFFFF);
        g.setCornerRadius(dp(19));
        v.setBackground(g);
        return v;
    }

    /** ভিডিওর অনুপাত বজায় রেখে পর্দায় বসায় */
    private void fitVideo() {
        if (vw <= 0 || vh <= 0 || videoBox.getWidth() <= 0) return;
        float bw = videoBox.getWidth(), bh = videoBox.getHeight();
        float s = Math.min(bw / vw, bh / vh);
        ViewGroup.LayoutParams lp = sv.getLayoutParams();
        lp.width = Math.round(vw * s);
        lp.height = Math.round(vh * s);
        sv.setLayoutParams(lp);
    }

    // ------------------------------------------------------------ লোড

    private void loadUser() {
        curUid = queue.get(qIndex);
        mine = curUid.equals(SupabaseRest.uid());
        mems.clear();
        idx = 0;
        final int g = ++loadGen;
        footer.setVisibility(mine ? View.VISIBLE : View.GONE);
        AvatarLoader.load(avatar, curUid);
        tvName.setText("");
        UserNames.fetch(curUid, n -> { if (g == loadGen && n != null) tvName.setText(n); });

        queryMems(g, true);
    }

    /** extra=true: ai_level, music_title সহ; কলাম না থাকলে (SQL বাকি) পুরনো select-এ ফিরে যায় */
    private void queryMems(final int g, final boolean extra) {
        String path = "/rest/v1/memories?user_id=eq." + curUid + "&expires_at=gt." + isoNow()
                + "&select=id,video_key,location,feeling,tag_names,privacy,created_at,expires_at"
                + (extra ? ",ai_level,music_title" : "")
                + "&order=created_at.asc&limit=50";
        SupabaseRest.call("GET", path, null, null, true, r -> {
            if (g != loadGen || isFinishing()) return;
            if (!r.ok() && extra) { queryMems(g, false); return; }
            if (r.ok()) {
                try {
                    JSONArray a = new JSONArray(r.body);
                    for (int i = 0; i < a.length(); i++) {
                        JSONObject o = a.getJSONObject(i);
                        Mem m = new Mem();
                        m.id = o.optString("id");
                        m.videoKey = o.optString("video_key");
                        m.location = o.optString("location", "");
                        m.feeling = o.optString("feeling", "");
                        m.privacy = o.optString("privacy", "public");
                        m.ai = o.optBoolean("ai_level", false);
                        m.musicTitle = o.optString("music_title", "");
                        m.created = parseIso(o.optString("created_at"));
                        m.expires = parseIso(o.optString("expires_at"));
                        JSONArray t = o.optJSONArray("tag_names");
                        if (t != null) for (int k = 0; k < t.length(); k++) m.tags.add(t.optString(k));
                        mems.add(m);
                    }
                } catch (Exception ignored) { }
            }
            if (mems.isEmpty()) { nextUser(); return; }
            // প্রথম না-দেখা Memory থেকে শুরু (নিজেরটা সবসময় প্রথম থেকে)
            if (!mine) {
                for (int i = 0; i < mems.size(); i++) if (!isSeen(this, mems.get(i).id)) { idx = i; break; }
            }
            bars.setCount(mems.size());
            playCurrent();
        });
    }

    private void playCurrent() {
        if (idx < 0 || idx >= mems.size()) return;
        final Mem m = mems.get(idx);
        final int g = loadGen;
        bars.setCurrent(idx);
        // নামের নিচের লাইন: "6h 🌐 is feeling 😃 Happy · Location [AI]"  (আইকন সব drawable থেকে)
        android.text.SpannableStringBuilder tl = new android.text.SpannableStringBuilder(ago(m.created));
        tl.append("  ");
        spanIcon(tl, "public".equals(m.privacy) ? R.drawable.ic_globe : R.drawable.ic_privacy_lock, Color.WHITE, 14);
        if (!m.feeling.isEmpty()) tl.append("  ").append(m.feeling);
        if (!m.location.isEmpty()) {
            tl.append(m.feeling.isEmpty() ? "  " : "  ·  ");
            spanIcon(tl, R.drawable.ic_current_city, Color.WHITE, 13);
            tl.append(" ").append(m.location);
        }
        if (m.ai) { tl.append("  "); spanIcon(tl, R.drawable.ic_ai_label, 0xFF22D3EE, 18); }
        tvTime.setText(tl);
        tvTime.setSingleLine(true);
        tvTime.setEllipsize(TextUtils.TruncateAt.END);
        if (m.musicTitle.isEmpty()) tvMusic.setVisibility(View.GONE);
        else {
            tvMusic.setText(" " + m.musicTitle);
            android.graphics.drawable.Drawable nd = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_music_note);
            if (nd != null) {
                nd = nd.mutate(); nd.setBounds(0, 0, dp(14), dp(14)); nd.setTint(0xB3FFFFFF);
                tvMusic.setCompoundDrawables(nd, null, null, null);
            }
            tvMusic.setVisibility(View.VISIBLE);
        }
        StringBuilder sb = new StringBuilder();
        if (!m.tags.isEmpty()) { if (sb.length() > 0) sb.append("\n"); sb.append("with ").append(TextUtils.join(", ", m.tags)); }
        tvInfo.setText(sb.toString());
        tvInfo.setVisibility(sb.length() == 0 ? View.GONE : View.VISIBLE);
        tvViews.setText(m.views >= 0 ? m.views + " Viewers" : "Viewers");
        tvBusy.setVisibility(View.VISIBLE);
        player.stop();
        Media.readUrl(m.videoKey, (url, err) -> {
            if (g != loadGen || isFinishing()) return;
            if (url == null) {
                Toast.makeText(this, "Couldn't load this Memory", Toast.LENGTH_SHORT).show();
                next();
                return;
            }
            player.setMediaItem(MediaItem.fromUri(url));
            player.prepare();
            player.setPlayWhenReady(!paused);
        });

        if (mine) loadViewCount(m);
        else markSeen(m);
    }

    private void markSeen(Mem m) {
        SharedPreferences sp = getSharedPreferences(SEEN_PREFS, Context.MODE_PRIVATE);
        Set<String> s = new HashSet<>(sp.getStringSet("ids", new HashSet<>()));
        s.add(m.id);
        if (s.size() > 400) s.clear();      // ছোট রাখার জন্য; মেয়াদ ২৪ ঘণ্টাই
        sp.edit().putStringSet("ids", s).apply();
        String me = SupabaseRest.uid();
        if (me == null) return;
        try {
            JSONObject body = new JSONObject().put("memory_id", m.id).put("viewer_id", me);
            SupabaseRest.call("POST", "/rest/v1/memory_views?on_conflict=memory_id,viewer_id",
                    body.toString(), "resolution=ignore-duplicates,return=minimal", true, null);
        } catch (Exception ignored) { }
    }

    private void loadViewCount(final Mem m) {
        SupabaseRest.call("GET", "/rest/v1/memory_views?memory_id=eq." + m.id + "&select=viewer_id&limit=1000",
                null, null, true, r -> {
            if (!r.ok()) return;
            try {
                m.views = new JSONArray(r.body).length();
                if (idx < mems.size() && mems.get(idx) == m) tvViews.setText(m.views + " Viewers");
            } catch (Exception ignored) { }
        });
    }

    // ------------------------------------------------------------ চলাচল

    private void next() {
        if (idx + 1 < mems.size()) { idx++; retried = false; playCurrent(); }
        else nextUser();
    }

    private void prev() {
        if (idx > 0) { idx--; retried = false; playCurrent(); }
        else if (player != null) player.seekTo(0);
    }

    private void nextUser() {
        if (qIndex + 1 < queue.size()) { qIndex++; retried = false; player.stop(); loadUser(); }
        else finish();
    }

    private void pause(boolean p) {
        paused = p;
        if (player != null) player.setPlayWhenReady(!p);
    }

    private static String ago(long t) {
        if (t <= 0) return "";
        long m = Math.max(0, (System.currentTimeMillis() - t) / 60000L);
        if (m < 1) return "Just now";
        if (m < 60) return m + "m";
        return (m / 60) + "h";
    }

    // ------------------------------------------------------------ মালিকের অপশন

    private void menu() {
        if (!mine || idx >= mems.size()) {
            Toast.makeText(this, "Only the owner can change this Memory", Toast.LENGTH_SHORT).show();
            return;
        }
        pause(true);
        new AlertDialog.Builder(this)
                .setItems(new String[]{"Privacy", "Delete Memory"}, (d, w) -> {
                    if (w == 0) changePrivacy(); else confirmDelete();
                })
                .setOnCancelListener(d -> pause(false))
                .setOnDismissListener(d -> { if (!isFinishing()) pause(false); })
                .show();
    }

    private void changePrivacy() {
        final Mem m = mems.get(idx);
        int cur = 0;
        for (int i = 0; i < MemoryUploader.PRIVACY_VALUE.length; i++)
            if (MemoryUploader.PRIVACY_VALUE[i].equals(m.privacy)) cur = i;
        String[] items = new String[MemoryUploader.PRIVACY_LABEL.length];
        for (int i = 0; i < items.length; i++)
            items[i] = MemoryUploader.PRIVACY_LABEL[i] + "\n" + MemoryUploader.PRIVACY_SUB[i];
        new AlertDialog.Builder(this)
                .setTitle("Who can see this Memory?")
                .setSingleChoiceItems(items, cur, (d, which) -> {
                    final String val = MemoryUploader.PRIVACY_VALUE[which];
                    d.dismiss();
                    try {
                        SupabaseRest.call("PATCH", "/rest/v1/memories?id=eq." + m.id,
                                new JSONObject().put("privacy", val).toString(), "return=minimal", true, r -> {
                            if (r.ok()) { m.privacy = val; Toast.makeText(this, "Privacy updated", Toast.LENGTH_SHORT).show(); }
                            else Toast.makeText(this, SupabaseRest.errorText(r), Toast.LENGTH_SHORT).show();
                        });
                    } catch (Exception ignored) { }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmDelete() {
        final Mem m = mems.get(idx);
        new AlertDialog.Builder(this)
                .setTitle("Delete this Memory?")
                .setMessage("It will be removed for everyone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, w) -> {
                    String me = SupabaseRest.uid();
                    SupabaseRest.call("DELETE", "/rest/v1/memories?id=eq." + m.id + "&user_id=eq." + me,
                            null, "return=minimal", true, r -> {
                        if (!r.ok()) { Toast.makeText(this, SupabaseRest.errorText(r), Toast.LENGTH_SHORT).show(); return; }
                        mems.remove(m);
                        if (mems.isEmpty()) { finish(); return; }
                        if (idx >= mems.size()) idx = mems.size() - 1;
                        bars.setCount(mems.size());
                        retried = false;
                        playCurrent();
                    });
                })
                .show();
    }

    // ------------------------------------------------------------ Views ও Insight

    private void showViews() {
        if (idx >= mems.size()) return;
        final Mem m = mems.get(idx);
        pause(true);
        final Dialog dlg = sheet();
        LinearLayout col = new LinearLayout(dlg.getContext());
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(16), dp(16), dp(24));
        TextView title = new TextView(this);
        title.setText("Viewers");
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.BLACK);
        col.addView(title);
        final TextView state = new TextView(this);
        state.setText("Loading…");
        state.setTextColor(0xFF65676B);
        state.setPadding(0, dp(12), 0, 0);
        col.addView(state);
        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView sc = new ScrollView(this);
        sc.addView(list);
        col.addView(sc, new LinearLayout.LayoutParams(-1, dp(360)));
        dlg.setContentView(col);
        dlg.setOnDismissListener(d -> { if (!isFinishing()) pause(false); });
        dlg.show();

        SupabaseRest.call("GET", "/rest/v1/memory_views?memory_id=eq." + m.id
                + "&select=viewer_id,viewed_at&order=viewed_at.desc&limit=200", null, null, true, r -> {
            if (!r.ok()) { state.setText(SupabaseRest.errorText(r)); return; }
            try {
                JSONArray a = new JSONArray(r.body);
                m.views = a.length();
                tvViews.setText(m.views + " Viewers");
                state.setText(a.length() == 0 ? "No views yet" : a.length() + (a.length() == 1 ? " view" : " views"));
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    String uid = o.optString("viewer_id");
                    LinearLayout row = new LinearLayout(this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(0, dp(8), 0, dp(8));
                    ImageView av = new ImageView(this);
                    AvatarLoader.load(av, uid);
                    row.addView(av, new LinearLayout.LayoutParams(dp(40), dp(40)));
                    final TextView nm = new TextView(this);
                    nm.setTextColor(Color.BLACK);
                    nm.setTextSize(15);
                    nm.setPadding(dp(12), 0, 0, 0);
                    UserNames.fetch(uid, n -> nm.setText(n == null ? "Family member" : n));
                    row.addView(nm, new LinearLayout.LayoutParams(0, -2, 1f));
                    TextView tm = new TextView(this);
                    tm.setText(ago(parseIso(o.optString("viewed_at"))));
                    tm.setTextColor(0xFF65676B);
                    tm.setTextSize(12);
                    row.addView(tm);
                    list.addView(row, new LinearLayout.LayoutParams(-1, -2));
                }
            } catch (Exception e) { state.setText("Couldn't load viewers"); }
        });
    }

    private void showInsight() {
        if (idx >= mems.size()) return;
        final Mem m = mems.get(idx);
        pause(true);
        long left = Math.max(0, (m.expires - System.currentTimeMillis()) / 60000L);
        String leftTxt = left >= 60 ? (left / 60) + "h " + (left % 60) + "m" : left + "m";
        String priv = m.privacy;
        for (int i = 0; i < MemoryUploader.PRIVACY_VALUE.length; i++)
            if (MemoryUploader.PRIVACY_VALUE[i].equals(m.privacy)) priv = MemoryUploader.PRIVACY_LABEL[i];
        String msg = "Views: " + (m.views >= 0 ? m.views : "—")
                + "\nPosted: " + ago(m.created) + " ago"
                + "\nTime left: " + leftTxt
                + "\nPrivacy: " + priv
                + "\nMemory " + (idx + 1) + " of " + mems.size()
                + (m.tags.isEmpty() ? "" : "\nTagged: " + TextUtils.join(", ", m.tags));
        new AlertDialog.Builder(this)
                .setTitle("Insight")
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .setOnDismissListener(d -> { if (!isFinishing()) pause(false); })
                .show();
    }

    private Dialog sheet() {
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window w = d.getWindow();
        if (w != null) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.WHITE);
            float r = dp(18);
            bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
            w.setBackgroundDrawable(bg);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
        }
        return d;
    }

    // ------------------------------------------------------------ জীবনচক্র

    @Override protected void onPause() { super.onPause(); if (player != null) player.setPlayWhenReady(false); }
    @Override protected void onResume() { super.onResume(); if (player != null && !paused) player.setPlayWhenReady(true); }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        H.removeCallbacksAndMessages(null);
        if (player != null) { player.release(); player = null; }
    }

    // ------------------------------------------------------------ প্রোগ্রেস বার

    private static final class SegBar extends View {
        private int count = 1, cur = 0;
        private float frac = 0f;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rf = new RectF();

        SegBar(Context c) { super(c); }

        void setCount(int n) { count = Math.max(1, n); invalidate(); }
        void setCurrent(int i) { cur = i; frac = 0f; invalidate(); }
        void setFraction(int i, float f) { cur = i; frac = Math.max(0f, Math.min(1f, f)); invalidate(); }

        @Override protected void onDraw(Canvas c) {
            float gap = 4f * getResources().getDisplayMetrics().density;
            float w = (getWidth() - gap * (count - 1)) / count;
            float h = getHeight();
            for (int i = 0; i < count; i++) {
                float x = i * (w + gap);
                p.setColor(0x66FFFFFF);
                rf.set(x, 0, x + w, h);
                c.drawRoundRect(rf, h / 2, h / 2, p);
                float f = i < cur ? 1f : (i == cur ? frac : 0f);
                if (f > 0f) {
                    p.setColor(Color.WHITE);
                    rf.set(x, 0, x + w * f, h);
                    c.drawRoundRect(rf, h / 2, h / 2, p);
                }
            }
        }
    }
}
