package com.familybook.app;

import android.app.AlertDialog;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Social media ফর্ম।
 *  - "+ Add": প্ল্যাটফর্ম বেছে username/লিংক দিলে তালিকায় যোগ হয়
 *  - "Accounts you can add": Profile Links-এ থাকা চেনা সোশ্যাল লিংক থেকে বানানো প্রস্তাব; + চাপলে যোগ হয়
 *  - "Who can see this?": Public / Friends / Only me (ডিফল্ট Public)
 *  - Save → profiles.info.contact.social ও social_priv
 */
public class ContactSocialActivity extends BaseActivity {

    private static final int MAX = 10;
    private static final String[] PLATFORMS = {"Instagram", "Facebook", "X (Twitter)", "YouTube",
            "TikTok", "LinkedIn", "WhatsApp", "Telegram", "Other"};

    private final ArrayList<String[]> added = new ArrayList<>();       // {platform, handle, url}
    private final ArrayList<String[]> suggestions = new ArrayList<>(); // {platform, handle, url}
    private String priv = ContactStore.PUBLIC;
    private boolean saving = false;

    private LinearLayout llAdded, llSuggest;
    private TextView tvEmpty, tvSuggestHead, tvWho, btnSave;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Me.isLoggedIn() || Me.uid() == null) { finish(); return; }
        setContentView(R.layout.activity_contact_social);

        llAdded = findViewById(R.id.llCtAdded);
        llSuggest = findViewById(R.id.llCtSuggest);
        tvEmpty = findViewById(R.id.tvCtEmpty);
        tvSuggestHead = findViewById(R.id.tvCtSuggestHead);
        tvWho = findViewById(R.id.tvCtWho);
        btnSave = findViewById(R.id.btnCtSave);

        findViewById(R.id.btnCtClose).setOnClickListener(v -> finish());
        findViewById(R.id.btnCtAdd).setOnClickListener(v -> showAddDialog());
        findViewById(R.id.rowCtWho).setOnClickListener(v ->
                ContactStore.choose(this, priv, p -> { priv = p; tvWho.setText(ContactStore.label(priv)); }));
        btnSave.setOnClickListener(v -> save());
        tvWho.setText(ContactStore.label(priv));
        render();

        ContactStore.load(Me.uid(), (c, links, err) -> {
            if (isFinishing()) return;
            JSONArray a = c.optJSONArray("social");
            added.clear();
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                String[] en = ContactStore.entry(o);
                if (en != null) added.add(en);
            }
            priv = ContactStore.norm(c.optString("social_priv", ""), ContactStore.PUBLIC);
            tvWho.setText(ContactStore.label(priv));
            buildSuggestions(links);
            render();
        });
    }

    // ---- প্রস্তাব: Profile Links থেকে চেনা সোশ্যাল সাইট ----
    private void buildSuggestions(JSONArray links) {
        suggestions.clear();
        for (int i = 0; links != null && i < links.length(); i++) {
            JSONObject o = links.optJSONObject(i);
            if (o == null) continue;
            String url = o.optString("url", "").trim();
            String[] ph = ContactStore.parseSocial(url);
            if (ph != null) suggestions.add(ph);
        }
    }

    private boolean has(String url) {
        for (String[] a : added) if (a[2].equalsIgnoreCase(url)) return true;
        return false;
    }

    // ---- আঁকা ----
    private void render() {
        ((TextView) findViewById(R.id.tvCtAddLabel)).setText(added.isEmpty() ? "Add" : "Add more");
        llAdded.removeAllViews();
        tvEmpty.setVisibility(added.isEmpty() ? View.VISIBLE : View.GONE);
        LayoutInflater inf = LayoutInflater.from(this);
        for (int i = 0; i < added.size(); i++) {
            final int idx = i;
            View row = socialRow(added.get(i)[0], ContactStore.display(added.get(i)[0], added.get(i)[1]), R.drawable.ic_ct_close, 10);
            row.findViewById(R.id.ctRowAction).setOnClickListener(v -> { added.remove(idx); render(); });
            llAdded.addView(row);
        }

        llSuggest.removeAllViews();
        int shown = 0;
        for (String[] s : suggestions) {
            if (has(s[2])) continue;
            final String[] sg = s;
            View row = socialRow(s[0], ContactStore.display(s[0], s[1]), R.drawable.ic_ct_plus, 8);
            View.OnClickListener add = v -> addItem(sg[0], sg[1], sg[2]);
            row.setOnClickListener(add);
            row.findViewById(R.id.ctRowAction).setOnClickListener(add);
            llSuggest.addView(row);
            shown++;
        }
        tvSuggestHead.setVisibility(shown > 0 ? View.VISIBLE : View.GONE);
    }

    private void addItem(String platform, String handle, String url) {
        if (added.size() >= MAX) { TopToast.show(this, "You can add up to " + MAX + " accounts"); return; }
        if (url == null || url.isEmpty()) { TopToast.show(this, "Enter a valid username or link"); return; }
        if (has(url)) { TopToast.show(this, "Already added"); return; }
        added.add(new String[]{platform, handle, url});
        render();
    }

    /** আইকন + নাম (বোল্ড) + প্ল্যাটফর্ম (ছোট) + ডানে অ্যাকশন আইকন */
    private View socialRow(String platform, String handle, int actionIcon, int actionPad) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));

        ImageView ic = new ImageView(this);
        ic.setImageResource(platform.equals("Instagram") ? R.drawable.ic_ct_instagram : R.drawable.ic_link_item);
        row.addView(ic, new LinearLayout.LayoutParams(dp(28), dp(28)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(handle);
        t.setTextSize(16);
        t.setTextColor(0xFF050505);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(t);
        TextView s = new TextView(this);
        s.setText(platform);
        s.setTextSize(13);
        s.setTextColor(0xFF65676B);
        col.addView(s);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cp.setMarginStart(dp(14));
        cp.setMarginEnd(dp(8));
        row.addView(col, cp);

        ImageView act = new ImageView(this);
        act.setId(R.id.ctRowAction);
        act.setImageResource(actionIcon);
        act.setPadding(dp(actionPad), dp(actionPad), dp(actionPad), dp(actionPad));
        row.addView(act, new LinearLayout.LayoutParams(dp(36), dp(36)));
        return row;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    // ---- নতুন যোগ ----
    private void showAddDialog() {
        if (added.size() >= MAX) { TopToast.show(this, "You can add up to " + MAX + " accounts"); return; }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), dp(0));
        final Spinner sp = new Spinner(this);
        sp.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, PLATFORMS));
        box.addView(sp);
        final EditText et = new EditText(this);
        et.setHint("Username or link");
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        box.addView(et);
        new AlertDialog.Builder(this).setTitle("Add social media").setView(box)
                .setPositiveButton("Add", (d, w) -> {
                    String platform = PLATFORMS[sp.getSelectedItemPosition()];
                    String input = et.getText().toString().trim();
                    if (input.isEmpty()) { TopToast.show(this, "Enter a username or link"); return; }
                    String handle = ContactStore.cleanHandle(input);
                    String url;
                    if (input.matches("(?i)^[a-z][a-z0-9+.-]*://.*") || input.contains("/")) {
                        // লিংক দিলে সেটাই রাখি (https না থাকলে বসাই); প্ল্যাটফর্ম চেনা গেলে সেটা নিই
                        String[] ph = ContactStore.parseSocial(input);
                        url = ph != null ? ph[2] : (input.matches("(?i)^[a-z][a-z0-9+.-]*://.*") ? input : "https://" + input);
                        if (ph != null && platform.equals("Other")) platform = ph[0];
                    } else {
                        url = ContactStore.buildUrl(platform, handle);
                    }
                    addItem(platform, handle, url);
                })
                .setNegativeButton("Cancel", null).show();
    }

    // ---- সেভ ----
    private void save() {
        if (saving) return;
        saving = true;
        btnSave.setAlpha(0.6f);
        try {
            JSONArray arr = new JSONArray();
            for (String[] a : added) arr.put(new JSONObject().put("platform", a[0]).put("handle", a[1]).put("url", a[2]));
            JSONObject ch = new JSONObject().put("social", arr).put("social_priv", priv);
            ContactStore.update(ch, e -> {
                if (isFinishing()) return;
                saving = false;
                btnSave.setAlpha(1f);
                if (e != null) { TopToast.show(this, e); return; }
                TopToast.show(this, "Saved!");
                setResult(RESULT_OK);
                finish();
            });
        } catch (Exception ex) {
            saving = false;
            btnSave.setAlpha(1f);
            TopToast.show(this, "Could not save");
        }
    }
}
