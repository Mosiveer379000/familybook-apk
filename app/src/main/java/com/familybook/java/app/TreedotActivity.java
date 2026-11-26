package com.familybook.app;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;

/**
 * Bottom navigation-এর ডানের Profile আইকনে চাপলে এই Menu খোলে।
 *
 * পেজটা এখন নেটিভ XML নয় — assets/familybook_menu.html (WebView) দেখায়।
 * নিচের Bottom Navigation আগের মতই নেটিভ থাকে।
 *
 * HTML <-> Java যোগাযোগ:
 *   HTML  -> Java : FBMenu.open("followers") ইত্যাদি (নিচের Bridge ক্লাস)
 *   Java  -> HTML : fbMenuSet(name, avatarDataUri), fbTheme(isDark), fbStrings(json) + FBMenu.strings()
 *
 * আগের সব ফাংশন একই আছে: প্রোফাইল খোলা, "+" অ্যাকাউন্ট শিট (New page + অন্য অ্যাকাউন্টে লগইন),
 * Search, Settings, Messenger (Family Chat handoff), Sign out (Supabase logout + কার্ড সেভ)।
 */
public class TreedotActivity extends BaseActivity {

    private static final String FAMILY_CHAT_PKG = "com.familychat.app";
    private static final String PAGE_URL = "file:///android_asset/familybook_menu.html";

    private final Handler h = new Handler(Looper.getMainLooper());

    private String myUid;
    private String myName;
    private String myAvatar = "";      // ছোট JPEG data-URI (HTML-এর কার্ডের ছবি)
    private WebView web;
    private boolean pageReady;
    private boolean destroyed;
    private boolean resumedOnce;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        myName = getString(R.string.tm_family_member);
        myUid = SupabaseRest.uid();
        if (!SupabaseRest.isLoggedIn() || myUid == null) {
            goLogin();
            return;
        }

        setContentView(R.layout.activity_treedot);
        applyMenuTheme();   // ডার্ক/লাইট অনুযায়ী উইন্ডো ও সিস্টেম বারের রং

        // নিচের নেভিগেশন বার (Menu স্ক্রিনেও থাকবে)
        BottomNav.attach(this);

        // ---------- WebView ----------
        web = findViewById(R.id.webMenu);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);                 // assets (file:///android_asset) এতে আটকায় না
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        // পেজ স্ক্রিনের প্রস্থ অনুযায়ী মাপে চলে (ডিজাইনের মতো ছোট লেখা); তাই ন্যূনতম ফন্ট সীমা ও ফন্ট-স্কেল বন্ধ
        s.setMinimumFontSize(1);
        s.setMinimumLogicalFontSize(1);
        s.setTextZoom(100);
        web.setBackgroundColor(DC.bg());             // লোডের আগের ফ্ল্যাশ এড়াতে
        web.setVerticalScrollBarEnabled(false);
        web.setHapticFeedbackEnabled(false);
        web.setOnLongClickListener(v -> true);       // লেখা সিলেক্ট/কপি মেনু বন্ধ
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return !r.getUrl().toString().startsWith("file:///android_asset/");
            }
        });
        web.addJavascriptInterface(new Bridge(), "FBMenu");
        web.loadUrl(PAGE_URL);

        // নিজের নাম ও ছবি
        loadMyName();
        loadMyAvatar();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // প্রোফাইল ছবি/নাম বদলে ফিরে এলে কার্ড নতুন হবে
        if (resumedOnce && myUid != null) {
            loadMyName();
            loadMyAvatar();
        }
        resumedOnce = true;
        refreshLookAndText();   // ডার্ক মোড / ভাষা সেটিং বদলালে সাথে সাথে বদলায়
    }

    /** ফোনের থিম বদলালে পেজও সাথে সাথে বদলায় */
    @Override
    public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        if (web == null) return;
        web.setBackgroundColor(DC.bg());
        web.evaluateJavascript("window.fbTheme&&fbTheme(" + DC.night() + ")", null);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        h.removeCallbacksAndMessages(null);
        if (web != null) {
            ViewGroup p = (ViewGroup) web.getParent();
            if (p != null) p.removeView(web);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    // ---------- HTML থেকে আসা কমান্ড ----------

    private class Bridge {
        /** এখন ডার্ক কি (পেজ প্রথম আঁকার আগেই রং ঠিক করতে) */
        @JavascriptInterface public boolean dark() { return DC.night(); }

        /** মেনুর সব লেখা বর্তমান অ্যাপ-ভাষায় (strings.xml -> tm_*) */
        @JavascriptInterface public String strings() { return buildStrings(); }

        /** পেজ লোড হয়ে গেছে — নাম ও ছবি পাঠাই */
        @JavascriptInterface public void ready() {
            h.post(() -> { pageReady = true; pushProfile(); });
        }

        /** মেনুর যেকোনো সারি/বাটনে চাপলে */
        @JavascriptInterface public void open(final String action) {
            h.post(() -> { if (!destroyed) handle(action); });
        }
    }

    private void handle(String action) {
        if (action == null) return;
        switch (action) {
            case "profile":
                ProfileActivity.open(this, myUid, myName);
                break;
            case "add":          // "+" : New page (ডেমো) + অন্য অ্যাকাউন্টে লগইন
                showAccountsSheet();
                break;
            case "search":
                startActivity(new Intent(this, SearchActivity.class));
                break;
            case "settings":
                startActivity(new Intent(this, SettingsActivity.class));
                break;
            case "followers":
                startActivity(new Intent(this, MembersActivity.class));
                break;
            case "dashboard":
                soon(getString(R.string.tm_dashboard));
                break;
            case "messenger":
                openFamilyChat();
                break;
            case "shorts":
                ShortViewerActivity.openFeed(this);
                break;
            case "claystore":
                startActivity(new Intent(this, ClayStoreActivity.class));
                break;
            case "groups":
                startActivity(new Intent(this, GroupsActivity.class));
                break;
            case "terms":        // assets/terms_conditions.html
                startActivity(new Intent(this, TermsActivity.class));
                break;
            case "birthday":
                soon(getString(R.string.tm_birthday));
                break;
            case "bluebadge":    // Settings থেকে যেভাবে খোলে সেভাবেই (অফলাইন সুরক্ষা সহ)
                SettingsGate.open(this, new Intent(this, BlueBadgeActivity.class));
                break;
            case "report":       // Report a problem (HTML: assets/report_problem.html)
                SettingsGate.open(this, new Intent(this, ReportProblemActivity.class));   // অফলাইন হলে ক্যাশ/offline.html
                break;
            case "help":
                soon(getString(R.string.tm_help));
                break;
            case "signout":
                confirmLogout();
                break;
            default:
                break;
        }
    }

    // ---------- ভাষা (strings.xml) ও থিম -> HTML ----------

    /** মেনুর লেখাগুলো JSON আকারে; [[ ]] দেওয়া অংশ HTML-এ নীল হাইলাইট হয় */
    private String buildStrings() {
        try {
            JSONObject o = new JSONObject();
            o.put("menu", getString(R.string.tm_menu));
            o.put("followers", getString(R.string.tm_followers));
            o.put("followers_d", getString(R.string.tm_followers_d));
            o.put("dashboard", getString(R.string.tm_dashboard));
            o.put("dashboard_d", getString(R.string.tm_dashboard_d));
            o.put("messenger", getString(R.string.tm_messenger));
            o.put("messenger_d", getString(R.string.tm_messenger_d));
            o.put("shorts", getString(R.string.tm_shorts));
            o.put("shorts_d", getString(R.string.tm_shorts_d));
            o.put("claystore", getString(R.string.tm_claystore));
            o.put("claystore_d", getString(R.string.tm_claystore_d));
            o.put("groups", getString(R.string.tm_groups));
            o.put("groups_d", getString(R.string.tm_groups_d));
            o.put("terms", getString(R.string.tm_terms));
            o.put("terms_d", getString(R.string.tm_terms_d));
            o.put("birthday", getString(R.string.tm_birthday));
            o.put("birthday_d", getString(R.string.tm_birthday_d));
            o.put("badge", getString(R.string.tm_badge));
            o.put("badge_d", getString(R.string.tm_badge_d));
            o.put("report", getString(R.string.tm_report));
            o.put("help", getString(R.string.tm_help));
            o.put("signout", getString(R.string.tm_signout));
            o.put("see_more", getString(R.string.tm_see_more));
            o.put("see_less", getString(R.string.tm_see_less));
            android.content.res.Configuration cfg = getResources().getConfiguration();
            o.put("_lang", cfg.getLocales().get(0).getLanguage());
            o.put("_rtl", cfg.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL);
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    /** ফিরে এলে: ডার্ক/লাইট ও ভাষা আবার HTML-এ পাঠায় */
    private void refreshLookAndText() {
        if (web == null || destroyed || !pageReady) return;
        applyMenuTheme();
        web.setBackgroundColor(DC.bg());
        web.evaluateJavascript("window.fbTheme&&fbTheme(" + DC.night() + ")", null);
        web.evaluateJavascript("window.fbStrings&&fbStrings(" + buildStrings() + ")", null);
    }

    // ---------- নাম ও ছবি -> HTML ----------

    private void pushProfile() {
        if (web == null || destroyed || !pageReady) return;
        web.evaluateJavascript("window.fbMenuSet&&fbMenuSet("
                + JSONObject.quote(myName == null ? "" : myName) + ","
                + JSONObject.quote(myAvatar == null ? "" : myAvatar) + ")", null);
    }

    private void loadMyName() {
        PostOps.loadMyName(myUid, n -> {
            myName = n;
            pushProfile();
        });
    }

    /**
     * প্রোফাইল ছবি: profiles.avatar_key দিয়ে AvatarLoader-এর মতো R2 থেকে আনে (Media.loadBitmap),
     * তারপর ছোট JPEG data-URI বানিয়ে HTML-এ পাঠায়। ছবি না থাকলে HTML-এর ডিফল্ট ছবি থাকে।
     */
    private void loadMyAvatar() {
        final String uid = myUid;
        SupabaseRest.call("GET", "/rest/v1/profiles?id=eq." + uid + "&select=avatar_key", null, null, true, r -> {
            if (destroyed) return;
            String key = "";
            try {
                if (r.ok()) {
                    JSONArray a = new JSONArray(r.body);
                    if (a.length() > 0 && !a.getJSONObject(0).isNull("avatar_key"))
                        key = a.getJSONObject(0).optString("avatar_key", "").trim();
                }
            } catch (Exception ignored) { }
            if (key.isEmpty()) return;
            Bitmap cached = AvatarLoader.cached(key);
            if (cached != null) { applyAvatar(cached); return; }
            Media.loadBitmap(key, bm -> applyAvatar(bm), (v, err) -> { });
        });
    }

    private void applyAvatar(Bitmap bm) {
        if (bm == null || destroyed) return;
        try {
            myAvatar = dataUri(bm, 256, 80);
            pushProfile();
        } catch (Throwable ignored) { }
    }

    private static String dataUri(Bitmap src, int px, int quality) {
        int side = Math.min(src.getWidth(), src.getHeight());
        Bitmap sq = Bitmap.createBitmap(src, (src.getWidth() - side) / 2, (src.getHeight() - side) / 2, side, side);
        Bitmap sm = Bitmap.createScaledBitmap(sq, px, px, true);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        sm.compress(Bitmap.CompressFormat.JPEG, quality, bo);
        return "data:image/jpeg;base64," + Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
    }

    // ---------- থিম ----------

    private boolean isNight() {
        return DC.night();   // অ্যাপের Dark mode সেটিং (Light / Dark / System) মেনে চলে
    }

    /** ডার্ক/লাইট/সিস্টেম মোডে উইন্ডো, স্ট্যাটাস বার ও নেভিগেশন-বারের রং মেলায় */
    private void applyMenuTheme() {
        int bg = androidx.core.content.ContextCompat.getColor(this, R.color.h_bg);
        getWindow().setBackgroundDrawable(new ColorDrawable(bg));
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        try {
            androidx.core.view.WindowInsetsControllerCompat c = androidx.core.view.WindowCompat
                    .getInsetsController(getWindow(), getWindow().getDecorView());
            if (c != null) {
                c.setAppearanceLightStatusBars(!isNight());
                c.setAppearanceLightNavigationBars(!isNight());
            }
        } catch (Exception ignored) { }
    }

    // ---------- "+" শিট: New page + অ্যাকাউন্ট ----------

    private void showAccountsSheet() {
        final Dialog dlg = new Dialog(this);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        View sheet = getLayoutInflater().inflate(R.layout.sheet_accounts, null);
        dlg.setContentView(sheet);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            w.setDimAmount(0.45f);
        }

        // New page — আপাতত ডেমো
        sheet.findViewById(R.id.rowNewPage).setOnClickListener(v -> {
            dlg.dismiss();
            soon(R.string.tm_new_page);
        });

        // এখনকার অ্যাকাউন্ট (টিক চিহ্ন সহ)
        LinearLayout list = sheet.findViewById(R.id.accountsList);
        addCurrentAccountRow(list);

        sheet.findViewById(R.id.rowAddAccount).setOnClickListener(v -> {
            dlg.dismiss();
            addAnotherAccount();
        });

        dlg.show();
    }

    private void addCurrentAccountRow(LinearLayout list) {
        final View row = getLayoutInflater().inflate(R.layout.item_account_row, list, false);
        ImageView img = row.findViewById(R.id.imgAcc);
        TextView tvN = row.findViewById(R.id.tvAccName);
        TextView tvS = row.findViewById(R.id.tvAccSub);
        ImageView state = row.findViewById(R.id.imgAccState);

        AvatarLoader.load(img, myUid);
        tvN.setText(myName);
        tvS.setText(R.string.tm_logged_in_now);
        state.setImageResource(R.drawable.ic_acc_check);
        row.setClickable(false);
        list.addView(row);
    }

    private void addAnotherAccount() {
        new AlertDialog.Builder(this, R.style.FbAlertDialog)
                .setTitle(R.string.tm_add_account)
                .setMessage(R.string.tm_add_msg)
                .setPositiveButton(R.string.tm_continue, (d, w) -> {
                    saveCardNow();
                    logoutAndGoLogin(true);
                })
                .setNegativeButton(R.string.tm_cancel, null)
                .show();
    }

    // ---------- Logout ----------
    private void confirmLogout() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(64, 24, 64, 0);

        TextView msg = new TextView(this);
        msg.setText(R.string.tm_logout_msg);
        msg.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.c_text));
        msg.setTextSize(16);
        box.addView(msg);

        final CheckBox save = new CheckBox(this);
        save.setText(R.string.tm_save_login);
        save.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.c_text));
        save.setChecked(true);
        box.addView(save);

        TextView hint = new TextView(this);
        hint.setText(R.string.tm_logout_hint);
        hint.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.c_text_sub));
        hint.setTextSize(13);
        box.addView(hint);

        new AlertDialog.Builder(this, R.style.FbAlertDialog)
                .setTitle(R.string.tm_logout_title)
                .setView(box)
                .setPositiveButton(R.string.tm_logout_title, (d, w) -> {
                    if (save.isChecked()) {
                        saveCardNow();
                    } else {
                        SecureStore.discardLogin(this, SupabaseRest.uid());
                    }
                    logoutAndGoLogin(false);
                })
                .setNegativeButton(R.string.tm_cancel, null)
                .show();
    }

    /** এই অ্যাকাউন্টের কার্ড সেভ (Remember me ছিল হলে পাসওয়ার্ডসহ) */
    private void saveCardNow() {
        boolean ok = SecureStore.commitCard(this, SupabaseRest.uid(), SupabaseRest.email(), myName);
        if (!ok) Toast.makeText(this, R.string.tm_login_not_saved, Toast.LENGTH_LONG).show();
    }

    private void logoutAndGoLogin(final boolean addAccount) {
        AuthRepo.logout(error -> goLogin(addAccount));
    }

    // ---------- Messenger (Family Chat) ----------

    private void openFamilyChat() {
        ChatHandoff.fetch(this, this::openFamilyChatNow);   // Family Chat-এ নিজে থেকে লগইনের কোড আনে
    }

    private void openFamilyChatNow(String handoff) {
        // Family Chat-এর হোম পেজ (কনভারসেশন তালিকা) সরাসরি খোলে
        try {
            Intent home = new Intent(Intent.ACTION_VIEW, Uri.parse("familychat://home"));
            home.setPackage(FAMILY_CHAT_PKG);
            ChatHandoff.putExtras(home, handoff);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(home);
            return;
        } catch (ActivityNotFoundException ignored) { }
        Intent intent = getPackageManager().getLaunchIntentForPackage(FAMILY_CHAT_PKG);
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } else {
            Toast.makeText(this, R.string.tm_chat_missing, Toast.LENGTH_SHORT).show();
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("market://details?id=" + FAMILY_CHAT_PKG)));
            } catch (ActivityNotFoundException ignored) { }
        }
    }

    // ---------- ছোটখাটো ----------

    private void soon(int whatRes) {
        soon(getString(whatRes));
    }

    private void soon(String what) {
        Toast.makeText(this, getString(R.string.tm_coming_soon, what), Toast.LENGTH_SHORT).show();
    }

    private void goLogin() { goLogin(false); }

    private void goLogin(boolean addAccount) {
        Intent i = new Intent(this, LoginActivity.class);
        if (addAccount) i.putExtra("add_account", true);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }
}
