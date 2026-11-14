package com.familybook.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Lock your profile (আগে) / You locked your profile (পরে) — Facebook-এর মতো পেজ।
 * Supabase SQL:
 *   alter table profiles add column if not exists is_locked boolean not null default false;
 *   alter table profiles add column if not exists locked_at timestamptz;
 */
public class LockProfileActivity extends Activity {

    static final int REQ = 77;

    static void open(Activity a, String avatarKey, boolean locked, long lockedAtMs) {
        a.startActivityForResult(new Intent(a, LockProfileActivity.class)
                .putExtra("avatarKey", avatarKey).putExtra("locked", locked)
                .putExtra("lockedAt", lockedAtMs), REQ);
    }

    private String avatarKey;
    private boolean locked;
    private long lockedAt;
    private LinearLayout root;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        avatarKey = getIntent().getStringExtra("avatarKey");
        locked = getIntent().getBooleanExtra("locked", false);
        lockedAt = getIntent().getLongExtra("lockedAt", 0L);
        render();
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    private void finishWithResult() {
        setResult(RESULT_OK, new Intent().putExtra("locked", locked).putExtra("lockedAt", lockedAt));
        finish();
    }

    @Override public void onBackPressed() { finishWithResult(); }

    private void render() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        // back
        GlyphView back = new GlyphView(this, GlyphView.BACK, Color.BLACK);
        back.setOnClickListener(v -> finishWithResult());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(26), dp(26));
        bp.setMargins(dp(20), dp(16), 0, 0);
        root.addView(back, bp);

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        sv.addView(col, new FrameLayout.LayoutParams(-1, -2));
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        // shield + avatar
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER);
        ShieldView shield = new ShieldView(this, locked);
        head.addView(shield, new LinearLayout.LayoutParams(dp(64), dp(64)));
        final ImageView av = new ImageView(this);
        av.setScaleType(ImageView.ScaleType.CENTER_CROP);
        av.setBackgroundColor(Color.parseColor("#BCC0C4"));
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(dp(60), dp(60));
        ap.setMarginStart(-dp(10));
        head.addView(av, ap);
        av.setClipToOutline(true);
        av.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) { o.setOval(0, 0, v.getWidth(), v.getHeight()); }
        });
        if (avatarKey != null) {
            Media.loadBitmap(avatarKey, bm -> {
                if (isFinishing()) return;
                RoundedBitmapDrawable d = RoundedBitmapDrawableFactory.create(getResources(), bm);
                d.setCircular(true);
                av.setImageDrawable(d);
            });
        }
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-2, -2);
        hp.topMargin = dp(locked ? 40 : 52);
        col.addView(head, hp);

        TextView title = new TextView(this);
        title.setText(locked ? "You locked your profile" : "Lock your profile");
        title.setTextSize(24);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(Color.parseColor("#050505"));
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.topMargin = dp(22);
        col.addView(title, tp);

        TextView sub = new TextView(this);
        sub.setTextSize(16);
        sub.setTextColor(Color.parseColor("#050505"));
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(dp(56), dp(4), dp(56), 0);
        if (locked) {
            sub.setText("Your photos and posts are more private.");
        } else if (lockedAt > 0) {
            String date = new SimpleDateFormat("d MMMM yyyy", Locale.ENGLISH).format(new Date(lockedAt));
            SpannableStringBuilder sb = new SpannableStringBuilder("Your last locking date is ");
            int s = sb.length();
            sb.append(date + ".");
            sb.setSpan(new StyleSpan(Typeface.BOLD), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.append(" You can ");
            int l = sb.length();
            sb.append("lock");
            sb.setSpan(new ForegroundColorSpan(Color.parseColor("#0866FF")), l, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.append(" again to secure your profile from bad peoples.");
            sub.setText(sb);
        } else {
            sub.setText("Lock your profile to secure it from bad peoples.");
        }
        col.addView(sub);

        if (locked) {
            LinearLayout acts = new LinearLayout(this);
            acts.setGravity(Gravity.CENTER);
            acts.addView(action(GlyphView.UNLOCK, "Unlock", v -> setLock(false)));
            View gap = new View(this);
            acts.addView(gap, new LinearLayout.LayoutParams(dp(56), 1));
            acts.addView(action(GlyphView.QUESTION, "Learn more", v -> TopToast.show(this, "Coming soon")));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.topMargin = dp(26);
            col.addView(acts, lp);
        }

        View spacer = new View(this);
        col.addView(spacer, new LinearLayout.LayoutParams(1, dp(locked ? 40 : 90)));

        col.addView(infoRow(GlyphView.PEOPLE,
                "Only followers can See whats inside your profile, including full views of profile, cover photos, shorts, posts, profile informations if your Lock function is enabled", null));
        col.addView(infoRow(GlyphView.USER,
                "will be disabled after you lock function is enable. your monitization tool will be remove.", "Professional Dashboard"));

        LinearLayout help = new LinearLayout(this);
        help.setGravity(Gravity.CENTER);
        help.addView(new GlyphView(this, GlyphView.INFO, Color.parseColor("#0866FF")), new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView ht = new TextView(this);
        ht.setText("Learn more for help!");
        ht.setTextSize(16);
        ht.setTypeface(null, Typeface.BOLD);
        ht.setTextColor(Color.parseColor("#0866FF"));
        ht.setPadding(dp(8), 0, 0, 0);
        help.addView(ht);
        help.setOnClickListener(v -> TopToast.show(this, "Coming soon"));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-2, -2);
        hlp.topMargin = dp(26);
        col.addView(help, hlp);

        if (!locked) {
            View fill = new View(this);
            col.addView(fill, new LinearLayout.LayoutParams(1, 0, 1));
            TextView btn = new TextView(this);
            btn.setText("Lock profile");
            btn.setTextSize(20);
            btn.setTypeface(null, Typeface.BOLD);
            btn.setTextColor(Color.WHITE);
            btn.setGravity(Gravity.CENTER);
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setColor(Color.parseColor("#0866FF"));
            g.setCornerRadius(dp(14));
            btn.setBackground(g);
            btn.setOnClickListener(v -> setLock(true));
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, dp(46));
            bl.setMargins(dp(60), dp(40), dp(60), dp(90));
            col.addView(btn, bl);
        }

        setContentView(root);
    }

    private View action(int glyph, String label, View.OnClickListener c) {
        LinearLayout a = new LinearLayout(this);
        a.setOrientation(LinearLayout.VERTICAL);
        a.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout circle = new FrameLayout(this);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setColor(Color.parseColor("#E4E6EB"));
        circle.setBackground(g);
        GlyphView gv = new GlyphView(this, glyph, Color.parseColor("#050505"));
        FrameLayout.LayoutParams gp = new FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER);
        circle.addView(gv, gp);
        a.addView(circle, new LinearLayout.LayoutParams(dp(54), dp(54)));
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(16);
        t.setTextColor(Color.parseColor("#050505"));
        t.setPadding(0, dp(6), 0, 0);
        a.addView(t);
        a.setOnClickListener(c);
        return a;
    }

    private View infoRow(int glyph, String text, String boldLead) {
        LinearLayout r = new LinearLayout(this);
        r.setPadding(dp(32), dp(8), dp(24), dp(8));
        GlyphView g = new GlyphView(this, glyph, Color.BLACK);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(dp(20), dp(20));
        gp.topMargin = dp(2);
        r.addView(g, gp);
        TextView t = new TextView(this);
        t.setTextSize(16);
        t.setTextColor(Color.parseColor("#050505"));
        t.setPadding(dp(12), 0, 0, 0);
        if (boldLead != null) {
            SpannableStringBuilder sb = new SpannableStringBuilder(boldLead);
            sb.setSpan(new StyleSpan(Typeface.BOLD), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.append(" ").append(text);
            t.setText(sb);
        } else {
            t.setText(text);
        }
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        return r;
    }

    private void setLock(final boolean target) {
        try {
            JSONObject o = new JSONObject().put("is_locked", target);
            long now = System.currentTimeMillis();
            if (target) {
                SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
                f.setTimeZone(TimeZone.getTimeZone("UTC"));
                o.put("locked_at", f.format(new Date(now)));
            }
            final long stamp = now;
            SupabaseRest.call("PATCH", "/rest/v1/profiles?id=eq." + Me.uid(), o.toString(),
                    "return=representation", true, r -> {
                if (isFinishing()) return;
                if (!r.ok()) { TopToast.show(this, SupabaseRest.errorText(r)); return; }
                try {
                    if (new JSONArray(r.body).length() == 0) { TopToast.show(this, "Not saved, try again"); return; }
                } catch (Exception ignored) { }
                locked = target;
                if (target) lockedAt = stamp;
                render();
            });
        } catch (Exception e) {
            TopToast.show(this, "Could not save");
        }
    }

    // ---------- আইকন (ছবির মতো আঁকা) ----------

    /** নীল গোল + সাদা শিল্ড + চাবির ছিদ্র; locked হলে সবুজ টিক ব্যাজ */
    static class ShieldView extends View {
        private final boolean badge;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        ShieldView(Context c, boolean badge) { super(c); this.badge = badge; }
        @Override protected void onDraw(Canvas c) {
            float s = Math.min(getWidth(), getHeight());
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.parseColor("#1877F2"));
            c.drawCircle(s / 2, s / 2, s / 2, p);
            Path sh = new Path();
            sh.moveTo(.5f * s, .20f * s);
            sh.lineTo(.74f * s, .29f * s);
            sh.quadTo(.74f * s, .60f * s, .5f * s, .80f * s);
            sh.quadTo(.26f * s, .60f * s, .26f * s, .29f * s);
            sh.close();
            p.setColor(Color.WHITE);
            c.drawPath(sh, p);
            p.setColor(Color.parseColor("#1877F2"));
            c.drawCircle(.5f * s, .43f * s, .06f * s, p);
            c.drawRect(.475f * s, .44f * s, .525f * s, .58f * s, p);
            if (badge) {
                float cx = .82f * s, cy = .80f * s, r = .17f * s;
                p.setColor(Color.WHITE);
                c.drawCircle(cx, cy, r + s * .03f, p);
                p.setColor(Color.parseColor("#31A24C"));
                c.drawCircle(cx, cy, r, p);
                p.setColor(Color.WHITE);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(s * .045f);
                p.setStrokeCap(Paint.Cap.ROUND);
                Path t = new Path();
                t.moveTo(cx - r * .5f, cy);
                t.lineTo(cx - r * .1f, cy + r * .4f);
                t.lineTo(cx + r * .55f, cy - r * .4f);
                c.drawPath(t, p);
            }
        }
    }

    static class GlyphView extends View {
        static final int BACK = 0, PEOPLE = 1, USER = 2, UNLOCK = 3, QUESTION = 4, INFO = 5;
        private final int type, color;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        GlyphView(Context c, int type, int color) { super(c); this.type = type; this.color = color; }
        @Override protected void onDraw(Canvas c) {
            float s = Math.min(getWidth(), getHeight());
            p.setColor(color);
            p.setStyle(Paint.Style.FILL);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            switch (type) {
                case BACK: {
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(s * .14f);
                    Path t = new Path();
                    t.moveTo(.68f * s, .12f * s);
                    t.lineTo(.30f * s, .50f * s);
                    t.lineTo(.68f * s, .88f * s);
                    c.drawPath(t, p);
                    break;
                }
                case PEOPLE: {
                    c.drawCircle(.34f * s, .28f * s, .17f * s, p);
                    c.drawRoundRect(new RectF(.04f * s, .52f * s, .64f * s, .90f * s), .18f * s, .18f * s, p);
                    c.drawCircle(.74f * s, .34f * s, .13f * s, p);
                    c.drawRoundRect(new RectF(.60f * s, .56f * s, .96f * s, .90f * s), .14f * s, .14f * s, p);
                    break;
                }
                case USER: {
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(s * .07f);
                    c.drawCircle(s / 2, s / 2, s * .44f, p);
                    p.setStyle(Paint.Style.FILL);
                    c.drawCircle(.5f * s, .38f * s, .15f * s, p);
                    c.save();
                    Path clip = new Path();
                    clip.addCircle(s / 2, s / 2, s * .42f, Path.Direction.CW);
                    c.clipPath(clip);
                    c.drawOval(new RectF(.22f * s, .58f * s, .78f * s, 1.10f * s), p);
                    c.restore();
                    break;
                }
                case UNLOCK: {
                    c.drawRoundRect(new RectF(.16f * s, .45f * s, .84f * s, .92f * s), .1f * s, .1f * s, p);
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(s * .11f);
                    c.drawArc(new RectF(.26f * s, .06f * s, .66f * s, .56f * s), 180, 180, false, p);
                    c.drawLine(.26f * s, .32f * s, .26f * s, .48f * s, p);
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(Color.parseColor("#E4E6EB"));
                    c.drawCircle(.5f * s, .64f * s, .07f * s, p);
                    c.drawRect(.47f * s, .65f * s, .53f * s, .78f * s, p);
                    break;
                }
                case QUESTION: {
                    c.drawCircle(s / 2, s / 2, s * .46f, p);
                    p.setColor(Color.parseColor("#E4E6EB"));
                    p.setTextAlign(Paint.Align.CENTER);
                    p.setTypeface(Typeface.DEFAULT_BOLD);
                    p.setTextSize(s * .72f);
                    c.drawText("?", s / 2, s * .73f, p);
                    break;
                }
                case INFO: {
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(s * .07f);
                    c.drawCircle(s / 2, s / 2, s * .44f, p);
                    p.setStyle(Paint.Style.FILL);
                    p.setTextAlign(Paint.Align.CENTER);
                    p.setTypeface(Typeface.DEFAULT_BOLD);
                    p.setTextSize(s * .62f);
                    c.drawText("i", s / 2, s * .72f, p);
                    break;
                }
            }
        }
    }
}
