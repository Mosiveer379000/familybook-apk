package com.familybook.app;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Add Family ফর্ম (Profile info → Personal info → Family-র পেন্সিল)।
 *
 * ফ্লো:  ফর্ম পূরণ → F / M / S / C ডকুমেন্ট আপলোড (OCR মিলিয়ে ডট সবুজ) → verify → সবুজ বর্ডার + verified ব্যাজ
 *        → Submit → profiles.info.familyMembers-এ সেভ → Profile info পেজে "Family Members :" ব্লক দেখায়।
 */
public class AddFamilyActivity extends BaseActivity {

    // privacy keys (profiles.info.familyMembers.priv)
    private static final String[] KEYS = {"father", "mother", "sister", "brother", "spouse", "son"};

    private boolean isUserAccountVerifiedInSettings = false;
    private String userAccountVerifiedName = "";

    private boolean isFatherVerified = false;
    private boolean isMotherVerified = false;
    private boolean isSpouseVerified = false;
    private String pendingSpouseType = null, pendingSpouseNumber = null; // স্পাউস ডকুমেন্টের ধরন ও নম্বর
    private boolean isChildVerified = false;
    private boolean isFormFullyVerified = false;
    private boolean verifying = false;
    private boolean loadingForm = false;
    private boolean isOther = false;    // Other হলে Spouse/Husband সারি পুরো লুকানো
    private boolean isFemale = false;   // Female হলে "Spouse" এর জায়গায় "Husband"

    private LinearLayout layoutContainerBorder;
    private TextView imgNotVerifiedHeader;
    private ImageView btnSubmit, btnVerify, btnReset, btnClose;
    private ProgressBar verificationProgressBar;

    private View rowFather, rowMother, rowSisters, rowBrother, rowSpouse, rowChild;

    private final List<String> sisterNamesList = new ArrayList<>();
    private final List<String> brotherNamesList = new ArrayList<>();
    private final List<String> spouseNamesList = new ArrayList<>();
    private final List<String> childNamesList = new ArrayList<>();

    // প্রতিটি সারির প্রাইভেসি: true = Public (গ্লোব), false = Only me (লক, ডিফল্ট)
    private final boolean[] pub = new boolean[6];

    private TextRecognizer textRecognizer;
    private Uri cameraImageUri;
    private File cameraFile;
    private String activeTargetType = "";

    private ActivityResultLauncher<String> galleryLauncher;
    private ActivityResultLauncher<Uri> cameraLauncher;
    private ActivityResultLauncher<String> permissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Me.isLoggedIn()) { finish(); return; }
        setContentView(R.layout.activity_add_family);

        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        initLaunchers();

        layoutContainerBorder = findViewById(R.id.layoutContainerBorder);
        imgNotVerifiedHeader = findViewById(R.id.imgNotVerifiedHeader);
        btnSubmit = findViewById(R.id.btnSubmit);
        btnVerify = findViewById(R.id.btnVerify);
        btnReset = findViewById(R.id.btnReset);
        btnClose = findViewById(R.id.btnClose);
        verificationProgressBar = findViewById(R.id.verificationProgressBar);

        rowFather = findViewById(R.id.rowFather);
        rowMother = findViewById(R.id.rowMother);
        rowSisters = findViewById(R.id.rowSisters);
        rowBrother = findViewById(R.id.rowBrother);
        rowSpouse = findViewById(R.id.rowSpouse);
        rowChild = findViewById(R.id.rowChild);

        styleNote();
        setupRowLabelsAndAddClicks();
        resetToBlankState();
        fetchUserAccountVerificationStatusAndPrefill();

        View[] rows = {rowFather, rowMother, rowSisters, rowBrother, rowSpouse, rowChild};
        for (int i = 0; i < rows.length; i++) setupPrivacyLock(rows[i], i);

        // নাম বদলালে আগের ভেরিফিকেশন বাতিল
        watchName(rowFather, () -> { isFatherVerified = false; setRowDotRed(rowFather); });
        watchName(rowMother, () -> { isMotherVerified = false; setRowDotRed(rowMother); });

        findViewById(R.id.btnUploadF).setOnClickListener(v -> {
            if (getInputValue(rowFather).isEmpty()) { showToast("Please enter Father's name first!"); return; }
            activeTargetType = "F";
            showImageSourceDialog();
        });

        findViewById(R.id.btnUploadM).setOnClickListener(v -> {
            if (getInputValue(rowMother).isEmpty()) { showToast("Please enter Mother's name first!"); return; }
            activeTargetType = "M";
            showImageSourceDialog();
        });

        findViewById(R.id.btnUploadS).setOnClickListener(v -> {
            commitPending();
            if (spouseNamesList.isEmpty()) { showToast("Please add " + spouseWord() + "'s name first!"); return; }
            activeTargetType = "S";
            showImageSourceDialog();
        });

        findViewById(R.id.btnUploadC).setOnClickListener(v -> {
            commitPending();
            if (childNamesList.isEmpty()) { showToast("Please add Child's name first!"); return; }
            if (!isUserAccountVerifiedInSettings || userAccountVerifiedName.isEmpty()) {
                showToast("Verify your NID in Settings first (Child certificate needs your name)");
                setRowDotRed(rowChild);
                return;
            }
            activeTargetType = "C";
            showImageSourceDialog();
        });

        btnVerify.setOnClickListener(v -> startVerify());

        btnSubmit.setOnClickListener(v -> {
            commitPending();
            if (isFormBlank()) { showToast("Form is empty."); return; }
            saveFamilyDataToProfile();
        });

        btnReset.setOnClickListener(v -> showResetConfirmationDialog());
        btnClose.setOnClickListener(v -> finish());
    }

    // ------------------------------------------------------------ verify

    private void startVerify() {
        if (verifying) return;
        commitPending();
        if (isFormBlank()) { showToast("Form is blank!"); return; }

        verifying = true;
        verificationProgressBar.setVisibility(View.VISIBLE);
        verificationProgressBar.setProgress(0);

        final Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(new Runnable() {
            int progress = 0;
            @Override public void run() {
                if (isFinishing()) return;
                if (progress <= 100) {
                    verificationProgressBar.setProgress(progress);
                    progress += 25;
                    handler.postDelayed(this, 60);
                    return;
                }
                verifying = false;
                verificationProgressBar.setVisibility(View.GONE);
                finishVerify();
            }
        }, 60);
    }

    private void finishVerify() {
        String err = null;
        if (getInputValue(rowFather).isEmpty() || !isFatherVerified) err = "Father NID Compulsory for Verification!";
        else if (getInputValue(rowMother).isEmpty() || !isMotherVerified) err = "Mother NID Compulsory for Verification!";
        else if (!spouseNamesList.isEmpty() && !isSpouseVerified) err = "Upload " + spouseWord() + " document (S) to verify!";
        else if (!childNamesList.isEmpty() && !isChildVerified) err = "Upload Child birth certificate (C) to verify!";

        if (err == null) {
            isFormFullyVerified = true;
            applyAutoVerifiedUI();
            showToast("System Verified Successfully!");
        } else {
            isFormFullyVerified = false;
            applyUnverifiedUI();
            showToast(err);
        }
    }

    // ------------------------------------------------------------ pickers / OCR

    private void initLaunchers() {
        permissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
            if (isGranted) launchCamera(); else showToast("Camera Permission Denied");
        });

        galleryLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) processImageWithMLKit(uri);
        });

        cameraLauncher = registerForActivityResult(new ActivityResultContracts.TakePicture(), success -> {
            if (success && cameraImageUri != null) processImageWithMLKit(cameraImageUri);
            else deleteCameraFile();
        });
    }

    private void showImageSourceDialog() {
        CharSequence[] options = {"Camera", "Gallery"};
        new AlertDialog.Builder(this)
                .setTitle("Select Document Source")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                            permissionLauncher.launch(Manifest.permission.CAMERA);
                        } else {
                            launchCamera();
                        }
                    } else {
                        galleryLauncher.launch("image/*");
                    }
                })
                .show();
    }

    private void launchCamera() {
        try {
            File dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
            if (dir != null && !dir.exists()) dir.mkdirs();
            cameraFile = new File(dir, "family_doc_" + System.currentTimeMillis() + ".jpg");
            cameraImageUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", cameraFile);
            cameraLauncher.launch(cameraImageUri);
        } catch (Exception e) {
            showToast("Could not open camera: " + e.getMessage());
        }
    }

    private void processImageWithMLKit(Uri uri) {
        showToast("Scanning Document...");
        try {
            InputImage image = InputImage.fromFilePath(this, uri);
            textRecognizer.process(image)
                    .addOnSuccessListener(textResult -> {
                        validateScannedData(textResult.getText());
                        deleteCameraFile();
                    })
                    .addOnFailureListener(e -> {
                        showToast("Failed to read image text. Unverified.");
                        deleteCameraFile();
                    });
        } catch (Exception e) {
            showToast("Error processing image.");
            deleteCameraFile();
        }
    }

    private void validateScannedData(String scannedText) {
        if (isFinishing()) return;
        String text = norm(scannedText);

        switch (activeTargetType) {
            case "F":
                if (nameMatches(text, getInputValue(rowFather))) {
                    isFatherVerified = true;
                    setRowDotGreen(rowFather);
                    showToast("Father NID Verified Successfully");
                } else {
                    isFatherVerified = false;
                    setRowDotRed(rowFather);
                    showToast("Father Name Not Found / Mismatch (Unverified)");
                }
                break;
            case "M":
                if (nameMatches(text, getInputValue(rowMother))) {
                    isMotherVerified = true;
                    setRowDotGreen(rowMother);
                    showToast("Mother NID Verified Successfully");
                } else {
                    isMotherVerified = false;
                    setRowDotRed(rowMother);
                    showToast("Mother Name Not Found / Mismatch (Unverified)");
                }
                break;
            case "S": {
                boolean ok = false;
                for (String n : spouseNamesList) if (nameMatches(text, n)) { ok = true; break; }
                isSpouseVerified = ok;
                pendingSpouseType = IdentityLink.detectType(scannedText);
                pendingSpouseNumber = ok ? IdentityLink.extractNumber(scannedText) : null;
                if (ok) {
                    setRowDotGreen(rowSpouse);
                    showToast(pendingSpouseNumber == null
                            ? spouseWord() + " Document Verified (ID number not readable: add relationship manually)"
                            : spouseWord() + " Document Verified Successfully");
                }
                else { setRowDotRed(rowSpouse); showToast(spouseWord() + " Name Not Found in Document!"); }
                break;
            }
            case "C": {
                boolean nameMatched = false;
                for (String c : childNamesList) if (nameMatches(text, c)) { nameMatched = true; break; }
                boolean parentMatched = nameMatches(text, userAccountVerifiedName);
                if (nameMatched && parentMatched) {
                    isChildVerified = true;
                    setRowDotGreen(rowChild);
                    showToast("Child Birth Certificate Verified Successfully");
                } else {
                    isChildVerified = false;
                    setRowDotRed(rowChild);
                    showToast("Child Name or Father Name Not Found in Birth Certificate!");
                }
                break;
            }
            default:
                break;
        }
        // কোনো ডকুমেন্ট বদলালে আগের "সম্পূর্ণ verified" অবস্থা আবার verify চেপে নিশ্চিত করতে হবে
        if (isFormFullyVerified) { isFormFullyVerified = false; applyUnverifiedUI(); }
    }

    /** ছোট হাতের অক্ষর, বিরামচিহ্ন বাদ, একাধিক স্পেস এক স্পেস */
    private static String norm(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.US).replaceAll("[^\\p{L}\\p{Nd}]+", " ").trim();
    }

    /** পুরো নাম সরাসরি পেলে বা নামের প্রতিটি শব্দ আলাদাভাবে পেলে মিল ধরা হয় (OCR-এ লাইন ভাঙলেও চলে) */
    private static boolean nameMatches(String normalizedText, String name) {
        String n = norm(name);
        if (n.isEmpty() || normalizedText.isEmpty()) return false;
        if (normalizedText.contains(n)) return true;
        String[] tokens = n.split(" ");
        int used = 0;
        for (String t : tokens) {
            if (t.length() < 2) continue;
            used++;
            if (!normalizedText.contains(t)) return false;
        }
        return used > 0;
    }

    private void deleteCameraFile() {
        try {
            if (cameraFile != null && cameraFile.exists()) cameraFile.delete();
        } catch (Exception ignored) { }
        cameraFile = null;
        cameraImageUri = null;
    }

    // ------------------------------------------------------------ load / prefill

    private void fetchUserAccountVerificationStatusAndPrefill() {
        Db.getMine("profiles", (row, err) -> {
            if (isFinishing() || err != null || row == null) return;
            JSONObject info = row.optJSONObject("info");
            if (info == null) return;
            isUserAccountVerifiedInSettings = info.optBoolean("nidVerified", false);
            String nidName = info.optString("nidName", "");
            String profileName = info.optString("profileName", "");
            userAccountVerifiedName = !nidName.isEmpty() ? nidName : profileName;

            String g = info.optString("gender", "").trim();
            if (!g.isEmpty()) {
                setGender(g);
            } else {
                // info.gender না থাকলে রেজিস্ট্রেশনের user_metadata থেকে
                SupabaseRest.call("GET", "/auth/v1/user", null, null, true, r -> {
                    if (isFinishing()) return;
                    try {
                        JSONObject meta = new JSONObject(r.body).optJSONObject("user_metadata");
                        if (meta != null) {
                            setGender(meta.optString("gender", "").trim());
                        }
                    } catch (Exception ignored) { }
                });
            }

            JSONObject fam = info.optJSONObject("familyMembers");
            if (fam != null) prefill(fam);
        });
    }

    private void setGender(String g) {
        isFemale = g.equalsIgnoreCase("female");
        isOther = g.equalsIgnoreCase("other");
        applySpouseLabel();
    }

    private String spouseWord() { return isFemale ? "Husband" : "Spouse"; }

    private void applySpouseLabel() {
        ((TextView) rowSpouse.findViewById(R.id.txtLabel)).setText(spouseWord());
        // Other জেন্ডারে স্বামী/স্ত্রীর অপশন থাকে না → সারি ও S বাটন দুটোই গায়েব
        rowSpouse.setVisibility(isOther ? View.GONE : View.VISIBLE);
        findViewById(R.id.btnUploadS).setVisibility(isOther ? View.GONE : View.VISIBLE);
        if (isOther) {
            spouseNamesList.clear();
            isSpouseVerified = false; pendingSpouseNumber = null;
            refreshChips(rowSpouse, spouseNamesList);
        }
    }

    private void prefill(JSONObject fam) {
        loadingForm = true;
        setInput(rowFather, fam.optString("father", ""));
        setInput(rowMother, fam.optString("mother", ""));
        readList(fam.optJSONArray("sisterList"), sisterNamesList);
        readList(fam.optJSONArray("brotherList"), brotherNamesList);
        readList(fam.optJSONArray("spouseList"), spouseNamesList);
        readList(fam.optJSONArray("sonList"), childNamesList);

        JSONObject priv = fam.optJSONObject("priv");
        for (int i = 0; i < KEYS.length; i++) pub[i] = priv != null && priv.optBoolean(KEYS[i], false);
        View[] rows = {rowFather, rowMother, rowSisters, rowBrother, rowSpouse, rowChild};
        for (int i = 0; i < rows.length; i++) applyLockIcon(rows[i], i);

        boolean verified = fam.optBoolean("verified", false);
        if (verified) {
            isFatherVerified = true;
            isMotherVerified = true;
            isSpouseVerified = !spouseNamesList.isEmpty();
            isChildVerified = !childNamesList.isEmpty();
            isFormFullyVerified = true;
            applyAutoVerifiedUI();
        }
        refreshAllChips();
        loadingForm = false;
    }

    private static void readList(JSONArray a, List<String> out) {
        out.clear();
        if (a == null) return;
        for (int i = 0; i < a.length(); i++) {
            String s = a.optString(i, "").trim();
            if (!s.isEmpty()) out.add(s);
        }
    }

    // ------------------------------------------------------------ rows

    private void styleNote() {
        TextView note = findViewById(R.id.txtNote);
        if (note == null) return;
        String full = "[ Note : Upload 4 Files to Get verified ]";
        SpannableString sp = new SpannableString(full);
        int i = full.indexOf("verified");
        sp.setSpan(new ForegroundColorSpan(Color.parseColor("#4CAF50")), i, i + 8, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        note.setText(sp);
    }

    private void setupRowLabelsAndAddClicks() {
        ((TextView) rowFather.findViewById(R.id.txtLabel)).setText("Father");
        ((TextView) rowMother.findViewById(R.id.txtLabel)).setText("Mother");
        ((TextView) rowSisters.findViewById(R.id.txtLabel)).setText("Sisters");
        ((TextView) rowBrother.findViewById(R.id.txtLabel)).setText("Brother");
        applySpouseLabel();
        ((TextView) rowChild.findViewById(R.id.txtLabel)).setText("Child");

        bindSplit(rowSisters, sisterNamesList, false);
        bindSplit(rowBrother, brotherNamesList, false);
        bindSplit(rowSpouse, spouseNamesList, true);
        bindSplit(rowChild, childNamesList, true);
    }

    private void bindSplit(View row, List<String> list, boolean hasDoc) {
        row.findViewById(R.id.btnAddChip).setOnClickListener(v -> addChip(row, list, hasDoc));
        ((EditText) row.findViewById(R.id.edtInput)).setOnEditorActionListener((tv, id, ev) -> {
            if (id == EditorInfo.IME_ACTION_DONE) { addChip(row, list, hasDoc); return true; }
            return false;
        });
    }

    private void addChip(View splitRow, List<String> list, boolean hasDoc) {
        EditText edt = splitRow.findViewById(R.id.edtInput);
        String name = edt.getText().toString().trim();
        if (name.isEmpty()) { showToast("Enter name first!"); return; }
        for (String s : list) {
            if (s.equalsIgnoreCase(name)) { showToast("Already added: " + name); return; }
        }
        list.add(name);
        edt.setText("");
        if (hasDoc) { // নাম বদলেছে → ডকুমেন্ট আবার আপলোড/ভেরিফাই লাগবে
            if (list == spouseNamesList) { isSpouseVerified = false; pendingSpouseNumber = null; setRowDotRed(rowSpouse); }
            if (list == childNamesList) { isChildVerified = false; setRowDotRed(rowChild); }
            unverifyIfNeeded();
        }
        refreshChips(splitRow, list);
        showToast("Added: " + name);
    }

    private void removeChip(View splitRow, List<String> list, String name) {
        new AlertDialog.Builder(this)
                .setMessage("Remove \"" + name + "\"?")
                .setPositiveButton("Remove", (d, w) -> {
                    list.remove(name);
                    if (list == spouseNamesList) { isSpouseVerified = false; pendingSpouseNumber = null; setRowDotRed(rowSpouse); unverifyIfNeeded(); }
                    if (list == childNamesList) { isChildVerified = false; setRowDotRed(rowChild); unverifyIfNeeded(); }
                    refreshChips(splitRow, list);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** আনকমিটেড লেখা থাকলে (Add চাপা হয়নি) সেটাও যোগ করে */
    private void commitPending() {
        commitOne(rowSisters, sisterNamesList, false);
        commitOne(rowBrother, brotherNamesList, false);
        commitOne(rowSpouse, spouseNamesList, true);
        commitOne(rowChild, childNamesList, true);
    }

    private void commitOne(View row, List<String> list, boolean hasDoc) {
        EditText edt = row.findViewById(R.id.edtInput);
        if (!edt.getText().toString().trim().isEmpty()) addChip(row, list, hasDoc);
    }

    private void refreshAllChips() {
        refreshChips(rowSisters, sisterNamesList);
        refreshChips(rowBrother, brotherNamesList);
        refreshChips(rowSpouse, spouseNamesList);
        refreshChips(rowChild, childNamesList);
    }

    private void refreshChips(View row, List<String> list) {
        FlowLayout flow = row.findViewById(R.id.flowChips);
        EditText count = row.findViewById(R.id.edtCount);
        flow.removeAllViews();
        for (String name : new ArrayList<>(list)) {
            TextView chip = new TextView(this);
            chip.setText(name);
            chip.setTextSize(14);
            chip.setTypeface(Typeface.DEFAULT_BOLD);
            chip.setTextColor(Color.parseColor("#333333"));
            chip.setPadding(dp(12), dp(5), dp(12), dp(5));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.parseColor("#D9D9D9"));
            bg.setCornerRadius(dp(20));
            chip.setBackground(bg);
            chip.setOnClickListener(v -> removeChip(row, list, name));
            flow.addView(chip);
        }
        flow.setVisibility(list.isEmpty() ? View.GONE : View.VISIBLE);
        // ফাঁকা অবস্থায় ঘর খালি; verified হলে 0 দেখায়
        count.setText(!list.isEmpty() ? String.valueOf(list.size()) : (isFormFullyVerified ? "0" : ""));
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void watchName(View row, Runnable onChanged) {
        ((EditText) row.findViewById(R.id.edtInput)).addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable e) {
                if (loadingForm) return;
                onChanged.run();
                unverifyIfNeeded();
            }
        });
    }

    private void unverifyIfNeeded() {
        if (loadingForm) return;
        if (isFormFullyVerified) {
            isFormFullyVerified = false;
            applyUnverifiedUI();
        }
    }

    // ------------------------------------------------------------ privacy

    private void setupPrivacyLock(View rowView, int idx) {
        ImageView imgLock = rowView.findViewById(R.id.imgLock);
        applyLockIcon(rowView, idx);
        imgLock.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Privacy Setting")
                .setItems(new String[]{"Only me", "Public"}, (dialog, which) -> {
                    pub[idx] = (which == 1);
                    applyLockIcon(rowView, idx);
                })
                .show());
    }

    private void applyLockIcon(View rowView, int idx) {
        ((ImageView) rowView.findViewById(R.id.imgLock))
                .setImageResource(pub[idx] ? R.drawable.ic_globe : R.drawable.ic_fam_lock);
    }

    // ------------------------------------------------------------ UI states

    /** Header ব্যাজ: Verified (সবুজ) / Not verified (লাল) — XML ব্যাকগ্রাউন্ড */
    private void setHeaderBadge(boolean verified) {
        imgNotVerifiedHeader.setText(verified ? "Verified" : "Not verified");
        imgNotVerifiedHeader.setTextColor(verified ? 0xFFFFFFFF : 0xFFFF7171);
        imgNotVerifiedHeader.setBackgroundResource(verified
                ? R.drawable.bg_badge_verified : R.drawable.bg_badge_not_verified);
    }

    private void applyAutoVerifiedUI() {
        layoutContainerBorder.setBackgroundResource(R.drawable.bg_green_border);
        setHeaderBadge(true);
        btnSubmit.setImageResource(R.drawable.ic_fam_submit_on);
        setRowDotGreen(rowFather);
        setRowDotGreen(rowMother);
        setRowDotGreen(rowSisters);
        setRowDotGreen(rowBrother);
        setRowDotGreen(rowSpouse);
        setRowDotGreen(rowChild);
        refreshAllChips(); // এখন ০ সংখ্যাও দেখাবে
    }

    private void applyUnverifiedUI() {
        layoutContainerBorder.setBackgroundResource(R.drawable.bg_gray_border);
        setHeaderBadge(false);
        btnSubmit.setImageResource(R.drawable.ic_fam_submit_off);
        dot(rowFather, isFatherVerified);
        dot(rowMother, isMotherVerified);
        dot(rowSisters, false);
        dot(rowBrother, false);
        dot(rowSpouse, isSpouseVerified);
        dot(rowChild, isChildVerified);
        refreshAllChips();
    }

    private void dot(View row, boolean green) {
        if (green) setRowDotGreen(row); else setRowDotRed(row);
    }

    private void setRowDotGreen(View row) {
        ((ImageView) row.findViewById(R.id.imgDot)).setImageResource(R.drawable.ic_green_dot);
    }

    private void setRowDotRed(View row) {
        ((ImageView) row.findViewById(R.id.imgDot)).setImageResource(R.drawable.ic_red_dot);
    }

    private void showResetConfirmationDialog() {
        AlertDialog alert = new AlertDialog.Builder(this)
                .setMessage("By Resetting information you will lose your verification & uploaded file will be deleted from the server\n\nAre your sure")
                .setCancelable(false)
                .setPositiveButton("OK", (dialog, id) -> resetToBlankState())
                .setNegativeButton("Cancel", (dialog, id) -> dialog.dismiss())
                .create();
        alert.show();
        if (alert.getWindow() != null) alert.getWindow().setBackgroundDrawable(new ColorDrawable(Color.WHITE));
    }

    private void resetToBlankState() {
        loadingForm = true;
        isFatherVerified = false;
        isMotherVerified = false;
        isSpouseVerified = false; pendingSpouseNumber = null;
        isChildVerified = false;
        isFormFullyVerified = false;

        sisterNamesList.clear();
        brotherNamesList.clear();
        spouseNamesList.clear();
        childNamesList.clear();
        for (int i = 0; i < pub.length; i++) pub[i] = false;

        View[] rows = {rowFather, rowMother, rowSisters, rowBrother, rowSpouse, rowChild};
        for (int i = 0; i < rows.length; i++) {
            clearInput(rows[i]);
            applyLockIcon(rows[i], i);
        }
        applyUnverifiedUI();
        loadingForm = false;
    }

    private boolean isFormBlank() {
        return getInputValue(rowFather).isEmpty() && getInputValue(rowMother).isEmpty()
                && sisterNamesList.isEmpty() && brotherNamesList.isEmpty()
                && spouseNamesList.isEmpty() && childNamesList.isEmpty();
    }

    private String getInputValue(View row) {
        EditText edt = row.findViewById(R.id.edtInput);
        return edt != null ? edt.getText().toString().trim() : "";
    }

    private void setInput(View row, String v) {
        EditText edt = row.findViewById(R.id.edtInput);
        if (edt != null) edt.setText(v);
    }

    private void clearInput(View row) {
        EditText edt = row.findViewById(R.id.edtInput);
        if (edt != null) edt.setText("");
        EditText edtCount = row.findViewById(R.id.edtCount);
        if (edtCount != null) edtCount.setText("");
        FlowLayout flow = row.findViewById(R.id.flowChips);
        if (flow != null) { flow.removeAllViews(); flow.setVisibility(View.GONE); }
    }

    // ------------------------------------------------------------ save

    /** ["Kohinoor", "Shahinur"] → "Kohinoor(1), Shahinur(2)" ; ফাঁকা হলে emptyText */
    private static String numbered(List<String> list, String emptyText) {
        if (list.isEmpty()) return emptyText;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            sb.append(list.get(i)).append("(").append(i + 1).append(")");
            if (i < list.size() - 1) sb.append(", ");
        }
        return sb.toString();
    }

    private static JSONArray toJson(List<String> list) {
        JSONArray a = new JSONArray();
        for (String s : list) a.put(s);
        return a;
    }

    private void saveFamilyDataToProfile() {
        Db.getMine("profiles", (row, err) -> {
            if (err != null) { showToast(err); return; }
            try {
                JSONObject info = row != null && row.optJSONObject("info") != null ? row.optJSONObject("info") : new JSONObject();
                JSONObject fam = new JSONObject();

                fam.put("father", getInputValue(rowFather));
                fam.put("mother", getInputValue(rowMother));
                // Profile info পেজে দেখানোর টেক্সট
                fam.put("sister", numbered(sisterNamesList, "(No Sister)"));
                fam.put("brother", numbered(brotherNamesList, "(No Brother)"));
                fam.put("spouse", isOther ? "" : numbered(spouseNamesList, "(No " + spouseWord() + ")"));
                fam.put("son", numbered(childNamesList, "(No Son)"));
                // ফর্ম আবার খুললে ভরার জন্য আসল তালিকা
                fam.put("sisterList", toJson(sisterNamesList));
                fam.put("brotherList", toJson(brotherNamesList));
                fam.put("spouseList", toJson(spouseNamesList));
                fam.put("sonList", toJson(childNamesList));

                JSONObject priv = new JSONObject();
                for (int i = 0; i < KEYS.length; i++) priv.put(KEYS[i], pub[i]);
                fam.put("priv", priv);

                fam.put("spouseLabel", spouseWord());
                fam.put("verified", isFormFullyVerified);
                info.put("familyMembers", fam);

                Db.patchMyProfile(new JSONObject().put("info", info), e2 -> {
                    if (e2 != null) { showToast(e2); return; }
                    final String saved = isFormFullyVerified ? "Verified & Saved Successfully!" : "Saved as Unverified";
                    if (!isOther && isSpouseVerified && pendingSpouseNumber != null) {
                        // স্পাউসের NID/BID নম্বর অন্য কারো ভেরিফাইড অ্যাকাউন্টের সাথে মিললে অটো Married
                        IdentityLink.linkSpouse(pendingSpouseType, pendingSpouseNumber, (matched, name, msg) -> {
                            String extra = matched
                                    ? "\nLinked with " + name + " (Relationship: Married)"
                                    : (msg.isEmpty() ? "\nNo account match: add relationship manually" : "\n" + msg + " - add relationship manually");
                            doneAndClose(saved + extra);
                        });
                    } else {
                        doneAndClose(saved);
                    }
                });
            } catch (Exception e) { showToast("Save failed"); }
        });
    }

    /** finish() এর পর TopToast থাকে না, তাই সিস্টেম Toast */
    private void doneAndClose(String msg) {
        android.widget.Toast.makeText(getApplicationContext(), msg, android.widget.Toast.LENGTH_LONG).show();
        finish();
    }

    private void showToast(String msg) {
        TopToast.show(this, msg);
    }

    @Override
    protected void onDestroy() {
        deleteCameraFile();
        super.onDestroy();
    }
}
