package com.familybook.app;

import android.app.Activity;
import android.graphics.Color;
import android.os.CountDownTimer;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

/**
 * ইমেইলে আসা কোড বসানোর ঘর। রেজিস্ট্রেশনের পরে (এবং ভেরিফাই না হওয়া অ্যাকাউন্টে লগইনে) আসে।
 * কোড ঠিক হলে session সেভ হয় ও onVerified চলে (তখন ফিডে যাওয়া হয়)।
 * দরকার: Supabase → Authentication → Email Templates → "Confirm signup"-এ {{ .Token }} থাকতে হবে।
 */
final class EmailCodeDialog {
    private EmailCodeDialog() { }

    interface Listener { void onVerified(); void onCancel(); }

    static void show(final Activity a, final String email, final Listener l) {
        show(a, email, false, l);
    }

    /** loginMode = true: কোডটি sendLoginCode()-এর (অ্যাকাউন্ট আগে থেকে চালু থাকলে); নইলে signup কোড */
    static void show(final Activity a, final String email, final boolean loginMode, final Listener l) {
        float d = a.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Math.round(24 * d), Math.round(8 * d), Math.round(24 * d), 0);

        TextView msg = new TextView(a);
        msg.setText(maskEmail(email) + " ঠিকানায় একটি কোড পাঠানো হয়েছে। কোডটি নিচে লিখুন।");
        msg.setTextColor(0xFF444444);
        msg.setTextSize(14);
        box.addView(msg);

        final EditText code = new EditText(a);
        code.setHint("Code");
        code.setInputType(InputType.TYPE_CLASS_NUMBER);
        code.setFilters(new InputFilter[]{new InputFilter.LengthFilter(8)});
        code.setGravity(Gravity.CENTER);
        code.setTextSize(24);
        code.setLetterSpacing(0.3f);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.topMargin = Math.round(14 * d);
        box.addView(code, cp);

        final TextView err = new TextView(a);
        err.setTextColor(Color.parseColor("#D32F2F"));
        err.setTextSize(13);
        box.addView(err);

        final AlertDialog dlg = new AlertDialog.Builder(a)
                .setTitle("Verify your email")
                .setView(box)
                .setCancelable(false)
                .setPositiveButton("Verify", null)
                .setNeutralButton("Resend code", null)
                .setNegativeButton("Cancel", (x, w) -> { if (l != null) l.onCancel(); })
                .create();
        dlg.show();

        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            final String c = code.getText().toString().trim();
            if (c.length() < 6) { err.setText("ইমেইলের কোডটি লিখুন"); return; }
            err.setText("");
            v.setEnabled(false);
            AuthRepo.Callback done = error -> {
                v.setEnabled(true);
                if (a.isFinishing()) return;
                if (error == null) {
                    dlg.dismiss();
                    if (l != null) l.onVerified();
                } else {
                    err.setText("কোড ভুল বা মেয়াদ শেষ। আবার চেষ্টা করুন বা নতুন কোড নিন।");
                }
            };
            if (loginMode) AuthRepo.verifyLoginCode(email, c, done);
            else AuthRepo.verifyEmailCode(email, c, done);
        });

        final TextView resend = dlg.getButton(AlertDialog.BUTTON_NEUTRAL);
        resend.setOnClickListener(v -> {
            v.setEnabled(false);
            AuthRepo.Callback sent = error -> {
                Toast.makeText(a, error == null ? "নতুন কোড পাঠানো হয়েছে" : "পাঠানো যায়নি: " + error,
                        Toast.LENGTH_SHORT).show();
                // ৩০ সেকেন্ড পর আবার পাঠানো যাবে
                new CountDownTimer(30000, 1000) {
                    @Override public void onTick(long ms) { resend.setText("Resend (" + (ms / 1000) + ")"); }
                    @Override public void onFinish() { resend.setText("Resend code"); resend.setEnabled(true); }
                }.start();
            };
            if (loginMode) AuthRepo.sendLoginCode(email, sent);
            else AuthRepo.resendVerification(email, sent);
        });
    }

    private static String maskEmail(String mail) {
        int at = mail.indexOf('@');
        if (at <= 0) return mail;
        String local = mail.substring(0, at);
        return local.substring(0, Math.min(2, local.length())) + "****" + mail.substring(at);
    }
}
