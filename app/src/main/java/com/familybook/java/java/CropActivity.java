package com.familybook.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * প্রোফাইল / কভার ছবি ক্রপ স্ক্রিন।
 * - আঙুল দিয়ে টেনে সরানো (drag) ও দুই আঙুলে জুম (pinch)
 * - Rotate বাটন (৯০° ঘোরানো)
 * - EXIF অনুযায়ী ছবি আপনা-আপনি সোজা হয়
 * - Save বাটনে ক্রপ করা ছবি cache ফাইলে সেভ হয়ে ProfileActivity-তে ফেরত যায়
 *
 * Intent extras: "target" = "avatar" | "cover"   (data = ছবির Uri)
 * Result extra : "path"   = ক্রপ করা JPEG ফাইলের পথ
 */
public class CropActivity extends AppCompatActivity {

    private CropView cropView;
    private Bitmap source;
    private boolean isAvatar;

    public static Intent intent(Context c, Uri uri, String target) {
        Intent i = new Intent(c, CropActivity.class);
        i.setData(uri);
        i.putExtra("target", target);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return i;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        isAvatar = !"cover".equals(getIntent().getStringExtra("target"));

        Uri uri = getIntent().getData();
        source = loadBitmap(uri);
        if (source == null) {
            Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show();
            setResult(Activity.RESULT_CANCELED);
            finish();
            return;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        // উপরের বার
        TextView title = new TextView(this);
        title.setText(isAvatar ? "Adjust profile photo" : "Adjust cover photo");
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(dp(16), dp(14), dp(16), dp(14));
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ক্রপ এরিয়া
        cropView = new CropView(this, isAvatar ? 1f : 840f / 319f, isAvatar);
        cropView.setBitmap(source);
        root.addView(cropView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView hint = new TextView(this);
        hint.setText("Drag to move, pinch to zoom");
        hint.setTextColor(0xFFB0BEC5);
        hint.setTextSize(13);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(8), 0, dp(4));
        root.addView(hint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // নিচের বাটন: Cancel | Rotate | Save
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        bar.setPadding(dp(12), dp(8), dp(12), dp(16));

        Button cancel = makeBtn("Cancel", 0xFF5F7D8C);
        Button rotate = makeBtn("Rotate", 0xFF5F7D8C);
        Button save = makeBtn("Save", 0xFF0D66FF);
        bar.addView(cancel, btnLp());
        bar.addView(rotate, btnLp());
        bar.addView(save, btnLp());
        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setResult(Activity.RESULT_CANCELED);
                finish();
            }
        });
        rotate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cropView.rotate90();
            }
        });
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveAndFinish();
            }
        });

        setContentView(root);
    }

    private Button makeBtn(String text, int color) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(14);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(18));
        b.setBackground(g);
        return b;
    }

    private LinearLayout.LayoutParams btnLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        lp.setMargins(dp(5), 0, dp(5), 0);
        return lp;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void saveAndFinish() {
        try {
            int outW = isAvatar ? 600 : 1000;
            Bitmap out = cropView.renderCrop(outW);
            File f = new File(getCacheDir(), "crop_" + System.currentTimeMillis() + ".jpg");
            FileOutputStream fos = new FileOutputStream(f);
            out.compress(Bitmap.CompressFormat.JPEG, 85, fos);
            fos.close();
            Intent data = new Intent();
            data.putExtra("path", f.getAbsolutePath());
            setResult(Activity.RESULT_OK, data);
            finish();
        } catch (Throwable e) {
            Toast.makeText(this, "Save failed", Toast.LENGTH_SHORT).show();
        }
    }

    /** ছবি লোড + সাইজ ছোট করা + EXIF অনুযায়ী সোজা করা */
    private Bitmap loadBitmap(Uri uri) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            InputStream in = getContentResolver().openInputStream(uri);
            BitmapFactory.decodeStream(in, null, o);
            if (in != null) in.close();

            int max = 2048;
            int sample = 1;
            while ((o.outWidth / sample) > max || (o.outHeight / sample) > max) {
                sample *= 2;
            }
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            in = getContentResolver().openInputStream(uri);
            Bitmap bm = BitmapFactory.decodeStream(in, null, o2);
            if (in != null) in.close();
            if (bm == null) return null;

            int orientation = ExifInterface.ORIENTATION_NORMAL;
            try {
                in = getContentResolver().openInputStream(uri);
                ExifInterface exif = new ExifInterface(in);
                orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL);
                if (in != null) in.close();
            } catch (Throwable ignore) {
            }

            Matrix m = new Matrix();
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                    m.postRotate(90);
                    break;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    m.postRotate(180);
                    break;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    m.postRotate(270);
                    break;
                case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                    m.postScale(-1, 1);
                    break;
                case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                    m.postScale(1, -1);
                    break;
                case ExifInterface.ORIENTATION_TRANSPOSE:
                    m.postRotate(90);
                    m.postScale(-1, 1);
                    break;
                case ExifInterface.ORIENTATION_TRANSVERSE:
                    m.postRotate(270);
                    m.postScale(-1, 1);
                    break;
                default:
                    return bm;
            }
            Bitmap r = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);
            if (r != bm) bm.recycle();
            return r;
        } catch (Throwable e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // ক্রপ ভিউ
    // ------------------------------------------------------------------
    static class CropView extends View {
        private Bitmap bmp;
        private final Matrix matrix = new Matrix();
        private final RectF crop = new RectF();
        private final float aspect;       // width / height
        private final boolean circle;
        private final Paint shade = new Paint();
        private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private final Path hole = new Path();
        private final ScaleGestureDetector scaleDetector;
        private float lastX, lastY;
        private int activePointer = -1;
        private boolean needsInit = true;

        CropView(Context c, float aspect, boolean circle) {
            super(c);
            this.aspect = aspect;
            this.circle = circle;
            shade.setColor(0xAA000000);
            border.setColor(Color.WHITE);
            border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(3f);
            scaleDetector = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector d) {
                    matrix.postScale(d.getScaleFactor(), d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                    fixBounds();
                    invalidate();
                    return true;
                }
            });
        }

        void setBitmap(Bitmap b) {
            bmp = b;
            needsInit = true;
            requestLayout();
            invalidate();
        }

        void rotate90() {
            if (bmp == null) return;
            Matrix m = new Matrix();
            m.postRotate(90);
            Bitmap r = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            bmp = r;
            needsInit = true;
            initMatrix();
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            float margin = 16f * getResources().getDisplayMetrics().density;
            float cw = w - 2 * margin;
            float ch = cw / aspect;
            if (ch > h - 2 * margin) {
                ch = h - 2 * margin;
                cw = ch * aspect;
            }
            float l = (w - cw) / 2f;
            float t = (h - ch) / 2f;
            crop.set(l, t, l + cw, t + ch);
            hole.reset();
            if (circle) {
                hole.addCircle(crop.centerX(), crop.centerY(), cw / 2f, Path.Direction.CW);
            } else {
                hole.addRect(crop, Path.Direction.CW);
            }
            needsInit = true;
            initMatrix();
        }

        private void initMatrix() {
            if (bmp == null || crop.isEmpty()) return;
            matrix.reset();
            float s = Math.max(crop.width() / bmp.getWidth(), crop.height() / bmp.getHeight());
            matrix.postScale(s, s);
            float dx = crop.centerX() - (bmp.getWidth() * s) / 2f;
            float dy = crop.centerY() - (bmp.getHeight() * s) / 2f;
            matrix.postTranslate(dx, dy);
            needsInit = false;
        }

        private float currentScale() {
            float[] v = new float[9];
            matrix.getValues(v);
            return v[Matrix.MSCALE_X];
        }

        /** ছবি যেন ক্রপ বক্সের ভেতরে সবসময় পুরো ভরে থাকে */
        private void fixBounds() {
            if (bmp == null) return;
            float minScale = Math.max(crop.width() / bmp.getWidth(), crop.height() / bmp.getHeight());
            float cur = currentScale();
            if (cur < minScale) {
                float f = minScale / cur;
                matrix.postScale(f, f, crop.centerX(), crop.centerY());
            } else if (cur > minScale * 8f) {
                float f = (minScale * 8f) / cur;
                matrix.postScale(f, f, crop.centerX(), crop.centerY());
            }
            RectF r = new RectF(0, 0, bmp.getWidth(), bmp.getHeight());
            matrix.mapRect(r);
            float dx = 0, dy = 0;
            if (r.left > crop.left) dx = crop.left - r.left;
            if (r.right < crop.right) dx = crop.right - r.right;
            if (r.top > crop.top) dy = crop.top - r.top;
            if (r.bottom < crop.bottom) dy = crop.bottom - r.bottom;
            matrix.postTranslate(dx, dy);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            scaleDetector.onTouchEvent(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    activePointer = e.getPointerId(0);
                    lastX = e.getX();
                    lastY = e.getY();
                    break;
                case MotionEvent.ACTION_POINTER_DOWN: {
                    int idx = e.getActionIndex();
                    activePointer = e.getPointerId(idx);
                    lastX = e.getX(idx);
                    lastY = e.getY(idx);
                    break;
                }
                case MotionEvent.ACTION_MOVE:
                    if (!scaleDetector.isInProgress()) {
                        int idx = e.findPointerIndex(activePointer);
                        if (idx >= 0) {
                            float x = e.getX(idx), y = e.getY(idx);
                            matrix.postTranslate(x - lastX, y - lastY);
                            lastX = x;
                            lastY = y;
                            fixBounds();
                            invalidate();
                        }
                    } else {
                        int idx = e.findPointerIndex(activePointer);
                        if (idx >= 0) {
                            lastX = e.getX(idx);
                            lastY = e.getY(idx);
                        }
                    }
                    break;
                case MotionEvent.ACTION_POINTER_UP: {
                    int upIdx = e.getActionIndex();
                    if (e.getPointerId(upIdx) == activePointer) {
                        int newIdx = upIdx == 0 ? 1 : 0;
                        activePointer = e.getPointerId(newIdx);
                        lastX = e.getX(newIdx);
                        lastY = e.getY(newIdx);
                    }
                    break;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    activePointer = -1;
                    break;
            }
            return true;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (bmp == null) return;
            if (needsInit) initMatrix();
            canvas.drawBitmap(bmp, matrix, bmpPaint);

            // ক্রপ বক্সের বাইরে অন্ধকার
            canvas.save();
            canvas.clipPath(hole, android.graphics.Region.Op.DIFFERENCE);
            canvas.drawRect(0, 0, getWidth(), getHeight(), shade);
            canvas.restore();

            if (circle) {
                canvas.drawCircle(crop.centerX(), crop.centerY(), crop.width() / 2f, border);
            } else {
                canvas.drawRect(crop, border);
            }
        }

        /** ক্রপ বক্সের ভেতরের অংশ outW চওড়া করে বের করে */
        Bitmap renderCrop(int outW) {
            int outH = Math.round(outW / aspect);
            Bitmap out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            c.drawColor(Color.WHITE);
            float k = outW / crop.width();
            c.scale(k, k);
            c.translate(-crop.left, -crop.top);
            c.drawBitmap(bmp, matrix, bmpPaint);
            return out;
        }
    }
}
