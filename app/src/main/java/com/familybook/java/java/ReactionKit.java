package com.familybook.app;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/** পোস্টের Like বাটনে চেপে ধরলে রিঅ্যাকশন বার (Facebook স্টাইল) */
public final class ReactionKit {
    private ReactionKit() {
    }

    /** রিঅ্যাকশন বারের ক্রম: Like, Love, Check, Stop, Angry, Haha (কমেন্ট ও পোস্ট দুই জায়গাতেই) */
    public static final String[] POST_KEYS = {"like", "love", "check", "stop", "angry", "haha", "wow"};
    public static final int LIKE_BLUE = 0xFF1877F2;

    /** Firebase-এর মান (true / "like" / "check" ...) থেকে রিঅ্যাকশন key; null = নেই */
    public static String normalize(Object raw) {
        if (raw == null) {
            return null;
        }
        String v = String.valueOf(raw);
        if (v.isEmpty() || "true".equals(v) || "like".equals(v)) {
            return "like";
        }
        return v;
    }

    public static int icon(String key) {
        if ("love".equals(key)) return R.drawable.react_love;
        if ("check".equals(key)) return R.drawable.react_check;
        if ("stop".equals(key)) return R.drawable.react_stop;
        if ("haha".equals(key)) return R.drawable.react_haha;
        if ("angry".equals(key)) return R.drawable.react_angry;
        if ("unlike".equals(key)) return R.drawable.ic_unlike;
        return R.drawable.ic_like;
    }

    /** Wow আইকন: res/drawable/react_wow.png (না থাকলে check_wow.png); কোনোটাই না থাকলে 0 */
    private static int wowIcon(android.content.Context c) {
        int id = c.getResources().getIdentifier("react_wow", "drawable", c.getPackageName());
        if (id == 0) {
            id = c.getResources().getIdentifier("check_wow", "drawable", c.getPackageName());
        }
        return id;
    }

    /** Context সহ আইকন: wow এখানে সামলানো হয়, বাকিগুলো icon(key) এর মতো */
    public static int icon(android.content.Context c, String key) {
        if ("wow".equals(key)) {
            int id = wowIcon(c);
            return id != 0 ? id : R.drawable.ic_like;
        }
        return icon(key);
    }

    /** আইকন বসায়: Like নীল রঙে, বাকিগুলো নিজের রঙে */
    public static void setIcon(ImageView iv, String key) {
        iv.setImageResource(icon(iv.getContext(), key));
        if (key == null || "like".equals(key)) {
            iv.setColorFilter(LIKE_BLUE);
        } else {
            iv.clearColorFilter();
        }
    }

    private static int dp(Activity a, int v) {
        return (int) (v * a.getResources().getDisplayMetrics().density + 0.5f);
    }

    /**
     * রিঅ্যাকশন সিলেক্টের সাউন্ড: haha / check / wow এর নিজের ফাইল (res/raw/react_haha.wav,
     * react_check.wav, react_wow.wav) থাকলে সেটা, না থাকলে সাধারণ react_select.mp3।
     * ফাইল না থাকলে বিল্ড ফেল করবে না। কমেন্ট ও পোস্ট দুই জায়গাতেই এটা ব্যবহার হয়।
     */
    public static int selectSound(android.content.Context c, String key) {
        if ("haha".equals(key) || "check".equals(key) || "wow".equals(key)) {
            int id = c.getResources().getIdentifier("react_" + key, "raw", c.getPackageName());
            if (id == 0 && "wow".equals(key)) {
                id = c.getResources().getIdentifier("check_wow", "raw", c.getPackageName());
            }
            if (id != 0) {
                return id;
            }
        }
        return R.raw.react_select;
    }

    // ---------- পোস্ট-ভিত্তিক সাউন্ড সেটিং (পোস্টের মালিক থ্রি-ডট মেনু থেকে ঠিক করে) ----------
    // Firebase: posts/<id>/reactSounds/{haha|check|wow} = false হলে ওই মিম সাউন্ড বন্ধ (ডিফল্ট সাউন্ড বাজবে)।
    // কিছু সেট না থাকলে মিম সাউন্ড চালু।
    private static final Map<String, Map<String, Boolean>> SOUND_CACHE = new HashMap<>();
    public static final String[] MEME_KEYS = {"haha", "check", "wow"};

    /** পোস্ট লোড হওয়ার সময় সেটিং ক্যাশে রাখে */
    public static void cacheSounds(String postId, boolean haha, boolean check, boolean wow) {
        if (postId == null) {
            return;
        }
        Map<String, Boolean> m = new HashMap<>();
        m.put("haha", haha);
        m.put("check", check);
        m.put("wow", wow);
        SOUND_CACHE.put(postId, m);
    }

    /** মালিক সেটিং সেভ করলে: Supabase + ক্যাশ */
    public static void saveSounds(String postId, boolean haha, boolean check, boolean wow) {
        try {
            JSONObject body = new JSONObject()
                    .put("sound_haha", haha)
                    .put("sound_check", check)
                    .put("sound_wow", wow);
            SupabaseRest.call("PATCH", "/rest/v1/posts?id=eq." + postId, body.toString(),
                    "return=minimal", true, r -> { });
        } catch (Exception ignored) {
        }
        cacheSounds(postId, haha, check, wow);
    }

    /** পোস্টে রিঅ্যাকশন বসায় (আগের থাকলে বদলে দেয়) */
    public static void setReaction(final Activity act, String postId, String myUid, String key) {
        try {
            JSONObject row = new JSONObject()
                    .put("post_id", postId)
                    .put("user_id", myUid)
                    .put("reaction", key);
            SupabaseRest.call("POST", "/rest/v1/post_reactions?on_conflict=post_id,user_id",
                    row.toString(), "resolution=merge-duplicates,return=minimal", true, r -> {
                if (!r.ok()) {
                    Toast.makeText(act, SupabaseRest.errorText(r), Toast.LENGTH_LONG).show();
                }
            });
        } catch (Exception ignored) {
        }
    }

    /** পোস্টের রিঅ্যাকশন তুলে নেয় (unlike) */
    public static void removeReaction(final Activity act, String postId, String myUid) {
        SupabaseRest.call("DELETE",
                "/rest/v1/post_reactions?post_id=eq." + postId + "&user_id=eq." + myUid,
                null, "return=minimal", true, r -> {
            if (!r.ok()) {
                Toast.makeText(act, SupabaseRest.errorText(r), Toast.LENGTH_LONG).show();
            }
        });
    }

    private static void playResolved(Activity a, Map<String, Boolean> m, String key) {
        boolean on = m == null || !Boolean.FALSE.equals(m.get(key));
        playSelect(a, on ? key : "default");
    }

    /** ওই পোস্টের সেটিং মেনে রিঅ্যাকশন সাউন্ড একবার বাজায় */
    public static void playFor(final Activity a, final String postId, final String key) {
        if (postId == null) {
            playSelect(a, key);
            return;
        }
        Map<String, Boolean> cached = SOUND_CACHE.get(postId);
        if (cached != null) {
            playResolved(a, cached, key);
            return;
        }
        SupabaseRest.call("GET",
                "/rest/v1/posts?id=eq." + postId + "&select=sound_haha,sound_check,sound_wow",
                null, null, true, r -> {
            if (!r.ok()) {
                playSelect(a, key);
                return;
            }
            try {
                JSONArray arr = new JSONArray(r.body);
                if (arr.length() == 0) {
                    playSelect(a, key);
                    return;
                }
                JSONObject o = arr.getJSONObject(0);
                cacheSounds(postId, o.optBoolean("sound_haha", true),
                        o.optBoolean("sound_check", true), o.optBoolean("sound_wow", true));
                playResolved(a, SOUND_CACHE.get(postId), key);
            } catch (Exception e) {
                playSelect(a, key);
            }
        });
    }

    /** রিঅ্যাকশনের সাউন্ড একবার বাজায় (নিজের ফাইল থাকলে সেটা, নইলে ডিফল্ট react_select) */
    public static void playSelect(Activity a, String key) {
        play(a, selectSound(a, key));
    }

    private static void play(Activity a, int resId) {
        try {
            final MediaPlayer mp = MediaPlayer.create(a, resId);
            if (mp == null) {
                return;
            }
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer player) {
                    player.release();
                }
            });
            mp.start();
        } catch (Exception ignored) {
        }
    }

    /** anchor (Like বাটন) এ চেপে ধরা (~0.35 সেকেন্ড) ধরে রিঅ্যাকশন বার খোলে; সাধারণ ট্যাপ আগের মতোই কাজ করে */
    /** রিঅ্যাকশন বার থেকে কোনো রিঅ্যাকশন বাছা হলে পর্দা সঙ্গে সঙ্গে বদলানোর জন্য */
    public interface OnPicked { void on(String key); }

    public static void attach(final Activity act, final View anchor, final String postId,
                              final String postOwner, final String myUid, final boolean alreadyLiked) {
        attach(act, anchor, postId, postOwner, myUid, alreadyLiked, null);
    }

    public static void attach(final Activity act, final View anchor, final String postId,
                              final String postOwner, final String myUid, final boolean alreadyLiked,
                              final OnPicked picked) {
        if (act == null || postId == null || myUid == null) {
            return;
        }
        final Handler handler = new Handler(Looper.getMainLooper());
        final boolean[] fired = {false};
        final float[] down = new float[2];
        final Runnable pressRun = new Runnable() {
            @Override
            public void run() {
                fired[0] = true;
                if (anchor.getParent() != null) {
                    anchor.getParent().requestDisallowInterceptTouchEvent(true);
                }
                anchor.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                showBar(act, anchor, postId, postOwner, myUid, alreadyLiked, picked);
            }
        };
        anchor.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                int action = e.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    fired[0] = false;
                    down[0] = e.getRawX();
                    down[1] = e.getRawY();
                    handler.postDelayed(pressRun, 350);
                    return false;
                }
                if (action == MotionEvent.ACTION_MOVE) {
                    if (!fired[0] && (Math.abs(e.getRawX() - down[0]) > dp(act, 12)
                            || Math.abs(e.getRawY() - down[1]) > dp(act, 12))) {
                        handler.removeCallbacks(pressRun);
                    }
                    return fired[0];
                }
                if (action == MotionEvent.ACTION_UP) {
                    handler.removeCallbacks(pressRun);
                    if (fired[0]) {
                        fired[0] = false;
                        return true;
                    }
                    return false;
                }
                if (action == MotionEvent.ACTION_CANCEL) {
                    handler.removeCallbacks(pressRun);
                }
                return false;
            }
        });
    }

    private static void showBar(final Activity act, final View anchor, final String postId,
                                final String postOwner, final String myUid, final boolean alreadyLiked,
                                final OnPicked picked) {
        play(act, R.raw.react_open);
        final FrameLayout overlay = new FrameLayout(act);
        overlay.setClickable(true);
        overlay.setFocusableInTouchMode(true);
        final Runnable dismiss = new Runnable() {
            @Override
            public void run() {
                ViewGroup p = (ViewGroup) overlay.getParent();
                if (p != null) {
                    p.removeView(overlay);
                }
            }
        };
        overlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss.run();
            }
        });
        overlay.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                    dismiss.run();
                    return true;
                }
                return false;
            }
        });

        final LinearLayout bar = new LinearLayout(act);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(act, 6), dp(act, 4), dp(act, 6), dp(act, 4));
        bar.setElevation((float) dp(act, 8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFFFFFFF);
        bg.setCornerRadius((float) dp(act, 24));
        bg.setStroke(dp(act, 1), 0xFFE0E0E0);
        bar.setBackground(bg);
        bar.setVisibility(View.INVISIBLE);

        for (final String key : POST_KEYS) {
            ImageView iv = new ImageView(act);
            setIcon(iv, key);
            iv.setPadding(dp(act, 3), dp(act, 3), dp(act, 3), dp(act, 3));
            iv.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dismiss.run();
                    if (!Net.check(act)) {
                        return;
                    }
                    playFor(act, postId, key);
                    setReaction(act, postId, myUid, key);
                    if (!alreadyLiked && postOwner != null) {
                        NotificationHelper.send(postOwner, "like", postId);
                    }
                    if (picked != null) picked.on(key);
                }
            });
            bar.addView(iv, new LinearLayout.LayoutParams(dp(act, 38), dp(act, 38)));
        }
        overlay.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        try {
            act.addContentView(overlay, new ViewGroup.LayoutParams(-1, -1));
            overlay.requestFocus();
        } catch (Exception e) {
            Toast.makeText(act, "Reaction error: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        overlay.post(new Runnable() {
            @Override
            public void run() {
                int[] a = new int[2];
                int[] o = new int[2];
                anchor.getLocationOnScreen(a);
                overlay.getLocationOnScreen(o);
                int w = bar.getWidth();
                int h = bar.getHeight();
                int x = Math.max(dp(act, 8), Math.min(a[0] - o[0], overlay.getWidth() - w - dp(act, 8)));
                int y = (a[1] - o[1]) - h - dp(act, 8);
                if (y < dp(act, 8)) {
                    y = (a[1] - o[1]) + anchor.getHeight() + dp(act, 8);
                }
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) bar.getLayoutParams();
                lp.gravity = Gravity.NO_GRAVITY;
                lp.leftMargin = x;
                lp.topMargin = y;
                bar.setLayoutParams(lp);
                bar.setVisibility(View.VISIBLE);
            }
        });
    }
}
