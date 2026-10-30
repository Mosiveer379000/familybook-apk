package com.familybook.app;

import android.content.Intent;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

/**
 * Extend this instead of AppCompatActivity in EVERY screen.
 * Bell icon is shown ONLY in a screen's own white header via bindBell(); never in the blue toolbar.
 * If your layout has a Toolbar with id "toolbar", it is set up automatically.
 *
 * নতুন নোটিফিকেশন: প্রতি ১৫ সেকেন্ডে সার্ভারে জিজ্ঞেস করা হয় (পরে Realtime যোগ করা যাবে)।
 */
public class BaseActivity extends AppCompatActivity {

    private static final long POLL_MS = 15000;

    private TextView badgeView;
    private int unreadCount = 0;

    private final Handler poller = new Handler(Looper.getMainLooper());
    private final Runnable pollTask = this::fetchUnread;
    private boolean polling = false;
    private int gen = 0;

    @Override
    public void setContentView(int layoutResID) {
        super.setContentView(layoutResID);
        setupToolbar();
    }

    @Override
    public void setContentView(View view) {
        super.setContentView(view);
        setupToolbar();
    }

    /** সব স্ক্রিনে স্ট্যাটাস বার (ব্যাটারি/সময়) লুকানো। দরকার না হলে এই মেথডের ভেতরটা মুছে দিন। */
    private void hideStatusBar() {
        try {
            WindowInsetsControllerCompat c = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
            if (c != null) {
                c.hide(WindowInsetsCompat.Type.statusBars());
                c.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideStatusBar();
    }

    private void setupToolbar() {
        Toolbar tb = findViewById(R.id.toolbar);
        if (tb == null) return;
        if (getSupportActionBar() == null) {
            setSupportActionBar(tb);
        } else {
            tb.setVisibility(View.GONE); // theme already has an ActionBar
        }
    }

    /** Use when your screen has its own custom header (no Toolbar menu). */
    protected void bindBell(View container, TextView badge) {
        badgeView = badge;
        container.setOnClickListener(v -> {
            if (!(this instanceof NotificationActivity)) {
                startActivity(new Intent(this, NotificationActivity.class));
            }
        });
        updateBadge();
    }

    @Override
    protected void onStart() {
        super.onStart();
        refreshUnreadNow();
    }

    @Override
    protected void onStop() {
        super.onStop();
        polling = false;
        gen++;
        poller.removeCallbacksAndMessages(null);
    }

    /** এখনই unread সংখ্যা আনে এবং আবার প্রতি ১৫ সেকেন্ডে আনা শুরু করে */
    protected void refreshUnreadNow() {
        poller.removeCallbacksAndMessages(null);
        polling = true;
        gen++;
        fetchUnread();
    }

    private void fetchUnread() {
        if (!polling) return;
        final int g = gen;
        final String uid = SupabaseRest.uid();
        if (uid == null) {
            unreadCount = 0;
            updateBadge();
            poller.postDelayed(pollTask, POLL_MS);
            return;
        }
        String path = "/rest/v1/notifications?user_id=eq." + uid
                + "&select=id,is_read&order=created_at.desc&limit=100";
        SupabaseRest.call("GET", path, null, null, true, r -> {
            if (!polling || g != gen) return;   // স্ক্রিন বন্ধ বা নতুন চক্র শুরু হয়েছে
            if (r.ok()) {
                try {
                    JSONArray a = new JSONArray(r.body);
                    int c = 0;
                    for (int i = 0; i < a.length(); i++) {
                        JSONObject o = a.optJSONObject(i);
                        if (o != null && !o.optBoolean("is_read", false)) c++;
                    }
                    unreadCount = c;
                    handleNewForSound(a, uid);
                    updateBadge();
                } catch (Exception ignored) { }
            }
            poller.postDelayed(pollTask, POLL_MS);
        });
    }

    // ---- sound for NEW notifications (only while app is open) ----
    private static final Set<String> knownIds = new HashSet<>();
    private static String knownUid = null;

    private void handleNewForSound(JSONArray a, String uid) {
        boolean first = !uid.equals(knownUid);
        if (first) { knownIds.clear(); knownUid = uid; }
        boolean fresh = false;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            boolean isNew = knownIds.add(o.optString("id", ""));
            if (isNew && !first && !o.optBoolean("is_read", false)) fresh = true;
        }
        if (fresh) playSound();
    }

    private void playSound() {
        try {
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            RingtoneManager.getRingtone(getApplicationContext(), uri).play();
        } catch (Exception ignored) {}
    }

    private void updateBadge() {
        if (badgeView == null) return;
        if (unreadCount <= 0) {
            badgeView.setVisibility(View.GONE);
        } else {
            badgeView.setText(unreadCount > 99 ? "99+" : String.valueOf(unreadCount));
            badgeView.setVisibility(View.VISIBLE);
        }
    }
}
