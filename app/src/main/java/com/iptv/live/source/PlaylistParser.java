package com.iptv.live.source;

import com.iptv.live.model.Channel;
import com.iptv.live.model.ChannelGroups;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 解析 M3U 播放列表，并把同一频道的多个源合并成一个 Channel。 */
public final class PlaylistParser {

    private PlaylistParser() {
    }

    /** 一条 EXTINF + 地址的原始记录，分组要等全部读完投票后才知道。 */
    private static final class Entry {
        final String key;
        final String display;
        final String group;
        final String logo;
        final String quality;
        final int responseMs;
        final String url;

        Entry(String key, String display, String group, String logo,
              String quality, int responseMs, String url) {
            this.key = key;
            this.display = display;
            this.group = group;
            this.logo = logo;
            this.quality = quality;
            this.responseMs = responseMs;
            this.url = url;
        }
    }

    /**
     * 解析 M3U 播放列表，并把同一频道的多个源合并成一个 Channel。
     *
     * <p><b>分组是投票出来的，不是"第一条说了算"。</b>原实现用第一条线路的
     * {@code group-title} 决定整个频道的分组，而节目单里同一频道的各条线路
     * 分组写法并不统一（实测 {@code cache2.m3u8}：CCTV1 有 5 条写"央视频道"，
     * 另有 1 条写 {@code General}；CCTV5 有 7 条"央视频道"、1 条"体育频道"）。
     * 谁排在前面纯属节目单生成顺序，于是 CCTV-1 会因为那单独一条
     * {@code General} 被整个搬进一个叫 {@code General} 的独立分组里，
     * 分组栏里凭空多一项，用户自己的频道也找不到。
     *
     * <p>现在改成两遍：先收集所有线路，再对每个频道的分组<b>按票数取多数</b>，
     * 最后才创建 Channel。票数相同时按在文件里出现的先后取前者。
     */
    public static List<Channel> parse(InputStream in) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        String line;
        String pendingName = null;
        String pendingGroup = null;
        String pendingLogo = null;
        String pendingTag = null;
        String pendingQuality = null;
        int pendingMs = 0;
        List<Entry> entries = new ArrayList<>();

        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#EXTINF")) {
                pendingTag = attr(line, "tvg-name");
                pendingLogo = attr(line, "tvg-logo");
                pendingGroup = attr(line, "group-title");
                pendingMs = parseInt(attr(line, "response-time"));
                int comma = line.lastIndexOf(',');
                pendingName = comma >= 0 ? line.substring(comma + 1).trim() : null;
                // 有 [1080] 这类标记就以标记为准，它比频道名可靠
                pendingQuality = Channel.qualityOf(pendingTag);
                continue;
            }
            if (line.startsWith("#")) {
                continue;
            }
            if (pendingName == null) {
                continue;
            }
            // 节目单末尾会带一条"更新时间"占位，直接丢弃
            if ("更新时间".equals(pendingGroup)
                    || line.contains("example.com/update_time")) {
                pendingName = null;
                continue;
            }
            String display = cleanName(pendingName);
            if (display.isEmpty()) {
                pendingName = null;
                continue;
            }
            String key = normalize(pendingName);
            entries.add(new Entry(key, displayName(key, display), pendingGroup,
                    pendingLogo,
                    // 档位只认可信来源：tvg-name 的 [1080] 标记，其次频道名里的 (720p)。
                    // URL 里出现 hd/1080 只是线索（很多老机房的路径凑巧带 hd），
                    // 不足以决定档位——它会影响清晰度上限，猜错就会把能播的线路挡掉。
                    // 所以 URL 猜测只写 measured，供显示和同档内排序。
                    pendingQuality != null ? pendingQuality : qualityFromName(pendingName, null),
                    pendingMs, line));
            pendingName = null;
            pendingTag = null;
            pendingQuality = null;
            pendingGroup = null;
            pendingLogo = null;
            pendingMs = 0;
        }
        reader.close();

        // 第二遍：按票数决定每个频道的分组
        Map<String, String> groupOf = voteGroups(entries);
        Map<String, Channel> merged = new LinkedHashMap<>();
        for (Entry e : entries) {
            Channel ch = merged.get(e.key);
            if (ch == null) {
                ch = new Channel(e.display, guessGroup(e.key, groupOf.get(e.key)), e.logo);
                merged.put(e.key, ch);
            }
            boolean dup = false;
            for (Channel.Source s : ch.sources) {
                if (s.url.equals(e.url)) {
                    dup = true;
                    break;
                }
            }
            if (dup) {
                continue;
            }
            Channel.Source s = new Channel.Source(e.url);
            s.quality = e.quality;
            if (s.untagged()) {
                s.measured = qualityFromUrl(e.url);
            }
            s.responseMs = e.responseMs;
            ch.sources.add(s);
        }
        List<Channel> result = new ArrayList<>(merged.values());
        for (Channel c : result) {
            // 清晰度高优先，同清晰度内响应快的排前面：
            // 换台时直接拿第一条起播，出画面最快
            c.sortBySpeed();
        }
        return result;
    }

    /**
     * 对每个频道的各条线路分组做投票，取票数最多的那个。
     *
     * <p>只统计"可用"的分组名：空值和 {@code General} 这类英文占位分组
     * 不算票（它们是节目单没填对，不代表频道的真实归类）。
     * 所有线路都没有有效分组时才回退到票数最多的原始值，
     * 由 {@link ChannelGroups#canonical(String)} 收敛。
     */
    private static Map<String, String> voteGroups(List<Entry> entries) {
        Map<String, Map<String, Integer>> votes = new LinkedHashMap<>();
        Map<String, String> firstRaw = new LinkedHashMap<>();
        for (Entry e : entries) {
            Map<String, Integer> v = votes.get(e.key);
            if (v == null) {
                v = new LinkedHashMap<>();
                votes.put(e.key, v);
            }
            String g = e.group == null ? "" : e.group.trim();
            if (!g.isEmpty()) {
                // 不能用 Map.merge(g, 1, Integer::sum)：merge 和 java.util.function
                // 都是 API 24 才有的，电视是 Android 6（API 23），
                // 会抛 NoClassDefFoundError: PlaylistParser$$ExternalSyntheticLambda0，
                // 表现为「装完点开立刻闪退回桌面」。手动累加等价且老版本安全。
                Integer prev = v.get(g);
                v.put(g, prev == null ? 1 : prev + 1);
            }
            if (!firstRaw.containsKey(e.key)) {
                firstRaw.put(e.key, g);
            }
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Integer>> e : votes.entrySet()) {
            Map<String, Integer> v = e.getValue();
            String best = null;
            int bestN = 0;
            int validBestN = 0;
            String validBest = null;
            for (Map.Entry<String, Integer> c : v.entrySet()) {
                String g = c.getKey();
                int n = c.getValue();
                if (n > bestN) {
                    bestN = n;
                    best = g;
                }
                if (!ChannelGroups.isPlaceholderGroup(g) && n > validBestN) {
                    validBestN = n;
                    validBest = g;
                }
            }
            // 有效分组优先：General/News 这类占位名即使票数更多也不算数
            out.put(e.getKey(), validBest != null ? validBest
                    : best != null ? best : firstRaw.get(e.getKey()));
        }
        return out;
    }

    /**
     * 去掉 "CCTV-1 (720p)"、"CCTV-9 (576i)"、"CCTV1 高清" 这类画质后缀。
     *
     * <p>必须同时认 {@code p} 和 {@code i} 两种后缀：节目单里隔行源写成
     * {@code (576i)}，只认 {@code p} 的话它就去不掉，normalize 后得到
     * {@code CCTV9(576I)}，与正常的 {@code CCTV9} 是两个键——
     * 分组列表里于是出现两个 CCTV-9（一个是干净名，一个带 {@code (576i)}）。
     */
    static String cleanName(String raw) {
        String s = raw.replaceAll("[（(]\\s*(\\d{3,4}[pPiI]?|HD|SD|高清|超清|标清)\\s*[)）]", "");
        s = s.replaceAll("\\s+(HD|SD|高清|超清|标清)$", "");
        s = s.replaceAll("\\[\\s*\\d{3,4}[pPiI]?\\s*\\]", "");
        return s.trim();
    }

    /** 归一化后用于合并同名频道。 */
    static String normalize(String raw) {
        String s = cleanName(raw).toUpperCase(Locale.US);
        // 加号必须在去符号前记下来，否则 CCTV5+ 会被并进 CCTV5
        boolean plus = s.contains("+") || s.contains("PLUS");
        s = s.replaceAll("[\\s\\-_·•.]", "");

        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^(CCTV|CETV)(\\d{1,2})(.*)$").matcher(s);
        if (m.matches()) {
            String rest = m.group(3);
            // 只吸收"综合/财经/纪录"这类频道名后缀，"测试"之类的另算一个频道
            if (rest.isEmpty() || rest.equals("+") || rest.matches("[\\u4e00-\\u9fa5]+")) {
                return m.group(1) + m.group(2) + (plus ? "+" : "");
            }
        }
        s = s.replaceAll("(高清|超清|标清|频道|直播)$", "");
        return plus ? s + "+" : s;
    }

    /** 统一 CCTV/CETV 的显示名，其余保留原始名。 */
    private static String displayName(String key, String fallback) {
        if (key.startsWith("CCTV")) {
            return "CCTV-" + key.substring(4);
        }
        if (key.startsWith("CETV")) {
            return "CETV-" + key.substring(4);
        }
        return fallback;
    }

    /** 源里部分条目缺 group-title，按频道名补一个分组，避免落到"其他"。 */
    private static String guessGroup(String key, String raw) {
        String group = raw;
        // 自己确实没有有效分组信息时才猜，节目单给的分组优先
        if (ChannelGroups.isPlaceholderGroup(group) || "其他".equals(group)) {
            if (key.startsWith("CCTV") || key.startsWith("CGTN")) {
                group = "央视频道";
            } else if (key.endsWith("卫视")) {
                group = "卫视频道";
            }
        }
        return ChannelGroups.canonical(group == null || group.isEmpty() ? "其他频道" : group);
    }

    /** 频道名里带 "(720p)" / "(576i)" 这类后缀时优先采用，它比 URL 更可信。 */
    private static String qualityFromName(String name, String fallback) {
        if (name == null) {
            return fallback;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("[（(]\\s*(\\d{3,4})\\s*[pPiI]?\\s*[)）]").matcher(name);
        if (m.find()) {
            return m.group(1) + "P";
        }
        return fallback;
    }

    /** URL 里常带 1080p/fhd 之类的线索，用于补清晰度。 */
    private static String qualityFromUrl(String url) {
        String u = url.toLowerCase(Locale.US);
        if (u.contains("4k") || u.contains("2160")) {
            return "4K";
        }
        if (u.contains("1080") || u.contains("fhd")) {
            return "1080P";
        }
        if (u.contains("720") || u.contains("hd")) {
            return "720P";
        }
        if (u.contains("576") || u.contains("sd")) {
            return "576P";
        }
        return "";
    }

    private static int parseInt(String s) {
        if (s == null) {
            return 0;
        }
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.append(c);
            } else if (digits.length() > 0) {
                break;
            }
        }
        if (digits.length() == 0) {
            return 0;
        }
        try {
            return Integer.parseInt(digits.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String attr(String line, String key) {
        int i = line.indexOf(key + "=\"");
        if (i < 0) {
            return null;
        }
        int start = i + key.length() + 2;
        int end = line.indexOf('"', start);
        return end > start ? line.substring(start, end) : null;
    }
}
