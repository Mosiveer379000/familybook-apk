package com.familybook.app;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class HomeActivity extends BaseActivity implements PostOps.Refreshable {

    private String myUid;
    private PostAdapter adapter;
    private PullRefreshLayout swipe;
    private String myName = "Family Member";

    // ইন্টারনেট ব্যানার
    private TextView banner;
    private boolean offlineShown = false;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable showOffline = () -> {
        offlineShown = true;
        banner.setBackgroundColor(0xFF424242);
        banner.setText("No internet connection");
        banner.setVisibility(View.VISIBLE);
    };
    private final Runnable hideBanner = () -> banner.setVisibility(View.GONE);

    // নিউজ ফিডের উপাদান
    private List<Post> loaded = null;               // সার্ভার থেকে আনা সব active পোস্ট
    private final Set<String> hiddenIds = new HashSet<>();
    private final Set<String> friendIds = new HashSet<>();
    private final java.util.Map<String, String> groupOwner = new java.util.HashMap<>();   // group id -> owner uid
    private final Set<String> followingIds = new HashSet<>();
    private boolean firstResume = true;

    // ফিড লোড না হলে দেখানো অবস্থা: ১ = Something went wrong (Try again), ২ = No internet (Refresh)
    private LinearLayout feedState, feedErrGroup, feedOffGroup;
    private boolean needsRefresh = false;           // লোড ব্যর্থ/অফলাইন ছিল — নেট এলে নিজে রিফ্রেশ

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Supabase-এ email confirm না হলে session-ই তৈরি হয় না, তাই session থাকা মানেই verified
        if (!SupabaseRest.isLoggedIn() || SupabaseRest.uid() == null) {
            SupabaseRest.clearSession();
            goLogin();
            return;
        }
        myUid = SupabaseRest.uid();

        setContentView(R.layout.activity_home);
        AccountPhoto.refresh(this);      // লগইন পেজের "সেভ করা অ্যাকাউন্ট" কার্ডের জন্য নাম ও ছবি ফোনে রাখে
        applyHomeTheme();

        // নোটিফিকেশন বেল (হেডারের ডানে)
        bindBell(findViewById(R.id.bellContainer), findViewById(R.id.tvBadge));

        Sfx.init(this);

        // নিজের প্রোফাইল ছবি ("What's on your mind?" এর পাশে)
        ImageView myAvatar = findViewById(R.id.imgMyAvatar);
        AvatarLoader.load(myAvatar, myUid);
        myAvatar.setOnClickListener(v -> ProfileActivity.open(this, myUid, myName));
        SearchActivity.syncEmailIndex(); // Email দিয়ে খোঁজার ইনডেক্স ঠিক রাখে

        // নিজের নাম লোড
        PostOps.loadMyName(myUid, n -> myName = n);

        RecyclerView rv = findViewById(R.id.rvPosts);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new PostAdapter(new PostAdapter.Actions() {
            @Override public void onLike(Post p) { toggleLike(p); }
            @Override public void onHide(Post p) {
                if (Net.check(HomeActivity.this)) PostOps.hide(HomeActivity.this, myUid, p.id);
            }
            @Override public void onMenu(Post p, View anchor) {
                PostOps.showMenu(HomeActivity.this, myUid, p, anchor, true);
            }
            @Override public void onComments(Post p) {
                startActivity(new Intent(HomeActivity.this, CommentsActivity.class)
                        .putExtra("postId", p.id));
            }
        }, true, true); // (Hide বাটন দেখাবে, ফেসবুক-স্টাইল কার্ড)
        rv.setAdapter(adapter);

        PostOps.cleanup(myUid);

        // উপর থেকে টেনে নিচে নামালে রিফ্রেশ
        swipe = findViewById(R.id.swipeRefresh);
        swipe.setColorSchemeColors(0xFF00FFC2, 0xFF476A91);
        swipe.setOnRefreshListener(this::pullRefresh);

        // ইন্টারনেট নেই/এলো — উপরে ছোট হিন্ট
        setupBanner();
        setupPostBanner();
        setupShortBanner();
        setupFeedState();
        Net.setCallback(this::onNet);
        onNet(Net.isOnline());

        // নেভিগেশন (নিচের বার): Home · Gallery · Shop · Dashboard · Profile
        findViewById(R.id.navHome).setOnClickListener(v -> rv.smoothScrollToPosition(0));
        findViewById(R.id.navGallery).setOnClickListener(v ->
                ShortViewerActivity.openFeed(this));   // Shorts আইকন → Shorts ভিডিও ফিড
        findViewById(R.id.navShop).setOnClickListener(v -> soon("Shop"));
        findViewById(R.id.navDash).setOnClickListener(v -> soon("Dashboard"));
        // Profile আইকনে চাপলে সরাসরি প্রোফাইল নয়, আগে Menu (TreedotActivity) খুলবে
        findViewById(R.id.navProfile).setOnClickListener(v ->
                startActivity(new Intent(this, TreedotActivity.class)));
        ImageView navAvatar = findViewById(R.id.imgNavAvatar);
        AvatarLoader.load(navAvatar, myUid);

        // হেডার: + / সার্চ / মেসেঞ্জার  (LogOut এখন Profile আইকন > Menu > LogOut)
        findViewById(R.id.btnAddPost).setOnClickListener(v -> showPlusMenu(v));
        findViewById(R.id.btnSearch).setOnClickListener(v ->
                startActivity(new Intent(this, SearchActivity.class)));
        findViewById(R.id.btnMessenger).setOnClickListener(v -> openFamilyChat());
        // ছবির বাটন: সরাসরি গ্যালারি খুলে ছবি-সহ পোস্ট
        findViewById(R.id.btnPostImage).setOnClickListener(v -> showPostDialog(true));
        findViewById(R.id.tvWhatsOnMind).setOnClickListener(v -> showPostDialog(false));
        findViewById(R.id.cardCreateMemory).setOnClickListener(v -> soon("Create memory"));

        loadCache();   // আগের ফিড সাথে সাথে (নেট না থাকলেও) দেখাই
        loadSets();
        loadPosts();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (myUid == null) return;
        PostQueue.setListener(this::syncPostBanner);
        syncPostBanner();
        ShortUploader.addListener(shortListener);
        syncShortBanner();
        if (adapter != null) findViewById(R.id.rvPosts).postDelayed(adapter::autoplayNow, 400);   // ভিউয়ার থেকে ফিরে অটো-প্লে
        // Firebase-এর মতো লাইভ আপডেট নেই, তাই অন্য পেজ থেকে ফিরলে নতুন করে আনি
        if (firstResume) {
            firstResume = false;
            return;
        }
        refreshPosts();
    }

    /** PostOps.Refreshable — পোস্ট বদলালে (hide/delete/archive...) নিজে থেকে ডাকা হয় */
    @Override
    public void refreshPosts() {
        if (myUid == null) return;
        loadSets();
        loadPosts();
    }

    // ---------- সার্ভার থেকে ডাটা আনা ----------

    /** আমার hidden পোস্ট, বন্ধু ও যাদের ফলো করি তাদের তালিকা */
    private void loadSets() {
        if (!Net.isOnline()) return;   // অফলাইনে ক্যাশের তালিকাই চলবে
        SupabaseRest.call("GET",
                "/rest/v1/hidden_posts?user_id=eq." + myUid + "&select=post_id",
                null, null, true, r -> {
            if (!r.ok()) return;
            FeedCache.put(getApplicationContext(), myUid, "hidden", r.body);
            fillSet(r.body, "post_id", hiddenIds);
            render();
        });
        SupabaseRest.call("GET",
                "/rest/v1/friendships?user_id=eq." + myUid + "&select=friend_id",
                null, null, true, r -> {
            if (!r.ok()) return;
            FeedCache.put(getApplicationContext(), myUid, "friends", r.body);
            fillSet(r.body, "friend_id", friendIds);
            render();
        });
        SupabaseRest.call("GET",
                "/rest/v1/follows?follower_id=eq." + myUid + "&select=followee_id",
                null, null, true, r -> {
            if (!r.ok()) return;
            FeedCache.put(getApplicationContext(), myUid, "follows", r.body);
            fillSet(r.body, "followee_id", followingIds);
            render();
            rebuildMemories();
        });
    }

    private void loadPosts() {
        if (!Net.isOnline()) {            // নেট নেই: ক্যাশের পোস্টই থাকবে; কিছুই না থাকলে "No internet"
            swipe.setRefreshing(false);
            needsRefresh = true;
            if (adapter.getItemCount() == 0) showFeedState(2);
            return;
        }
        SupabaseRest.call("GET",
                "/rest/v1/post_cards_g?status=eq.active&order=created_at.desc&limit=200",
                null, null, true, r -> {
            if (isFinishing() || isDestroyed()) return;
            swipe.setRefreshing(false);
            if (r.code == 401) {
                // refresh token সত্যিই অচল হলে SupabaseRest নিজেই সেশন মুছে ফেলে।
                // নেটওয়ার্ক/সাময়িক সমস্যায় লগআউট করা যাবে না।
                if (!SupabaseRest.isLoggedIn()) { goLogin(); return; }
                needsRefresh = true;
                feedLoadFailed(r.code);
                return;
            }
            if (!r.ok()) {
                needsRefresh = true;
                feedLoadFailed(r.code);
                return;
            }
            try {
                JSONArray a = new JSONArray(r.body);
                List<Post> out = new ArrayList<>();
                for (int i = 0; i < a.length(); i++) {
                    out.add(Post.from(a.getJSONObject(i), myUid));
                }
                loaded = out;
                needsRefresh = false;
                hideFeedState();
                FeedCache.put(getApplicationContext(), myUid, "posts", r.body);
                render();
                loadFriendsGroupOwners(out);
                GroupJoin.load(this::render);
                // Feeling / Activity আলাদা করে এনে কার্ডে দেখানো
                PostExtras.load(() -> {
                    if (!isFinishing() && !isDestroyed()) adapter.notifyDataSetChanged();
                });
            } catch (Exception e) {
                Toast.makeText(this, "পোস্ট পড়া যায়নি", Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** Friends-প্রাইভেসির গ্রুপগুলোর নির্মাতা কে — ফিডে দেখানো হবে কি না ঠিক করতে */
    private void loadFriendsGroupOwners(List<Post> posts) {
        Set<String> need = new HashSet<>();
        for (Post p : posts) {
            if (p.isGroupPost() && "friends".equals(p.groupVisibility) && !groupOwner.containsKey(p.groupId)) {
                need.add(p.groupId);
            }
        }
        if (need.isEmpty()) return;
        SupabaseRest.call("GET", "/rest/v1/groups?id=in.(" + android.text.TextUtils.join(",", need)
                + ")&select=id,owner_id&limit=300", null, null, true, r -> {
            if (!r.ok() || isFinishing() || isDestroyed()) return;
            try {
                JSONArray a = new JSONArray(r.body);
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    groupOwner.put(o.optString("id", ""), o.optString("owner_id", ""));
                }
            } catch (Exception ignored) { }
            render();
        });
    }

    private static void fillSet(String body, String field, Set<String> target) {
        target.clear();
        try {
            JSONArray a = new JSONArray(body);
            for (int i = 0; i < a.length(); i++) {
                String v = a.getJSONObject(i).optString(field, "");
                if (!v.isEmpty()) target.add(v);
            }
        } catch (Exception ignored) { }
    }

    /** ফিডে কোন পোস্ট দেখানো হবে তা ঠিক করে: active + ফলো + প্রাইভেসি মিললে */
    private void render() {
        if (loaded == null) return;
        List<Post> list = new ArrayList<>();
        for (Post p : loaded) {
            if (!"active".equals(p.status)) continue; // আর্কাইভ/ট্রাশ ফিডে আসবে না
            if (p.isGroupPost()) {
                // নিউজ ফিডে: Public গ্রুপের পোস্ট সবসময়। Friends গ্রুপ: আমি সদস্য, বা নির্মাতা আমার বন্ধু
                // বা আমি তাকে ফলো করি। Secret (Group members only): শুধু সদস্য হলে।
                boolean member = "active".equals(GroupJoin.status(p.groupId));
                boolean showIt;
                if (p.isMine(myUid) || "public".equals(p.groupVisibility)) {
                    showIt = true;
                } else if ("friends".equals(p.groupVisibility)) {
                    String owner = groupOwner.get(p.groupId);
                    showIt = member || (owner != null
                            && (friendIds.contains(owner) || followingIds.contains(owner)));
                } else {
                    showIt = member;
                }
                if (!showIt) continue;
                p.hiddenByMe = hiddenIds.contains(p.id);
                list.add(p);
                continue;
            }
            if (!p.isMine(myUid)) {
                // শুধু যাকে ফলো করি তার পোস্ট; প্রাইভেসিও মানতে হবে
                if (!followingIds.contains(p.uid)) continue;
                boolean visible = "public".equals(p.privacy)
                        || ("friends".equals(p.privacy) && friendIds.contains(p.uid));
                if (!visible) continue;
            }
            p.hiddenByMe = hiddenIds.contains(p.id);
            list.add(p);
        }
        // নতুন পোস্ট সবার উপরে
        Collections.sort(list, (x, y) -> Long.compare(y.timestamp, x.timestamp));
        adapter.setItems(list);
    }

    /** টেনে রিফ্রেশ: সার্ভার থেকে নতুন করে পোস্ট আনে */
    private void pullRefresh() {
        if (!Net.isOnline()) {
            swipe.setRefreshing(false);
            Toast.makeText(this, "ইন্টারনেট নেই", Toast.LENGTH_SHORT).show();
            return;
        }
        Sfx.refresh();
        refreshPosts();
    }

    // ---------- + মেনু ----------

    /** + বাটনের মেনু: [ছবি] Post  /  [গ্যালারি] Photo  /  [Short আইকন] Short */
    private void showPlusMenu(View anchor) {
        int bgc = androidx.core.content.ContextCompat.getColor(this, R.color.h_bg);
        int txt = androidx.core.content.ContextCompat.getColor(this, R.color.h_text);
        int sub = androidx.core.content.ContextCompat.getColor(this, R.color.h_text_sub);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(bgc);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), sub);

        final android.widget.PopupWindow pw = new android.widget.PopupWindow(col,
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        pw.setBackgroundDrawable(bg);
        pw.setElevation(dp(10));

        col.addView(plusRow(R.drawable.ic_h_postnew, "Post", txt, () -> {
            pw.dismiss();
            showPostDialog(false);
        }));
        col.addView(plusRow(android.R.drawable.ic_menu_gallery, "Photo", txt, () -> {
            pw.dismiss();
            showPostDialog(true);
        }));

        col.addView(plusRow(R.drawable.ic_tm_shorts, "Short", txt, () -> {
            pw.dismiss();
            ShortUploader.openCreate(HomeActivity.this);
        }));

        // + বাটনের নিচে ডানদিকে, পিলের নিচে মাঝামাঝি/বামে খোলে
        int g = anchor.getId() == R.id.btnAddPost ? Gravity.END : Gravity.START;
        pw.showAsDropDown(anchor, g == Gravity.START ? dp(12) : 0, dp(2), g);
    }

    private View plusRow(int iconRes, String label, int txt, final Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(22), dp(12));

        ImageView ic = new ImageView(this);
        ic.setImageResource(iconRes);
        ic.setColorFilter(txt);
        row.addView(ic, new LinearLayout.LayoutParams(dp(30), dp(30)));

        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(txt);
        t.setTextSize(19);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.setMarginStart(dp(12));
        row.addView(t, tp);

        row.setOnClickListener(v -> action.run());
        return row;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private boolean isNight() {
        return (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    /** ডার্ক/লাইট মোডে উইন্ডো ও নেভিগেশন-বারের রং মেলায় (মোড বদলালে অ্যাক্টিভিটি নিজেই নতুন করে সাজে) */
    private void applyHomeTheme() {
        int bg = androidx.core.content.ContextCompat.getColor(this, R.color.h_bg);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(bg));
        getWindow().setNavigationBarColor(bg);
        try {
            androidx.core.view.WindowInsetsControllerCompat c = androidx.core.view.WindowCompat
                    .getInsetsController(getWindow(), getWindow().getDecorView());
            if (c != null) c.setAppearanceLightNavigationBars(!isNight());
        } catch (Exception ignored) { }
    }

    // ---------- Memory সারি ----------

    /** Memory সারি: "Create memory" এর পরে আমি যাদের ফলো করি তাদের কার্ড (সর্বোচ্চ ১৫টি) */
    private void rebuildMemories() {
        final LinearLayout row = findViewById(R.id.memRow);
        if (row == null) return;
        while (row.getChildCount() > 1) row.removeViewAt(1);
        int n = 0;
        for (final String uid : followingIds) {
            if (n++ >= 15) break;
            row.addView(memoryCard(uid));
        }
    }

    private View memoryCard(final String uid) {
        FrameLayout card = new FrameLayout(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(80), dp(130));
        lp.setMarginEnd(dp(6));
        card.setLayoutParams(lp);
        card.setBackgroundResource(R.drawable.bg_h_mem_card);
        card.setClipToOutline(true);
        card.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(12));
            }
        });

        // বড় ছবি: জেন্ডার অনুযায়ী ডিফল্ট (আসল ছবি R2 যুক্ত হলে AvatarLoader-এ আসবে)
        final ImageView photo = new ImageView(this);
        photo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        photo.setTag(uid);
        card.addView(photo, new FrameLayout.LayoutParams(-1, -1));
        AvatarLoader.load(photo, uid, false);

        // ছোট রিং-অ্যাভাটার (ওপরে বামে)
        ImageView small = new ImageView(this);
        small.setBackgroundResource(R.drawable.bg_h_ring);
        small.setPadding(dp(2), dp(2), dp(2), dp(2));
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(dp(32), dp(32));
        sp.setMargins(dp(6), dp(6), 0, 0);
        card.addView(small, sp);
        AvatarLoader.load(small, uid);

        // নাম (নিচে)
        final TextView name = new TextView(this);
        name.setTextColor(Color.WHITE);
        name.setTextSize(12);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        name.setSingleLine(true);
        name.setShadowLayer(4f, 0, 1f, 0xFF000000);
        name.setPadding(dp(6), 0, dp(6), dp(6));
        FrameLayout.LayoutParams np = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        card.addView(name, np);
        PostOps.loadMyName(uid, n -> name.setText(n));   // profiles.name পড়ে

        card.setOnClickListener(v -> ProfileActivity.open(this, uid, null));
        return card;
    }

    // ---------- পোস্ট কাজ ----------

    /** photo = true হলে নতুন-পোস্ট স্ক্রিন খুলেই গ্যালারি খোলে */
    private void showPostDialog(boolean photo) {
        // অফলাইনেও খোলে — Post চাপলে Draft হিসেবে জমা থাকে, নেট এলে নিজে আপলোড হয়
        startActivity(new Intent(this, NewPostActivity.class)
                .putExtra("name", myName)
                .putExtra("pickPhoto", photo));
    }

    private void toggleLike(Post p) {
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
        adapter.notifyDataSetChanged();   // সার্ভারের উত্তরের জন্য না থেকে সাথে সাথে দেখাই
    }

    // ---------- "Posting… Keep FamilyBook open." বার ----------
    private LinearLayout postBanner;
    private ImageView pbThumb;
    private TextView pbTitle, pbSub, pbClose;
    private RingProgressView pbSpin;
    private TextView pbWait;
    private boolean postWasActive = false;

    private void setupPostBanner() {
        float d = getResources().getDisplayMetrics().density;
        postBanner = new LinearLayout(this);
        postBanner.setOrientation(LinearLayout.HORIZONTAL);
        postBanner.setGravity(Gravity.CENTER_VERTICAL);
        postBanner.setBackgroundColor(Color.WHITE);
        int pad = Math.round(14 * d);
        postBanner.setPadding(pad, Math.round(10 * d), pad, Math.round(10 * d));
        postBanner.setVisibility(View.GONE);

        pbThumb = new ImageView(this);
        pbThumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        postBanner.addView(pbThumb, new LinearLayout.LayoutParams(Math.round(52 * d), Math.round(52 * d)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cp.leftMargin = Math.round(14 * d);
        postBanner.addView(col, cp);
        pbTitle = new TextView(this);
        pbTitle.setTextColor(Color.BLACK);
        pbTitle.setTextSize(19);
        pbTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(pbTitle);
        pbWait = new TextView(this);
        pbWait.setTextColor(0xFF8A8D91);
        pbWait.setTextSize(12);
        pbWait.setPadding(dp(8), dp(3), 0, 0);
        pbWait.setVisibility(View.GONE);
        titleRow.addView(pbWait);
        col.addView(titleRow);
        pbSub = new TextView(this);
        pbSub.setTextColor(0xFF65676B);
        pbSub.setTextSize(16);
        col.addView(pbSub);

        pbSpin = new RingProgressView(this);   // গোল, নীল, ছোট — ১–১০০%
        postBanner.addView(pbSpin, new LinearLayout.LayoutParams(Math.round(34 * d), Math.round(34 * d)));

        pbClose = new TextView(this);
        pbClose.setText("\u2715");
        pbClose.setTextSize(22);
        pbClose.setTextColor(0xFF65676B);
        pbClose.setGravity(Gravity.CENTER);
        pbClose.setVisibility(View.GONE);
        pbClose.setOnClickListener(v -> PostQueue.dismiss());
        postBanner.addView(pbClose, new LinearLayout.LayoutParams(Math.round(40 * d), Math.round(40 * d)));

        // ব্যর্থ হলে বারে চাপলে আবার চেষ্টা
        postBanner.setOnClickListener(v -> { if (PostQueue.isFailed()) PostQueue.retry(); });

        ViewGroup parent = (ViewGroup) swipe.getParent();
        if (parent instanceof LinearLayout) {
            parent.addView(postBanner, parent.indexOfChild(swipe), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            addContentView(postBanner, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        }
    }

    private void syncPostBanner() {
        if (postBanner == null) return;
        if (!PostQueue.isActive()) {
            postBanner.setVisibility(View.GONE);
            if (postWasActive) {          // পোস্ট শেষ — ফিড নতুন করে আনি
                postWasActive = false;
                refreshPosts();
            }
            return;
        }
        postWasActive = true;
        postBanner.setVisibility(View.VISIBLE);
        android.graphics.Bitmap t = PostQueue.thumb();
        if (t != null) pbThumb.setImageBitmap(t);
        else pbThumb.setImageDrawable(new android.graphics.drawable.ColorDrawable(0xFFE4E6EB));
        int n = PostQueue.total();
        String title = n > 1 ? "Posting\u2026 (" + PostQueue.done() + "/" + n + ")" : "Posting\u2026";
        if (PostQueue.isFailed()) {
            pbTitle.setText("Post failed");
            pbWait.setVisibility(View.GONE);
            pbSub.setText(PostQueue.error() + " \u2022 Tap to retry");
            pbSpin.setVisibility(View.GONE);
            pbClose.setVisibility(View.VISIBLE);
        } else if (PostQueue.isWaiting()) {
            // নেট নেই: Draft হিসেবে জমা, পাশে ছোট করে "waiting for network…"
            pbTitle.setText(title);
            pbWait.setText("waiting for network\u2026");
            pbWait.setVisibility(View.VISIBLE);
            pbSub.setText("Draft saved \u2022 uploads when you\u2019re online");
            pbSpin.setProgress(0);
            pbSpin.setVisibility(View.VISIBLE);
            pbClose.setVisibility(View.VISIBLE);       // চাইলে Draft বাতিল
        } else {
            pbTitle.setText(title);
            pbWait.setVisibility(View.GONE);
            pbSub.setText("Keep FamilyBook open.");
            pbSpin.setProgress(PostQueue.percent());
            pbSpin.setVisibility(View.VISIBLE);
            pbClose.setVisibility(View.GONE);
        }
    }

    // ---------- "Uploading short… Keep FamilyBook open." বার (পোস্টের বারের মতোই) ----------
    private LinearLayout shortBanner;
    private ImageView sbThumb;
    private TextView sbTitle, sbSub, sbClose;
    private RingProgressView sbRing;
    private boolean shortWasActive = false;
    private final ShortUploader.Listener shortListener = () -> syncShortBanner();

    private void setupShortBanner() {
        float d = getResources().getDisplayMetrics().density;
        shortBanner = new LinearLayout(this);
        shortBanner.setOrientation(LinearLayout.HORIZONTAL);
        shortBanner.setGravity(Gravity.CENTER_VERTICAL);
        shortBanner.setBackgroundColor(Color.WHITE);
        int pad = Math.round(14 * d);
        shortBanner.setPadding(pad, Math.round(10 * d), pad, Math.round(10 * d));
        shortBanner.setVisibility(View.GONE);

        sbThumb = new ImageView(this);
        sbThumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        shortBanner.addView(sbThumb, new LinearLayout.LayoutParams(Math.round(52 * d), Math.round(52 * d)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cp.leftMargin = Math.round(14 * d);
        shortBanner.addView(col, cp);
        sbTitle = new TextView(this);
        sbTitle.setTextColor(Color.BLACK);
        sbTitle.setTextSize(19);
        sbTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        col.addView(sbTitle);
        sbSub = new TextView(this);
        sbSub.setTextColor(0xFF65676B);
        sbSub.setTextSize(16);
        col.addView(sbSub);

        sbRing = new RingProgressView(this);
        shortBanner.addView(sbRing, new LinearLayout.LayoutParams(Math.round(34 * d), Math.round(34 * d)));

        sbClose = new TextView(this);
        sbClose.setText("\u2715");
        sbClose.setTextSize(22);
        sbClose.setTextColor(0xFF65676B);
        sbClose.setGravity(Gravity.CENTER);
        sbClose.setVisibility(View.GONE);
        sbClose.setOnClickListener(v -> ShortUploader.dismiss());
        shortBanner.addView(sbClose, new LinearLayout.LayoutParams(Math.round(40 * d), Math.round(40 * d)));

        shortBanner.setOnClickListener(v -> {
            if (ShortUploader.state() == ShortUploader.State.FAILED) ShortUploader.retry();
        });

        ViewGroup parent = (ViewGroup) swipe.getParent();
        if (parent instanceof LinearLayout) {
            parent.addView(shortBanner, parent.indexOfChild(swipe), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            addContentView(shortBanner, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        }
    }

    private void syncShortBanner() {
        if (shortBanner == null) return;
        if (!ShortUploader.isSubmitted()) {
            shortBanner.setVisibility(View.GONE);
            if (shortWasActive) {         // Short শেষ — ফিডে "uploaded a short" পোস্ট আসবে
                shortWasActive = false;
                if (ShortUploader.state() == ShortUploader.State.DONE) refreshPosts();
            }
            return;
        }
        shortWasActive = true;
        shortBanner.setVisibility(View.VISIBLE);
        android.graphics.Bitmap t = ShortUploader.thumb();
        if (t != null) sbThumb.setImageBitmap(t);
        else sbThumb.setImageDrawable(new android.graphics.drawable.ColorDrawable(0xFFE4E6EB));
        if (ShortUploader.state() == ShortUploader.State.FAILED) {
            sbTitle.setText("Short failed");
            String e = ShortUploader.error();
            sbSub.setText((e == null ? "" : e + " \u2022 ") + "Tap to retry");
            sbRing.setVisibility(View.GONE);
            sbClose.setVisibility(View.VISIBLE);
        } else {
            sbTitle.setText("Uploading short\u2026");
            sbSub.setText("Keep FamilyBook open.");
            sbRing.setProgress(ShortUploader.displayPercent());
            sbRing.setVisibility(View.VISIBLE);
            sbClose.setVisibility(View.GONE);
        }
    }

    // ---------- ফিড ক্যাশ ও লোড-ব্যর্থ অবস্থা ----------

    /** ফোনে রাখা সর্বশেষ ফিড (নেট ছাড়াই) দেখায় */
    private void loadCache() {
        String pj = FeedCache.get(this, myUid, "posts");
        if (pj == null) return;
        try {
            JSONArray a = new JSONArray(pj);
            List<Post> out = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) out.add(Post.from(a.getJSONObject(i), myUid));
            loaded = out;
        } catch (Exception e) {
            return;
        }
        String h = FeedCache.get(this, myUid, "hidden");
        String f = FeedCache.get(this, myUid, "friends");
        String fo = FeedCache.get(this, myUid, "follows");
        if (h != null) fillSet(h, "post_id", hiddenIds);
        if (f != null) fillSet(f, "friend_id", friendIds);
        if (fo != null) fillSet(fo, "followee_id", followingIds);
        render();
        rebuildMemories();
    }

    /** সোয়াইপ-লেআউটকে FrameLayout-এ মুড়ে তার উপরে এরর-অবস্থার ভিউ বসায় */
    private void setupFeedState() {
        ViewGroup parent = (ViewGroup) swipe.getParent();
        int idx = parent.indexOfChild(swipe);
        ViewGroup.LayoutParams lp = swipe.getLayoutParams();
        parent.removeView(swipe);
        FrameLayout holder = new FrameLayout(this);
        parent.addView(holder, idx, lp);
        holder.addView(swipe, new FrameLayout.LayoutParams(-1, -1));

        int bg = androidx.core.content.ContextCompat.getColor(this, R.color.h_bg);
        int txt = androidx.core.content.ContextCompat.getColor(this, R.color.h_text);
        int sub = androidx.core.content.ContextCompat.getColor(this, R.color.h_text_sub);
        int pill = androidx.core.content.ContextCompat.getColor(this, R.color.h_divider);

        feedState = new LinearLayout(this);
        feedState.setOrientation(LinearLayout.VERTICAL);
        feedState.setGravity(Gravity.CENTER);
        feedState.setBackgroundColor(bg);
        feedState.setPadding(dp(24), dp(28), dp(24), dp(28));
        feedState.setClickable(true);
        feedState.setVisibility(View.GONE);

        // ১) Something went wrong
        feedErrGroup = new LinearLayout(this);
        feedErrGroup.setOrientation(LinearLayout.VERTICAL);
        feedErrGroup.setGravity(Gravity.CENTER_HORIZONTAL);
        feedErrGroup.addView(centerText("Something went wrong", 17, sub, false));
        feedErrGroup.addView(centerText("Please check your connection and try again.", 17, sub, false));
        TextView again = centerText("Try again", 17, txt, true);
        again.setPadding(dp(30), dp(13), dp(30), dp(13));
        again.setBackground(roundBg(pill, 14));
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-2, -2);
        ap.topMargin = dp(24);
        feedErrGroup.addView(again, ap);
        again.setOnClickListener(v -> retryFeed());
        feedState.addView(feedErrGroup, new LinearLayout.LayoutParams(-1, -2));

        // ২) No internet connection
        feedOffGroup = new LinearLayout(this);
        feedOffGroup.setOrientation(LinearLayout.VERTICAL);
        feedOffGroup.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView wifi = new ImageView(this);
        wifi.setImageResource(R.drawable.ic_no_wifi);
        feedOffGroup.addView(wifi, new LinearLayout.LayoutParams(dp(96), dp(96)));
        TextView t1 = centerText("No internet connection", 20, sub, true);
        LinearLayout.LayoutParams t1p = new LinearLayout.LayoutParams(-2, -2);
        t1p.topMargin = dp(16);
        feedOffGroup.addView(t1, t1p);
        feedOffGroup.addView(centerText("Check your service or refresh to try again.", 15, sub, false));
        TextView refresh = centerText("Refresh", 17, Color.WHITE, true);
        refresh.setPadding(dp(34), dp(13), dp(34), dp(13));
        refresh.setBackground(roundBg(0xFF0866FF, 14));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-2, -2);
        rp.topMargin = dp(24);
        feedOffGroup.addView(refresh, rp);
        refresh.setOnClickListener(v -> retryFeed());
        feedState.addView(feedOffGroup, new LinearLayout.LayoutParams(-1, -2));

        holder.addView(feedState, new FrameLayout.LayoutParams(-1, -1));
    }

    private TextView centerText(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        if (bold) t.setTypeface(null, android.graphics.Typeface.BOLD);
        return t;
    }

    private android.graphics.drawable.GradientDrawable roundBg(int color, int radiusDp) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    /** mode 1 = Something went wrong, 2 = No internet. পোস্ট না থাকলে পুরো জায়গা জুড়ে; থাকলে ফিডের নিচে */
    private void showFeedState(int mode) {
        if (feedState == null) return;
        boolean empty = adapter.getItemCount() == 0;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) feedState.getLayoutParams();
        lp.height = empty ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT;
        lp.gravity = empty ? Gravity.NO_GRAVITY : Gravity.BOTTOM;
        feedState.setLayoutParams(lp);
        feedErrGroup.setVisibility(mode == 1 ? View.VISIBLE : View.GONE);
        feedOffGroup.setVisibility(mode == 2 ? View.VISIBLE : View.GONE);
        feedState.setVisibility(View.VISIBLE);
    }

    private void hideFeedState() {
        if (feedState != null) feedState.setVisibility(View.GONE);
    }

    private void feedLoadFailed(int code) {
        if (adapter.getItemCount() > 0) {
            // পোস্ট দেখা যাচ্ছে: নেট না থাকলে ওপরের "No internet" বারই যথেষ্ট; নইলে নিচে Try again
            if (Net.isOnline()) showFeedState(1); else hideFeedState();
        } else {
            showFeedState(Net.isOnline() && code != -1 ? 1 : 2);
        }
    }

    private void retryFeed() {
        if (!Net.isOnline()) {
            Toast.makeText(this, "ইন্টারনেট নেই", Toast.LENGTH_SHORT).show();
            return;
        }
        hideFeedState();
        swipe.setRefreshing(true);
        refreshPosts();
    }

    // ---------- ইন্টারনেট ব্যানার ----------
    private void setupBanner() {
        banner = new TextView(this);
        banner.setTextColor(Color.WHITE);
        banner.setTextSize(13);
        banner.setGravity(Gravity.CENTER);
        int pad = (int) (6 * getResources().getDisplayMetrics().density);
        banner.setPadding(pad, pad, pad, pad);
        banner.setVisibility(View.GONE);
        ViewGroup parent = (ViewGroup) swipe.getParent();
        if (parent instanceof LinearLayout) {
            parent.addView(banner, parent.indexOfChild(swipe), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            addContentView(banner, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP));
        }
    }

    private void onNet(boolean on) {
        if (banner == null) return;
        if (!on) {
            // কয়েক সেকেন্ড নেট না থাকলে তবেই দেখাও (শুরুতে ঝলকানি এড়াতে)
            uiHandler.removeCallbacks(hideBanner);
            uiHandler.removeCallbacks(showOffline);
            uiHandler.postDelayed(showOffline, 2500);
        } else {
            uiHandler.removeCallbacks(showOffline);
            if (needsRefresh && myUid != null) refreshPosts();   // আগে লোড হয়নি — নেট এলেই আবার
            if (offlineShown) {
                offlineShown = false;
                banner.setBackgroundColor(0xFF00A87E);
                banner.setText("Back online");
                banner.setVisibility(View.VISIBLE);
                uiHandler.removeCallbacks(hideBanner);
                uiHandler.postDelayed(hideBanner, 2500);
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        PostQueue.setListener(null);
        ShortUploader.removeListener(shortListener);
        ShortPlayerView.stopAll();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        PostQueue.setListener(null);
        ShortUploader.removeListener(shortListener);
        Net.setCallback(null);
        uiHandler.removeCallbacksAndMessages(null);
    }

    // Family Chat অ্যাপের package name (Family Chat-এর build.gradle-এর applicationId)
    private static final String FAMILY_CHAT_PKG = "com.familychat.app";

    private void openFamilyChat() {
        Intent intent = getPackageManager().getLaunchIntentForPackage(FAMILY_CHAT_PKG);
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } else {
            Toast.makeText(this, "Family Chat অ্যাপ ইনস্টল করা নেই", Toast.LENGTH_SHORT).show();
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("market://details?id=" + FAMILY_CHAT_PKG)));
            } catch (ActivityNotFoundException e) {
                // Play Store নেই — কিছু করার নেই, টোস্টই যথেষ্ট
            }
        }
    }

    private void soon(String what) {
        Toast.makeText(this, what + " — শীঘ্রই আসছে", Toast.LENGTH_SHORT).show();
    }

    private void goLogin() {
        Intent i = new Intent(this, LoginActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }
}
