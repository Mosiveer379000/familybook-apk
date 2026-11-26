package com.familybook.app;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * Terms & Conditions (assets/terms_conditions.html)
 * এখান থেকেই Privacy Policy, Community Standards ও Blue Badge এর নিয়ম খোলে।
 */
public class TermsActivity extends BaseActivity {

    private static final String ASSETS = "file:///android_asset/";
    /** কোন পেজ সরাসরি খুলবে: "terms" (ডিফল্ট) | "community" | "privacy" — Account status থেকে আসে */
    static final String EXTRA_PAGE = "page";

    private WebView web;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        web.setFitsSystemWindows(true);
        web.setBackgroundColor(DC.bg());
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // ভাষা ও থিম মনে রাখে
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri url = r.getUrl();
                if (url.toString().startsWith(ASSETS + "buy_blue_badge.html")) {
                    // আসল যোগ্যতা যাচাইয়ের জন্য মেনুর মতই BlueBadgeActivity
                    SettingsGate.open(TermsActivity.this,
                            new Intent(TermsActivity.this, BlueBadgeActivity.class));
                    return true;
                }
                if (url.toString().startsWith(ASSETS)) return false;   // অ্যাপের ভেতরের পেজ
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, url));  // বাইরের লিংক
                } catch (ActivityNotFoundException ignored) { }
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "FBPage");

        if (savedInstanceState == null || web.restoreState(savedInstanceState) == null) {
            String pg = getIntent().getStringExtra(EXTRA_PAGE);
            String file = "community".equals(pg) ? "community_standards.html"
                    : "privacy".equals(pg) ? "pivracy_policy.html" : "terms_conditions.html";
            web.loadUrl(ASSETS + file);
        }
    }

    /** HTML এর Back বাটন */
    private class Bridge {
        @JavascriptInterface
        public void back() {
            runOnUiThread(() -> goBack());
        }
    }

    private void goBack() {
        if (web != null && web.canGoBack()) web.goBack();
        else finish();
    }

    @Override
    public void onBackPressed() {
        goBack();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (web != null) web.saveState(out);
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            android.view.ViewGroup p = (android.view.ViewGroup) web.getParent();
            if (p != null) p.removeView(web);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
