package com.familybook.app;

import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.drawable.GradientDrawable;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.InputStream;

/**
 * NID scan page with TWO ways in: "Scan with camera" (full-resolution photo from the camera app)
 * and "Upload photo" (gallery). Both go through the same OCR.
 *
 * Needs in app/build.gradle:  implementation 'com.google.mlkit:text-recognition:16.0.1'
 *
 * Start with extras: "profileName" (String), "profileDob" (dd/MM/yyyy).
 * Result (RESULT_OK): "dob", "nid", "nameOk" (boolean), "dobOk" (boolean), "ocrText".
 *
 * Why this reads blurry cards better: the bitmap is NOT downscaled below ~3000px, EXIF rotation is fixed,
 * OCR runs on the original AND a grayscale/high-contrast copy, only the English lines are used
 * (ML Kit has no Bengali model), and matching is fuzzy (NidTextParser).
 */
public class NidScanActivity extends BaseActivity {

    private String profileName = "", profileDob = "";
    private Uri cameraUri;
    private ImageView preview;
    private TextView tvResult;
    private Button btnDone;
    private final TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    private Intent resultData;

    private ActivityResultLauncher<Uri> takePicture;
    private ActivityResultLauncher<String> pickImage;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        profileName = nz(getIntent().getStringExtra("profileName"));
        profileDob = nz(getIntent().getStringExtra("profileDob"));

        takePicture = registerForActivityResult(new ActivityResultContracts.TakePicture(), ok -> {
            if (ok && cameraUri != null) scan(cameraUri);
        });
        pickImage = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) scan(uri);
        });

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(20));
        root.setBackgroundColor(Color.WHITE);

        TextView title = new TextView(this);
        title.setText("Scan your NID");
        title.setTextSize(22);
        title.setTextColor(Color.parseColor("#050505"));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView tip = new TextView(this);
        tip.setText("Hold the card flat in good light. The English name and date of birth must be sharp.");
        tip.setTextColor(Color.parseColor("#65676B"));
        tip.setTextSize(14);
        tip.setPadding(0, dp(6), 0, dp(14));
        root.addView(tip);

        // "+ Front side" box: tap -> choose Camera or Gallery (both stay available)
        TextView front = new TextView(this);
        front.setText("+ Front side");
        front.setTextSize(18);
        front.setTypeface(null, android.graphics.Typeface.BOLD);
        front.setTextColor(Color.parseColor("#0866FF"));
        front.setGravity(Gravity.CENTER);
        GradientDrawable fb = new GradientDrawable();
        fb.setColor(Color.parseColor("#F0F2F5"));
        fb.setCornerRadius(dp(14));
        fb.setStroke(dp(2), Color.parseColor("#CED0D4"), dp(6), dp(4));
        front.setBackground(fb);
        front.setOnClickListener(v -> showSourceChooser());
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(110));
        fp.topMargin = dp(10);
        root.addView(front, fp);

        preview = new ImageView(this);
        preview.setAdjustViewBounds(true);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setVisibility(View.GONE);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(200));
        pp.topMargin = dp(14);
        root.addView(preview, pp);

        tvResult = new TextView(this);
        tvResult.setTextSize(16);
        tvResult.setTextColor(Color.parseColor("#050505"));
        tvResult.setPadding(0, dp(12), 0, dp(12));
        root.addView(tvResult);

        btnDone = button("Use this result", v -> {
            if (resultData != null) setResult(RESULT_OK, resultData);
            finish();
        });
        btnDone.setVisibility(View.GONE);
        root.addView(btnDone);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
    }

    /** Two options: Open camera / Gallery */
    private void showSourceChooser() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setItems(new String[]{"Open camera", "Gallery"}, (d, which) -> {
                    if (which == 0) openCamera();
                    else pickImage.launch("image/*");
                }).show();
    }

    private void openCamera() {
        try {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Images.Media.DISPLAY_NAME, "nid_" + System.currentTimeMillis() + ".jpg");
            cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            cameraUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (cameraUri == null) { TopToast.show(this, "Could not open camera"); return; }
            takePicture.launch(cameraUri);
        } catch (Exception e) {
            TopToast.show(this, "Could not open camera");
        }
    }

    // ------------------------------------------------------------------ scan
    private void scan(Uri uri) {
        tvResult.setText("Reading card...");
        btnDone.setVisibility(View.GONE);
        new Thread(() -> {
            final Bitmap bmp = load(uri);
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (bmp == null) { tvResult.setText(""); TopToast.show(this, "Could not read this image"); return; }
                preview.setImageBitmap(bmp);
                preview.setVisibility(View.VISIBLE);
                // pass 1: original, pass 2: enhanced copy; use both texts together
                recognize(bmp, t1 -> recognize(NidImagePrep.forOcr(bmp), t2 -> finish(t1 + "\n" + t2)));
            });
        }).start();
    }

    private interface Done { void on(String text); }

    private void recognize(Bitmap bmp, Done d) {
        recognizer.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener(r -> d.on(r.getText()))
                .addOnFailureListener(e -> d.on(""));
    }

    private void finish(String text) {
        if (isFinishing()) return;
        String dob = NidTextParser.extractDob(text);
        String nid = NidTextParser.extractNidNumber(text);
        boolean nameOk = NidTextParser.nameMatches(text, profileName);
        boolean dobOk = NidTextParser.dobMatches(dob, profileDob);

        tvResult.setText("Name: " + (nameOk ? "matched" : "not matched")
                + "\nDate of birth: " + (dob == null ? "not found" : dob + (dobOk ? " (matched)" : " (different)"))
                + "\nNID number: " + (nid == null ? "not found" : "found"));
        resultData = new Intent().putExtra("dob", dob).putExtra("nid", nid)
                .putExtra("nameOk", nameOk).putExtra("dobOk", dobOk).putExtra("ocrText", text);
        btnDone.setVisibility(View.VISIBLE);
        if (!nameOk || !dobOk) TopToast.show(this, "Some fields did not match. Try a sharper photo.");
    }

    /** decode (long side kept <= 3000px) and fix EXIF rotation */
    private Bitmap load(Uri uri) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(in, null, o); }
            int s = 1;
            while (Math.max(o.outWidth, o.outHeight) / s > 3000) s *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = s;
            Bitmap bm;
            try (InputStream in = getContentResolver().openInputStream(uri)) { bm = BitmapFactory.decodeStream(in, null, o); }
            if (bm == null) return null;
            int rot = 0;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                int ori = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
                rot = ori == 6 ? 90 : ori == 3 ? 180 : ori == 8 ? 270 : 0;
            } catch (Exception ignored) { }
            if (rot != 0) {
                Matrix m = new Matrix();
                m.postRotate(rot);
                bm = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);
            }
            return bm;
        } catch (Exception e) {
            return null;
        }
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(16);
        b.setTypeface(null, android.graphics.Typeface.BOLD);
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.parseColor("#0866FF"));
        g.setCornerRadius(dp(10));
        b.setBackground(g);
        b.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        lp.topMargin = dp(10);
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
