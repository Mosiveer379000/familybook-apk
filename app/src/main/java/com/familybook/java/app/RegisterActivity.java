package com.familybook.app;

import android.content.Intent;
import android.graphics.Color;
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

    private final ActivityResultLauncher<String> picker =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    certUri = uri;
                    tvUpload.setText("Selected ✔  (tap to change)");
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
            @Override public void afterTextChanged(Editable e) { showEmailHint(false); }
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
        tvUpload.setOnClickListener(v -> picker.launch("image/*"));
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
                if (certUri != null) uploadCert(RegisterActivity.this::goHome); else goHome();
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