package com.familybook.app;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.Dialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.Window;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * Post settings পেজ (Next চাপলে খোলে, SHARE চাপলে পোস্ট হয়)।
 * Post audience · Who can comment · Scheduling · Share to memory (demo) · Share to groups ·
 * Product link · AI label (শুধু ছবির পোস্টে) · Monetization (demo)
 */
public class WhatsOnMindActivity extends AppCompatActivity {

    private static final int GRAY = 0xFF9E9E9E;
    private static final int BLUE = 0xFF1877F2;

    private static final String[] AUD_LABEL = {"Public", "Friends", "Only me"};
    private static final String[] AUD_VALUE = {"public", "friends", "only_me"};
    private static final String[] CMT_LABEL = {"Public", "Followers", "Only me"};
    private static final String[] CMT_VALUE = {"public", "followers", "me"};

    private ImageView btnBack, btnShare;
    private Switch switchAiLabel, switchMonetization;
    private TextView tvAudience, tvComment, tvSchedule, tvMemory, tvGroups, tvProduct;

    private int audience = 0;
    private int commentWho = 0;
    private long scheduleAt = 0;
    private boolean memoryDemo = false;
    private final ArrayList<String> groupIds = new ArrayList<>();
    private final ArrayList<String> groupNames = new ArrayList<>();
    private String productUrl = null, productName = null;

    private final ActivityResultLauncher<Intent> groupsLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), res -> {
                if (res.getResultCode() != RESULT_OK || res.getData() == null) return;
                ArrayList<String> ids = res.getData().getStringArrayListExtra(ShortPickActivity.EXTRA_IDS);
                ArrayList<String> names = res.getData().getStringArrayListExtra(ShortPickActivity.EXTRA_NAMES);
                groupIds.clear();
                groupNames.clear();
                if (ids != null && names != null) { groupIds.addAll(ids); groupNames.addAll(names); }
                refreshGroups();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.whats_on_mind_setting);

        final PostQueue.Job job = PostQueue.stagedJob();
        if (job == null) { finish(); return; }   // কোনো অপেক্ষমাণ পোস্ট নেই

        btnBack = findViewById(R.id.btnBack);
        btnShare = findViewById(R.id.btnShare);
        switchAiLabel = findViewById(R.id.switchAiLabel);
        switchMonetization = findViewById(R.id.switchMonetization);
        tvAudience = findViewById(R.id.tvAudience);
        tvComment = findViewById(R.id.tvComment);
        tvSchedule = findViewById(R.id.tvSchedule);
        tvMemory = findViewById(R.id.tvMemory);
        tvGroups = findViewById(R.id.tvGroups);
        tvProduct = findViewById(R.id.tvProduct);

        final boolean inGroup = job.groupId != null && !job.groupId.isEmpty();
        final boolean hasPhoto = !job.photos.isEmpty();

        // Editor-এ বাছা প্রাইভেসি দিয়ে শুরু
        for (int i = 0; i < AUD_VALUE.length; i++) if (AUD_VALUE[i].equals(job.privacy)) audience = i;
        tvAudience.setText(inGroup ? "Group privacy" : AUD_LABEL[audience]);
        tvComment.setText(CMT_LABEL[commentWho]);

        // AI label: লেখা-শুধু (টেক্সট) পোস্টে সেটিংসে লুকানো
        if (!hasPhoto) {
            findViewById(R.id.rowAi).setVisibility(View.GONE);
            switchAiLabel.setChecked(false);
        }
        // গ্রুপের ভেতর থেকে পোস্ট করলে আবার "Share to groups" লাগে না
        if (inGroup) findViewById(R.id.rowGroups).setVisibility(View.GONE);

        btnBack.setOnClickListener(v -> { setResult(RESULT_CANCELED); finish(); });   // NewPostActivity বাতিল সামলায়

        // ---- Post audience ----
        findViewById(R.id.rowAudience).setOnClickListener(v -> {
            if (inGroup) {
                Toast.makeText(this, "Group post: গ্রুপের প্রাইভেসিই প্রযোজ্য", Toast.LENGTH_SHORT).show();
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle("Who can see this post?")
                    .setSingleChoiceItems(AUD_LABEL, audience, (d, which) -> {
                        audience = which;
                        tvAudience.setText(AUD_LABEL[which]);
                        d.dismiss();
                    }).show();
        });

        // ---- Who can comment: Public / Followers / Only me ----
        findViewById(R.id.rowComment).setOnClickListener(v ->
                new AlertDialog.Builder(this)
                        .setTitle("Who can comment")
                        .setSingleChoiceItems(CMT_LABEL, commentWho, (d, which) -> {
                            commentWho = which;
                            tvComment.setText(CMT_LABEL[which]);
                            tvComment.setTextColor(which == 2 ? BLUE : GRAY);
                            d.dismiss();
                            if (which == 2) {
                                Toast.makeText(this, "Only me: পোস্টে কমেন্ট বক্স দেখা যাবে না", Toast.LENGTH_SHORT).show();
                            }
                        }).show());

        // ---- Scheduling ----
        findViewById(R.id.rowSchedule).setOnClickListener(v ->
                new AlertDialog.Builder(this)
                        .setTitle("Scheduling options")
                        .setItems(new String[]{"Post now", "Schedule for later…"}, (d, which) -> {
                            if (which == 0) {
                                scheduleAt = 0;
                                tvSchedule.setText("Publish now or schedule for later.");
                                tvSchedule.setTextColor(GRAY);
                            } else {
                                pickScheduleTime();
                            }
                        }).show());

        // ---- Share to memory (demo) ----
        findViewById(R.id.rowMemory).setOnClickListener(v -> {
            memoryDemo = !memoryDemo;
            tvMemory.setText(memoryDemo ? "Sharing to memory (demo)" : "Demo \u2022 coming soon");
            tvMemory.setTextColor(memoryDemo ? BLUE : GRAY);
        });

        // ---- Share to groups: আমার তৈরি/জয়েন করা গ্রুপের তালিকা ----
        findViewById(R.id.rowGroups).setOnClickListener(v -> {
            Intent it = new Intent(this, ShortPickActivity.class);
            it.putExtra(ShortPickActivity.EXTRA_MODE, ShortPickActivity.MODE_GROUPS);
            it.putStringArrayListExtra(ShortPickActivity.EXTRA_IDS, groupIds);
            it.putStringArrayListExtra(ShortPickActivity.EXTRA_NAMES, groupNames);
            groupsLauncher.launch(it);
        });

        // ---- Product link ----
        findViewById(R.id.rowProduct).setOnClickListener(v -> showProductForm());

        // ---- AI label / Monetization (demo) ----
        findViewById(R.id.rowAi).setOnClickListener(v -> switchAiLabel.toggle());
        findViewById(R.id.rowMonet).setOnClickListener(v -> switchMonetization.toggle());

        // ---- SHARE ----
        btnShare.setOnClickListener(v -> {
            PostQueue.Job j = PostQueue.stagedJob();
            if (j == null) { setResult(RESULT_CANCELED); finish(); return; }
            if (scheduleAt != 0 && scheduleAt <= System.currentTimeMillis()) {
                Toast.makeText(this, "Schedule time must be in the future", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!inGroup) j.privacy = AUD_VALUE[audience];
            j.commentPerm = CMT_VALUE[commentWho];
            j.scheduleAt = scheduleAt;
            j.aiLabel = hasPhoto && switchAiLabel.isChecked();
            j.monetize = switchMonetization.isChecked();
            j.productUrl = productUrl;
            j.productName = productName;
            j.extraGroupIds.clear();
            if (!inGroup) j.extraGroupIds.addAll(groupIds);
            btnShare.setEnabled(false);
            PostQueue.commit();
            if (scheduleAt != 0) {
                Toast.makeText(this, "Scheduled: " + fmt(scheduleAt), Toast.LENGTH_LONG).show();
            }
            setResult(RESULT_OK);
            finish();
        });
    }

    // ---------------------------------------------------------

    private void refreshGroups() {
        if (groupIds.isEmpty()) {
            tvGroups.setText("You can now automatically share to relevant groups.");
            tvGroups.setTextColor(GRAY);
        } else {
            tvGroups.setText("Sharing to: " + TextUtils.join(", ", groupNames));
            tvGroups.setTextColor(BLUE);
        }
    }

    private static String fmt(long ms) {
        return new SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.US).format(new Date(ms));
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
                    tvSchedule.setText("Scheduled: " + fmt(scheduleAt));
                    tvSchedule.setTextColor(BLUE);
                }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), false).show(),
                c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH));
        dp.getDatePicker().setMinDate(System.currentTimeMillis() - 1000);
        dp.show();
    }

    /** "Add a product link" ফর্ম: URL + Link name (optional) + Save */
    private void showProductForm() {
        final Dialog dlg = new Dialog(this, android.R.style.Theme_DeviceDefault_Light_NoActionBar);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dlg.setContentView(R.layout.dialog_product_link);
        final EditText etUrl = dlg.findViewById(R.id.plUrl);
        final EditText etName = dlg.findViewById(R.id.plName);
        final View remove = dlg.findViewById(R.id.plRemove);
        if (productUrl != null) {
            etUrl.setText(productUrl);
            if (productName != null) etName.setText(productName);
            remove.setVisibility(View.VISIBLE);
        }
        dlg.findViewById(R.id.plBack).setOnClickListener(v -> dlg.dismiss());
        remove.setOnClickListener(v -> {
            productUrl = null;
            productName = null;
            refreshProduct();
            dlg.dismiss();
        });
        dlg.findViewById(R.id.plSave).setOnClickListener(v -> {
            String u = etUrl.getText().toString().trim();
            if (u.isEmpty()) {
                etUrl.setError("URL দিন");
                etUrl.requestFocus();
                return;
            }
            if (!u.matches("(?i)^https?://.*")) u = "https://" + u;
            if (!android.util.Patterns.WEB_URL.matcher(u).matches()) {
                etUrl.setError("সঠিক URL দিন");
                etUrl.requestFocus();
                return;
            }
            String n = etName.getText().toString().trim();
            productUrl = u;
            productName = n.isEmpty() ? null : n;
            refreshProduct();
            dlg.dismiss();
        });
        dlg.show();
        if (dlg.getWindow() != null) {
            dlg.getWindow().setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT);
        }
    }

    private void refreshProduct() {
        if (productUrl == null) {
            tvProduct.setText("Make it easy for viewers to shop your affiliate content.");
            tvProduct.setTextColor(GRAY);
        } else {
            tvProduct.setText(productName != null ? productName : productUrl);
            tvProduct.setTextColor(BLUE);
        }
    }
}
