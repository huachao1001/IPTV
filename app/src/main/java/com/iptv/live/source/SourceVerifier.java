package com.iptv.live.source;

import android.content.Context;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 后台"体检"那些<b>不在新清单里</b>的老线路（"滚雪球"策略的减法环节）。
 *
 * <p><b>为什么需要它。</b>刷新时旧线路默认全部保留——上游清单每次都会掉一批，
 * 直接覆盖会让频道越刷越少。但保留不等于永远留着：如果一条线路从此再没出现在
 * 上游清单里，它早晚会彻底失效，攒着只会拖慢排序和选源。
 *
 * <p>所以每次刷新成功后，挑出"只在旧缓存/内置清单里、这次新清单已经不给"的线路，
 * 挨个取一次它的 {@code .m3u8}：能取到且内容像播放列表 ⇒ 还活着；
 * 连不上/404/内容不像列表 ⇒ 判为无效，交 {@link DeadSources} 记 3 天。
 *
 * <p><b>为什么这个探测可以直接判死，而速度探测不行。</b>
 * 这里只判"能不能取到清单"，是 0/1 的连通性事实，与带宽无关；
 * 而"这条线喂不喂得动 1080"必须靠真实播放才知道——分片开头的突发速率
 * 能比持续速率高四五倍（实测 2.02 MB/s 的线路真实只有 0.42 MB/s），
 * 用速度判死会误杀好源。两件事不能混为一谈。
 *
 * <p>而且判死是可逆的：线路上游修好、真播起来了，{@code MainActivity}
 * 会调 {@link DeadSources#revive} 撤销，见 {@code STATE_READY} 分支。
 */
public final class SourceVerifier {

    /**
     * 每轮最多体检多少条。
     *
     * <p>合并后的老线路可能有几百条，全量串一遍要几分钟、还会挤占
     * 起播用的带宽。分批做：每轮挑一批，没轮到的留到下轮——
     * 反正判死有 14 天的窗口，不急。
     */
    private static final int MAX_PER_RUN = 150;

    /** 并发数。取 6 是在模拟器上测出来的：再高就会互相抢带宽，出现假 timeout。 */
    private static final int THREADS = 6;

    /** 单条超时。老源多半卡在建连上，6 秒足够分辨"活但慢"和"根本不通"。 */
    private static final int TIMEOUT_MS = 6000;

    /** 上一轮体检到第几条，用来轮转，避免永远只测前 150 条。 */
    private static int cursor;

    private SourceVerifier() {
    }

    /**
     * 开始一轮后台体检，立即返回。
     *
     * @param urls 待体检的线路（调用方已过滤掉"新清单里还有"的那些）
     */
    public static void verifyAsync(final Context ctx, final List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            return;
        }
        final Context app = ctx.getApplicationContext();
        final List<String> batch = pick(urls);
        new Thread(() -> run(app, batch), "iptv-verify").start();
    }

    /** 从列表里按游标取一批，形成轮转。 */
    private static synchronized List<String> pick(List<String> urls) {
        int n = urls.size();
        int take = Math.min(MAX_PER_RUN, n);
        List<String> out = new ArrayList<>(take);
        for (int i = 0; i < take; i++) {
            out.add(urls.get((cursor + i) % n));
        }
        cursor = (cursor + take) % n;
        return out;
    }

    private static void run(Context ctx, List<String> batch) {
        final AtomicInteger ok = new AtomicInteger();
        final AtomicInteger bad = new AtomicInteger();
        // 每次一轮自己的线程池：用完就关，static 单例关掉一次就再也不能用了
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        for (final String url : batch) {
            pool.execute(() -> {
                if (reachable(url)) {
                    // 取得到清单 ⇒ 这条线路还在，续命
                    SourceHealth.markAlive(ctx, url);
                    ok.incrementAndGet();
                } else {
                    DeadSources.markDead(ctx, url);
                    bad.incrementAndGet();
                }
            });
        }
        pool.shutdown();
        try {
            pool.awaitTermination(180, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (ok.get() + bad.get() > 0) {
            android.util.Log.i("IPTVVERIFY", "old-line ok=" + ok.get()
                    + " dead=" + bad.get() + " total=" + batch.size());
        }
    }

    /**
     * 取一次清单，判断这条线路是否还活着。
     *
     * <p>只看"能取到、内容像播放列表"，<b>不看成败速度</b>：
     * 慢但能出画面的是好源，快但内容没了的是坏源。
     */
    private static boolean reachable(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "IptvLive/1.0");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                return false;
            }
            // 只读开头一段：清单本身只有几百字节，读满 4KB 足够判断
            InputStream in = conn.getInputStream();
            try {
                byte[] buf = new byte[4096];
                int n = 0;
                while (n < buf.length) {
                    int r = in.read(buf, n, buf.length - n);
                    if (r < 0) {
                        break;
                    }
                    n += r;
                }
                String head = new String(buf, 0, n, "UTF-8");
                // 正常是 #EXTM3U / #EXTINF；有源站会返回一张空清单，同样算活
                return head.contains("#EXTM3U") || head.contains("#EXTINF");
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
