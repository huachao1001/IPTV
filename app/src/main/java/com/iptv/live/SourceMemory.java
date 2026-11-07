package com.iptv.live;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;

/**
 * 记住每个频道<b>上一次真正播起来的线路</b>。
 *
 * <p>换台慢的根因不是网络，而是每次都要先把同一频道的十几路流全部探一遍
 * （{@code SourceProber} 的总预算是 4.5 秒），探完才肯准备播放器。
 * 但用户来回切的多半就是那十几个台，线路早就被验证过了。
 *
 * <p>这里把"验证过的线路"落盘：下次换到同一频道直接起播，
 * 探测改到后台做精修，不再挡在起播前面。
 */
public final class SourceMemory {

    private static final String PREFS = "source_mem";
    private static final String KEY = "map";
    /** 最多记住的频道数，防止无上限增长。 */
    private static final int LIMIT = 600;

    private static Map<String, String> cache;

    private SourceMemory() {
    }

    /** @return 该频道上次播成功的线路地址；没记录过返回 null。 */
    public static String get(Context ctx, String channel) {
        if (channel == null) {
            return null;
        }
        return map(ctx).get(channel);
    }

    /** 记录某频道当前可用的线路。 */
    public static void put(Context ctx, String channel, String url) {
        if (channel == null || url == null || url.isEmpty()) {
            return;
        }
        Map<String, String> m = map(ctx);
        if (url.equals(m.get(channel))) {
            return;
        }
        m.put(channel, url);
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (n++ >= LIMIT) {
                break;
            }
            sb.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
        }
        prefs(ctx).edit().putString(KEY, sb.toString()).apply();
    }

    /** 线路失效时清掉，避免一直拿坏地址起播。 */
    public static void remove(Context ctx, String channel) {
        if (channel == null) {
            return;
        }
        Map<String, String> m = map(ctx);
        if (m.remove(channel) != null) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : m.entrySet()) {
                sb.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
            }
            prefs(ctx).edit().putString(KEY, sb.toString()).apply();
        }
    }

    private static synchronized Map<String, String> map(Context ctx) {
        if (cache != null) {
            return cache;
        }
        cache = new HashMap<>();
        String raw = prefs(ctx).getString(KEY, "");
        if (raw != null) {
            for (String line : raw.split("\n")) {
                int tab = line.indexOf('\t');
                if (tab > 0) {
                    cache.put(line.substring(0, tab), line.substring(tab + 1));
                }
            }
        }
        return cache;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
