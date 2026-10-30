package com.familybook.app;

import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/** পোস্টের ড্যাশবোর্ড (এখন ডেমো) — চোখের আইকন/ভিউজে চাপলে খোলে। পরে আসল ড্যাশবোর্ড যোগ হবে। */
public class PostDashboardActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_post_dashboard);
        ((TextView) findViewById(R.id.tvDashViews)).setText(PostAdapter.compact(getIntent().getIntExtra("views", 0)));
        ((TextView) findViewById(R.id.tvDashLikes)).setText(String.valueOf(getIntent().getIntExtra("likes", 0)));
        ((TextView) findViewById(R.id.tvDashComments)).setText(String.valueOf(getIntent().getIntExtra("comments", 0)));
        ((TextView) findViewById(R.id.tvDashShares)).setText(String.valueOf(getIntent().getIntExtra("shares", 0)));
        findViewById(R.id.btnDashBack).setOnClickListener(v -> finish());
    }
}
