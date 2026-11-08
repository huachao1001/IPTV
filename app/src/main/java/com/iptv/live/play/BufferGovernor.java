package com.iptv.live.play;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.LoadControl;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.upstream.Allocator;
import androidx.media3.exoplayer.upstream.DefaultAllocator;

/**
 * 播放缓冲的"管家"：自适应调节起播门槛、缓冲目标与直播延迟。
 *
 * <p><b>为什么不能写死参数。</b>写死一套阈值只对某一类线路成立：
 * <ul>
 *   <li>守得太紧（起播 4 秒、落后直播 10 秒），码率稍高或网络稍抖就
 *       播十几秒缓冲耗尽、进一次 {@code STATE_BUFFERING}——用户看到的就是
 *       <b>缓冲圈一直在闪</b>；</li>
 *   <li>守得太松（起播 8 秒、落后 18 秒），网络明明很好也要干等，
 *       换台要盯着黑屏好几秒，延迟也大。</li>
 * </ul>
 * 所以这里改成<b>按实测证据逐级调整</b>：真的闪过一次缓冲就加厚一层，
 * 稳定跑够一段时间再慢慢收回。宽度只有几档，收放都有上限，
 * 不会因为一次抖动就把延迟推到窗口外（那会导致永久缓冲）。
 *
 * <p><b>上调的直接依据是"看着看着又卡了"</b>（{@link #onRebuffer(long)}），
 * 起播时的首次攒缓冲不算——那是正常流程。
 *
 * <p><b>下调的依据是"连续稳定"</b>（{@link #onStablePlayback()}）：
 * 播满 {@link #STABLE_STEP_MS} 没有闪缓冲才收回一档，避免来回抖。
 *
 * <p>直播有一个硬约束：能缓冲多深取决于"落后直播多少"，
 * 因为数据到直播边缘就没了。所以加厚缓冲必须同时放大
 * {@link #offsetMs()}（直播延迟），两者一起动才有效。
 * 具体数值约束见 {@link #LEVELS} 的注释。
 */
@UnstableApi
public final class BufferGovernor implements LoadControl {

    private static final String TAG = "IPTVBUFFER";

    /* ---------------- 档位表 ---------------- */

    /**
     * 各档参数：{起播门槛, 重缓冲门槛, 最大缓冲, 直播延迟}，单位毫秒。
     *
     * <p>第 0 档是"网络很好"的激进值，换台最快、延迟最小；
     * 每升一档多攒一到两秒余量。档位不多（5 档）是有意的：
     * 档位太密会让参数频繁变动，观感反而不稳。
     *
     * <p><b>这张表有三条硬约束，改数字前必须逐行核对：</b>
     * <ol>
     *   <li><b>{@code max < offset}</b>。直播能缓冲多深，完全由"播放位置
     *       落后直播多少"决定——数据到直播边缘就没了。{@code max} 一旦不小于
     *       {@code offset}，播放器就会一直请求、永远攒不满，表现为
     *       "缓冲量卡在某个值不动"或反复"缓满/追直播"。
     *       第一版把 {@code max} 写成 14000 而 {@code offset} 只有 10000，
     *       实测就是缓冲一路从 9.7 秒掉到 0 秒、再跳回 9.6 秒，
     *       每 16 秒一次——正是用户看到的<b>缓冲圈一直闪</b>。</li>
     *   <li><b>{@code offset} 必须明显小于直播窗口</b>（实测 5 片 × 5 秒 = 25 秒）。
     *       越高抗抖动越好，但越过窗口左边界就再也拿不到数据，
     *       播放器会安静地停在 BUFFERING、位置持续往负方向涨，永远恢复不了。
     *       上限取 18 秒，离 25 秒窗口还有 7 秒富余。</li>
     *   <li><b>{@code gate <= max}</b>。起播门槛高于能攒到的深度，
     *       播放器永远起不来。</li>
     * </ol>
     *
     * <p><b>这里刻意没有"最小缓冲"这一列。</b>{@code DefaultLoadControl} 的
     * 公式是"攒到 min 就停手"，本类已改成"一直吃到 max"（理由见
     * {@link #shouldContinueLoading}），min 不再参与任何判断。
     * 留一个不生效的列只会误导后人。
     *
     * <p>第 0 档的 {@code offset} 从早期的 10 秒放宽到 14 秒：分片是 5 秒一个，
     * 只落后 10 秒时手里最多攥住一片多一点，下次分片还没下完就播空了；
     * 14 秒能稳稳装下两个整片，这才是"不再每十几秒卡一次"的关键。
     * 起播速度不受影响——{@link #shouldStartPlayback} 只看起播门槛（4.5 秒），
     * 不看 offset。
     */
    private static final int[][] LEVELS = {
            //  gate   rebuf   max     offset
            {4_500, 5_500, 12_000, 14_000},
            {5_000, 6_000, 14_000, 16_000},
            {5_500, 6_500, 15_000, 17_000},
            {6_000, 7_000, 15_500, 18_000},
            {7_000, 8_000, 16_000, 18_000},
    };

    /** 升档窗口内闪过几次缓冲就加厚一档。 */
    private static final int ESCALATE_HITS = 2;
    /** 统计"闪缓冲"的滑动窗口。 */
    private static final long ESCALATE_WINDOW_MS = 90_000L;
    /**
     * 缓冲持续多久才算"真卡了一次"。
     *
     * <p>分片边界的空档通常在 400 毫秒以内，追直播的一次微调也在 1 秒以内。
     * 取 1 秒能把这类正常抖动全部挡掉，同时任何真的"喂不动"都远长于它。
     * 不设这道门槛的话，坏网络下每秒都能记一次，半分钟就把档位推满。
     */
    private static final long MIN_FLASH_MS = 1_000L;
    /** 同一次抖动会多次进出缓冲态，按这个间隔去重。 */
    private static final long HIT_DEDUPE_MS = 15_000L;
    /**
     * 两次升档之间的最小间隔。
     *
     * <p>没有这条冷却时会看到"30 秒内升 4 档"——延迟一路推到 18 秒，
     * 而起播门槛也涨到 7 秒，换台明显变慢。实际上加厚一层就要观察
     * 一段时间才知道够不够，连续升档毫无信息量。取 2 分钟：
     * 够观察一个新档位是否生效，又不会让真卡顿等太久。
     */
    private static final long RAISE_COOLDOWN_MS = 2 * 60_000L;
    /** 满档告警的限频间隔，避免每秒刷一条日志。 */
    private static final long MAX_LOG_INTERVAL_MS = 30_000L;
    /** 连续稳定播多久才收回一档。 */
    private static final long STABLE_STEP_MS = 5 * 60_000L;
    /** 相邻两次降档之间的最小间隔，防止"稳定—降档—又卡—升档"打摆子。 */
    private static final long DEESCALATE_COOLDOWN_MS = 3 * 60_000L;

    private final DefaultAllocator allocator =
            new DefaultAllocator(true, 0x10000 /* 64 KiB */);

    /** 当前档位，见 {@link #LEVELS}。 */
    private volatile int level;
    /** 本轮窗口内的闪缓冲次数与窗口起点。 */
    private int hits;
    private long windowAt;
    /** 上一次记入命中、升档、满档告警的时刻。 */
    private long lastHitAt;
    private long lastRaiseAt;
    private long lastMaxLogAt;
    /** 上次降档时刻。 */
    private long lastLowerAt;
    /** 上一路稳定播放的累计时长（用于判断"够稳了"）。 */
    private long stableSince;

    public BufferGovernor(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
        // 上一轮选过的档位要沿用：它是这台机器 + 这条网络的实测结论，
        // 每次启动都从 0 档重来等于让用户每天重新踩一遍同样的坑。
        this.level = clamp(BufferLevelStore.get(appCtx));
        Log.i(TAG, "init " + snapshot());
    }

    /** ApplicationContext，用于把档位落盘（用 Application 不会泄漏 Activity）。 */
    private final Context appCtx;

    /* ---------------- LoadControl 实现 ---------------- */

    @Override
    public Allocator getAllocator() {
        return allocator;
    }

    @Override
    public long getBackBufferDurationUs() {
        return 0;
    }

    @Override
    public boolean retainBackBufferFromKeyframe() {
        return false;
    }

    /**
     * 要不要继续下载。
     *
     * <p><b>这里不能照抄 {@code DefaultLoadControl} 的公式。</b>它的判据是
     * {@code buffered < min || (buffered < max && speed > 1)}——1 倍速下
     * 只要攒到 {@code min} 就会停手。对点播没问题（数据一直在那儿），
     * 对直播却是个陷阱：停下时手里的余量正好是最薄的那一档，
     * 下个分片稍慢就播空、进一次 BUFFERING，然后再攒到 min 再播空——
     * 用户看到的就是<b>每十几秒闪一次缓冲圈</b>。
     *
     * <p>实测就发生过：缓冲量从 9.7 秒一路掉到 0.1 秒、卡住、再跳到 9.7 秒，
     * 周期 16 秒，和分片长度完全对得上。
     *
     * <p>所以这里改成"一直吃到 {@code max} 为止"。直播的数据量天然有上限
     * （到直播边缘就没有了），不会像点播那样把整部片子拖下来，
     * 多吃一点纯粹是垫厚余量。{@code max} 已按硬约束取在 {@code offset} 之下，
     * 不会出现"永远吃不满"。
     */
    @Override
    public boolean shouldContinueLoading(Parameters p) {
        long bufferedMs = p.bufferedDurationUs / 1000;
        return bufferedMs < maxBufferMs();
    }

    @Override
    public boolean shouldStartPlayback(Parameters p) {
        long bufferedMs = p.bufferedDurationUs / 1000;
        int gate = p.rebuffering ? rebufferGateMs() : playGateMs();
        // 直播起点有下限：目标偏移一小段之后就已经是"可播"了。
        // 若门槛高于当前能缓冲到的深度，播放器永远起不来，所以取小值兜底。
        long usable = Math.max(0, p.targetLiveOffsetUs / 1000);
        if (usable > 0) {
            gate = Math.min(gate, (int) Math.max(1_000, usable - 500));
        }
        return bufferedMs >= gate;
    }

    /* 下面几个回调只在需要感知"选中了哪些轨道"时才用，这里不需要。 */

    @Override
    public void onPrepared(PlayerId playerId) {
    }

    @Override
    public void onTracksSelected(PlayerId playerId, androidx.media3.common.Timeline timeline,
                                 MediaSource.MediaPeriodId mediaPeriodId,
                                 androidx.media3.exoplayer.Renderer[] renderers,
                                 TrackGroupArray trackGroups, ExoTrackSelection[] selections) {
    }

    @Override
    public void onStopped(PlayerId playerId) {
    }

    @Override
    public void onReleased(PlayerId playerId) {
        allocator.reset();
    }

    /* ---------------- 自适应 ---------------- */

    /**
     * 记录一次"看着看着又卡了"。
     *
     * <p><b>只有出过画面之后、且卡了超过 {@code MIN_FLASH_MS} 的缓冲才算。</b>
     * 两个条件都不能省：
     * <ul>
     *   <li>起播时攒那几秒是正常流程，算进来会让"换个高码率台"直接升两档；</li>
     *   <li>分片边界的几十毫秒空档在坏网络上每秒都能出现好几次，
     *       全算进来会在半分钟内把档位推满、延迟涨到 18 秒，
     *       而画面其实一直是好的。</li>
     * </ul>
     *
     * @param stalledMs 这次缓冲实际持续了多久（毫秒）
     */
    public void onRebuffer(long stalledMs) {
        if (stalledMs < MIN_FLASH_MS) {
            return;
        }
        long now = System.currentTimeMillis();
        // 又卡了一次，"连续稳定"从头算起
        stableSince = 0;
        if (windowAt == 0 || now - windowAt > ESCALATE_WINDOW_MS) {
            windowAt = now;
            hits = 0;
        }
        // 一次抖动只记一笔。窗口内连续两次才算"这台机器就是喂不动"。
        if (now - lastHitAt < HIT_DEDUPE_MS) {
            return;
        }
        lastHitAt = now;
        hits++;
        if (hits < ESCALATE_HITS) {
            Log.i(TAG, "rebuffer hits=" + hits + "/" + ESCALATE_HITS
                    + " stalledMs=" + stalledMs + " " + snapshot());
            return;
        }
        hits = 0;
        windowAt = now;
        if (level >= LEVELS.length - 1) {
            // 已经是最厚的一档还是卡：这不是缓冲策略能解决的问题，
            // 交给上层的降档/换线逻辑（那是带宽真的不够）。
            // 这一句限频，否则每秒一次会把日志淹掉。
            if (now - lastMaxLogAt > MAX_LOG_INTERVAL_MS) {
                lastMaxLogAt = now;
                Log.w(TAG, "rebuffer maxed out " + snapshot());
            }
            return;
        }
        if (now - lastRaiseAt < RAISE_COOLDOWN_MS) {
            // 刚加厚过，还没到观察期结束。连着升会把延迟一路推到上限，
            // 而问题多半在带宽而不是余量，该交给降档逻辑。
            Log.i(TAG, "escalate deferred cooldown=" + (RAISE_COOLDOWN_MS / 1000) + "s");
            return;
        }
        level++;
        lastRaiseAt = now;
        hits = 0;
        BufferLevelStore.put(appCtx, level);
        Log.w(TAG, "escalate level=" + level + " stalledMs=" + stalledMs + " " + snapshot());
    }

    /**
     * 记录"这一路还在稳定播"。由宿主的周期轮询调用（每秒一次即可）。
     *
     * <p>收得慢是刻意的：升档是为了救急，降档只是为了把延迟调回来，
     * 不值得冒"刚降完又卡"的风险。所以要求连续稳定 5 分钟、
     * 且与上次降档间隔 3 分钟以上。
     *
     * <p>"连续"的口径是<b>距离最近一次闪缓冲或换流</b>的时间，
     * 所以 {@link #onRebuffer(long)} 与 {@link #onStreamChanged()} 都会重置它。
     */
    public void onStablePlayback() {
        if (level <= 0) {
            stableSince = 0;
            return;
        }
        long now = System.currentTimeMillis();
        if (stableSince == 0) {
            stableSince = now;
            return;
        }
        if (now - stableSince < STABLE_STEP_MS) {
            return;
        }
        if (now - lastLowerAt < DEESCALATE_COOLDOWN_MS) {
            return;
        }
        stableSince = now;
        lastLowerAt = now;
        level--;
        BufferLevelStore.put(appCtx, level);
        Log.i(TAG, "deescalate level=" + level + " " + snapshot());
    }

    /**
     * 换流时清掉"连续稳定"的计时。
     *
     * <p><b>但绝不重置 {@link #hits}。</b>线路被看门狗重开同一条地址，
     * 本身就是"这条流喂不动"的证据，把它当成全新一轮等于永远攒不到
     * {@link #ESCALATE_HITS}——实测日志里每一次都是 {@code rebuffer hits=1/2}，
     * 档位一次都升不上去，缓冲圈于是照样一直闪。
     *
     * <p>档位本身是<b>这台机器 + 这条网络</b>的结论，不是某个频道的结论，
     * 所以换台时把上一台的卡顿次数带过来是对的。
     */
    public void onStreamChanged() {
        stableSince = 0;
    }

    /* ---------------- 对外读数 ---------------- */

    public int playGateMs() {
        return LEVELS[level][0];
    }

    public int rebufferGateMs() {
        return LEVELS[level][1];
    }

    public int maxBufferMs() {
        return LEVELS[level][2];
    }

    /**
     * 当前应该落后直播多少毫秒。
     *
     * <p>新建播放条目时读它，所以档位变化从<b>下一次起播</b>开始生效——
     * 播放中途改偏移只能靠跳转，那会在画面上看得见。
     */
    public int offsetMs() {
        return LEVELS[level][3];
    }

    public int level() {
        return level;
    }

    /** 一行日志，便于真机核对当前实际参数。 */
    public String snapshot() {
        return "level=" + level
                + " gate=" + playGateMs()
                + " rebuf=" + rebufferGateMs()
                + " max=" + maxBufferMs()
                + " offset=" + offsetMs();
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(LEVELS.length - 1, v));
    }

    /* ---------------- 落盘 ---------------- */

    /**
     * 档位落盘。
     *
     * <p>不落盘的话每次冷启动都从 0 档开始，用户就要重新经历一遍
     * "播一会儿闪一下 → 加厚 → 稳定"的过程。档位是这台机器在当前网络下的
     * 实测结论，跨启动沿用才对。
     */
    private static final class BufferLevelStore {
        private static final String PREFS = "buffer_level";
        private static final String KEY = "level";

        static int get(Context ctx) {
            return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getInt(KEY, 0);
        }

        static void put(Context ctx, int level) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putInt(KEY, level).apply();
        }
    }
}
