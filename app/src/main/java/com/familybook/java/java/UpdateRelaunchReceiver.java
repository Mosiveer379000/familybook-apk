package com.familybook.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * আপডেট ইনস্টল হওয়ার পর (অ্যাপ নতুন ভার্সনে বদলালে) সরাসরি "Congratulations" পেজ খোলার চেষ্টা করে।
 * কিছু ফোনে ব্যাকগ্রাউন্ড থেকে স্ক্রিন খোলা নিষেধ; সেক্ষেত্রে পরের বার অ্যাপ খুললেই পেজটা দেখা যাবে।
 */
public class UpdateRelaunchReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null || !Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) return;
        try {
            SharedPreferences sp = ctx.getSharedPreferences("update_prefs", Context.MODE_PRIVATE);
            if (sp.getLong("installing_code", 0) <= 0) return;
            Intent i = new Intent(ctx, UpdateActivity.class);
            i.putExtra(UpdateActivity.EXTRA_SUCCESS, true);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            ctx.startActivity(i);
        } catch (Throwable ignore) {
        }
    }
}
