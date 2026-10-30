package com.familybook.app;

import android.content.Context;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ফিডের পোস্ট কার্ডের গ্রুপ-নামের পাশের Join বাটনের লজিক।
 *  - Public গ্রুপ: Join চাপলেই সদস্য (active)
 *  - Friends গ্রুপ: Join চাপলে রিকোয়েস্ট (pending) যায়; অ্যাডমিন approve করলে তবেই সদস্য
 *  - ইনভাইট পেলে যেকোনো গ্রুপে সরাসরি জয়েন
 * GroupDetailActivity-র join() এর মতোই একই টেবিলে (group_members) লেখে।
 */
public final class GroupJoin {

    private static final Map<String, String> STATUS = new ConcurrentHashMap<>();
    private static volatile boolean loaded = false;

    private GroupJoin() { }

    public interface Done { void run(); }

    /** null = এখনো জানা নেই (বাটন লুকানো), "none" = সদস্য নই, নইলে active / pending */
    public static String status(String gid) {
        if (!loaded || gid == null) return null;
        String s = STATUS.get(gid);
        return s == null ? "none" : s;
    }

    /** আমার সব গ্রুপ-সদস্যতা (active/pending) একবারে আনা */
    public static void load(final Done onReady) {
        final String my = SupabaseRest.uid();
        if (my == null) return;
        SupabaseRest.call("GET", "/rest/v1/group_members?user_id=eq." + my
                + "&select=group_id,status&limit=2000", null, null, true, r -> {
            if (!r.ok()) return;
            try {
                JSONArray a = new JSONArray(r.body);
                STATUS.clear();
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    String g = o.optString("group_id", "");
                    String st = o.optString("status", "active");
                    if (!g.isEmpty()) STATUS.put(g, st);
                }
                loaded = true;
            } catch (Exception ignored) { }
            if (onReady != null) onReady.run();
        });
    }

    /** Join / Join request */
    public static void join(final Context ctx, final String gid, final String visHint, final Done done) {
        final String my = SupabaseRest.uid();
        if (my == null || gid == null) return;
        SupabaseRest.call("GET", "/rest/v1/groups?id=eq." + gid
                + "&select=owner_id,visibility,name,post_mode&limit=1", null, null, true, gr -> {
            String owner = "", vis = visHint == null ? "" : visHint, gname = "", mode = "auto";
            try {
                JSONArray a = new JSONArray(gr.body);
                if (gr.ok() && a.length() > 0) {
                    JSONObject g = a.getJSONObject(0);
                    owner = g.isNull("owner_id") ? "" : g.optString("owner_id", "");
                    if (!g.isNull("visibility")) vis = g.optString("visibility", vis);
                    gname = g.isNull("name") ? "" : g.optString("name", "");
                    mode = g.isNull("post_mode") ? "auto" : g.optString("post_mode", "auto");
                }
            } catch (Exception ignored) { }
            final String fOwner = owner, fVis = vis, fName = gname;
            final boolean needApproval = "admin_approval".equals(mode) || "admins_only".equals(mode);
            final boolean inviteNeedsApproval = "admins_only".equals(mode);
            SupabaseRest.call("GET", "/rest/v1/group_invites?group_id=eq." + gid + "&invitee_id=eq." + my
                    + "&status=eq.pending&select=id&limit=1", null, null, true, ir -> {
                boolean invited = false;
                try {
                    JSONArray a = new JSONArray(ir.body);
                    invited = ir.ok() && a.length() > 0;
                } catch (Exception ignored) { }
                // ২ ও ৩নং মোডে Join approval লাগে; ৩নং-এ Invite পেলেও approval লাগে
                if (invited) insert(ctx, gid, my, inviteNeedsApproval ? "pending" : "active", true, fOwner, fName, done);
                else if ("public".equals(fVis) || "friends".equals(fVis))
                    insert(ctx, gid, my, needApproval ? "pending" : "active", false, fOwner, fName, done);
                else Toast.makeText(ctx, "এটি শুধু ইনভাইটে জয়েন করা যায়", Toast.LENGTH_SHORT).show();
            });
        });
    }

    private static void insert(final Context ctx, final String gid, final String my, final String status,
                               final boolean viaInvite, final String owner, final String gname, final Done done) {
        try {
            JSONObject b = new JSONObject().put("group_id", gid).put("user_id", my)
                    .put("role", "member").put("status", status);
            SupabaseRest.call("POST", "/rest/v1/group_members", b.toString(), "return=minimal", true, r -> {
                if (!r.ok()) {
                    Toast.makeText(ctx, SupabaseRest.errorText(r), Toast.LENGTH_SHORT).show();
                    return;
                }
                STATUS.put(gid, status);
                if (viaInvite) {
                    SupabaseRest.call("PATCH", "/rest/v1/group_invites?group_id=eq." + gid
                            + "&invitee_id=eq." + my, "{\"status\":\"accepted\"}", "return=minimal", true, x -> { });
                }
                if ("pending".equals(status)) {
                    Toast.makeText(ctx, "Join request পাঠানো হয়েছে। অ্যাডমিন approve করলে সদস্য হবেন।",
                            Toast.LENGTH_LONG).show();
                    if (owner != null && !owner.isEmpty()) {
                        PostOps.loadMyName(my, n -> NotificationHelper.sendGroup(owner, "group_join_request", gid,
                                (n == null || n.isEmpty() ? "Someone" : n) + " wants to join your group \"" + gname + "\""));
                    }
                } else {
                    Toast.makeText(ctx, "আপনি গ্রুপে যোগ দিয়েছেন", Toast.LENGTH_SHORT).show();
                }
                if (done != null) done.run();
            });
        } catch (Exception e) {
            Toast.makeText(ctx, "জয়েন করা যায়নি", Toast.LENGTH_SHORT).show();
        }
    }

    /** পেন্ডিং রিকোয়েস্ট বাতিল */
    public static void cancel(final Context ctx, final String gid, final Done done) {
        final String my = SupabaseRest.uid();
        if (my == null || gid == null) return;
        SupabaseRest.call("DELETE", "/rest/v1/group_members?group_id=eq." + gid + "&user_id=eq." + my
                + "&status=eq.pending", null, "return=minimal", true, r -> {
            if (!r.ok()) {
                Toast.makeText(ctx, SupabaseRest.errorText(r), Toast.LENGTH_SHORT).show();
                return;
            }
            STATUS.remove(gid);
            Toast.makeText(ctx, "রিকোয়েস্ট বাতিল হয়েছে", Toast.LENGTH_SHORT).show();
            if (done != null) done.run();
        });
    }
}
