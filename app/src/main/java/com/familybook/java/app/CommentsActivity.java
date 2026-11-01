package com.familybook.app;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.format.DateUtils;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.PopupMenu;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/** কমেন্ট স্ক্রিন। সব ডেটা Supabase টেবিলে: comments, comment_reactions, comment_dislikes, post_reactions, post_shares। */
public class CommentsActivity extends AppCompatActivity {

    private static final int FB_BLUE = 0xFF1877F2;
    private static final int FB_RED = 0xFFE41E3F;
    private static final int FB_GRAY = 0xFF65676B;

    /** রিঅ্যাকশন পপআপে যে ৪টা অপশন দেখাবে (key = DB-তে সেভ হওয়া মান) */
    private static final String[] REACTION_KEYS = {"like", "love", "check", "stop", "angry", "haha", "wow"};

    /** একটি কমেন্ট বা রিপ্লাইয়ের তথ্য */
    private static class Row {
        String id;
        String parentId;
        String uid;
        String name;
        String text;
        String imageKey;          // কমেন্টে দেওয়া GIF (R2 key বা GIPHY লিংক)
        boolean hidden;
        long ts;
        final Map<String, String> likes = new LinkedHashMap<>();   // user uid -> reaction key
        final Set<String> dislikes = new HashSet<>();              // user uid
        final List<Row> replies = new ArrayList<>();
    }

    private String myUid;
    private String myName = "Family Member";
    private String postId;
    private String postOwner;
    private String postPerm = "public";
    private boolean canComment = false;
    private boolean scrollToEnd = false;      // নতুন কমেন্ট দিলে উপরে স্ক্রল (Newest সাজানো হয়)

    private static final int SORT_RELEVANT = 0, SORT_NEWEST = 1, SORT_ALL = 2;
    private int sortMode = SORT_RELEVANT;
    private TextView sortTv;
    private ImageView sendBtn;
    private final Set<String> following = new HashSet<>();
    private boolean followLoaded = false;

    private EditText input;
    private LinearLayout inputRow;

    // ---- @ মেনশন, ক্যামেরা/প্লাস প্যানেল
    private static final int PANEL_NONE = 0, PANEL_GALLERY = 1, PANEL_STICKER = 2;
    private int panelMode = PANEL_NONE;
    private LinearLayout toolRow;
    private ImageView camBtn, plusBtn;
    private FrameLayout panelHost;
    private ScrollView mentionBox;
    private LinearLayout mentionList;
    private CommentPanels panels;
    private View galleryView, stickerView;
    private boolean uploadingImage = false;
    private boolean wantFocus = false, wantAt = false, firstAllow = true;
    private final Map<String, String> mentions = new LinkedHashMap<>();   // "@নাম" -> uid  ("*" = সব ফলোয়ার)
    private int tokStart = -1;
    private boolean mentionEdit = false;
    private static final int MENTION_BLUE = 0xFF1877F2;
    private final androidx.activity.result.ActivityResultLauncher<String> photoPermLauncher =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.RequestPermission(), ok -> {
                if (panels != null) panels.refreshGallery();
            });
    private android.net.Uri cameraUri;
    private final androidx.activity.result.ActivityResultLauncher<android.net.Uri> cameraLauncher =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.TakePicture(), ok -> {
                if (ok && cameraUri != null) uploadImage(cameraUri);
            });
    private LinearLayout list;
    private TextView notice;
    private ScrollView scroll;
    private TextView replyBar;
    private String replyCid;
    private String replyName;
    private String replyPrefill;   // রিপ্লাইর শুরুতে বসানো "@Name "

    private LinearLayout postHeader;
    private LinearLayout postHeaderLeft;
    private TextView postHeaderShares;
    private final List<String> postReactionKeys = new ArrayList<>();
    private int postShareCount = 0;

    private final List<Row> topLevel = new ArrayList<>();
    private View reactionOverlay;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        myUid = Me.uid();
        postId = getIntent().getStringExtra("postId");
        if (!Me.isLoggedIn() || myUid == null || postId == null) {
            finish();
            return;
        }
        // নিচ থেকে ওঠা শিট: স্ক্রিনের ~৬৪%, উপরের অংশে পেছনের স্ক্রিন (ভিডিও/পোস্ট) দেখা যায়
        overridePendingTransition(R.anim.sheet_in, 0);
        android.view.Window w = getWindow();
        w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
        w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        w.addFlags(WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH);
        w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.round(getResources().getDisplayMetrics().heightPixels * 0.64f));
        w.setGravity(android.view.Gravity.BOTTOM);

        wantFocus = getIntent().getBooleanExtra("focus", false);
        wantAt = getIntent().getBooleanExtra("at", false);
        buildUi();
        MentionRepo.load(people -> { if (input != null && tokStart >= 0) refreshMentions(); });
        PostOps.loadMyName(myUid, new PostOps.NameCb() {
            @Override
            public void on(String n) {
                myName = n;
                if (replyCid == null && input != null) input.setHint(commentHint());
            }
        });
        loadPost();
        loadPostHeader();
        loadComments();
        loadFollowing();
    }

    /** শিটের বাইরে (উপরের ফাঁকা অংশে) ট্যাপ করলে বন্ধ */
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_OUTSIDE) {
            finish();
            return true;
        }
        return super.onTouchEvent(e);
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, R.anim.sheet_out);
    }

    private String commentHint() {
        return "Comment as " + myName;
    }

    /** আমি কাদের follow করি (কমেন্টে \"Follow\" দেখানো/লুকানোর জন্য) */
    private void loadFollowing() {
        SupabaseRest.call("GET", "/rest/v1/follows?follower_id=eq." + myUid + "&select=followee_id&limit=5000",
                null, null, true, r -> {
            if (isFinishing()) return;
            if (r.ok()) {
                JSONArray a = parseArray(r.body);
                if (a != null) {
                    for (int i = 0; i < a.length(); i++) {
                        JSONObject o = a.optJSONObject(i);
                        String f = str(o, "followee_id");
                        if (f != null) following.add(f);
                    }
                }
                followLoaded = true;
                render();
            }
        });
    }

    private void doFollow(final String target) {
        if (target == null || !Net.check(this)) return;
        try {
            JSONObject row = new JSONObject().put("follower_id", myUid).put("followee_id", target);
            following.add(target);
            render();
            SupabaseRest.call("POST", "/rest/v1/follows?on_conflict=follower_id,followee_id",
                    row.toString(), "resolution=merge-duplicates,return=minimal", true, r -> {
                if (isFinishing()) return;
                if (!r.ok()) {
                    following.remove(target);
                    render();
                    Toast.makeText(this, SupabaseRest.errorText(r), Toast.LENGTH_SHORT).show();
                    return;
                }
                NotificationHelper.send(target, "follow", null);
            });
        } catch (Exception e) {
            following.remove(target);
            render();
        }
    }

    // ---------- লোড ----------

    /** পোস্টের মালিক ও কমেন্ট করার অনুমতি */
    private void loadPost() {
        SupabaseRest.call("GET", "/rest/v1/posts?id=eq." + postId + "&select=user_id,comment_perm",
                null, null, true, r -> {
            if (isFinishing()) return;
            JSONArray a = parseArray(r.body);
            if (!r.ok() || a == null || a.length() == 0) {
                allow(false, "Could not check comment settings.");
                return;
            }
            JSONObject o = a.optJSONObject(0);
            postOwner = str(o, "user_id");
            String perm = str(o, "comment_perm");
            postPerm = perm == null ? "public" : perm;
            checkPermission();
            render();
        });
    }

    private void checkPermission() {
        if (myUid.equals(postOwner) || "public".equals(postPerm)) {
            allow(true, null);
            return;
        }
        if (!"friends".equals(postPerm) || postOwner == null) {
            allow(false, "Comments are turned off for this post.");
            return;
        }
        SupabaseRest.call("GET",
                "/rest/v1/friendships?user_id=eq." + myUid + "&friend_id=eq." + postOwner + "&select=friend_id",
                null, null, true, r -> {
            if (isFinishing()) return;
            JSONArray a = parseArray(r.body);
            boolean friend = r.ok() && a != null && a.length() > 0;
            allow(friend, "Only friends of the post owner can comment.");
        });
    }

    /** পোস্টের রিঅ্যাকশন ও শেয়ার সংখ্যা (উপরের সারাংশ) */
    private void loadPostHeader() {
        SupabaseRest.call("GET", "/rest/v1/post_reactions?post_id=eq." + postId + "&select=user_id,reaction",
                null, null, true, r -> {
            if (isFinishing()) return;
            postReactionKeys.clear();
            JSONArray a = parseArray(r.body);
            if (r.ok() && a != null) {
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o == null) continue;
                    postReactionKeys.add(ReactionKit.normalize(str(o, "reaction")));
                }
            }
            SupabaseRest.call("GET", "/rest/v1/post_shares?post_id=eq." + postId + "&select=user_id",
                    null, null, true, r2 -> {
                if (isFinishing()) return;
                JSONArray s = parseArray(r2.body);
                postShareCount = (r2.ok() && s != null) ? s.length() : 0;
                refreshPostHeader();
            });
            refreshPostHeader();
        });
    }

    /** সব কমেন্ট, তারপর তাদের রিঅ্যাকশন ও ডিসলাইক */
    private void loadComments() {
        SupabaseRest.call("GET",
                "/rest/v1/comments?post_id=eq." + postId + "&select=*&order=created_at.asc&limit=2000",
                null, null, true, r -> {
            if (isFinishing()) return;
            if (!r.ok()) {
                Toast.makeText(this, SupabaseRest.errorText(r), Toast.LENGTH_SHORT).show();
                return;
            }
            JSONArray a = parseArray(r.body);
            List<Row> all = new ArrayList<>();
            Map<String, Row> byId = new HashMap<>();
            if (a != null) {
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o == null) continue;
                    Row row = new Row();
                    row.id = str(o, "id");
                    row.parentId = str(o, "parent_id");
                    row.uid = str(o, "user_id");
                    row.name = str(o, "author_name");
                    row.text = str(o, "text");
                    row.imageKey = str(o, "image_key");
                    row.hidden = o.optBoolean("hidden", false);
                    row.ts = parseTs(str(o, "created_at"));
                    if (row.id != null) {
                        all.add(row);
                        byId.put(row.id, row);
                    }
                }
            }
            loadReactions(all, byId);
        });
    }

    private void loadReactions(final List<Row> all, final Map<String, Row> byId) {
        if (all.isEmpty()) {
            setRows(all, byId);
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (Row c : all) {
            if (sb.length() > 0) sb.append(',');
            sb.append(c.id);
        }
        final String inList = "(" + sb + ")";
        SupabaseRest.call("GET",
                "/rest/v1/comment_reactions?comment_id=in." + inList + "&select=comment_id,user_id,reaction",
                null, null, true, r1 -> {
            if (isFinishing()) return;
            if (r1.ok()) {
                JSONArray a = parseArray(r1.body);
                if (a != null) {
                    for (int i = 0; i < a.length(); i++) {
                        JSONObject o = a.optJSONObject(i);
                        if (o == null) continue;
                        Row c = byId.get(str(o, "comment_id"));
                        String uid = str(o, "user_id");
                        if (c != null && uid != null) {
                            String v = str(o, "reaction");
                            c.likes.put(uid, isLikeKey(v) ? "like" : v);
                        }
                    }
                }
            }
            SupabaseRest.call("GET",
                    "/rest/v1/comment_dislikes?comment_id=in." + inList + "&select=comment_id,user_id",
                    null, null, true, r2 -> {
                if (isFinishing()) return;
                if (r2.ok()) {
                    JSONArray a = parseArray(r2.body);
                    if (a != null) {
                        for (int i = 0; i < a.length(); i++) {
                            JSONObject o = a.optJSONObject(i);
                            if (o == null) continue;
                            Row c = byId.get(str(o, "comment_id"));
                            String uid = str(o, "user_id");
                            if (c != null && uid != null) c.dislikes.add(uid);
                        }
                    }
                }
                setRows(all, byId);
            });
        });
    }

    /** সারিগুলোকে উপরের কমেন্ট ও তাদের রিপ্লাইয়ে ভাগ করে render করে */
    private void setRows(List<Row> all, Map<String, Row> byId) {
        topLevel.clear();
        for (Row row : all) {
            if (row.parentId == null) {
                topLevel.add(row);
            } else {
                Row parent = byId.get(row.parentId);
                if (parent != null) parent.replies.add(row);
            }
        }
        render();
    }

    // ---------- কাজ (লেখা) ----------

    // ---------- কমেন্টে GIF ----------
    private String pendingGif = null;
    private ImageView gifChip;
    private GifLibrary gifLib;
    private final androidx.activity.result.ActivityResultLauncher<String> gifFileLauncher =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.GetContent(), uri -> {
                if (uri != null && gifLib != null) gifLib.onFilePicked(uri);
            });

    private void openGifPicker() {
        if (!canComment) return;
        if (gifLib == null) {
            gifLib = new GifLibrary(this, new GifLibrary.Host() {
                @Override public void pickFile() { gifFileLauncher.launch("image/gif"); }
                @Override public void onPick(android.net.Uri gif) { }
                @Override public boolean keyOnly() { return true; }
                @Override public void onPickKey(String key) { setPendingGif(key); }
            });
        }
        gifLib.show();
    }

    private void setPendingGif(String key) {
        pendingGif = key;
        if (gifChip != null) {
            if (key == null) {
                gifChip.setVisibility(View.GONE);
            } else {
                gifChip.setVisibility(View.VISIBLE);
                gifChip.setTag(key);
                PostImage.showGif(gifChip, key, key);       // ছোট অ্যানিমেটেড প্রিভিউ; ট্যাপ করলে বাদ
            }
        }
        updateSendVisibility();
    }

    private void updateSendVisibility() {
        if (sendBtn == null || input == null) return;
        boolean any = !input.getText().toString().trim().isEmpty() || pendingGif != null;
        sendBtn.setVisibility(any ? View.VISIBLE : View.INVISIBLE);
        sendBtn.setAlpha(uploadingImage ? 0.4f : 1f);
        updateToolRow();
    }

    private void sendComment() {
        final String t = input.getText().toString().trim();
        final String gif = pendingGif;
        if ((t.isEmpty() && gif == null) || !canComment || uploadingImage) return;
        final String parent = replyCid;
        // শুধু "@Name" থাকলে (কিছু লেখা হয়নি) পাঠানো হবে না
        if (gif == null && parent != null && replyPrefill != null && t.equals(replyPrefill.trim())) return;
        try {
            JSONObject row = new JSONObject()
                    .put("post_id", postId)
                    .put("user_id", myUid)
                    .put("author_name", myName)
                    .put("text", t);
            if (parent != null) row.put("parent_id", parent);
            if (gif != null) row.put("image_key", gif);
            if (parent == null) { scrollToEnd = true; sortMode = SORT_NEWEST; updateSortLabel(); }
            SupabaseRest.call("POST", "/rest/v1/comments", row.toString(), "return=minimal", true, r -> {
                if (isFinishing()) return;
                if (!r.ok()) {
                    Toast.makeText(this, SupabaseRest.errorText(r), Toast.LENGTH_SHORT).show();
                    return;
                }
                NotificationHelper.send(postOwner, "comment", postId);
                notifyMentions(t);
                input.setText("");
                setPendingGif(null);
                mentions.clear();
                closePanel(false);
                clearReply();
                loadComments();
            });
        } catch (Exception e) {
            Toast.makeText(this, "কমেন্ট পাঠানো যায়নি", Toast.LENGTH_SHORT).show();
        }
    }

    /** কমেন্টের রিঅ্যাকশন বদলানো। key == null হলে রিঅ্যাকশন তুলে নেওয়া */
    private void setReaction(final String commentId, final String key) {
        if (key == null) {
            SupabaseRest.call("DELETE",
                    "/rest/v1/comment_reactions?comment_id=eq." + commentId + "&user_id=eq." + myUid,
                    null, "return=minimal", true, r -> {
                if (isFinishing()) return;
                if (!r.ok()) Toast.makeText(this, SupabaseRest.errorText(r), Toast.LENGTH_SHORT).show();
                loadComments();
            });
            return;
        }
        try {
            JSONObject row = new JSONObject()
                    .put("comment_id", commentId)
                    .put("user_id", myUid)
                    .put("reaction", key);
            SupabaseRest.call("POST", "/rest/v1/comment_reactions?on_conflict=comment_id,user_id",
                    row.toString(), "resolution=merge-duplicates,return=minimal", true, r -> {
                if (isFinishing()) return;
                if (!r.ok()) Toast.makeText(this, SupabaseRest.errorText(r), Toast.LENGTH_SHORT).show();
                // লাইক দিলে আগের Unlike সরে যায়
                SupabaseRest.call("DELETE",
                        "/rest/v1/comment_dislikes?comment_id=eq." + commentId + "&user_id=eq." + myUid,
                        null, "return=minimal", true, x -> loadComments());
            });
        } catch (Exception e) {
            Toast.makeText(this, "রিঅ্যাকশন সেভ হয়নি", Toast.LENGTH_SHORT).show();
        }
    }

    /** Unlike চালু/বন্ধ। চালু করলে লাইক সরে যায় */
    private void setDislike(final String commentId, boolean on, final boolean wasLiked) {
        if (!on) {
            SupabaseRest.call("DELETE",
                    "/rest/v1/comment_dislikes?comment_id=eq." + commentId + "&user_id=eq." + myUid,
                    null, "return=minimal", true, r -> {
                if (isFinishing()) return;
                if (!r.ok()) Toast.makeText(this, SupabaseRest.errorText(r), Toast.LENGTH_SHORT).show();
                loadComments();
            });
            return;
        }
        try {
            JSONObject row = new JSONObject()
                    .put("comment_id", commentId)
                    .put("user_id", myUid);
            SupabaseRest.call("POST", "/rest/v1/comment_dislikes?on_conflict=comment_id,user_id",
                    row.toString(), "resolution=merge-duplicates,return=minimal", true, r -> {
                if (isFinishing()) return;
                if (!r.ok()) Toast.makeText(this, SupabaseRest.errorText(r), Toast.LENGTH_SHORT).show();
                if (wasLiked) {
                    SupabaseRest.call("DELETE",
                            "/rest/v1/comment_reactions?comment_id=eq." + commentId + "&user_id=eq." + myUid,
                            null, "return=minimal", true, x -> loadComments());
                } else {
                    loadComments();
                }
            });
        } catch (Exception e) {
            Toast.makeText(this, "সেভ হয়নি", Toast.LENGTH_SHORT).show();
        }
    }

    /** owner কমেন্ট লুকাতে/দেখাতে পারবে */
    private void hideComment(final String commentId, boolean hide) {
        SupabaseRest.call("PATCH", "/rest/v1/comments?id=eq." + commentId,
                "{\"hidden\":" + hide + "}", "return=minimal", true, r -> {
            if (isFinishing()) return;
            if (!r.ok()) Toast.makeText(this, SupabaseRest.errorText(r), Toast.LENGTH_SHORT).show();
            loadComments();
        });
    }

    // ---------- UI: উপরের বার ও পোস্ট সারাংশ ----------

    private void refreshPostHeader() {
        if (postHeader == null) return;
        int likes = postReactionKeys.size();
        if (likes == 0 && postShareCount == 0) {
            postHeader.setVisibility(8);
            return;
        }
        postHeader.setVisibility(0);
        postHeaderLeft.removeAllViews();
        if (likes > 0) {
            final Map<String, Integer> counts = new LinkedHashMap<>();
            for (String k : postReactionKeys) {
                Integer old = counts.get(k);
                counts.put(k, old == null ? 1 : old + 1);
            }
            List<String> keys = new ArrayList<>(counts.keySet());
            Collections.sort(keys, new Comparator<String>() {
                @Override
                public int compare(String a, String b) {
                    return counts.get(b) - counts.get(a);
                }
            });
            for (int i = 0; i < keys.size() && i < 3; i++) {
                ImageView iv = new ImageView(this);
                setSummaryIcon(iv, keys.get(i));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(20), dp(20));
                if (i > 0) lp.leftMargin = -dp(4);
                postHeaderLeft.addView(iv, lp);
            }
            TextView count = new TextView(this);
            count.setText(String.valueOf(likes));
            count.setTextSize(15.0f);
            count.setTextColor(FB_GRAY);
            count.setPadding(dp(6), 0, 0, 0);
            postHeaderLeft.addView(count);
        }
        postHeaderShares.setText(postShareCount > 0
                ? postShareCount + (postShareCount == 1 ? " share" : " shares") : "");
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable sheetBg = new GradientDrawable();
        sheetBg.setColor(0xFFFFFFFF);
        float rad = dp(28);
        sheetBg.setCornerRadii(new float[]{rad, rad, rad, rad, 0f, 0f, 0f, 0f});
        root.setBackground(sheetBg);
        root.setClipToOutline(true);

        // ---- ধরার দাগ (handle)
        View handle = new View(this);
        GradientDrawable hb = new GradientDrawable();
        hb.setColor(0xFF8A8D91);
        hb.setCornerRadius(dp(3));
        handle.setBackground(hb);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(40), dp(5));
        hp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        hp.topMargin = dp(10);
        root.addView(handle, hp);

        // ---- "Most relevant ▾" (বাঁয়ে) + রিঅ্যাকশন/শেয়ার সারাংশ (ডানে)
        LinearLayout sortRow = new LinearLayout(this);
        sortRow.setOrientation(LinearLayout.HORIZONTAL);
        sortRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        sortRow.setPadding(dp(16), dp(20), dp(16), dp(8));
        sortTv = new TextView(this);
        sortTv.setTextSize(20.0f);
        sortTv.setTextColor(0xFF050505);
        sortTv.setTypeface(null, android.graphics.Typeface.BOLD);
        sortTv.setOnClickListener(v -> showSortMenu());
        updateSortLabel();
        sortRow.addView(sortTv, new LinearLayout.LayoutParams(0, -2, 1.0f));

        postHeader = new LinearLayout(this);
        postHeader.setOrientation(LinearLayout.HORIZONTAL);
        postHeader.setGravity(android.view.Gravity.CENTER_VERTICAL);
        postHeader.setVisibility(8);
        postHeaderLeft = new LinearLayout(this);
        postHeaderLeft.setOrientation(LinearLayout.HORIZONTAL);
        postHeaderLeft.setGravity(android.view.Gravity.CENTER_VERTICAL);
        postHeaderLeft.setOnClickListener(v -> {
            Intent i = new Intent(CommentsActivity.this, ReactionsActivity.class);
            i.putExtra("postId", postId);
            startActivity(i);
        });
        postHeader.addView(postHeaderLeft, new LinearLayout.LayoutParams(-2, -2));
        postHeaderShares = new TextView(this);
        postHeaderShares.setTextSize(14.0f);
        postHeaderShares.setTextColor(FB_GRAY);
        postHeaderShares.setPadding(dp(8), 0, 0, 0);
        postHeader.addView(postHeaderShares);
        sortRow.addView(postHeader, new LinearLayout.LayoutParams(-2, -2));
        root.addView(sortRow, new LinearLayout.LayoutParams(-1, -2));

        // ---- কমেন্টের তালিকা
        scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFFFFFFFF);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(14), dp(6), dp(14), dp(12));
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1.0f));

        notice = new TextView(this);
        notice.setTextColor(0xFFFFFFFF);
        notice.setBackgroundColor(0xFF606770);
        notice.setPadding(dp(16), dp(14), dp(16), dp(14));
        notice.setVisibility(8);
        root.addView(notice);

        replyBar = new TextView(this);
        replyBar.setTextColor(0xFFFFFFFF);
        replyBar.setBackgroundColor(0xFF606770);
        replyBar.setPadding(dp(16), dp(8), dp(16), dp(8));
        replyBar.setVisibility(8);
        replyBar.setOnClickListener(v -> clearReply());
        root.addView(replyBar);

        View divider = new View(this);
        divider.setBackgroundColor(0xFFE4E6EB);
        root.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));

        // ---- "@" মেনশন তালিকা (ইনপুটের ঠিক উপরে ভেসে ওঠে)
        mentionBox = new ScrollView(this);
        mentionBox.setBackgroundColor(0xFFFFFFFF);
        mentionBox.setVisibility(View.GONE);
        mentionList = new LinearLayout(this);
        mentionList.setOrientation(LinearLayout.VERTICAL);
        mentionBox.addView(mentionList);
        root.addView(mentionBox, new LinearLayout.LayoutParams(-1, dp(250)));

        // ---- নিচের "Comment as <নাম>" বক্স + (কিবোর্ডের সাথে) ক্যামেরা / গোল-প্লাস বাটন + প্যানেল
        inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.VERTICAL);
        inputRow.setBackgroundColor(0xFFFFFFFF);
        inputRow.setPadding(dp(12), dp(10), dp(12), dp(6));
        inputRow.setVisibility(8);

        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(android.view.Gravity.CENTER_VERTICAL);
        GradientDrawable pb = new GradientDrawable();
        pb.setColor(0xFFF0F2F5);
        pb.setCornerRadius(dp(28));
        pill.setBackground(pb);
        pill.setPadding(dp(20), dp(2), dp(14), dp(2));

        gifChip = new ImageView(this);
        gifChip.setScaleType(ImageView.ScaleType.CENTER_CROP);
        gifChip.setVisibility(View.GONE);
        gifChip.setOnClickListener(v -> { if (!uploadingImage) setPendingGif(null); });
        LinearLayout.LayoutParams gcp = new LinearLayout.LayoutParams(dp(44), dp(44));
        gcp.setMarginEnd(dp(8));
        pill.addView(gifChip, gcp);

        input = new EditText(this);
        input.setBackground(null);
        input.setHint(commentHint());
        input.setHintTextColor(0xFF65676B);
        input.setTextColor(0xFF050505);
        input.setTextSize(19.0f);
        input.setMaxLines(4);
        input.setMinHeight(dp(52));
        input.setPadding(0, dp(8), 0, dp(8));
        pill.addView(input, new LinearLayout.LayoutParams(0, -2, 1.0f));
        input.setOnClickListener(v -> { if (panelMode != PANEL_NONE) closePanel(true); });
        input.setOnFocusChangeListener((v, has) -> {
            if (has && panelMode != PANEL_NONE) closePanel(false);
            updateToolRow();
        });
        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable e) {
                if (mentionEdit) return;
                updateSendVisibility();
                styleInputMentions(e);
                checkMentionToken();
            }
        });
        inputRow.addView(pill, new LinearLayout.LayoutParams(-1, -2));

        // কিবোর্ড খুললে বক্সের নিচে: Camera + গোল Plus ......... Send
        toolRow = new LinearLayout(this);
        toolRow.setOrientation(LinearLayout.HORIZONTAL);
        toolRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        toolRow.setPadding(dp(4), dp(6), dp(4), dp(2));
        toolRow.setVisibility(View.GONE);
        camBtn = new ImageView(this);
        camBtn.setImageResource(R.drawable.ic_cm_camera);
        camBtn.setPadding(dp(5), dp(5), dp(5), dp(5));
        camBtn.setOnClickListener(v -> togglePanel(PANEL_GALLERY));
        toolRow.addView(camBtn, new LinearLayout.LayoutParams(dp(40), dp(40)));
        plusBtn = new ImageView(this);
        plusBtn.setImageResource(R.drawable.ic_cm_plus_circle);
        plusBtn.setPadding(dp(5), dp(5), dp(5), dp(5));
        plusBtn.setOnClickListener(v -> togglePanel(PANEL_STICKER));
        LinearLayout.LayoutParams pbp = new LinearLayout.LayoutParams(dp(40), dp(40));
        pbp.setMarginStart(dp(10));
        toolRow.addView(plusBtn, pbp);
        toolRow.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        sendBtn = new ImageView(this);
        sendBtn.setImageResource(R.drawable.ic_cm_send);
        sendBtn.setColorFilter(FB_BLUE);
        sendBtn.setPadding(dp(6), dp(6), dp(6), dp(6));
        sendBtn.setVisibility(View.INVISIBLE);
        sendBtn.setOnClickListener(v -> sendComment());
        toolRow.addView(sendBtn, new LinearLayout.LayoutParams(dp(42), dp(42)));
        inputRow.addView(toolRow, new LinearLayout.LayoutParams(-1, -2));
        styleToolIcons();

        panelHost = new FrameLayout(this);
        panelHost.setVisibility(View.GONE);
        inputRow.addView(panelHost, new LinearLayout.LayoutParams(-1, dp(CommentPanels.PANEL_DP)));
        root.addView(inputRow);
        setContentView(root);
    }

    // ---------- সাজানো: Most relevant / Newest / All comments ----------

    private void updateSortLabel() {
        if (sortTv == null) return;
        String t = sortMode == SORT_NEWEST ? "Newest" : (sortMode == SORT_ALL ? "All comments" : "Most relevant");
        sortTv.setText(t + " \u25BE");
    }

    private void showSortMenu() {
        PopupMenu pm = new PopupMenu(this, sortTv);
        pm.getMenu().add(0, SORT_RELEVANT, 0, "Most relevant");
        pm.getMenu().add(0, SORT_NEWEST, 1, "Newest");
        pm.getMenu().add(0, SORT_ALL, 2, "All comments");
        pm.setOnMenuItemClickListener(it -> {
            sortMode = it.getItemId();
            updateSortLabel();
            scrollToEnd = true;
            render();
            return true;
        });
        pm.show();
    }

    private List<Row> sortedTop() {
        List<Row> l = new ArrayList<>(topLevel);
        Collections.sort(l, new Comparator<Row>() {
            @Override
            public int compare(Row a, Row b) {
                if (sortMode == SORT_ALL) return Long.compare(a.ts, b.ts);
                if (sortMode == SORT_NEWEST) return Long.compare(b.ts, a.ts);
                int sa = a.likes.size() * 2 + a.replies.size() - a.dislikes.size();
                int sb = b.likes.size() * 2 + b.replies.size() - b.dislikes.size();
                if (sa != sb) return sb - sa;
                return Long.compare(b.ts, a.ts);
            }
        });
        return l;
    }

    private void allow(boolean ok, String msg) {
        canComment = ok;
        inputRow.setVisibility(ok ? View.VISIBLE : View.GONE);
        if (ok && firstAllow) {
            firstAllow = false;
            if (wantAt) {
                input.setText("@");
                input.setSelection(1);
            }
            if (wantFocus || wantAt) {
                input.postDelayed(() -> {
                    input.requestFocus();
                    android.view.inputmethod.InputMethodManager imm =
                            (android.view.inputmethod.InputMethodManager) getSystemService("input_method");
                    if (imm != null) imm.showSoftInput(input, 1);
                }, 250);
            }
        }
        notice.setVisibility(ok ? View.GONE : View.VISIBLE);
        if (!ok && msg != null) notice.setText(msg);
    }

    private void startReply(String cid, String name) {
        if (!canComment) {
            Toast.makeText(this, notice.getText(), Toast.LENGTH_SHORT).show();
            return;
        }
        replyCid = cid;
        replyName = name == null ? "" : name;
        replyBar.setText("Replying to " + replyName + "   ✕ cancel");
        replyBar.setVisibility(View.VISIBLE);
        input.setHint("Reply to " + replyName + "...");
        // রিপ্লাইর শুরুতে @Name বসে যায় (ফেসবুকের মতো)
        String pre = replyName.isEmpty() ? "" : "@" + replyName + " ";
        String cur = input.getText().toString();
        if (cur.isEmpty() || cur.equals(replyPrefill)) {
            input.setText(pre);
            input.setSelection(pre.length());
        }
        replyPrefill = pre;
        input.requestFocus();
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService("input_method");
        if (imm != null) imm.showSoftInput(input, 1);
    }

    private void clearReply() {
        if (replyPrefill != null && input.getText().toString().equals(replyPrefill)) input.setText("");
        replyPrefill = null;
        replyCid = null;
        replyName = null;
        replyBar.setVisibility(View.GONE);
        input.setHint(commentHint());
    }

    // ---------- রিঅ্যাকশন helper ----------

    private static boolean isLikeKey(String key) {
        return key == null || "like".equals(key) || "true".equals(key);
    }

    private void playOpenSound() {
        playRawSound(R.raw.react_open);
    }

    private void playReactSound(String key) {
        ReactionKit.playFor(this, postId, key);
    }

    private void playRawSound(int resId) {
        try {
            final MediaPlayer mp = MediaPlayer.create(this, resId);
            if (mp == null) return;
            mp.setOnCompletionListener(player -> player.release());
            mp.start();
        } catch (Exception e) {
            // সাউন্ড বাজাতে না পারলেও রিঅ্যাকশন সেভ হবে
        }
    }

    /** কমেন্টের উপরে রিঅ্যাকশন সারাংশ। ক্লিক করলে কে কী দিয়েছে দেখাবে */
    private LinearLayout reactionSummary(final Row r) {
        int likeCount = r.likes.size();
        int dislikeCount = r.dislikes.size();
        if (likeCount + dislikeCount == 0) return null;

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(6), dp(2), dp(6), dp(2));
        Set<String> kinds = new java.util.LinkedHashSet<>(r.likes.values());
        if (dislikeCount > 0) kinds.add("unlike");
        int shown = 0;
        for (String k : kinds) {
            if (shown++ >= 3) break;
            ImageView iv = new ImageView(this);
            setSummaryIcon(iv, k);
            row.addView(iv, new LinearLayout.LayoutParams(dp(18), dp(18)));
        }
        TextView t = new TextView(this);
        t.setText(String.valueOf(likeCount + dislikeCount));
        t.setTextSize(15.0f);
        t.setTextColor(FB_GRAY);
        t.setPadding(dp(6), 0, 0, 0);
        row.addView(t);
        row.setOnClickListener(v -> {
            Intent i = new Intent(CommentsActivity.this, ReactionsActivity.class);
            i.putExtra("postId", postId);
            i.putExtra("commentId", r.id);
            startActivity(i);
        });
        return row;
    }

    private void dismissReactionPicker() {
        if (reactionOverlay != null) {
            ViewGroup parent = (ViewGroup) reactionOverlay.getParent();
            if (parent != null) parent.removeView(reactionOverlay);
            reactionOverlay = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (reactionOverlay != null) {
            dismissReactionPicker();
        } else if (mentionBox != null && mentionBox.getVisibility() == View.VISIBLE) {
            hideMentions();
        } else if (panelMode != PANEL_NONE) {
            closePanel(false);
        } else {
            super.onBackPressed();
        }
    }

    /** লম্বা চাপ দিলে রিঅ্যাকশন বার */
    private void showReactionPicker(final View anchor, final String commentId) {
        dismissReactionPicker();
        playOpenSound();
        final FrameLayout overlay = new FrameLayout(this);
        overlay.setClickable(true);
        overlay.setOnClickListener(v -> dismissReactionPicker());

        final LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(6), dp(4), dp(6), dp(4));
        bar.setElevation((float) dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFFFFFFF);
        bg.setCornerRadius((float) dp(24));
        bg.setStroke(dp(1), 0xFFE0E0E0);
        bar.setBackground(bg);
        bar.setVisibility(View.INVISIBLE);

        for (final String key : REACTION_KEYS) {
            ImageView iv = new ImageView(this);
            ReactionKit.setIcon(iv, key);
            iv.setPadding(dp(3), dp(3), dp(3), dp(3));
            iv.setOnClickListener(v -> {
                dismissReactionPicker();
                playReactSound(key);
                setReaction(commentId, key);
            });
            bar.addView(iv, new LinearLayout.LayoutParams(dp(38), dp(38)));
        }
        overlay.addView(bar, new FrameLayout.LayoutParams(-2, -2));
        reactionOverlay = overlay;
        try {
            addContentView(overlay, new ViewGroup.LayoutParams(-1, -1));
        } catch (Exception e) {
            reactionOverlay = null;
            Toast.makeText(this, "Reaction error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            return;
        }
        overlay.post(() -> {
            int[] a = new int[2];
            int[] o = new int[2];
            anchor.getLocationOnScreen(a);
            overlay.getLocationOnScreen(o);
            int w = bar.getWidth();
            int h = bar.getHeight();
            int x = Math.max(dp(8), Math.min(a[0] - o[0], overlay.getWidth() - w - dp(8)));
            int y = (a[1] - o[1]) - h - dp(8);
            if (y < dp(8)) y = (a[1] - o[1]) + anchor.getHeight() + dp(8);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) bar.getLayoutParams();
            lp.leftMargin = x;
            lp.topMargin = y;
            bar.setLayoutParams(lp);
            bar.setVisibility(View.VISIBLE);
        });
    }

    private LinearLayout iconButton(int iconRes, int tint, String count, View.OnClickListener l) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(android.view.Gravity.CENTER_VERTICAL);
        b.setPadding(dp(6), dp(4), dp(6), dp(4));
        ImageView iv = new ImageView(this);
        iv.setImageResource(iconRes);
        if (tint != 0) iv.setColorFilter(tint);
        b.addView(iv, new LinearLayout.LayoutParams(dp(15), dp(15)));
        if (count != null) {
            TextView t = new TextView(this);
            t.setText(count);
            t.setTextSize(12.0f);
            t.setTextColor(tint == 0 ? FB_GRAY : tint);
            t.setPadding(dp(4), 0, 0, 0);
            b.addView(t);
        }
        b.setOnClickListener(l);
        return b;
    }

    private TextView textButton(String label, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(12.0f);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setTextColor(FB_GRAY);
        t.setPadding(dp(6), dp(4), dp(6), dp(4));
        t.setOnClickListener(l);
        return t;
    }

    private LinearLayout.LayoutParams gapLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = dp(4);
        return lp;
    }

    private void shareComment(String name, String text) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, (name == null ? "" : name) + ": " + (text == null ? "" : text));
        startActivity(Intent.createChooser(i, "Share via"));
    }

    /** Like → নীল গোল আইকন (সাদা থাম্ব); বাকি রিঅ্যাকশন নিজের আইকন */
    private void setSummaryIcon(ImageView iv, String key) {
        if (key == null || "like".equals(key)) {
            iv.setImageResource(R.drawable.ic_like_circle);
            iv.clearColorFilter();
        } else {
            ReactionKit.setIcon(iv, key);
        }
    }

    /** ১ম লাইনের সময়: 4w, 3d, 5h, 12m */
    private static String ago(long ts) {
        long s = Math.max(0, (System.currentTimeMillis() - ts) / 1000);
        if (s < 60) return "now";
        long m = s / 60;
        if (m < 60) return m + "m";
        long h = m / 60;
        if (h < 24) return h + "h";
        long d = h / 24;
        if (d < 7) return d + "d";
        long w = d / 7;
        if (w < 52) return w + "w";
        return (d / 365) + "y";
    }

    /** লাইক বাটনে ট্যাপ = লাইক/আনলাইক, চেপে ধরা = রিঅ্যাকশন বার */
    private void attachLike(final View likeBtn, final Row r, final boolean liked) {
        final Handler pressHandler = new Handler(Looper.getMainLooper());
        final boolean[] fired = {false};
        final float[] downXY = new float[2];
        final Runnable pressRun = () -> {
            fired[0] = true;
            if (likeBtn.getParent() != null) {
                likeBtn.getParent().requestDisallowInterceptTouchEvent(true);
            }
            likeBtn.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            showReactionPicker(likeBtn, r.id);
        };
        likeBtn.setOnClickListener(v -> setReaction(r.id, liked ? null : "like"));
        likeBtn.setOnTouchListener((v, e) -> {
            int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                fired[0] = false;
                downXY[0] = e.getRawX();
                downXY[1] = e.getRawY();
                pressHandler.postDelayed(pressRun, 350);
                return false;
            }
            if (action == MotionEvent.ACTION_MOVE) {
                if (!fired[0] && (Math.abs(e.getRawX() - downXY[0]) > dp(12)
                        || Math.abs(e.getRawY() - downXY[1]) > dp(12))) {
                    pressHandler.removeCallbacks(pressRun);
                }
                return fired[0];
            }
            if (action == MotionEvent.ACTION_UP) {
                pressHandler.removeCallbacks(pressRun);
                if (fired[0]) {
                    fired[0] = false;
                    return true;
                }
                return false;
            }
            if (action == MotionEvent.ACTION_CANCEL) {
                pressHandler.removeCallbacks(pressRun);
            }
            return false;
        });
    }

    /** একটা কমেন্ট/রিপ্লাই সারি (ছবির মতো): বাঁয়ে ছবি; ডানে নাম · সময়, লেখা, নিচে Reply + রিঅ্যাকশন সারাংশ ... 👍 👎 */
    private View commentRow(final Row r, final String replyTarget, final boolean isReply,
                            final Row parent, final boolean iOwn) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(10), 0, dp(10));

        int av = isReply ? 36 : 48;
        ImageView avatar = new ImageView(this);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        AvatarLoader.load(avatar, r.uid);
        avatar.setOnClickListener(v -> ProfileActivity.open(CommentsActivity.this, r.uid, r.name));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(av), dp(av));
        alp.topMargin = dp(2);
        row.addView(avatar, alp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, -2, 1.0f);
        clp.setMarginStart(dp(12));
        row.addView(col, clp);

        // নাম (বোল্ড) · সময় · Follow — এক TextView-তে, জায়গা না থাকলে নিজে থেকে নিচের লাইনে নামে
        SpannableStringBuilder head = new SpannableStringBuilder();
        String nm = r.name == null ? "" : r.name;
        int s0 = head.length();
        head.append(nm);
        head.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), s0, head.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        head.setSpan(new ForegroundColorSpan(0xFF050505), s0, head.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        head.setSpan(new ClickableSpan() {
            @Override public void onClick(View w) { ProfileActivity.open(CommentsActivity.this, r.uid, r.name); }
            @Override public void updateDrawState(android.text.TextPaint p) { p.setUnderlineText(false); }
        }, s0, head.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        int s1 = head.length();
        head.append(" \u00B7 ").append(ago(r.ts));
        if (r.hidden) head.append(" \u00B7 Hidden");
        head.setSpan(new ForegroundColorSpan(0xFF65676B), s1, head.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        boolean showFollow = followLoaded && r.uid != null && !r.uid.equals(myUid) && !following.contains(r.uid);
        if (showFollow) {
            int s2 = head.length();
            head.append(" \u00B7 ");
            head.setSpan(new ForegroundColorSpan(0xFF65676B), s2, head.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            int s3 = head.length();
            head.append("Follow");
            head.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), s3, head.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            head.setSpan(new ForegroundColorSpan(FB_BLUE), s3, head.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            head.setSpan(new ClickableSpan() {
                @Override public void onClick(View w) { doFollow(r.uid); }
                @Override public void updateDrawState(android.text.TextPaint p) { p.setUnderlineText(false); }
            }, s3, head.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        TextView headTv = new TextView(this);
        headTv.setText(head);
        headTv.setTextSize(15.0f);
        headTv.setMovementMethod(LinkMovementMethod.getInstance());
        headTv.setHighlightColor(0);
        col.addView(headTv, new LinearLayout.LayoutParams(-1, -2));

        TextView body = new TextView(this);
        body.setText(isReply ? mention(r.text, parent, r) : styleMentions(r.text));
        body.setTextColor(0xFF050505);
        body.setTextSize(19.0f);
        body.setPadding(0, dp(2), 0, 0);
        body.setOnLongClickListener(v -> { showCommentMenu(v, r, iOwn); return true; });
        if (r.imageKey != null && !r.imageKey.isEmpty() && (r.text == null || r.text.trim().isEmpty())) {
            body.setVisibility(View.GONE);
        }
        col.addView(body, new LinearLayout.LayoutParams(-1, -2));

        // কমেন্টে দেওয়া GIF (অ্যানিমেটেড, নিজের অনুপাতে)
        if (r.imageKey != null && !r.imageKey.isEmpty()) {
            ImageView gi = new ImageView(this);
            gi.setAdjustViewBounds(true);
            gi.setScaleType(ImageView.ScaleType.FIT_CENTER);
            gi.setMaxWidth(dp(240));
            gi.setMaxHeight(dp(320));
            gi.setTag(r.imageKey);
            gi.setOnLongClickListener(v -> { showCommentMenu(v, r, iOwn); return true; });
            LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-2, -2);
            glp.topMargin = dp(6);
            col.addView(gi, glp);
            PostImage.showGif(gi, r.imageKey, r.imageKey);
        }

        // নিচের সারি: Reply · রিঅ্যাকশন সারাংশ ........ 👍 👎
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(android.view.Gravity.CENTER_VERTICAL);
        if (!r.hidden) {
            TextView reply = new TextView(this);
            reply.setText("Reply");
            reply.setTextSize(15.0f);
            reply.setTypeface(null, android.graphics.Typeface.BOLD);
            reply.setTextColor(FB_GRAY);
            reply.setPadding(0, dp(8), dp(14), dp(8));
            reply.setOnClickListener(v -> startReply(replyTarget, r.name));
            actions.addView(reply);
        }
        LinearLayout summary = reactionSummary(r);
        if (summary != null) actions.addView(summary);
        View spacer = new View(this);
        actions.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1.0f));

        final String myReaction = r.likes.get(myUid);
        final boolean disliked = r.dislikes.contains(myUid);
        final boolean liked = myReaction != null;
        ImageView likeBtn = new ImageView(this);
        likeBtn.setPadding(dp(5), dp(5), dp(5), dp(5));
        if (!liked) {
            likeBtn.setImageResource(R.drawable.ic_like);
            likeBtn.setColorFilter(FB_GRAY);
        } else if (isLikeKey(myReaction)) {
            likeBtn.setImageResource(R.drawable.ic_like_circle);
            likeBtn.clearColorFilter();
            likeBtn.setPadding(dp(2), dp(2), dp(2), dp(2));
        } else {
            likeBtn.setImageResource(ReactionKit.icon(this, myReaction));
            likeBtn.clearColorFilter();
        }
        attachLike(likeBtn, r, liked);
        actions.addView(likeBtn, new LinearLayout.LayoutParams(dp(34), dp(34)));

        ImageView dislikeBtn = new ImageView(this);
        dislikeBtn.setPadding(dp(5), dp(5), dp(5), dp(5));
        dislikeBtn.setImageResource(R.drawable.ic_unlike);
        dislikeBtn.setColorFilter(disliked ? FB_RED : FB_GRAY);
        dislikeBtn.setOnClickListener(v -> setDislike(r.id, !disliked, liked));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(34), dp(34));
        dlp.setMarginStart(dp(14));
        dlp.setMarginEnd(dp(4));
        actions.addView(dislikeBtn, dlp);
        col.addView(actions, new LinearLayout.LayoutParams(-1, -2));

        if (r.hidden) row.setAlpha(0.5f);
        return row;
    }

    /** লেখায় চেপে ধরলে: Share (আর পোস্টের মালিকের জন্য Hide/Unhide) */
    private void showCommentMenu(View anchor, final Row r, boolean iOwn) {
        PopupMenu pm = new PopupMenu(this, anchor);
        pm.getMenu().add(0, 1, 0, "Share");
        if (iOwn) pm.getMenu().add(0, 2, 1, r.hidden ? "Unhide" : "Hide");
        pm.setOnMenuItemClickListener(it -> {
            if (it.getItemId() == 1) shareComment(r.name, r.text);
            else hideComment(r.id, !r.hidden);
            return true;
        });
        pm.show();
    }

    private void render() {
        if (list == null) return;
        final boolean iOwn = myUid != null && myUid.equals(postOwner);
        final int keepY = scroll.getScrollY();
        list.removeAllViews();
        if (topLevel.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No comments yet");
            empty.setTextColor(0xFF65676B);
            empty.setTextSize(17.0f);
            empty.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            empty.setPadding(0, dp(40), 0, 0);
            list.addView(empty);
            scrollToEnd = false;
            return;
        }
        for (final Row c : sortedTop()) {
            if (c.hidden && !iOwn) continue;
            list.addView(commentRow(c, c.id, false, null, iOwn), new LinearLayout.LayoutParams(-1, -2));
            if (c.hidden) continue;
            for (Row rep : c.replies) {
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
                rlp.leftMargin = dp(60);
                list.addView(commentRow(rep, c.id, true, c, iOwn), rlp);
            }
        }

        final boolean toTop = scrollToEnd;
        scrollToEnd = false;
        scroll.post(() -> scroll.scrollTo(0, toTop ? 0 : keepY));
    }

    // ---------- "@" মেনশন ----------

    /** কার্সরের আগে "@শব্দ" থাকলে (শুরুতে বা ফাঁকার পরে) সেটাই মেনশন-টোকেন */
    private void checkMentionToken() {
        if (!canComment) return;
        CharSequence t = input.getText();
        int cur = input.getSelectionStart();
        if (cur < 0) cur = t.length();
        int i = cur - 1;
        while (i >= 0 && t.charAt(i) != '@' && t.charAt(i) != ' ' && t.charAt(i) != '\n') i--;
        if (i >= 0 && t.charAt(i) == '@' && (i == 0 || t.charAt(i - 1) == ' ' || t.charAt(i - 1) == '\n')) {
            tokStart = i;
            refreshMentions();
        } else {
            tokStart = -1;
            hideMentions();
        }
    }

    private String mentionQuery() {
        if (tokStart < 0) return "";
        int cur = Math.max(tokStart + 1, input.getSelectionStart());
        CharSequence t = input.getText();
        return t.subSequence(tokStart + 1, Math.min(cur, t.length())).toString().toLowerCase(Locale.ROOT);
    }

    private void hideMentions() {
        if (mentionBox != null) mentionBox.setVisibility(View.GONE);
    }

    private void refreshMentions() {
        if (tokStart < 0) return;
        final String q = mentionQuery();
        mentionList.removeAllViews();
        if ("followers".startsWith(q)) {
            addMentionRow(null, "@followers", "Some followers might receive notifications", true);
        }
        List<MentionRepo.Person> all = MentionRepo.cached();
        int shown = 0;
        for (MentionRepo.Person p : all) {
            if (!q.isEmpty() && !p.name.toLowerCase(Locale.ROOT).contains(q)) continue;
            addMentionRow(p, p.name, p.sub(), false);
            if (++shown >= 60) break;
        }
        boolean any = mentionList.getChildCount() > 0;
        mentionBox.setVisibility(any ? View.VISIBLE : View.GONE);
        if (!MentionRepo.loaded()) {
            TextView w = new TextView(this);
            w.setText("Loading\u2026");
            w.setTextColor(FB_GRAY);
            w.setTextSize(15.0f);
            w.setPadding(dp(16), dp(10), dp(16), dp(10));
            mentionList.addView(w);
            mentionBox.setVisibility(View.VISIBLE);
        }
    }

    private void addMentionRow(final MentionRepo.Person p, final String title, String sub, boolean group) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(7), dp(14), dp(7));
        ImageView av = new ImageView(this);
        av.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (group) {
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(0xFFE4E6EB);
            av.setBackground(g);
            av.setImageResource(R.drawable.ic_people);
            av.setColorFilter(0xFF65676B);
            av.setPadding(dp(11), dp(11), dp(11), dp(11));
        } else {
            AvatarLoader.load(av, p.uid);
        }
        row.addView(av, new LinearLayout.LayoutParams(dp(50), dp(50)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -2, 1f);
        cl.setMarginStart(dp(12));
        row.addView(col, cl);
        TextView n = new TextView(this);
        n.setText(title);
        n.setTextColor(0xFF050505);
        n.setTextSize(group ? 18.0f : 17.0f);
        n.setTypeface(null, android.graphics.Typeface.BOLD);
        n.setSingleLine(true);
        col.addView(n);
        TextView sTv = new TextView(this);
        sTv.setText(sub);
        sTv.setTextColor(FB_GRAY);
        sTv.setTextSize(14.0f);
        sTv.setSingleLine(true);
        col.addView(sTv);
        row.setOnClickListener(v -> pickMention(p, title, group));
        mentionList.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    /** তালিকা থেকে বাছলে "@নাম " বসে; @followers = সব ফলোয়ার */
    private void pickMention(MentionRepo.Person p, String title, boolean group) {
        if (tokStart < 0) return;
        String tag = group ? "@followers" : "@" + title;
        android.text.Editable e = input.getText();
        int cur = Math.max(tokStart + 1, input.getSelectionStart());
        mentionEdit = true;
        e.replace(tokStart, Math.min(cur, e.length()), tag + " ");
        mentionEdit = false;
        mentions.put(tag, group ? "*" : p.uid);
        input.setSelection(Math.min(input.getText().length(), tokStart + tag.length() + 1));
        tokStart = -1;
        hideMentions();
        styleInputMentions(input.getText());
        updateSendVisibility();
    }

    /** লেখার ভেতরের বাছা মেনশনগুলো নীল ও বোল্ড */
    private void styleInputMentions(android.text.Editable e) {
        for (Object o : e.getSpans(0, e.length(), MentionSpan.class)) e.removeSpan(o);
        for (String tag : mentions.keySet()) {
            int from = 0;
            while (true) {
                int i = e.toString().indexOf(tag, from);
                if (i < 0) break;
                e.setSpan(new MentionSpan(), i, i + tag.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                from = i + tag.length();
            }
        }
    }

    private static final class MentionSpan extends ForegroundColorSpan {
        MentionSpan() { super(MENTION_BLUE); }
    }

    /** কমেন্ট পাঠানো হলে: যাদের মেনশন করা হয়েছে তাদের (বা সব ফলোয়ারকে) "mentioned you" নোটিফিকেশন */
    private void notifyMentions(String sentText) {
        Set<String> targets = new HashSet<>();
        for (Map.Entry<String, String> m : mentions.entrySet()) {
            if (!sentText.contains(m.getKey())) continue;
            if ("*".equals(m.getValue())) targets.addAll(MentionRepo.followerIds());
            else targets.add(m.getValue());
        }
        targets.remove(myUid);
        for (String uid : targets) NotificationHelper.send(uid, "mention", postId);
    }

    /** কমেন্টের লেখায় @followers ও চেনা নামের মেনশন নীল */
    private CharSequence styleMentions(String text) {
        if (text == null || text.indexOf('@') < 0) return text == null ? "" : text;
        SpannableString ss = new SpannableString(text);
        List<String> tags = new ArrayList<>();
        tags.add("@followers");
        for (MentionRepo.Person p : MentionRepo.cached()) tags.add("@" + p.name);
        for (String tag : tags) {
            int from = 0;
            while (true) {
                int i = text.indexOf(tag, from);
                if (i < 0) break;
                ss.setSpan(new ForegroundColorSpan(MENTION_BLUE), i, i + tag.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                ss.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), i, i + tag.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                from = i + tag.length();
            }
        }
        return ss;
    }

    // ---------- ক্যামেরা (Gallery) ও গোল প্লাস (Stickers / GIF) প্যানেল ----------

    private void updateToolRow() {
        if (toolRow == null) return;
        boolean show = input.hasFocus() || panelMode != PANEL_NONE || pendingGif != null
                || !input.getText().toString().isEmpty();
        toolRow.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void styleToolIcons() {
        camBtn.setColorFilter(panelMode == PANEL_GALLERY ? FB_BLUE : FB_GRAY);
        plusBtn.setColorFilter(panelMode == PANEL_STICKER ? FB_BLUE : FB_GRAY);
    }

    private void ensurePanels() {
        if (panels != null) return;
        panels = new CommentPanels(this, new CommentPanels.Host() {
            @Override public void onImagePicked(android.net.Uri uri) { uploadImage(uri); }
            @Override public void onGifKey(String key) { setPendingGif(key); closePanel(false); }
            @Override public void openGifLibrary() { openGifPicker(); }
            @Override public void takePhoto() { launchCamera(); }
            @Override public void requestGalleryPermission() { photoPermLauncher.launch(CommentPanels.photoPermission()); }
        });
        galleryView = panels.buildGallery();
        stickerView = panels.buildStickers();
    }

    private void togglePanel(int mode) {
        if (!canComment) return;
        if (panelMode == mode) { closePanel(true); return; }
        ensurePanels();
        hideKeyboard();
        hideMentions();
        panelMode = mode;
        panelHost.removeAllViews();
        panelHost.addView(mode == PANEL_GALLERY ? galleryView : stickerView, new FrameLayout.LayoutParams(-1, -1));
        panelHost.setVisibility(View.VISIBLE);
        android.view.Window w = getWindow();
        w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(getResources().getDisplayMetrics().heightPixels * 0.84f));
        styleToolIcons();
        updateToolRow();
        if (mode == PANEL_GALLERY) {
            panels.refreshGallery();
            if (!CommentPanels.granted(this)) photoPermLauncher.launch(CommentPanels.photoPermission());
        } else {
            panels.refreshStickers();
        }
    }

    private void closePanel(boolean showKeyboard) {
        if (panelHost == null) return;
        boolean was = panelMode != PANEL_NONE;
        panelMode = PANEL_NONE;
        panelHost.setVisibility(View.GONE);
        panelHost.removeAllViews();
        if (was) {
            getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    Math.round(getResources().getDisplayMetrics().heightPixels * 0.64f));
        }
        styleToolIcons();
        if (showKeyboard) {
            input.requestFocus();
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService("input_method");
            if (imm != null) imm.showSoftInput(input, 1);
        }
        updateToolRow();
    }

    private void hideKeyboard() {
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService("input_method");
        if (imm != null && input != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
    }

    private void launchCamera() {
        try {
            java.io.File dir = getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES);
            if (dir != null && !dir.exists()) dir.mkdirs();
            java.io.File f = new java.io.File(dir, "comment_" + System.currentTimeMillis() + ".jpg");
            cameraUri = androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
            cameraLauncher.launch(cameraUri);
        } catch (Exception e) {
            Toast.makeText(this, "Camera not available", Toast.LENGTH_SHORT).show();
        }
    }

    /** গ্যালারি/ক্যামেরার ছবি R2-তে আপলোড করে কমেন্টের ছবি (image_key) হিসেবে বসায় */
    private void uploadImage(final android.net.Uri uri) {
        if (uploadingImage || uri == null || !Net.check(this)) return;
        byte[] data;
        String mime = "image/jpeg";
        try {
            String m = getContentResolver().getType(uri);
            if (m != null && m.startsWith("image/")) mime = m;
            java.io.InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) throw new java.io.IOException("no stream");
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > 25L * 1024 * 1024) { in.close(); Toast.makeText(this, "Photo is too large", Toast.LENGTH_SHORT).show(); return; }
                bo.write(buf, 0, n);
            }
            in.close();
            data = bo.toByteArray();
        } catch (Exception e) {
            Toast.makeText(this, "Could not read this photo", Toast.LENGTH_SHORT).show();
            return;
        }
        uploadingImage = true;
        closePanel(false);
        gifChip.setVisibility(View.VISIBLE);
        gifChip.setTag("uploading");
        gifChip.setImageDrawable(new android.graphics.drawable.ColorDrawable(0xFFE4E6EB));
        com.bumptech.glide.Glide.with(gifChip).load(uri).centerCrop().into(gifChip);
        updateSendVisibility();
        String ext = mime.contains("png") ? "png" : (mime.contains("gif") ? "gif" : "jpg");
        Media.upload(data, "post", "comment." + ext, mime, (key, err) -> {
            if (isFinishing()) return;
            uploadingImage = false;
            if (key == null) {
                gifChip.setVisibility(View.GONE);
                Toast.makeText(this, err == null ? "Upload failed" : err, Toast.LENGTH_SHORT).show();
                updateSendVisibility();
                return;
            }
            pendingGif = key;
            gifChip.setTag(key);
            updateSendVisibility();
        });
    }

    // ---------- ছোট helper ----------

    /** রিপ্লাইর শুরুর "@Name" নীল ও বোল্ড */
    private CharSequence mention(String text, Row parent, Row self) {
        if (text == null || !text.startsWith("@")) return text;
        int end = -1;
        List<String> names = new ArrayList<>();
        if (parent != null && parent.name != null) names.add(parent.name);
        if (parent != null) for (Row r : parent.replies) if (r.name != null) names.add(r.name);
        for (String n : names) {
            if (!n.isEmpty() && text.startsWith("@" + n) && 1 + n.length() > end) end = 1 + n.length();
        }
        if (end < 0) {
            end = text.indexOf(' ');
            if (end < 0) end = text.length();
        }
        SpannableString ss = new SpannableString(text);
        ss.setSpan(new ForegroundColorSpan(0xFF1877F2), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        ss.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return ss;
    }

    private static JSONArray parseArray(String body) {
        try {
            return new JSONArray(body);
        } catch (Exception e) {
            return null;
        }
    }

    private static String str(JSONObject o, String k) {
        if (o == null || o.isNull(k)) return null;
        String v = o.optString(k, "");
        return v.isEmpty() ? null : v;
    }

    /** Supabase-এর created_at (ISO, UTC) থেকে millisecond */
    private static long parseTs(String iso) {
        if (iso == null || iso.length() < 19) return 0L;
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            return f.parse(iso.substring(0, 19)).getTime();
        } catch (Exception e) {
            return 0L;
        }
    }

    private int dp(int v) {
        return Math.round(((float) v) * getResources().getDisplayMetrics().density);
    }
}
