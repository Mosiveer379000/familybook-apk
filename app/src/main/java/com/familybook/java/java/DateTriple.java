package com.familybook.app;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Calendar;

/**
 * Year ▾ | Month ▾ | Day ▾ — তিনটি ড্রপডাউন বাটন।
 * Month চালু হয় Year বাছলে, Day চালু হয় Month বাছলে (না বাছা পর্যন্ত ধূসর)।
 * বাটনে চাপলে তালিকা খোলে; "Clear" দিয়ে মান মুছে ফেলা যায়।
 */
final class DateTriple {

    static final String[] MONTHS = {"January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December"};

    interface Changed { void on(); }
    interface PickIndex { void on(int index); }       // -1 = Clear
    interface PickYear { void on(int year); }         // 0 = Clear

    int y, m, d;

    private final Context ctx;
    private final boolean future;
    private final Changed cb;
    private boolean enabled = true;
    private final LinearLayout pY, pM, pD;

    DateTriple(Context ctx, LinearLayout host, boolean future, Changed cb) {
        this.ctx = ctx;
        this.future = future;
        this.cb = cb;
        pY = pill(host);
        pM = pill(host);
        pD = pill(host);

        pY.setOnClickListener(v -> {
            if (!enabled) return;
            pickYear(ctx, "Year", y, future, yr -> {
                y = yr;
                if (y == 0) { m = 0; d = 0; }
                fixDay();
                changed();
            });
        });
        pM.setOnClickListener(v -> {
            if (!enabled || y == 0) return;
            pick(ctx, "Month", MONTHS, m - 1, i -> {
                m = i + 1;                                  // -1 → 0 (Clear)
                if (m == 0) d = 0;
                fixDay();
                changed();
            });
        });
        pD.setOnClickListener(v -> {
            if (!enabled || y == 0 || m == 0) return;
            int n = daysIn(y, m);
            String[] days = new String[n];
            for (int i = 0; i < n; i++) days[i] = String.valueOf(i + 1);
            pick(ctx, "Day", days, d - 1, i -> { d = i + 1; changed(); });
        });
        render();
    }

    void set(int y, int m, int d) {
        this.y = Math.max(0, y);
        this.m = this.y == 0 ? 0 : Math.max(0, Math.min(12, m));
        this.d = this.m == 0 ? 0 : Math.max(0, d);
        fixDay();
        render();
    }

    void setEnabled(boolean on) {
        enabled = on;
        render();
    }

    private void changed() {
        render();
        if (cb != null) cb.on();
    }

    private void fixDay() {
        if (d > 0 && y > 0 && m > 0 && d > daysIn(y, m)) d = 0;
    }

    private static int daysIn(int y, int m) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(y, m - 1, 1);
        return c.getActualMaximum(Calendar.DAY_OF_MONTH);
    }

    // ---- আঁকা ----
    private void render() {
        paint(pY, y > 0 ? String.valueOf(y) : "Year", enabled);
        paint(pM, m > 0 ? MONTHS[m - 1] : "Month", enabled && y > 0);
        paint(pD, d > 0 ? String.valueOf(d) : "Day", enabled && y > 0 && m > 0);
    }

    private void paint(LinearLayout p, String text, boolean on) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(ctx, 12));
        g.setColor(Color.parseColor(on ? "#E4E6EB" : "#F0F2F5"));
        p.setBackground(g);
        TextView t = (TextView) p.getChildAt(0);
        t.setText(text);
        t.setTextColor(Color.parseColor(on ? "#050505" : "#BCC0C4"));
        ((ImageView) p.getChildAt(1)).setAlpha(on ? 1f : 0.35f);
    }

    private LinearLayout pill(LinearLayout host) {
        LinearLayout p = new LinearLayout(ctx);
        p.setOrientation(LinearLayout.HORIZONTAL);
        p.setGravity(Gravity.CENTER_VERTICAL);
        p.setPadding(dp(ctx, 14), dp(ctx, 14), dp(ctx, 10), dp(ctx, 14));
        p.setClickable(true);

        TextView t = new TextView(ctx);
        t.setTextSize(17);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setSingleLine(true);
        p.addView(t);

        ImageView c = new ImageView(ctx);
        c.setImageResource(R.drawable.ic_ct_caret);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(ctx, 20), dp(ctx, 20));
        lp.setMarginStart(dp(ctx, 6));
        p.addView(c, lp);

        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pl.setMarginEnd(dp(ctx, 8));
        host.addView(p, pl);
        return p;
    }

    // ------------------------------------------------------------ তালিকা ডায়ালগ

    static void pick(Context ctx, String title, String[] labels, int sel, PickIndex cb) {
        new AlertDialog.Builder(ctx).setTitle(title)
                .setSingleChoiceItems(labels, sel, (dlg, which) -> { dlg.dismiss(); cb.on(which); })
                .setNeutralButton("Clear", (dlg, w) -> cb.on(-1))
                .setNegativeButton("Cancel", null).show();
    }

    static void pickYear(Context ctx, String title, int cur, boolean future, PickYear cb) {
        int now = Calendar.getInstance().get(Calendar.YEAR);
        int top = future ? now + 10 : now;
        int n = top - (now - 90) + 1;
        String[] labels = new String[n];
        for (int i = 0; i < n; i++) labels[i] = String.valueOf(top - i);
        int sel = cur > 0 && cur <= top ? top - cur : -1;
        pick(ctx, title, labels, sel, i -> cb.on(i < 0 ? 0 : top - i));
    }

    private static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}
