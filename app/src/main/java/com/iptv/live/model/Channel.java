package com.iptv.live.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 一个频道聚合了同一频道的多路流，界面上按清晰度区分。 */
public class Channel {

    /** 线路可信度：URL 代号与清单名字一致，铁证。 */
    public static final int TRUST_VERIFIED = 2;
    /** 线路可信度：没有任何线索，无从判断。 */
    public static final int TRUST_UNKNOWN = 1;
    /** 线路可信度：线索与清单名字矛盾，很可能是标错了。 */
    public static final int TRUST_SUSPECT = 0;

    /** 一路流：播放地址 + 已知清晰度。 */
    public static class Source {
        public final String url;
        /**
         * 节目单声明的清晰度（如 {@code [1080]} 标签），购选源/排序/清晰度上限都用它。
         *
         * <p><b>它必须稳定。</b>一旦被实测值改写，{@code QualityCap} 依据的档位
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
         * 这条线路的可信度分档，见 {@code ChannelStore#assignTrust()}。
         *
         * <p>换台时优先选分高的线路，因为"名字与实际播出内容不一致"是上游清单
         * 真实存在的问题（选「山东卫视」播英超、选「广东卫视」播 CCTV-11），
         * 而本地无法从流内部读出台名（国内 IPTV 流的 SDT 表只有 {@code Service01}）。
         *
         * <p><b>分档依据全部来自清单自身的重复信息</b>，不猜测、不联网识别：
         * <ol>
         *   <li>{@link #TRUST_VERIFIED}：URL 里带厂商写的台名代号，且与清单名字一致。
         *       这是<b>唯一可信的正向证据</b>，实测 120 条带代号的线路里 119 条自洽。</li>
         *   <li>{@link #TRUST_UNKNOWN}：没有代号，也没有冲突线索，无从判断。</li>
         *   <li>{@link #TRUST_SUSPECT}：代号与名字矛盾，或同频道号槽位被多个台名共用。</li>
         * </ol>
         *
         * <p>排序只降权、<b>不删除</b>：不同机房的编号方案并不通用，
         * 同一 {@code /hls/6/index.m3u8} 在 A 机房是 CCTV-5+、B 机房是 CCTV-6，
         * 两边都可能对，删掉会误杀能播的线路。它仍留在列表里当备胎。
         */
        public int trust = TRUST_UNKNOWN;

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
     * 排序：<b>可信度 → 清晰度 → 实测分辨率 → 响应耗时</b>。
     *
     * <p>换台时直接用第 0 条起播，所以这个顺序就是"先试哪条线"。
     *
     * <p><b>为什么可信度排最前：</b>清单里确实存在错标——实测选「山东卫视」
     * 播英超、选「黑龙江卫视」播美剧、选「广东卫视」播 CCTV-11 戏曲。
     * 而带台名代号的线路（{@code ?id=sdwshd}、{@code /live/hnws.m3u8}）
     * 是清单自身的正向证据，实测 120 条里 119 条与名字自洽。
     * 让它们优先，等于用清单自己的信息纠正清单的错误。
     *
     * <p>可信度只在<b>同一清晰度档内</b>比较吗？——不是。
     * 实测山东卫视的 1080P 线路统统没有代号、且播的是英超，
     * 而带 {@code sdwshd} 代号的 1080P 线路排在它们后面。
     * 若把可信度限制在档内比较，正确的线路永远轮不到，
     * 所以可信度必须跨档优先：宁可先播一条确定的 576，
     * 也不要先播一条错的 1080——后者用户一眼就能看出"这不是我要的台"。
     */
    public void sortBySpeed() {
        Collections.sort(sources, (a, b) -> {
            if (a.trust != b.trust) {
                return b.trust - a.trust;
            }
            int qa = rank(a.quality);
            int qb = rank(b.quality);
            if (qa != qb) {
                return qb - qa;
            }
            // 同档内：实测分辨率高的排前面
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

    public static int rank(String q) {
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
    public static String qualityOf(String tag) {
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
