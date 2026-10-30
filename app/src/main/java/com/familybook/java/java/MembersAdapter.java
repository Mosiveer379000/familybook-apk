package com.familybook.app;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupMenu;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class MembersAdapter extends RecyclerView.Adapter<MembersAdapter.VH> {

    public interface Actions {
        void onOpen(MembersActivity.MemberItem m);
        void onToggleFollow(MembersActivity.MemberItem m);
        void onBlock(MembersActivity.MemberItem m);
        void onMessage(MembersActivity.MemberItem m);
    }

    private final List<MembersActivity.MemberItem> items = new ArrayList<>();
    private final Actions actions;

    public MembersAdapter(Actions actions) { this.actions = actions; }

    public void setItems(List<MembersActivity.MemberItem> list) {
        items.clear();
        items.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_member, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        final MembersActivity.MemberItem m = items.get(position);
        h.tvName.setText(m.name);
        h.tvLocation.setText(m.location);
        h.tvLocation.setVisibility(m.location.isEmpty() ? View.GONE : View.VISIBLE);
        AvatarLoader.load(h.imgAvatar, m.uid);

        // বাটন: আমি ফলো করি => "Following" (ধূসর; চাপলে আনফলো); করি না => "Follow" (নীল)
        if (m.isFollowing) {
            h.btnUnfollow.setText("Following");
            h.btnUnfollow.setTextColor(0xFF000000);
            h.btnUnfollow.setBackgroundResource(R.drawable.bg_btn_following_gray);
        } else {
            h.btnUnfollow.setText("Follow");
            h.btnUnfollow.setTextColor(0xFFFFFFFF);
            h.btnUnfollow.setBackgroundResource(R.drawable.bg_btn_follow_blue);
        }

        View.OnClickListener open = v -> actions.onOpen(m);
        h.imgAvatar.setOnClickListener(open);
        h.tvName.setOnClickListener(open);
        h.btnUnfollow.setOnClickListener(v -> actions.onToggleFollow(m));
        h.btnMore.setOnClickListener(v -> {
            PopupMenu pm = new PopupMenu(v.getContext(), v);
            pm.getMenu().add(0, 1, 0, "View Profile");
            pm.getMenu().add(0, 2, 1, "Message");
            pm.getMenu().add(0, 3, 2, "Block");
            pm.setOnMenuItemClickListener(it -> {
                if (it.getItemId() == 1) actions.onOpen(m);
                else if (it.getItemId() == 2) actions.onMessage(m);
                else actions.onBlock(m);
                return true;
            });
            pm.show();
        });
    }

    @Override
    public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        ImageView imgAvatar;
        TextView tvName, tvLocation, btnUnfollow, btnMore;

        VH(@NonNull View v) {
            super(v);
            imgAvatar = v.findViewById(R.id.imgAvatar);
            tvName = v.findViewById(R.id.tvName);
            tvLocation = v.findViewById(R.id.tvLocation);
            btnMore = v.findViewById(R.id.btnMore);
            btnUnfollow = v.findViewById(R.id.btnUnfollow);
        }
    }
}
