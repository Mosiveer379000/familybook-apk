package com.familybook.app;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

/**
 * কারো সাথে সরাসরি Family Chat খোলে (ProfileActivity-র "Message" বাটনের মতোই: deep link -> অ্যাপ খোলা -> Play/Store)।
 * Birthday reminder-এর "Message" বাটন (মালিকের "Wishes from followers" বন্ধ থাকলে) এটা ব্যবহার করে।
 */
final class FamilyChatOpen {
    private static final String PKG = "com.familychat.app";

    private FamilyChatOpen() { }

    static void open(final Context c, final String uid, final String name) {
        if (uid == null) return;
        ChatHandoff.fetch(c, handoff -> openNow(c, uid, name == null ? "" : name, handoff));
    }

    private static void openNow(Context c, String uid, String name, String handoff) {
        try {
            Intent dl = new Intent(Intent.ACTION_VIEW, Uri.parse("familychat://chat?uid=" + Uri.encode(uid)));
            dl.setPackage(PKG);
            dl.putExtra("chat_uid", uid);
            dl.putExtra("chat_name", name);
            ChatHandoff.putExtras(dl, handoff);
            dl.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(dl);
            return;
        } catch (ActivityNotFoundException ignored) { }
        Intent intent = c.getPackageManager().getLaunchIntentForPackage(PKG);
        if (intent != null) {
            intent.putExtra("chat_uid", uid);
            intent.putExtra("chat_name", name);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(intent);
        } else {
            Toast.makeText(c, "Family Chat app is not installed", Toast.LENGTH_SHORT).show();
            try {
                c.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + PKG))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException ignored) { }
        }
    }
}
