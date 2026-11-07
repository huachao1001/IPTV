package com.iptv.live;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 一个频道聚合了同一频道的多路流，界面上按清晰度区分。 */
public class Channel {

    /** 一路流：播放地址 + 已知清晰度。 */
    public static class Source {
        public final String url;
        /** 解析出或实测到的清晰度，如 1080P；未知为空。 */
        public String quality;
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

        /** 界面上显示的清晰度文字，未知时显示"自动"。 */
        public String label() {
            return quality == null || quality.isEmpty() ? "自动" : quality;
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
