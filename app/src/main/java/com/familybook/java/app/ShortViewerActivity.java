package com.familybook.app;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;


/**
 * Short-এর ফুল-ব্ল্যাক-স্ক্রিন ভিউয়ার (Facebook Reels স্টাইল): উপর থেকে নিচে একটার পর একটা।
 *  - 16:9 / 1:1 ভিডিও  → কালো স্ক্রিনের মাঝখানে পুরো ভিডিও (ছবি ২-এর মতো)
 *  - লম্বা (9:16, 4:5, 3:4 …) → পুরো স্ক্রিন জুড়ে (ছবি ৩-এর মতো)
 * উপরে: প্রোফাইল ছবি + নাম + গ্লোব + follow, "with ..." লাইন, ডানে X (বন্ধ), তার নিচে ক্যাপশন + more।
 * ডানে: Like / Comment / Share / Save / Sound / ⋯ । নিচে: গানের নাম (বাঁয়ে), View insights (ডানে — শুধু নিজের Short)।
 */
public class ShortViewerActivity extends AppCompatActivity implements PostOps.Refreshable {

    static void open(Context c, ArrayList<String> ids, int index) {
        Intent i = new Intent(c, ShortViewerActivity.class);
        i.putStringArrayListExtra("ids", ids);
        i.putExtra("index", index);
        c.startActivity(i);
    }

    /** নিচের বার / মেনুর Shorts আইকন: Shorts ফিড পেজ (Your Shorts · Followers · Public) */
    static void openFeed(Context c) {
        c.startActivity(new Intent(c, ShortViewerActivity.class).putExtra("feed", true));
    }

    private static final int FEED_BG = 0xFF000000;   // হেডার সম্পূর্ণ কালো
    private boolean feed = false;
    private String feedTab = "your", pendingTab = null;
    private int tabToken = 0;
    private TextView tvEmpty;
    private final List<TextView> tabViews = new ArrayList<>();
    private static final String[] TAB_KEYS = {"your", "followers", "public"};
    private static final String[] TAB_NAMES = {"Your Shorts", "Followers", "Public"};

    private static final int ACTION_DP = 46;      // লাইক/কমেন্ট/শেয়ার... বাটনের মাপ
    private static final int ACTION_PAD_DP = 9;
    private static final int STRIP_DP = 28;       // কালো স্ট্রিপ: সময় / A.I / View insights / ⋯
    private static final int SEEK_DP = 16;        // সিক-লাইন
    private static final int COMMENT_DP = 52;     // নিচের "Add a comment" বক্স

    private final List<ShortStore.Info> list = new ArrayList<>();
    private final Set<String> following = new HashSet<>();
    private boolean followLoaded = false;
    private final Set<String> followersSet = new HashSet<>();   // আমাকে যারা follow করে
    private boolean followersLoaded = false;
    private RecyclerView rv;
    private LinearLayoutManager lm;
    private PagerSnapHelper snap;
    private String myUid;
    private int startIndex;
    private boolean started = false;
    private boolean commentsOpen = false;   // কমেন্ট শিট খোলা: ভিডিও উপরে ছোট হয়ে চলতে থাকে
    private Page commentsPage;
    private boolean fromSound = false;      // সাউন্ড পেজ থেকে ফিরলে ভিডিও আবার চালু ও আনমিউট

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        w.setStatusBarColor(Color.BLACK);
        w.setNavigationBarColor(Color.BLACK);
        myUid = SupabaseRest.uid();

        feed = getIntent().getBooleanExtra("feed", false);
        ArrayList<String> ids = getIntent().getStringArrayListExtra("ids");
        startIndex = getIntent().getIntExtra("index", 0);
        if (ids != null && !feed) {
            for (String id : ids) {
                ShortStore.Info i = ShortStore.byId(id);
                if (i != null && i.videoKey != null) list.add(i);
                else if (ids.indexOf(id) < startIndex) startIndex--;   // বাদ পড়লে শুরুর সূচক মেলাই
            }
        }
        if (list.isEmpty() && !feed) { finish(); return; }
        startIndex = list.isEmpty() ? 0 : Math.max(0, Math.min(startIndex, list.size() - 1));

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        rv = new RecyclerView(this);
        lm = new LinearLayoutManager(this, RecyclerView.VERTICAL, false);
        rv.setLayoutManager(lm);
        rv.setAdapter(new PageAdapter());
        snap = new PagerSnapHelper();
        snap.attachToRecyclerView(rv);
        rv.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrollStateChanged(RecyclerView r, int state) {
                if (state == RecyclerView.SCROLL_STATE_IDLE) playCurrent();
            }
        });
        if (feed) {
            w.setStatusBarColor(Color.BLACK);
            LinearLayout rootV = new LinearLayout(this);
            rootV.setOrientation(LinearLayout.VERTICAL);
            rootV.setBackgroundColor(Color.BLACK);
            rootV.addView(buildFeedHeader(), new LinearLayout.LayoutParams(-1, -2));
            FrameLayout body = new FrameLayout(this);
            body.addView(rv, new FrameLayout.LayoutParams(-1, -1));
            tvEmpty = new TextView(this);
            tvEmpty.setTextColor(0xFF9A9A9A);
            tvEmpty.setTextSize(16);
            tvEmpty.setTypeface(null, Typeface.BOLD);
            tvEmpty.setGravity(Gravity.CENTER);
            tvEmpty.setVisibility(View.GONE);
            body.addView(tvEmpty, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
            rootV.addView(body, new LinearLayout.LayoutParams(-1, 0, 1f));
            setContentView(rootV);
            loadFollowing();
            loadTab("your");
        } else {
            root.addView(rv, new FrameLayout.LayoutParams(-1, -1));
            setContentView(root);
            lm.scrollToPositionWithOffset(startIndex, 0);
            rv.post(this::playCurrent);
            loadFollowing();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (commentsOpen) {                 // কমেন্ট শিট বন্ধ করে ফিরেছি: ভিডিও আগের জায়গায়, কাউন্ট নতুন
            commentsOpen = false;
            final Page p = commentsPage;
            commentsPage = null;
            if (p != null) {
                p.exitSheet();
                refreshPostStats(p);
            }
            return;
        }
        if (fromSound) ShortPlayerView.viewerMuted = false;
        if (rv != null) rv.post(this::playCurrent);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (commentsOpen) return;           // কমেন্ট শিটের পেছনে ভিডিও চলতেই থাকবে
        ShortPlayerView.stopAll();
        ShortPlayerView.releasePreloaded();
        String ck = MusicPlayer.currentKey();
        if (ck != null && ck.startsWith("short:")) MusicPlayer.stop();
    }

    /** আমি কাদের follow করি — একবার এনে রাখি (follow বাটন দেখানো/লুকানোর জন্য) */
    private void loadFollowing() {
        if (myUid == null) return;
        SupabaseRest.call("GET", "/rest/v1/follows?follower_id=eq." + myUid + "&select=followee_id&limit=5000",
                null, null, true, r -> {
                    if (isFinishing()) return;
                    if (r.ok()) {
                        try {
                            JSONArray a = new JSONArray(r.body);
                            for (int i = 0; i < a.length(); i++) {
                                String f = a.getJSONObject(i).optString("followee_id", "");
                                if (!f.isEmpty()) following.add(f);
                            }
                            followLoaded = true;
                        } catch (Exception ignored) { }
                    }
                    refreshFollowButtons();
                    runPendingTab();
                });
        if (feed) {
            SupabaseRest.call("GET", "/rest/v1/follows?followee_id=eq." + myUid + "&select=follower_id&limit=5000",
                    null, null, true, r -> {
                        if (isFinishing()) return;
                        if (r.ok()) {
                            try {
                                JSONArray a = new JSONArray(r.body);
                                for (int i = 0; i < a.length(); i++) {
                                    String f = a.getJSONObject(i).optString("follower_id", "");
                                    if (!f.isEmpty()) followersSet.add(f);
                                }
                            } catch (Exception ignored) { }
                        }
                        followersLoaded = true;
                        runPendingTab();
                    });
        }
    }

    private void runPendingTab() {
        if (feed && pendingTab != null && followLoaded && followersLoaded) {
            String t = pendingTab; pendingTab = null; loadTab(t);
        }
    }

    // ------------------------------------------------------------ Shorts ফিড পেজ: হেডার + ট্যাব + লোড

    private int dpi(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private View buildFeedHeader() {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.VERTICAL);
        h.setBackgroundColor(FEED_BG);

        FrameLayout row1 = new FrameLayout(this);
        ImageView back = new ImageView(this);
        back.setImageResource(R.drawable.ic_back_left);
        back.setColorFilter(Color.WHITE);
        back.setPadding(dpi(13), dpi(13), dpi(13), dpi(13));
        back.setOnClickListener(v -> finish());
        row1.addView(back, new FrameLayout.LayoutParams(dpi(44), dpi(44), Gravity.START | Gravity.CENTER_VERTICAL));
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.HORIZONTAL);
        mid.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_shorts_logo);
        logo.setColorFilter(Color.WHITE);
        mid.addView(logo, new LinearLayout.LayoutParams(dpi(26), dpi(26)));
        TextView title = new TextView(this);
        title.setText("Shorts");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
        tl.setMarginStart(dpi(6));
        mid.addView(title, tl);
        row1.addView(mid, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        h.addView(row1, new LinearLayout.LayoutParams(-1, dpi(42)));

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER);
        row2.setPadding(0, 0, 0, dpi(6));
        for (int i = 0; i < 3; i++) {
            final String key = TAB_KEYS[i];
            TextView t = new TextView(this);
            t.setText(TAB_NAMES[i]);
            t.setTextColor(Color.WHITE);
            t.setTextSize(13);
            t.setTypeface(null, Typeface.BOLD);
            t.setGravity(Gravity.CENTER);
            t.setOnClickListener(v -> loadTab(key));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dpi(80), dpi(27));
            lp.setMargins(dpi(3), 0, dpi(3), 0);
            row2.addView(t, lp);
            tabViews.add(t);
        }
        h.addView(row2, new LinearLayout.LayoutParams(-1, -2));

        View band = new View(this);
        band.setBackgroundColor(0xFF4A4546);
        h.addView(band, new LinearLayout.LayoutParams(-1, dpi(3)));
        styleTabs();
        return h;
    }

    private void styleTabs() {
        for (int i = 0; i < tabViews.size(); i++) {
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dpi(4));
            g.setColor(TAB_KEYS[i].equals(feedTab) ? 0xFF0A6BCF : 0xFF666666);
            tabViews.get(i).setBackground(g);
        }
    }

    /** ট্যাবের Shorts এনে দেখায়: Your = আমার; Followers = যাদের follow করি; Public = সবার public */
    private void loadTab(final String tab) {
        feedTab = tab;
        styleTabs();
        final int token = ++tabToken;
        ShortPlayerView.stopAll();
        list.clear();
        rv.getAdapter().notifyDataSetChanged();
        started = false;
        tvEmpty.setText("Loading…");
        tvEmpty.setVisibility(View.VISIBLE);

        if (myUid == null) { tvEmpty.setText("No shorts yet"); return; }
        if (!"your".equals(tab) && !ReelInterest.loaded) {      // Interested / Not interested আগে আনি
            ReelInterest.load(() -> { if (!isFinishing() && token == tabToken) loadTab(tab); });
            return;
        }
        final Set<String> interested = ReelInterest.with(1);
        final Set<String> notInterested = ReelInterest.with(-1);
        String filter;
        if ("your".equals(tab)) {
            filter = "user_id=eq." + myUid;
        } else if ("followers".equals(tab)) {
            // Followers = আমি যাদের follow করি + যারা আমাকে follow করে
            if (!followLoaded || !followersLoaded) { pendingTab = tab; return; }
            Set<String> ids = new HashSet<>(following);
            ids.addAll(followersSet);
            ids.remove(myUid);
            ids.removeAll(notInterested);
            if (ids.isEmpty()) { tvEmpty.setText("No shorts yet"); return; }
            filter = "user_id=in.(" + join(ids) + ")";
        } else {
            // Public = FamilyBook-এর বাকি সব ইউজার (আমি, আমার following ও followers বাদে)
            if (!followLoaded || !followersLoaded) { pendingTab = tab; return; }
            Set<String> ex = new HashSet<>(following);
            ex.addAll(followersSet);
            ex.add(myUid);
            ex.addAll(notInterested);
            interested.removeAll(notInterested);
            interested.remove(myUid);
            filter = interested.isEmpty()
                    ? "user_id=not.in.(" + join(ex) + ")"
                    : "or=(user_id.not.in.(" + join(ex) + "),user_id.in.(" + join(interested) + "))";
        }
        SupabaseRest.call("GET", "/rest/v1/shorts?" + filter + "&status=neq.deleted&select=" + ShortStore.SELECT
                + "&order=created_at.desc&limit=40", null, null, true, r -> {
            if (isFinishing() || token != tabToken) return;
            final List<ShortStore.Info> got = new ArrayList<>();
            if (r.ok()) {
                try {
                    JSONArray a = new JSONArray(r.body);
                    for (int i = 0; i < a.length(); i++) {
                        ShortStore.Info s = ShortStore.put(a.getJSONObject(i));
                        if (s != null && s.videoKey != null) got.add(s);
                    }
                } catch (Exception ignored) { }
            } else {
                tvEmpty.setText("Could not load shorts");
                return;
            }
            if (got.isEmpty()) { tvEmpty.setText("No shorts yet"); return; }
            final int[] left = {got.size()};
            for (final ShortStore.Info s : got) {
                ShortStore.loadPost(s, p -> {
                    if (isFinishing() || token != tabToken) return;
                    if (--left[0] > 0) return;
                    for (ShortStore.Info x : got) {
                        String pv = x.post == null ? null : x.post.privacy;
                        boolean ok;
                        if ("your".equals(tab)) ok = true;
                        else if ("followers".equals(tab)) ok = pv != null && !"me".equals(pv);
                        else ok = "public".equals(pv);
                        if (ok) list.add(x);
                    }
                    // Interested দেওয়া নির্মাতাদের ভিডিও সবার আগে (নতুন আপলোড সহ)
                    if (!"your".equals(tab) && !interested.isEmpty()) {
                        List<ShortStore.Info> first = new ArrayList<>(), rest = new ArrayList<>();
                        for (ShortStore.Info x : list) (interested.contains(x.uid) ? first : rest).add(x);
                        list.clear();
                        list.addAll(first);
                        list.addAll(rest);
                    }
                    rv.getAdapter().notifyDataSetChanged();
                    tvEmpty.setText("No shorts yet");
                    tvEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
                    rv.post(this::playCurrent);
                });
            }
        });
    }

    private static String join(Set<String> ids) {
        StringBuilder b = new StringBuilder();
        for (String f : ids) { if (b.length() > 0) b.append(','); b.append(f); }
        return b.toString();
    }

    /** চলতি সময়: ১ মিনিটের কম হলে শুধু সেকেন্ড ("15"), নইলে m:ss */
    static String fmtCur(int ms) {
        int s = Math.max(0, ms / 1000);
        return s < 60 ? String.valueOf(s) : (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }

    /** মোট সময়: m:ss ("1:00") */
    static String fmtTotal(int ms) {
        int s = Math.max(0, ms / 1000);
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }

    static String ago(long ts) {
        if (ts <= 0) return "";
        long s = Math.max(0, (System.currentTimeMillis() - ts) / 1000);
        if (s < 60) return "Just now";
        long m = s / 60;
        if (m < 60) return m + (m == 1 ? " Minute ago..." : " Minutes ago...");
        long h = m / 60;
        if (h < 24) return h + (h == 1 ? " Hour ago..." : " Hours ago...");
        long d = h / 24;
        if (d < 30) return d + (d == 1 ? " Day ago..." : " Days ago...");
        long mo = d / 30;
        if (mo < 12) return mo + (mo == 1 ? " Month ago..." : " Months ago...");
        long y = mo / 12;
        return y + (y == 1 ? " Year ago..." : " Years ago...");
    }

    /** "3 weeks ago" স্টাইল (More খুললে কালো অংশে) */
    static String agoShort(long ts) {
        if (ts <= 0) return "";
        long s = Math.max(0, (System.currentTimeMillis() - ts) / 1000);
        if (s < 60) return "Just now";
        long m = s / 60;
        if (m < 60) return m + (m == 1 ? " minute ago" : " minutes ago");
        long h = m / 60;
        if (h < 24) return h + (h == 1 ? " hour ago" : " hours ago");
        long d = h / 24;
        if (d < 7) return d + (d == 1 ? " day ago" : " days ago");
        long w = d / 7;
        if (d < 30) return w + (w == 1 ? " week ago" : " weeks ago");
        long mo = d / 30;
        if (mo < 12) return mo + (mo == 1 ? " month ago" : " months ago");
        long y = mo / 12;
        return y + (y == 1 ? " year ago" : " years ago");
    }

    private void refreshFollowButtons() {
        if (rv == null) return;
        for (int i = 0; i < rv.getChildCount(); i++) {
            RecyclerView.ViewHolder vh = rv.getChildViewHolder(rv.getChildAt(i));
            if (vh instanceof Page) ((Page) vh).updateFollow();
        }
    }

    private void doFollow(Page h) {
        if (h.info == null || h.info.uid == null || myUid == null) return;
        if (!Net.check(this)) return;
        final String target = h.info.uid;
        try {
            JSONObject row = new JSONObject().put("follower_id", myUid).put("followee_id", target);
            following.add(target);        // সাথে সাথে বাটন সরে যায়; ব্যর্থ হলে ফিরে আসে
            refreshFollowButtons();
            SupabaseRest.call("POST", "/rest/v1/follows?on_conflict=follower_id,followee_id",
                    row.toString(), "resolution=merge-duplicates,return=minimal", true, r -> {
                        if (!r.ok()) {
                            following.remove(target);
                            refreshFollowButtons();
                            toast(SupabaseRest.errorText(r));
                            return;
                        }
                        NotificationHelper.send(target, "follow", null);
                    });
        } catch (Exception e) {
            following.remove(target);
            refreshFollowButtons();
            toast("Could not follow");
        }
    }

    /** বর্তমান পাতার ভিডিও চলে, বাকিগুলো থামে (ডিকোডার বাঁচাতে) */
    private void playCurrent() {
        if (rv == null) return;
        View cur = snap.findSnapView(lm);
        // চলতি ভিডিওর উপরে ২টি ও নিচে ২টি ভিডিও আগে থেকেই লোড করে রাখি, যাতে স্ক্রল করলে লোডিং না আসে
        int curPos = cur == null ? -1 : rv.getChildAdapterPosition(cur);
        ShortPlayerView.autoNext = MediaPrefs.autoNext(this);
        boolean auto = MediaPrefs.autoplay(this);
        for (int i = 0; i < rv.getChildCount(); i++) {
            View c = rv.getChildAt(i);
            RecyclerView.ViewHolder vh = rv.getChildViewHolder(c);
            if (!(vh instanceof Page)) continue;
            Page p = (Page) vh;
            if (c == cur) {
                // প্রথমটা (ট্যাপ করে খোলা) সবসময় চলে; পরেরগুলো অটো-প্লে সেটিং মানে
                if (!started || auto || fromSound || ShortPlayerView.autoNext) p.player.play();
                else p.player.stop();
                started = true;
                p.updateMuteIcon();
            } else {
                p.player.stop();
            }
        }
        fromSound = false;
        // চলতি ভিডিও আগে চালু হয়, তারপর আশপাশের ভিডিও রেডি করা হয়
        if (curPos >= 0) {
            Set<String> warm = new java.util.LinkedHashSet<>();
            for (int k = 1; k <= 2; k++) {
                if (curPos + k < list.size()) warm.add(list.get(curPos + k).videoKey);
                if (curPos - k >= 0) warm.add(list.get(curPos - k).videoKey);
            }
            ShortPlayerView.preload(warm);
        }
    }

    /** Short-এর সাথে সেভ হওয়া গান (shorts টেবিল), নইলে ফিড-পোস্টের গান; কিছু না থাকলে null = অরিজিনাল সাউন্ড */
    static PostMusic.Info musicOf(ShortStore.Info i) {
        if (i == null) return null;
        if (i.musicUrl != null && !i.musicUrl.isEmpty()) {
            PostMusic.Info m = new PostMusic.Info();
            m.title = i.musicTitle; m.artist = i.musicArtist; m.cover = i.musicCover; m.url = i.musicUrl;
            m.start = i.musicStart; m.len = i.musicLen > 0 ? i.musicLen : 15000;
            return m;
        }
        return i.post == null ? null : PostMusic.get(i.post.id);
    }

    /** রিল শেষ: পরেরটায় নিচে স্ক্রল (শেষ রিল হলে আবার শুরু থেকে) */
    private void scrollNext(Page h) {
        int pos = h.getBindingAdapterPosition();
        if (pos < 0) return;
        if (pos + 1 < list.size()) {
            rv.smoothScrollToPosition(pos + 1);     // স্ক্রল থামলে playCurrent() পরেরটা চালায়
        } else {
            h.player.seekTo(0);
            h.player.play();
        }
    }

    /** কমেন্ট: ভিডিও উপরে ছোট হয়ে চলতে থাকে, নিচে কমেন্ট শিট। focus = সাথে সাথে কিবোর্ড; at = "@" বসিয়ে মেনশন-তালিকা */
    private void openComments(Page h, boolean focus, boolean at) {
        if (h.info == null || h.info.post == null) { toast("Loading…"); return; }
        commentsOpen = true;
        commentsPage = h;
        h.enterSheet();
        startActivity(new Intent(this, CommentsActivity.class)
                .putExtra("postId", h.info.post.id)
                .putExtra("focus", focus)
                .putExtra("at", at));
    }

    /** কমেন্ট/রিঅ্যাকশন বদলাতে পারে, তাই ফিরে এসে পোস্টের কাউন্ট নতুন করে আনি */
    private void refreshPostStats(final Page p) {
        final ShortStore.Info i = p.info;
        if (i == null) return;
        final Post old = i.post;
        i.post = null;
        ShortStore.loadPost(i, post -> {
            if (post == null) i.post = old;
            if (p.info == i) p.stats();
        });
    }

    /** ভিডিওর উপরের সাউন্ড বাটন: সব সময় শুধু মিউট/আনমিউট */
    private void onSoundButton(Page h) {
        if (h.info == null) return;
        h.player.setMuted(!h.player.isMuted());
        h.updateMuteIcon();
    }

    /** নিচে বামের গানের নাম: গান থাকলে মিউজিকের সাউন্ড পেজ, না থাকলে Original_sound.mp3-এর সাউন্ড পেজ */
    private void onSoundLabel(Page h) {
        if (h.info == null) return;
        if (!Net.check(this)) return;
        PostMusic.Info m = musicOf(h.info);
        h.player.setMuted(true);        // ভিডিওর সাউন্ড অটো মিউট
        h.player.pause();               // ভিডিও বন্ধ
        h.updateMuteIcon();
        fromSound = true;
        if (m != null && m.url != null && !m.url.isEmpty()) ShortSoundActivity.open(this, m, h.info.id);
        else ShortSoundActivity.openOriginal(this, h.info);
    }

    /** ⋯ মেনু থেকে পোস্ট মুছলে/সরালে — ভিউয়ার বন্ধ */
    @Override
    public void refreshPosts() {
        finish();
    }

    // ------------------------------------------------------------ পাতা

    private final class PageAdapter extends RecyclerView.Adapter<Page> {
        @Override public Page onCreateViewHolder(ViewGroup parent, int type) {
            return new Page(ShortViewerActivity.this);
        }
        @Override public void onBindViewHolder(Page h, int pos) { h.bind(list.get(pos)); }
        @Override public int getItemCount() { return list.size(); }
        @Override public void onViewRecycled(Page h) { h.player.stop(); h.stopTick(); }
    }

    private final class Page extends RecyclerView.ViewHolder {
        FrameLayout box;
        ShortPlayerView player;
        ImageView avatar, globe, like, comment, share, save, more, spk, backBtn, searchBtn, dotsBtn;
        TextView name, withLine, follow, caption, likes, comments, insights, aiTag, tags, soundName, stripTime;
        View insightsBox, soundRow;
        LinearLayout topBox, col, stripLeft;
        SeekBar seek;
        GradientDrawable thumb;
        final List<View> chrome = new ArrayList<>();     // কমেন্ট শিটে লুকানো অংশ
        boolean sheet = false, dragging = false, ctrlOn = false, expanded = false;
        ShortStore.Info info;
        String fullCaption = "";

        final Runnable tick = new Runnable() {
            @Override public void run() {
                if (info != null && itemView.isAttachedToWindow()) {
                    updateProgress();
                    if (ctrlOn) updateStrip();
                }
                seek.postDelayed(this, 250);
            }
        };
        final Runnable autoHide = this::hideControls;

        Page(Context c) {
            super(new FrameLayout(c));
            final float d = c.getResources().getDisplayMetrics().density;
            box = (FrameLayout) itemView;
            box.setLayoutParams(new RecyclerView.LayoutParams(-1, -1));
            box.setBackgroundColor(Color.BLACK);
            final int barTotal = Math.round((STRIP_DP + SEEK_DP + COMMENT_DP) * d);
            final int lowBar = Math.round((SEEK_DP + COMMENT_DP) * d);

            // ভিডিও: নিচের কালো বারের উপরের পুরো জায়গা
            player = new ShortPlayerView(c, true);
            FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(-1, -1);
            pl.bottomMargin = barTotal;
            box.addView(player, pl);
            player.setOnClickListener(v -> player.togglePlay());
            player.onEnded = () -> scrollNext(this);

            // উপরের ও নিচের হালকা ছায়া (লেখা পড়ার জন্য)
            View scrimTop = new View(c);
            scrimTop.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0x66000000, 0x00000000}));
            box.addView(scrimTop, new FrameLayout.LayoutParams(-1, Math.round(90 * d), Gravity.TOP));
            View scrimBottom = new View(c);
            scrimBottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0x00000000, 0xB3000000}));
            FrameLayout.LayoutParams sbl = new FrameLayout.LayoutParams(-1, Math.round(230 * d), Gravity.BOTTOM);
            sbl.bottomMargin = barTotal;
            box.addView(scrimBottom, sbl);

            // নিচের কালো অংশ: স্ট্রিপ (সময় / A.I / insights / ⋯) + সিক-লাইন + কমেন্ট বক্স
            View barBg = new View(c);
            barBg.setBackgroundColor(Color.BLACK);
            box.addView(barBg, new FrameLayout.LayoutParams(-1, barTotal, Gravity.BOTTOM));

            seek = new SeekBar(c);
            GradientDrawable trackBg = new GradientDrawable();
            trackBg.setColor(0xFF4A4546);
            trackBg.setSize(1, Math.round(2 * d));
            GradientDrawable trackFg = new GradientDrawable();
            trackFg.setColor(Color.WHITE);
            trackFg.setSize(1, Math.round(2 * d));
            LayerDrawable ld = new LayerDrawable(new android.graphics.drawable.Drawable[]{
                    trackBg, new ClipDrawable(trackFg, Gravity.START, ClipDrawable.HORIZONTAL)});
            ld.setId(0, android.R.id.background);
            ld.setId(1, android.R.id.progress);
            seek.setProgressDrawable(ld);
            thumb = new GradientDrawable();
            thumb.setShape(GradientDrawable.OVAL);
            thumb.setColor(0x00FFFFFF);
            thumb.setSize(Math.round(10 * d), Math.round(10 * d));
            seek.setThumb(thumb);
            seek.setThumbOffset(0);
            seek.setPadding(0, 0, 0, 0);
            seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar sb, int prog, boolean fromUser) {
                    if (fromUser) stripTime.setText(fmtCur(prog) + "/" + fmtTotal(sb.getMax()));
                }
                @Override public void onStartTrackingTouch(SeekBar sb) {
                    dragging = true;
                    thumb.setColor(Color.WHITE);
                    showControls();
                    seek.removeCallbacks(autoHide);
                }
                @Override public void onStopTrackingTouch(SeekBar sb) {
                    dragging = false;
                    thumb.setColor(0x00FFFFFF);
                    player.seekTo(sb.getProgress());
                    restartHide();
                }
            });
            FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(-1, Math.round(SEEK_DP * d), Gravity.BOTTOM);
            sl.bottomMargin = Math.round(COMMENT_DP * d);
            box.addView(seek, sl);

            // কমেন্ট বক্স (১নং ছবির মতো): Add a comment ........ @  ☺  GIF
            LinearLayout cbar = new LinearLayout(c);
            cbar.setOrientation(LinearLayout.HORIZONTAL);
            cbar.setGravity(Gravity.CENTER_VERTICAL);
            cbar.setBackgroundColor(Color.BLACK);
            cbar.setPadding(Math.round(14 * d), Math.round(5 * d), Math.round(14 * d), Math.round(7 * d));
            LinearLayout pill = new LinearLayout(c);
            pill.setOrientation(LinearLayout.HORIZONTAL);
            pill.setGravity(Gravity.CENTER_VERTICAL);
            pill.setBackgroundResource(R.drawable.bg_cm_pill_dark);
            pill.setPadding(Math.round(18 * d), 0, Math.round(12 * d), 0);
            TextView hint = new TextView(c);
            hint.setText("Add a comment");
            hint.setTextColor(Color.WHITE);
            hint.setTextSize(16);
            hint.setSingleLine(true);
            pill.addView(hint, new LinearLayout.LayoutParams(0, -2, 1f));
            pill.setOnClickListener(v -> openComments(this, true, false));
            ImageView atIc = new ImageView(c);
            atIc.setImageResource(R.drawable.ic_cm_at);
            atIc.setColorFilter(Color.WHITE);
            atIc.setPadding(Math.round(6 * d), Math.round(6 * d), Math.round(6 * d), Math.round(6 * d));
            atIc.setOnClickListener(v -> openComments(this, true, true));
            pill.addView(atIc, new LinearLayout.LayoutParams(Math.round(38 * d), Math.round(38 * d)));
            ImageView stIc = new ImageView(c);
            stIc.setImageResource(R.drawable.ic_cm_sticker);
            stIc.setColorFilter(Color.WHITE);
            stIc.setPadding(Math.round(6 * d), Math.round(6 * d), Math.round(6 * d), Math.round(6 * d));
            stIc.setOnClickListener(v -> { });            // ডেমো: কিছু করে না, টোস্টও নেই
            LinearLayout.LayoutParams stl = new LinearLayout.LayoutParams(Math.round(38 * d), Math.round(38 * d));
            stl.setMarginStart(Math.round(4 * d));
            pill.addView(stIc, stl);
            TextView gifIc = new TextView(c);
            gifIc.setText("GIF");
            gifIc.setTextColor(Color.WHITE);
            gifIc.setTextSize(11);
            gifIc.setTypeface(null, Typeface.BOLD);
            gifIc.setGravity(Gravity.CENTER);
            gifIc.setBackgroundResource(R.drawable.bg_cm_gif);
            gifIc.setOnClickListener(v -> { });           // ডেমো
            LinearLayout.LayoutParams gfl = new LinearLayout.LayoutParams(Math.round(36 * d), Math.round(24 * d));
            gfl.setMarginStart(Math.round(8 * d));
            pill.addView(gifIc, gfl);
            cbar.addView(pill, new LinearLayout.LayoutParams(-1, -1));
            box.addView(cbar, new FrameLayout.LayoutParams(-1, Math.round(COMMENT_DP * d), Gravity.BOTTOM));

            // কালো স্ট্রিপ: বামে আপলোডের সময় / চলতি সময়; ডানে A.I / View insights (ছোট) + ⋯
            LinearLayout strip = new LinearLayout(c);
            strip.setOrientation(LinearLayout.HORIZONTAL);
            strip.setGravity(Gravity.CENTER_VERTICAL);
            strip.setPadding(Math.round(14 * d), 0, Math.round(8 * d), 0);
            stripLeft = new LinearLayout(c);
            stripLeft.setOrientation(LinearLayout.HORIZONTAL);
            stripLeft.setGravity(Gravity.CENTER_VERTICAL);
            stripLeft.setVisibility(View.GONE);
            stripTime = new TextView(c);
            stripTime.setTextColor(Color.WHITE);
            stripTime.setTextSize(13);
            stripTime.setSingleLine(true);
            stripTime.setOnClickListener(v -> { if (ctrlOn) { player.togglePlay(); restartHide(); } });
            stripLeft.addView(stripTime, new LinearLayout.LayoutParams(-2, -2));
            spk = new ImageView(c);
            spk.setColorFilter(Color.WHITE);
            spk.setPadding(Math.round(5 * d), Math.round(5 * d), Math.round(5 * d), Math.round(5 * d));
            spk.setVisibility(View.GONE);
            spk.setOnClickListener(v -> { onSoundButton(this); restartHide(); });
            LinearLayout.LayoutParams spl = new LinearLayout.LayoutParams(Math.round(28 * d), Math.round(28 * d));
            spl.setMarginStart(Math.round(10 * d));
            stripLeft.addView(spk, spl);
            strip.addView(stripLeft, new LinearLayout.LayoutParams(0, -2, 1f));

            aiTag = new TextView(c);
            aiTag.setText("A.I");
            aiTag.setTextColor(Color.WHITE);
            aiTag.setTextSize(11);
            aiTag.setTypeface(null, Typeface.BOLD);
            aiTag.setPadding(Math.round(6 * d), Math.round(1 * d), Math.round(6 * d), Math.round(1 * d));
            aiTag.setBackgroundResource(R.drawable.bg_cm_gif);
            aiTag.setVisibility(View.GONE);
            LinearLayout.LayoutParams ail = new LinearLayout.LayoutParams(-2, -2);
            ail.setMarginEnd(Math.round(10 * d));
            strip.addView(aiTag, ail);

            LinearLayout ib = new LinearLayout(c);
            ib.setOrientation(LinearLayout.HORIZONTAL);
            ib.setGravity(Gravity.CENTER_VERTICAL);
            ImageView ii = new ImageView(c);
            ii.setImageResource(R.drawable.ic_short_insights);
            ii.setColorFilter(Color.WHITE);
            ib.addView(ii, new LinearLayout.LayoutParams(Math.round(13 * d), Math.round(13 * d)));
            insights = new TextView(c);
            insights.setText("View insights");
            insights.setTextColor(Color.WHITE);
            insights.setTextSize(11);
            LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(-2, -2);
            il.setMarginStart(Math.round(4 * d));
            ib.addView(insights, il);
            ib.setVisibility(View.GONE);
            ib.setPadding(Math.round(4 * d), Math.round(4 * d), Math.round(6 * d), Math.round(4 * d));
            strip.addView(ib, new LinearLayout.LayoutParams(-2, -2));
            insightsBox = ib;
            ib.setOnClickListener(v -> {
                Post p = info == null ? null : info.post;
                if (p == null) { toast("Loading…"); return; }
                startActivity(new Intent(ShortViewerActivity.this, PostDashboardActivity.class)
                        .putExtra("postId", p.id)
                        .putExtra("views", p.views)
                        .putExtra("likes", p.likes)
                        .putExtra("comments", p.comments)
                        .putExtra("shares", p.shares));
            });

            more = new ImageView(c);
            more.setImageResource(R.drawable.ic_more);
            more.setColorFilter(Color.WHITE);
            more.setPadding(Math.round(5 * d), Math.round(5 * d), Math.round(5 * d), Math.round(5 * d));
            strip.addView(more, new LinearLayout.LayoutParams(Math.round(30 * d), Math.round(30 * d)));
            FrameLayout.LayoutParams stp = new FrameLayout.LayoutParams(-1, Math.round(STRIP_DP * d), Gravity.BOTTOM);
            stp.bottomMargin = lowBar;
            box.addView(strip, stp);
            more.setOnClickListener(v -> {
                if (info != null && info.post != null) ShortMenuSheet.show(ShortViewerActivity.this, myUid, info.post);
                else toast("Menu not available");
            });

            // নিচে-বামের লেখার ব্লক: প্রোফাইল + নাম + প্রাইভেসি + Follow / গান / টাইটেল
            topBox = new LinearLayout(c);
            topBox.setOrientation(LinearLayout.VERTICAL);
            FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
            tp.setMargins(Math.round(12 * d), 0, Math.round(68 * d), lowBar + Math.round(2 * d));
            box.addView(topBox, tp);

            LinearLayout header = new LinearLayout(c);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            topBox.addView(header, new LinearLayout.LayoutParams(-1, -2));

            avatar = new ImageView(c);
            avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
            avatar.setClipToOutline(true);
            avatar.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View v, Outline o) { o.setOval(0, 0, v.getWidth(), v.getHeight()); }
            });
            header.addView(avatar, new LinearLayout.LayoutParams(Math.round(42 * d), Math.round(42 * d)));

            LinearLayout txt = new LinearLayout(c);
            txt.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams txl = new LinearLayout.LayoutParams(-2, -2);
            txl.setMarginStart(Math.round(10 * d));
            header.addView(txt, txl);

            LinearLayout nameRow = new LinearLayout(c);
            nameRow.setOrientation(LinearLayout.HORIZONTAL);
            nameRow.setGravity(Gravity.CENTER_VERTICAL);
            txt.addView(nameRow, new LinearLayout.LayoutParams(-2, -2));

            name = new TextView(c);
            name.setTextColor(Color.WHITE);
            name.setTextSize(16);
            name.setTypeface(null, Typeface.BOLD);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setMaxWidth(Math.round(c.getResources().getDisplayMetrics().widthPixels * 0.45f));
            name.setShadowLayer(4f, 0f, 1f, 0xAA000000);
            nameRow.addView(name);

            globe = new ImageView(c);
            globe.setImageResource(R.drawable.ic_globe);
            globe.setColorFilter(0xFFDDDDDD);
            LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(Math.round(15 * d), Math.round(15 * d));
            gl.setMarginStart(Math.round(7 * d));
            nameRow.addView(globe, gl);

            withLine = new TextView(c);
            withLine.setTextSize(13);
            withLine.setSingleLine(true);
            withLine.setEllipsize(TextUtils.TruncateAt.END);
            withLine.setShadowLayer(4f, 0f, 1f, 0xAA000000);
            withLine.setVisibility(View.GONE);
            txt.addView(withLine, new LinearLayout.LayoutParams(-2, -2));

            // নামের নিচে: স্থির মিউজিক আইকন + খুব ছোট চলমান (marquee) গানের বিবরণ
            LinearLayout sr = new LinearLayout(c);
            sr.setOrientation(LinearLayout.HORIZONTAL);
            sr.setGravity(Gravity.CENTER_VERTICAL);
            ImageView mi = new ImageView(c);
            mi.setImageResource(R.drawable.ic_cm_music);
            mi.setColorFilter(Color.WHITE);
            sr.addView(mi, new LinearLayout.LayoutParams(Math.round(11 * d), Math.round(11 * d)));
            soundName = new TextView(c);
            soundName.setTextColor(0xFFEDEDED);
            soundName.setTextSize(11);
            soundName.setSingleLine(true);
            soundName.setEllipsize(TextUtils.TruncateAt.MARQUEE);
            soundName.setMarqueeRepeatLimit(-1);
            soundName.setHorizontallyScrolling(true);
            soundName.setFocusable(true);
            soundName.setFocusableInTouchMode(true);
            soundName.setShadowLayer(4f, 0f, 1f, 0xAA000000);
            LinearLayout.LayoutParams snl = new LinearLayout.LayoutParams(Math.round(150 * d), -2);
            snl.setMarginStart(Math.round(5 * d));
            sr.addView(soundName, snl);
            sr.setOnClickListener(v -> onSoundLabel(this));
            soundRow = sr;
            LinearLayout.LayoutParams srl = new LinearLayout.LayoutParams(-2, -2);
            srl.topMargin = Math.round(2 * d);
            txt.addView(sr, srl);

            follow = new TextView(c);
            follow.setText("Follow");
            follow.setTextColor(Color.WHITE);
            follow.setTextSize(14);
            follow.setTypeface(null, Typeface.BOLD);
            follow.setGravity(Gravity.CENTER);
            follow.setBackgroundResource(R.drawable.bg_cm_follow);
            follow.setPadding(Math.round(14 * d), Math.round(6 * d), Math.round(14 * d), Math.round(6 * d));
            follow.setVisibility(View.GONE);
            LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(-2, -2);
            fl.setMarginStart(Math.round(14 * d));
            header.addView(follow, fl);
            follow.setOnClickListener(v -> doFollow(this));

            caption = new TextView(c);
            caption.setTextColor(Color.WHITE);
            caption.setTextSize(15);
            caption.setShadowLayer(4f, 0f, 1f, 0xAA000000);
            caption.setVisibility(View.GONE);
            LinearLayout.LayoutParams cap = new LinearLayout.LayoutParams(-1, -2);
            cap.topMargin = Math.round(8 * d);
            topBox.addView(caption, cap);
            caption.setOnClickListener(v -> { expanded = !expanded; setCaption(); updateStrip(); });

            tags = new TextView(c);
            tags.setTextColor(0xFF0A84FF);
            tags.setTextSize(15);
            tags.setTypeface(null, Typeface.BOLD);
            tags.setVisibility(View.GONE);
            LinearLayout.LayoutParams tgl = new LinearLayout.LayoutParams(-1, -2);
            tgl.topMargin = Math.round(3 * d);
            topBox.addView(tags, tgl);

            View.OnClickListener openProfile = v -> {
                if (info != null) ProfileActivity.open(ShortViewerActivity.this, info.uid, name.getText().toString());
            };
            avatar.setOnClickListener(openProfile);
            name.setOnClickListener(openProfile);

            // ডানের কলাম: Like / Comment / Share / Save (ভিডিওর উপরে, কালো বারের ঠিক উপরে)
            col = new LinearLayout(c);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
            cl.setMargins(0, 0, Math.round(6 * d), barTotal + Math.round(6 * d));
            box.addView(col, cl);
            LinearLayout likeCell = newCell(c);
            like = action(c, likeCell, R.drawable.ic_like);
            likes = count(c, likeCell);
            LinearLayout commentCell = newCell(c);
            comment = action(c, commentCell, R.drawable.ic_comment);
            comments = count(c, commentCell);
            LinearLayout shareCell = newCell(c);
            share = action(c, shareCell, R.drawable.ic_short_share);
            LinearLayout saveCell = newCell(c);
            save = action(c, saveCell, R.drawable.ic_short_bookmark);
            like.setOnClickListener(v -> toggleLike(this));
            comment.setOnClickListener(v -> openComments(this, false, false));
            share.setOnClickListener(v -> {
                if (info != null && info.post != null) PostOps.share(ShortViewerActivity.this, info.post);
            });
            save.setOnClickListener(v -> { });             // ডেমো: কিছু করে না, টোস্টও নেই

            // উপরের ওভারলে: (শুধু ভিউয়ারে) back · ডানে search + ⋯ (ডেমো)
            FrameLayout topBar = new FrameLayout(c);
            backBtn = new ImageView(c);
            backBtn.setImageResource(R.drawable.ic_back_left);
            backBtn.setColorFilter(Color.WHITE);
            backBtn.setPadding(Math.round(14 * d), Math.round(14 * d), Math.round(14 * d), Math.round(14 * d));
            backBtn.setOnClickListener(v -> finish());
            backBtn.setVisibility(feed ? View.GONE : View.VISIBLE);
            topBar.addView(backBtn, new FrameLayout.LayoutParams(Math.round(52 * d), Math.round(52 * d), Gravity.START | Gravity.CENTER_VERTICAL));
            dotsBtn = new ImageView(c);
            dotsBtn.setImageResource(R.drawable.ic_fb_cover_more);
            dotsBtn.setPadding(Math.round(9 * d), Math.round(9 * d), Math.round(9 * d), Math.round(9 * d));
            dotsBtn.setOnClickListener(v -> { });          // ডেমো
            topBar.addView(dotsBtn, new FrameLayout.LayoutParams(Math.round(44 * d), Math.round(44 * d), Gravity.END | Gravity.CENTER_VERTICAL));
            searchBtn = new ImageView(c);
            searchBtn.setImageResource(R.drawable.ic_fb_cover_search);
            searchBtn.setPadding(Math.round(9 * d), Math.round(9 * d), Math.round(9 * d), Math.round(9 * d));
            searchBtn.setOnClickListener(v -> { });        // ডেমো
            FrameLayout.LayoutParams sbp = new FrameLayout.LayoutParams(Math.round(44 * d), Math.round(44 * d), Gravity.END | Gravity.CENTER_VERTICAL);
            sbp.setMarginEnd(Math.round(48 * d));
            topBar.addView(searchBtn, sbp);
            FrameLayout.LayoutParams tbp = new FrameLayout.LayoutParams(-1, Math.round(52 * d), Gravity.TOP);
            tbp.setMargins(0, Math.round(2 * d), Math.round(6 * d), 0);
            box.addView(topBar, tbp);

            chrome.add(scrimTop); chrome.add(scrimBottom); chrome.add(barBg); chrome.add(seek);
            chrome.add(cbar); chrome.add(strip); chrome.add(topBox); chrome.add(col); chrome.add(topBar);

            box.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                if (b - t != ob - ot || r - l != or - ol) relayout();
            });
            relayout();
        }

        private LinearLayout newCell(Context c) {
            LinearLayout cell = new LinearLayout(c);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            col.addView(cell, new LinearLayout.LayoutParams(-2, -2));
            return cell;
        }

        /** জায়গা ঠিক: ভিডিও কালো বারের উপরের পুরো জায়গা জুড়ে; লেখার ব্লক বারের ঠিক উপরে */
        void relayout() {
            if (sheet) { layoutSheet(); return; }
            final float d = box.getResources().getDisplayMetrics().density;
            FrameLayout.LayoutParams pl = (FrameLayout.LayoutParams) player.getLayoutParams();
            int barTotal = Math.round((STRIP_DP + SEEK_DP + COMMENT_DP) * d);
            if (pl.width != -1 || pl.height != -1 || pl.topMargin != 0 || pl.bottomMargin != barTotal) {
                pl.width = -1; pl.height = -1; pl.gravity = Gravity.NO_GRAVITY;
                pl.topMargin = 0; pl.bottomMargin = barTotal;
                player.setLayoutParams(pl);
            }
            applyInfoMargin();
            updateNameWidth();
        }

        /** কালো স্ট্রিপে কিছু দেখা গেলে (সময় / A.I / insights) সব লেখা ভিডিওর উপরে উঠে যায় */
        void applyInfoMargin() {
            final float d = box.getResources().getDisplayMetrics().density;
            boolean busy = ctrlOn || expanded || insightsBox.getVisibility() == View.VISIBLE
                    || aiTag.getVisibility() == View.VISIBLE;
            int want = Math.round((SEEK_DP + COMMENT_DP + (busy ? STRIP_DP : 0) + 2) * d);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) topBox.getLayoutParams();
            if (lp.bottomMargin != want) {
                lp.bottomMargin = want;
                topBox.setLayoutParams(lp);
            }
        }

        /** কমেন্ট শিট খুললে: বাকি সব লুকিয়ে ভিডিও উপরে গোল কোণে ছোট হয় */
        void enterSheet() {
            sheet = true;
            for (View v : chrome) v.setVisibility(View.GONE);
            hideControls();
            relayout();
        }

        void exitSheet() {
            sheet = false;
            for (View v : chrome) v.setVisibility(View.VISIBLE);
            player.setClipToOutline(false);
            relayout();
        }

        private void layoutSheet() {
            final float d = box.getResources().getDisplayMetrics().density;
            int sw = box.getWidth() > 0 ? box.getWidth() : box.getResources().getDisplayMetrics().widthPixels;
            int sh = box.getHeight() > 0 ? box.getHeight() : box.getResources().getDisplayMetrics().heightPixels;
            float r = (info != null && info.width > 0 && info.height > 0) ? (float) info.width / info.height : 9f / 16f;
            int top = Math.round(12 * d);
            int vh = Math.round(sh * 0.36f) - top - Math.round(4 * d);
            int vw = Math.round(vh * r);
            if (vw > sw) { vw = sw; vh = Math.round(sw / r); }
            FrameLayout.LayoutParams pl = (FrameLayout.LayoutParams) player.getLayoutParams();
            pl.width = vw; pl.height = vh; pl.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            pl.topMargin = top; pl.bottomMargin = 0;
            player.setLayoutParams(pl);
            final float rad = 16 * d;
            player.setClipToOutline(true);
            player.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), rad); }
            });
        }

        // ------------------------------------------------------------ সিক-বার / সময় / স্পিকার

        /** সিক-বারে চাপলে: চলতি সময় (10/5:00) + স্পিকার দেখায়; ৫-৬ সেকেন্ড পর আবার লুকায় */
        void showControls() {
            if (info == null || sheet) return;
            ctrlOn = true;
            updateMuteIcon();
            updateProgress();
            updateStrip();
            restartHide();
        }

        void hideControls() {
            seek.removeCallbacks(autoHide);
            ctrlOn = false;
            dragging = false;
            thumb.setColor(0x00FFFFFF);
            updateStrip();
        }

        void restartHide() {
            seek.removeCallbacks(autoHide);
            seek.postDelayed(autoHide, 5500);
        }

        void updateProgress() {
            if (dragging) return;
            int dur = player.duration();
            int pos = Math.min(player.position(), dur);
            seek.setMax(Math.max(dur, 1));
            seek.setProgress(pos);
        }

        /** স্ট্রিপের বাম অংশ: সময় দেখানো হলে "10/5:00", নইলে (More খুললে) আপলোডের সময় */
        void updateStrip() {
            boolean showAgo = expanded && !ctrlOn && info != null && info.post != null;
            if (ctrlOn) {
                int dur = player.duration();
                int pos = Math.min(player.position(), dur);
                if (!dragging) stripTime.setText(fmtCur(pos) + "/" + fmtTotal(dur));
                spk.setVisibility(View.VISIBLE);
                stripLeft.setVisibility(View.VISIBLE);
            } else if (showAgo) {
                stripTime.setText(agoShort(info.post.timestamp));
                spk.setVisibility(View.GONE);
                stripLeft.setVisibility(View.VISIBLE);
            } else {
                spk.setVisibility(View.GONE);
                stripLeft.setVisibility(View.GONE);
            }
            applyInfoMargin();
        }

        /** নাম পুরোটা দেখায়; জায়গা না থাকলেই শুধু শেষে "…" (Follow বাটন থাকলে তার জায়গা বাদ) */
        void updateNameWidth() {
            float d = box.getResources().getDisplayMetrics().density;
            int sw = box.getWidth() > 0 ? box.getWidth() : box.getResources().getDisplayMetrics().widthPixels;
            int used = Math.round((12 + 68 + 42 + 10 + 22) * d);
            if (follow.getVisibility() == View.VISIBLE) used += Math.round(84 * d);
            name.setMaxWidth(Math.max(Math.round(80 * d), sw - used));
        }

        /** স্পিকার আইকন (মিউট/আনমিউট) ও নামের নিচের গানের বিবরণ */
        void updateMuteIcon() {
            spk.setImageResource(player.isMuted() ? R.drawable.audmix_volume_off : R.drawable.audmix_volume_on);
            PostMusic.Info m = musicOf(info);
            boolean hasMusic = m != null && m.url != null && !m.url.isEmpty();
            String t;
            if (hasMusic) {
                t = m.title == null || m.title.isEmpty() ? "Music" : m.title;
                if (m.artist != null && !m.artist.isEmpty()) t += " · " + m.artist;
            } else {
                String who = name.getText().toString();
                t = (who.isEmpty() ? "" : who + " · ") + "Original audio";
            }
            if (!t.contentEquals(soundName.getText())) soundName.setText(t);
            soundName.setSelected(true);
        }

        private ImageView action(Context c, LinearLayout cell, int res) {
            float d = c.getResources().getDisplayMetrics().density;
            ImageView iv = new ImageView(c);
            iv.setImageResource(res);
            iv.setColorFilter(Color.WHITE);
            int p = Math.round(ACTION_PAD_DP * d);
            iv.setPadding(p, p, p, p);
            iv.setAlpha(0.95f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Math.round(ACTION_DP * d), Math.round(ACTION_DP * d));
            lp.topMargin = Math.round(6 * d);
            cell.addView(iv, lp);
            return iv;
        }

        private TextView count(Context c, LinearLayout cell) {
            TextView t = new TextView(c);
            t.setTextColor(Color.WHITE);
            t.setTextSize(13);
            t.setGravity(Gravity.CENTER);
            t.setShadowLayer(4f, 0f, 1f, 0xAA000000);
            cell.addView(t, new LinearLayout.LayoutParams(-2, -2));
            return t;
        }

        void bind(final ShortStore.Info i) {
            info = i;
            player.bind(i);
            player.setControlsVisible(false);
            expanded = false;
            hideControls();
            updateMuteIcon();
            relayout();
            AvatarLoader.load(avatar, i.uid);
            fullCaption = i.title == null ? "" : i.title;
            name.setText("");
            withLine.setVisibility(View.GONE);
            globe.setVisibility(View.VISIBLE);
            insightsBox.setVisibility(myUid != null && myUid.equals(i.uid) ? View.VISIBLE : View.GONE);
            aiTag.setVisibility(i.aiLabel ? View.VISIBLE : View.GONE);
            updateFollow();
            setCaption();
            stats();
            applyInfoMargin();
            seek.removeCallbacks(tick);
            seek.post(tick);
            ShortStore.loadPost(i, p -> {
                if (info != i) return;
                stats();
                if (i.post != null)
                    PostMusic.prefetch(Collections.singletonList(i.post), () -> { if (info == i) stats(); });
            });
        }

        void stopTick() {
            seek.removeCallbacks(tick);
            seek.removeCallbacks(autoHide);
        }

        void updateFollow() {
            boolean show = followLoaded && info != null && info.uid != null && myUid != null
                    && !myUid.equals(info.uid) && !following.contains(info.uid);
            follow.setVisibility(show ? View.VISIBLE : View.GONE);
            updateNameWidth();
        }

        /** টাইটেল: ২ লাইন + "… more"; ট্যাপ করলে পুরোটা উপরে খোলে, কালো অংশে আপলোডের সময় আসে */
        void setCaption() {
            final String t = fullCaption == null ? "" : fullCaption;
            if (t.isEmpty()) { caption.setVisibility(View.GONE); return; }
            caption.setVisibility(View.VISIBLE);
            caption.setText(t);
            if (expanded) {
                caption.setEllipsize(null);
                caption.setMaxLines(8);
                return;
            }
            caption.setMaxLines(2);
            caption.setEllipsize(TextUtils.TruncateAt.END);
            caption.post(() -> {
                Layout l = caption.getLayout();
                if (l == null || expanded || l.getLineCount() < 2) return;
                int ell = l.getEllipsisCount(1);
                if (ell > 0) {
                    int end = l.getLineEnd(1) - ell - 7;
                    if (end > 0 && end <= t.length()) caption.setText(t.substring(0, end).trim() + "… more");
                }
            });
        }

        void stats() {
            Post p = info == null ? null : info.post;
            if (p == null) {
                likes.setText("0");
                comments.setText("0");
                return;
            }
            if (p.name != null) name.setText(p.name);
            globe.setVisibility(p.privacy == null || "public".equals(p.privacy) ? View.VISIBLE : View.GONE);
            String w = PostExtras.collab(p.id);
            if (w != null && !w.isEmpty()) {
                SpannableStringBuilder sb = new SpannableStringBuilder("with ");
                sb.setSpan(new ForegroundColorSpan(0xFFBBBBBB), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                int s = sb.length();
                sb.append(w);
                sb.setSpan(new ForegroundColorSpan(Color.WHITE), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new StyleSpan(Typeface.BOLD), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                withLine.setText(sb);
                withLine.setVisibility(View.VISIBLE);
            } else {
                withLine.setVisibility(View.GONE);
            }
            updateMuteIcon();
            if (p.hashtags != null && !p.hashtags.trim().isEmpty()) {
                tags.setText(p.hashtags.trim());
                HashtagLinker.apply(tags, 0);
                tags.setVisibility(View.VISIBLE);
            } else tags.setVisibility(View.GONE);
            if ((fullCaption == null || fullCaption.isEmpty()) && p.text != null && !p.text.isEmpty()) {
                fullCaption = p.text;
                setCaption();
            }
            likes.setText(PostAdapter.compact(p.likes));
            comments.setText(PostAdapter.compact(p.comments));
            float d = like.getResources().getDisplayMetrics().density;
            int basePad = Math.round(ACTION_PAD_DP * d);
            String react = p.likedByMe ? p.myReaction : null;
            if (p.likedByMe && react != null && !"like".equals(react)) {
                like.setImageResource(ReactionKit.icon(like.getContext(), react));
                like.clearColorFilter();
                like.setPadding(basePad - Math.round(3 * d), basePad - Math.round(3 * d), basePad - Math.round(3 * d), basePad - Math.round(3 * d));
            } else if (p.likedByMe) {
                like.setImageResource(R.drawable.ic_like_circle);
                like.clearColorFilter();
                like.setPadding(Math.round(2 * d), Math.round(2 * d), Math.round(2 * d), Math.round(2 * d));
            } else {
                like.setImageResource(R.drawable.ic_like);
                like.setColorFilter(Color.WHITE);
                like.setPadding(basePad, basePad, basePad, basePad);
            }
            if (myUid != null && p.id != null) {
                ReactionKit.attach(ShortViewerActivity.this, like, p.id, p.uid, myUid, p.likedByMe, key -> {
                    boolean was = p.likedByMe;
                    p.likedByMe = true;
                    p.myReaction = key;
                    if (!was) p.likes = p.likes + 1;
                    stats();
                });
            }
            updateStrip();
        }
    }

    private void toggleLike(Page h) {
        Post p = h.info == null ? null : h.info.post;
        if (p == null || myUid == null) { toast("Loading…"); return; }
        if (!Net.check(this)) return;
        if (p.likedByMe) {
            ReactionKit.removeReaction(this, p.id, myUid);
            p.likedByMe = false;
            p.myReaction = null;
            p.likes = Math.max(0, p.likes - 1);
        } else {
            ReactionKit.setReaction(this, p.id, myUid, "like");
            Sfx.like();
            NotificationHelper.send(p.uid, "like", p.id);
            p.likedByMe = true;
            p.myReaction = "like";
            p.likes = p.likes + 1;
        }
        h.stats();
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }
}
