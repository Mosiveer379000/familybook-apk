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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

/**
 * পোস্টের ৩-ডট মেনু > Report: কারণ বাছাই করার শিট। বাছলে রিপোর্ট content_reports টেবিলে জমা হয়
 * (sql/10_report_problem.sql)। প্রতি ইউজার প্রতি পোস্টে একবারই গণনা হয়।
 */
final class ReportPostSheet {

    private static final String[] REASONS = {
            "Hate speech",
            "Harassment or bullying",
            "Spam",
            "Nudity or sexual content",
            "Violence or dangerous content",
            "False information",
            "Scam or fraud",
            "Something else"
    };

    static void show(Activity a, Post p) {
        if (a == null || a.isFinishing() || p == null || p.id == null) return;
        new ReportPostSheet(a, p).open();
    }

    private final Activity a;
    private final Post p;
    private final Dialog dlg;
    private final LinearLayout sheet;

    private ReportPostSheet(Activity a, Post p) {
        this.a = a;
        this.p = p;
        dlg = new Dialog(a);
        sheet = new LinearLayout(a);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(14), dp(8), dp(14), dp(18));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(cc(R.color.c_bg));
        bg.setCornerRadii(new float[]{dp(28), dp(28), dp(28), dp(28), 0, 0, 0, 0});
        sheet.setBackground(bg);

        View handle = new View(a);
        GradientDrawable hg = new GradientDrawable();
        hg.setColor(cc(R.color.c_handle));
        hg.setCornerRadius(dp(3));
        handle.setBackground(hg);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(dp(44), dp(5));
        hl.gravity = Gravity.CENTER_HORIZONTAL;
        hl.topMargin = dp(4);
        hl.bottomMargin = dp(12);
        sheet.addView(handle, hl);

        TextView title = new TextView(a);
        title.setText("Report");
        title.setTextColor(cc(R.color.c_ink));
        title.setTextSize(20f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(dp(10), 0, dp(10), 0);
        sheet.addView(title);

        TextView sub = new TextView(a);
        sub.setText("Why are you reporting this post?");
        sub.setTextColor(cc(R.color.c_text_sub));
        sub.setTextSize(14f);
        sub.setPadding(dp(10), dp(2), dp(10), dp(12));
        sheet.addView(sub);

        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(6), dp(6), dp(6), dp(6));
        GradientDrawable g = new GradientDrawable();
        g.setColor(cc(R.color.c_card));
        g.setCornerRadius(dp(22));
        card.setBackground(g);
        for (final String reason : REASONS) {
            TextView r = new TextView(a);
            r.setText(reason);
            r.setTextColor(cc(R.color.c_ink));
            r.setTextSize(16.5f);
            r.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setMinimumHeight(dp(52));
            r.setPadding(dp(14), dp(8), dp(12), dp(8));
            r.setClickable(true);
            r.setOnClickListener(v -> {
                dlg.dismiss();
                submit(reason);
            });
            card.addView(r);
        }
        sheet.addView(card, new LinearLayout.LayoutParams(-1, -2));
    }

    private void submit(String reason) {
        if (!Net.check(a)) return;
        String who = p.name == null || p.name.isEmpty() ? "User" : p.name;
        String uid = p.uid == null ? "" : p.uid;
        try {
            JSONObject row = new JSONObject()
                    .put("target_account", who + (uid.length() >= 8 ? " (" + uid.substring(0, 8) + ")" : ""))
                    .put("surface", "post")
                    .put("content_ref", p.id)
                    .put("reason", reason);
            if (!uid.isEmpty()) row.put("target_user_id", uid);
            SupabaseRest.call("POST", "/rest/v1/content_reports", row.toString(), "return=minimal", true, r -> {
                if (a.isFinishing()) return;
                if (r.ok()) {
                    Toast.makeText(a, "Report submitted. Thanks for helping keep Family Book safe.", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(a, SupabaseRest.errorText(r), Toast.LENGTH_LONG).show();
                }
            });
        } catch (Exception e) {
            Toast.makeText(a, "Report could not be sent", Toast.LENGTH_SHORT).show();
        }
    }

    private void open() {
        ScrollView sv = new ScrollView(a);
        sv.setVerticalScrollBarEnabled(false);
        sv.setBackgroundColor(Color.TRANSPARENT);
        sv.addView(sheet, new ViewGroup.LayoutParams(-1, -2));

        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dlg.setContentView(sv);
        dlg.setCanceledOnTouchOutside(true);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setDimAmount(0.45f);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setWindowAnimations(R.style.ShortMenuAnim);
            DisplayMetrics dm = a.getResources().getDisplayMetrics();
            sheet.measure(View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int h = Math.min(sheet.getMeasuredHeight(), (int) (dm.heightPixels * 0.92f));
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.gravity = Gravity.BOTTOM;
            w.setAttributes(lp);
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, h);
        }
        dlg.show();
    }

    private int cc(int resId) {
        return androidx.core.content.ContextCompat.getColor(a, resId);
    }

    private int dp(int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }
}
