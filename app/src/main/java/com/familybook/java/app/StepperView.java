package com.familybook.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

/**
 * ৫ ধাপের স্টেপার: Name - Contacts - Verify - City - Review
 * setStep(0..4): আগের ধাপগুলোতে ✓, বর্তমান ধাপ গাঢ় নীল (হালকা হ্যালোসহ), পরের ধাপগুলো সাদা বৃত্ত।
 */
public class StepperView extends View {

    private static final String[] LABELS = {"Name", "Contacts", "Verify", "City", "Review"};
    private static final int BLUE = Color.parseColor("#0A66FF");
    private static final int LINE_LIGHT = Color.parseColor("#9DBBFF");
    private static final int HALO = Color.parseColor("#2E0A66FF");

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint num = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path tickPath = new Path();
    private final float dp;
    private int step = 0;

    public StepperView(Context c) { this(c, null); }

    public StepperView(Context c, AttributeSet a) {
        super(c, a);
        dp = c.getResources().getDisplayMetrics().density;
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(2 * dp);
        stroke.setColor(BLUE);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2 * dp);
        num.setTextAlign(Paint.Align.CENTER);
        num.setTypeface(Typeface.DEFAULT_BOLD);
        num.setTextSize(14 * dp);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setColor(BLUE);
        tick.setStyle(Paint.Style.STROKE);
        tick.setStrokeWidth(2.4f * dp);
        tick.setStrokeCap(Paint.Cap.ROUND);
        tick.setStrokeJoin(Paint.Join.ROUND);
        tick.setColor(Color.WHITE);
    }

    /** 0 = Name ... 4 = Review */
    public void setStep(int s) {
        step = Math.max(0, Math.min(4, s));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas cv) {
        float w = getWidth();
        float spacing = w * 0.15f;
        float startX = (w - 4 * spacing) / 2f;
        float r = 13 * dp;
        float cy = 8 * dp + r + 4 * dp;

        // লেবেল ছোট করে বসানো, যাতে পাশের লেবেলের সাথে না লাগে
        float ts = 13 * dp;
        label.setTextSize(ts);
        float widest = 0;
        for (String l : LABELS) widest = Math.max(widest, label.measureText(l));
        float maxW = spacing - 3 * dp;
        if (widest > maxW) label.setTextSize(ts * maxW / widest);

        for (int i = 0; i < 4; i++) {
            line.setColor(i < step ? BLUE : LINE_LIGHT);
            float x1 = startX + i * spacing + r;
            float x2 = startX + (i + 1) * spacing - r;
            cv.drawLine(x1, cy, x2, cy, line);
        }

        Paint.FontMetrics fm = num.getFontMetrics();
        float numY = cy - (fm.ascent + fm.descent) / 2f;
        Paint.FontMetrics lf = label.getFontMetrics();
        float labelY = cy + r + 8 * dp - lf.ascent;

        for (int i = 0; i < 5; i++) {
            float cx = startX + i * spacing;
            if (i < step) {
                fill.setColor(BLUE);
                cv.drawCircle(cx, cy, r, fill);
                tickPath.reset();
                tickPath.moveTo(cx - r * 0.42f, cy + r * 0.02f);
                tickPath.lineTo(cx - r * 0.10f, cy + r * 0.34f);
                tickPath.lineTo(cx + r * 0.46f, cy - r * 0.32f);
                cv.drawPath(tickPath, tick);
            } else if (i == step) {
                fill.setColor(HALO);
                cv.drawCircle(cx, cy, r + 5 * dp, fill);
                fill.setColor(BLUE);
                cv.drawCircle(cx, cy, r, fill);
                num.setColor(Color.WHITE);
                cv.drawText(String.valueOf(i + 1), cx, numY, num);
            } else {
                fill.setColor(Color.WHITE);
                cv.drawCircle(cx, cy, r, fill);
                cv.drawCircle(cx, cy, r, stroke);
                num.setColor(BLUE);
                cv.drawText(String.valueOf(i + 1), cx, numY, num);
            }
            cv.drawText(LABELS[i], cx, labelY, label);
        }
    }
}
