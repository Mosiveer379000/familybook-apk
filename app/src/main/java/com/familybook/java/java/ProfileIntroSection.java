package com.familybook.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Profile info পেজের "Intro" সেকশন।
 * Intro সারিতে চাপলে নিচে খোলে: Bio (Realtime প্রোফাইলের বায়ো + এডিট আইকন) ও Pinned details (Demo)।
 * এডিট আইকনে চাপলে BioEditActivity খোলে; Save দিলে এই পেজে ফিরে এসে সবুজ "Bio updated!" দেখায়।
 *
 * ব্যবহার (ProfileInfoEditActivity.onCreate-এর শেষে, setContentView-এর পরে):
 *   new ProfileIntroSection(this, findViewById(R.id.rowIntro),
 *           getIntent().getStringExtra("uid")).attach();
 */
public class ProfileIntroSection {

    private final ComponentActivity act;
    private final View rowIntro;
    private final String uid;
    private final boolean isMe;

    private String currentBio = "";
    private String currentPrivacy = "public";
    private boolean open = false;

    private View section;
    private ImageView arrow;
    private TextView tvBioText, tvBioUpdated;

    private final ActivityResultLauncher<Intent> launcher;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hideTag = new Runnable() {
        @Override public void run() {
            if (tvBioUpdated != null) tvBioUpdated.setVisibility(View.GONE);
        }
    };

    // অবশ্যই Activity-র onCreate-এর ভেতরে বানাতে হবে (launcher রেজিস্টার হয় এখানে)
    public ProfileIntroSection(ComponentActivity act, View rowIntro, String uid) {
        this.act = act;
        this.rowIntro = rowIntro;
        String me = Me.uid();
        this.uid = uid != null ? uid : me;
        this.isMe = this.uid != null && this.uid.equals(me);

        this.launcher = act.registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), r -> {
            if (r.getResultCode() == Activity.RESULT_OK && r.getData() != null) {
                String b = r.getData().getStringExtra("bio");
                String p = r.getData().getStringExtra("privacy");
                currentBio = b == null ? "" : b;
                if (p != null) currentPrivacy = p;
                renderBio();
                showUpdatedTag();
            }
        });
    }

    public void attach() {
        ViewGroup parent = (ViewGroup) rowIntro.getParent();
        int idx = parent.indexOfChild(rowIntro);
        section = LayoutInflater.from(act).inflate(R.layout.layout_profile_intro_section, parent, false);
        section.setVisibility(View.GONE);
        parent.addView(section, idx + 1);

        tvBioText = section.findViewById(R.id.tvBioText);
        tvBioUpdated = act.findViewById(R.id.tvBioUpdated);
        if (tvBioUpdated != null) {
            tvBioUpdated.setBackground(rounded(Color.parseColor("#2E7D32"), 8));
        }

        // সারির ডান পাশের তীর (সারির শেষ ImageView)
        arrow = lastImageView(rowIntro);

        // Intro সারির যেকোনো জায়গায় চাপলে খুলবে/বন্ধ হবে
        bindClick(rowIntro, v -> toggle());

        View btnEdit = section.findViewById(R.id.btnEditBio);
        btnEdit.setVisibility(isMe ? View.VISIBLE : View.GONE);
        btnEdit.setOnClickListener(v -> launcher.launch(
                new Intent(act, BioEditActivity.class)
                        .putExtra("uid", uid)
                        .putExtra("bio", currentBio)
                        .putExtra("privacy", currentPrivacy)));

        section.findViewById(R.id.rowPinned).setOnClickListener(v -> {
            if (!isMe) return;
            act.startActivity(new Intent(act, PinnedDetailsActivity.class));
        });

        renderBio();
        loadBio();
    }

    private void toggle() {
        open = !open;
        section.setVisibility(open ? View.VISIBLE : View.GONE);
        if (arrow != null) arrow.animate().rotation(open ? 180f : 0f).setDuration(150).start();
    }

    // ---- Realtime প্রোফাইল থেকে বায়ো লোড ----
    private void loadBio() {
        // প্রাইভেসি (Public/Friends/Only me) সার্ভারের RLS দিয়েই কাজ করে:
        // দেখার অনুমতি না থাকলে সারি ফেরত আসে না, তাই বায়ো ফাঁকা দেখায়।
        SupabaseRest.call("GET",
                "/rest/v1/profile_bios?user_id=eq." + this.uid + "&select=bio,bio_privacy&limit=1",
                null, null, true, r -> {
            if (act.isFinishing() || !r.ok()) return;
            JSONObject row = null;
            try {
                JSONArray a = new JSONArray(r.body);
                if (a.length() > 0) row = a.optJSONObject(0);
            } catch (Exception ignored) { }
            if (row == null) {
                currentBio = "";
                currentPrivacy = "public";
            } else {
                currentBio = row.isNull("bio") ? "" : row.optString("bio", "");
                String p = row.isNull("bio_privacy") ? "public" : row.optString("bio_privacy", "public");
                currentPrivacy = p.isEmpty() ? "public" : p;
            }
            renderBio();
        });
    }

    private void renderBio() {
        if (tvBioText == null) return;
        if (currentBio != null && !currentBio.trim().isEmpty()) {
            tvBioText.setText(currentBio);
            tvBioText.setTextColor(Color.parseColor("#1C1E21"));
        } else {
            tvBioText.setText(isMe ? "Add bio" : "");
            tvBioText.setTextColor(Color.parseColor("#65676B"));
        }
    }

    // ---- সবুজ "Bio updated!" ----
    private void showUpdatedTag() {
        if (tvBioUpdated == null) {
            Toast.makeText(act, "Bio updated!", Toast.LENGTH_SHORT).show();
            return;
        }
        handler.removeCallbacks(hideTag);
        tvBioUpdated.setVisibility(View.VISIBLE);
        handler.postDelayed(hideTag, 2500);
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

    private GradientDrawable rounded(int fill, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radiusDp * act.getResources().getDisplayMetrics().density);
        return g;
    }

    public void destroy() {
        handler.removeCallbacks(hideTag);
    }
}
