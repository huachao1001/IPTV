package com.iptv.live.source;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;

/**
 * 记录每路流实测到的分辨率。
 * 节目单本身不带清晰度标记，播放一次后记下来，下次启动直接显示。
 */
public final class QualityMemory {

    private static final String PREFS = "quality";
    private static final String KEY = "map";
    /** 最多记住的条目数，避免无上限增长。 */
    private static final int LIMIT = 1200;

    private static Map<String, String> cache;

    private QualityMemory() {
    }

    public static String get(Context ctx, String url) {
        return map(ctx).get(url);
    }

    public static void put(Context ctx, String url, String label) {
        if (url == null || label == null || label.isEmpty()) {
            return;
        }
        Map<String, String> m = map(ctx);
        if (label.equals(m.get(url))) {
            return;
        }
        m.put(url, label);
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
        return ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 分辨率高度转成界面上的清晰度文字。 */
    public static String labelOf(int height) {
        if (height <= 0) {
            return null;
        }
        if (height >= 2000) {
            return "4K";
        }
        if (height >= 1000) {
            return "1080P";
        }
        if (height >= 700) {
            return "720P";
        }
        if (height >= 500) {
            return "576P";
        }
        if (height >= 400) {
            return "480P";
        }
        return height + "P";
    }
}
