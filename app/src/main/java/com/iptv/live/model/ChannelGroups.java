package com.iptv.live.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 播放列表按 group-title 分组后的结果。 */
public class ChannelGroups {

    /** 分组顺序与展示名（顺序即导航栏顺序）。 */
    private static final String[][] ORDER = {
            {"央视频道", "央视频道"},
            {"卫视频道", "卫视频道"},
            {"影视频道", "影视频道"},
            {"电影频道", "影视频道"},
            {"影视", "影视频道"},
            {"卡通频道", "卡通频道"},
            {"体育频道", "体育频道"},
            {"科教频道", "科教频道"},
            {"娱乐综艺", "娱乐综艺"},
            {"其他频道", "其他频道"},
    };

    /** 分组名 -> 频道列表，保持播放列表顺序。 */
    public final Map<String, List<Channel>> groups = new LinkedHashMap<>();

    public static ChannelGroups from(List<Channel> channels) {
        ChannelGroups g = new ChannelGroups();
        for (Channel c : channels) {
            String key = canonical(c.group);
            List<Channel> list = g.groups.get(key);
            if (list == null) {
                list = new ArrayList<>();
                g.groups.put(key, list);
            }
            list.add(c);
        }
        g.reorder();
        return g;
    }

    /** 把源里的各种分组写法收敛到统一的名字。 */
    public static String canonical(String group) {
        if (group == null || group.trim().isEmpty()) {
            return "其他频道";
        }
        // 节目单里存在带尾随空格的 "央视台 "
        String g = group.trim();
        if ("央视台".equals(g)) {
            return "央视频道";
        }
        if ("影视".equals(g) || "电影频道".equals(g)) {
            return "影视频道";
        }
        // 节目单里偶发的英文分组名，统一并入其他
        if (isPlaceholderGroup(g)) {
            return "其他频道";
        }
        return g;
    }

    /**
     * 节目单里"没填分组"时的占位名。
     *
     * <p>上游清单里同一个频道的各条线路分组写法并不统一，其中
     * {@code General} / {@code News} / {@code Other} / {@code Misc} 这类
     * 是生成工具塞的英文占位，不是真实归类。它们<b>不能参与分组投票</b>
     * （否则 CCTV-1 会被那单独一条 {@code General} 带走，单独形成一个分组），
     * 也不能作为最终分组名（分组栏里冒出英文 Tab）。
     */
    public static boolean isPlaceholderGroup(String group) {
        if (group == null || group.trim().isEmpty()) {
            return true;
        }
        String g = group.trim();
        return "General".equalsIgnoreCase(g)
                || "News".equalsIgnoreCase(g)
                || "Other".equalsIgnoreCase(g)
                || "Others".equalsIgnoreCase(g)
                || "Misc".equalsIgnoreCase(g)
                || "Undefined".equalsIgnoreCase(g)
                || "Default".equalsIgnoreCase(g);
    }

    /** 按预设顺序重排分组，未列出的放在最后。 */
    private void reorder() {
        Map<String, List<Channel>> sorted = new LinkedHashMap<>();
        for (String[] pair : ORDER) {
            List<Channel> list = groups.get(pair[0]);
            if (list != null && !list.isEmpty()) {
                sorted.put(pair[0], list);
            }
        }
        for (Map.Entry<String, List<Channel>> e : groups.entrySet()) {
            if (!sorted.containsKey(e.getKey()) && !e.getValue().isEmpty()) {
                sorted.put(e.getKey(), e.getValue());
            }
        }
        groups.clear();
        groups.putAll(sorted);
    }

    /** 分组 TAB 上显示的名字。 */
    public static String displayName(String group) {
        for (String[] pair : ORDER) {
            if (pair[0].equals(group)) {
                return pair[1];
            }
        }
        return group;
    }

    /** 排序用的分组优先级列表。 */
    public static List<String> order() {
        List<String> list = new ArrayList<>();
        for (String[] pair : ORDER) {
            if (!list.contains(pair[0])) {
                list.add(pair[0]);
            }
        }
        return list;
    }

    public int size() {
        return groups.size();
    }
}
