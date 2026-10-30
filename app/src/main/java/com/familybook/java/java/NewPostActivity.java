package com.familybook.app;

import android.content.Intent;
import android.net.Uri;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;

public class NewPostActivity extends AppCompatActivity {

    private static final String TAG = "NewPostActivity";

    // Edit মোড: "edit_post_id" ইন্টেন্ট থাকলে পুরনো পোস্ট এই স্ক্রিনেই এডিট হয়
    private String editId = null;
    private PostOps.Loaded editLoaded = null;

    // Views
    private ImageView btnClose, imgProfile;
    private TextView tvUserName;
    private LinearLayout btnMusic, btnTagCollab, btnLocation, btnFeeling;
    private LinearLayout btnPrivacy, btnGallery, btnGif;
    private TextView tvPrivacy;
    private EditText etText, etHashtags;
    private Button btnPost;

    // Privacy state
    private String currentPrivacy = "Public";

    // গ্রুপ মোড: "group_id" থাকলে পোস্ট গ্রুপে যায়। বাকি সবকিছু (ছবি, গ্রিড, হ্যাশট্যাগ, feeling,
    // location, music, tag, collab) হোমের হুবহু একই। শুধু ট্যাগ/কোলাব গ্রুপ সদস্যদের মধ্যে সীমাবদ্ধ।
    private String groupId = null;
    private boolean groupPublic = true;
    private ArrayList<String> memberIds = null;
    private ArrayList<String> memberNames = null;
    private boolean inGroup() { return groupId != null; }

    // profiles টেবিল থেকে আনা আসল নাম
    private String authorName = null;
    // লেআউটে যে নাম আছে (profile আসার আগ পর্যন্ত দেখানোর জন্য)
    private String shownName = "";

    // Feeling / Activity (একবারে একটাই)
    private String feelingText = null;
    private String activityText = null;

    // Tag / Collab
    private final ArrayList<String> tagIds = new ArrayList<>();
    private final ArrayList<String> tagNames = new ArrayList<>();
    private String collabId = null;
    private String collabName = null;

    // Music (ফেসবুকের মতো): committed = পোস্টে যা যাবে, pending = বাছাই/কাটার সময়ের অস্থায়ী মান
    private String musicTitle, musicArtist, musicCover, musicUrl;
    private int musicStart = 0;
    private String pTitle, pArtist, pCover, pUrl;
    private int pStart = 0;

    private final ActivityResultLauncher<Intent> pickLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), res -> {
                if (res.getResultCode() != RESULT_OK || res.getData() == null) return;
                Intent d = res.getData();
                pTitle = d.getStringExtra(MusicPickerActivity.EXTRA_TITLE);
                pArtist = d.getStringExtra(MusicPickerActivity.EXTRA_ARTIST);
                pCover = d.getStringExtra(MusicPickerActivity.EXTRA_COVER);
                pUrl = d.getStringExtra(MusicPickerActivity.EXTRA_URL);
                pStart = 0;
                openTrim();            // গান বাছার পর সরাসরি ক্লিপ-কাটার স্ক্রিন
            });

    private final ActivityResultLauncher<Intent> trimLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), res -> {
                int code = res.getResultCode();
                if (code == MusicTrimActivity.RESULT_CHANGE) {
                    openPicker();
                } else if (code == MusicTrimActivity.RESULT_DELETE) {
                    musicTitle = musicArtist = musicCover = musicUrl = null;
                    musicStart = 0;
                    pTitle = pArtist = pCover = pUrl = null;
                    updateMusicChip();
                } else if (code == RESULT_OK && res.getData() != null) {
                    musicTitle = pTitle;
                    musicArtist = pArtist;
                    musicCover = pCover;
                    musicUrl = pUrl;
                    musicStart = res.getData().getIntExtra(MusicTrimActivity.EXTRA_START, 0);
                    updateMusicChip();
                }
                // Cancel: অস্থায়ী বাছাই বাতিল, আগের গান (থাকলে) যেমন ছিল তেমন
            });

    // Photo (গ্যালারি থেকে একসাথে অনেক ছবি; বড় ছবি অটো কমপ্রেস হয়ে R2-তে আপলোড হয়)
    private static final int MAX_PHOTOS = 10;

    private final java.util.List<PostPhoto> photos = new ArrayList<>();
    private int layoutVariant = 0;           // গ্রিডের সাজানো (গ্রিড আইকনে চাপলে বদলায়)
    private FrameLayout photoBox;
    private ImageView photoPreviewImg;

    private final ActivityResultLauncher<String> photoLauncher =
            registerForActivityResult(new ActivityResultContracts.GetMultipleContents(), uris -> {
                if (uris != null && !uris.isEmpty()) addPhotos(uris);
            });

    // Location
    private String locationText = null;

    private final ActivityResultLauncher<Intent> locationLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), res -> {
                if (res.getResultCode() != RESULT_OK || res.getData() == null) return;
                String loc = res.getData().getStringExtra(LocationActivity.EXTRA_LOCATION);
                locationText = TextUtils.isEmpty(loc) ? null : loc;   // খালি = সরানো
                refreshHeader();
                updateLocationChip();
            });

    private final ActivityResultLauncher<Intent> feelingLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), res -> {
                if (res.getResultCode() != RESULT_OK || res.getData() == null) return;
                String kind = res.getData().getStringExtra(FeelingActivity.EXTRA_KIND);
                String text = res.getData().getStringExtra(FeelingActivity.EXTRA_TEXT);
                if ("feeling".equals(kind)) {
                    feelingText = text;
                    activityText = null;
                } else {
                    activityText = text;
                    feelingText = null;
                }
                refreshHeader();
            });

    private final ActivityResultLauncher<Intent> tagLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), res -> {
                if (res.getResultCode() != RESULT_OK || res.getData() == null) return;
                Intent d = res.getData();
                ArrayList<String> ids = d.getStringArrayListExtra(TagCollabActivity.EXTRA_TAG_IDS);
                ArrayList<String> names = d.getStringArrayListExtra(TagCollabActivity.EXTRA_TAG_NAMES);
                tagIds.clear();
                tagNames.clear();
                if (ids != null) tagIds.addAll(ids);
                if (names != null) tagNames.addAll(names);
                collabId = d.getStringExtra(TagCollabActivity.EXTRA_COLLAB_ID);
                collabName = d.getStringExtra(TagCollabActivity.EXTRA_COLLAB_NAME);
                refreshHeader();
                updateTagChip();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_new_post);

        bindViews();
        shownName = tvUserName.getText().toString();
        setupAvatarView();
        AvatarLoader.load(imgProfile, SupabaseRest.uid());   // হোমের মতো একই ছবি
        refreshHeader();
        updateTagChip();
        updateLocationChip();
        updateMusicChip();
        setupListeners();
        buildPhotoBox();
        loadAuthorName();
        groupId = getIntent().getStringExtra("group_id");
        if (groupId != null) {
            groupPublic = !"private".equals(getIntent().getStringExtra("group_visibility"));
            memberIds = getIntent().getStringArrayListExtra("member_ids");
            memberNames = getIntent().getStringArrayListExtra("member_names");
            // প্রাইভেসি বাটন থাকে, কিন্তু দেখায় গ্রুপের প্রাইভেসি (পাবলিক গ্রুপ = নিউজ ফিডেও আসবে)
            tvPrivacy.setText(groupPublic ? "\uD83C\uDF10 Public group" : "\uD83D\uDD12 Private group");
        }
        editId = getIntent().getStringExtra("edit_post_id");
        // হোমের ছবির বাটন থেকে এলে সরাসরি গ্যালারি খোলে
        if (editId != null) {
            startEditMode();
        } else if (savedInstanceState == null && getIntent().getBooleanExtra("pickPhoto", false)) {
            photoLauncher.launch("image/*");
        }
    }

    // ---------------------------------------------------------
    // profiles টেবিল থেকে লগইন করা ইউজারের আসল নাম আনা
    // ---------------------------------------------------------
    private void loadAuthorName() {
        String uid = SupabaseRest.uid();
        if (uid == null) return;
        fetchProfile("id", uid, true);
    }

    // কলামের নাম "id" না হলে "user_id" দিয়ে আবার চেষ্টা করে
    private void fetchProfile(final String keyCol, final String uid, final boolean retry) {
        SupabaseRest.call("GET",
                "/rest/v1/profiles?" + keyCol + "=eq." + uid + "&select=*&limit=1",
                null, null, true, r -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (!r.ok()) {
                        Log.w(TAG, "profile fetch failed (" + keyCol + "): "
                                + r.code + " " + r.body);
                        if (retry) fetchProfile("user_id", uid, false);
                        return;
                    }
                    try {
                        JSONArray arr = new JSONArray(r.body);
                        if (arr.length() == 0) {
                            if (retry) fetchProfile("user_id", uid, false);
                            return;
                        }
                        JSONObject p = arr.getJSONObject(0);
                        String[] keys = {"full_name", "name", "display_name", "username"};
                        for (String k : keys) {
                            String v = p.optString(k, "");
                            if (!v.isEmpty() && !"null".equals(v)) {
                                authorName = v;
                                refreshHeader();
                                break;
                            }
                        }
                        if (authorName == null) {
                            Log.w(TAG, "profile columns: " + p.names());
                        }
                    } catch (JSONException e) {
                        Log.e(TAG, "profile parse error", e);
                    }
                });
    }

    // নাম বোল্ড; "— 😊 feeling happy with X at Y." অংশে feeling-এর শব্দ, নাম ও লোকেশন বোল্ড
    private void refreshHeader() {
        String base = !TextUtils.isEmpty(authorName) ? authorName : shownName;
        SpannableStringBuilder sb = new SpannableStringBuilder(base);
        sb.setSpan(new StyleSpan(Typeface.BOLD), 0, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

        String chipDefault = null;
        if (!TextUtils.isEmpty(feelingText)) {
            String[] parts = splitEmoji(feelingText);
            chipDefault = parts[0] + " feeling " + parts[1] + ".";
        } else if (!TextUtils.isEmpty(activityText)) {
            String[] parts = splitEmoji(activityText);
            chipDefault = parts[0] + " " + lowerFirst(parts[1]) + ".";
        }
        // with: কোলাবরেটর আগে, তারপর ট্যাগ (ফেসবুকের মতো: A / A and B / A and 2 others)
        ArrayList<String> withNames = new ArrayList<>();
        if (!TextUtils.isEmpty(collabName)) withNames.add(collabName);
        for (String n : tagNames) if (!withNames.contains(n)) withNames.add(n);

        SpannableStringBuilder extra = PostExtras.compose(feelingText, activityText, withNames,
                locationText, 0xFF000000, 0xFF000000);
        if (extra != null) sb.append(extra);
        tvUserName.setText(sb);
        updateFeelingChip(chipDefault);
    }

    private String lowerFirst(String t) {
        if (t == null || t.isEmpty()) return t;
        return Character.toLowerCase(t.charAt(0)) + t.substring(1);
    }

    // "😊 happy" -> {"😊", "happy"}
    private String[] splitEmoji(String t) {
        int i = t.indexOf(' ');
        if (i < 0) return new String[]{"", t};
        return new String[]{t.substring(0, i), t.substring(i + 1)};
    }

    // Feeling/Activity বাটনের লেখা বদলে নির্বাচিত মান দেখানো
    private String chipOriginal = null;

    private void updateFeelingChip(String chipText) {
        TextView tv = findFirstTextView(btnFeeling);
        if (tv == null) return;
        if (chipOriginal == null) chipOriginal = tv.getText().toString();
        tv.setText(chipText != null ? chipText : chipOriginal);
    }

    // Tag/Collab চিপ: বাছাই হলে ধূসর পিল + প্রথম জনের ছবি + নাম ("Farhana Kanom +1")
    private String tagChipOriginal = null;
    private Drawable tagBgOrig = null;
    private ImageView tagAvatar = null;

    private void updateTagChip() {
        TextView tv = findFirstTextView(btnTagCollab);
        if (tv == null) return;
        if (tagChipOriginal == null) tagChipOriginal = tv.getText().toString();
        if (tagBgOrig == null) tagBgOrig = btnTagCollab.getBackground();
        if (collabName == null && tagIds.isEmpty()) {
            tv.setText(tagChipOriginal);
            tv.setTypeface(null, Typeface.BOLD);
            tv.setMaxWidth(Integer.MAX_VALUE);
            btnTagCollab.setBackground(tagBgOrig);
            if (tagAvatar != null) tagAvatar.setVisibility(View.GONE);
            return;
        }
        String firstId = collabId != null ? collabId : tagIds.get(0);
        String firstName = collabName != null ? collabName : tagNames.get(0);
        int total = tagIds.size() + (collabId != null && !tagIds.contains(collabId) ? 1 : 0);
        tv.setText(total > 1 ? firstName + " +" + (total - 1) : firstName);
        tv.setTypeface(null, Typeface.NORMAL);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setMaxWidth(dp(160));
        btnTagCollab.setBackground(selectedChipBg());
        if (tagAvatar == null) {
            tagAvatar = new ImageView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(26), dp(26));
            lp.setMarginEnd(dp(8));
            tagAvatar.setLayoutParams(lp);
            tagAvatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
            tagAvatar.setClipToOutline(true);
            tagAvatar.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline outline) {
                    outline.setOval(0, 0, view.getWidth(), view.getHeight());
                }
            });
            btnTagCollab.addView(tagAvatar, 0);
        }
        tagAvatar.setVisibility(View.VISIBLE);
        AvatarLoader.load(tagAvatar, firstId);
    }

    // Location চিপ: বাছাই হলে ধূসর পিল + কালো পিন + লোকেশন
    private String locChipOriginal = null;
    private Drawable locBgOrig = null;

    private void updateLocationChip() {
        TextView tv = findFirstTextView(btnLocation);
        if (tv == null) return;
        if (locChipOriginal == null) locChipOriginal = tv.getText().toString();
        if (locBgOrig == null) locBgOrig = btnLocation.getBackground();
        if (TextUtils.isEmpty(locationText)) {
            tv.setText(locChipOriginal);
            tv.setTypeface(null, Typeface.BOLD);
            tv.setCompoundDrawables(null, null, null, null);
            tv.setMaxWidth(Integer.MAX_VALUE);
            btnLocation.setBackground(locBgOrig);
            return;
        }
        tv.setText(locationText);
        tv.setTypeface(null, Typeface.NORMAL);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setMaxWidth(dp(190));
        tv.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_pin_black, 0, 0, 0);
        tv.setCompoundDrawablePadding(dp(8));
        btnLocation.setBackground(selectedChipBg());
    }

    // Music চিপ: গান বাছা হলে ধূসর পিল + গানের নোট আইকন + গানের নাম
    private String musicChipOriginal = null;
    private Drawable musicBgOrig = null;

    private void updateMusicChip() {
        TextView tv = findFirstTextView(btnMusic);
        if (tv == null) return;
        if (musicChipOriginal == null) musicChipOriginal = tv.getText().toString();
        if (musicBgOrig == null) musicBgOrig = btnMusic.getBackground();
        if (TextUtils.isEmpty(musicUrl)) {
            tv.setText(musicChipOriginal);
            tv.setTypeface(null, Typeface.BOLD);
            tv.setCompoundDrawables(null, null, null, null);
            tv.setMaxWidth(Integer.MAX_VALUE);
            btnMusic.setBackground(musicBgOrig);
            return;
        }
        tv.setText(musicTitle);
        tv.setTypeface(null, Typeface.NORMAL);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setMaxWidth(dp(170));
        android.graphics.Bitmap mc = MusicApi.cover(MusicApi.idOf(musicUrl));
        if (mc != null) {          // Family Song-এর ছোট ছবি চিপে (২৪dp)
            android.graphics.drawable.BitmapDrawable bd = new android.graphics.drawable.BitmapDrawable(
                    getResources(), android.graphics.Bitmap.createScaledBitmap(mc, dp(24), dp(24), true));
            bd.setBounds(0, 0, dp(24), dp(24));
            tv.setCompoundDrawables(bd, null, null, null);
        } else {
            tv.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_music_note, 0, 0, 0);
        }
        tv.setCompoundDrawablePadding(dp(8));
        btnMusic.setBackground(selectedChipBg());
    }

    private void openPicker() {
        pickLauncher.launch(new Intent(this, MusicPickerActivity.class));
    }

    private void openTrim() {
        Intent i = new Intent(this, MusicTrimActivity.class);
        i.putExtra(MusicPickerActivity.EXTRA_TITLE, pTitle);
        i.putExtra(MusicPickerActivity.EXTRA_ARTIST, pArtist);
        i.putExtra(MusicPickerActivity.EXTRA_COVER, pCover);
        i.putExtra(MusicPickerActivity.EXTRA_URL, pUrl);
        i.putExtra(MusicTrimActivity.EXTRA_START, pStart);
        trimLauncher.launch(i);
    }

    // Music চিপে চাপ: গান আগে থেকে থাকলে সরাসরি কাটার স্ক্রিন, না থাকলে গান বাছাই
    private void onMusicClick() {
        if (!TextUtils.isEmpty(musicUrl)) {
            pTitle = musicTitle;
            pArtist = musicArtist;
            pCover = musicCover;
            pUrl = musicUrl;
            pStart = musicStart;
            openTrim();
        } else {
            openPicker();
        }
    }

    private GradientDrawable selectedChipBg() {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(40));
        g.setColor(0xFFE4E6EB);
        return g;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView findFirstTextView(View v) {
        if (v instanceof TextView) return (TextView) v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                TextView t = findFirstTextView(g.getChildAt(i));
                if (t != null) return t;
            }
        }
        return null;
    }

    // ---------------------------------------------------------
    // প্রোফাইল ছবি (profiles টেবিলের ছবির লিংক থেকে)
    // ---------------------------------------------------------
    private String findPicUrl(JSONObject p) {
        java.util.Iterator<String> it = p.keys();
        while (it.hasNext()) {
            String k = it.next();
            String kl = k.toLowerCase();
            if (!(kl.contains("avatar") || kl.contains("photo") || kl.contains("pic")
                    || kl.contains("image") || kl.contains("img") || kl.equals("dp"))) continue;
            String v = p.optString(k, "");
            if (v.isEmpty() || "null".equals(v)) continue;
            if (v.startsWith("http")) return v;
            // শুধু ফাইলের পাথ থাকলে Supabase Storage এর পাবলিক লিংক ধরা
            String path = v.startsWith("/") ? v.substring(1) : v;
            return SupabaseRest.BASE + "/storage/v1/object/public/" + path;
        }
        return null;
    }

    private boolean avatarLoaded = false;

    // গোল ছবির ফ্রেম
    private void setupAvatarView() {
        imgProfile.setClipToOutline(true);
        imgProfile.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        imgProfile.setScaleType(ImageView.ScaleType.CENTER_CROP);
        imgProfile.setBackgroundColor(0xFFCCD0D5);
    }

    // ছবি না আসা পর্যন্ত (বা ছবি না থাকলে) নামের প্রথম অক্ষর দেখানো
    private void setInitialAvatar(String name) {
        if (avatarLoaded || TextUtils.isEmpty(name)) return;
        int size = 160;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(0xFF1877F2);
        Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG);
        pt.setColor(Color.WHITE);
        pt.setTextSize(84);
        pt.setTextAlign(Paint.Align.CENTER);
        pt.setTypeface(Typeface.DEFAULT_BOLD);
        String letter = name.trim().substring(0, 1).toUpperCase();
        float y = size / 2f - (pt.descent() + pt.ascent()) / 2f;
        c.drawText(letter, size / 2f, y, pt);
        imgProfile.setImageBitmap(bmp);
    }

    private void loadAvatar(final String url) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(20000);
                InputStream in = conn.getInputStream();
                final Bitmap bmp = BitmapFactory.decodeStream(in);
                in.close();
                if (bmp != null) {
                    runOnUiThread(() -> {
                        if (!isFinishing() && !isDestroyed()) {
                            avatarLoaded = true;
                            imgProfile.setImageBitmap(bmp);
                        }
                    });
                }
            } catch (Exception e) {
                Log.w(TAG, "avatar load failed: " + url, e);
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    private void bindViews() {
        btnClose     = findViewById(R.id.btnClose);
        imgProfile   = findViewById(R.id.imgProfile);
        tvUserName   = findViewById(R.id.tvUserName);
        btnMusic     = findViewById(R.id.btnMusic);
        btnTagCollab = findViewById(R.id.btnTagCollab);
        btnLocation  = findViewById(R.id.btnLocation);
        btnFeeling   = findViewById(R.id.btnFeeling);
        btnPrivacy   = findViewById(R.id.btnPrivacy);
        btnGallery   = findViewById(R.id.btnGallery);
        btnGif       = findViewById(R.id.btnGif);
        tvPrivacy    = findViewById(R.id.tvPrivacy);
        etText       = findViewById(R.id.etText);
        etHashtags   = findViewById(R.id.etHashtags);
        btnPost      = findViewById(R.id.btnPost);
    }

    private void setupListeners() {
        btnClose.setOnClickListener(v -> finish());

        btnPost.setOnClickListener(v -> submitPost());

        btnPrivacy.setOnClickListener(v -> togglePrivacy());

        btnFeeling.setOnClickListener(v ->
                feelingLauncher.launch(new Intent(this, FeelingActivity.class)));

        btnMusic.setOnClickListener(v -> onMusicClick());

        btnGallery.setOnClickListener(v -> photoLauncher.launch("image/*"));

        btnTagCollab.setOnClickListener(v -> openTagCollab());

        btnLocation.setOnClickListener(v -> {
            Intent i = new Intent(this, LocationActivity.class);
            i.putExtra(LocationActivity.EXTRA_LOCATION, locationText);
            locationLauncher.launch(i);
        });
    }

    // ---------------------------------------------------------
    // Tag / Collab picker খোলা
    // ---------------------------------------------------------
    private void openTagCollab() {
        Intent i = new Intent(this, TagCollabActivity.class);
        i.putStringArrayListExtra(TagCollabActivity.EXTRA_TAG_IDS, tagIds);
        i.putStringArrayListExtra(TagCollabActivity.EXTRA_TAG_NAMES, tagNames);
        i.putExtra(TagCollabActivity.EXTRA_COLLAB_ID, collabId);
        i.putExtra(TagCollabActivity.EXTRA_COLLAB_NAME, collabName);
        if (inGroup() && memberIds != null) {          // শুধু গ্রুপ সদস্যদের দেখাবে
            i.putStringArrayListExtra(TagCollabActivity.EXTRA_MEMBER_IDS, memberIds);
            i.putStringArrayListExtra(TagCollabActivity.EXTRA_MEMBER_NAMES, memberNames);
        }
        tagLauncher.launch(i);
    }

    // ---------------------------------------------------------
    // Post submit  ->  Supabase "posts" টেবিলে insert
    //                 তারপর ট্যাগ/কোলাব থাকলে RPC দিয়ে যোগ
    // ---------------------------------------------------------
    private void submitPost() {
        final String text = etText.getText().toString().trim();
        if (editId != null) { submitEdit(text); return; }
        boolean hasPhoto = !photos.isEmpty();
        if (TextUtils.isEmpty(text) && !hasPhoto) {
            etText.setError("কিছু লিখুন");
            etText.requestFocus();
            return;
        }
        if (anyPhotoBusy()) {
            Toast.makeText(this, "ছবি প্রস্তুত হচ্ছে, একটু অপেক্ষা করুন", Toast.LENGTH_SHORT).show();
            return;
        }

        final String uid = SupabaseRest.uid();
        if (!SupabaseRest.isLoggedIn() || uid == null) {
            Toast.makeText(this, "লগইন করা নেই, আবার লগইন করুন", Toast.LENGTH_LONG).show();
            return;
        }

        startPostJob(text, uid);
    }

    // ---------------------------------------------------------
    // Edit মোড: পুরনো পোস্টের সব তথ্য বসিয়ে দেয়; Save চাপলে PATCH
    // ---------------------------------------------------------
    private void startEditMode() {
        ((TextView) findViewById(R.id.tvTitle)).setText("Edit post");
        btnPost.setText("Save");
        btnPost.setEnabled(false);
        Toast.makeText(this, "লোড হচ্ছে…", Toast.LENGTH_SHORT).show();
        PostOps.loadForEdit(this, editId, (l, err) -> {
            if (isFinishing() || isDestroyed()) return;
            if (l == null) {
                Toast.makeText(this, err == null ? "পোস্ট লোড করা যায়নি" : err, Toast.LENGTH_LONG).show();
                finish();
                return;
            }
            editLoaded = l;
            etText.setText(l.text);
            etText.setSelection(etText.getText().length());
            etHashtags.setText(l.hashtags);
            if ("friends".equals(l.privacy)) {
                currentPrivacy = "Friends";
                tvPrivacy.setText("👥 Friends");
            } else if ("me".equals(l.privacy)) {
                currentPrivacy = "Only Me";
                tvPrivacy.setText("🔒 Only Me");
            }
            PostEditExtras e = l.ex;
            feelingText = e.feeling;
            activityText = e.activity;
            locationText = e.location;
            musicTitle = e.musicTitle;
            musicArtist = e.musicArtist;
            musicCover = e.musicCover;
            musicUrl = e.musicUrl;
            musicStart = e.musicStart;
            if (e.tagsKnown) {
                tagIds.addAll(e.tagIds);
                tagNames.addAll(e.tagNames);
                collabId = e.collabId;
                collabName = e.collabName;
            } else {
                btnTagCollab.setVisibility(View.GONE);   // SQL (get_post_tag_state) চালানো না থাকলে
            }
            photos.addAll(l.photos);
            layoutVariant = l.variant;
            refreshHeader();
            updateTagChip();
            updateLocationChip();
            updateMusicChip();
            refreshPreview();
            btnPost.setEnabled(true);
        });
    }

    private void submitEdit(final String text) {
        if (editLoaded == null) return;
        if (anyPhotoBusy()) {
            Toast.makeText(this, "ছবি প্রস্তুত হচ্ছে, একটু অপেক্ষা করুন", Toast.LENGTH_SHORT).show();
            return;
        }
        PostEditExtras ex = new PostEditExtras();
        ex.feeling = feelingText;
        ex.activity = activityText;
        ex.location = locationText;
        ex.musicTitle = musicTitle;
        ex.musicArtist = musicArtist;
        ex.musicCover = musicCover;
        ex.musicUrl = musicUrl;
        ex.musicStart = musicStart;
        ex.tagsKnown = editLoaded.ex.tagsKnown;
        ex.tagIds = new ArrayList<>(tagIds);
        ex.tagNames = new ArrayList<>(tagNames);
        ex.collabId = collabId;
        ex.collabName = collabName;

        JSONObject more = new JSONObject();
        PostOps.putCol(more, "hashtags", etHashtags.getText().toString().trim(), editLoaded.hashtags);
        String pv = "Friends".equals(currentPrivacy) ? "friends" : "Only Me".equals(currentPrivacy) ? "me" : "public";
        if (!pv.equals(editLoaded.privacy)) {
            try { more.put("privacy", pv); } catch (JSONException ignored) { }
        }

        btnPost.setEnabled(false);
        btnPost.setText("Saving…");
        Post p = new Post();
        p.id = editId;
        p.text = editLoaded.text;
        PostOps.savePostEdit(this, p, photos, layoutVariant, text, editLoaded.ex, ex,
                editLoaded.hadImages, more, err -> {
            if (isFinishing() || isDestroyed()) return;
            if (err == null) {
                finishOk();
            } else {
                btnPost.setEnabled(true);
                btnPost.setText("Save");
            }
        });
    }

    private boolean anyPhotoBusy() {
        for (PostPhoto p : photos) if (p.comp == null) return true;
        return false;
    }

    /** পোস্ট ব্যাকগ্রাউন্ডে পাঠিয়ে সাথে সাথে ফিডে ফেরা; ফিডের উপরে "Posting…" বার দেখায় */
    private void startPostJob(String text, String uid) {
        if (PostQueue.isActive()) {
            Toast.makeText(this, "আগের পোস্ট এখনও আপলোড হচ্ছে, একটু অপেক্ষা করুন", Toast.LENGTH_SHORT).show();
            return;
        }
        PostQueue.Job j = new PostQueue.Job();
        j.uid = uid;
        j.authorName = authorName;
        j.text = text;
        j.hashtags = etHashtags.getText().toString().trim();
        j.privacy = privacyValue();
        j.groupId = groupId;
        j.feeling = feelingText;
        j.activity = activityText;
        j.location = locationText;
        j.musicTitle = musicTitle;
        j.musicArtist = musicArtist;
        j.musicCover = musicCover;
        j.musicUrl = musicUrl;
        j.musicStart = musicStart;
        j.photos.addAll(photos);
        j.variant = layoutVariant;
        j.tagIds.addAll(tagIds);
        j.collabId = collabId;
        btnPost.setEnabled(false);
        PostQueue.start(getApplicationContext(), j);
        if (!Net.isOnline()) {
            Toast.makeText(this, "ইন্টারনেট নেই — Draft হিসেবে সেভ হয়েছে, নেট এলেই পোস্ট হবে", Toast.LENGTH_LONG).show();
        }
        finishOk();
    }

    // ---------------------------------------------------------
    // Photo: প্রিভিউ বক্স (লেখার নিচে), বাছাই, কমপ্রেস, মুছে ফেলা
    // ---------------------------------------------------------
    private void buildPhotoBox() {
        photoBox = new FrameLayout(this);
        photoBox.setVisibility(View.GONE);
        photoBox.setBackgroundColor(0xFFE4E6EB);
        photoBox.setClipToOutline(true);
        photoBox.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(14));
            }
        });
        photoPreviewImg = new ImageView(this);
        photoPreviewImg.setAdjustViewBounds(true);
        photoPreviewImg.setScaleType(ImageView.ScaleType.FIT_CENTER);
        photoBox.addView(photoPreviewImg, new FrameLayout.LayoutParams(-1, -2));

        // "Add media" (বামে উপরে)
        LinearLayout add = new LinearLayout(this);
        add.setOrientation(LinearLayout.HORIZONTAL);
        add.setGravity(Gravity.CENTER_VERTICAL);
        add.setPadding(dp(12), dp(10), dp(16), dp(10));
        GradientDrawable ab = new GradientDrawable();
        ab.setColor(0xCC6B6B6B);
        ab.setCornerRadius(dp(10));
        add.setBackground(ab);
        ImageView ai = new ImageView(this);
        ai.setImageDrawable(Icons.addMedia(this, dp(24), Color.WHITE));
        add.addView(ai, new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView at = new TextView(this);
        at.setText("Add media");
        at.setTextColor(Color.WHITE);
        at.setTextSize(17);
        at.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams atp = new LinearLayout.LayoutParams(-2, -2);
        atp.leftMargin = dp(8);
        add.addView(at, atp);
        add.setOnClickListener(v -> photoLauncher.launch("image/*"));
        FrameLayout.LayoutParams addp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        addp.setMargins(dp(12), dp(12), 0, 0);
        photoBox.addView(add, addp);

        // গ্রিড (সাজানো বদল) ও পেন্সিল (Edit স্ক্রিন) — ডানে উপরে
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.HORIZONTAL);
        right.addView(circleBtn(Icons.grid(this, dp(24), Color.WHITE), v -> {
            layoutVariant = 1 - layoutVariant;
            refreshPreview();
        }));
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(dp(50), dp(50));
        ep.leftMargin = dp(10);
        right.addView(circleBtn(Icons.pencil(this, dp(24), Color.WHITE), v -> {
            if (anyPhotoBusy()) {
                Toast.makeText(this, "ছবি প্রস্তুত হচ্ছে, একটু অপেক্ষা করুন", Toast.LENGTH_SHORT).show();
                return;
            }
            PhotoListDialog.show(this, photos, this::refreshPreview);
        }), ep);
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        rp.setMargins(0, dp(12), dp(12), 0);
        photoBox.addView(right, rp);

        ViewGroup parent = (ViewGroup) etText.getParent();
        if (parent instanceof LinearLayout) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(dp(12), dp(8), dp(12), dp(8));
            parent.addView(photoBox, parent.indexOfChild(etText) + 1, lp);
        } else {
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
            lp.setMargins(dp(12), 0, dp(12), dp(130));
            addContentView(photoBox, lp);
        }
    }

    private View circleBtn(android.graphics.drawable.Drawable icon, View.OnClickListener l) {
        ImageView b = new ImageView(this);
        b.setImageDrawable(icon);
        b.setPadding(dp(13), dp(13), dp(13), dp(13));
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0xCC6B6B6B);
        b.setBackground(g);
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(50), dp(50)));
        return b;
    }

    /** বাছা ছবিগুলোর গ্রিড-প্রিভিউ নতুন করে আঁকে */
    private void refreshPreview() {
        int n = photos.size();
        if (n == 0) {
            photoBox.setVisibility(View.GONE);
            return;
        }
        photoBox.setVisibility(View.VISIBLE);
        if (n == 1 && photos.get(0).comp != null) {
            photoPreviewImg.setImageBitmap(photos.get(0).comp.preview);
            return;
        }
        int shown = Math.min(n, PhotoGrid.MAX_TILES);
        Bitmap[] tiles = new Bitmap[shown];
        for (int i = 0; i < shown; i++) {
            PostPhoto p = photos.get(i);
            tiles[i] = p.comp == null ? null : p.comp.preview;
        }
        photoPreviewImg.setImageBitmap(PhotoGrid.compose(tiles, n, layoutVariant));
    }

    private void addPhotos(java.util.List<Uri> uris) {
        int room = MAX_PHOTOS - photos.size();
        if (room <= 0) {
            Toast.makeText(this, "সর্বোচ্চ " + MAX_PHOTOS + "টি ছবি দেওয়া যাবে", Toast.LENGTH_SHORT).show();
            return;
        }
        if (uris.size() > room) {
            Toast.makeText(this, "সর্বোচ্চ " + MAX_PHOTOS + "টি ছবি; প্রথম " + room + "টি নেওয়া হলো",
                    Toast.LENGTH_SHORT).show();
            uris = uris.subList(0, room);
        }
        for (Uri u : uris) {
            final PostPhoto it = new PostPhoto();
            photos.add(it);
            PhotoUploader.compressAsync(this, u, (c, err) -> {
                if (isFinishing() || isDestroyed()) return;
                if (!photos.contains(it)) return;     // এর মধ্যে বাদ দেওয়া হয়েছে
                if (c == null) {
                    photos.remove(it);
                    refreshPreview();
                    Toast.makeText(this, err == null ? "ছবি প্রস্তুত করা যায়নি" : err, Toast.LENGTH_LONG).show();
                    return;
                }
                it.comp = c;
                refreshPreview();
            });
        }
        refreshPreview();
    }

    private void finishOk() {
        setResult(RESULT_OK);
        finish();
    }

    // ডাটাবেসের ডিফল্ট 'public' (ছোট হাতের), তাই বাকিগুলোও ছোট হাতের
    private String privacyValue() {
        if (inGroup()) return "public";   // দৃশ্যমানতা গ্রুপের প্রাইভেসি দিয়ে ঠিক হয় (ভিউ-তে)
        switch (currentPrivacy) {
            case "Friends": return "friends";
            case "Only Me": return "only_me";
            default:        return "public";
        }
    }

    // ---------------------------------------------------------
    // Privacy toggle
    // ---------------------------------------------------------
    private void togglePrivacy() {
        if (inGroup()) {
            Toast.makeText(this, groupPublic
                    ? "Public group: গ্রুপের সদস্যরা দেখবে, আর নিউজ ফিডেও আসবে"
                    : "Private group: শুধু গ্রুপের সদস্যরা দেখবে", Toast.LENGTH_SHORT).show();
            return;
        }
        switch (currentPrivacy) {
            case "Public":
                currentPrivacy = "Friends";
                tvPrivacy.setText("👥 Friends");
                break;
            case "Friends":
                currentPrivacy = "Only Me";
                tvPrivacy.setText("🔒 Only Me");
                break;
            default:
                currentPrivacy = "Public";
                tvPrivacy.setText("🌐 Public");
                break;
        }
    }
}
