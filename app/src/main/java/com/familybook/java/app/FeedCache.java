package com.familybook.app;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ফিডের সর্বশেষ সার্ভার-উত্তর ফাইলে রাখে, যাতে ইন্টারনেট না থাকলেও (বা অ্যাপ নতুন খুললেই)
 * আগের পোস্টগুলো সাথে সাথে দেখানো যায়। প্রতি ইউজারের আলাদা ফাইল।
 */
final class FeedCache {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private FeedCache() { }

    private static File file(Context c, String uid, String name) {
        File dir = new File(c.getApplicationContext().getFilesDir(), "feedcache");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, uid + "_" + name + ".json");
    }

    /** ব্যাকগ্রাউন্ডে লেখে (মেইন থ্রেড আটকায় না) */
    static void put(Context c, String uid, String name, String json) {
        if (c == null || uid == null || json == null) return;
        final File f = file(c, uid, name);
        IO.execute(() -> {
            File tmp = new File(f.getPath() + ".tmp");
            try (FileOutputStream o = new FileOutputStream(tmp)) {
                o.write(json.getBytes("UTF-8"));
            } catch (Exception e) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                return;
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(f);
        });
    }

    /** না থাকলে null */
    static String get(Context c, String uid, String name) {
        if (c == null || uid == null) return null;
        File f = file(c, uid, name);
        if (!f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        } catch (Exception e) {
            return null;
        }
    }
}
