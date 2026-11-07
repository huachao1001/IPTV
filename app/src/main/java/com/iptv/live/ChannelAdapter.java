package com.iptv.live;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 频道列表适配器，按 TV 遥控器焦点模式工作，同时支持触摸点击。 */
public class ChannelAdapter extends RecyclerView.Adapter<ChannelAdapter.VH> {

    public interface OnPick {
        void onPick(int position);
    }

    public interface OnLongPress {
        void onLongPress(int position);
    }

    private final List<Channel> data;
    private final OnPick pick;
    private OnLongPress longPress;
    private int current = -1;

    /** 图标已随包提供，直接同步解码，避免异步闪烁。 */
    private final Set<String> missingLogos = new HashSet<>();

    public ChannelAdapter(List<Channel> data, OnPick pick) {
        this.data = data;
        this.pick = pick;
        setHasStableIds(true);
    }

    public void setOnLongPress(OnLongPress l) {
        this.longPress = l;
    }

    public void setCurrent(int index) {
        int old = current;
        current = index;
        if (old >= 0 && old < data.size()) {
            notifyItemChanged(old);
        }
        if (index >= 0 && index < data.size()) {
            notifyItemChanged(index);
        }
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_channel, parent, false);
        return new VH(v, parent.getContext());
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Channel c = data.get(position);
        h.name.setText(c.name);
        // 行高每次 bind 都固定下来，避免首行沿用 wrap_content 被压扁
        h.applyRowHeight();
        h.bindLogo(c);
        h.bindQualities(c, position == current);
        h.itemView.setActivated(position == current);
        h.itemView.setOnClickListener(v -> pick.onPick(h.getBindingAdapterPosition()));
        h.itemView.setOnLongClickListener(v -> {
            if (longPress == null) {
                return false;
            }
            longPress.onLongPress(h.getBindingAdapterPosition());
            return true;
        });
    }

    @Override
    public long getItemId(int position) {
        return data.get(position).name.hashCode();
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final Context ctx;
        final ImageView logo;
        final TextView name;
        final LinearLayout metaRow;

        VH(View v, Context ctx) {
            super(v);
            this.ctx = ctx;
            logo = v.findViewById(R.id.logo);
            name = v.findViewById(R.id.name);
            metaRow = v.findViewById(R.id.metaRow);
            UiScale.text(name, 34);
        }

        /** 行高固定，图标占行高的八成左右，四周留出呼吸空间。 */
        void applyRowHeight() {
            int rowHeight = UiScale.pxH(140);
            int gap = UiScale.pxH(16);
            ViewGroup.LayoutParams rowLp = itemView.getLayoutParams();
            rowLp.height = rowHeight;
            itemView.setLayoutParams(rowLp);

            // 台标已归一化成 2:1 画布，按同一比例取框，视觉重量才一致
            int boxH = rowHeight - gap * 2;
            ViewGroup.LayoutParams lp = logo.getLayoutParams();
            lp.width = boxH * 2;
            lp.height = boxH;
            logo.setLayoutParams(lp);

            UiScale.padding(itemView, 10, 0, 16, 0);
        }

        void bindLogo(Channel c) {
            String key = c.logoKey();
            Bitmap bmp = LogoCache.get(ctx, key);
            if (bmp == null) {
                logo.setImageDrawable(null);
            } else {
                logo.setImageBitmap(bmp);
            }
        }

        /** 底部一行只展示清晰度，可横向滑动。 */
        void bindQualities(Channel c, boolean active) {
            metaRow.removeAllViews();
            Set<String> seen = new java.util.LinkedHashSet<>();
            for (Channel.Source s : c.sources) {
                if (s.quality != null && !s.quality.isEmpty()) {
                    seen.add(s.quality);
                }
            }
            for (String label : seen) {
                addChip(label, 0xFF3D6FB8);
            }
            if (seen.isEmpty()) {
                addChip("自动", 0xFF4A5568);
            }
            if (active) {
                addChip("正在播放", 0xFF2E9E63);
            }
        }

        private void addChip(String text, int color) {
            TextView t = new TextView(ctx);
            t.setText(text);
            UiScale.text(t, 22);
            t.setTextColor(0xCCFFFFFF);
            t.setBackgroundResource(R.drawable.chip_bg);
            t.getBackground().setTint(color);
            int px = UiScale.pxW(14);
            int py = UiScale.pxH(4);
            t.setPadding(px, py, px, py);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = UiScale.pxW(8);
            metaRow.addView(t, lp);
        }
    }

    /** 图标解码结果缓存，列表滚动时不重复解码。 */
    static final class LogoCache {
        private static final android.util.LruCache<String, Bitmap> CACHE =
                new android.util.LruCache<String, Bitmap>(64) {
                    @Override
                    protected int sizeOf(String key, Bitmap value) {
                        return 1;
                    }
                };

        static Bitmap get(Context ctx, String key) {
            Bitmap hit = CACHE.get(key);
            if (hit != null) {
                return hit;
            }
            try {
                InputStream in = ctx.getAssets().open("logos/" + key + ".png");
                try {
                    Bitmap bmp = BitmapFactory.decodeStream(in);
                    if (bmp != null) {
                        CACHE.put(key, bmp);
                    }
                    return bmp;
                } finally {
                    in.close();
                }
            } catch (IOException e) {
                return null;
            }
        }
    }
}
