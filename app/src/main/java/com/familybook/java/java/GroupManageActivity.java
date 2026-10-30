package com.familybook.app;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * গ্রুপ ম্যানেজার স্ক্রিন (শুধু Admin ও Moderator)।
 *  কার্ড-লেআউট: লোকেশন/বর্ণনা · Join Requests (Accept/Decline) · Members · Post Settings ·
 *  Post Privacy · Blocked Users · Delete (সবার নিচে, শুধু Admin)।
 */
public class GroupManageActivity extends BaseActivity {

    public static final String EXTRA_DELETED = "deleted";

    private String gid, myUid;
    private String gName = "", gDesc = "", gLocation = "", gOwner = "";
    private boolean groupLoaded = false, membersLoaded = false;
    private int weekCount = -1;                 // -1 = জানা যায়নি (লাইনটি লুকানো)
    private String myRole = "";
    private String postMode = "auto";          // auto | admin_approval | admins_only
    private String postPrivacy = "open";       // open | view_only
    private final List<String> blockedUids = new ArrayList<>();
    private boolean blocksLoaded = false;
    private boolean showAllRequests = false;
    private int nameChanges = 0;               // এ পর্যন্ত কতবার নাম বদলানো হয়েছে
    private String nameChangedAt = "";         // শেষবার বদলানোর সময় (ISO, UTC)
    private final List<String[]> pendingPosts = new ArrayList<>();   // {postId, uid, text, "1" = সদস্য এডিট করেছেন}

    private final Map<String, String> roles = new LinkedHashMap<>();     // active সদস্য: uid -> role
    private final List<String> pendingUids = new ArrayList<>();
    private final Set<String> friendIds = new HashSet<>();
    private final Map<String, String> names = new HashMap<>();

    private LinearLayout content;
    private TextView tvTitle;
    private int cText, cSub, cBg, cDivider, cCard;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        myUid = SupabaseRest.uid();
        gid = getIntent().getStringExtra("gid");
        if (!SupabaseRest.isLoggedIn() || myUid == null || gid == null) { finish(); return; }

        cText = ContextCompat.getColor(this, R.color.h_text);
        cSub = ContextCompat.getColor(this, R.color.h_text_sub);
        cBg = ContextCompat.getColor(this, R.color.h_bg);
        cDivider = 0xFFE4E6EB;
        cCard = 0xFFFFFFFF;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF0F2F5);

        // টপ বার: ← গ্রুপের নাম
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        ImageView back = new ImageView(this);
        back.setImageResource(R.drawable.ic_gp_back);
        back.setPadding(dp(10), dp(10), dp(10), dp(10));
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(dp(44), dp(48)));
        tvTitle = new TextView(this);
        tvTitle.setTextColor(cText);
        tvTitle.setTextSize(17);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvTitle.setSingleLine(true);
        tvTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tp.setMarginStart(dp(6));
        bar.addView(tvTitle, tp);
        bar.setBackgroundColor(cCard);
        root.addView(bar, new LinearLayout.LayoutParams(-1, dp(48)));

        View line = new View(this);
        line.setBackgroundColor(cDivider);
        root.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), dp(10), dp(12), dp(28));
        sv.addView(content, new ViewGroup.LayoutParams(-1, -2));
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        setContentView(root);
        reload();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (groupLoaded) reload();     // See all থেকে ফিরলে সদস্য-তালিকা নতুন করে
    }

    // ------------------------------------------------------------------ লোড

    private void reload() {
        SupabaseRest.call("GET", "/rest/v1/groups?id=eq." + gid
                + "&select=name,description,location,owner_id,post_mode,post_privacy,name_changes,name_changed_at&limit=1", null, null, true, r -> {
            if (isFinishing()) return;
            if (!r.ok()) { TopToast.show(this, SupabaseRest.errorText(r)); return; }
            JSONObject g = first(r.body);
            if (g == null) { TopToast.show(this, "Group not found"); finish(); return; }
            gName = Db.s(g, "name");
            gDesc = Db.s(g, "description");
            gLocation = Db.s(g, "location");
            nameChanges = g.optInt("name_changes", 0);
            nameChangedAt = Db.s(g, "name_changed_at");
            gOwner = Db.s(g, "owner_id");
            String pm = Db.s(g, "post_mode"), pp = Db.s(g, "post_privacy");
            postMode = pm.isEmpty() ? "auto" : pm;
            postPrivacy = pp.isEmpty() ? "open" : pp;
            groupLoaded = true;
            render();
        });
        SupabaseRest.call("GET", "/rest/v1/group_members?group_id=eq." + gid
                + "&select=user_id,role,status&limit=3000", null, null, true, r -> {
            if (isFinishing() || !r.ok()) return;
            roles.clear();
            pendingUids.clear();
            myRole = "";
            JSONArray a = arr(r.body);
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                String uid = Db.s(o, "user_id"), role = Db.s(o, "role"), st = Db.s(o, "status");
                if (uid.isEmpty()) continue;
                if ("pending".equals(st)) { pendingUids.add(uid); continue; }
                if (role.isEmpty()) role = "member";
                roles.put(uid, role);
                if (uid.equals(myUid)) myRole = role;
            }
            if (!isManager()) {
                TopToast.show(this, "Only Admins and Moderators can view this page");
                finish();
                return;
            }
            membersLoaded = true;
            fetchNames(namesNeeded(), this::render);
            render();
        });
        SupabaseRest.call("GET", "/rest/v1/friendships?user_id=eq." + myUid + "&select=friend_id", null, null, true, r -> {
            if (isFinishing() || !r.ok()) return;
            friendIds.clear();
            JSONArray a = arr(r.body);
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null) friendIds.add(Db.s(o, "friend_id"));
            }
            fetchNames(namesNeeded(), this::render);
            render();
        });
        // গত ৭ দিনে নতুন সদস্য (created_at কলাম না থাকলে লাইনটি লুকানো থাকে)
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            String since = URLEncoder.encode(f.format(new Date(System.currentTimeMillis() - 7L * 24 * 3600 * 1000)), "UTF-8");
            SupabaseRest.call("GET", "/rest/v1/group_members?group_id=eq." + gid
                    + "&status=eq.active&created_at=gte." + since + "&select=user_id", null, null, true, r -> {
                if (isFinishing()) return;
                if (!r.ok()) { weekCount = -1; return; }
                JSONArray a = arr(r.body);
                weekCount = a == null ? -1 : a.length();
                render();
            });
        } catch (Exception ignored) { }
        loadBlocked();
        loadPendingPosts();
    }

    private void loadPendingPosts() {
        SupabaseRest.call("GET", "/rest/v1/posts?group_id=eq." + gid
                + "&status=eq.pending&order=created_at.asc&select=id,user_id,text,edited,pending_prev&limit=50", null, null, true, r -> {
            if (isFinishing() || !r.ok()) return;
            pendingPosts.clear();
            JSONArray a = arr(r.body);
            List<String> uids = new ArrayList<>();
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                // pending_prev থাকলে এটি নতুন পোস্ট নয়, আগে-অ্যাপ্রুভ-হওয়া পোস্টের এডিট
                boolean isEdit = o.optJSONObject("pending_prev") != null;
                pendingPosts.add(new String[]{Db.s(o, "id"), Db.s(o, "user_id"), Db.s(o, "text"), isEdit ? "1" : "0"});
                uids.add(Db.s(o, "user_id"));
            }
            fetchNames(uids, this::render);
            render();
        });
    }

    private void decidePost(final String[] post, final boolean approve, final View row) {
        // সরাসরি PATCH/DELETE করলে posts-এর সুরক্ষা (RLS) অন্যের পোস্টে ০ সারি বদলায় → "Permission denied"।
        // তাই ডাটাবেসের ফাংশন কল করি (ফাংশন নিজেই চেক করে ইউজার Admin/Moderator কি না)
        String fn = approve ? "approve_pending_post" : "reject_pending_post";
        SupabaseRest.call("POST", "/rest/v1/rpc/" + fn, "{\"p_post_id\":\"" + post[0] + "\"}",
                null, true, r -> {
            if (isFinishing()) return;
            if (!r.ok()) { TopToast.show(this, SupabaseRest.errorText(r)); return; }
            if (r.body == null || !r.body.trim().equals("true")) {
                TopToast.show(this, "This post is no longer pending");
                loadPendingPosts();
                return;
            }
            if (approve) NotificationHelper.sendGroup(post[1], "group_post_approved", gid,
                    "Your " + ("1".equals(post[3]) ? "edit" : "post") + " in \"" + gName + "\" was approved");
            row.setVisibility(View.GONE);
            loadPendingPosts();
        });
    }

    private void loadBlocked() {
        SupabaseRest.call("GET", "/rest/v1/group_blocks?group_id=eq." + gid + "&select=user_id&limit=1000",
                null, null, true, r -> {
            if (isFinishing()) return;
            blockedUids.clear();
            blocksLoaded = r.ok();
            JSONArray a = r.ok() ? arr(r.body) : null;
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null && !Db.s(o, "user_id").isEmpty()) blockedUids.add(Db.s(o, "user_id"));
            }
            render();
        });
    }

    private boolean isAdmin() { return "admin".equals(myRole); }
    private boolean isManager() { return "admin".equals(myRole) || "moderator".equals(myRole); }

    /** ছবির সারি, লেখার নাম ও অ্যাডমিনদের নাম */
    private List<String> namesNeeded() {
        List<String> need = new ArrayList<>(shownMembers());
        for (Map.Entry<String, String> e : roles.entrySet()) if ("admin".equals(e.getValue())) need.add(e.getKey());
        need.addAll(pendingUids);
        need.addAll(blockedUids);
        for (String[] pp : pendingPosts) need.add(pp[1]);
        return need;
    }

    /** সারিতে যাদের ছবি: আমার বন্ধুরা আগে, তারপর বাকিরা (আমি বাদে) — সর্বোচ্চ ১২ */
    private List<String> shownMembers() {
        List<String> fr = new ArrayList<>(), rest = new ArrayList<>();
        for (String u : roles.keySet()) {
            if (u.equals(myUid)) continue;
            if (friendIds.contains(u)) fr.add(u); else rest.add(u);
        }
        fr.addAll(rest);
        return fr.size() > 12 ? new ArrayList<>(fr.subList(0, 12)) : fr;
    }

    // ------------------------------------------------------------------ ছবি

    private void render() {
        if (!groupLoaded || !membersLoaded || content == null) return;
        tvTitle.setText(gName);
        content.removeAllViews();

        // ১) লোকেশন + বর্ণনা (এক কার্ডে)
        LinearLayout info = card();
        LinearLayout nameRow = row();
        TextView nm = new TextView(this);
        nm.setText("Change group name");
        nm.setTextSize(14);
        nm.setTypeface(Typeface.DEFAULT_BOLD);
        nm.setTextColor(cText);
        nameRow.addView(nm, new LinearLayout.LayoutParams(0, -2, 1f));
        nameRow.addView(editIcon(), new LinearLayout.LayoutParams(dp(18), dp(18)));
        nameRow.setOnClickListener(v -> openNameEditor());
        info.addView(nameRow);
        info.addView(divider());
        LinearLayout locRow = row();
        TextView loc = new TextView(this);
        loc.setText(gLocation.isEmpty() ? "Add location" : gLocation);
        loc.setTextSize(13);
        loc.setTextColor(gLocation.isEmpty() ? 0xFF9AA0A6 : cSub);
        locRow.addView(loc, new LinearLayout.LayoutParams(0, -2, 1f));
        locRow.addView(editIcon(), new LinearLayout.LayoutParams(dp(18), dp(18)));
        locRow.setOnClickListener(v -> editField("Location", gLocation, false, "location"));
        info.addView(locRow);
        info.addView(divider());
        LinearLayout descRow = row();
        TextView dt = new TextView(this);
        dt.setText(gDesc.isEmpty() ? "Add description" : gDesc);
        dt.setTextSize(14);
        dt.setTextColor(gDesc.isEmpty() ? 0xFF1877F2 : cText);
        if (gDesc.isEmpty()) dt.setTypeface(Typeface.DEFAULT_BOLD);
        else {
            GradientDrawable dbg = new GradientDrawable();
            dbg.setCornerRadius(dp(10));
            dbg.setColor(0xFFF7F8FA);
            dbg.setStroke(dp(1), cDivider);
            dt.setBackground(dbg);
            dt.setPadding(dp(10), dp(8), dp(10), dp(8));
        }
        descRow.addView(dt, new LinearLayout.LayoutParams(0, -2, 1f));
        descRow.addView(editIcon(), new LinearLayout.LayoutParams(dp(18), dp(18)));
        descRow.setOnClickListener(v -> editField("Description", gDesc, true, "description"));
        info.addView(descRow);
        content.addView(info, cardLp());

        // ২) Join requests (থাকলে) — সরাসরি Accept / Decline
        if (!pendingUids.isEmpty()) {
            LinearLayout rc = card();
            rc.addView(sectionHeader("Join Requests (" + pendingUids.size() + ")", null, null));
            int limit = showAllRequests ? pendingUids.size() : Math.min(3, pendingUids.size());
            for (int i = 0; i < limit; i++) {
                if (i > 0) rc.addView(divider());
                rc.addView(inlineRequestRow(pendingUids.get(i)));
            }
            if (pendingUids.size() > 3 && !showAllRequests) {
                rc.addView(divider());
                TextView more = new TextView(this);
                more.setText("See more (" + (pendingUids.size() - 3) + ")");
                more.setTextSize(13);
                more.setTypeface(Typeface.DEFAULT_BOLD);
                more.setTextColor(0xFF1877F2);
                more.setGravity(Gravity.CENTER);
                more.setPadding(0, dp(10), 0, dp(4));
                more.setOnClickListener(v -> { showAllRequests = true; render(); });
                rc.addView(more, new LinearLayout.LayoutParams(-1, -2));
            }
            content.addView(rc, cardLp());
        }

        // ২.৫) Pending Posts (Admin approval মোডে)
        if (!pendingPosts.isEmpty()) {
            LinearLayout pc = card();
            pc.addView(sectionHeader("Pending Posts (" + pendingPosts.size() + ")", null, null));
            for (int i = 0; i < pendingPosts.size(); i++) {
                if (i > 0) pc.addView(divider());
                pc.addView(pendingPostRow(pendingPosts.get(i)));
            }
            content.addView(pc, cardLp());
        }

        // ৩) Members
        LinearLayout mc = card();
        mc.addView(sectionHeader("Members", "See all", v -> startActivity(
                new Intent(this, GroupMembersActivity.class).putExtra("gid", gid).putExtra("gname", gName))));
        List<String> shown = shownMembers();
        if (!shown.isEmpty()) {
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
            sp.topMargin = dp(6);
            mc.addView(avatarStrip(shown, 32, 12), sp);
            CharSequence txt = joinedText(shown);
            if (txt != null) {
                TextView jt = new TextView(this);
                jt.setText(txt);
                jt.setTextSize(13);
                jt.setTextColor(cSub);
                LinearLayout.LayoutParams jp = new LinearLayout.LayoutParams(-1, -2);
                jp.topMargin = dp(6);
                mc.addView(jt, jp);
            }
        }
        List<String> admins = new ArrayList<>();
        for (Map.Entry<String, String> e : roles.entrySet()) if ("admin".equals(e.getValue())) admins.add(e.getKey());
        if (!admins.isEmpty()) {
            mc.addView(divider(), dividerLp(10));
            LinearLayout ar = new LinearLayout(this);
            ar.setOrientation(LinearLayout.HORIZONTAL);
            ar.setGravity(Gravity.CENTER_VERTICAL);
            ar.addView(avatarStrip(admins, 28, 6));
            TextView at = new TextView(this);
            at.setText(adminText(admins));
            at.setTextSize(13);
            at.setTextColor(cSub);
            LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(0, -2, 1f);
            ap.setMarginStart(dp(8));
            ar.addView(at, ap);
            mc.addView(ar, stripLp(8));
        }
        mc.addView(divider(), dividerLp(10));
        LinearLayout tot = new LinearLayout(this);
        tot.setOrientation(LinearLayout.HORIZONTAL);
        tot.setGravity(Gravity.CENTER_VERTICAL);
        ImageView pi = new ImageView(this);
        pi.setImageResource(R.drawable.ic_person_solid);
        pi.setColorFilter(cText);
        tot.addView(pi, new LinearLayout.LayoutParams(dp(18), dp(18)));
        TextView t1 = new TextView(this);
        t1.setText(roles.size() + " total member" + (roles.size() == 1 ? "" : "s"));
        t1.setTextSize(14);
        t1.setTypeface(Typeface.DEFAULT_BOLD);
        t1.setTextColor(cText);
        LinearLayout.LayoutParams t1p = new LinearLayout.LayoutParams(0, -2, 1f);
        t1p.setMarginStart(dp(8));
        tot.addView(t1, t1p);
        if (weekCount >= 0) {
            TextView t2 = new TextView(this);
            t2.setText("+" + weekCount + " this week");
            t2.setTextSize(12);
            t2.setTextColor(0xFF2E7D32);
            tot.addView(t2);
        }
        mc.addView(tot, stripLp(10));
        content.addView(mc, cardLp());

        // ৪) Settings কার্ড: Post Settings · Post Privacy · Blocked Users
        LinearLayout sc = card();
        sc.addView(navRow("Post Settings", postModeLabel(), v -> choosePostMode()));
        sc.addView(divider());
        sc.addView(navRow("Post Privacy", postPrivacyLabel(), v -> choosePostPrivacy()));
        sc.addView(divider());
        sc.addView(navRow("Blocked Users" + (blocksLoaded ? " (" + blockedUids.size() + ")" : ""),
                "Blocked users can't enter the group", v -> showBlocked()));
        content.addView(sc, cardLp());

        // ৫) Delete — একদম নিচে
        LinearLayout dc = card();
        LinearLayout dr = new LinearLayout(this);
        dr.setOrientation(LinearLayout.VERTICAL);
        dr.setPadding(dp(4), dp(8), dp(4), dp(8));
        dr.setBackground(ContextCompat.getDrawable(this, android.R.drawable.list_selector_background));
        dr.setClickable(true);
        TextView del = new TextView(this);
        del.setText("Delete group");
        del.setTextSize(15);
        del.setTypeface(Typeface.DEFAULT_BOLD);
        del.setTextColor(0xFFE53935);
        dr.addView(del);
        TextView dn = new TextView(this);
        dn.setText("[ Delete group permanently. only admin can delete ]");
        dn.setTextSize(11);
        dn.setTextColor(0xFF9AA0A6);
        LinearLayout.LayoutParams dnp = new LinearLayout.LayoutParams(-2, -2);
        dnp.topMargin = dp(2);
        dr.addView(dn, dnp);
        dr.setOnClickListener(v -> confirmDelete());
        dc.addView(dr);
        content.addView(dc, cardLp());
    }

    // ------------------------------------------------------------------ UI helpers

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(12), dp(8), dp(12), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(cCard);
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), cDivider);
        c.setBackground(bg);
        return c;
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = dp(10);
        return p;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(0xFFF0F2F5);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(1)));
        return v;
    }

    private LinearLayout.LayoutParams dividerLp(int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(1));
        p.topMargin = dp(topDp);
        return p;
    }

    /** ছোট সেকশন-হেডার; right দিলে ডানে নীল লিংক */
    private View sectionHeader(String title, String right, View.OnClickListener onRight) {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(cText);
        h.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        if (right != null) {
            TextView r = new TextView(this);
            r.setText(right);
            r.setTextSize(13);
            r.setTextColor(0xFF1877F2);
            r.setPadding(dp(8), dp(4), 0, dp(4));
            r.setOnClickListener(onRight);
            h.addView(r);
        }
        return h;
    }

    /** শিরোনাম + ছোট সাবটাইটেল + ডানে › */
    private View navRow(String title, String sub, View.OnClickListener click) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(2), dp(10), dp(2), dp(10));
        r.setBackground(ContextCompat.getDrawable(this, android.R.drawable.list_selector_background));
        r.setClickable(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(14);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(cText);
        col.addView(t);
        if (sub != null && !sub.isEmpty()) {
            TextView s = new TextView(this);
            s.setText(sub);
            s.setTextSize(12);
            s.setTextColor(cSub);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
            sp.topMargin = dp(2);
            col.addView(s, sp);
        }
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextSize(22);
        arrow.setTextColor(0xFF9AA0A6);
        r.addView(arrow);
        r.setOnClickListener(click);
        return r;
    }

    private String postModeLabel() {
        if ("admin_approval".equals(postMode)) return "Anyone can post (Admin approval)";
        if ("admins_only".equals(postMode)) return "Nobody can post (Admin & Moderator only)";
        return "Anyone can post (Auto approval)";
    }

    private String postPrivacyLabel() {
        return "view_only".equals(postPrivacy)
                ? "Nobody can like, comment, share (only see posts)"
                : "Anyone can like, comment, share";
    }

    /** Join request — ছোট সারি: ছবি · "Karim wants to join this group" · Accept / Decline */
    private View inlineRequestRow(final String uid) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));
        ImageView av = new ImageView(this);
        row.addView(av, new LinearLayout.LayoutParams(dp(36), dp(36)));
        AvatarLoader.load(av, uid);
        av.setOnClickListener(v -> ProfileActivity.open(this, uid, nameOf(uid)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append(nameOf(uid));
        sb.setSpan(new StyleSpan(Typeface.BOLD), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append(" wants to join this group");
        t.setText(sb);
        t.setTextSize(13);
        t.setTextColor(cText);
        col.addView(t);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        final TextView ok = chip("Accept", true), no = chip("Decline", false);
        btns.addView(ok);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-2, -2);
        np.setMarginStart(dp(8));
        btns.addView(no, np);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
        bp.topMargin = dp(6);
        col.addView(btns, bp);

        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -2, 1f);
        cp.setMarginStart(dp(10));
        row.addView(col, cp);
        wireRequestButtons(row, ok, no, uid);
        return row;
    }

    private View pendingPostRow(final String[] post) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));
        TextView who = new TextView(this);
        SpannableStringBuilder sb = new SpannableStringBuilder(nameOf(post[1]));
        sb.setSpan(new StyleSpan(Typeface.BOLD), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append("1".equals(post[3]) ? " edited a post" : " wants to post");
        who.setText(sb);
        who.setTextSize(13);
        who.setTextColor(cText);
        row.addView(who);
        TextView body = new TextView(this);
        body.setText(post[2].isEmpty() ? "(photo)" : post[2]);
        body.setTextSize(12);
        body.setTextColor(cSub);
        body.setMaxLines(3);
        body.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
        bp.topMargin = dp(3);
        row.addView(body, bp);
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        TextView ok = chip("Approve", true), no = chip("Decline", false);
        btns.addView(ok);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-2, -2);
        np.setMarginStart(dp(8));
        btns.addView(no, np);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, -2);
        bl.topMargin = dp(6);
        row.addView(btns, bl);
        ok.setOnClickListener(v -> { ok.setOnClickListener(null); no.setOnClickListener(null); decidePost(post, true, row); });
        no.setOnClickListener(v -> { ok.setOnClickListener(null); no.setOnClickListener(null); decidePost(post, false, row); });
        return row;
    }

    private void wireRequestButtons(final View row, final TextView ok, final TextView no, final String uid) {
        ok.setOnClickListener(v -> {
            ok.setOnClickListener(null); no.setOnClickListener(null);
            SupabaseRest.call("PATCH", "/rest/v1/group_members?group_id=eq." + gid + "&user_id=eq." + uid,
                    "{\"status\":\"active\"}", "return=representation", true, r -> {
                        if (isFinishing()) return;
                        JSONArray a = r.ok() ? arr(r.body) : null;
                        if (a == null || a.length() == 0) { TopToast.show(this, r.ok() ? "Permission denied" : SupabaseRest.errorText(r)); wireRequestButtons(row, ok, no, uid); return; }
                        NotificationHelper.sendGroup(uid, "group_join_approved", gid,
                                "Your request to join \"" + gName + "\" was approved");
                        row.setVisibility(View.GONE);
                        reload();
                    });
        });
        no.setOnClickListener(v -> {
            ok.setOnClickListener(null); no.setOnClickListener(null);
            SupabaseRest.call("DELETE", "/rest/v1/group_members?group_id=eq." + gid + "&user_id=eq." + uid
                    + "&status=eq.pending", null, "return=minimal", true, r -> {
                if (isFinishing()) return;
                if (!r.ok()) { TopToast.show(this, SupabaseRest.errorText(r)); wireRequestButtons(row, ok, no, uid); return; }
                row.setVisibility(View.GONE);
                reload();
            });
        });
    }

    // ------------------------------------------------------------------ Post Settings / Privacy

    private void choosePostMode() {
        showOptionCards("Post Settings",
                new String[]{"auto", "admin_approval", "admins_only"},
                new String[]{"Anyone can post", "Anyone can post", "Nobody can post"},
                new String[]{"Auto approval \u2014 posts appear instantly",
                        "Admin approval \u2014 posts appear after an admin approves",
                        "Only Admin & Moderator can post"},
                postMode, "auto", key -> saveSetting("post_mode", key));
    }

    private void choosePostPrivacy() {
        showOptionCards("Post Privacy",
                new String[]{"open", "view_only"},
                new String[]{"Anyone can like, comment, share", "Nobody can like, comment, share"},
                new String[]{"Everyone in the group can react to posts",
                        "People can only see posts"},
                postPrivacy, "open", key -> saveSetting("post_privacy", key));
    }

    private interface Pick { void on(String key); }

    /** রেডিও-বাটন কার্ড: আগে বাছুন, তারপর নিচের Save চাপলে তবেই সেভ হয় */
    private void showOptionCards(String title, final String[] keys, final String[] heads, final String[] subs,
                                 final String current, final String defKey, final Pick pick) {
        final String[] chosen = {current != null ? current : defKey};
        final AlertDialog[] dlg = new AlertDialog[1];
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(6), dp(16), dp(12));
        final Runnable[] draw = new Runnable[1];
        draw[0] = () -> {
            box.removeAllViews();
            for (int i = 0; i < keys.length; i++) {
                final String key = keys[i];
                boolean sel = key.equals(chosen[0]);
                LinearLayout card = new LinearLayout(this);
                card.setOrientation(LinearLayout.HORIZONTAL);
                card.setGravity(Gravity.CENTER_VERTICAL);
                card.setPadding(dp(12), dp(12), dp(12), dp(12));
                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(dp(12));
                bg.setColor(sel ? 0xFFE7F0FD : 0xFFFFFFFF);
                bg.setStroke(dp(sel ? 2 : 1), sel ? 0xFF1877F2 : 0xFFD0D3D8);
                card.setBackground(bg);

                // রেডিও বাটন: বাইরে গোল বর্ডার, বাছা হলে ভেতরে নীল বিন্দু
                FrameLayout radio = new FrameLayout(this);
                GradientDrawable ro = new GradientDrawable();
                ro.setShape(GradientDrawable.OVAL);
                ro.setColor(0xFFFFFFFF);
                ro.setStroke(dp(2), sel ? 0xFF1877F2 : 0xFFB0B3B8);
                radio.setBackground(ro);
                if (sel) {
                    View dot = new View(this);
                    GradientDrawable dd = new GradientDrawable();
                    dd.setShape(GradientDrawable.OVAL);
                    dd.setColor(0xFF1877F2);
                    dot.setBackground(dd);
                    FrameLayout.LayoutParams dp0 = new FrameLayout.LayoutParams(dp(10), dp(10), Gravity.CENTER);
                    radio.addView(dot, dp0);
                }
                card.addView(radio, new LinearLayout.LayoutParams(dp(20), dp(20)));

                LinearLayout col = new LinearLayout(this);
                col.setOrientation(LinearLayout.VERTICAL);
                LinearLayout head = new LinearLayout(this);
                head.setOrientation(LinearLayout.HORIZONTAL);
                head.setGravity(Gravity.CENTER_VERTICAL);
                TextView h = new TextView(this);
                h.setText(heads[i]);
                h.setTextSize(14);
                h.setTypeface(Typeface.DEFAULT_BOLD);
                h.setTextColor(cText);
                head.addView(h);
                if (key.equals(defKey)) {
                    TextView df = new TextView(this);
                    df.setText("Default");
                    df.setTextSize(10);
                    df.setTextColor(0xFF1877F2);
                    df.setPadding(dp(6), dp(1), dp(6), dp(1));
                    GradientDrawable dbg = new GradientDrawable();
                    dbg.setCornerRadius(dp(8));
                    dbg.setStroke(dp(1), 0xFF1877F2);
                    df.setBackground(dbg);
                    LinearLayout.LayoutParams dfp = new LinearLayout.LayoutParams(-2, -2);
                    dfp.setMarginStart(dp(8));
                    head.addView(df, dfp);
                }
                col.addView(head);
                TextView sb = new TextView(this);
                sb.setText(subs[i]);
                sb.setTextSize(12);
                sb.setTextColor(cSub);
                LinearLayout.LayoutParams sbp = new LinearLayout.LayoutParams(-2, -2);
                sbp.topMargin = dp(2);
                col.addView(sb, sbp);
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -2, 1f);
                cp.setMarginStart(dp(12));
                card.addView(col, cp);

                card.setOnClickListener(v -> { chosen[0] = key; draw[0].run(); });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.bottomMargin = dp(8);
                box.addView(card, lp);
            }
            // Save / Cancel
            LinearLayout btns = new LinearLayout(this);
            btns.setOrientation(LinearLayout.HORIZONTAL);
            TextView cancel = new TextView(this);
            cancel.setText("Cancel");
            cancel.setTextSize(14);
            cancel.setTypeface(Typeface.DEFAULT_BOLD);
            cancel.setGravity(Gravity.CENTER);
            cancel.setTextColor(0xFF4B4C4F);
            GradientDrawable cb = new GradientDrawable();
            cb.setCornerRadius(dp(10));
            cb.setColor(0xFFE4E6EB);
            cancel.setBackground(cb);
            cancel.setOnClickListener(v -> { if (dlg[0] != null) dlg[0].dismiss(); });
            btns.addView(cancel, new LinearLayout.LayoutParams(0, dp(42), 1f));
            TextView save = new TextView(this);
            save.setText("Save");
            save.setTextSize(14);
            save.setTypeface(Typeface.DEFAULT_BOLD);
            save.setGravity(Gravity.CENTER);
            save.setTextColor(Color.WHITE);
            GradientDrawable sbg = new GradientDrawable();
            sbg.setCornerRadius(dp(10));
            sbg.setColor(0xFF1877F2);
            save.setBackground(sbg);
            save.setOnClickListener(v -> {
                if (dlg[0] != null) dlg[0].dismiss();
                if (!chosen[0].equals(current != null ? current : defKey)) pick.on(chosen[0]);
            });
            LinearLayout.LayoutParams svp = new LinearLayout.LayoutParams(0, dp(42), 1f);
            svp.setMarginStart(dp(10));
            btns.addView(save, svp);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
            bp.topMargin = dp(6);
            box.addView(btns, bp);
        };
        draw[0].run();
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        dlg[0] = new AlertDialog.Builder(this).setTitle(title).setView(sv).show();
    }

    private void saveSetting(final String column, final String value) {
        try {
            JSONObject b = new JSONObject().put(column, value);
            SupabaseRest.call("PATCH", "/rest/v1/groups?id=eq." + gid, b.toString(), "return=representation", true, r -> {
                if (isFinishing()) return;
                JSONArray a = r.ok() ? arr(r.body) : null;
                if (a == null || a.length() == 0) {
                    TopToast.show(this, r.ok() ? "Permission denied" : SupabaseRest.errorText(r));
                    return;
                }
                if ("post_mode".equals(column)) postMode = value; else postPrivacy = value;
                TopToast.show(this, "Saved");
                render();
            });
        } catch (Exception e) {
            TopToast.show(this, "Could not save");
        }
    }

    // ------------------------------------------------------------------ Blocked users

    private void showBlocked() {
        if (blockedUids.isEmpty()) { TopToast.show(this, "No blocked users"); return; }
        fetchNames(new ArrayList<>(blockedUids), () -> {
            if (isFinishing()) return;
            LinearLayout list = new LinearLayout(this);
            list.setOrientation(LinearLayout.VERTICAL);
            list.setPadding(dp(16), dp(8), dp(16), 0);
            for (String uid : new ArrayList<>(blockedUids)) list.addView(blockedRow(uid));
            ScrollView s = new ScrollView(this);
            s.addView(list);
            new AlertDialog.Builder(this).setTitle("Blocked Users")
                    .setMessage("Blocked users can't enter the group")
                    .setView(s).setNegativeButton("Close", null).show();
        });
    }

    private View blockedRow(final String uid) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));
        ImageView av = new ImageView(this);
        row.addView(av, new LinearLayout.LayoutParams(dp(34), dp(34)));
        AvatarLoader.load(av, uid);
        TextView t = new TextView(this);
        t.setText(nameOf(uid));
        t.setTextSize(14);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.leftMargin = dp(10);
        row.addView(t, lp);
        final TextView un = chip("Unblock", false);
        row.addView(un);
        un.setOnClickListener(v -> {
            un.setOnClickListener(null);
            SupabaseRest.call("DELETE", "/rest/v1/group_blocks?group_id=eq." + gid + "&user_id=eq." + uid,
                    null, "return=minimal", true, r -> {
                if (isFinishing()) return;
                if (!r.ok()) { TopToast.show(this, SupabaseRest.errorText(r)); return; }
                row.setVisibility(View.GONE);
                blockedUids.remove(uid);
                render();
            });
        });
        return row;
    }

    // ------------------------------------------------------------------ Join requests

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(2), dp(8), dp(2), dp(8));
        r.setBackground(ContextCompat.getDrawable(this, android.R.drawable.list_selector_background));
        r.setClickable(true);
        return r;
    }

    private ImageView editIcon() {
        ImageView ic = new ImageView(this);
        ic.setImageResource(R.drawable.ic_edit_square);
        ic.setColorFilter(cText);
        return ic;
    }

    private LinearLayout.LayoutParams stripLp(int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.topMargin = dp(topDp);
        return p;
    }

    /** একটু একটু ঢেকে থাকা গোল ছবির সারি */
    private View avatarStrip(List<String> uids, int sizeDp, int max) {
        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        int n = 0;
        for (String u : uids) {
            if (n++ >= max) break;
            ImageView iv = new ImageView(this);
            GradientDrawable ring = new GradientDrawable();
            ring.setShape(GradientDrawable.OVAL);
            ring.setColor(cBg);
            iv.setBackground(ring);
            iv.setPadding(dp(2), dp(2), dp(2), dp(2));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(sizeDp + 4), dp(sizeDp + 4));
            if (n > 1 && max > 6) lp.setMarginStart(-dp(8));
            else if (n > 1) lp.setMarginStart(dp(4));
            strip.addView(iv, lp);
            AvatarLoader.load(iv, u);
            final String uid = u;
            iv.setOnClickListener(v -> ProfileActivity.open(this, uid, nameOf(uid)));
        }
        return strip;
    }

    /** "A, B and 65 other friends have joined" (নাম বোল্ড) */
    private CharSequence joinedText(List<String> shown) {
        boolean friendsOnly = false;
        List<String> pool = new ArrayList<>();
        for (String u : roles.keySet()) if (!u.equals(myUid) && friendIds.contains(u)) pool.add(u);
        if (!pool.isEmpty()) friendsOnly = true;
        else for (String u : roles.keySet()) if (!u.equals(myUid)) pool.add(u);
        if (pool.isEmpty()) return null;

        List<String> nm = new ArrayList<>();
        for (int i = 0; i < Math.min(2, pool.size()); i++) nm.add(nameOf(pool.get(i)));
        int rest = pool.size() - nm.size();
        String noun = friendsOnly ? "friend" : "member";

        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (int i = 0; i < nm.size(); i++) {
            int s = sb.length();
            sb.append(nm.get(i));
            sb.setSpan(new StyleSpan(Typeface.BOLD), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (i < nm.size() - 1) sb.append(rest > 0 ? ", " : " and ");
        }
        if (rest > 0) sb.append(" and ").append(String.valueOf(rest)).append(" other ").append(noun).append(rest == 1 ? "" : "s").append(" have joined");
        else sb.append(nm.size() == 1 ? " has joined" : " have joined");
        return sb;
    }

    private CharSequence adminText(List<String> admins) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (int i = 0; i < admins.size(); i++) {
            int s = sb.length();
            sb.append(nameOf(admins.get(i)));
            sb.setSpan(new StyleSpan(Typeface.BOLD), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (i < admins.size() - 2) sb.append(", ");
            else if (i == admins.size() - 2) sb.append(" and ");
        }
        sb.append(admins.size() == 1 ? " is an admin." : " are admins.");
        return sb;
    }

    // ------------------------------------------------------------------ লোকেশন / বর্ণনা এডিট

    // ------------------------------------------------------------------ গ্রুপের নাম বদলানো
    // নিয়ম: প্রথমবার যেকোনো সময়; এরপর ১ম বদলের ৩০ দিন, ২য়-র ৬০, ৩য়-র ৯০, ৪র্থ-র ১২০ ... দিন পর (৩০ × এ পর্যন্ত বদলের সংখ্যা)

    private static final long DAY_MS = 24L * 3600 * 1000;

    private long parseUtc(String iso) {
        try {
            if (iso == null || iso.length() < 19) return 0;
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date d = f.parse(iso.substring(0, 19));
            return d == null ? 0 : d.getTime();
        } catch (Exception e) { return 0; }
    }

    /** পরের বদল কখন থেকে সম্ভব (millis); 0 = এখনই */
    private long nameUnlockAt() {
        if (nameChanges <= 0) return 0;
        long last = parseUtc(nameChangedAt);
        return last == 0 ? 0 : last + 30L * nameChanges * DAY_MS;
    }

    private void openNameEditor() {
        long unlock = nameUnlockAt();
        long now = System.currentTimeMillis();
        if (unlock > now) {
            long days = (unlock - now + DAY_MS - 1) / DAY_MS;
            SimpleDateFormat df = new SimpleDateFormat("d MMM yyyy", Locale.ENGLISH);
            new AlertDialog.Builder(this).setTitle("Group name is locked")
                    .setMessage("You can change the group name again on " + df.format(new Date(unlock))
                            + " (" + days + (days == 1 ? " day" : " days") + " left).")
                    .setPositiveButton("OK", null).show();
            return;
        }
        editField("Group name", gName, false, "name");
    }

    private static final int DESC_MAX = 1000, DESC_MIN = 5;   // ডাটাবেসের groups_description_check এর সাথে মেলানো

    /** বর্ণনা/লোকেশন এডিট: আগের লেখা বক্সে বসানো থাকে, বদলে আবার Save করা যায় */
    private void editField(final String title, String current, boolean multi, final String column) {
        final EditText et = new EditText(this);
        et.setText(current);
        et.setSelection(et.getText().length());
        et.setHint(multi ? "Write something about this group..." : ("name".equals(column) ? "Group name" : "Add location"));
        et.setGravity(multi ? (Gravity.TOP | Gravity.START) : Gravity.START);
        et.setTextSize(14);
        et.setTextColor(cText);
        et.setHintTextColor(0xFF9AA0A6);
        et.setBackground(null);
        et.setInputType(multi ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES : InputType.TYPE_CLASS_TEXT);
        int max = multi ? DESC_MAX : ("name".equals(column) ? 60 : 120);
        et.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(max)});
        if (multi) { et.setMinLines(5); et.setMaxLines(9); et.setGravity(Gravity.TOP | Gravity.START); }
        else et.setSingleLine(true);
        et.setPadding(dp(12), dp(10), dp(12), dp(10));

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(18), dp(10), dp(18), 0);
        // গুগলের মতো আউটলাইন বক্স: বড় গোল কোণ, বর্ডারের ওপর ভাসমান লেবেল
        FrameLayout outer = new FrameLayout(this);
        FrameLayout boxFrame = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(0xFFFFFFFF);
        bg.setStroke(dp(2), 0xFF1877F2);
        boxFrame.setBackground(bg);
        et.setPadding(dp(14), dp(16), dp(14), dp(14));
        if (multi) { et.setMinLines(8); et.setMaxLines(12); et.setMinHeight(dp(190)); }
        boxFrame.addView(et, new FrameLayout.LayoutParams(-1, -2));
        FrameLayout.LayoutParams bfp = new FrameLayout.LayoutParams(-1, -2);
        bfp.topMargin = dp(9);
        outer.addView(boxFrame, bfp);
        TextView label = new TextView(this);
        label.setText(multi ? "Description" : title);
        label.setTextSize(12);
        label.setTextColor(0xFF1877F2);
        label.setBackgroundColor(0xFFFFFFFF);
        label.setPadding(dp(6), 0, dp(6), 0);
        FrameLayout.LayoutParams lbp = new FrameLayout.LayoutParams(-2, -2);
        lbp.setMarginStart(dp(14));
        outer.addView(label, lbp);
        wrap.addView(outer, new LinearLayout.LayoutParams(-1, -2));
        final TextView counter = new TextView(this);
        counter.setTextSize(11);
        counter.setTextColor(0xFF9AA0A6);
        counter.setGravity(Gravity.END);
        final boolean isDesc = multi;
        final int maxF = max;
        counter.setText(et.getText().length() + "/" + max);
        et.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int a, int b, int d) { }
            @Override public void onTextChanged(CharSequence c, int a, int b, int d) { }
            @Override public void afterTextChanged(android.text.Editable e) {
                boolean bad = isDesc && e.length() > 0 && e.length() < DESC_MIN;
                counter.setText(bad ? "Minimum " + DESC_MIN + " characters  \u00B7  " + e.length() + "/" + maxF
                        : e.length() + "/" + maxF);
                counter.setTextColor(bad ? 0xFFE53935 : 0xFF9AA0A6);
            }
        });
        LinearLayout.LayoutParams cnp = new LinearLayout.LayoutParams(-1, -2);
        cnp.topMargin = dp(4);
        wrap.addView(counter, cnp);
        if ("name".equals(column)) {
            TextView note = new TextView(this);
            note.setText("After you change the name, you must wait " + (30 * (nameChanges + 1))
                    + " days before you can change it again.");
            note.setTextSize(12);
            note.setTextColor(cSub);
            LinearLayout.LayoutParams np2 = new LinearLayout.LayoutParams(-1, -2);
            np2.topMargin = dp(8);
            wrap.addView(note, np2);
        }

        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(multi ? "Group description" : title).setView(wrap)
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null).create();
        d.show();
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String val = et.getText().toString().trim();
            if ("name".equals(column)) {
                if (val.isEmpty()) { TopToast.show(this, "Group name can't be empty"); return; }
                if (val.equals(gName)) { d.dismiss(); return; }
            }
            if (multi && !val.isEmpty() && val.length() < DESC_MIN) {
                TopToast.show(this, "Description must be at least " + DESC_MIN + " characters");
                return;
            }
            d.dismiss();
            saveColumn(column, val);
        });
        et.requestFocus();
        if (d.getWindow() != null)
            d.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
    }

    private void saveColumn(final String column, final String value) {
        try {
            JSONObject b = new JSONObject();
            b.put(column, value.isEmpty() ? JSONObject.NULL : value);
            SupabaseRest.call("PATCH", "/rest/v1/groups?id=eq." + gid, b.toString(), "return=representation", true, r -> {
                if (isFinishing()) return;
                JSONArray a = r.ok() ? arr(r.body) : null;
                if (a == null || a.length() == 0) {
                    String err = r.ok() ? "Permission denied" : SupabaseRest.errorText(r);
                    if (err != null && err.contains("description_check"))
                        err = "Description must be between 5 and 1000 characters";
                    if (err != null && err.contains("NAME_LOCKED"))
                        err = "You can't change the group name yet";
                    TopToast.show(this, err);
                    return;
                }
                if ("location".equals(column)) gLocation = value;
                else if ("name".equals(column)) { gName = value; tvTitle.setText(gName); }
                else gDesc = value;
                TopToast.show(this, "name".equals(column) ? "Group name updated" : "Saved");
                if ("name".equals(column)) reload(); else render();
            });
        } catch (Exception e) {
            TopToast.show(this, "Could not save");
        }
    }

    private TextView chip(String label, boolean primary) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextSize(12);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(14), dp(5), dp(14), dp(5));
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(dp(14));
        pill.setColor(primary ? 0xFF1877F2 : 0xFFE4E6EB);
        b.setBackground(pill);
        b.setTextColor(primary ? Color.WHITE : 0xFF4B4C4F);
        return b;
    }

    // ------------------------------------------------------------------ Delete

    private void confirmDelete() {
        if (!isAdmin()) {
            TopToast.show(this, "Only an Admin can delete the group");
            return;
        }
        new AlertDialog.Builder(this).setTitle("Delete group?")
                .setMessage("\"" + gName + "\" and all its posts and members will be permanently deleted. This can\'t be undone.")
                .setPositiveButton("Delete", (d, w) -> deleteGroup())
                .setNegativeButton("Cancel", null).show();
    }

    private void deleteGroup() {
        TopToast.show(this, "Deleting…");
        // পোস্ট ও সদস্য আগে (ডাটাবেসে cascade থাকলে এগুলো এমনিই যেত); ব্যর্থ হলেও গ্রুপ মোছার চেষ্টা চলে
        SupabaseRest.call("DELETE", "/rest/v1/posts?group_id=eq." + gid, null, "return=minimal", true, p ->
                SupabaseRest.call("DELETE", "/rest/v1/group_members?group_id=eq." + gid, null, "return=minimal", true, m ->
                        SupabaseRest.call("DELETE", "/rest/v1/groups?id=eq." + gid, null, "return=representation", true, r -> {
                            if (isFinishing()) return;
                            JSONArray a = r.ok() ? arr(r.body) : null;
                            if (a == null || a.length() == 0) {
                                TopToast.show(this, r.ok() ? "Permission denied — only an Admin can delete" : SupabaseRest.errorText(r));
                                reload();
                                return;
                            }
                            TopToast.show(this, "Group deleted");
                            setResult(RESULT_OK, new Intent().putExtra(EXTRA_DELETED, true));
                            finish();
                        })));
    }

    // ------------------------------------------------------------------ সাহায্যকারী

    private String nameOf(String uid) {
        String n = names.get(uid);
        return n == null || n.isEmpty() ? "Member" : n;
    }

    private void fetchNames(List<String> uids, final Runnable done) {
        List<String> need = new ArrayList<>();
        for (String u : uids) if (u != null && !u.isEmpty() && !names.containsKey(u) && !need.contains(u)) need.add(u);
        if (need.isEmpty()) { if (done != null) done.run(); return; }
        StringBuilder in = new StringBuilder();
        for (String u : need) { if (in.length() > 0) in.append(','); in.append(u); }
        SupabaseRest.call("GET", "/rest/v1/profiles?id=in.(" + in + ")&select=id,name", null, null, true, r -> {
            if (r.ok()) {
                JSONArray a = arr(r.body);
                for (int i = 0; a != null && i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o != null) names.put(Db.s(o, "id"), Db.s(o, "name"));
                }
            }
            if (!isFinishing() && done != null) done.run();
        });
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
}
