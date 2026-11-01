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
import android.widget.ImageView;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.os.Build;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import java.util.List;
import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;
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

    private boolean formShown = false;
    private boolean removeMode = false;
    private boolean passShown = false;

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

        // আগে কোনো অ্যাকাউন্ট লগইন হয়ে থাকলে Switch Account পেজ, না হলে সাধারণ লগইন ফর্ম
        boolean addMode = getIntent() != null && getIntent().getBooleanExtra("add_account", false);
        if (!isAuthCallback && !addMode && !SecureStore.cards(this).isEmpty()) {
            showSwitch();
        } else {
            showForm();
        }

        handleAuthCallback(getIntent());
    }

    @Override
    public void onBackPressed() {
        if ((formShown || passShown) && !SecureStore.cards(this).isEmpty()) {
            showSwitch();
            return;
        }
        super.onBackPressed();
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

    // ---------- সাধারণ লগইন ফর্ম ----------
    private void showForm() {
        formShown = true;
        passShown = false;
        setContentView(R.layout.activity_login);

        etUsername   = findViewById(R.id.etUsername);
        etPassword   = findViewById(R.id.etPassword);
        cbTogglePass = findViewById(R.id.cbTogglePass);
        cbRememberMe = findViewById(R.id.cbRememberMe);

        TextView btnSign   = findViewById(R.id.btnSign);
        TextView tvForgot  = findViewById(R.id.tvForgot);
        TextView btnSignUp = findViewById(R.id.btnSignUp);
        View     btnClose  = findViewById(R.id.btnClose);

        cbRememberMe.setChecked(prefs().getBoolean(KEY_REMEMBER, true));

        String last = prefs().getString(KEY_LAST_EMAIL, null);
        if (last != null && !last.isEmpty()) etUsername.setText(last);

        cbTogglePass.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                etPassword.setTransformationMethod(HideReturnsTransformationMethod.getInstance());
            } else {
                etPassword.setTransformationMethod(PasswordTransformationMethod.getInstance());
            }
            etPassword.setSelection(etPassword.getText().length());
        });

        btnSign.setOnClickListener(v -> attemptLogin());
        btnSignUp.setOnClickListener(v ->
                startActivity(new Intent(this, RegisterActivity.class)));
        tvForgot.setOnClickListener(v -> resetPassword());

        if (btnClose != null) {
            btnClose.setOnClickListener(v -> {
                if (!SecureStore.cards(this).isEmpty()) showSwitch(); else finish();
            });
        }
    }

    // ---------- Switch Account পেজ ----------
    private void showSwitch() {
        formShown = false;
        passShown = false;
        removeMode = false;
        setContentView(R.layout.activity_switch_account);

        // উপরের লেখা: serif, হালকা ধূসর (weight 200); বোল্ড অংশগুলো হুবহু নিচের মতো
        Typeface base = Typeface.SERIF;
        if (Build.VERSION.SDK_INT >= 28) base = Typeface.create(Typeface.SERIF, 200, false);

        TextView intro = findViewById(R.id.tvSwIntro);
        intro.setTypeface(base);
        intro.setText(styled(
                "Switch Account from you saved login info card to login faster, & easily. "
                        + "To delete Any saved login? click three dot menu > select card > remove account.",
                "Switch Account", "three dot menu", "select card", "remove account."));

        TextView bottom = findViewById(R.id.tvSwBottom);
        bottom.setTypeface(base);
        bottom.setText(styled("If you have an account you can simply add here", "an account"));

        findViewById(R.id.btnSwSignIn).setOnClickListener(v -> showForm());
        findViewById(R.id.btnSwDots).setOnClickListener(v -> {
            removeMode = !removeMode;           // থ্রি ডট: Remove বাটন দেখায়/লুকায়
            renderCards();
        });

        renderCards();
    }

    /** text-এর ভেতরের bold অংশগুলোকে বোল্ড ও একটু গাঢ় ধূসর করে */
    private SpannableStringBuilder styled(String text, String... bolds) {
        SpannableStringBuilder sb = new SpannableStringBuilder(text);
        for (String b : bolds) {
            int i = text.indexOf(b);
            if (i < 0) continue;
            sb.setSpan(new StyleSpan(Typeface.BOLD), i, i + b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new ForegroundColorSpan(0xFF4A4A4A), i, i + b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sb;
    }

    private void renderCards() {
        LinearLayout list = findViewById(R.id.cardList);
        list.removeAllViews();
        LayoutInflater inf = LayoutInflater.from(this);

        for (final SecureStore.Card c : SecureStore.cards(this)) {
            View row = inf.inflate(R.layout.item_switch_card, list, false);

            ((TextView) row.findViewById(R.id.tvCardName)).setText(displayName(c));

            ImageView photo = row.findViewById(R.id.imgCardAvatar);
            Bitmap bm = AccountPhoto.load(this, c.uid);
            if (bm != null) {
                RoundedBitmapDrawable rd = RoundedBitmapDrawableFactory.create(getResources(), bm);
                rd.setCircular(true);
                photo.setImageDrawable(rd);
            }

            View remove = row.findViewById(R.id.btnCardRemove);
            remove.setVisibility(removeMode ? View.VISIBLE : View.GONE);
            remove.setOnClickListener(v -> confirmRemove(c));

            row.setOnClickListener(v -> {
                if (removeMode) return;
                onCardTap(c);
            });
            list.addView(row);
        }
    }

    private String displayName(SecureStore.Card c) {
        return (c.name != null && !c.name.isEmpty()) ? c.name : c.email;
    }

    private void confirmRemove(final SecureStore.Card c) {
        new AlertDialog.Builder(this)
                .setMessage("Remove " + displayName(c) + " from saved logins?")
                .setPositiveButton("Remove", (d, w) -> {
                    SecureStore.removeCard(this, c.uid);
                    if (SecureStore.cards(this).isEmpty()) showForm(); else renderCards();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void setLoading(boolean on) {
        View l = findViewById(R.id.swLoading);
        if (l != null) l.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    /** পাসওয়ার্ড সেভ থাকলে সরাসরি লগইন (লোডিং দেখিয়ে); না থাকলে শুধু পাসওয়ার্ডের ঘর */
    private void onCardTap(final SecureStore.Card c) {
        if (c.hasPass()) {
            setLoading(true);
            doLogin(c.email, c.pass, true, () -> {
                setLoading(false);
                askPassword(c);
            });
        } else {
            askPassword(c);
        }
    }

    /** পাসওয়ার্ড সেভ নেই: লোগো, ছোট লেখা, গোল ছবি + নাম, পাসওয়ার্ড বক্স ও নীল Login বাটন */
    private void askPassword(final SecureStore.Card c) {
        formShown = false;
        passShown = true;
        setContentView(R.layout.activity_card_password);

        ((TextView) findViewById(R.id.tvPwName)).setText(displayName(c));
        ImageView photo = findViewById(R.id.imgPwAvatar);
        Bitmap bm = AccountPhoto.load(this, c.uid);
        if (bm != null) {
            RoundedBitmapDrawable rd = RoundedBitmapDrawableFactory.create(getResources(), bm);
            rd.setCircular(true);
            photo.setImageDrawable(rd);
        }

        final EditText et = findViewById(R.id.etCardPass);
        ((CheckBox) findViewById(R.id.cbCardShow)).setOnCheckedChangeListener((b, on) -> {
            et.setTransformationMethod(on ? HideReturnsTransformationMethod.getInstance()
                    : PasswordTransformationMethod.getInstance());
            et.setSelection(et.getText().length());
        });

        findViewById(R.id.btnCardLogin).setOnClickListener(v -> {
            String p = et.getText().toString();
            if (p.isEmpty()) { et.setError("Password required"); return; }
            setLoading(true);
            doLogin(c.email, p, false, () -> setLoading(false));
        });
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
        doLogin(email, pass, cbRememberMe.isChecked(), null);
    }

    /**
     * remember = true হলে পাসওয়ার্ডসহ কার্ড সেভ; false হলে শুধু কার্ড (পাসওয়ার্ড সেভ হয় না)।
     */
    private void doLogin(final String email, final String pass, final boolean remember, final Runnable onFail) {
        if (email == null || pass == null) {
            toast("Login করা যায়নি, email ও password দিন");
            if (onFail != null) onFail.run();
            return;
        }
        AuthRepo.login(email, pass, error -> {
            if (error == null) {
                prefs().edit()
                        .putString(KEY_LAST_EMAIL, email)
                        .putBoolean(KEY_REMEMBER, remember)
                        .apply();
                SecureStore.setLoginInfo(this, SupabaseRest.uid(), email, remember ? pass : null);
                AccountPhoto.refresh(this);       // কার্ডের জন্য নাম ও ছবি ফোনে রাখে
                goHome();
            } else if (AuthRepo.isUnverified(error)) {
                if (onFail != null) onFail.run();
                showVerifyDialog(email, pass, remember);
            } else {
                if (onFail != null) onFail.run();
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

    // ---------- Verify (ইমেইলের কোড) ----------
    private void showVerifyDialog(final String email, final String pass, final boolean remember) {
        AuthRepo.resendVerification(email, err -> {
            if (err != null) toast("কোড পাঠানো যায়নি: " + err);
            EmailCodeDialog.show(this, email, new EmailCodeDialog.Listener() {
                @Override public void onVerified() {
                    toast("ইমেইল verify হয়েছে, স্বাগতম!");
                    SecureStore.setLoginInfo(LoginActivity.this, SupabaseRest.uid(), email, remember ? pass : null);
                    AccountPhoto.refresh(LoginActivity.this);
                    goHome();
                }
                @Override public void onCancel() { }
            });
        });
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