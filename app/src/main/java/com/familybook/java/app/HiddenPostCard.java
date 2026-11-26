package com.familybook.app;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Post Hide করার পর পোস্টের জায়গায় যে "Post Hidden" ফিডব্যাক কার্ড দেখায়
 * (Post_Hidden_Feedback_UI.html-এর নেটিভ রূপ):
 *   [ছবি] {Name}'s Post Hidden
 *   Why aren't you interested?  → ৬টি চিপ
 *   Other steps you can take    → Report post / Unhide post / Block him/her
 */
final class HiddenPostCard {
    interface Host {
        void onReason(String reasonOrNull);   // null = নির্বাচন বাতিল
        void onReport();
        void onUnhide();
        void onBlock();
    }

    private static final String[] REASONS = {
            "Doesn't match my interest...", "Scam", "Sexual", "Disturbing",
            "I don't like the creator", "Other"
    };

    final LinearLayout root;
    private final ImageView avatar;
    private final TextView title;
    private final TextView question, other;
    private final LinearLayout chipBox;
    private final TextView[] chips = new TextView[REASONS.length];
    private final TextView[] actions = new TextView[3];
    private final float d;

    HiddenPostCard(Context c) {
        d = c.getResources().getDisplayMetrics().density;
        root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(12), dp(14), dp(12));
        root.setVisibility(View.GONE);

        // ---- হেডার ----
        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        avatar = new ImageView(c);
        avatar.setBackgroundResource(R.drawable.bg_h_ring);
        avatar.setPadding(dp(3), dp(3), dp(3), dp(3));
        avatar.setImageResource(R.drawable.ic_profile);
        head.addView(avatar, new LinearLayout.LayoutParams(dp(44), dp(44)));
        title = new TextView(c);
        title.setTextSize(2, 17f);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1f);
        tlp.setMarginStart(dp(10));
        head.addView(title, tlp);
        root.addView(head, new LinearLayout.LayoutParams(-1, -2));

        // ---- প্রশ্ন + চিপ ----
        question = heading(c, "Why aren't you interested?");
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(-1, -2);
        qlp.topMargin = dp(14);
        root.addView(question, qlp);

        chipBox = new LinearLayout(c);   // FlowLayout-এ ভরা হয়
        FlowLayout flow = new FlowLayout(c);
        for (int i = 0; i < REASONS.length; i++) {
            TextView ch = new TextView(c);
            ch.setText(REASONS[i]);
            ch.setTextSize(2, 14f);
            ch.setSingleLine(true);
            ch.setPadding(dp(14), dp(8), dp(14), dp(8));
            chips[i] = ch;
            flow.addView(ch);
        }
        chipBox.setPadding(dp(8), 0, 0, 0);
        chipBox.addView(flow, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.topMargin = dp(8);
        root.addView(chipBox, clp);

        // ---- Other steps you can take ----
        other = heading(c, "Other steps you can take");
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(-1, -2);
        olp.topMargin = dp(14);
        root.addView(other, olp);

        int[] icons = {R.drawable.ic_menu_report, R.drawable.ic_menu_unhide, R.drawable.ic_blocked_user};
        for (int i = 0; i < 3; i++) {
            TextView row = new TextView(c);
            row.setTextSize(2, 15.5f);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setCompoundDrawablePadding(dp(12));
            row.setPadding(dp(2), dp(9), dp(2), dp(9));
            row.setCompoundDrawablesRelative(sized(c, icons[i]), null, null, null);
            row.setClickable(true);
            actions[i] = row;
            root.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    /** কার্ডে পোস্টের তথ্য বসায়। selected = আগে বাছা কারণ (বা null) */
    void bind(Post p, String selected, int textColor, int subColor, int bgColor, final Host host) {
        root.setBackgroundColor(bgColor);
        String name = (p.name == null || p.name.trim().isEmpty() || "null".equals(p.name)) ? "Family Member" : p.name;
        SpannableStringBuilder sb = new SpannableStringBuilder(name + "'s");
        sb.setSpan(new StyleSpan(Typeface.BOLD), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new ForegroundColorSpan(textColor), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        int st = sb.length();
        sb.append(" Post Hidden");
        sb.setSpan(new ForegroundColorSpan(subColor), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        title.setText(sb);
        AvatarLoader.load(avatar, p.uid);

        question.setTextColor(textColor);
        other.setTextColor(textColor);

        for (int i = 0; i < chips.length; i++) {
            final String reason = REASONS[i];
            boolean on = reason.equals(selected);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(40));
            if (on) {
                g.setColor(0x261877F2);
                g.setStroke(Math.max(1, dp(1)), 0x661877F2);
                chips[i].setTextColor(0xFF1877F2);
                chips[i].setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            } else {
                g.setColor(0x26808080);
                chips[i].setTextColor(textColor);
                chips[i].setTypeface(Typeface.DEFAULT);
            }
            chips[i].setBackground(g);
            chips[i].setOnClickListener(v -> host.onReason(on ? null : reason));
        }

        String[] labels = {"Report post", "Unhide post", "Block him/her"};
        for (int i = 0; i < 3; i++) {
            actions[i].setTextColor(textColor);
            actions[i].setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            if (i == 2) {   // "Block" বোল্ড, "him/her" হালকা
                SpannableStringBuilder b = new SpannableStringBuilder("Block him/her");
                b.setSpan(new StyleSpan(Typeface.BOLD), 0, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                b.setSpan(new ForegroundColorSpan(subColor), 5, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                actions[i].setText(b);
            } else {
                actions[i].setText(labels[i]);
            }
            for (android.graphics.drawable.Drawable dr : actions[i].getCompoundDrawablesRelative()) {
                if (dr != null) dr.setTint(textColor);
            }
        }
        actions[0].setOnClickListener(v -> host.onReport());
        actions[1].setOnClickListener(v -> host.onUnhide());
        actions[2].setOnClickListener(v -> host.onBlock());
    }

    private TextView heading(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(2, 17f);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        return t;
    }

    private android.graphics.drawable.Drawable sized(Context c, int res) {
        android.graphics.drawable.Drawable dr = androidx.core.content.ContextCompat.getDrawable(c, res);
        if (dr == null) return null;
        dr = dr.mutate();
        dr.setBounds(0, 0, dp(22), dp(22));
        return dr;
    }

    private int dp(float v) { return Math.round(v * d); }
}
