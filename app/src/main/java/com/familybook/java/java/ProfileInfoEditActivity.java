package com.familybook.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/**
 * Profile info পেজ (Edit-এর পেন্সিলে চাপলে খোলে)।
 */
public class ProfileInfoEditActivity extends BaseActivity {

    private ProfileIntroSection intro;
    private ProfileCategorySection category;
    private PersonalInfoSection personal;
    private ProfileLinksSection links;
    private ProfileCommunitiesSection communities;
    private ProfileContactSection contactSec;
    private ProfileListSection hobbiesSec, travelSec;
    private ProfileInterestsSection interestsSec;
    private ProfileCareerSection workSec, eduSec;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Me.isLoggedIn() || Me.uid() == null) {
            finish();
            return;
        }

        setContentView(R.layout.activity_profile_info_edit);

        // ব্যাক বাটন
        findViewById(R.id.btnClose).setOnClickListener(v -> finish());

        // ---- সারির টাইটেল ----
        setRowTitle(R.id.rowIntro, "Intro");
        setRowTitle(R.id.rowCategory, "Category");
        setRowTitle(R.id.rowPersonalDetails, "Personal info");

        setRowTitle(R.id.rowLinks, "Links");
        setRowTitle(R.id.rowCommunities, "Communities");
        setRowTitle(R.id.rowWork, "Work");
        setRowTitle(R.id.rowEducation, "Education");
        setRowTitle(R.id.rowHobbies, "Hobbies");
        setRowTitle(R.id.rowInterests, "Interests");
        setRowTitle(R.id.rowTravel, "Travel");
        setRowTitle(R.id.rowContactInfo, "Contact info");

        // ---- ক্লিক লিসেনার যুক্ত করা (Relationship ও অন্যান্য মেইন সেকশন) ----
        setupClickListeners();

        // ---- বাকি সারি (আপাতত Demo) ----

        // ---- Intro: Bio + Pinned details ----
        intro = new ProfileIntroSection(this, findViewById(R.id.rowIntro),
                getIntent().getStringExtra("uid"));
        intro.attach();

        // ---- Category ----
        category = new ProfileCategorySection(this, findViewById(R.id.rowCategory),
                getIntent().getStringExtra("uid"));
        category.attach();

        // ---- Personal info (Current City, Hometown, Family, Languages ইত্যাদি এর ভেতরেই রয়েছে) ----
        personal = new PersonalInfoSection(this, findViewById(R.id.rowPersonalDetails),
                getIntent().getStringExtra("uid"));
        personal.attach();

        // ---- Links ----
        links = new ProfileLinksSection(this, findViewById(R.id.rowLinks),
                getIntent().getStringExtra("uid"));
        links.attach();

        // ---- Communities (Add group / Add pages) ----
        communities = new ProfileCommunitiesSection(this, findViewById(R.id.rowCommunities),
                getIntent().getStringExtra("uid"));
        communities.attach();

        // ---- Work > Work experience ----
        workSec = new ProfileCareerSection(this, findViewById(R.id.rowWork),
                getIntent().getStringExtra("uid"), ProfileCareerSection.WORK);
        workSec.attach();

        // ---- Education > High school or college (Add college / Add high school) ----
        eduSec = new ProfileCareerSection(this, findViewById(R.id.rowEducation),
                getIntent().getStringExtra("uid"), ProfileCareerSection.EDUCATION);
        eduSec.attach();

        // ---- Hobbies (Add Your Hobbies) ----
        hobbiesSec = new ProfileListSection(this, findViewById(R.id.rowHobbies),
                getIntent().getStringExtra("uid"), ProfileListSection.HOBBIES);
        hobbiesSec.attach();

        // ---- Interests > Music / TV shows / Movies / Games / Sports teams and athletes ----
        interestsSec = new ProfileInterestsSection(this, findViewById(R.id.rowInterests),
                getIntent().getStringExtra("uid"));
        interestsSec.attach();

        // ---- Travel > Places > Add Places ----
        travelSec = new ProfileListSection(this, findViewById(R.id.rowTravel),
                getIntent().getStringExtra("uid"), ProfileListSection.PLACES);
        travelSec.attach();

        // ---- Contact info (Social media / Phone / Email) — প্রোফাইলের শেষ সেটিং ----
        contactSec = new ProfileContactSection(this, findViewById(R.id.rowContactInfo),
                getIntent().getStringExtra("uid"));
        contactSec.attach();
    }

    /**
     * সকল গুরুত্বপূর্ণ রো বা সেকশনের ক্লিক লিসেনার এখানে হ্যান্ডেল করা হয়েছে
     */
    private void setupClickListeners() {
        String uid = getIntent().getStringExtra("uid");

        // রিলেশনশিপ এডিট: Personal info সেকশনের Relationship রো (PersonalInfoSection) থেকে খোলে

        // ২. Intro রো ক্লিক
        View rowIntro = findViewById(R.id.rowIntro);
        if (rowIntro != null) {
            rowIntro.setOnClickListener(v -> {
                TopToast.show(this, "Intro clicked");
            });
        }

        // ৩. Category রো ক্লিক
        View rowCategory = findViewById(R.id.rowCategory);
        if (rowCategory != null) {
            rowCategory.setOnClickListener(v -> {
                TopToast.show(this, "Category clicked");
            });
        }

        // ৪. Personal info রো ক্লিক
        View rowPersonalDetails = findViewById(R.id.rowPersonalDetails);
        if (rowPersonalDetails != null) {
            rowPersonalDetails.setOnClickListener(v -> {
                TopToast.show(this, "Personal info clicked");
            });
        }
    }

    private void setRowTitle(int rowId, String title) {
        View row = findViewById(rowId);
        if (row == null) return;
        TextView tv = firstTextView(row);
        if (tv != null) tv.setText(title);
    }

    private TextView firstTextView(View v) {
        if (v instanceof TextView) return (TextView) v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                TextView t = firstTextView(g.getChildAt(i));
                if (t != null) return t;
            }
        }
        return null;
    }

    private void demoRow(int rowId, final String name) {
        View row = findViewById(rowId);
        if (row == null) return;
        bindClick(row, v -> TopToast.show(this, name + " coming soon"));
    }

    private void bindClick(View v, View.OnClickListener l) {
        v.setOnClickListener(l);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) bindClick(g.getChildAt(i), l);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (personal != null) personal.refresh();
    }

    @Override
    protected void onDestroy() {
        if (intro != null) intro.destroy();
        super.onDestroy();
    }
}
