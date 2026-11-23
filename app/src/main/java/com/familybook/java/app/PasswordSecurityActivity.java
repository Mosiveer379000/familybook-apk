package com.familybook.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Settings > Password & Security.
 * assets/password_security.html (WebView) খোলে। HTML-এ কোনো key নেই; সব নেটওয়ার্ক কাজ এই Activity
 * SupabaseRest দিয়ে করে (Edge Function "account-security"), ফোন নম্বর profiles.info.phone-এ সেভ হয়।
 */
public class PasswordSecurityActivity extends AppCompatActivity {

    private static final String PAGE = "file:///android_asset/password_security.html";
    private static final String FUNCTION_PATH = "/functions/v1/account-security";
    private static final Set<String> ACTIONS = new HashSet<>(Arrays.asList(
            "state", "send_code", "change_password", "change_primary", "set_recovery", "verify_recovery"));

    private WebView web;
    private View loading;
    private boolean leaving = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!SupabaseRest.isLoggedIn() || SupabaseRest.uid() == null) {
            goLogin();
            return;
        }
        setContentView(R.layout.activity_password_security);
        web = findViewById(R.id.webPasswordSecurity);
        loading = findViewById(R.id.progressPasswordSecurity);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setAllowFileAccess(false);          // file:///android_asset তবুও চলে
        s.setAllowContentAccess(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);

        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                loading.setVisibility(View.GONE);
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true;                  // পেজ থেকে অন্য কোথাও যাওয়া বন্ধ
            }
        });
        WebDark.attach(web);
        web.loadUrl(PAGE);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                web.evaluateJavascript("window.fbBack ? window.fbBack() : false", v -> {
                    if (!"true".equals(v)) finish();
                });
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // "Forgot password" দিয়ে ঘুরে এলে session মুছে যায়; তখন Login-এ পাঠাও
        if (!leaving && !SupabaseRest.isLoggedIn()) goLogin();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.removeJavascriptInterface("Android");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    private void goLogin() {
        leaving = true;
        Intent i = new Intent(this, LoginActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }

    // ------------------------------------------------------------------ result -> JS

    private void deliver(String id, int code, String body) {
        if (web == null || isFinishing() || isDestroyed()) return;
        String js = "window.__nativeResult(" + JSONObject.quote(id) + "," + code + ","
                + JSONObject.quote(body == null ? "" : body) + ");";
        web.evaluateJavascript(js, null);
    }

    private static String errJson(String msg) {
        try {
            return new JSONObject().put("error", msg == null ? "Something went wrong" : msg).toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    // ------------------------------------------------------------------ Edge Function calls

    private void doCall(final String id, final String action, String json) {
        if (!ACTIONS.contains(action)) { deliver(id, 400, errJson("Unknown action")); return; }
        final JSONObject body;
        try {
            body = new JSONObject(json == null || json.isEmpty() ? "{}" : json);
            body.put("action", action);
        } catch (Exception e) {
            deliver(id, 400, errJson("Bad request"));
            return;
        }
        final String nextPass = body.optString("next", "");
        final String curPass = body.optString("current", "");
        final String newEmail = body.optString("email", "").trim().toLowerCase();

        SupabaseRest.call("POST", FUNCTION_PATH, body.toString(), null, true, r -> {
            if (r.ok()) {
                if ("change_password".equals(action)) onPasswordChanged(nextPass);
                else if ("change_primary".equals(action)) onPrimaryChanged(newEmail, curPass);
            }
            if (r.code == 401 && !SupabaseRest.isLoggedIn()) { goLogin(); return; }
            deliver(id, r.code, r.body);
        });
    }

    /** সেভ করা লগইন কার্ডের পাসওয়ার্ড নতুনটা করে দেয়, নইলে কার্ড দিয়ে লগইন আটকে যেত */
    private void onPasswordChanged(String newPass) {
        String email = SupabaseRest.email();
        if (email != null && !newPass.isEmpty()) {
            SecureStore.setLoginInfo(this, SupabaseRest.uid(), email, newPass);
        }
    }

    /** ইমেইল বদলের পর নতুন ইমেইলে আবার লগইন করে session ও সেভ করা কার্ড ঠিক করে */
    private void onPrimaryChanged(final String newEmail, final String pass) {
        if (newEmail.isEmpty() || pass.isEmpty()) return;
        final android.content.Context app = getApplicationContext();
        new Thread(() -> {
            try {
                String payload = new JSONObject().put("email", newEmail).put("password", pass).toString();
                SupabaseRest.Resp r = SupabaseRest.send("POST", "/auth/v1/token?grant_type=password",
                        payload, null, null);
                if (r.ok()) {
                    SupabaseRest.saveSession(r.body);
                    app.getSharedPreferences("fb_auth", MODE_PRIVATE)
                            .edit().putString("last_email", newEmail).apply();
                    SecureStore.setLoginInfo(app, SupabaseRest.uid(), newEmail, pass);
                }
            } catch (Exception ignored) { }
        }).start();
    }

    // ------------------------------------------------------------------ phone (profiles.info.phone)

    private void doGetPhone(final String id) {
        Db.getMine("profiles", (row, err) -> {
            if (err != null) { deliver(id, 500, errJson(err)); return; }
            try {
                JSONObject info = row != null && row.optJSONObject("info") != null
                        ? row.optJSONObject("info") : new JSONObject();
                deliver(id, 200, new JSONObject().put("phone", info.optString("phone", "")).toString());
            } catch (Exception e) {
                deliver(id, 500, errJson("Could not read your phone number"));
            }
        });
    }

    /** value খালি হলে নম্বর মুছে যায় (কোনো পাসওয়ার্ড লাগে না) */
    private void doSavePhone(final String id, String raw) {
        final String value = raw == null ? "" : raw.trim();
        if (!value.isEmpty() && !value.matches("\\+8801[0-9]{9}")) {
            deliver(id, 400, errJson("Enter a valid mobile number (e.g. 01721932421)."));
            return;
        }
        Db.getMine("profiles", (row, err) -> {
            if (err != null) { deliver(id, 500, errJson(err)); return; }
            try {
                JSONObject info = row != null && row.optJSONObject("info") != null
                        ? row.optJSONObject("info") : new JSONObject();
                info.put("phone", value);
                Db.patchMyProfile(new JSONObject().put("info", info), e2 -> {
                    if (e2 != null) { deliver(id, 500, errJson(e2)); return; }
                    deliver(id, 200, "{\"phone\":" + JSONObject.quote(value) + "}");
                });
            } catch (Exception e) {
                deliver(id, 500, errJson("Could not save your phone number"));
            }
        });
    }

    // ------------------------------------------------------------------ JS bridge

    private class Bridge {
        @JavascriptInterface
        public void call(final String id, final String action, final String json) {
            runOnUiThread(() -> doCall(id, action, json));
        }

        @JavascriptInterface
        public void getPhone(final String id) {
            runOnUiThread(() -> doGetPhone(id));
        }

        @JavascriptInterface
        public void savePhone(final String id, final String phone) {
            runOnUiThread(() -> doSavePhone(id, phone));
        }

        @JavascriptInterface
        public void openForgot() {
            runOnUiThread(() ->
                    startActivity(new Intent(PasswordSecurityActivity.this, ForgotPasswordActivity.class)));
        }

        @JavascriptInterface
        public void close() {
            runOnUiThread(PasswordSecurityActivity.this::finish);
        }
    }
}
