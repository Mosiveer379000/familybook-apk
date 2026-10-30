package com.familybook.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.MediaScannerConnection;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.widget.FrameLayout;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Photos পেজের ছবির তালিকা (ProfilePhotosActivity-র ভেতরে বসে)।
 * ফোল্ডার: Profile Photos / Cover Photos / Timeline Photos / Albums — সবগুলো একই স্টাইলে
 * (৩ কলামের গ্রিড, উপর-নিচে স্ক্রল)। ডিফল্টে Timeline Photos।
 * ইতিহাস profiles.info.photoHistory-তে থাকে: [{k: R2 key, t: "avatar"|"cover", at: millis}]
 * Timeline Photos আসে প্রোফাইলের পোস্টের ছবি থেকে (setTimeline)।
 */
final class ProfileMedia {

    static final int TAB_ALL = 0, TAB_PHOTOS = 1, TAB_POSTS = 2, TAB_SHORTS = 3;
    static final int FOLDER_PROFILE = 1, FOLDER_COVER = 2, FOLDER_TIMELINE = 3, FOLDER_ALBUMS = 4;
    private static final int MAX_ITEMS = 200;

    private static final class Item {
        String key, kind;
        long at;
    }

    private final Activity act;
    private final LinearLayout box;
    private final float density;

    private final List<Item> avatars = new ArrayList<>();
    private final List<Item> covers = new ArrayList<>();
    private final List<Item> timeline = new ArrayList<>();
    private final List<Item> albums = new ArrayList<>();   // অ্যালবামের ডেটা এখনও নেই — খালি থাকবে
    private int folder = FOLDER_TIMELINE;
    private boolean isMe = false;
    private String ownerUid;
    private Runnable onChanged;

    ProfileMedia(Activity act, ViewGroup parent, int index) {
        this.act = act;
        this.density = act.getResources().getDisplayMetrics().density;
        box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(4), dp(12), dp(24));
        parent.addView(box, index, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private int dp(int v) { return Math.round(v * density); }

    void setOwner(boolean isMe, String uid, Runnable onChanged) {
        this.isMe = isMe;
        this.ownerUid = uid;
        this.onChanged = onChanged;
    }

    /** কোন ফোল্ডার দেখাবে (FOLDER_*) */
    void showFolder(int f) {
        folder = f;
        render();
    }

    // ------------------------------------------------------------------ ডেটা

    void setData(JSONObject profileRow) {
        avatars.clear();
        covers.clear();
        if (profileRow != null) {
            JSONObject info = profileRow.optJSONObject("info");
            JSONArray h = info == null ? null : info.optJSONArray("photoHistory");
            if (h != null) {
                for (int i = 0; i < h.length(); i++) {
                    JSONObject o = h.optJSONObject(i);
                    if (o == null) continue;
                    String k = o.optString("k", "");
                    if (k.isEmpty()) continue;
                    Item it = new Item();
                    it.key = k;
                    it.kind = o.optString("t", "avatar");
                    it.at = o.optLong("at", 0);
                    ("cover".equals(it.kind) ? covers : avatars).add(it);
                }
            }
            // ইতিহাস শুরুর আগের বর্তমান ছবিও যেন ফোল্ডারে থাকে
            addCurrent(profileRow.isNull("avatar_key") ? "" : profileRow.optString("avatar_key", ""), "avatar", avatars);
            addCurrent(profileRow.isNull("cover_key") ? "" : profileRow.optString("cover_key", ""), "cover", covers);
        }
        Collections.sort(avatars, (a, b) -> Long.compare(b.at, a.at));
        Collections.sort(covers, (a, b) -> Long.compare(b.at, a.at));
        render();
    }

    /** পোস্টের ছবির key-গুলো (নতুন থেকে পুরনো ক্রমে) → Timeline Photos */
    void setTimeline(List<String> keys) {
        timeline.clear();
        if (keys != null) {
            long t = keys.size();
            for (String k : keys) {
                Item it = new Item();
                it.key = k;
                it.kind = "timeline";
                it.at = t--;
                timeline.add(it);
            }
        }
        render();
    }

    private static void addCurrent(String key, String kind, List<Item> into) {
        if (key == null || key.isEmpty()) return;
        for (Item it : into) if (key.equals(it.key)) return;
        Item it = new Item();
        it.key = key;
        it.kind = kind;
        it.at = 1;
        into.add(it);
    }

    /** নতুন ছবি আপলোডের পর ইতিহাসে যোগ করে, তারপর done চালায় (সফল না হলেও চালায়) */
    static void record(final String kind, final String key, final Runnable done) {
        Db.getMine("profiles", (p, err) -> {
            if (err != null) { if (done != null) done.run(); return; }
            try {
                JSONObject info = p != null && p.optJSONObject("info") != null
                        ? p.getJSONObject("info") : new JSONObject();
                JSONArray old = info.optJSONArray("photoHistory");
                JSONArray arr = new JSONArray();
                if (old != null) {
                    int from = Math.max(0, old.length() - (MAX_ITEMS - 1));
                    for (int i = from; i < old.length(); i++) arr.put(old.get(i));
                }
                arr.put(new JSONObject().put("k", key).put("t", kind).put("at", System.currentTimeMillis()));
                info.put("photoHistory", arr);
                Db.patchMyProfile(new JSONObject().put("info", info), e -> { if (done != null) done.run(); });
            } catch (Exception e) {
                if (done != null) done.run();
            }
        });
    }

    // ------------------------------------------------------------------ UI

    private void render() {
        box.removeAllViews();
        List<Item> items;
        String empty;
        switch (folder) {
            case FOLDER_PROFILE: items = avatars; empty = "No profile photos yet"; break;
            case FOLDER_COVER:   items = covers;  empty = "No cover photos yet"; break;
            case FOLDER_ALBUMS:  items = albums;  empty = "No albums yet"; break;
            default:             items = timeline; empty = "No timeline photos yet"; break;
        }
        addGrid(items, empty);
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(act);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, Typeface.BOLD);
        return t;
    }

    private void addEmpty(String msg) {
        TextView t = text(msg, 16, 0xFF9AA0A6, false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(16), dp(48), dp(16), dp(32));
        box.addView(t, new LinearLayout.LayoutParams(-1, -2));
    }

    /** R2 key হলে AvatarLoader, পুরনো http লিংক হলে PostImage-এর লোডার */
    private static void loadInto(final ImageView iv, final String key) {
        if (key != null && key.startsWith("http")) {
            iv.setTag(key);
            PostImage.loadAny(key, bm -> { if (bm != null && key.equals(iv.getTag())) iv.setImageBitmap(bm); });
        } else {
            AvatarLoader.loadKey(iv, key);
        }
    }

    private void addGrid(List<Item> items, String emptyMsg) {
        if (items.isEmpty()) { addEmpty(emptyMsg); return; }
        int cols = 3, gap = dp(4);
        int total = act.getResources().getDisplayMetrics().widthPixels - dp(24);
        int cell = (total - gap * (cols - 1)) / cols;
        LinearLayout row = null;
        for (int i = 0; i < items.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(act);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
                rp.bottomMargin = gap;
                box.addView(row, rp);
            }
            final Item it = items.get(i);
            ImageView iv = new ImageView(act);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackgroundColor(0xFFE4E6EB);
            iv.setOnClickListener(v -> openViewer(it));
            loadInto(iv, it.key);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(cell, cell);
            if (i % cols != 0) ip.setMarginStart(gap);
            row.addView(iv, ip);
        }
    }

    // ------------------------------------------------------------------ ছবি দেখা, লাইক/কমেন্ট সংখ্যা, ডাউনলোড, ডিলেট

    private int likeCount, commentCount;
    private boolean likedByMe;

    private void openViewer(final Item it) {
        final Dialog d = new Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        FrameLayout root = new FrameLayout(act);
        root.setBackgroundColor(Color.BLACK);

        final ImageView iv = new ImageView(act);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        loadInto(iv, it.key);
        root.addView(iv, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout bar = new LinearLayout(act);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackgroundColor(0xB3000000);
        bar.setPadding(dp(16), dp(10), dp(16), dp(14));
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-1, -2);
        bp.gravity = Gravity.BOTTOM;
        root.addView(bar, bp);

        final TextView stats = text("", 16, Color.WHITE, true);
        bar.addView(stats);

        LinearLayout actions = new LinearLayout(act);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(10), 0, 0);
        bar.addView(actions);

        final TextView like = actionBtn("👍 Like");
        TextView save = actionBtn("⬇ Download");
        TextView close = actionBtn("✕ Close");
        actions.addView(like);
        actions.addView(save);
        if (isMe && !"timeline".equals(it.kind)) {
            TextView del = actionBtn("🗑 Delete");
            del.setTextColor(0xFFFF6B6B);
            del.setOnClickListener(v -> new AlertDialog.Builder(act)
                    .setMessage("এই ছবিটি FamilyBook থেকে ডিলেট করবেন?")
                    .setPositiveButton("Delete", (dd, w) -> { d.dismiss(); deleteItem(it); })
                    .setNegativeButton("Cancel", null)
                    .show());
            actions.addView(del);
        }
        actions.addView(close);

        likeCount = 0; commentCount = 0; likedByMe = false;
        refreshStats(stats, like);
        loadCounts(it.key, stats, like);

        like.setOnClickListener(v -> toggleLike(it.key, stats, like));
        save.setOnClickListener(v -> download(it.key));
        close.setOnClickListener(v -> d.dismiss());

        d.setContentView(root);
        d.show();
    }

    private TextView actionBtn(String label) {
        TextView t = text(label, 15, Color.WHITE, true);
        t.setPadding(dp(4), dp(6), dp(18), dp(6));
        return t;
    }

    private void refreshStats(TextView stats, TextView like) {
        stats.setText("👍 " + likeCount + "     💬 " + commentCount);
        like.setTextColor(likedByMe ? 0xFF4DA3FF : Color.WHITE);
    }

    private void loadCounts(final String key, final TextView stats, final TextView like) {
        final String k = android.net.Uri.encode(key);
        SupabaseRest.call("GET", "/rest/v1/photo_likes?photo_key=eq." + k + "&select=user_id&limit=5000",
                null, null, true, r -> {
            if (!r.ok()) return;
            try {
                JSONArray a = new JSONArray(r.body);
                likeCount = a.length();
                String me = Me.uid();
                for (int i = 0; i < a.length(); i++) {
                    if (me != null && me.equals(a.getJSONObject(i).optString("user_id"))) likedByMe = true;
                }
                refreshStats(stats, like);
            } catch (Exception ignored) { }
        });
        SupabaseRest.call("GET", "/rest/v1/photo_comments?photo_key=eq." + k + "&select=id&limit=5000",
                null, null, true, r -> {
            if (!r.ok()) return;
            try {
                commentCount = new JSONArray(r.body).length();
                refreshStats(stats, like);
            } catch (Exception ignored) { }
        });
    }

    private void toggleLike(final String key, final TextView stats, final TextView like) {
        final String me = Me.uid();
        if (me == null) return;
        final boolean was = likedByMe;
        likedByMe = !was;
        likeCount = Math.max(0, likeCount + (was ? -1 : 1));
        refreshStats(stats, like);
        String k = android.net.Uri.encode(key);
        if (was) {
            SupabaseRest.call("DELETE", "/rest/v1/photo_likes?photo_key=eq." + k + "&user_id=eq." + me,
                    null, "return=minimal", true, r -> { });
        } else {
            try {
                String body = new JSONObject().put("photo_key", key).put("user_id", me).toString();
                SupabaseRest.call("POST", "/rest/v1/photo_likes?on_conflict=photo_key,user_id", body,
                        "resolution=merge-duplicates,return=minimal", true, r -> { });
            } catch (Exception ignored) { }
        }
    }

    private void download(String key) {
        Bitmap bm = AvatarLoader.cached(key);
        if (bm == null) { Toast.makeText(act, "ছবি এখনও লোড হয়নি", Toast.LENGTH_SHORT).show(); return; }
        String name = "FamilyBook_" + System.currentTimeMillis() + ".jpg";
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/FamilyBook");
                android.net.Uri u = act.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                if (u == null) throw new Exception("insert");
                try (OutputStream os = act.getContentResolver().openOutputStream(u)) {
                    bm.compress(Bitmap.CompressFormat.JPEG, 95, os);
                }
            } else {
                if (act.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
                    act.requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 7731);
                    Toast.makeText(act, "অনুমতি দিয়ে আবার Download চাপুন", Toast.LENGTH_LONG).show();
                    return;
                }
                File dir = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_PICTURES), "FamilyBook");
                if (!dir.exists()) dir.mkdirs();
                File f = new File(dir, name);
                try (FileOutputStream os = new FileOutputStream(f)) {
                    bm.compress(Bitmap.CompressFormat.JPEG, 95, os);
                }
                MediaScannerConnection.scanFile(act, new String[]{f.getAbsolutePath()}, null, null);
            }
            Toast.makeText(act, "ছবি গ্যালারিতে সেভ হয়েছে (Pictures/FamilyBook)", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(act, "ছবি সেভ করা যায়নি", Toast.LENGTH_SHORT).show();
        }
    }

    /** নিজের ছবি ডিলেট: ইতিহাস থেকে সরায়; বর্তমান প্রোফাইল/কভার হলে সেটাও খালি করে */
    private void deleteItem(final Item it) {
        Db.getMine("profiles", (p, err) -> {
            if (err != null || p == null) {
                Toast.makeText(act, "ডিলেট করা যায়নি", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                JSONObject info = p.optJSONObject("info") != null ? p.getJSONObject("info") : new JSONObject();
                JSONArray old = info.optJSONArray("photoHistory");
                JSONArray keep = new JSONArray();
                if (old != null) {
                    for (int i = 0; i < old.length(); i++) {
                        JSONObject o = old.optJSONObject(i);
                        if (o != null && !it.key.equals(o.optString("k"))) keep.put(o);
                    }
                }
                info.put("photoHistory", keep);
                JSONObject f = new JSONObject().put("info", info);
                if (it.key.equals(p.isNull("avatar_key") ? "" : p.optString("avatar_key", ""))) {
                    f.put("avatar_key", JSONObject.NULL);
                    AvatarLoader.setKey(ownerUid, "");
                }
                if (it.key.equals(p.isNull("cover_key") ? "" : p.optString("cover_key", ""))) {
                    f.put("cover_key", JSONObject.NULL);
                }
                Db.patchMyProfile(f, e -> {
                    if (e != null) {
                        Toast.makeText(act, "ডিলেট করা যায়নি", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Toast.makeText(act, "ছবি ডিলেট হয়েছে", Toast.LENGTH_SHORT).show();
                    if (onChanged != null) onChanged.run();
                });
            } catch (Exception e) {
                Toast.makeText(act, "ডিলেট করা যায়নি", Toast.LENGTH_SHORT).show();
            }
        });
    }
}
