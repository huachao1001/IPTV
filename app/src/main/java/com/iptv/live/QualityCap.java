package com.iptv.live;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;

/**
 * 每个频道「清晰度上限」的记忆：能让播放长期稳定的最高清晰度。
 *
 * <p><b>为什么需要它。</b>节目单里同一频道常挂 1080/720/576 三档，
 * 1080 的标签是可信的（实测 {@code 222.223.41.27} 确实是 1920×1080），
 * 但这条路要 1.0 MB/s，而本机实测持续只有 0.42~0.54 MB/s——
 * 起播能出画面，播十几秒就开始跳分片。
 *
 * <p>所以策略是「先试最高清，卡了再降」。但<b>降下去之后必须锁住</b>：
 * <ul>
 *   <li>{@code SourceProber.pickBetter()} 每隔几秒就在后台探一遍，
 *       它按"速率余量"挑最优，1080 一旦偶尔测出高突发速率就会被选中，
 *       于是刚降下来又跳回 1080，再卡、再降——用户看到的就是画面反复重建；</li>
 *   <li>换台回来、重连、静默卡死恢复都会重新选源，同样会再撞一次 1080。</li>
 * </ul>
 * 因此这里存一个<b>上限档位</b>：超过上限的线路在选源阶段就被排除，
 * 所有路径（起播、后台精修、失败重挑）都读同一份上限，不可能自行升档。
 *
 * <p>上限带冷却：{@link #CAP_TTL_MS} 之后自动失效，重新给高清一次机会。
 * 这样网络变好（换了宽带、机房恢复）时能自己升回去，而不是永久钉死在低清。
 */
public final class QualityCap {

    private static final String PREFS = "quality_cap";
    /** 上限放在 map 里：频道名 + \t + 档位 + \t + 生效时刻。 */
    private static final String KEY = "map";
    /** 上限的有效期。到期后重新允许尝试更高清晰度。 */
    private static final long CAP_TTL_MS = 3L * 60 * 60 * 1000;
    /** 最多记住的频道数。 */
    private static final int LIMIT = 600;

    /** 频道名 -> 上限档位（{@link Channel#rank(String)} 的返回值）。 */
    private static Map<String, Integer> ranks;
    /** 频道名 -> 上限生效时刻。 */
    private static Map<String, Long> stamps;

    private QualityCap() {
    }

    /**
     * 该频道允许的最高清晰度档位；{@link Integer#MAX_VALUE} 表示不限。
     *
     * <p>过期记录视为不限，并顺手清掉，避免旧上限一直压着。
     */
    public static int capRank(Context ctx, String channel) {
        if (channel == null) {
            return Integer.MAX_VALUE;
        }
        load(ctx);
        Integer r = ranks.get(channel);
        if (r == null) {
            return Integer.MAX_VALUE;
        }
        Long at = stamps.get(channel);
        long when = at == null ? 0L : at;
        if (System.currentTimeMillis() - when > CAP_TTL_MS) {
            ranks.remove(channel);
            stamps.remove(channel);
            save(ctx);
            return Integer.MAX_VALUE;
        }
        return r;
    }

    /**
     * 比 {@code from} 低一档的档位（严格小于它的最高档）。
     *
     * <p>降档必须<b>一次只降一档</b>：1080 喂不动应降到 720，
     * 而不是直接跌到 576。实测精修在首帧出现前会按速率挑最优线路，
     * 若不加这一层，它会直接选中 576（分数一样是 2.0），
     * 用户看到的是"从高清一下变成很糊"，中间那档明明能播却被跳过。
     *
     * @return 下一档；没有更低档时返回 {@link Integer#MAX_VALUE}
     */
    public static int nextLower(Channel ch, int from) {
        if (from <= 0) {
            return Integer.MAX_VALUE;
        }
        int next = 0;
        boolean found = false;
        for (Channel.Source s : ch.sources) {
            int r = s.effectiveRank();
            if (r <= 0) {
                // 无节目单标记的线路：清晰度未知，实测多是老机房
                continue;
            }
            if (r >= from) {
                continue;
            }
            if (!found || r > next) {
                next = r;
                found = true;
            }
        }
        // 没有更低档的<b>标记</b>线路时返回"降不下去"，不再退到 0。
        //
        // 曾经退到 0（意思是"只允许无标记线路"），实测是个陷阱：
        // 那批线路清晰度未知，既可能是真 576，也可能是没标清楚的 1080 坏源。
        // 真机实测（build/final_grace.log 14:59:00）降到底档后连续三路全超时，
        // 而这个上限会落盘保留 3 小时，该频道在 3 小时内彻底放不出来。
        // 宁可报告"已是最低档"，让调用方去换同档线路，也不要主动跳进未知档。
        return found ? next : Integer.MAX_VALUE;
    }

    /**
     * 把上限降到「比 {@code fromRank} 更低的最好一档」。
     *
     * <p>只在<b>确认当前档位喂不动</b>时调用（分片跳号、起播超时、
     * 反复静默卡死、反复重缓冲）。降到下一档而不是一降到 576，是为了留住
     * 720 这种"既能看又喂得饱"的中间档。
     *
     * <p>已经是该频道最低档时不再下调，否则会把能播的线路也排除掉。
     *
     * @param fromRank 当前正在播、且已被判定喂不动的线路档位（<b>有效档位</b>，
     *                 无节目单标记的线路用实测值，见 {@link Channel.Source#effectiveRank()}）
     * @return 新的上限档位；无法再降时返回 {@link Integer#MAX_VALUE}
     */
    public static int lowerBelow(Context ctx, Channel ch, int fromRank) {
        if (ch == null) {
            return Integer.MAX_VALUE;
        }
        final int from = fromRank;
        int next = nextLower(ch, from);
        if (next == Integer.MAX_VALUE) {
            // 已经是能识别的最低档，没有再降的余地
            return Integer.MAX_VALUE;
        }
        load(ctx);
        Integer cur = ranks.get(ch.name);
        // 已有更低的上限就不要抬高它
        if (cur != null && cur <= next) {
            return cur;
        }
        ranks.put(ch.name, next);
        stamps.put(ch.name, System.currentTimeMillis());
        save(ctx);
        android.util.Log.i("IPTVQUAL", "cap ch=" + ch.name
                + " from=" + from + " -> " + next
                + " ttl=" + (CAP_TTL_MS / 60000) + "min");
        return next;
    }

    /**
     * 把上限钉在指定档位（含）。
     *
     * <p>用于精修已经确定了某条具体线路的场合：此时不需要"降一档"这个推导，
     * 直接把上限设成那条线路的档位，保证后续任何路径都不会挑比它更高的。
     *
     * @param rank 允许的最高档位
     */
    public static void lockAt(Context ctx, String channel, int rank) {
        if (channel == null) {
            return;
        }
        load(ctx);
        Integer cur = ranks.get(channel);
        if (cur != null && cur <= rank) {
            return;
        }
        ranks.put(channel, rank);
        stamps.put(channel, System.currentTimeMillis());
        save(ctx);
        android.util.Log.i("IPTVQUAL", "cap-lock ch=" + channel + " -> " + rank);
    }

    /** 用户手动选了某条线路：以他选的档位为准，清掉上限。 */
    public static void clear(Context ctx, String channel) {
        if (channel == null) {
            return;
        }
        load(ctx);
        if (ranks.remove(channel) != null) {
            stamps.remove(channel);
            save(ctx);
        }
    }

    private static synchronized void load(Context ctx) {
        if (ranks != null) {
            return;
        }
        ranks = new HashMap<>();
        stamps = new HashMap<>();
        String raw = prefs(ctx).getString(KEY, "");
        if (raw == null) {
            return;
        }
        for (String line : raw.split("\n")) {
            String[] p = line.split("\t");
            if (p.length < 2) {
                continue;
            }
            try {
                ranks.put(p[0], Integer.parseInt(p[1]));
            } catch (NumberFormatException ignored) {
                continue;
            }
            try {
                stamps.put(p[0], p.length > 2 ? Long.parseLong(p[2]) : 0L);
            } catch (NumberFormatException ignored) {
                stamps.put(p[0], 0L);
            }
        }
    }

    private static void save(Context ctx) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Map.Entry<String, Integer> e : ranks.entrySet()) {
            if (n++ >= LIMIT) {
                break;
            }
            Long at = stamps.get(e.getKey());
            sb.append(e.getKey()).append('\t').append(e.getValue())
                    .append('\t').append(at == null ? 0L : at).append('\n');
        }
        prefs(ctx).edit().putString(KEY, sb.toString()).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
