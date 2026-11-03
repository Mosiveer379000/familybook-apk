package com.familybook.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/**
 * Memory শেয়ারের আগের পর্দা (এডিটের Next-এর পর): প্রিভিউ · Location · Feeling · Tag people · Privacy · Share।
 * Intent data = এডিট করা ভিডিওর Uri। Share চাপলে MemoryUploader ব্যাকগ্রাউন্ডে আপলোড করে।
 * ক্যামেরা-ফিল্টার, ফন্টসহ টেক্সট, মিউজিক ইতিমধ্যে ShortCreate/ShortEdit-এ ভিডিওতে বসে গেছে।
 */
public class MemorySettingsActivity extends BaseActivity {

    static final String PREFS = "memory_prefs";
    static final String KEY_PRIVACY = "default_privacy";
    static final String[] PRIVACY_VALUE = {"public", "followers", "only_me"};
    static final String[] PRIVACY_LABEL = {"Public", "Followers", "Only me"};
    static final String[] PRIVACY_SUB = {
            "Anyone on FamilyBook can see it",
            "Only people who follow you",
            "Only you can see it"};

    private static final int BLUE = 0xFF0866FF;

    private Uri video;
    private String location = "";
    private String feeling = "";
    private final ArrayList<String> tagIds = new ArrayList<>();
    private final ArrayList<String> tagNames = new ArrayList<>();
    private String collabId, collabName;
    private int privacy = 0;
    private boolean submitted = false;

    private TextView tvLoc, tvFeel, tvTag, tvPriv, tvShare;
    private ImageView preview;

    private ActivityResultLauncher<Intent> locLauncher, feelLauncher, tagLauncher;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        video = getIntent().getData();
        if (video == null) { finish(); return; }

        SharedPreferences sp = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String saved = sp.getString(KEY_PRIVACY, "public");
        for (int i = 0; i < PRIVACY_VALUE.length; i++) if (PRIVACY_VALUE[i].equals(saved)) privacy = i;

        locLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
            if (r.getResultCode() != RESULT_OK || r.getData() == null) return;
            String loc = r.getData().getStringExtra(LocationActivity.EXTRA_LOCATION);
            location = TextUtils.isEmpty(loc) ? "" : loc;
            refreshRows();
        });
        feelLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
            if (r.getResultCode() != RESULT_OK || r.getData() == null) return;
            String t = r.getData().getStringExtra(FeelingActivity.EXTRA_TEXT);
            feeling = t == null ? "" : t;
            refreshRows();
        });
        tagLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
            if (r.getResultCode() != RESULT_OK || r.getData() == null) return;
            Intent d = r.getData();
            ArrayList<String> ids = d.getStringArrayListExtra(TagCollabActivity.EXTRA_TAG_IDS);
            ArrayList<String> names = d.getStringArrayListExtra(TagCollabActivity.EXTRA_TAG_NAMES);
            tagIds.clear(); tagNames.clear();
            if (ids != null) tagIds.addAll(ids);
            if (names != null) tagNames.addAll(names);
            collabId = d.getStringExtra(TagCollabActivity.EXTRA_COLLAB_ID);
            collabName = d.getStringExtra(TagCollabActivity.EXTRA_COLLAB_NAME);
            refreshRows();
        });

        setContentView(buildUi());
        refreshRows();
        loadPreview();
    }

    // ------------------------------------------------------------ UI

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        // হেডার
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(6), dp(8), dp(12), dp(8));
        ImageView back = new ImageView(this);
        back.setImageResource(R.drawable.ic_back);
        back.setColorFilter(Color.BLACK);
        back.setPadding(dp(10), dp(10), dp(10), dp(10));
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));
        TextView title = new TextView(this);
        title.setText("Memory settings");
        title.setTextColor(Color.BLACK);
        title.setTextSize(19);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(top, new LinearLayout.LayoutParams(-1, -2));

        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(4), dp(16), dp(16));
        sv.addView(col, new ScrollView.LayoutParams(-1, -2));
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        // প্রিভিউ
        preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable pbg = new GradientDrawable();
        pbg.setColor(0xFF222222);
        pbg.setCornerRadius(dp(14));
        preview.setBackground(pbg);
        preview.setClipToOutline(true);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(150), dp(240));
        plp.gravity = Gravity.CENTER_HORIZONTAL;
        plp.bottomMargin = dp(8);
        col.addView(preview, plp);

        TextView hint = new TextView(this);
        hint.setText("Your Memory disappears after 24 hours");
        hint.setTextColor(0xFF65676B);
        hint.setTextSize(13);
        hint.setGravity(Gravity.CENTER);
        col.addView(hint, new LinearLayout.LayoutParams(-1, -2));

        tvLoc = row(col, "📍", "Location", v -> {
            Intent i = new Intent(this, LocationActivity.class);
            i.putExtra(LocationActivity.EXTRA_LOCATION, location);
            locLauncher.launch(i);
        });
        tvFeel = row(col, "😊", "Feeling / Activity", v ->
                feelLauncher.launch(new Intent(this, FeelingActivity.class)));
        tvTag = row(col, "🏷️", "Tag people", v -> {
            Intent i = new Intent(this, TagCollabActivity.class);
            i.putStringArrayListExtra(TagCollabActivity.EXTRA_TAG_IDS, tagIds);
            i.putStringArrayListExtra(TagCollabActivity.EXTRA_TAG_NAMES, tagNames);
            i.putExtra(TagCollabActivity.EXTRA_COLLAB_ID, collabId);
            i.putExtra(TagCollabActivity.EXTRA_COLLAB_NAME, collabName);
            tagLauncher.launch(i);
        });
        tvPriv = row(col, "🔒", "Privacy", v -> pickPrivacy());

        // Share বাটন
        tvShare = new TextView(this);
        tvShare.setText("Share to Memory");
        tvShare.setTextColor(Color.WHITE);
        tvShare.setTextSize(17);
        tvShare.setTypeface(Typeface.DEFAULT_BOLD);
        tvShare.setGravity(Gravity.CENTER);
        GradientDrawable sbg = new GradientDrawable();
        sbg.setColor(BLUE);
        sbg.setCornerRadius(dp(24));
        tvShare.setBackground(sbg);
        tvShare.setOnClickListener(v -> share());
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, dp(48));
        slp.setMargins(dp(16), dp(8), dp(16), dp(16));
        root.addView(tvShare, slp);
        return root;
    }

    /** আইকন + নাম + মান (ডানে) + ">" সারি; মান দেখানোর TextView ফেরত দেয় */
    private TextView row(LinearLayout parent, String emoji, String label, View.OnClickListener l) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(14), 0, dp(14));
        TypedValue tv = new TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true))
            r.setBackgroundResource(tv.resourceId);
        r.setOnClickListener(l);

        TextView ic = new TextView(this);
        ic.setText(emoji);
        ic.setTextSize(20);
        ic.setGravity(Gravity.CENTER);
        r.addView(ic, new LinearLayout.LayoutParams(dp(36), -2));

        TextView name = new TextView(this);
        name.setText(label);
        name.setTextColor(Color.BLACK);
        name.setTextSize(16);
        r.addView(name, new LinearLayout.LayoutParams(-2, -2));

        TextView val = new TextView(this);
        val.setTextColor(0xFF65676B);
        val.setTextSize(14);
        val.setGravity(Gravity.END);
        val.setSingleLine(true);
        val.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(0, -2, 1f);
        vlp.setMargins(dp(12), 0, dp(8), 0);
        r.addView(val, vlp);

        TextView chev = new TextView(this);
        chev.setText("›");
        chev.setTextColor(0xFF8A8D91);
        chev.setTextSize(22);
        r.addView(chev, new LinearLayout.LayoutParams(-2, -2));

        parent.addView(r, new LinearLayout.LayoutParams(-1, -2));
        View line = new View(this);
        line.setBackgroundColor(0xFFE4E6EB);
        parent.addView(line, new LinearLayout.LayoutParams(-1, 1));
        return val;
    }

    private void refreshRows() {
        tvLoc.setText(location.isEmpty() ? "Add" : location);
        tvFeel.setText(feeling.isEmpty() ? "Add" : feeling);
        tvTag.setText(tagNames.isEmpty() ? "Add" : TextUtils.join(", ", tagNames));
        tvPriv.setText(PRIVACY_LABEL[privacy]);
    }

    private void pickPrivacy() {
        String[] items = new String[PRIVACY_LABEL.length];
        for (int i = 0; i < items.length; i++) items[i] = PRIVACY_LABEL[i] + "\n" + PRIVACY_SUB[i];
        new AlertDialog.Builder(this)
                .setTitle("Who can see your Memory?")
                .setSingleChoiceItems(items, privacy, (d, which) -> {
                    privacy = which;
                    getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                            .putString(KEY_PRIVACY, PRIVACY_VALUE[which]).apply();
                    refreshRows();
                    d.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void loadPreview() {
        final Context app = getApplicationContext();
        new Thread(() -> {
            Bitmap bm = null;
            try {
                MediaMetadataRetriever mr = new MediaMetadataRetriever();
                mr.setDataSource(app, video);
                bm = mr.getFrameAtTime(300_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                mr.release();
            } catch (Throwable ignored) { }
            final Bitmap fb = bm;
            runOnUiThread(() -> { if (fb != null && !isFinishing()) preview.setImageBitmap(fb); });
        }).start();
    }

    private void share() {
        if (submitted) return;
        try {
            JSONObject m = new JSONObject();
            m.put("location", location);
            m.put("feeling", feeling);
            m.put("tag_ids", new JSONArray(tagIds));
            m.put("tag_names", new JSONArray(tagNames));
            m.put("privacy", PRIVACY_VALUE[privacy]);
            submitted = true;
            MemoryUploader.post(this, video, m);
            setResult(RESULT_OK);
            finish();
        } catch (Exception e) {
            submitted = false;
            Toast.makeText(this, "Couldn't share the Memory", Toast.LENGTH_SHORT).show();
        }
    }
}
