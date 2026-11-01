package com.familybook.app;

import java.util.ArrayList;
import java.util.List;

/** একই পোস্টের একই ধরনের (like/comment) নোটিফিকেশন একসাথে গ্রুপ করে — ফেসবুকের মতো "A, B and 3 others" */
public class NotificationGroup {
    public NotificationItem latest;                         // সবচেয়ে নতুন নোটিফিকেশন
    public final List<NotificationItem> items = new ArrayList<>();
    public final List<String> actors = new ArrayList<>();   // ইউনিক actorUid, নতুন আগে
    public boolean read = true;                             // সবগুলো পড়া হলে true

    public NotificationGroup(NotificationItem first) {
        latest = first;
        add(first);
    }

    public void add(NotificationItem n) {
        items.add(n);
        if (n.actorUid != null && !actors.contains(n.actorUid)) actors.add(n.actorUid);
        if (!n.read) read = false;
    }
}
