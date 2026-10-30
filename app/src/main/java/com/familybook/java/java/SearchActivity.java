package com.familybook.app;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Patterns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;


import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * Home-এর Search বাটনে খোলে।
 *  - ফাঁকা: Recent + People you may know
 *  - নাম / @username / পুরো Email দিয়ে মানুষ খোঁজা (Email: শুধু যিনি বন্ধ রাখেননি)
 *  - ব্লক: A যদি B-কে ব্লক করে, B সার্চ করলে A আসবে না
 *  - #হ্যাশট্যাগ দিয়ে পোস্ট খোঁজা (পোস্টের হ্যাশট্যাগে ডাবল-ট্যাপ করলেও এখানে আসে)
 *
 * Supabase: profiles, blocked_users, email_index, follows, post_cards
 */
public class SearchActivity extends BaseActivity {

    public static final String EXTRA_QUERY = "query";
    private static final int RECENT_SHOWN = 6;
    private static final int MAX_PEOPLE = 40;

    private String myUid;

    private EditText etSearch;
    private View panelHome, panelResults, scrollSuggest;
    private LinearLayout listRecent, rowSuggest;
    private TextView btnSeeAll, tvNoRecent, tvSuggestTitle, tvEmailToggle, tabPeople, tabPosts, tvEmpty;
    private RecyclerView rvPeople, rvPosts;
    private UserAdapter userAdapter;
    private PostAdapter postAdapter;

    // ডেটা
    private List<JSONObject> allProfiles;                       // null = এখনও লোড হয়নি
    private final Map<String, JSONObject> profileById = new HashMap<>();
    private List<Post> lastPosts;                                // null = এখনও লোড হয়নি
    private String lastPostsTag = null;
    private int postsToken = 0;
    private final Set<String> myBlocked = new HashSet<>();      // আমি যাদের ব্লক করেছি
    private final Set<String> blockedByIds = new HashSet<>();   // যারা আমাকে ব্লক করেছে
    private boolean blockedByLoaded = false;
    private final Set<String> myFollowing = new HashSet<>();
    private int baseLoaded = 0;
    private boolean emailSearchOn = true;

    // অবস্থা
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable pending;
    private int searchToken = 0;
    private String currentQuery = "";
    private boolean showingPosts = false;
    private boolean lastWasHash = false;
    private boolean exactTag = false;        // হ্যাশট্যাগে ট্যাপ করে এলে পুরো ট্যাগ মিলবে
    private String programmaticText = null;
    private boolean showAllRecent = false;

    private static class Match {
        final String uid, name, sub;
        Match(String uid, String name, String sub) { this.uid = uid; this.name = name; this.sub = sub; }
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        myUid = SupabaseRest.uid();
        if (!SupabaseRest.isLoggedIn() || myUid == null) { finish(); return; }
        setContentView(R.layout.activity_search);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(color(R.color.h_bg)));

        etSearch = findViewById(R.id.etSearch);
        panelHome = findViewById(R.id.panelHome);
        panelResults = findViewById(R.id.panelResults);
        scrollSuggest = findViewById(R.id.scrollSuggest);
        listRecent = findViewById(R.id.listRecent);
        rowSuggest = findViewById(R.id.rowSuggest);
        btnSeeAll = findViewById(R.id.btnSeeAll);
        tvNoRecent = findViewById(R.id.tvNoRecent);
        tvSuggestTitle = findViewById(R.id.tvSuggestTitle);
        tvEmailToggle = findViewById(R.id.tvEmailToggle);
        tabPeople = findViewById(R.id.tabPeople);
        tabPosts = findViewById(R.id.tabPosts);
        tvEmpty = findViewById(R.id.tvEmpty);
        rvPeople = findViewById(R.id.rvPeople);
        rvPosts = findViewById(R.id.rvPosts);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        userAdapter = new UserAdapter();
        rvPeople.setLayoutManager(new LinearLayoutManager(this));
        rvPeople.setAdapter(userAdapter);

        postAdapter = new PostAdapter(new PostAdapter.Actions() {
            @Override public void onLike(Post p) { toggleLike(p); }
            @Override public void onHide(Post p) { }
            @Override public void onMenu(Post p, View anchor) {
                PostOps.showMenu(SearchActivity.this, myUid, p, anchor);
            }
            @Override public void onComments(Post p) {
                startActivity(new Intent(SearchActivity.this, CommentsActivity.class)
                        .putExtra("postId", p.id));
            }
        }, false, true);
        rvPosts.setLayoutManager(new LinearLayoutManager(this));
        rvPosts.setAdapter(postAdapter);

        tabPeople.setOnClickListener(v -> { setTab(false); });
        tabPosts.setOnClickListener(v -> { setTab(true); renderPosts(); });
        setTab(false);

        btnSeeAll.setOnClickListener(v -> { showAllRecent = !showAllRecent; renderRecent(); });
        tvEmailToggle.setOnClickListener(v -> toggleEmailSearch());

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                if (programmaticText == null || !s.toString().equals(programmaticText)) exactTag = false;
                if (pending != null) handler.removeCallbacks(pending);
                pending = SearchActivity.this::runSearch;
                handler.postDelayed(pending, 300);
            }
        });
        etSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                if (pending != null) handler.removeCallbacks(pending);
                runSearch();
                String q = etSearch.getText().toString().trim();
                if (!q.isEmpty()) saveRecentQuery(q);
                hideKeyboard();
                return true;
            }
            return false;
        });

        renderRecent();
        loadBase();

        String initial = getIntent().getStringExtra(EXTRA_QUERY);
        if (initial != null && !initial.trim().isEmpty()) {
            runQuery(initial.trim());
        } else {
            etSearch.requestFocus();
            etSearch.post(() -> {
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(etSearch, InputMethodManager.SHOW_IMPLICIT);
            });
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (myUid != null && showingPosts) renderPosts();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (pending != null) handler.removeCallbacks(pending);
    }

    /** হ্যাশট্যাগ ট্যাপ বা রিসেন্ট থেকে কোনো লেখা দিয়ে সার্চ চালায় */
    public void runQuery(String q) {
        exactTag = q.startsWith("#");
        programmaticText = q;
        etSearch.setText(q);
        etSearch.setSelection(q.length());
        if (pending != null) handler.removeCallbacks(pending);
        runSearch();
        saveRecentQuery(q);
    }

    // ------------------------------------------------------------------ ডেটা লোড

    private void loadBase() {
        baseLoaded = 0;

        SupabaseRest.call("GET", "/rest/v1/profiles?select=id,name,username,city&limit=5000",
                null, null, true, r -> {
            if (isFinishing() || isDestroyed()) return;
            if (r.ok()) {
                List<JSONObject> list = new ArrayList<>();
                profileById.clear();
                try {
                    JSONArray a2 = new JSONArray(r.body);
                    for (int i = 0; i < a2.length(); i++) {
                        JSONObject o = a2.getJSONObject(i);
                        list.add(o);
                        profileById.put(o.optString("id"), o);
                    }
                } catch (Exception ignored) { }
                allProfiles = list;
            }
            baseDone();
            runSearch();
        });
        SupabaseRest.call("GET", "/rest/v1/blocked_users?blocker_id=eq." + myUid + "&select=blocked_id&limit=5000",
                null, null, true, r -> {
            if (isFinishing() || isDestroyed()) return;
            myBlocked.clear();
            if (r.ok()) for (JSONObject o : rows(r.body)) myBlocked.add(o.optString("blocked_id"));
            baseDone();
        });
        SupabaseRest.call("GET", "/rest/v1/blocked_users?blocked_id=eq." + myUid + "&select=blocker_id&limit=5000",
                null, null, true, r -> {
            if (isFinishing() || isDestroyed()) return;
            blockedByIds.clear();
            if (r.ok()) {
                for (JSONObject o : rows(r.body)) blockedByIds.add(o.optString("blocker_id"));
                blockedByLoaded = true;
            }
            baseDone();
            runSearch();      // ব্লকের তথ্য আসার আগে ফলাফল দেখানো হয় না
        });
        SupabaseRest.call("GET", "/rest/v1/follows?follower_id=eq." + myUid + "&select=followee_id&limit=5000",
                null, null, true, r -> {
            if (isFinishing() || isDestroyed()) return;
            myFollowing.clear();
            if (r.ok()) for (JSONObject o : rows(r.body)) myFollowing.add(o.optString("followee_id"));
            baseDone();
        });
        SupabaseRest.call("GET", "/rest/v1/profiles?id=eq." + myUid + "&select=email_search",
                null, null, true, r -> {
            if (isFinishing() || isDestroyed()) return;
            List<JSONObject> l = r.ok() ? rows(r.body) : new ArrayList<JSONObject>();
            // মান false হলেই বন্ধ; null/true মানে চালু
            emailSearchOn = l.isEmpty() || l.get(0).isNull("email_search") || l.get(0).optBoolean("email_search", true);
            updateEmailToggle();
        });
        updateEmailToggle();
    }

    private static List<JSONObject> rows(String body) {
        List<JSONObject> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(body);
            for (int i = 0; i < a.length(); i++) out.add(a.getJSONObject(i));
        } catch (Exception ignored) { }
        return out;
    }

    private void baseDone() {
        if (++baseLoaded >= 4) buildSuggestions();
    }

    // ------------------------------------------------------------------ Email ইনডেক্স

    static String emailKey(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    /** আমার ইমেইল ইনডেক্সে আছে কিনা ঠিক রাখে (Home খুললে একবার চালানো হয়) */
    public static void syncEmailIndex() {
        final String my = SupabaseRest.uid();
        final String email = SupabaseRest.email();
        if (my == null || email == null) return;
        final String key = emailKey(email);
        SupabaseRest.call("GET", "/rest/v1/profiles?id=eq." + my + "&select=email_search",
                null, null, true, r -> {
            List<JSONObject> l = r.ok() ? rows(r.body) : new ArrayList<JSONObject>();
            boolean on = l.isEmpty() || l.get(0).isNull("email_search") || l.get(0).optBoolean("email_search", true);
            applyEmailIndex(my, key, on);
        });
    }

    private static void applyEmailIndex(final String my, String key, final boolean on) {
        if (on) {
            try {
                String body = new JSONObject().put("email", key).put("user_id", my).toString();
                SupabaseRest.call("POST", "/rest/v1/email_index?on_conflict=email", body,
                        "resolution=merge-duplicates,return=minimal", true, r -> { });
            } catch (Exception ignored) { }
        } else {
            SupabaseRest.call("DELETE", "/rest/v1/email_index?user_id=eq." + my, null,
                    "return=minimal", true, r -> { });
        }
    }

    private void toggleEmailSearch() {
        if (!Net.check(this) || SupabaseRest.email() == null) return;
        emailSearchOn = !emailSearchOn;
        updateEmailToggle();
        try {
            Db.patchMyProfile(new JSONObject().put("email_search", emailSearchOn), null);
        } catch (Exception ignored) { }
        applyEmailIndex(myUid, emailKey(SupabaseRest.email()), emailSearchOn);
    }

    private void updateEmailToggle() {
        tvEmailToggle.setText(emailSearchOn
                ? "Email দিয়ে আপনাকে খোঁজা যাবে — বন্ধ করতে এখানে ট্যাপ করুন"
                : "Email দিয়ে আপনাকে খোঁজা বন্ধ আছে — চালু করতে এখানে ট্যাপ করুন");
    }

    // ------------------------------------------------------------------ সার্চ

    private void runSearch() {
        final String q = etSearch.getText().toString().trim();
        currentQuery = q;
        final int token = ++searchToken;
        if (q.isEmpty()) {
            lastWasHash = false;
            showResults(false);
            renderRecent();
            return;
        }
        showResults(true);
        boolean hash = q.startsWith("#");
        if (hash) setTab(true);
        else if (lastWasHash) setTab(false);
        lastWasHash = hash;

        searchPeople(q, token);
        if (showingPosts) renderPosts();
    }

    private void showResults(boolean on) {
        panelResults.setVisibility(on ? View.VISIBLE : View.GONE);
        panelHome.setVisibility(on ? View.GONE : View.VISIBLE);
    }

    private void setTab(boolean posts) {
        showingPosts = posts;
        int active = color(R.color.h_nav_active), idle = color(R.color.h_text_sub);
        tabPeople.setTextColor(posts ? idle : active);
        tabPosts.setTextColor(posts ? active : idle);
        tabPeople.setTypeface(null, posts ? Typeface.NORMAL : Typeface.BOLD);
        tabPosts.setTypeface(null, posts ? Typeface.BOLD : Typeface.NORMAL);
        tabPeople.setBackground(underline(!posts));
        tabPosts.setBackground(underline(posts));
        rvPeople.setVisibility(posts ? View.GONE : View.VISIBLE);
        rvPosts.setVisibility(posts ? View.VISIBLE : View.GONE);
        updateEmpty();
    }

    private android.graphics.drawable.Drawable underline(boolean on) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.TRANSPARENT);
        if (!on) return g;
        android.graphics.drawable.LayerDrawable ld = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{g, bar()});
        ld.setLayerGravity(1, Gravity.BOTTOM);
        ld.setLayerHeight(1, dp(3));
        return ld;
    }

    private android.graphics.drawable.Drawable bar() {
        return new android.graphics.drawable.ColorDrawable(color(R.color.h_nav_active));
    }

    // ---- মানুষ খোঁজা

    private void searchPeople(final String q, final int token) {
        final List<Match> found = new ArrayList<>();
        if (q.startsWith("#")) { finishPeople(found, token); return; }

        String raw = q.startsWith("@") ? q.substring(1) : q;
        final String ql = raw.trim().toLowerCase(Locale.ROOT);
        final String my = myUid;

        if (allProfiles != null && !ql.isEmpty()) {
            final Map<String, Integer> score = new HashMap<>();
            for (JSONObject p : allProfiles) {
                String id = p.optString("id", "");
                // নিজেকেও খোঁজা যাবে (শুধু ব্লক করা ব্যক্তিরা বাদ)
                if (id.isEmpty() || myBlocked.contains(id)) continue;
                String name = str(p, "name").toLowerCase(Locale.ROOT);
                String un = str(p, "username").toLowerCase(Locale.ROOT);
                int s = 0;
                if (name.equals(ql) || un.equals(ql)) s = 3;
                else if (name.startsWith(ql) || un.startsWith(ql)) s = 2;
                else if (name.contains(ql) || un.contains(ql)) s = 1;
                else {
                    // একাধিক শব্দ হলে যেকোনো ক্রমে সব শব্দ মিললেই চলবে (যেমন "masud alam")
                    String[] words = ql.split("\\s+");
                    if (words.length > 1) {
                        boolean all = true;
                        for (String w : words) {
                            if (w.isEmpty()) continue;
                            if (!name.contains(w) && !un.contains(w)) { all = false; break; }
                        }
                        if (all) s = 1;
                    }
                }
                if (s > 0) { score.put(id, s); found.add(matchOf(p)); }
            }
            Collections.sort(found, (a, b) -> {
                int d = score.get(b.uid) - score.get(a.uid);
                return d != 0 ? d : a.name.compareToIgnoreCase(b.name);
            });
        }

        // পুরো ইমেইল লিখলে: email_index থেকে (যারা বন্ধ রেখেছে তারা ইনডেক্সে নেই)
        if (Patterns.EMAIL_ADDRESS.matcher(q).matches()) {
            SupabaseRest.call("GET", "/rest/v1/email_index?email=eq." + android.net.Uri.encode(emailKey(q))
                    + "&select=user_id&limit=1", null, null, true, r -> {
                if (token != searchToken) return;
                if (r.ok()) {
                    List<JSONObject> l = rows(r.body);
                    final String uid = l.isEmpty() ? null : l.get(0).optString("user_id", null);
                    if (uid != null && !myBlocked.contains(uid)) {
                        boolean dup = false;
                        for (Match m : found) if (m.uid.equals(uid)) dup = true;
                        if (!dup) {
                            JSONObject p = profileById.get(uid);
                            found.add(0, p != null ? matchOf(p) : new Match(uid, "Member", ""));
                        }
                    }
                }
                finishPeople(found, token);
            });
        } else {
            finishPeople(found, token);
        }
    }

    /** যারা আমাকে ব্লক করেছে তাদের বাদ দিয়ে তালিকা দেখায় (ব্লক-তথ্য আসার আগে অপেক্ষা করে) */
    private void finishPeople(List<Match> list, final int token) {
        if (!blockedByLoaded) return;      // লোড শেষে loadBase() আবার runSearch() ডাকবে
        List<Match> ok = new ArrayList<>();
        for (Match m : list) {
            if (ok.size() >= MAX_PEOPLE) break;
            if (!blockedByIds.contains(m.uid)) ok.add(m);
        }
        showPeople(ok, token);
    }

    private void showPeople(List<Match> list, int token) {
        if (token != searchToken) return;
        userAdapter.setItems(list);
        updateEmpty();
    }

    private Match matchOf(JSONObject p) {
        String un = str(p, "username");
        String city = str(p, "city");
        StringBuilder sb = new StringBuilder();
        if (!un.isEmpty()) sb.append('@').append(un);
        if (!city.isEmpty()) { if (sb.length() > 0) sb.append(" · "); sb.append(city); }
        return new Match(p.optString("id", ""), nameOf(p), sb.toString());
    }

    // ---- পোস্ট (হ্যাশট্যাগ)

    private static String normTag(String q) {
        String t = q.trim();
        while (t.startsWith("#")) t = t.substring(1);
        return t.toLowerCase(Locale.ROOT);
    }

    private static boolean hasTag(Post p, String tag, boolean exact) {
        return matchIn(p.hashtags, tag, exact) || matchIn(p.text, tag, exact);
    }

    private static boolean matchIn(String s, String tag, boolean exact) {
        if (s == null || s.indexOf('#') < 0) return false;
        Matcher m = HashtagLinker.PATTERN.matcher(s);
        while (m.find()) {
            String t = m.group().substring(1).toLowerCase(Locale.ROOT);
            if (exact ? t.equals(tag) : t.startsWith(tag)) return true;
        }
        return false;
    }

    /** ট্যাগ বদলালে সার্ভার থেকে সেই ট্যাগের পোস্ট আনে; আগে থেকে আনা থাকলে সরাসরি দেখায় */
    private void renderPosts() {
        final String tag = normTag(currentQuery);
        if (tag.isEmpty()) { postAdapter.setItems(new ArrayList<Post>()); updateEmpty(); return; }
        if (lastPosts != null && tag.equals(lastPostsTag)) { showPosts(tag); return; }
        if (tag.equals(lastPostsTag)) return;          // আনা চলছে
        lastPostsTag = tag;
        lastPosts = null;
        updateEmpty();
        final int token = ++postsToken;
        String enc = android.net.Uri.encode(tag);
        SupabaseRest.call("GET", "/rest/v1/post_cards_g?status=eq.active&or=(hashtags.ilike.*%23" + enc
                + "*,text.ilike.*%23" + enc + "*)&order=created_at.desc&limit=200",
                null, null, true, r -> {
            if (isFinishing() || isDestroyed() || token != postsToken) return;
            if (!r.ok()) {
                lastPostsTag = null;
                Toast.makeText(this, SupabaseRest.errorText(r), Toast.LENGTH_SHORT).show();
                return;
            }
            List<Post> list = new ArrayList<>();
            for (JSONObject o : rows(r.body)) list.add(Post.from(o, myUid));
            lastPosts = list;
            showPosts(tag);
        });
    }

    private void showPosts(String tag) {
        List<Post> out = new ArrayList<>();
        if (lastPosts != null) {
            for (Post p : lastPosts) {
                if (!"active".equals(p.status)) continue;
                if (!p.isMine(myUid)) {
                    // দৃশ্যমানতা (public/friends/me) post_cards ভিউ নিজেই ঠিক করে; এখানে শুধু ব্লক বাদ
                    if (p.uid == null || myBlocked.contains(p.uid)) continue;
                    if (!blockedByLoaded || blockedByIds.contains(p.uid)) continue;
                }
                if (!hasTag(p, tag, exactTag)) continue;
                out.add(p);
            }
        }
        Collections.sort(out, (x, y) -> Long.compare(y.timestamp, x.timestamp));
        postAdapter.setItems(out);
        updateEmpty();
    }

    private void toggleLike(Post p) {
        if (!Net.check(this)) return;
        if (p.likedByMe) {
            ReactionKit.removeReaction(this, p.id, myUid);
            p.likedByMe = false;
            p.myReaction = null;
            p.likes = Math.max(0, p.likes - 1);
        } else {
            ReactionKit.setReaction(this, p.id, myUid, "like");   // নোটিফিকেশন ReactionKit নিজেই পাঠায়
            Sfx.like();
            p.likedByMe = true;
            p.myReaction = "like";
            p.likes = p.likes + 1;
        }
        postAdapter.notifyDataSetChanged();
    }

    private void updateEmpty() {
        if (tvEmpty == null) return;
        boolean empty = showingPosts ? postAdapter.getItemCount() == 0 : userAdapter.getItemCount() == 0;
        boolean loading = !showingPosts && (allProfiles == null || !blockedByLoaded);
        if (showingPosts && lastPosts == null) {
            tvEmpty.setText("খোঁজা হচ্ছে…");
            tvEmpty.setVisibility(View.VISIBLE);
            return;
        }
        tvEmpty.setText(loading ? "খোঁজা হচ্ছে…"
                : showingPosts ? "এই হ্যাশট্যাগের কোনো পোস্ট পাওয়া যায়নি"
                : "কোনো ব্যবহারকারী পাওয়া যায়নি");
        tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------------ People you may know

    private void buildSuggestions() {
        rowSuggest.removeAllViews();
        if (allProfiles == null) return;
        List<JSONObject> cands = new ArrayList<>();
        for (JSONObject p : allProfiles) {
            String id = p.optString("id", "");
            if (id.isEmpty() || id.equals(myUid) || myFollowing.contains(id)
                    || myBlocked.contains(id) || blockedByIds.contains(id)) continue;
            cands.add(p);
        }
        Collections.shuffle(cands);
        int n = Math.min(10, cands.size());
        for (int i = 0; i < n; i++) rowSuggest.addView(suggestCard(cands.get(i)));
        int vis = n == 0 ? View.GONE : View.VISIBLE;
        tvSuggestTitle.setVisibility(vis);
        scrollSuggest.setVisibility(vis);
    }

    private View suggestCard(final JSONObject p) {
        final String id = p.optString("id", "");
        final String name = nameOf(p);

        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(12), dp(16), dp(12), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(170), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(10));
        card.setLayoutParams(lp);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color(R.color.h_bg));
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), 0x33808080);
        card.setBackground(bg);

        ImageView img = new ImageView(this);
        img.setLayoutParams(new LinearLayout.LayoutParams(dp(96), dp(96)));
        AvatarLoader.load(img, id);
        card.addView(img);

        TextView tn = new TextView(this);
        tn.setText(name);
        tn.setTextSize(17);
        tn.setTypeface(null, Typeface.BOLD);
        tn.setTextColor(color(R.color.h_text));
        tn.setGravity(Gravity.CENTER);
        tn.setMaxLines(2);
        tn.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams lpn = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpn.topMargin = dp(10);
        card.addView(tn, lpn);

        final TextView tm = new TextView(this);
        tm.setTextSize(13);
        tm.setTextColor(color(R.color.h_text_sub));
        tm.setGravity(Gravity.CENTER);
        tm.setText(" ");
        card.addView(tm);

        final TextView btn = new TextView(this);
        btn.setGravity(Gravity.CENTER);
        btn.setTextSize(15);
        btn.setTypeface(null, Typeface.BOLD);
        styleFollowBtn(btn, myFollowing.contains(id));
        LinearLayout.LayoutParams lpb = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
        lpb.topMargin = dp(10);
        card.addView(btn, lpb);

        btn.setOnClickListener(v -> toggleFollow(id, btn));
        card.setOnClickListener(v -> openUser(id, name));

        // মিউচুয়াল: আমি যাদের ফলো করি তাদের কতজন ওকে ফলো করে
        SupabaseRest.call("GET", "/rest/v1/follows?followee_id=eq." + id + "&select=follower_id&limit=2000",
                null, null, true, r -> {
            if (isFinishing() || isDestroyed() || !r.ok()) return;
            int mutual = 0;
            for (JSONObject o : rows(r.body)) if (myFollowing.contains(o.optString("follower_id"))) mutual++;
            tm.setText(mutual == 0 ? "Suggested for you"
                    : mutual + (mutual == 1 ? " mutual friend" : " mutual friends"));
        });
        return card;
    }

    private void styleFollowBtn(TextView b, boolean following) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(8));
        g.setColor(following ? 0xFFE4E6EB : color(R.color.h_nav_active));
        b.setBackground(g);
        b.setText(following ? "Following" : "Follow");
        b.setTextColor(following ? 0xFF000000 : 0xFFFFFFFF);
    }

    private void toggleFollow(final String id, final TextView btn) {
        if (!Net.check(this)) return;
        final boolean was = myFollowing.contains(id);
        if (was) {
            SupabaseRest.call("DELETE", "/rest/v1/follows?follower_id=eq." + myUid + "&followee_id=eq." + id,
                    null, "return=minimal", true, r -> {
                if (!r.ok()) { toastErr(r); return; }
                myFollowing.remove(id);
                SupabaseRest.call("DELETE", "/rest/v1/notifications?user_id=eq." + id
                        + "&actor_id=eq." + myUid + "&type=eq.follow", null, null, true, x -> { });
                styleFollowBtn(btn, false);
            });
        } else {
            try {
                String body = new JSONObject().put("follower_id", myUid).put("followee_id", id).toString();
                SupabaseRest.call("POST", "/rest/v1/follows?on_conflict=follower_id,followee_id", body,
                        "resolution=ignore-duplicates,return=minimal", true, r -> {
                    if (!r.ok()) { toastErr(r); return; }
                    myFollowing.add(id);
                    NotificationHelper.send(id, "follow", null);
                    styleFollowBtn(btn, true);
                });
            } catch (Exception e) {
                Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    private void toastErr(SupabaseRest.Resp r) {
        if (isFinishing() || isDestroyed()) return;
        Toast.makeText(this, SupabaseRest.errorText(r), Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------------ Recent (SharedPreferences)

    private android.content.SharedPreferences prefs() {
        return getSharedPreferences("fb_search_" + myUid, MODE_PRIVATE);
    }

    /** প্রতিটি এন্ট্রি: {type "u"|"q", key, label} */
    private List<String[]> readRecent() {
        List<String[]> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs().getString("recent", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new String[]{o.getString("t"), o.getString("k"), o.optString("l", "")});
            }
        } catch (Exception ignored) { }
        return out;
    }

    private void writeRecent(List<String[]> list) {
        try {
            JSONArray a = new JSONArray();
            for (String[] e : list) {
                JSONObject o = new JSONObject();
                o.put("t", e[0]); o.put("k", e[1]); o.put("l", e[2]);
                a.put(o);
            }
            prefs().edit().putString("recent", a.toString()).apply();
        } catch (Exception ignored) { }
    }

    private void addRecent(String type, String key, String label) {
        List<String[]> r = readRecent();
        for (int i = r.size() - 1; i >= 0; i--)
            if (r.get(i)[0].equals(type) && r.get(i)[1].equalsIgnoreCase(key)) r.remove(i);
        r.add(0, new String[]{type, key, label});
        while (r.size() > 30) r.remove(r.size() - 1);
        writeRecent(r);
    }

    private void saveRecentUser(String uid, String name) { addRecent("u", uid, name); }

    private void saveRecentQuery(String q) { if (!q.trim().isEmpty()) addRecent("q", q.trim(), q.trim()); }

    private void renderRecent() {
        if (listRecent == null) return;
        listRecent.removeAllViews();
        final List<String[]> r = readRecent();
        tvNoRecent.setVisibility(r.isEmpty() ? View.VISIBLE : View.GONE);
        btnSeeAll.setVisibility(r.size() > RECENT_SHOWN ? View.VISIBLE : View.GONE);
        btnSeeAll.setText(showAllRecent ? "Show less" : "See all");
        int limit = showAllRecent ? r.size() : Math.min(RECENT_SHOWN, r.size());
        for (int i = 0; i < limit; i++) listRecent.addView(recentRow(r.get(i)));
    }

    private View recentRow(final String[] e) {
        View row = getLayoutInflater().inflate(R.layout.item_search_user, listRecent, false);
        ImageView img = row.findViewById(R.id.imgAvatar);
        TextView name = row.findViewById(R.id.tvName);
        View more = row.findViewById(R.id.btnMore);
        name.setText(e[2]);
        if ("u".equals(e[0])) {
            AvatarLoader.load(img, e[1]);
            row.setOnClickListener(v -> openUser(e[1], e[2]));
        } else {
            img.setImageResource(R.drawable.ic_h_search);
            img.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            img.setPadding(dp(12), dp(12), dp(12), dp(12));
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(0xFFF0F2F5);
            img.setBackground(g);
            row.setOnClickListener(v -> runQuery(e[1]));
        }
        more.setVisibility(View.VISIBLE);
        more.setOnClickListener(v -> {
            PopupMenu pm = new PopupMenu(this, v);
            pm.getMenu().add("Remove from recent");
            pm.setOnMenuItemClickListener(item -> {
                List<String[]> all = readRecent();
                for (int i = all.size() - 1; i >= 0; i--)
                    if (all.get(i)[0].equals(e[0]) && all.get(i)[1].equals(e[1])) all.remove(i);
                writeRecent(all);
                renderRecent();
                return true;
            });
            pm.show();
        });
        return row;
    }

    // ------------------------------------------------------------------ ছোট সাহায্যকারী

    private void openUser(String uid, String name) {
        saveRecentUser(uid, name);
        ProfileActivity.open(this, uid, name);
    }

    private static String str(JSONObject p, String k) {
        if (p == null || p.isNull(k)) return "";
        return p.optString(k, "").trim();
    }

    private static String nameOf(JSONObject p) {
        String n = str(p, "name");
        return n.isEmpty() ? "Member" : n;
    }

    private int color(int res) { return ContextCompat.getColor(this, res); }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(etSearch.getWindowToken(), 0);
    }

    // ------------------------------------------------------------------ People রেজাল্টের অ্যাডাপ্টার

    private class UserAdapter extends RecyclerView.Adapter<UserAdapter.VH> {
        private final List<Match> items = new ArrayList<>();

        void setItems(List<Match> l) { items.clear(); items.addAll(l); notifyDataSetChanged(); }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(getLayoutInflater().inflate(R.layout.item_search_user, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            final Match m = items.get(position);
            h.name.setText(m.name);
            h.sub.setText(m.sub);
            h.sub.setVisibility(m.sub.isEmpty() ? View.GONE : View.VISIBLE);
            h.more.setVisibility(View.GONE);
            AvatarLoader.load(h.avatar, m.uid);
            h.itemView.setOnClickListener(v -> openUser(m.uid, m.name));
        }

        @Override public int getItemCount() { return items.size(); }

        class VH extends RecyclerView.ViewHolder {
            final ImageView avatar;
            final TextView name, sub, more;
            VH(@NonNull View v) {
                super(v);
                avatar = v.findViewById(R.id.imgAvatar);
                name = v.findViewById(R.id.tvName);
                sub = v.findViewById(R.id.tvSub);
                more = v.findViewById(R.id.btnMore);
            }
        }
    }
}
