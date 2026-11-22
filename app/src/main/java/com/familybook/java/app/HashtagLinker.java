package com.familybook.app;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.graphics.Color;
import android.os.SystemClock;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.View;
import android.widget.TextView;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * লেখার ভেতরের #হ্যাশট্যাগ ও http(s):// / www. লিংককে ক্লিকযোগ্য করে।
 * হ্যাশট্যাগে ডাবল-ট্যাপ করলে Search খুলে ওই ট্যাগের পোস্ট দেখায়; লিংকে ট্যাপ করলে LinkOpener (Media সেটিং অনুযায়ী)।
 * ব্যবহার: HashtagLinker.apply(textView, linkColor)  (linkColor = 0 হলে লেখার নিজের রং থাকবে)
 */
public final class HashtagLinker {

    /** #ট্যাগ (বাংলা অক্ষর + স্বরচিহ্নসহ) */
    static final Pattern PATTERN = Pattern.compile("#[\\p{L}\\p{M}\\p{N}_]+");

    /** http(s):// বা www. দিয়ে শুরু হওয়া লিংক */
    static final Pattern URL = Pattern.compile("(?i)\\b(?:https?://|www\\.)[^\\s<>\"']+");

    private static final long DOUBLE_TAP_MS = 400;
    private static long lastTime = 0;
    private static String lastTag = "";

    private HashtagLinker() { }

    public static void apply(TextView tv, int linkColor) {
        CharSequence cs = tv.getText();
        if (cs == null || cs.length() == 0) return;
        String plain = cs.toString();
        String low = plain.toLowerCase();
        if (plain.indexOf('#') < 0 && !low.contains("http") && !low.contains("www.")) return;

        SpannableString ss = new SpannableString(cs);
        boolean any = false;

        // আগে লিংক (লিংকের ভেতরের #fragment হ্যাশট্যাগ ধরা হবে না)
        java.util.ArrayList<int[]> urlRanges = new java.util.ArrayList<>();
        Matcher um = URL.matcher(ss);
        while (um.find()) {
            int end = um.end();
            // শেষের যতিচিহ্ন লিংকের অংশ নয়
            while (end > um.start() && ".,;:!?)]}\u0964".indexOf(ss.charAt(end - 1)) >= 0) end--;
            if (end <= um.start()) continue;
            ss.setSpan(new LinkSpan(ss.subSequence(um.start(), end).toString(), linkColor),
                    um.start(), end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            urlRanges.add(new int[]{um.start(), end});
            any = true;
        }

        Matcher m = PATTERN.matcher(ss);
        while (m.find()) {
            boolean inUrl = false;
            for (int[] r : urlRanges) {
                if (m.start() >= r[0] && m.start() < r[1]) { inUrl = true; break; }
            }
            if (inUrl) continue;
            ss.setSpan(new TagSpan(m.group(), linkColor), m.start(), m.end(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            any = true;
        }
        if (!any) return;
        tv.setText(ss);
        tv.setMovementMethod(LinkMovementMethod.getInstance());
        tv.setHighlightColor(Color.TRANSPARENT);
    }

    private static void open(Context ctx, String tag) {
        Activity act = activityOf(ctx);
        if (act instanceof SearchActivity) {
            ((SearchActivity) act).runQuery(tag);
        } else {
            ctx.startActivity(new Intent(ctx, SearchActivity.class)
                    .putExtra(SearchActivity.EXTRA_QUERY, tag));
        }
    }

    private static Activity activityOf(Context c) {
        while (c instanceof ContextWrapper) {
            if (c instanceof Activity) return (Activity) c;
            c = ((ContextWrapper) c).getBaseContext();
        }
        return null;
    }

    private static class TagSpan extends ClickableSpan {
        private final String tag;
        private final int color;

        TagSpan(String tag, int color) { this.tag = tag; this.color = color; }

        @Override public void onClick(View widget) {
            long now = SystemClock.uptimeMillis();
            if (tag.equalsIgnoreCase(lastTag) && now - lastTime < DOUBLE_TAP_MS) {
                lastTag = "";
                lastTime = 0;
                open(widget.getContext(), tag);
            } else {
                lastTag = tag;
                lastTime = now;
            }
        }

        @Override public void updateDrawState(TextPaint ds) {
            if (color != 0) ds.setColor(color);
            ds.setUnderlineText(false);
            ds.setFakeBoldText(true);
        }
    }

    private static class LinkSpan extends ClickableSpan {
        private final String url;
        private final int color;

        LinkSpan(String url, int color) { this.url = url; this.color = color; }

        @Override public void onClick(View widget) {
            LinkOpener.open(widget.getContext(), url);
        }

        @Override public void updateDrawState(TextPaint ds) {
            ds.setColor(color != 0 ? color : 0xFF1877F2);
            ds.setUnderlineText(true);
        }
    }
}
