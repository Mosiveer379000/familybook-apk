package com.familybook.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.util.Base64;
import android.util.Patterns;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

public class LoginActivity extends AppCompatActivity {

    private static final String PREFS = "fb_auth";
    private static final String KEY_LAST_EMAIL = "last_email";
    private static final String KEY_REMEMBER = "remember_me";

    private EditText etUsername, etPassword;
    private CheckBox cbTogglePass, cbRememberMe;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // সেভ করা সেশন থাকলে সরাসরি হোমে যাবে
        Uri launchData = getIntent() == null ? null : getIntent().getData();
        boolean isAuthCallback = launchData != null && "familybook".equals(launchData.getScheme());
        if (!isAuthCallback && SupabaseRest.isLoggedIn() && SupabaseRest.uid() != null) {
            goHome();
            return;
        }

        setContentView(R.layout.activity_login);

        // ---------- Views: XML এর ID এর সাথে হুবহু মিল ----------
        etUsername   = findViewById(R.id.etUsername);
        etPassword   = findViewById(R.id.etPassword);
        cbTogglePass = findViewById(R.id.cbTogglePass);   // ✅ CheckBox
        cbRememberMe = findViewById(R.id.cbRememberMe);   // ✅ CheckBox

        TextView btnSign   = findViewById(R.id.btnSign);
        TextView tvForgot  = findViewById(R.id.tvForgot);
        TextView btnSignUp = findViewById(R.id.btnSignUp);
        View     btnClose  = findViewById(R.id.btnClose);

        // Remember me এর আগের অবস্থা restore
        cbRememberMe.setChecked(prefs().getBoolean(KEY_REMEMBER, false));

        setupSavedAccount();

        // ---------- Password Show/Hide (CheckBox) ----------
        cbTogglePass.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                // ✅ টিক পড়লে পাসওয়ার্ড দেখাবে
                etPassword.setTransformationMethod(HideReturnsTransformationMethod.getInstance());
            } else {
                // ⬜ টিক না থাকলে পাসওয়ার্ড লুকাবে
                etPassword.setTransformationMethod(PasswordTransformationMethod.getInstance());
            }
            // কার্সর শেষে রাখে, নাহলে লাফিয়ে যেতে পারে
            etPassword.setSelection(etPassword.getText().length());
        });

        // ---------- Click Listeners ----------
        btnSign.setOnClickListener(v -> attemptLogin());
        btnSignUp.setOnClickListener(v ->
                startActivity(new Intent(this, RegisterActivity.class)));
        tvForgot.setOnClickListener(v -> resetPassword());

        if (btnClose != null) {
            btnClose.setOnClickListener(v -> finish());
        }

        handleAuthCallback(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleAuthCallback(intent);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    // ---------- Saved Account ----------
    private void setupSavedAccount() {
        View row = findViewById(R.id.savedAccount);
        final boolean full = SecureStore.hasSaved(this);
        final String last = full ? SecureStore.savedEmail(this)
                                 : prefs().getString(KEY_LAST_EMAIL, null);

        if (last == null || last.isEmpty()) {
            row.setVisibility(View.GONE);
            return;
        }

        row.setVisibility(View.VISIBLE);
        String name = full ? SecureStore.savedName(this) : null;
        ((TextView) findViewById(R.id.tvSavedName))
                .setText((name != null && !name.isEmpty()) ? name : last);

        row.setOnClickListener(v -> {
            if (full) {
                etUsername.setText(last);
                doLogin(last, SecureStore.savedPass(this));
            } else {
                etUsername.setText(last);
                etPassword.requestFocus();
            }
        });

        findViewById(R.id.btnRemoveSaved).setOnClickListener(v ->
                new AlertDialog.Builder(this)
                        .setMessage("Remove saved login?")
                        .setPositiveButton("Remove", (d, w) -> {
                            SecureStore.clearSaved(this);
                            SecureStore.clearPending(this);
                            prefs().edit().remove(KEY_LAST_EMAIL).apply();
                            row.setVisibility(View.GONE);
                        })
                        .setNegativeButton("Cancel", null)
                        .show());
    }

    // ---------- Login ----------
    private void attemptLogin() {
        String email = etUsername.getText().toString().trim().toLowerCase();
        String pass  = etPassword.getText().toString();

        if (email.isEmpty()) { etUsername.setError("Email required"); return; }
        if (pass.isEmpty())  { etPassword.setError("Password required"); return; }
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            etUsername.setError("সঠিক ইমেইল দিন");
            return;
        }
        doLogin(email, pass);
    }

    private void doLogin(final String email, final String pass) {
        if (email == null || pass == null) {
            toast("Login করা যায়নি, email ও password দিন");
            return;
        }
        AuthRepo.login(email, pass, error -> {
            if (error == null) {
                prefs().edit()
                        .putString(KEY_LAST_EMAIL, email)
                        .putBoolean(KEY_REMEMBER, cbRememberMe.isChecked())
                        .apply();

                if (cbRememberMe.isChecked()) {
                    SecureStore.savePending(this, email, pass);
                } else {
                    SecureStore.clearSaved(this);
                    SecureStore.clearPending(this);
                }
                goHome();
            } else if (AuthRepo.isUnverified(error)) {
                showVerifyDialog(email);
            } else {
                toast("Email বা Password ভুল: " + error);
            }
        });
    }

    // ---------- Forgot Password ----------
    private EditText dialogField(String hint, int inputType) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setInputType(inputType);
        et.setSingleLine(true);
        return et;
    }

    private LinearLayout dialogBox(EditText... fields) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(64, 24, 64, 0);
        for (EditText f : fields) box.addView(f);
        return box;
    }

    private void resetPassword() {
        EditText etMail = dialogField("Email",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        String pre = etUsername.getText().toString().trim();
        if (!pre.isEmpty()) etMail.setText(pre);

        new AlertDialog.Builder(this)
                .setTitle("Forgot password")
                .setMessage("আপনার ইমেইল লিখুন।")
                .setView(dialogBox(etMail))
                .setPositiveButton("Send", (d, w) -> {
                    String in = etMail.getText().toString().trim().toLowerCase();
                    if (in.isEmpty()) { toast("ইমেইল লিখুন"); return; }
                    if (!Patterns.EMAIL_ADDRESS.matcher(in).matches()) {
                        toast("সঠিক ইমেইল দিন");
                        return;
                    }
                    AuthRepo.resetPassword(in, error -> {
                        if (error == null) {
                            toast(maskEmail(in) + " ঠিকানায় reset লিংক পাঠানো হয়েছে।");
                        } else {
                            toast("রিসেট লিংক পাঠানো যায়নি: " + error);
                        }
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private String maskEmail(String mail) {
        int at = mail.indexOf('@');
        if (at <= 0) return mail;
        String local = mail.substring(0, at);
        String shown = local.substring(0, Math.min(2, local.length()));
        return shown + "****" + mail.substring(at);
    }

    // ---------- Verify Dialog ----------
    private void showVerifyDialog(final String email) {
        new AlertDialog.Builder(this)
                .setTitle("ইমেইল verify হয়নি")
                .setMessage("আপনার ইমেইলে পাঠানো লিংকে ক্লিক করে verify করুন, তারপর login করুন।")
                .setPositiveButton("আবার পাঠান", (d, w) ->
                        AuthRepo.resendVerification(email, err -> toast(err == null
                                ? "নতুন লিংক পাঠানো হয়েছে" : "পাঠানো যায়নি: " + err)))
                .setNegativeButton("ঠিক আছে", null)
                .show();
    }

    // ---------- Auth Callback (email verify link) ----------
    private void handleAuthCallback(Intent intent) {
        Uri data = intent == null ? null : intent.getData();
        if (data == null || !"familybook".equals(data.getScheme())
                || !"auth".equals(data.getHost())) return;

        String frag = data.getFragment();
        setIntent(new Intent());
        if (frag == null) return;

        Map<String, String> p = new HashMap<>();
        for (String part : frag.split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0) p.put(part.substring(0, eq), Uri.decode(part.substring(eq + 1)));
        }
        String err = p.get("error_description");
        if (err != null) { toast(err.replace('+', ' ')); return; }

        String access  = p.get("access_token");
        String refresh = p.get("refresh_token");
        if (access == null || refresh == null) return;

        try {
            String[] seg = access.split("\\.");
            byte[] raw = Base64.decode(seg[1],
                    Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
            JSONObject claims = new JSONObject(new String(raw, StandardCharsets.UTF_8));
            String email = claims.optString("email", "");
            JSONObject user = new JSONObject()
                    .put("id", claims.optString("sub", ""))
                    .put("email", email);
            String ex = p.get("expires_in");
            long expires = ex == null ? 3600L : Long.parseLong(ex);
            JSONObject session = new JSONObject()
                    .put("access_token", access)
                    .put("refresh_token", refresh)
                    .put("expires_in", expires)
                    .put("user", user);
            SupabaseRest.saveSession(session.toString());
            prefs().edit().putString(KEY_LAST_EMAIL, email).apply();
            toast("ইমেইল verify হয়েছে, স্বাগতম!");
            goHome();
        } catch (Exception e) {
            toast("Verify সম্পন্ন হয়নি, আবার login করুন");
        }
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