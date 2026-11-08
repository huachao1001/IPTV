package com.iptv.live.source;

import androidx.annotation.Nullable;

/**
 * HLS 地址工具：拼相对路径、从清单里取第一个分片。
 *
 * <p><b>为什么自己写。</b>{@code android.net.Uri} 没有 {@code resolve} 方法
 * （那是 {@code java.net.URI} 才有的，而且它对包含中文/空格的地址会抛异常），
 * 所以只能按最后一个 {@code /} 自己算。节目单里三种写法都出现过：
 * <ul>
 *   <li>绝对地址 {@code http://host/a/b.ts}</li>
 *   <li>站内绝对路径 {@code /live/a/b.ts}</li>
 *   <li>协议相对 {@code //cdn/a/b.ts}</li>
 * </ul>
 *
 * <p>预取（{@code PlaybackCache}）和线路体检（{@link SourceAudit}）
 * 都要做同一件事，两处各写一份迟早不一致，所以收在这里。
 */
public final class HlsUrls {

    private HlsUrls() {
    }

    /** 清单里第一个分片行，没有则返回 {@code null}。 */
    @Nullable
    public static String firstSegment(@Nullable String playlist) {
        if (playlist == null) {
            return null;
        }
        for (String line : playlist.split("\\r?\\n")) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("#")) {
                continue;
            }
            return s;
        }
        return null;
    }

    /**
     * 把清单里的相对地址补成绝对地址。
     *
     * @param base 清单自身的地址
     * @param rel  清单里读到的地址
     */
    @Nullable
    public static String resolve(@Nullable String base, @Nullable String rel) {
        if (base == null || rel == null || rel.isEmpty()) {
            return null;
        }
        if (rel.startsWith("http://") || rel.startsWith("https://")) {
            return rel;
        }
        try {
            if (rel.startsWith("//")) {
                int colon = base.indexOf("://");
                String scheme = colon > 0 ? base.substring(0, colon + 1) : "http:";
                return scheme + rel;
            }
            int q = rel.indexOf('?');
            String pathPart = q >= 0 ? rel.substring(0, q) : rel;
            String queryPart = q >= 0 ? rel.substring(q) : "";
            String dir;
            if (pathPart.startsWith("/")) {
                // 站内绝对路径：从 base 的 scheme://host 之后接上
                int afterHost = base.indexOf('/', base.indexOf("://") + 3);
                dir = afterHost > 0 ? base.substring(0, afterHost) : base;
            } else {
                int slash = base.lastIndexOf('/');
                dir = slash > 0 ? base.substring(0, slash + 1) : base + "/";
            }
            return dir + pathPart + queryPart;
        } catch (Exception e) {
            return null;
        }
    }

    /** 内容看起来是不是一个 M3U 播放列表。 */
    public static boolean isPlaylist(@Nullable String head) {
        return head != null && (head.contains("#EXTM3U") || head.contains("#EXTINF"));
    }
}
