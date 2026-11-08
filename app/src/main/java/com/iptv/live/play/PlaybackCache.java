package com.iptv.live.play;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.CacheWriter;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.database.StandaloneDatabaseProvider;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 播放缓存管家：磁盘缓存 + 预取。
 *
 * <p><b>它解决两个不同的问题。</b>
 *
 * <p>1. <b>同一路流重复下载</b>。换台来回切、播放器因抖动重开同一条线路时，
 * 分片会被整段重新拉一遍。HLS 的分片地址带序号，同一片内容不同时间取是同一份，
 * 天然适合缓存。{@link SimpleCache} 按 URL 存字节，重开同一条线路时
 * 直接从磁盘读，起播立刻就能出画面。
 *
 * <p>2. <b>预判用户下一步</b>。用户按 OK 打开菜单时，几乎一定是要换台；
 * 上下浏览频道时，落点就在相邻几个台。这时提前把候选频道的
 * <b>播放列表和图头几个分片</b>抓下来，等真的按下去时首帧几乎是瞬时的。
 *
 * <p><b>预取只抓"开头几秒"，不抓整条流。</b>直播流是无限的，
 * 抓整条会把带宽和磁盘都占满，反而把正在播的那一路拖卡。这里只取
 * 播放列表 + 前 {@link #PREFETCH_SEGMENTS} 个分片，够起播就行——
 * 起播之后播放器自己按需继续取。
 *
 * <p><b>预取必须能立刻取消。</b>用户浏览得很快时，候选一直在变；
 * 不取消的话几十个请求同时排队，抢占当前播放的带宽，正好造成
 * "越预取越卡"的反效果。所以给每个任务留一个 {@link Future}，
 * 换候选时先 {@code cancel}。
 *
 * <p>缓存策略是 {@link LeastRecentlyUsedCacheEvictor}：磁盘上限
 * {@link #CACHE_BYTES}，超了淘汰最久未用的。分片是短命数据，
 * 一条流看过之后基本不会再回来，所以不需要也不应该留太久。
 */
@UnstableApi
public final class PlaybackCache {

    private static final String TAG = "IPTVCACHE";

    /**
     * 磁盘缓存上限。
     *
     * <p>1080 一路约 1 MB/s，5 秒分片约 5 MB。这里取 256 MB：
     * 够存几路流各二十来个分片（足以覆盖"来回切几个台"的场景），
     * 又不会把电视那点存储吃掉。超了 LRU 会自动淘汰。
     */
    private static final long CACHE_BYTES = 256L * 1024 * 1024;

    /**
     * 预取多少个分片。
     *
     * <p>起播只需要几秒数据，但分片长度不一（实测 5~6.5 秒），
     * 取 3 片能保证足够起播，又不会为了预取把正在播的那一路挤掉。
     */
    private static final int PREFETCH_SEGMENTS = 3;

    /** 预取并发数。取 2 是有意的：再多就会和正在播的流抢带宽。 */
    private static final int PREFETCH_THREADS = 2;

    @Nullable
    private static SimpleCache cache;
    @Nullable
    private static CacheDataSource.Factory factory;
    @Nullable
    private static ExecutorService prefetchPool;
    /** 当前正在跑的预取任务，换候选时要先取消。 */
    @Nullable
    private static Future<?> prefetchTask;

    private PlaybackCache() {
    }

    /**
     * 初始化磁盘缓存，返回带缓存的 {@link DataSource.Factory}。
     *
     * <p>必须<b>整个进程只建一个</b> {@link SimpleCache}：同一目录被打开两次会
     * 直接抛 {@code IllegalStateException: Another SimpleCache instance uses the
     * folder}。所以这里做成静态单例，重复调用返回同一个实例。
     */
    public static synchronized CacheDataSource.Factory factory(Context ctx) {
        if (factory != null) {
            return factory;
        }
        Context app = ctx.getApplicationContext();
        File dir = new File(app.getCacheDir(), "media");
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "cannot create cache dir " + dir);
        }
        try {
            cache = new SimpleCache(dir,
                    new LeastRecentlyUsedCacheEvictor(CACHE_BYTES),
                    new StandaloneDatabaseProvider(app));
        } catch (RuntimeException e) {
            // 缓存目录损坏（或上次进程被杀在写入中间）时不要连累播放：
            // 删掉重建一次，仍失败就退化为无缓存直连
            Log.w(TAG, "cache init failed, rebuilding: " + e);
            try {
                SimpleCache.delete(dir, new StandaloneDatabaseProvider(app));
                cache = new SimpleCache(dir,
                        new LeastRecentlyUsedCacheEvictor(CACHE_BYTES),
                        new StandaloneDatabaseProvider(app));
            } catch (RuntimeException again) {
                Log.w(TAG, "cache disabled: " + again);
                cache = null;
            }
        }

        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent("IptvLive/1.0")
                .setConnectTimeoutMs(5_000)
                .setReadTimeoutMs(8_000)
                .setAllowCrossProtocolRedirects(true);
        // 网络层要挂上测速监听，缓冲时才能显示实时速率
        http.setTransferListener(LiveSpeedMeter.INSTANCE);

        if (cache == null) {
            // 缓存不可用：直接把上游给播放器
            factory = new CacheDataSource.Factory()
                    .setUpstreamDataSourceFactory(http)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
            return factory;
        }

        factory = new CacheDataSource.Factory()
                .setCache(cache)
                // 缓存命中的分片用最快的本地读取器，不走网络栈
                .setCacheReadDataSourceFactory(new DefaultDataSource.Factory(app))
                .setUpstreamDataSourceFactory(http)
                // 不阻塞等缓存：直播要的是"现在就有数据"，等缓存写完反而更慢
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
        Log.i(TAG, "cache ready dir=" + dir + " limit=" + (CACHE_BYTES / 1024 / 1024) + "MB");
        return factory;
    }

    /** 已占用的磁盘缓存字节数，用于设置页显示。 */
    public static synchronized long usedBytes() {
        return cache == null ? 0 : cache.getCacheSpace();
    }

    /** 清空缓存（设置页用）。 */
    public static synchronized void clear(Context ctx) {
        SimpleCache c = cache;
        if (c == null) {
            return;
        }
        for (String key : c.getKeys()) {
            c.removeResource(key);
        }
        Log.i(TAG, "cache cleared");
    }

    /* ---------------- 预取 ---------------- */

    /**
     * 预判用户可能切到的频道，提前把它们"起播需要的那几秒"抓下来。
     *
     * <p><b>调用时机就是用户意图最明显的时刻</b>：按 OK 打开菜单（几乎一定是要换台）、
     * 或者在列表里上下移动焦点（落点就在相邻几个台）。这时候后台把候选流抓下来，
     * 等真的切过去，播放器直接从磁盘读，首帧几乎是瞬时的。
     *
     * <p>已经在播的那一路会被跳过：它自己有带宽优先权，预取它没有意义，
     * 还会白白多一份请求。
     *
     * @param urls     候选线路，按"越可能被选"排前面；只有前 {@link #PREFETCH_MAX} 个会被处理
     * @param playing  当前正在播的地址，可为 {@code null}
     */
    public static synchronized void prefetch(Context ctx, java.util.List<String> urls,
                                             @Nullable String playing) {
        if (urls == null || urls.isEmpty()) {
            return;
        }
        cancelPrefetch();
        ExecutorService pool = pool();
        if (pool == null) {
            return;
        }
        final java.util.List<String> todo = new java.util.ArrayList<>();
        for (String u : urls) {
            if (u == null || u.equals(playing) || todo.contains(u)) {
                continue;
            }
            todo.add(u);
            if (todo.size() >= PREFETCH_MAX) {
                break;
            }
        }
        if (todo.isEmpty()) {
            return;
        }
        prefetchTask = pool.submit(() -> {
            for (String url : todo) {
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
                warm(ctx, url);
            }
        });
    }

    /** 最多同时预取几路。3 路足够覆盖"上下相邻的几个台"。 */
    private static final int PREFETCH_MAX = 3;

    /** 取消所有预取。换候选、切台、退出播放页都要调。 */
    public static synchronized void cancelPrefetch() {
        Future<?> f = prefetchTask;
        prefetchTask = null;
        if (f != null) {
            f.cancel(true);
        }
    }

    /**
     * 把一路流的"起播部分"抓进缓存。
     *
     * <p>顺序很关键：先取播放列表（否则不知道分片地址），再依次取分片。
     * 每个分片都限定长度，{@code 0} 表示"读到流结束"——分片本身有界，
     * 读完自然结束，不会无限下载。
     */
    private static void warm(Context ctx, String url) {
        CacheDataSource source = null;
        try {
            source = (CacheDataSource) factory(ctx).createDataSource();
            // 1) 播放列表本身：几百字节，但每次起播都要它
            download(source, url);
            // 2) 分片：从清单里解析出前几个 .ts，逐个抓进缓存
            String list = readText(source, url);
            if (list == null) {
                return;
            }
            int taken = 0;
            for (String line : list.split("\\r?\\n")) {
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
                String seg = line.trim();
                if (seg.isEmpty() || seg.startsWith("#")) {
                    continue;
                }
                String abs = resolve(url, seg);
                if (abs == null) {
                    continue;
                }
                download(source, abs);
                if (++taken >= PREFETCH_SEGMENTS) {
                    break;
                }
            }
            Log.i(TAG, "prefetch ok segs=" + taken + " " + url);
        } catch (Exception e) {
            // 预取失败完全不影响正常播放，静默即可
            Log.i(TAG, "prefetch skip " + url + " -> " + e);
        } finally {
            if (source != null) {
                try {
                    source.close();
                } catch (Exception ignored) {
                    // 关流失败无所谓，缓存写入已经按 CacheWriter 的语义提交
                }
            }
        }
    }

    /** 把一个地址整体读进缓存（已缓存的部分自动跳过）。 */
    private static void download(CacheDataSource source, String url) throws Exception {
        new CacheWriter(source,
                new DataSpec.Builder().setUri(Uri.parse(url)).build(),
                null, (requestLength, bytesCached, newBytes) -> {
                    // 进度回调必须给，否则 CacheWriter 认为调用方不关心而提前退出
                }).cache();
    }

    /** 读出播放列表文本（走缓存数据源，顺带把清单本身也缓存一份）。 */
    @Nullable
    private static String readText(CacheDataSource source, String url) throws Exception {
        DataSpec spec = new DataSpec.Builder().setUri(Uri.parse(url)).build();
        long len = source.open(spec);
        if (len <= 0 || len > 512 * 1024) {
            // 清单不会这么大；异常长度说明这不是一个播放列表
            source.close();
            return null;
        }
        try {
            byte[] buf = new byte[(int) len];
            int read = 0;
            while (read < buf.length) {
                int n = source.read(buf, read, buf.length - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            return new String(buf, 0, read, "UTF-8");
        } finally {
            source.close();
        }
    }

    /**
     * 把清单里的相对地址补成绝对地址。
     *
     * <p>{@code android.net.Uri} 没有 {@code resolve} 方法，只能自己算：
     * 以最后一个 {@code /} 为界拼相对路径。顺便处理 {@code //host/path}
     * 这种协议相对写法和 {@code ?query} 这种绝对查询串——节目单里三种都出现过。
     */
    @Nullable
    private static String resolve(String base, String rel) {
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

    private static synchronized ExecutorService pool() {
        if (prefetchPool == null) {
            prefetchPool = Executors.newFixedThreadPool(PREFETCH_THREADS, r -> {
                Thread t = new Thread(r, "iptv-prefetch");
                t.setDaemon(true);
                return t;
            });
        }
        return prefetchPool;
    }
}
