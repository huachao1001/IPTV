package com.iptv.live.source;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;

/**
 * 每条线路的"体检记录"：上次在清单里出现的时间、上次真的播起来的时间。
 *
 * <p><b>为什么要记这个。</b>刷新时不删旧线路（用户要求"滚雪球一样越滚越多"），
 * 但如果只增不减，几年下来缓存会堆进几千条早已下线的地址，
 * 换台时排序、探测、界面都要为它们付代价。
 *
 * <p>所以给每条线路记两枚时间戳，用它们做<b>唯一</b>的淘汰依据：
 * <ol>
 *   <li>{@code seen} —— 最近一次在<b>新下载的清单</b>里看到它。上游还在挂它，
 *       说明线路大概率还活着，一定要留。</li>
 *   <li>{@code good} —— 最近一次<b>真的播出画面</b>（播放器进 {@code STATE_READY}）。
 *       这是最硬的存活证据，比任何探测都准。</li>
 * </ol>
 * 两者都很久没有更新时（{@link #RETIRE_MS}），才认为这条线路确实没了，淘汰掉。
 *
 * <p>注意与 {@link DeadSources} 分工不同：那个是"播过、明确播不了"的黑名单，
 * TTL 3 天；这里是"长期没有任何存活证据"的冷藏名单，14 天。
 * 两者都只信真实播放，不信一次速度探测。
 */
public final class SourceHealth {

    private static final String PREFS = "source_health";
    private static final String KEY = "map";

    /**
     * 多久没有任何存活证据就淘汰。
     *
     * <p>取 14 天：上游清单通常一天一更，一条线路从"还在清单里"到
     * "彻底消失"往往要经过好几天的抖动（哪天源站抽风就不见了，第二天又回来）。
     * 太短会把临时掉线的线路误删，太长则缓存无限膨胀。
     */
    private static final long RETIRE_MS = 14L * 24 * 60 * 60 * 1000;

    /** 上限，防止 SharedPreferences 无限增长。 */
    private static final int LIMIT = 6000;

    /** url -> [seen, good] 两个时间戳。 */
    private static Map<String, long[]> cache;

    private SourceHealth() {
    }

    /** 这条线路在新下载的清单里出现过，记一笔。 */
    public static synchronized void markSeen(Context ctx, String url) {
        if (url == null || url.isEmpty()) {
            return;
        }
        long[] v = entry(ctx, url);
        v[0] = System.currentTimeMillis();
        save(ctx);
    }

    /**
     * 批量记"在新清单里出现过"。
     *
     * <p>必须批量：{@link #save} 会把整张表序列化一次，一条线路写一次的话
     * 一次刷新（250 条以上）就是 250 次全表序列化，白费几秒 CPU——
     * 而这个时间是排在起播路径前面的。
     */
    public static synchronized void markSeenAll(Context ctx, java.util.List<String> urls) {
        long now = System.currentTimeMillis();
        boolean any = false;
        for (String url : urls) {
            if (url == null || url.isEmpty()) {
                continue;
            }
            entry(ctx, url)[0] = now;
            any = true;
        }
        if (any) {
            save(ctx);
        }
    }

    /** 这条线路真的播出了画面，记一笔。这是最硬的存活证据。 */
    public static synchronized void markGood(Context ctx, String url) {
        if (url == null || url.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        long[] v = entry(ctx, url);
        v[1] = now;
        save(ctx);
    }

    /**
     * 后台体检确认这条线路仍能取到清单，续一次命（写 {@code seen}）。
     *
     * <p>与 {@link #markGood} 的区别：这里证明的是"源站还挂着它"，
     * 不是"它能喂得动高清"。用来阻止"上游这轮刚好没列它"的老线路
     * 因为连续缺席被淘汰——它明明还活着。
     */
    public static synchronized void markAlive(Context ctx, String url) {
        markSeen(ctx, url);
    }

    /**
     * 这条线路是否已经没有存活证据、可以淘汰了。
     *
     * <p>从没记录过的线路<b>不淘汰</b>：它可能是刚合并进来还没轮到播的新源，
     * 也可能是升级前就存在的老源。宁可多留一轮，也不要凭"没有记录"就删。
     */
    public static synchronized boolean retired(Context ctx, String url) {
        long[] v = map(ctx).get(url);
        if (v == null) {
            return false;
        }
        long last = Math.max(v[0], v[1]);
        return System.currentTimeMillis() - last > RETIRE_MS;
    }

    /** 淘汰窗口本身，供调用方判断"最近有没有拿到新证据"。 */
    public static long retireWindowMs() {
        return RETIRE_MS;
    }

    /** 淘汰后把记录也清掉，别让存储只增不减。 */
    public static synchronized void forget(Context ctx, String url) {
        if (url != null && map(ctx).remove(url) != null) {
            save(ctx);
        }
    }

    private static long[] entry(Context ctx, String url) {
        Map<String, long[]> m = map(ctx);
        long[] v = m.get(url);
        if (v == null) {
            v = new long[2];
            m.put(url, v);
        }
        return v;
    }

    private static synchronized Map<String, long[]> map(Context ctx) {
        if (cache != null) {
            return cache;
        }
        cache = new HashMap<>();
        String raw = prefs(ctx).getString(KEY, "");
        if (raw != null) {
            for (String line : raw.split("\n")) {
                String[] f = line.split("\t");
                if (f.length >= 3) {
                    try {
                        cache.put(f[0], new long[]{Long.parseLong(f[1]), Long.parseLong(f[2])});
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        return cache;
    }

    private static void save(Context ctx) {
        Map<String, long[]> m = cache;
        if (m == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Map.Entry<String, long[]> e : m.entrySet()) {
            if (n++ >= LIMIT) {
                break;
            }
            long[] v = e.getValue();
            sb.append(e.getKey()).append('\t').append(v[0]).append('\t').append(v[1]).append('\n');
        }
        prefs(ctx).edit().putString(KEY, sb.toString()).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
