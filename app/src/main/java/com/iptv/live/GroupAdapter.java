package com.iptv.live;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/** 左侧分组导航（我的收藏 / 央视频道 / 卫视频道 ...）。 */
public class GroupAdapter extends RecyclerView.Adapter<GroupAdapter.VH> {

    public interface OnPick {
        void onPick(int position);
    }

    private final List<String> groups;
    private final OnPick pick;
    private int current = 0;

    public GroupAdapter(List<String> groups, OnPick pick) {
        this.groups = groups;
        this.pick = pick;
        setHasStableIds(true);
    }

    public void setCurrent(int index) {
        int old = current;
        current = index;
        if (old >= 0 && old < groups.size()) {
            notifyItemChanged(old);
        }
        if (index >= 0 && index < groups.size()) {
            notifyItemChanged(index);
        }
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_group, parent, false);
        return new VH(v, parent.getContext());
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        String group = groups.get(position);
        h.name.setText(ChannelGroups.displayName(group));
        h.name.setSelected(position == current);
        h.name.setTag(group);
        h.itemView.setOnClickListener(v -> pick.onPick(h.getBindingAdapterPosition()));
        // 最后一个分组按 ↓ 落到下方的「设置 / 收起」，不被右侧频道列表抢走
        boolean last = position == groups.size() - 1;
        h.itemView.setNextFocusDownId(last ? R.id.btnDrawerSettings : View.NO_ID);
    }

    @Override
    public long getItemId(int position) {
        return groups.get(position).hashCode();
    }

    @Override
    public int getItemCount() {
        return groups.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView name;

        VH(View v, Context ctx) {
            super(v);
            name = v.findViewById(R.id.groupName);
            // 字号与行高随屏幕等比放大，大屏不显小
            UiScale.text(name, 30);
            UiScale.padding(name, 22, 20, 16, 20);
        }
    }
}
