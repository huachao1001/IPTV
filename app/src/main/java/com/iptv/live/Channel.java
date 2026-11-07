package com.iptv.live;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 一个频道聚合了同一频道的多路流，界面上按清晰度区分。 */
public class Channel {

    /** 一路流：播放地址 + 已知清晰度。 */
    public static class Source {
        public final String url;
        /**
         * 节目单声明的清晰度（如 {@code [1080]} 标签），购选源/排序/清晰度上限都用它。
         *
         * <p><b>它必须稳定。</b>一旦被实测值改写，{@link QualityCap} 依据的档位
         * 就跟着漂移，降档的「上限」会失效或误判，表现为画面来回切。
         * 所以实测结果只写 {@link #measured}，不碰这个字段。
         */
        public String quality;
        /**
         * 实测到的清晰度（解码器真正输出的高度）。
         *
         * <p>只用于界面显示和"无标签线路"之间的排序，<b>不参与</b>档位判定：
         * 节目单没给标记的线路档位恒为 0，这样"只允许无标记线路"的上限
         * （{@code cap=0}）在实测之后依然成立，不会因为测出 576 就把自己排除掉。
         */
        public String measured;
        /** 源文件里记录的响应耗时，0 表示未知。 */
        public int responseMs;

        /**
         * 实测"喂不饱"标记。
         *
         * <p>{@code true} 表示这路流实际播放时反复跳分片、缓冲，
         * 本机带宽喂不动它。探测测的是分片开头的突发速率，
         * 与真实持续速率能差四五倍，只有播起来才能判准；
         * 一旦测出就记下来，换台/换源时直接跳过，不再重复踩坑。
         */
        public Boolean starved;

        public Source(String url) {
            this.url = url;
        }

        /** 界面上显示的清晰度文字：优先节目单标记，其次实测，都没有显示"自动"。 */
        public String label() {
            if (quality != null && !quality.isEmpty()) {
                return quality;
            }
            if (measured != null && !measured.isEmpty()) {
                return measured;
            }
            return "自动";
        }

        /**
         * 参与选源/上限判定的有效档位。
         *
         * <p><b>只认节目单标记，绝不掺实测值。</b>实测值写进独立的
         * {@link #measured}，只用于界面显示和同档内排序。
         *
         * <p>这一点必须守住，否则上限会自己失效：节目单没给标记的线路
         * 档位恒为 0，而"降到底档"的上限正是 {@code 0}。一旦让实测值参与，
         * 一条无标记线路播过一次、被测成 576P，它的档位就变成 576，
         * 立刻被 {@code cap=0} 排除掉——明明是<b>允许</b>的线路却消失了。
         * 真机实测（build/final_grace.log 14:59:00）正是这样：
         * <pre>
         *   qual[src=1080P cap=0]   ← 未标记线路测出 1080P 后仍被选中
         *   startup-timeout buffered=0   ← 结果它根本喂不动
         * </pre>
         *
         * <p>实测值未知时返回 0，表示"确实不知道"，仍允许兜底选用。
         */
        public int effectiveRank() {
            return Channel.rank(quality);
        }

        /** 节目单没给清晰度标记。这类线路档位视为 0，是最低档的兜底。 */
        public boolean untagged() {
            return quality == null || quality.isEmpty();
        }

        /**
         * 界面展示用的清晰度键：有标记用标记，否则用实测值，都没有返回空串。
         *
         * <p>只用于信息条的胶囊分组，不影响 {@link #quality} 的档位语义。
         */
        public String displayKey() {
            if (quality != null && !quality.isEmpty()) {
                return quality;
            }
            return measured == null ? "" : measured;
        }
    }

    public String name;
    public final String group;
    public String logo;
    public final List<Source> sources = new ArrayList<>();

    public Channel(String name, String group, String logo) {
        this.name = name;
        this.group = group;
        this.logo = logo;
    }

    public String url(int index) {
        return sources.get(index % sources.size()).url;
    }

    public Source source(int index) {
        return sources.get(index % sources.size());
    }

    /** 离线图标在 assets/logos 下的文件名（不含扩展名）。 */
    public String logoKey() {
        if (name.startsWith("CCTV") || name.startsWith("CETV")) {
            // CCTV-1 / CCTV-5+ 在图标库里是 CCTV1 / CCTV5+
            return name.replace("-", "");
        }
        return name;
    }

    /**
     * 按清晰度从高到低排序，界面上的顺序固定下来。
     * 地址随之重排，播放索引仍然指向正确的流。
     */
    public void sortByQuality() {
        Collections.sort(sources, (a, b) -> rank(b.quality) - rank(a.quality));
    }

    /**
     * 按节目单自带的响应耗时升序排列，同一清晰度内最快的排前面。
     *
     * <p>换台时直接用第 0 条起播，响应越快的线路出画面越快。
     * 没有清晰度标记的线路（rank 相同）之间也按响应耗时排，
     * 这样"最快可播"永远是第一条。
     *
     * <p>实测分辨率只作为<b>同档内</b>的次级排序依据：它是事实，
     * 比猜测可信，但绝不能越级盖过节目单的声明档位。
     */
    public void sortBySpeed() {
        Collections.sort(sources, (a, b) -> {
            int qa = rank(a.quality);
            int qb = rank(b.quality);
            if (qa != qb) {
                return qb - qa;
            }
            // 同为无标记（或同档）时，实测分辨率高的排前面
            int ma = rank(a.measured);
            int mb = rank(b.measured);
            if (ma != mb) {
                return mb - ma;
            }
            int ra = a.responseMs > 0 ? a.responseMs : Integer.MAX_VALUE;
            int rb = b.responseMs > 0 ? b.responseMs : Integer.MAX_VALUE;
            return ra - rb;
        });
    }

    static int rank(String q) {
        if (q == null || q.isEmpty()) {
            return 0;
        }
        if (q.startsWith("4K") || q.startsWith("2160")) {
            return 4100;
        }
        try {
            return Integer.parseInt(q.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 从 tvg-name 的标记（如 CCTV1[1080][S]）里取清晰度。 */
    static String qualityOf(String tag) {
        if (tag == null) {
            return null;
        }
        int start = tag.indexOf('[');
        int end = tag.indexOf(']', start + 1);
        if (start < 0 || end < 0) {
            return null;
        }
        String v = tag.substring(start + 1, end).trim();
        if (v.endsWith("p") || v.endsWith("P")) {
            v = v.substring(0, v.length() - 1);
        }
        return v.isEmpty() ? null : v + "P";
    }
}
