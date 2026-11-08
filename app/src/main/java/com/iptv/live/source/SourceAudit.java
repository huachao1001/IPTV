package com.iptv.live.source;

import com.iptv.live.model.Channel;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 线路体检：把"这条线路到底能不能出数据"变成一个<b>可查询的事实</b>。
 *
 * <p><b>要解决的问题。</b>节目单是从上游抄来的，其中相当一部分地址已经死了
 * （机房下线、切片 404、域名解析不到）。以前这些线路只有"真的播一次"
 * 才会被发现——用户点开菜单，看到一个 1080P 就按下去，
 * 结果转圈十几秒、黑屏、再自己换回来。他要的不是"程序会自愈"，
 * 而是<b>这个选项本来就不该出现在菜单里</b>。
 *
 * <p><b>为什么可以不阻塞地判。</b>这里检查的是<b>连通性</b>，不是带宽：
 * 能不能取到清单、清单里第一个分片能不能出字节。这是 0/1 的事实，
 * 与"这条线喂不喂得动 1080"完全是两件事——后者只能靠真实播放判定
 * （探测测的是分片开头的突发速率，能比持续速率高四五倍，
 * 用它判"喂不动"会大面积误杀，见 {@link Channel.Source#starved}）。
 * 所以本类<b>只判生死、不判快慢</b>，判死也<b>不写</b> {@link DeadSources}
 * ——那是"真的播失败过"的黑名单，两者证据强度不同，不能混用。
 *
 * <p><b>只在带宽有余量时干活。</b>宿主每秒把播放状态告诉本类
 * （{@link #setHeadroom(boolean)}）：判据是「实测下载速率 ÷ 这路流的码率需求」，
 * 不是「缓冲攒了多厚」。后者踩过：很多源一个分片窗口只有 3~5 秒，
 * 缓冲永远到不了 8 秒，体检一次都不跑（实测模拟器 buffered 峰值 5.3 秒）。
 * 起播和卡顿期间一律停手——那时候探测就是和正在播的那一路抢带宽，
 * 正好造成"越探测越卡"。
 *
 * <p><b>被打断不算结论。</b>探测中途因为播放吃紧要让路时，
 * {@code probe()/sample()/readText()} 返回 {@link #ABORTED}，
 * 这条线路<b>放回队尾</b>（仍是"排队中"，不是"已测完"，也绝不记 BAD）。
 * 早先是直接从队列里删掉，等于让它回到"从没测过"——而重新入队只发生在
 * 打开菜单/移动焦点时，弱网下被让路是常态，那几条于是永远显示不出来。
 *
 * <p>状态只在内存里，进程重启就重来。这是刻意的：判死只保留
 * {@link #BAD_TTL_MS}，一次临时断网不该让一个好源消失一整天。
 */
public final class SourceAudit {

    private static final String TAG = "IPTVAUDIT";

    /** 没测过。 */
    public static final int UNKNOWN = 0;
    /** 取到清单、第一个分片能出字节。 */
    public static final int OK = 1;
    /** 连不上 / HTTP 错误 / 内容不是播放列表 / 分片一个字节都没有。 */
    public static final int BAD = 2;

    /** 探测并发。取 2 是有意的：再多就会和正在播的那一路抢带宽。 */
    private static final int THREADS = 2;

    /** 建连超时。老源多半卡在建连上，4 秒足够分辨"活但慢"和"根本不通"。 */
    private static final int CONNECT_TIMEOUT_MS = 4_000;
    private static final int READ_TIMEOUT_MS = 6_000;

    /** 分片采样时长与上限：够看出"出不出数据"，又不至于真把整片拉下来。 */
    private static final long SAMPLE_MS = 2_000;
    private static final int SAMPLE_BYTES = 768 * 1024;

    /** 清单本身很小，超过这个长度说明不是清单。 */
    private static final int MAX_PLAYLIST_BYTES = 512 * 1024;

    /**
     * 结论的有效期。好源给久一点，坏源也要给够——但别太长。
     *
     * <p>坏源取 10 分钟而不是几十秒：过期就重新入队再测一遍，若 TTL 太短，
     * 一批死链会被无限重复探测（体检线程有带宽余量就"轮着测"，
     * 加上 {@code rotate()} 每次都会把 UNKNOWN 重新排队），
     * 白白吃掉本可以给好源做验证的带宽。
     * 10 分钟仍然满足"一次临时断网不该让好源锁死一整天"。
     */
    private static final long OK_TTL_MS = 15 * 60_000L;
    private static final long BAD_TTL_MS = 10 * 60_000L;

    /** 队列上限，防止把整份节目单一次性塞进来。 */
    private static final int MAX_QUEUE = 400;
    /** 一次 {@link #rotate} 最多放多少条进普通队列。 */
    private static final int ROTATE_BATCH = 24;

    /**
     * {@link #probe} 的返回值：这次采样被"让路"打断了，<b>不能当作结论</b>。
     *
     * <p>必须与 {@code -1}（确实连不上）分开：播放吃紧时停手是调度决定，
     * 不是线路的罪状。混在一起会把好源判死，见 {@code loop()}。
     */
    private static final long ABORTED = -2;

    /** 一条线路的体检结论。 */
    private static final class Entry {
        int state = UNKNOWN;
        long at;
        long speed;
    }

    private static final Map<String, Entry> STATE = new HashMap<>();
    /** 已入队或正在测的地址，避免同一路被排好几次。 */
    private static final Set<String> QUEUED = new HashSet<>();
    /** 高优先队列：用户正看着的那台。 */
    private static final Deque<String> FRONT = new ArrayDeque<>();
    /** 普通队列：当前分组的其余线路，轮着来。 */
    private static final Deque<String> BACK = new ArrayDeque<>();

    private static final Object LOCK = new Object();
    /** 现在有没有带宽余量。没有就整个停下。 */
    private static volatile boolean spare;
    private static boolean started;
    private static Context appCtx;

    /** 有结论更新时通知宿主重绘菜单（主线程）。 */
    @Nullable
    private static volatile Runnable listener;

    private SourceAudit() {
    }

    /* ---------------- 对外查询 ---------------- */

    /** 当前结论：{@link #UNKNOWN} / {@link #OK} / {@link #BAD}。过期的按 UNKNOWN 算。 */
    public static int state(@Nullable String url) {
        if (url == null || url.isEmpty()) {
            return UNKNOWN;
        }
        synchronized (LOCK) {
            Entry e = STATE.get(url);
            if (e == null) {
                return UNKNOWN;
            }
            long ttl = e.state == OK ? OK_TTL_MS : BAD_TTL_MS;
            if (System.currentTimeMillis() - e.at > ttl) {
                return UNKNOWN;
            }
            return e.state;
        }
    }

    /** 这条线路是否已被证伪（连不上 / 取不到内容）。 */
    public static boolean isBad(@Nullable String url) {
        return state(url) == BAD;
    }

    /** 这条线路是否已被证实能出数据。 */
    public static boolean isOk(@Nullable String url) {
        return state(url) == OK;
    }

    /** 记住"这条真的播起来了"：比任何探测都硬的证据，直接置 OK。 */
    public static void markPlaying(@Nullable String url) {
        if (url == null || url.isEmpty()) {
            return;
        }
        synchronized (LOCK) {
            Entry e = STATE.get(url);
            if (e == null) {
                e = new Entry();
                STATE.put(url, e);
            }
            e.state = OK;
            e.at = System.currentTimeMillis();
        }
    }

    /* ---------------- 调度 ---------------- */

    /** 宿主回报"现在有没有带宽余量"。false 时所有探测立刻停在检查点上。 */
    public static void setHeadroom(boolean hasHeadroom) {
        if (spare == hasHeadroom) {
            return;
        }
        spare = hasHeadroom;
        if (hasHeadroom) {
            synchronized (LOCK) {
                LOCK.notifyAll();
            }
            Log.i(TAG, "headroom on, queued=" + queuedCount());
        }
    }

    /** 结论有更新时回调（主线程）。用于重绘清晰度面板与频道行。 */
    public static void setListener(@Nullable Runnable r) {
        listener = r;
    }

    /**
     * 把一台频道的线路排到<b>队首</b>：用户正看着它，菜单里马上要显示它的档位。
     *
     * <p>已经测过且仍在有效期内的会被跳过，所以反复打开菜单不会重复发请求。
     */
    public static void prioritize(Context ctx, @Nullable Channel c) {
        if (c == null || c.sources.isEmpty()) {
            return;
        }
        ensureStarted(ctx);
        List<String> urls = new ArrayList<>();
        for (Channel.Source s : c.sources) {
            if (s.url != null && state(s.url) == UNKNOWN) {
                urls.add(s.url);
            }
        }
        if (urls.isEmpty()) {
            return;
        }
        synchronized (LOCK) {
            // 倒序压栈，取出来时就是原来的顺序（最优线路先测）
            for (int i = urls.size() - 1; i >= 0; i--) {
                String u = urls.get(i);
                if (QUEUED.add(u)) {
                    FRONT.addFirst(u);
                }
            }
            trimLocked();
            LOCK.notifyAll();
        }
        Log.i(TAG, "prioritize ch=" + c.name + " links=" + urls.size());
    }

    /**
     * 把当前分组的线路排进普通队列，轮着测。
     *
     * <p>这是"带宽有余量就挨个探"的落点：一次只放一批，测完再放下一批，
     * 免得把整份节目单塞进队列、探测线程永远排在播放后面。
     */
    public static void rotate(Context ctx, @Nullable List<Channel> channels) {
        if (channels == null || channels.isEmpty()) {
            return;
        }
        ensureStarted(ctx);
        int added = 0;
        synchronized (LOCK) {
            outer:
            for (Channel c : channels) {
                for (Channel.Source s : c.sources) {
                    if (added >= ROTATE_BATCH) {
                        // 一次只放一批：放太多会让体检线程永远追在播放后面，
                        // 而且整份节目单几千条线路也没必要同时排队。
                        break outer;
                    }
                    if (s.url == null || state(s.url) != UNKNOWN) {
                        continue;
                    }
                    if (QUEUED.add(s.url)) {
                        BACK.addLast(s.url);
                        added++;
                    }
                }
            }
            trimLocked();
            LOCK.notifyAll();
        }
        if (added > 0) {
            Log.i(TAG, "rotate queued=" + added + " backlog=" + queuedCount());
        }
    }

    /** 队列里还有多少条待测，供日志与界面提示使用。 */
    public static int pending() {
        synchronized (LOCK) {
            return QUEUED.size();
        }
    }

    private static int queuedCount() {
        return QUEUED.size();
    }

    private static void trimLocked() {
        while (QUEUED.size() > MAX_QUEUE && !BACK.isEmpty()) {
            String drop = BACK.pollLast();
            if (drop != null) {
                QUEUED.remove(drop);
            }
        }
    }

    private static synchronized void ensureStarted(final Context ctx) {
        if (started) {
            return;
        }
        started = true;
        appCtx = ctx.getApplicationContext();
        for (int i = 0; i < THREADS; i++) {
            final int id = i;
            Thread t = new Thread(() -> loop(appCtx), "iptv-audit-" + id);
            t.setDaemon(true);
            // 探测永远不该抢播放的 CPU：整条线程让到最低优先级
            t.setPriority(Thread.MIN_PRIORITY);
            t.start();
        }
    }

    private static void loop(Context ctx) {
        while (true) {
            String url;
            try {
                synchronized (LOCK) {
                    while (true) {
                        if (!spare || (FRONT.isEmpty() && BACK.isEmpty())) {
                            // 没余量 / 没活干：睡着等，1 秒醒一次重新看条件
                            LOCK.wait(1_000L);
                            continue;
                        }
                        url = FRONT.pollFirst();
                        if (url == null) {
                            url = BACK.pollFirst();
                        }
                        if (url != null) {
                            break;
                        }
                    }
                }
            } catch (InterruptedException e) {
                return;
            }
            if (url == null) {
                continue;
            }
            long speed;
            try {
                speed = probe(url);
            } catch (Throwable t) {
                speed = -1;
            }
            if (speed == ABORTED) {
                // 让路被打断，<b>什么结论都不下</b>。
                //
                // 这一点必须严格：探测中途因为播放吃紧而停手，说明的是
                // "现在没空测"，不是"这条线路死了"。若在这里记 BAD，
                // 用户一开始播高码率频道，体检就会轮流把一堆好源判死，
                // 菜单里的档位会莫名其妙消失。
                //
                // 但<b>也不能直接从队列里拿掉</b>（早先是 QUEUED.remove）。
                // 拿掉就等于这条线路回到"从没测过"，而重新入队只发生在
                // openMenu / 焦点移动 / rotate 时——一次会话里可能再也不会有。
                // 弱网下被让路是常态（实测 10 条里有 2~3 条 defer），
                // 结果是那几条永远显示不出来，正是用户说的"每个选项都要有效"
                // 落不了地的原因。放回队尾，属于"排队中"而不是"已测完"。
                synchronized (LOCK) {
                    // 仍在 QUEUED 里（不能重复入队），只是换个位置等下一轮
                    if (BACK.size() < MAX_QUEUE) {
                        BACK.addLast(url);
                        LOCK.notifyAll();
                    } else {
                        QUEUED.remove(url);
                    }
                }
                Log.i(TAG, "defer " + url + " requeued=" + queuedCount());
                continue;
            }
            record(url, speed);
        }
    }

    private static void record(String url, long speed) {
        int state = speed >= 0 ? OK : BAD;
        synchronized (LOCK) {
            Entry e = STATE.get(url);
            if (e == null) {
                e = new Entry();
                STATE.put(url, e);
            }
            e.state = state;
            e.speed = speed;
            e.at = System.currentTimeMillis();
            QUEUED.remove(url);
        }
        Log.i(TAG, (state == OK ? "ok " : "bad ") + speed + "B/s " + url);
        Runnable r = listener;
        if (r != null) {
            r.run();
        }
    }

    /* ---------------- 真正的探测 ---------------- */

    /**
     * 探一路：取清单 → 取第一个分片。
     *
     * @return 采样到的字节速率（B/s）；判死返回 {@code -1}；
     *         中途让路返回 {@link #ABORTED}（<b>不是结论</b>）
     */
    private static long probe(String url) {
        HttpURLConnection conn = null;
        try {
            conn = open(url);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                return -1;
            }
            String body = readText(conn.getInputStream(), MAX_PLAYLIST_BYTES);
            if (body == null) {
                // readText 因为让路提前返回：这次不算数
                return ABORTED;
            }
            if (HlsUrls.isPlaylist(body)) {
                String seg = HlsUrls.resolve(url, HlsUrls.firstSegment(body));
                if (seg == null) {
                    // 清单是空的：这个台现在确实没有内容
                    return -1;
                }
                return sample(seg);
            }
            // 不是清单：这多半是直连的分片/裸流地址。
            // 门户的错误页也会返回 200，必须靠内容挡掉，否则"看着能连"其实什么都没有
            if (body.isEmpty() || looksLikeHtml(body)) {
                return -1;
            }
            // 已经读到的这部分本身也是字节，按它估个速率就够判"活着"
            return body.length() > 0 ? Math.max(1, body.length() * 1000L
                    / Math.max(1, conn.getReadTimeout())) : -1;
        } catch (Throwable t) {
            return -1;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** 取一个分片，边读边计时，读满 {@link #SAMPLE_MS} 或 {@link #SAMPLE_BYTES} 就停。 */
    private static long sample(String url) {
        HttpURLConnection conn = null;
        try {
            conn = open(url);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                return -1;
            }
            InputStream in = conn.getInputStream();
            byte[] buf = new byte[16 * 1024];
            long start = System.currentTimeMillis();
            long total = 0;
            boolean aborted = false;
            try {
                while (total < SAMPLE_BYTES) {
                    if (!spare) {
                        // 播放开始吃紧了：立刻让路。
                        //
                        // 读到的字节<b>不能</b>拿去当结论：这时候停手是
                        // "现在没空测"，不是"这条线路有问题"。一个字节都没读
                        // 也要返回 ABORTED 而不是 -1，否则用户一开始播高码率台，
                        // 体检就会轮流把好源判死。
                        aborted = true;
                        break;
                    }
                    int n;
                    try {
                        n = in.read(buf);
                    } catch (java.net.SocketTimeoutException e) {
                        // 卡住了：已经读到的算证据，一个字节都没有就是死源
                        break;
                    }
                    if (n < 0) {
                        break;
                    }
                    total += n;
                    if (System.currentTimeMillis() - start >= SAMPLE_MS) {
                        break;
                    }
                }
            } finally {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
            if (aborted) {
                return ABORTED;
            }
            return total > 0 ? bytesPerSec(total, start) : -1;
        } catch (Throwable t) {
            return -1;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static long bytesPerSec(long bytes, long startMs) {
        long dt = Math.max(1, System.currentTimeMillis() - startMs);
        return bytes * 1000L / dt;
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "IptvLive/1.0");
        return conn;
    }

    /**
     * 读清单正文。
     *
     * @return 正文；<b>中途让路时返回 {@code null}</b>，表示"这次不算数"
     */
    private static String readText(InputStream in, int cap) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(4096);
        byte[] b = new byte[8192];
        boolean aborted = false;
        try {
            while (buf.size() < cap) {
                if (!spare) {
                    // 清单都还没读完播放就吃紧了：立刻让路，不产生结论
                    aborted = true;
                    break;
                }
                int n = in.read(b);
                if (n < 0) {
                    break;
                }
                buf.write(b, 0, n);
            }
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
            }
        }
        if (aborted) {
            return null;
        }
        return new String(buf.toByteArray(), "UTF-8");
    }

    /** 门户/路由器的错误页会以 200 返回，内容看着"有字节"但根本没有流。 */
    private static boolean looksLikeHtml(String body) {
        String head = body.length() > 512 ? body.substring(0, 512) : body;
        String lower = head.toLowerCase(java.util.Locale.US);
        return lower.contains("<!doctype") || lower.contains("<html") || lower.contains("<head>");
    }
}
