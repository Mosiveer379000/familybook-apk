package com.familybook.app;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;

/**
 * New Group ফর্ম: Group name (সর্বোচ্চ ৭৫), Category, Add Location, Description (ঐচ্ছিক; লিখলে কমপক্ষে ২০, সর্বোচ্চ ১০০০),
 * Group & Post visible to? (Public / Friends / Group members), Save।
 * Save দিলে সার্ভারে গ্রুপ তৈরি হয়, আমি admin হই; ফিরে গিয়ে Groups পেজে সবার আগে দেখা যায়।
 */
public class NewGroupActivity extends BaseActivity {

    private static final int NAME_MAX = 75;   // Facebook-এর মতো
    private static final int DESC_MIN = 20;     // লিখলে কমপক্ষে ২০ (না লিখলে ফাঁকা থাকতে পারে)
    private static final int DESC_MAX = 1000;   // সর্বোচ্চ ১০০০ (গ্রুপ পোস্টের শুরুতে পিন থাকবে)

    private static final String[] CATEGORIES = {
            "Family group", "Relatives", "Friends group", "Neighbours & Community",
            "Village / Local area", "Political group", "Social organization", "Organization / NGO",
            "Charity & Volunteer", "Private group", "Secret group", "Support group",
            "Business", "Buy & Sell / Marketplace", "Job & Career", "Freelancing",
            "Professional network", "Education", "Study group", "School", "College / University",
            "Students", "Teachers", "Alumni", "Sports", "Football", "Cricket", "Gaming",
            "Religion & Spirituality", "Hobbies", "Photography", "Art & Design", "Music",
            "Movies & TV", "Books & Reading", "Cooking & Food", "Travel", "Technology",
            "Programming", "Farming & Agriculture", "Health & Fitness", "Medical",
            "Parenting", "Fashion & Beauty", "Cars & Bikes", "Pets & Animals",
            "News & Media", "Entertainment", "Events", "Other"
    };

    // দেখা যাবে কাদের কাছে: স্ক্রিনে নাম / ডেটাবেসে মান
    private static final String[] VIS_LABELS = {"Public", "Friends", "Group members"};
    private static final String[] VIS_VALUES = {"public", "friends", "members"};
    private static final String[] VIS_HELP = {
            "Public \u2014 সবাই গ্রুপ ও পোস্ট দেখতে পারবে",
            "Friends \u2014 শুধু আপনার বন্ধুরা দেখতে পারবে",
            "Group members \u2014 শুধু গ্রুপের সদস্যরা দেখতে পারবে"
    };

    private String myUid;
    private EditText etName, etDesc;
    private TextView tvCategory, tvLocation, tvVisibility, tvCount, btnSave;

    private String category = "";
    private String location = "";
    private String visibility = "public";
    private boolean saving = false;

    private final ActivityResultLauncher<Intent> locationLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), res -> {
                if (res.getResultCode() != RESULT_OK || res.getData() == null) return;
                String loc = res.getData().getStringExtra(LocationActivity.EXTRA_LOCATION);
                location = TextUtils.isEmpty(loc) ? "" : loc;   // খালি = সরানো
                tvLocation.setText(location);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        myUid = SupabaseRest.uid();
        if (!SupabaseRest.isLoggedIn() || myUid == null) {
            finish();
            return;
        }
        setContentView(R.layout.activity_new_group);

        etName = findViewById(R.id.etName);
        etDesc = findViewById(R.id.etDesc);
        tvCategory = findViewById(R.id.tvCategory);
        tvLocation = findViewById(R.id.tvLocation);
        tvVisibility = findViewById(R.id.tvVisibility);
        tvCount = findViewById(R.id.tvCount);
        btnSave = findViewById(R.id.btnSave);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        tvCategory.setOnClickListener(v -> pickCategory());

        tvLocation.setOnClickListener(v -> {
            Intent i = new Intent(this, LocationActivity.class);
            i.putExtra(LocationActivity.EXTRA_LOCATION, location);
            locationLauncher.launch(i);
        });

        tvVisibility.setOnClickListener(v -> pickVisibility());

        etDesc.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                tvCount.setText(s.length() + "/" + DESC_MAX);
                tvCount.setTextColor(s.length() > 0 && s.length() < DESC_MIN ? 0xFFC62828 : 0xFF555555);
            }
        });

        btnSave.setOnClickListener(v -> save());
    }

    private void pickCategory() {
        new AlertDialog.Builder(this)
                .setTitle("Category")
                .setItems(CATEGORIES, (d, which) -> {
                    category = CATEGORIES[which];
                    tvCategory.setText(category);
                })
                .show();
    }

    private void pickVisibility() {
        new AlertDialog.Builder(this)
                .setTitle("Group & Post visible to?")
                .setItems(VIS_HELP, (d, which) -> {
                    visibility = VIS_VALUES[which];
                    tvVisibility.setText(VIS_LABELS[which]);
                })
                .show();
    }

    private void save() {
        if (saving) return;
        String name = etName.getText().toString().trim();
        if (name.isEmpty()) {
            etName.setError("Group name লিখুন");
            etName.requestFocus();
            return;
        }
        String desc = etDesc.getText().toString().trim();
        if (!desc.isEmpty() && desc.length() < DESC_MIN) {
            etDesc.setError("Description কমপক্ষে " + DESC_MIN + " অক্ষরের হতে হবে (এখন "
                    + desc.length() + ")");
            etDesc.requestFocus();
            return;
        }

        saving = true;
        btnSave.setEnabled(false);
        btnSave.setText("Saving...");

        GroupsRepo.create(myUid, name, category, location, desc, visibility, err -> {
            saving = false;
            btnSave.setEnabled(true);
            btnSave.setText("Save");
            if (err != null) {
                Toast.makeText(this, err, Toast.LENGTH_LONG).show();
                return;
            }
            Toast.makeText(getApplicationContext(), "গ্রুপ তৈরি হয়েছে", Toast.LENGTH_SHORT).show();
            setResult(RESULT_OK);
            finish();
        });
    }
}
