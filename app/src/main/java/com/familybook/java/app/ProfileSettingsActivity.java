package com.familybook.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * ProfileActivity-র কভারের ৩-ডট বাটন থেকে খোলে (Facebook-এর Profile Settings পেজ)।
 * নিজের প্রোফাইলে সব অপশন; অন্যের প্রোফাইলে শুধু Publish profile / Profile link / Invite people / Share profile।
 */
public class ProfileSettingsActivity extends Activity {

    static final String BASE = "https://familybook-25n.pages.dev/";

    static void open(Activity a, String uid, String name, String slug,
                     String avatarKey, String coverKey, boolean isMe, boolean locked, long lockedAtMs) {
        a.startActivity(new Intent(a, ProfileSettingsActivity.class)
                .putExtra("uid", uid).putExtra("name", name).putExtra("slug", slug)
                .putExtra("avatarKey", avatarKey).putExtra("coverKey", coverKey)
                .putExtra("isMe", isMe).putExtra("locked", locked).putExtra("lockedAt", lockedAtMs));
    }

    private String uid, name, slug, avatarKey, coverKey;
    private boolean isMe, locked;
    private long lockedAt;
    private LinearLayout list;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        uid = getIntent().getStringExtra("uid");
        name = getIntent().getStringExtra("name");
        slug = getIntent().getStringExtra("slug");
        avatarKey = getIntent().getStringExtra("avatarKey");
        coverKey = getIntent().getStringExtra("coverKey");
        isMe = getIntent().getBooleanExtra("isMe", false);
        locked = getIntent().getBooleanExtra("locked", false);
        lockedAt = getIntent().getLongExtra("lockedAt", 0L);
        if (name == null || name.isEmpty()) name = "Family Member";
        build();
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    private String link() { return BASE + (slug == null ? "" : slug); }

    private void build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        // উপরের বার: বামে back আইকন, মাঝে "Profile Settings", ডানে ছোট গোল প্রোফাইল আইকন
        FrameLayout bar = new FrameLayout(this);
        bar.setBackgroundColor(Color.WHITE);

        ImageView back = new ImageView(this);
        back.setImageResource(R.drawable.ic_back_left);
        back.setScaleType(ImageView.ScaleType.FIT_CENTER);
        back.setPadding(dp(14), dp(14), dp(14), dp(14));
        back.setOnClickListener(v -> finish());
        bar.addView(back, new FrameLayout.LayoutParams(dp(52), dp(52), Gravity.START | Gravity.CENTER_VERTICAL));

        TextView title = new TextView(this);
        title.setText("Profile Settings");
        title.setTextSize(20);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(Color.parseColor("#1C1E21"));
        title.setGravity(Gravity.CENTER);
        bar.addView(title, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        final ImageView me = new ImageView(this);
        me.setScaleType(ImageView.ScaleType.CENTER_CROP);
        me.setImageResource(R.drawable.ic_profile);
        me.setClipToOutline(true);
        me.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) { o.setOval(0, 0, v.getWidth(), v.getHeight()); }
        });
        me.setOnClickListener(v -> finish()); // এই সেটিংস যে প্রোফাইল থেকে খোলা, সেই প্রোফাইলে ফেরত
        FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(dp(34), dp(34), Gravity.END | Gravity.CENTER_VERTICAL);
        mp.setMarginEnd(dp(14));
        bar.addView(me, mp);
        if (avatarKey != null) {
            Media.loadBitmap(avatarKey, bm -> {
                if (isFinishing() || bm == null) return;
                me.setImageBitmap(bm);
            });
        }
        root.addView(bar, new LinearLayout.LayoutParams(-1, dp(56)));

        View line = new View(this);
        line.setBackgroundColor(Color.parseColor("#F0F2F5"));
        root.addView(line, new LinearLayout.LayoutParams(-1, dp(4)));

        ScrollView sv = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        if (isMe) {
            row(R.drawable.profile_status, "Profile Status", v -> soon());
            row(R.drawable.view_as, "View as", v -> {
                // লক না থাকলে: নিজের আসল প্রোফাইল, অন্যে যেমন দেখে তেমন। লক থাকলে: আগের মতো সীমিত প্রিভিউ
                if (locked) ViewAsActivity.open(this, name, avatarKey, coverKey, true);
                else ProfileActivity.openViewAs(this, uid, name);
            });
            row(R.drawable.activity_logs, "Activity log", v -> soon());
            row(R.drawable.review_tag_post, "Review tag", v -> soon());
            row(R.drawable.ic_h_search, "Search", v -> soon());
        }
        row(R.drawable.ic_menu_share, "Share profile", v -> shareProfile());
        row(R.drawable.invit_people, "Invite people", v -> invite());
        if (isMe) {
            row(R.drawable.ic_profile_locked, "Lock profile", v -> LockProfileActivity.open(this, avatarKey, locked, lockedAt));
            row(R.drawable.get_verified, "Get verified", v -> soon());
        }
        row(R.drawable.publish_profile, "Publish profile", v -> soon());

        View gap = new View(this);
        gap.setBackgroundColor(Color.parseColor("#F0F2F5"));
        list.addView(gap, new LinearLayout.LayoutParams(-1, dp(8)));

        TextView t = new TextView(this);
        t.setText(isMe ? "Your link" : "Profile link");
        t.setTextSize(22);
        t.setTypeface(null, Typeface.BOLD);
        t.setTextColor(Color.parseColor("#1C1E21"));
        t.setPadding(dp(20), dp(18), dp(20), 0);
        list.addView(t);

        TextView sub = new TextView(this);
        sub.setText("Your personalized link on Familybook.");
        sub.setTextSize(15);
        sub.setTextColor(Color.parseColor("#65676B"));
        sub.setPadding(dp(20), dp(2), dp(20), dp(10));
        list.addView(sub);

        TextView l = new TextView(this);
        l.setText(link());
        l.setTextSize(16);
        l.setTypeface(null, Typeface.BOLD);
        l.setTextColor(Color.parseColor("#0866FF"));
        l.setPadding(dp(20), dp(10), dp(20), dp(10));
        list.addView(l);

        Button copy = new Button(this);
        copy.setText("Copy link");
        copy.setAllCaps(false);
        copy.setTextColor(Color.parseColor("#1C1E21"));
        copy.setBackgroundColor(Color.parseColor("#E4E6EB"));
        copy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("Familybook link", link()));
            TopToast.show(this, "Link copied");
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(dp(20), dp(4), dp(20), dp(28));
        list.addView(copy, lp);
    }

    // সব অপশন: একই সাইজের আইকন, একই ফন্ট, একই সাইজ, কোনোটাই bold না
    private void row(int iconRes, String label, View.OnClickListener c) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(20), dp(14), dp(20), dp(14));
        ImageView i = new ImageView(this);
        i.setImageResource(iconRes);
        i.setScaleType(ImageView.ScaleType.FIT_CENTER);
        r.addView(i, new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(18);
        t.setTypeface(Typeface.DEFAULT);
        t.setTextColor(Color.parseColor("#1C1E21"));
        t.setPadding(dp(20), 0, 0, 0);
        r.addView(t);
        r.setOnClickListener(c);
        list.addView(r);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == LockProfileActivity.REQ && res == RESULT_OK && data != null) {
            locked = data.getBooleanExtra("locked", locked);
            lockedAt = data.getLongExtra("lockedAt", lockedAt);
        }
    }

    private void soon() { TopToast.show(this, "Coming soon"); }

    // ---------- Share profile ----------
    // ধাপ ২: সবার আগে FamilyChat চ্যাট লিস্ট, "More" এ ফোনের অ্যাপ + Share to timeline
    private void shareProfile() {
        Intent s = new Intent(Intent.ACTION_SEND);
        s.setType("text/plain");
        s.putExtra(Intent.EXTRA_TEXT, name + " on Familybook\n" + link());
        startActivity(Intent.createChooser(s, "Share profile"));
    }

    // ---------- Invite people ----------
    private void invite() {
        final String me = Me.uid();
        SupabaseRest.call("GET", "/rest/v1/follows?followee_id=eq." + me
                + "&select=follower_id&limit=1000", null, null, true, r -> {
            if (isFinishing()) return;
            if (!r.ok()) { TopToast.show(this, SupabaseRest.errorText(r)); return; }
            final List<String> ids = new ArrayList<>();
            try {
                JSONArray a = new JSONArray(r.body);
                for (int i = 0; i < a.length(); i++) ids.add(a.getJSONObject(i).optString("follower_id"));
            } catch (Exception ignored) { }
            if (ids.isEmpty()) { TopToast.show(this, "You have no followers to invite yet"); return; }
            loadNames(ids);
        });
    }

    private void loadNames(final List<String> ids) {
        final String[] names = new String[ids.size()];
        final int[] left = {ids.size()};
        for (int i = 0; i < ids.size(); i++) {
            final int idx = i;
            UserNames.fetch(ids.get(i), n -> {
                names[idx] = (n == null || n.isEmpty()) ? "Family Member" : n;
                if (--left[0] == 0 && !isFinishing()) showPicker(ids, names);
            });
        }
    }

    private void showPicker(final List<String> ids, final String[] names) {
        final boolean[] on = new boolean[ids.size()];
        new AlertDialog.Builder(this)
                .setTitle("Invite followers to follow " + (isMe ? "your profile" : name))
                .setMultiChoiceItems(names, on, (d, which, checked) -> on[which] = checked)
                .setPositiveButton("Send invite", (d, w) -> {
                    List<String> picked = new ArrayList<>();
                    for (int i = 0; i < on.length; i++) if (on[i]) picked.add(ids.get(i));
                    sendInvites(picked);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void sendInvites(final List<String> to) {
        if (to.isEmpty()) { TopToast.show(this, "Select at least one person"); return; }
        final String me = Me.uid();
        PostOps.loadMyName(me, myName -> {
            String who = (myName == null || myName.isEmpty()) ? "Family Member" : myName;
            String msg = isMe ? who + " invited you to follow their profile"
                              : who + " invited you to follow " + name;
            try {
                JSONArray rows = new JSONArray();
                for (String u : to) {
                    rows.put(new JSONObject()
                            .put("user_id", u).put("actor_id", me)
                            .put("type", "follow_invite").put("message", msg)
                            .put("is_read", false));
                }
                SupabaseRest.call("POST", "/rest/v1/notifications", rows.toString(),
                        "return=minimal", true, r -> {
                    if (isFinishing()) return;
                    TopToast.show(this, r.ok() ? "Invitation sent" : SupabaseRest.errorText(r));
                });
            } catch (Exception e) {
                TopToast.show(this, "Could not send invite");
            }
        });
    }
}
