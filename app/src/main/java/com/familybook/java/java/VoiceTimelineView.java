package com.familybook.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** ভয়েসওভার টাইমলাইন: ধূসর ট্র্যাক + লাল (রেকর্ড হওয়া অংশ) + সাদা প্লে-হেড। */
public class VoiceTimelineView extends View {

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint seg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint head = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final float d;

    private List<float[]> segments = new ArrayList<>();   // [a, b] 0..1
    private float playhead = 0f;
    private float openStart = -1f;

    public VoiceTimelineView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        track.setColor(0xFF4A4B4D);
        seg.setColor(0xFFC4706E);
        grid.setColor(0x33000000);
        grid.setStrokeWidth(1f * d);
        head.setColor(0xFFFFFFFF);
    }

    public void setSegments(List<float[]> s) { segments = s == null ? new ArrayList<float[]>() : s; invalidate(); }
    public void setOpenStart(float s) { openStart = s; invalidate(); }
    public void setPlayhead(float p) { playhead = Math.max(0f, Math.min(1f, p)); invalidate(); }

    @Override protected void onDraw(Canvas c) {
        float left = 10 * d, right = getWidth() - 10 * d;
        float top = getHeight() * 0.12f, bottom = getHeight() * 0.88f;
        float w = right - left;

        r.set(left, top, right, bottom);
        c.drawRoundRect(r, 4 * d, 4 * d, track);
        for (float[] s : segments) {
            r.set(left + w * s[0], top, left + w * Math.max(s[0], s[1]), bottom);
            c.drawRect(r, seg);
        }
        if (openStart >= 0f) {
            r.set(left + w * openStart, top, left + w * Math.max(openStart, playhead), bottom);
            c.drawRect(r, seg);
        }
        for (int i = 1; i < 15; i++) {
            float x = left + w * i / 15f;
            c.drawLine(x, top, x, bottom, grid);
        }
        float hx = left + w * playhead;
        r.set(hx - 4 * d, 0, hx + 4 * d, getHeight());
        c.drawRoundRect(r, 4 * d, 4 * d, head);
    }
}
