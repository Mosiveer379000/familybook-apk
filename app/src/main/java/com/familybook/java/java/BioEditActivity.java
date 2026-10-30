package com.familybook.app;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Bio edit স্ক্রিন (৩নং ছবি)।
 * Bio লিখে, নিচের প্রাইভেসি (Public / Friends / Only me) বদলে Save দিলে
 * Supabase profile_bios.bio ও profile_bios.bio_privacy-তে সেভ হয় এবং আগের পেজে ফিরে যায়।
 */
public class BioEditActivity extends BaseActivity {

    private static final int BIO_MAX = 120;
    private static final String[] KEYS = {"public", "friends", "locked"};
    private static final String[] LABELS = {"Public", "Friends", "Only me"};

    private EditText etBio;
    private TextView tvBioCount, tvPrivacy;
    private ImageView ivPrivacy;
    private String privacy = "public";
    private boolean saving = false;
    private boolean isMe;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Me.isLoggedIn() || Me.uid() == null) {
            finish();
            return;
        }
        String uid = getIntent().getStringExtra("uid");
        isMe = uid == null || uid.equals(Me.uid());
        if (!isMe) {
            finish();
            return;
        }

        setContentView(R.layout.activity_bio_edit);

        etBio = findViewById(R.id.etBio);
        tvBioCount = findViewById(R.id.tvBioCount);
        tvPrivacy = findViewById(R.id.tvPrivacy);
        ivPrivacy = findViewById(R.id.ivPrivacy);

        // Bio বক্সের নীল বর্ডার
        GradientDrawable box = new GradientDrawable();
        box.setColor(Color.WHITE);
        box.setCornerRadius(dp(16));
        box.setStroke(dp(2), Color.parseColor("#0866FF"));
        etBio.setBackground(box);

        String bio = getIntent().getStringExtra("bio");
        String p = getIntent().getStringExtra("privacy");
        if (p != null && indexOf(p) >= 0) privacy = p;

        etBio.setText(bio == null ? "" : bio);
        etBio.setSelection(etBio.getText().length());
        tvBioCount.setText(etBio.getText().length() + "/" + BIO_MAX);
        applyPrivacy();

        etBio.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                tvBioCount.setText(s.length() + "/" + BIO_MAX);
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        findViewById(R.id.btnCloseBio).setOnClickListener(v -> finish());
        findViewById(R.id.rowPrivacy).setOnClickListener(this::showPrivacyMenu);
        findViewById(R.id.btnSaveBio).setOnClickListener(v -> save());

        etBio.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(etBio, InputMethodManager.SHOW_IMPLICIT);
    }

    // ---- প্রাইভেসি বদলানো (সাথে সাথে আইকন বদলায়) ----
    private void showPrivacyMenu(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        for (int i = 0; i < LABELS.length; i++) pm.getMenu().add(0, i, i, LABELS[i]);
        pm.setOnMenuItemClickListener(item -> {
            privacy = KEYS[item.getItemId()];
            applyPrivacy();
            return true;
        });
        pm.show();
    }

    private void applyPrivacy() {
        int i = Math.max(0, indexOf(privacy));
        tvPrivacy.setText(LABELS[i]);
        if ("locked".equals(privacy)) {
            ivPrivacy.setImageResource(R.drawable.ic_privacy_lock);
            ivPrivacy.clearColorFilter();
        } else {
            ivPrivacy.setImageResource("friends".equals(privacy) ? R.drawable.ic_people : R.drawable.ic_globe);
            ivPrivacy.setColorFilter(Color.parseColor("#65676B"), PorterDuff.Mode.SRC_IN);
        }
    }

    private int indexOf(String key) {
        for (int i = 0; i < KEYS.length; i++) if (KEYS[i].equals(key)) return i;
        return -1;
    }

    // ---- Supabase-এ সেভ ----
    private void save() {
        if (saving) return;
        if (!Net.check(this)) return;

        String t = etBio.getText().toString().trim();
        if (t.length() > BIO_MAX) t = t.substring(0, BIO_MAX);
        final String newBio = t;
        final String newPrivacy = privacy;

        saving = true;
        try {
            JSONObject f = new JSONObject()
                    .put("user_id", Me.uid())
                    .put("bio", newBio.isEmpty() ? JSONObject.NULL : newBio)
                    .put("bio_privacy", newPrivacy);
            SupabaseRest.call("POST", "/rest/v1/profile_bios?on_conflict=user_id", f.toString(),
                    "resolution=merge-duplicates,return=representation", true, r -> {
                saving = false;
                if (isFinishing()) return;
                if (!r.ok()) {
                    toast(SupabaseRest.errorText(r));
                    return;
                }
                JSONArray a = null;
                try { a = new JSONArray(r.body); } catch (Exception ignored) { }
                if (a == null || a.length() == 0) {
                    toast("প্রোফাইল সেভ হয়নি, আবার চেষ্টা করুন");
                    return;
                }
                // আগের পেজে ফিরে যাও
                Intent data = new Intent()
                        .putExtra("bio", newBio)
                        .putExtra("privacy", newPrivacy);
                setResult(RESULT_OK, data);
                hideKeyboard();
                finish();
            });
        } catch (Exception e) {
            saving = false;
            toast("সেভ হয়নি");
        }
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(etBio.getWindowToken(), 0);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
