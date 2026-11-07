package com.iptv.live;

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

    public static List<Channel> parse(InputStream in) throws IOException {
        // 键为归一化频道名，保证同一频道聚合到一起
        Map<String, Channel> merged = new LinkedHashMap<>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        String line;
        String pendingName = null;
        String pendingGroup = null;
        String pendingLogo = null;
        String pendingTag = null;
        String pendingQuality = null;
        int pendingMs = 0;

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
                continue;
            }
            String key = normalize(pendingName);
            Channel ch = merged.get(key);
            if (ch == null) {
                ch = new Channel(displayName(key, display), guessGroup(key, pendingGroup),
                        pendingLogo);
                merged.put(key, ch);
            }
            boolean dup = false;
            for (Channel.Source s : ch.sources) {
                if (s.url.equals(line)) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                Channel.Source s = new Channel.Source(line);
                // 档位只认可信来源：tvg-name 的 [1080] 标记，其次频道名里的 (720p)。
                // URL 里出现 hd/1080 只是线索（很多老机房的路径凑巧带 hd），
                // 不足以决定档位——它会影响清晰度上限，猜错就会把能播的线路挡掉。
                // 所以 URL 猜测只写 measured，供显示和同档内排序。
                s.quality = pendingQuality != null ? pendingQuality
                        : qualityFromName(pendingName, null);
                if (s.untagged()) {
                    s.measured = qualityFromUrl(line);
                }
                s.responseMs = pendingMs;
                ch.sources.add(s);
            }
            pendingName = null;
            pendingTag = null;
            pendingQuality = null;
            pendingGroup = null;
            pendingLogo = null;
            pendingMs = 0;
        }
        reader.close();
        List<Channel> result = new ArrayList<>(merged.values());
        for (Channel c : result) {
            // 清晰度高优先，同清晰度内响应快的排前面：
            // 换台时直接拿第一条起播，出画面最快
            c.sortBySpeed();
        }
        return result;
    }

    /** 去掉 "CCTV-1 (720p)"、"CCTV1 高清" 这类画质后缀。 */
    static String cleanName(String raw) {
        String s = raw.replaceAll("[（(]\\s*(\\d{3,4}p?|HD|SD|高清|超清|标清)\\s*[)）]", "");
        s = s.replaceAll("\\s+(HD|SD|高清|超清|标清)$", "");
        s = s.replaceAll("\\[\\s*\\d{3,4}\\s*\\]", "");
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
        // 只在自己没有分组信息时才猜，节目单给的分组优先
        if (group == null || group.isEmpty() || "其他".equals(group)) {
            if (key.startsWith("CCTV") || key.startsWith("CGTN")) {
                group = "央视频道";
            } else if (key.endsWith("卫视")) {
                group = "卫视频道";
            }
        }
        return ChannelGroups.canonical(group == null || group.isEmpty() ? "其他频道" : group);
    }

    /** 频道名里带 "(720p)" 这类后缀时优先采用，它比 URL 更可信。 */
    private static String qualityFromName(String name, String fallback) {
        if (name == null) {
            return fallback;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("[（(]\\s*(\\d{3,4})\\s*[pP]?\\s*[)）]").matcher(name);
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
