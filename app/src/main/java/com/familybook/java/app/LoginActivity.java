package com.familybook.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.util.Patterns;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Login. Two screens share one layout (activity_login.xml):
 *   1) saved profiles  (shown when this phone has saved login cards)
 *   2) login form      (Email / AGU / NID-BRC + password), opened by "Use another profile"
 */
public class LoginActivity extends AppCompatActivity {

    private static final String PREFS = "fb_auth";
    private static final String KEY_LAST_EMAIL = "last_email";   // SecureStore reads this key too
    private static final String KEY_LANG = "login_lang_demo";

    // Demo language list (real switching comes later)
    private static final String[] LANGS = {"English (US)", "Bangla"};

    private View backRow, panelSaved, panelForm, footerLogo, loading, langRow, btnDots;
    private ImageView imgLogo;
    private TextView tvLang;
    private LinearLayout cardList;
    private EditText etId, etPass;

    private boolean formShown = false;
    private boolean removeMode = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Already logged in: go straight to Home
        Uri launchData = getIntent() == null ? null : getIntent().getData();
        boolean isAuthCallback = launchData != null && "familybook".equals(launchData.getScheme());
        if (!isAuthCallback && SupabaseRest.isLoggedIn() && SupabaseRest.uid() != null) {
            goHome();
            return;
        }

        setContentView(R.layout.activity_login);
        bindViews();
        setupLanguage();

        // Saved cards exist -> saved profiles screen, otherwise the login form
        boolean addMode = getIntent() != null && getIntent().getBooleanExtra("add_account", false);
        if (!isAuthCallback && !addMode && !SecureStore.cards(this).isEmpty()) {
            showSaved();
        } else {
            showForm();
        }

        handleAuthCallback(getIntent());
    }

    @Override
    public void onBackPressed() {
        if (formShown && !SecureStore.cards(this).isEmpty()) {
            showSaved();
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

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ---------------------------------------------------------------- views

    private void bindViews() {
        backRow = findViewById(R.id.backRow);
        panelSaved = findViewById(R.id.panelSaved);
        panelForm = findViewById(R.id.panelForm);
        footerLogo = findViewById(R.id.footerLogo);
        loading = findViewById(R.id.loginLoading);
        langRow = findViewById(R.id.langRow);
        btnDots = findViewById(R.id.btnDots);
        imgLogo = findViewById(R.id.imgLogo);
        tvLang = findViewById(R.id.tvLang);
        cardList = findViewById(R.id.cardList);
        etId = findViewById(R.id.etLoginId);
        etPass = findViewById(R.id.etLoginPass);

        findViewById(R.id.btnBack).setOnClickListener(v -> onBackPressed());
        findViewById(R.id.btnUseAnother).setOnClickListener(v -> showForm());
        findViewById(R.id.btnSign).setOnClickListener(v -> attemptLogin());
        findViewById(R.id.btnRegister).setOnClickListener(v ->
                startActivity(new Intent(this, RegisterActivity.class)));
        findViewById(R.id.tvForgot).setOnClickListener(v ->
                toast("Forgot password is coming soon"));          // demo for now

        btnDots.setOnClickListener(v -> {
            PopupMenu pm = new PopupMenu(this, v);
            Menu m = pm.getMenu();
            m.add(0, 1, 0, removeMode ? "Done" : "Remove a profile");
            pm.setOnMenuItemClickListener((MenuItem item) -> {
                removeMode = !removeMode;
                renderCards();
                return true;
            });
            pm.show();
        });

        etPass.setOnEditorActionListener((tv, actionId, ev) -> {
            attemptLogin();
            return true;
        });
    }

    // Language dropdown (demo): only changes the label for now
    private void setupLanguage() {
        String saved = prefs().getString(KEY_LANG, LANGS[0]);
        tvLang.setText(saved);
        langRow.setOnClickListener(v -> {
            PopupMenu pm = new PopupMenu(this, v);
            for (int i = 0; i < LANGS.length; i++) pm.getMenu().add(0, i, i, LANGS[i]);
            pm.setOnMenuItemClickListener(item -> {
                String pick = LANGS[item.getItemId()];
                tvLang.setText(pick);
                prefs().edit().putString(KEY_LANG, pick).apply();
                return true;
            });
            pm.show();
        });
    }

    private void setLogoTop(int dpTop) {
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) imgLogo.getLayoutParams();
        lp.topMargin = dp(dpTop);
        imgLogo.setLayoutParams(lp);
    }

    private void setLoading(boolean on) {
        loading.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    // ---------------------------------------------------------------- screen 1: saved profiles

    private void showSaved() {
        formShown = false;
        removeMode = false;
        backRow.setVisibility(View.GONE);
        btnDots.setVisibility(View.VISIBLE);
        panelForm.setVisibility(View.GONE);
        footerLogo.setVisibility(View.GONE);
        panelSaved.setVisibility(View.VISIBLE);
        setLogoTop(16);
        renderCards();
    }

    private void renderCards() {
        cardList.removeAllViews();
        LayoutInflater inf = LayoutInflater.from(this);

        for (final SecureStore.Card c : SecureStore.cards(this)) {
            View row = inf.inflate(R.layout.item_login_card, cardList, false);

            ((TextView) row.findViewById(R.id.tvCardName)).setText(displayName(c));

            ImageView photo = row.findViewById(R.id.imgCardAvatar);
            Bitmap bm = AccountPhoto.load(this, c.uid);
            if (bm != null) {
                RoundedBitmapDrawable rd = RoundedBitmapDrawableFactory.create(getResources(), bm);
                rd.setCircular(true);
                photo.setImageDrawable(rd);
            }

            View remove = row.findViewById(R.id.btnCardRemove);
            View arrow = row.findViewById(R.id.imgCardArrow);
            remove.setVisibility(removeMode ? View.VISIBLE : View.GONE);
            arrow.setVisibility(removeMode ? View.GONE : View.VISIBLE);
            remove.setOnClickListener(v -> confirmRemove(c));

            row.setOnClickListener(v -> {
                if (removeMode) return;
                onCardTap(c);
            });
            cardList.addView(row);
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

    /** Password saved: log in directly (with loading). Not saved, or it fails: open the form with the ID filled in. */
    private void onCardTap(final SecureStore.Card c) {
        if (c.hasPass()) {
            setLoading(true);
            doLogin(c.email, c.pass, () -> {
                setLoading(false);
                openFormFor(c);
            });
        } else {
            openFormFor(c);
        }
    }

    private void openFormFor(SecureStore.Card c) {
        showForm();
        etId.setText(c.email);
        etPass.setText("");
        etPass.requestFocus();
    }

    // ---------------------------------------------------------------- screen 2: login form

    private void showForm() {
        formShown = true;
        removeMode = false;
        backRow.setVisibility(SecureStore.cards(this).isEmpty() ? View.GONE : View.VISIBLE);
        btnDots.setVisibility(View.GONE);
        panelSaved.setVisibility(View.GONE);
        panelForm.setVisibility(View.VISIBLE);
        footerLogo.setVisibility(View.VISIBLE);
        setLogoTop(backRow.getVisibility() == View.VISIBLE ? 70 : 96);
        etId.setText("");
        etPass.setText("");
    }

    // ---------------------------------------------------------------- Login

    /**
     * ID can be: email, AGU (username + number), username, or NID / birth registration number.
     * Email logs in directly; everything else is first resolved to an email on the server.
     */
    private void attemptLogin() {
        final String id = etId.getText().toString().trim();
        final String pass = etPass.getText().toString();

        if (id.isEmpty()) { etId.setError("Enter your email, AGU or NID/BRC"); return; }
        if (pass.isEmpty()) { etPass.setError("Password required"); return; }

        if (id.contains("@")) {
            String email = id.toLowerCase();
            if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                etId.setError("Enter a valid email");
                return;
            }
            setLoading(true);
            doLogin(email, pass, () -> setLoading(false));
            return;
        }

        setLoading(true);
        AuthRepo.resolveEmail(id, (email, error) -> {
            if (email == null) {
                setLoading(false);
                toast(error != null ? "Could not check this ID: " + error
                        : "No account found for this AGU / NID / BRC");
                return;
            }
            doLogin(email, pass, () -> setLoading(false));
        });
    }

    /** Password is kept (encrypted) until logout; a saved card is created only if "Save login information" is ticked. */
    private void doLogin(final String email, final String pass, final Runnable onFail) {
        if (email == null || pass == null) {
            toast("Enter your ID and password to log in");
            if (onFail != null) onFail.run();
            return;
        }
        AuthRepo.login(email, pass, error -> {
            if (error == null) {
                prefs().edit().putString(KEY_LAST_EMAIL, email).apply();
                SecureStore.setLoginInfo(this, SupabaseRest.uid(), email, pass);
                AccountPhoto.refresh(this);       // keeps name and photo on the phone for the saved card
                goHome();
            } else if (AuthRepo.isUnverified(error)) {
                if (onFail != null) onFail.run();
                showVerifyDialog(email, pass);
            } else {
                if (onFail != null) onFail.run();
                toast("Wrong ID or password: " + error);
            }
        });
    }

    // ---------------------------------------------------------------- Verify (email code)

    private void showVerifyDialog(final String email, final String pass) {
        AuthRepo.resendVerification(email, err -> {
            if (err != null) toast("Could not send code: " + err);
            EmailCodeDialog.show(this, email, new EmailCodeDialog.Listener() {
                @Override public void onVerified() {
                    toast("Email verified, welcome!");
                    SecureStore.setLoginInfo(LoginActivity.this, SupabaseRest.uid(), email, pass);
                    AccountPhoto.refresh(LoginActivity.this);
                    goHome();
                }
                @Override public void onCancel() { }
            });
        });
    }

    // ---------------------------------------------------------------- Auth callback (email verify link)

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
            toast("Email verified, welcome!");
            goHome();
        } catch (Exception e) {
            toast("Verification not completed, please log in again");
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
