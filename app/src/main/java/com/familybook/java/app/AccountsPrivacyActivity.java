package com.familybook.app;

import android.animation.ObjectAnimator;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.util.Patterns;
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
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public class AccountsPrivacyActivity extends AppCompatActivity {

    private static final int GREEN = Color.parseColor("#2E9E44");
    private static final int BLACK = Color.parseColor("#000000");
    private static final int GRAY = Color.parseColor("#444444");
    private static final int AGN_EDITABLE_FROM = 1001;   // 1001 বা তার বেশি নম্বরের ইউজার নম্বর নিজে বদলাতে পারে
    private static final String[] GENDERS = {"Male", "Female", "Other"};
    private static final String[] BLOODS = {"A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-"};

    private EditText etNidName, etProfileName, etUsername, etMainEmail, etRecoveryEmail, etPhone,
            etCity, etHome, etNationality, etNidNumber, etDay, etMonth, etYear, etAgnNumber;
    private TextView tvGender, tvBlood, tvProgressPercent, tvNidTitle, tvBidTitle,
            tvLoadingText, tvVerifyStatus;
    private ImageView ivNidLockToggle, ivProfileLockToggle, ivUsernamePrivacy, ivMainEmailPrivacy,
            ivRecoveryPrivacy, ivPhonePrivacy, ivCityPrivacy, ivHomeTownPrivacy, ivNationalityPrivacy,
            ivGenderPrivacy, ivBloodPrivacy, ivNidNumberPrivacy, ivBirthdayPrivacy,
            ivFrontNidPreview, ivBidPreview;
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
    private int nameEdits = 0;
    private long lastNameEdit = 0;
    private boolean isNidVerified = false;
    private boolean isNidNamePublic = false;
    private boolean genderVerified = false;      // জেন্ডার NID/BRC থেকে এসেছে (তাই লক)
    private boolean busy = false;
    private boolean saving = false;
    private String nidFrontKey = "", nidBidKey = "";
    private String nidDoc = "";                  // "NID" বা "BRC" — কোন ডকুমেন্টে ভেরিফাই হয়েছে
    private String gender = "", blood = "";
    private boolean nameLockedByNid = false, nameLockedByCount = false;

    // ভেরিফাইয়ের সময়ের নাম / জন্মদিন / জেন্ডার (এগুলোর সাথে প্রোফাইলের মান না মিললে Verified থাকে না)
    private String verName = "", verDob = "", verGender = "";

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
            if (isGranted) toastCustom("Camera Permission Granted");
            else toastCustom("Camera Permission Denied");
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
        } catch (Exception ignored) { }
    }

    private void toastCustom(String message) {
        try {
            Toast toast = new Toast(this);
            toast.setDuration(Toast.LENGTH_SHORT);
            toast.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, 100);

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

    // ---------- ছবি বাছাই (আগের মতই) ----------
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
        etCity = findViewById(R.id.etCurrentCity);
        etHome = findViewById(R.id.etHomeTown);
        etNationality = findViewById(R.id.etNationality);
        etNidNumber = findViewById(R.id.etNidNumber);
        etDay = findViewById(R.id.etDay);
        etMonth = findViewById(R.id.etMonth);
        etYear = findViewById(R.id.etYear);
        tvGender = findViewById(R.id.tvGender);
        tvBlood = findViewById(R.id.tvBlood);
        etAgnNumber = findViewById(R.id.etAgnNumber);
        tvProgressPercent = findViewById(R.id.tvProgressPercent);
        tvLoadingText = findViewById(R.id.tvLoadingText);
        tvNidTitle = findViewById(R.id.tvNidTitle);
        tvBidTitle = findViewById(R.id.tvBidTitle);
        tvVerifyStatus = findViewById(R.id.tvVerifyStatus);
        ivNidLockToggle = findViewById(R.id.ivNidLockToggle);
        ivProfileLockToggle = findViewById(R.id.ivProfileLockToggle);
        ivUsernamePrivacy = findViewById(R.id.ivUsernamePrivacy);
        ivMainEmailPrivacy = findViewById(R.id.ivMainEmailPrivacy);
        ivRecoveryPrivacy = findViewById(R.id.ivRecoveryPrivacy);
        ivPhonePrivacy = findViewById(R.id.ivPhonePrivacy);
        ivCityPrivacy = findViewById(R.id.ivCityPrivacy);
        ivHomeTownPrivacy = findViewById(R.id.ivHomeTownPrivacy);
        ivNationalityPrivacy = findViewById(R.id.ivNationalityPrivacy);
        ivGenderPrivacy = findViewById(R.id.ivGenderPrivacy);
        ivBloodPrivacy = findViewById(R.id.ivBloodPrivacy);
        ivNidNumberPrivacy = findViewById(R.id.ivNidNumberPrivacy);
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
        etNidName.setEnabled(false);       // ভেরিফাই হলে অটো বসে
        etNidNumber.setEnabled(false);     // ভেরিফাই হলে অটো বসে

        applyUsernameFilter();
        validateForm();
        applyVerifiedUI();
    }

    private void applyUsernameFilter() {
        InputFilter lower = (src, s, e, d, ds, de) -> src.subSequence(s, e).toString().toLowerCase(Locale.US);
        InputFilter letters = (src, s, e, d, ds, de) -> {
            for (int i = s; i < e; i++) {
                char c = src.charAt(i);
                if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'))) return "";
            }
            return null;
        };
        etUsername.setFilters(new InputFilter[]{lower, letters, new InputFilter.LengthFilter(8)});
    }

    // ---------- প্রোফাইল লোড: রেজিস্ট্রেশনে যা দেওয়া হয়েছিল তা বসে, যা দেওয়া হয়নি তা খালি থাকে ----------
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
            genderVerified = isNidVerified && info.optBoolean("genderVerified", false);
            nidFrontKey = info.optString("nidFrontKey", "");
            nidBidKey = info.optString("nidBidKey", "");
            nidDoc = info.optString("nidDoc", "");
            priv = info.optJSONObject("priv") != null ? info.optJSONObject("priv") : new JSONObject();

            etProfileName.setText(savedProfileName);
            etUsername.setText(savedUsername);
            etNidName.setText(info.optString("nidName", ""));
            etNidNumber.setText(info.optString("nidNo", ""));
            etRecoveryEmail.setText(info.optString("recoveryEmail", ""));
            etPhone.setText(info.optString("phone", ""));
            etCity.setText(info.optString("currentCity", ""));
            etHome.setText(info.optString("hometown", ""));
            etNationality.setText(info.optString("nationality", ""));
            gender = info.optString("gender", "");
            tvGender.setText(gender);
            blood = info.optString("blood", "");
            tvBlood.setText(blood);
            String dob = info.optString("dob", "");
            if (dob.matches("\\d{1,2}/\\d{1,2}/\\d{4}")) setDobFields(dob);

            // ভেরিফাইয়ের সময়ের মান; প্রোফাইলের বর্তমান নাম/জন্মদিন/জেন্ডারের সাথে না মিললে Verified দেখাবে না
            verName = info.optString("nidVerName", "");
            verDob = info.optString("nidVerDob", "");
            verGender = info.optString("nidVerGender", "");
            String mismatchWhy = null;
            if (isNidVerified) {
                if (verName.isEmpty()) verName = info.optString("nidName", "");   // পুরনো অ্যাকাউন্টে স্ন্যাপশট নেই
                String curName = isNidNamePublic ? "" : savedProfileName;
                mismatchWhy = nidMismatch(verName, verDob, verGender, curName, dob, gender);
                if (mismatchWhy != null) {
                    dropVerification();
                    if (savedProfileName.isEmpty()) { savedProfileName = dbName; etProfileName.setText(savedProfileName); }
                }
            }

            applyVerifiedUI();
            if (mismatchWhy != null) {
                toastCustom("NID not verified: your " + mismatchWhy + " does not match the NID/BRC.");
            }
            loaded = true;
            fillFromMeta();
            applyAgnPhase();
        });
    }

    /** রেজিস্ট্রেশনের সময় দেওয়া তথ্য (ফোন, জাতীয়তা, জেন্ডার, ব্লাড, জন্মদিন) যেগুলো এখনও খালি, সেগুলো বসায় */
    private void fillFromMeta() {
        SupabaseRest.call("GET", "/auth/v1/user", null, null, true, r -> {
            if (isFinishing() || !r.ok() || r.body == null) return;
            try {
                JSONObject m = new JSONObject(r.body).optJSONObject("user_metadata");
                if (m == null) return;
                if (txt(etPhone).isEmpty()) etPhone.setText(m.optString("phone", "").trim());
                if (txt(etNationality).isEmpty()) etNationality.setText(m.optString("nationality", "").trim());
                if (gender.isEmpty()) { gender = m.optString("gender", "").trim(); tvGender.setText(gender); }
                if (blood.isEmpty()) { blood = m.optString("blood", "").trim(); tvBlood.setText(blood); }
                if (txt(etDay).isEmpty() && txt(etMonth).isEmpty() && txt(etYear).isEmpty()) {
                    String d = m.optString("dob", "").trim();
                    if (d.matches("\\d{1,2}/\\d{1,2}/\\d{4}")) setDobFields(d);
                }
                applyVerifiedUI();
            } catch (Exception ignored) { }
        });
    }

    /**
     * AGU নম্বর রেজিস্ট্রেশনের সময় একবারই অটো বসে (157 থেকে 1000, তারপর 1001 থেকে); Settings নতুন নম্বর দেয় না।
     * - 157-1000: নম্বর শুধু দেখায় (লক), নাম এডিট করা যায়।
     * - 1001 থেকে: নাম ও নম্বর দুটোই এডিট করা যায়।
     * - নম্বর নেই (পুরনো অ্যাকাউন্ট): নাম ও নম্বর দুটোই লক, নম্বরের জায়গায় "---"।
     */
    private void applyAgnPhase() {
        applyUsernameFilter();
        etAgnNumber.setText(agn > 0 ? String.valueOf(agn) : "---");
        boolean numberEditable = agn >= AGN_EDITABLE_FROM;
        etAgnNumber.setEnabled(numberEditable);
        etAgnNumber.setAlpha(numberEditable ? 1f : 0.8f);
        etUsername.setEnabled(agn > 0);
        etUsername.setAlpha(agn > 0 ? 1f : 0.6f);
        updateNamePrivacyUI();
        applyPrivacyIcons();
        validateForm();
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

    /** ভেরিফাই হলে অটো-পূরণ হওয়া ঘরগুলো সবুজ ও লক; রিসেট হলে আবার সাধারণ */
    private void applyVerifiedUI() {
        int c = isNidVerified ? GREEN : BLACK;
        etNidName.setTextColor(c);
        etNidNumber.setTextColor(c);
        etNationality.setTextColor(c);
        etDay.setTextColor(c);
        etMonth.setTextColor(c);
        etYear.setTextColor(c);
        tvGender.setTextColor(genderVerified ? GREEN : BLACK);
        etNationality.setEnabled(!isNidVerified);
        lockBirthday(isNidVerified);

        tvVerifyStatus.setText(isNidVerified ? "( Verified \u2714 )" : "( Optional )");
        tvVerifyStatus.setTextColor(isNidVerified ? GREEN : GRAY);
        boolean nid = isNidVerified && "NID".equals(nidDoc);
        boolean brc = isNidVerified && "BRC".equals(nidDoc);
        tvNidTitle.setText(nid ? "NID \u2714" : "NID");
        tvNidTitle.setTextColor(nid ? GREEN : Color.parseColor("#505050"));
        tvBidTitle.setText(brc ? "BRC \u2714" : "BRC");
        tvBidTitle.setTextColor(brc ? GREEN : Color.parseColor("#505050"));
    }

    // ---------- NID মিল যাচাই (নাম / জন্মদিন / জেন্ডার) ----------
    /** ভেরিফাইয়ের মান আর প্রোফাইলের মান তুলনা করে; অমিল থাকলে কোন তথ্য মেলেনি তার নাম, ঠিক থাকলে null। খালি মান বাদ যায়। */
    private String nidMismatch(String vName, String vDob, String vGender,
                               String curName, String curDob, String curGender) {
        if (!vName.isEmpty() && !curName.isEmpty() && !namesMatch(vName, curName)) return "Profile Name";
        if (!vDob.isEmpty() && !curDob.isEmpty() && !normDob(vDob).equals(normDob(curDob))) return "Birthday";
        if (!vGender.isEmpty() && !curGender.isEmpty() && !vGender.equalsIgnoreCase(curGender)) return "Gender";
        return null;
    }

    /** "05/03/1996" ও "5/3/1996" একই ধরা হয় */
    private static String normDob(String d) {
        try {
            String[] p = d.trim().split("/");
            return Integer.parseInt(p[0]) + "/" + Integer.parseInt(p[1]) + "/" + Integer.parseInt(p[2]);
        } catch (Exception e) {
            return d == null ? "" : d.trim();
        }
    }

    /** Md/Mohammad/Muhammad ইত্যাদি বাদ দিয়ে শব্দ মেলানো; এক নামের সব শব্দ অন্য নামে থাকলে মিল ধরা হয় */
    private static Set<String> nameTokens(String s) {
        Set<String> out = new HashSet<>();
        String[] parts = s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L} ]", " ").trim().split("\\s+");
        for (String t : parts) {
            if (t.isEmpty()) continue;
            if (t.equals("md") || t.equals("mohd") || t.equals("mohammad") || t.equals("mohammed")
                    || t.equals("muhammad") || t.equals("mohammod") || t.equals("mr")) continue;
            out.add(t);
        }
        return out;
    }

    private static boolean namesMatch(String a, String b) {
        Set<String> x = nameTokens(a), y = nameTokens(b);
        if (x.isEmpty() || y.isEmpty()) return a.trim().equalsIgnoreCase(b.trim());
        return x.containsAll(y) || y.containsAll(x);
    }

    /** ভেরিফিকেশন বাতিল (UI ও মেমরিতে); সার্ভারের ছবি রিসেট বাটনে মোছে */
    private void dropVerification() {
        isNidVerified = false;
        genderVerified = false;
        isNidNamePublic = false;
        nidDoc = "";
        verName = ""; verDob = ""; verGender = "";
        etNidName.setText("");
        etNidNumber.setText("");
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
        bindPrivacy(ivCityPrivacy, "currentCity");
        bindPrivacy(ivHomeTownPrivacy, "hometown");
        bindPrivacy(ivNationalityPrivacy, "nationality");
        bindPrivacy(ivGenderPrivacy, "gender");
        bindPrivacy(ivBloodPrivacy, "blood");
        bindPrivacy(ivNidNumberPrivacy, "nidNo");
        bindPrivacy(ivBirthdayPrivacy, "birthday");

        tvGender.setOnClickListener(v -> {
            if (genderVerified) { toastCustom("Gender is verified by your NID/BRC. Reset to change."); return; }
            new AlertDialog.Builder(this).setTitle("Gender")
                    .setItems(GENDERS, (d, w) -> { gender = GENDERS[w]; tvGender.setText(gender); })
                    .show();
        });
        tvBlood.setOnClickListener(v -> {
            CharSequence[] items = new CharSequence[BLOODS.length + 1];
            System.arraycopy(BLOODS, 0, items, 0, BLOODS.length);
            items[BLOODS.length] = "Clear";
            new AlertDialog.Builder(this).setTitle("Blood group")
                    .setItems(items, (d, w) -> {
                        blood = w < BLOODS.length ? BLOODS[w] : "";
                        tvBlood.setText(blood);
                    }).show();
        });

        TextWatcher w = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { validateForm(); }
            @Override public void afterTextChanged(Editable s) { }
        };
        etUsername.addTextChangedListener(w);
        etProfileName.addTextChangedListener(w);

        btnFrontNid.setOnClickListener(v -> showImageSourceDialog(true));
        btnUploadBid.setOnClickListener(v -> showImageSourceDialog(false));
        ivFrontNidPreview.setOnClickListener(v -> showImageSourceDialog(true));
        ivBidPreview.setOnClickListener(v -> showImageSourceDialog(false));

        btnVerify.setOnClickListener(v -> {
            if (uriNidFront == null && uriBid == null) { toastCustom("Please provide NID (front) or BRC image first"); return; }
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
        if (busy) return;
        if (isNidVerified) { toastCustom("Already verified. Tap Reset to verify a different document."); return; }
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
            if (isFrontNid) cameraFrontLauncher.launch(cameraImageUri);
            else cameraBidLauncher.launch(cameraImageUri);
        } catch (Exception e) {
            toastCustom("Could not open camera: " + e.getMessage());
        }
    }

    // ---------- প্রাইভেসি (গ্লোব / লক) ----------
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
        setLock(ivCityPrivacy, "currentCity");
        setLock(ivHomeTownPrivacy, "hometown");
        setLock(ivNationalityPrivacy, "nationality");
        setLock(ivGenderPrivacy, "gender");
        setLock(ivBloodPrivacy, "blood");
        setLock(ivNidNumberPrivacy, "nidNo");
        setLock(ivBirthdayPrivacy, "birthday");
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
    }

    // ---------- NID / BRC ভেরিফাই (RegisterActivity-এর মতো অটো-পূরণ) ----------
    private void verifyDocument() {
        if (busy) return;
        if (isNidVerified) { toastCustom("Already verified. Tap Reset to verify again."); return; }
        final Uri target = uriNidFront != null ? uriNidFront : uriBid;
        final boolean isBidDoc = uriNidFront == null;
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

        new Thread(() -> {
            final Bitmap bmp = loadBitmap(target);
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (bmp == null) { verifyFailed("Could not read image, please provide a clearer picture."); return; }
                // ১ম পাস: আসল ছবি, ২য় পাস: কনট্রাস্ট বাড়ানো কপি; দুটোর লেখা একসাথে পার্স হয়
                recognize(bmp, t1 -> recognize(NidImagePrep.forOcr(bmp), t2 -> onOcr(t1 + "\n" + t2, isBidDoc)));
            });
        }).start();
    }

    private interface TextDone { void on(String text); }

    private void recognize(Bitmap bmp, TextDone d) {
        recognizer.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener(r -> d.on(r.getText()))
                .addOnFailureListener(e -> d.on(""));
    }

    private void onOcr(String text, boolean isBidDoc) {
        final NidAutoFill.Result r = NidAutoFill.parse(text);
        if (!r.bangladeshi && r.name == null && r.dob == null) {
            verifyFailed("This does not look like a Bangladeshi NID or birth certificate. Try a sharper photo.");
            return;
        }
        if (!r.verified()) {
            verifyFailed("Some details could not be read. Try a sharper photo.");
            return;
        }

        // আগে থেকে থাকা Profile Name / Birthday / Gender-এর সাথে NID না মিললে ভেরিফাই হবে না
        String curDob = dobValue();
        String why = nidMismatch(
                r.name == null ? "" : r.name,
                r.dob == null ? "" : r.dob,
                r.male ? "Male" : "",
                isNidNamePublic ? "" : txt(etProfileName),
                curDob == null ? "" : curDob,
                gender);
        if (why != null) {
            verifyFailed("NID/BRC does not match your " + why + ". Verification not completed.");
            return;
        }

        final String idNumber = (r.nid != null && !r.nid.isEmpty()) ? r.nid : IdentityLink.extractNumber(text);
        uploadDoc(isBidDoc ? uriBid : uriNidFront, isBidDoc, () -> {
            IdentityLink.saveMine(isBidDoc ? "BID" : "NID", idNumber);   // স্বামী/স্ত্রী মেলানোর জন্য
            completeVerify(r, idNumber, isBidDoc);
        });
    }

    private void completeVerify(NidAutoFill.Result r, String idNumber, boolean isBidDoc) {
        // অটো জেনারেট: NID/BRC Name, Nationality, Gender, Birthday, NID/BRC No
        if (r.name != null) etNidName.setText(r.name);
        etNationality.setText("Bangladeshi");
        if (r.male) { gender = "Male"; tvGender.setText(gender); genderVerified = true; }
        if (r.dob != null) setDobFields(r.dob);
        etNidNumber.setText(idNumber == null ? "" : idNumber);
        nidDoc = isBidDoc ? "BRC" : "NID";
        verName = r.name == null ? "" : r.name;
        verDob = r.dob == null ? "" : normDob(r.dob);
        verGender = r.male ? "Male" : "";
        isNidVerified = true;
        applyVerifiedUI();

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
        validateForm();
        toastCustom("Verification Successful! Click Save to save.");
    }

    private void uploadDoc(Uri uri, boolean bid, Runnable ok) {
        tvLoadingText.setText("Uploading...");
        new Thread(() -> {
            byte[] data = null;
            try (InputStream in = getContentResolver().openInputStream(uri);
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

    /** ছবি লোড (লম্বা দিক ≤ 3000px) ও EXIF ঘোরানো ঠিক করা */
    private Bitmap loadBitmap(Uri uri) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(in, null, o); }
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / sample > 3000) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap bm;
            try (InputStream in = getContentResolver().openInputStream(uri)) { bm = BitmapFactory.decodeStream(in, null, o); }
            if (bm == null) return null;
            int rot = 0;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                int ori = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
                rot = ori == 6 ? 90 : ori == 3 ? 180 : ori == 8 ? 270 : 0;
            } catch (Exception ignored) { }
            if (rot != 0) {
                Matrix m = new Matrix();
                m.postRotate(rot);
                bm = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);
            }
            return bm;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- রিসেট ----------
    private void resetDocuments() {
        if (busy) return;
        if (!isNidVerified) { clearPickedDocs(); return; }
        new AlertDialog.Builder(this)
                .setTitle("NID Reset")
                .setMessage("NID/BRC images will be deleted and verification will reset. Do you want to continue?")
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
            isNidVerified = false; isNidNamePublic = false; genderVerified = false;
            nidFrontKey = ""; nidBidKey = ""; nidDoc = "";
            verName = ""; verDob = ""; verGender = "";
            etNidName.setText("");
            etNidNumber.setText("");
            clearPickedDocs();
            applyVerifiedUI();
            updateNamePrivacyUI();
            validateForm();
            toastCustom("NID reset successfully. Please verify again.");
        });
    }

    private void clearPickedDocs() {
        uriNidFront = null; uriBid = null;
        ivFrontNidPreview.setVisibility(View.GONE); btnFrontNid.setVisibility(View.VISIBLE);
        ivBidPreview.setVisibility(View.GONE); btnUploadBid.setVisibility(View.VISIBLE);
        layoutLoading.setVisibility(View.GONE);
        applyVerifiedUI();
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

    // ---------- যাচাই ----------
    private boolean usernameValid() {
        String u = txt(etUsername);
        return AgnRules.USERNAME_AGN.matcher(u).matches();
    }

    private boolean profileNameValid() {
        return isNidNamePublic || txt(etProfileName).matches("[\\p{L} .]{2,40}");
    }

    /** খালি হলে "", সঠিক হলে "+8801XXXXXXXXX", ভুল হলে null */
    private String phoneValue() {
        String p = txt(etPhone).replaceAll("[\\s-]", "");
        if (p.isEmpty()) return "";
        if (p.startsWith("+880")) p = p.substring(4);
        else if (p.startsWith("880")) p = p.substring(3);
        else if (p.startsWith("0")) p = p.substring(1);
        return p.matches("1[0-9]{9}") ? "+880" + p : null;
    }

    /** সব ঘর খালি হলে "", সঠিক হলে "d/m/yyyy", ভুল বা অর্ধেক হলে null */
    private String dobValue() {
        String d = txt(etDay), m = txt(etMonth), y = txt(etYear);
        if (d.isEmpty() && m.isEmpty() && y.isEmpty()) return "";
        if (d.isEmpty() || m.isEmpty() || y.length() != 4) return null;
        try {
            int di = Integer.parseInt(d), mi = Integer.parseInt(m), yi = Integer.parseInt(y);
            Calendar c = Calendar.getInstance();
            c.setLenient(false);
            c.clear();
            c.set(yi, mi - 1, di);
            Date date = c.getTime();
            if (yi < 1900 || date.after(new Date())) return null;
            return di + "/" + mi + "/" + yi;
        } catch (Exception e) {
            return null;
        }
    }

    /** Save বাটন সবসময় চালু ও সবুজ (ভেরিফিকেশন ঐচ্ছিক); ভুল থাকলে চাপার পর বার্তা দেখায় */
    private void validateForm() {
        btnSubmit.setEnabled(true);
        btnSubmit.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#00A477")));
    }

    // ---------- সেভ ----------
    // নিয়ম: কোনো একটি তথ্য ভুল বা লক থাকলে শুধু সেটি আগের মতো থাকে, বাকি সব তথ্য সেভ হয়।
    private final java.util.ArrayList<String> saveNotes = new java.util.ArrayList<>();

    private void submit() {
        if (saving) return;
        if (!loaded) { toastCustom("Please wait, loading your profile"); return; }
        if (busy) { toastCustom("Please wait, verification is running"); return; }
        saveNotes.clear();

        // --- Profile Name: ৩০/৬০/৯০ দিনের নিয়ম AgnRules-এ; ভুল বা লক হলে নামটি অপরিবর্তিত থাকে ---
        final long now = System.currentTimeMillis();
        final String typed = txt(etProfileName);
        final boolean wantsChange = !isNidNamePublic && !typed.equals(savedProfileName);
        boolean nameChanged = false;
        String newName = savedProfileName;
        if (wantsChange) {
            if (!typed.matches("[\\p{L} .]{2,40}")) {
                etProfileName.setText(savedProfileName);
                saveNotes.add("Profile Name unchanged (letters only, 2-40).");
            } else {
                String block = AgnRules.nameEditBlock(nameEdits, lastNameEdit, now);
                if (block == null) {
                    nameChanged = true;
                    newName = typed;
                } else {
                    etProfileName.setText(savedProfileName);
                    saveNotes.add("LOCKED".equals(block) ? "Profile Name is locked." : "Profile Name unchanged. " + block);
                }
            }
        }

        // --- AGU Name ও নম্বর ---
        // 157-1000: শুধু নামটুকু বদলানো যায়, নম্বর অটো ও লক। 1001 থেকে: নাম ও নম্বর দুটোই বদলানো যায়।
        String uname = txt(etUsername);
        int wantAgn = 0;                       // 0 = নম্বর বদলানো হচ্ছে না
        boolean claim = false;
        if (agn <= 0) {
            if (!uname.equals(savedUsername)) {
                etUsername.setText(savedUsername);
                saveNotes.add("AGU Name unchanged (no AGU number was assigned at registration).");
            }
        } else {
            boolean nameChanged2 = !uname.equals(savedUsername);
            if (nameChanged2 && !usernameValid()) {
                saveNotes.add("AGU Name unchanged (4-8 small letters a-z only).");
                etUsername.setText(savedUsername);
                uname = savedUsername;
                nameChanged2 = false;
            }
            boolean numChanged = false;
            String numTxt = txt(etAgnNumber);
            if (agn >= AGN_EDITABLE_FROM && !numTxt.equals(String.valueOf(agn))) {
                if (numTxt.matches("\\d{4,6}") && Integer.parseInt(numTxt) >= AGN_EDITABLE_FROM) {
                    wantAgn = Integer.parseInt(numTxt);
                    numChanged = true;
                } else {
                    saveNotes.add("AGU number unchanged (must be " + AGN_EDITABLE_FROM + " or higher).");
                    etAgnNumber.setText(String.valueOf(agn));
                }
            }
            claim = nameChanged2 || numChanged;
        }

        saving = true;
        if (claim) claimUsername(uname, wantAgn, nameChanged, newName, now);
        else persist(nameChanged, newName, now);
    }

    /** wantAgn > 0 হলে নম্বরও বদলানোর অনুরোধ যায় (শুধু 1001+ ইউজার); সার্ভার ইউনিক কিনা যাচাই করে */
    private void claimUsername(String uname, int wantAgn, boolean nameChanged, String newName, long now) {
        try {
            JSONObject body = new JSONObject().put("p_username", uname);
            if (wantAgn > 0) body.put("p_agn", wantAgn);
            SupabaseRest.call("POST", "/rest/v1/rpc/claim_username", body.toString(), null, true, r -> {
                if (isFinishing()) return;
                if (!r.ok()) {
                    // নাম/নম্বর বদলানো গেল না, তবু বাকি সব সেভ হবে
                    saveNotes.add("AGU Name/number not changed: " + SupabaseRest.errorText(r));
                    etUsername.setText(savedUsername);
                    etAgnNumber.setText(String.valueOf(agn));
                    persist(nameChanged, newName, now);
                    return;
                }
                try {
                    JSONObject o = new JSONObject(r.body);
                    savedUsername = o.optString("username", uname);
                    if (wantAgn > 0 && !o.isNull("agn")) agn = o.optInt("agn", agn);   // শুধু নম্বর বদলের অনুরোধে নম্বর নেওয়া হয়
                } catch (Exception ignored) { savedUsername = uname; }
                etAgnNumber.setText(String.valueOf(agn));
                persist(nameChanged, newName, now);
            });
        } catch (Exception e) {
            saveNotes.add("AGU Name/number not changed.");
            persist(nameChanged, newName, now);
        }
    }

    private void persist(boolean nameChanged, String newName, long now) {
        Db.getMine("profiles", (row, err) -> {
            if (isFinishing()) return;
            if (err != null) { toastCustom(err); saving = false; return; }
            try {
                JSONObject info = row != null && row.optJSONObject("info") != null ? row.optJSONObject("info") : new JSONObject();
                String profileName = nameChanged ? newName : (savedProfileName.isEmpty() ? newName : savedProfileName);
                if (nameChanged) { nameEdits++; lastNameEdit = now; }

                // সেভের আগে: নাম/জন্মদিন/জেন্ডার NID-এর সাথে না মিললে Verified বাতিল
                if (isNidVerified) {
                    String dobNow = dobValue();
                    String why = nidMismatch(verName, verDob, verGender,
                            isNidNamePublic ? "" : profileName,
                            dobNow == null ? "" : dobNow, gender);
                    if (why != null) {
                        dropVerification();
                        applyVerifiedUI();
                        updateNamePrivacyUI();
                        saveNotes.add("NID verification removed: " + why + " does not match your NID/BRC.");
                    }
                }

                String nidName = txt(etNidName);
                boolean nidPublic = isNidNamePublic && isNidVerified && !nidName.isEmpty();

                info.put("profileName", profileName);
                info.put("nameEdits", nameEdits);
                info.put("nameEditedAt", lastNameEdit);
                info.put("nidVerified", isNidVerified);
                info.put("nidDoc", isNidVerified ? nidDoc : "");
                info.put("nidName", isNidVerified ? nidName : "");
                info.put("nidNo", isNidVerified ? txt(etNidNumber) : "");
                info.put("nidVerName", isNidVerified ? verName : "");
                info.put("nidVerDob", isNidVerified ? verDob : "");
                info.put("nidVerGender", isNidVerified ? verGender : "");
                info.put("nidFrontKey", isNidVerified ? nidFrontKey : "");
                info.put("nidBidKey", isNidVerified ? nidBidKey : "");
                info.put("nidNamePublic", nidPublic);
                info.put("genderVerified", isNidVerified && genderVerified);
                info.put("currentCity", txt(etCity));
                info.put("hometown", txt(etHome));
                info.put("nationality", txt(etNationality));
                info.put("gender", gender);
                info.put("blood", blood);
                info.put("priv", priv);

                // ফোন / রিকভারি ইমেইল / জন্মদিন: ভুল হলে আগের মান থেকে যায়, বাকি সব সেভ হয়
                String phone = phoneValue();
                if (phone != null) info.put("phone", phone);
                else saveNotes.add("Phone unchanged (invalid number).");

                String rec = txt(etRecoveryEmail);
                if (rec.isEmpty() || Patterns.EMAIL_ADDRESS.matcher(rec).matches()) info.put("recoveryEmail", rec);
                else saveNotes.add("Recovery email unchanged (invalid).");

                String dob = dobValue();
                if (dob != null) info.put("dob", dob);
                else saveNotes.add("Birthday unchanged (invalid date).");

                String shown = nidPublic ? nidName : profileName;
                JSONObject patch = new JSONObject().put("info", info);
                if (!shown.isEmpty()) patch.put("name", shown);
                if (dob != null && !dob.isEmpty()) {
                    String b = ProfileActivity.birthdayFromDob(dob);
                    if (b != null) patch.put("birthday", b);
                }

                final String finalProfileName = profileName;
                Db.patchMyProfile(patch, e2 -> {
                    if (isFinishing()) return;
                    if (e2 != null) { toastCustom(e2); saving = false; return; }
                    savedProfileName = finalProfileName;
                    toastCustom(saveNotes.isEmpty() ? "Account Updated Successfully!"
                            : "Saved. " + android.text.TextUtils.join(" ", saveNotes));
                    finish();
                });
            } catch (Exception e) { toastCustom("Save failed"); saving = false; }
        });
    }

    private String txt(EditText e) { return e.getText().toString().trim(); }
}
