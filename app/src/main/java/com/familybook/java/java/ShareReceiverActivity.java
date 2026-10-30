package com.familybook.app;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

/**
 * "Share to Family Book": যেকোনো অ্যাপের (এবং Family Book এর নিজের) Share লিস্টে দেখা যাবে।
 * সিলেক্ট করলে শেয়ার করা লেখা দিয়ে নতুন পোস্ট বানানোর স্ক্রিন খোলে।
 */
public class ShareReceiverActivity extends AppCompatActivity {

    private String myUid;
    private EditText input;
    private Button postBtn;
    private boolean posting = false;
    private String myName = "Family Member";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        myUid = SupabaseRest.uid();
        if (!SupabaseRest.isLoggedIn() || myUid == null) {
            Toast.makeText(this, "Please log in to Family Book first", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        PostOps.loadMyName(myUid, n -> myName = n);

        String shared = getIntent().getStringExtra(Intent.EXTRA_TEXT);
        buildUi(shared == null ? "" : shared);
    }

    private void buildUi(String initial) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        TextView title = new TextView(this);
        title.setText("Share to Family Book");
        title.setTextSize(20);
        title.setTextColor(0xFF1877F2);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        input = new EditText(this);
        input.setHint("Say something about this...");
        input.setText(initial);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setMinLines(5);
        input.setGravity(Gravity.TOP | Gravity.START);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(12);
        root.addView(input, lp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END);
        Button cancel = new Button(this);
        cancel.setText("Cancel");
        cancel.setOnClickListener(v -> finish());
        postBtn = new Button(this);
        postBtn.setText("Post");
        postBtn.setOnClickListener(v -> publish());
        row.addView(cancel);
        row.addView(postBtn);
        root.addView(row);

        setContentView(root);
    }

    private void publish() {
        if (posting) return;
        String text = input.getText().toString().trim();
        if (text.isEmpty()) return;
        if (!Net.check(this)) return;

        JSONObject row;
        try {
            row = new JSONObject()
                    .put("user_id", myUid)
                    .put("author_name", myName)
                    .put("text", text)
                    .put("privacy", "public")
                    .put("comment_perm", "public")
                    .put("status", "active");
        } catch (Exception e) {
            return;
        }

        posting = true;
        postBtn.setEnabled(false);
        SupabaseRest.call("POST", "/rest/v1/posts", row.toString(), "return=minimal", true, r -> {
            posting = false;
            if (r.ok()) {
                Toast.makeText(getApplicationContext(), "Shared to Family Book",
                        Toast.LENGTH_SHORT).show();
                finish();
            } else {
                postBtn.setEnabled(true);
                Toast.makeText(getApplicationContext(), SupabaseRest.errorText(r),
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
