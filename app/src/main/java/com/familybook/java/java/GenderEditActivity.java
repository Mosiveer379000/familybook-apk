package com.familybook.app;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * Gender পেজ। রেজিস্ট্রেশনের মতোই Male / Female / Other।
 * Intent extra: "gender", "pub" (Public হলে true)
 * Save → info.gender ও info.priv.gender
 */
public class GenderEditActivity extends BaseActivity {

    private static final String[] GENDERS = {"Male", "Female", "Other"};

    private String selected = "";
    private final View[] dots = new View[GENDERS.length];
    private PersonalInfoStore.PrivToggle priv;
    private boolean saving = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Me.isLoggedIn() || Me.uid() == null) {
            finish();
            return;
        }
        setContentView(R.layout.activity_gender_edit);

        String old = getIntent().getStringExtra("gender");
        selected = old == null ? "" : old.replace(".", "").trim();

        LinearLayout box = findViewById(R.id.llGenderOptions);
        for (int i = 0; i < GENDERS.length; i++) {
            final String g = GENDERS[i];
            final int idx = i;

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(14), dp(14), dp(14));
            row.setBackground(ring(14, 2, "#CED0D4", Color.WHITE));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(10);
            row.setLayoutParams(lp);

            TextView tv = new TextView(this);
            tv.setText(g);
            tv.setTextSize(18);
            tv.setTypeface(Typeface.DEFAULT_BOLD);
            tv.setTextColor(Color.parseColor("#050505"));
            tv.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            View dot = new View(this);
            dot.setLayoutParams(new LinearLayout.LayoutParams(dp(22), dp(22)));
            dots[idx] = dot;

            row.addView(tv);
            row.addView(dot);
            row.setOnClickListener(v -> { selected = g; refreshDots(); });
            box.addView(row);
        }
        refreshDots();

        priv = new PersonalInfoStore.PrivToggle((ImageView) findViewById(R.id.ivPrivGender),
                getIntent().getBooleanExtra("pub", true));

        findViewById(R.id.btnBackGender).setOnClickListener(v -> finish());
        findViewById(R.id.btnSaveGender).setOnClickListener(v -> save());
    }

    private void refreshDots() {
        for (int i = 0; i < GENDERS.length; i++) {
            boolean on = GENDERS[i].equalsIgnoreCase(selected);
            dots[i].setBackground(on
                    ? ring(30, 0, "#00000000", Color.parseColor("#1877F2"))
                    : ring(30, 2, "#BCC0C4", Color.WHITE));
        }
    }

    private void save() {
        if (saving) return;
        if (selected.isEmpty()) {
            TopToast.show(this, "Please select a gender");
            return;
        }
        saving = true;
        try {
            JSONObject info = new JSONObject().put("gender", selected);
            JSONObject pr = new JSONObject().put("gender", priv.pub);
            PersonalInfoStore.save(info, pr, err -> {
                saving = false;
                if (isFinishing()) return;
                if (err != null) {
                    TopToast.show(this, err);
                    return;
                }
                TopToast.show(this, "Gender saved!");
                finish();
            });
        } catch (Exception e) {
            saving = false;
            TopToast.show(this, "Could not save");
        }
    }

    private GradientDrawable ring(int radiusDp, int strokeDp, String strokeColor, int fill) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) g.setStroke(dp(strokeDp), Color.parseColor(strokeColor));
        return g;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
