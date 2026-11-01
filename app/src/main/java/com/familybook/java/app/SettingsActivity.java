package com.familybook.app;

import android.content.Intent;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.LinearLayout;
import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {

    private ImageView ivBack;
    private LinearLayout layoutAccount, layoutPrivacy, layoutBlocking;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        ivBack = findViewById(R.id.ivBack);
        layoutAccount = findViewById(R.id.layoutAccount);
        layoutPrivacy = findViewById(R.id.layoutPrivacy);
        layoutBlocking = findViewById(R.id.layoutBlocking);

        ivBack.setOnClickListener(v -> finish());

        // Account & Security ও Privacy Check — দুটোই একই ফর্ম খোলে
        android.view.View.OnClickListener openForm = v ->
                startActivity(new Intent(SettingsActivity.this, AccountsPrivacyActivity.class));
        layoutAccount.setOnClickListener(openForm);
        layoutPrivacy.setOnClickListener(openForm);

        // Blocking
        layoutBlocking.setOnClickListener(v ->
                startActivity(new Intent(SettingsActivity.this, BlockingActivity.class)));

        // Media: ভিডিও অটো-প্লে বন্ধ করার অপশন
        findViewById(R.id.layoutMedia).setOnClickListener(v ->
                startActivity(new Intent(SettingsActivity.this, MediaSettingsActivity.class)));
    }
}
