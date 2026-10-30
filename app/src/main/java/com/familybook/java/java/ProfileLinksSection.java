package com.familybook.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/**
 * Profile info পেজের "Links" সেকশন।
 * - Links সারিতে চাপলে খোলে/বন্ধ হয় (তীর ঘোরে)
 * - কিছু সেভ না থাকলে: লিংক আইকন + ধূসর "Websites, blogs, portfolios"; চাপলে ফর্ম (LinksEditActivity) খোলে
 * - সেভ থাকলে: প্রতিটি লিংক (শিরোনাম বোল্ড, নিচে নীল URL), পাশে পেন্সিল; লেখায় চাপলে ব্রাউজারে খোলে;
 *   শেষে "Add another link"
 * - ডাটা: profiles.info.links = [{url, title}]
 *
 * ব্যবহার (ProfileInfoEditActivity.onCreate-এ):
 *   new ProfileLinksSection(this, findViewById(R.id.rowLinks), uid).attach();
 */
public class ProfileLinksSection {

    private static final int MAX_LINKS = 10;

    private final ComponentActivity act;
    private final View rowLinks;
    private final String uid;
    private final boolean isMe;

    private final ArrayList<String[]> links = new ArrayList<>(); // {url, title}
    private boolean loaded = false;
    private boolean open = false;

    private View section;
    private LinearLayout items;
    private ImageView arrow;

    private final ActivityResultLauncher<Intent> launcher;

    // অবশ্যই Activity-র onCreate-এর ভেতরে বানাতে হবে
    public ProfileLinksSection(ComponentActivity act, View rowLinks, String uid) {
        this.act = act;
        this.rowLinks = rowLinks;
        String me = Me.uid();
        this.uid = uid != null ? uid : me;
        this.isMe = this.uid != null && this.uid.equals(me);

        this.launcher = act.registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), r -> {
            if (r.getResultCode() == Activity.RESULT_OK) {
                open = true;
                load();
            }
        });
    }

    public void attach() {
        ViewGroup parent = (ViewGroup) rowLinks.getParent();
        int idx = parent.indexOfChild(rowLinks);
        section = LayoutInflater.from(act).inflate(R.layout.layout_profile_links_section, parent, false);
        section.setVisibility(View.GONE);
        parent.addView(section, idx + 1);
        items = section.findViewById(R.id.llLinkItems);
        arrow = lastImageView(rowLinks);

        bindClick(rowLinks, v -> onRowTap());
        load();
    }

    private void onRowTap() {
        if (!loaded) {
            TopToast.show(act, "Loading, please wait");
            load();
            return;
        }
        open = !open;
        render();
    }

    private void openEditor(int index) {
        if (!isMe) return;
        Intent i = new Intent(act, LinksEditActivity.class).putExtra("index", index);
        if (index >= 0 && index < links.size()) {
            i.putExtra("url", links.get(index)[0]).putExtra("title", links.get(index)[1]);
        }
        launcher.launch(i);
    }

    // ---- সার্ভার থেকে লোড ----
    private void load() {
        Db.get("profiles", "id", uid, (row, err) -> {
            if (act.isFinishing()) return;
            if (err != null) {
                TopToast.show(act, err);
                return;
            }
            links.clear();
            JSONObject info = row != null ? row.optJSONObject("info") : null;
            JSONArray a = info != null ? info.optJSONArray("links") : null;
            if (a != null) {
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o == null) continue;
                    String url = o.optString("url", "").trim();
                    if (url.isEmpty()) continue;
                    links.add(new String[]{url, o.optString("title", "").trim()});
                }
            }
            loaded = true;
            render();
        });
    }

    // ---- আঁকা ----
    private void render() {
        items.removeAllViews();
        LayoutInflater inf = LayoutInflater.from(act);

        if (links.isEmpty()) {
            // খালি অবস্থা: আইকন + "Websites, blogs, portfolios"
            View row = inf.inflate(R.layout.item_profile_link_row, items, false);
            ((TextView) row.findViewById(R.id.tvLinkTitle)).setText("Websites, blogs, portfolios");
            if (isMe) row.setOnClickListener(v -> openEditor(-1));
            items.addView(row);
        } else {
            for (int i = 0; i < links.size(); i++) {
                final int idx = i;
                final String url = links.get(i)[0];
                final String title = links.get(i)[1];

                View row = inf.inflate(R.layout.item_profile_link_row, items, false);
                TextView tvTitle = row.findViewById(R.id.tvLinkTitle);
                TextView tvUrl = row.findViewById(R.id.tvLinkUrl);
                tvTitle.setText(title.isEmpty() ? url : title);
                tvTitle.setTextColor(0xFF050505);
                if (!title.isEmpty()) {
                    tvUrl.setText(url);
                    tvUrl.setVisibility(View.VISIBLE);
                }
                row.findViewById(R.id.llLinkText).setOnClickListener(v -> openUrl(url));

                View edit = row.findViewById(R.id.btnEditLink);
                edit.setVisibility(isMe ? View.VISIBLE : View.GONE);
                edit.setOnClickListener(v -> openEditor(idx));
                items.addView(row);
            }
            if (isMe && links.size() < MAX_LINKS) {
                View add = inf.inflate(R.layout.item_profile_link_row, items, false);
                TextView t = add.findViewById(R.id.tvLinkTitle);
                t.setText("Add another link");
                t.setTextColor(0xFF1877F2);
                add.setOnClickListener(v -> openEditor(-1));
                items.addView(add);
            }
        }

        section.setVisibility(open ? View.VISIBLE : View.GONE);
        if (arrow != null) arrow.animate().rotation(open ? 180f : 0f).setDuration(150).start();
    }

    private void openUrl(String url) {
        try {
            act.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            TopToast.show(act, "Can't open this link");
        }
    }

    // ---- helpers ----
    private void bindClick(View v, View.OnClickListener l) {
        v.setOnClickListener(l);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) bindClick(g.getChildAt(i), l);
        }
    }

    private ImageView lastImageView(View v) {
        ImageView found = null;
        if (v instanceof ImageView) found = (ImageView) v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                ImageView c = lastImageView(g.getChildAt(i));
                if (c != null) found = c;
            }
        }
        return found;
    }
}
