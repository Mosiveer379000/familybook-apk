package com.familybook.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** ফটো ঘর: শুধু নিজের পোস্টগুলো দেখায় */
public class MyPostsActivity extends AppCompatActivity implements PostOps.Refreshable {

    private String myUid;
    private PostAdapter adapter;
    private TextView tvEmpty;
    private PullRefreshLayout swipe;
    private boolean firstResume = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        myUid = SupabaseRest.uid();
        if (!SupabaseRest.isLoggedIn() || myUid == null) { finish(); return; }

        setContentView(R.layout.activity_my_posts);
        Sfx.init(this);
        tvEmpty = findViewById(R.id.tvEmpty);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        RecyclerView rv = findViewById(R.id.rvMyPosts);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new PostAdapter(new PostAdapter.Actions() {
            @Override public void onLike(Post p) { toggleLike(p); }
            @Override public void onHide(Post p) { }   // প্রোফাইলে X নেই
            @Override public void onMenu(Post p, View anchor) {
                PostOps.showMenu(MyPostsActivity.this, myUid, p, anchor);
            }
            @Override public void onComments(Post p) {
                startActivity(new Intent(MyPostsActivity.this, CommentsActivity.class)
                        .putExtra("postId", p.id));
            }
        }, false);
        rv.setAdapter(adapter);

        PostOps.cleanup(myUid);
        swipe = findViewById(R.id.swipeRefresh);
        swipe.setColorSchemeColors(0xFF00FFC2, 0xFF476A91);
        swipe.setOnRefreshListener(this::pullRefresh);

        loadPosts();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (myUid == null) return;
        // Firebase-এর মতো লাইভ আপডেট নেই, তাই অন্য পেজ থেকে ফিরলে নতুন করে আনি
        if (firstResume) {
            firstResume = false;
            return;
        }
        loadPosts();
    }

    /** PostOps.Refreshable — পোস্ট বদলালে (delete/archive...) নিজে থেকে ডাকা হয় */
    @Override
    public void refreshPosts() {
        if (myUid == null) return;
        loadPosts();
    }

    private void loadPosts() {
        SupabaseRest.call("GET",
                "/rest/v1/profile_posts?profile_user_id=eq." + myUid
                        + "&status=eq.active&order=created_at.desc&limit=200",
                null, null, true, r -> {
            if (isFinishing() || isDestroyed()) return;
            swipe.setRefreshing(false);
            if (!r.ok()) {
                Toast.makeText(this, SupabaseRest.errorText(r), Toast.LENGTH_LONG).show();
                return;
            }
            try {
                JSONArray a = new JSONArray(r.body);
                List<Post> list = new ArrayList<>();
                for (int i = 0; i < a.length(); i++) {
                    list.add(Post.from(a.getJSONObject(i), myUid));
                }
                Collections.sort(list, (x, y) -> Long.compare(y.timestamp, x.timestamp));
                show(list);
            } catch (Exception e) {
                Toast.makeText(this, "পোস্ট পড়া যায়নি", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void show(List<Post> list) {
        adapter.setItems(list);
        tvEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void pullRefresh() {
        if (!Net.isOnline()) {
            swipe.setRefreshing(false);
            Toast.makeText(this, "ইন্টারনেট নেই", Toast.LENGTH_SHORT).show();
            return;
        }
        Sfx.refresh();
        loadPosts();
    }

    private void toggleLike(Post p) {
        if (!Net.check(this)) return;
        if (p.likedByMe) {
            ReactionKit.removeReaction(this, p.id, myUid);
            p.likedByMe = false;
            p.myReaction = null;
            p.likes = Math.max(0, p.likes - 1);
        } else {
            ReactionKit.setReaction(this, p.id, myUid, "like");
            Sfx.like();
            p.likedByMe = true;
            p.myReaction = "like";
            p.likes = p.likes + 1;
        }
        adapter.notifyDataSetChanged();
    }
}
