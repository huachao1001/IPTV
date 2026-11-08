package com.iptv.live.source;

import com.iptv.live.model.Channel;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.List;

/**
 * 把频道列表写回 M3U 文本。
 *
 * <p><b>为什么需要写回：</b>刷新时不能拿远程清单直接盖掉本地缓存，
 * 而要"旧的 + 新的"合并后再存下来（用户要求"滚雪球一样，把频道越滚越多"）。
 * 合并结果只存在于内存里的 {@link Channel} 对象，要保住就得序列化成 M3U。
 *
 * <p>写出的格式与 {@link PlaylistParser} 的读取规则严格对称：
 * <ul>
 *   <li>清晰度写进 {@code tvg-name} 的方括号（{@code 频道[1080]}），
 *       解析端的 {@link Channel#qualityOf(String)} 取第一个方括号，
 *       所以往返一趟档位不会漂移——这是清晰度上限判定的基础。</li>
 *   <li>分组写在 {@code group-title}，已按投票收敛过，往返稳定。</li>
 *   <li>{@code response-time} 原样带上，换台时的"选最快"依赖它。</li>
 * </ul>
 */
public final class PlaylistWriter {

    private PlaylistWriter() {
    }

    /** 把频道列表写成 M3U 文本；每个频道下按现有顺序输出各条线路。 */
    public static void write(List<Channel> list, OutputStream out) throws IOException {
        BufferedWriter w = new BufferedWriter(new OutputStreamWriter(out, "UTF-8"));
        try {
            w.write("#EXTM3U\n");
            for (Channel c : list) {
                if (c.sources.isEmpty()) {
                    continue;
                }
                for (Channel.Source s : c.sources) {
                    w.write(extinf(c, s));
                    w.write('\n');
                    w.write(s.url);
                    w.write('\n');
                }
            }
            w.flush();
        } finally {
            w.close();
        }
    }

    /** 拼一行 EXTINF。 */
    private static String extinf(Channel c, Channel.Source s) {
        StringBuilder sb = new StringBuilder("#EXTINF:-1");
        sb.append(" tvg-name=\"").append(c.name);
        String tag = bracketOf(s.quality);
        if (tag != null) {
            sb.append('[').append(tag).append(']');
        }
        sb.append('"');
        if (c.logo != null && !c.logo.isEmpty()) {
            sb.append(" tvg-logo=\"").append(c.logo).append('"');
        }
        sb.append(" group-title=\"").append(c.group).append('"');
        if (s.responseMs > 0) {
            sb.append(" response-time=\"").append(s.responseMs).append("ms\"");
        }
        sb.append(',').append(c.name);
        return sb.toString();
    }

    /**
     * 把内部档位换算成方括号里写的值。
     *
     * <p>{@code 4K} 必须写成 {@code 2160}：解析端只认数字方括号，
     * 写 {@code [4K]} 会被读成 {@code 4KP}，档位就从"4K 特判"掉成普通数值档。
     */
    private static String bracketOf(String quality) {
        if (quality == null || quality.isEmpty()) {
            return null;
        }
        if (quality.startsWith("4K")) {
            return "2160";
        }
        String digits = quality.replaceAll("[^0-9]", "");
        return digits.isEmpty() ? null : digits;
    }
}
