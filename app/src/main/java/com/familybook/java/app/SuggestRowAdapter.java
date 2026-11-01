package com.familybook.app;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/** "Suggested for you": শুধু ছবি + নাম + শহর, কোনো Follow/Block বাটন নেই; চাপলে প্রোফাইল খোলে */
public class SuggestRowAdapter extends RecyclerView.Adapter<SuggestRowAdapter.VH> {

    public interface OnOpen { void open(MembersActivity.MemberItem m); }

    private final List<MembersActivity.MemberItem> items = new ArrayList<>();
    private final OnOpen onOpen;

    public SuggestRowAdapter(OnOpen onOpen) { this.onOpen = onOpen; }

    public void setItems(List<MembersActivity.MemberItem> list) {
        items.clear();
        items.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_person_row, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        final MembersActivity.MemberItem m = items.get(position);
        h.tvName.setText(m.name);
        h.tvLocation.setText(m.location);
        h.tvLocation.setVisibility(m.location.isEmpty() ? View.GONE : View.VISIBLE);
        AvatarLoader.load(h.imgAvatar, m.uid);
        h.itemView.setOnClickListener(v -> onOpen.open(m));
    }

    @Override
    public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView imgAvatar;
        final TextView tvName, tvLocation;
        VH(@NonNull View v) {
            super(v);
            imgAvatar = v.findViewById(R.id.imgAvatar);
            tvName = v.findViewById(R.id.tvName);
            tvLocation = v.findViewById(R.id.tvLocation);
        }
    }
}
