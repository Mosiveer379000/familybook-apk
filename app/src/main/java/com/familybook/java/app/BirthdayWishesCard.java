package com.familybook.app;

import android.app.Activity;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import java.util.List;

/**
 * প্রোফাইলে জন্মদিনের শুভেচ্ছার স্থির কার্ড (পোস্টের তালিকার ঠিক ওপরে)।
 *  - সর্বশেষ PREVIEW টা শুভেচ্ছা দেখায়; বেশি থাকলে "See more" চাপলে সবগুলো একসাথে (ডায়ালগে)।
 *  - শুভেচ্ছা না থাকলে কার্ডই দেখা যায় না।
 *  - মালিক নিজে দেখলে প্রাইভেট মেসেজও (Message from Followers) আসে, "Private" লেবেল সহ।
 * ProfileActivity.onResume-এ refresh() ডাকা হয়।
 */
final class BirthdayWishesCard {

    private static final int PREVIEW = 3;

    private final Activity act;
    private final ViewGroup parent;
    private final View anchor;       // এই ভিউর ঠিক আগে কার্ড বসে (tvNoPosts)
    private final String ownerUid;
    private final boolean isMe;
    private View card;
    private int gen = 0;

    BirthdayWishesCard(Activity act, ViewGroup parent, View anchor, String ownerUid, boolean isMe) {
        this.act = act;
        this.parent = parent;
        this.anchor = anchor;
        this.ownerUid = ownerUid;
        this.isMe = isMe;
    }

    void refresh() {
        final int g = ++gen;
        BirthdayWishes.forProfile(ownerUid, isMe, list -> {
            if (g != gen || act.isFinishing() || act.isDestroyed()) return;
            show(list);
        });
    }

    private void show(final List<BirthdayWishes.Wish> list) {
        if (card != null) { parent.removeView(card); card = null; }
        if (list == null || list.isEmpty()) return;

        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(12), dp(16), dp(8));
        box.setBackgroundColor(ContextCompat.getColor(act, R.color.p_bg));

        LinearLayout head = new LinearLayout(act);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(act.getString(R.string.bday_wishes_title), 18, R.color.p_text, true);
        TextView count = text("  (" + list.size() + ")", 15, R.color.p_sub, false);
        head.addView(title);
        head.addView(count);
        box.addView(head);

        int shown = Math.min(PREVIEW, list.size());
        for (int i = 0; i < shown; i++) box.addView(row(list.get(i)));

        if (list.size() > PREVIEW) {
            TextView more = text(act.getString(R.string.bday_see_more), 15, R.color.p_text, true);
            more.setPadding(0, dp(10), 0, dp(6));
            more.setOnClickListener(v -> showAll(list));
            box.addView(more);
        }

        View line = new View(act);
        line.setBackgroundColor(ContextCompat.getColor(act, R.color.p_divider));
        box.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));

        card = box;
        parent.addView(card, Math.max(0, parent.indexOfChild(anchor)));
    }

    /** "See more": সব শুভেচ্ছা একসাথে */
    private void showAll(List<BirthdayWishes.Wish> list) {
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(8), dp(16), dp(8));
        for (BirthdayWishes.Wish w : list) col.addView(row(w));
        ScrollView sv = new ScrollView(act);
        sv.addView(col);
        new AlertDialog.Builder(act, R.style.FbAlertDialog)
                .setTitle(act.getString(R.string.bday_wishes_title) + " (" + list.size() + ")")
                .setView(sv)
                .setPositiveButton(R.string.bday_close, null)
                .show();
    }

    private View row(final BirthdayWishes.Wish w) {
        LinearLayout r = new LinearLayout(act);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, dp(8), 0, dp(8));

        ImageView av = new ImageView(act);
        av.setScaleType(ImageView.ScaleType.CENTER_CROP);
        r.addView(av, new LinearLayout.LayoutParams(dp(40), dp(40)));
        AvatarLoader.load(av, w.senderUid);
        av.setOnClickListener(v -> ProfileActivity.open(act, w.senderUid, w.senderName));

        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.setMarginStart(dp(10));
        r.addView(col, lp);

        String name = w.senderName.isEmpty() ? act.getString(R.string.notif_someone) : w.senderName;
        col.addView(text(name, 15, R.color.p_text, true));
        col.addView(text(w.message, 15, R.color.p_text, false));
        String meta = NotificationAdapter.relTime(act, w.at);
        if ("message".equals(w.kind)) meta = act.getString(R.string.bday_private) + " · " + meta;
        col.addView(text(meta, 12, R.color.p_sub, false));
        return r;
    }

    private TextView text(String s, int sp, int colorRes, boolean bold) {
        TextView t = new TextView(act);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(ContextCompat.getColor(act, colorRes));
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private int dp(int v) { return (int) (v * act.getResources().getDisplayMetrics().density + 0.5f); }
}
