package com.familybook.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;



public class PostOps {

    private static final long TRASH_TTL_MS = 604800000L;     // 7 দিন
    private static final long ARCHIVE_TTL_MS = 2592000000L;  // 30 দিন

    public interface NameCb {
        void on(String name);
    }

    /** যে স্ক্রিনে পোস্ট দেখানো হয় সেটি এটি implement করলে, পোস্ট বদলালে নিজে থেকে রিফ্রেশ হবে */
    public interface Refreshable {
        void refreshPosts();
    }

    /** Edit post স্ক্রিন যে স্ক্রিন থেকে খোলা হয়েছে (সেভের পর সেটি রিফ্রেশ হবে) */
    private static java.lang.ref.WeakReference<Activity> editHost;

    /** আমার প্রোফাইলে পিন করা পোস্টের id (ফাঁকা = নেই)। ProfileActivity লোডের সময় সেট করে */
    static String myPinnedId = "";

    /** পোস্ট প্রোফাইলের একদম শুরুতে পিন / আনপিন (profiles.info.pinnedPost) */
    static void togglePin(final Activity a, final Post p) {
        final String me = SupabaseRest.uid();
        if (me == null || p.id == null) return;
        Db.get("profiles", "id", me, (row, err) -> {
            String cur = "";
            JSONObject info = row == null ? null : row.optJSONObject("info");
            if (info != null) cur = info.optString("pinnedPost", "");
            final boolean unpin = p.id.equals(cur);
            try {
                Db.mergeMyInfo(new JSONObject().put("pinnedPost", unpin ? "" : p.id), e2 -> {
                    if (e2 != null) {
                        Toast.makeText(a, e2, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    myPinnedId = unpin ? "" : p.id;
                    Toast.makeText(a, unpin ? "Unpinned" : "Pinned to your profile", Toast.LENGTH_SHORT).show();
                    changed(a);
                });
            } catch (Exception ex) {
                Toast.makeText(a, "Could not pin", Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** পোস্ট বদলালে: খোলা ছবির ভিউয়ার বন্ধ করে নিচের স্ক্রিন রিফ্রেশ */
    static void changed(Activity a) {
        PostImage.dismissViewer();
        refreshHost(a);
    }

    static void refreshHost(Activity a) {
        if (a instanceof Refreshable) {
            ((Refreshable) a).refreshPosts();
        } else if (editHost != null) {
            Activity h = editHost.get();
            if (h instanceof Refreshable && !h.isFinishing()) ((Refreshable) h).refreshPosts();
        }
    }

    private static JSONObject obj(Object... kv) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i + 1 < kv.length; i += 2) o.put((String) kv[i], kv[i + 1]);
        } catch (Exception ignored) {
        }
        return o;
    }

    private static String iso(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(ms));
    }

    private static void toast(Activity a, SupabaseRest.Resp r) {
        Toast.makeText(a, SupabaseRest.errorText(r), Toast.LENGTH_LONG).show();
    }

    /** আমার ট্রাশের ৭ দিনের পুরনো ও আর্কাইভের ৩০ দিনের পুরনো পোস্ট মুছে দেয় (অ্যাপ খুললে চলে) */
    static void cleanup(String uid) {
        long now = System.currentTimeMillis();
        SupabaseRest.call("DELETE", "/rest/v1/posts?user_id=eq." + uid + "&status=eq.trashed"
                + "&status_at=lt." + iso(now - TRASH_TTL_MS), null, "return=minimal", true, r -> { });
        SupabaseRest.call("DELETE", "/rest/v1/posts?user_id=eq." + uid + "&status=eq.archived"
                + "&status_at=lt." + iso(now - ARCHIVE_TTL_MS), null, "return=minimal", true, r -> { });
    }

    /** profiles.name থেকে আমার নাম; ফাঁকা বা ব্যর্থ হলে null */
    private static String nameFrom(SupabaseRest.Resp r) {
        if (!r.ok()) return null;
        try {
            JSONArray arr = new JSONArray(r.body);
            if (arr.length() == 0) return null;
            JSONObject o = arr.getJSONObject(0);
            if (o.isNull("name")) return null;
            String n = o.optString("name", "").trim().replaceAll("\\s+", " ");
            return n.isEmpty() ? null : n;
        } catch (Exception e) {
            return null;
        }
    }

    /** নাম পেলেই callback চলে (না পেলে চলে না) */
    static void loadMyName(String uid, NameCb cb) {
        SupabaseRest.call("GET", "/rest/v1/profiles?id=eq." + uid + "&select=name",
                null, null, true, r -> {
            String n = nameFrom(r);
            if (n != null) cb.on(n);
        });
    }

    /** নাম না পেলেও callback চলবে (default "Family Member") */
    private static void nameThen(String uid, NameCb cb) {
        SupabaseRest.call("GET", "/rest/v1/profiles?id=eq." + uid + "&select=name",
                null, null, true, r -> {
            String n = nameFrom(r);
            cb.on(n == null ? "Family Member" : n);
        });
    }

    /** নিউজ ফিডে পোস্ট লুকানো: শুধু আমার ফিডে, প্রোফাইলে নয় */
    static void hide(Activity a, String uid, String postId) {
        SupabaseRest.call("POST", "/rest/v1/hidden_posts",
                obj("user_id", uid, "post_id", postId).toString(),
                "resolution=ignore-duplicates,return=minimal", true, r -> {
            if (r.ok()) changed(a); else toast(a, r);
        });
    }

    static void unhide(Activity a, String uid, String postId) {
        SupabaseRest.call("DELETE", "/rest/v1/hidden_posts?user_id=eq." + uid + "&post_id=eq." + postId,
                null, "return=minimal", true, r -> {
            if (r.ok()) changed(a); else toast(a, r);
        });
    }

    /** প্রোফাইল/ফটো ঘরের জন্য (Hide অপশন ছাড়া) */
    static void showMenu(Activity a, String uid, Post p, View anchor) {
        showMenu(a, uid, p, anchor, false);
    }

    /** feed = true হলে (Home) Hide/Unhide অপশন দেখাবে */
    static void showMenu(Activity a, String uid, Post p, View anchor,
                         boolean feed) {
        // Short ভিডিও: নিচ থেকে ওঠা নতুন মেনু শিট (পুরোনো ডায়ালগ মেনু শুধু Status text / Photos-এর জন্য)
        if (isShortPost(p)) {
            ShortMenuSheet.show(a, uid, p);
            return;
        }
        final boolean mine = p.isMine(uid);
        final Dialog[] dlg = new Dialog[1];
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(a, 4), dp(a, 6), dp(a, 4), dp(a, 6));

        // ---- মেনুর আইটেম (ডিজাইনের ক্রমে) ----
        // আইকনের নাম = res/drawable এর ফাইলের নাম (.png/.xml); ফাইল না থাকলে ইমোজি দেখাবে
        if (mine) {
            row(a, box, dlg, true, "ic_menu_edit", "\u270F\uFE0F", "Edit post",
                    "After Edit Posting time will be changed & shows Edited",
                    () -> editPost(a, p));
            row(a, box, dlg, true, "ic_menu_comments", "\uD83D\uDCAC", "Comments",
                    "who can comment ?   (now: " + labelOf(p.commentPerm) + ")",
                    () -> choose(a, p, "comment_perm", "Who can comment",
                            new String[]{"Public", "Friends", "Only me"},
                            new String[]{"public", "friends", "me"}));
            row(a, box, dlg, true, "ic_menu_privacy", "\uD83C\uDF10", "Edit Privacy",
                    "Public - Friends - Only me   (now: " + labelOf(p.privacy) + ")",
                    () -> choose(a, p, "privacy", "Who can see this post",
                            new String[]{"Public", "Friends", "Only me"},
                            new String[]{"public", "friends", "me"}));
            row(a, box, dlg, true,
                    a.getResources().getIdentifier("ic_menu_sound", "drawable", a.getPackageName()) != 0
                            ? "ic_menu_sound" : "ic_h_bell",
                    "\uD83D\uDD0A", "Reaction sounds",
                    soundNow(p), () -> soundSettings(a, p));
            if ("active".equals(p.status)) {
                row(a, box, dlg, true, "ic_menu_archive", "\uD83D\uDDC2\uFE0F", "Archive",
                        "Archive will auto deleted after 30 days",
                        () -> setStatus(a, p, "archived"));
                row(a, box, dlg, true, "ic_menu_trash", "\uD83D\uDDD1\uFE0F", "Trash",
                        "Trash will be Deleted After 7 Days",
                        () -> setStatus(a, p, "trashed"));
            } else {
                row(a, box, dlg, true, "ic_menu_restore", "\u21A9\uFE0F", "Restore",
                        "Move this post back to your timeline",
                        () -> setStatus(a, p, "active"));
            }
        }
        if (mine && "active".equals(p.status)) {
            final boolean pinnedNow = p.id != null && p.id.equals(myPinnedId);
            row(a, box, dlg, true, "ic_pin_black", "\uD83D\uDCCC",
                    pinnedNow ? "Unpin from profile" : "Pin to profile",
                    pinnedNow ? "Remove from the top of your profile" : "Show this post first on your profile",
                    () -> togglePin(a, p));
        }
        row(a, box, dlg, true, p.mutedByMe ? "ic_menu_bell_on" : "ic_menu_bell_off",
                p.mutedByMe ? "\uD83D\uDD14" : "\uD83D\uDD15",
                p.mutedByMe ? "Turn on" : "Turn off",
                "Notifications for this post",
                () -> toggleMute(a, uid, p));
        row(a, box, dlg, false, "ic_menu_copy", "\uD83D\uDCCB", "Copy link",
                "Copy post link to share on timeline", () -> copyLink(a, p));
        if (feed) {
            if (p.hiddenByMe) {
                row(a, box, dlg, true, "ic_menu_unhide", "\uD83D\uDC41\uFE0F", "Unhide",
                        "Show this post in your news feed again", () -> unhide(a, uid, p.id));
            } else {
                row(a, box, dlg, true, "ic_menu_hide", "\uD83D\uDE48", "Hide",
                        "Hide from your news feed only (not from profile)", () -> hide(a, uid, p.id));
            }
        }
        if (mine) {
            row(a, box, dlg, true, "ic_menu_delete", "\u274C", "Permanent Delete", null,
                    () -> confirmDelete(a, p));
        }
        row(a, box, dlg, false, "ic_menu_share", "\u21AA\uFE0F", "Share", null, () -> share(a, p));

        // ---- ডায়ালগ ----
        ScrollView sv = new ScrollView(a);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF333333);
        bg.setCornerRadius(dp(a, 18));
        sv.setBackground(bg);
        sv.setClipToOutline(true);
        sv.addView(box);

        Dialog d = new Dialog(a);
        dlg[0] = d;
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setContentView(sv);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setDimAmount(0.25f);
            DisplayMetrics dm = a.getResources().getDisplayMetrics();
            int width = dp(a, 250);
            box.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int h = Math.min(box.getMeasuredHeight(), (int) (dm.heightPixels * 0.85f));
            int[] loc = new int[2];
            anchor.getLocationOnScreen(loc);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.width = width;
            lp.height = h;
            lp.gravity = Gravity.TOP | Gravity.END;
            lp.x = dp(a, 12);
            lp.y = Math.max(dp(a, 8), Math.min(loc[1] - dp(a, 24), dm.heightPixels - h - dp(a, 48)));
            w.setAttributes(lp);
        }
        d.show();
    }

    /** ভিডিও (Short) পোস্ট কি না — "uploaded a short" মার্ক, নয়তো ছবির key কোনো Short-এর থাম্বনেইল */
    static boolean isShortPost(Post p) {
        if (p == null || p.id == null) return false;
        if (PostExtras.isShort(p.id)) return true;
        String k = PostImage.get(p.id);
        return k != null && ShortStore.byThumb(k) != null;
    }

    /** এই পোস্টের নোটিফিকেশন বন্ধ / চালু */
    static void toggleMute(final Activity a, String uid, final Post p) {
        final boolean mute = !p.mutedByMe;
        SupabaseRest.call("POST", "/rest/v1/post_user_state?on_conflict=post_id,user_id",
                obj("post_id", p.id, "user_id", uid, "muted", mute).toString(),
                "resolution=merge-duplicates,return=minimal", true, r -> {
            if (r.ok()) {
                p.mutedByMe = mute;
                changed(a);
            } else {
                toast(a, r);
            }
        });
    }

    private static int dp(Activity a, int v) {
        return (int) (v * a.getResources().getDisplayMetrics().density + 0.5f);
    }

    static String labelOf(String v) {
        if ("friends".equals(v)) return "Friends";
        if ("me".equals(v)) return "Only me";
        return "Public";
    }

    /** একটি মেনু সারি: সাদা আইকন (drawable) + সাদা শিরোনাম + ছোট সাদা লেখা.
     *  iconName নামে drawable না পেলে emoji দেখায়। */
    private static void row(Activity a, LinearLayout parent, Dialog[] dlg, boolean net,
                            String iconName, String emoji,
                            String title, String sub, Runnable action) {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(a, 10), dp(a, 5), dp(a, 10), dp(a, 5));
        r.setClickable(true);

        int resId = a.getResources().getIdentifier(iconName, "drawable", a.getPackageName());
        if (resId != 0) {
            ImageView iv = new ImageView(a);
            iv.setImageResource(resId);
            iv.setColorFilter(Color.WHITE);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setPadding(dp(a, 4), dp(a, 4), dp(a, 4), dp(a, 4));
            r.addView(iv, new LinearLayout.LayoutParams(dp(a, 30), dp(a, 30)));
        } else {
            TextView ic = new TextView(a);
            ic.setText(emoji);
            ic.setTextSize(17);
            ic.setTextColor(Color.WHITE);
            ic.setGravity(Gravity.CENTER);
            r.addView(ic, new LinearLayout.LayoutParams(dp(a, 30), dp(a, 30)));
        }

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(a, 6), 0, 0, 0);

        TextView t = new TextView(a);
        t.setText(title);
        t.setTextColor(Color.WHITE);
        t.setTextSize(15);
        col.addView(t);

        if (sub != null) {
            TextView s2 = new TextView(a);
            s2.setText(sub);
            s2.setTextColor(Color.WHITE);
            s2.setTextSize(10.5f);
            col.addView(s2);
        }
        r.addView(col, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        r.setOnClickListener(v -> {
            if (dlg[0] != null) dlg[0].dismiss();
            if (net && !Net.check(a)) return; // অফলাইনে কাজ হবে না
            action.run();
        });
        parent.addView(r);
    }

    private static String link(Post p) {
        return "familybook://post/" + p.id;
    }

    static void copyLink(Activity a, Post p) {
        ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("post link", link(p)));
            Toast.makeText(a, "Link copied", Toast.LENGTH_SHORT).show();
        }
    }

    /** Share বাটন: নিজের টাইমলাইনে শেয়ার (ফেসবুকের মতো) অথবা অন্য অ্যাপে */
    static void share(Activity a, Post p) {
        if (!"public".equals(p.privacy)) {
            Toast.makeText(a, "Only public posts can be shared", Toast.LENGTH_LONG).show();
            return;
        }
        if (SupabaseRest.uid() == null) return;

        String[] opts = {"Share to timeline", "Share with a caption...", "Share to other apps"};
        new AlertDialog.Builder(a).setTitle("Share")
                .setItems(opts, (d, which) -> {
                    if (which == 0) {
                        if (!Net.check(a)) return;
                        shareToTimeline(a, p, "");
                    } else if (which == 1) {
                        EditText et = new EditText(a);
                        et.setHint("Say something about this...");
                        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
                        et.setMinLines(2);
                        et.setPadding(48, 32, 48, 32);
                        new AlertDialog.Builder(a).setTitle("Share with a caption").setView(et)
                                .setPositiveButton("Share", (d2, w) -> {
                                    if (!Net.check(a)) return;
                                    shareToTimeline(a, p, et.getText().toString().trim());
                                })
                                .setNegativeButton("Cancel", null).show();
                    } else {
                        shareExternal(a, p);
                    }
                })
                .setNegativeButton("Cancel", null).show();
    }

    private static void shareToTimeline(Activity a, Post p, String caption) {
        final String myUid = SupabaseRest.uid();
        if (myUid == null) return;
        // শেয়ার করা পোস্ট আবার শেয়ার করলে আসল পোস্টটাই ধরা হয়
        final boolean again = p.sharedPostId != null;
        final String origId = again ? p.sharedPostId : p.id;
        final String origUid = again ? p.sharedUid : p.uid;

        nameThen(myUid, myName -> {
            // share_post ডাটাবেস ফাংশন একসাথে নতুন পোস্ট ও শেয়ার সংখ্যা বসায়
            SupabaseRest.call("POST", "/rest/v1/rpc/share_post",
                    obj("orig", origId, "caption", caption, "my_name", myName).toString(),
                    null, true, r -> {
                if (r.ok()) {
                    Toast.makeText(a, "Shared to your timeline", Toast.LENGTH_SHORT).show();
                    NotificationHelper.send(origUid, "share", origId);
                    changed(a);
                } else if (r.body != null && r.body.contains("already shared")) {
                    Toast.makeText(a, "You already shared this post", Toast.LENGTH_SHORT).show();
                } else {
                    toast(a, r);
                }
            });
        });
    }

    private static void shareExternal(Activity a, Post p) {
        String body = (p.name == null ? "" : p.name + ":\n")
                + (p.text == null ? "" : p.text) + "\n\n" + link(p);
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, body);
        a.startActivity(Intent.createChooser(i, "Share"));
    }

    private static void patchPost(Activity a, String postId, JSONObject body) {
        SupabaseRest.call("PATCH", "/rest/v1/posts?id=eq." + postId, body.toString(),
                "return=minimal", true, r -> {
            if (r.ok()) changed(a); else toast(a, r);
        });
    }

    /** Archive / Trash / Restore। Archive বা Trash হলে ওই পোস্টের গান এখনই বন্ধ হবে */
    static void setStatus(Activity a, Post p, String status) {
        if (!"active".equals(status)) MusicPlayer.stopPost(p.id);
        patchPost(a, p.id, obj("status", status, "status_at", iso(System.currentTimeMillis())));
    }

    static void choose(Activity a, Post p, String column, String title,
                               String[] labels, String[] values) {
        new AlertDialog.Builder(a).setTitle(title).setItems(labels, (d, which) ->
                patchPost(a, p.id, obj(column, values[which]))
        ).show();
    }

    private static String soundNow(Post p) {
        StringBuilder sb = new StringBuilder();
        if (p.soundHaha) sb.append("Haha");
        if (p.soundCheck) sb.append(sb.length() > 0 ? ", " : "").append("Check");
        if (p.soundWow) sb.append(sb.length() > 0 ? ", " : "").append("Wow");
        return "Default or meme sounds   (now: "
                + (sb.length() == 0 ? "Default" : sb.toString()) + ")";
    }

    /** পোস্টের মালিক: কোন কোন রিঅ্যাকশনে মিম সাউন্ড বাজবে (বাকিগুলোতে ডিফল্ট সাউন্ড) */
    static void soundSettings(Activity a, Post p) {
        final boolean[] on = {p.soundHaha, p.soundCheck, p.soundWow};
        String[] labels = {"Haha sound", "Check sound", "Wow sound"};
        new AlertDialog.Builder(a)
                .setTitle("Reaction sounds")
                .setMultiChoiceItems(labels, on, (d, which, checked) -> on[which] = checked)
                .setPositiveButton("Save", (d, w) -> {
                    ReactionKit.saveSounds(p.id, on[0], on[1], on[2]);
                    p.soundHaha = on[0];
                    p.soundCheck = on[1];
                    p.soundWow = on[2];
                    Toast.makeText(a, "Sound settings saved", Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("Set as default", (d, w) -> {
                    ReactionKit.saveSounds(p.id, false, false, false);
                    p.soundHaha = false;
                    p.soundCheck = false;
                    p.soundWow = false;
                    Toast.makeText(a, "Default sound set for all reactions", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null).show();
    }

    /** Edit post: নতুন-পোস্ট স্ক্রিনই এডিট মোডে খোলে (ছবির গ্রিড, Add media, পেন্সিল, চিপ সব সহ) */
    static void editPost(final Activity a, final Post p) {
        if (!p.isGroupPost()) { openEditor(a, p); return; }
        // গ্রুপ পোস্ট: ১নং = সবাই নিজের পোস্ট এডিট করতে পারে; ২নং = এডিটের পর Admin approval; ৩নং = শুধু Admin/Moderator
        SupabaseRest.call("GET", "/rest/v1/groups?id=eq." + p.groupId + "&select=post_mode&limit=1",
                null, null, true, r -> {
            String mode = "auto";
            try {
                JSONArray arr = new JSONArray(r.body);
                if (r.ok() && arr.length() > 0) {
                    String m = arr.getJSONObject(0).optString("post_mode", "auto");
                    if (m != null && !m.isEmpty() && !"null".equals(m)) mode = m;
                }
            } catch (Exception ignored) { }
            String role = p.groupRole == null ? "" : p.groupRole;
            boolean manager = "admin".equals(role) || "moderator".equals(role);
            if ("admins_only".equals(mode) && !manager) {
                Toast.makeText(a, "Only Admins and Moderators can edit posts in this group", Toast.LENGTH_LONG).show();
                return;
            }
            if ("admin_approval".equals(mode) && !manager) {
                Toast.makeText(a, "After editing, your post will appear again once an Admin approves it", Toast.LENGTH_LONG).show();
            }
            openEditor(a, p);
        });
    }

    static void openEditor(final Activity a, final Post p) {
        editHost = new java.lang.ref.WeakReference<>(a);
        String sk = PostImage.get(p.id);
        if (sk != null && PostExtras.isShort(p.id)) {   // Short: ফটো/টেক্সট এডিটর নয় — আপলোডের Short Settings পেজই এডিট মোডে
            a.startActivity(new Intent(a, ShortSettingsActivity.class).putExtra("edit_thumb_key", sk));
            return;
        }
        a.startActivity(new Intent(a, NewPostActivity.class).putExtra("edit_post_id", p.id));
    }

    /** এডিট স্ক্রিনের জন্য আনা পোস্টের সব তথ্য */
    static final class Loaded {
        PostEditExtras ex;
        final List<PostPhoto> photos = new ArrayList<>();
        int variant;
        boolean hadImages;
        String text = "", hashtags = "", privacy = "public";
    }

    interface LoadCb { void on(Loaded l, String err); }

    /** পোস্টের সারি + ট্যাগ/কোলাব + ছবির bytes আনে। কিছু ব্যর্থ হলে l = null (নইলে সেভে মুছে যেত) */
    static void loadForEdit(final Activity a, final String postId, final LoadCb cb) {
        SupabaseRest.call("GET", "/rest/v1/posts?id=eq." + postId + "&select=*", null, null, true, r -> {
            JSONObject row = null;
            try {
                JSONArray arr = new JSONArray(r.body);
                if (r.ok() && arr.length() > 0) row = arr.getJSONObject(0);
            } catch (Exception ignored) { }
            if (row == null) { cb.on(null, "পোস্ট লোড করা যায়নি, আবার চেষ্টা করুন"); return; }
            final Loaded l = new Loaded();
            l.ex = PostEditExtras.from(row);
            l.text = row.isNull("text") ? "" : row.optString("text", "");
            l.hashtags = row.isNull("hashtags") ? "" : row.optString("hashtags", "");
            String pv = row.isNull("privacy") ? "" : row.optString("privacy", "");
            l.privacy = "friends".equals(pv) ? "friends" : ("me".equals(pv) || "only_me".equals(pv)) ? "me" : "public";
            // বর্তমান ট্যাগ/কোলাব (SQL ফাংশন না থাকলে ব্যর্থ হয়; তখন Tag বদলানো বন্ধ থাকে)
            SupabaseRest.call("POST", "/rest/v1/rpc/get_post_tag_state",
                    obj("p_post_id", postId).toString(), null, true, r2 -> {
                if (r2.ok()) {
                    try { l.ex.loadTags(new JSONArray(r2.body)); } catch (Exception ignored) { }
                }
                loadPhotos(postId, l, cb);
            });
        });
    }

    private static void loadPhotos(final String postId, final Loaded l, final LoadCb cb) {
        final String val = PostImage.raw(postId);
        l.hadImages = val != null;
        l.variant = val != null && val.startsWith("#B|") ? 1 : 0;
        if (val != null) {
            final String[] keys = (l.variant == 1 ? val.substring(3) : val).split("\\|");
            final String[] caps = PostImage.captions(postId);
            for (int i = 0; i < keys.length; i++) {
                PostPhoto ph = new PostPhoto();
                ph.key = keys[i];
                ph.caption = (caps != null && i < caps.length && caps[i] != null) ? caps[i] : "";
                l.photos.add(ph);
            }
        }
        if (l.photos.isEmpty()) { cb.on(l, null); return; }
        final int[] left = {l.photos.size()};
        final boolean[] bad = {false};
        for (final PostPhoto ph : l.photos) {
            Media.loadBytes(ph.key, (bytes, err) -> {
                if (bytes == null) {
                    bad[0] = true;
                } else {
                    PhotoUploader.Compressed c = new PhotoUploader.Compressed();
                    c.bytes = bytes;
                    ph.comp = c;
                    c.preview = ph.decode(720);
                }
                if (--left[0] > 0) return;
                if (bad[0]) cb.on(null, "ছবি লোড করা যায়নি, আবার চেষ্টা করুন");
                else cb.on(l, null);
            });
        }
    }

    /** কলামের মান বদলালে (বা আগে/এখন কোনোটায় থাকলে) body-তে বসায়; ফাঁকা হলে null পাঠিয়ে মুছে দেয় */
    static void putCol(JSONObject body, String col, String now, String old) {
        boolean n = now != null && !now.trim().isEmpty();
        boolean o = old != null && !old.trim().isEmpty();
        if (!n && !o) return;
        try { body.put(col, n ? now : JSONObject.NULL); } catch (Exception ignored) { }
    }

    /** নতুন/বদলানো ছবি আপলোড (key == null গুলো) → posts আপডেট → ট্যাগ → ক্যাশ বদল। শেষে done (null = সফল) */
    static void savePostEdit(final Activity a, final Post p, final List<PostPhoto> photos,
                             final int variant, final String text,
                             final PostEditExtras orig, final PostEditExtras ex,
                             final boolean hadImages, final JSONObject more, final ErrCb done) {
        if (text.isEmpty() && photos.isEmpty()) {
            Toast.makeText(a, "লেখা বা ছবি কিছু একটা থাকতে হবে", Toast.LENGTH_SHORT).show();
            done.on("empty");
            return;
        }
        uploadPending(a, photos, 0, err -> {
            if (err != null) {
                Toast.makeText(a, err, Toast.LENGTH_LONG).show();
                done.on(err);
                return;
            }
            StringBuilder sb = new StringBuilder();
            JSONArray caps = new JSONArray();
            boolean anyCap = false;
            final String[] capArr = new String[photos.size()];
            for (int i = 0; i < photos.size(); i++) {
                PostPhoto ph = photos.get(i);
                if (i > 0) sb.append('|');
                sb.append(ph.key);
                String c = ph.caption == null ? "" : ph.caption.trim();
                if (!c.isEmpty()) anyCap = true;
                caps.put(c);
                capArr[i] = c;
            }
            final String img = photos.isEmpty() ? null : (variant == 1 ? "#B|" : "") + sb;
            final boolean hasCaps = anyCap;
            JSONObject body = obj("text", text, "edited", true,
                    "created_at", iso(System.currentTimeMillis()));
            try {
                if (hadImages || img != null) {
                    body.put("image_key", img == null ? JSONObject.NULL : img);
                    body.put("image_captions", hasCaps ? caps.toString() : JSONObject.NULL);
                }
                if (more != null) {
                    java.util.Iterator<String> it = more.keys();
                    while (it.hasNext()) { String k = it.next(); body.put(k, more.get(k)); }
                }
            } catch (Exception ignored) { }
            putCol(body, "feeling", ex.feeling, orig.feeling);
            putCol(body, "activity", ex.activity, orig.activity);
            putCol(body, "location", ex.location, orig.location);
            final boolean hasMusic = !empty(ex.musicUrl);
            if (hasMusic || !empty(orig.musicUrl)) {
                try {
                    body.put("music_title", hasMusic ? ex.musicTitle : JSONObject.NULL);
                    body.put("music_artist", hasMusic ? ex.musicArtist : JSONObject.NULL);
                    body.put("music_cover", hasMusic ? ex.musicCover : JSONObject.NULL);
                    body.put("music_url", hasMusic ? ex.musicUrl : JSONObject.NULL);
                    body.put("music_start", hasMusic ? ex.musicStart : 0);
                    body.put("music_len", hasMusic ? MusicTrimActivity.CLIP_MS : 0);
                } catch (Exception ignored) { }
            }
            SupabaseRest.call("PATCH", "/rest/v1/posts?id=eq." + p.id, body.toString(),
                    "return=minimal", true, r -> {
                if (!r.ok()) { toast(a, r); done.on(SupabaseRest.errorText(r)); return; }
                p.text = text;
                if (hadImages || img != null) PostImage.set(p.id, img, hasCaps ? capArr : null);
                if (hasMusic) {
                    PostMusic.Info mi = new PostMusic.Info();
                    mi.title = ex.musicTitle;
                    mi.artist = ex.musicArtist;
                    mi.cover = ex.musicCover;
                    mi.url = ex.musicUrl;
                    mi.start = ex.musicStart;
                    mi.len = MusicTrimActivity.CLIP_MS;
                    PostMusic.put(p.id, mi);
                    if (!ex.musicUrl.equals(orig.musicUrl)) MusicPlayer.stopPost(p.id);
                } else if (!empty(orig.musicUrl)) {
                    MusicPlayer.stopPost(p.id);
                    PostMusic.remove(p.id);
                }
                final Runnable reload = () -> PostExtras.load(() -> changed(a));
                if (!ex.tagsChanged(orig)) { reload.run(); done.on(null); return; }
                JSONArray tg = new JSONArray();
                for (String t : ex.tagIds) tg.put(t);
                JSONObject tb = new JSONObject();
                try {
                    tb.put("p_post_id", p.id);
                    tb.put("p_tags", tg);
                    tb.put("p_collab", ex.collabId == null || ex.collabId.isEmpty()
                            ? JSONObject.NULL : ex.collabId);
                } catch (Exception ignored) { }
                SupabaseRest.call("POST", "/rest/v1/rpc/update_post_extras", tb.toString(), null, true, r3 -> {
                    if (!r3.ok()) {
                        Toast.makeText(a, "পোস্ট সেভ হয়েছে, কিন্তু ট্যাগ বদলায়নি: "
                                + SupabaseRest.errorText(r3), Toast.LENGTH_LONG).show();
                    }
                    reload.run();
                    done.on(null);
                });
            });
        });
    }

    static boolean empty(String s) { return s == null || s.trim().isEmpty(); }

    interface ErrCb { void on(String err); }

    private static void uploadPending(final Activity a, final List<PostPhoto> photos, final int from,
                                      final ErrCb cb) {
        for (int i = from; i < photos.size(); i++) {
            final PostPhoto ph = photos.get(i);
            if (ph.key != null) continue;
            final int next = i + 1;
            PhotoUploader.upload(a, ph.comp, (key, err) -> {
                if (key == null) { cb.on(err == null ? "ছবি আপলোড ব্যর্থ" : err); return; }
                ph.key = key;
                uploadPending(a, photos, next, cb);
            });
            return;
        }
        cb.on(null);
    }

    private static void editText(Activity a, Post p) {
        EditText et = new EditText(a);
        et.setText(p.text == null ? "" : p.text);
        et.setInputType(147457);
        et.setMinLines(3);
        et.setPadding(48, 32, 48, 32);
        new AlertDialog.Builder(a).setTitle("Edit post").setView(et)
                .setPositiveButton("Save", (d, w) -> {
                    String t = et.getText().toString().trim();
                    if (t.isEmpty()) return;
                    // সময় বদলে যাবে (created_at = এখন) ও "Edited" দেখাবে
                    patchPost(a, p.id, obj("text", t, "edited", true,
                            "created_at", iso(System.currentTimeMillis())));
                })
                .setNegativeButton("Cancel", null).show();
    }

    /** Permanent Delete: Delete চাপা মাত্র গান বন্ধ হবে, তারপর সার্ভার থেকে মুছবে */
    static void confirmDelete(Activity a, Post p) {
        new AlertDialog.Builder(a).setTitle("Delete post?")
                .setMessage("This cannot be undone.")
                .setPositiveButton("Delete", (d, w) -> {
                    MusicPlayer.stopPost(p.id);   // সার্ভারের উত্তরের আগেই গান থামে
                    SupabaseRest.call("DELETE", "/rest/v1/posts?id=eq." + p.id, null,
                            "return=minimal", true, r -> {
                        if (r.ok()) changed(a); else toast(a, r);
                    });
                    // শেয়ার করা পোস্ট মুছলে আসল পোস্টের শেয়ার সংখ্যা কমবে
                    String me = SupabaseRest.uid();
                    if (p.sharedPostId != null && me != null) {
                        SupabaseRest.call("DELETE", "/rest/v1/post_shares?post_id=eq." + p.sharedPostId
                                + "&user_id=eq." + me, null, "return=minimal", true, r -> { });
                    }
                })
                .setNegativeButton("Cancel", null).show();
    }
}
