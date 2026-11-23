package com.familybook.app;

import android.content.Context;
import android.net.Uri;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/**
 * Settings-এর HTML পেজের জন্য অফলাইন/স্লো-নেট সুরক্ষা।
 *
 * পেজগুলো Tailwind (cdn.tailwindcss.com) ও Google Fonts নেট থেকে আনত; নেট না থাকলে পেজ ভেঙে যেত বা কালো/সাদা থাকত।
 * এখন:
 *  1) ওই ফাইলগুলো প্রথমবার নেট থেকে এনে ফোনে জমায়, পরে নেট ছাড়াই চলে (Facebook-এর ক্যাশের মতো)।
 *     চাইলে assets/vendor/tailwind.js রাখলে সেটাই আগে ব্যবহার হয় (প্রথমবারও অফলাইনে চলে)।
 *  2) Fonts না পেলে সাথে সাথে সিস্টেম ফন্ট (অপেক্ষা নেই)।
 *  3) Tailwind একেবারেই না পেলে ভাঙা পেজের বদলে সুন্দর "No internet" পেজ + Retry।
 */
public final class WebGuard {
    private WebGuard() { }

    static final String OFFLINE = "file:///android_asset/offline.html";
    private static final String TAILWIND = "https://cdn.tailwindcss.com";
    private static final long REFRESH_MS = 7L * 24 * 3600 * 1000;

    private static Context app;

    public static void init(Context c) {
        app = c.getApplicationContext();
        Net.addListener(online -> { if (online) prefetch(); });
        if (Net.isOnline()) prefetch();
    }

    private static boolean cachedHost(String h) {
        return "cdn.tailwindcss.com".equals(h) || "fonts.googleapis.com".equals(h)
                || "fonts.gstatic.com".equals(h) || "cdn.jsdelivr.net".equals(h);
    }

    private static File dir() {
        File d = new File(app.getFilesDir(), "webcache");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    private static String key(String url) {
        try {
            byte[] b = MessageDigest.getInstance("SHA-1").digest(url.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(url.hashCode());
        }
    }

    /** অ্যাপ চালু/নেট ফিরলে Tailwind আগেই এনে রাখে (৭ দিনের পুরোনো হলে নতুন করে) */
    public static void prefetch() {
        if (app == null) return;
        new Thread(() -> {
            try {
                File f = new File(dir(), key(TAILWIND));
                if (f.exists() && System.currentTimeMillis() - f.lastModified() < REFRESH_MS) return;
                download(TAILWIND, null);
            } catch (Exception ignored) { }
        }, "webguard-prefetch").start();
    }

    /** ডাউনলোড করে ফাইলে রাখে; ব্যর্থ হলে null */
    private static File download(String url, String ua) {
        HttpURLConnection c = null;
        File f = new File(dir(), key(url));
        File tmp = new File(f.getPath() + ".tmp");
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(8000);
            c.setInstanceFollowRedirects(true);
            if (ua != null) c.setRequestProperty("User-Agent", ua);
            if (c.getResponseCode() != 200) return null;
            String mime = c.getContentType();
            try (InputStream in = c.getInputStream(); FileOutputStream o = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            }
            if (!tmp.renameTo(f)) return null;
            try (FileOutputStream m = new FileOutputStream(new File(f.getPath() + ".mime"))) {
                m.write((mime == null ? "" : mime).getBytes("UTF-8"));
            }
            return f;
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String mimeOf(File f, String url) {
        String m = null;
        try (FileInputStream in = new FileInputStream(new File(f.getPath() + ".mime"))) {
            byte[] b = new byte[256];
            int n = in.read(b);
            if (n > 0) m = new String(b, 0, n, "UTF-8");
        } catch (Exception ignored) { }
        if (m != null) { int i = m.indexOf(';'); if (i > 0) m = m.substring(0, i); m = m.trim(); }
        if (m == null || m.isEmpty()) {
            String u = url.toLowerCase();
            if (u.contains("fonts.googleapis.com")) m = "text/css";
            else if (u.contains(".woff2")) m = "font/woff2";
            else if (u.contains(".woff")) m = "font/woff";
            else if (u.contains(".ttf")) m = "font/ttf";
            else m = "application/javascript";
        }
        return m;
    }

    private static WebResourceResponse respond(String mime, InputStream in) {
        WebResourceResponse r = new WebResourceResponse(mime, "UTF-8", in);
        Map<String, String> h = new HashMap<>();
        h.put("Access-Control-Allow-Origin", "*");
        r.setResponseHeaders(h);
        return r;
    }

    private static WebResourceResponse empty(String url) {
        boolean css = url.contains("fonts.googleapis.com");
        return respond(css ? "text/css" : "application/javascript", new ByteArrayInputStream(new byte[0]));
    }

    /** WebViewClient: ক্যাশ + অফলাইন পেজ। নিজের পেজের Client এটাকেই extend করবে। */
    public static class Client extends WebViewClient {
        private String pageUrl;
        private boolean showingOffline;

        @Override public void onPageStarted(WebView v, String url, android.graphics.Bitmap icon) {
            if (url != null && !url.startsWith(OFFLINE)) { pageUrl = url; showingOffline = false; }
            super.onPageStarted(v, url, icon);
        }

        private void goOffline(final WebView v) {
            if (showingOffline || pageUrl == null) return;
            showingOffline = true;
            final String target = OFFLINE + "#" + Uri.encode(pageUrl);
            v.post(() -> v.loadUrl(target));
        }

        @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
            if (r != null && r.isForMainFrame() && !String.valueOf(r.getUrl()).startsWith(OFFLINE)) goOffline(v);
            super.onReceivedError(v, r, e);
        }

        @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
            try {
                Uri u = r.getUrl();
                if (app == null || u == null || !"https".equals(u.getScheme()) || !cachedHost(u.getHost())) {
                    return super.shouldInterceptRequest(v, r);
                }
                String url = u.toString();
                boolean tailwind = "cdn.tailwindcss.com".equals(u.getHost());

                // ১) প্রজেক্টের সাথে দেওয়া কপি (assets/vendor/tailwind.js)
                if (tailwind) {
                    try {
                        InputStream in = app.getAssets().open("vendor/tailwind.js");
                        return respond("application/javascript", in);
                    } catch (Exception ignored) { }
                }
                // ২) ফোনে জমানো কপি
                File f = new File(dir(), key(url));
                if (f.exists() && f.length() > 0) {
                    return respond(mimeOf(f, url), new FileInputStream(f));
                }
                // ৩) নেট থাকলে এনে জমাই
                if (Net.isOnline()) {
                    String ua = r.getRequestHeaders() == null ? null : r.getRequestHeaders().get("User-Agent");
                    File d = download(url, ua);
                    if (d != null) return respond(mimeOf(d, url), new FileInputStream(d));
                }
                // ৪) পাওয়া গেল না
                if (tailwind) { goOffline(v); }
                return empty(url);   // ফন্ট/CSS হলে সাথে সাথে খালি উত্তর → সিস্টেম ফন্ট, অপেক্ষা নেই
            } catch (Exception e) {
                return super.shouldInterceptRequest(v, r);
            }
        }
    }
}
