package com.familybook.app;

import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.Switch;
import androidx.appcompat.app.AppCompatActivity;

public class WhatsOnMindActivity extends AppCompatActivity {

    private ImageView btnBack, btnShare;
    private Switch switchAiLabel, switchMonetization;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.whats_on_mind_setting);

        if (PostQueue.stagedJob() == null) { finish(); return; }   // কোনো অপেক্ষমাণ পোস্ট নেই

        // Views initialization
        btnBack = findViewById(R.id.btnBack);
        btnShare = findViewById(R.id.btnShare);
        switchAiLabel = findViewById(R.id.switchAiLabel);
        switchMonetization = findViewById(R.id.switchMonetization);

        // Back arrow click event
        btnBack.setOnClickListener(v -> { setResult(RESULT_CANCELED); finish(); });   // NewPostActivity বাতিল সামলায়

        // SHARE: সেটিংস জবে বসিয়ে বাকি আপলোড + পোস্ট শুরু (Next-এ আগেই ছবি আংশিক আপলোড হয়ে ফোনে সেভ আছে)
        btnShare.setOnClickListener(v -> {
            PostQueue.Job j = PostQueue.stagedJob();
            if (j == null) { setResult(RESULT_CANCELED); finish(); return; }
            j.aiLabel = switchAiLabel.isChecked();
            j.monetize = switchMonetization.isChecked();
            btnShare.setEnabled(false);
            PostQueue.commit();
            setResult(RESULT_OK);
            finish();
        });

        // AI Label Switch listener
        switchAiLabel.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                // AI Label logic
            } else {
                // AI Label disable logic
            }
        });

        // Monetization Switch listener
        switchMonetization.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                // Monetization enable logic
            } else {
                // Monetization disable logic
            }
        });
    }
}
