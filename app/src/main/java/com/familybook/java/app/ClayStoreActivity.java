package com.familybook.app;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

/**
 * অ্যাডমিনের জন্য পুরো Clay Store (Play Store-এর মতো): অ্যাপের তালিকা, সাজেশন, সার্চ, Games/Apps ক্যাটাগরি।
 * পেজের ফাইল থাকে app/src/main/assets/clay/। Install চাপলে {@link ClayDownloadActivity} (ডাউনলোড পেজ) খোলে।
 */
public class ClayStoreActivity extends AppCompatActivity {

    public static final String EXTRA_APP = "clay_app";

    private WebView web;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!ClayAccess.isAdmin()) {
            Toast.makeText(this, "Admins only", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);   // GitHub API-র ETag ক্যাশের জন্য

        web.addJavascriptInterface(new Bridge(), "ClayApp");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String u = r.getUrl().toString();
                if (u.startsWith("file:///android_asset/")) return false;
                if (u.contains("/releases/download/") || u.endsWith(".apk")) {
                    openDownload(u, URLUtil.guessFileName(u, null, null), "", "");
                    return true;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, r.getUrl()));
                } catch (ActivityNotFoundException ignored) { }
                return true;
            }
        });

        String app = getIntent().getStringExtra(EXTRA_APP);
        web.loadUrl("file:///android_asset/clay/index.html" + (app == null || app.isEmpty() ? "" : "#" + app));

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (web.canGoBack()) web.goBack(); else finish();
            }
        });
    }

    private void openDownload(String url, String name, String ver, String icon) {
        if (url == null || !url.startsWith("https://github.com/")) return;
        startActivity(new Intent(this, ClayDownloadActivity.class)
                .putExtra(ClayDownloadActivity.EXTRA_URL, url)
                .putExtra(ClayDownloadActivity.EXTRA_NAME, name)
                .putExtra(ClayDownloadActivity.EXTRA_VER, ver)
                .putExtra(ClayDownloadActivity.EXTRA_ICON, icon));
    }

    /** পেজের JavaScript থেকে ডাকা হয়: ClayApp.install(url, name, version, iconUrl) */
    private class Bridge {
        @JavascriptInterface
        public void install(final String url, final String name, final String ver, final String icon) {
            runOnUiThread(() -> openDownload(url, name, ver, icon));
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (web != null) web.destroy();
    }
}
