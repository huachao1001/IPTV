package com.iptv.live.source;

import com.iptv.live.model.Channel;
import com.iptv.live.play.TsAudio;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 并发探测同一频道的多路流，挑<b>实测够快</b>的那一路。
 *
 * <p>只看"能不能连上"远远不够：节目单里大量机房能连上但只有 200~300 KB/s，
 * 而 1080p 直播动辄需要 1 MB/s。这类源不会立刻报错，
 * 表现为播十几秒卡一下，所以必须拿实测速率和这路流自身的码率需求对比。
 */
public final class SourceProber {

    /**
     * 连接超时。
     * 必须让"连接 + 采样"落在 {@link #PROBE_TOTAL_MS} 之内，
     * 否则慢一点但能稳定播放的源会被整体判成超时丢掉。
     */
    private static final int CONNECT_TIMEOUT_MS = 2000;
    private static final int READ_TIMEOUT_MS = 3000;
    /**
     * 探测时最多读取的字节数。
     * 采样是按时间的，这个上限只用来防极端快源把内存打爆。
     */
    private static final int PROBE_BYTES = 6 * 1024 * 1024;
    /**
     * 编码检测只留开头这么多数据。
     * PAT/PMT 可能出现在分片较靠后的位置（实测 85KB 之后），但 1MB 足够覆盖；
     * 留更多只是白占内存，十几路并发时会撑爆 dalvik 堆。
     */
    private static final int HEAD_BYTES = 1024 * 1024;
    /**
     * 单路探测的时间上限。
     *
     * <p>刻意保持很短：分片开头那段是服务器的突发窗口（实测 1.9MB/s），
     * 而真实持续速率只有 0.3~0.5MB/s，整片下完要 15 秒以上。
     * 靠探测根本测不准真实能力，探太久又会让换台卡死十几秒。
     * 所以探测只做粗筛（能不能连、有没有硬解不了编码），
     * 真实"喂不喂得饱"由播放过程判定，见 {@code Source.starved}。
     */
    private static final long PROBE_BUDGET_MS = 2_500;
    /**
     * 候选很少时的采样预算。
     *
     * <p>纯 2.5 秒样本对慢源太乐观：实测有线路测出 569KB/s、
     * 实际持续速率不到 100KB/s，播起来就永久缓冲。
     * 候选多的时候靠广度取胜、每路探短；候选少时没有备胎，
     * 宁可探久一点拿到更接近真实的读数。
     */
    private static final long PROBE_BUDGET_SOLO_MS = 5_000;
    /** 候选数不超过这个值时，每路都探得更久。 */
    private static final int FEW_CANDIDATES = 3;
    /** 所有线路一起探测的总预算，到点用已有结果，不让换台无限等。 */
    private static final long PROBE_TOTAL_MS = 4_500;
    /**
     * 候选很少时的总预算。
     *
     * <p>必须严格大于 {@link #PROBE_BUDGET_SOLO_MS}，否则探测还没跑完
     * 就被总超时统一取消，所有结果都是 null，表现为"一个源都选不出来"。
     * 留 2 秒余量给连接建立和调度抖动。
     */
    private static final long PROBE_TOTAL_SOLO_MS = PROBE_BUDGET_SOLO_MS + 2_000;
    /**
     * 速率相对码率的余量。
     * 下载和播放同时进行，等速就永远追不上。
     * 实测比值 1.55 的 720p 能稳定播放，所以门槛定 1.35；
     * 再高会把整份节目单都判成不可用（盒子总带宽只有 1.5~2 MB/s）。
     */
    private static final double SPEED_MARGIN = 1.35;
    /** 完全不知道码率时的保守门槛。 */
    private static final long FALLBACK_NEED = 400 * 1024L;
    /**
     * 高码率分界。
     * 超过这个码率的流（大致 5 Mbps 以上）对网络抖动更敏感，
     * 余量要求必须更高，否则就是典型的"播十几秒卡一下"。
     */
    private static final long HIGH_BITRATE = 600 * 1024L;

    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "iptv-probe");
        t.setDaemon(true);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface Callback {
        /** @param index 可用源的下标，全部不可用时为 -1。 */
        void onPicked(int index);
    }

    /** 一路流的探测结果：实测速率 + 这路流需要的码率 + 音视频编码。 */
    private static final class Result {
        final long speed;
        final long need;
        final TsAudio.Kind audio;
        final TsAudio.Video video;

        Result(long speed, long need, TsAudio.Kind audio, TsAudio.Video video) {
            this.speed = speed;
            this.need = need;
            this.audio = audio;
            this.video = video;
        }

        /** 够不够实时播放：速率要压过这路流自身码率，并留出余量。 */
        boolean usable() {
            long req = need > 0 ? need : FALLBACK_NEED;
            return speed >= (long) (req * SPEED_MARGIN);
        }

        /** 视频编码本机能硬解才会真正出画面。 */
        boolean playableVideo() {
            return video == TsAudio.Video.H264 || video == TsAudio.Video.HEVC
                    || video == TsAudio.Video.UNKNOWN;
        }
    }

    private SourceProber() {
    }

    /** 后台挑一路可用的源并回调；回调总在主线程。 */
    public static void pick(Context ctx, Channel ch, int preferred, Callback cb) {
        pickInternal(ctx, ch, preferred, cb, -1);
    }

    /** 跳过指定源再挑，用于播放失败后换线。 */
    public static void pickExcluding(Context ctx, Channel ch, int preferred, int exclude,
                                     Callback cb) {
        pickInternal(ctx, ch, preferred, cb, exclude);
    }

    /** 播放中换线的最小收益倍数：余量要高出这么多才值得黑屏一下。 */
    private static final double REFINE_GAIN = 1.6;

    /**
     * 后台精修：只在发现<b>明显更好</b>的线路时才回调，否则回调 -1。
     *
     * <p>用于"已经起播、顺便看看有没有更优线路"的场景。
     * 回调总在主线程。
     *
     * <p>门槛分两档：当前线路<b>还没出画面</b>时用低门槛 {@link #REFINE_URGENT_GAIN}，
     * 因为此时换线没有沉没成本——反正用户盯着的就是缓冲圈；
     * 已经出画面了才用高门槛，避免"看着好好的突然黑一下"。
     */
    public static void pickBetter(Context ctx, Channel ch, int playing, boolean struggling,
                                 Callback cb) {
        final Context app = ctx.getApplicationContext();
        POOL.execute(() -> {
            int better = -1;
            try {
                better = pickBetterBlocking(app, ch, playing, struggling);
            } catch (Exception ignored) {
                // 精修失败无所谓，当前线路照常播
            }
            final int result = better;
            MAIN.post(() -> cb.onPicked(result));
        });
    }

    /**
     * 当前线路还卡在起播时的换线门槛。
     *
     * <p>这时用户本来就在看缓冲圈，换线不会打断任何观看，
     * 只要新线路明显更快就值得切——门槛低到 1.25 倍。
     */
    private static final double REFINE_URGENT_GAIN = 1.25;

    private static int pickBetterBlocking(Context ctx, Channel ch, int playing,
                                          boolean struggling) {
        if (ch.sources.size() <= 1) {
            return -1;
        }
        // 当前线路的实测速率作为基准；拿不到就放弃精修
        Result current = probe(ch.sources.get(playing).url);
        if (current == null) {
            return -1;
        }
        double base = score(current);
        double gainNeeded = struggling ? REFINE_URGENT_GAIN : REFINE_GAIN;
        // 清晰度上限：已经被判定喂不动而降到低档后，精修不许再把它抬回去。
        // 没有这一条，1080 只要偶尔测出高突发速率就会被选中，
        // 于是刚降下来又跳回 1080、再卡、再降 —— 画面反复重建。
        final int cap = QualityCap.capRank(ctx, ch.name);
        // 档位下限：精修<b>永远不许把画面换糊</b>。
        //
        // 用户的要求是"优先 1080"。而精修只是一次几秒的速率探测，
        // 拿它去决定降档太武断：实测它曾在首帧出现前 2 秒就把 1080 判死，
        // 一路降到 576，用户连高清长什么样都没看到。真正可靠的"喂不动"
        // 证据只有播起来之后的形态——起播超时、反复重缓冲、分片跳号、
        // 静默卡死，这些都由 {@code lowerQualityInstead()} 处理。
        //
        // 所以这里只允许"同档或更高"的替换：1080 卡可以换成另一条 1080，
        // 但不会因为某个 576 线路恰好突发速率高就被顶掉。
        int playingRank = ch.sources.get(playing).effectiveRank();
        final int floor = Math.max(0, playingRank);
        // 可信度闸门：精修只允许换成"不比当前更不可信"的线路。
        // 与 floor 同理——当前线路若是清单里带代号确认过的，
        // 一条跑得更快但名字标错的线路不该把它顶掉。
        final int playingTrust = ch.sources.get(playing).trust;

        CompletionService<Result> results = new ExecutorCompletionService<>(POOL);
        List<Future<Result>> probing = new ArrayList<>();
        List<Integer> probeIndex = new ArrayList<>();
        int candidates = 0;
        for (int i = 0; i < ch.sources.size(); i++) {
            if (i == playing || Boolean.TRUE.equals(ch.sources.get(i).starved)) {
                continue;
            }
            int r = ch.sources.get(i).effectiveRank();
            if (r > cap || r < floor) {
                continue;
            }
            if (ch.sources.get(i).trust < playingTrust) {
                continue;
            }
            final int idx = i;
            candidates++;
            probing.add(results.submit(() -> probe(ch.sources.get(idx).url)));
            probeIndex.add(i);
        }
        if (candidates == 0) {
            return -1;
        }

        Result best = null;
        int bestIdx = -1;
        long deadline = System.currentTimeMillis() + PROBE_TOTAL_MS;
        try {
            for (int k = 0; k < candidates; k++) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    break;
                }
                Future<Result> f = results.poll(left, TimeUnit.MILLISECONDS);
                if (f == null) {
                    break;
                }
                int pos = probing.indexOf(f);
                Result r = f.get();
                if (pos < 0 || r == null) {
                    continue;
                }
                int idx = probeIndex.get(pos);
                if (!r.usable() || !r.playableVideo()) {
                    continue;
                }
                if (better(r, best)) {
                    best = r;
                    bestIdx = idx;
                }
            }
        } catch (Exception ignored) {
            // 打断就退回已有结果
        } finally {
            for (Future<Result> f : probing) {
                f.cancel(true);
            }
        }

        if (bestIdx < 0) {
            return -1;
        }
        double gain = score(best) / Math.max(base, 0.01);
        android.util.Log.i("IPTVPROBE", "refine ch=" + ch.name + " base="
                + String.format("%.2f", base) + " best=" + bestIdx + " score="
                + String.format("%.2f", score(best)) + " gain=" + String.format("%.2f", gain)
                + " need=" + String.format("%.2f", gainNeeded));
        // 收益不够大就不折腾，换线是有代价的
        return gain >= gainNeeded ? bestIdx : -1;
    }

    private static void pickInternal(Context ctx, Channel ch, int preferred, Callback cb,
                                     int exclude) {
        final Context app = ctx.getApplicationContext();
        POOL.execute(() -> {
            final int idx = pickBlocking(app, ch, preferred, exclude);
            // 播放器只能在主线程访问，探测结果必须切回来再回调
            MAIN.post(() -> cb.onPicked(idx));
        });
    }

    private static int pickBlocking(Context ctx, Channel ch, int preferred, int exclude) {
        int n = ch.sources.size();
        if (n == 1) {
            return exclude == 0 ? -1 : 0;
        }
        // 清晰度上限：超过它的线路直接不参与竞争。
        // 这是"降档后不再自己跳回高清"的关键——后台精修和失败重挑
        // 都走这里，不可能越权升档。
        final int cap = QualityCap.capRank(ctx, ch.name);

        // 可信度优先：只在"最高可信度"的线路里选。
        //
        // 探测只量得出快不快，量不出"这是不是那个台"。清单里确实有错标
        // （实测选「山东卫视」播英超、选「广东卫视」播 CCTV-11），
        // 一条标错的高码率线路往往比正确线路更快，不设这道闸就会被它抢走。
        //
        // 最高可信度那批全都探不通时，允许降到下一档再试（见下面的重试）。
        int bestTrust = -1;
        for (int i = 0; i < n; i++) {
            Channel.Source s = ch.sources.get(i);
            if (i == exclude || Boolean.TRUE.equals(s.starved) || s.effectiveRank() > cap) {
                continue;
            }
            if (s.trust > bestTrust) {
                bestTrust = s.trust;
            }
        }

        // 候选少的时候没有备胎，每路都探久一点，读数更接近真实持续速率
        int alive = 0;
        for (int i = 0; i < n; i++) {
            Channel.Source s = ch.sources.get(i);
            if (i != exclude && !Boolean.TRUE.equals(s.starved)
                    && s.effectiveRank() <= cap && s.trust == bestTrust) {
                alive++;
            }
        }
        final long budget = alive <= FEW_CANDIDATES ? PROBE_BUDGET_SOLO_MS : PROBE_BUDGET_MS;

        CompletionService<Result> results = new ExecutorCompletionService<>(POOL);
        List<Future<Result>> probing = new ArrayList<>();
        List<Integer> probeIndex = new ArrayList<>();
        int candidates = 0;
        for (int i = 0; i < n; i++) {
            if (i == exclude) {
                continue;
            }
            // 实测喂不饱的线路直接不探，省下时间也给真正能播的源更多机会
            if (Boolean.TRUE.equals(ch.sources.get(i).starved)) {
                continue;
            }
            // 超过清晰度上限的线路不探：探了也不会选，白花时间
            if (ch.sources.get(i).effectiveRank() > cap) {
                continue;
            }
            // 低可信度的线路留到"高可信度全都不行"时再探
            if (ch.sources.get(i).trust != bestTrust) {
                continue;
            }
            final int idx = i;
            candidates++;
            probing.add(results.submit(() -> probe(ch.sources.get(idx).url, budget)));
            probeIndex.add(i);
        }
        if (candidates == 0) {
            // 全部候选都被标记过喂不饱。网络状况会变，必须给一次重试机会，
            // 但把 3 秒内刚失败的那一路排除在外，避免原地反复撞同一个坑。
            //
            // 注意只在「没被清晰度上限挡住」的线路里重试：
            // 上限是用户看到卡顿后降档的结果，不能因为重试又把它抬回去。
            for (Channel.Source s : ch.sources) {
                s.starved = null;
            }
            for (int i = 0; i < n; i++) {
                if (i == exclude || ch.sources.get(i).effectiveRank() > cap) {
                    continue;
                }
                final int idx = i;
                candidates++;
                probing.add(results.submit(() -> probe(ch.sources.get(idx).url, budget)));
                probeIndex.add(i);
            }
        }
        if (candidates == 0) {
            return -1;
        }

        Result best = null;
        int bestIdx = -1;
        Result preferredResult = null;
        // 降级候选：不满足码率余量、但至少能连上播得动的线路
        Result fallback = null;
        int fallbackIdx = -1;
        // 总预算必须盖得住单路预算，否则探测会被统一取消、结果全是 null
        long total = alive <= FEW_CANDIDATES ? PROBE_TOTAL_SOLO_MS : PROBE_TOTAL_MS;
        long deadline = System.currentTimeMillis() + total;
        try {
            for (int k = 0; k < candidates; k++) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    break;
                }
                Future<Result> f = results.poll(left, TimeUnit.MILLISECONDS);
                if (f == null) {
                    break;
                }
                int pos = probing.indexOf(f);
                Result r = f.get();
                if (pos < 0 || r == null) {
                    continue;
                }
                int idx = probeIndex.get(pos);
                android.util.Log.i("IPTVPROBE", "ch=" + ch.name + " idx=" + idx
                        + " speed=" + (r.speed / 1024) + "KB/s need=" + (r.need / 1024)
                        + "KB/s score=" + String.format("%.2f", score(r))
                        + " audio=" + r.audio + " video=" + r.video
                        + " ok=" + (r.usable() && r.playableVideo())
                        + " " + ch.sources.get(idx).url);
                if (idx == preferred) {
                    preferredResult = r;
                }
                if (r.usable() && r.playableVideo()) {
                    if (better(r, best)) {
                        best = r;
                        bestIdx = idx;
                    }
                } else if (r.playableVideo() && r.speed > 0) {
                    // 记下"能播但喂不饱"里最可能出画面的一路，全部不达标时兜底，
                    // 宁可偶尔卡一下也不能黑屏
                    if (betterFallback(r, fallback)) {
                        fallback = r;
                        fallbackIdx = idx;
                    }
                }
                // 已有余量充足的源，且首选线路也有结论了，不必再等慢源
                if (best != null && preferredResult != null && score(best) >= 1.9) {
                    break;
                }
            }
        } catch (Exception ignored) {
            // 探测被打断就退回已有结果
        } finally {
            for (Future<Result> f : probing) {
                f.cancel(true);
            }
        }

        if (bestIdx >= 0) {
            // 首选线路不比最优差时才尊重用户的选择
            if (preferredResult != null
                    && preferredResult.usable() && preferredResult.playableVideo()
                    && !better(best, preferredResult)) {
                android.util.Log.i("IPTVPROBE", "pick=preferred(" + preferred + ")");
                return preferred;
            }
            android.util.Log.i("IPTVPROBE", "pick=best(" + bestIdx + ") score="
                    + String.format("%.2f", score(best)));
            return bestIdx;
        }
        // 没有任何线路能稳定喂饱，挑最接近的一路播起来
        android.util.Log.i("IPTVPROBE", "pick=fallback(" + fallbackIdx + ")"
                + (fallback != null ? " score=" + String.format("%.2f", score(fallback)) : ""));
        return fallbackIdx;
    }

    /**
     * 是否比当前最优更好：先要能出声，再比余量，余量接近时取更清晰的。
     *
     * <p>不看绝对速度：1080p 拿到 1.4 MB/s 很猛，但它自身要 1 MB/s，余量只有 0.4 倍，
     * 播出十几秒就断一次；720p 拿 0.9 MB/s 只需 0.3 MB/s，余量 3 倍能一直播下去。
     */
    private static boolean better(Result r, @Nullable Result best) {
        if (best == null) {
            return true;
        }
        boolean rAac = r.audio == TsAudio.Kind.AAC;
        boolean bestAac = best.audio == TsAudio.Kind.AAC;
        if (rAac != bestAac) {
            return rAac;
        }
        double rs = score(r);
        double bs = score(best);
        // 余量差 0.05 就足以决定"偶尔卡"和"一直顺"。
        // 之前用 0.15，把 1.55(720p/AAC) 和 1.44(1080p) 当成等价，
        // 于是选了更清晰的 1080p——它需要 1262KB/s 而实测只有 820KB/s，
        // 必然每播一会儿就缓冲一次。清晰度是次要的，先保证喂得饱。
        if (Math.abs(rs - bs) > 0.05) {
            return rs > bs;
        }
        // 余量真的相当，才取码率更高的那路（画面更清晰）
        return r.need > best.need;
    }

    /** 余量比值，封顶 2.0：超过 2 倍再多也不提升流畅度，只会偏向低清。 */
    private static double score(Result r) {
        long req = r.need > 0 ? r.need : FALLBACK_NEED;
        return Math.min((double) r.speed / req, 2.0);
    }

    /**
     * 兜底候选的比较：优先"最可能真的出画面"，而不是分数最高。
     *
     * <p>实测教训：某频道只有两条线路，实测 241KB/s 和 569KB/s，
     * 需求都是 1074KB/s，两条都远不够。原逻辑按分数取了 569KB/s 那条
     * （score 0.53），结果起播后一直停在缓冲、永远出不来画面。
     *
     * <p>所以兜底不追速度，而追<b>出画面的概率</b>：
     * 码率需求低意味着分片小、同样带宽下攒得更快，起播成功率更高。
     */
    private static boolean betterFallback(Result r, @Nullable Result best) {
        if (best == null) {
            return true;
        }
        boolean rOk = r.playableVideo();
        boolean bestOk = best.playableVideo();
        if (rOk != bestOk) {
            return rOk;
        }
        boolean rAac = r.audio == TsAudio.Kind.AAC;
        boolean bestAac = best.audio == TsAudio.Kind.AAC;
        if (rAac != bestAac) {
            return rAac;
        }
        long rNeed = r.need > 0 ? r.need : FALLBACK_NEED;
        long bNeed = best.need > 0 ? best.need : FALLBACK_NEED;
        if (rNeed != bNeed) {
            return rNeed < bNeed;
        }
        return r.speed > best.speed;
    }

    /**
     * 量一路流的实测速率，并算出它需要多少码率。
     *
     * <p>播放列表本身只有几百字节，量不出带宽，必须下载真实分片；
     * 分片长度除以 EXTINF 时长就是这路流的实时码率需求。
     * 长度从 GET 响应头直接读，不发额外请求。
     */
    private static Result probe(String url) {
        return probe(url, PROBE_BUDGET_MS);
    }

    /** 指定采样预算的探测；候选很少时用更长的预算拿到真实速率。 */
    private static Result probe(String url, long budgetMs) {
        HttpURLConnection conn = null;
        try {
            String target = url;
            double duration = 0;
            // 最多往下剥两层：master 播放列表 -> 子播放列表 -> 分片
            for (int depth = 0; depth < 2; depth++) {
                conn = open(target);
                if (conn.getResponseCode() < 200 || conn.getResponseCode() >= 400) {
                    return null;
                }
                String text = readHead(conn);
                if (!text.startsWith("#EXTM3U")) {
                    break;
                }
                String next = pickMedia(text);
                if (next == null) {
                    return null;
                }
                if (duration == 0) {
                    duration = segmentDuration(text);
                }
                target = absolute(next, target);
                conn.disconnect();
                conn = null;
            }

            conn = open(target);
            if (conn.getResponseCode() < 200 || conn.getResponseCode() >= 400) {
                return null;
            }
            // 响应头已经拿到，分片真实长度决定了这路流的码率需求
            // getContentLengthLong() 是 API 24 才有的，Android 6 上会 NoSuchMethodError，
            // 这里的异常会被下面的 catch 吞掉导致所有源都探测失败，必须用 int 版。
            long length = conn.getContentLength();
            long need = (duration > 0 && length > 0) ? (long) (length / duration) : 0;

            // 按时间粗测：这里只判断"能不能连、快不快"，不追求真实持续速率。
            // 分片开头的突发窗口会给出偏高的数值，但足够用来排序候选；
            // 真正的"喂不喂得饱"由播放过程判定（Source.starved）。
            long start = System.currentTimeMillis();
            InputStream in = conn.getInputStream();
            byte[] buf = new byte[16 * 1024];
            long total = 0;
            byte[] head = new byte[HEAD_BYTES];
            int headLen = 0;
            long budgetEnd = start + budgetMs;
            while (System.currentTimeMillis() < budgetEnd && total < PROBE_BYTES) {
                int got = in.read(buf);
                if (got <= 0) {
                    break;
                }
                if (headLen < head.length) {
                    int n = Math.min(got, head.length - headLen);
                    System.arraycopy(buf, 0, head, headLen, n);
                    headLen += n;
                }
                total += got;
            }
            in.close();
            long elapsed = Math.max(1, System.currentTimeMillis() - start);
            return new Result(total * 1000L / elapsed, need,
                    TsAudio.detect(head, headLen), TsAudio.detectVideo(head, headLen));
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** 读响应开头，用于判断是不是播放列表。 */
    private static String readHead(HttpURLConnection conn) throws IOException {
        InputStream in = conn.getInputStream();
        try {
            ByteArrayOutputStream head = new ByteArrayOutputStream(1024);
            byte[] buf = new byte[1024];
            int got;
            while (head.size() < 1024) {
                got = in.read(buf, 0, 1024 - head.size());
                if (got <= 0) {
                    break;
                }
                head.write(buf, 0, got);
            }
            return new String(head.toByteArray(), "UTF-8").trim();
        } finally {
            in.close();
        }
    }

    /**
     * 从播放列表里挑出真正要下载的地址。
     * master 列表取最高带宽的变体，普通列表取最后一个分片。
     */
    private static String pickMedia(String playlist) {
        String best = null;
        long bestBandwidth = -1;
        String[] lines = playlist.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String s = lines[i].trim();
            if (s.startsWith("#EXT-X-STREAM-INF")) {
                long bw = parseIntAttr(s, "BANDWIDTH");
                String uri = nextUri(lines, i);
                if (uri != null && bw > bestBandwidth) {
                    bestBandwidth = bw;
                    best = uri;
                }
            }
        }
        if (best != null) {
            return best;
        }
        // 普通媒体列表：最后一个分片最新，也最接近实时下载
        for (int i = lines.length - 1; i >= 0; i--) {
            String s = lines[i].trim();
            if (!s.isEmpty() && !s.startsWith("#")) {
                return s;
            }
        }
        return null;
    }

    private static String nextUri(String[] lines, int from) {
        for (int i = from + 1; i < lines.length; i++) {
            String s = lines[i].trim();
            if (s.isEmpty()) {
                continue;
            }
            return s.startsWith("#") ? null : s;
        }
        return null;
    }

    /** 取播放列表里 EXTINF 的时长（秒）。 */
    private static double segmentDuration(String playlist) {
        for (String line : playlist.split("\n")) {
            String s = line.trim();
            if (!s.startsWith("#EXTINF:")) {
                continue;
            }
            int comma = s.indexOf(',');
            String v = comma > 0 ? s.substring(8, comma) : s.substring(8);
            try {
                return Double.parseDouble(v.trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private static long parseIntAttr(String line, String key) {
        int i = line.indexOf(key + "=");
        if (i < 0) {
            return -1;
        }
        int p = i + key.length() + 1;
        int end = p;
        while (end < line.length() && Character.isDigit(line.charAt(end))) {
            end++;
        }
        try {
            return Long.parseLong(line.substring(p, end));
        } catch (Exception e) {
            return -1;
        }
    }

    /** 相对地址按上一层的目录补全。 */
    private static String absolute(String ref, String base) {
        if (ref.startsWith("http")) {
            return ref;
        }
        int cut = base.lastIndexOf('/');
        return cut < 0 ? ref : base.substring(0, cut + 1) + ref;
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "IptvLive/1.0");
        return conn;
    }
}
