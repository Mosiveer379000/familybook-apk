package com.familybook.app;

import android.app.Activity;
import android.graphics.Rect;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.format.DateUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class PostAdapter extends RecyclerView.Adapter<PostAdapter.VH> {
    // পোস্ট কার্ডের নামের সাইজ (sp) ও ছবির সাইজ (dp): ছোট/বড় করতে এই দুটো সংখ্যা বদলান
    private static final float NAME_SP = 16.0f;
    private static final float GROUP_SP = 15.0f;   // গ্রুপের নামের সাইজ
    private static final float PHOTO_TEXT_SP = 15.0f; // ছবিসহ পোস্টের লেখা (ফেসবুকের মতো ছোট)
    private static final int AVATAR_DP = 44;
    // তিন ডট (⋮) মেনুর সাইজ (dp)
    private static final int MENU_DP = 30;

    private final Actions actions;
    private final List<Post> items = new ArrayList<>();
    private final boolean showHide;
    private final boolean homeStyle; // Home ফিডের ফেসবুক-স্টাইল (ডার্ক/লাইট) কার্ড
    private static final android.util.LruCache<String, Boolean> VERIFIED = new android.util.LruCache<>(200);
    private final Set<String> viewedNow = new HashSet<>(); // এই সেশনে যেগুলোর ভিউ লেখা হয়েছে

    public interface Actions {
        void onComments(Post post);

        void onHide(Post post);

        void onLike(Post post);

        void onMenu(Post post, View view);
    }

    /** প্রোফাইলে পিন করা পোস্টের id — সময়ের আগে "📌 Pinned" দেখায় */
    public String pinnedPostId;

    public PostAdapter(Actions actions, boolean showHide) {
        this(actions, showHide, false);
    }

    public PostAdapter(Actions actions, boolean showHide, boolean homeStyle) {
        this.actions = actions;
        this.showHide = showHide;
        this.homeStyle = homeStyle;
    }

    private int col(View v, int resId) {
        return androidx.core.content.ContextCompat.getColor(v.getContext(), resId);
    }

    /** profiles/{uid}/verifiedBadge == true হলে নামের পাশে ব্যাজ (শুধু Home-স্টাইলে) */
    private void bindVerified(final VH h, final String uid) {
        if (h.verified == null) return;
        h.verified.setTag(uid);
        Boolean c = uid == null ? Boolean.FALSE : VERIFIED.get(uid);
        if (c != null) {
            h.verified.setVisibility(c ? View.VISIBLE : View.GONE);
            return;
        }
        h.verified.setVisibility(View.GONE);
        if (uid == null) return;
        SupabaseRest.call("GET", "/rest/v1/profiles?id=eq." + uid + "&select=verified_badge",
                null, null, true, r -> {
            if (!r.ok()) return;   // ব্যর্থ হলে ক্যাশ করব না, পরের বার আবার চেষ্টা হবে
            boolean on = false;
            try {
                org.json.JSONArray arr = new org.json.JSONArray(r.body);
                on = arr.length() > 0 && arr.getJSONObject(0).optBoolean("verified_badge", false);
            } catch (Exception ignored) { }
            VERIFIED.put(uid, on);
            if (uid.equals(h.verified.getTag())) h.verified.setVisibility(on ? View.VISIBLE : View.GONE);
        });
    }

    // ---------- গ্রুপ পোস্টের হেডার (নামের উপরে গ্রুপ, নামের পাশে রোল) ----------

    private static int dpx(View v, float d) {
        return Math.round(d * v.getResources().getDisplayMetrics().density);
    }

    /** কার্ডের XML না বদলে কোডেই দুটো ভিউ বসানো: নামের কলামের উপরে গ্রুপের নাম, নামের পাশে রোল-চিপ */
    private static void buildGroupViews(VH h) {
        try {
            android.view.ViewParent row = h.name.getParent();                  // নাম + ব্যাজের সারি
            if (!(row instanceof android.widget.LinearLayout)) return;
            android.widget.LinearLayout nameRow = (android.widget.LinearLayout) row;
            android.view.ViewParent col = nameRow.getParent();                 // নাম/সময়ের কলাম
            if (!(col instanceof android.widget.LinearLayout)) return;

            android.content.Context cx = h.name.getContext();
            android.widget.LinearLayout wrap = new android.widget.LinearLayout(cx);
            wrap.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            wrap.setGravity(android.view.Gravity.CENTER_VERTICAL);

            TextView g = new TextView(cx);
            g.setTextSize(GROUP_SP);
            g.setTypeface(Typeface.DEFAULT_BOLD);
            g.setMaxLines(1);
            g.setEllipsize(android.text.TextUtils.TruncateAt.END);
            g.setMaxWidth(Math.max(dpx(h.name, 120),
                    h.name.getResources().getDisplayMetrics().widthPixels - dpx(h.name, 270)));
            g.setVisibility(View.GONE);
            wrap.addView(g, new android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView j = new TextView(cx);
            j.setTextSize(12.5f);
            j.setTypeface(Typeface.DEFAULT_BOLD);
            j.setGravity(android.view.Gravity.CENTER);
            j.setPadding(dpx(h.name, 11), dpx(h.name, 3), dpx(h.name, 11), dpx(h.name, 3));
            j.setVisibility(View.GONE);
            android.widget.LinearLayout.LayoutParams jlp = new android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            jlp.setMarginStart(dpx(h.name, 8));
            wrap.addView(j, jlp);

            ((android.widget.LinearLayout) col).addView(wrap, 0, new android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            h.groupTv = g;
            h.joinTv = j;

            TextView r = new TextView(h.name.getContext());
            r.setTextSize(12);
            r.setTypeface(Typeface.DEFAULT_BOLD);
            r.setVisibility(View.GONE);
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginStart(dpx(h.name, 6));
            nameRow.addView(r, lp);
            h.roleTv = r;
        } catch (Exception ignored) { }
    }

    private void bindGroup(VH h, final Post p) {
        if (h.groupTv == null || h.roleTv == null) return;
        if (!p.isGroupPost() || p.groupName == null || p.groupName.isEmpty()) {
            h.groupTv.setVisibility(View.GONE);
            h.roleTv.setVisibility(View.GONE);
            if (h.joinTv != null) h.joinTv.setVisibility(View.GONE);
            return;
        }
        int sub = homeStyle ? col(h.name, R.color.h_text) : 0xFF65676B;   // হোম-ফিডে গ্রুপের নাম কালো (h_text)
        h.groupTv.setText("\uD83D\uDC65 " + p.groupName);
        h.groupTv.setTextSize(homeStyle ? 18f : GROUP_SP);
        h.groupTv.setTextColor(sub);
        h.groupTv.setVisibility(View.VISIBLE);
        h.groupTv.setOnClickListener(v -> GroupDetailActivity.open(v.getContext(), p.groupId));

        String role = p.groupRole == null ? "member" : p.groupRole;
        String label = "admin".equals(role) ? "Admin" : "moderator".equals(role) ? "Moderator" : "Member";
        int fg = "admin".equals(role) ? 0xFF1877F2 : "moderator".equals(role) ? 0xFF1B7F3B : 0xFF65676B;
        int bg = "admin".equals(role) ? 0x261877F2 : "moderator".equals(role) ? 0x261B7F3B : 0x1F65676B;
        GradientDrawable chip = new GradientDrawable();
        chip.setColor(bg);
        chip.setCornerRadius(dpx(h.roleTv, 8));
        if (homeStyle) {
            chip.setCornerRadius(dpx(h.roleTv, 12));
            if ("admin".equals(role)) chip.setColor(0xFFE1ECFD);   // হালকা নীল পিল
        }
        h.roleTv.setTextSize(homeStyle ? 14f : 12f);
        h.roleTv.setBackground(chip);
        h.roleTv.setTextColor(fg);
        h.roleTv.setText(label);
        if (homeStyle) h.roleTv.setPadding(dpx(h.roleTv, 10), dpx(h.roleTv, 3), dpx(h.roleTv, 10), dpx(h.roleTv, 3));
        else h.roleTv.setPadding(dpx(h.roleTv, 6), dpx(h.roleTv, 1), dpx(h.roleTv, 6), dpx(h.roleTv, 1));
        h.roleTv.setVisibility(View.VISIBLE);
        bindJoin(h, p);
        if (homeStyle) {
            // নামের সর্বোচ্চ প্রস্থ: স্ক্রিন − (অ্যাভাটার + ⋮/✕ বাটন + মার্জিন) − (Join দেখা গেলে তার জায়গা)
            int screen = h.name.getResources().getDisplayMetrics().widthPixels;
            int reserve = dpx(h.name, 150);
            if (h.joinTv != null && h.joinTv.getVisibility() == View.VISIBLE) reserve += dpx(h.name, 96);
            h.groupTv.setMaxWidth(Math.max(dpx(h.name, 120), screen - reserve));
            // লম্বা/বড় নাম কাটা পড়বে না — নিচের লাইনে ভেঙে যাবে; Join নামের পাশে (প্রথম লাইনের সারিতে) থাকবে
            h.groupTv.setMaxLines(Integer.MAX_VALUE);
            h.groupTv.setEllipsize(null);
            if (h.groupTv.getParent() instanceof android.widget.LinearLayout) {
                ((android.widget.LinearLayout) h.groupTv.getParent()).setGravity(android.view.Gravity.TOP);
            }
        }
    }

    /** গ্রুপ-নামের পাশে Join / Requested। Public = সাথে সাথে; Friends = অ্যাডমিন approve করলে */
    private void bindJoin(VH h, final Post p) {
        if (h.joinTv == null) return;
        String st = GroupJoin.status(p.groupId);
        boolean offerable = "public".equals(p.groupVisibility) || "friends".equals(p.groupVisibility);
        // সদস্য (active) হলে, নিজের পোস্ট হলে, বা সদস্যতার তথ্য এখনো না এলে Join দেখাবে না
        boolean mine = p.isMine(SupabaseRest.uid());
        if (st == null || !offerable || "active".equals(st) || mine) {
            h.joinTv.setVisibility(View.GONE);
            h.joinTv.setOnClickListener(null);
            return;
        }
        boolean pending = "pending".equals(st);
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(dpx(h.joinTv, 14));
        if (homeStyle) {
            pill.setCornerRadius(dpx(h.joinTv, 16));
            pill.setColor(0xFFE4E6EB);
            h.joinTv.setTextSize(17f);
            h.joinTv.setPadding(dpx(h.joinTv, 14), dpx(h.joinTv, 5), dpx(h.joinTv, 14), dpx(h.joinTv, 5));
            h.joinTv.setTextColor(pending ? 0xFF65676B : 0xFF1877F2);
        } else {
            pill.setColor(pending ? 0x1F65676B : 0xFF1877F2);
            h.joinTv.setTextColor(pending ? 0xFF65676B : 0xFFFFFFFF);
        }
        h.joinTv.setBackground(pill);
        h.joinTv.setText(pending ? "Requested" : "Join");
        h.joinTv.setVisibility(View.VISIBLE);
        h.joinTv.setOnClickListener(v -> {
            final Context cx = v.getContext();
            final GroupJoin.Done refresh = () -> {
                if (!items.isEmpty()) notifyItemRangeChanged(0, items.size(), "join");
            };
            if ("pending".equals(GroupJoin.status(p.groupId))) {
                new android.app.AlertDialog.Builder(cx).setTitle("Cancel join request?")
                        .setPositiveButton("Cancel request", (d, w) -> GroupJoin.cancel(cx, p.groupId, refresh))
                        .setNegativeButton("Keep", null).show();
            } else {
                GroupJoin.join(cx, p.groupId, p.groupVisibility, refresh);
            }
        });
    }

    public void setItems(List<Post> list) {
        this.items.clear();
        this.items.addAll(list);
        stopMusicIfRemoved();
        notifyDataSetChanged();
        // পোস্টগুলোর গানের তথ্য এনে শুধু স্পিকার আইকন আপডেট
        PostMusic.prefetch(list, () -> {
            if (!items.isEmpty()) notifyItemRangeChanged(0, items.size(), "music");
        });
        // আমার গ্রুপ-সদস্যতা এনে Join বাটন আপডেট
        GroupJoin.load(() -> {
            if (!items.isEmpty()) notifyItemRangeChanged(0, items.size(), "join");
        });
        // পোস্টগুলোর ছবির লিংক এনে শুধু ছবি-ভিউ আপডেট
        PostImage.prefetch(list, () -> {
            if (!items.isEmpty()) notifyItemRangeChanged(0, items.size(), "photo");
        });
        // প্রোফাইল ছবি/কভার ফটো পোস্ট চেনার তথ্য (সব তালিকায়: ফিড, প্রোফাইল, গ্রুপ, My Posts, Search) — এলে ছবি নিজের সাইজে বসে
        PostExtras.ensureLoaded(() -> {
            if (!items.isEmpty()) notifyItemRangeChanged(0, items.size(), "photo");
        });
    }

    // গান চালু/বন্ধ হলে সংশ্লিষ্ট কার্ডের আইকন বদলাতে
    private final MusicPlayer.Listener musicListener = (key, playing) -> {
        if (key == null || !key.startsWith("post:")) return;
        String id = key.substring(5);
        for (int i = 0; i < items.size(); i++) {
            if (id.equals(items.get(i).id)) notifyItemChanged(i, "music");
        }
    };

    /** চালু গানের পোস্ট তালিকা থেকে মুছে গেলে বা ট্র্যাশে গেলে গান থামে */
    private void stopMusicIfRemoved() {
        String key = MusicPlayer.currentKey();
        if (key == null || !key.startsWith("post:")) return;
        String id = key.substring(5);
        for (Post p : items) {
            if (id.equals(p.id) && !"trashed".equals(p.status) && !"deleted".equals(p.status)) return;
        }
        MusicPlayer.stopIfKey(key);
    }

    // স্ক্রল করলে চালু গানের পোস্ট স্ক্রিন থেকে সরে গেছে কি না দেখতে
    private final RecyclerView.OnScrollListener musicScrollStop = new RecyclerView.OnScrollListener() {
        @Override
        public void onScrolled(RecyclerView rv, int dx, int dy) {
            stopMusicIfGone(rv);
        }

        @Override
        public void onScrollStateChanged(RecyclerView rv, int newState) {
            if (newState == RecyclerView.SCROLL_STATE_IDLE) stopMusicIfGone(rv);
        }
    };

    /** চালু গানের পোস্ট স্ক্রিন থেকে সরে গেলে (অর্ধেকের কম দেখা গেলে) গান বন্ধ হবে */
    private void stopMusicIfGone(final RecyclerView rv) {
        final String key = MusicPlayer.currentKey();
        if (key == null || !key.startsWith("post:")) return;
        final String id = key.substring(5);
        int pos = -1;
        for (int i = 0; i < items.size(); i++) {
            if (id.equals(items.get(i).id)) {
                pos = i;
                break;
            }
        }
        boolean visible = false;
        if (pos >= 0 && rv.getLayoutManager() != null) {
            View v = rv.getLayoutManager().findViewByPosition(pos);
            if (v != null) {
                Rect r = new Rect();
                if (v.getGlobalVisibleRect(r) && r.height() * 2 >= v.getHeight()) visible = true;
            }
        }
        // স্ক্রলের ভেতরে সরাসরি বন্ধ না করে পরে করি (RecyclerView হিসাব শেষ হওয়ার পর)
        if (!visible) rv.post(() -> MusicPlayer.stopIfKey(key));
    }

    // ---------- Short ভিডিও কার্ডের অটো-প্লে ----------
    private RecyclerView rvRef;
    private final Runnable autoRun = this::autoplayNow;
    // প্রোফাইলে RecyclerView নিজে স্ক্রল করে না (ScrollView-র ভেতরে), তাই যেকোনো স্ক্রলে ধরি
    private final android.view.ViewTreeObserver.OnScrollChangedListener treeScroll = this::scheduleAutoplay;

    private void scheduleAutoplay() {
        if (rvRef == null) return;
        rvRef.removeCallbacks(autoRun);
        rvRef.postDelayed(autoRun, 180);
    }

    /** স্ক্রিনে সবচেয়ে বেশি দেখা (৫৫%+) ভিডিও কার্ডটা চলে, বাকিগুলো থামে; Settings-এ অটো-প্লে বন্ধ থাকলে কিছু চলে না */
    public void autoplayNow() {
        RecyclerView rv = rvRef;
        if (rv == null) return;
        boolean auto = MediaPrefs.autoplay(rv.getContext());
        ShortPlayerView best = null;
        float bestFrac = 0.55f;
        for (int i = 0; i < rv.getChildCount(); i++) {
            RecyclerView.ViewHolder vh = rv.getChildViewHolder(rv.getChildAt(i));
            if (!(vh instanceof VH)) continue;
            ShortPlayerView sv = ((VH) vh).shortView;
            if (sv == null || sv.getVisibility() != View.VISIBLE || sv.getHeight() <= 0) continue;
            Rect r = new Rect();
            float frac = sv.getGlobalVisibleRect(r) ? r.height() / (float) sv.getHeight() : 0f;
            if (frac > bestFrac) {
                best = sv;
                bestFrac = frac;
            }
        }
        for (int i = 0; i < rv.getChildCount(); i++) {
            RecyclerView.ViewHolder vh = rv.getChildViewHolder(rv.getChildAt(i));
            if (!(vh instanceof VH)) continue;
            ShortPlayerView sv = ((VH) vh).shortView;
            if (sv != null && sv != best && sv.isWanted()) sv.stop();
        }
        if (best != null && auto && !best.isWanted()) best.play();
    }

    @Override
    public void onViewRecycled(VH h) {
        if (h.shortView != null) h.shortView.stop();
        super.onViewRecycled(h);
    }

    /** ভিডিও কার্ডে ট্যাপ: ফুল-ব্ল্যাক-স্ক্রিনে এই ফিডের সব Short উপর থেকে নিচে, ট্যাপ করাটা থেকে শুরু */
    private void openViewer(View v, ShortStore.Info tapped) {
        ArrayList<String> ids = new ArrayList<>();
        int idx = 0;
        for (Post q : items) {
            if (q.hiddenByMe) continue;
            String k = PostImage.get(q.id);
            ShortStore.Info qi = (k != null && PostExtras.isShort(q.id)) ? ShortStore.byThumb(k) : null;
            if (qi == null) continue;
            if (qi == tapped) idx = ids.size();
            ids.add(qi.id);
        }
        ShortPlayerView.stopAll();
        ShortViewerActivity.open(v.getContext(), ids, idx);
    }

    @Override
    public void onAttachedToRecyclerView(RecyclerView rv) {
        super.onAttachedToRecyclerView(rv);
        MusicPlayer.addListener(musicListener);
        rv.addOnScrollListener(musicScrollStop);
        rvRef = rv;
        rv.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrollStateChanged(RecyclerView r, int s) { scheduleAutoplay(); }
        });
        rv.getViewTreeObserver().addOnScrollChangedListener(treeScroll);
    }

    @Override
    public void onDetachedFromRecyclerView(RecyclerView rv) {
        MusicPlayer.removeListener(musicListener);
        rv.removeOnScrollListener(musicScrollStop);
        try { rv.getViewTreeObserver().removeOnScrollChangedListener(treeScroll); } catch (Exception ignored) { }
        rv.removeCallbacks(autoRun);
        rvRef = null;
        super.onDetachedFromRecyclerView(rv);
    }

    @Override
    public void onBindViewHolder(VH h, int position, List<Object> payloads) {
        if (!payloads.isEmpty() && (payloads.contains("music") || payloads.contains("photo") || payloads.contains("join"))) {
            Post pp = items.get(position);
            if (payloads.contains("join")) bindGroup(h, pp);
            if (payloads.contains("music") && h.music != null) {
                PostMusic.bindIcon(h.music, pp);
                if (PostExtras.isShort(pp.id)) h.music.setVisibility(View.GONE);   // শর্ট ভিডিওর সাউন্ড ভিডিওর উপরের বাটনেই
            }
            if (payloads.contains("photo")) bindPhoto(h, pp, pp.hiddenByMe);
            return;
        }
        super.onBindViewHolder(h, position, payloads);
    }

    @Override
    public VH onCreateViewHolder(ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(parent.getContext()).inflate(homeStyle ? R.layout.item_post_home : R.layout.item_post, parent, false));
    }

    @Override
    public void onBindViewHolder(VH h, int position) {
        final Post p = this.items.get(position);
        h.name.setText(p.edited ? p.name + " · Edited" : p.name);
        final boolean isShare = p.sharedPostId != null;
        if (isShare) {
            String who = p.name == null ? "" : p.name;
            String orig = p.sharedName == null ? "someone" : p.sharedName;
            SpannableString ss = new SpannableString(who + " shared " + orig + "'s post");
            ss.setSpan(new StyleSpan(Typeface.NORMAL), who.length(), ss.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            h.name.setText(ss);
        }

        // পোস্ট কার্ডে নাম ও ছবি ছোট, যাতে "— 😊 feeling happy." ডান পাশে একই লাইনে ধরে
        float dens = h.name.getResources().getDisplayMetrics().density;
        h.name.setTextSize(2, NAME_SP);
        if (h.avatar != null) {
            ViewGroup.LayoutParams alp = h.avatar.getLayoutParams();
            int want = Math.round(AVATAR_DP * dens);
            if (alp != null && (alp.width != want || alp.height != want)) {
                alp.width = want;
                alp.height = want;
                h.avatar.setLayoutParams(alp);
            }
        }

        // Feeling / Activity: নামের ডান পাশে, একই লাইনে (নিচে ভাঙবে না)
        h.name.setTypeface(h.nameTf);
        h.name.setTransformationMethod(h.nameTm);
        h.name.setMaxLines(h.nameMax);
        h.name.setEllipsize(h.nameEll);
        int extraNormal = homeStyle ? col(h.name, R.color.h_text_sub) : 0xFF65676B;
        int extraBold = homeStyle ? col(h.name, R.color.h_text) : 0xFF050505;
        CharSequence extra = isShare ? null : PostExtras.styled(p.id, extraNormal, extraBold);
        if (extra != null) {
            SpannableStringBuilder sb = new SpannableStringBuilder(h.name.getText());
            sb.setSpan(new StyleSpan(Typeface.BOLD), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            int st = sb.length();
            sb.append(extra);
            sb.setSpan(new RelativeSizeSpan(0.88f), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            h.name.setTypeface(Typeface.DEFAULT);
            // with/at যোগ হলে লাইন লম্বা হয়, তাই ৩ লাইন পর্যন্ত
            h.name.setMaxLines(3);
            h.name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            h.name.setText(sb);
        }

        String body = p.text == null ? "" : p.text;
        h.text.setText(body);
        h.text.setTextSize(2, sizeFor(body.length()));
        h.likes.setText(String.valueOf(p.likes));
        CharSequence t = DateUtils.getRelativeTimeSpanString(p.timestamp, System.currentTimeMillis(), 60000);
        if ("archived".equals(p.status)) {
            t = t + "  ·  Archived";
        } else if ("trashed".equals(p.status)) {
            t = t + "  ·  In trash";
        }
        if (pinnedPostId != null && !pinnedPostId.isEmpty() && pinnedPostId.equals(p.id)) {
            t = "\uD83D\uDCCC Pinned \u00B7 " + t;
        }
        h.time.setText(t);
        bindPrivacy(h, p.privacy);
        boolean hid = p.hiddenByMe;
        if (hid) {
            h.text.setText("This post is hidden from your news feed.\n⋮ Use the menu to Unhide.");
            h.text.setTextSize(2, 15.0f);
        }
        h.text.setVisibility((isShare && !hid && body.isEmpty()) ? View.GONE : View.VISIBLE);
        if (h.sharedBox != null) {
            if (isShare && !hid) {
                GradientDrawable box = new GradientDrawable();
                box.setColor(homeStyle ? 0x14808080 : 0x14000000);
                box.setCornerRadius(24f);
                box.setStroke(2, homeStyle ? 0x66808080 : 0x33000000);
                h.sharedBox.setBackground(box);
                h.sharedName.setText(p.sharedName == null ? "" : p.sharedName);
                h.sharedText.setText(p.sharedText == null ? "" : p.sharedText);
                h.sharedBox.setVisibility(View.VISIBLE);
                h.sharedBox.setOnClickListener(v ->
                        ProfileActivity.open(v.getContext(), p.sharedUid, p.sharedName));
            } else {
                h.sharedBox.setVisibility(View.GONE);
            }
        }
        styleBody(h, p, hid, homeStyle ? col(h.text, R.color.h_text) : 0xFF4A4A4F);
        if (!hid) {
            // লেখার ভেতরের #হ্যাশট্যাগ: ডাবল-ট্যাপ করলে Search খোলে
            HashtagLinker.apply(h.text, p.bgColor == 0 ? col(h.text, R.color.h_link) : 0);
        }
        bindVerified(h, p.uid);
        if (h.hashtags != null) {
            boolean showTags = !hid && p.hashtags != null && !p.hashtags.isEmpty();
            h.hashtags.setVisibility(showTags ? View.VISIBLE : View.GONE);
            if (showTags) {
                h.hashtags.setText(p.hashtags);
                HashtagLinker.apply(h.hashtags, 0);
            }
        }
        if (h.avatar instanceof ImageView) {
            AvatarLoader.load((ImageView) h.avatar, p.uid);
        }
        final String myUid = SupabaseRest.uid();
        if (!hid && myUid != null && !p.viewedByMe
                && "active".equals(p.status) && p.id != null && viewedNow.add(p.id)) {
            p.views++;
            p.viewedByMe = true;
            try {
                org.json.JSONObject row = new org.json.JSONObject()
                        .put("post_id", p.id)
                        .put("user_id", myUid);
                SupabaseRest.call("POST", "/rest/v1/post_views", row.toString(),
                        "resolution=ignore-duplicates,return=minimal", true, r -> { });
            } catch (Exception ignored) { }
        }
        if (h.music != null) {
            PostMusic.bindIcon(h.music, p);
            if (hid || PostExtras.isShort(p.id)) h.music.setVisibility(View.GONE);   // শর্ট ভিডিওর সাউন্ড ভিডিওর উপরের বাটনেই
        }
        bindPhoto(h, p, hid);
        if (h.viewsBox != null) {
            h.viewsBox.setVisibility(hid ? View.GONE : View.VISIBLE);
            if (h.views != null) {
                h.views.setText(compact(p.views) + (p.views == 1 ? " view" : " views"));
            }
            h.viewsBox.setOnClickListener(v -> v.getContext().startActivity(
                    new android.content.Intent(v.getContext(), PostDashboardActivity.class)
                            .putExtra("postId", p.id)
                            .putExtra("views", p.views)
                            .putExtra("likes", p.likes)
                            .putExtra("comments", p.comments)
                            .putExtra("shares", p.shares)));
        }
        int vis = hid ? 8 : 0;
        h.like.setVisibility(vis);
        h.likes.setVisibility(vis);
        if (h.comment != null) {
            h.comment.setVisibility(vis);
        }
        if (h.comments != null) {
            h.comments.setVisibility(vis);
        }
        // গ্রুপের Post Privacy = "Nobody can like, comment, share" হলে শুধু দেখা যাবে
        final boolean locked = p.isGroupPost() && GroupRules.viewOnly(p.groupId);
        if (p.isGroupPost() && !GroupRules.known(p.groupId)) {
            GroupRules.load(p.groupId, () -> { if (!items.isEmpty()) notifyDataSetChanged(); });
        }
        final View.OnClickListener lockedClick = v -> {
            Activity la = activityOf(v);
            if (la != null) TopToast.show(la, "Likes, comments and shares are turned off in this group");
        };
        View.OnClickListener doShare = v -> {
            if (locked) { lockedClick.onClick(v); return; }
            Activity act = activityOf(v);
            if (act != null) {
                PostOps.share(act, p);
            }
        };
        if (h.shares != null) {
            h.shares.setText(String.valueOf(p.shares));
            h.shares.setVisibility(vis);
            h.shares.setOnClickListener(doShare);
        }
        if (h.btnShare != null) {
            h.btnShare.setVisibility(vis);
            h.btnShare.setOnClickListener(doShare);
        }
        String react = p.likedByMe ? p.myReaction : null;
        if (p.likedByMe && react != null && !"like".equals(react)) {
            // Love / Check / Stop / Angry / Haha: নিজের আইকন ও রঙে
            h.like.setImageResource(ReactionKit.icon(h.like.getContext(), react));
            h.like.clearColorFilter();
        } else {
            // Like আইকন সবসময় অরিজিনাল রঙে (Light/Dark কোনো মোডেই রং বদলাবে না)
            h.like.setImageResource(R.drawable.ic_like);
            h.like.clearColorFilter();
        }
        h.like.setOnClickListener(v -> { if (locked) lockedClick.onClick(v); else actions.onLike(p); });
        float lockAlpha = locked ? 0.4f : 1f;
        h.like.setAlpha(lockAlpha);
        if (h.comment != null) h.comment.setAlpha(lockAlpha);
        if (h.btnShare != null) h.btnShare.setAlpha(lockAlpha);
        // Like বাটনে চেপে ধরলে রিঅ্যাকশন বার (Love, Check, Stop, Angry, Haha)
        if (!hid && !locked && myUid != null && p.id != null) {
            ReactionKit.attach(activityOf(h.like), h.like, p.id, p.uid, myUid, p.likedByMe, key -> {
                // সার্ভারের উত্তরের জন্য না থেকে সঙ্গে সঙ্গে আইকন ও সংখ্যা বদলাই
                if (!p.likedByMe) p.likes = p.likes + 1;
                p.likedByMe = true;
                p.myReaction = key;
                notifyDataSetChanged();
            });
        }
        if (h.hide != null) {
            // ✕ বাটন আর নেই: Hide/Unhide এখন শুধু ⋮ মেনুতে
            h.hide.setVisibility(View.GONE);
            h.hide.setOnClickListener(null);
        }
        if (h.menu != null) {
            // ⋮ মেনু একটু ছোট ও একদম ডানে
            ViewGroup.LayoutParams mlp = h.menu.getLayoutParams();
            if (mlp != null) {
                int ms = Math.round(MENU_DP * dens);
                mlp.width = ms;
                mlp.height = ms;
                if (mlp instanceof ViewGroup.MarginLayoutParams) {
                    ((ViewGroup.MarginLayoutParams) mlp).setMarginEnd(0);
                }
                h.menu.setLayoutParams(mlp);
            }
            if (h.menu instanceof ImageView) {
                int pd = Math.round(5 * dens);
                h.menu.setPadding(pd, pd, pd, pd);
            }
            h.menu.setOnClickListener(v -> actions.onMenu(p, v));
        }
        if (h.comment != null) {
            h.comment.setOnClickListener(v -> { if (locked) lockedClick.onClick(v); else actions.onComments(p); });
        }
        if (h.comments != null) {
            h.comments.setText(String.valueOf(p.comments));
            h.comments.setOnClickListener(v -> { if (locked) lockedClick.onClick(v); else actions.onComments(p); });
        }
        // গোল প্রোফাইল ছবি বা নামে ক্লিক করলে তার প্রোফাইল খুলবে
        View.OnClickListener openProfile = v ->
                ProfileActivity.open(v.getContext(), p.uid, p.name);
        bindGroup(h, p);
        h.name.setOnClickListener(openProfile);
        if (h.avatar != null) {
            h.avatar.setOnClickListener(openProfile);
        }
    }

    /** পোস্টে ছবি থাকলে লেখার নিচে দেখায়; শুধু ছবির পোস্টে ফাঁকা লেখা-ঘর লুকায় */
    private void bindPhoto(VH h, Post p, boolean hid) {
        if (h.photo == null) return;
        if (hid) {
            h.photo.setVisibility(View.GONE);
            if (h.shortView != null) { h.shortView.stop(); h.shortView.setVisibility(View.GONE); }
            return;
        }
        PostImage.bind(h.photo, p);
        boolean has = PostImage.get(p.id) != null;
        // "uploaded a short" পোস্ট: ছবির জায়গায় অটো-প্লে ভিডিও কার্ড (ট্যাপে ফুল-স্ক্রিন, ডানে-নিচে মিউট)
        if (h.shortView != null) {
            ShortStore.Info si = null;
            String key = PostImage.get(p.id);
            if (key != null && PostExtras.isShort(p.id)) {
                si = ShortStore.byThumb(key);
                if (si == null) {
                    ShortStore.request(key, () -> {
                        if (!items.isEmpty()) notifyItemRangeChanged(0, items.size(), "photo");
                    });
                }
            }
            if (si != null) {
                final ShortStore.Info fsi = si;
                h.photo.setVisibility(View.GONE);
                h.shortView.setVisibility(View.VISIBLE);
                h.shortView.bind(fsi);
                h.shortView.setOnClickListener(v -> openViewer(v, fsi));
                scheduleAutoplay();
            } else {
                h.shortView.stop();
                h.shortView.setVisibility(View.GONE);
            }
        }
        boolean noText = p.text == null || p.text.trim().isEmpty();
        if (has && noText && p.sharedPostId == null) h.text.setVisibility(View.GONE);
        // ছবিসহ পোস্টে লেখা ছোট (ফেসবুকের মতো); ছবি ছাড়া পোস্টে আগের মতো বড়
        if (p.sharedPostId == null) {
            if (has) {
                h.text.setTextSize(2, PHOTO_TEXT_SP);
            } else {
                h.text.setTextSize(2, sizeFor(noText ? 0 : p.text.length()));
            }
        }
    }

    /** পোস্টের নামের নিচে প্রাইভেসি আইকন: Only me = তালা, Public = গ্লোব, Friends = বন্ধু */
    private void bindPrivacy(VH h, String privacy) {
        if (h.privacy == null) {
            return;
        }
        String[] names;
        if ("me".equals(privacy)) {
            names = new String[]{"ic_privacy_lock", "ic_lock"};
        } else if ("friends".equals(privacy)) {
            names = new String[]{"ic_privacy_friends", "ic_people"};
        } else {
            names = new String[]{"ic_privacy_public", "ic_globe"};
        }
        int id = 0;
        for (String n : names) {
            id = h.privacy.getResources().getIdentifier(n, "drawable", h.privacy.getContext().getPackageName());
            if (id != 0) {
                break;
            }
        }
        if (id == 0) {
            // তালার আইকন ফাইল এখনো না থাকলে ইমোজি
            h.privacy.setVisibility(View.GONE);
            if ("me".equals(privacy)) {
                h.time.append("  \uD83D\uDD12");
            }
            return;
        }
        h.privacy.setVisibility(View.VISIBLE);
        h.privacy.setImageResource(id);
        h.privacy.setColorFilter(homeStyle ? col(h.privacy, R.color.h_text_sub) : 0xFFA0A0A0);
    }

    private static Activity activityOf(View v) {
        Context c = v.getContext();
        while (c instanceof ContextWrapper) {
            if (c instanceof Activity) {
                return (Activity) c;
            }
            c = ((ContextWrapper) c).getBaseContext();
        }
        return null;
    }

    /** রঙিন পোস্ট হলে লেখার রং + ব্যাকগ্রাউন্ড বক্স, নইলে সাধারণ লেখা */
    private static void styleBody(VH h, Post p, boolean plain, int plainColor) {
        TextView t = h.text;
        if (!plain && p.bgColor != 0) {
            GradientDrawable g = new GradientDrawable();
            g.setColor(p.bgColor);
            g.setCornerRadius(t.getResources().getDisplayMetrics().density * 22f);
            t.setBackground(g);
            int pd = Math.round(t.getResources().getDisplayMetrics().density * 16f);
            t.setPadding(pd, pd, pd, pd);
            t.setTextColor(p.textColor != 0 ? p.textColor : 0xFFFFFFFF);
        } else {
            t.setBackground(null);
            t.setPadding(0, 0, 0, 0);
            t.setTextColor(plainColor);
        }
    }

    /** 1200 -> 1.2K, 2000 -> 2K */
    static String compact(int n) {
        if (n < 1000) {
            return String.valueOf(n);
        }
        if (n < 10000) {
            String s = String.format(Locale.US, "%.1f", n / 1000f);
            return (s.endsWith(".0") ? s.substring(0, s.length() - 2) : s) + "K";
        }
        return (n / 1000) + "K";
    }

    /** লেখা যত ছোট, অক্ষর তত বড় (নতুন পোস্ট স্ক্রিন ও ফিড দুই জায়গায় একই) */
    static float sizeFor(int len) {
        if (len <= 50) {
            return 34.0f;
        }
        if (len <= 120) {
            return 26.0f;
        }
        if (len <= 220) {
            return 21.0f;
        }
        return 17.0f;
    }

    @Override
    public int getItemCount() {
        return this.items.size();
    }

    public static class VH extends RecyclerView.ViewHolder {
        View avatar;
        View comment;
        TextView comments;
        View hide;
        ImageView like;
        TextView likes;
        View menu;
        ImageView privacy;
        ImageView verified;
        View sharedBox;
        TextView sharedName;
        TextView sharedText;
        TextView shares;
        View btnShare;
        TextView hashtags;
        View viewsBox;
        ImageView music;       // কার্ডের ডানে-নিচের গান চালু/বন্ধ আইকন
        TextView groupTv;      // গ্রুপ পোস্ট: নামের উপরে গ্রুপের নাম
        TextView roleTv;       // গ্রুপ পোস্ট: নামের পাশে Admin / Moderator / Member
        TextView joinTv;       // গ্রুপ পোস্ট: গ্রুপ-নামের পাশে Join / Requested
        ImageView photo;       // পোস্টের ছবি (লেখার নিচে)
        ShortPlayerView shortView;   // "uploaded a short" পোস্টের ভিডিও কার্ড
        TextView views;
        TextView name;
        Typeface nameTf;
        android.text.method.TransformationMethod nameTm;
        int nameMax;
        android.text.TextUtils.TruncateAt nameEll;
        TextView text;
        TextView time;

        VH(View v) {
            super(v);
            this.name = (TextView) v.findViewById(R.id.tvName);
            this.nameTf = this.name.getTypeface();
            this.nameTm = this.name.getTransformationMethod();
            this.nameMax = this.name.getMaxLines();
            this.nameEll = this.name.getEllipsize();
            this.time = (TextView) v.findViewById(R.id.tvTime);
            buildGroupViews(this);
            this.text = (TextView) v.findViewById(R.id.tvText);
            this.likes = (TextView) v.findViewById(R.id.tvLikes);
            this.like = (ImageView) v.findViewById(R.id.btnLike);
            this.avatar = opt(v, "imgAvatar");
            this.privacy = (ImageView) opt(v, "imgPrivacy");
            this.verified = (ImageView) opt(v, "imgVerified");
            this.sharedBox = opt(v, "sharedBox");
            this.sharedName = (TextView) opt(v, "tvSharedName");
            this.sharedText = (TextView) opt(v, "tvSharedText");
            this.shares = (TextView) opt(v, "tvShares");
            this.btnShare = opt(v, "btnShare");
            this.hashtags = (TextView) opt(v, "tvHashtags");
            this.viewsBox = opt(v, "viewsBox");
            this.views = (TextView) opt(v, "tvViews");
            // গানের আইকন: ভিউ-সংখ্যার ডান পাশে (সবার ডানে-নিচে) কোডেই বসানো হয়
            if (this.viewsBox != null && this.viewsBox.getParent() instanceof android.widget.LinearLayout) {
                android.widget.LinearLayout row = (android.widget.LinearLayout) this.viewsBox.getParent();
                float d = v.getResources().getDisplayMetrics().density;
                ImageView mi = new ImageView(v.getContext());
                android.widget.LinearLayout.LayoutParams lp =
                        new android.widget.LinearLayout.LayoutParams(Math.round(34 * d), Math.round(34 * d));
                lp.setMarginStart(Math.round(10 * d));
                mi.setLayoutParams(lp);
                int pad = Math.round(5 * d);
                mi.setPadding(pad, pad, pad, pad);
                mi.setVisibility(View.GONE);
                row.addView(mi);
                this.music = mi;
            }
            // ছবি-ভিউ: লেখার ঠিক নিচে, কোডেই বসানো হয়
            if (this.text.getParent() instanceof android.widget.LinearLayout) {
                android.widget.LinearLayout col = (android.widget.LinearLayout) this.text.getParent();
                float d2 = v.getResources().getDisplayMetrics().density;
                ImageView pi = new SquareImageView(v.getContext());   // ফিডে সবসময় ১:১
                pi.setScaleType(ImageView.ScaleType.CENTER_CROP);
                pi.setVisibility(View.GONE);
                android.widget.LinearLayout.LayoutParams plp =
                        new android.widget.LinearLayout.LayoutParams(-1, -2);
                plp.topMargin = Math.round(8 * d2);
                col.addView(pi, col.indexOfChild(this.text) + 1, plp);
                this.photo = pi;
                ShortPlayerView sv = new ShortPlayerView(v.getContext(), false);   // ১:১ ভিডিও কার্ড
                sv.setVisibility(View.GONE);
                android.widget.LinearLayout.LayoutParams slp =
                        new android.widget.LinearLayout.LayoutParams(-1, -2);
                slp.topMargin = Math.round(8 * d2);
                col.addView(sv, col.indexOfChild(pi) + 1, slp);
                this.shortView = sv;
            }
            this.hide = opt(v, "btnHide");
            this.menu = opt(v, "btnMenu");
            this.comment = opt(v, "btnComment");
            this.comments = (TextView) opt(v, "tvComments");
        }

        @SuppressWarnings("unchecked")
        private static <T extends View> T opt(View root, String name) {
            int id = root.getResources().getIdentifier(name, "id", root.getContext().getPackageName());
            if (id == 0) {
                return null;
            }
            return (T) root.findViewById(id);
        }
    }
}
