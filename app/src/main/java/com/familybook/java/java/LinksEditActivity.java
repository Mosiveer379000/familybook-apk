package com.familybook.app;

import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Patterns;
import android.widget.EditText;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Links ফর্ম পেজ।
 * Intent extra: "index" (-1 = নতুন লিংক), "url", "title"
 *
 * - Link ফাঁকা থাকলে Next ধূসর। লিখলেই নীল বাটন।
 * - Link ফাঁকা রেখে Next চাপলে বা লিখে মুছে দিলে লাল "Required" ওয়ার্নিং।
 * - Save → profiles.info.links = [{url, title}, ...]; সফল হলে RESULT_OK দিয়ে ফেরে।
 */
public class LinksEditActivity extends BaseActivity {

    private static final int MAX_LINKS = 10;

    private EditText etLink, etTitle;
    private TextView tvRequired, tvCount, btnNext, btnDelete;
    private int index = -1;
    private boolean touched = false;   // ইউজার Link ঘরে কিছু করেছে কি না
    private boolean saving = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Me.isLoggedIn() || Me.uid() == null) {
            finish();
            return;
        }
        setContentView(R.layout.activity_links_edit);

        etLink = findViewById(R.id.etLink);
        etTitle = findViewById(R.id.etLinkTitle);
        tvRequired = findViewById(R.id.tvLinkRequired);
        tvCount = findViewById(R.id.tvTitleCount);
        btnNext = findViewById(R.id.btnNext);
        btnDelete = findViewById(R.id.btnDeleteLink);

        index = getIntent().getIntExtra("index", -1);
        String url = getIntent().getStringExtra("url");
        String title = getIntent().getStringExtra("title");
        if (url != null) etLink.setText(url);
        if (title != null) etTitle.setText(title);
        tvCount.setText(etTitle.getText().length() + "/150");

        etLink.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                if (s.toString().trim().isEmpty()) {
                    if (touched) showError("Required");
                } else {
                    clearError();
                }
                updateNext();
                touched = true;
            }
        });
        etLink.setOnFocusChangeListener((v, has) -> {
            if (!has && etLink.getText().toString().trim().isEmpty() && touched) showError("Required");
        });

        etTitle.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                tvCount.setText(s.length() + "/150");
            }
        });

        if (index >= 0) {
            btnDelete.setVisibility(android.view.View.VISIBLE);
            btnDelete.setOnClickListener(v -> delete());
        }

        findViewById(R.id.btnBackLink).setOnClickListener(v -> finish());
        btnNext.setOnClickListener(v -> next());
        updateNext();
    }

    /** Link লেখা থাকলে নীল বাটন, না থাকলে ধূসর লেখা */
    private void updateNext() {
        boolean has = !etLink.getText().toString().trim().isEmpty();
        if (has) {
            btnNext.setBackgroundResource(R.drawable.bg_pi_save_btn);
            btnNext.setTextColor(Color.WHITE);
            btnNext.setTypeface(null, android.graphics.Typeface.BOLD);
        } else {
            btnNext.setBackground(null);
            btnNext.setTextColor(Color.parseColor("#B0B3B8"));
            btnNext.setTypeface(null, android.graphics.Typeface.NORMAL);
        }
    }

    private void showError(String msg) {
        etLink.setBackgroundResource(R.drawable.bg_link_input_error);
        tvRequired.setText(msg);
        tvRequired.setTextColor(Color.parseColor("#E41E3F"));
    }

    private void clearError() {
        etLink.setBackgroundResource(R.drawable.bg_link_input);
        tvRequired.setText("Required");
        tvRequired.setTextColor(Color.parseColor("#65676B"));
    }

    /** https:// না থাকলে বসায়; ভুল হলে null */
    private String normalize(String raw) {
        String u = raw.trim();
        if (u.isEmpty() || u.contains(" ")) return null;
        if (!u.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) u = "https://" + u;
        String low = u.toLowerCase();
        if (!low.startsWith("http://") && !low.startsWith("https://")) return null;
        return Patterns.WEB_URL.matcher(u).matches() ? u : null;
    }

    private void next() {
        if (saving) return;
        String raw = etLink.getText().toString();
        if (raw.trim().isEmpty()) {
            touched = true;
            showError("Required");
            etLink.requestFocus();
            return;
        }
        String url = normalize(raw);
        if (url == null) {
            showError("Enter a valid link");
            etLink.requestFocus();
            return;
        }
        String title = etTitle.getText().toString().trim();
        write(url, title, false);
    }

    private void delete() {
        if (saving) return;
        write("", "", true);
    }

    private void write(String url, String title, boolean remove) {
        saving = true;
        btnNext.setAlpha(0.6f);
        Db.getMine("profiles", (p, err) -> {
            if (isFinishing()) return;
            if (err != null) { fail(err); return; }
            try {
                JSONObject info = p != null && p.optJSONObject("info") != null
                        ? p.getJSONObject("info") : new JSONObject();
                JSONArray old = info.optJSONArray("links");
                JSONArray arr = new JSONArray();
                if (old != null) for (int i = 0; i < old.length(); i++) {
                    if (remove && i == index) continue;
                    arr.put(old.get(i));
                }
                if (!remove) {
                    JSONObject o = new JSONObject().put("url", url).put("title", title);
                    if (index >= 0 && index < arr.length()) {
                        arr.put(index, o);
                    } else {
                        if (arr.length() >= MAX_LINKS) { fail("You can add up to " + MAX_LINKS + " links"); return; }
                        arr.put(o);
                    }
                }
                PersonalInfoStore.save(new JSONObject().put("links", arr), null, e -> {
                    if (isFinishing()) return;
                    if (e != null) { fail(e); return; }
                    TopToast.show(this, remove ? "Link deleted" : "Link saved!");
                    setResult(RESULT_OK);
                    finish();
                });
            } catch (Exception ex) {
                fail("Could not save");
            }
        });
    }

    private void fail(String msg) {
        saving = false;
        btnNext.setAlpha(1f);
        TopToast.show(this, msg);
    }
}
