package com.familybook.app;

import android.graphics.Color;
import android.graphics.Typeface;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.Patterns;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Forgot password. Method dropdown: Email, AGU Number, NID, BRC.
 *
 *  Email : email -> 8 digit code -> new password
 *  AGU   : Find AGU -> Select -> 8 digit code (sent to the account email) -> new password
 *  NID / BRC : number -> Verify (shows the card name) -> new password -> Save
 *
 * AGU: the code step is added on purpose. An AGU is public, so without the email code
 * anyone could reset another person's password.
 */
public class ForgotPasswordActivity extends AppCompatActivity {

    private static final int M_EMAIL = 0, M_AGU = 1, M_NID = 2, M_BRC = 3;
    private static final String[] METHODS = {"Email", "AGU Number", "NID", "BRC"};
    private static final int COOLDOWN_SECONDS = 30;
    private static final int BLUE = 0xFF2F6BFF;

    private View panelEmail, panelAgu, panelNid, panelCode, panelNewPass, loading, aguList;
    private TextView methodText, tvEmailExpire, btnSendCode, btnAguSelect, tvNidLabel, btnNidVerify,
            tvNidName, tvCodeLabel, tvCodeExpire, btnCodeSubmit, btnSave, tvMsg;
    private android.widget.EditText etEmail, etAgu, etNid, etCode, etNew, etRe;
    private LinearLayout aguListBox;

    private int method = M_EMAIL;
    private String targetEmail;          // email that receives the code
    private String selectedAgu;
    private String verifiedNid, verifiedDoc;
    private boolean sessionOpen = false; // recovery session saved: cleared if the user leaves before saving
    private boolean busy = false;

    private CountDownTimer timer;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable aguSearchRun;
    private int aguReq = 0;
    private boolean suppressAguWatcher = false;
    private final List<String> lastAguItems = new ArrayList<>();

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_forgot_password);
        bind();
        buildIntro();
        setMethod(M_EMAIL);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cancelTimer();
        handler.removeCallbacksAndMessages(null);
        if (sessionOpen) SupabaseRest.clearSession();   // code was verified but password not saved
    }

    // ------------------------------------------------------------------ setup

    private void bind() {
        panelEmail = findViewById(R.id.panelEmail);
        panelAgu = findViewById(R.id.panelAgu);
        panelNid = findViewById(R.id.panelNid);
        panelCode = findViewById(R.id.panelCode);
        panelNewPass = findViewById(R.id.panelNewPass);
        loading = findViewById(R.id.fpLoading);
        aguList = findViewById(R.id.fpAguList);
        aguListBox = findViewById(R.id.fpAguList);

        methodText = findViewById(R.id.fpMethodText);
        tvEmailExpire = findViewById(R.id.tvEmailExpire);
        btnSendCode = findViewById(R.id.btnSendCode);
        btnAguSelect = findViewById(R.id.btnAguSelect);
        tvNidLabel = findViewById(R.id.tvNidLabel);
        btnNidVerify = findViewById(R.id.btnNidVerify);
        tvNidName = findViewById(R.id.tvNidName);
        tvCodeLabel = findViewById(R.id.tvCodeLabel);
        tvCodeExpire = findViewById(R.id.tvCodeExpire);
        btnCodeSubmit = findViewById(R.id.btnCodeSubmit);
        btnSave = findViewById(R.id.btnFpSave);
        tvMsg = findViewById(R.id.tvFpMsg);

        etEmail = findViewById(R.id.etFpEmail);
        etAgu = findViewById(R.id.etFpAgu);
        etNid = findViewById(R.id.etFpNid);
        etCode = findViewById(R.id.etFpCode);
        etNew = findViewById(R.id.etFpNew);
        etRe = findViewById(R.id.etFpRe);

        findViewById(R.id.fpClose).setOnClickListener(v -> finish());

        findViewById(R.id.fpMethodBox).setOnClickListener(v -> {
            PopupMenu pm = new PopupMenu(this, v);
            for (int i = 0; i < METHODS.length; i++) pm.getMenu().add(0, i, i, METHODS[i]);
            pm.setOnMenuItemClickListener(item -> {
                if (item.getItemId() != method) setMethod(item.getItemId());
                return true;
            });
            pm.show();
        });

        btnSendCode.setOnClickListener(v -> sendEmailCode());
        btnAguSelect.setOnClickListener(v -> selectAgu());
        btnNidVerify.setOnClickListener(v -> verifyNid());
        btnCodeSubmit.setOnClickListener(v -> submitCode());
        btnSave.setOnClickListener(v -> savePassword());
        etRe.setOnEditorActionListener((tv, id, ev) -> { savePassword(); return true; });

        etAgu.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                if (suppressAguWatcher) return;
                selectedAgu = null;
                searchAgu(s.toString().trim());
            }
        });
    }

    private void buildIntro() {
        String a = "If your \uD83E\uDDE0 brain cannot remember your ";
        String b1 = "last password";
        String c = "? or you didn\u2019t back it up, feel free to request us for a new password by writing your ";
        String b2 = "email";
        String d = " of your account or ";
        String b3 = "AGU";
        String e = " Number, ";
        String b4 = "NID, BRC,";
        String f = " Details.";
        SpannableStringBuilder sb = new SpannableStringBuilder(a + b1 + c + b2 + d + b3 + e + b4 + f);
        String[] bold = {b1, b2, b3, b4};
        String all = sb.toString();
        int from = 0;
        for (String w : bold) {
            int i = all.indexOf(w, from);
            if (i < 0) continue;
            sb.setSpan(new ForegroundColorSpan(0xFF0A5CFF), i, i + w.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new StyleSpan(Typeface.BOLD), i, i + w.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            from = i + w.length();
        }
        ((TextView) findViewById(R.id.fpIntro)).setText(sb);
    }

    // ------------------------------------------------------------------ method switch

    private void setMethod(int m) {
        method = m;
        methodText.setText(METHODS[m]);

        cancelTimer();
        if (sessionOpen) { SupabaseRest.clearSession(); sessionOpen = false; }
        targetEmail = null;
        selectedAgu = null;
        verifiedNid = null;
        verifiedDoc = null;
        aguReq++;

        etEmail.setText("");
        suppressAguWatcher = true;
        etAgu.setText("");
        suppressAguWatcher = false;
        etAgu.setEnabled(true);
        etNid.setText("");
        etNid.setEnabled(true);
        etCode.setText("");
        etNew.setText("");
        etRe.setText("");
        hideMsg();
        aguList.setVisibility(View.GONE);
        tvNidName.setVisibility(View.GONE);

        btnNidVerify.setText("Verify");
        btnNidVerify.setBackgroundResource(R.drawable.bg_fp_pill);
        btnNidVerify.setEnabled(true);
        btnAguSelect.setVisibility(View.VISIBLE);

        panelEmail.setVisibility(m == M_EMAIL ? View.VISIBLE : View.GONE);
        panelAgu.setVisibility(m == M_AGU ? View.VISIBLE : View.GONE);
        panelNid.setVisibility(m == M_NID || m == M_BRC ? View.VISIBLE : View.GONE);
        panelCode.setVisibility(View.GONE);
        panelNewPass.setVisibility(View.GONE);

        if (m == M_NID) tvNidLabel.setText("10 Digits NID no:");
        if (m == M_BRC) tvNidLabel.setText("17 Digits BRC no:");

        renderExpire(tvEmailExpire, COOLDOWN_SECONDS, false, null);
    }

    // ------------------------------------------------------------------ email method

    private void sendEmailCode() {
        if (busy) return;
        String em = etEmail.getText().toString().trim().toLowerCase(Locale.ROOT);
        if (!Patterns.EMAIL_ADDRESS.matcher(em).matches()) {
            etEmail.setError("Enter a valid email");
            return;
        }
        setBusy(true);
        final String email = em;
        AuthRepo.resetPassword(email, error -> {
            setBusy(false);
            if (isFinishing()) return;
            if (error != null) { showMsg(error); return; }
            startCodeStage(email);
        });
    }

    // ------------------------------------------------------------------ AGU method

    private void searchAgu(final String q) {
        if (aguSearchRun != null) handler.removeCallbacks(aguSearchRun);
        final int req = ++aguReq;
        if (q.isEmpty()) { aguList.setVisibility(View.GONE); return; }
        aguSearchRun = () -> AuthRepo.findAgu(q, (items, error) -> {
            if (isFinishing() || req != aguReq || method != M_AGU) return;
            showAguList(items == null ? new ArrayList<String>() : items);
        });
        handler.postDelayed(aguSearchRun, 350);
    }

    private void showAguList(List<String> items) {
        lastAguItems.clear();
        lastAguItems.addAll(items);
        aguListBox.removeAllViews();
        if (items.isEmpty()) {
            aguListBox.addView(listRow("No AGU found", 0xFF888888, false));
        } else {
            int n = 0;
            for (final String agu : items) {
                if (n++ >= 6) break;
                TextView row = listRow(agu, BLUE, true);
                row.setOnClickListener(v -> {
                    selectedAgu = agu;
                    suppressAguWatcher = true;
                    etAgu.setText(agu);
                    etAgu.setSelection(agu.length());
                    suppressAguWatcher = false;
                    aguListBox.setVisibility(View.GONE);
                });
                aguListBox.addView(row);
            }
        }
        aguListBox.setVisibility(View.VISIBLE);
    }

    private TextView listRow(String text, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(18);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        int p = dp(10);
        t.setPadding(p, p, p, p);
        t.setLayoutParams(new ViewGroup.LayoutParams(-1, -2));
        return t;
    }

    private void selectAgu() {
        if (busy) return;
        String typed = etAgu.getText().toString().trim();
        if (selectedAgu == null) {
            for (String s : lastAguItems) if (s.equalsIgnoreCase(typed)) selectedAgu = s;
        }
        if (selectedAgu == null) {
            toast("Pick your AGU from the list");
            return;
        }
        setBusy(true);
        final String agu = selectedAgu;
        AuthRepo.resolveEmail(agu, (email, error) -> {
            if (isFinishing()) return;
            if (email == null) {
                setBusy(false);
                toast(error != null ? error : "No account found for this AGU");
                return;
            }
            AuthRepo.resetPassword(email, e2 -> {
                setBusy(false);
                if (isFinishing()) return;
                if (e2 != null) { showMsg(e2); return; }
                etAgu.setEnabled(false);
                aguList.setVisibility(View.GONE);
                btnAguSelect.setVisibility(View.GONE);
                startCodeStage(email);
            });
        });
    }

    // ------------------------------------------------------------------ code stage (Email and AGU)

    private void startCodeStage(String email) {
        targetEmail = email;
        hideMsg();
        panelEmail.setVisibility(View.GONE);
        panelCode.setVisibility(View.VISIBLE);
        tvCodeLabel.setText("Enter 8 Digit Code from '" + maskEmail(email));
        etCode.setText("");
        etCode.requestFocus();
        startCooldown();
    }

    private void startCooldown() {
        cancelTimer();
        renderExpire(tvCodeExpire, COOLDOWN_SECONDS, true, null);
        timer = new CountDownTimer(COOLDOWN_SECONDS * 1000L, 1000) {
            @Override public void onTick(long ms) {
                renderExpire(tvCodeExpire, (int) Math.ceil(ms / 1000.0), true, null);
            }
            @Override public void onFinish() {
                renderExpire(tvCodeExpire, 0, true, () -> resendCode());
            }
        }.start();
    }

    private void resendCode() {
        if (busy || targetEmail == null) return;
        setBusy(true);
        AuthRepo.resetPassword(targetEmail, error -> {
            setBusy(false);
            if (isFinishing()) return;
            if (error != null) { showMsg(error); return; }
            toast("New code sent");
            startCooldown();
        });
    }

    private void submitCode() {
        if (busy || targetEmail == null) return;
        String code = etCode.getText().toString().trim();
        if (code.length() < 6) { etCode.setError("Enter the code from your email"); return; }
        setBusy(true);
        AuthRepo.verifyRecoveryCode(targetEmail, code, error -> {
            setBusy(false);
            if (isFinishing()) return;
            if (error != null) {
                showMsg("Wrong or expired code. Try again or request a new code.");
                return;
            }
            sessionOpen = true;
            cancelTimer();
            hideMsg();
            panelCode.setVisibility(View.GONE);
            panelNewPass.setVisibility(View.VISIBLE);
            etNew.requestFocus();
        });
    }

    // ------------------------------------------------------------------ NID / BRC method

    private void verifyNid() {
        if (busy || verifiedNid != null) return;
        final String doc = method == M_NID ? "NID" : "BRC";
        final int len = method == M_NID ? 10 : 17;
        final String n = etNid.getText().toString().trim();
        if (!n.matches("[0-9]{" + len + "}")) {
            etNid.setError("Enter " + len + " digits");
            return;
        }
        setBusy(true);
        AuthRepo.lookupNid(n, doc, (name, error) -> {
            setBusy(false);
            if (isFinishing()) return;
            if (name == null) {
                showMsg(error != null ? error : "No verified " + doc + " found for this number");
                return;
            }
            hideMsg();
            verifiedNid = n;
            verifiedDoc = doc;
            etNid.setEnabled(false);
            btnNidVerify.setText("Verified");
            btnNidVerify.setBackgroundResource(R.drawable.bg_fp_pill_green);
            btnNidVerify.setEnabled(false);

            String label = doc + " Full Name : ";
            SpannableStringBuilder sb = new SpannableStringBuilder(label + name);
            sb.setSpan(new StyleSpan(Typeface.BOLD), label.length(), sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            tvNidName.setText(sb);
            tvNidName.setVisibility(View.VISIBLE);
            panelNewPass.setVisibility(View.VISIBLE);
            etNew.requestFocus();
        });
    }

    // ------------------------------------------------------------------ save

    private void savePassword() {
        if (busy) return;
        String p1 = etNew.getText().toString();
        String p2 = etRe.getText().toString();
        if (!isStrong(p1)) {
            etNew.setError("Use 8+ characters with upper and lower case letters, a number and a symbol");
            return;
        }
        if (!p1.equals(p2)) { etRe.setError("Passwords do not match"); return; }

        setBusy(true);
        AuthRepo.Callback done = error -> {
            setBusy(false);
            if (isFinishing()) return;
            if (error != null) { showMsg("Could not save the password: " + error); return; }
            SupabaseRest.clearSession();     // back to Login with the new password
            sessionOpen = false;
            toast("Password updated. Log in with your new password.");
            finish();
        };
        if (method == M_NID || method == M_BRC) {
            if (verifiedNid == null) { setBusy(false); toast("Verify your number first"); return; }
            AuthRepo.resetByNid(verifiedNid, verifiedDoc, p1, done);
        } else {
            AuthRepo.updatePassword(p1, done);
        }
    }

    // ------------------------------------------------------------------ helpers

    /** secs > 0: "Expire After 30 Seconds"; secs == 0 and tappable: "Resend code" */
    private void renderExpire(TextView tv, int secs, boolean allowResend, final Runnable onTap) {
        if (secs > 0 || !allowResend) {
            String a = "Expire After ";
            String b = secs + " Seconds";
            SpannableStringBuilder sb = new SpannableStringBuilder(a + b);
            sb.setSpan(new ForegroundColorSpan(BLUE), a.length(), sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            tv.setText(sb);
            tv.setMovementMethod(null);
            return;
        }
        SpannableStringBuilder sb = new SpannableStringBuilder("Resend code");
        sb.setSpan(new ClickableSpan() {
            @Override public void onClick(View widget) { if (onTap != null) onTap.run(); }
            @Override public void updateDrawState(TextPaint ds) {
                ds.setColor(BLUE);
                ds.setUnderlineText(false);
            }
        }, 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        tv.setText(sb);
        tv.setMovementMethod(LinkMovementMethod.getInstance());
        tv.setHighlightColor(Color.TRANSPARENT);
    }

    private void cancelTimer() {
        if (timer != null) { timer.cancel(); timer = null; }
    }

    private void setBusy(boolean b) {
        busy = b;
        loading.setVisibility(b ? View.VISIBLE : View.GONE);
    }

    private void showMsg(String m) {
        tvMsg.setText(m);
        tvMsg.setVisibility(View.VISIBLE);
    }

    private void hideMsg() {
        tvMsg.setVisibility(View.GONE);
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_LONG).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** same rule as Registration */
    private static boolean isStrong(String p) {
        return p.length() >= 8 && p.matches(".*[A-Z].*") && p.matches(".*[a-z].*")
                && p.matches(".*[0-9].*") && p.matches(".*[^A-Za-z0-9].*");
    }

    /** ma***16@gmail.com */
    private static String maskEmail(String mail) {
        int at = mail.indexOf('@');
        if (at <= 0) return mail;
        String local = mail.substring(0, at);
        String domain = mail.substring(at);
        if (local.length() <= 4) return local.substring(0, 1) + "***" + domain;
        return local.substring(0, 2) + "***" + local.substring(local.length() - 2) + domain;
    }
}
