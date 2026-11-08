package com.iptv.live.source;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 「已确认播不了」的线路黑名单（规则 6）。
 *
 * <p>节目单里总有相当一部分线路是死的：机房下线、切片 404、或者只有
 * 100~300 KB/s 而 1080 需要 1 MB/s。这些线路每次换台都要重新踩一遍，
 * 用户看到的就是「切过去黑屏十几秒再自己换回来」。
 *
 * <p>所以把<b>真实播放证据</b>判死的地址落盘，下次加载节目单时直接剔除。
 * 证据只有三种，都来自播放而不是一次速度探测：
 * 起播超时、反复跳分片、反复缓冲降档。探测测的是分片开头的突发速率，
 * 能比真实持续速率高四五倍，用它判死会把好源误杀。
 *
 * <p>带 TTL：线路会修好、也会被换掉。永久拉黑等于让频道越用越少，
 * 所以过了 {@link #TTL_MS} 就重新给一次机会。
 */
public final class DeadSources {

    private static final String PREFS = "dead_sources";
    private static final String KEY = "map";
    /** 拉黑多久后重新尝试。节目单通常一天一更，给 3 天足够它换掉坏源。 */
    private static final long TTL_MS = 3L * 24 * 60 * 60 * 1000;
    /** 上限，防止无限增长把 SharedPreferences 撑大。 */
    private static final int LIMIT = 3000;

    private static Map<String, Long> cache;

    private DeadSources() {
    }

    /** 这条线路是否已被判死（且还没过期）。 */
    public static boolean isDead(Context ctx, String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        Long at = map(ctx).get(url);
        if (at == null) {
            return false;
        }
        if (System.currentTimeMillis() - at > TTL_MS) {
            // 过期了，顺手清掉，让它重新参与选源
            Map<String, Long> m = map(ctx);
            m.remove(url);
            save(ctx, m);
            return false;
        }
        return true;
    }

    /** 记一条已被真实播放证伪的线路。 */
    public static void markDead(Context ctx, String url) {
        if (url == null || url.isEmpty()) {
            return;
        }
        Map<String, Long> m = map(ctx);
        m.put(url, System.currentTimeMillis());
        // 超上限时按时间戳淘汰最老的一批，避免无界增长
        if (m.size() > LIMIT) {
            long now = System.currentTimeMillis();
            Iterator<Map.Entry<String, Long>> it = m.entrySet().iterator();
            while (it.hasNext() && m.size() > LIMIT) {
                Map.Entry<String, Long> e = it.next();
                if (now - e.getValue() > TTL_MS / 2) {
                    it.remove();
                }
            }
        }
        save(ctx, m);
    }

    /** 同一条流重新播起来时撤销标记，避免误判永久生效。 */
    public static void revive(Context ctx, String url) {
        if (url == null || url.isEmpty()) {
            return;
        }
        Map<String, Long> m = map(ctx);
        if (m.remove(url) != null) {
            save(ctx, m);
        }
    }

    private static synchronized Map<String, Long> map(Context ctx) {
        if (cache != null) {
            return cache;
        }
        cache = new HashMap<>();
        String raw = prefs(ctx).getString(KEY, "");
        if (raw != null) {
            for (String line : raw.split("\n")) {
                int tab = line.lastIndexOf('\t');
                if (tab <= 0) {
                    continue;
                }
                try {
                    cache.put(line.substring(0, tab),
                            Long.parseLong(line.substring(tab + 1)));
                } catch (NumberFormatException ignored) {
                    // 坏行直接跳过，名单损坏不该影响播放
                }
            }
        }
        return cache;
    }

    private static void save(Context ctx, Map<String, Long> m) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Long> e : m.entrySet()) {
            sb.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
        }
        prefs(ctx).edit().putString(KEY, sb.toString()).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
