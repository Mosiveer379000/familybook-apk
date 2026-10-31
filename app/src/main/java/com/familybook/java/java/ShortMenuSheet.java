package com.familybook.app;

import android.app.Activity;
import android.app.DownloadManager;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Environment;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

/**
 * Short (ভিডিও) এর ৩-ডট মেনু — নিচ থেকে ওপরে এনিমেশনে ওঠা শিট।
 *  - নিজের Short: ৪নং ছবির মতো পুরো মেনু
 *  - অন্যের Short (Public): ৫নং ছবির মতো মেনু
 * আইকনগুলো res/drawable থেকে। (পুরোনো ডায়ালগ মেনু PostOps.showMenu শুধু Status text / Photos-এর জন্য)
 */
final class ShortMenuSheet {

    private static final int BG = 0xFFF2F3F7;
    private static final int CARD = 0xFFFFFFFF;
    private static final int INK = 0xFF1C1E21;
    private static final int SUB = 0xFF65676B;

    private final Activity a;
    private final String uid;
    private final Post p;
    private final Dialog dlg;
    private final LinearLayout sheet;
    private LinearLayout card;

    static void show(Activity a, String uid, Post p) {
        if (a == null || a.isFinishing() || p == null) return;
        new ShortMenuSheet(a, uid, p).open();
    }

    private ShortMenuSheet(Activity a, String uid, Post p) {
        this.a = a;
        this.uid = uid;
        this.p = p;
        dlg = new Dialog(a);
        sheet = new LinearLayout(a);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(10), dp(6), dp(10), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(BG);
        bg.setCornerRadii(new float[]{dp(28), dp(28), dp(28), dp(28), 0, 0, 0, 0});
        sheet.setBackground(bg);

        // উপরের ছোট হ্যান্ডেল
        View handle = new View(a);
        GradientDrawable hg = new GradientDrawable();
        hg.setColor(0xFF3A3B3C);
        hg.setCornerRadius(dp(3));
        handle.setBackground(hg);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(dp(40), dp(4));
        hl.gravity = Gravity.CENTER_HORIZONTAL;
        hl.topMargin = dp(4);
        hl.bottomMargin = dp(8);
        sheet.addView(handle, hl);

        if (p.isMine(uid)) buildMine(); else buildPublic();
    }

    // ------------------------------------------------------------ নিজের Short (৪নং ছবি)

    private void buildMine() {
        final boolean active = "active".equals(p.status);
        newCard();
        row(R.drawable.ic_short_bookmark, "Save reel", "Add this to your saved reels.", false,
                () -> toast("Save is coming soon"));
        row(R.drawable.ic_pin_details, "Pin to your reels",
                "You can pin up to 3 reels. Pinning this reel replaces your oldest pinned reel.", true,
                () -> {
                    String k = PostImage.get(p.id);
                    ShortStore.Info si = k == null ? null : ShortStore.byThumb(k);
                    if (si == null) toast("Could not pin this reel"); else PinnedReels.toggle(a, si.id);
                });
        row(R.drawable.ic_rm_download, "Download reel", "Download reel", false, this::download);
        row(R.drawable.ic_menu_edit, "Edit reel", "You can edit the description, cover photo and more.", true,
                () -> PostOps.editPost(a, p));
        row(R.drawable.ic_menu_pivracy, "Edit privacy", null, true,
                () -> PostOps.choose(a, p, "privacy", "Who can see this reel",
                        new String[]{"Public", "Friends", "Only me"},
                        new String[]{"public", "friends", "me"}));
        row(R.drawable.ic_menu_comment, "Who can comment on your reel?", null, true,
                () -> PostOps.choose(a, p, "comment_perm", "Who can comment",
                        new String[]{"Public", "Friends", "Only me"},
                        new String[]{"public", "friends", "me"}));

        newCard();
        final boolean autoOnMine = MediaPrefs.autoNext(a);
        row(R.drawable.ic_final_next, "Scroll to next reel automatically", autoOnMine ? "On" : "Off", false, () -> {
            MediaPrefs.setAutoNext(a, !autoOnMine);
            toast(autoOnMine ? "Auto scroll off" : "Auto scroll on");
        });
        row(R.drawable.ic_menu_sound, "Reaction sounds", soundNow(), true,
                () -> PostOps.soundSettings(a, p));
        if (active) {
            row(R.drawable.ic_menu_archive, "Archive", "Archive will auto deleted after 30 days", true,
                    () -> PostOps.setStatus(a, p, "archived"));
            row(R.drawable.ic_menu_trash, "Trash", "Trash will be Deleted After 7 Days", true,
                    () -> PostOps.setStatus(a, p, "trashed"));
            final boolean pinnedNow = p.id != null && p.id.equals(PostOps.myPinnedId);
            row(R.drawable.ic_pin_black, pinnedNow ? "Unpin from profile" : "Pin to profile",
                    pinnedNow ? "Remove from the top of your profile" : "Show this post first on your profile", true,
                    () -> PostOps.togglePin(a, p));
        } else {
            row(R.drawable.ic_menu_restore, "Restore", "Move this post back to your timeline", true,
                    () -> PostOps.setStatus(a, p, "active"));
        }
        row(p.mutedByMe ? R.drawable.ic_menu_bell_on : R.drawable.ic_menu_bell_off,
                p.mutedByMe ? "Turn on" : "Turn off", "Notifications for this post", true,
                () -> PostOps.toggleMute(a, uid, p));
        row(R.drawable.ic_link_item, "Copy link", "Copy post link to share on timeline", false,
                () -> PostOps.copyLink(a, p));
        row(R.drawable.ic_menu_delete, "Permanent Delete", null, true,
                () -> PostOps.confirmDelete(a, p));
        row(R.drawable.ic_menu_share, "Share", null, false, () -> PostOps.share(a, p));
    }

    // ------------------------------------------------------------ অন্যের Short — Public (৫নং ছবি)

    private void buildPublic() {
        newCard();
        row(R.drawable.ic_rm_interested, "Interested", "More of your reels will be like this.", false,
                () -> {
                    ReelInterest.set(p.uid, 1);
                    toast("We'll show you more reels like this");
                });
        row(R.drawable.ic_rm_not_interested, "Not interested", "Less of your reels will be like this.", false,
                () -> {
                    ReelInterest.set(p.uid, -1);
                    toast("We'll show you fewer reels like this");
                    PostOps.refreshHost(a);     // ভিউয়ারে থাকলে বন্ধ হয়ে যায়
                });

        newCard();
        row(R.drawable.cam_speed, "Playback speed", null, false, this::pickSpeed);
        row(R.drawable.ic_tm_settings, "Quality settings", null, false, null);   // ডেমো: চাপলে কিছু হয় না
        row(R.drawable.ic_captions, "Captions", null, false, null);
        final boolean autoOn = MediaPrefs.autoNext(a);
        row(R.drawable.ic_final_next, "Scroll to next reel automatically", autoOn ? "On" : "Off", false, () -> {
            MediaPrefs.setAutoNext(a, !autoOn);
            toast(autoOn ? "Auto scroll off" : "Auto scroll on");
        });
        row(R.drawable.ic_languages, "Audio and language", null, false, null);
        row(R.drawable.ic_short_bookmark, "Save reel", "Add this to your saved reels.", false,
                () -> toast("Save is coming soon"));
        row(R.drawable.ic_link_item, "Copy link", null, false, () -> PostOps.copyLink(a, p));
        row(R.drawable.ic_menu_share, "Share", null, false, () -> PostOps.share(a, p));
    }

    // ------------------------------------------------------------ কাজ

    private void pickSpeed() {
        final String[] names = {"0.5x", "0.75x", "Normal", "1.25x", "1.5x", "2x"};
        final float[] vals = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f};
        int cur = 2;
        for (int i = 0; i < vals.length; i++) if (vals[i] == ShortPlayerView.speed) cur = i;
        new AlertDialog.Builder(a)
                .setTitle("Playback speed")
                .setSingleChoiceItems(names, cur, (d, which) -> {
                    ShortPlayerView.setSpeedAll(vals[which]);
                    d.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** ভিডিও ফোনে নামায় (Movies/FamilyBook) */
    private void download() {
        String thumb = PostImage.get(p.id);
        final ShortStore.Info info = thumb == null ? null : ShortStore.byThumb(thumb);
        if (info == null || info.videoKey == null) { toast("Could not download this reel"); return; }
        if (!Net.check(a)) return;
        Media.readUrl(info.videoKey, (url, err) -> {
            if (url == null) { toast("Could not download this reel"); return; }
            try {
                DownloadManager.Request rq = new DownloadManager.Request(Uri.parse(url));
                rq.setTitle("FamilyBook reel");
                rq.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                rq.setDestinationInExternalPublicDir(Environment.DIRECTORY_MOVIES, "FamilyBook/short_" + info.id + ".mp4");
                DownloadManager dm = (DownloadManager) a.getSystemService(Activity.DOWNLOAD_SERVICE);
                dm.enqueue(rq);
                toast("Downloading…");
            } catch (Exception e) {
                toast("Could not download this reel");
            }
        });
    }

    private String soundNow() {
        StringBuilder sb = new StringBuilder();
        if (p.soundHaha) sb.append("Haha");
        if (p.soundCheck) sb.append(sb.length() > 0 ? ", " : "").append("Check");
        if (p.soundWow) sb.append(sb.length() > 0 ? ", " : "").append("Wow");
        return "Default or meme sounds   (now: " + (sb.length() == 0 ? "Default" : sb.toString()) + ")";
    }

    // ------------------------------------------------------------ UI

    private void newCard() {
        card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(4), dp(4), dp(4), dp(4));
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadius(dp(16));
        card.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(8);
        sheet.addView(card, lp);
    }

    private void row(int icon, String title, String sub, final boolean net, final Runnable action) {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(10), dp(7), dp(10), dp(7));
        r.setClickable(true);

        ImageView iv = new ImageView(a);
        iv.setImageResource(icon);
        iv.setColorFilter(INK);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        r.addView(iv, new LinearLayout.LayoutParams(dp(22), dp(22)));

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(12), 0, 0, 0);
        TextView t = new TextView(a);
        t.setText(title);
        t.setTextColor(INK);
        t.setTextSize(15);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        col.addView(t);
        if (sub != null) {
            TextView s = new TextView(a);
            s.setText(sub);
            s.setTextColor(SUB);
            s.setTextSize(12);
            col.addView(s);
        }
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));

        if (action != null) {             // action == null হলে ডেমো সারি: চাপলে কিছু হয় না
            r.setOnClickListener(v -> {
                dlg.dismiss();
                if (net && !Net.check(a)) return;
                action.run();
            });
        }
        card.addView(r);
    }

    private void open() {
        ScrollView sv = new ScrollView(a);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(sheet, new ViewGroup.LayoutParams(-1, -2));

        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dlg.setContentView(sv);
        dlg.setCanceledOnTouchOutside(true);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setDimAmount(0.45f);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setWindowAnimations(R.style.ShortMenuAnim);       // নিচ থেকে ওপরে ওঠা/নামা
            DisplayMetrics dm = a.getResources().getDisplayMetrics();
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.width = WindowManager.LayoutParams.MATCH_PARENT;
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            lp.gravity = Gravity.BOTTOM;
            w.setAttributes(lp);
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, Math.min(
                    measuredHeight(dm.widthPixels), (int) (dm.heightPixels * 0.9f)));
        }
        dlg.show();
    }

    private int measuredHeight(int width) {
        sheet.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        return sheet.getMeasuredHeight();
    }

    private void toast(String m) {
        Toast.makeText(a, m, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }
}
