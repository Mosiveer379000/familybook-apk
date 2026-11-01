package com.familybook.app;

import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextWatcher;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.text.style.ForegroundColorSpan;
import android.util.Patterns;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

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
    private static final int GREEN = Color.parseColor("#2E9E44");
    private static final int RED = Color.parseColor("#E53935");

    private EditText etName, etEmail, etPhone, etDay, etMonth, etYear, etBirthNo, etPass;
    private TextView tvGender, tvUpload, tvRules, btnSubmit, tvToggle;
    private TextView starName, starEmail, starPhone, starDob, starGender, starBirthNo, starPass;
    private CheckBox cbTerms;
    private String gender = null;
    private Uri certUri = null;

    private final ActivityResultLauncher<String> picker =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    certUri = uri;
                    tvUpload.setText("Selected ✔");
                    tvUpload.setTextColor(GREEN);
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
        etEmail = findViewById(R.id.etEmail);
        etPhone = findViewById(R.id.etPhone);
        etDay = findViewById(R.id.etDay);
        etMonth = findViewById(R.id.etMonth);
        etYear = findViewById(R.id.etYear);
        etBirthNo = findViewById(R.id.etBirthNo);
        etPass = findViewById(R.id.etPassword);
        tvGender = findViewById(R.id.tvGender);
        tvUpload = findViewById(R.id.tvUpload);
        tvRules = findViewById(R.id.tvPassRules);
        tvToggle = findViewById(R.id.tvTogglePass);
        btnSubmit = findViewById(R.id.btnSubmit);
        cbTerms = findViewById(R.id.cbTerms);
        starName = findViewById(R.id.starName);
        starEmail = findViewById(R.id.starEmail);
        starPhone = findViewById(R.id.starPhone);
        starDob = findViewById(R.id.starDob);
        starGender = findViewById(R.id.starGender);
        starBirthNo = findViewById(R.id.starBirthNo);
        starPass = findViewById(R.id.starPass);

        for (EditText e : new EditText[]{etName, etEmail, etPhone, etDay, etMonth,
                etYear, etBirthNo, etPass}) {
            e.addTextChangedListener(watcher);
        }
        advance(etDay, etMonth, 2);
        advance(etMonth, etYear, 2);

        findViewById(R.id.btnClose).setOnClickListener(v -> finish());
        findViewById(R.id.btnSignIn).setOnClickListener(v -> finish());
        tvUpload.setOnClickListener(v -> picker.launch("image/*"));
        tvGender.setOnClickListener(v ->
                new AlertDialog.Builder(this).setTitle("Gender")
                        .setItems(GENDERS, (d, w) -> {
                            gender = GENDERS[w];
                            tvGender.setText(gender);
                            refresh();
                        }).show());

        tvToggle.setOnClickListener(v -> {
            boolean hidden = etPass.getTransformationMethod() instanceof PasswordTransformationMethod;
            etPass.setTransformationMethod(hidden
                    ? HideReturnsTransformationMethod.getInstance()
                    : PasswordTransformationMethod.getInstance());
            tvToggle.setText(hidden ? "HIDE" : "SHOW");
            etPass.setSelection(etPass.getText().length());
        });

        btnSubmit.setOnClickListener(v -> validateAndRegister());
        refresh();
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

    /** ঐচ্ছিক: খালি হলে "", সঠিক হলে "+880...", ভুল হলে null */
    private String phoneValue() {
        String p = s(etPhone);
        if (p.startsWith("0")) p = p.substring(1);
        if (p.isEmpty()) return "";
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
        star(starEmail, Patterns.EMAIL_ADDRESS.matcher(s(etEmail).toLowerCase()).matches());
        star(starPhone, !s(etPhone).isEmpty() && phoneValue() != null);
        star(starDob, dobValue() != null);
        star(starGender, gender != null);
        star(starBirthNo, s(etBirthNo).matches("\\d{17}"));
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
        String bn = s(etBirthNo);

        if (name.length() < 2) { err(etName, "পুরো নাম দিন"); return; }
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) { err(etEmail, "সঠিক ইমেইল দিন"); return; }
        String phone = phoneValue();
        if (phone == null) { err(etPhone, "সঠিক মোবাইল নম্বর দিন (যেমন 1721932421)"); return; }
        String dob = dobValue();
        if (dob == null) { err(etDay, "সঠিক জন্ম তারিখ দিন"); return; }
        if (gender == null) { toast("Gender বেছে নিন"); return; }
        if (!bn.isEmpty() && !bn.matches("\\d{17}")) { err(etBirthNo, "জন্ম নিবন্ধন নম্বর ১৭ ডিজিটের হতে হবে"); return; }
        if (!isStrong(pass)) { err(etPass, "পাসওয়ার্ড শক্তিশালী নয়"); return; }
        if (!cbTerms.isChecked()) { toast("Terms & Conditions-এ টিক দিন"); return; }

        btnSubmit.setEnabled(false);

        Map<String, String> meta = new HashMap<>();
        meta.put("name", name);
        meta.put("firstName", name);   // পুরনো কোডের সাথে মিল রাখতে
        meta.put("dob", dob);          // dd/MM/yyyy
        meta.put("gender", gender);
        meta.put("phone", phone);      // ঐচ্ছিক, খালি হতে পারে
        meta.put("birthNo", bn);       // ঐচ্ছিক, খালি হতে পারে

        AuthRepo.signUp(email, pass, meta, error -> {
            if (error != null) {
                btnSubmit.setEnabled(true);
                toast("রেজিস্ট্রেশন ব্যর্থ হয়েছে: " + error);
                return;
            }
            if (SupabaseRest.isLoggedIn()) {
                // Supabase-এ "Confirm email" বন্ধ থাকলে সাইন-আপেই session চলে আসে। তবুও হোমে না গিয়ে
                // ইমেইলে কোড পাঠিয়ে সেটা বসাতে হবে; ঠিক কোড দিলে তবেই ফিডে যাবে।
                SupabaseRest.clearSession();
                AuthRepo.sendLoginCode(email, sendErr -> {
                    if (sendErr != null) {
                        btnSubmit.setEnabled(true);
                        toast("কোড পাঠানো যায়নি: " + sendErr + "। লগইন পেজ থেকে আবার চেষ্টা করুন।");
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
                toast("ইমেইল verify হয়েছে, স্বাগতম!");
                SecureStore.savePending(RegisterActivity.this, email, pass);
                AccountPhoto.refresh(RegisterActivity.this);
                if (certUri != null) uploadCert(RegisterActivity.this::goHome); else goHome();
            }
            @Override public void onCancel() {
                btnSubmit.setEnabled(true);
                toast("কোড না দিলে অ্যাকাউন্ট চালু হবে না। আবার Sign up চাপুন বা লগইন করে কোড দিন।");
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
                toast("ফাইল ৫MB-এর বেশি, তাই আপলোড হয়নি");
                done.run();
                return;
            }
            String mime = getContentResolver().getType(certUri);
            if (mime == null) mime = "image/jpeg";
            String ext = mime.contains("png") ? "png" : "jpg";
            Media.upload(bytes, "birth_cert", "birth_cert." + ext, mime, (key, e) -> {
                if (e != null) {
                    toast("ফাইল আপলোড হয়নি: " + e);
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

    private void showPassRules(String p) {
        String[] labels = {"কমপক্ষে ৮ অক্ষর", "বড় হাতের অক্ষর (A-Z)", "ছোট হাতের অক্ষর (a-z)",
                "সংখ্যা (0-9)", "বিশেষ চিহ্ন (* # @ ! ...)"};
        boolean[] ok = {p.length() >= 8, p.matches(".*[A-Z].*"), p.matches(".*[a-z].*"),
                p.matches(".*[0-9].*"), p.matches(".*[^A-Za-z0-9].*")};
        int g = Color.parseColor("#1B8E3E"), r = Color.parseColor("#D32F2F");
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (int i = 0; i < labels.length; i++) {
            int st = sb.length();
            sb.append(ok[i] ? "✔ " : "✘ ").append(labels[i]).append('\n');
            sb.setSpan(new ForegroundColorSpan(ok[i] ? g : r), st, sb.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        boolean strong = isStrong(p);
        int st = sb.length();
        sb.append(p.isEmpty() ? "" : (strong ? "Strong password ✔" : "Weak password"));
        sb.setSpan(new ForegroundColorSpan(strong ? g : r), st, sb.length(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
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