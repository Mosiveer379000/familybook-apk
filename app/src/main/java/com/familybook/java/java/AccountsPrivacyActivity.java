package com.familybook.app;

import android.animation.ObjectAnimator;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.json.JSONObject;

import java.io.File;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AccountsPrivacyActivity extends AppCompatActivity {

    private EditText etNidName, etProfileName, etUsername, etMainEmail, etRecoveryEmail, etPhone, etDay, etMonth, etYear, etPassword;
    private TextView tvAgnNumber, tvProgressPercent, tvNidTitle, tvBidTitle, tvLoadingText;
    private View dotNidName, dotProfileName, dotUsername, dotMainEmail, dotRecoveryEmail, dotPhone, dotPassword, dotBirthday;
    private ImageView ivNidLockToggle, ivProfileLockToggle, ivUsernamePrivacy, ivMainEmailPrivacy, ivRecoveryPrivacy,
            ivPhonePrivacy, ivPasswordEye, ivBirthdayPrivacy, ivFrontNidPreview, ivBidPreview;
    private Button btnSubmit, btnReset, btnVerify, btnFrontNid, btnUploadBid, btnHide;
    private LinearLayout layoutLoading;
    private ProgressBar progressBar;

    private Uri uriNidFront, uriBid;
    private Uri cameraImageUri;
    private TextRecognizer recognizer;

    private boolean loaded = false;
    private JSONObject priv = new JSONObject();
    private String savedProfileName = "";
    private String savedUsername = "";
    private int agn = 0;
    private boolean agnPhase = true;
    private int nameEdits = 0;
    private long lastNameEdit = 0;
    private boolean isNidVerified = false;
    private boolean isNidNamePublic = false;
    private boolean busy = false;
    private String nameNote = null;
    private String nidFrontKey = "", nidBidKey = "";
    private boolean nameLockedByNid = false, nameLockedByCount = false;

    private static final String[] MON = {"jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"};

    private ActivityResultLauncher<String> galleryFrontLauncher, galleryBidLauncher;
    private ActivityResultLauncher<Uri> cameraFrontLauncher, cameraBidLauncher;
    private ActivityResultLauncher<String> requestPermissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_accounts_privacy);
        if (!Me.isLoggedIn()) { finish(); return; }

        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        
        requestPermissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
            if (isGranted) {
                toastCustom("Camera Permission Granted");
            } else {
                toastCustom("Camera Permission Denied");
            }
        });

        initLaunchers();
        initViews();
        setupListeners();
        loadProfile();
    }

    private void playSound(int soundResId) {
        try {
            MediaPlayer mp = MediaPlayer.create(this, soundResId);
            if (mp != null) {
                mp.start();
                mp.setOnCompletionListener(MediaPlayer::release);
            }
        } catch (Exception ignored) {}
    }

    private void toastCustom(String message) {
        try {
            Toast toast = new Toast(this);
            toast.setDuration(Toast.LENGTH_SHORT);
            toast.setGravity(Gravity.TOP|Gravity.CENTER_HORIZONTAL, 0, 100);

            LinearLayout layout = new LinearLayout(this);
            layout.setOrientation(LinearLayout.HORIZONTAL);
            layout.setPadding(30, 20, 30, 20);
            layout.setBackgroundColor(Color.WHITE);

            TextView tv = new TextView(this);
            tv.setText(message);
            tv.setTextColor(Color.parseColor("#00C853"));
            tv.setTextSize(14f);
            tv.setTypeface(null, android.graphics.Typeface.BOLD);

            layout.addView(tv);
            toast.setView(layout);
            toast.show();
        } catch (Exception e) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        }
    }

    private void initLaunchers() {
        galleryFrontLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) {
                uriNidFront = uri;
                btnFrontNid.setVisibility(View.GONE);
                ivFrontNidPreview.setVisibility(View.VISIBLE);
                ivFrontNidPreview.setImageURI(uri);
            }
        });

        cameraFrontLauncher = registerForActivityResult(new ActivityResultContracts.TakePicture(), success -> {
            if (success && cameraImageUri != null) {
                uriNidFront = cameraImageUri;
                btnFrontNid.setVisibility(View.GONE);
                ivFrontNidPreview.setVisibility(View.VISIBLE);
                ivFrontNidPreview.setImageURI(uriNidFront);
            }
        });

        galleryBidLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) {
                uriBid = uri;
                btnUploadBid.setVisibility(View.GONE);
                ivBidPreview.setVisibility(View.VISIBLE);
                ivBidPreview.setImageURI(uri);
            }
        });

        cameraBidLauncher = registerForActivityResult(new ActivityResultContracts.TakePicture(), success -> {
            if (success && cameraImageUri != null) {
                uriBid = cameraImageUri;
                btnUploadBid.setVisibility(View.GONE);
                ivBidPreview.setVisibility(View.VISIBLE);
                ivBidPreview.setImageURI(uriBid);
            }
        });
    }

    private void initViews() {
        etNidName = findViewById(R.id.etNidName);
        etProfileName = findViewById(R.id.etProfileName);
        etUsername = findViewById(R.id.etUsername);
        etMainEmail = findViewById(R.id.etMainEmail);
        etRecoveryEmail = findViewById(R.id.etRecoveryEmail);
        etPhone = findViewById(R.id.etPhone);
        etDay = findViewById(R.id.etDay);
        etMonth = findViewById(R.id.etMonth);
        etYear = findViewById(R.id.etYear);
        etPassword = findViewById(R.id.etPassword);
        tvAgnNumber = findViewById(R.id.tvAgnNumber);
        tvProgressPercent = findViewById(R.id.tvProgressPercent);
        tvLoadingText = findViewById(R.id.tvLoadingText);
        tvNidTitle = findViewById(R.id.tvNidTitle);
        tvBidTitle = findViewById(R.id.tvBidTitle);
        dotNidName = findViewById(R.id.dotNidName);
        dotProfileName = findViewById(R.id.dotProfileName);
        dotUsername = findViewById(R.id.dotUsername);
        dotMainEmail = findViewById(R.id.dotMainEmail);
        dotRecoveryEmail = findViewById(R.id.dotRecoveryEmail);
        dotPhone = findViewById(R.id.dotPhone);
        dotPassword = findViewById(R.id.dotPassword);
        dotBirthday = findViewById(R.id.dotBirthday);
        ivNidLockToggle = findViewById(R.id.ivNidLockToggle);
        ivProfileLockToggle = findViewById(R.id.ivProfileLockToggle);
        ivUsernamePrivacy = findViewById(R.id.ivUsernamePrivacy);
        ivMainEmailPrivacy = findViewById(R.id.ivMainEmailPrivacy);
        ivRecoveryPrivacy = findViewById(R.id.ivRecoveryPrivacy);
        ivPhonePrivacy = findViewById(R.id.ivPhonePrivacy);
        ivPasswordEye = findViewById(R.id.ivPasswordEye);
        ivBirthdayPrivacy = findViewById(R.id.ivBirthdayPrivacy);
        ivFrontNidPreview = findViewById(R.id.ivFrontNidPreview);
        ivBidPreview = findViewById(R.id.ivBidPreview);
        btnSubmit = findViewById(R.id.btnSubmit);
        btnReset = findViewById(R.id.btnReset);
        btnVerify = findViewById(R.id.btnVerify);
        btnFrontNid = findViewById(R.id.btnFrontNid);
        btnUploadBid = findViewById(R.id.btnUploadBid);
        btnHide = findViewById(R.id.btnHide);
        layoutLoading = findViewById(R.id.layoutLoading);
        progressBar = findViewById(R.id.progressBar);

        String email = Me.email();
        etMainEmail.setText(email == null ? "" : email);
        etMainEmail.setEnabled(false);
        etPassword.setText("********");
        etPassword.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        etPassword.setEnabled(false);
        ivPasswordEye.setVisibility(View.GONE);
        etNidName.setEnabled(false);
        ivMainEmailPrivacy.setTag(null);

        applyUsernameFilter();
        btnSubmit.setEnabled(false);
        colorizeIcons();
        refreshDots();
    }

    private void applyUsernameFilter() {
        InputFilter lower = (src, s, e, d, ds, de) -> src.subSequence(s, e).toString().toLowerCase(Locale.US);
        InputFilter letters = (src, s, e, d, ds, de) -> {
            for (int i = s; i < e; i++) {
                char c = src.charAt(i);
                boolean ok = agnPhase || agn > 0 ? (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                        : Character.isLetterOrDigit(c) || c == '.' || c == '_';
                if (!ok) return "";
            }
            return null;
        };
        int max = (agnPhase || agn > 0) ? 8 : 20;
        etUsername.setFilters(new InputFilter[]{lower, letters, new InputFilter.LengthFilter(max)});
    }

    private void colorizeIcons() {
        ImageView[] all = {ivNidLockToggle, ivProfileLockToggle, ivUsernamePrivacy, ivMainEmailPrivacy,
                ivRecoveryPrivacy, ivPhonePrivacy, ivBirthdayPrivacy};
        for (ImageView v : all) if (v != null) v.setColorFilter(Color.BLACK, PorterDuff.Mode.SRC_IN);
    }

    private void loadProfile() {
        Db.getMine("profiles", (row, err) -> {
            if (isFinishing()) return;
            if (err != null) { toastCustom(err); finish(); return; }
            JSONObject info = row != null && row.optJSONObject("info") != null ? row.optJSONObject("info") : new JSONObject();

            String dbName = Db.s(row, "name");
            isNidNamePublic = info.optBoolean("nidNamePublic", false);
            savedProfileName = info.optString("profileName", "");
            if (savedProfileName.isEmpty()) savedProfileName = isNidNamePublic ? "" : dbName;
            savedUsername = Db.s(row, "username");
            agn = row == null ? 0 : row.optInt("agn", 0);
            nameEdits = info.optInt("nameEdits", 0);
            lastNameEdit = info.optLong("nameEditedAt", 0);
            isNidVerified = info.optBoolean("nidVerified", false);
            nidFrontKey = info.optString("nidFrontKey", "");
            nidBidKey = info.optString("nidBidKey", "");
            priv = info.optJSONObject("priv") != null ? info.optJSONObject("priv") : new JSONObject();

            etProfileName.setText(savedProfileName);
            etUsername.setText(savedUsername);
            etNidName.setText(info.optString("nidName", ""));
            etRecoveryEmail.setText(info.optString("recoveryEmail", ""));
            etPhone.setText(info.optString("phone", ""));
            fillDob(info.optString("dob", ""));

            if (isNidVerified) {
                tvNidTitle.setText("NID");
                lockBirthday(true);
            }
            loaded = true;
            applyAgnPhase();
        });
    }

    private void applyAgnPhase() {
        SupabaseRest.call("POST", "/rest/v1/rpc/agn_available", "{}", null, true, r -> {
            if (isFinishing()) return;
            if (r.ok()) agnPhase = r.body != null && r.body.trim().startsWith("t");
            applyUsernameFilter();
            tvAgnNumber.setText(agn > 0 ? String.valueOf(agn) : (agnPhase ? "auto" : ""));
            updateNamePrivacyUI();
            applyPrivacyIcons();
            refreshDots();
            validateForm();
        });
    }

    private void fillDob(String dob) {
        if (dob.matches("\\d{1,2}/\\d{1,2}/\\d{4}")) { setDobFields(dob); return; }
        SupabaseRest.call("GET", "/auth/v1/user", null, null, true, r -> {
            try {
                JSONObject meta = new JSONObject(r.body).optJSONObject("user_metadata");
                String d = meta == null ? "" : meta.optString("dob", "").trim();
                if (d.matches("\\d{1,2}/\\d{1,2}/\\d{4}")) setDobFields(d);
            } catch (Exception ignored) { }
        });
    }

    private void setDobFields(String d) {
        String[] p = d.split("/");
        etDay.setText(String.valueOf(Integer.parseInt(p[0])));
        etMonth.setText(String.valueOf(Integer.parseInt(p[1])));
        etYear.setText(p[2]);
    }

    private void lockBirthday(boolean lock) {
        etDay.setEnabled(!lock); etMonth.setEnabled(!lock); etYear.setEnabled(!lock);
    }

    private void setupListeners() {
        findViewById(R.id.btnBackArrow).setOnClickListener(v -> finish());

        ivNidLockToggle.setOnClickListener(v -> {
            if (!isNidVerified) { toastCustom("Please verify NID first"); return; }
            isNidNamePublic = true;
            updateNamePrivacyUI();
        });
        ivProfileLockToggle.setOnClickListener(v -> {
            if (!isNidVerified) { toastCustom("Please verify NID first"); return; }
            isNidNamePublic = false;
            updateNamePrivacyUI();
        });

        etProfileName.setOnClickListener(v -> {
            if (nameLockedByNid) toastCustom("NID Name is Public, Profile Name is locked.");
            else if (nameLockedByCount) showAdminRequestDialog();
        });

        bindPrivacy(ivUsernamePrivacy, "username");
        bindPrivacy(ivMainEmailPrivacy, "mainEmail");
        bindPrivacy(ivRecoveryPrivacy, "recoveryEmail");
        bindPrivacy(ivPhonePrivacy, "phone");
        bindPrivacy(ivBirthdayPrivacy, "birthday");

        TextWatcher w = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { refreshDots(); validateForm(); }
            @Override public void afterTextChanged(Editable s) { }
        };
        etUsername.addTextChangedListener(w);
        etProfileName.addTextChangedListener(w);
        etRecoveryEmail.addTextChangedListener(w);
        etPhone.addTextChangedListener(w);

        btnFrontNid.setOnClickListener(v -> showImageSourceDialog(true));
        btnUploadBid.setOnClickListener(v -> showImageSourceDialog(false));

        btnVerify.setOnClickListener(v -> {
            if (uriNidFront == null && uriBid == null) { toastCustom("Please provide NID (front) or BID image first"); return; }
            verifyDocument();
        });
        btnReset.setOnClickListener(v -> resetDocuments());
        btnHide.setOnClickListener(v -> layoutLoading.setVisibility(View.GONE));
        
        btnSubmit.setOnClickListener(v -> {
            playSound(R.raw.submit);
            submit();
        });
    }

    private void showImageSourceDialog(boolean isFrontNid) {
        CharSequence[] options = {"Camera", "Gallery"};
        new AlertDialog.Builder(this)
                .setTitle("Select Image Source")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        openCamera(isFrontNid);
                    } else {
                        if (isFrontNid) galleryFrontLauncher.launch("image/*");
                        else galleryBidLauncher.launch("image/*");
                    }
                })
                .show();
    }

    private void openCamera(boolean isFrontNid) {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(android.Manifest.permission.CAMERA);
            return;
        }
        try {
            File photoFile = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "doc_" + System.currentTimeMillis() + ".jpg");
            cameraImageUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", photoFile);
            if (isFrontNid) {
                cameraFrontLauncher.launch(cameraImageUri);
            } else {
                cameraBidLauncher.launch(cameraImageUri);
            }
        } catch (Exception e) {
            toastCustom("Could not open camera: " + e.getMessage());
        }
    }

    private void bindPrivacy(ImageView iv, String key) {
        iv.setOnClickListener(v -> {
            try { priv.put(key, !priv.optBoolean(key, false)); } catch (Exception ignored) { }
            applyPrivacyIcons();
        });
    }

    private void applyPrivacyIcons() {
        setLock(ivUsernamePrivacy, "username");
        setLock(ivMainEmailPrivacy, "mainEmail");
        setLock(ivRecoveryPrivacy, "recoveryEmail");
        setLock(ivPhonePrivacy, "phone");
        setLock(ivBirthdayPrivacy, "birthday");
        colorizeIcons();
    }

    private void setLock(ImageView iv, String key) {
        iv.setImageResource(priv.optBoolean(key, false) ? R.drawable.ic_globe : R.drawable.ic_locked);
    }

    private void updateNamePrivacyUI() {
        ivNidLockToggle.setImageResource(isNidNamePublic ? R.drawable.ic_globe : R.drawable.ic_locked);
        ivProfileLockToggle.setImageResource(isNidNamePublic ? R.drawable.ic_locked : R.drawable.ic_globe);
        nameLockedByNid = isNidNamePublic;
        nameLockedByCount = AgnRules.isNameLocked(nameEdits, lastNameEdit, System.currentTimeMillis());
        boolean locked = nameLockedByNid || nameLockedByCount;
        etProfileName.setFocusable(!locked);
        etProfileName.setFocusableInTouchMode(!locked);
        etProfileName.setCursorVisible(!locked);
        etProfileName.setAlpha(locked ? 0.6f : 1f);
        colorizeIcons();
    }

    private void refreshDots() {
        dot(dotProfileName, true);
        dot(dotMainEmail, true);
        dot(dotPassword, true);
        dot(dotUsername, usernameValid());
        dot(dotRecoveryEmail, android.util.Patterns.EMAIL_ADDRESS.matcher(txt(etRecoveryEmail)).matches());
        dot(dotPhone, txt(etPhone).length() >= 6);
        dot(dotNidName, isNidVerified && !txt(etNidName).isEmpty());
        dot(dotBirthday, isNidVerified);
    }

    private void dot(View v, boolean green) {
        v.setBackgroundResource(green ? R.drawable.dot_green : R.drawable.dot_red);
    }

    private void verifyDocument() {
        if (busy) return;
        Uri target = uriNidFront != null ? uriNidFront : uriBid;
        boolean isBidDoc = uriNidFront == null;
        busy = true;
        layoutLoading.setVisibility(View.VISIBLE);
        
        progressBar.setProgress(0);
        tvProgressPercent.setText("0%");
        tvLoadingText.setText("Scanning...");
        
        ObjectAnimator animator = ObjectAnimator.ofInt(progressBar, "progress", 0, 90);
        animator.setDuration(1200);
        animator.addUpdateListener(animation -> {
            int p = (int) animation.getAnimatedValue();
            tvProgressPercent.setText(p + "%");
        });
        animator.start();

        try {
            InputImage image = InputImage.fromFilePath(this, target);
            recognizer.process(image)
                    .addOnSuccessListener(t -> onOcr(t.getText(), isBidDoc))
                    .addOnFailureListener(e -> verifyFailed("Could not read image, please provide a clearer picture."));
        } catch (Exception e) {
            verifyFailed("Could not open image.");
        }
    }

    private void onOcr(String text, boolean isBidDoc) {
        String low = text.toLowerCase(Locale.US);
        boolean looksBid = low.contains("birth registration");
        boolean looksNid = low.contains("national id") || low.contains("nid no") || low.contains("bangladesh");
        if (isBidDoc ? !looksBid : !looksNid) {
            verifyFailed(isBidDoc ? "This does not look like a Birth Registration Certificate." : "This does not look like an NID card.");
            return;
        }

        int[] dob = findDob(text);
        if (dob == null) { verifyFailed("Could not read date of birth from card."); return; }

        int d = parseInt(etDay), m = parseInt(etMonth), y = parseInt(etYear);
        if (dob[0] != d || dob[1] != m || dob[2] != y) {
            verifyFailed("Card DOB (" + dob[0] + "/" + dob[1] + "/" + dob[2] + ") does not match your Birthday.");
            return;
        }

        String name = findName(text);
        if (name == null) { verifyFailed("Could not read name from card. Please ensure clear English name text is visible."); return; }

        final String cardName = name;
        final String idNumber = IdentityLink.extractNumber(text);   // স্বামী/স্ত্রী মেলানোর জন্য
        uploadDoc(isBidDoc ? uriBid : uriNidFront, isBidDoc, () -> {
            IdentityLink.saveMine(isBidDoc ? "BID" : "NID", idNumber);
            completeVerify(cardName, isBidDoc);
        });
    }

    private void completeVerify(String cardName, boolean isBidDoc) {
        etNidName.setText(cardName);
        isNidVerified = true;
        lockBirthday(true);
        tvNidTitle.setText(isBidDoc ? "Add_NID" : "NID");
        tvBidTitle.setText(isBidDoc ? "BID" : "Add_BID");
        
        playSound(R.raw.success);
        
        ObjectAnimator animator = ObjectAnimator.ofInt(progressBar, "progress", progressBar.getProgress(), 100);
        animator.setDuration(300);
        animator.addUpdateListener(animation -> {
            int p = (int) animation.getAnimatedValue();
            tvProgressPercent.setText(p + "%");
        });
        animator.start();

        tvLoadingText.setText("Scan Completed Successfully!");
        busy = false;
        refreshDots();
        toastCustom("Verification Successful! Click Submit to save.");
    }

    private void uploadDoc(Uri uri, boolean bid, Runnable ok) {
        tvLoadingText.setText("Uploading...");
        new Thread(() -> {
            byte[] data = null;
            try (java.io.InputStream in = getContentResolver().openInputStream(uri);
                 java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while (in != null && (n = in.read(buf)) > 0) bo.write(buf, 0, n);
                data = bo.toByteArray();
            } catch (Exception ignored) { }
            final byte[] fd = data;
            final String mime = getContentResolver().getType(uri) == null ? "image/jpeg" : getContentResolver().getType(uri);
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (fd == null || fd.length == 0) { verifyFailed("Failed to read image data."); return; }
                Media.upload(fd, bid ? "birth_cert" : "nid", bid ? "bid.jpg" : "nid-front.jpg", mime, (key, err) -> {
                    if (isFinishing()) return;
                    if (err != null) { verifyFailed("Upload failed: " + err); return; }
                    if (bid) nidBidKey = key; else nidFrontKey = key;
                    ok.run();
                });
            });
        }).start();
    }

    private void verifyFailed(String msg) {
        playSound(R.raw.error);
        busy = false;
        progressBar.setProgress(0);
        layoutLoading.setVisibility(View.GONE);
        toastCustom(msg);
    }

    private static int[] findDob(String text) {
        int[] any = null;
        for (String line : text.split("\\n")) {
            int[] f = parseDate(line);
            if (f == null) continue;
            if (line.toLowerCase(Locale.US).contains("birth")) return f;
            if (any == null) any = f;
        }
        return any;
    }

    private static int[] parseDate(String s) {
        Matcher a = Pattern.compile("(\\d{1,2})\\s+([A-Za-z]{3,9})[,.]?\\s+(\\d{4})").matcher(s);
        if (a.find()) {
            String mon = a.group(2).substring(0, 3).toLowerCase(Locale.US);
            for (int i = 0; i < 12; i++) if (MON[i].equals(mon)) return new int[]{Integer.parseInt(a.group(1)), i + 1, Integer.parseInt(a.group(3))};
        }
        Matcher b = Pattern.compile("(\\d{1,2})[/-](\\d{1,2})[/-](\\d{4})").matcher(s);
        if (b.find()) return new int[]{Integer.parseInt(b.group(1)), Integer.parseInt(b.group(2)), Integer.parseInt(b.group(3))};
        return null;
    }

    private static String findName(String text) {
        for (String line : text.split("\\n")) {
            String clean = line.trim();
            if (clean.matches("^[A-Za-z .]{3,50}$") && !clean.toLowerCase().contains("republic") && !clean.toLowerCase().contains("bangladesh") && !clean.toLowerCase().contains("card") && !clean.toLowerCase().contains("nid")) {
                if (clean.contains(" ")) {
                    StringBuilder sb = new StringBuilder();
                    for (String w : clean.split("\\s+")) {
                        if (w.isEmpty()) continue;
                        if (sb.length() > 0) sb.append(' ');
                        sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1).toLowerCase(Locale.US));
                    }
                    return sb.toString();
                }
            }
        }
        
        Pattern p = Pattern.compile("(?i)^\\s*(?:name)?\\s*[:\\-]?\\s*([A-Za-z .]{3,})$");
        for (String line : text.split("\\n")) {
            Matcher m = p.matcher(line);
            if (m.find()) {
                String n = m.group(1).trim().replaceAll("\\s+", " ");
                if (n.length() > 3 && !n.toLowerCase().contains("bangladesh")) return n;
            }
        }
        return null;
    }

    private void resetDocuments() {
        if (busy) return;
        if (!isNidVerified) { clearPickedDocs(); return; }
        new AlertDialog.Builder(this)
                .setTitle("NID Reset")
                .setMessage("NID/BID images will be deleted and verification will reset. Do you want to continue?")
                .setPositiveButton("Reset", (d, w) -> resetOnServer())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void resetOnServer() {
        busy = true;
        SupabaseRest.call("POST", "/rest/v1/rpc/reset_nid", "{}", null, true, r -> {
            if (isFinishing()) return;
            busy = false;
            if (!r.ok()) { toastCustom(SupabaseRest.errorText(r)); return; }
            isNidVerified = false; isNidNamePublic = false;
            nidFrontKey = ""; nidBidKey = "";
            etNidName.setText("");
            lockBirthday(false);
            clearPickedDocs();
            updateNamePrivacyUI();
            refreshDots();
            validateForm();
            toastCustom("NID reset successfully. Please verify again.");
        });
    }

    private void clearPickedDocs() {
        uriNidFront = null; uriBid = null;
        ivFrontNidPreview.setVisibility(View.GONE); btnFrontNid.setVisibility(View.VISIBLE);
        ivBidPreview.setVisibility(View.GONE); btnUploadBid.setVisibility(View.VISIBLE);
        tvNidTitle.setText("Add_NID"); tvBidTitle.setText("Add_BID");
        layoutLoading.setVisibility(View.GONE);
    }

    private void showAdminRequestDialog() {
        final EditText et = new EditText(this);
        et.setHint("New Profile Name");
        et.setSingleLine(true);
        et.setPadding(48, 32, 48, 32);
        new AlertDialog.Builder(this)
                .setTitle("Profile Name Locked")
                .setMessage("You have changed your name 3 times. Send a request to the admin to change it further.")
                .setView(et)
                .setPositiveButton("Send", (d, w) -> {
                    String want = et.getText().toString().trim();
                    if (!want.matches("[\\p{L} .]{2,40}")) { toastCustom("Please enter a valid name"); return; }
                    try {
                        JSONObject body = new JSONObject()
                                .put("current_name", savedProfileName)
                                .put("requested_name", want);
                        SupabaseRest.call("POST", "/rest/v1/name_change_requests", body.toString(),
                                "return=minimal", true, r -> toastCustom(r.ok()
                                        ? "Request sent to Admin."
                                        : SupabaseRest.errorText(r)));
                    } catch (Exception e) { toastCustom("Could not send request"); }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private boolean usernameValid() {
        String u = txt(etUsername);
        return (agnPhase || agn > 0) ? AgnRules.USERNAME_AGN.matcher(u).matches()
                : AgnRules.USERNAME_FREE.matcher(u).matches();
    }

    private boolean profileNameValid() {
        return isNidNamePublic || txt(etProfileName).matches("[\\p{L} .]{2,40}");
    }

    private void validateForm() {
        boolean ok = loaded && !busy && usernameValid() && profileNameValid();
        btnSubmit.setEnabled(ok);
        btnSubmit.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor(ok ? "#00C853" : "#B0BEC5")));
    }

    private void submit() {
        final long now = System.currentTimeMillis();
        final String typed = txt(etProfileName);
        final boolean wantsChange = !isNidNamePublic && !typed.equals(savedProfileName);
        final String block = wantsChange ? AgnRules.nameEditBlock(nameEdits, lastNameEdit, now) : null;
        final boolean nameChanged = wantsChange && block == null;
        final String newName = nameChanged ? typed : savedProfileName;
        nameNote = null;
        if (block != null) {
            etProfileName.setText(savedProfileName);
            nameNote = "LOCKED".equals(block)
                    ? "Profile Name is locked."
                    : "Profile Name unchanged. " + block;
        }
        btnSubmit.setEnabled(false);
        final String uname = txt(etUsername);
        if (!uname.equals(savedUsername) || agn == 0) claimUsername(uname, nameChanged, newName, now);
        else persist(nameChanged, newName, now);
    }

    private void claimUsername(String uname, boolean nameChanged, String newName, long now) {
        try {
            SupabaseRest.call("POST", "/rest/v1/rpc/claim_username",
                    new JSONObject().put("p_username", uname).toString(), null, true, r -> {
                if (isFinishing()) return;
                if (!r.ok()) { toastCustom(SupabaseRest.errorText(r)); validateForm(); return; }
                try {
                    JSONObject o = new JSONObject(r.body);
                    savedUsername = o.optString("username", uname);
                    agn = o.isNull("agn") ? 0 : o.optInt("agn", 0);
                    tvAgnNumber.setText(agn > 0 ? String.valueOf(agn) : "");
                } catch (Exception ignored) { savedUsername = uname; }
                persist(nameChanged, newName, now);
            });
        } catch (Exception e) { toastCustom("Save failed"); validateForm(); }
    }

    private void persist(boolean nameChanged, String newName, long now) {
        Db.getMine("profiles", (row, err) -> {
            if (isFinishing()) return;
            if (err != null) { toastCustom(err); validateForm(); return; }
            try {
                JSONObject info = row != null && row.optJSONObject("info") != null ? row.optJSONObject("info") : new JSONObject();
                String profileName = nameChanged ? newName : (savedProfileName.isEmpty() ? newName : savedProfileName);
                if (nameChanged) { nameEdits++; lastNameEdit = now; }

                String d = String.valueOf(parseInt(etDay)), m = String.valueOf(parseInt(etMonth)), y = String.valueOf(parseInt(etYear));
                String nidName = txt(etNidName);
                boolean nidPublic = isNidNamePublic && isNidVerified && !nidName.isEmpty();

                info.put("profileName", profileName);
                info.put("nameEdits", nameEdits);
                info.put("nameEditedAt", lastNameEdit);
                info.put("nidVerified", isNidVerified);
                info.put("nidName", isNidVerified ? nidName : "");
                info.put("nidFrontKey", isNidVerified ? nidFrontKey : "");
                info.put("nidBidKey", isNidVerified ? nidBidKey : "");
                info.put("nidNamePublic", nidPublic);
                info.put("recoveryEmail", txt(etRecoveryEmail));
                info.put("phone", txt(etPhone));
                info.put("dob", d + "/" + m + "/" + y);
                info.put("priv", priv);

                String shown = nidPublic ? nidName : profileName;
                JSONObject patch = new JSONObject().put("name", shown).put("info", info);
                String b = ProfileActivity.birthdayFromDob(d + "/" + m + "/" + y);
                if (b != null) patch.put("birthday", b);

                Db.patchMyProfile(patch, e2 -> {
                    if (isFinishing()) return;
                    if (e2 != null) { toastCustom(e2); validateForm(); return; }
                    savedProfileName = profileName;
                    toastCustom(nameNote == null ? "Account Updated Successfully!" : "Saved successfully. " + nameNote);
                    finish();
                });
            } catch (Exception e) { toastCustom("Save failed"); validateForm(); }
        });
    }

    private String txt(EditText e) { return e.getText().toString().trim(); }
    private int parseInt(EditText e) { try { return Integer.parseInt(txt(e)); } catch (Exception x) { return 0; } }
}
