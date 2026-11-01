package com.familybook.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** ছবির উপর ক্রপ-বক্স: কোণ টেনে ছোট-বড়, ভেতরে টেনে সরানো; aspect ঠিক করা যায় */
public class CropView extends View {
    private static final int NONE = 0, MOVE = 1, TL = 2, TR = 3, BL = 4, BR = 5;

    private Bitmap bmp;
    private final RectF img = new RectF();
    private final RectF crop = new RectF();
    private final Paint pBmp = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint pDim = new Paint();
    private final Paint pLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pHandle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float d, touch, minSize;
    private boolean cropEnabled = false;
    private float aspect = 0f;
    private int mode = NONE;
    private float lx, ly;

    public CropView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        touch = 40 * d;
        minSize = 64 * d;
        pDim.setColor(0xAA000000);
        pLine.setColor(0xFFFFFFFF);
        pLine.setStyle(Paint.Style.STROKE);
        pLine.setStrokeWidth(1.5f * d);
        pHandle.setColor(0xFFFFFFFF);
        pHandle.setStyle(Paint.Style.STROKE);
        pHandle.setStrokeWidth(4 * d);
        pHandle.setStrokeCap(Paint.Cap.ROUND);
    }

    public void setBitmap(Bitmap b) {
        bmp = b;
        layoutImage();
        invalidate();
    }

    public void setFilter(ColorFilter f) {
        pBmp.setColorFilter(f);
        invalidate();
    }

    public void setCropEnabled(boolean on) {
        cropEnabled = on;
        invalidate();
    }

    public void setAspect(float a) {
        aspect = a;
        resetCrop();
        invalidate();
    }

    /** ক্রপ-বক্স আসল ছবির কোন অংশ (pixel) */
    public RectF getCropInBitmap() {
        if (bmp == null || img.width() <= 0) return new RectF(0, 0, 1, 1);
        float s = bmp.getWidth() / img.width();
        return new RectF((crop.left - img.left) * s, (crop.top - img.top) * s,
                (crop.right - img.left) * s, (crop.bottom - img.top) * s);
    }

    /** ক্রপ করা হয়েছে কি (পুরো ছবির চেয়ে ছোট) */
    public boolean isCropped() {
        return crop.width() < img.width() - 2 || crop.height() < img.height() - 2;
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        layoutImage();
    }

    private void layoutImage() {
        if (bmp == null || getWidth() == 0 || getHeight() == 0) return;
        float s = Math.min((float) getWidth() / bmp.getWidth(), (float) getHeight() / bmp.getHeight());
        float w = bmp.getWidth() * s, h = bmp.getHeight() * s;
        img.set((getWidth() - w) / 2f, (getHeight() - h) / 2f, (getWidth() + w) / 2f, (getHeight() + h) / 2f);
        resetCrop();
    }

    private void resetCrop() {
        if (img.width() <= 0) return;
        if (aspect <= 0f) {
            crop.set(img);
            return;
        }
        float w = img.width(), h = w / aspect;
        if (h > img.height()) {
            h = img.height();
            w = h * aspect;
        }
        float cx = img.centerX(), cy = img.centerY();
        crop.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
    }

    @Override protected void onDraw(Canvas c) {
        if (bmp == null) return;
        c.drawBitmap(bmp, null, img, pBmp);
        if (!cropEnabled && !isCropped()) return;
        // বাইরের অংশ অন্ধকার
        c.drawRect(img.left, img.top, img.right, crop.top, pDim);
        c.drawRect(img.left, crop.bottom, img.right, img.bottom, pDim);
        c.drawRect(img.left, crop.top, crop.left, crop.bottom, pDim);
        c.drawRect(crop.right, crop.top, img.right, crop.bottom, pDim);
        if (!cropEnabled) return;
        c.drawRect(crop, pLine);
        float w3 = crop.width() / 3f, h3 = crop.height() / 3f;
        c.drawLine(crop.left + w3, crop.top, crop.left + w3, crop.bottom, pLine);
        c.drawLine(crop.left + 2 * w3, crop.top, crop.left + 2 * w3, crop.bottom, pLine);
        c.drawLine(crop.left, crop.top + h3, crop.right, crop.top + h3, pLine);
        c.drawLine(crop.left, crop.top + 2 * h3, crop.right, crop.top + 2 * h3, pLine);
        float L = 22 * d;
        // চার কোণের মোটা দাগ
        c.drawLine(crop.left, crop.top, crop.left + L, crop.top, pHandle);
        c.drawLine(crop.left, crop.top, crop.left, crop.top + L, pHandle);
        c.drawLine(crop.right, crop.top, crop.right - L, crop.top, pHandle);
        c.drawLine(crop.right, crop.top, crop.right, crop.top + L, pHandle);
        c.drawLine(crop.left, crop.bottom, crop.left + L, crop.bottom, pHandle);
        c.drawLine(crop.left, crop.bottom, crop.left, crop.bottom - L, pHandle);
        c.drawLine(crop.right, crop.bottom, crop.right - L, crop.bottom, pHandle);
        c.drawLine(crop.right, crop.bottom, crop.right, crop.bottom - L, pHandle);
    }

    private int hitTest(float x, float y) {
        if (near(x, y, crop.left, crop.top)) return TL;
        if (near(x, y, crop.right, crop.top)) return TR;
        if (near(x, y, crop.left, crop.bottom)) return BL;
        if (near(x, y, crop.right, crop.bottom)) return BR;
        if (crop.contains(x, y)) return MOVE;
        return NONE;
    }

    private boolean near(float x, float y, float px, float py) {
        return Math.abs(x - px) <= touch && Math.abs(y - py) <= touch;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (!cropEnabled || bmp == null) return false;
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                mode = hitTest(e.getX(), e.getY());
                lx = e.getX();
                ly = e.getY();
                return mode != NONE;
            case MotionEvent.ACTION_MOVE:
                if (mode == NONE) return false;
                float dx = e.getX() - lx, dy = e.getY() - ly;
                lx = e.getX();
                ly = e.getY();
                if (mode == MOVE) move(dx, dy);
                else resize(mode, dx, dy);
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mode = NONE;
                return true;
            default:
                return true;
        }
    }

    private void move(float dx, float dy) {
        float nl = Math.max(img.left, Math.min(crop.left + dx, img.right - crop.width()));
        float nt = Math.max(img.top, Math.min(crop.top + dy, img.bottom - crop.height()));
        crop.offsetTo(nl, nt);
    }

    private void resize(int corner, float dx, float dy) {
        boolean left = corner == TL || corner == BL;
        boolean top = corner == TL || corner == TR;
        float l = crop.left, t = crop.top, r = crop.right, b = crop.bottom;
        if (left) l += dx; else r += dx;
        if (top) t += dy; else b += dy;
        l = Math.max(l, img.left);
        r = Math.min(r, img.right);
        t = Math.max(t, img.top);
        b = Math.min(b, img.bottom);
        float ax = left ? crop.right : crop.left;      // যে কোণ স্থির থাকে
        float ay = top ? crop.bottom : crop.top;
        float w = Math.abs((left ? l : r) - ax);
        float h = Math.abs((top ? t : b) - ay);
        float maxW = left ? ax - img.left : img.right - ax;
        float maxH = top ? ay - img.top : img.bottom - ay;
        if (aspect > 0f) {
            w = Math.max(w, minSize);
            h = w / aspect;
            if (h > maxH) { h = maxH; w = h * aspect; }
            if (w > maxW) { w = maxW; h = w / aspect; }
        } else {
            w = Math.min(Math.max(w, minSize), maxW);
            h = Math.min(Math.max(h, minSize), maxH);
        }
        if (left) { crop.left = ax - w; crop.right = ax; } else { crop.left = ax; crop.right = ax + w; }
        if (top) { crop.top = ay - h; crop.bottom = ay; } else { crop.top = ay; crop.bottom = ay + h; }
    }
}
