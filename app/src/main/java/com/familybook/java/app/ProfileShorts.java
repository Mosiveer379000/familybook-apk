package com.familybook.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * প্রোফাইলের Shorts ট্যাব (ডিফল্ট ট্যাব) — ছবির (স্ক্রিনশট) মাপ ধরে হুবহু।
 *
 * - প্রথম ঘরে নীল বর্ডারের রাউন্ড কার্ড: "+New short" বাটন + "Criteria" তালিকা
 * - Short না থাকলে ডান পাশের ফাঁকা জায়গায় কম opacity-তে "No shorts yet"
 * - আপলোড চললে ওই ফাঁকা জায়গার ঠিক মাঝখানে গোল প্রোগ্রেস রিং (%)
 * - Short থাকলে ৩ কলামের রাউন্ড কার্ড (+New short কার্ডের মতো কোণ ও নীল বর্ডার); নিচে বাঁ দিকে 👁 + ভিউ কাউন্ট
 *
 * সব মাপ স্ক্রিনশটের পিক্সেল থেকে নেওয়া (কার্ড চওড়া = ৪৯৩ ধরে) এবং স্ক্রিনের প্রস্থ অনুযায়ী
 * স্কেল হয়, তাই যেকোনো ফোনে অনুপাত একই থাকবে (সিস্টেম ফন্ট সাইজ বদলালেও নড়বে না)।
 * পরে আসল ভিডিও এলে: setItems(list) ডাকুন।
 */
final class ProfileShorts {

    /** একটি Short কার্ডের ডেটা */
    static final class Item {
        String thumbKey;   // থাম্বনেইলের R2 key
        long views;
        String id;
    }

    private static final int COLS = 3;
    private static final int BLUE = 0xFF0866FF;
    private static final float REF_W = 385f;       // স্ক্রিনশটে কার্ডের চওড়া (px)
    private static final float RATIO = 1.63f;      // কার্ডের উচ্চতা / চওড়া
    private static final float RADIUS = 42f;       // কার্ডের কোণের গোলাই (স্ক্রিনশট px, REF_W অনুপাতে)

    private final Activity act;
    private final LinearLayout box;
    private final float density;
    private final List<Item> items = new ArrayList<>();
    private boolean visible = false;
    private boolean mine = true;                    // নিজের প্রোফাইল? (অন্যের হলে +New short কার্ড নেই)
    private String ownerUid;
    private boolean limitReached = false;           // শেষ ৭ দিনে ৮টি হয়ে গেছে → +New short ব্লক
    private ShortUploader.State lastState = ShortUploader.state();

    // আপলোড-প্যানেল (চলমান Short আপলোডের প্রোগ্রেস — Shorts-এর মাঝখানে)
    private LinearLayout pane;
    private RingProgressView ring;
    private TextView paneTitle, paneSub;

    private final ShortUploader.Listener uploadListener = () -> {
        if (!visible) return;
        ShortUploader.State st = ShortUploader.state();
        if (st == ShortUploader.State.DONE && lastState != ShortUploader.State.DONE) refresh();  // নতুন Short তালিকায় আসবে
        lastState = st;
        boolean need = mine && ShortUploader.isSubmitted();
        if (need != (pane != null)) build();
        else if (pane != null) updatePane();
    };

    ProfileShorts(Activity act, ViewGroup parent, int index) {
        this.act = act;
        this.density = act.getResources().getDisplayMetrics().density;
        box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Color.WHITE);
        box.setPadding(0, dp(1), 0, dp(24));
        parent.addView(box, index, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.setVisibility(View.GONE);
    }

    private int dp(int v) { return Math.round(v * density); }

    void setVisible(boolean show) {
        boolean was = visible;
        visible = show;
        box.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            ShortUploader.addListener(uploadListener);
            if (!was || box.getChildCount() == 0) build();
            if (!was) refresh();
        } else {
            ShortUploader.removeListener(uploadListener);   // স্ক্রিন বন্ধ হলে Activity ধরে রাখে না
        }
    }

    /** কার প্রোফাইল দেখা হচ্ছে — ProfileActivity setVisible-এর আগে জানাবে */
    void setOwner(boolean isMe, String uid) {
        boolean diff = isMe != mine || (uid != null && !uid.equals(ownerUid));
        mine = isMe;
        ownerUid = uid;
        if (diff) {
            items.clear();
            limitReached = false;
            if (visible) { build(); refresh(); }
        }
    }

    /** সার্ভার থেকে Shorts (নিজের হলে শেষ ৭ দিনের সংখ্যাও) আনে */
    void refresh() {
        final String target = mine ? SupabaseRest.uid() : ownerUid;
        ShortUploader.loadFor(target, list -> setItems(list));
        if (!mine) return;
        ShortUploader.weeklyCount(c -> {
            boolean lim = c >= ShortUploader.WEEKLY_LIMIT;
            if (lim != limitReached) {
                limitReached = lim;
                if (visible) build();
            }
        });
    }

    /** আসল Shorts এলে এটা ডাকুন */
    void setItems(List<Item> list) {
        items.clear();
        if (list != null) items.addAll(list);
        if (visible) build();
    }

    // ------------------------------------------------------------------ UI

    private void build() {
        box.removeAllViews();
        pane = null;
        ring = null;

        int total = act.getResources().getDisplayMetrics().widthPixels;
        int cell = total / COLS;
        int pad = dp(6);                                // প্রতিটি কার্ডের চারপাশের ফাঁক
        int tileW = cell - 2 * pad;
        int tileH = Math.round(tileW * RATIO);
        boolean uploading = mine && ShortUploader.isSubmitted();

        LinearLayout first = new LinearLayout(act);
        first.setOrientation(LinearLayout.HORIZONTAL);
        box.addView(first, new LinearLayout.LayoutParams(-1, -2));

        if (mine) {
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(tileW, tileH);
            cardLp.setMargins(pad, pad, pad, 0);
            first.addView(newShortCard(tileW), cardLp);
        }

        if (uploading) {
            // চলমান আপলোড: Shorts-এর মাঝখানে গোল প্রোগ্রেস
            first.addView(buildPane(cell), new LinearLayout.LayoutParams(0, tileH + pad, 1f));
        } else if (items.isEmpty()) {
            // Short না থাকলে: drawable-এর লোগো, তার নিচে "No shorts yet" (কম opacity)
            LinearLayout none = new LinearLayout(act);
            none.setOrientation(LinearLayout.VERTICAL);
            none.setGravity(Gravity.CENTER);
            none.setAlpha(0.45f);
            ImageView noLogo = new ImageView(act);
            noLogo.setImageResource(R.drawable.ic_no_shorts);
            noLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int logoPx = Math.round(cell * 0.5f);
            none.addView(noLogo, new LinearLayout.LayoutParams(logoPx, logoPx));
            TextView noText = new TextView(act);
            noText.setText("No shorts yet");
            noText.setTextColor(0xFF65676B);
            noText.setTextSize(TypedValue.COMPLEX_UNIT_PX, cell * 0.12f);
            noText.setTypeface(null, Typeface.BOLD);
            noText.setGravity(Gravity.CENTER);
            none.addView(noText, new LinearLayout.LayoutParams(-2, -2));
            first.addView(none, new LinearLayout.LayoutParams(0, mine ? tileH + pad : dp(200), 1f));
            return;
        }

        LinearLayout row = first;
        // নিজের হলে কার্ডের পাশের ২ ঘরে; আপলোড চললে প্যানেল সেই জায়গা নিয়েছে, তাই নতুন সারি থেকে
        int col = !mine ? 0 : (uploading ? COLS : 1);
        for (int i = 0; i < items.size(); i++) {
            if (col == COLS) {
                row = new LinearLayout(act);
                row.setOrientation(LinearLayout.HORIZONTAL);
                box.addView(row, new LinearLayout.LayoutParams(-1, -2));
                col = 0;
            }
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(tileW, tileH);
            tp.setMargins(pad, pad, pad, 0);
            row.addView(tile(items.get(i), tileW), tp);
            col++;
        }
    }

    // ------------------------------------------------------------ আপলোড-প্যানেল (মাঝখানে)

    private View buildPane(int cell) {
        pane = new LinearLayout(act);
        pane.setOrientation(LinearLayout.VERTICAL);
        pane.setGravity(Gravity.CENTER);
        pane.setPadding(dp(8), 0, dp(8), 0);

        ring = new RingProgressView(act);
        ring.setSizing(5f, 17f);
        int rs = Math.min(dp(92), Math.round(cell * 0.62f));
        pane.addView(ring, new LinearLayout.LayoutParams(rs, rs));

        paneTitle = new TextView(act);
        paneTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, cell * 0.105f);
        paneTitle.setTypeface(null, Typeface.BOLD);
        paneTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.topMargin = dp(10);
        pane.addView(paneTitle, tp);

        paneSub = new TextView(act);
        paneSub.setTextSize(TypedValue.COMPLEX_UNIT_PX, cell * 0.085f);
        paneSub.setTextColor(0xFF8A8D91);
        paneSub.setGravity(Gravity.CENTER);
        pane.addView(paneSub, new LinearLayout.LayoutParams(-2, -2));

        pane.setOnClickListener(v -> {
            if (ShortUploader.state() == ShortUploader.State.FAILED) ShortUploader.retry();
        });
        updatePane();
        return pane;
    }

    private void updatePane() {
        if (pane == null) return;
        if (ShortUploader.state() == ShortUploader.State.FAILED) {
            String e = ShortUploader.error();
            ring.setVisibility(View.GONE);
            paneTitle.setText("Upload failed");
            paneTitle.setTextColor(0xFFE41E3F);
            paneSub.setText((e == null ? "" : e + "\n") + "Tap to retry");
        } else {
            ring.setVisibility(View.VISIBLE);
            ring.setProgress(ShortUploader.displayPercent());
            paneTitle.setText("Uploading short…");
            paneTitle.setTextColor(0xFF4F5155);
            paneSub.setText("Keep FamilyBook open.");
        }
    }

    /** নীল বর্ডারের রাউন্ড কার্ড (স্ক্রিনশটের মতো) — সব মাপ স্ক্রিনশট থেকে (s = স্কেল) */
    private View newShortCard(int cardPx) {
        final float s = cardPx / REF_W;

        FrameLayout card = new FrameLayout(act);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(RADIUS * s);
        bg.setStroke(Math.max(2, Math.round(5 * s)), BLUE);
        card.setBackground(bg);
        card.setAlpha(limitReached ? 0.4f : 1f);   // সীমা শেষ হলে ঝাপসা/ব্লক

        // ---- "+New short" বাটন (পিল) ----
        LinearLayout btn = new LinearLayout(act);
        btn.setOrientation(LinearLayout.HORIZONTAL);
        btn.setGravity(Gravity.CENTER_VERTICAL);
        btn.setPadding(Math.round(52 * s), 0, 0, 0);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(0xFFFAFBFE);
        pill.setCornerRadius(60 * s);
        pill.setStroke(Math.max(1, Math.round(2 * s)), BLUE);
        btn.setBackground(pill);
        btn.setClickable(true);
        btn.setOnClickListener(v -> {
            if (limitReached) {
                Toast.makeText(act, "এই সপ্তাহে ৮টি Shorts আপলোড হয়ে গেছে। কিছুদিন পরে আবার চেষ্টা করুন।",
                        Toast.LENGTH_LONG).show();
            } else if (ShortUploader.isSubmitted()) {
                Toast.makeText(act, "আগের Short আপলোড চলছে, শেষ হওয়া পর্যন্ত অপেক্ষা করুন", Toast.LENGTH_SHORT).show();
            } else {
                act.startActivity(new Intent(act, ShortCreateActivity.class));
            }
        });

        ImageView logo = new ImageView(act);
        logo.setImageResource(R.drawable.ic_shorts_logo);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        btn.addView(logo, new LinearLayout.LayoutParams(Math.round(44 * s), Math.round(44 * s)));

        TextView label = new TextView(act);
        label.setText("+New short");
        label.setTextColor(BLUE);
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX, 32 * s);
        label.setTypeface(null, Typeface.BOLD);
        label.setSingleLine(true);
        label.setIncludeFontPadding(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMarginStart(Math.round(26 * s));
        btn.addView(label, lp);

        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(Math.round(369 * s), Math.round(117 * s));
        bp.leftMargin = Math.round(8 * s);
        bp.topMargin = Math.round(8 * s);
        card.addView(btn, bp);

        // ---- Criteria ----
        addText(card, s, "Criteria", 55, 185, 81f, 0xFF0A4DA6);
        String[] lines = {"Stay Consistent", "Make Own Face", "Don’t use Sound", "Video in 1:1", "Upload 8 for 7Days"};
        float[] cy = {263f, 335f, 408f, 480f, 553f};
        for (int i = 0; i < lines.length; i++) addText(card, s, lines[i], 48, cy[i], 35f, 0xFF4F5155);
        return card;
    }

    /** x = বাঁ দিক, cy = লাইনের মাঝের উচ্চতা (স্ক্রিনশট px, কার্ডের ভেতরে), size = ফন্ট px */
    private void addText(FrameLayout card, float s, String text, float x, float cy, float size, int color) {
        TextView t = new TextView(act);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * s);
        t.setTypeface(null, Typeface.BOLD);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        t.setGravity(Gravity.CENTER_VERTICAL);
        int h = Math.round(size * 1.4f * s);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, h);
        lp.leftMargin = Math.round(x * s);
        lp.topMargin = Math.round(cy * s) - h / 2;
        card.addView(t, lp);
    }

    /** Short কার্ড: +New short কার্ডের মতো গোল কোণ ও নীল বর্ডার; নিচে বাঁ দিকে 👁 + ভিউ কাউন্ট */
    private View tile(Item it, int w) {
        FrameLayout t = new FrameLayout(act);
        t.setBackgroundColor(0xFF3A3B3C);

        final float r = RADIUS * w / REF_W;
        t.setClipToOutline(true);
        t.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r);
            }
        });
        GradientDrawable border = new GradientDrawable();   // বর্ডার থাম্বনেইলের উপরে আঁকা হয়
        border.setColor(Color.TRANSPARENT);
        border.setCornerRadius(r);
        border.setStroke(Math.max(2, Math.round(5 * w / REF_W)), BLUE);
        t.setForeground(border);

        ImageView thumb = new ImageView(act);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (it.thumbKey != null && !it.thumbKey.isEmpty()) AvatarLoader.loadKey(thumb, it.thumbKey);
        t.addView(thumb, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout views = new LinearLayout(act);
        views.setOrientation(LinearLayout.HORIZONTAL);
        views.setGravity(Gravity.CENTER_VERTICAL);
        ImageView eye = new ImageView(act);
        eye.setImageResource(R.drawable.ic_short_eye);
        int eyeSz = Math.round(w * 0.11f);
        views.addView(eye, new LinearLayout.LayoutParams(eyeSz, eyeSz));
        TextView tv = new TextView(act);
        tv.setText(fmt(it.views));
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, w * 0.10f);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setShadowLayer(4f, 0f, 1f, 0xAA000000);
        tv.setSingleLine(true);
        tv.setIncludeFontPadding(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMarginStart(Math.round(w * 0.03f));
        views.addView(tv, lp);
        FrameLayout.LayoutParams vp = new FrameLayout.LayoutParams(-2, -2);
        vp.gravity = Gravity.BOTTOM | Gravity.START;
        vp.leftMargin = Math.round(w * 0.11f);
        vp.bottomMargin = Math.round(w * 0.11f);
        t.addView(views, vp);

        t.setClickable(true);
        t.setOnClickListener(v -> openViewer(it));
        if (mine) {
            t.setOnLongClickListener(v -> { confirmDelete(it); return true; });
        }
        return t;
    }

    /** ফুল-ব্ল্যাক-স্ক্রিন ভিউয়ার: এই প্রোফাইলের Shorts উপর থেকে নিচে, ট্যাপ করাটা থেকে শুরু */
    private void openViewer(Item it) {
        ArrayList<String> ids = new ArrayList<>();
        for (Item x : items) if (x.id != null && !x.id.isEmpty()) ids.add(x.id);
        int idx = Math.max(0, ids.indexOf(it.id));
        ShortPlayerView.stopAll();
        ShortViewerActivity.open(act, ids, idx);
    }

    /** লং-প্রেসে ডিলিট: মুছলে সাপ্তাহিক সংখ্যা কমে, সীমা খুলে যায় */
    private void confirmDelete(final Item it) {
        new android.app.AlertDialog.Builder(act)
                .setMessage("এই Short ডিলিট করবেন? এটা আর ফেরানো যাবে না।")
                .setPositiveButton("ডিলিট", (d, w) -> ShortUploader.delete(it.id, it.thumbKey, err -> {
                    if (err != null) {
                        Toast.makeText(act, err, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    items.remove(it);
                    Toast.makeText(act, "Short ডিলিট হয়েছে", Toast.LENGTH_SHORT).show();
                    if (visible) build();
                    refresh();   // সার্ভার থেকে আবার গুনে +New short খুলে দেয়
                }))
                .setNegativeButton("বাদ", null)
                .show();
    }

    private static String fmt(long n) {
        if (n < 1000) return String.valueOf(n);
        if (n < 1_000_000) return trim(n / 1000.0) + "K";
        return trim(n / 1_000_000.0) + "M";
    }

    private static String trim(double d) {
        String s = String.format(java.util.Locale.US, "%.1f", d);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }
}
