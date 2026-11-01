package com.familybook.app;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Intro > Pinned details।
 * Personal info বাদে যত তথ্য যোগ করা আছে সব এখানে তালিকায় আসে; সর্বোচ্চ ৫টি টিক দিয়ে সিলেক্ট করা যায়।
 * Save দিলে প্রোফাইলে Personal details-এর নিচে দেখায়।
 * লেআউট: res/layout/activity_pinned_details.xml, item_pinned_pick_row.xml
 */
public class PinnedDetailsActivity extends BaseActivity {

    private LinearLayout list;
    private final ArrayList<String> selected = new ArrayList<>();
    private List<PinnedItems.Item> items = new ArrayList<>();
    private boolean saving = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!Me.isLoggedIn() || Me.uid() == null) { finish(); return; }
        setContentView(R.layout.activity_pinned_details);

        list = findViewById(R.id.llList);
        findViewById(R.id.ivClose).setOnClickListener(v -> finish());
        findViewById(R.id.tvSave).setOnClickListener(v -> save());
        load();
    }

    private void load() {
        PinnedItems.load(Me.uid(), true, (all, pinned) -> {
            if (isFinishing()) return;
            items = all;
            selected.clear();
            for (String k : pinned) if (PinnedItems.find(all, k) != null) selected.add(k);
            render();
        });
    }

    private void render() {
        list.removeAllViews();
        if (items.isEmpty()) {
            TextView e = new TextView(this);
            e.setText("Add info (Work, Education, Hobbies...) to your profile first, then pin it here.");
            e.setTextColor(0xFF65676B);
            e.setTextSize(15);
            list.addView(e);
            return;
        }
        LayoutInflater inf = LayoutInflater.from(this);
        for (PinnedItems.Item it : items) {
            View r = inf.inflate(R.layout.item_pinned_pick_row, list, false);
            int id = getResources().getIdentifier(it.icon, "drawable", getPackageName());
            ((ImageView) r.findViewById(R.id.ivIcon)).setImageResource(id != 0 ? id : R.drawable.ic_profile);
            ((TextView) r.findViewById(R.id.tvLabel)).setText(it.label);
            ((TextView) r.findViewById(R.id.tvSummary)).setText(summary(it));
            final TextView box = r.findViewById(R.id.tvBox);
            paintBox(box, selected.contains(it.key));
            r.setOnClickListener(v -> {
                if (selected.contains(it.key)) selected.remove(it.key);
                else if (selected.size() >= PinnedItems.MAX) {
                    TopToast.show(this, "You can pin up to " + PinnedItems.MAX + " details");
                    return;
                } else selected.add(it.key);
                paintBox(box, selected.contains(it.key));
            });
            list.addView(r);
        }
    }

    private String summary(PinnedItems.Item it) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < it.lines.size() && i < 3; i++) {
            if (sb.length() > 0) sb.append(" \u00B7 ");
            sb.append(it.lines.get(i));
        }
        if (it.lines.size() > 3) sb.append(" +").append(it.lines.size() - 3);
        return sb.toString();
    }

    private void paintBox(TextView box, boolean on) {
        box.setBackgroundResource(on ? R.drawable.bg_pinned_box_on : R.drawable.bg_pinned_box_off);
        box.setText(on ? "\u2713" : "");
    }

    private void save() {
        if (saving) return;
        saving = true;
        try {
            JSONArray a = new JSONArray();
            for (String k : selected) a.put(k);
            Db.mergeMyInfo(new JSONObject().put("pinned", a), err -> {
                saving = false;
                if (isFinishing()) return;
                if (err != null) { TopToast.show(this, err); return; }
                setResult(RESULT_OK);
                finish();
            });
        } catch (Exception e) {
            saving = false;
            TopToast.show(this, "Could not save");
        }
    }
}
