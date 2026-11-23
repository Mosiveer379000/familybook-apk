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

    private String myUid;
    private String myName;
    private TextView tvName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        myName = getString(R.string.tm_family_member);
        myUid = SupabaseRest.uid();
        if (!SupabaseRest.isLoggedIn() || myUid == null) {
            goLogin();
            return;
        }

        setContentView(R.layout.activity_treedot);
        applyMenuTheme();   // ডার্ক/লাইট অনুযায়ী উইন্ডো ও সিস্টেম বারের রং

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
        setupMenuItem(R.id.menu_followers, R.drawable.ic_tm_followers, getString(R.string.tm_followers),
                () -> startActivity(new Intent(this, MembersActivity.class)));
        setupMenuItem(R.id.menu_dashboard, R.drawable.ic_tm_dashboard, getString(R.string.tm_dashboard),
                () -> soon(R.string.tm_dashboard));
        setupMenuItem(R.id.menu_family_chat, R.drawable.ic_tm_familychat, getString(R.string.tm_familychat),
                this::openFamilyChat);
        setupMenuItem(R.id.menu_groups, R.drawable.ic_tm_groups, getString(R.string.tm_groups),
                () -> startActivity(new Intent(this, GroupsActivity.class)));
        setupMenuItem(R.id.menu_posts, R.drawable.ic_tm_posts, getString(R.string.tm_posts),
                () -> startActivity(new Intent(this, MyPostsActivity.class)));
        setupMenuItem(R.id.menu_shorts, R.drawable.ic_tm_shorts, getString(R.string.tm_shorts),
                () -> ShortViewerActivity.openFeed(this));
        setupMenuItem(R.id.menu_terms, R.drawable.ic_tm_terms, getString(R.string.tm_terms),
                () -> soon(R.string.tm_terms));

        // নিচের আইটেম: Report a problem (আপাতত ডেমো — চাপলে "coming soon")
        setupMenuItem(R.id.menu_settings, R.drawable.ic_tm_report, getString(R.string.tm_report),
                () -> soon(R.string.tm_report));

        // Logout (লাল বাটন)
        findViewById(R.id.btnLogout).setOnClickListener(v -> confirmLogout());
    }

    private void setupMenuItem(int includeId, int iconResId, String titleText, Runnable action) {
        View rowView = findViewById(includeId);
        if (rowView == null) return;
        ImageView imgIcon = rowView.findViewById(R.id.imgIcon);
        TextView txtTitle = rowView.findViewById(R.id.txtTitle);

        imgIcon.setImageResource(iconResId);
        // আইকনগুলো কালো ছবি: ডার্ক মোডে রং উল্টে সাদা (ভেতরের সাদা অংশ থাকা আইকনও ঠিক থাকে)
        if (isNight()) imgIcon.setColorFilter(invertFilter()); else imgIcon.clearColorFilter();
        txtTitle.setText(titleText);
        rowView.setOnClickListener(v -> action.run());
    }

    private boolean isNight() {
        return (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    private static android.graphics.ColorFilter invertFilter() {
        return new android.graphics.ColorMatrixColorFilter(new float[]{
                -1, 0, 0, 0, 255,
                0, -1, 0, 0, 255,
                0, 0, -1, 0, 255,
                0, 0, 0, 1, 0});
    }

    /** ডার্ক/লাইট/সিস্টেম মোডে উইন্ডো, স্ট্যাটাস বার ও নেভিগেশন-বারের রং মেলায় */
    private void applyMenuTheme() {
        int bg = androidx.core.content.ContextCompat.getColor(this, R.color.h_bg);
        getWindow().setBackgroundDrawable(new ColorDrawable(bg));
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        try {
            androidx.core.view.WindowInsetsControllerCompat c = androidx.core.view.WindowCompat
                    .getInsetsController(getWindow(), getWindow().getDecorView());
            if (c != null) {
                c.setAppearanceLightStatusBars(!isNight());
                c.setAppearanceLightNavigationBars(!isNight());
            }
        } catch (Exception ignored) { }
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
            soon(R.string.tm_new_page);
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
        tvS.setText(R.string.tm_logged_in_now);
        state.setImageResource(R.drawable.ic_acc_check);
        row.setClickable(false);
        list.addView(row);
    }

    private void addAnotherAccount() {
        new AlertDialog.Builder(this, R.style.FbAlertDialog)
                .setTitle(R.string.tm_add_account)
                .setMessage(R.string.tm_add_msg)
                .setPositiveButton(R.string.tm_continue, (d, w) -> {
                    saveCardNow();
                    logoutAndGoLogin(true);
                })
                .setNegativeButton(R.string.tm_cancel, null)
                .show();
    }

    // ---------- Logout ----------
    private void confirmLogout() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(64, 24, 64, 0);

        TextView msg = new TextView(this);
        msg.setText(R.string.tm_logout_msg);
        msg.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.c_text));
        msg.setTextSize(16);
        box.addView(msg);

        final CheckBox save = new CheckBox(this);
        save.setText(R.string.tm_save_login);
        save.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.c_text));
        save.setChecked(true);
        box.addView(save);

        TextView hint = new TextView(this);
        hint.setText(R.string.tm_logout_hint);
        hint.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.c_text_sub));
        hint.setTextSize(13);
        box.addView(hint);

        new AlertDialog.Builder(this, R.style.FbAlertDialog)
                .setTitle(R.string.tm_logout_title)
                .setView(box)
                .setPositiveButton(R.string.tm_logout_title, (d, w) -> {
                    if (save.isChecked()) {
                        saveCardNow();
                    } else {
                        SecureStore.discardLogin(this, SupabaseRest.uid());
                    }
                    logoutAndGoLogin(false);
                })
                .setNegativeButton(R.string.tm_cancel, null)
                .show();
    }

    /** এই অ্যাকাউন্টের কার্ড সেভ (Remember me ছিল হলে পাসওয়ার্ডসহ) */
    private void saveCardNow() {
        boolean ok = SecureStore.commitCard(this, SupabaseRest.uid(), SupabaseRest.email(), myName);
        if (!ok) Toast.makeText(this, R.string.tm_login_not_saved, Toast.LENGTH_LONG).show();
    }

    private void logoutAndGoLogin(final boolean addAccount) {
        AuthRepo.logout(error -> goLogin(addAccount));
    }

    private void openFamilyChat() {
        ChatHandoff.fetch(this, this::openFamilyChatNow);   // Family Chat-এ নিজে থেকে লগইনের কোড আনে
    }

    private void openFamilyChatNow(String handoff) {
        // Family Chat-এর হোম পেজ (কনভারসেশন তালিকা) সরাসরি খোলে
        try {
            Intent home = new Intent(Intent.ACTION_VIEW, Uri.parse("familychat://home"));
            home.setPackage(FAMILY_CHAT_PKG);
            ChatHandoff.putExtras(home, handoff);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(home);
            return;
        } catch (ActivityNotFoundException ignored) { }
        Intent intent = getPackageManager().getLaunchIntentForPackage(FAMILY_CHAT_PKG);
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } else {
            Toast.makeText(this, R.string.tm_chat_missing, Toast.LENGTH_SHORT).show();
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("market://details?id=" + FAMILY_CHAT_PKG)));
            } catch (ActivityNotFoundException ignored) { }
        }
    }

    private void soon(int whatRes) {
        Toast.makeText(this, getString(R.string.tm_coming_soon, getString(whatRes)), Toast.LENGTH_SHORT).show();
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
