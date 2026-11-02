package com.familybook.app;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * "People you may know" (ফেসবুকের মতো): পাশে স্ক্রল হওয়া কার্ড — উপরে বর্গ বড় ছবি, নিচে নাম ও ছোট লেখা,
 * তারপর Follow / Remove বাটন। ক্রস বা ৩-ডট নেই। "See all >" লেখাটা তালিকার নিচে (স্ক্রিনের লেআউটে)।
 */
public class SuggestAdapter extends RecyclerView.Adapter<SuggestAdapter.CardVH> {

    public interface Actions {
        void onOpen(MembersActivity.MemberItem m);
        void onFollow(MembersActivity.MemberItem m);
        void onRemove(MembersActivity.MemberItem m);
    }

    private final List<MembersActivity.MemberItem> items = new ArrayList<>();
    private final Actions actions;

    public SuggestAdapter(Actions actions) { this.actions = actions; }

    public void setItems(List<MembersActivity.MemberItem> list) {
        items.clear();
        items.addAll(list);
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() { return items.size(); }

    // ------------------------------------------------------------ কার্ডের মাপ ও বাটনের সাজ (Search স্ক্রিনও ব্যবহার করে)

    /** কার্ডের চওড়া = স্ক্রিনের প্রায় ২/৩, তাই পাশের কার্ডের একটু অংশ উঁকি দেয় */
    static int cardWidth(Context c) {
        return Math.round(c.getResources().getDisplayMetrics().widthPixels * 0.66f);
    }

    /** কার্ডের মাপ বসায়: চওড়া স্ক্রিন অনুযায়ী, ছবি বর্গ */
    static void sizeCard(View card) {
        int w = cardWidth(card.getContext());
        ViewGroup.LayoutParams lp = card.getLayoutParams();
        if (lp == null) lp = new ViewGroup.MarginLayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.width = w;
        card.setLayoutParams(lp);
        View photo = card.findViewById(R.id.imgPhoto);
        if (photo != null) {
            ViewGroup.LayoutParams pl = photo.getLayoutParams();
            pl.height = w;
            photo.setLayoutParams(pl);
        }
    }

    /** Follow = নীল + সাদা আইকন/লেখা; Following = ধূসর + কালো লেখা */
    static void styleFollow(TextView b, boolean following) {
        if (following) {
            b.setBackgroundResource(R.drawable.bg_pymk_btn_gray);
            b.setText("Following");
            b.setTextColor(0xFF050505);
            b.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0);
        } else {
            b.setBackgroundResource(R.drawable.bg_pymk_btn_blue);
            b.setText("Follow");
            b.setTextColor(0xFFFFFFFF);
            b.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_person_add, 0, 0, 0);
        }
    }

    @NonNull
    @Override
    public CardVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_suggest_card, parent, false);
        sizeCard(v);
        return new CardVH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull CardVH h, int position) {
        final MembersActivity.MemberItem m = items.get(position);
        h.tvName.setText(m.name);
        h.tvSub.setText(m.location == null || m.location.isEmpty() ? "Suggested for you" : m.location);
        AvatarLoader.load(h.imgPhoto, m.uid, false);   // বর্গ বড় ছবি
        styleFollow(h.btnFollow, false);
        h.imgPhoto.setOnClickListener(v -> actions.onOpen(m));
        h.tvName.setOnClickListener(v -> actions.onOpen(m));
        h.btnFollow.setOnClickListener(v -> actions.onFollow(m));
        h.btnRemove.setOnClickListener(v -> actions.onRemove(m));
    }

    static class CardVH extends RecyclerView.ViewHolder {
        final ImageView imgPhoto;
        final TextView tvName, tvSub, btnFollow, btnRemove;
        CardVH(@NonNull View v) {
            super(v);
            imgPhoto = v.findViewById(R.id.imgPhoto);
            tvName = v.findViewById(R.id.tvName);
            tvSub = v.findViewById(R.id.tvSub);
            btnFollow = v.findViewById(R.id.btnFollow);
            btnRemove = v.findViewById(R.id.btnRemove);
        }
    }
}
