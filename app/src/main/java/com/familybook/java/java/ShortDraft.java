package com.familybook.app;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * "Continue editing your last reel?" এর জন্য সাময়িক ড্রাফট।
 * শুধু রেকর্ড করা ভিডিওটা সেভ থাকে (filesDir/short_draft/draft.mp4);
 * টেক্সট, মিউজিক, ফিল্টার ইত্যাদি সেভ হয় না।
 */
final class ShortDraft {
    private ShortDraft() { }

    private static volatile int gen = 0;   // clear() হলে চলমান কপি বাতিল

    static File file(Context c) {
        return new File(new File(c.getFilesDir(), "short_draft"), "draft.mp4");
    }

    static boolean exists(Context c) {
        File f = file(c);
        return f.exists() && f.length() > 0;
    }

    /** ব্যাকগ্রাউন্ডে ভিডিও কপি করে রাখে (এডিট স্ক্রিন সাথে সাথেই খোলে)। */
    static void saveAsync(Context c, final File src) {
        final Context app = c.getApplicationContext();
        final int g = gen;
        new Thread(() -> {
            try {
                File dst = file(app);
                File dir = dst.getParentFile();
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                File tmp = new File(dir, "draft.tmp");
                try (FileInputStream in = new FileInputStream(src); FileOutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                if (g != gen) { tmp.delete(); return; }      // ইতিমধ্যে ক্যান্সেল/পোস্ট হয়ে গেছে
                dst.delete();
                //noinspection ResultOfMethodCallIgnored
                tmp.renameTo(dst);
            } catch (Throwable ignored) { }
        }).start();
    }

    static void clear(Context c) {
        gen++;
        file(c).delete();
        new File(file(c).getParentFile(), "draft.tmp").delete();
    }
}
