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

/** "People you may know": পাশে স্ক্রল হওয়া বড় ছবির কার্ড; সবার শেষে সবসময় "See more.." কার্ড */
public class SuggestAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public interface Actions {
        void onOpen(MembersActivity.MemberItem m);
        void onFollow(MembersActivity.MemberItem m);
        void onRemove(MembersActivity.MemberItem m);
        void onSeeMore();
    }

    private static final int T_CARD = 0, T_MORE = 1;

    private final List<MembersActivity.MemberItem> items = new ArrayList<>();
    private final Actions actions;

    public SuggestAdapter(Actions actions) { this.actions = actions; }

    public void setItems(List<MembersActivity.MemberItem> list) {
        items.clear();
        items.addAll(list);
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) { return position == items.size() ? T_MORE : T_CARD; }

    @Override
    public int getItemCount() { return items.size() + 1; }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        if (viewType == T_MORE) {
            return new MoreVH(inf.inflate(R.layout.item_suggest_more, parent, false));
        }
        return new CardVH(inf.inflate(R.layout.item_suggest_card, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof MoreVH) {
            holder.itemView.setOnClickListener(v -> actions.onSeeMore());
            return;
        }
        final MembersActivity.MemberItem m = items.get(position);
        CardVH h = (CardVH) holder;
        h.tvName.setText(m.name);
        AvatarLoader.load(h.imgPhoto, m.uid, false);   // বর্গ বড় ছবি
        h.imgPhoto.setOnClickListener(v -> actions.onOpen(m));
        h.tvName.setOnClickListener(v -> actions.onOpen(m));
        h.btnFollow.setOnClickListener(v -> actions.onFollow(m));
        h.btnRemove.setOnClickListener(v -> actions.onRemove(m));
    }

    static class CardVH extends RecyclerView.ViewHolder {
        final ImageView imgPhoto;
        final TextView tvName;
        final View btnFollow, btnRemove;
        CardVH(@NonNull View v) {
            super(v);
            imgPhoto = v.findViewById(R.id.imgPhoto);
            tvName = v.findViewById(R.id.tvName);
            btnFollow = v.findViewById(R.id.btnFollow);
            btnRemove = v.findViewById(R.id.btnRemove);
        }
    }

    static class MoreVH extends RecyclerView.ViewHolder {
        MoreVH(@NonNull View v) { super(v); }
    }
}
