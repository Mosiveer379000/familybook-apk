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
import android.view.ViewGroup;
import android.widget.Button;
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

        // উপরের বার
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(12), dp(8), dp(12));
        TextView back = new TextView(this);
        back.setText("←");
        back.setTextSize(26);
        back.setTextColor(Color.parseColor("#1C1E21"));
        back.setPadding(dp(12), 0, dp(12), 0);
        back.setOnClickListener(v -> finish());
        bar.addView(back);
        TextView title = new TextView(this);
        title.setText("Profile Settings");
        title.setTextSize(20);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(Color.parseColor("#1C1E21"));
        bar.addView(title);
        root.addView(bar);

        ScrollView sv = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        if (isMe) {
            row("🛡", "Profile status", false, v -> soon());
            row("👁", "View as", false, v -> ViewAsActivity.open(this, name, avatarKey, coverKey, locked));
            row("☰", "Activity log", false, v -> soon());
            row("📝", "Review posts and tags", false, v -> soon());
            row("🔍", "Search", false, v -> soon());
        }
        row("↪", "Share profile", false, v -> shareProfile());
        row("➕", "Invite people to connect", false, v -> invite());
        if (isMe) {
            rowRes(R.drawable.ic_profile_locked, "Lock Profile", true, v -> LockProfileActivity.open(this, avatarKey, locked, lockedAt));
            row("✔", "Get verified", true, v -> soon());
        }
        row("📢", "Publish profile", true, v -> soon());

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
        l.setTextColor(Color.parseColor("#1C1E21"));
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

    private void rowRes(int res, String label, boolean bold, View.OnClickListener c) {
        row("", label, bold, c);
        LinearLayout r = (LinearLayout) list.getChildAt(list.getChildCount() - 1);
        TextView old = (TextView) r.getChildAt(0);
        android.widget.ImageView iv = new android.widget.ImageView(this);
        iv.setImageResource(res);
        iv.setPadding(dp(5), dp(5), dp(5), dp(5));
        r.removeViewAt(0);
        r.addView(iv, 0, new LinearLayout.LayoutParams(dp(40), dp(34)));
    }

    private void row(String icon, String label, boolean bold, View.OnClickListener c) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(20), dp(15), dp(20), dp(15));
        TextView i = new TextView(this);
        i.setText(icon);
        i.setTextSize(22);
        i.setGravity(Gravity.CENTER);
        r.addView(i, new LinearLayout.LayoutParams(dp(40), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(18);
        t.setTextColor(Color.parseColor("#1C1E21"));
        if (bold) t.setTypeface(null, Typeface.BOLD);
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
