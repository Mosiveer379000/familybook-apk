package com.familybook.app;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * নিজের Memory-র "N viewers ⌄" চাপলে এই পর্দা:
 *  উপরে ৩-ডট (ডেমো) ও X · মাঝে Memory কার্ডের সারি (ডান থেকে বামে স্ক্রল, শেষে "Add Memory") ·
 *  নিচে সাদা প্যানেলে Viewers / Insights ট্যাব (Insights ডেমো)।
 */
public class MemoryViewersActivity extends BaseActivity {

    static final String EXTRA_IDS = "mv_ids", EXTRA_THUMBS = "mv_thumbs", EXTRA_START = "mv_start";

    static void open(Context c, ArrayList<String> ids, ArrayList<String> thumbs, int start) {
        Intent i = new Intent(c, MemoryViewersActivity.class);
        i.putStringArrayListExtra(EXTRA_IDS, ids);
        i.putStringArrayListExtra(EXTRA_THUMBS, thumbs);
        i.putExtra(EXTRA_START, start);
        c.startActivity(i);
    }

    private static final int BLUE = 0xFF1877F2, BLUE_BG = 0xFFE3EEFD, PAGE_BG = 0xFFF2F4F8;

    private ArrayList<String> ids, thumbs;
    private int sel = 0;
    private boolean tabViewers = true;
    private final Map<String, JSONArray> cache = new HashMap<>();
    private final ArrayList<FrameLayout> cards = new ArrayList<>();
    private LinearLayout strip;
    private HorizontalScrollView hsv;
    private FrameLayout content;
    private TextView tabV, tabI;
    private int gen = 0;

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ids = getIntent().getStringArrayListExtra(EXTRA_IDS);
        thumbs = getIntent().getStringArrayListExtra(EXTRA_THUMBS);
        if (ids == null || ids.isEmpty()) { finish(); return; }
        if (thumbs == null) thumbs = new ArrayList<>();
        sel = Math.max(0, Math.min(ids.size() - 1, getIntent().getIntExtra(EXTRA_START, 0)));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(PAGE_BG);

        // উপরের সারি: ৩-ডট (ডেমো) + X
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        top.setPadding(dp(8), dp(28), dp(14), 0);
        TextView dots = new TextView(this);
        dots.setText("•••");
        dots.setTextColor(Color.BLACK);
        dots.setTextSize(18);
        dots.setTypeface(Typeface.DEFAULT_BOLD);
        dots.setGravity(Gravity.CENTER);
        dots.setOnClickListener(v -> TopToast.show(this, "Menu (demo)"));
        top.addView(dots, new LinearLayout.LayoutParams(dp(48), dp(44)));
        ImageView x = new ImageView(this);
        x.setImageResource(R.drawable.close_x);
        x.setColorFilter(Color.BLACK);
        x.setPadding(dp(12), dp(12), dp(12), dp(12));
        x.setOnClickListener(v -> finish());
        top.addView(x, new LinearLayout.LayoutParams(dp(44), dp(44)));
        root.addView(top, new LinearLayout.LayoutParams(-1, -2));

        // কার্ডের সারি
        hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        strip.setPadding(dp(16), dp(8), dp(16), dp(8));
        hsv.addView(strip, new FrameLayout.LayoutParams(-2, dp(212)));
        root.addView(hsv, new LinearLayout.LayoutParams(-1, dp(212)));
        buildCards();

        // সাদা প্যানেল
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable pbg = new GradientDrawable();
        pbg.setColor(Color.WHITE);
        float r = dp(24);
        pbg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        panel.setBackground(pbg);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setPadding(dp(16), dp(14), dp(16), dp(6));
        tabV = tab("Viewers", true);
        tabI = tab("Insights", false);
        tabV.setOnClickListener(v -> setTab(true));
        tabI.setOnClickListener(v -> setTab(false));
        tabs.addView(tabV);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
        tl.leftMargin = dp(8);
        tabs.addView(tabI, tl);
        panel.addView(tabs, new LinearLayout.LayoutParams(-1, -2));

        content = new FrameLayout(this);
        panel.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));
        root.addView(panel, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);

        render();
        hsv.post(() -> scrollToSel(false));
    }

    // ---------------------------------------------------------------- কার্ড

    private void buildCards() {
        strip.removeAllViews();
        cards.clear();
        for (int i = 0; i < ids.size(); i++) {
            final int pos = i;
            FrameLayout c = new FrameLayout(this);
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            c.addView(iv, new FrameLayout.LayoutParams(-1, -1));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFFAABBC6);
            bg.setCornerRadius(dp(16));
            c.setBackground(bg);
            c.setClipToOutline(true);
            String tk = i < thumbs.size() ? thumbs.get(i) : "";
            if (tk != null && !tk.isEmpty())
                Media.loadBitmap(tk, bm -> { if (bm != null) iv.setImageBitmap(bm); });
            c.setOnClickListener(v -> select(pos));
            cards.add(c);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(92), dp(160));
            lp.rightMargin = dp(10);
            strip.addView(c, lp);
        }
        // শেষে "+ Add Memory" কার্ড
        FrameLayout add = new FrameLayout(this);
        GradientDrawable abg = new GradientDrawable();
        abg.setColor(0xFFC5D7F5);
        abg.setCornerRadius(dp(16));
        add.setBackground(abg);
        LinearLayout in = new LinearLayout(this);
        in.setOrientation(LinearLayout.VERTICAL);
        in.setGravity(Gravity.CENTER);
        TextView plus = new TextView(this);
        plus.setText("+");
        plus.setTextColor(Color.WHITE);
        plus.setTextSize(28);
        plus.setGravity(Gravity.CENTER);
        GradientDrawable pc = new GradientDrawable();
        pc.setShape(GradientDrawable.OVAL);
        pc.setColor(0xFF0A5BD6);
        plus.setBackground(pc);
        in.addView(plus, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView t = new TextView(this);
        t.setText("Add Memory");
        t.setTextColor(BLUE);
        t.setTextSize(13);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(4), dp(8), dp(4), 0);
        in.addView(t, new LinearLayout.LayoutParams(-1, -2));
        add.addView(in, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        add.setOnClickListener(v -> MemoryRow.openCreate(this));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(92), dp(160));
        strip.addView(add, alp);
        applySel();
    }

    /** বাছাই করা কার্ড বড়, বাকিগুলো ছোট */
    private void applySel() {
        for (int i = 0; i < cards.size(); i++) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) cards.get(i).getLayoutParams();
            boolean on = i == sel;
            lp.width = dp(on ? 112 : 92);
            lp.height = dp(on ? 192 : 160);
            cards.get(i).setLayoutParams(lp);
        }
    }

    private void select(int pos) {
        if (pos == sel) return;
        sel = pos;
        applySel();
        scrollToSel(true);
        render();
    }

    private void scrollToSel(boolean smooth) {
        if (sel >= cards.size()) return;
        View c = cards.get(sel);
        int target = c.getLeft() - (hsv.getWidth() - c.getWidth()) / 2;
        if (smooth) hsv.smoothScrollTo(Math.max(0, target), 0);
        else hsv.scrollTo(Math.max(0, target), 0);
    }

    // ---------------------------------------------------------------- ট্যাব

    private TextView tab(String s, boolean on) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(16);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(18), dp(10), dp(18), dp(10));
        styleTab(t, on);
        return t;
    }

    private void styleTab(TextView t, boolean on) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(22));
        bg.setColor(on ? BLUE_BG : Color.TRANSPARENT);
        t.setBackground(bg);
        t.setTextColor(on ? BLUE : Color.BLACK);
        t.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
    }

    private void setTab(boolean viewers) {
        tabViewers = viewers;
        styleTab(tabV, viewers);
        styleTab(tabI, !viewers);
        render();
    }

    private void render() {
        content.removeAllViews();
        if (tabViewers) renderViewers(); else renderInsights();
    }

    // ---------------------------------------------------------------- Viewers

    private void renderViewers() {
        final String memId = ids.get(sel);
        final int g = ++gen;
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(10), dp(16), dp(10));
        final TextView head = new TextView(this);
        head.setTextColor(Color.BLACK);
        head.setTextSize(24);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        head.setText("Loading…");
        head.setPadding(0, dp(8), 0, dp(10));
        col.addView(head);
        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView sc = new ScrollView(this);
        sc.addView(list);
        col.addView(sc, new LinearLayout.LayoutParams(-1, 0, 1f));
        content.addView(col, new FrameLayout.LayoutParams(-1, -1));

        JSONArray cached = cache.get(memId);
        if (cached != null) { fill(cached, head, list); return; }
        SupabaseRest.call("GET", "/rest/v1/memory_views?memory_id=eq." + memId
                + "&select=viewer_id,viewed_at&order=viewed_at.desc&limit=200", null, null, true, r -> {
            if (g != gen || isFinishing()) return;
            if (!r.ok()) { head.setText(SupabaseRest.errorText(r)); return; }
            try {
                JSONArray a = new JSONArray(r.body);
                cache.put(memId, a);
                fill(a, head, list);
            } catch (Exception e) { head.setText("Couldn't load viewers"); }
        });
    }

    private void fill(JSONArray a, TextView head, LinearLayout list) {
        int n = a.length();
        head.setText(n == 0 ? "No viewers yet" : n + (n == 1 ? " viewer" : " viewers"));
        for (int i = 0; i < n; i++) {
            String uid = a.optJSONObject(i).optString("viewer_id");
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(7), 0, dp(7));
            ImageView av = new ImageView(this);
            AvatarLoader.load(av, uid);
            row.addView(av, new LinearLayout.LayoutParams(dp(56), dp(56)));
            final TextView nm = new TextView(this);
            nm.setTextColor(Color.BLACK);
            nm.setTextSize(18);
            nm.setPadding(dp(16), 0, 0, 0);
            UserNames.fetch(uid, name -> nm.setText(name == null ? "Family member" : name));
            row.addView(nm, new LinearLayout.LayoutParams(0, -2, 1f));
            list.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    // ---------------------------------------------------------------- Insights (ডেমো)

    private void renderInsights() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(10), dp(16), dp(10));
        TextView head = new TextView(this);
        head.setText("Insights");
        head.setTextColor(Color.BLACK);
        head.setTextSize(24);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        head.setPadding(0, dp(8), 0, dp(14));
        col.addView(head);
        JSONArray a = cache.get(ids.get(sel));
        String[][] rows = {
                {"Views", a == null ? "—" : String.valueOf(a.length())},
                {"Reach", "128"},
                {"Replies", "4"},
                {"Shares", "2"},
        };
        for (String[] r : rows) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(14), dp(16), dp(14));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(PAGE_BG);
            bg.setCornerRadius(dp(14));
            row.setBackground(bg);
            TextView k = new TextView(this);
            k.setText(r[0]);
            k.setTextSize(17);
            k.setTextColor(Color.BLACK);
            row.addView(k, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView v = new TextView(this);
            v.setText(r[1]);
            v.setTextSize(19);
            v.setTypeface(Typeface.DEFAULT_BOLD);
            v.setTextColor(BLUE);
            row.addView(v);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = dp(10);
            col.addView(row, lp);
        }
        TextView note = new TextView(this);
        note.setText("Demo data — real insights coming soon");
        note.setTextColor(0xFF65676B);
        note.setTextSize(13);
        note.setPadding(dp(4), dp(6), 0, 0);
        col.addView(note);
        content.addView(col, new FrameLayout.LayoutParams(-1, -1));
    }
}
