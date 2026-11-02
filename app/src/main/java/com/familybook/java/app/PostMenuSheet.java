package com.familybook.app;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
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

/**
 * Text / Photo / GIF পোস্টের ৩-ডট মেনু — নিচ থেকে ওপরে ওঠা সাদা শিট (সাদা-কালো আইকন ও লেখা, উপর থেকে নিচে)।
 *  - নিজের পোস্ট: প্রায় পুরো স্ক্রিন জুড়ে খোলে
 *  - অন্যের পোস্ট: Turn off notifications / Copy link / Hide / Share — একই ডিজাইনে, কনটেন্ট-মাপের শিট
 * শুধু UI বদলেছে; প্রতিটি অপশনের কাজ আগের মতোই PostOps-এর ফাংশনে যায়।
 * (ভিডিও/Short-এর মেনু আলাদা: ShortMenuSheet)
 */
final class PostMenuSheet {

    private static final int SHEET = 0xFFFFFFFF;
    private static final int CARD = 0xFFF2F3F7;
    private static final int INK = 0xFF000000;
    private static final int SUB = 0xFF65676B;

    private final Activity a;
    private final String uid;
    private final Post p;
    private final boolean feed;
    private final Dialog dlg;
    private final LinearLayout sheet;
    private LinearLayout card;

    static void show(Activity a, String uid, Post p, boolean feed) {
        if (a == null || a.isFinishing() || p == null) return;
        new PostMenuSheet(a, uid, p, feed).open();
    }

    private PostMenuSheet(Activity a, String uid, Post p, boolean feed) {
        this.a = a;
        this.uid = uid;
        this.p = p;
        this.feed = feed;
        dlg = new Dialog(a);
        sheet = new LinearLayout(a);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(14), dp(8), dp(14), dp(18));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(SHEET);
        bg.setCornerRadii(new float[]{dp(28), dp(28), dp(28), dp(28), 0, 0, 0, 0});
        sheet.setBackground(bg);

        View handle = new View(a);
        GradientDrawable hg = new GradientDrawable();
        hg.setColor(0xFF8A8D91);
        hg.setCornerRadius(dp(3));
        handle.setBackground(hg);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(dp(44), dp(5));
        hl.gravity = Gravity.CENTER_HORIZONTAL;
        hl.topMargin = dp(4);
        hl.bottomMargin = dp(12);
        sheet.addView(handle, hl);

        if (p.isMine(uid)) buildMine(); else buildOthers();
    }

    // ------------------------------------------------------------ নিজের পোস্ট

    private void buildMine() {
        final boolean active = "active".equals(p.status);
        newCard();
        row(R.drawable.ic_menu_edit, "Edit post",
                "After Edit Posting time will be changed & shows Edited", true,
                () -> PostOps.editPost(a, p));
        row(R.drawable.ic_menu_comment, "Who can comment on your post?",
                "Now: " + PostOps.labelOf(p.commentPerm), true,
                () -> PostOps.choose(a, p, "comment_perm", "Who can comment",
                        new String[]{"Public", "Followers", "Only me"},
                        new String[]{"public", "followers", "me"}));
        row(R.drawable.ic_menu_pivracy, "Edit Privacy",
                "Public - Friends - Only me   (now: " + PostOps.labelOf(p.privacy) + ")", true,
                () -> PostOps.choose(a, p, "privacy", "Who can see this post",
                        new String[]{"Public", "Friends", "Only me"},
                        new String[]{"public", "friends", "me"}));
        row(R.drawable.ic_menu_sound, "Reaction sounds", soundNow(), true,
                () -> PostOps.soundSettings(a, p));
        if (active) {
            final boolean pinnedNow = p.id != null && p.id.equals(PostOps.myPinnedId);
            row(R.drawable.ic_pin_black, pinnedNow ? "Unpin from profile" : "Pin post",
                    pinnedNow ? "Remove from the top of your profile" : "Show this post first on your profile", true,
                    () -> PostOps.togglePin(a, p));
        }
        row(R.drawable.ic_short_bookmark, "Save post", "Add this to your saved items.", false,
                () -> toast("Save is coming soon"));
        if (active) {
            row(R.drawable.ic_menu_archive, "Move to archive", "Archive will auto deleted after 30 days", true,
                    () -> PostOps.setStatus(a, p, "archived"));
            row(R.drawable.ic_menu_trash, "Move to trash", "Trash will be Deleted After 7 Days", true,
                    () -> PostOps.setStatus(a, p, "trashed"));
        } else {
            row(R.drawable.ic_menu_restore, "Restore", "Move this post back to your timeline", true,
                    () -> PostOps.setStatus(a, p, "active"));
        }
        notifyRow();
        row(R.drawable.ic_link_item, "Copy link", null, false, () -> PostOps.copyLink(a, p));
        hideRow();
        row(R.drawable.ic_menu_delete, "Permanent Delete", null, true, () -> PostOps.confirmDelete(a, p));

        newCard();
        row(R.drawable.ic_menu_share, "Share", null, false, () -> PostOps.share(a, p));
    }

    // ------------------------------------------------------------ অন্যের পোস্ট

    private void buildOthers() {
        newCard();
        notifyRow();
        row(R.drawable.ic_link_item, "Copy link", "Copy post link to share on timeline", false,
                () -> PostOps.copyLink(a, p));
        hideRow();
        row(R.drawable.ic_menu_share, "Share", null, false, () -> PostOps.share(a, p));
    }

    private void notifyRow() {
        row(p.mutedByMe ? R.drawable.ic_menu_bell_on : R.drawable.ic_menu_bell_off,
                p.mutedByMe ? "Turn on" : "Turn off", "Notifications for this post", true,
                () -> PostOps.toggleMute(a, uid, p));
    }

    private void hideRow() {
        if (!feed) return;
        if (p.hiddenByMe) {
            row(R.drawable.ic_menu_unhide, "Unhide", "Show this post in your news feed again", true,
                    () -> PostOps.unhide(a, uid, p.id));
        } else {
            row(R.drawable.ic_menu_hide, "Hide", "Hide from your news feed only (not from profile)", true,
                    () -> PostOps.hide(a, uid, p.id));
        }
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
        card.setPadding(dp(6), dp(6), dp(6), dp(6));
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadius(dp(22));
        card.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(10);
        sheet.addView(card, lp);
    }

    /** সব সারিতে আইকনের কলাম একই চওড়া (৪০dp) ও মাঝে বসানো — তাই লেখা সবসময় এক সোজা লাইনে থাকে */
    private void row(int icon, String title, String sub, final boolean net, final Runnable action) {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(52));
        r.setPadding(dp(10), dp(8), dp(12), dp(8));
        r.setClickable(true);

        ImageView iv = new ImageView(a);
        iv.setImageResource(icon);
        iv.setColorFilter(INK);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setPadding(dp(8), dp(8), dp(8), dp(8));
        r.addView(iv, new LinearLayout.LayoutParams(dp(40), dp(40)));

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(10), 0, 0, 0);
        TextView t = new TextView(a);
        t.setText(title);
        t.setTextColor(INK);
        t.setTextSize(16.5f);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        col.addView(t);
        if (sub != null) {
            TextView s = new TextView(a);
            s.setText(sub);
            s.setTextColor(SUB);
            s.setTextSize(13.5f);
            s.setPadding(0, dp(1), 0, 0);
            col.addView(s);
        }
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));

        r.setOnClickListener(v -> {
            dlg.dismiss();
            if (net && !Net.check(a)) return;     // অফলাইনে কাজ হবে না
            action.run();
        });
        card.addView(r);
    }

    private void open() {
        final boolean full = p.isMine(uid);
        ScrollView sv = new ScrollView(a);
        sv.setVerticalScrollBarEnabled(false);
        sv.setFillViewport(true);
        sv.setBackgroundColor(Color.TRANSPARENT);
        sv.addView(sheet, new ViewGroup.LayoutParams(-1, full ? -1 : -2));

        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dlg.setContentView(sv);
        dlg.setCanceledOnTouchOutside(true);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setDimAmount(0.45f);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setWindowAnimations(R.style.ShortMenuAnim);          // নিচ থেকে ওপরে ওঠা/নামা
            DisplayMetrics dm = a.getResources().getDisplayMetrics();
            int maxH = (int) (dm.heightPixels * 0.92f);            // নিজের পোস্ট: প্রায় পুরো স্ক্রিন
            int h = full ? maxH : Math.min(measuredHeight(dm.widthPixels), maxH);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.gravity = Gravity.BOTTOM;
            w.setAttributes(lp);
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, h);
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
