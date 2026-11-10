package com.familybook.app;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.ExifInterface;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.graphics.Typeface;
import android.util.Patterns;
import android.view.View;
import android.widget.ScrollView;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.core.text.HtmlCompat;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class RegisterActivity extends AppCompatActivity {

    private static final String[] GENDERS = {"Male", "Female", "Other"};
    private static final String[] BLOODS = {"A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-"};
    private static final int STEP_NAME = 0, STEP_CONTACTS = 1, STEP_VERIFY = 2;   // ৩য় ধাপ এখন অস্থায়ী; পরে ৪,৫ যোগ হবে
    private int step = STEP_NAME;
    private static final int GREEN = Color.parseColor("#2E9E44");
    private static final int RED = Color.parseColor("#E53935");

    private EditText etName, etNation, etEmail, etPhone, etDay, etMonth, etYear, etPass, etAgu, etNidName;
    private TextView tvGender, tvBlood, tvUpload, tvRules, btnSubmit, btnBack, btnNext, tvAguNum, tvAguHint;
    private TextView starName, starNation, starEmail, starPhone, starDob, starGender, starPass, starAgu, starNidName;
    private CheckBox cbTerms;
    private TextView tvEmailHint;
    private View panelStep1, panelStep2, panelTemp;
    private StepperView stepper;
    private ScrollView scroll;
    private String gender = null;
    private String blood = "";
    private Uri certUri = null;

    // ---------- AGU অটো (Email লেখার সাথে সাথে) ----------
    private boolean aguSetByCode = false;     // কোড বসালে true; ইউজার নিজে লিখলে false
    private boolean aguTouched = false;       // ইউজার নিজে AGU ঘর বদলালে আর অটো বসবে না
    private int aguReq = 0;                   // পুরনো সার্ভার-উত্তর বাদ দেওয়ার জন্য
    private int nextAgnCache = 0;             // সার্ভার থেকে পাওয়া পরের নম্বর (Admin ছাড়া)
    private final Handler aguHandler = new Handler(Looper.getMainLooper());
    private Runnable aguFetch;

    // ---------- NID/BRC অটো-ভেরিফাই ----------
    private static final int NID_IDLE = 0, NID_LOADING = 1, NID_VERIFIED = 2;
    private static final int DEFAULT_TEXT = Color.parseColor("#333333");
    private boolean scanning = false, nidVerified = false;
    private String nidNumber = null;                       // শুধু ডিজিট; সার্ভারে হ্যাশ হয়ে সেভ হবে
    private String verName, verNation, verDob, verGender;  // ভেরিফাইয়ের সময়ের মান (বদলালে সবুজ রং চলে যায়)
    private TextView optionalView;                         // NID সারির ডানের "(Optional)" লেখা
    private CharSequence optionalDefault;
    private Drawable uploadBgDefault;
    private SpinnerDrawable spinner;
    private TextRecognizer recognizer;

    private final ActivityResultLauncher<String> picker =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    certUri = uri;
                    verifyNid(uri);
                }
            });

    private final TextWatcher watcher = new TextWatcher() {
        @Override public void beforeTextChanged(CharSequence c, int a, int b, int d) { }
        @Override public void onTextChanged(CharSequence c, int a, int b, int d) { }
        @Override public void afterTextChanged(Editable e) { refresh(); }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register);

        etName = findViewById(R.id.etName);
        etNation = findViewById(R.id.etNation);
        etEmail = findViewById(R.id.etEmail);
        etPhone = findViewById(R.id.etPhone);
        etDay = findViewById(R.id.etDay);
        etMonth = findViewById(R.id.etMonth);
        etYear = findViewById(R.id.etYear);
        etPass = findViewById(R.id.etPassword);
        tvGender = findViewById(R.id.tvGender);
        tvBlood = findViewById(R.id.tvBlood);
        btnBack = findViewById(R.id.btnBack);
        btnNext = findViewById(R.id.btnNext);
        panelStep1 = findViewById(R.id.panelStep1);
        panelStep2 = findViewById(R.id.panelStep2);
        panelTemp = findViewById(R.id.panelTemp);
        stepper = findViewById(R.id.stepper);
        scroll = findViewById(R.id.scroll);
        tvUpload = findViewById(R.id.tvUpload);
        uploadBgDefault = tvUpload.getBackground();
        // NID সারির শেষ ছোট লেখা ("(Optional)") — লোডিং চাকা ও verified ব্যাজ এখানে বসবে
        try {
            ViewGroup row = (ViewGroup) tvUpload.getParent().getParent();
            View last = row.getChildAt(row.getChildCount() - 1);
            if (last instanceof TextView) { optionalView = (TextView) last; optionalDefault = optionalView.getText(); }
        } catch (Exception ignored) { }
        tvRules = findViewById(R.id.tvPassRules);
        tvEmailHint = findViewById(R.id.tvEmailHint);
        etAgu = findViewById(R.id.etAgu);
        etNidName = findViewById(R.id.etNidName);
        tvAguNum = findViewById(R.id.tvAguNum);
        tvAguHint = findViewById(R.id.tvAguHint);
        btnSubmit = findViewById(R.id.btnSubmit);
        cbTerms = findViewById(R.id.cbTerms);
        ((TextView) findViewById(R.id.tvIntro)).setText(HtmlCompat.fromHtml(INTRO_HTML,
                HtmlCompat.FROM_HTML_MODE_LEGACY));
        starName = findViewById(R.id.starName);
        starNation = findViewById(R.id.starNation);
        starEmail = findViewById(R.id.starEmail);
        starPhone = findViewById(R.id.starPhone);
        starDob = findViewById(R.id.starDob);
        starGender = findViewById(R.id.starGender);
        starPass = findViewById(R.id.starPass);
        starAgu = findViewById(R.id.starAgu);
        starNidName = findViewById(R.id.starNidName);

        for (EditText e : new EditText[]{etName, etNation, etEmail, etPhone, etDay, etMonth,
                etYear, etPass, etAgu, etNidName}) {
            e.addTextChangedListener(watcher);
        }
        setupAguField();
        setupAguHint();
        etEmail.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int a, int b, int d) { }
            @Override public void onTextChanged(CharSequence c, int a, int b, int d) { }
            @Override public void afterTextChanged(Editable e) { showEmailHint(false); autoAgu(); }
        });
        etAgu.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int a, int b, int d) { }
            @Override public void onTextChanged(CharSequence c, int a, int b, int d) { }
            @Override public void afterTextChanged(Editable e) {
                if (aguSetByCode) return;
                aguTouched = e.length() > 0;   // একেবারে মুছে দিলে আবার অটো চালু
                if (!aguTouched) autoAgu();
            }
        });
        advance(etDay, etMonth, 2);
        advance(etMonth, etYear, 2);

        findViewById(R.id.btnClose).setOnClickListener(v -> finish());
        btnBack.setOnClickListener(v -> goBack());
        btnNext.setOnClickListener(v -> goNext());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { goBack(); }
        });
        tvBlood.setOnClickListener(v ->
                new AlertDialog.Builder(this).setTitle("Blood group")
                        .setItems(BLOODS, (d, w) -> {
                            blood = BLOODS[w];
                            tvBlood.setText(blood);
                        }).show());
        findViewById(R.id.btnSignIn).setOnClickListener(v -> finish());
        tvUpload.setOnClickListener(v -> { if (!scanning) picker.launch("image/*"); });
        tvGender.setOnClickListener(v ->
                new AlertDialog.Builder(this).setTitle("Gender")
                        .setItems(GENDERS, (d, w) -> {
                            gender = GENDERS[w];
                            tvGender.setText(gender);
                            refresh();
                        }).show());

        btnSubmit.setOnClickListener(v -> validateAndRegister());
        refresh();
        showStep(STEP_NAME);
    }

    private static final String INTRO_HTML =
            "<font color='#0A66FF'><b>familyBook</b></font> is a Bangladeshi social media app "
            + "designed for sharing photos, videos, and updates with your loved ones. It also "
            + "allows you to earn money conditionally through its monetization tools. Plus, with "
            + "<font color='#0A66FF'><b>\"familyChat\"</b></font>\u2014a dedicated messenger app for "
            + "communicating with your close-knit circle\u2014connecting has never been easier. "
            + "So why wait? <font color='#0A66FF'><b>Sign up</b></font> today and proudly join "
            + "our community as a valued member!";

    // ---------- ধাপ নেভিগেশন ----------
    private void showStep(int s) {
        step = s;
        panelStep1.setVisibility(s == STEP_NAME ? View.VISIBLE : View.GONE);
        panelStep2.setVisibility(s == STEP_CONTACTS ? View.VISIBLE : View.GONE);
        panelTemp.setVisibility(s == STEP_VERIFY ? View.VISIBLE : View.GONE);
        btnNext.setVisibility(s == STEP_VERIFY ? View.INVISIBLE : View.VISIBLE);
        stepper.setStep(s);
        scroll.post(() -> scroll.scrollTo(0, 0));
    }

    private void goBack() {
        if (step == STEP_NAME) finish(); else showStep(step - 1);
    }

    private void goNext() {
        if (step == STEP_NAME && validateStep1()) showStep(STEP_CONTACTS);
        else if (step == STEP_CONTACTS && validateStep2()) showStep(STEP_VERIFY);
    }

    /** ধাপ ১ এর যাচাই: নাম, জাতীয়তা, জেন্ডার, জন্মদিন লাগবে; ব্লাড ও NID/BRC ঐচ্ছিক */
    private boolean validateStep1() {
        if (s(etName).length() < 2) { err(etName, "Enter your full name"); return false; }
        if (s(etNation).length() < 2) { err(etNation, "Enter your nationality"); return false; }
        if (gender == null) { toast("Select your gender"); return false; }
        if (dobValue() == null) { err(etDay, "Enter a valid date of birth"); return false; }
        return true;
    }

    /** ধাপ ২ এর যাচাই: Email, Phone, AGU (username), Password লাগবে; NID/BRC Name স্বয়ংক্রিয়, বাধ্যতামূলক নয় */
    private boolean validateStep2() {
        if (!Patterns.EMAIL_ADDRESS.matcher(s(etEmail).toLowerCase()).matches()) { err(etEmail, "Enter a valid email"); return false; }
        if (phoneValue() == null) { err(etPhone, "Enter a valid mobile number (e.g. 01721932421)"); return false; }
        if (!aguValid()) { err(etAgu, "4-8 small letters (a-z) only"); return false; }
        if (!isStrong(etPass.getText().toString())) { err(etPass, "Password is not strong enough"); return false; }
        return true;
    }

    /** AGU ঘরে শুধু ইংরেজি অক্ষর, নিজে নিজে ছোট হাতের হয়ে যায় */
    private void setupAguField() {
        etAgu.setFilters(new InputFilter[]{
                (src, st, en, d, ds, de) -> {
                    StringBuilder b = new StringBuilder();
                    for (int k = st; k < en; k++) {
                        char c = Character.toLowerCase(src.charAt(k));
                        if (c >= 'a' && c <= 'z') b.append(c);
                    }
                    return b.toString();
                },
                new InputFilter.LengthFilter(8)});
    }

    /** "Auto Generated After Step-1 Completed" — Step-1 নীল ও গাঢ় */
    private void setupAguHint() {
        String t = "Auto Generated After Step-1 Completed";
        SpannableStringBuilder sb = new SpannableStringBuilder(t);
        int a = t.indexOf("Step-1");
        sb.setSpan(new ForegroundColorSpan(Color.parseColor("#0A66FF")), a, a + 6,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new StyleSpan(Typeface.BOLD), a, a + 6, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        tvAguHint.setText(sb);
    }

    /** কালো ঘরের অটো নম্বর (সার্ভার থেকে পেলে এখানে বসানো হবে) */
    void setAguNumber(int agn) {
        tvAguNum.setText(agn > 0 ? String.valueOf(agn) : "---");
    }

    /**
     * Primary Email লেখার সাথে সাথে: @-এর আগের অংশ থেকে শুধু a-z (সর্বোচ্চ ৮) AGU ঘরে বসে,
     * কালো ঘরে নম্বর আসে। Admin email = 379; বাকিদের পরের নম্বর AgnRules মতো (157 থেকে)।
     * যেমন masudd16@gmail.com -> masudd + 379
     */
    private void autoAgu() {
        String email = s(etEmail).toLowerCase();
        int at = email.indexOf('@');
        String local = at >= 0 ? email.substring(0, at) : email;

        if (!aguTouched) {
            StringBuilder b = new StringBuilder();
            for (int k = 0; k < local.length() && b.length() < 8; k++) {
                char c = local.charAt(k);
                if (c >= 'a' && c <= 'z') b.append(c);
            }
            aguSetByCode = true;
            etAgu.setText(b.toString());
            aguSetByCode = false;
        }

        if (aguFetch != null) aguHandler.removeCallbacks(aguFetch);
        aguReq++;
        if (AgnRules.ADMIN_EMAIL.equalsIgnoreCase(email.trim())) { setAguNumber(AgnRules.ADMIN_AGN); return; }
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) { setAguNumber(0); return; }
        if (nextAgnCache > 0) { setAguNumber(nextAgnCache); return; }

        final int req = aguReq;
        aguFetch = () -> SupabaseRest.call("POST", "/rest/v1/rpc/agn_next", "{}", null, false, r -> {
            if (isFinishing() || req != aguReq) return;
            int n = 0;
            if (r.ok() && r.body != null) {
                try { n = Integer.parseInt(r.body.trim()); } catch (Exception ignored) { }
            }
            if (n > 0) nextAgnCache = n;
            setAguNumber(n);
        });
        aguHandler.postDelayed(aguFetch, 500);   // টাইপ থামার ০.৫ সেকেন্ড পর একবার
    }

    /** NID/BRC ভেরিফাই হলে নাম বসবে (এখন এই ঘর শুধু পড়া যায়) */
    void setVerifiedNidName(String name) {
        etNidName.setText(name == null ? "" : name);
    }

    private boolean aguValid() {
        return AgnRules.USERNAME_AGN.matcher(s(etAgu)).matches();
    }

    /** ইমেইল ঘরের নিচের ছোট লেখা: সাধারণত "One Email for One Account", ইমেইল আগে থেকে থাকলে লাল সতর্কবার্তা */
    private void showEmailHint(boolean taken) {
        if (taken) {
            tvEmailHint.setText("This Gmail is already associated with an account");
            tvEmailHint.setTextColor(RED);
        } else {
            tvEmailHint.setText("One Email for One Account");
            tvEmailHint.setTextColor(Color.parseColor("#333333"));
        }
    }

    private void advance(EditText from, EditText to, int len) {
        from.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int a, int b, int d) { }
            @Override public void onTextChanged(CharSequence c, int a, int b, int d) { }
            @Override public void afterTextChanged(Editable e) {
                if (e.length() == len) to.requestFocus();
            }
        });
    }

    // ---------- Helpers ----------
    private String s(EditText e) { return e.getText().toString().trim(); }

    /** বাধ্যতামূলক: 01XXXXXXXXX / 1XXXXXXXXX / +8801XXXXXXXXX সব নেয়; সঠিক হলে "+8801XXXXXXXXX", নাহলে null */
    private String phoneValue() {
        String p = s(etPhone).replaceAll("[\\s-]", "");
        if (p.startsWith("+880")) p = p.substring(4);
        else if (p.startsWith("880")) p = p.substring(3);
        else if (p.startsWith("0")) p = p.substring(1);
        return p.matches("1[0-9]{9}") ? "+880" + p : null;
    }

    /** সঠিক হলে dd/MM/yyyy, নাহলে null */
    private String dobValue() {
        String d = s(etDay), m = s(etMonth), y = s(etYear);
        if (d.isEmpty() || m.isEmpty() || y.length() != 4) return null;
        try {
            int di = Integer.parseInt(d), mi = Integer.parseInt(m), yi = Integer.parseInt(y);
            Calendar c = Calendar.getInstance();
            c.setLenient(false);
            c.clear();
            c.set(yi, mi - 1, di);
            Date date = c.getTime();
            if (yi < 1900 || date.after(new Date())) return null;
            return String.format(Locale.US, "%02d/%02d/%04d", di, mi, yi);
        } catch (Exception e) {
            return null;
        }
    }

    private void refresh() {
        star(starName, s(etName).length() >= 2);
        star(starNation, s(etNation).length() >= 2);
        star(starEmail, Patterns.EMAIL_ADDRESS.matcher(s(etEmail).toLowerCase()).matches());
        star(starPhone, phoneValue() != null);
        star(starAgu, aguValid());
        star(starNidName, !s(etNidName).isEmpty());
        star(starDob, dobValue() != null);
        star(starGender, gender != null);
        // NID ভেরিফাই হওয়া ঘরগুলো সবুজ; হাতে বদলালে সাধারণ রঙে ফেরে
        String dv = dobValue();
        boolean dobG = nidVerified && dv != null && dv.equals(verDob);
        etName.setTextColor(nidVerified && s(etName).equals(verName) ? GREEN : DEFAULT_TEXT);
        etNation.setTextColor(nidVerified && s(etNation).equalsIgnoreCase(verNation) ? GREEN : DEFAULT_TEXT);
        tvGender.setTextColor(nidVerified && gender != null && gender.equals(verGender) ? GREEN : DEFAULT_TEXT);
        etNidName.setTextColor(nidVerified && s(etNidName).equals(verName) ? GREEN : DEFAULT_TEXT);
        for (EditText d : new EditText[]{etDay, etMonth, etYear}) d.setTextColor(dobG ? GREEN : DEFAULT_TEXT);
        String p = etPass.getText().toString();
        star(starPass, isStrong(p));
        showPassRules(p);
    }

    private void star(TextView t, boolean ok) { t.setTextColor(ok ? GREEN : RED); }

    // ---------- Validate ----------
    private void validateAndRegister() {
        String name = s(etName);
        String email = s(etEmail).toLowerCase();
        String pass = etPass.getText().toString();

        if (!validateStep1()) { showStep(STEP_NAME); return; }
        if (!validateStep2()) { showStep(STEP_CONTACTS); return; }
        String phone = phoneValue();
        String dob = dobValue();
        if (!cbTerms.isChecked()) { toast("Please accept the Terms & Conditions"); return; }

        btnSubmit.setEnabled(false);

        Map<String, String> meta = new HashMap<>();
        meta.put("name", name);
        meta.put("firstName", name);   // পুরনো কোডের সাথে মিল রাখতে
        meta.put("dob", dob);          // dd/MM/yyyy
        meta.put("gender", gender);
        meta.put("phone", phone);
        meta.put("username", s(etAgu));   // AGU Number-এর অক্ষরের অংশ; নম্বর সার্ভার বসায়
        meta.put("birthNo", "");       // নতুন ডিজাইনে NID/BRC ফাইল আপলোড হয়, নম্বর ঘর নেই
        meta.put("nationality", s(etNation));
        meta.put("blood", blood);      // ঐচ্ছিক, খালি হতে পারে

        AuthRepo.signUp(email, pass, meta, error -> {
            if (error != null) {
                btnSubmit.setEnabled(true);
                if (AuthRepo.EMAIL_EXISTS.equals(error)) {
                    // আগে থেকেই অ্যাকাউন্ট আছে: ভেরিফিকেশনে না গিয়ে এখানেই আটকানো
                    showStep(STEP_CONTACTS);
                    showEmailHint(true);
                    etEmail.requestFocus();
                    return;
                }
                toast("Registration failed: " + error);
                return;
            }
            if (SupabaseRest.isLoggedIn()) {
                // Supabase-এ "Confirm email" বন্ধ থাকলে সাইন-আপেই session চলে আসে। তবুও হোমে না গিয়ে
                // ইমেইলে কোড পাঠিয়ে সেটা বসাতে হবে; ঠিক কোড দিলে তবেই ফিডে যাবে।
                SupabaseRest.clearSession();
                AuthRepo.sendLoginCode(email, sendErr -> {
                    if (sendErr != null) {
                        btnSubmit.setEnabled(true);
                        toast("Could not send code: " + sendErr + ". Please try again from the login page.");
                        return;
                    }
                    askCode(email, pass, true);
                });
            } else {
                askCode(email, pass, false);     // ইমেইলে আসা কোড বসানোর ঘর
            }
        });
    }

    /** কোড বসানোর ঘর; ঠিক কোড দিলে সরাসরি ফিডে */
    private void askCode(final String email, final String pass, boolean loginMode) {
        EmailCodeDialog.show(this, email, loginMode, new EmailCodeDialog.Listener() {
            @Override public void onVerified() {
                toast("Email verified, welcome!");
                SecureStore.savePending(RegisterActivity.this, email, pass);
                AccountPhoto.refresh(RegisterActivity.this);
                // আগে রিকভারি তথ্য সেভ, তারপর NID ছবি আপলোড (সার্ভার ৫-১০ মিনিট পরে মুছবে), তারপর হোম
                saveRecovery(() -> {
                    if (certUri != null) uploadCert(RegisterActivity.this::goHome); else goHome();
                });
            }
            @Override public void onCancel() {
                btnSubmit.setEnabled(true);
            }
        });
    }

    private void err(EditText e, String msg) {
        e.setError(msg);
        e.requestFocus();
    }

    // ---------- NID/BRC ছবি → অটো-ফিল ----------
    private TextRecognizer rec() {
        if (recognizer == null) recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        return recognizer;
    }

    private void verifyNid(Uri uri) {
        scanning = true;
        setNidState(NID_LOADING);
        new Thread(() -> {
            final Bitmap bmp = loadBitmap(uri);
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (bmp == null) { nidFailed("Could not read this image"); return; }
                // ১ম পাস: আসল ছবি, ২য় পাস: কনট্রাস্ট বাড়ানো কপি; দুটো লেখা একসাথে পার্স হয়
                recognize(bmp, t1 -> recognize(NidImagePrep.forOcr(bmp), t2 -> onNidText(t1 + "\n" + t2)));
            });
        }).start();
    }

    private interface TextDone { void on(String text); }

    private void recognize(Bitmap bmp, TextDone d) {
        rec().process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener(r -> d.on(r.getText()))
                .addOnFailureListener(e -> d.on(""));
    }

    private void onNidText(String text) {
        scanning = false;
        NidAutoFill.Result r = NidAutoFill.parse(text);
        if (!r.bangladeshi && r.name == null && r.dob == null) {
            nidFailed("This does not look like a Bangladeshi NID or birth certificate. Try a sharper photo.");
            return;
        }
        // যা যা পাওয়া গেছে ফর্মে বসাও (সবই পরে হাতে বদলানো যায়)
        if (r.name != null) etName.setText(r.name);
        if (r.bangladeshi) etNation.setText("Bangladeshi");
        if (r.male) { gender = "Male"; tvGender.setText(gender); }     // Mohammad/Md থাকলে Male; নাহলে খালি থাকবে
        if (r.dob != null) {
            String[] p = r.dob.split("/");
            etDay.setText(p[0]); etMonth.setText(p[1]); etYear.setText(p[2]);
        }
        if (r.name != null) setVerifiedNidName(r.name);               // ধাপ ২-এর NID/BRC Name

        if (r.verified()) {
            nidVerified = true;
            nidNumber = r.nid;
            verName = r.name; verNation = "Bangladeshi"; verDob = r.dob;
            verGender = r.male ? "Male" : null;
            setNidState(NID_VERIFIED);
            toast("NID verified");
        } else {
            nidVerified = false;
            nidNumber = null;
            nidFailed("Some details could not be read. Try a sharper photo.");
        }
        View f = getCurrentFocus();
        if (f != null) f.clearFocus();
        refresh();
    }

    private void nidFailed(String msg) {
        scanning = false;
        nidVerified = false;
        nidNumber = null;
        certUri = null;
        setNidState(NID_IDLE);
        toast(msg);
        refresh();
    }

    /** বাটনের লেখা, ডানের চাকা/ব্যাজ — তিন অবস্থা */
    private void setNidState(int st) {
        if (spinner != null) { spinner.stop(); spinner = null; }
        if (optionalView != null) {
            optionalView.setCompoundDrawables(null, null, null, null);
            optionalView.setBackground(null);
            optionalView.setPadding(0, 0, 0, 0);
        }
        if (st == NID_LOADING) {
            tvUpload.setText("Verifying...");
            tvUpload.setBackground(uploadBgDefault);
            if (optionalView != null) {
                optionalView.setText("");
                spinner = new SpinnerDrawable();
                spinner.setBounds(0, 0, dp(18), dp(18));
                optionalView.setCompoundDrawables(spinner, null, null, null);   // ছোট গাঢ় লোডিং চাকা
                spinner.start();
            }
        } else if (st == NID_VERIFIED) {
            tvUpload.setText("Verified \u2714  (tap to change)");
            GradientDrawable g = new GradientDrawable();
            g.setColor(GREEN);
            g.setCornerRadius(dp(6));
            tvUpload.setBackground(g);
            if (optionalView != null) {
                optionalView.setText("verified");
                optionalView.setTextColor(GREEN);
                optionalView.setTextSize(11);
                optionalView.setTypeface(null, Typeface.BOLD);
                GradientDrawable badge = new GradientDrawable();
                badge.setColor(Color.parseColor("#E8F5E9"));
                badge.setStroke(dp(1), GREEN);
                badge.setCornerRadius(dp(8));
                optionalView.setBackground(badge);
                optionalView.setPadding(dp(5), dp(2), dp(5), dp(2));
            }
        } else {
            tvUpload.setText("Upload BRC or NID");
            tvUpload.setBackground(uploadBgDefault);
            if (optionalView != null) optionalView.setText(optionalDefault);
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
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

    /** ছোট গাঢ় ঘোরানো চাকা (বাইরের লাইব্রেরি লাগে না) */
    private final class SpinnerDrawable extends Drawable implements Runnable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Handler h = new Handler(Looper.getMainLooper());
        private float rot = 0;
        private boolean running = false;

        SpinnerDrawable() {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(Color.parseColor("#333333"));
        }
        void start() { running = true; h.post(this); }
        void stop() { running = false; h.removeCallbacks(this); }
        @Override public void run() {
            if (!running) return;
            rot = (rot + 30) % 360;
            invalidateSelf();
            h.postDelayed(this, 50);
        }
        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            float pad = paint.getStrokeWidth();
            c.drawArc(new RectF(b.left + pad, b.top + pad, b.right - pad, b.bottom - pad), rot, 270, false, paint);
        }
        @Override public void setAlpha(int a) { paint.setAlpha(a); }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) { paint.setColorFilter(cf); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    // ---------- রিকভারি টেবিলে সেভ (পাসওয়ার্ড রিসেটের জন্য) ----------
    /** ইমেইল কোড ঠিক হওয়ার পর (লগইন অবস্থায়) চলে। NID নম্বর সার্ভারে সিক্রেট পেপার দিয়ে হ্যাশ হয়; ব্যর্থ হলেও সাইন-আপ আটকায় না */
    private void saveRecovery(final Runnable done) {
        if (!nidVerified || nidNumber == null) { done.run(); return; }
        try {
            JSONObject b = new JSONObject()
                    .put("p_nid", nidNumber)
                    .put("p_nid_name", s(etNidName))
                    .put("p_birth", dobValue())
                    .put("p_email", s(etEmail).toLowerCase())
                    .put("p_agu", s(etAgu))
                    .put("p_name", s(etName));
            SupabaseRest.call("POST", "/rest/v1/rpc/save_nid_recovery", b.toString(), null, true,
                    r -> done.run());
        } catch (Exception e) {
            done.run();
        }
    }

    // ---------- জন্ম নিবন্ধনের ফাইল (ঐচ্ছিক) ----------
    private void uploadCert(final Runnable done) {
        try {
            InputStream in = getContentResolver().openInputStream(certUri);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while (in != null && (n = in.read(buf)) > 0) out.write(buf, 0, n);
            if (in != null) in.close();
            byte[] bytes = out.toByteArray();
            if (bytes.length > 5 * 1024 * 1024) {
                toast("File is larger than 5MB, not uploaded");
                done.run();
                return;
            }
            String mime = getContentResolver().getType(certUri);
            if (mime == null) mime = "image/jpeg";
            String ext = mime.contains("png") ? "png" : "jpg";
            Media.upload(bytes, "birth_cert", "birth_cert." + ext, mime, (key, e) -> {
                if (e != null) {
                    toast("File upload failed: " + e);
                    done.run();
                    return;
                }
                try {
                    JSONObject row = new JSONObject()
                            .put("user_id", SupabaseRest.uid())
                            .put("birth_cert_key", key);
                    Db.upsert("signup_extra", row, e2 -> done.run());
                } catch (Exception ex) {
                    done.run();
                }
            });
        } catch (Exception e) {
            done.run();
        }
    }

    // ---------- Password ----------
    private static boolean isStrong(String p) {
        return p.length() >= 8 && p.matches(".*[A-Z].*") && p.matches(".*[a-z].*")
                && p.matches(".*[0-9].*") && p.matches(".*[^A-Za-z0-9].*");
    }

    /** স্ক্রিনশটের মতো: অমিল থাকলে লাল "x", মিললে সবুজ "✓" (লেখা হুবহু ডিজাইনের) */
    private void showPassRules(String p) {
        String[] labels = {"8 Letters,", "1 UperCase (A-Z),", "LowerCase (a-z),",
                "Numbers (0-9),", "Special Character (@#%&..)"};
        boolean[] ok = {p.length() >= 8, p.matches(".*[A-Z].*"), p.matches(".*[a-z].*"),
                p.matches(".*[0-9].*"), p.matches(".*[^A-Za-z0-9].*")};
        int g = Color.parseColor("#1B8E3E"), r = Color.parseColor("#E53935");
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (int i = 0; i < labels.length; i++) {
            int st = sb.length();
            sb.append(ok[i] ? "\u2713 " : "x ").append(labels[i]);
            if (i < labels.length - 1) sb.append('\n');
            sb.setSpan(new ForegroundColorSpan(ok[i] ? g : r), st, sb.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new StyleSpan(Typeface.BOLD), st, st + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        tvRules.setText(sb);
    }

    private void goHome() {
        Intent i = new Intent(this, HomeActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_LONG).show();
    }
}