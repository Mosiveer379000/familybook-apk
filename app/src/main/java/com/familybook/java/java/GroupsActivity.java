package com.familybook.app;

import android.content.Intent;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Groups পেজ (Menu > Groups)।
 *  - উপরে: ব্যাক, মাঝে "Groups", + ও সার্চ (আপাতত ডেমো)
 *  - ট্যাব: Your group (আমার বানানো + জয়েন করা সব) / Public group (শুধু পাবলিক)
 *  - তালিকার ক্রম: আমার বানানো (admin) গ্রুপ সবার আগে, নতুনটি একদম শুরুতে, এগুলো সবসময় পিন
 *    > হাতে পিন করা > নতুন পোস্ট বেশি > নাম
 *  - প্রথমে ৩টি দেখায়; "See all" দিলে সব, আবার চাপলে ৩টি
 *  - "From your groups": প্রতি গ্রুপের একটি করে সবচেয়ে নতুন পোস্ট; প্রতিটির নিজস্ব "See more" চাপলে
 *    গ্রুপ খোলার জন্য (চাপলে গ্রুপের নিজস্ব পেজ (GroupDetailActivity) খোলে)
 *  - গ্রুপ না থাকলে শুধু সেকশনের শিরোনাম থাকে; See all / See more / পিন কিছুই দেখায় না
 */
public class GroupsActivity extends BaseActivity {

    private static final int TAB_YOUR = 0, TAB_PUBLIC = 1;
    private static final int COLLAPSED = 3;

    private String myUid;
    private int tab = TAB_YOUR;
    private boolean groupsExpanded = false;
    
    private final List<GroupItem> all = new ArrayList<>();

    private TextView tabYour, tabPublic, tvSectionTitle, tvSeeAll;
    private LinearLayout groupsList, postsList;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        myUid = SupabaseRest.uid();
        if (!SupabaseRest.isLoggedIn() || myUid == null) {
            finish();
            return;
        }
        setContentView(R.layout.activity_groups);

        tabYour = findViewById(R.id.tabYour);
        tabPublic = findViewById(R.id.tabPublic);
        tvSectionTitle = findViewById(R.id.tvSectionTitle);
        tvSeeAll = findViewById(R.id.tvSeeAll);
        groupsList = findViewById(R.id.groupsList);
        postsList = findViewById(R.id.postsList);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnPlus).setOnClickListener(v ->
                startActivity(new Intent(this, NewGroupActivity.class)));
        findViewById(R.id.btnSearch).setOnClickListener(v -> soon("Search groups"));

        tabYour.setOnClickListener(v -> selectTab(TAB_YOUR));
        tabPublic.setOnClickListener(v -> selectTab(TAB_PUBLIC));

        tvSeeAll.setOnClickListener(v -> {
            groupsExpanded = !groupsExpanded;
            renderGroups();
        });

        renderTabs();
        renderGroups();
        renderPosts();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (myUid == null) return;
        GroupsRepo.load(myUid, (groups, error) -> {
            if (error != null) {
                Toast.makeText(this, error, Toast.LENGTH_SHORT).show();
                return;
            }
            all.clear();
            if (groups != null) all.addAll(groups);
            renderGroups();
            renderPosts();
        });
    }

    // ---------- ট্যাব ----------

    private void selectTab(int t) {
        if (tab == t) return;
        tab = t;
        groupsExpanded = false;
        renderTabs();
        renderGroups();
    }

    private void renderTabs() {
        tabYour.setBackgroundResource(tab == TAB_YOUR ? R.drawable.bg_gp_tab_sel : 0);
        tabPublic.setBackgroundResource(tab == TAB_PUBLIC ? R.drawable.bg_gp_tab_sel : 0);
        tvSectionTitle.setText(tab == TAB_YOUR ? "Your groups" : "Public groups");
    }

    // ---------- গ্রুপ তালিকা ----------

    /** বর্তমান ট্যাব অনুযায়ী ছাঁকা + সাজানো তালিকা। */
    private List<GroupItem> visibleGroups() {
        final List<String> pins = GroupsRepo.pinned(this, myUid);
        List<GroupItem> out = new ArrayList<>();
        for (GroupItem g : all) {
            if (tab == TAB_PUBLIC && !g.isPublic) continue;
            out.add(g);
        }
        Collections.sort(out, new Comparator<GroupItem>() {
            @Override public int compare(GroupItem a, GroupItem b) {
                // আমার বানানো গ্রুপ সবার আগে (সবসময় পিন), নতুনটি একদম শুরুতে
                if (a.isAdmin != b.isAdmin) return a.isAdmin ? -1 : 1;
                if (a.isAdmin) return b.createdAt.compareTo(a.createdAt);
                // হাতে পিন করা
                int pa = pins.indexOf(a.id), pb = pins.indexOf(b.id);
                boolean ia = pa >= 0, ib = pb >= 0;
                if (ia != ib) return ia ? -1 : 1;
                if (ia) return Integer.compare(pa, pb);
                if (a.newPosts != b.newPosts) return Integer.compare(b.newPosts, a.newPosts);
                String an = a.name == null ? "" : a.name, bn = b.name == null ? "" : b.name;
                return an.compareToIgnoreCase(bn);
            }
        });
        return out;
    }

    private void renderGroups() {
        groupsList.removeAllViews();
        List<GroupItem> list = visibleGroups();
        List<String> pins = GroupsRepo.pinned(this, myUid);

        int show = groupsExpanded ? list.size() : Math.min(COLLAPSED, list.size());
        for (int i = 0; i < show; i++) {
            groupsList.addView(buildRow(list.get(i), list.get(i).isAdmin || pins.contains(list.get(i).id)));
        }

        // See all শুধু তখনই, যখন ৩টির বেশি গ্রুপ আছে
        if (list.size() > COLLAPSED) {
            tvSeeAll.setVisibility(View.VISIBLE);
            tvSeeAll.setText(groupsExpanded ? "Show less" : "See all");
        } else {
            tvSeeAll.setVisibility(View.GONE);
        }
    }

    private View buildRow(final GroupItem g, boolean pinned) {
        View row = getLayoutInflater().inflate(R.layout.item_group_row, groupsList, false);
        ((TextView) row.findViewById(R.id.tvGroupName)).setText(g.name);

        ImageView imgAdmin = row.findViewById(R.id.imgAdmin);
        TextView sub = row.findViewById(R.id.tvGroupSub);
        View dot = row.findViewById(R.id.dotNew);

        String members = g.members + (g.members == 1 ? " member" : " members");
        if (g.isAdmin) {
            imgAdmin.setVisibility(View.VISIBLE);
            sub.setText("Admin \u00B7 " + members);
            dot.setVisibility(View.GONE);
        } else if (g.newPosts > 0) {
            imgAdmin.setVisibility(View.GONE);
            sub.setText((g.newPosts > 25 ? "25+" : String.valueOf(g.newPosts)) + " new posts");
            dot.setVisibility(View.VISIBLE);
        } else {
            imgAdmin.setVisibility(View.GONE);
            sub.setText(members);
            dot.setVisibility(View.GONE);
        }

        ImageView pin = row.findViewById(R.id.btnPin);
        pin.setImageResource(pinned ? R.drawable.ic_gp_pin_on : R.drawable.ic_gp_pin);
        pin.setOnClickListener(v -> {
            if (g.isAdmin) {
                Toast.makeText(this, "আপনার তৈরি গ্রুপ সবসময় পিন থাকে", Toast.LENGTH_SHORT).show();
                return;
            }
            boolean ok = GroupsRepo.togglePin(this, myUid, g.id);
            if (!ok) {
                Toast.makeText(this, "সর্বোচ্চ " + GroupsRepo.MAX_PINS + "টি গ্রুপ পিন করা যায়",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            renderGroups();
        });

        // সারিতে চাপলে গ্রুপের নিজস্ব পেজ খোলে (পিন বাটনে চাপলে শুধু পিন হয়)
        row.setOnClickListener(v -> openGroup(g));
        return row;
    }

    // ---------- From your groups ----------

    private void renderPosts() {
        postsList.removeAllViews();

        // সব গ্রুপ (দুই ট্যাবের) থেকে একটি করে নতুন পোস্ট, সবচেয়ে নতুন আগে
        List<GroupItem> withPost = new ArrayList<>();
        for (GroupItem g : all) if (g.hasPost()) withPost.add(g);
        Collections.sort(withPost, (a, b) -> Long.compare(b.lastPostTime, a.lastPostTime));

        for (GroupItem g : withPost) {
            postsList.addView(buildPost(g));
        }

        // পোস্টগুলোর ছবির তথ্য এনে (না থাকলে) কার্ডগুলো আবার সাজানো
        List<Post> ps = new ArrayList<>();
        for (GroupItem g : withPost) {
            if (g.lastPostId == null) continue;
            Post p = new Post();
            p.id = g.lastPostId;
            ps.add(p);
        }
        if (!ps.isEmpty()) {
            PostImage.prefetch(ps, () -> {
                if (!isFinishing() && !isDestroyed()) renderPosts();
            });
        }
    }

    private View buildPost(final GroupItem g) {
        View card = getLayoutInflater().inflate(R.layout.item_group_post, postsList, false);
        ((TextView) card.findViewById(R.id.tvPostGroup)).setText(g.name);

        String when = g.lastPostTime > 0
                ? DateUtils.getRelativeTimeSpanString(g.lastPostTime,
                        System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
                : "";
        String author = g.lastPostAuthor == null ? "" : g.lastPostAuthor;
        String meta = author.isEmpty() ? when : (when.isEmpty() ? author : author + " \u00B7 " + when);
        ((TextView) card.findViewById(R.id.tvPostMeta)).setText(meta);
        ((TextView) card.findViewById(R.id.tvPostText)).setText(g.lastPostText);

        ImageView photo = card.findViewById(R.id.imgPostPhoto);
        if (photo != null) PostImage.bindFirst(photo, g.lastPostId);

        // কার্ডে বা তার নিজের "See more"-এ চাপলে ঠিক এই গ্রুপটিই খুলবে
        card.setOnClickListener(v -> openGroup(g));
        card.findViewById(R.id.tvPostSeeMore).setOnClickListener(v -> openGroup(g));
        return card;
    }

    // ---------- সাহায্যকারী ----------

    private void openGroup(GroupItem g) {
        GroupDetailActivity.open(this, g.id);
    }

    private void soon(String what) {
        Toast.makeText(this, what + " \u2014 শীঘ্রই আসছে (Demo)", Toast.LENGTH_SHORT).show();
    }
}
