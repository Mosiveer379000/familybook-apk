package com.familybook.app;

import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

/**
 * Bottom navigation-এর ডানের Profile আইকনে চাপলে প্রথমে এই Menu খোলে।
 * প্রোফাইল দেখতে উপরের প্রোফাইল কার্ডে চাপুন; LogOut এখান থেকেই।
 * কার্ডের "+" বাটনে চাপলে: New page (ডেমো) + অন্য অ্যাকাউন্টে লগইন।
 */
public class TreedotActivity extends BaseActivity {

    private static final String FAMILY_CHAT_PKG = "com.familychat.app";
    private static final String DEFAULT_NAME = "Family Member";

    private String myUid;
    private String myName = DEFAULT_NAME;
    private TextView tvName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        myUid = SupabaseRest.uid();
        if (!SupabaseRest.isLoggedIn() || myUid == null) {
            goLogin();
            return;
        }

        setContentView(R.layout.activity_treedot);

        // নিচের নেভিগেশন বার (Menu স্ক্রিনেও থাকবে)
        BottomNav.attach(this);

        // নিজের নাম ও ছবি
        tvName = findViewById(R.id.tvMenuName);
        ImageView avatar = findViewById(R.id.imgMenuAvatar);
        AvatarLoader.load(avatar, myUid);
        loadMyName();

        // প্রোফাইল কার্ডে চাপলে প্রোফাইল খুলবে
        findViewById(R.id.cardProfile).setOnClickListener(v ->
                ProfileActivity.open(this, myUid, myName));

        // "+" : New page (ডেমো) ও অন্য অ্যাকাউন্টে লগইন
        findViewById(R.id.btnAddAccount).setOnClickListener(v -> showAccountsSheet());

        // উপরের সার্চ আইকন
        findViewById(R.id.btnMenuSearch).setOnClickListener(v ->
                startActivity(new Intent(this, SearchActivity.class)));

        // সার্চ বারের ডান পাশের সেটিং আইকন (btnMenuManage) - এখানে SettingsActivity যুক্ত করা হলো
        findViewById(R.id.btnMenuManage).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class))); // স্যার, এখানে পরিবর্তন করা হয়েছে

        // মেনু আইটেম
        setupMenuItem(R.id.menu_followers, R.drawable.ic_tm_followers, "Followers",
                () -> startActivity(new Intent(this, MembersActivity.class)));
        setupMenuItem(R.id.menu_dashboard, R.drawable.ic_tm_dashboard, "Dashboard",
                () -> soon("Dashboard"));
        setupMenuItem(R.id.menu_family_chat, R.drawable.ic_tm_familychat, "FamilyChat",
                this::openFamilyChat);
        setupMenuItem(R.id.menu_groups, R.drawable.ic_tm_groups, "Groups",
                () -> startActivity(new Intent(this, GroupsActivity.class)));
        setupMenuItem(R.id.menu_members, R.drawable.ic_tm_members, "Members",
                () -> startActivity(new Intent(this, MembersActivity.class)));
        setupMenuItem(R.id.menu_posts, R.drawable.ic_tm_posts, "Posts",
                () -> startActivity(new Intent(this, MyPostsActivity.class)));
        setupMenuItem(R.id.menu_shorts, R.drawable.ic_tm_shorts, "Shorts",
                () -> ShortViewerActivity.openFeed(this));
        setupMenuItem(R.id.menu_live_voice, R.drawable.ic_tm_livevoice, "LiveVoice",
                () -> soon("LiveVoice"));
        setupMenuItem(R.id.menu_terms, R.drawable.ic_tm_terms, "Terms & Conditions",
                () -> soon("Terms & Conditions"));

        // মেনু তালিকার সেটিং আইটেম - এখানেও SettingsActivity যুক্ত করা হলো
        setupMenuItem(R.id.menu_settings, R.drawable.ic_tm_settings, "Settings and privacy",
                () -> startActivity(new Intent(this, SettingsActivity.class))); // স্যার, এখানেও পরিবর্তন করা হয়েছে

        // Logout (লাল বাটন)
        findViewById(R.id.btnLogout).setOnClickListener(v -> confirmLogout());
    }

    private void setupMenuItem(int includeId, int iconResId, String titleText, Runnable action) {
        View rowView = findViewById(includeId);
        if (rowView == null) return;
        ImageView imgIcon = rowView.findViewById(R.id.imgIcon);
        TextView txtTitle = rowView.findViewById(R.id.txtTitle);

        imgIcon.setImageResource(iconResId);
        txtTitle.setText(titleText);
        rowView.setOnClickListener(v -> action.run());
    }

    private void loadMyName() {
        PostOps.loadMyName(myUid, n -> {
            myName = n;
            tvName.setText(n);
        });
    }

    // ---------- "+" শিট: New page + অ্যাকাউন্ট ----------

    private void showAccountsSheet() {
        final Dialog dlg = new Dialog(this);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        View sheet = getLayoutInflater().inflate(R.layout.sheet_accounts, null);
        dlg.setContentView(sheet);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            w.setDimAmount(0.45f);
        }

        // New page — আপাতত ডেমো
        sheet.findViewById(R.id.rowNewPage).setOnClickListener(v -> {
            dlg.dismiss();
            soon("New page");
        });

        // এখনকার অ্যাকাউন্ট (টিক চিহ্ন সহ)
        LinearLayout list = sheet.findViewById(R.id.accountsList);
        addCurrentAccountRow(list);

        sheet.findViewById(R.id.rowAddAccount).setOnClickListener(v -> {
            dlg.dismiss();
            addAnotherAccount();
        });

        dlg.show();
    }

    private void addCurrentAccountRow(LinearLayout list) {
        final View row = getLayoutInflater().inflate(R.layout.item_account_row, list, false);
        ImageView img = row.findViewById(R.id.imgAcc);
        TextView tvN = row.findViewById(R.id.tvAccName);
        TextView tvS = row.findViewById(R.id.tvAccSub);
        ImageView state = row.findViewById(R.id.imgAccState);

        AvatarLoader.load(img, myUid);
        tvN.setText(myName);
        tvS.setText("Logged in now");
        state.setImageResource(R.drawable.ic_acc_check);
        row.setClickable(false);
        list.addView(row);
    }

    private void addAnotherAccount() {
        new AlertDialog.Builder(this)
                .setTitle("Add another account")
                .setMessage("এই অ্যাকাউন্টের কার্ড সেভ থাকবে এবং আপনি লগইন পেজে যাবেন। চালিয়ে যাবেন?")
                .setPositiveButton("Continue", (d, w) -> {
                    saveCardNow();
                    logoutAndGoLogin(true);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---------- Logout ----------
    private void confirmLogout() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(64, 24, 64, 0);

        TextView msg = new TextView(this);
        msg.setText("Are you sure you want to log out?");
        msg.setTextColor(Color.parseColor("#1C1E21"));
        msg.setTextSize(16);
        box.addView(msg);

        final CheckBox save = new CheckBox(this);
        save.setText("Save login information");
        save.setTextColor(Color.parseColor("#1C1E21"));
        save.setChecked(true);
        box.addView(save);

        TextView hint = new TextView(this);
        hint.setText("Next time just tap your profile on the login page to sign in.");
        hint.setTextColor(Color.parseColor("#65676B"));
        hint.setTextSize(13);
        box.addView(hint);

        new AlertDialog.Builder(this)
                .setTitle("Log out")
                .setView(box)
                .setPositiveButton("Log out", (d, w) -> {
                    if (save.isChecked()) {
                        saveCardNow();
                    } else {
                        SecureStore.discardLogin(this, SupabaseRest.uid());
                    }
                    logoutAndGoLogin(false);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** এই অ্যাকাউন্টের কার্ড সেভ (Remember me ছিল হলে পাসওয়ার্ডসহ) */
    private void saveCardNow() {
        boolean ok = SecureStore.commitCard(this, SupabaseRest.uid(), SupabaseRest.email(), myName);
        if (!ok) Toast.makeText(this, "Login information could not be saved", Toast.LENGTH_LONG).show();
    }

    private void logoutAndGoLogin(final boolean addAccount) {
        AuthRepo.logout(error -> goLogin(addAccount));
    }

    private void openFamilyChat() {
        Intent intent = getPackageManager().getLaunchIntentForPackage(FAMILY_CHAT_PKG);
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } else {
            Toast.makeText(this, "Family Chat অ্যাপ ইনস্টল করা নেই", Toast.LENGTH_SHORT).show();
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("market://details?id=" + FAMILY_CHAT_PKG)));
            } catch (ActivityNotFoundException ignored) { }
        }
    }

    private void soon(String what) {
        Toast.makeText(this, what + " — শীঘ্রই আসছে (Demo)", Toast.LENGTH_SHORT).show();
    }

    private void goLogin() { goLogin(false); }

    private void goLogin(boolean addAccount) {
        Intent i = new Intent(this, LoginActivity.class);
        if (addAccount) i.putExtra("add_account", true);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }
}
