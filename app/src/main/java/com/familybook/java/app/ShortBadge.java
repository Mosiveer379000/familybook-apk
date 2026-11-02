package com.familybook.app;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import org.json.JSONArray;

import java.util.HashSet;
import java.util.Set;

/**
 * নতুন Short ভিডিও এলে ছোট লাল ডট।
 *  - নিচের বারের Shorts আইকনের উপরে ডানে (Home / Profile / Menu)
 *  - Shorts পেজের ট্যাবে (Your Shorts বাদে)
 * নিজের আপলোড কখনো ডট দেখায় না। ট্যাব/Shorts পেজ খুললে ডট চলে যায়।
 */
final class ShortBadge {
    private ShortBadge() { }

    private static final String PREF = "short_badge";
    private static final String NAV = "nav";
    private static final long POLL_MS = 30000;

    interface LongCb { void on(long ts); }

    // ------------------------------------------------------------ সংরক্ষণ

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static String key(String tab) {
        String me = SupabaseRest.uid();
        return "seen_" + (me == null ? "" : me) + "_" + tab;
    }

    /** প্রথমবার: এখনকার সময় ধরে নিই, যাতে পুরনো ভিডিওর জন্য ডট না আসে */
    static long seen(Context c, String tab) {
        SharedPreferences p = sp(c);
        String k = key(tab);
        if (!p.contains(k)) {
            long now = System.currentTimeMillis();
            p.edit().putLong(k, now).apply();
            return now;
        }
        return p.getLong(k, 0);
    }

    private static void setSeen(Context c, String tab, long ts) {
        sp(c).edit().putLong(key(tab), ts).apply();
    }

    // ------------------------------------------------------------ ফিল্টার

    /** ট্যাব অনুযায়ী অন্যদের ভিডিওর শর্ত; null = কোনো ভিডিও হতেই পারে না */
    static String filterFor(String tab, String me, Set<String> following, Set<String> followers) {
        if (me == null) return null;
        if ("followers".equals(tab)) {
            Set<String> ids = new HashSet<>(following);
            ids.addAll(followers);
            ids.remove(me);
            return ids.isEmpty() ? null : "user_id=in.(" + join(ids) + ")";
        }
        if ("public".equals(tab)) {
            Set<String> ex = new HashSet<>(following);
            ex.addAll(followers);
            ex.add(me);
            return "user_id=not.in.(" + join(ex) + ")";
        }
        return "user_id=neq." + me;
    }

    private static String join(Set<String> ids) {
        StringBuilder b = new StringBuilder();
        for (String f : ids) { if (b.length() > 0) b.append(','); b.append(f); }
        return b.toString();
    }

    /** শর্ত মেলা সবচেয়ে নতুন Short-এর সময় (millis); না থাকলে 0, ব্যর্থ হলে -1 */
    static void latest(String filter, final LongCb cb) {
        if (filter == null) { cb.on(0); return; }
        SupabaseRest.call("GET", "/rest/v1/shorts?" + filter + "&status=neq.deleted&select=created_at"
                + "&order=created_at.desc&limit=1", null, null, true, r -> {
            if (!r.ok()) { cb.on(-1); return; }
            try {
                JSONArray a = new JSONArray(r.body);
                if (a.length() == 0) { cb.on(0); return; }
                cb.on(NotificationItem.parseIso(a.getJSONObject(0).optString("created_at", "")));
            } catch (Exception e) {
                cb.on(-1);
            }
        });
    }

    // ------------------------------------------------------------ ট্যাব ডট

    /** ট্যাব খোলা হলো: এখনকার সবচেয়ে নতুন ভিডিও পর্যন্ত দেখা ধরি */
    static void markTabSeen(final Context c, final String tab, String filter) {
        final long now = System.currentTimeMillis();
        setSeen(c, tab, now);
        latest(filter, ts -> { if (ts > 0) setSeen(c, tab, Math.max(ts, 1)); });
    }

    /** এই ট্যাবে দেখা হয়নি এমন নতুন ভিডিও আছে কিনা */
    static void hasNew(final Context c, final String tab, String filter, final Runnable yes) {
        final long seen = seen(c, tab);
        latest(filter, ts -> { if (ts > seen) yes.run(); });
    }

    // ------------------------------------------------------------ নিচের বারের ডট

    /** Shorts পেজ খুললে নিচের বারের ডট চলে যায় */
    static void markNavSeen(final Context c) {
        String me = SupabaseRest.uid();
        if (me == null) return;
        setSeen(c, NAV, System.currentTimeMillis());
        latest("user_id=neq." + me, ts -> { if (ts > 0) setSeen(c, NAV, ts); });
    }

    /**
     * নিচের বারের Shorts আইকনের ডট বসায়: স্ক্রিন সামনে এলে ও খোলা থাকলে প্রতি ৩০ সেকেন্ডে আবার দেখে।
     */
    static void attach(final Activity a, final View dot) {
        if (a == null || dot == null) return;
        if ("badge".equals(dot.getTag())) return;     // একই ডটে দুইবার বসাব না
        dot.setTag("badge");
        final Application app = a.getApplication();
        final Handler h = new Handler(Looper.getMainLooper());
        final Runnable[] loop = new Runnable[1];
        loop[0] = () -> {
            refreshNav(a, dot);
            h.postDelayed(loop[0], POLL_MS);
        };
        dot.setVisibility(View.GONE);
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity x) {
                if (x != a) return;
                h.removeCallbacks(loop[0]);
                loop[0].run();
            }
            @Override public void onActivityPaused(Activity x) {
                if (x == a) h.removeCallbacks(loop[0]);
            }
            @Override public void onActivityDestroyed(Activity x) {
                if (x != a) return;
                h.removeCallbacks(loop[0]);
                app.unregisterActivityLifecycleCallbacks(this);
            }
            @Override public void onActivityCreated(Activity x, Bundle b) { }
            @Override public void onActivityStarted(Activity x) { }
            @Override public void onActivityStopped(Activity x) { }
            @Override public void onActivitySaveInstanceState(Activity x, Bundle b) { }
        });
        if (a.getWindow() != null && a.getWindow().getDecorView().isShown()) loop[0].run();
    }

    private static void refreshNav(final Activity a, final View dot) {
        String me = SupabaseRest.uid();
        if (me == null) { dot.setVisibility(View.GONE); return; }
        final long seen = seen(a, NAV);
        latest("user_id=neq." + me, ts -> {
            if (a.isFinishing() || a.isDestroyed()) return;
            if (ts < 0) return;                       // ব্যর্থ হলে আগের অবস্থাই থাকুক
            dot.setVisibility(ts > seen ? View.VISIBLE : View.GONE);
        });
    }
}
