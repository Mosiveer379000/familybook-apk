package com.familybook.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

/** পোস্টে বাছা একটি ছবি: কমপ্রেস করা bytes + আপলোড হলে R2 key */
public class PostPhoto {
    public PhotoUploader.Compressed comp;   // null মানে এখনও কমপ্রেস চলছে
    public String caption = "";            // ছবির নিচের ক্যাপশন (ফাঁকা হতে পারে)
    public String key;                      // R2-তে আপলোড হলে; এডিট করলে null হয়ে আবার আপলোড হবে

    /** কমপ্রেস করা ছবি থেকে সর্বোচ্চ maxSide আকারের Bitmap */
    public Bitmap decode(int maxSide) {
        if (comp == null || comp.bytes == null) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(comp.bytes, 0, comp.bytes.length, o);
        int s = 1;
        while (o.outWidth / (s * 2) >= maxSide || o.outHeight / (s * 2) >= maxSide) s *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = s;
        return BitmapFactory.decodeByteArray(comp.bytes, 0, comp.bytes.length, d);
    }
}
