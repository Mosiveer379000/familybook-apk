package com.familybook.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Opens a link from a post.
 * Settings > Media > "Open links in external browser":
 *   ON  -> asks first, then opens the phone's default browser (Chrome etc.)
 *   OFF -> opens inside the app (full screen page with a close button)
 */
final class LinkOpener {

    private LinkOpener() { }

    static String normalize(String url) {
        String u = url == null ? "" : url.trim();
        if (u.regionMatches(true, 0, "www.", 0, 4)) u = "https://" + u;
        return u;
    }

    static void open(Context ctx, String rawUrl) {
        final String url = normalize(rawUrl);
        if (url.isEmpty()) return;
        final Activity act = activityOf(ctx);
        if (act == null || act.isFinishing()) return;

        if (MediaPrefs.externalBrowser(act)) {
            new AlertDialog.Builder(act)
                    .setMessage("Link is opening on external browser, leaving Familybook. Are you sure?")
                    .setPositiveButton("Yes", (d, w) -> openExternal(act, url))
                    .setNegativeButton("No", null)
                    .show();
        } else {
            openInApp(act, url);
        }
    }

    private static void openExternal(Context c, String url) {
        try {
            c.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addCategory(Intent.CATEGORY_BROWSABLE));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(c, "No browser found to open this link", Toast.LENGTH_SHORT).show();
        }
    }

    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private static void openInApp(Activity act, String url) {
        float d = act.getResources().getDisplayMetrics().density;
        final Dialog dlg = new Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen);

        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        LinearLayout bar = new LinearLayout(act);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Color.WHITE);
        TextView close = new TextView(act);
        close.setText("\u2715");
        close.setTextSize(20);
        close.setTextColor(Color.BLACK);
        close.setGravity(Gravity.CENTER);
        close.setOnClickListener(v -> dlg.dismiss());
        bar.addView(close, new LinearLayout.LayoutParams(Math.round(52 * d), Math.round(52 * d)));
        final TextView title = new TextView(act);
        title.setText(Uri.parse(url).getHost());
        title.setTextColor(Color.BLACK);
        title.setTextSize(15);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(bar, new LinearLayout.LayoutParams(-1, Math.round(52 * d)));

        final WebView web = new WebView(act);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String s = r.getUrl().toString();
                return !(s.startsWith("http://") || s.startsWith("https://"));
            }
            @Override public void onPageFinished(WebView v, String u) {
                String h = Uri.parse(u).getHost();
                if (h != null) title.setText(h);
            }
        });
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1f));

        dlg.setContentView(root);
        dlg.setOnKeyListener((di, key, ev) -> {
            if (key == KeyEvent.KEYCODE_BACK && ev.getAction() == KeyEvent.ACTION_UP) {
                if (web.canGoBack()) web.goBack(); else dlg.dismiss();
                return true;
            }
            return false;
        });
        dlg.setOnDismissListener(di -> {
            web.stopLoading();
            web.destroy();
        });
        dlg.show();
        web.loadUrl(url);
    }

    private static Activity activityOf(Context c) {
        while (c instanceof ContextWrapper) {
            if (c instanceof Activity) return (Activity) c;
            c = ((ContextWrapper) c).getBaseContext();
        }
        return null;
    }
}
