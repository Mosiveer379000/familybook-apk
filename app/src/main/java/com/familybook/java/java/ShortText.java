package com.familybook.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.Editable;
import android.text.InputType;
import android.text.Layout;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.OptIn;
import androidx.media3.common.Effect;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.BitmapOverlay;
import androidx.media3.effect.OverlayEffect;
import androidx.media3.effect.OverlaySettings;
import androidx.media3.effect.TextureOverlay;

import com.google.common.collect.ImmutableList;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/*
 * Shorts এডিটের "Text" সুবিধা (একটি ফাইলে সব):
 *   TextItem        — একটি টেক্সটের সব তথ্য (লেখা, রং, স্ট্রোক, ব্যাকগ্রাউন্ড, ফন্ট, জায়গা, সময়)
 *   TextFonts       — ফন্ট তালিকা (সিস্টেম + assets/fonts-এর ৭টি ফন্ট) ও রঙের তালিকা
 *   TextRender      — প্রিভিউ ও এক্সপোর্ট দুই জায়গায় একই ছবি আঁকে
 *   TIcon / TSwatch — ছোট আইকন ও রঙের গোল
 *   TextEditor      — টেক্সট এডিট প্যানেল (কিবোর্ড + Text/Stroke/Background রং + ফন্ট)
 *   TextOverlayView — ভিডিওর ওপরে টেক্সট দেখায়; টানা/পিঞ্চ/ঘোরানো
 *   TimelineView    — টাইমলাইন (Audio ট্র্যাক + টেক্সট ক্লিপ, ট্রিম হ্যান্ডেল)
 *   ShortTextTool   — সব জোড়া দেয়; Next-এ ভিডিওতে টেক্সট বসানোর OverlayEffect বানায়
 */

// ============================================================ ডেটা

final class TextItem {
    String text = "";
    int color = Color.WHITE;   // টেক্সটের রং
    int stroke = 0;            // স্ট্রোক রং (0 = নেই)
    int bg = 0;                // ব্যাকগ্রাউন্ড রং (0 = নেই)
    int font = 0;
    int align = 0;             // 0 মাঝে, 1 বামে, 2 ডানে
    float cx = 0.5f, cy = 0.5f; // কেন্দ্র (ভিডিওর ০..১)
    float size = 0.09f;        // ফন্টের মাপ = ভিডিওর প্রস্থের ভগ্নাংশ
    float rot = 0f;
    long start = 0, end = 3000;
    Bitmap bmp = null;         // স্টিকার/GIF ছবি (থাকলে লেখার বদলে ছবি আঁকা হয়)

    TextItem copy() {
        TextItem t = new TextItem();
        t.text = text; t.color = color; t.stroke = stroke; t.bg = bg; t.font = font; t.align = align;
        t.cx = cx; t.cy = cy; t.size = size; t.rot = rot; t.start = start; t.end = end; t.bmp = bmp;
        return t;
    }

    boolean visibleAt(long ms) { return ms >= start && ms < end; }

    boolean empty() { return bmp == null && (text == null || text.trim().isEmpty()); }

    static List<TextItem> copyAll(List<TextItem> src) {
        List<TextItem> l = new ArrayList<>();
        for (TextItem t : src) l.add(t.copy());
        return l;
    }
}

final class TextFonts {
    private TextFonts() { }

    /** নাম | সিস্টেম ফ্যামিলি | স্টাইল | assets পথ (থাকলে) */
    private static final String[][] F = {
            {"Classic", "sans-serif", "B", null},
            {"Neon", "cursive", "N", null},
            {"Typewriter", "monospace", "B", null},
            {"Strong", "sans-serif-black", "I", null},
            {"Meme", "sans-serif-condensed", "B", null},
            {"Elegant", "serif", "I", null},
            {"Literature", "serif", "N", null},
            {"Heavy", "sans-serif-black", "N", null},
            {"Casual", "casual", "N", null},
            {"Slab Serif", "serif", "B", null},
            {"Geometric", "sans-serif-medium", "N", null},
            // assets/fonts-এর ৭টি ফন্ট (নাম অবশ্যই ফাইলের নামের সাথে হুবহু মিলতে হবে; বাংলা সাপোর্ট: Sohid Osman Hadi, Bornomala Bold)
            {"sohid osman Hadi", null, "N", "fonts/sohid osman hadi.ttf"},
            {"monospace bold", null, "N", "fonts/monospace bold.ttf"},
            {"gondens demo", null, "N", "fonts/gondens demo.otf"},
            {"jameel noori nastaleeq reqular", null, "N", "fonts/jameel noori nastaleeq regular.ttf"},
            {"bornomala bold", null, "N", "fonts/bornomala bold.ttf"},
            {"montserrat bold", null, "N", "fonts/montserrat bold.ttf"},
            {"pixel purl", null, "N", "fonts/pixel purl.ttf"},
    };

    private static final Typeface[] CACHE = new Typeface[F.length];

    static int count() { return F.length; }

    static String name(int i) { return F[clamp(i)][0]; }

    private static int clamp(int i) { return Math.max(0, Math.min(F.length - 1, i)); }

    static synchronized Typeface get(Context c, int i) {
        i = clamp(i);
        if (CACHE[i] != null) return CACHE[i];
        Typeface t = null;
        try {
            if (F[i][3] != null) t = Typeface.createFromAsset(c.getApplicationContext().getAssets(), F[i][3]);
        } catch (Throwable ignored) { }
        if (t == null) {
            String st = F[i][2];
            int style = "B".equals(st) ? Typeface.BOLD : "I".equals(st) ? Typeface.ITALIC : Typeface.NORMAL;
            try { t = Typeface.create(F[i][1] == null ? "sans-serif" : F[i][1], style); } catch (Throwable ignored) { }
            if (t == null) t = Typeface.DEFAULT_BOLD;
        }
        CACHE[i] = t;
        return t;
    }

    static final String[] CN = {"White", "Black", "Red", "Red-pink", "Pink", "Magenta", "Purple", "Indigo", "Blue",
            "Light blue", "Cyan", "Teal", "Green", "Lime", "Yellow", "Pale yellow", "Pale orange", "Orange",
            "Peach", "Brown", "Dark brown", "Gray", "Silver"};
    static final int[] CC = {0xFFFFFFFF, 0xFF000000, 0xFFEE0A1E, 0xFFE8838F, 0xFFFFC9D0, 0xFFCC0A6F, 0xFF9C0DBA,
            0xFF5B2EE0, 0xFF1F6FEB, 0xFF58B4F5, 0xFF18D6D6, 0xFF0E9C8F, 0xFF2DB84B, 0xFFA7DB2E, 0xFFFFD60A,
            0xFFFFF0A0, 0xFFFDC283, 0xFFF58A1F, 0xFFFFDAB3, 0xFF9A5F32, 0xFF45201F, 0xFF8A8A8A, 0xFFD0D0D0};

    static String colorName(int c) {
        if (c == 0) return "None";
        for (int i = 0; i < CC.length; i++) if (CC[i] == c) return CN[i];
        return "Custom";
    }

    static boolean isLight(int c) {
        return (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f > 0.6f;
    }
}

// ============================================================ আঁকা (প্রিভিউ = এক্সপোর্ট)

final class TextRender {
    private TextRender() { }

    static final class Lay { StaticLayout sl; TextPaint p; int w, h; }

    /** fixedW <= 0 হলে লেখার মাপ অনুযায়ী প্রস্থ (সর্বোচ্চ unitW-এর ৮৬%) */
    static Lay layout(Context c, TextItem it, float unitW, int fixedW) {
        if (it.bmp != null) {
            Lay b = new Lay();
            b.w = Math.max(8, Math.round(it.size * unitW));
            b.h = Math.max(8, Math.round(b.w * it.bmp.getHeight() / (float) it.bmp.getWidth()));
            return b;
        }
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(TextFonts.get(c, it.font));
        p.setTextSize(Math.max(4f, it.size * unitW));
        String t = it.text == null ? "" : it.text;
        int w = fixedW;
        if (w <= 0) {
            float d = Layout.getDesiredWidth(t, p);
            w = (int) Math.ceil(Math.min(unitW * 0.86f, d)) + 2;
        }
        Layout.Alignment al = it.align == 1 ? Layout.Alignment.ALIGN_NORMAL
                : it.align == 2 ? Layout.Alignment.ALIGN_OPPOSITE : Layout.Alignment.ALIGN_CENTER;
        Lay l = new Lay();
        l.p = p;
        l.w = w;
        l.sl = StaticLayout.Builder.obtain(t, 0, t.length(), p, w).setAlignment(al).build();
        l.h = l.sl.getHeight();
        return l;
    }

    /** (0,0) = লেআউটের বাঁ-উপর কোণ */
    static void paint(Canvas cv, Lay l, TextItem it) {
        if (it.bmp != null) {
            Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            cv.drawBitmap(it.bmp, null, new RectF(0, 0, l.w, l.h), bp);
            return;
        }
        TextPaint p = l.p;
        float ts = p.getTextSize();
        if (it.bg != 0) {
            Paint b = new Paint(Paint.ANTI_ALIAS_FLAG);
            b.setColor(it.bg);
            float pad = ts * 0.22f, rad = ts * 0.3f;
            for (int i = 0; i < l.sl.getLineCount(); i++) {
                float a = l.sl.getLineLeft(i), z = l.sl.getLineRight(i);
                if (z - a < 1f) continue;
                cv.drawRoundRect(new RectF(a - pad, l.sl.getLineTop(i) - pad * 0.3f, z + pad,
                        l.sl.getLineBottom(i) + pad * 0.3f), rad, rad, b);
            }
        }
        if (it.stroke != 0) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setStrokeWidth(ts * 0.16f);
            p.setColor(it.stroke);
            l.sl.draw(cv);
        }
        p.setStyle(Paint.Style.FILL);
        p.setColor(it.color);
        l.sl.draw(cv);
    }

    static void draw(Canvas cv, Context c, TextItem it, float cx, float cy, float unitW) {
        if (it.empty()) return;
        Lay l = layout(c, it, unitW, 0);
        cv.save();
        cv.translate(cx, cy);
        cv.rotate(it.rot);
        cv.translate(-l.w / 2f, -l.h / 2f);
        paint(cv, l, it);
        cv.restore();
    }
}

// ============================================================ ছোট ভিউ

final class TIcon extends View {
    static final int STYLE = 0, CLOCK = 1, WHEEL = 2, FONT = 3, ALIGN = 4, PLAY = 5, PAUSE = 6, UNDO = 7,
            REDO = 8, AA = 9, PENCIL = 10, TRASH = 11;
    int type, mode;
    boolean active;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    TIcon(Context c, int type) { super(c); this.type = type; }

    void set(int mode, boolean active) { this.mode = mode; this.active = active; invalidate(); }

    @Override protected void onDraw(Canvas c) {
        float s = Math.min(getWidth(), getHeight());
        c.save();
        c.translate((getWidth() - s) / 2f, (getHeight() - s) / 2f);
        p.setShader(null);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextAlign(Paint.Align.LEFT);
        p.setStyle(Paint.Style.STROKE);
        p.setColor(Color.WHITE);
        p.setStrokeWidth(s * 0.075f);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        float h = s / 2f;
        switch (type) {
            case CLOCK:
                c.drawCircle(h, h, s * 0.38f, p);
                c.drawLine(h, h, h, s * 0.28f, p);
                c.drawLine(h, h, s * 0.66f, s * 0.58f, p);
                break;
            case WHEEL:
                p.setStyle(Paint.Style.FILL);
                p.setShader(new SweepGradient(h, h, new int[]{0xFFFF3B30, 0xFFFFCC00, 0xFF34C759, 0xFF00C7FF,
                        0xFF5856D6, 0xFFFF2D95, 0xFFFF3B30}, null));
                c.drawCircle(h, h, s * 0.40f, p);
                p.setShader(null);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(s * 0.06f);
                c.drawCircle(h, h, s * 0.43f, p);
                break;
            case FONT:
            case AA: {
                boolean box = type == FONT;
                RectF r = new RectF(s * 0.08f, s * 0.12f, s * 0.92f, s * 0.88f);
                boolean fill = box && active;
                if (box) {
                    p.setStyle(fill ? Paint.Style.FILL : Paint.Style.STROKE);
                    c.drawRoundRect(r, s * 0.2f, s * 0.2f, p);
                }
                p.setStyle(Paint.Style.FILL);
                p.setColor(fill ? Color.BLACK : Color.WHITE);
                p.setTextSize(s * (box ? 0.42f : 0.62f));
                p.setTextAlign(Paint.Align.CENTER);
                c.drawText("Aa", h, h + s * (box ? 0.15f : 0.22f), p);
                break;
            }
            case STYLE: {
                RectF r = new RectF(s * 0.1f, s * 0.1f, s * 0.9f, s * 0.9f);
                if (mode == 1) { p.setStyle(Paint.Style.FILL); c.drawRoundRect(r, s * 0.2f, s * 0.2f, p); }
                else c.drawRoundRect(r, s * 0.2f, s * 0.2f, p);
                p.setStyle(mode == 2 ? Paint.Style.STROKE : Paint.Style.FILL);
                p.setStrokeWidth(s * 0.03f);
                p.setColor(mode == 1 ? Color.BLACK : Color.WHITE);
                p.setTextSize(s * 0.5f);
                p.setTextAlign(Paint.Align.CENTER);
                c.drawText("A", h, h + s * 0.18f, p);
                break;
            }
            case ALIGN: {
                float[] wd = {0.7f, 0.42f, 0.58f};
                for (int i = 0; i < 3; i++) {
                    float y = s * (0.3f + 0.2f * i), w = wd[i] * s;
                    float x0 = mode == 1 ? s * 0.15f : mode == 2 ? s * 0.85f - w : h - w / 2f;
                    c.drawLine(x0, y, x0 + w, y, p);
                }
                break;
            }
            case PLAY:
                p.setStyle(Paint.Style.FILL);
                path.reset();
                path.moveTo(s * 0.32f, s * 0.2f); path.lineTo(s * 0.78f, h); path.lineTo(s * 0.32f, s * 0.8f); path.close();
                c.drawPath(path, p);
                break;
            case PAUSE:
                p.setStyle(Paint.Style.FILL);
                c.drawRoundRect(new RectF(s * 0.27f, s * 0.2f, s * 0.43f, s * 0.8f), s * 0.05f, s * 0.05f, p);
                c.drawRoundRect(new RectF(s * 0.57f, s * 0.2f, s * 0.73f, s * 0.8f), s * 0.05f, s * 0.05f, p);
                break;
            case UNDO:
            case REDO:
                if (type == REDO) c.scale(-1f, 1f, h, h);
                c.drawArc(new RectF(s * 0.22f, s * 0.3f, s * 0.82f, s * 0.82f), 180f, 180f, false, p);
                c.drawLine(s * 0.22f, s * 0.56f, s * 0.1f, s * 0.42f, p);
                c.drawLine(s * 0.22f, s * 0.56f, s * 0.38f, s * 0.46f, p);
                break;
            case PENCIL:
                c.save();
                c.rotate(45f, h, h);
                path.reset();
                path.moveTo(s * 0.42f, s * 0.1f); path.lineTo(s * 0.58f, s * 0.1f); path.lineTo(s * 0.58f, s * 0.7f);
                path.lineTo(h, s * 0.88f); path.lineTo(s * 0.42f, s * 0.7f); path.close();
                c.drawPath(path, p);
                c.drawLine(s * 0.42f, s * 0.26f, s * 0.58f, s * 0.26f, p);
                c.restore();
                break;
            case TRASH:
                c.drawLine(s * 0.2f, s * 0.28f, s * 0.8f, s * 0.28f, p);
                path.reset();
                path.moveTo(s * 0.4f, s * 0.28f); path.lineTo(s * 0.4f, s * 0.16f); path.lineTo(s * 0.6f, s * 0.16f);
                path.lineTo(s * 0.6f, s * 0.28f);
                c.drawPath(path, p);
                path.reset();
                path.moveTo(s * 0.27f, s * 0.28f); path.lineTo(s * 0.32f, s * 0.86f); path.lineTo(s * 0.68f, s * 0.86f);
                path.lineTo(s * 0.73f, s * 0.28f);
                c.drawPath(path, p);
                c.drawLine(s * 0.43f, s * 0.42f, s * 0.43f, s * 0.72f, p);
                c.drawLine(s * 0.57f, s * 0.42f, s * 0.57f, s * 0.72f, p);
                break;
            default: break;
        }
        c.restore();
    }
}

/** রঙের গোল (color == 0 হলে "None") */
final class TSwatch extends View {
    final int color;
    boolean sel;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    TSwatch(Context c, int color) { super(c); this.color = color; }

    @Override protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(cx, cy);
        float dpv = getResources().getDisplayMetrics().density;
        float inner = r - 5f * dpv;
        p.setStyle(Paint.Style.FILL);
        p.setColor(color == 0 ? 0xFF2A2A2A : color);
        c.drawCircle(cx, cy, inner, p);
        if (color == 0) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2.5f * dpv);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(0xFFEE3B3B);
            c.drawLine(cx - inner * 0.6f, cy + inner * 0.6f, cx + inner * 0.6f, cy - inner * 0.6f, p);
        }
        if (sel) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2.5f * dpv);
            p.setColor(Color.WHITE);
            c.drawCircle(cx, cy, r - 1.5f * dpv, p);
        }
    }
}

// ============================================================ টেক্সট এডিট প্যানেল

/** এডিটরে লেখার ছবি (স্ট্রোক/ব্যাকগ্রাউন্ডসহ) — ওপরে স্বচ্ছ EditText বসে */
final class TPreview extends View {
    TextItem it;
    private TextRender.Lay lay;
    private final float unit;

    TPreview(Context c, TextItem it) {
        super(c);
        this.it = it;
        unit = c.getResources().getDisplayMetrics().widthPixels;
    }

    @Override protected void onMeasure(int ws, int hs) {
        int w = MeasureSpec.getSize(ws);
        if (it.empty()) {
            lay = null;
            setMeasuredDimension(w, Math.round(it.size * unit * 1.25f));
            return;
        }
        lay = TextRender.layout(getContext(), it, unit, w);
        setMeasuredDimension(w, lay.h);
    }

    @Override protected void onDraw(Canvas c) {
        if (lay == null || it.empty()) return;
        lay = TextRender.layout(getContext(), it, unit, getWidth());
        TextRender.paint(c, lay, it);
    }
}

final class TextEditor extends FrameLayout {

    interface CB { void onDone(TextItem it); }

    private TextItem it = new TextItem();
    private CB cb;
    private final float unit;
    private final EditText et;
    private final TPreview pv;
    private final TIcon icStyle, icClock, icWheel, icFont, icAlign;
    private final LinearLayout colorPanel;
    private final HorizontalScrollView swScroll, fontScroll;
    private final LinearLayout swRow, fontRow;
    private final TextView tip;
    private final TextView[] seg = new TextView[3];
    private final List<TextView> fontChips = new ArrayList<>();
    private final List<TSwatch> swatches = new ArrayList<>();
    private int target = 0, styleMode = 0;
    private boolean loading = false, fontMode = false;

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    TextEditor(Context c) {
        super(c);
        unit = c.getResources().getDisplayMetrics().widthPixels;
        setBackgroundColor(0xEE000000);
        setClickable(true);
        setVisibility(GONE);

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        addView(col, new FrameLayout.LayoutParams(-1, -1));

        // ---- উপরের বার
        LinearLayout top = new LinearLayout(c);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(8), dp(30), dp(12), dp(8));
        LinearLayout icons = new LinearLayout(c);
        icons.setGravity(Gravity.CENTER);
        icStyle = icon(icons, TIcon.STYLE);
        icClock = icon(icons, TIcon.CLOCK);
        icWheel = icon(icons, TIcon.WHEEL);
        icFont = icon(icons, TIcon.FONT);
        icAlign = icon(icons, TIcon.ALIGN);
        top.addView(icons, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView done = pill("Done", true);
        done.setBackground(round(0xFF262626, 12));
        done.setTextColor(Color.WHITE);
        done.setPadding(dp(16), dp(7), dp(16), dp(7));
        done.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        done.setOnClickListener(v -> finish());
        top.addView(done);
        col.addView(top, new LinearLayout.LayoutParams(-1, -2));

        // ---- মাঝে: লেখা
        FrameLayout mid = new FrameLayout(c);
        mid.setClipChildren(false);
        col.addView(mid, new LinearLayout.LayoutParams(-1, 0, 1f));
        pv = new TPreview(c, it);
        FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        mp.leftMargin = dp(28); mp.rightMargin = dp(28);
        mid.addView(pv, mp);
        et = new EditText(c);
        et.setBackground(null);
        et.setPadding(0, 0, 0, 0);
        et.setMinHeight(0); et.setMinimumHeight(0);
        et.setTextColor(Color.TRANSPARENT);
        et.setHintTextColor(0x99FFFFFF);
        et.setHint("Start typing");
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        et.setLineSpacing(0f, 1f);
        if (Build.VERSION.SDK_INT >= 29) {
            GradientDrawable cur = new GradientDrawable();
            cur.setColor(Color.WHITE);
            cur.setSize(dp(2), dp(30));
            et.setTextCursorDrawable(cur);
        }
        FrameLayout.LayoutParams ep = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        ep.leftMargin = dp(28); ep.rightMargin = dp(28);
        mid.addView(et, ep);
        et.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void afterTextChanged(Editable e) {
                if (loading) return;
                it.text = e.toString();
                pv.requestLayout(); pv.invalidate();
            }
        });

        // ---- নিচের প্যানেল: রং / ফন্ট
        FrameLayout bottom = new FrameLayout(c);
        col.addView(bottom, new LinearLayout.LayoutParams(-1, -2));

        colorPanel = new LinearLayout(c);
        colorPanel.setOrientation(LinearLayout.VERTICAL);
        colorPanel.setGravity(Gravity.CENTER_HORIZONTAL);
        bottom.addView(colorPanel, new FrameLayout.LayoutParams(-1, -2));

        LinearLayout segRow = new LinearLayout(c);
        segRow.setGravity(Gravity.CENTER);
        String[] sn = {"Text", "Stroke", "Background"};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            TextView t = pill(sn[i], false);
            t.setOnClickListener(v -> { target = idx; buildSwatches(); refreshAll(); });
            seg[i] = t;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.leftMargin = dp(4); lp.rightMargin = dp(4);
            segRow.addView(t, lp);
        }
        colorPanel.addView(segRow, new LinearLayout.LayoutParams(-2, -2));

        tip = new TextView(c);
        tip.setTextColor(Color.WHITE);
        tip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tip.setTypeface(null, Typeface.BOLD);
        tip.setBackground(round(0xFF262626, 12));
        tip.setPadding(dp(12), dp(5), dp(12), dp(5));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.topMargin = dp(10);
        colorPanel.addView(tip, tp);

        swScroll = new HorizontalScrollView(c);
        swScroll.setHorizontalScrollBarEnabled(false);
        swRow = new LinearLayout(c);
        swRow.setGravity(Gravity.CENTER_VERTICAL);
        swRow.setPadding(dp(12), 0, dp(12), 0);
        swScroll.addView(swRow, new FrameLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, dp(48));
        sp.topMargin = dp(2);
        colorPanel.addView(swScroll, sp);

        fontScroll = new HorizontalScrollView(c);
        fontScroll.setHorizontalScrollBarEnabled(false);
        fontRow = new LinearLayout(c);
        fontRow.setGravity(Gravity.CENTER_VERTICAL);
        fontRow.setPadding(dp(12), 0, dp(12), 0);
        fontScroll.addView(fontRow, new FrameLayout.LayoutParams(-2, -2));
        bottom.addView(fontScroll, new FrameLayout.LayoutParams(-1, dp(64)));
        for (int i = 0; i < TextFonts.count(); i++) {
            final int fi = i;
            TextView t = new TextView(c);
            t.setText(TextFonts.name(i));
            t.setTypeface(TextFonts.get(c, i));
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            t.setGravity(Gravity.CENTER);
            t.setSingleLine(true);
            t.setPadding(dp(14), dp(8), dp(14), dp(8));
            t.setOnClickListener(v -> { it.font = fi; apply(); scrollFontTo(fi); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.leftMargin = dp(5); lp.rightMargin = dp(5);
            fontRow.addView(t, lp);
            fontChips.add(t);
        }

        // ---- আইকনের কাজ
        icStyle.setOnClickListener(v -> cycleStyle());
        icClock.setOnClickListener(v -> finish());
        icWheel.setOnClickListener(v -> { fontMode = false; refreshAll(); });
        icFont.setOnClickListener(v -> { fontMode = true; refreshAll(); scrollFontTo(it.font); });
        icAlign.setOnClickListener(v -> { it.align = (it.align + 1) % 3; apply(); });
    }

    private TIcon icon(LinearLayout row, int type) {
        TIcon i = new TIcon(getContext(), type);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(34), dp(34));
        lp.leftMargin = dp(5); lp.rightMargin = dp(5);
        row.addView(i, lp);
        return i;
    }

    private TextView pill(String s, boolean bold) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTypeface(null, Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), dp(5), dp(12), dp(5));
        return t;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    void show(TextItem src, CB cb) {
        this.cb = cb;
        it = src.copy();
        pv.it = it;
        loading = true;
        et.setText(it.text);
        et.setSelection(et.getText().length());
        loading = false;
        styleMode = it.stroke != 0 ? 2 : it.bg != 0 ? 1 : 0;
        target = 0;
        fontMode = false;
        buildSwatches();
        apply();
        setVisibility(VISIBLE);
        et.requestFocus();
        et.postDelayed(() -> {
            InputMethodManager m = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (m != null) m.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT);
        }, 120);
    }

    boolean isOpen() { return getVisibility() == VISIBLE; }

    void finish() {
        if (!isOpen()) return;
        InputMethodManager m = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (m != null) m.hideSoftInputFromWindow(et.getWindowToken(), 0);
        setVisibility(GONE);
        if (cb != null) { CB k = cb; cb = null; k.onDone(it.copy()); }
    }

    private void cycleStyle() {
        styleMode = (styleMode + 1) % 3;
        if (styleMode == 0) { it.stroke = 0; it.bg = 0; }
        else if (styleMode == 1) {
            it.bg = it.color; it.stroke = 0;
            it.color = TextFonts.isLight(it.bg) ? Color.BLACK : Color.WHITE;
        } else {
            if (it.bg != 0) { it.color = it.bg; }
            it.bg = 0;
            it.stroke = TextFonts.isLight(it.color) ? Color.BLACK : Color.WHITE;
        }
        buildSwatches();
        apply();
    }

    private void buildSwatches() {
        swRow.removeAllViews();
        swatches.clear();
        if (target != 0) addSwatch(0);
        for (int c : TextFonts.CC) addSwatch(c);
    }

    private void addSwatch(final int color) {
        TSwatch s = new TSwatch(getContext(), color);
        s.setOnClickListener(v -> {
            if (target == 0) it.color = color;
            else if (target == 1) it.stroke = color;
            else it.bg = color;
            styleMode = it.stroke != 0 ? 2 : it.bg != 0 ? 1 : 0;
            apply();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(36), dp(36));
        lp.leftMargin = dp(2); lp.rightMargin = dp(2);
        swRow.addView(s, lp);
        swatches.add(s);
    }

    private int current() { return target == 0 ? it.color : target == 1 ? it.stroke : it.bg; }

    private void scrollFontTo(final int i) {
        fontScroll.post(() -> {
            if (i < 0 || i >= fontChips.size()) return;
            View v = fontChips.get(i);
            fontScroll.smoothScrollTo(v.getLeft() - (fontScroll.getWidth() - v.getWidth()) / 2, 0);
        });
    }

    private void apply() {
        et.setTypeface(TextFonts.get(getContext(), it.font));
        et.setTextSize(TypedValue.COMPLEX_UNIT_PX, it.size * unit);
        et.setGravity((it.align == 1 ? Gravity.START : it.align == 2 ? Gravity.END : Gravity.CENTER_HORIZONTAL) | Gravity.TOP);
        pv.it = it;
        pv.requestLayout();
        pv.invalidate();
        refreshAll();
    }

    private void refreshAll() {
        colorPanel.setVisibility(fontMode ? GONE : VISIBLE);
        fontScroll.setVisibility(fontMode ? VISIBLE : GONE);
        icWheel.set(0, !fontMode);
        icFont.set(0, fontMode);
        icAlign.set(it.align, true);
        icStyle.set(styleMode, true);
        for (int i = 0; i < 3; i++) {
            boolean on = i == target;
            seg[i].setBackground(round(on ? Color.WHITE : 0xFF262626, 16));
            seg[i].setTextColor(on ? Color.BLACK : Color.WHITE);
        }
        int cur = current();
        tip.setText(TextFonts.colorName(cur));
        for (TSwatch s : swatches) { s.sel = s.color == cur; s.invalidate(); }
        for (int i = 0; i < fontChips.size(); i++) {
            boolean on = i == it.font;
            TextView t = fontChips.get(i);
            t.setBackground(round(on ? Color.WHITE : 0xFF262626, 30));
            t.setTextColor(on ? Color.BLACK : Color.WHITE);
        }
    }
}

// ============================================================ ভিডিওর ওপরের টেক্সট ভিউ

final class TextOverlayView extends View {

    interface L { void select(int i); void before(); }

    private final List<TextItem> items;
    private long time = 0;
    int sel = -1;
    boolean interactive = false;
    L listener;
    private final Paint dash = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float dpv;
    private float lx, ly, d0, a0, sz0, rot0;
    private int mode = 0;
    private boolean pushed = false;

    TextOverlayView(Context c, List<TextItem> items) {
        super(c);
        this.items = items;
        dpv = c.getResources().getDisplayMetrics().density;
        dash.setStyle(Paint.Style.STROKE);
        dash.setStrokeWidth(1.5f * dpv);
        dash.setColor(0xCCFFFFFF);
        dash.setPathEffect(new DashPathEffect(new float[]{6f * dpv, 4f * dpv}, 0));
    }

    void setTime(long t) { if (t != time) { time = t; invalidate(); } }

    void setSel(int i) { sel = i; invalidate(); }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        if (w <= 0) return;
        for (TextItem it : items)
            if (it.visibleAt(time)) TextRender.draw(c, getContext(), it, it.cx * w, it.cy * h, w);
        if (interactive && sel >= 0 && sel < items.size()) {
            TextItem it = items.get(sel);
            if (it.visibleAt(time) && !it.empty()) {
                TextRender.Lay l = TextRender.layout(getContext(), it, w, 0);
                float pd = 8f * dpv;
                c.save();
                c.translate(it.cx * w, it.cy * h);
                c.rotate(it.rot);
                c.drawRect(-l.w / 2f - pd, -l.h / 2f - pd, l.w / 2f + pd, l.h / 2f + pd, dash);
                c.restore();
            }
        }
    }

    private int hit(float x, float y) {
        float w = getWidth(), h = getHeight();
        for (int i = items.size() - 1; i >= 0; i--) {
            TextItem it = items.get(i);
            if (!it.visibleAt(time) || it.empty()) continue;
            TextRender.Lay l = TextRender.layout(getContext(), it, w, 0);
            double th = Math.toRadians(it.rot);
            float dx = x - it.cx * w, dy = y - it.cy * h;
            float px = (float) (dx * Math.cos(th) + dy * Math.sin(th));
            float py = (float) (-dx * Math.sin(th) + dy * Math.cos(th));
            float pd = 12f * dpv;
            if (Math.abs(px) <= l.w / 2f + pd && Math.abs(py) <= l.h / 2f + pd) return i;
        }
        return -1;
    }

    private void before() {
        if (!pushed) { pushed = true; if (listener != null) listener.before(); }
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (!interactive) return false;
        float w = Math.max(1, getWidth()), h = Math.max(1, getHeight());
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                pushed = false;
                int i = hit(e.getX(), e.getY());
                if (i < 0) { mode = 0; return false; }
                sel = i;
                if (listener != null) listener.select(i);
                invalidate();
                mode = 1; lx = e.getX(); ly = e.getY();
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN:
                if (e.getPointerCount() == 2 && sel >= 0 && sel < items.size()) {
                    mode = 2;
                    d0 = Math.max(1f, dist(e));
                    a0 = ang(e);
                    sz0 = items.get(sel).size; rot0 = items.get(sel).rot;
                }
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (sel < 0 || sel >= items.size()) return true;
                TextItem it = items.get(sel);
                if (mode == 1 && e.getPointerCount() == 1) {
                    before();
                    it.cx = Math.max(0f, Math.min(1f, it.cx + (e.getX() - lx) / w));
                    it.cy = Math.max(0f, Math.min(1f, it.cy + (e.getY() - ly) / h));
                    lx = e.getX(); ly = e.getY();
                    invalidate();
                } else if (mode == 2 && e.getPointerCount() >= 2) {
                    before();
                    it.size = Math.max(0.02f, Math.min(0.5f, sz0 * dist(e) / d0));
                    it.rot = rot0 + (ang(e) - a0);
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_POINTER_UP:
                if (mode == 2) {
                    mode = 1;
                    int k = e.getActionIndex() == 0 ? 1 : 0;
                    lx = e.getX(k); ly = e.getY(k);
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mode = 0;
                return true;
            default: return true;
        }
    }

    private static float dist(MotionEvent e) {
        float dx = e.getX(0) - e.getX(1), dy = e.getY(0) - e.getY(1);
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static float ang(MotionEvent e) {
        return (float) Math.toDegrees(Math.atan2(e.getY(1) - e.getY(0), e.getX(1) - e.getX(0)));
    }
}

// ============================================================ টাইমলাইন

final class TimelineView extends View {

    interface L { void scrub(long ms); void select(int i); void before(); void changed(); void audioTap(); }

    private static final int NONE = 0, SCRUB = 1, TRIM_L = 2, TRIM_R = 3, MOVE = 4;

    private final List<TextItem> items;
    L listener;
    int sel = -1;
    long head = 0, lo = 0, hi = 1000;
    boolean hasAudio = false;
    String audioLabel = "Audio";
    private boolean scrubbing = false;
    private int mode = NONE, lanes = 1;
    private int[] lane = new int[0];
    private float downX, downY, ppms;
    private long origS, origE, origHead;
    private boolean moved = false, pushed = false;
    private final float dpv;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dashP = new Paint(Paint.ANTI_ALIAS_FLAG);

    TimelineView(Context c, List<TextItem> items) {
        super(c);
        this.items = items;
        dpv = c.getResources().getDisplayMetrics().density;
        ppms = 60f * dpv / 1000f;
        dashP.setStyle(Paint.Style.STROKE);
        dashP.setStrokeWidth(1.5f * dpv);
        dashP.setColor(0xAAFFFFFF);
        dashP.setPathEffect(new DashPathEffect(new float[]{6f * dpv, 4f * dpv}, 0));
    }

    boolean isScrubbing() { return scrubbing; }

    void setHead(long ms) { if (ms != head) { head = ms; invalidate(); } }

    void refresh() {
        int old = lanes;
        recompute();
        if (old != lanes) requestLayout();
        invalidate();
    }

    private void recompute() {
        lane = new int[items.size()];
        int mx = 0;
        for (int i = 0; i < items.size(); i++) {
            int ln = 0;
            while (true) {
                boolean clash = false;
                for (int j = 0; j < i; j++) {
                    if (lane[j] == ln && items.get(j).start < items.get(i).end && items.get(i).start < items.get(j).end) { clash = true; break; }
                }
                if (!clash) break;
                ln++;
            }
            lane[i] = ln;
            mx = Math.max(mx, ln);
        }
        lanes = Math.min(mx + 1, 4);
    }

    private float rowTop(int ln) { return 70f * dpv + ln * 46f * dpv; }

    private float x(long t) { return getWidth() / 2f + (t - head) * ppms; }

    @Override protected void onMeasure(int ws, int hs) {
        recompute();
        int h = Math.round(rowTop(0) + lanes * 46f * dpv + 6f * dpv);
        setMeasuredDimension(MeasureSpec.getSize(ws), h);
    }

    private static RectF rf(float l, float t, float r, float b) { return new RectF(l, t, r, b); }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), hgt = getHeight();
        // সেকেন্ডের বিন্দু
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x88FFFFFF);
        for (long s = (lo / 1000) * 1000; s <= hi; s += 1000) {
            if (s < lo) continue;
            float xx = x(s);
            if (xx < -4 || xx > w + 4) continue;
            c.drawCircle(xx, 8f * dpv, 1.6f * dpv, p);
        }
        // Audio ট্র্যাক
        float aL = x(lo), aR = x(hi), aT = 22f * dpv, aB = aT + 40f * dpv;
        if (aR > 0 && aL < w) {
            if (hasAudio) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xFF2E7D6B);
                c.drawRoundRect(rf(aL, aT, aR, aB), 10f * dpv, 10f * dpv, p);
            } else {
                c.drawRoundRect(rf(aL, aT, aR, aB), 10f * dpv, 10f * dpv, dashP);
            }
            c.save();
            c.clipRect(Math.max(aL, 0), aT, Math.min(aR, w), aB);
            float tx = Math.max(aL, 0) + 12f * dpv;
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFDDDDDD);
            if (!hasAudio) {
                c.drawRoundRect(rf(tx, aT + 11f * dpv, tx + 18f * dpv, aT + 29f * dpv), 4f * dpv, 4f * dpv, p);
                p.setColor(0xFF111111);
                p.setStrokeWidth(2f * dpv);
                c.drawLine(tx + 9f * dpv - 5f * dpv, aT + 20f * dpv, tx + 9f * dpv + 5f * dpv, aT + 20f * dpv, p);
                c.drawLine(tx + 9f * dpv, aT + 15f * dpv, tx + 9f * dpv, aT + 25f * dpv, p);
                tx += 24f * dpv;
            }
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFDDDDDD);
            p.setTextSize(15f * dpv);
            p.setTypeface(Typeface.DEFAULT);
            String lb = hasAudio ? "♪ " + (audioLabel == null ? "Audio" : audioLabel) : "Audio";
            c.drawText(lb, tx, aT + 26f * dpv, p);
            c.restore();
        }
        // টেক্সট ক্লিপ
        for (int i = 0; i < items.size() && i < lane.length; i++) {
            if (lane[i] > 3) continue;
            TextItem it = items.get(i);
            float l = x(it.start), r = x(it.end);
            if (r < 0 || l > w) continue;
            float t = rowTop(lane[i]), b = t + 40f * dpv;
            boolean s = i == sel;
            String lb = it.text == null ? "" : it.text.replace('\n', ' ');
            if (s) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xFFFFD60A);
                c.drawRoundRect(rf(l, t, r, b), 10f * dpv, 10f * dpv, p);
                p.setColor(0xFF6C63E6);
                float il = l + 20f * dpv, ir = r - 20f * dpv;
                if (ir > il) {
                    c.drawRect(il, t + 3f * dpv, ir, b - 3f * dpv, p);
                    drawLabel(c, lb, il + 6f * dpv, t, il, ir);
                }
                p.setColor(0xFF7A2E0E);
                c.drawRoundRect(rf(l + 7f * dpv, t + 8f * dpv, l + 11f * dpv, b - 8f * dpv), 2f * dpv, 2f * dpv, p);
                c.drawRoundRect(rf(r - 11f * dpv, t + 8f * dpv, r - 7f * dpv, b - 8f * dpv), 2f * dpv, 2f * dpv, p);
            } else {
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xFF6C63E6);
                c.drawRoundRect(rf(l, t, r, b), 10f * dpv, 10f * dpv, p);
                drawLabel(c, lb, l + 10f * dpv, t, l, r);
            }
        }
        // প্লেহেড
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.WHITE);
        c.drawRoundRect(rf(w / 2f - 1f * dpv, 0, w / 2f + 1f * dpv, hgt), dpv, dpv, p);
    }

    private void drawLabel(Canvas c, String s, float tx, float top, float clipL, float clipR) {
        c.save();
        c.clipRect(Math.max(clipL, 0), top, Math.min(clipR, getWidth()), top + 40f * dpv);
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.WHITE);
        p.setTextSize(15f * dpv);
        p.setTypeface(Typeface.DEFAULT);
        c.drawText(s, Math.max(tx, Math.max(clipL, 0) + 6f * dpv), top + 26f * dpv, p);
        c.restore();
    }

    private int hitItem(float xx, float yy) {
        for (int i = items.size() - 1; i >= 0; i--) {
            if (i >= lane.length || lane[i] > 3) continue;
            TextItem it = items.get(i);
            float t = rowTop(lane[i]);
            if (yy >= t && yy <= t + 40f * dpv && xx >= x(it.start) && xx <= x(it.end)) return i;
        }
        return -1;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        float ex = e.getX(), ey = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                getParent().requestDisallowInterceptTouchEvent(true);
                downX = ex; downY = ey; moved = false; pushed = false; mode = NONE; origHead = head;
                if (sel >= 0 && sel < items.size() && sel < lane.length) {
                    TextItem it = items.get(sel);
                    float t = rowTop(lane[sel]);
                    boolean row = ey >= t - 6f * dpv && ey <= t + 46f * dpv;
                    float lxp = x(it.start), rxp = x(it.end);
                    origS = it.start; origE = it.end;
                    if (row && Math.abs(ex - lxp) <= 22f * dpv) mode = TRIM_L;
                    else if (row && Math.abs(ex - rxp) <= 22f * dpv) mode = TRIM_R;
                    else if (row && ex > lxp && ex < rxp) mode = MOVE;
                }
                if (mode == NONE) { mode = SCRUB; }
                scrubbing = true;
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                float dx = ex - downX;
                if (!moved && Math.abs(dx) < 5f * dpv && Math.abs(ey - downY) < 5f * dpv) return true;
                moved = true;
                if (mode == SCRUB) {
                    long nh = origHead - Math.round(dx / ppms);
                    nh = Math.max(lo, Math.min(hi, nh));
                    head = nh;
                    if (listener != null) listener.scrub(nh);
                    invalidate();
                } else if (sel >= 0 && sel < items.size()) {
                    TextItem it = items.get(sel);
                    long dt = Math.round(dx / ppms);
                    if (!pushed) { pushed = true; if (listener != null) listener.before(); }
                    long minLen = 300;
                    if (mode == TRIM_L) it.start = Math.max(lo, Math.min(origE - minLen, origS + dt));
                    else if (mode == TRIM_R) it.end = Math.min(hi, Math.max(origS + minLen, origE + dt));
                    else if (mode == MOVE) {
                        long len = origE - origS;
                        long ns = Math.max(lo, Math.min(hi - len, origS + dt));
                        it.start = ns; it.end = ns + len;
                    }
                    refresh();
                    if (listener != null) listener.changed();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (!moved && e.getActionMasked() == MotionEvent.ACTION_UP) {
                    int i = hitItem(ex, ey);
                    if (i >= 0) { sel = i; if (listener != null) listener.select(i); invalidate(); }
                    else if (ey >= 22f * dpv && ey <= 62f * dpv && ex >= x(lo) && ex <= x(hi)) { if (listener != null) listener.audioTap(); }
                }
                scrubbing = false;
                mode = NONE;
                return true;
            }
            default: return true;
        }
    }
}

// ============================================================ সব জোড়া দেওয়া

@OptIn(markerClass = UnstableApi.class)
final class ShortTextTool {

    interface Host {
        long trimStart();
        long trimEnd();
        long pos();
        void seek(long ms);
        boolean playing();
        void setPlaying(boolean play);
        boolean hasAudio();
        String audioLabel();
        void openAudio();
        void panelVisible(boolean v);
    }

    private final Context ctx;
    private final Host host;
    private final List<TextItem> items = new ArrayList<>();
    private final TextOverlayView overlay;
    private final TextEditor editor;
    private final View bar;
    private final LinearLayout panel;
    private final FrameLayout topBar;
    private final TimelineView timeline;
    private final TextView tvTime;
    private final TIcon playBtn;
    private boolean panelOn = false;
    private int sel = -1;
    private List<TextItem> initial = new ArrayList<>();
    private final List<List<TextItem>> undo = new ArrayList<>(), redo = new ArrayList<>();

    private int dp(int v) { return Math.round(v * ctx.getResources().getDisplayMetrics().density); }

    ShortTextTool(Context c, Host h, FrameLayout root, FrameLayout videoBox, LinearLayout main, View bar) {
        ctx = c; host = h; this.bar = bar;

        overlay = new TextOverlayView(c, items);
        videoBox.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        overlay.listener = new TextOverlayView.L() {
            @Override public void select(int i) { syncSel(i); }
            @Override public void before() { pushUndo(); }
        };

        // ---- Cancel / Done (ভিডিওর ওপরে)
        topBar = new FrameLayout(c);
        topBar.setVisibility(View.GONE);
        TextView cancel = topBtn("Cancel"), done = topBtn("Done");
        cancel.setOnClickListener(v -> closePanel(false));
        done.setOnClickListener(v -> closePanel(true));
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(-2, -2, Gravity.START);
        FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(-2, -2, Gravity.END);
        topBar.addView(cancel, cl);
        topBar.addView(done, dl);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        tl.topMargin = dp(28); tl.leftMargin = dp(12); tl.rightMargin = dp(12);
        videoBox.addView(topBar, tl);

        // ---- নিচের টাইমলাইন প্যানেল
        panel = new LinearLayout(c);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xFF000000);
        panel.setPadding(0, dp(6), 0, dp(12));
        panel.setVisibility(View.GONE);

        FrameLayout ctl = new FrameLayout(c);
        playBtn = new TIcon(c, TIcon.PAUSE);
        playBtn.setOnClickListener(v -> host.setPlaying(!host.playing()));
        ctl.addView(playBtn, new FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER));
        LinearLayout ur = new LinearLayout(c);
        TIcon un = new TIcon(c, TIcon.UNDO), re = new TIcon(c, TIcon.REDO);
        un.setOnClickListener(v -> doUndo());
        re.setOnClickListener(v -> doRedo());
        ur.addView(un, new LinearLayout.LayoutParams(dp(36), dp(36)));
        ur.addView(re, new LinearLayout.LayoutParams(dp(36), dp(36)));
        FrameLayout.LayoutParams url = new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL);
        url.rightMargin = dp(16);
        ctl.addView(ur, url);
        panel.addView(ctl, new LinearLayout.LayoutParams(-1, dp(46)));

        tvTime = new TextView(c);
        tvTime.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvTime.setGravity(Gravity.CENTER);
        panel.addView(tvTime, new LinearLayout.LayoutParams(-1, -2));

        timeline = new TimelineView(c, items);
        timeline.listener = new TimelineView.L() {
            @Override public void scrub(long ms) {
                if (host.playing()) host.setPlaying(false);
                host.seek(ms);
                overlay.setTime(ms);
                updateTime(ms);
            }
            @Override public void select(int i) { sel = i; overlay.setSel(i); }
            @Override public void before() { pushUndo(); }
            @Override public void changed() { overlay.invalidate(); }
            @Override public void audioTap() { host.openAudio(); }
        };
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.topMargin = dp(6);
        panel.addView(timeline, tlp);

        LinearLayout acts = new LinearLayout(c);
        acts.setGravity(Gravity.CENTER);
        acts.addView(action(TIcon.AA, "Add text", v -> openEditor(-1)), new LinearLayout.LayoutParams(0, -2, 1f));
        acts.addView(action(TIcon.PENCIL, "Edit", v -> { if (sel >= 0) openEditor(sel); }), new LinearLayout.LayoutParams(0, -2, 1f));
        acts.addView(action(TIcon.TRASH, "Delete", v -> deleteSel()), new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-1, -2);
        alp.topMargin = dp(10);
        panel.addView(acts, alp);
        main.addView(panel, new LinearLayout.LayoutParams(-1, -2));

        editor = new TextEditor(c);
        root.addView(editor, new FrameLayout.LayoutParams(-1, -1));
    }

    private void syncSel(int i) { sel = i; timeline.sel = i; timeline.invalidate(); }

    private TextView topBtn(String s) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTypeface(null, Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(16), dp(7), dp(16), dp(7));
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFF262626);
        g.setCornerRadius(dp(12));
        t.setBackground(g);
        return t;
    }

    private View action(int icon, String label, View.OnClickListener l) {
        LinearLayout it = new LinearLayout(ctx);
        it.setOrientation(LinearLayout.VERTICAL);
        it.setGravity(Gravity.CENTER_HORIZONTAL);
        it.addView(new TIcon(ctx, icon), new LinearLayout.LayoutParams(dp(26), dp(26)));
        TextView t = new TextView(ctx);
        t.setText(label);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dp(3);
        it.addView(t, lp);
        it.setOnClickListener(l);
        return it;
    }

    // ---------------------------------------------------------------- সাধারণ

    void fit(int w, int h) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.gravity = Gravity.CENTER;
        overlay.setLayoutParams(lp);
    }

    boolean hasText() {
        for (TextItem t : items) if (!t.empty()) return true;
        return false;
    }

    List<TextItem> items() { return items; }

    /** স্টিকার / ইমোজি / Location-Temp-Time চিপ যোগ করে টেক্সট প্যানেল খোলে */
    void addItem(TextItem it) {
        if (editor.isOpen()) return;
        if (!panelOn) beginSession();
        long lo = host.trimStart(), hi = host.trimEnd();
        long s = host.pos();
        if (s < lo || s > hi - 500) s = Math.max(lo, hi - 3000);
        it.start = s;
        it.end = Math.min(hi, s + 3000);
        if (it.end - it.start < 500) it.end = Math.min(hi, it.start + 500);
        pushUndo();
        items.add(it);
        sel = items.size() - 1;
        showPanel();
    }

    boolean handleBack() {
        if (editor.isOpen()) { editor.finish(); return true; }
        if (panelOn) { closePanel(true); return true; }
        return false;
    }

    void tick(long pos) {
        overlay.setTime(pos);
        if (!panelOn) return;
        if (!timeline.isScrubbing()) timeline.setHead(pos);
        updateTime(pos);
        playBtn.type = host.playing() ? TIcon.PAUSE : TIcon.PLAY;
        playBtn.invalidate();
    }

    private void updateTime(long pos) {
        long lo = host.trimStart(), hi = host.trimEnd();
        String a = String.format(Locale.US, "%.1f", Math.max(0, pos - lo) / 1000f);
        String b = String.format(Locale.US, "%.1f", Math.max(0, hi - lo) / 1000f);
        SpannableString sp = new SpannableString(a + "   /   " + b);
        sp.setSpan(new ForegroundColorSpan(Color.WHITE), 0, a.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sp.setSpan(new ForegroundColorSpan(0xFF9A9A9A), a.length(), sp.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        tvTime.setText(sp);
    }

    // ---------------------------------------------------------------- প্রবেশ / প্যানেল

    /** নিচের বারের "Text" চাপলে */
    void open() {
        if (editor.isOpen()) return;
        if (!panelOn) beginSession();
        if (items.isEmpty()) openEditor(-1);
        else showPanel();
    }

    private void beginSession() {
        initial = TextItem.copyAll(items);
        undo.clear(); redo.clear();
    }

    private void showPanel() {
        panelOn = true;
        if (sel < 0 || sel >= items.size()) sel = items.isEmpty() ? -1 : items.size() - 1;
        bar.setVisibility(View.GONE);
        panel.setVisibility(View.VISIBLE);
        topBar.setVisibility(View.VISIBLE);
        overlay.interactive = true;
        overlay.setSel(sel);
        timeline.lo = host.trimStart(); timeline.hi = Math.max(host.trimEnd(), host.trimStart() + 1000);
        timeline.sel = sel;
        timeline.hasAudio = host.hasAudio();
        timeline.audioLabel = host.audioLabel();
        timeline.setHead(host.pos());
        timeline.refresh();
        host.panelVisible(true);
        tick(host.pos());
    }

    private void closePanel(boolean keep) {
        if (!keep) {
            items.clear();
            items.addAll(TextItem.copyAll(initial));
        }
        panelOn = false;
        panel.setVisibility(View.GONE);
        topBar.setVisibility(View.GONE);
        bar.setVisibility(View.VISIBLE);
        overlay.interactive = false;
        overlay.invalidate();
        host.panelVisible(false);
    }

    // ---------------------------------------------------------------- এডিটর

    private void openEditor(int idx) {
        if (idx >= 0 && idx < items.size() && items.get(idx).bmp != null) return;   // স্টিকারে লেখা এডিট নেই
        TextItem src;
        if (idx >= 0 && idx < items.size()) src = items.get(idx);
        else {
            idx = -1;
            src = new TextItem();
            long lo = host.trimStart(), hi = host.trimEnd();
            long s = host.pos();
            if (s < lo || s > hi - 500) s = Math.max(lo, hi - 3000);
            src.start = s;
            src.end = Math.min(hi, s + 3000);
            if (src.end - src.start < 500) src.end = Math.min(hi, src.start + 500);
        }
        final int at = idx;
        editor.show(src, res -> onEdited(at, res));
    }

    private void onEdited(int idx, TextItem res) {
        if (res.empty()) {
            if (idx >= 0) {                       // আগের টেক্সট মুছে ফেলা হলে ডিলিট
                pushUndo();
                items.remove(idx);
                sel = items.isEmpty() ? -1 : Math.min(idx, items.size() - 1);
            }
            if (!panelOn) return;                 // কিছুই যোগ হয়নি
        } else if (idx >= 0) {
            pushUndo();
            items.set(idx, res);
            sel = idx;
        } else {
            pushUndo();
            items.add(res);
            sel = items.size() - 1;
        }
        showPanel();
    }

    private void deleteSel() {
        if (sel < 0 || sel >= items.size()) return;
        pushUndo();
        items.remove(sel);
        sel = items.isEmpty() ? -1 : Math.min(sel, items.size() - 1);
        overlay.setSel(sel);
        timeline.sel = sel;
        timeline.refresh();
    }

    // ---------------------------------------------------------------- Undo / Redo

    private void pushUndo() {
        undo.add(TextItem.copyAll(items));
        if (undo.size() > 40) undo.remove(0);
        redo.clear();
    }

    private void restore(List<TextItem> s) {
        items.clear();
        items.addAll(s);
        if (sel >= items.size()) sel = items.size() - 1;
        overlay.setSel(sel);
        timeline.sel = sel;
        timeline.refresh();
        overlay.invalidate();
    }

    private void doUndo() {
        if (undo.isEmpty()) return;
        redo.add(TextItem.copyAll(items));
        restore(undo.remove(undo.size() - 1));
    }

    private void doRedo() {
        if (redo.isEmpty()) return;
        undo.add(TextItem.copyAll(items));
        restore(redo.remove(redo.size() - 1));
    }

    // ---------------------------------------------------------------- এক্সপোর্ট

    /**
     * ভিডিওতে টেক্সট বসানোর ইফেক্ট। fw/fh = চূড়ান্ত (ক্রপের পরের) ফ্রেমের মাপ;
     * win = {x0, y0, প্রস্থের ভগ্নাংশ, উচ্চতার ভগ্নাংশ} (ক্রপ না থাকলে {0,0,1,1});
     * trimStart = ক্লিপের শুরু (টেক্সটের সময় থেকে বাদ যাবে)।
     */
    Effect overlayEffect(int fw, int fh, float[] win, long trimStart) {
        if (fw <= 0 || fh <= 0) return null;
        ImmutableList.Builder<TextureOverlay> list = ImmutableList.builder();
        boolean any = false;
        try {
            for (TextItem it : items) {
                if (it.empty()) continue;
                Bitmap bmp = Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888);
                Canvas cv = new Canvas(bmp);
                float cx = (it.cx - win[0]) / win[2] * fw, cy = (it.cy - win[1]) / win[3] * fh;
                TextRender.draw(cv, ctx, it, cx, cy, fw / win[2]);
                final Bitmap fb = bmp;
                final long s = (it.start - trimStart) * 1000L, e = (it.end - trimStart) * 1000L;
                final OverlaySettings vis = new OverlaySettings.Builder().build();
                final OverlaySettings hid = new OverlaySettings.Builder().setAlphaScale(0f).build();
                list.add(new BitmapOverlay() {
                    @Override public Bitmap getBitmap(long presentationTimeUs) { return fb; }
                    @Override public OverlaySettings getOverlaySettings(long presentationTimeUs) {
                        return presentationTimeUs >= s && presentationTimeUs < e ? vis : hid;
                    }
                });
                any = true;
            }
        } catch (Throwable t) {
            return null;
        }
        return any ? new OverlayEffect(list.build()) : null;
    }
}
