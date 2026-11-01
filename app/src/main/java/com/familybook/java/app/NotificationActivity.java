package com.familybook.app;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class NotificationActivity extends BaseActivity {

    private static final long POLL_MS = 15000;

    private NotificationAdapter adapter;
    private TextView tvEmpty;

    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean active = false;
    private int gen = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_notification);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Notifications");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        tvEmpty = findViewById(R.id.tvEmpty);
        RecyclerView rv = findViewById(R.id.rvNotifications);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new NotificationAdapter(this::onGroupClick);
        rv.setAdapter(adapter);
    }

    /** ক্লিক: পড়া হয়েছে মার্ক করো, তারপর পোস্ট / প্রোফাইলে নিয়ে যাও */
    private void onGroupClick(NotificationGroup g) {
        for (NotificationItem it : g.items) {
            if (!it.read) NotificationHelper.markRead(it.id);
        }
        final NotificationItem n = g.latest;

        // ট্যাগ / কোলাব রিকোয়েস্ট: Approve / Decline ডায়ালগ
        String type = n.type == null ? "" : n.type.toLowerCase();

        // গ্রুপ ইনভাইট: Join / Decline; অন্য গ্রুপ নোটিফিকেশনে গ্রুপ খোলে
        if (type.startsWith("group") && n.groupId != null && !n.groupId.isEmpty()) {
            if ("group_invite".equals(type)) openInviteDialog(n);
            else GroupDetailActivity.open(this, n.groupId);
            return;
        }
        if (("tag_request".equals(type) || "collab_request".equals(type))
                && n.postId != null && !n.postId.isEmpty()) {
            openRequestDialog(n, "tag_request".equals(type));
            return;
        }

        if (n.postId != null && !n.postId.isEmpty()) {
            // পোস্টটা এখনো আছে কিনা দেখে নিই (ডিলিট হয়ে গেলে ফাঁকা স্ক্রিন খুলবে না)
            SupabaseRest.call("GET", "/rest/v1/posts?id=eq." + n.postId + "&select=id",
                    null, null, true, r -> {
                if (!r.ok()) {
                    Toast.makeText(this, SupabaseRest.errorText(r), Toast.LENGTH_LONG).show();
                    return;
                }
                boolean exists = false;
                try { exists = new JSONArray(r.body).length() > 0; } catch (Exception ignored) { }
                if (exists) {
                    startActivity(new Intent(this, CommentsActivity.class)
                            .putExtra("postId", n.postId));
                } else {
                    Toast.makeText(this, "This post is no longer available", Toast.LENGTH_SHORT).show();
                }
            });
        } else if (n.actorUid != null) {
            // follow/mention ইত্যাদি — যে করেছে তার প্রোফাইলে
            ProfileActivity.open(this, n.actorUid, adapter.nameOf(n.actorUid));
        }
    }

    /** গ্রুপ ইনভাইট এখনো pending থাকলে Join / Decline ডায়ালগ, নইলে সরাসরি গ্রুপ খোলে */
    private void openInviteDialog(final NotificationItem n) {
        final String me = SupabaseRest.uid();
        if (me == null) return;
        SupabaseRest.call("GET", "/rest/v1/group_invites?group_id=eq." + n.groupId + "&invitee_id=eq." + me
                + "&select=status&limit=1", null, null, true, r -> {
            if (isFinishing() || isDestroyed()) return;
            String status = "";
            if (r.ok()) {
                try {
                    JSONArray a = new JSONArray(r.body);
                    if (a.length() > 0) status = a.getJSONObject(0).optString("status", "");
                } catch (Exception ignored) { }
            }
            if (!"pending".equals(status)) {
                GroupDetailActivity.open(this, n.groupId);
                return;
            }
            String msg = n.message != null && !n.message.isEmpty() ? n.message
                    : "You were invited to join a group";
            new AlertDialog.Builder(this)
                    .setTitle("Group invitation")
                    .setMessage(msg)
                    .setPositiveButton("Join", (d, w) -> acceptInvite(n.groupId, me))
                    .setNegativeButton("Decline", (d, w) -> declineInvite(n.groupId, me))
                    .setNeutralButton("View group", (d, w) -> GroupDetailActivity.open(this, n.groupId))
                    .show();
        });
    }

    private void acceptInvite(final String groupId, final String me) {
        try {
            JSONObject b = new JSONObject().put("group_id", groupId).put("user_id", me)
                    .put("role", "member").put("status", "active");
            SupabaseRest.call("POST", "/rest/v1/group_members", b.toString(), "return=minimal", true, r -> {
                if (isFinishing() || isDestroyed()) return;
                if (!r.ok()) {
                    Toast.makeText(this, SupabaseRest.errorText(r), Toast.LENGTH_LONG).show();
                    return;
                }
                SupabaseRest.call("PATCH", "/rest/v1/group_invites?group_id=eq." + groupId
                        + "&invitee_id=eq." + me, "{\"status\":\"accepted\"}", "return=minimal", true, x -> { });
                GroupDetailActivity.open(this, groupId);
            });
        } catch (Exception e) {
            Toast.makeText(this, "Could not join", Toast.LENGTH_SHORT).show();
        }
    }

    private void declineInvite(String groupId, String me) {
        SupabaseRest.call("PATCH", "/rest/v1/group_invites?group_id=eq." + groupId + "&invitee_id=eq." + me,
                "{\"status\":\"declined\"}", "return=minimal", true, r -> {
            if (isFinishing() || isDestroyed()) return;
            Toast.makeText(this, r.ok() ? "Invitation declined" : SupabaseRest.errorText(r),
                    Toast.LENGTH_SHORT).show();
        });
    }

    /** রিকোয়েস্ট এখনো pending থাকলে ডায়ালগ, না থাকলে সরাসরি পোস্ট খোলে */
    private void openRequestDialog(final NotificationItem n, final boolean isTag) {
        final String me = SupabaseRest.uid();
        if (me == null) return;
        String path = isTag
                ? "/rest/v1/post_tags?post_id=eq." + n.postId + "&tagged_user_id=eq." + me + "&select=status"
                : "/rest/v1/post_collaborators?post_id=eq." + n.postId + "&collaborator_id=eq." + me + "&select=status";
        SupabaseRest.call("GET", path, null, null, true, r -> {
            if (isFinishing() || isDestroyed()) return;
            String status = "";
            if (r.ok()) {
                try {
                    JSONArray a = new JSONArray(r.body);
                    if (a.length() > 0) status = a.getJSONObject(0).optString("status", "");
                } catch (Exception ignored) { }
            }
            if ("pending".equals(status)) {
                CollabApproveDialog.show(this, n.postId, adapter.nameOf(n.actorUid), isTag,
                        accepted -> load());
            } else if (status.isEmpty()) {
                Toast.makeText(this, "This request is no longer available", Toast.LENGTH_SHORT).show();
            } else {
                // আগেই approve/decline করা হয়েছে -> পোস্ট দেখাও
                startActivity(new Intent(this, CommentsActivity.class).putExtra("postId", n.postId));
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        active = true;
        load();
    }

    @Override
    protected void onStop() {
        super.onStop();
        active = false;
        gen++;
        h.removeCallbacksAndMessages(null);
    }

    /** নোটিফিকেশন আনে; খোলা থাকলে প্রতি ১৫ সেকেন্ডে আবার */
    private void load() {
        h.removeCallbacksAndMessages(null);
        if (!active) return;
        final int g = ++gen;
        String me = SupabaseRest.uid();
        if (me == null) { tvEmpty.setVisibility(View.VISIBLE); return; }

        String path = "/rest/v1/notifications?user_id=eq." + me
                + "&order=created_at.desc&limit=100";
        SupabaseRest.call("GET", path, null, null, true, r -> {
            if (!active || g != gen) return;
            if (r.ok()) show(r.body);
            h.postDelayed(this::load, POLL_MS);
        });
    }

    private void show(String body) {
        List<NotificationItem> all = new ArrayList<>();   // সার্ভার থেকেই নতুন আগে
        try {
            JSONArray a = new JSONArray(body);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null) all.add(NotificationItem.fromJson(o));
            }
        } catch (Exception e) {
            return;
        }

        // একই পোস্টের like / comment একসাথে গ্রুপ
        Map<String, NotificationGroup> map = new LinkedHashMap<>();
        for (NotificationItem n : all) {
            String t = n.type == null ? "" : n.type.toLowerCase();
            boolean groupable = n.postId != null && (t.contains("like") || t.contains("comment"));
            String key = groupable ? t + "|" + n.postId : "id|" + n.id;
            NotificationGroup g = map.get(key);
            if (g == null) map.put(key, new NotificationGroup(n));
            else g.add(n);
        }

        List<NotificationGroup> fresh = new ArrayList<>(), today = new ArrayList<>(), earlier = new ArrayList<>();
        for (NotificationGroup g : map.values()) {
            if (!g.read) { fresh.add(g); continue; }
            long ts = g.latest.timestamp;
            boolean isToday = ts == 0 || DateUtils.isToday(ts);
            (isToday ? today : earlier).add(g);
        }

        List<Object> rows = new ArrayList<>();
        if (!fresh.isEmpty()) { rows.add("New"); rows.addAll(fresh); }
        if (!today.isEmpty()) { rows.add("Today"); rows.addAll(today); }
        if (!earlier.isEmpty()) { rows.add("Earlier"); rows.addAll(earlier); }
        adapter.setRows(rows);
        tvEmpty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
    }

    @Override
    public boolean onSupportNavigateUp() { finish(); return true; }
}
