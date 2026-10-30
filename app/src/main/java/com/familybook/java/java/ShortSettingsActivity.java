package com.familybook.app;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.View;
import android.widget.CompoundButton;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Short Settings ফর্ম — ভিডিও বেছে নেওয়ার পরের পর্দা।
 * Intent data = ভিডিওর Uri। ভিডিও কাটা/থাম্বনেইল/আপলোড ShortUploader ব্যাকগ্রাউন্ডে করে;
 * "Share now" চাপলে ফর্মের তথ্য ShortUploader.submit(...)-এ যায় এবং বাকি কাজ ওখানেই শেষ হয়।
 */
public class ShortSettingsActivity extends BaseActivity {

    private static final int MAX_HASH = 5;
    private static final int MAX_TITLE = 255;
    private static final int MAX_DESC = 10000;
    private static final int BLUE = 0xFF0866FF;
    private static final int REQ_PLAYLIST = 701;
    private static final int REQ_GALLERY = 702;
    private static final int REQ_LOCATION = 703;
    private static final int REQ_GROUPS = 704;
    private static final int REQ_COLLAB = 705;

    private EditText etTitle, etDescription, etHash;
    private TextView tvTitleCount, tvDescCount, tvHashCount, tvAudienceStatus, tvScheduleStatus,
            tvCaptionStatus, tvLocationStatus, tvGroupsStatus, tvStoryStatus, tvTopicsStatus,
            tvAiBadge, tvDuration, btnShareNow;
    private ImageView ivThumbnail;
    private Switch switchAiLabel;
    private TextView tvPlaylistName;
    private ImageView ivPlaylistArrow, ivPlaylistClear, ivThumbEdit;

    // ফর্মের মান
    private final List<String> playlists = new ArrayList<>();
    private final List<String> topics = new ArrayList<>();
    private String location = "";
    private boolean shareGroups = false;
    private final ArrayList<String> groupIds = new ArrayList<>();
    private final ArrayList<String> groupNames = new ArrayList<>();
    private final ArrayList<String> collabIds = new ArrayList<>();
    private final ArrayList<String> collabNames = new ArrayList<>();
    private TextView tvCollabStatus;
    private boolean shareStory = false;
    private int audience = 0;                 // 0 Public, 1 Friends, 2 Only me
    private boolean captions = true;
    private long scheduleAt = 0;              // 0 = এখনই পোস্ট
    private boolean submitted = false;

    // ---- গানের তথ্য (ShortEditActivity থেকে আসে) — Short-এর সাথে সার্ভারে সেভ হয়
    private String musicTitle, musicArtist, musicCover, musicUrl;
    private int musicStart = 0;

    // ---- এডিট মোড: আপলোডের এই পেজই খোলে; শুধু থাম্বনেইল / টাইটেল / ডেসক্রিপশন / হ্যাশট্যাগ / AI লেবেল বদলানো যায়
    private boolean editMode = false;
    private String editThumbKey, editShortId, newThumbKey;
    private float editRatio = 0.5625f;
    private byte[] editThumbBytes;
    private boolean editLoaded = false, editSaving = false;

    private static final String[] AUDIENCE_LABEL = {"Public", "Friends", "Only me"};
    private static final String[] AUDIENCE_VALUE = {"public", "friends", "only_me"};
    private static final String[] TOPIC_LIST = {"Funny Video", "Motivational", "Religious",
            "Warning video", "Tips & Trick", "Tech", "Science"};

    private final ShortUploader.Listener uploadListener = this::onUploadChanged;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_short_settings);

        editThumbKey = getIntent().getStringExtra("edit_thumb_key");
        editMode = editThumbKey != null && !editThumbKey.isEmpty();
        Uri video = getIntent().getData();
        if (!editMode && video == null) {
            Toast.makeText(this, "Video not found", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        bindViews();
        setupTextWatchers();
        setupClicks();
        refreshShareButton();

        if (editMode) {
            setupEditMode();
            return;
        }
        musicUrl = getIntent().getStringExtra(MusicPickerActivity.EXTRA_URL);
        if (musicUrl != null && !musicUrl.isEmpty()) {
            musicTitle = getIntent().getStringExtra(MusicPickerActivity.EXTRA_TITLE);
            musicArtist = getIntent().getStringExtra(MusicPickerActivity.EXTRA_ARTIST);
            musicCover = getIntent().getStringExtra(MusicPickerActivity.EXTRA_COVER);
            musicStart = getIntent().getIntExtra(MusicTrimActivity.EXTRA_START, 0);
        } else {
            musicUrl = null;
        }

        // ফর্ম পূরণের সময়েই ব্যাকগ্রাউন্ডে ভিডিও প্রস্তুত + আপলোড শুরু
        ShortUploader.start(this, video);
    }

    // ------------------------------------------------------------ এডিট মোড

    private void setupEditMode() {
        // এডিটে বাকি সবকিছু (প্লেলিস্ট, লোকেশন, গ্রুপ, স্টোরি, অডিয়েন্স, শিডিউল, ক্যাপশন, কোলাব, টপিক, ড্রাফট) লুকানো
        View ai = findViewById(R.id.layoutAiLabel);
        ViewGroup parent = (ViewGroup) ai.getParent();
        for (int i = 0; i < parent.getChildCount(); i++) {
            View c = parent.getChildAt(i);
            boolean keep = c == ai || c.findViewById(R.id.etTitle) != null || c.findViewById(R.id.ivThumbnail) != null
                    || c.findViewById(R.id.layoutAiLabel) != null;
            if (!keep) c.setVisibility(View.GONE);
        }
        int[] hide = {R.id.layoutPlaylist, R.id.layoutLocation, R.id.layoutShareGroups, R.id.layoutShareStory,
                R.id.layoutAudience, R.id.layoutSchedule, R.id.layoutCaptions, R.id.layoutCollab,
                R.id.layoutTopics, R.id.btnDraft, R.id.tvDuration};
        for (int id : hide) {
            View v = findViewById(id);
            if (v != null) v.setVisibility(View.GONE);
        }
        btnShareNow.setText("Save");

        SupabaseRest.call("GET", "/rest/v1/shorts?thumb_key=eq." + Uri.encode(editThumbKey)
                + "&select=id,title,description,hashtags,ai_label,thumb_key,width,height&limit=1", null, null, true, r -> {
            if (isFinishing()) return;
            try {
                JSONArray a = new JSONArray(r.body);
                if (!r.ok() || a.length() == 0) throw new Exception("none");
                JSONObject o = a.getJSONObject(0);
                editShortId = o.optString("id", null);
                etTitle.setText(o.isNull("title") ? "" : o.optString("title", ""));
                etDescription.setText(o.isNull("description") ? "" : o.optString("description", ""));
                Object h = o.opt("hashtags");
                StringBuilder sb = new StringBuilder();
                if (h instanceof JSONArray) {
                    JSONArray ha = (JSONArray) h;
                    for (int i = 0; i < ha.length(); i++) sb.append(sb.length() > 0 ? " " : "").append('#').append(ha.optString(i));
                } else if (h != null && !JSONObject.NULL.equals(h)) {
                    sb.append(h.toString());
                }
                etHash.setText(sb.toString());
                switchAiLabel.setChecked(o.optBoolean("ai_label", false));
                int w = o.optInt("width", 0), hh = o.optInt("height", 0);
                if (w > 0 && hh > 0) editRatio = (float) w / hh;
                editLoaded = true;
            } catch (Exception e) {
                Toast.makeText(this, "Couldn't load this Short", Toast.LENGTH_SHORT).show();
                finish();
            }
        });
        AvatarLoader.loadKey(ivThumbnail, editThumbKey);
    }

    /** থাম্বনেইল বদলানো (এডিট মোডে আলাদা রাখি; নতুন আপলোডে ShortUploader-এ যায়) */
    private void applyThumb(Bitmap b) {
        if (b == null) return;
        if (!editMode) { ShortUploader.setThumb(b); return; }
        try {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.JPEG, 88, bo);
            editThumbBytes = bo.toByteArray();
            ivThumbnail.setImageBitmap(b);
        } catch (Throwable ignored) { }
    }

    private void saveEdit() {
        if (editSaving) return;
        if (!editLoaded || editShortId == null) { Toast.makeText(this, "Loading…", Toast.LENGTH_SHORT).show(); return; }
        final String title = etTitle.getText().toString().trim();
        if (title.isEmpty()) { Toast.makeText(this, "Enter a title", Toast.LENGTH_SHORT).show(); etTitle.requestFocus(); return; }
        final List<String> tags = parseHashtags();
        if (tags.size() > MAX_HASH) {
            Toast.makeText(this, "Maximum " + MAX_HASH + " hashtags allowed", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!Net.check(this)) return;
        editSaving = true;
        btnShareNow.setAlpha(0.5f);
        if (editThumbBytes != null) {
            String tn = System.currentTimeMillis() + "_thumb.jpg";
            Media.upload(editThumbBytes, "post", tn, "image/jpeg", (key, err) -> {
                if (isFinishing()) return;
                if (err != null) { editSaving = false; refreshShareButton(); Toast.makeText(this, err, Toast.LENGTH_SHORT).show(); return; }
                newThumbKey = key;
                patchShort(title, tags);
            });
        } else {
            patchShort(title, tags);
        }
    }

    private void patchShort(final String title, final List<String> tags) {
        try {
            JSONObject row = new JSONObject()
                    .put("title", title)
                    .put("description", etDescription.getText().toString().trim())
                    .put("hashtags", new JSONArray(tags))
                    .put("ai_label", switchAiLabel.isChecked());
            if (newThumbKey != null) row.put("thumb_key", newThumbKey);
            final String me = SupabaseRest.uid();
            SupabaseRest.call("PATCH", "/rest/v1/shorts?id=eq." + editShortId + "&user_id=eq." + me,
                    row.toString(), "return=minimal", true, r -> {
                if (isFinishing()) return;
                if (!r.ok()) {
                    editSaving = false; refreshShareButton();
                    Toast.makeText(this, SupabaseRest.errorText(r), Toast.LENGTH_SHORT).show();
                    return;
                }
                // ফিড/প্রোফাইল/গ্রুপের "uploaded a short" পোস্টগুলোর লেখা ও থাম্বনেইলও মেলাই
                try {
                    JSONObject pb = new JSONObject().put("text", title).put("edited", true);
                    if (newThumbKey != null) pb.put("image_key", newThumbKey);
                    SupabaseRest.call("PATCH", "/rest/v1/posts?image_key=eq." + Uri.encode(editThumbKey)
                            + "&user_id=eq." + me + "&activity=eq."
                            + Uri.encode(ProfilePhotoPost.MARK + ShortUploader.SHORT_PHRASE),
                            pb.toString(), "return=minimal", true, pr -> finishEdit(title));
                } catch (Exception e) { finishEdit(title); }
            });
        } catch (Exception e) {
            editSaving = false; refreshShareButton();
            Toast.makeText(this, "Couldn't save", Toast.LENGTH_SHORT).show();
        }
    }

    private void finishEdit(String title) {
        ShortStore.updateLocal(editShortId, title, newThumbKey);
        Toast.makeText(this, "Short updated", Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        PostOps.refreshHost(this);
        finish();
    }

    private void bindViews() {
        etTitle = findViewById(R.id.etTitle);
        etDescription = findViewById(R.id.etDescription);
        etHash = findViewById(R.id.etHash);
        tvTitleCount = findViewById(R.id.tvTitleCount);
        tvDescCount = findViewById(R.id.tvDescCount);
        tvHashCount = findViewById(R.id.tvHashCount);
        tvAudienceStatus = findViewById(R.id.tvAudienceStatus);
        tvScheduleStatus = findViewById(R.id.tvScheduleStatus);
        tvCaptionStatus = findViewById(R.id.tvCaptionStatus);
        tvLocationStatus = findViewById(R.id.tvLocationStatus);
        tvGroupsStatus = findViewById(R.id.tvGroupsStatus);
        tvStoryStatus = findViewById(R.id.tvStoryStatus);
        tvCollabStatus = findViewById(R.id.tvCollabStatus);
        tvTopicsStatus = findViewById(R.id.tvTopicsStatus);
        tvAiBadge = findViewById(R.id.tvAiBadge);
        tvDuration = findViewById(R.id.tvDuration);
        ivThumbnail = findViewById(R.id.ivThumbnail);
        btnShareNow = findViewById(R.id.btnShareNow);
        switchAiLabel = findViewById(R.id.switchAiLabel);
        tvPlaylistName = findViewById(R.id.tvPlaylistName);
        ivPlaylistArrow = findViewById(R.id.ivPlaylistArrow);
        ivPlaylistClear = findViewById(R.id.ivPlaylistClear);
        ivThumbEdit = findViewById(R.id.ivThumbEdit);

        etTitle.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MAX_TITLE)});
        etDescription.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MAX_DESC)});
    }

    private void setupClicks() {
        findViewById(R.id.btnBack).setOnClickListener(v -> onBackPressed());

        findViewById(R.id.layoutPlaylist).setOnClickListener(v -> openPlaylistPicker());
        ivPlaylistClear.setOnClickListener(v -> applyPlaylist(""));
        ivThumbEdit.setOnClickListener(v -> showThumbChooser());
        findViewById(R.id.layoutLocation).setOnClickListener(v -> openLocationPicker());
        findViewById(R.id.layoutShareGroups).setOnClickListener(v -> {
            Intent it = new Intent(this, ShortPickActivity.class);
            it.putExtra(ShortPickActivity.EXTRA_MODE, ShortPickActivity.MODE_GROUPS);
            it.putStringArrayListExtra(ShortPickActivity.EXTRA_IDS, groupIds);
            it.putStringArrayListExtra(ShortPickActivity.EXTRA_NAMES, groupNames);
            startActivityForResult(it, REQ_GROUPS);
        });
        findViewById(R.id.layoutShareStory).setOnClickListener(v -> {
            shareStory = !shareStory;
            tvStoryStatus.setText(shareStory
                    ? "Sharing to your story · Public"
                    : "Story privacy is set to Public.");
            tvStoryStatus.setTextColor(shareStory ? BLUE : 0xFF666666);
        });
        findViewById(R.id.layoutAiLabel).setOnClickListener(v -> switchAiLabel.toggle());
        switchAiLabel.setOnCheckedChangeListener((CompoundButton b, boolean on) ->
                tvAiBadge.setVisibility(on ? View.VISIBLE : View.GONE));

        findViewById(R.id.layoutAudience).setOnClickListener(v -> showAudienceDialog());
        findViewById(R.id.layoutSchedule).setOnClickListener(v -> showScheduleDialog());
        findViewById(R.id.layoutCaptions).setOnClickListener(v -> showCaptionsDialog());
        findViewById(R.id.layoutCollab).setOnClickListener(v -> {
            Intent it = new Intent(this, ShortPickActivity.class);
            it.putExtra(ShortPickActivity.EXTRA_MODE, ShortPickActivity.MODE_COLLAB);
            it.putStringArrayListExtra(ShortPickActivity.EXTRA_IDS, collabIds);
            it.putStringArrayListExtra(ShortPickActivity.EXTRA_NAMES, collabNames);
            startActivityForResult(it, REQ_COLLAB);
        });
        findViewById(R.id.layoutTopics).setOnClickListener(v -> showTopicsDialog());

        findViewById(R.id.btnDraft).setOnClickListener(v ->
                Toast.makeText(this, "Draft feature coming soon", Toast.LENGTH_SHORT).show());

        btnShareNow.setOnClickListener(v -> onShareNow());
    }

    // ------------------------------------------------------------ লাইফসাইকেল

    @Override
    protected void onStart() {
        super.onStart();
        if (editMode) return;
        ShortUploader.addListener(uploadListener);
        onUploadChanged();
    }

    @Override
    protected void onStop() {
        super.onStop();
        ShortUploader.removeListener(uploadListener);
    }

    @Override
    public void onBackPressed() {
        if (!editMode && !submitted && ShortUploader.state() != ShortUploader.State.IDLE) {
            new AlertDialog.Builder(this)
                    .setMessage("Discard this video and go back?")
                    .setPositiveButton("Discard", (d, w) -> {
                        ShortUploader.cancel();
                        super.onBackPressed();
                    })
                    .setNegativeButton("Stay", null)
                    .show();
            return;
        }
        super.onBackPressed();
    }

    /** ShortUploader-এর অবস্থা বদলালে: থাম্বনেইল/দৈর্ঘ্য দেখানো, ত্রুটি ধরা */
    private void onUploadChanged() {
        if (submitted || editMode) return;
        if (ShortUploader.thumb() != null) {
            ivThumbnail.setImageBitmap(ShortUploader.thumb());
            long s = ShortUploader.durationMs() / 1000;
            tvDuration.setText(String.format(Locale.US, "%d:%02d", s / 60, s % 60));
            tvDuration.setVisibility(View.VISIBLE);
        }
        if (ShortUploader.state() == ShortUploader.State.FAILED && !ShortUploader.isSubmitted()) {
            // প্রস্তুতির ধাপেই ব্যর্থ (যেমন ৩ সেকেন্ডের কম) হলে আর এগোনো যায় না।
            // (ত্রুটির বার্তা ShortUploader নিজেই Toast-এ দেখিয়েছে)
            if (ShortUploader.thumb() == null) {
                ShortUploader.cancel();
                finish();
            }
        }
    }

    // ------------------------------------------------------------ ইনপুট

    private void setupTextWatchers() {
        etTitle.addTextChangedListener(new SimpleWatcher() {
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                tvTitleCount.setText(s.length() + "/" + MAX_TITLE);
                refreshShareButton();
            }
        });
        etDescription.addTextChangedListener(new SimpleWatcher() {
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                tvDescCount.setText(s.length() + "/" + MAX_DESC);
            }
        });
        etHash.addTextChangedListener(new SimpleWatcher() {
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                int n = parseHashtags().size();
                tvHashCount.setText(String.format(Locale.US, "%d/%02d", n, MAX_HASH));
                tvHashCount.setTextColor(n > MAX_HASH ? 0xFFE41E3F : 0xFF888888);
            }
        });
    }

    private abstract static class SimpleWatcher implements TextWatcher {
        @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
        @Override public void afterTextChanged(Editable s) { }
    }

    /** "#nature #fun, vlog" → [nature, fun, vlog] (ডুপ্লিকেট বাদ, # ও চিহ্ন বাদ) */
    private List<String> parseHashtags() {
        Set<String> set = new LinkedHashSet<>();
        for (String t : etHash.getText().toString().split("[\\s,]+")) {
            String c = t.replaceAll("[^\\p{L}\\p{N}_]", "");
            if (!c.isEmpty()) set.add(c);
        }
        return new ArrayList<>(set);
    }

    private void refreshShareButton() {
        boolean ok = !etTitle.getText().toString().trim().isEmpty();
        btnShareNow.setAlpha(ok ? 1f : 0.5f);   // ফর্ম অসম্পূর্ণ হলে ধূসর-ঘেঁষা
    }

    // ------------------------------------------------------------ Share now

    private void onShareNow() {
        if (editMode) { saveEdit(); return; }
        if (etTitle.getText().toString().trim().isEmpty()) {
            Toast.makeText(this, "Enter a title", Toast.LENGTH_SHORT).show();
            etTitle.requestFocus();
            return;
        }
        List<String> tags = parseHashtags();
        if (tags.size() > MAX_HASH) {
            Toast.makeText(this, "Maximum " + MAX_HASH + " hashtags allowed", Toast.LENGTH_SHORT).show();
            return;
        }
        if (scheduleAt != 0 && scheduleAt <= System.currentTimeMillis()) {
            Toast.makeText(this, "Schedule time must be in the future", Toast.LENGTH_SHORT).show();
            return;
        }
        if (ShortUploader.state() == ShortUploader.State.IDLE) {
            Toast.makeText(this, "Video isn't ready, try again", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            JSONObject m = new JSONObject();
            m.put("title", etTitle.getText().toString().trim());
            m.put("description", etDescription.getText().toString().trim());
            m.put("hashtags", new JSONArray(tags));
            m.put("playlists", new JSONArray(playlists));
            m.put("location", location);
            m.put("share_groups", !groupIds.isEmpty());
            m.put("group_ids", new JSONArray(groupIds));
            m.put("collab_ids", new JSONArray(collabIds));
            m.put("share_story", shareStory);
            m.put("ai_label", switchAiLabel.isChecked());
            m.put("audience", AUDIENCE_VALUE[audience]);
            m.put("captions", captions);
            m.put("topics", new JSONArray(topics));
            if (musicUrl != null) {
                m.put("music_title", musicTitle == null ? "" : musicTitle);
                m.put("music_artist", musicArtist == null ? "" : musicArtist);
                m.put("music_cover", musicCover == null ? "" : musicCover);
                m.put("music_url", musicUrl);
                m.put("music_start", musicStart);
                m.put("music_len", MusicTrimActivity.CLIP_MS);
            }
            if (scheduleAt != 0) m.put("scheduled_at", isoUtc(scheduleAt));
            submitted = true;
            ShortUploader.submit(m);
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't submit the form", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "Uploading…", Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);   // ShortCreateActivity-ও বন্ধ হয়ে প্রোফাইলে ফিরবে; প্রোগ্রেস Shorts ট্যাবে
        finish();
    }

    private static String isoUtc(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(ms));
    }

    // ------------------------------------------------------------ ডায়ালগ

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    // ------------------------------------------------------------ প্লেলিস্ট

    private void openPlaylistPicker() {
        Intent it = new Intent(this, ShortPlaylistActivity.class);
        it.putExtra(ShortPlaylistActivity.EXTRA_SELECTED, playlists.isEmpty() ? "" : playlists.get(0));
        startActivityForResult(it, REQ_PLAYLIST);
    }

    /** name খালি = প্লেলিস্ট বাদ। বেছে নিলে ৫ নম্বর ছবির মতো: নামটা নিচে, ডানে X */
    private void applyPlaylist(String name) {
        playlists.clear();
        if (name != null && !name.isEmpty()) playlists.add(name);
        boolean has = !playlists.isEmpty();
        tvPlaylistName.setText(has ? name : "");
        tvPlaylistName.setVisibility(has ? View.VISIBLE : View.GONE);
        ivPlaylistClear.setVisibility(has ? View.VISIBLE : View.GONE);
        ivPlaylistArrow.setVisibility(has ? View.GONE : View.VISIBLE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode == REQ_LOCATION) {
            location = data.getStringExtra(LocationActivity.EXTRA_LOCATION);
            if (location == null) location = "";
            tvLocationStatus.setText(location.isEmpty() ? "Not set" : location);
            tvLocationStatus.setTextColor(location.isEmpty() ? 0xFF666666 : BLUE);
        } else if (requestCode == REQ_GROUPS) {
            groupIds.clear(); groupNames.clear();
            ArrayList<String> ids = data.getStringArrayListExtra(ShortPickActivity.EXTRA_IDS);
            ArrayList<String> names = data.getStringArrayListExtra(ShortPickActivity.EXTRA_NAMES);
            if (ids != null && names != null) { groupIds.addAll(ids); groupNames.addAll(names); }
            shareGroups = !groupIds.isEmpty();
            tvGroupsStatus.setText(shareGroups ? "Sharing to: " + android.text.TextUtils.join(", ", groupNames)
                    : "You can now automatically share to relevant groups.");
            tvGroupsStatus.setTextColor(shareGroups ? BLUE : 0xFF666666);
        } else if (requestCode == REQ_COLLAB) {
            collabIds.clear(); collabNames.clear();
            ArrayList<String> ids = data.getStringArrayListExtra(ShortPickActivity.EXTRA_IDS);
            ArrayList<String> names = data.getStringArrayListExtra(ShortPickActivity.EXTRA_NAMES);
            if (ids != null && names != null) { collabIds.addAll(ids); collabNames.addAll(names); }
            tvCollabStatus.setText(collabIds.isEmpty() ? "Share credit for your short with up to 5 collaborators."
                    : "Invited: " + android.text.TextUtils.join(", ", collabNames));
            tvCollabStatus.setTextColor(collabIds.isEmpty() ? 0xFF666666 : BLUE);
        } else if (requestCode == REQ_PLAYLIST) {
            applyPlaylist(data.getStringExtra(ShortPlaylistActivity.EXTRA_NAME));
        } else if (requestCode == REQ_GALLERY && data.getData() != null) {
            useGalleryImage(data.getData());
        }
    }

    // ------------------------------------------------------------ থাম্বনেইল (পেন্সিল)

    private void showThumbChooser() {
        if (editMode) {      // এডিটে ভিডিও ফাইল হাতে নেই — গ্যালারি থেকে নতুন থাম্বনেইল
            Intent it = new Intent(Intent.ACTION_GET_CONTENT);
            it.setType("image/*");
            startActivityForResult(Intent.createChooser(it, "Select image"), REQ_GALLERY);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Thumbnail")
                .setItems(new String[]{"Select from video", "From Gallery"}, (d, which) -> {
                    if (which == 0) {
                        showVideoFramePicker();
                    } else {
                        Intent it = new Intent(Intent.ACTION_GET_CONTENT);
                        it.setType("image/*");
                        startActivityForResult(Intent.createChooser(it, "Select image"), REQ_GALLERY);
                    }
                })
                .show();
    }

    /** স্লাইডার টেনে ভিডিওর যেকোনো ফ্রেম থাম্বনেইল হিসেবে বেছে নেওয়া */
    private void showVideoFramePicker() {
        final File f = ShortUploader.videoFile();
        final long durMs = ShortUploader.durationMs();
        if (f == null || durMs <= 0) {
            Toast.makeText(this, "Video is still being prepared, try again shortly", Toast.LENGTH_SHORT).show();
            return;
        }
        final MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(f.getAbsolutePath());
        } catch (Throwable e) {
            Toast.makeText(this, "Couldn't open the video", Toast.LENGTH_SHORT).show();
            return;
        }

        final ImageView iv = new ImageView(this);
        iv.setBackgroundColor(0xFF000000);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        final SeekBar sb = new SeekBar(this);
        sb.setMax((int) Math.max(1, durMs / 100));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), 0);
        // ফ্রেমের ছবির মাপ বর্তমান থাম্বনেইলের অনুপাতে
        Bitmap cur0 = ShortUploader.thumb();
        float ratio = cur0 != null && cur0.getHeight() > 0 ? (float) cur0.getWidth() / cur0.getHeight() : 0.5625f;
        int h = dp(300);
        box.addView(iv, new LinearLayout.LayoutParams(Math.round(h * ratio), h));
        ((LinearLayout.LayoutParams) iv.getLayoutParams()).gravity = android.view.Gravity.CENTER_HORIZONTAL;
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(12);
        box.addView(sb, sp);

        final Bitmap[] chosen = new Bitmap[1];
        final int[] req = {0};
        final ExecutorService ex = Executors.newSingleThreadExecutor();
        final Runnable[] load = new Runnable[1];
        load[0] = () -> {
            final int id = ++req[0];
            final long us = sb.getProgress() * 100000L;
            ex.execute(() -> {
                Bitmap b = null;
                try { b = r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST); } catch (Throwable ignored) { }
                final Bitmap fb = b;
                runOnUiThread(() -> {
                    if (fb != null && id == req[0]) { chosen[0] = fb; iv.setImageBitmap(fb); }
                });
            });
        };
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) { load[0].run(); }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        sb.setProgress((int) Math.min(10, sb.getMax() / 2));   // ~১ সেকেন্ডের ফ্রেম
        load[0].run();

        new AlertDialog.Builder(this)
                .setTitle("Select from video")
                .setView(box)
                .setPositiveButton("Done", (d, w) -> {
                    if (chosen[0] != null) applyThumb(scaleDown(chosen[0]));
                })
                .setNegativeButton("Cancel", null)
                .setOnDismissListener(d -> {
                    ex.shutdownNow();
                    new Thread(() -> {
                        try { ex.awaitTermination(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
                        try { r.release(); } catch (Throwable ignored) { }
                    }).start();
                })
                .show();
    }

    private static Bitmap scaleDown(Bitmap b) {
        int ls = Math.max(b.getWidth(), b.getHeight());
        if (ls <= 720) return b;
        float k = 720f / ls;
        return Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * k)),
                Math.max(1, Math.round(b.getHeight() * k)), true);
    }

    /** গ্যালারির ছবি → ভিডিওর অনুপাতে মাঝখান থেকে কেটে থাম্বনেইল */
    private void useGalleryImage(final Uri uri) {
        final Bitmap ref = editMode ? null : ShortUploader.thumb();
        final float ratio = editMode ? editRatio
                : (ref != null && ref.getHeight() > 0 ? (float) ref.getWidth() / ref.getHeight() : 0.5625f);
        new Thread(() -> {
            Bitmap out = null;
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                try (InputStream in = getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(in, null, o); }
                int ss = 1;
                while (Math.max(o.outWidth, o.outHeight) / ss > 2000) ss *= 2;
                o = new BitmapFactory.Options();
                o.inSampleSize = ss;
                Bitmap b;
                try (InputStream in = getContentResolver().openInputStream(uri)) { b = BitmapFactory.decodeStream(in, null, o); }
                if (b == null) throw new Exception("decode");
                int rot = 0;
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    int ori = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
                    if (ori == ExifInterface.ORIENTATION_ROTATE_90) rot = 90;
                    else if (ori == ExifInterface.ORIENTATION_ROTATE_180) rot = 180;
                    else if (ori == ExifInterface.ORIENTATION_ROTATE_270) rot = 270;
                } catch (Throwable ignored) { }
                if (rot != 0) {
                    android.graphics.Matrix m = new android.graphics.Matrix();
                    m.postRotate(rot);
                    b = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
                }
                int w = b.getWidth(), h = b.getHeight();
                int cw = w, ch = Math.round(w / ratio);
                if (ch > h) { ch = h; cw = Math.round(h * ratio); }
                out = scaleDown(Bitmap.createBitmap(b, (w - cw) / 2, (h - ch) / 2, Math.max(1, cw), Math.max(1, ch)));
            } catch (Throwable e) {
                out = null;
            }
            final Bitmap res = out;
            runOnUiThread(() -> {
                if (res == null) Toast.makeText(this, "Couldn't use this image", Toast.LENGTH_SHORT).show();
                else applyThumb(res);
            });
        }).start();
    }

    /** Location: Add-location স্ক্রিন (কাছাকাছি জায়গা + টাইপ করে সার্চ), পোস্টের মতোই */
    private void openLocationPicker() {
        Intent it = new Intent(this, LocationActivity.class);
        it.putExtra(LocationActivity.EXTRA_LOCATION, location);
        startActivityForResult(it, REQ_LOCATION);
    }

    private void showAudienceDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Who can see this?")
                .setSingleChoiceItems(AUDIENCE_LABEL, audience, (d, which) -> {
                    audience = which;
                    tvAudienceStatus.setText(AUDIENCE_LABEL[which]);
                    d.dismiss();
                })
                .show();
    }

    private void showScheduleDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Scheduling options")
                .setItems(new String[]{"Post now", "Schedule for later…"}, (d, which) -> {
                    if (which == 0) {
                        scheduleAt = 0;
                        tvScheduleStatus.setText("Post now");
                    } else {
                        pickScheduleTime();
                    }
                })
                .show();
    }

    private void pickScheduleTime() {
        final Calendar c = Calendar.getInstance();
        c.add(Calendar.HOUR_OF_DAY, 1);
        DatePickerDialog dp = new DatePickerDialog(this, (view, y, m, day) ->
                new TimePickerDialog(this, (tv, h, min) -> {
                    Calendar pick = Calendar.getInstance();
                    pick.set(y, m, day, h, min, 0);
                    if (pick.getTimeInMillis() <= System.currentTimeMillis()) {
                        Toast.makeText(this, "Pick a future time", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    scheduleAt = pick.getTimeInMillis();
                    tvScheduleStatus.setText(new SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.US)
                            .format(new Date(scheduleAt)));
                }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), false).show(),
                c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH));
        dp.getDatePicker().setMinDate(System.currentTimeMillis() - 1000);
        dp.show();
    }

    private void showCaptionsDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Closed captions")
                .setSingleChoiceItems(new String[]{"On", "Off"}, captions ? 0 : 1, (d, which) -> {
                    captions = which == 0;
                    tvCaptionStatus.setText(captions ? "On" : "Off");
                    d.dismiss();
                })
                .show();
    }

    private void showTopicsDialog() {
        final boolean[] checked = new boolean[TOPIC_LIST.length];
        for (int i = 0; i < TOPIC_LIST.length; i++) checked[i] = topics.contains(TOPIC_LIST[i]);
        new AlertDialog.Builder(this)
                .setTitle("Add topics")
                .setMultiChoiceItems(TOPIC_LIST, checked, (d, which, on) -> checked[which] = on)
                .setPositiveButton("Done", (d, w) -> {
                    topics.clear();
                    for (int i = 0; i < TOPIC_LIST.length; i++) if (checked[i]) topics.add(TOPIC_LIST[i]);
                    tvTopicsStatus.setText(topics.isEmpty() ? "None selected" : android.text.TextUtils.join(", ", topics));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
