package com.familybook.app;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Add location: কাছাকাছি জায়গার সাজেশন (ফোনের লোকেশন থেকে) + টাইপ করে সার্চ।
 * ডাটা: OpenStreetMap Nominatim (ফ্রি, API key লাগে না; সার্চ ৪০০ms থেমে তারপর)।
 * ফলাফল: EXTRA_LOCATION = "Patenga, Chittagong, Bangladesh" (খালি স্ট্রিং = লোকেশন সরানো)
 */
public class LocationActivity extends AppCompatActivity {

    public static final String EXTRA_LOCATION = "location";

    private static final String TAG = "LocationActivity";
    private static final String UA = "FamilyBook/1.0 (Android)";
    private static final String BASE = "https://nominatim.openstreetmap.org";

    static final class Place {
        final String title;   // বড় লেখা
        final String sub;     // ছোট লেখা
        final String label;   // পোস্টে যা বসবে
        Place(String title, String sub, String label) {
            this.title = title;
            this.sub = sub;
            this.label = label;
        }
    }

    private final List<Place> places = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable searchTask;
    private int seq = 0;
    private float density = 1f;

    private TextView tvHeader, tvStatus, tvRemove;
    private RecyclerView rv;
    private PlaceAdapter adapter;

    private final ActivityResultLauncher<String> permLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) loadNearby();
                else showStatus("লোকেশন পারমিশন দেওয়া নেই। উপরে সার্চ করে জায়গা বেছে নিন।");
            });

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_location);
        density = getResources().getDisplayMetrics().density;

        tvHeader = findViewById(R.id.tvHeader);
        tvStatus = findViewById(R.id.tvStatus);
        tvRemove = findViewById(R.id.tvRemove);
        rv = findViewById(R.id.rvPlaces);
        final android.widget.EditText et = findViewById(R.id.etSearch);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnRefresh).setOnClickListener(v -> {
            et.setText("");
            startNearby();
        });

        String current = getIntent().getStringExtra(EXTRA_LOCATION);
        if (!TextUtils.isEmpty(current)) {
            tvRemove.setVisibility(View.VISIBLE);
            tvRemove.setOnClickListener(v -> returnLocation(""));
        }

        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new PlaceAdapter();
        rv.setAdapter(adapter);

        et.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                if (searchTask != null) handler.removeCallbacks(searchTask);
                final String q = s.toString().trim();
                if (q.isEmpty()) {
                    searchTask = LocationActivity.this::startNearby;
                } else {
                    searchTask = () -> search(q);
                }
                handler.postDelayed(searchTask, 400);
            }
            @Override public void afterTextChanged(Editable s) { }
        });

        startNearby();
    }

    @Override
    protected void onDestroy() {
        if (searchTask != null) handler.removeCallbacks(searchTask);
        super.onDestroy();
    }

    // ---------------------------------------------------------
    // কাছাকাছি: ফোনের শেষ জানা লোকেশন -> reverse geocode
    // ---------------------------------------------------------
    private void startNearby() {
        tvHeader.setText("Suggestions");
        boolean fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        boolean coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        if (fine || coarse) {
            loadNearby();
        } else {
            permLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION);
        }
    }

    @SuppressWarnings("MissingPermission")
    private void loadNearby() {
        Location best = null;
        try {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            for (String prov : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(prov);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
        } catch (Exception e) {
            Log.w(TAG, "last location failed", e);
        }
        if (best == null) {
            showStatus("ফোনের লোকেশন পাওয়া যায়নি (GPS/Location চালু আছে?)। সার্চ করুন।");
            return;
        }
        final double lat = best.getLatitude(), lon = best.getLongitude();
        final int my = ++seq;
        showStatus("খোঁজা হচ্ছে…");
        new Thread(() -> {
            List<Place> out = new ArrayList<>();
            try {
                JSONObject o = new JSONObject(get(BASE + "/reverse?format=jsonv2&addressdetails=1&zoom=18"
                        + "&accept-language=en&lat=" + lat + "&lon=" + lon));
                JSONObject a = o.optJSONObject("address");
                if (a != null) out.addAll(areaSuggestions(a));
            } catch (Exception e) {
                Log.w(TAG, "reverse failed", e);
            }
            final List<Place> res = out;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || my != seq) return;
                if (res.isEmpty()) {
                    showStatus("সাজেশন আনা যায়নি। ইন্টারনেট দেখুন বা সার্চ করুন।");
                } else {
                    showPlaces(res);
                }
            });
        }).start();
    }

    // ছোট এলাকা -> বড় এলাকা: "Patenga, Chittagong, Bangladesh", "Chittagong", ...
    private List<Place> areaSuggestions(JSONObject a) {
        List<Place> out = new ArrayList<>();
        String local = first(a, "neighbourhood", "suburb", "quarter", "village", "hamlet", "city_district");
        String city = first(a, "city", "town", "municipality", "county", "state_district");
        String state = first(a, "state_district", "state");
        String country = a.optString("country", "");

        if (!local.isEmpty()) {
            String parent = !city.isEmpty() ? city : state;
            String label = join(local, parent, country);
            out.add(new Place(label, "Nearby", label));
        }
        if (!city.isEmpty() && !city.equals(local)) {
            out.add(new Place(city, join(state.equals(city) ? "" : state, country, ""), join(city, country, "")));
        }
        if (!state.isEmpty() && !state.equals(city)) {
            out.add(new Place(state, country, join(state, country, "")));
        }
        if (!country.isEmpty()) {
            out.add(new Place(country, "Country", country));
        }
        return out;
    }

    // ---------------------------------------------------------
    // টাইপ করে সার্চ
    // ---------------------------------------------------------
    private void search(final String q) {
        final int my = ++seq;
        tvHeader.setText("Results");
        showStatus("খোঁজা হচ্ছে…");
        new Thread(() -> {
            List<Place> out = new ArrayList<>();
            try {
                JSONArray arr = new JSONArray(get(BASE + "/search?format=jsonv2&addressdetails=1&limit=10"
                        + "&accept-language=en&q=" + URLEncoder.encode(q, "UTF-8")));
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    JSONObject a = o.optJSONObject("address");
                    String name = o.optString("name", "");
                    String disp = o.optString("display_name", "");
                    String title = !name.isEmpty() ? name : firstPart(disp);
                    String label = title;
                    if (a != null) {
                        String city = first(a, "city", "town", "municipality", "county", "state_district", "state");
                        String country = a.optString("country", "");
                        label = join(title, city.equals(title) ? "" : city, country);
                    }
                    out.add(new Place(title, disp, label));
                }
            } catch (Exception e) {
                Log.w(TAG, "search failed", e);
            }
            final List<Place> res = out;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || my != seq) return;
                if (res.isEmpty()) showStatus("কিছু পাওয়া যায়নি");
                else showPlaces(res);
            });
        }).start();
    }

    // ---------------------------------------------------------
    // হেল্পার
    // ---------------------------------------------------------
    private String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", UA);   // Nominatim-এর নিয়ম
            InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return bo.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private static String first(JSONObject a, String... keys) {
        for (String k : keys) {
            String v = a.optString(k, "");
            if (!v.isEmpty() && !"null".equals(v)) return v;
        }
        return "";
    }

    private static String firstPart(String disp) {
        int i = disp.indexOf(',');
        return i > 0 ? disp.substring(0, i).trim() : disp;
    }

    /** খালি অংশ বাদ দিয়ে কমা দিয়ে জোড়া */
    private static String join(String a, String b, String c) {
        StringBuilder sb = new StringBuilder();
        for (String s : new String[]{a, b, c}) {
            if (s == null || s.isEmpty()) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(s);
        }
        return sb.toString();
    }

    private void showStatus(String msg) {
        places.clear();
        adapter.notifyDataSetChanged();
        tvStatus.setText(msg);
        tvStatus.setVisibility(View.VISIBLE);
    }

    private void showPlaces(List<Place> list) {
        tvStatus.setVisibility(View.GONE);
        places.clear();
        places.addAll(list);
        adapter.notifyDataSetChanged();
    }

    private void returnLocation(String label) {
        Intent d = new Intent();
        d.putExtra(EXTRA_LOCATION, label);
        setResult(RESULT_OK, d);
        finish();
    }

    private int dp(int v) {
        return Math.round(v * density);
    }

    // ---------------------------------------------------------
    // লিস্ট (row কোডে বানানো)
    // ---------------------------------------------------------
    private class PlaceAdapter extends RecyclerView.Adapter<VH> {
        @Override
        public VH onCreateViewHolder(ViewGroup parent, int viewType) {
            Context ctx = parent.getContext();
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(10), dp(16), dp(10));
            row.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            ImageView icon = new ImageView(ctx);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(48), dp(48));
            icon.setLayoutParams(ilp);
            icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            icon.setPadding(dp(12), dp(12), dp(12), dp(12));
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(0xFFE4E6EB);
            icon.setBackground(g);
            icon.setImageResource(R.drawable.ic_pin_black);
            row.addView(icon);

            LinearLayout texts = new LinearLayout(ctx);
            texts.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tlp.setMarginStart(dp(12));
            texts.setLayoutParams(tlp);

            TextView title = new TextView(ctx);
            title.setTextSize(17);
            title.setTextColor(Color.BLACK);
            title.setTypeface(null, Typeface.BOLD);
            TextView sub = new TextView(ctx);
            sub.setTextSize(14);
            sub.setTextColor(0xFF65676B);
            sub.setMaxLines(2);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(title);
            texts.addView(sub);
            row.addView(texts);
            return new VH(row, title, sub);
        }

        @Override
        public void onBindViewHolder(VH h, int position) {
            final Place p = places.get(position);
            h.title.setText(p.title);
            h.sub.setText(p.sub);
            h.sub.setVisibility(TextUtils.isEmpty(p.sub) || p.sub.equals(p.title) ? View.GONE : View.VISIBLE);
            h.itemView.setOnClickListener(v -> returnLocation(p.label));
        }

        @Override
        public int getItemCount() {
            return places.size();
        }
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView title, sub;
        VH(View v, TextView title, TextView sub) {
            super(v);
            this.title = title;
            this.sub = sub;
        }
    }
}
