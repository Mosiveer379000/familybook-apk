package com.familybook.app;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.activity.result.contract.ActivityResultContracts;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * একটি গ্রুপের নিজস্ব পেজ (Groups > Your group > গ্রুপে চাপলে)।
 *
 *  - উপরে: ব্যাক (বামে), সার্চ (ডানে — গ্রুপের পোস্ট, সদস্য, Admin/Member/Moderator সব খোঁজে)
 *  - কভার ফটো + অ্যাডমিনের জন্য কভার বদলানোর ক্যামেরা আইকন; নিচে "Created by / Creation time"
 *  - গ্রুপের নাম + তীর (গ্রুপের তথ্য), লোকেশন, "Private group · N members"
 *  - Manage (অ্যাডমিন) / Join / Invite
 *      Join: public গ্রুপে সাথে সাথে সদস্য; friends গ্রুপে অ্যাডমিন approve করলে; invite পেলে যেকোনো গ্রুপে।
 *      Invite: ইনভাইট নোটিফিকেশনে যায় (Join / Decline)
 *  - ট্যাব: You / Photos / Videos / Status
 *  - পোস্ট বক্স "Say good or keep silence" (ছবি পোস্ট কাজ করে, ভিডিও আপাতত ডেমো)
 *  - সদস্যদের পোস্ট, নামের পাশে ছোট করে Admin / Moderator / Member
 *
 * টেবিল: sql/09_group_detail.sql একবার Supabase SQL Editor-এ চালাতে হবে।
 */
public class GroupDetailActivity extends BaseActivity implements PostOps.Refreshable {

    /** true = "You" ট্যাবে শুধু আমার নিজের পোস্ট (Admin / Moderator / Member সবার জন্য); false = সবার পোস্ট */
    private static final boolean YOU_TAB_ONLY_MINE = true;

    private static final int TAB_ALL = 0, TAB_PHOTOS = 1, TAB_VIDEOS = 2, TAB_STATUS = 3, TAB_YOU = 4, TAB_DESC = 5;

    private static final LruCache<String, Bitmap> IMG = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };

    public static void open(Context c, String groupId) {
        c.startActivity(new Intent(c, GroupDetailActivity.class).putExtra("gid", groupId));
    }

    // ---- অবস্থা ----
    private String gid, myUid;
    private String myRole = "";              // "" = সদস্য নই, "pending" = রিকোয়েস্ট পাঠানো, নইলে admin/moderator/member
    private String gName = "", gDesc = "", gCategory = "", gLocation = "", gVisibility = "public", gPostMode = "auto";
    private String gOwner = "", gCreated = "", gCoverKey = "", ownerName = "";
    private int memberCount;
    private boolean loaded = false;

    private final Map<String, String> roles = new LinkedHashMap<>();   // active সদস্য: uid -> role
    private final List<String> pendingUids = new ArrayList<>();
    private final Map<String, String> names = new HashMap<>();
    private final List<Post> posts = new ArrayList<>();
    private final java.util.Set<String> hiddenIds = new java.util.HashSet<>();
    private PostAdapter adapter;
    private RecyclerView rvPosts;
    private TextView postingBanner;
    private boolean postWasActive = false;

    private int tab = TAB_ALL;           // গ্রুপ খুললেই "All" চালু
    private String query = "";
    private boolean searching = false;
    private boolean pickingCover = true;      // এই পিকার এখন শুধু কভারের জন্য (পোস্ট করা নতুন-পোস্ট স্ক্রিনে)

    // ---- ভিউ ----
    private TextView tvCreated, tvCreatedTime, tvGroupName, tvLocation, tvPrivacy, tvEmpty;
    private TextView btnManage, btnJoin, btnInvite, tvPostHint;
    private TextView[] tabs;
    private ImageView imgCover, btnCoverCamera, imgPrivacy, imgMe, btnSearch;
    private EditText etSearch;
    private View composeBox, tabsRow, manageWrap, manageDot;
    private boolean hasPendingPosts = false;      // Manage-এ ডট দেখাতে: অ্যাপ্রুভালের অপেক্ষায় পোস্ট আছে কি
    // গ্রুপ রুলস (পিন করা কার্ড + Description ট্যাব)
    private View rulesView, rulesCard, rulesFull;
    private TextView rulesText, rulesMenu, rulesFullText, rulesEmpty, rulesAdd;
    // "You" ট্যাবে আমার পেন্ডিং পোস্ট (অ্যাডমিন অ্যাপ্রুভ করার আগে, Delete করা যায়)
    private LinearLayout pendingBox;
    private final List<String[]> myPending = new ArrayList<>();       // {postId, text, imageKey}
    private LinearLayout postsBox, membersBox;
    private ScrollView scroll;

    private ActivityResultLauncher<String> imagePicker;
    private ActivityResultLauncher<Intent> cropLauncher;
    private ActivityResultLauncher<Intent> manageLauncher;
    private final Handler h = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        myUid = SupabaseRest.uid();
        gid = getIntent().getStringExtra("gid");
        if (!SupabaseRest.isLoggedIn() || myUid == null || gid == null) {
            finish();
            return;
        }

        imagePicker = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri == null) return;
            cropLauncher.launch(CropActivity.intent(this, uri, "cover"));
        });
        cropLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
            if (r.getResultCode() == RESULT_OK && r.getData() != null) {
                String path = r.getData().getStringExtra("path");
                if (path != null) uploadCover(path);
            }
        });

        manageLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
            if (r.getResultCode() == RESULT_OK && r.getData() != null
                    && r.getData().getBooleanExtra(GroupManageActivity.EXTRA_DELETED, false)) {
                finish();                      // গ্রুপ মুছে গেছে
            } else {
                loadAll();                     // লোকেশন/বর্ণনা/সদস্য বদলে থাকতে পারে
            }
        });

        setContentView(R.layout.activity_group_detail);
        bind();
        sizeCover();
        selectTab(TAB_ALL, false);
        AvatarLoader.load(imgMe, myUid);
        setupRules();
        setupFeed();
        loadAll();
    }

    // ---------------------------------------------------------------- হোমের মতো পোস্ট কার্ড

    /** হোম ফিডের একই PostAdapter: লাইক, কমেন্ট, শেয়ার, ভিউ, রিয়্যাকশন, মেনু সব হুবহু এক */
    private void setupFeed() {
        adapter = new PostAdapter(new PostAdapter.Actions() {
            @Override public void onLike(Post p) { toggleLike(p); }
            @Override public void onHide(Post p) {
                if (Net.check(GroupDetailActivity.this)) PostOps.hide(GroupDetailActivity.this, myUid, p.id);
            }
            @Override public void onMenu(Post p, View anchor) {
                PostOps.showMenu(GroupDetailActivity.this, myUid, p, anchor, true);
            }
            @Override public void onComments(Post p) {
                startActivity(new Intent(GroupDetailActivity.this, CommentsActivity.class)
                        .putExtra("postId", p.id));
            }
        }, true, true);
        rvPosts = new RecyclerView(this);
        rvPosts.setLayoutManager(new LinearLayoutManager(this));
        rvPosts.setNestedScrollingEnabled(false);      // বাইরের ScrollView-ই স্ক্রল করবে
        rvPosts.setOverScrollMode(View.OVER_SCROLL_NEVER);
        rvPosts.setAdapter(adapter);
        postsBox.addView(rvPosts, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
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
        adapter.notifyDataSetChanged();
    }

    /** PostOps.Refreshable — hide/delete/archive হলে ফিড নতুন করে আনে */
    @Override
    public void refreshPosts() {
        loadPosts();
    }

    // নতুন পোস্ট ব্যাকগ্রাউন্ডে আপলোড হয়: "Posting…" বার, শেষ হলে ফিড রিফ্রেশ
    @Override
    protected void onResume() {
        super.onResume();
        PostQueue.setListener(this::syncPostBanner);
        syncPostBanner();
    }

    @Override
    protected void onPause() {
        super.onPause();
        PostQueue.setListener(null);
    }

    private void syncPostBanner() {
        if (postingBanner == null) {
            postingBanner = new TextView(this);
            postingBanner.setTextSize(15);
            postingBanner.setTypeface(Typeface.DEFAULT_BOLD);
            postingBanner.setTextColor(Color.parseColor("#1877F2"));
            postingBanner.setPadding(dp(14), dp(10), dp(14), dp(4));
            postingBanner.setVisibility(View.GONE);
            postingBanner.setOnClickListener(v -> { if (PostQueue.isFailed()) PostQueue.retry(); });
            ((LinearLayout) composeBox).addView(postingBanner, 0);
        }
        if (!PostQueue.isActive()) {
            postingBanner.setVisibility(View.GONE);
            if (postWasActive) { postWasActive = false; loadPosts(); }
            return;
        }
        postWasActive = true;
        postingBanner.setVisibility(View.VISIBLE);
        postingBanner.setText(PostQueue.isFailed()
                ? "Post failed: " + PostQueue.error() + " \u2022 Tap to retry"
                : PostQueue.isWaiting()
                ? "Posting\u2026 waiting for network\u2026 (Draft saved)"
                : "Posting\u2026 " + PostQueue.percent() + "% \u2022 Keep FamilyBook open.");
    }

    private void bind() {
        scroll = findViewById(R.id.scroll);
        imgCover = findViewById(R.id.imgCover);
        btnCoverCamera = findViewById(R.id.btnCoverCamera);
        tvCreated = findViewById(R.id.tvCreated);
        tvCreatedTime = findViewById(R.id.tvCreatedTime);
        manageWrap = findViewById(R.id.manageWrap);
        manageDot = findViewById(R.id.manageDot);
        tvGroupName = findViewById(R.id.tvGroupName);
        tvLocation = findViewById(R.id.tvLocation);
        tvPrivacy = findViewById(R.id.tvPrivacy);
        imgPrivacy = findViewById(R.id.imgPrivacy);
        btnManage = findViewById(R.id.btnManage);
        btnJoin = findViewById(R.id.btnJoin);
        btnInvite = findViewById(R.id.btnInvite);
        tabsRow = findViewById(R.id.tabsRow);
        tabs = new TextView[]{findViewById(R.id.tabAll), findViewById(R.id.tabPhotos),
                findViewById(R.id.tabVideos), findViewById(R.id.tabStatus), findViewById(R.id.tabYou)};
        composeBox = findViewById(R.id.composeBox);
        imgMe = findViewById(R.id.imgMe);
        tvPostHint = findViewById(R.id.tvPostHint);
        membersBox = findViewById(R.id.membersBox);
        postsBox = findViewById(R.id.postsBox);
        tvEmpty = findViewById(R.id.tvEmpty);
        etSearch = findViewById(R.id.etSearch);
        btnSearch = findViewById(R.id.btnSearch);

        findViewById(R.id.btnBack).setOnClickListener(v -> onBackPressed());
        btnSearch.setOnClickListener(v -> setSearching(true));
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                query = s.toString().trim().toLowerCase(Locale.ROOT);
                renderFeed();
            }
        });

        btnCoverCamera.setOnClickListener(v -> { pickingCover = true; imagePicker.launch("image/*"); });
        findViewById(R.id.nameRow).setOnClickListener(v -> showInfo());
        btnManage.setOnClickListener(v -> showManage());
        btnJoin.setOnClickListener(v -> onJoinClick());
        btnInvite.setOnClickListener(v -> showInvite());

        addDescriptionTab();
        for (int i = 0; i < tabs.length; i++) {
            final int t = i;
            tabs[i].setOnClickListener(v -> selectTab(t, true));
        }

        // হোমের মতো: লেখার বক্স বা ছবির আইকনে চাপলে পূর্ণ পোস্ট স্ক্রিন খোলে
        tvPostHint.setOnClickListener(v -> openComposer(false));
        findViewById(R.id.btnPickPhoto).setOnClickListener(v -> openComposer(true));
        findViewById(R.id.btnPickVideo).setOnClickListener(v ->
                TopToast.show(this, "Video upload \u2014 coming soon (Demo)"));
    }

    // ------------------------------------------------------------------ গ্রুপ রুলস / Description ট্যাব

    /** "You"-র পাশে নতুন "Description" ট্যাব। XML না ছুঁয়ে শেষ ট্যাবের স্টাইল কপি করে বানানো, তাই হুবহু মিলবে */
    private void addDescriptionTab() {
        TextView last = tabs[tabs.length - 1];
        ViewGroup parent = (ViewGroup) last.getParent();
        TextView t = new TextView(this);
        t.setText("Description");
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, last.getTextSize());
        t.setTypeface(last.getTypeface());
        t.setGravity(last.getGravity());
        t.setSingleLine(true);
        t.setPadding(last.getPaddingLeft(), last.getPaddingTop(), last.getPaddingRight(), last.getPaddingBottom());
        ViewGroup.LayoutParams src = last.getLayoutParams();
        ViewGroup.LayoutParams lp = src instanceof LinearLayout.LayoutParams
                ? new LinearLayout.LayoutParams((LinearLayout.LayoutParams) src)
                : new ViewGroup.LayoutParams(src);
        parent.addView(t, parent.indexOfChild(last) + 1, lp);
        tabs = Arrays.copyOf(tabs, tabs.length + 1);
        tabs[tabs.length - 1] = t;
    }

    // ------------------------------------------------------------------ "You" ট্যাব: আমার পেন্ডিং পোস্ট

    /** এই গ্রুপে আমার অ্যাপ্রুভালের অপেক্ষায় থাকা পোস্ট (ডাটাবেসের my_pending_posts ফাংশন) */
    private void loadMyPending() {
        SupabaseRest.call("POST", "/rest/v1/rpc/my_pending_posts",
                "{\"p_group_id\":\"" + gid + "\"}", null, true, r -> {
            if (isFinishing() || !r.ok()) return;
            myPending.clear();
            JSONArray a = arr(r.body);
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null) myPending.add(new String[]{Db.s(o, "id"), Db.s(o, "text"), Db.s(o, "image_key")});
            }
            renderFeed();
        });
    }

    private void renderPending() {
        if (pendingBox == null) return;
        pendingBox.removeAllViews();
        boolean show = tab == TAB_YOU && !searching && loaded && canSeePosts() && !myPending.isEmpty();
        pendingBox.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        for (String[] p : myPending) pendingBox.addView(pendingRow(p));
    }

    private View pendingRow(final String[] p) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_group_rules_card);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.setMargins(dp(12), dp(8), dp(12), dp(4));
        card.setLayoutParams(cp);

        TextView chip = new TextView(this);
        chip.setText("Pending approval");
        chip.setTextSize(13);
        chip.setTypeface(Typeface.DEFAULT_BOLD);
        chip.setTextColor(Color.parseColor("#B26A00"));
        card.addView(chip);

        TextView body = new TextView(this);
        boolean hasText = p[1] != null && !p[1].trim().isEmpty();
        boolean hasPhoto = p[2] != null && !p[2].isEmpty();
        body.setText(hasText ? p[1].trim() : (hasPhoto ? "(Photo post)" : ""));
        if (hasText && hasPhoto) body.setText(p[1].trim() + "\n(with photo)");
        body.setTextSize(15);
        body.setTextColor(Color.parseColor("#111111"));
        body.setMaxLines(6);
        body.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = dp(6);
        card.addView(body, bp);

        TextView note = new TextView(this);
        note.setText("Only you can see this until an Admin approves it.");
        note.setTextSize(12);
        note.setTextColor(Color.parseColor("#65676B"));
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        np.topMargin = dp(6);
        card.addView(note, np);

        TextView del = new TextView(this);
        del.setText("Delete");
        del.setTextSize(14);
        del.setTypeface(Typeface.DEFAULT_BOLD);
        del.setTextColor(Color.parseColor("#D8000C"));
        del.setBackgroundResource(R.drawable.bg_gp_chip_off);
        del.setGravity(Gravity.CENTER);
        del.setPadding(dp(18), dp(8), dp(18), dp(8));
        LinearLayout.LayoutParams dp0 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dp0.topMargin = dp(10);
        card.addView(del, dp0);
        del.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Delete this post?")
                .setMessage("It is waiting for Admin approval. If you delete it, it will be gone.")
                .setPositiveButton("Delete", (d, w) -> deleteMyPending(p[0]))
                .setNegativeButton("Cancel", null).show());
        return card;
    }

    private void deleteMyPending(final String postId) {
        if (!Net.check(this)) return;
        SupabaseRest.call("POST", "/rest/v1/rpc/delete_my_pending_post",
                "{\"p_post_id\":\"" + postId + "\"}", null, true, r -> {
            if (isFinishing()) return;
            if (!r.ok()) { toast(SupabaseRest.errorText(r)); return; }
            for (int i = 0; i < myPending.size(); i++) {
                if (myPending.get(i)[0].equals(postId)) { myPending.remove(i); break; }
            }
            toast("Post deleted");
            renderFeed();
        });
    }

    private boolean isManager() { return isAdmin() || "moderator".equals(myRole); }

    /** পোস্ট লিস্টের উপরে (postsBox index 0) গ্রুপ রুলসের কার্ড বসায় */
    private void setupRules() {
        rulesView = getLayoutInflater().inflate(R.layout.view_group_rules, postsBox, false);
        rulesCard = rulesView.findViewById(R.id.rulesCard);
        rulesFull = rulesView.findViewById(R.id.rulesFull);
        rulesText = rulesView.findViewById(R.id.rulesText);
        rulesMenu = rulesView.findViewById(R.id.rulesMenu);
        rulesFullText = rulesView.findViewById(R.id.rulesFullText);
        rulesEmpty = rulesView.findViewById(R.id.rulesEmpty);
        rulesAdd = rulesView.findViewById(R.id.rulesAdd);
        rulesView.setVisibility(View.GONE);
        postsBox.addView(rulesView, 0);
        pendingBox = new LinearLayout(this);
        pendingBox.setOrientation(LinearLayout.VERTICAL);
        pendingBox.setVisibility(View.GONE);
        postsBox.addView(pendingBox, 1);

        rulesCard.setOnClickListener(v -> selectTab(TAB_DESC, true));
        rulesMenu.setOnClickListener(this::showRulesMenu);
        rulesAdd.setOnClickListener(v -> showManage());      // বর্ণনা Manage স্ক্রিনেই লেখা হয়
    }

    private void showRulesMenu(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        pm.getMenu().add(0, 1, 0, "View full rules");
        if (isManager()) pm.getMenu().add(0, 2, 1, gDesc.trim().isEmpty() ? "Add rules" : "Edit rules");
        pm.setOnMenuItemClickListener(it -> {
            if (it.getItemId() == 1) selectTab(TAB_DESC, true);
            else showManage();
            return true;
        });
        pm.show();
    }

    /** পিন করা কার্ড (ডেসক্রিপশন ট্যাব বাদে সব ট্যাবে) অথবা Description ট্যাবের পূর্ণ পেজ দেখায় */
    private void updateRules() {
        if (rulesView == null) return;
        boolean show = loaded && canSeePosts() && !searching;
        boolean has = !gDesc.trim().isEmpty();
        boolean onDesc = tab == TAB_DESC;

        rulesText.setText(gDesc.trim());
        rulesFullText.setText(gDesc.trim());
        rulesFullText.setVisibility(has ? View.VISIBLE : View.GONE);
        rulesEmpty.setVisibility(has ? View.GONE : View.VISIBLE);
        rulesAdd.setVisibility(!has && isManager() ? View.VISIBLE : View.GONE);

        rulesCard.setVisibility(show && has && !onDesc ? View.VISIBLE : View.GONE);
        rulesFull.setVisibility(show && onDesc ? View.VISIBLE : View.GONE);
        rulesView.setVisibility(rulesCard.getVisibility() == View.VISIBLE
                || rulesFull.getVisibility() == View.VISIBLE ? View.VISIBLE : View.GONE);
    }

    private void sizeCover() {
        View frame = findViewById(R.id.coverFrame);
        frame.getLayoutParams().height = (getResources().getDisplayMetrics().widthPixels * 9) / 16;
    }

    @Override
    public void onBackPressed() {
        if (searching) { setSearching(false); return; }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------ লোড

    private void loadAll() {
        SupabaseRest.call("GET", "/rest/v1/groups?id=eq." + gid
                        + "&select=id,name,description,category,location,visibility,owner_id,created_at,cover_key,post_mode,post_privacy&limit=1",
                null, null, true, r -> {
                    if (isFinishing()) return;
                    if (!r.ok()) { toast(SupabaseRest.errorText(r)); return; }
                    JSONObject g = first(r.body);
                    if (g == null) { toast("Group not found"); finish(); return; }
                    gName = Db.s(g, "name");
                    gDesc = Db.s(g, "description");
                    gCategory = Db.s(g, "category");
                    gLocation = Db.s(g, "location");
                    gVisibility = Db.s(g, "visibility");
                    if (gVisibility.isEmpty()) gVisibility = "public";
                    gOwner = Db.s(g, "owner_id");
                    gPostMode = Db.s(g, "post_mode").isEmpty() ? "auto" : Db.s(g, "post_mode");
                    GroupRules.put(gid, Db.s(g, "post_privacy"), Db.s(g, "post_mode"));
                    gCreated = Db.s(g, "created_at");
                    gCoverKey = Db.s(g, "cover_key");
                    loaded = true;
                    updateHeader();
                    loadCover();
                    if (!gOwner.isEmpty()) {
                        fetchNames(java.util.Collections.singletonList(gOwner), () -> {
                            ownerName = nameOf(gOwner);
                            updateHeader();
                        });
                    }
                    loadMembers();
                });
    }

    private void loadMembers() {
        SupabaseRest.call("GET", "/rest/v1/group_members?group_id=eq." + gid
                        + "&select=user_id,role,status&limit=2000",
                null, null, true, r -> {
                    if (isFinishing()) return;
                    if (!r.ok()) { toast(SupabaseRest.errorText(r)); return; }
                    roles.clear();
                    pendingUids.clear();
                    myRole = "";
                    memberCount = 0;
                    JSONArray a = arr(r.body);
                    for (int i = 0; a != null && i < a.length(); i++) {
                        JSONObject o = a.optJSONObject(i);
                        if (o == null) continue;
                        String uid = Db.s(o, "user_id"), role = Db.s(o, "role"), st = Db.s(o, "status");
                        if (uid.isEmpty()) continue;
                        if ("pending".equals(st)) {
                            pendingUids.add(uid);
                            if (uid.equals(myUid)) myRole = "pending";
                            continue;
                        }
                        if (role.isEmpty()) role = "member";
                        roles.put(uid, role);
                        if (!"admin".equals(role)) memberCount++;
                        if (uid.equals(myUid)) myRole = role;
                    }
                    updateHeader();
                    loadPendingFlag();
                    loadPosts();
                });
    }

    /** অ্যাডমিন/মডারেটরের জন্য: অ্যাপ্রুভালের অপেক্ষায় (নতুন বা এডিট করা) পোস্ট আছে কি — Manage-এর লাল ডটের জন্য */
    private void loadPendingFlag() {
        if (!(isAdmin() || "moderator".equals(myRole))) {
            if (hasPendingPosts) { hasPendingPosts = false; updateHeader(); }
            return;
        }
        SupabaseRest.call("GET", "/rest/v1/posts?group_id=eq." + gid
                + "&status=eq.pending&select=id&limit=1", null, null, true, r -> {
            if (isFinishing() || !r.ok()) return;
            JSONArray a = arr(r.body);
            boolean has = a != null && a.length() > 0;
            if (has != hasPendingPosts) { hasPendingPosts = has; updateHeader(); }
        });
    }

    private boolean canSeePosts() {
        return "public".equals(gVisibility) || isMember();
    }

    private void loadPosts() {
        posts.clear();
        if (!canSeePosts()) { myPending.clear(); renderFeed(); return; }
        loadMyPending();
        // হোমের পোস্ট-কার্ড ভিউ: লাইক/কমেন্ট/শেয়ার/ভিউ গোনা সব আছে; গ্রুপের প্রাইভেসি ভিউ নিজেই মানে
        SupabaseRest.call("GET", "/rest/v1/post_cards_g?group_id=eq." + gid
                        + "&status=eq.active&order=created_at.desc&limit=100",
                null, null, true, r -> {
                    if (isFinishing()) return;
                    if (!r.ok()) { toast(SupabaseRest.errorText(r)); renderFeed(); return; }
                    JSONArray a = arr(r.body);
                    for (int i = 0; a != null && i < a.length(); i++) {
                        JSONObject o = a.optJSONObject(i);
                        if (o == null) continue;
                        posts.add(Post.from(o, myUid));
                    }
                    // আমার hidden পোস্ট (হোমের মতো)
                    SupabaseRest.call("GET", "/rest/v1/hidden_posts?user_id=eq." + myUid + "&select=post_id",
                            null, null, true, hr -> {
                        hiddenIds.clear();
                        JSONArray ha = hr.ok() ? arr(hr.body) : null;
                        for (int i = 0; ha != null && i < ha.length(); i++) {
                            JSONObject ho = ha.optJSONObject(i);
                            if (ho != null) hiddenIds.add(Db.s(ho, "post_id"));
                        }
                        for (Post p : posts) p.hiddenByMe = hiddenIds.contains(p.id);
                        if (isFinishing()) return;
                        // ছবি আছে কি না জানতে ছবির তথ্য আগে আনি (Photos ট্যাবের জন্য)
                        PostImage.prefetch(posts, this::renderFeed);
                        renderFeed();
                        PostExtras.load(() -> { if (!isFinishing()) adapter.notifyDataSetChanged(); });
                    });
                });
    }

    private void loadCover() {
        if (gCoverKey.isEmpty()) { imgCover.setImageDrawable(null); return; }
        Bitmap c = IMG.get(gCoverKey);
        if (c != null) { imgCover.setImageBitmap(c); return; }
        final String key = gCoverKey;
        Media.loadBitmap(key, bm -> {
            if (isFinishing()) return;
            IMG.put(key, bm);
            if (key.equals(gCoverKey)) imgCover.setImageBitmap(bm);
        }, null);
    }

    // ------------------------------------------------------------------ হেডার

    private boolean isAdmin() { return "admin".equals(myRole); }
    private boolean isMember() {
        return "admin".equals(myRole) || "moderator".equals(myRole) || "member".equals(myRole);
    }

    private void updateHeader() {
        if (!loaded) return;
        tvGroupName.setText(gName);

        if (gLocation.isEmpty()) tvLocation.setVisibility(View.GONE);
        else { tvLocation.setText(gLocation); tvLocation.setVisibility(View.VISIBLE); }

        String label;
        int icon;
        if ("public".equals(gVisibility)) { label = "Public group"; icon = R.drawable.ic_globe; }
        else if ("friends".equals(gVisibility)) { label = "Friends group"; icon = R.drawable.ic_pivracy_friends; }
        else { label = "Private group"; icon = R.drawable.ic_locked; }
        imgPrivacy.setImageResource(icon);
        tvPrivacy.setText(label + " \u00B7 " + memberCount + (memberCount == 1 ? " member" : " members"));

        String by = ownerName.isEmpty() ? "..." : ownerName;
        tvCreated.setText("Created by : " + by);
        tvCreatedTime.setText("Creation time : " + fmtDate(gCreated));

        btnCoverCamera.setVisibility(isAdmin() ? View.VISIBLE : View.GONE);
        boolean mgr = isAdmin() || "moderator".equals(myRole);
        manageWrap.setVisibility(mgr ? View.VISIBLE : View.GONE);   // Admin ও Moderator
        // নতুন Join request বা পোস্ট/এডিটের অ্যাপ্রুভাল থাকলে Manage-এর উপর লাল ডট
        manageDot.setVisibility(mgr && (!pendingUids.isEmpty() || hasPendingPosts) ? View.VISIBLE : View.GONE);

        // Join বাটন
        if (isAdmin()) {
            btnJoin.setVisibility(View.GONE);
        } else {
            btnJoin.setVisibility(View.VISIBLE);
            if (isMember()) {
                btnJoin.setText("Joined");
                btnJoin.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0);
            } else if ("pending".equals(myRole)) {
                btnJoin.setText("Requested");
                btnJoin.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0);
            } else {
                btnJoin.setText("Join");
                btnJoin.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_gp_plus_btn, 0, 0, 0);
            }
        }
        // Invite: সদস্য সবসময়; সদস্য নয় এমন কেউ শুধু public গ্রুপে
        btnInvite.setVisibility(isMember() || "public".equals(gVisibility) ? View.VISIBLE : View.GONE);

        composeBox.setVisibility(isMember() && !searching ? View.VISIBLE : View.GONE);
    }

    private String fmtDate(String iso) {
        if (iso == null || iso.isEmpty()) return "-";
        long t = NotificationItem.parseIso(iso);
        if (t == 0) return "-";
        return new SimpleDateFormat("d MMM yyyy", Locale.ENGLISH).format(new java.util.Date(t));
    }

    private void showInfo() {
        StringBuilder sb = new StringBuilder();
        if (!gDesc.isEmpty()) sb.append(gDesc).append("\n\n");
        if (!gCategory.isEmpty()) sb.append("Category: ").append(gCategory).append('\n');
        if (!gLocation.isEmpty()) sb.append("Location: ").append(gLocation).append('\n');
        sb.append("Privacy: ").append("public".equals(gVisibility) ? "Public"
                : "friends".equals(gVisibility) ? "Friends" : "Group members").append('\n');
        sb.append("Members: ").append(memberCount).append('\n');
        sb.append("Created: ").append(fmtDate(gCreated));
        new AlertDialog.Builder(this).setTitle(gName).setMessage(sb.toString())
                .setPositiveButton("OK", null).show();
    }

    // ------------------------------------------------------------------ ট্যাব ও ফিড

    private void selectTab(int t, boolean render) {
        tab = t;
        for (int i = 0; i < tabs.length; i++) {
            boolean on = i == t;
            tabs[i].setBackgroundResource(on ? R.drawable.bg_gp_tab_on : R.drawable.bg_gp_tab_off);
            tabs[i].setTextColor(on ? Color.WHITE : Color.parseColor("#111111"));
        }
        if (render) renderFeed();
    }

    private void setSearching(boolean on) {
        searching = on;
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (on) {
            etSearch.setText("");
            etSearch.setVisibility(View.VISIBLE);
            btnSearch.setVisibility(View.GONE);
            tabsRow.setVisibility(View.GONE);
            composeBox.setVisibility(View.GONE);
            etSearch.requestFocus();
            if (imm != null) imm.showSoftInput(etSearch, InputMethodManager.SHOW_IMPLICIT);
        } else {
            etSearch.setText("");
            etSearch.setVisibility(View.GONE);
            btnSearch.setVisibility(View.VISIBLE);
            tabsRow.setVisibility(View.VISIBLE);
            if (imm != null) imm.hideSoftInputFromWindow(etSearch.getWindowToken(), 0);
            query = "";
            updateHeader();
        }
        renderFeed();
    }

    private String nameOf(String uid) {
        String n = names.get(uid);
        return n == null || n.isEmpty() ? "Member" : n;
    }

    private static String roleLabel(String role) {
        if ("admin".equals(role)) return "Admin";
        if ("moderator".equals(role)) return "Moderator";
        if ("member".equals(role)) return "Member";
        return "";
    }

    private boolean matchesTab(Post p) {
        String img = PostImage.raw(p.id);
        boolean hasImg = img != null && !img.isEmpty();
        switch (tab) {
            case TAB_PHOTOS: return hasImg;
            case TAB_VIDEOS: return false;           // ভিডিও এখনো আসেনি (Demo)
            case TAB_STATUS: return !hasImg && p.text != null && !p.text.trim().isEmpty();
            case TAB_YOU: return !YOU_TAB_ONLY_MINE || p.isMine(myUid);
            case TAB_DESC: return false;             // Description ট্যাবে পোস্ট নয়, শুধু রুলস
            default: return true;                     // All — সব পোস্ট একসাথে
        }
    }

    private void renderFeed() {
        membersBox.removeAllViews();
        tvEmpty.setVisibility(View.GONE);
        if (!loaded) return;
        updateRules();
        renderPending();

        if (!canSeePosts()) {
            adapter.setItems(new ArrayList<Post>());
            tvEmpty.setText("Join this group to see what members share.");
            tvEmpty.setVisibility(View.VISIBLE);
            return;
        }

        if (tab == TAB_DESC && !searching) {            // Description ট্যাব: পোস্ট লুকিয়ে রুলস দেখাই
            adapter.setItems(new ArrayList<Post>());
            return;
        }

        boolean q = searching && !query.isEmpty();
        int shownMembers = 0, shownPosts = 0;

        if (q) {
            for (Map.Entry<String, String> e : roles.entrySet()) {
                String nm = nameOf(e.getKey()).toLowerCase(Locale.ROOT);
                String rl = roleLabel(e.getValue()).toLowerCase(Locale.ROOT);
                if (!nm.contains(query) && !rl.contains(query)) continue;
                if (shownMembers >= 10) break;
                membersBox.addView(memberRow(e.getKey(), e.getValue()));
                shownMembers++;
            }
        }

        List<Post> visible = new ArrayList<>();
        for (Post p : posts) {
            if (q) {
                String hay = ((p.text == null ? "" : p.text) + " " + (p.name == null ? "" : p.name)
                        + " " + roleLabel(roles.get(p.uid))).toLowerCase(Locale.ROOT);
                if (!hay.contains(query)) continue;
            } else if (!matchesTab(p)) {
                continue;
            }
            visible.add(p);
            shownPosts++;
        }
        adapter.setItems(visible);

        if (shownMembers == 0 && shownPosts == 0 && !(tab == TAB_YOU && !searching && !myPending.isEmpty())) {
            String msg;
            if (q) msg = "No results for \"" + query + "\"";
            else if (searching) msg = "Search posts, members, Admin, Moderator…";
            else if (tab == TAB_PHOTOS) msg = "No photo posts yet";
            else if (tab == TAB_VIDEOS) msg = "No video posts yet";
            else if (tab == TAB_STATUS) msg = "No status posts yet";
            else if (tab == TAB_YOU) msg = YOU_TAB_ONLY_MINE ? "You haven't posted in this group yet." : "No posts yet. Say something good!";
            else msg = "No posts yet. Say something good!";
            tvEmpty.setText(msg);
            tvEmpty.setVisibility(View.VISIBLE);
        }
    }

    private View memberRow(final String uid, String role) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(8), dp(14), dp(8));
        row.setBackgroundResource(android.R.drawable.list_selector_background);

        ImageView av = new ImageView(this);
        row.addView(av, new LinearLayout.LayoutParams(dp(44), dp(44)));
        AvatarLoader.load(av, uid);

        TextView nm = new TextView(this);
        nm.setText(nameOf(uid));
        nm.setTextColor(Color.parseColor("#111111"));
        nm.setTextSize(16);
        nm.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(10);
        row.addView(nm, lp);

        TextView chip = new TextView(this);
        styleRole(chip, role);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.leftMargin = dp(6);
        row.addView(chip, cp);

        row.setOnClickListener(v -> ProfileActivity.open(this, uid, nameOf(uid)));
        return row;
    }

    private void styleRole(TextView chip, String role) {
        String label = roleLabel(role);
        if (label.isEmpty()) { chip.setVisibility(View.GONE); return; }
        chip.setVisibility(View.VISIBLE);
        chip.setText(label);
        if ("admin".equals(role)) {
            chip.setBackgroundResource(R.drawable.bg_gp_role_admin);
            chip.setTextColor(Color.parseColor("#1877F2"));
        } else if ("moderator".equals(role)) {
            chip.setBackgroundResource(R.drawable.bg_gp_role_mod);
            chip.setTextColor(Color.parseColor("#1B7F3B"));
        } else {
            chip.setBackgroundResource(R.drawable.bg_gp_role_member);
            chip.setTextColor(Color.parseColor("#65676B"));
        }
        chip.setTextSize(11);
        chip.setTypeface(Typeface.DEFAULT_BOLD);
        chip.setPadding(dp(6), dp(1), dp(6), dp(1));
    }

    // ------------------------------------------------------------------ পোস্ট করা

    /** হোমের পোস্ট স্ক্রিনই খোলে (গ্রুপ মোডে): ছবি (একাধিক), গ্রিড, হ্যাশট্যাগ, Collaborate, Tag, Music, Feeling, Location সব আছে */
    private void openComposer(final boolean pickPhoto) {
        if (!isMember()) return;
        if (!Net.check(this)) return;
        boolean manager = isAdmin() || "moderator".equals(myRole);
        if ("admins_only".equals(gPostMode) && !manager) {
            toast("Only Admins and Moderators can post in this group");
            return;
        }
        if ("admin_approval".equals(gPostMode) && !manager) {
            toast("Your post will appear in the group once an Admin approves it");
        }
        // ট্যাগ/কোলাব শুধু গ্রুপ সদস্যদের — তাই সদস্যদের নাম আগে এনে নিই
        final List<String> uids = new ArrayList<>(roles.keySet());
        fetchNames(uids, () -> {
            if (isFinishing()) return;
            ArrayList<String> ids = new ArrayList<>();
            ArrayList<String> nm = new ArrayList<>();
            for (String u : uids) {
                if (u.equals(myUid)) continue;           // নিজেকে ট্যাগ করা যাবে না
                ids.add(u);
                nm.add(nameOf(u));
            }
            Intent i = new Intent(this, NewPostActivity.class);
            i.putExtra("group_id", gid);
            i.putExtra("group_name", gName);
            i.putExtra("group_visibility", gVisibility);
            i.putStringArrayListExtra("member_ids", ids);
            i.putStringArrayListExtra("member_names", nm);
            if (pickPhoto) i.putExtra("pickPhoto", true);
            startActivity(i);
        });
    }

    // ------------------------------------------------------------------ কভার

    private void uploadCover(String path) {
        final Bitmap bm = BitmapFactory.decodeFile(path);
        if (bm == null) { toast("Failed to load image"); return; }
        toast("Uploading cover...");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        bm.compress(Bitmap.CompressFormat.JPEG, 85, baos);
        Media.upload(baos.toByteArray(), "cover", "group_cover.jpg", "image/jpeg", (key, err) -> {
            if (isFinishing()) return;
            if (err != null) { toast(err); return; }
            try {
                JSONObject f = new JSONObject().put("cover_key", key);
                SupabaseRest.call("PATCH", "/rest/v1/groups?id=eq." + gid, f.toString(),
                        "return=representation", true, r -> {
                            if (isFinishing()) return;
                            JSONArray a = r.ok() ? arr(r.body) : null;
                            if (!r.ok()) { toast(SupabaseRest.errorText(r)); return; }
                            if (a == null || a.length() == 0) { toast("You don\'t have permission to change the cover"); return; }
                            gCoverKey = key;
                            IMG.put(key, bm);
                            imgCover.setImageBitmap(bm);
                        });
            } catch (Exception e) {
                toast("Could not save cover");
            }
        });
    }

    // ------------------------------------------------------------------ Join / Leave

    private void onJoinClick() {
        if (isAdmin()) return;
        if (isMember()) {
            new AlertDialog.Builder(this).setTitle("Leave group?")
                    .setMessage("You will leave \"" + gName + "\".")
                    .setPositiveButton("Leave", (d, w) -> removeMe("You left the group"))
                    .setNegativeButton("Cancel", null).show();
        } else if ("pending".equals(myRole)) {
            new AlertDialog.Builder(this).setTitle("Cancel join request?")
                    .setPositiveButton("Cancel request", (d, w) -> removeMe("Request cancelled"))
                    .setNegativeButton("Keep", null).show();
        } else {
            join();
        }
    }

    private void removeMe(final String okMsg) {
        SupabaseRest.call("DELETE", "/rest/v1/group_members?group_id=eq." + gid + "&user_id=eq." + myUid,
                null, "return=minimal", true, r -> {
                    if (isFinishing()) return;
                    if (!r.ok()) { toast(SupabaseRest.errorText(r)); return; }
                    toast(okMsg);
                    loadMembers();
                });
    }

    private void join() {
        // ব্লক করা থাকলে ঢোকা যাবে না
        SupabaseRest.call("GET", "/rest/v1/group_blocks?group_id=eq." + gid + "&user_id=eq." + myUid
                + "&select=user_id&limit=1", null, null, true, br -> {
            if (isFinishing()) return;
            JSONArray ba = br.ok() ? arr(br.body) : null;
            if (ba != null && ba.length() > 0) { toast("You have been blocked from this group"); return; }
            joinAfterBlockCheck();
        });
    }

    private void joinAfterBlockCheck() {
        // আগে দেখি আমাকে ইনভাইট করা হয়েছে কিনা — হলে যেকোনো গ্রুপে সরাসরি জয়েন
        SupabaseRest.call("GET", "/rest/v1/group_invites?group_id=eq." + gid + "&invitee_id=eq." + myUid
                + "&status=eq.pending&select=id&limit=1", null, null, true, r -> {
            if (isFinishing()) return;
            JSONArray a = r.ok() ? arr(r.body) : null;
            boolean invited = a != null && a.length() > 0;
            // ১নং: সরাসরি সদস্য। ২ ও ৩নং: Join বা Invite — দুটোতেই Admin/Moderator approve করলে তবে সদস্য। Private গ্রুপ শুধু ইনভাইটে
            boolean needApproval = "admin_approval".equals(gPostMode) || "admins_only".equals(gPostMode);
            boolean inviteNeedsApproval = "admins_only".equals(gPostMode);
            if (invited) insertMe(inviteNeedsApproval ? "pending" : "active", true);
            else if ("public".equals(gVisibility) || "friends".equals(gVisibility)) insertMe(needApproval ? "pending" : "active", false);
            else toast("This group can only be joined by invitation");
        });
    }

    private void insertMe(final String status, final boolean viaInvite) {
        try {
            JSONObject b = new JSONObject().put("group_id", gid).put("user_id", myUid)
                    .put("role", "member").put("status", status);
            SupabaseRest.call("POST", "/rest/v1/group_members", b.toString(), "return=minimal", true, r -> {
                if (isFinishing()) return;
                if (!r.ok()) { toast(SupabaseRest.errorText(r)); return; }
                if (viaInvite) {
                    SupabaseRest.call("PATCH", "/rest/v1/group_invites?group_id=eq." + gid
                            + "&invitee_id=eq." + myUid, "{\"status\":\"accepted\"}",
                            "return=minimal", true, x -> { });
                }
                if ("pending".equals(status)) {
                    toast("Join request sent. You will become a member once an Admin approves it.");
                    final String owner = gOwner;
                    PostOps.loadMyName(myUid, n -> NotificationHelper.sendGroup(owner, "group_join_request", gid,
                            (n == null || n.isEmpty() ? "Someone" : n) + " wants to join your group \"" + gName + "\""));
                } else {
                    toast("You joined the group");
                }
                loadMembers();
            });
        } catch (Exception e) {
            toast("Could not join");
        }
    }

    // ------------------------------------------------------------------ Invite

    private void showInvite() {
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), 0);

        final EditText et = new EditText(this);
        et.setHint("Search people by name");
        et.setSingleLine(true);
        box.addView(et);

        final LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(this);
        sv.addView(results);
        box.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(320)));

        final Runnable[] pend = new Runnable[1];
        et.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(final Editable s) {
                if (pend[0] != null) h.removeCallbacks(pend[0]);
                pend[0] = () -> searchPeople(s.toString().trim(), results);
                h.postDelayed(pend[0], 400);
            }
        });

        new AlertDialog.Builder(this).setTitle("Invite to \"" + gName + "\"")
                .setView(box).setNegativeButton("Close", null).show();
    }

    private void searchPeople(String q, final LinearLayout results) {
        results.removeAllViews();
        if (q.length() < 2) return;
        SupabaseRest.call("GET", "/rest/v1/profiles?name=ilike.*" + Uri.encode(q) + "*&id=neq." + myUid
                + "&select=id,name&limit=20", null, null, true, r -> {
            if (isFinishing() || !r.ok()) return;
            results.removeAllViews();
            JSONArray a = arr(r.body);
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                final String uid = Db.s(o, "id"), nm = Db.s(o, "name");
                names.put(uid, nm);
                results.addView(inviteRow(uid, nm));
            }
            if (results.getChildCount() == 0) {
                TextView t = new TextView(this);
                t.setText("No people found");
                t.setPadding(0, dp(16), 0, 0);
                results.addView(t);
            }
        });
    }

    private View inviteRow(final String uid, final String nm) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));

        ImageView av = new ImageView(this);
        row.addView(av, new LinearLayout.LayoutParams(dp(40), dp(40)));
        AvatarLoader.load(av, uid);

        TextView t = new TextView(this);
        t.setText(nm);
        t.setTextSize(16);
        t.setTextColor(Color.parseColor("#111111"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = dp(10);
        row.addView(t, lp);

        final TextView btn = new TextView(this);
        btn.setGravity(Gravity.CENTER);
        btn.setTextSize(14);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setPadding(dp(16), dp(7), dp(16), dp(7));
        row.addView(btn);

        if (roles.containsKey(uid)) {
            btn.setText("Member");
            btn.setTextColor(Color.parseColor("#65676B"));
        } else {
            btn.setText("Invite");
            btn.setTextColor(Color.WHITE);
            btn.setBackgroundResource(R.drawable.bg_gp_btn_manage);
            btn.setOnClickListener(v -> sendInvite(uid, btn));
        }
        return row;
    }

    private void sendInvite(final String uid, final TextView btn) {
        btn.setOnClickListener(null);
        btn.setText("Sending...");
        try {
            JSONObject b = new JSONObject().put("group_id", gid).put("inviter_id", myUid)
                    .put("invitee_id", uid).put("status", "pending");
            SupabaseRest.call("POST", "/rest/v1/group_invites?on_conflict=group_id,invitee_id", b.toString(),
                    "resolution=merge-duplicates,return=minimal", true, r -> {
                        if (isFinishing()) return;
                        if (!r.ok()) { btn.setText("Invite"); toast(SupabaseRest.errorText(r)); return; }
                        btn.setBackground(null);
                        btn.setTextColor(Color.parseColor("#65676B"));
                        btn.setText("Invited");
                        PostOps.loadMyName(myUid, n -> NotificationHelper.sendGroup(uid, "group_invite", gid,
                                (n == null || n.isEmpty() ? "Someone" : n)
                                        + " invited you to join their group \"" + gName + "\""));
                    });
        } catch (Exception e) {
            btn.setText("Invite");
        }
    }

    // ------------------------------------------------------------------ Manage (অ্যাডমিন)

    private void showManage() {
        // ম্যানেজার স্ক্রিন: লোকেশন/বর্ণনা, সদস্য, অ্যাডমিন, মোট সদস্য, Delete
        manageLauncher.launch(new Intent(this, GroupManageActivity.class).putExtra("gid", gid));
    }

    private void showRequests() {
        if (pendingUids.isEmpty()) { toast("No join requests"); return; }
        fetchNames(new ArrayList<>(pendingUids), () -> {
            if (isFinishing()) return;
            LinearLayout list = new LinearLayout(this);
            list.setOrientation(LinearLayout.VERTICAL);
            list.setPadding(dp(16), dp(8), dp(16), 0);
            for (String uid : new ArrayList<>(pendingUids)) list.addView(requestRow(uid));
            ScrollView sv = new ScrollView(this);
            sv.addView(list);
            new AlertDialog.Builder(this).setTitle("Join requests").setView(sv)
                    .setNegativeButton("Close", null).show();
        });
    }

    private View requestRow(final String uid) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));

        ImageView av = new ImageView(this);
        row.addView(av, new LinearLayout.LayoutParams(dp(40), dp(40)));
        AvatarLoader.load(av, uid);

        TextView t = new TextView(this);
        t.setText(nameOf(uid));
        t.setTextSize(16);
        t.setTextColor(Color.parseColor("#111111"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = dp(10);
        row.addView(t, lp);

        TextView ok = smallBtn("Approve", true), no = smallBtn("Decline", false);
        row.addView(ok);
        row.addView(no);
        ok.setOnClickListener(v -> {
            ok.setOnClickListener(null); no.setOnClickListener(null);
            SupabaseRest.call("PATCH", "/rest/v1/group_members?group_id=eq." + gid + "&user_id=eq." + uid,
                    "{\"status\":\"active\"}", "return=representation", true, r -> {
                        if (isFinishing()) return;
                        JSONArray a = r.ok() ? arr(r.body) : null;
                        if (a == null || a.length() == 0) { toast(r.ok() ? "Permission denied" : SupabaseRest.errorText(r)); return; }
                        NotificationHelper.sendGroup(uid, "group_join_approved", gid,
                                "Your request to join \"" + gName + "\" was approved");
                        row.setVisibility(View.GONE);
                        loadMembers();
                    });
        });
        no.setOnClickListener(v -> {
            ok.setOnClickListener(null); no.setOnClickListener(null);
            SupabaseRest.call("DELETE", "/rest/v1/group_members?group_id=eq." + gid + "&user_id=eq." + uid
                    + "&status=eq.pending", null, "return=minimal", true, r -> {
                if (isFinishing()) return;
                if (!r.ok()) { toast(SupabaseRest.errorText(r)); return; }
                row.setVisibility(View.GONE);
                loadMembers();
            });
        });
        return row;
    }

    private TextView smallBtn(String label, boolean primary) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextSize(13);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setPadding(dp(10), dp(6), dp(10), dp(6));
        if (primary) { b.setBackgroundResource(R.drawable.bg_gp_btn_manage); b.setTextColor(Color.WHITE); }
        else { b.setTextColor(Color.parseColor("#65676B")); }
        return b;
    }

    // ------------------------------------------------------------------ সাহায্যকারী

    /** দরকারি uid-গুলোর নাম profiles থেকে এনে names-এ রাখে, তারপর done চালায় */
    private void fetchNames(List<String> uids, final Runnable done) {
        List<String> need = new ArrayList<>();
        for (String u : uids) if (u != null && !u.isEmpty() && !names.containsKey(u) && !need.contains(u)) need.add(u);
        if (need.isEmpty()) { if (done != null) done.run(); return; }
        final int CHUNK = 60;
        final int[] left = {(need.size() + CHUNK - 1) / CHUNK};
        for (int s = 0; s < need.size(); s += CHUNK) {
            StringBuilder in = new StringBuilder();
            for (int i = s; i < Math.min(need.size(), s + CHUNK); i++) {
                if (in.length() > 0) in.append(',');
                in.append(need.get(i));
            }
            SupabaseRest.call("GET", "/rest/v1/profiles?id=in.(" + in + ")&select=id,name", null, null, true, r -> {
                if (r.ok()) {
                    JSONArray a = arr(r.body);
                    for (int i = 0; a != null && i < a.length(); i++) {
                        JSONObject o = a.optJSONObject(i);
                        if (o != null) names.put(Db.s(o, "id"), Db.s(o, "name"));
                    }
                }
                if (--left[0] == 0 && done != null && !isFinishing()) done.run();
            });
        }
    }

    private static JSONArray arr(String body) {
        try { return new JSONArray(body); } catch (Exception e) { return null; }
    }

    private static JSONObject first(String body) {
        JSONArray a = arr(body);
        return a != null && a.length() > 0 ? a.optJSONObject(0) : null;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String m) { TopToast.show(this, m); }
}
