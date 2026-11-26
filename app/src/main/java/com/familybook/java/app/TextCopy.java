package com.familybook.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.widget.TextView;
import android.widget.Toast;

/**
 * লেখায় চেপে ধরলে (long press) পুরো লেখা কপি হয় আর "Text Copied" দেখায়।
 * পোস্ট (status / ছবির ক্যাপশন / ভিডিওর ক্যাপশন) — ফিড, প্রোফাইল ও গ্রুপ সব জায়গায় এটাই ব্যবহার হয়।
 */
final class TextCopy {
    private TextCopy() { }

    interface Src { CharSequence get(); }

    /** tv-তে চেপে ধরলে src.get() এর লেখা কপি হবে (টপ-টোস্ট) */
    static void attach(TextView tv, Src src) {
        attach(tv, src, true);
    }

    /** topToast = false হলে সাধারণ Toast (ফুল-স্ক্রিন Dialog-এর ভেতরে TopToast ঢাকা পড়ে যায়) */
    static void attach(TextView tv, Src src, boolean topToast) {
        if (tv == null) return;
        tv.setOnLongClickListener(v -> {
            copy(v.getContext(), src == null ? null : src.get(), topToast);
            return true;
        });
    }

    static void copy(Context ctx, CharSequence text, boolean topToast) {
        if (ctx == null || text == null) return;
        String t = text.toString().trim();
        if (t.isEmpty()) return;
        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText("text", t));
        Activity a = activityOf(ctx);
        if (topToast && a != null) TopToast.show(a, "Text Copied");
        else Toast.makeText(ctx, "Text Copied", Toast.LENGTH_SHORT).show();
    }

    private static Activity activityOf(Context c) {
        while (c instanceof ContextWrapper) {
            if (c instanceof Activity) return (Activity) c;
            c = ((ContextWrapper) c).getBaseContext();
        }
        return null;
    }
}
