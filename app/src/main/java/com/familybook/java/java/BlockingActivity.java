package com.familybook.app;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class BlockingActivity extends AppCompatActivity {

    private final List<BlockManager.BlockedUser> allUsers = new ArrayList<>();
    private final List<BlockManager.BlockedUser> shown = new ArrayList<>();
    private BlockedAdapter adapter;
    private EditText et;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_blocking);

        findViewById(R.id.ivBack).setOnClickListener(v -> finish());

        RecyclerView rv = findViewById(R.id.rvBlocked);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new BlockedAdapter();
        rv.setAdapter(adapter);

        et = findViewById(R.id.etSearchBlocked);
        et.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { applyFilter(); }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadFromServer(); // Supabase থেকে সর্বশেষ লিস্ট আনে
    }

    private void loadFromServer() {
        BlockManager.getAll(new BlockManager.Result<List<BlockManager.BlockedUser>>() {
            @Override public void onSuccess(List<BlockManager.BlockedUser> data) {
                allUsers.clear();
                allUsers.addAll(data);
                applyFilter();
            }
            @Override public void onError(String message) {
                Toast.makeText(BlockingActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void applyFilter() {
        String q = et.getText().toString().trim().toLowerCase(Locale.getDefault());
        shown.clear();
        for (BlockManager.BlockedUser u : allUsers) {
            if (q.isEmpty() || u.name.toLowerCase(Locale.getDefault()).contains(q)) shown.add(u);
        }
        adapter.notifyDataSetChanged();
    }

    class BlockedAdapter extends RecyclerView.Adapter<BlockedAdapter.VH> {

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup p, int t) {
            return new VH(LayoutInflater.from(p.getContext())
                    .inflate(R.layout.item_blocked_user, p, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            BlockManager.BlockedUser u = shown.get(pos);
            h.tvName.setText(u.name);

            Glide.with(h.ivUser)
                    .load(u.photoUrl == null || u.photoUrl.isEmpty() ? null : u.photoUrl)
                    .placeholder(R.drawable.ic_profile)
                    .error(R.drawable.ic_profile)
                    .into(h.ivUser);

            h.tvUnblock.setOnClickListener(v -> {
                h.tvUnblock.setEnabled(false);
                BlockManager.unblock(u.id, new BlockManager.Result<Void>() {
                    @Override public void onSuccess(Void d) {
                        allUsers.remove(u);
                        applyFilter();
                        Toast.makeText(BlockingActivity.this, u.name + " unblocked", Toast.LENGTH_SHORT).show();
                    }
                    @Override public void onError(String message) {
                        h.tvUnblock.setEnabled(true);
                        Toast.makeText(BlockingActivity.this, message, Toast.LENGTH_LONG).show();
                    }
                });
            });
        }

        @Override public int getItemCount() { return shown.size(); }

        class VH extends RecyclerView.ViewHolder {
            ImageView ivUser; TextView tvName, tvUnblock;
            VH(View v) {
                super(v);
                ivUser = v.findViewById(R.id.ivUser);
                tvName = v.findViewById(R.id.tvName);
                tvUnblock = v.findViewById(R.id.tvUnblock);
            }
        }
    }
}
