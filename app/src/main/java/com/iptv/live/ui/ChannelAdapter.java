package com.iptv.live.ui;

import com.iptv.live.model.Channel;

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
import com.iptv.live.R;

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
    /**
     * 正在播放的频道名。
     *
     * <p><b>必须按名字判断，不能按下标。</b>早先这里存的是"列表选中行下标"，
     * 而选中行会被强制夹到 0（切到不含正在播频道的分组时找不到，就落到第一行），
     * 于是第一行被标上"正在播放"——明明播的是别的分组里的台。
     * 名字跨分组稳定，找不到就一个都不标，这才是实事求是。
     */
    private String playingName = "";

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

    /**
     * 设置"正在播放"的频道。
     *
     * <p>按<b>名字</b>匹配，不按下标：切到不含正在播频道的分组时，
     * 列表里一条都不该带"正在播放"标，而不是把第一行误标上去。
     */
    public void setPlaying(String name) {
        String next = name == null ? "" : name;
        if (playingName.equals(next)) {
            return;
        }
        int oldPos = indexOf(playingName);
        playingName = next;
        int newPos = indexOf(playingName);
        // 新旧位置都要重绘：只刷一个的话另一行的高亮会残留
        if (oldPos >= 0) {
            notifyItemChanged(oldPos);
        }
        if (newPos >= 0 && newPos != oldPos) {
            notifyItemChanged(newPos);
        }
    }

    /** 在 data 里按名字找下标，找不到返回 -1。 */
    private int indexOf(String name) {
        if (name == null || name.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < data.size(); i++) {
            if (name.equals(data.get(i).name)) {
                return i;
            }
        }
        return -1;
    }

    /** 当前列表里"正在播放"那一行的下标，不在本列表里返回 -1。 */
    public int playingPosition() {
        return indexOf(playingName);
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
        // "正在播放"按名字匹配：不在本分组里就一条都不标（实事求是）
        boolean playing = !playingName.isEmpty() && playingName.equals(c.name);
        h.bindQualities(c, playing);
        h.itemView.setActivated(playing);
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
            // 每档一个胶囊。已经被线路体检证伪的档位压暗 + 加 ✕：
            // 用户还没按下去就知道这一档现在没戏，而不是点了等黑屏。
            java.util.Map<String, Boolean> tiers = new java.util.LinkedHashMap<>();
            for (Channel.Source s : c.sources) {
                String key = s.displayKey();
                if (key.isEmpty()) {
                    continue;
                }
                Boolean alive = tiers.get(key);
                boolean thisAlive = !com.iptv.live.source.SourceAudit.isBad(s.url);
                if (alive == null) {
                    tiers.put(key, thisAlive);
                } else if (!alive && thisAlive) {
                    tiers.put(key, true);
                }
            }
            if (tiers.isEmpty()) {
                tiers.put("自动", !allBad(c));
            }
            for (java.util.Map.Entry<String, Boolean> e : tiers.entrySet()) {
                boolean alive = e.getValue();
                addChip(alive ? e.getKey() : e.getKey() + " ✕",
                        alive ? 0xFF3D6FB8 : 0xFF4A5568, alive);
            }
            if (active) {
                addChip("正在播放", 0xFF2E9E63, true);
            }
        }

        /** 这个台是不是一条能出数据的线路都没有了。 */
        private static boolean allBad(Channel c) {
            for (Channel.Source s : c.sources) {
                if (!com.iptv.live.source.SourceAudit.isBad(s.url)) {
                    return false;
                }
            }
            return !c.sources.isEmpty();
        }

        private void addChip(String text, int color) {
            addChip(text, color, true);
        }

        private void addChip(String text, int color, boolean solid) {
            TextView t = new TextView(ctx);
            t.setText(text);
            UiScale.text(t, 22);
            t.setTextColor(0xCCFFFFFF);
            t.setBackgroundResource(R.drawable.chip_bg);
            t.getBackground().setTint(color);
            int px = UiScale.pxW(14);
            int py = UiScale.pxH(4);
            t.setPadding(px, py, px, py);
            if (!solid) {
                t.setAlpha(0.45f);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = UiScale.pxW(8);
            metaRow.addView(t, lp);
        }
    }

    /** 图标解码结果缓存，列表滚动时不重复解码。 */
    public static final class LogoCache {
        private static final android.util.LruCache<String, Bitmap> CACHE =
                new android.util.LruCache<String, Bitmap>(64) {
                    @Override
                    protected int sizeOf(String key, Bitmap value) {
                        return 1;
                    }
                };

        public static Bitmap get(Context ctx, String key) {
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
