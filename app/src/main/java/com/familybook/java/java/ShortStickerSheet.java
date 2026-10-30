package com.familybook.app;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * "Stickers" bottom sheet:
 *   handle + X button
 *   Location / temperature / time chips, GIF / Music chips
 *   grid: gallery tile -> GIPHY stickers -> emojis   (GIF chip: GIPHY GIFs + search)
 * No error / "not found" messages are shown anywhere in this sheet.
 */
final class ShortStickerSheet {

    interface Host {
        void addText(String text, int color, int bg, float size);   // emoji and Location/Temp/Time chips
        void addImage(Bitmap b);                                    // GIPHY image
        void pickGallery();
        void pickMusic();
        void withLocationPermission(Runnable granted);
    }

    private static final int GALLERY = 0, IMG = 1, EMOJI = 2;

    private static final class Cell {
        int type; String text, prev, full;
        Cell(int type) { this.type = type; }
    }

    private static final OkHttpClient HTTP = new OkHttpClient();

    private final Context ctx;
    private final Host host;
    private final Handler H = new Handler(Looper.getMainLooper());
    private final float dens;

    private final FrameLayout overlay;
    private final LinearLayout sheet;
    private final int sheetH, cellW, emojiW;
    private final RecyclerView rv;
    private final Adapter adapter = new Adapter();
    private final EditText search;
    private final TextView tvLoc, tvTemp, tvTime, chipGif;
    private final List<Cell> cells = new ArrayList<>();
    private final List<Cell> giphy = new ArrayList<>();
    private boolean open = false, gifMode = false;
    private int req = 0;
    private volatile String place = null, temp = null;

    private int dp(float v) { return Math.round(v * dens); }

    ShortStickerSheet(Context c, Host h) {
        ctx = c; host = h;
        dens = c.getResources().getDisplayMetrics().density;
        int sw = c.getResources().getDisplayMetrics().widthPixels;
        int sh = c.getResources().getDisplayMetrics().heightPixels;
        sheetH = Math.round(sh * 0.84f);
        cellW = (sw - dp(12)) / 4;
        emojiW = (sw - dp(12)) / 8;

        overlay = new FrameLayout(c);
        overlay.setBackgroundColor(0x66000000);
        overlay.setVisibility(View.GONE);
        overlay.setClickable(true);
        overlay.setOnClickListener(v -> hide());

        sheet = new LinearLayout(c);
        sheet.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF242526);
        bg.setCornerRadii(new float[]{dp(24), dp(24), dp(24), dp(24), 0, 0, 0, 0});
        sheet.setBackground(bg);
        sheet.setClickable(true);
        overlay.addView(sheet, new FrameLayout.LayoutParams(-1, sheetH, Gravity.BOTTOM));

        // handle
        View handle = new View(c);
        GradientDrawable hg = new GradientDrawable();
        hg.setColor(0xFF8A8D91);
        hg.setCornerRadius(dp(2));
        handle.setBackground(hg);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(dp(40), dp(4));
        hlp.gravity = Gravity.CENTER_HORIZONTAL; hlp.topMargin = dp(8);
        sheet.addView(handle, hlp);

        // X button
        TextView x = new TextView(c);
        x.setText("✕");
        x.setTextColor(Color.WHITE);
        x.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        x.setGravity(Gravity.CENTER);
        GradientDrawable xg = new GradientDrawable();
        xg.setShape(GradientDrawable.OVAL);
        xg.setColor(0xFF3A3B3C);
        x.setBackground(xg);
        x.setOnClickListener(v -> hide());
        LinearLayout.LayoutParams xlp = new LinearLayout.LayoutParams(dp(30), dp(30));
        xlp.gravity = Gravity.CENTER_HORIZONTAL; xlp.topMargin = dp(8); xlp.bottomMargin = dp(8);
        sheet.addView(x, xlp);

        // chips row 1: Location / Temp / Time
        LinearLayout row1 = chipRow(c);
        tvLoc = chip(c, "📍", "Location");
        tvTemp = chip(c, "☁️", "Temp");
        tvTime = chip(c, "🕒", "");
        row1.addView(wrapChip(tvLoc)); row1.addView(wrapChip(tvTemp)); row1.addView(wrapChip(tvTime));
        sheet.addView(row1, new LinearLayout.LayoutParams(-1, -2));

        // chips row 2: GIF / Music
        LinearLayout row2 = chipRow(c);
        chipGif = chip(c, "🔍", "GIF");
        TextView chipMusic = chip(c, "🎵", "Music");
        row2.addView(wrapChip(chipGif)); row2.addView(wrapChip(chipMusic));
        sheet.addView(row2, new LinearLayout.LayoutParams(-1, -2));

        tvLoc.setOnClickListener(v -> ensureInfo(() -> {
            if (place != null) { host.addText("📍 " + place, 0xFF111111, 0xFFFFFFFF, 0.055f); hide(); }
        }));
        tvTemp.setOnClickListener(v -> ensureInfo(() -> {
            if (temp != null) { host.addText("☁️ " + temp, 0xFF111111, 0xFFFFFFFF, 0.055f); hide(); }
        }));
        tvTime.setOnClickListener(v -> { host.addText("🕒 " + nowText(), 0xFF111111, 0xFFFFFFFF, 0.055f); hide(); });
        chipGif.setOnClickListener(v -> setMode(!gifMode));
        chipMusic.setOnClickListener(v -> { host.pickMusic(); hide(); });

        // search bar (GIF mode)
        search = new EditText(c);
        search.setHint("Search GIFs");
        search.setHintTextColor(0xFF8A8D91);
        search.setTextColor(Color.WHITE);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        search.setSingleLine(true);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setPadding(dp(14), dp(6), dp(14), dp(6));
        GradientDrawable sg = new GradientDrawable();
        sg.setColor(0xFF3A3B3C);
        sg.setCornerRadius(dp(20));
        search.setBackground(sg);
        search.setVisibility(View.GONE);
        search.setOnEditorActionListener((v, a, e) -> {
            hideKb();
            loadGiphy(gifMode, search.getText().toString().trim());
            return true;
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, dp(38));
        sp.leftMargin = dp(14); sp.rightMargin = dp(14); sp.topMargin = dp(8);
        sheet.addView(search, sp);

        // grid
        rv = new RecyclerView(c);
        GridLayoutManager gl = new GridLayoutManager(c, 8);
        gl.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override public int getSpanSize(int p) { return cells.get(p).type == EMOJI ? 1 : 2; }
        });
        rv.setLayoutManager(gl);
        rv.setAdapter(adapter);
        rv.setClipToPadding(false);
        rv.setPadding(dp(6), dp(8), dp(6), dp(8));
        sheet.addView(rv, new LinearLayout.LayoutParams(-1, 0, 1f));

        // required GIPHY attribution
        TextView pw = new TextView(c);
        pw.setText("Powered by GIPHY");
        pw.setTextColor(0xFF6B6E73);
        pw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        pw.setGravity(Gravity.CENTER);
        pw.setPadding(0, dp(4), 0, dp(6));
        sheet.addView(pw, new LinearLayout.LayoutParams(-1, -2));
    }

    View view() { return overlay; }

    boolean handleBack() {
        if (open) { hide(); return true; }
        return false;
    }

    // ---------------------------------------------------------------- show / hide

    void show() {
        if (open) return;
        open = true;
        overlay.setVisibility(View.VISIBLE);
        sheet.setTranslationY(sheetH);
        sheet.animate().translationY(0).setDuration(200).start();
        tvTime.setText("🕒  " + nowText());
        gifMode = false;
        search.setVisibility(View.GONE);
        styleGif();
        rebuild();
        loadGiphy(false, "");
        refreshInfo();
    }

    private void hide() {
        if (!open) return;
        open = false;
        hideKb();
        sheet.animate().translationY(sheetH).setDuration(180).withEndAction(() -> overlay.setVisibility(View.GONE)).start();
    }

    private void hideKb() {
        InputMethodManager im = (InputMethodManager) ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(search.getWindowToken(), 0);
    }

    private void setMode(boolean gif) {
        gifMode = gif;
        search.setVisibility(gif ? View.VISIBLE : View.GONE);
        search.setText("");
        styleGif();
        giphy.clear();
        rebuild();
        loadGiphy(gif, "");
    }

    private void styleGif() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(gifMode ? 0xFFD6E6FF : Color.WHITE);
        g.setCornerRadius(dp(14));
        chipGif.setBackground(g);
    }

    // ---------------------------------------------------------------- chips

    private LinearLayout chipRow(Context c) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER);
        r.setPadding(0, dp(3), 0, dp(3));
        return r;
    }

    private TextView chip(Context c, String icon, String label) {
        TextView t = new TextView(c);
        t.setText(icon + "  " + label);
        t.setTextColor(0xFF222222);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setPadding(dp(12), dp(7), dp(12), dp(7));
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.WHITE);
        g.setCornerRadius(dp(14));
        t.setBackground(g);
        return t;
    }

    private View wrapChip(TextView t) {
        FrameLayout f = new FrameLayout(ctx);
        f.setPadding(dp(4), 0, dp(4), 0);
        f.addView(t, new FrameLayout.LayoutParams(-2, -2));
        return f;
    }

    private static String nowText() {
        return new SimpleDateFormat("h:mm a", Locale.getDefault()).format(new Date());
    }

    // ---------------------------------------------------------------- location / weather

    private boolean hasLocPerm() {
        return ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void refreshInfo() {
        if (!hasLocPerm()) return;
        new Thread(() -> { loadInfoBlocking(); H.post(this::updateChips); }).start();
    }

    private void ensureInfo(final Runnable then) {
        host.withLocationPermission(() -> new Thread(() -> {
            loadInfoBlocking();
            H.post(() -> { updateChips(); then.run(); });
        }).start());
    }

    private void updateChips() {
        if (place != null) tvLoc.setText("📍  " + place);
        if (temp != null) tvTemp.setText("☁️  " + temp);
    }

    private void loadInfoBlocking() {
        Location l = lastLoc();
        if (l == null) return;
        try {
            if (Geocoder.isPresent()) {
                List<Address> a = new Geocoder(ctx, Locale.getDefault()).getFromLocation(l.getLatitude(), l.getLongitude(), 1);
                if (a != null && !a.isEmpty()) {
                    Address ad = a.get(0);
                    String n = ad.getLocality() != null ? ad.getLocality()
                            : ad.getSubAdminArea() != null ? ad.getSubAdminArea() : ad.getAdminArea();
                    if (n != null) place = n;
                }
            }
        } catch (Exception ignored) { }
        try {
            String u = String.format(Locale.US, "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m",
                    l.getLatitude(), l.getLongitude());
            JSONObject o = new JSONObject(httpGet(u));
            double t = o.getJSONObject("current").getDouble("temperature_2m");
            temp = Math.round(t) + "°C";
        } catch (Exception ignored) { }
    }

    private Location lastLoc() {
        if (!hasLocPerm()) return null;
        LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return null;
        Location best = null;
        try {
            for (String p : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
        } catch (SecurityException ignored) { }
        return best;
    }

    private static String httpGet(String url) throws Exception {
        Request r = new Request.Builder().url(url).build();
        try (Response resp = HTTP.newCall(r).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) throw new Exception("HTTP " + resp.code());
            return resp.body().string();
        }
    }

    // ---------------------------------------------------------------- GIPHY (silent on any problem)

    private void loadGiphy(final boolean gif, final String q) {
        final int token = ++req;
        giphy.clear();
        rebuild();
        if (!StickerConfig.hasKey()) return;
        new Thread(() -> {
            final List<Cell> got = new ArrayList<>();
            try {
                HttpUrl.Builder b = HttpUrl.parse("https://api.giphy.com/v1/" + (gif ? "gifs" : "stickers") + (q.isEmpty() ? "/trending" : "/search")).newBuilder()
                        .addQueryParameter("api_key", StickerConfig.GIPHY_KEY)
                        .addQueryParameter("limit", "36")
                        .addQueryParameter("rating", "g");
                if (!q.isEmpty()) b.addQueryParameter("q", q);
                JSONArray data = new JSONObject(httpGet(b.build().toString())).getJSONArray("data");
                for (int i = 0; i < data.length(); i++) {
                    JSONObject im = data.getJSONObject(i).getJSONObject("images");
                    JSONObject prev = im.optJSONObject("fixed_width");
                    JSONObject full = im.optJSONObject("original");
                    if (prev == null) continue;
                    Cell c = new Cell(IMG);
                    c.prev = prev.getString("url");
                    c.full = full != null && full.has("url") ? full.getString("url") : c.prev;
                    got.add(c);
                }
            } catch (Exception ignored) { }
            H.post(() -> {
                if (token != req) return;
                giphy.clear(); giphy.addAll(got);
                rebuild();
            });
        }).start();
    }

    private void pickImage(String url) {
        Glide.with(ctx.getApplicationContext()).asBitmap().load(url).override(512, 512).into(new CustomTarget<Bitmap>() {
            @Override public void onResourceReady(@NonNull Bitmap b, Transition<? super Bitmap> t) {
                host.addImage(b);
                hide();
            }
            @Override public void onLoadCleared(Drawable d) { }
        });
    }

    // ---------------------------------------------------------------- grid

    private void rebuild() {
        cells.clear();
        if (!gifMode) cells.add(new Cell(GALLERY));
        cells.addAll(giphy);
        if (!gifMode) {
            for (String e : emojis()) { Cell c = new Cell(EMOJI); c.text = e; cells.add(c); }
        }
        adapter.notifyDataSetChanged();
    }

    private static List<String> EMOJIS;

    private static synchronized List<String> emojis() {
        if (EMOJIS != null) return EMOJIS;
        Set<String> set = new LinkedHashSet<>();
        String[] first = {"😂", "😘", "😍", "😴", "😄", "😎", "🙁", "💋", "👀", "💯", "🎶", "❤️", "💕", "👍", "🙏", "☀️", "🔥", "✨"};
        for (String s : first) set.add(s);
        int[][] ranges = {{0x1F600, 0x1F64F}, {0x1F910, 0x1F9FF}, {0x1F300, 0x1F5FF}, {0x1F680, 0x1F6FF}, {0x1FA70, 0x1FAFF}};
        Paint p = new Paint();
        for (int[] r : ranges) {
            for (int cp = r[0]; cp <= r[1]; cp++) {
                String s = new String(Character.toChars(cp));
                if (p.hasGlyph(s)) set.add(s);
            }
        }
        EMOJIS = new ArrayList<>(set);
        return EMOJIS;
    }

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        @Override public int getItemViewType(int p) { return cells.get(p).type; }

        @Override public int getItemCount() { return cells.size(); }

        @NonNull @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int t) {
            View v;
            if (t == EMOJI) {
                TextView tv = new TextView(ctx);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
                tv.setGravity(Gravity.CENTER);
                tv.setLayoutParams(new RecyclerView.LayoutParams(-1, emojiW));
                v = tv;
            } else {
                FrameLayout f = new FrameLayout(ctx);
                f.setLayoutParams(new RecyclerView.LayoutParams(-1, cellW));
                ImageView iv = new ImageView(ctx);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                iv.setPadding(dp(6), dp(6), dp(6), dp(6));
                f.addView(iv, new FrameLayout.LayoutParams(-1, -1));
                TextView gi = new TextView(ctx);
                gi.setText("🖼️");
                gi.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
                gi.setGravity(Gravity.CENTER);
                gi.setVisibility(View.GONE);
                f.addView(gi, new FrameLayout.LayoutParams(-1, -1));
                v = f;
            }
            return new RecyclerView.ViewHolder(v) { };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int p) {
            final Cell c = cells.get(p);
            View v = h.itemView;
            if (c.type == EMOJI) {
                ((TextView) v).setText(c.text);
                v.setOnClickListener(x -> { host.addText(c.text, Color.WHITE, 0, 0.16f); hide(); });
                return;
            }
            FrameLayout f = (FrameLayout) v;
            ImageView iv = (ImageView) f.getChildAt(0);
            View gi = f.getChildAt(1);
            if (c.type == GALLERY) {
                iv.setImageDrawable(null);
                GradientDrawable og = new GradientDrawable();
                og.setShape(GradientDrawable.OVAL);
                og.setColor(0xFF3A3B3C);
                iv.setBackground(og);
                gi.setVisibility(View.VISIBLE);
                v.setOnClickListener(x -> { host.pickGallery(); hide(); });
            } else {
                iv.setBackground(null);
                gi.setVisibility(View.GONE);
                Glide.with(iv).load(c.prev).into(iv);
                v.setOnClickListener(x -> pickImage(c.full));
            }
        }
    }
}
