package com.familybook.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.format.DateUtils;
import android.text.style.StyleSpan;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Members পেজ: Online / Followers / All তিনটি ট্যাব।
 * শুধু ফলো-সম্পর্কের মানুষ (আমি যাকে বা যে আমাকে ফলো করে) তালিকায় আসে।
 * নিচে: "People you may know" (বড় ছবির কার্ড) ও "Suggested for you" (বাটন ছাড়া সারি)।
 * ব্লক blocked_users টেবিলে সেভ হয় এবং Settings → Blocking-এ দেখা যায়।
 */
public class MembersActivity extends BaseActivity {

    private static final int TAB_ONLINE = 0, TAB_FOLLOWERS = 1, TAB_ALL = 2;
    private static final int PAGE = 10;          // তালিকায় ও কার্ডে একবারে ১০ জন
    private static final int ROWS_MAX = 5;       // "Suggested for you"-তে সর্বোচ্চ সারি

    private RecyclerView rvMembers, rvPeople, rvSugRows;
    private MembersAdapter adapter;
    private SuggestAdapter peopleAdapter;
    private SuggestRowAdapter rowsAdapter;
    private View tabOnline, sectionPeople, sectionSuggested;
    private TextView tvOnlineLabel, tabFollowers, tabAll, tvEmptyList, tvNotifEmpty;
    private TextView tvRecentTitle, tvRecentTime, btnSeeMore, tvListSeeMore;
    private View cardRecent;
    private ImageView topAvatar;

    private String myUid;
    private String myName = "Family Member";
    private int tab = TAB_FOLLOWERS;
    private int limit = PAGE;        // তালিকায় কতজন দেখাচ্ছে
    private int cardLimit = PAGE;    // People you may know-এ কতজন দেখাচ্ছে
    private int candCount = 0;       // কার্ডের সম্ভাব্য মোট প্রার্থী
    private final Handler ui = new Handler(Looper.getMainLooper());
    private static final long POLL_MS = 15000;

    // ডেটা
    private final List<String> followerIds = new ArrayList<>();
    private final Set<String> iFollow = new HashSet<>();
    private final Set<String> onlineIds = new HashSet<>();
    private final Set<String> myBlocked = new HashSet<>();
    private final Set<String> blockedByIds = new HashSet<>();   // যারা আমাকে ব্লক করেছে
    private boolean blockedByLoaded = false;
    private final Map<String, String[]> infoCache = new HashMap<>();  // uid -> {name, city}
    private final Set<String> infoLoading = new HashSet<>();
    private final List<String> suggestPool = new ArrayList<>();       // সব প্রোফাইল (এলোমেলো)
    private boolean poolRequested = false;
    private final Set<String> dismissed = new HashSet<>();            // Remove চাপা সাজেশন
    private List<JSONObject> followNotifs = null;
    private boolean followersLoaded = false;
    private boolean started = false;

    private final Runnable refreshRunnable = this::refreshList;
    private final Runnable pollRunnable = new Runnable() {
        @Override public void run() {
            if (!started) return;
            loadLive();
            ui.postDelayed(this, POLL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        myUid = SupabaseRest.uid();
        if (!SupabaseRest.isLoggedIn() || myUid == null) { goLogin(); return; }
        setContentView(R.layout.activity_members);

        dismissed.addAll(prefs().getStringSet("dismissed_" + myUid, new HashSet<String>()));

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnTopSearch).setOnClickListener(v ->
                startActivity(new Intent(this, SearchActivity.class)));
        topAvatar = findViewById(R.id.imgTopAvatar);
        AvatarLoader.load(topAvatar, myUid);
        topAvatar.setOnClickListener(v -> ProfileActivity.open(this, myUid, myName));

        tabOnline = findViewById(R.id.tabOnline);
        tvOnlineLabel = findViewById(R.id.tvOnlineLabel);
        tabFollowers = findViewById(R.id.tabFollowers);
        tabAll = findViewById(R.id.tabAll);
        tvEmptyList = findViewById(R.id.tvEmptyList);
        tvNotifEmpty = findViewById(R.id.tvNotifEmpty);
        cardRecent = findViewById(R.id.cardRecent);
        tvRecentTitle = findViewById(R.id.tvRecentTitle);
        tvRecentTime = findViewById(R.id.tvRecentTime);
        btnSeeMore = findViewById(R.id.btnSeeMore);
        tvListSeeMore = findViewById(R.id.tvListSeeMore);
        sectionPeople = findViewById(R.id.sectionPeople);
        sectionSuggested = findViewById(R.id.sectionSuggested);
        rvMembers = findViewById(R.id.rvMembers);
        rvPeople = findViewById(R.id.rvPeople);
        rvSugRows = findViewById(R.id.rvSuggestedRows);

        // সদস্য তালিকা
        adapter = new MembersAdapter(new MembersAdapter.Actions() {
            @Override public void onOpen(MemberItem m) { ProfileActivity.open(MembersActivity.this, m.uid, m.name); }
            @Override public void onToggleFollow(MemberItem m) { toggleFollow(m); }
            @Override public void onBlock(MemberItem m) { confirmBlock(m); }
            @Override public void onMessage(MemberItem m) { openFamilyChatWith(m); }
        });
        rvMembers.setLayoutManager(new LinearLayoutManager(this));
        rvMembers.setAdapter(adapter);
        rvMembers.setNestedScrollingEnabled(false);

        // People you may know (কার্ড)
        peopleAdapter = new SuggestAdapter(new SuggestAdapter.Actions() {
            @Override public void onOpen(MemberItem m) { ProfileActivity.open(MembersActivity.this, m.uid, m.name); }
            @Override public void onFollow(MemberItem m) { toggleFollow(m); }
            @Override public void onRemove(MemberItem m) { dismiss(m.uid); }
            @Override public void onSeeMore() {
                if (candCount > cardLimit) { cardLimit += PAGE; refreshSuggestions(); }
                else Toast.makeText(MembersActivity.this, "No more people right now", Toast.LENGTH_SHORT).show();
            }
        });
        rvPeople.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        rvPeople.setAdapter(peopleAdapter);

        // Suggested for you (বাটন ছাড়া সারি)
        rowsAdapter = new SuggestRowAdapter(m -> ProfileActivity.open(this, m.uid, m.name));
        rvSugRows.setLayoutManager(new LinearLayoutManager(this));
        rvSugRows.setAdapter(rowsAdapter);
        rvSugRows.setNestedScrollingEnabled(false);

        // Notifications "see more" (খালি থাকলে চাপা যায় না) ও তালিকার "see more"
        btnSeeMore.setOnClickListener(v -> {
            if (btnSeeMore.isEnabled()) startActivity(new Intent(this, NotificationActivity.class));
        });
        tvListSeeMore.setOnClickListener(v -> {
            if (tvListSeeMore.isEnabled()) { limit += PAGE; refreshList(); }
        });

        tabOnline.setOnClickListener(v -> { tab = TAB_ONLINE; limit = PAGE; refreshList(); });
        tabFollowers.setOnClickListener(v -> { tab = TAB_FOLLOWERS; limit = PAGE; refreshList(); });
        tabAll.setOnClickListener(v -> { tab = TAB_ALL; limit = PAGE; refreshList(); });

        setNotifEmpty(true);
        PostOps.loadMyName(myUid, n -> myName = n);
    }

    private SharedPreferences prefs() { return getSharedPreferences("members_prefs", MODE_PRIVATE); }

    @Override
    protected void onStart() {
        super.onStart();
        if (myUid == null) return;
        AvatarLoader.load(topAvatar, myUid);
        started = true;
        ui.removeCallbacks(pollRunnable);
        ui.post(pollRunnable);
        loadSuggestionPool();
    }

    @Override
    protected void onStop() {
        super.onStop();
        started = false;
        ui.removeCallbacks(pollRunnable);
        ui.removeCallbacks(refreshRunnable);
    }

    private boolean alive() { return started && !isFinishing() && !isDestroyed(); }

    /** একবারে সব "লাইভ" তথ্য আনে */
    private void loadLive() {
        SupabaseRest.call("GET", "/rest/v1/follows?followee_id=eq." + myUid
                + "&select=follower_id&order=created_at.desc&limit=1000", null, null, true, r -> {
            if (!alive() || !r.ok()) return;
            followerIds.clear();
            for (JSONObject o : rows(r.body)) followerIds.add(o.optString("follower_id"));
            followersLoaded = true;
            loadInfos(followerIds);
            if (followNotifs != null) buildRecentCard(followNotifs);
            refreshList();
        });
        SupabaseRest.call("GET", "/rest/v1/follows?follower_id=eq." + myUid
                + "&select=followee_id&limit=5000", null, null, true, r -> {
            if (!alive() || !r.ok()) return;
            iFollow.clear();
            for (JSONObject o : rows(r.body)) iFollow.add(o.optString("followee_id"));
            loadInfos(iFollow);
            refreshList();
        });
        // আমি যাদের ব্লক করেছি
        SupabaseRest.call("GET", "/rest/v1/blocked_users?blocker_id=eq." + myUid
                + "&select=blocked_id&limit=5000", null, null, true, r -> {
            if (!alive() || !r.ok()) return;
            myBlocked.clear();
            for (JSONObject o : rows(r.body)) myBlocked.add(o.optString("blocked_id"));
            refreshList();
        });
        // যারা আমাকে ব্লক করেছে
        SupabaseRest.call("GET", "/rest/v1/blocked_users?blocked_id=eq." + myUid
                + "&select=blocker_id&limit=5000", null, null, true, r -> {
            if (!alive() || !r.ok()) return;
            blockedByIds.clear();
            for (JSONObject o : rows(r.body)) blockedByIds.add(o.optString("blocker_id"));
            blockedByLoaded = true;
            refreshList();
        });
        SupabaseRest.call("GET", "/rest/v1/presence?is_online=eq.true&select=user_id&limit=1000",
                null, null, true, r -> {
            if (!alive() || !r.ok()) return;
            onlineIds.clear();
            for (JSONObject o : rows(r.body)) onlineIds.add(o.optString("user_id"));
            loadInfos(onlineIds);
            refreshList();
        });
        SupabaseRest.call("GET", "/rest/v1/notifications?user_id=eq." + myUid
                + "&type=eq.follow&select=actor_id,created_at&order=created_at.asc&limit=100",
                null, null, true, r -> {
            if (!alive() || !r.ok()) return;
            followNotifs = rows(r.body);
            buildRecentCard(followNotifs);
        });
    }

    private static List<JSONObject> rows(String body) {
        List<JSONObject> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(body);
            for (int i = 0; i < a.length(); i++) out.add(a.getJSONObject(i));
        } catch (Exception ignored) { }
        return out;
    }

    private static long parseTime(String iso) {
        if (iso == null || iso.length() < 19) return 0;
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US);
            f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            return f.parse(iso.substring(0, 19)).getTime();
        } catch (Exception e) { return 0; }
    }

    /** নোটিফিকেশন ঘর: খালি হলে ছোট হালকা লেখা, "see more" চাপা যায় না */
    private void setNotifEmpty(boolean empty) {
        tvNotifEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        cardRecent.setVisibility(empty ? View.GONE : View.VISIBLE);
        setSeeMore(btnSeeMore, !empty);
    }

    private static void setSeeMore(TextView t, boolean on) {
        t.setEnabled(on);
        t.setClickable(on);
        t.setAlpha(on ? 1f : 0.35f);
    }

    /** কার্ডে শুধু এখনো ফলোয়ার এমন কারও follow নোটিফিকেশন; না থাকলে খালি-অবস্থা */
    private void buildRecentCard(List<JSONObject> list) {
        if (!followersLoaded) return;
        final List<String> actors = new ArrayList<>();
        long latest = 0;
        for (JSONObject c : list) {
            String a = c.optString("actor_id", "");
            if (a.isEmpty() || !followerIds.contains(a)) continue;
            actors.remove(a);
            actors.add(a);
            long ts = parseTime(c.optString("created_at", ""));
            if (ts > latest) latest = ts;
        }
        if (actors.isEmpty()) { setNotifEmpty(true); return; }

        final long when = latest;
        final int total = actors.size();
        final String last = actors.get(actors.size() - 1);
        final String prev = total > 1 ? actors.get(actors.size() - 2) : null;
        loadInfos(actors);
        String ids = prev == null ? last : last + "," + prev;
        SupabaseRest.call("GET", "/rest/v1/profiles?id=in.(" + ids + ")&select=id,name",
                null, null, true, r -> {
            if (!alive()) return;
            String n1 = null, n2 = null;
            for (JSONObject o : rows(r.ok() ? r.body : "[]")) {
                if (o.optString("id").equals(last)) n1 = o.optString("name", null);
                else if (o.optString("id").equals(prev)) n2 = o.optString("name", null);
            }
            n1 = nameOrMember(n1);
            if (prev == null) { showRecent(n1, " started following you.", when); return; }
            n2 = nameOrMember(n2);
            if (total == 2) showRecent(n1 + " & " + n2, " started following you.", when);
            else showRecent(n1 + ", " + n2 + " &", " " + (total - 2) + " others started following you.", when);
        });
    }

    private void showRecent(String boldPart, String normalPart, long when) {
        SpannableStringBuilder sb = new SpannableStringBuilder(boldPart);
        sb.setSpan(new StyleSpan(Typeface.BOLD), 0, boldPart.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append(normalPart);
        tvRecentTitle.setText(sb);
        tvRecentTime.setText("-" + DateUtils.getRelativeTimeSpanString(when, System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS) + "..");
        setNotifEmpty(false);
    }

    private static String nameOrMember(String n) { return (n == null || n.trim().isEmpty()) ? "Member" : n; }

    private void loadInfos(Collection<String> ids) {
        List<String> need = new ArrayList<>();
        for (String id : new ArrayList<>(ids)) {
            if (id == null || id.isEmpty() || infoCache.containsKey(id) || !infoLoading.add(id)) continue;
            need.add(id);
        }
        for (int i = 0; i < need.size(); i += 100) {
            final List<String> chunk = need.subList(i, Math.min(i + 100, need.size()));
            SupabaseRest.call("GET", "/rest/v1/profiles?id=in.(" + android.text.TextUtils.join(",", chunk)
                    + ")&select=id,name,city", null, null, true, r -> {
                for (String id : chunk) infoLoading.remove(id);
                if (!alive() || !r.ok()) return;
                for (JSONObject o : rows(r.body)) {
                    infoCache.put(o.optString("id"), new String[]{
                            o.isNull("name") ? null : o.optString("name"),
                            o.isNull("city") ? null : o.optString("city")});
                }
                scheduleRefresh();
            });
        }
    }

    /** সাজেশনের জন্য প্রোফাইল একবার আনে, এলোমেলো করে রাখে */
    private void loadSuggestionPool() {
        if (poolRequested) return;
        poolRequested = true;
        SupabaseRest.call("GET", "/rest/v1/profiles?select=id,name,city&order=name.asc&limit=200",
                null, null, true, r -> {
            if (isFinishing() || isDestroyed()) return;
            if (!r.ok()) { poolRequested = false; return; }
            suggestPool.clear();
            for (JSONObject c : rows(r.body)) {
                String id = c.optString("id", "");
                if (id.isEmpty() || id.equals(myUid)) continue;
                infoCache.put(id, new String[]{
                        c.isNull("name") ? null : c.optString("name"),
                        c.isNull("city") ? null : c.optString("city")});
                suggestPool.add(id);
            }
            Collections.shuffle(suggestPool);
            refreshList();
        });
    }

    private void scheduleRefresh() {
        ui.removeCallbacks(refreshRunnable);
        ui.postDelayed(refreshRunnable, 120);
    }

    private boolean visible(String id, boolean needBlockCheck) {
        if (id.equals(myUid) || myBlocked.contains(id)) return false;
        if (!needBlockCheck) return true;
        return blockedByLoaded && !blockedByIds.contains(id);
    }

    /** শুধু যাদের সাথে ফলো-সম্পর্ক আছে (আমি তাকে বা সে আমাকে ফলো করে) */
    private boolean connected(String id) {
        return iFollow.contains(id) || followerIds.contains(id);
    }

    private void refreshList() {
        int onlineCount = 0;
        for (String id : onlineIds) if (visible(id, true) && connected(id)) onlineCount++;
        tvOnlineLabel.setText(onlineCount + " Online");

        styleTab(tabOnline, tvOnlineLabel, tab == TAB_ONLINE);
        styleTab(tabFollowers, tabFollowers, tab == TAB_FOLLOWERS);
        styleTab(tabAll, tabAll, tab == TAB_ALL);

        final Collection<String> src;
        if (tab == TAB_ONLINE) src = new ArrayList<>(onlineIds);
        else if (tab == TAB_ALL) {                                 // All: আমি যাদের ফলো করি + যারা আমাকে ফলো করে
            Set<String> u = new HashSet<>(followerIds);
            u.addAll(iFollow);
            src = u;
        } else src = followerIds;                                  // Followers: শুধু যারা আমাকে ফলো করে
        final boolean check = tab != TAB_FOLLOWERS;

        List<MemberItem> out = new ArrayList<>();
        for (String id : src) {
            if (!visible(id, check) || !connected(id)) continue;
            String[] inf = infoCache.get(id);
            String name = nameOrMember(inf == null ? null : inf[0]);
            String city = inf == null ? null : inf[1];
            out.add(new MemberItem(id, name, city == null ? "" : city,
                    iFollow.contains(id), followerIds.contains(id)));
        }
        if (tab != TAB_FOLLOWERS) {
            Collections.sort(out, (a, b) -> a.name.compareToIgnoreCase(b.name));
        }

        boolean more = out.size() > limit;
        List<MemberItem> shown = more ? new ArrayList<>(out.subList(0, limit)) : out;
        adapter.setItems(shown);
        setSeeMore(tvListSeeMore, more);

        if (shown.isEmpty()) {
            tvEmptyList.setVisibility(View.VISIBLE);
            if (tab == TAB_FOLLOWERS) {
                tvEmptyList.setText("Don't have any followers. yet");
            } else if (tab == TAB_ONLINE && (!followerIds.isEmpty() || !iFollow.isEmpty())) {
                tvEmptyList.setText("No one is online right now");
            } else {
                tvEmptyList.setText("you didn't following anyone\nor don't have any followers.");
            }
        } else {
            tvEmptyList.setVisibility(View.GONE);
        }

        refreshSuggestions();
    }

    /** People you may know (কার্ড, ১০ জন + See more) ও Suggested for you (বাটন ছাড়া সারি) */
    private void refreshSuggestions() {
        if (!blockedByLoaded) return;
        List<MemberItem> cand = new ArrayList<>();
        for (String id : suggestPool) {
            // শুধু যাদের সাথে কোনো ফলো-সম্পর্ক নেই (আমি তাকে ফলো করি না, সেও আমাকে করে না)
            // তারাই People you may know-এ আসে; আনফলো করা মানুষ (যে আমাকে ফলো করে না) সামনে আসে
            if (!visible(id, true) || connected(id) || dismissed.contains(id)) continue;
            String[] inf = infoCache.get(id);
            String name = nameOrMember(inf == null ? null : inf[0]);
            String city = inf == null ? null : inf[1];
            cand.add(new MemberItem(id, name, city == null ? "" : city, false));
        }
        candCount = cand.size();
        int n = Math.min(cardLimit, cand.size());
        peopleAdapter.setItems(new ArrayList<>(cand.subList(0, n)));
        sectionPeople.setVisibility(cand.isEmpty() ? View.GONE : View.VISIBLE);

        List<MemberItem> rows = new ArrayList<>(cand.subList(n, Math.min(cand.size(), n + ROWS_MAX)));
        rowsAdapter.setItems(rows);
        sectionSuggested.setVisibility(rows.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void dismiss(String uid) {
        dismissed.add(uid);
        prefs().edit().putStringSet("dismissed_" + myUid, new HashSet<>(dismissed)).apply();
        refreshSuggestions();
    }

    /** ট্যাব: নির্বাচিত = #2D69E5 পটভূমি + সাদা লেখা; নইলে হালকা ধূসর + কালো লেখা */
    private static void styleTab(View pill, TextView label, boolean on) {
        pill.setBackgroundResource(on ? R.drawable.bg_mtab_on : R.drawable.bg_mtab_off);
        label.setTextColor(on ? 0xFFFFFFFF : 0xFF000000);
    }

    private void toggleFollow(final MemberItem m) {
        final boolean wasFollowing = m.isFollowing;
        if (wasFollowing) {
            SupabaseRest.call("DELETE", "/rest/v1/follows?follower_id=eq." + myUid
                    + "&followee_id=eq." + m.uid, null, "return=minimal", true, r -> {
                if (!r.ok()) { fail(r); return; }
                iFollow.remove(m.uid);
                SupabaseRest.call("DELETE", "/rest/v1/notifications?user_id=eq." + m.uid
                        + "&actor_id=eq." + myUid + "&type=eq.follow", null, null, true, x -> { });
                // আনফলো করা মানুষ তালিকা থেকে সরে "People you may know" সাজেশনের সামনে আসবে
                // (সে যদি আমাকে ফলো করে, তবে Followers-এ "Follow" বাটনসহ থাকবে)
                if (dismissed.remove(m.uid)) {
                    prefs().edit().putStringSet("dismissed_" + myUid, new HashSet<>(dismissed)).apply();
                }
                suggestPool.remove(m.uid);
                suggestPool.add(0, m.uid);
                refreshList();
            });
        } else {
            try {
                String body = new JSONObject().put("follower_id", myUid).put("followee_id", m.uid).toString();
                SupabaseRest.call("POST", "/rest/v1/follows?on_conflict=follower_id,followee_id", body,
                        "resolution=ignore-duplicates,return=minimal", true, r -> {
                    if (!r.ok()) { fail(r); return; }
                    iFollow.add(m.uid);
                    NotificationHelper.send(m.uid, "follow", null);
                    refreshList();
                });
            } catch (Exception e) {
                Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    private static final String FAMILY_CHAT_PKG = "com.familychat.app";

    /** Message: Family Chat অ্যাপ খুলে সরাসরি এই ব্যক্তির চ্যাটে যায় (ProfileActivity-র মতোই) */
    private void openFamilyChatWith(MemberItem m) {
        try {
            Intent dl = new Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse("familychat://chat?uid=" + android.net.Uri.encode(m.uid)));
            dl.setPackage(FAMILY_CHAT_PKG);
            dl.putExtra("chat_uid", m.uid);
            dl.putExtra("chat_name", m.name);
            dl.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(dl);
            return;
        } catch (android.content.ActivityNotFoundException ignored) { }
        Intent intent = getPackageManager().getLaunchIntentForPackage(FAMILY_CHAT_PKG);
        if (intent != null) {
            intent.putExtra("chat_uid", m.uid);
            intent.putExtra("chat_name", m.name);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } else {
            Toast.makeText(this, "Family Chat অ্যাপ ইনস্টল করা নেই", Toast.LENGTH_SHORT).show();
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        android.net.Uri.parse("market://details?id=" + FAMILY_CHAT_PKG)));
            } catch (android.content.ActivityNotFoundException ignored) { }
        }
    }

    private void fail(SupabaseRest.Resp r) {
        if (isFinishing() || isDestroyed()) return;
        Toast.makeText(this, SupabaseRest.errorText(r), Toast.LENGTH_LONG).show();
    }

    private void confirmBlock(final MemberItem m) {
        new AlertDialog.Builder(this)
                .setTitle("Block " + m.name + "?")
                .setMessage("তিনি আপনাকে সার্চে খুঁজে পাবেন না এবং ফলো করতে পারবেন না। আপনারা একে অপরকে আর ফলো করবেন না।")
                .setPositiveButton("Block", (d, w) -> blockUser(m))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void blockUser(final MemberItem m) {
        try {
            String body = new JSONObject()
                    .put("blocker_id", myUid)
                    .put("blocked_id", m.uid)
                    .put("blocked_name", m.name)
                    .toString();
            SupabaseRest.call("POST", "/rest/v1/blocked_users?on_conflict=blocker_id,blocked_id", body,
                    "resolution=ignore-duplicates,return=minimal", true, r -> {
                if (!r.ok()) { fail(r); return; }
                myBlocked.add(m.uid);
                iFollow.remove(m.uid);
                followerIds.remove(m.uid);
                refreshList();
                String both = "or=(and(follower_id.eq." + myUid + ",followee_id.eq." + m.uid + "),"
                        + "and(follower_id.eq." + m.uid + ",followee_id.eq." + myUid + "))";
                SupabaseRest.call("DELETE", "/rest/v1/follows?" + both, null, "return=minimal", true, x -> { });
                SupabaseRest.call("DELETE", "/rest/v1/notifications?user_id=eq." + m.uid
                        + "&actor_id=eq." + myUid + "&type=eq.follow", null, null, true, x -> { });
                Toast.makeText(this, m.name + " blocked", Toast.LENGTH_SHORT).show();
            });
        } catch (Exception e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void goLogin() {
        Intent i = new Intent(this, LoginActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }

    /** তালিকার একটি সারির মডেল */
    public static class MemberItem {
        public final String uid, name, location;
        public final boolean isFollowing, followsMe;
        public MemberItem(String uid, String name, String location, boolean isFollowing) {
            this(uid, name, location, isFollowing, false);
        }
        public MemberItem(String uid, String name, String location, boolean isFollowing, boolean followsMe) {
            this.uid = uid; this.name = name; this.location = location;
            this.isFollowing = isFollowing; this.followsMe = followsMe;
        }
    }
}
