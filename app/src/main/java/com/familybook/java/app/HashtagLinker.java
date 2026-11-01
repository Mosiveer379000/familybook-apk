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
 * লেখার ভেতরের #হ্যাশট্যাগকে লিংকের মতো করে। হ্যাশট্যাগে ডাবল-ট্যাপ করলে Search খুলে ওই ট্যাগের পোস্ট দেখায়।
 * ব্যবহার: HashtagLinker.apply(textView, linkColor)  (linkColor = 0 হলে লেখার নিজের রং থাকবে)
 */
public final class HashtagLinker {

    /** #ট্যাগ (বাংলা অক্ষর + স্বরচিহ্নসহ) */
    static final Pattern PATTERN = Pattern.compile("#[\\p{L}\\p{M}\\p{N}_]+");

    private static final long DOUBLE_TAP_MS = 400;
    private static long lastTime = 0;
    private static String lastTag = "";

    private HashtagLinker() { }

    public static void apply(TextView tv, int linkColor) {
        CharSequence cs = tv.getText();
        if (cs == null || cs.length() == 0 || cs.toString().indexOf('#') < 0) return;

        SpannableString ss = new SpannableString(cs);
        Matcher m = PATTERN.matcher(ss);
        boolean any = false;
        while (m.find()) {
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
}
