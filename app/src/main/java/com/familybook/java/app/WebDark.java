package com.familybook.app;

import android.webkit.WebChromeClient;
import android.webkit.WebView;

/**
 * Settings-এর HTML (WebView) পেজে ডার্ক মোড: অ্যাপ ডার্কে থাকলে পেজের রং উল্টে
 * (#18191A ব্যাকগ্রাউন্ড, #E4E6EB লেখা) দেখায়; ছবি/ভিডিও আগের রঙেই থাকে।
 *
 * ব্যবহার:
 *  1) সাধারণ পেজ: web.loadUrl(...)-এর আগে WebDark.attach(web);
 *  2) নিজের WebChromeClient থাকলে (যেমন ফাইল চুজার): তার onProgressChanged-এর ভেতরে
 *     WebDark.inject(v); ডাকুন।
 */
public final class WebDark {
    private WebDark() { }

    private static final String JS =
            "(function(){try{var d=document;if(!d.documentElement)return;"
          + "if(d.getElementById('fb-dark-css'))return;"
          + "var s=d.createElement('style');s.id='fb-dark-css';"
          + "s.textContent='html{background:#fff!important;filter:invert(.91) hue-rotate(180deg)!important;}"
          + "img,video,canvas,picture,iframe,svg image{filter:invert(1) hue-rotate(180deg)!important;}';"
          + "(d.head||d.documentElement).appendChild(s);}catch(e){}})();";

    /** নিজস্ব WebChromeClient সেট করে ডার্ক মোড চালু করে। */
    public static void attach(final WebView web) {
        if (!DC.night()) return;
        web.setBackgroundColor(0xFF18191A);
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView v, int progress) {
                inject(v);
            }
        });
    }

    /** শুধু ডার্ক CSS বসায়; আপনার নিজের WebChromeClient থেকে ডাকার জন্য। */
    public static void inject(WebView web) {
        if (web == null || !DC.night()) return;
        web.evaluateJavascript(JS, null);
    }
}
