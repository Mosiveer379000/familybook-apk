package com.familybook.app;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.CountDownTimer;
import android.text.InputFilter;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * ইমেইলে আসা ৮-ডিজিট কোড বসানোর ফুল-স্ক্রিন পেজ। রেজিস্ট্রেশনের পরে (এবং ভেরিফাই না হওয়া অ্যাকাউন্টে লগইনে) আসে।
 * কোড ঠিক হলে session সেভ হয় ও onVerified চলে (তখন ফিডে যাওয়া হয়)।
 * নতুন কোড: ৩০ সেকেন্ড কাউন্টডাউন শেষ হলে "NewCode" (নীল) ট্যাপ করে নতুন কোড নেওয়া যায়।
 * দরকার: Supabase → Authentication → Email Templates → "Confirm signup"-এ {{ .Token }} থাকতে হবে।
 */
final class EmailCodeDialog {
    private EmailCodeDialog() { }

    private static final int BLUE = 0xFF0A5CFF;
    private static final int BLUE_DIM = 0xFF9DBBFF;
    private static final int GREY = 0xFF7A7A7A;
    private static final int RESEND_SECONDS = 30;

    interface Listener { void onVerified(); void onCancel(); }

    static void show(final Activity a, final String email, final Listener l) {
        show(a, email, false, l);
    }

    /** loginMode = true: কোডটি sendLoginCode()-এর (অ্যাকাউন্ট আগে থেকে চালু থাকলে); নইলে signup কোড */
    static void show(final Activity a, final String email, final boolean loginMode, final Listener l) {
        final float d = a.getResources().getDisplayMetrics().density;

        final Dialog dlg = new Dialog(a, android.R.style.Theme_DeviceDefault_Light_NoActionBar);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dlg.setCancelable(false);

        // ---------- রুট ----------
        FrameLayout root = new FrameLayout(a);
        root.setBackgroundColor(Color.WHITE);

        ScrollView sv = new ScrollView(a);
        sv.setFillViewport(true);
        root.addView(sv, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Math.round(14 * d), Math.round(14 * d), Math.round(14 * d), Math.round(24 * d));
        sv.addView(col, new ViewGroup.LayoutParams(-1, -2));

        // ---------- লোগো (লগইন/সুইচ অ্যাকাউন্টের লোগোই) ----------
        ImageView logo = new ImageView(a);
        logo.setImageResource(R.drawable.ic_family_header);
        logo.setAdjustViewBounds(true);
        logo.setScaleType(ImageView.ScaleType.FIT_START);
        col.addView(logo, new LinearLayout.LayoutParams(-2, Math.round(52 * d)));

        // ---------- ডান পাশের উপরের ">" ----------
        TextView close = new TextView(a);
        close.setText("\u203A");
        close.setTextColor(Color.BLACK);
        close.setTextSize(32);
        close.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(Math.round(40 * d), Math.round(40 * d),
                Gravity.TOP | Gravity.END);
        clp.topMargin = Math.round(10 * d);
        clp.rightMargin = Math.round(8 * d);
        root.addView(close, clp);

        // ---------- শিরোনাম ----------
        TextView title = new TextView(a);
        String t1 = "Enter ";
        String t2 = "8-digit verification";
        String t3 = " code sent to your email : " + maskEmail(email) + ".";
        SpannableStringBuilder sb = new SpannableStringBuilder(t1 + t2 + t3);
        sb.setSpan(new ForegroundColorSpan(BLUE), t1.length(), t1.length() + t2.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        title.setText(sb);
        title.setTextColor(GREY);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2);
        tp.topMargin = Math.round(28 * d);
        tp.leftMargin = Math.round(4 * d);
        col.addView(title, tp);

        // ---------- কোডের ঘর (নীল বর্ডার, গোল কোণা) ----------
        final EditText code = new EditText(a);
        GradientDrawable box = new GradientDrawable();
        box.setColor(Color.WHITE);
        box.setCornerRadius(24 * d);
        box.setStroke(Math.round(Math.max(1, 1.5f * d)), BLUE);
        code.setBackground(box);
        code.setInputType(InputType.TYPE_CLASS_NUMBER);
        code.setFilters(new InputFilter[]{new InputFilter.LengthFilter(8)});
        code.setGravity(Gravity.CENTER);
        code.setTextSize(20);
        code.setLetterSpacing(0.25f);
        code.setTextColor(Color.BLACK);
        code.setSingleLine(true);
        code.setPadding(Math.round(16 * d), 0, Math.round(16 * d), 0);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, Math.round(46 * d));
        cp.topMargin = Math.round(12 * d);
        col.addView(code, cp);

        final TextView err = new TextView(a);
        err.setTextColor(Color.parseColor("#D32F2F"));
        err.setTextSize(12);
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(-1, -2);
        ep.topMargin = Math.round(6 * d);
        ep.leftMargin = Math.round(8 * d);
        col.addView(err, ep);

        // ---------- নিচের সারি: "Did not received code? Sent a NewCode (30second)" + Verify ----------
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.topMargin = Math.round(8 * d);
        col.addView(row, rp);

        final TextView resendTv = new TextView(a);
        resendTv.setTextColor(Color.BLACK);
        resendTv.setTextSize(13);
        resendTv.setTypeface(Typeface.DEFAULT_BOLD);
        resendTv.setMovementMethod(LinkMovementMethod.getInstance());
        resendTv.setHighlightColor(Color.TRANSPARENT);
        LinearLayout.LayoutParams rtp = new LinearLayout.LayoutParams(0, -2, 1f);
        rtp.leftMargin = Math.round(6 * d);
        rtp.rightMargin = Math.round(10 * d);
        row.addView(resendTv, rtp);

        final TextView verify = new TextView(a);
        GradientDrawable vb = new GradientDrawable();
        vb.setColor(BLUE);
        vb.setCornerRadius(12 * d);
        verify.setBackground(vb);
        verify.setText("Verify");
        verify.setTextColor(Color.WHITE);
        verify.setTextSize(16);
        verify.setTypeface(Typeface.DEFAULT_BOLD);
        verify.setGravity(Gravity.CENTER);
        verify.setIncludeFontPadding(false);
        verify.setPadding(Math.round(12 * d), Math.round(8 * d), Math.round(12 * d), Math.round(8 * d));
        row.addView(verify, new LinearLayout.LayoutParams(Math.round(92 * d), -2));

        dlg.setContentView(root);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setLayout(-1, -1);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }

        // ---------- ৩০ সেকেন্ড কাউন্টডাউন ----------
        final CountDownTimer[] timer = new CountDownTimer[1];
        final boolean[] sending = {false};

        final Runnable[] startCountdown = new Runnable[1];
        startCountdown[0] = () -> {
            if (timer[0] != null) timer[0].cancel();
            renderResend(resendTv, RESEND_SECONDS, null);
            timer[0] = new CountDownTimer(RESEND_SECONDS * 1000L, 1000) {
                @Override public void onTick(long ms) {
                    renderResend(resendTv, (int) Math.ceil(ms / 1000.0), null);
                }
                @Override public void onFinish() {
                    renderResend(resendTv, 0, () -> {
                        if (sending[0]) return;
                        sending[0] = true;
                        AuthRepo.Callback sent = error -> {
                            sending[0] = false;
                            if (a.isFinishing()) return;
                            Toast.makeText(a, error == null ? "New code sent"
                                    : "Could not send: " + error, Toast.LENGTH_SHORT).show();
                            err.setText("");
                            if (error == null) startCountdown[0].run();
                            else renderResend(resendTv, 0, null); // আবার ট্যাপ করা যাবে
                        };
                        if (loginMode) AuthRepo.sendLoginCode(email, sent);
                        else AuthRepo.resendVerification(email, sent);
                    });
                }
            }.start();
        };
        startCountdown[0].run();

        // ---------- Verify ----------
        verify.setOnClickListener(v -> {
            final String c = code.getText().toString().trim();
            if (c.length() < 6) { err.setText("Enter the code from your email"); return; }
            err.setText("");
            v.setEnabled(false);
            v.setAlpha(0.6f);
            AuthRepo.Callback done = error -> {
                v.setEnabled(true);
                v.setAlpha(1f);
                if (a.isFinishing()) return;
                if (error == null) {
                    if (timer[0] != null) timer[0].cancel();
                    dlg.dismiss();
                    if (l != null) l.onVerified();
                } else {
                    err.setText("Wrong or expired code. Try again or request a new code.");
                }
            };
            if (loginMode) AuthRepo.verifyLoginCode(email, c, done);
            else AuthRepo.verifyEmailCode(email, c, done);
        });

        // ---------- বন্ধ / ব্যাক ----------
        final Runnable cancel = () -> {
            if (timer[0] != null) timer[0].cancel();
            dlg.dismiss();
            if (l != null) l.onCancel();
        };
        close.setOnClickListener(v -> cancel.run());
        dlg.setOnKeyListener((di, keyCode, ev) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK && ev.getAction() == KeyEvent.ACTION_UP) {
                cancel.run();
                return true;
            }
            return false;
        });
        dlg.setOnDismissListener(di -> { if (timer[0] != null) timer[0].cancel(); });

        dlg.show();
        code.requestFocus();
    }

    /** secs > 0: "NewCode" ধূসর-নীল (বন্ধ) + (Ns); secs == 0: "NewCode" গাঢ় নীল, ট্যাপ করা যায় */
    private static void renderResend(TextView tv, int secs, final Runnable onTap) {
        String p1 = "Did not received code? Sent a ";
        String p2 = "NewCode";
        String p3 = secs > 0 ? "\n(" + secs + "second)" : "";
        SpannableStringBuilder sb = new SpannableStringBuilder(p1 + p2 + p3);
        int s = p1.length(), e = s + p2.length();
        if (secs > 0) {
            sb.setSpan(new ForegroundColorSpan(BLUE_DIM), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else {
            sb.setSpan(new ClickableSpan() {
                @Override public void onClick(View widget) { if (onTap != null) onTap.run(); }
                @Override public void updateDrawState(TextPaint ds) {
                    ds.setColor(BLUE);
                    ds.setUnderlineText(false);
                }
            }, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        tv.setText(sb);
    }

    /** ma***16@gmail.com — প্রথম ২ ও শেষ ২ অক্ষর দেখায়, মাঝে *** */
    private static String maskEmail(String mail) {
        int at = mail.indexOf('@');
        if (at <= 0) return mail;
        String local = mail.substring(0, at);
        String domain = mail.substring(at);
        if (local.length() <= 4) return local.substring(0, 1) + "***" + domain;
        return local.substring(0, 2) + "***" + local.substring(local.length() - 2) + domain;
    }
}
