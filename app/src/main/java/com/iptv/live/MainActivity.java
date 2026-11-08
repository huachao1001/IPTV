package com.iptv.live;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime;
import androidx.media3.exoplayer.source.LoadEventInfo;
import androidx.media3.exoplayer.source.MediaLoadData;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.iptv.live.model.Channel;
import com.iptv.live.model.ChannelGroups;
import com.iptv.live.play.LiveSpeedMeter;
import com.iptv.live.play.LoadSpeedMeter;
import com.iptv.live.play.PlaybackTuning;
import com.iptv.live.play.StallDetector;
import com.iptv.live.source.ChannelStore;
import com.iptv.live.source.DailyRefresh;
import com.iptv.live.source.DeadSources;
import com.iptv.live.source.QualityCap;
import com.iptv.live.source.QualityMemory;
import com.iptv.live.source.SourceMemory;
import com.iptv.live.source.SourceProber;
import com.iptv.live.ui.BufferingIndicator;
import com.iptv.live.ui.ChannelAdapter;
import com.iptv.live.ui.CenteringLayoutManager;
import com.iptv.live.ui.GroupAdapter;
import com.iptv.live.ui.UiScale;
import com.iptv.live.ui.VirtualRemote;

/** 直播主界面：全屏播放 + 分组导航抽屉 + 清晰度落地窗。 */
public class MainActivity extends Activity {

    private static final long HIDE_BAR_MS = 6000;
    /** 卡顿判定阈值：只用于内部状态记账，不再弹提示。 */
    private static final long STALL_LIMIT_MS = 6000;
    /** 卡死检测的轮询间隔。 */
    private static final long STALL_POLL_MS = 1000;
    /**
     * 同一条线路原地重试的轮数。
     * 节目单里的流很稳，报错多是分片偶发 404，重试几次就能接上，
     * 因此宁可多试也不要急着判死、更不要打断画面。
     */
    private static final int MAX_RETRY_ROUNDS = 8;
    /**
     * 分片跳变多少次就判定这条线路喂不饱。
     *
     * <p>带宽不足时播放器只能跳过追不上的分片，表现为分片序号出现断层。
     * 这是"探测测不出、但用户一定看得到"的真凭实据，
     * 比任何预探测都准，所以用它来给线路定性。
     */
    private static final int STARVED_SKIP_LIMIT = 2;
    /** 数字键换台的有效输入窗口。 */
    private static final long NUMBER_WINDOW_MS = 1500;
    /**
     * 右侧清晰度面板宽度（屏幕宽度的百分比）。
     *
     * <p>规则 4：宽度要小。面板里只有 `1080P` 这种四个字符的档位标签，
     * 26% 会白占掉四分之一画面；16% 在 1080p 上是 307px，
     * 足够放下四个字加左右呼吸空间，同时把画面让出来。
     */
    private static final int RIGHT_PANEL_PCT = 16;
    /** 缓冲区攒到这个时长即视为可以起播，环形进度以此为 100%。 */
    private static final long BUFFER_TARGET_MS = 10_000;
    /** 收藏分组在导航栏的位置。 */
    private static final String FAVORITES = "收藏";
    /**
     * 纯预览模式：不创建播放器、不探测网络，画面位置固定显示静态图。
     * 只在调界面时临时打开；默认走真实播放。
     */
    private static final boolean PREVIEW_ONLY = false;
    /** 临时诊断：每 2 秒打印播放器内部状态，定位卡顿用。 */
    private static final boolean DEBUG_PLAYBACK = true;

    private ExoPlayer player;
    private PlayerView playerView;
    private BufferingIndicator loading;
    private RecyclerView list;
    /** 频道列表的布局管理器：负责把当前行滚到垂直正中。 */
    private CenteringLayoutManager listManager;
    /**
     * 频道列表里上一次落焦的行下标。
     *
     * <p>居中滚动会把焦点行滚出并回收，此时 {@code getCurrentFocus()}
     * 已经拿不到它，{@code focusedPosition()} 返回 -1。用它兜住续算
     * 的基准，否则连续按 DOWN 会在同一行上原地打转。
     */
    private int lastFocusPos = -1;
    /**
     * 是否正在由 {@link #moveChannelFocusTo(int)} 主动滚动。
     *
     * <p>落焦会触发 {@code OnGlobalFocusChangeListener}，那里也会居中一次；
     * 两处同时发起平滑滚动会互相打断（表现为滚一下停住）。
     * 主动滚的那一次把标志置位，让监听器这一帧让位。
     */
    private boolean smoothScrollPending;
    private RecyclerView groupList;
    private View drawer;
    private View topBar;
    /** 正在播放的台标：在左上角顶栏里，紧挨台名（原右下角两个 logo 已合并成这一个）。 */
    private android.widget.ImageView infoLogo;
    /** 右侧清晰度面板：贴屏幕右缘竖排，每个档位一行。 */
    private LinearLayout qualityPanel;
    private LinearLayout qualityRow;
    private android.widget.ScrollView qualityScroll;
    private TextView nowName;
    private TextView nowMeta;
    private TextView clock;
    private TextView hint;
    private TextView drawerSub;
    private TextView emptyTip;

    /** 全量频道，跨分组保留。 */
    private final List<Channel> master = new ArrayList<>();
    /** 当前分组下的频道，适配器直接读它。 */
    private final List<Channel> channels = new ArrayList<>();
    /** 分组名 -> 频道，顺序与导航栏一致。 */
    private final Map<String, List<Channel>> groupMap = new LinkedHashMap<>();
    private final List<String> groupNames = new ArrayList<>();

    private ChannelAdapter adapter;
    private GroupAdapter groupAdapter;
    private ChannelStore store;
    private GestureDetector gestures;

    private int currentGroup = 0;
    /**
     * 列表焦点行 / 当前选中的行在 {@link #channels} 里的下标。
     *
     * <p><b>它只表示"列表里的位置"，不表示"正在播放哪个台"。</b>
     * 允许为 -1：切到不含正在播频道的分组时，列表里没有任何一行是
     * "正在播放"，此时不能把它夹成 0——那会把第一行误标成正在播放，
     * 且后续所有 {@code channels.get(current)} 都会操作到另一个台。
     *
     * <p>"正在播放哪个台"一律看 {@link #playingName}。
     */
    private int current = -1;
    /**
     * 换台令牌：每次 play() 自增。
     *
     * <p>异步回调（探测、精修）都带着发起时的令牌回来，
     * 令牌对不上说明期间又换过台，结果必须丢弃。
     * 只比 current 是不够的——用户可能切走又切回同一个台。
     */
    private int playToken = 0;
    /**
     * 正在播放的频道名。<b>这是"正在播放"的唯一权威</b>。
     *
     * <p>播放相关的逻辑一律通过它取频道（{@link #playingChannel()}），
     * 不用 {@code channels.get(current)}：current 是列表焦点行，
     * 用户换到别的分组浏览时它指向的是另一个台，甚至是 -1。
     */
    private String playingName = "";
    /**
     * 正在播放的那个 {@link Channel} <b>实例</b>。
     *
     * <p>为什么不能只按名字去 {@code master} 里找：节目单每次刷新都会
     * 重新解析出<b>全新的 Channel 对象</b>，来源线路的顺序、甚至数量都可能变。
     * 此刻播放器播的还是旧对象，若让看门狗/降档操作新对象，{@code sourceIndex}
     * 就会指到另一条 URL 上——"降档后还是卡"、"换源换到没播的那条"都来自这里。
     *
     * <p>所以播放相关的逻辑一律操作这个实例；它和 UI 里显示的同名行是两回事。
     */
    private Channel playingRef;
    /** 当前频道内第几路流，用于失败换源。 */
    private int sourceIndex = 0;
    private int retryRound = 0;
    /** 卡死检测：上一次看到的缓冲量与对应时间。 */
    private long lastBuffered = -1;
    private long lastBufferedAt = 0L;
    /** 最近一个媒体分片的序号，用于发现"跳分片"。 */
    private int lastChunkSeq = -1;
    /** 分片命名前缀；前缀变了说明序号不可比，重新起算。 */
    private String lastChunkPrefix = "";
    private int chunkSkips = 0;
    private final StringBuilder numberBuf = new StringBuilder();
    private int aspectMode = AspectRatioFrameLayout.RESIZE_MODE_FIT;
    /** 最近一次按键时间，用于区分遥控器与触摸操作。 */
    private long lastKeyAt = 0L;
    /** 本次播放是否已经出过画面，用来区分起播缓冲与中途卡顿。 */
    private boolean hasPlayed = false;
    /** 中途卡顿的起始时刻，0 表示当前没在卡。 */
    private long rebufferStart = 0L;

    /**
     * 本次播放已经因为「喂不饱」降过档，不再重复降。
     *
     * <p>降档要走"标记 → 重挑 → 换线"，一轮就要几秒黑屏。
     * 同一次播放里反复降会变成连续黑屏，所以只降一次；
     * 真降不下来再交给看门狗，而不是继续在这里换档。
     */
    private boolean qualityLoweredThisSession = false;
    /**
     * 降档的冷却时间。
     *
     * <p>刚降下去时新线路正要开始缓冲，此时 {@code STATE_BUFFERING}
     * 还会被记一次卡顿，如果不加冷却就会立刻再降一档，
     * 一路降到 576 —— 用户会觉得"越看越糊"。
     */
    private long lastQualityLowerAt = 0L;
    private static final long QUALITY_LOWER_COOLDOWN_MS = 30_000L;

    /**
     * 用户手动选过清晰度之后，一段时间内<b>不许自动降档</b>。
     *
     * <p>用户点 1080 说明他明确想看高清。若两秒后因为一次起播缓冲就自动
     * 降到 720，他会觉得"点了没反应"甚至"跟我对着干"。所以给一段
     * 观察期：这段时间内自动降档完全禁用，让用户自己判断要不要换回去。
     *
     * <p>观察期过后恢复正常：真喂不动还是会降，否则就等于把用户锁在
     * 一个永远卡的画面里。这段时间也给新线路留出攒够缓冲的机会——
     * 1080 的缓冲本来就是边播边耗，头十几秒的缓冲不代表喂不动。
     */
    private static final long MANUAL_PICK_GRACE_MS = 90_000L;
    /** 用户最后一次手动选清晰度的时刻，0 表示本次会话没手动选过。 */
    private long lastManualPickAt = 0L;
    /**
     * 手动选清晰度发生在<b>哪个频道</b>。
     *
     * <p>必须绑定频道名：观察期的意思是"别打断这个台的观看"，
     * 不是"全局 90 秒不允许换线"。否则用户在 A 台点了高清，
     * 马上换到 B 台，B 台的自动降档和精修也会被一起冻住——
     * B 台可能本来就喂不动，却因为 A 台的操作一直卡着。
     */
    private String manualPickChannel = null;
    /**
     * 手动选中的这条线路连续起不来的次数。
     *
     * <p>观察期内不能无限容忍黑屏：用户点 1080 之后若每次都超时，
     * 只能降档救场。但第一次超时先给同一条线路再试一次——
     * 起播慢七八秒在高码率源上很常见，一次超时就降档等于"点了没用"。
     */
    private int manualGraceStrikes = 0;
    /** 观察期内允许重开同一条线路的次数。 */
    private static final int MANUAL_GRACE_STRIKES = 1;

    /**
     * 当前是否处于"用户刚手动选过清晰度"的观察期。
     *
     * @param channel 正在判断的频道名
     */
    private boolean inManualGrace(String channel) {
        if (lastManualPickAt <= 0 || channel == null) {
            return false;
        }
        if (manualPickChannel != null && !manualPickChannel.equals(channel)) {
            return false;
        }
        return System.currentTimeMillis() - lastManualPickAt < MANUAL_PICK_GRACE_MS;
    }

    /**
     * 已经出画面之后，又开始重新缓冲的次数统计。
     *
     * <p><b>这是"喂不饱"最直接的证据，也是最常被漏掉的一种。</b>
     * 实测 1080 那条线路（{@code 222.223.41.27}，需求约 1.0 MB/s，
     * 本机只能拿到 0.42~0.54 MB/s）不会报错、也不会跳分片，
     * 它的形态是：出画面 → 播七八秒缓冲耗尽 → 缓冲 → 再播 → 再缓冲，
     * 45 次采样里 17 次落在 {@code state=2}。
     *
     * <p>其它三条判据都盖不住这种：
     * <ul>
     *   <li>起播看门狗只管"还没出过画面"；</li>
     *   <li>{@code STARVED_SKIP_LIMIT} 要看分片跳号，而它每次只晚了不到一片；</li>
     *   <li>静默卡死看的是 READY 下画面不动，而它是在缓冲和播放之间来回。</li>
     * </ul>
     * 所以在同一段时间内反复进入缓冲，就该降档了。
     */
    private int rebufferCount = 0;
    /** 本轮统计的起始时刻，0 表示还没开始计。 */
    private long rebufferWindowAt = 0L;
    /** 多少秒内反复缓冲就判定喂不动。太短会把正常的换线缓冲也算进来。 */
    private static final long REBUFFER_WINDOW_MS = 90_000L;
    /** 窗口内缓冲几次算喂不动。1080 实测是每 5~8 秒一次，2 次太少会误判。 */
    private static final int REBUFFER_LIMIT = 3;
    /**
     * 上一次真正开始播放的地址，用来判断"是不是换了新流"。
     *
     * <p>反复缓冲的计数<b>只能在新流上清零</b>。同一个地址被看门狗重开时
     * 若也清零，那"一直喂不动、反复被重开"的线路永远攒不到阈值——
     * 实测 CCTV-9 的 1080 就是这样：缓冲 18 秒 → 播 4 秒 → 又缓冲 → 被重开，
     * 计数器每次归零，直到起播看门狗超时才降档。
     */
    private String lastStartedUrl = null;

    private final Handler ui = new Handler(Looper.getMainLooper());
    /** 分片下载速率，缓冲时显示给用户看。 */
    private final LoadSpeedMeter speed = new LoadSpeedMeter();
    private final Runnable hideBar = () -> topBar.setVisibility(View.GONE);
    private final Runnable clearNumber = () -> numberBuf.setLength(0);
    private final Runnable hideHint = () -> hint.setVisibility(View.GONE);


    /**
     * 缓冲期间刷新环形进度。
     * 以"攒够起播所需的缓冲"为 100%，用户能直接看出还要等多久。
     */
    private final Runnable bufferProgress = new Runnable() {
        @Override
        public void run() {
            if (player == null || player.getPlaybackState() != Player.STATE_BUFFERING) {
                return;
            }
            loading.setProgress(player.getTotalBufferedDuration() / (float) BUFFER_TARGET_MS);
            loading.setSpeedText(speedText());
            ui.postDelayed(this, 200);
        }
    };

    private void startBufferProgress() {
        ui.removeCallbacks(bufferProgress);
        loading.setProgress(-1f);
        ui.post(bufferProgress);
    }

    /** 缓冲环下方的文案：有实测速率就显示速率，刚开始连流时给一句可读的状态。 */
    private String speedText() {
        LiveSpeedMeter m = LiveSpeedMeter.INSTANCE;
        long bps = m.bytesPerSecond();
        if (bps > 0) {
            return LoadSpeedMeter.format(bps);
        }
        return m.hasSample() ? "0 KB/s" : "连接中";
    }

    private void stopBufferProgress() {
        ui.removeCallbacks(bufferProgress);
    }

    /**
     * 卡死自愈：缓冲量长时间不增长就强制重建播放。
     *
     * <p>恢复逻辑只挂在 onPlayerError 上是不够的：滑出直播窗口之后
     * 播放器不会报错，而是安静地停在 STATE_BUFFERING、位置持续往负方向增长，
     * 缓冲量一动不动。用户看到的是永久黑屏，必须主动救回来。
     */
    private final Runnable stallWatchdog = new Runnable() {
        @Override
        public void run() {
            if (player == null || player.getPlaybackState() != Player.STATE_BUFFERING) {
                return;
            }
            long buffered = player.getTotalBufferedDuration();
            long now = System.currentTimeMillis();
            if (buffered > lastBuffered + 200 || buffered <= 0) {
                // 还在涨（或刚清空准备重下），继续观察
                lastBuffered = buffered;
                lastBufferedAt = now;
                ui.postDelayed(this, STALL_POLL_MS);
                return;
            }
            if (now - lastBufferedAt < STALL_LIMIT_MS) {
                ui.postDelayed(this, STALL_POLL_MS);
                return;
            }
            // 缓冲量在 STALL_LIMIT_MS 内没有任何增长，判定卡死。
            // 窗口滑走只能重开这一路流，继续等下去永远不会恢复
            android.util.Log.w("IPTVSTALL", "stalled buffered=" + buffered
                    + " pos=" + player.getCurrentPosition() + ", recovering");
            lastBuffered = 0;
            lastBufferedAt = now;
            retryRound = 0;
            Channel c = playingChannel();
            if (c != null) {
                startPlayback(c);
            }
        }
    };
    /** 起播超时：准备这么久仍未出画面，就判定这条线路不行，换下一条。 */
    private static final long STARTUP_TIMEOUT_MS = 7_000;
    /** 起播看门狗的轮询间隔。 */
    private static final long STARTUP_POLL_MS = 500;
    /**
     * 起播连续失败多少轮就放弃。
     *
     * <p>节目单里确实有害群之马（实测某台三条线路都只有 100~440KB/s，
     * 需求 1074KB/s），这种台怎么换都起不来。若不停重试，
     * 看门狗会把 CPU 和带宽全耗在这个台上，用户看到的是永远转圈。
     * 到上限就停下来，等用户自己换台。
     */
    private static final int STARTUP_GIVE_UP_ROUNDS = 4;

    /** 当前这轮起播已经连续失败了几次。 */
    private int startupFailures = 0;

    /**
     * 静默卡死检测：状态是 READY、也「在播」，但画面其实不再更新。
     *
     * <p>现有两个看门狗都盖不住这种形态——起播看门狗只管「还没出过画面」，
     * 卡顿看门狗只在 STATE_BUFFERING 下启动。一旦停在 READY 而画面不动，
     * 两个看门狗都不触发，只能靠用户手动换台。
     *
     * <p>判定逻辑见 {@link StallDetector}，同时看两个口径：
     * <ul>
     *   <li><b>位置</b>：只有「严格向前」才算进展，因为直播位置在分片滑出
     *       窗口时会整体后退（实测每 5 秒退 5000ms）；</li>
     *   <li><b>帧数</b>：位置照走但画面不动也能抓到。实测确实出现过
     *       {@code pos 一路前进、fps 却归零 3.5 秒} 的形态，只看位置必然漏检。</li>
     * </ul>
     */
    private final StallDetector stallDetector = new StallDetector();
    /** 静默卡死的检测间隔。 */
    private static final long SILENT_STALL_TICK_MS = 1_000L;
    /** 连续静默卡死多少轮就放弃这个频道，避免无限重启。 */
    private static final int SILENT_STALL_GIVE_UP = 5;
    /** 本次播放已经因为静默卡死重启过几次。 */
    private int silentStallRounds = 0;

    private final Runnable silentStallTick = new Runnable() {
        @Override
        public void run() {
            ExoPlayer p = player;
            if (p != null) {
                boolean eligible = hasPlayed
                        && p.isPlaying()
                        && p.getPlaybackState() == Player.STATE_READY
                        && p.getPlaybackSuppressionReason()
                        == Player.PLAYBACK_SUPPRESSION_REASON_NONE
                        && p.getPlayerError() == null;
                long now = System.currentTimeMillis();
                // 帧数取已渲染输出数：位置照走而画面停住的那种停帧，
                // 只有这个计数能反映出来。
                long frames = -1L;
                androidx.media3.exoplayer.DecoderCounters vc = p.getVideoDecoderCounters();
                if (vc != null) {
                    vc.ensureUpdated();
                    frames = vc.renderedOutputBufferCount;
                }
                if (stallDetector.sample(eligible, p.getCurrentPosition(), frames, now)) {
                    onSilentStall(p);
                }
            }
            ui.postDelayed(this, SILENT_STALL_TICK_MS);
        }
    };

    /**
     * 状态 READY、位置也在走，但画面长时间不出新帧：重建这一路播放管线。
     *
     * <p><b>绝不能用 {@code seekToDefaultPosition()} 来恢复。</b>
     * 直播流的默认位置是<b>窗口起点</b>，实测
     * {@code old=16557 new=0 buffered=0}——等于往回跳 16.5 秒，
     * 用户当场看到刚看过的内容重播一遍（正是「重复播放」这个症状本身），
     * 而且缓冲被清空后只能从 467ms 重新攒，恢复反而更慢。
     *
     * <p>更麻烦的是 {@link LiveVideoRenderer} 有意不在
     * {@link androidx.media3.exoplayer.video.MediaCodecVideoRenderer#onPositionReset}
     * 里重建 PTS 高水位线（否则任何回跳都会变成整段重播）。回跳后每一帧都低于
     * 基准，全被判成重复帧丢掉，画面要冻到 60 秒兜底才恢复——雪上加霜。
     *
     * <p>所以恢复动作只有一种：重开同一路。它会把解码器和新时间轴一起重建，
     * 并且落在直播边缘附近（{@code TARGET_OFFSET_MS}），不会重播任何内容。
     */
    private void onSilentStall(ExoPlayer p) {
        // 用"正在播放的频道"，不是列表焦点行：用户可能正浏览别的分组
        Channel c = playingChannel();
        if (c == null) {
            return;
        }
        android.util.Log.w("IPTVSTALL", "silent-stall ch=" + c.name
                + " pos=" + p.getCurrentPosition()
                + " posStalledMs=" + stallDetector.lastTriggerPosMs()
                + " frameStalledMs=" + stallDetector.lastTriggerFrameMs()
                + " buffered=" + p.getTotalBufferedDuration()
                + " round=" + (silentStallRounds + 1));
        stallDetector.reset();
        silentStallRounds++;
        if (silentStallRounds > SILENT_STALL_GIVE_UP) {
            // 这个台的线路怎么重开都卡，别再耗着，把状态清干净等用户换台
            android.util.Log.w("IPTVSTALL", "silent-stall give up ch=" + c.name);
            silentStallRounds = 0;
            loading.setVisibility(View.GONE);
            showHint("该频道播放异常，请换个台");
            return;
        }
        if (silentStallRounds >= 2) {
            // 重开同一路救不回来，多半是这条线路（或这个码率）喂不动。
            // 先降一档清晰度；降不下来才换同档的另一条线路。
            if (lowerQualityInstead(c, "silent-stall")) {
                return;
            }
            repickSource();
            return;
        }
        startPlayback(c);
    }

    /**
     * 起播看门狗。
     *
     * <p>探测只能测到分片开头的突发速率，与真实持续速率能差四五倍，
     * 于是经常出现"探测说能播、实际起不来"——播放器安静地停在
     * STATE_BUFFERING，缓冲量迟迟不到起播门槛，用户对着黑屏干等。
     * 原来没有任何机制能从这个状态里出来。
     *
     * <p>这里给每次起播设一个上限：超时就把这条线路标记为喂不饱，
     * 静默换下一条，而不是让用户无限等。
     */
    private final Runnable startupWatchdog = new Runnable() {
        @Override
        public void run() {
            if (player == null) {
                return;
            }
            int state = player.getPlaybackState();
            if (state == Player.STATE_READY) {
                return;
            }
            // 起播期间不计缓冲增长，只看"有没有出画面"
            if (state == Player.STATE_ENDED) {
                return;
            }
            long waited = System.currentTimeMillis() - startupAt;
            if (waited < STARTUP_TIMEOUT_MS) {
                ui.postDelayed(this, STARTUP_POLL_MS);
                return;
            }
            Channel c = playingChannel();
            if (c == null) {
                return;
            }
            Channel.Source s = c.source(sourceIndex);
            android.util.Log.w("IPTVSTALL", "startup-timeout ch=" + c.name
                    + " idx=" + sourceIndex + " waited=" + waited
                    + " buffered=" + player.getTotalBufferedDuration());
            // 这条线路起不来，记成喂不饱，下次不再优先选它
            s.starved = Boolean.TRUE;
            // 规则 6：落盘成黑名单，下次加载节目单直接剔除，
            // 换台时不再反复踩这条死源
            DeadSources.markDead(MainActivity.this, s.url);
            SourceMemory.remove(MainActivity.this, c.name);
            startupFailures++;
            if (startupFailures >= STARTUP_GIVE_UP_ROUNDS) {
                // 这个台的线路全都不行，别再耗下去。
                //
                // 但在放弃之前先看看是不是<b>清晰度上限把好线路挡掉了</b>。
                // 上限一旦落到某个低档（或历史上被写坏成 0），超出它的线路
                // 就全部不参与选源；若恰好那档线路都喂不动，这个台就会
                // 永远起不来。上限还会写进 quality_cap.xml 保留 3 小时，
                // 于是问题会跨越多次启动一直存在
                // （真机实测 build/final_grace.log 14:59:15：cap=0 之后三路全超时）。
                //
                // 所以放弃判定必须能让上限失效：清掉它再重来一轮，
                // 让播放器重新在所有线路上找，而不是被一个旧结论永久困住。
                // 这层兜底与 nextLower() 无关——那里已经不退到 0 了，
                // 但历史落盘的数据、以及未来任何新的降档逻辑出错，都要靠它自愈。
                if (QualityCap.capRank(MainActivity.this, c.name) != Integer.MAX_VALUE) {
                    android.util.Log.w("IPTVQUAL", "give-up releases cap ch=" + c.name
                            + " cap=" + QualityCap.capRank(MainActivity.this, c.name));
                    QualityCap.clear(MainActivity.this, c.name);
                    // 连带把"喂不饱"标记也清掉：那些标记很可能是在低档上
                    // 被反复超时的线路留下的，放开上限后要重新给它们机会
                    for (Channel.Source src : c.sources) {
                        src.starved = null;
                    }
                    startupFailures = 0;
                    retryRound = 0;
                    // 放开上限后重新挑，而不是继续降——降档正是刚才把
                    // 可播线路排除掉的原因，再降只会更没得选
                    repickSource();
                    return;
                }
                android.util.Log.w("IPTVSTALL", "give up ch=" + c.name
                        + " after " + startupFailures + " tries");
                startupFailures = 0;
                loading.setVisibility(View.GONE);
                ui.removeCallbacks(startupWatchdog);
                showHint("该频道暂时无法播放，请换个台");
                return;
            }
            retryRound = 0;
            // 起播超时同样是"喂不动"的证据：7 秒还没攒够能出画面的数据，
            // 对 1080 这种 1 MB/s 的流很典型。先降一档，别急着换同档备胎。
            //
            // 但用户刚手动选了这条清晰度时，先别急着否定他的选择：
            // 起播慢七八秒在高码率源上很常见，第一次超时重开同一条线路再给
            // 一个周期，第二次还出不来才降档。否则"点了 1080 → 7 秒后自动
            // 变 720"，用户只会觉得这个按钮没用。
            if (inManualGrace(c.name) && manualGraceStrikes < MANUAL_GRACE_STRIKES) {
                manualGraceStrikes++;
                android.util.Log.w("IPTVSTALL", "startup-timeout retry-same ch=" + c.name
                        + " strike=" + manualGraceStrikes + "/" + MANUAL_GRACE_STRIKES);
                startPlayback(c);
                return;
            }
            if (lowerQualityInstead(c, "startup-timeout")) {
                return;
            }
            repickSource();
        }
    };

    /**
     * 记录一次"看着看着又卡了"，窗口内次数到阈值就降一档。
     *
     * <p>只统计<b>已经出过画面之后</b>的缓冲：起播时攒缓冲是正常流程，
     * 把它算进去会导致一进台就误判。
     *
     * <p>降不了档（已降过 / 冷却中 / 已是最低档）时只留下日志，
     * 不打断播放——反复缓冲虽然难受，也好过把能看的画面打断。
     */
    private void noteRebuffer() {
        Channel c = playingChannel();
        if (c == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (rebufferWindowAt == 0 || now - rebufferWindowAt > REBUFFER_WINDOW_MS) {
            // 换了一个新窗口：上一轮的计数不算数了
            rebufferWindowAt = now;
            rebufferCount = 0;
        }
        rebufferCount++;
        if (rebufferCount < REBUFFER_LIMIT) {
            return;
        }
        rebufferCount = 0;
        rebufferWindowAt = now;
        android.util.Log.w("IPTVSTALL", "rebuffer-heavy ch=" + c.name
                + " times=" + REBUFFER_LIMIT
                + " in=" + (REBUFFER_WINDOW_MS / 1000) + "s"
                + " qual=" + c.source(sourceIndex).label());
        if (lowerQualityInstead(c, "rebuffer-heavy")) {
            return;
        }
        // 降不下来就别再反复评估了，等下一个窗口
    }

    /** 当前这轮起播的起始时刻。 */
    private long startupAt = 0L;

    private void armStartupWatchdog() {
        ui.removeCallbacks(startupWatchdog);
        startupAt = System.currentTimeMillis();
        ui.postDelayed(startupWatchdog, STARTUP_TIMEOUT_MS);
    }

    private void disarmStartupWatchdog() {
        ui.removeCallbacks(startupWatchdog);
        // 出画面了就说明这个台是好的，失败计数归零
        startupFailures = 0;
        // 手动选中那条线路已经出画面，观察期内的重试额度也用不上了，
        // 下次手动选台重新从 0 起算
        manualGraceStrikes = 0;
    }

    /**
     * 跟踪媒体分片序号是否连续。
     *
     * <p>带宽喂不饱时播放器只能跳过追不上的分片，分片序号会出现断层；
     * 这是"用户一定能看到画面跳"的真凭实据。累计两次跳变就判定这条线路
     * 喂不饱，标记后换一条：探测测不准真实持续速率，只能靠播放来判。
     */
    private void noteChunk(String uri) {
        if (uri == null || !uri.contains(".ts") && !uri.contains(".aac") && !uri.contains(".mp4")) {
            // 播放列表、密钥这类请求不参与序号比较
            return;
        }
        int slash = uri.lastIndexOf('/');
        int q = uri.indexOf('?', slash + 1);
        String name = q > slash ? uri.substring(slash + 1, q) : uri.substring(slash + 1);
        int end = -1;
        for (int i = name.length() - 1; i >= 0; i--) {
            if (Character.isDigit(name.charAt(i))) {
                end = i;
                break;
            }
        }
        if (end < 0) {
            return;
        }
        int start = end;
        while (start > 0 && Character.isDigit(name.charAt(start - 1))) {
            start--;
        }
        int seq;
        try {
            seq = Integer.parseInt(name.substring(start, end + 1));
        } catch (NumberFormatException e) {
            return;
        }
        // 前缀（不含序号的部分）变了说明换了一套命名，序号不可比
        String prefix = name.substring(0, start);
        if (!prefix.equals(lastChunkPrefix)) {
            lastChunkPrefix = prefix;
            lastChunkSeq = seq;
            chunkSkips = 0;
            return;
        }
        int delta = seq - lastChunkSeq;
        if (delta >= 2 && delta <= 20) {
            // 只有在连续播放中跳号才说明"追不上、丢分片"。
            // 重缓冲或 seek 之后本来就会跳过若干分片，那是正常的，
            // 把它算进来会误判好源，进而把整份节目单淘汰光
            boolean stablePlaying = player != null
                    && player.getPlaybackState() == Player.STATE_READY
                    && player.getTotalBufferedDuration() > 2_000;
            if (stablePlaying) {
                chunkSkips++;
                android.util.Log.w("IPTVLOAD", "chunk-jump " + lastChunkSeq + " -> " + seq
                        + " skips=" + chunkSkips);
                if (chunkSkips >= STARVED_SKIP_LIMIT) {
                    Channel ch = playingChannel();
                    if (ch == null || ch.sources.isEmpty()) {
                        return;
                    }
                    Channel.Source s = ch.source(sourceIndex);
                    s.starved = Boolean.TRUE;
                    android.util.Log.w("IPTVLOAD", "starved url=" + s.url);
                    DeadSources.markDead(MainActivity.this, s.url);
                    chunkSkips = 0;
                    lastChunkSeq = seq;
                    // 这条线路喂不动：先降一档清晰度再重挑。
                    // 分片跳号是带宽不足的铁证，而同机房同码率的备胎往往一样卡，
                    // 单纯换线解决不了根本问题。
                    if (lowerQualityInstead(ch, "chunk-jump")) {
                        return;
                    }
                    repickSource();
                    return;
                }
            }
        } else if (delta < 0 || delta > 20) {
            chunkSkips = 0;
        }
        lastChunkSeq = seq;
    }


    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            clock.setText(new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date()));
            if (DEBUG_PLAYBACK && player != null) {
                dumpPlayerState();
            }
            ui.postDelayed(this, DEBUG_PLAYBACK ? 2000 : 20000);
        }
    };

    /** 临时诊断：确认设备有没有 MP2 解码器，并打印当前播放地址。 */
    private void dumpAudioSupport() {
        try {
            for (String mime : new String[]{"audio/mpeg-L2", "audio/mpeg", "audio/mp4a-latm"}) {
                android.media.MediaFormat f = new android.media.MediaFormat();
                f.setString(android.media.MediaFormat.KEY_MIME, mime);
                f.setInteger(android.media.MediaFormat.KEY_SAMPLE_RATE, 48000);
                f.setInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT, 2);
                String name = null;
                try {
                    name = new android.media.MediaCodecList(
                            android.media.MediaCodecList.REGULAR_CODECS).findDecoderForFormat(f);
                } catch (Throwable ignored) {
                }
                android.util.Log.i("IPTVAUDIO", mime + " -> " + (name == null ? "NO DECODER" : name));
            }
            // MP2 与 MP3 同属 MPEG-1 音频，试试 mp3 解码器能否直接吃 L2
            for (String mime : new String[]{"audio/mpeg-L2", "audio/mpeg"}) {
                try {
                    android.media.MediaFormat f = new android.media.MediaFormat();
                    f.setString(android.media.MediaFormat.KEY_MIME, mime);
                    f.setInteger(android.media.MediaFormat.KEY_SAMPLE_RATE, 48000);
                    f.setInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT, 2);
                    android.media.MediaCodec codec =
                            android.media.MediaCodec.createByCodecName("c2.android.mp3.decoder");
                    codec.configure(f, null, null, 0);
                    android.util.Log.i("IPTVAUDIO", "mp3decoder accepts " + mime + " = YES");
                    codec.release();
                } catch (Throwable t) {
                    android.util.Log.i("IPTVAUDIO", "mp3decoder accepts " + mime + " = NO (" + t + ")");
                }
            }
        } catch (Throwable t) {
            android.util.Log.i("IPTVAUDIO", "dump failed: " + t);
        }
    }

    /** 临时诊断：把播放器内部状态打到 logcat，用来分辨卡在缓冲还是卡在渲染。 */
    private void dumpPlayerState() {
        try {
            StringBuilder audio = new StringBuilder();
            for (androidx.media3.common.Tracks.Group g : player.getCurrentTracks().getGroups()) {
                if (g.getType() == androidx.media3.common.C.TRACK_TYPE_AUDIO) {
                    audio.append(g.getMediaTrackGroup().getFormat(0)).append(" sel=")
                            .append(g.isSelected()).append(" sup=").append(g.isSupported());
                }
            }
            // 帧计数是判断"卡在管线"还是"卡在显示"的决定性依据：
            // 本机内容实测为 576i25（每个 picture 都是一场，见 field_check.py），
            // 所以 rendered 每秒约 50（50 场/秒）。dropped/skipped 才是真正丢掉的画面。
            // 三者都正常却仍看着不顺，问题就在显示侧：
            // 50 场/秒 的隔行内容喂给 60Hz 面板，需要 3:2 pulldown 重新排帧，
            // 这不是网络或解码能修的。
            String frames = "-";
            androidx.media3.exoplayer.DecoderCounters vc = player.getVideoDecoderCounters();
            if (vc != null) {
                vc.ensureUpdated();
                long now = System.currentTimeMillis();
                long dt = lastFrameAt == 0 ? 0 : Math.max(1, now - lastFrameAt);
                int df = lastFrameCount < 0 ? 0 : vc.renderedOutputBufferCount - lastFrameCount;
                double fps = dt > 0 ? df * 1000.0 / dt : 0;
                // 帧提前量：media3 记的是 getEarlyUs()，正值表示这一帧
                // 比排期时刻提前了多少送显。稳定在几十毫秒是正常的提前量；
                // 真正的问题形态是它变成大负值（帧总是来不及），
                // 或送显节奏被打断（帧率掉 0），平均值看不出后者。
                double earlyMs = vc.videoFrameProcessingOffsetCount > 0
                        ? vc.totalVideoFrameProcessingOffsetUs
                        / 1000.0 / vc.videoFrameProcessingOffsetCount : 0;
                // 音频时钟同步对照：视频排期跟着音频走，
                // 若音频帧数也在同一时刻停走，说明卡的是主时钟（音频解码/渲染），
                // 只有视频停而音频照走，才是视频管线自己的问题。
                String au = "-";
                androidx.media3.exoplayer.DecoderCounters ac = player.getAudioDecoderCounters();
                if (ac != null) {
                    ac.ensureUpdated();
                    int da = lastAudioCount < 0 ? 0
                            : ac.renderedOutputBufferCount - lastAudioCount;
                    double afps = dt > 0 ? da * 1000.0 / dt : 0;
                    au = "aRendered=" + ac.renderedOutputBufferCount
                            + " aFps=" + String.format(Locale.US, "%.1f", afps)
                            + " aDropped=" + ac.droppedBufferCount;
                    lastAudioCount = ac.renderedOutputBufferCount;
                }
                frames = "rendered=" + vc.renderedOutputBufferCount
                        + " fps=" + String.format(Locale.US, "%.1f", fps)
                        + " early=" + String.format(Locale.US, "%.1f", earlyMs) + "ms"
                        + " dropped=" + vc.droppedBufferCount
                        + " skipped=" + vc.skippedOutputBufferCount
                        + " " + au;
                lastFrameAt = now;
                lastFrameCount = vc.renderedOutputBufferCount;
            }
            android.util.Log.i("IPTVPLAY", "state=" + player.getPlaybackState()
                    + " supp=" + player.getPlaybackSuppressionReason()
                    + " err=" + (player.getPlayerError() == null ? "-" : player.getPlayerError().errorCode)
                    + " buffered=" + player.getTotalBufferedDuration()
                    + " pos=" + player.getCurrentPosition()
                    + " speed=" + player.getPlaybackParameters().speed
                    + " vol=" + player.getVolume()
                    + " frames[" + frames + "]"
                    + " qual[" + qualityState() + "]"
                    + " url=" + (player.getCurrentMediaItem() == null ? "-"
                            : player.getCurrentMediaItem().localConfiguration.uri)
                    + " audio[" + audio + "]");
        } catch (Throwable ignored) {
        }
    }

    /** 上一次采样时的已渲染帧数与时刻，用来算实时帧率。 */
    private long lastFrameAt = 0L;
    private int lastFrameCount = -1;
    private int lastAudioCount = -1;

    /**
     * 当前清晰度状态，供日志判断"是否真的优先 1080、是否真的降档锁住"。
     *
     * <p>三条信息缺一不可：
     * <ul>
     *   <li>{@code cap}：这个频道的清晰度上限，{@code max} 表示没降过；</li>
     *   <li>{@code src}：当前这条线路节目单上标的档位（实测覆盖后的值）；</li>
     *   <li>{@code lowered}：本次会话是否已经降过档，{@code 1} 之后不会再降。</li>
     * </ul>
     * 降档后若 {@code src} 又变回 1080，就说明"不许弹回"的链路有漏洞。
     */
    private String qualityState() {
        Channel c = playingChannel();
        if (c == null) {
            return "-";
        }
        int cap = QualityCap.capRank(this, c.name);
        String capText = cap == Integer.MAX_VALUE ? "max" : String.valueOf(cap);
        String src = c.source(sourceIndex).label();
        return "src=" + src + " cap=" + capText
                + " lowered=" + (qualityLoweredThisSession ? 1 : 0);
    }

    @Override
    protected void onCreate(@Nullable Bundle bundle) {
        super.onCreate(bundle);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        UiScale.init(this);
        setContentView(R.layout.activity_main);

        playerView = findViewById(R.id.player);
        loading = findViewById(R.id.loading);
        drawer = findViewById(R.id.drawer);
        topBar = findViewById(R.id.topBar);
        infoLogo = findViewById(R.id.infoLogo);
        qualityPanel = findViewById(R.id.qualityPanel);
        qualityRow = findViewById(R.id.qualityRow);
        qualityScroll = findViewById(R.id.qualityScroll);
        nowName = findViewById(R.id.nowName);
        nowMeta = findViewById(R.id.nowMeta);
        clock = findViewById(R.id.clock);
        hint = findViewById(R.id.hint);
        drawerSub = findViewById(R.id.drawerSub);
        emptyTip = findViewById(R.id.emptyTip);
        list = findViewById(R.id.list);
        groupList = findViewById(R.id.groupList);

        // 左栏分组导航
        groupList.setLayoutManager(new LinearLayoutManager(this));
        groupAdapter = new GroupAdapter(groupNames, this::onGroupPicked);
        groupList.setAdapter(groupAdapter);

        // 右栏频道列表：用居中的布局管理器。
        //
        // 默认 LinearLayoutManager 只在"行被裁掉"时才滚，且滚到"刚好露出来"，
        // 结果焦点走到视口底部几行后就卡死（下一行只露出十几像素，
        // 焦点搜索找得到它却跳不过去；实测连续 DOWN 到第 5 行后 bounds 完全不变）。
        // 居中滚动同时解决"焦点卡死"和"看不到下一个频道"两件事。
        listManager = new CenteringLayoutManager(this);
        list.setLayoutManager(listManager);
        adapter = new ChannelAdapter(channels, this::select);
        adapter.setOnLongPress(this::showChannelMenu);
        list.setAdapter(adapter);
        // 关掉条目动画：高亮切换的动画会触发 relayout，把滚动位置顶回原处
        list.setItemAnimator(null);
        // 让列表首尾留白，第一行不被标题压住，最后一行不被屏幕边缘切掉
        list.setClipToPadding(true);
        list.setPadding(UiScale.pxW(12), UiScale.pxH(26), UiScale.pxW(12), UiScale.pxH(24));
        // 原来这里还挂了一套"焦点预览台标"，已删除（右下角曾同时出现两个 logo）。
        // 现在只保留焦点行居中这一件事。
        setupFocusCentering();

        findViewById(R.id.btnSettings).setOnClickListener(v -> showSettings());
        findViewById(R.id.btnClose).setOnClickListener(v -> closeMenu());
        findViewById(R.id.btnDrawerSettings).setOnClickListener(v -> showSettings());
        applyScaleToViews();
        setupGestures();
        // 调试包才有：屏幕上叠一组方向键/OK/返回，走和实体遥控器完全相同的
        // dispatchKeyEvent 路径。release 包在 attach 内部直接返回，不留任何视图。
        VirtualRemote.attach(this, (android.view.ViewGroup) findViewById(R.id.root));

        if (PREVIEW_ONLY) {
            // 纯预览：播放器整个移除，画面位置只留静态图
            playerView.setVisibility(View.GONE);
            findViewById(R.id.loading).setVisibility(View.GONE);
        } else {
            findViewById(R.id.previewImage).setVisibility(View.GONE);
            player = PlaybackTuning.create(this);
            player.setHandleAudioBecomingNoisy(true);
            player.setPlaybackParameters(new PlaybackParameters(1f));
            playerView.setPlayer(player);
            dumpAudioSupport();
            // 记录每个分片的实际下载速率，缓冲环下方展示
            player.addAnalyticsListener(new AnalyticsListener() {
                @Override
                public void onLoadCompleted(EventTime eventTime, LoadEventInfo info, MediaLoadData data) {
                    speed.record(info.bytesLoaded, info.loadDurationMs);
                    noteChunk(info.uri == null ? null : info.uri.toString());
                    android.util.Log.i("IPTVLOAD", "bytes=" + info.bytesLoaded
                            + " ms=" + info.loadDurationMs
                            + " speed=" + LoadSpeedMeter.format(speed.bytesPerSecond())
                            + " uri=" + info.uri);
                }

                @Override
                public void onDroppedVideoFrames(EventTime eventTime, int droppedFrames,
                                                 long elapsedMs) {
                    // 真的掉帧才会让人看出"卡了一下"，重叠帧剔除不算在内。
                    // 这个回调由渲染器线程投递，退出播放页时 player 可能已被置空，
                    // 不判空会在 onDestroy 后抛 NPE 直接把进程打死（实测崩溃点）。
                    ExoPlayer p = player;
                    if (p == null) {
                        return;
                    }
                    android.util.Log.i("IPTVVIDEO", "dropped=" + droppedFrames
                            + " elapsedMs=" + elapsedMs
                            + " buffered=" + p.getTotalBufferedDuration());
                }
            });
            player.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int state) {
                    if (state == Player.STATE_BUFFERING) {
                        loading.setVisibility(View.VISIBLE);
                        startBufferProgress();
                        // 起播时的缓冲是正常的，只有播起来以后又卡才算卡顿
                        if (hasPlayed) {
                            rebufferStart = System.currentTimeMillis();
                            lastBuffered = -1;
                            lastBufferedAt = System.currentTimeMillis();
                            ui.removeCallbacks(stallWatchdog);
                            ui.postDelayed(stallWatchdog, STALL_POLL_MS);
                            noteRebuffer();
                        }
                    } else if (state == Player.STATE_READY) {
                        stopBufferProgress();
                        loading.setVisibility(View.GONE);
                        hasPlayed = true;
                        retryRound = 0;
                        ui.removeCallbacks(stallWatchdog);
                        disarmStartupWatchdog();
                        rebufferStart = 0;
                        // 这一路真的播起来了，记下来供下次换台秒起播
                        Channel c = playingChannel();
                        if (c != null) {
                            SourceMemory.put(MainActivity.this, c.name, c.url(sourceIndex));
                            // 能播起来就说明它不是死源：撤销之前可能留下的黑名单，
                            // 免得一次误判（比如临时断网）把它永久剔掉
                            DeadSources.revive(MainActivity.this, c.url(sourceIndex));
                        }
                    }
                }

                @Override
                public void onPositionDiscontinuity(Player.PositionInfo oldPosition,
                                                    Player.PositionInfo newPosition,
                                                    int reason) {
                    if (DEBUG_PLAYBACK) {
                        long delta = newPosition.positionMs - oldPosition.positionMs;
                        android.util.Log.i("IPTVSEEK", "reason=" + reason
                                + " delta=" + delta
                                + " old=" + oldPosition.positionMs
                                + " new=" + newPosition.positionMs
                                + " buffered=" + player.getTotalBufferedDuration());
                    }
                }

                @Override
                public void onPlayerError(PlaybackException error) {
                    onSourceFailed(error);
                }

                @Override
                public void onVideoSizeChanged(VideoSize size) {
                    rememberRealQuality(size.height);
                }
            });
        }

        store = new ChannelStore(this);
        DailyRefresh.schedule(this);

        // 便于调试：am start -e playlist <url> 可直接切换数据源
        String preset = getIntent().getStringExtra("playlist");
        if (!TextUtils.isEmpty(preset)) {
            store.setCustomSource(preset.trim());
        }

        loadChannels(getIntent().getIntExtra("channel", -1));
        ui.post(tick);
        if (!PREVIEW_ONLY) {
            ui.post(silentStallTick);
            // 规则 6：每次打开都在后台刷新，顺带剔除已证伪的坏线路
            refreshInBackground();
        }
    }

    /**
     * 按屏幕物理像素统一放大界面元素。
     * 布局里的 dp/sp 只是 1080p 基准占位，这里才是真实尺寸来源：
     * 横向走屏幕宽度比例，纵向走屏幕高度比例，因此 240dpi 和 320dpi
     * 上报的两台 1080p 电视会得到完全一致的观感。
     */
    private void applyScaleToViews() {
        // 抽屉整体宽度按屏幕宽度百分比，两栏再按权重分
        View drawerV = drawer;
        ViewGroup.LayoutParams dlp = drawerV.getLayoutParams();
        dlp.width = UiScale.pctW(52);
        dlp.height = ViewGroup.LayoutParams.MATCH_PARENT;
        drawerV.setLayoutParams(dlp);

        View groupPanel = findViewById(R.id.groupPanel);
        View channelPanel = findViewById(R.id.channelPanel);
        UiScale.padding(groupPanel, 12, 24, 12, 20);
        if (groupPanel.getLayoutParams() instanceof LinearLayout.LayoutParams) {
            ((LinearLayout.LayoutParams) groupPanel.getLayoutParams()).weight = 0.30f;
            groupPanel.setLayoutParams(groupPanel.getLayoutParams());
        }
        if (channelPanel.getLayoutParams() instanceof LinearLayout.LayoutParams) {
            ((LinearLayout.LayoutParams) channelPanel.getLayoutParams()).weight = 0.70f;
            channelPanel.setLayoutParams(channelPanel.getLayoutParams());
        }

        // 顶栏
        UiScale.padding(topBar, 30, 16, 24, 18);
        UiScale.text(nowName, 32);
        UiScale.text(nowMeta, 21);
        UiScale.text(clock, 21);
        UiScale.text((TextView) findViewById(R.id.btnSettings), 21);
        UiScale.padding(findViewById(R.id.btnSettings), 22, 9, 22, 9);

        // 抽屉左栏：分组列表 + 概况 + 设置/收起
        UiScale.padding(groupPanel, 10, 20, 10, 16);
        UiScale.text((TextView) findViewById(R.id.groupLabel), 19);
        UiScale.text(drawerSub, 17);
        UiScale.margin(drawerSub, 12, 6, 8, 12);
        UiScale.text((TextView) findViewById(R.id.btnClose), 21);
        UiScale.text((TextView) findViewById(R.id.btnDrawerSettings), 21);
        UiScale.padding(findViewById(R.id.btnClose), 10, 10, 10, 10);
        UiScale.padding(findViewById(R.id.btnDrawerSettings), 10, 10, 10, 10);
        UiScale.margin(findViewById(R.id.btnClose), 8, 0, 0, 0);
        UiScale.text(emptyTip, 21);

        // 两个按钮留在左栏底部，只有最后一个分组按 ↓ 才轮到它们
        View btnSettings = findViewById(R.id.btnDrawerSettings);
        View btnClose = findViewById(R.id.btnClose);
        btnSettings.setNextFocusUpId(R.id.groupList);
        btnSettings.setNextFocusRightId(R.id.btnClose);
        btnClose.setNextFocusUpId(R.id.groupList);
        btnClose.setNextFocusLeftId(R.id.btnDrawerSettings);

        // 底部提示
        UiScale.text(hint, 23);
        UiScale.padding(hint, 26, 12, 26, 12);
        UiScale.margin(hint, 0, 0, 0, 18);

        // 左上角台标：和台名同排，高一点但不能盖过顶栏。
        // 台标本身已归一化成 2:1，按 2:1 给框才能完整不变形地放下。
        int logoH = UiScale.pxH(38);
        int logoW = logoH * 2;
        ViewGroup.LayoutParams logoLp = infoLogo.getLayoutParams();
        logoLp.width = logoW;
        logoLp.height = logoH;
        infoLogo.setLayoutParams(logoLp);
        UiScale.margin(infoLogo, 0, 0, 14, 0);

        // 右侧清晰度面板：宽度按规则 4 收窄到 RIGHT_PANEL_PCT，
        // 高度铺满整屏（用户要求：面板背景上下对齐屏幕上下）。
        // 面板本身 match_parent，里面的档位靠 LinearLayout 的
        // gravity=center_vertical 垂直居中——背景撑满、内容居中，
        // 而不是让背景跟着内容高度走。
        ViewGroup.LayoutParams qlp = qualityPanel.getLayoutParams();
        qlp.width = UiScale.pctW(RIGHT_PANEL_PCT);
        qlp.height = ViewGroup.LayoutParams.MATCH_PARENT;
        qualityPanel.setLayoutParams(qlp);
        // 背景铺到屏幕右缘（paddingEnd 只让内容离边，不影响背景），
        // 内容右对齐贴着"右实左虚"渐变的不透明一侧
        UiScale.padding(qualityPanel, 24, 26, 14, 26);
        qualityPanel.setGravity(android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL);
        TextView qLabel = findViewById(R.id.qualityLabel);
        if (qLabel != null) {
            UiScale.text(qLabel, 13);
            UiScale.margin(qLabel, 0, 0, 4, 10);
        }

        // 列表上下渐隐的高度随屏幕缩放，避免大屏上显得过窄
        for (int id : new int[]{R.id.listFadeTop, R.id.listFadeBottom}) {
            View fade = findViewById(id);
            if (fade != null) {
                ViewGroup.LayoutParams flp = fade.getLayoutParams();
                flp.height = UiScale.pxH(30);
                fade.setLayoutParams(flp);
            }
        }

        // 预览台标那套已删除：屏幕上只保留左上角一个正在播的台标。
    }

    /* ---------------- 抽屉内焦点居中 ---------------- */

    /**
     * 焦点在频道列表上被系统搬走时，把那一行重新滚到垂直正中。
     *
     * <p>主动按上下键走 {@link #moveChannelFocusTo(int)}，那里自带平滑滚动；
     * 这里只兜住"焦点不是我搬的"那种情况（比如落焦时的自动定位），
     * 两边都滚会互相打架，所以用 {@code smoothScrollPending} 让位。
     *
     * <p>原来这个方法还兼管右下角的"焦点预览台标"。那套已删除——
     * 用户反馈右下角同时出现两个 logo，分不清哪个是正在播的。
     */
    private void setupFocusCentering() {
        // 用焦点变化广播而不是给每行设监听：行是复用的，逐行挂容易漏
        list.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        list.getViewTreeObserver().addOnGlobalFocusChangeListener((oldFocus, newFocus) -> {
            if (newFocus != null && isInside(newFocus, list)
                    && listManager != null && !smoothScrollPending) {
                int pos = listManager.focusedPosition(newFocus);
                if (pos >= 0) {
                    listManager.smoothCenterOn(pos);
                }
            }
        });
    }

    /** newFocus 是否在 container 内部。 */
    private static boolean isInside(View newFocus, View container) {
        View f = newFocus;
        while (f != null) {
            if (f == container) {
                return true;
            }
            if (!(f.getParent() instanceof View)) {
                return false;
            }
            f = (View) f.getParent();
        }
        return false;
    }

    /* ---------------- 数据装载与分组 ---------------- */

    private void loadChannels(int savedIndex) {
        int pick = savedIndex;
        try {
            List<Channel> loaded = store.load();
            ChannelStore.sort(loaded);
            master.clear();
            master.addAll(loaded);
        } catch (Exception e) {
            // 读取失败不弹提示，安静停在空列表即可
            return;
        }
        rebuildGroups();
        if (pick < 0) {
            pick = getPreferences(MODE_PRIVATE).getInt("current", 0);
            // 没有历史记录时别盲选第 0 个：节目单按响应耗时排，
            // 排第一的往往是冷门台（实测 CETV-1 三条线路都喂不动）。
            // 优先落到 CCTV-1，它是国内直播里最稳的一路。
            if (pick == 0) {
                int cctv1 = indexInMaster("CCTV-1");
                if (cctv1 >= 0) {
                    pick = cctv1;
                }
            }
        }
        if (master.isEmpty()) {
            return;
        }
        pick = Math.max(0, Math.min(pick, master.size() - 1));
        // 首次进入定位到该频道所在分组
        selectGroupOf(master.get(pick).group);
        applyGroup(currentGroup);
        sourceIndex = 0;
        retryRound = 0;
        play(indexOfName(master.get(pick).name));
    }

    /** 重建分组索引；分组顺序由 ChannelGroups 决定。 */
    private void rebuildGroups() {
        ChannelGroups g = ChannelGroups.from(master);
        groupMap.clear();
        groupMap.putAll(g.groups);
        groupNames.clear();
        groupNames.add(FAVORITES);
        groupNames.addAll(groupMap.keySet());
        if (groupAdapter != null) {
            groupAdapter.notifyDataSetChanged();
        }
    }

    /** 把当前分组切到指定分组名，找不到就留在原分组。 */
    private void selectGroupOf(String group) {
        int i = groupNames.indexOf(group);
        if (i >= 0) {
            currentGroup = i;
        }
    }

    private void onGroupPicked(int position) {
        if (position < 0 || position >= groupNames.size()) {
            return;
        }
        applyGroup(position);
        // 选完分组把焦点顺到频道列表，省一次按键。
        //
        // 这里**不能**判 isTouchMode()：用户点虚拟遥控器（或任何点击）之后
        // 窗口就在 touch mode 里，此时 requestFocus() 会被静默拒绝，
        // 焦点留在分组行上 —— 再按 OK 只会重复选中分组，进不了频道列表。
        // requestFocusRobust() 会在失败时回退 requestFocusFromTouch()。
        if (channels.isEmpty()) {
            return;
        }
        ui.post(() -> {
            View row = current >= 0 && listManager != null
                    ? listManager.findViewByPosition(current) : null;
            if (row == null) {
                listManager.centerOn(Math.max(0, current));
                ui.postDelayed(() -> {
                    View again = listManager.findViewByPosition(Math.max(0, current));
                    if (again != null) {
                        requestFocusRobust(again);
                    } else {
                        requestFocusRobust(list);
                    }
                }, 50);
                return;
            }
            requestFocusRobust(row);
        });
    }

    /** 切换分组：只换列表内容，不打断正在播放的画面。 */
    private void applyGroup(int position) {
        currentGroup = Math.max(0, Math.min(position, Math.max(0, groupNames.size() - 1)));
        String name = groupNames.get(currentGroup);
        List<Channel> src = FAVORITES.equals(name) ? favorites() : groupMap.get(name);
        channels.clear();
        if (src != null) {
            channels.addAll(src);
        }
        adapter.notifyDataSetChanged();
        // 列表选中行：正在播的台在本分组里就落在它那一行，否则落在第一行。
        //
        // 注意"列表选中行"和"正在播放"是两件事：
        // 正在播的台不在本分组时，这里仍要选一行（否则方向键没有落点），
        // 但那一行**绝不能**被标成"正在播放"——是否正在播只看名字
        // （{@link ChannelAdapter#setPlaying(String)}）。
        int found = indexOfName(playingName);
        current = channels.isEmpty() ? -1 : Math.max(0, found);
        lastFocusPos = current;
        // 高亮标记按名字刷新：不在本分组时一条都不标
        adapter.setPlaying(playingName);
        if (listManager != null) {
            // 切分组是"整屏内容换掉"：上一个分组留下的滚动过渡没意义，
            // 而且残留的 translationY 会让新列表整体偏半行
            listManager.cancelScrollAnim();
        }
        if (current >= 0) {
            scrollCurrentIntoComfort(current);
        }
        groupAdapter.setCurrent(currentGroup);
        emptyTip.setVisibility(channels.isEmpty() ? View.VISIBLE : View.GONE);
        emptyTip.setText(FAVORITES.equals(name)
                ? "还没有收藏频道\n在频道上长按即可收藏"
                : "该分组暂无频道");
        updateDrawerInfo();
    }

    private List<Channel> favorites() {
        Set<String> fav = favoritesSet();
        List<Channel> out = new ArrayList<>();
        for (Channel c : master) {
            if (fav.contains(c.name)) {
                out.add(c);
            }
        }
        return out;
    }

    private Set<String> favoritesSet() {
        return new LinkedHashSet<>(
                getPreferences(MODE_PRIVATE).getStringSet("favorites", new LinkedHashSet<>()));
    }

    private void toggleFavorite(Channel c) {
        Set<String> fav = favoritesSet();
        boolean added = fav.add(c.name);
        if (!added) {
            fav.remove(c.name);
        }
        getPreferences(MODE_PRIVATE).edit().putStringSet("favorites", fav).apply();
        showHint(added ? "已收藏「" + c.name + "」" : "已取消收藏「" + c.name + "」");
        if (FAVORITES.equals(groupNames.get(currentGroup))) {
            applyGroup(currentGroup);
        }
    }

    private int indexOfName(String name) {
        if (TextUtils.isEmpty(name)) {
            return -1;
        }
        for (int i = 0; i < channels.size(); i++) {
            if (channels.get(i).name.equals(name)) {
                return i;
            }
        }
        return -1;
    }

    /** 在 master 里按频道名找下标；加载早期 channels 还是空的，用它。 */
    private int indexInMaster(String name) {
        if (TextUtils.isEmpty(name)) {
            return -1;
        }
        for (int i = 0; i < master.size(); i++) {
            if (master.get(i).name.equals(name)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 正在播放的频道对象，<b>与"列表当前选中哪一行"无关</b>。
     *
     * <p>这是播放相关逻辑取频道的唯一正确方式。原来的写法是
     * {@code channels.get(current)}，而 {@code current} 是列表焦点行：
     * 用户切到别的分组浏览时它就指向另一个台（甚至因为夹到 0 而指向第一台），
     * 于是降档、换源、重开流全都作用到<b>没在播的那个台</b>上。
     *
     * <p>返回 {@link #playingRef}（播放器真正在用的那个实例），
     * 而不是按名字去 master 里重新找——刷新后那是新对象，{@code sourceIndex}
     * 会指到另一条线路上。返回 null 表示"确实没有正在播的频道"。
     */
    @Nullable
    private Channel playingChannel() {
        return playingRef;
    }

    /* ---------------- 触摸手势 ---------------- */

    /**
     * 触摸屏手势：点按开关列表，上下滑动换台，左右滑动切画面比例。
     * 遥控器走 onKeyDown，两条路径最终走同一套换台逻辑。
     */
    private void setupGestures() {
        gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                // 触摸屏点一下等同 OK 键：开/关整个菜单
                if (isMenuOpen()) {
                    closeMenu();
                } else {
                    openMenu();
                }
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null || e2 == null) {
                    return false;
                }
                float dx = e2.getX() - e1.getX();
                float dy = e2.getY() - e1.getY();
                if (Math.abs(dy) > Math.abs(dx) && Math.abs(dy) > 60) {
                    // 菜单打开时上下滑不换台（与规则 2 一致：只有菜单没出现才换台）
                    if (isMenuOpen()) {
                        return false;
                    }
                    // 上滑下一个频道，下滑上一个
                    stepFromPlaying(dy < 0 ? 1 : -1);
                    return true;
                }
                if (Math.abs(dx) > Math.abs(dy) && Math.abs(dx) > 80) {
                    if (isMenuOpen() && dx < 0) {
                        closeMenu();
                        return true;
                    }
                    if (isMenuOpen()) {
                        return false;
                    }
                    toggleAspect();
                    return true;
                }
                return false;
            }
        });

        View layer = findViewById(R.id.gestureLayer);
        layer.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN && !isMenuOpen()) {
                showBar();
            }
            return gestures.onTouchEvent(event);
        });
    }

    /** Z 键和左右滑动共用：自适应 → 拉伸 → 裁剪。 */
    private void toggleAspect() {
        if (PREVIEW_ONLY) {
            showHint("预览模式，画面比例不生效");
            return;
        }
        aspectMode = aspectMode == AspectRatioFrameLayout.RESIZE_MODE_FIT
                ? AspectRatioFrameLayout.RESIZE_MODE_FILL
                : (aspectMode == AspectRatioFrameLayout.RESIZE_MODE_FILL
                ? AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                : AspectRatioFrameLayout.RESIZE_MODE_FIT);
        playerView.setResizeMode(aspectMode);
        showHint(aspectMode == AspectRatioFrameLayout.RESIZE_MODE_FIT ? "画面：自适应"
                : aspectMode == AspectRatioFrameLayout.RESIZE_MODE_FILL ? "画面：拉伸" : "画面：裁剪");
    }

    /* ---------------- 播放 ---------------- */

    /**
     * 换台入口：先用记忆线路秒起播，探测退到后台做精修。
     *
     * <p>原实现是"先并发探测全部线路再起播"，{@code SourceProber} 的总预算是
     * 4.5 秒，于是每次换台都要先空等探测。但用户来回切的多半是那几个台，
     * 线路早已验证过。这里改成：
     * <ul>
     *   <li>有记忆线路 → 立刻起播，探测在后台跑，只有发现明显更好的线路才切；</li>
     *   <li>没有记忆 → 才走阻塞探测（首次访问该频道）。</li>
     * </ul>
     */
    private void play(int index) {
        if (index < 0 || index >= channels.size()) {
            return;
        }
        Channel c = channels.get(index);
        if (c.sources.isEmpty()) {
            return;
        }
        current = index;
        playingName = c.name;
        // 记下播放器真正在用的实例：后续看门狗/降档/换源都操作它，
        // 而不是按名字去列表里重新找（刷新后那是另一个对象）
        playingRef = c;
        hasPlayed = false;
        rebufferStart = 0;
        // 换台后分片序号重新开始，旧计数不能带过来
        lastChunkSeq = -1;
        lastChunkPrefix = "";
        chunkSkips = 0;
        // 上一次播放的失败计数不能带过来，否则新台第一下失败就可能直接换线
        retryRound = 0;
        startupFailures = 0;
        // 换台是全新会话：静默卡死的计时和重启次数都要清零，
        // 否则上一路的停滞会被算到新台上，一进台就被判卡死
        silentStallRounds = 0;
        stallDetector.reset();
        playToken++;
        // 换台是全新会话：降档状态跟着重置。
        // 但不能清 QualityCap —— 那是跨会话的结论，
        // 清了就会在换台回来时又去撞一次已知喂不动的 1080。
        qualityLoweredThisSession = false;
        // 反复缓冲的统计也从零开始，别把上一路的卡顿算到新台上
        rebufferCount = 0;
        rebufferWindowAt = 0L;
        // 观察期内"重开同一条线路"的额度也是每次播放独立的
        manualGraceStrikes = 0;
        // 名字和图标立刻显示，不让用户对着黑屏等探测
        nowName.setText(c.name);
        nowMeta.setText(ChannelGroups.displayName(c.group));
        renderTopLogo(c);
        // "正在播放"按名字刷新：列表里只有这个台会带上标记
        adapter.setPlaying(playingName);
        // 只有抽屉打开时才需要跟着滚动，否则会把隐藏列表滚到用户看不到的位置
        if (drawer.getVisibility() == View.VISIBLE) {
            scrollCurrentIntoComfort(index);
        }
        showBar();
        getPreferences(MODE_PRIVATE).edit().putInt("current", index).apply();

        loading.setVisibility(View.VISIBLE);
        // 换台后重新统计网速，避免显示上一条流的读数
        speed.reset();
        LiveSpeedMeter.INSTANCE.reset();
        if (PREVIEW_ONLY) {
            // 预览模式不探测网络，直接按当前线路渲染静态画面
            sourceIndex = Math.max(0, Math.min(sourceIndex, c.sources.size() - 1));
            ui.post(() -> startPlayback(c));
            return;
        }

        // ---- 快路径一：记忆线路直接起播 ----
        //
        // 记忆线路必须再过一道清晰度上限：SourceMemory 记的是"上次真正播起来的
        // 那条"，降档之前它可能正是已经被判定喂不动的 1080。若这里不检查，
        // 用户切走再切回来就会又撞一次 1080、再卡、再降——正是要避免的来回切换。
        int remembered = indexOfUrl(c, SourceMemory.get(this, c.name));
        if (remembered >= 0 && !Boolean.TRUE.equals(c.sources.get(remembered).starved)
                && c.sources.get(remembered).effectiveRank()
                        <= QualityCap.capRank(this, c.name)) {
            sourceIndex = remembered;
            final int token = playToken;
            startPlayback(c);
            // 背景探测只用来"发现更好的线路"，不阻塞起播
            refineSourceInBackground(c, token);
            return;
        }

        // ---- 快路径二：首次访问该频道，按节目单自带的 response-time 起播 ----
        //
        // 节目单里每条线路都带源站实测的响应耗时，解析时已按它从小到大排好，
        // 第 0 条通常就是最快的可用线路。原来这里要等 SourceProber 探完
        // （总预算 4.5 秒）才肯起播，这就是"每次换台都要重新加载一遍"的来源。
        // 现在直接起播，探测退到后台精修；真起不来由起播看门狗兜底。
        int quickest = quickestSource(c);
        if (quickest >= 0) {
            sourceIndex = quickest;
            final int token = playToken;
            startPlayback(c);
            refineSourceInBackground(c, token);
            return;
        }

        // ---- 慢路径：节目单没给任何线索，才阻塞探测 ----
        final int want = sourceIndex;
        final int token = playToken;
        SourceProber.pick(this, c, want, picked -> {
            // 探测期间用户可能已经换台，旧结果直接丢弃
            if (token != playToken || current != index) {
                return;
            }
            if (picked < 0) {
                // 一路都没挑出来：多半是清晰度上限把所有候选都挡掉了
                // （上限落到「只允许无标记线路」而该频道一条都没有）。
                // 这时宁可放开上限再试，也不能把用户留在永久黑屏。
                if (QualityCap.capRank(this, c.name) != Integer.MAX_VALUE) {
                    android.util.Log.w("IPTVQUAL", "cap leaves no candidate ch=" + c.name
                            + ", releasing cap");
                    QualityCap.clear(this, c.name);
                    play(index);
                    return;
                }
                loading.setVisibility(View.GONE);
                return;
            }
            sourceIndex = picked;
            startPlayback(c);
        });
    }

    /**
     * 挑选起播线路：<b>在允许的清晰度内取最高档，同档取响应最快的</b>。
     *
     * <p>原来这里只取"第一个没被标记的线路"，而解析是按 response-time 升序排的，
     * 于是永远命中那批 720×576 的小机房线路——节目单里 1080 的线路排在后面
     * 永远轮不到。实测就是这个原因让画面一直是低清。
     *
     * <p>现在先读频道当前的清晰度上限（{@link QualityCap}），在不超过上限的
     * 线路里取档位最高的一条；同档内仍按响应耗时取快的。上限就是"降档后
     * 锁住不回升"的地方：一旦 1080 被判定喂不动，上限降到 720，
     * 这里就不可能再挑 1080。
     *
     * <p>全部线路都被标记喂不饱时返回 -1，交给上层阻塞探测（它会清标记重试）。
     */
    private int quickestSource(Channel c) {
        int cap = QualityCap.capRank(this, c.name);
        int best = -1;
        int bestRank = -1;
        for (int i = 0; i < c.sources.size(); i++) {
            if (Boolean.TRUE.equals(c.sources.get(i).starved)) {
                continue;
            }
            int r = c.sources.get(i).effectiveRank();
            if (r > cap) {
                continue;
            }
            // sources 已按（清晰度降序, response-time 升序）排过，
            // 所以第一个最高档的就是同档里最快的
            if (r > bestRank) {
                bestRank = r;
                best = i;
                if (r == cap) {
                    break;
                }
            }
        }
        return best;
    }

    /**
     * 起播后的后台精修：探测所有线路，只有明显更好才静默切换。
     *
     * <p>快路径用的是节目单线索，未必是最优线路。这里在后台探一遍：
     * 还没出画面时用低门槛（换线没成本），已出画面时用高门槛
     * （避免"看着好好的突然黑一下"）。
     */
    private void refineSourceInBackground(Channel c, int token) {
        final int playing = sourceIndex;
        final String playingUrl = c.url(playing);
        final boolean struggling = !hasPlayed;
        SourceProber.pickBetter(this, c, playing, struggling, better -> {
            // 期间换台了就丢弃：按频道名比，不按列表下标
            if (token != playToken || !c.name.equals(playingName)) {
                return;
            }
            if (better < 0 || better == playing) {
                return;
            }
            // 正在播放的线路已经换了就不要用这份结果覆盖
            if (!playingUrl.equals(c.url(sourceIndex))) {
                return;
            }
            Channel ch = c;
            int fromRank = ch.source(sourceIndex).effectiveRank();
            int toRank = ch.source(better).effectiveRank();
            // 用户刚手动点过清晰度：这段时间内不做任何静默换线。
            // 换了线路播放器就要重建管线、画面会黑一下，
            // 用户刚从菜单里选完清晰度就看到黑屏，会以为"点了没用"。
            //
            // 例外：他选的那条线路还没出过画面。此时屏幕上只有缓冲圈，
            // 换线不会打断任何观看，反而可能救回起播——照旧冻住它只会黑屏等着。
            if (inManualGrace(c.name) && hasPlayed) {
                android.util.Log.i("IPTVPLAY", "refine skip-manual-grace " + playing
                        + " -> " + better + " " + c.name);
                return;
            }
            if (toRank < fromRank) {
                // 精修把画面换低了：必须和自动降档走同一份上限，
                // 否则下次精修又按速率把 1080 挑回来 —— 来回切就是这么来的。
                // 只在「还没出画面」时允许这条路（此时换线没有观看代价）。
                if (hasPlayed || fromRank <= 0) {
                    android.util.Log.i("IPTVPLAY", "refine skip-lower " + playing
                            + " -> " + better + " " + c.name
                            + " from=" + fromRank + " to=" + toRank);
                    return;
                }
                QualityCap.lockAt(this, ch.name, toRank);
                qualityLoweredThisSession = true;
                lastQualityLowerAt = System.currentTimeMillis();
                android.util.Log.w("IPTVQUAL", "lower ch=" + c.name
                        + " why=refine-never-played from=" + fromRank + " to=" + toRank);
            }
            android.util.Log.i("IPTVPLAY", "refine " + playing + " -> " + better + " " + c.name);
            sourceIndex = better;
            renderTopLogo(c);
            startPlayback(c);
        });
    }

    /** 在频道里找某条线路的下标，找不到返回 -1。 */
    private static int indexOfUrl(Channel c, String url) {
        if (url == null || url.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < c.sources.size(); i++) {
            if (url.equals(c.sources.get(i).url)) {
                return i;
            }
        }
        return -1;
    }

    /** 真正起播：此时源已确认可达。预览模式下只刷新界面。 */
    private void startPlayback(Channel c) {
        if (player == null) {
            loading.setVisibility(View.GONE);
            renderTopLogo(c);
            return;
        }
        // 换了一条新流：清掉视频渲染器的显示时间基准，
        // 否则新流 PTS 从头开始，会被判成"重复帧"整段丢掉（换台后长时间不动）
        PlaybackTuning.resetStreamState();
        // 新管线的时间轴也是全新的，静默卡死检测必须重新起算，
        // 否则换源瞬间的位置落差会被当成长时间停滞
        stallDetector.reset();
        // 换了一条新流，之前那一段的缓冲次数不能带过来；
        // 但<b>同一个地址</b>被看门狗重开时不能清零 —— 否则反复喂不动的线路
        // 永远攒不到 rebuffer-heavy 阈值。
        String url = c.url(sourceIndex);
        if (lastStartedUrl == null || !lastStartedUrl.equals(url)) {
            rebufferCount = 0;
            rebufferWindowAt = 0L;
        }
        lastStartedUrl = url;
        // 帧率采样也重新起算，否则换源那一跳会被算成一次帧率突变
        lastFrameAt = 0L;
        lastFrameCount = -1;
        lastAudioCount = -1;
        player.setMediaItem(PlaybackTuning.liveItem(c.url(sourceIndex)));
        player.prepare();
        player.setPlayWhenReady(true);
        renderTopLogo(c);
        // 每次起播都重新计时：超时还没出画面就说明这条线路喂不饱
        armStartupWatchdog();
    }

    /**
     * 左上角台标：只画一张 logo，紧跟台名左边。
     *
     * <p>用户要求把 logo 移到左上角和频道文字放在一起。原来右下角
     * 有<b>两</b>个 logo（焦点预览 + 正在播放），各有一套淡入淡出，
     * 屏幕上同时出现两个台标，分不清哪个是正在播。现在全屏只有这一个。
     *
     * <p>可见性完全跟随顶栏：顶栏由 {@link #showBar()} / {@code hideBar}
     * 统一计时收起，这里不再单独维护一套"台标淡出"的逻辑。
     */
    private void renderTopLogo(Channel c) {
        android.graphics.Bitmap bmp = ChannelAdapter.LogoCache.get(this, c.logoKey());
        if (bmp != null) {
            infoLogo.setImageBitmap(bmp);
            infoLogo.setVisibility(View.VISIBLE);
        } else {
            infoLogo.setImageDrawable(null);
            infoLogo.setVisibility(View.GONE);
        }
        renderQualityPanel(c);
    }

    /**
     * 右侧清晰度面板：每档一行，选中态高亮，点即切到该档的线路。
     *
     * <p>去重规则和原来的胶囊一致：已知分辨率按 {@code displayKey()} 去重，
     * 还没测出分辨率的线路折叠成一行"更多线路"，避免十条未知源把面板撑爆。
     */
    private void renderQualityPanel(Channel c) {
        if (qualityRow == null) {
            return;
        }
        // 先记住"重建前焦点在不在面板里"。removeAllViews() 会把焦点连带清掉，
        // 之后再问 inQualityPanel() 一定返回 false —— 那样用户在面板里选完一档，
        // 焦点就会掉回左侧分组栏，还得再按一次右键才能继续选。
        final boolean keepFocus = qualityPanel.getVisibility() == View.VISIBLE
                && inQualityPanel();
        qualityRow.removeAllViews();
        String currentLabel = c.source(sourceIndex).displayKey();

        Set<String> used = new LinkedHashSet<>();
        int firstUnknown = -1;
        int unknownCount = 0;
        for (int i = 0; i < c.sources.size(); i++) {
            Channel.Source s = c.sources.get(i);
            String key = s.displayKey();
            if (key.isEmpty()) {
                if (firstUnknown < 0) {
                    firstUnknown = i;
                }
                unknownCount++;
                continue;
            }
            if (!used.add(key)) {
                continue;
            }
            addQualityRowItem(key, i, key.equals(currentLabel));
        }
        boolean onUnknown = c.source(sourceIndex).displayKey().isEmpty();
        if (firstUnknown >= 0) {
            int target = onUnknown ? nextUnknown(c, sourceIndex) : firstUnknown;
            addQualityRowItem("更多线路" + (unknownCount > 1 ? " " + unknownCount : ""),
                    target, onUnknown);
        }
        // 面板开着时重建了视图（用户刚选了另一档），焦点会随旧视图一起没掉，
        // 不补回来的话上面板立刻"失灵"，还得再按一次右键。
        // 但焦点本来就不在面板里时（OK 键同开抽屉、用户在选台）不要抢：
        // 那种情况下重建只是刷新选中态，抢焦点会把用户从频道列表踹出来。
        if (keepFocus) {
            final View focus = currentQualityItem();
            ui.post(() -> {
                if (qualityPanel.getVisibility() == View.VISIBLE && focus != null) {
                    focus.requestFocus();
                    scrollQualityIntoView(focus);
                }
            });
        }
    }

    /** 找下一个清晰度未知的线路，用来在"更多线路"上循环。 */
    private static int nextUnknown(Channel c, int from) {
        for (int k = 1; k <= c.sources.size(); k++) {
            int i = (from + k) % c.sources.size();
            if (c.sources.get(i).displayKey().isEmpty()) {
                return i;
            }
        }
        return from;
    }

    /**
     * 清晰度面板上的一行：一个档位。
     *
     * <p>样式与左侧抽屉的分组项完全同构（{@code group_bg} / {@code group_text}）：
     * 10dp 圆角、焦点实心蓝、选中淡蓝 + 描边、未选中全透明。
     * 早先用的是 999dp 胶囊 + 半透明白底，跟左边一比就像两个 App 拼起来的。
     *
     * <p>行宽铺满面板而不是包住文字：左侧频道行也是整行高亮，
     * 竖排时整行才看得出"这是一行选项"；胶囊宽度不一反而参差。
     */
    private void addQualityRowItem(String text, int index, boolean active) {
        TextView item = new TextView(this);
        item.setText(text);
        // 规则 4 把面板收窄到 16% 后字号要跟着收一档，
        // 否则 `1080P` 五个字符会把整行撑满、没有呼吸空间
        UiScale.text(item, 22);
        item.setTextColor(getResources().getColorStateList(R.color.quality_text));
        item.setTypeface(item.getTypeface(), android.graphics.Typeface.BOLD);
        item.setGravity(android.view.Gravity.CENTER);
        item.setBackgroundResource(R.drawable.quality_row_bg);
        UiScale.padding(item, 8, 12, 8, 12);
        item.setSelected(active);
        item.setClickable(true);
        item.setFocusable(true);
        // 同 item_channel.xml：触摸模式下 requestFocus 会被拒绝，
        // 加了这个标记才能点完虚拟遥控器后用方向键继续选档位
        item.setFocusableInTouchMode(true);
        item.setOnClickListener(v -> switchQuality(index));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = UiScale.pxH(8);
        qualityRow.addView(item, lp);
    }

    /**
     * 记下这路流实际输出的分辨率，下次显示真实清晰度。
     *
     * <p>只写 {@link Channel.Source#measured}，<b>绝不改写</b>节目单声明的
     * {@link Channel.Source#quality}：档位是 {@link QualityCap} 判定的唯一依据，
     * 被实测值一覆盖就说明"1080 的标签被记成 576"，上限随之失效，
     * 降档后又会把 1080 当成低档挑回来。
     */
    private void rememberRealQuality(int height) {
        String label = QualityMemory.labelOf(height);
        if (label == null) {
            return;
        }
        Channel c = playingChannel();
        if (c == null || c.sources.isEmpty()) {
            return;
        }
        Channel.Source s = c.source(sourceIndex);
        if (label.equals(s.measured)) {
            return;
        }
        s.measured = label;
        QualityMemory.put(this, s.url, label);
        // 标签和实测不一致时留个记录：节目单可能不准，也可能这路线路被降过码
        if (!label.equals(s.quality) && !s.untagged()) {
            android.util.Log.i("IPTVQUAL", "measured ch=" + c.name
                    + " tag=" + s.quality + " real=" + label + " url=" + s.url);
        }
        renderTopLogo(c);
    }

    /* ---------------- 右侧清晰度面板 ---------------- */

    /** 菜单是否打开（抽屉或清晰度面板任一可见即算，两者本应同进同退）。 */
    private boolean isMenuOpen() {
        return drawer.getVisibility() == View.VISIBLE
                || qualityPanel.getVisibility() == View.VISIBLE;
    }

    /**
     * 把焦点送进清晰度面板的当前档位，带一帧延迟重试。
     *
     * <p>规则 3：右键 / 左菜单能跳到右侧档位，所以这是个独立的焦点搬运动作，
     * 不再伴随"打开面板"——面板此时已经是可见的。
     */
    private void focusQualityPanel() {
        // 目标必须是"已 selected 的那一档"（当前正在播的清晰度），
        // 而不是按下标猜。面板是按 displayKey() 去重后的视图，与 sourceIndex 不同构。
        ui.post(() -> {
            if (qualityPanel.getVisibility() != View.VISIBLE) {
                return;
            }
            attemptQualityFocus(0);
        });
    }

    /**
     * 把焦点落到当前档位，带重试。
     *
     * <p>面板刚 setVisibility(VISIBLE) 时行还没布局，{@code requestFocus()}
     * 会静默失败；早先只补一次 40ms 重试，实测仍会落到上一档停留处
     * （按 UP 只前进一步就卡住）。这里改成逐帧重试，
     * 直到焦点真的落在 qualityRow 的子项上为止。
     */
    private void attemptQualityFocus(int attempt) {
        if (qualityPanel.getVisibility() != View.VISIBLE) {
            return;
        }
        View target = currentQualityItem();
        if (target != null && requestFocusRobust(target)) {
            View now = getCurrentFocus();
            if (indexContaining(qualityRow, now) >= 0) {
                scrollQualityIntoView(now);
                return;
            }
        }
        if (attempt < 6) {
            ui.postDelayed(() -> attemptQualityFocus(attempt + 1), 40);
        } else {
            android.util.Log.w("IPTVFOCUS", "quality focus give-up attempt=" + attempt);
        }
    }

    /**
     * 在右侧清晰度面板内上下移动焦点。
     *
     * <p><b>为什么不用焦点导航自己走。</b>系统只按几何位置找下一个控件，
     * 从最上面一档再按「上」会横向窜到左侧频道行（实测落到
     * {@code id/metaScroll}），面板里就再也按不回来了。菜单内"上下选档"
     * 必须是一个自洽闭环：到顶就停在顶上，到底就停在底下。
     *
     * <p>上下到达边界时返回 {@code true} 吞掉按键，而不是交给系统——
     * 否则同样会触发那次窜行。
     *
     * @return 恒为 true（面板内的上下键一律由这里接管）
     */
    private boolean stepQualityFocus(int delta) {
        if (qualityRow == null || qualityRow.getChildCount() == 0) {
            return true;
        }
        int count = qualityRow.getChildCount();
        int from = indexContaining(qualityRow, getCurrentFocus());
        if (from < 0) {
            from = 0;
        }
        int to = Math.max(0, Math.min(count - 1, from + delta));
        if (to != from) {
            View item = qualityRow.getChildAt(to);
            requestFocusRobust(item);
            scrollQualityIntoView(item);
        }
        return true;
    }

    /**
     * {@code focused} 落在 {@code parent} 的第几个直接子项里；不在里面返回 -1。
     *
     * <p><b>不能用 {@code parent.getChildAt(i) == focused} 直接比。</b>
     * 档位项/频道行内部还有 TextView，焦点实际停在那个子 View 上，
     * 精确比较永远不成立，{@code from} 于是恒为 0——
     * 表现为「在面板里按 UP 到某一档就再也上不去」（实测 720P 卡住）。
     */
    private static int indexContaining(ViewGroup parent, View focused) {
        if (parent == null || focused == null) {
            return -1;
        }
        View f = focused;
        while (f != null) {
            if (f.getParent() == parent) {
                for (int i = 0; i < parent.getChildCount(); i++) {
                    if (parent.getChildAt(i) == f) {
                        return i;
                    }
                }
                return -1;
            }
            Object p = f.getParent();
            f = p instanceof View ? (View) p : null;
        }
        return -1;
    }

    /** 面板里表示"当前正在播这一档"的那一行；没有就返回第一行。 */
    private View currentQualityItem() {
        if (qualityRow == null || qualityRow.getChildCount() == 0) {
            return null;
        }
        for (int i = 0; i < qualityRow.getChildCount(); i++) {
            View v = qualityRow.getChildAt(i);
            if (v.isSelected()) {
                return v;
            }
        }
        return qualityRow.getChildAt(0);
    }

    /** 当前焦点的那一行滚进可视区，防止档位多时选中项在屏幕外。 */
    private void scrollQualityIntoView(View item) {
        if (item == null || qualityScroll == null || qualityRow == null) {
            return;
        }
        int top = item.getTop();
        int bottom = item.getBottom();
        int viewH = qualityScroll.getHeight();
        int cur = qualityScroll.getScrollY();
        if (top < cur || bottom > cur + viewH) {
            qualityScroll.smoothScrollTo(0, Math.max(0, top - viewH / 3));
        }
    }

    /**
     * 档位太多时把面板限高，超出的部分滚动。
     *
     * <p>CCTV-1 这类有十几个备胎的频道，如果按内容高度铺开，
     * 面板会被撑出屏幕、标题被顶飞。这里量一次内容高度，
     * 超过屏幕 72% 就锁在上限，让 ScrollView 自己滚；
     * 内容本来就矮（只有两档）时保持 wrap_content，
     * 面板才会紧贴内容居中，而不是上下留一大片空白。
     */
    private void adaptQualityScrollHeight() {
        if (qualityScroll == null || qualityRow == null) {
            return;
        }
        final int maxH = UiScale.pctH(72);
        final ViewGroup.LayoutParams lp = qualityScroll.getLayoutParams();
        qualityRow.post(() -> {
            // 量"所有子行的高度之和"，而不是 qualityRow.getHeight()：
            // 上一次把 ScrollView 限高后，子行会被裁剪，
            // getHeight() 拿到的是被压过的值，档位变少时就会卡在旧高度上。
            int contentH = 0;
            for (int i = 0; i < qualityRow.getChildCount(); i++) {
                View child = qualityRow.getChildAt(i);
                contentH += child.getHeight();
                if (child.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                    contentH += ((ViewGroup.MarginLayoutParams)
                            child.getLayoutParams()).bottomMargin;
                }
            }
            int want = Math.min(contentH, maxH);
            if (want > 0 && lp.height != want) {
                lp.height = want;
                qualityScroll.setLayoutParams(lp);
            }
        });
    }

    /** 切换清晰度：用户明确点了某一路，就照播那一路，不再自动挑。 */
    private void switchQuality(int index) {
        Channel c = playingChannel();
        if (c == null) {
            return;
        }
        if (index < 0 || index >= c.sources.size()) {
            return;
        }
        sourceIndex = index;
        retryRound = 0;
        hasPlayed = false;
        rebufferStart = 0;
        // 用户亲手选了线路，就照他的选择播：清掉自动降档留下的上限，
        // 否则切到高清后会被 cap 立刻打回低清，看起来像"点了没反应"
        QualityCap.clear(this, c.name);
        // 但降档许可不要锁死：用户选 1080 后发现确实卡，观察期过后仍会降下来，
        // 否则就是"点了高清后一直卡着不动"。
        qualityLoweredThisSession = false;
        // 上一次自动降档的冷却也要清掉，否则用户是在自动降档后 30 秒内手动
        // 选了高清，那条冷却会先把观察期过后的第一次降档挡回去。
        lastQualityLowerAt = 0L;
        // 观察期从这一刻起算：这段时间内禁止自动降档，避免用户刚点高清
        // 就被一次起播缓冲打回低清。
        lastManualPickAt = System.currentTimeMillis();
        manualPickChannel = c.name;
        manualGraceStrikes = 0;
        android.util.Log.i("IPTVQUAL", "manual-pick ch=" + c.name
                + " -> " + c.source(index).label()
                + " grace=" + (MANUAL_PICK_GRACE_MS / 1000) + "s");
        loading.setVisibility(View.VISIBLE);
        speed.reset();
        LiveSpeedMeter.INSTANCE.reset();
        // 重新渲染信息条让选中态跟上用户的选择
        renderTopLogo(c);
        startPlayback(c);
    }

    /**
     * 播放失败一律自愈，不向用户弹提示。
     * 直播流很稳，失败多是播放器自己滑出窗口这类可恢复状态；
     * 静默重试即可，绝不把用户留在永久黑屏里。
     */
    private void onSourceFailed(PlaybackException error) {
        loading.setVisibility(View.GONE);
        ui.removeCallbacks(recoverPlayback);
        ui.postDelayed(recoverPlayback, 400);
    }

    /**
     * 从失败状态回到正常播放。
     * BEHIND_LIVE_WINDOW 是滑出了直播窗口的左边界，只能重新定位到直播边缘；
     * 其余错误先原地重试，连续多轮仍失败才换下一条线路。
     */
    private final Runnable recoverPlayback = new Runnable() {
        @Override
        public void run() {
            Channel c = playingChannel();
            if (player == null || c == null) {
                return;
            }
            int err = player.getPlayerError() == null ? 0 : player.getPlayerError().errorCode;
            if (err == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                // 窗口滑走只能回到直播边缘，重试同一地址即可
                retryRound = 0;
                startPlayback(c);
                return;
            }
            // 其余错误多给几轮机会，节目单里的线路时常是短暂抽风
            if (retryRound < MAX_RETRY_ROUNDS) {
                retryRound++;
                startPlayback(c);
                return;
            }
            // 原地重试都失败了，这条线路确实不可用。交给探测重新挑一条，
            // 而不是简单换下一条——节目单里连不上的线路很多，撞运气会黑屏很久
            retryRound = 0;
            repickSource();
        }
    };

    /**
     * 当前线路喂不动时，<b>把清晰度降一档再重挑</b>，而不是只换同档的另一条。
     *
     * <p>实测（build/verify11.log 13:01:10~13:01:50）1080 线路的形态是：
     * 每 5 秒的分片边界掉一次帧，{@code proc} 从 100 掉到 48~70，
     * {@code held-stuck earlyUs≈280000}（正常是 45000）——
     * 分片下载追不上播放，播放器只能攥着帧等数据。
     * 这类问题换同档的另一条线路往往一样卡（同机房同码率），
     * 真正有效的是降低码率需求本身。
     *
     * <p>降档只在<b>确认当前档位喂不动</b>时发生，且：
     * <ul>
     *   <li>同一次播放只降一次（{@link #qualityLoweredThisSession}）；</li>
     *   <li>降后有 30 秒冷却（{@link #QUALITY_LOWER_COOLDOWN_MS}），
     *       避免新线路起播的缓冲被当成又一次卡顿而连降两档；</li>
     *   <li>上限由 {@link QualityCap} 落盘，后台精修也读同一份，
     *       所以<b>不会自己弹回高清</b>。</li>
     * </ul>
     *
     * @return true 表示已经降档并触发了重挑，调用方不要再走普通换线
     */
    private boolean lowerQualityInstead(Channel c, String why) {
        if (qualityLoweredThisSession) {
            return false;
        }
        long now = System.currentTimeMillis();
        // 用户刚手动选过清晰度：给足观察期，这段时间不自动降档。
        // 用户点了 1080 就是想看高清，两秒后就给他降回去等于无视他的选择。
        //
        // 但观察期保护的是<b>已经看到的画面</b>。若他选中那条线路压根没出过画面
        // （{@code hasPlayed=false}，屏幕只有缓冲圈），就没有"打断观看"这回事：
        // 继续禁止降档只会让看门狗连续超时，最后提示"该频道暂时无法播放"，
        // 比降一档难看得多。此时放行降档，先想办法出个画面。
        if (inManualGrace(c.name)) {
            if (hasPlayed) {
                android.util.Log.i("IPTVQUAL", "lower skip ch=" + c.name + " why=" + why
                        + " manualGrace=" + ((now - lastManualPickAt) / 1000) + "s/"
                        + (MANUAL_PICK_GRACE_MS / 1000) + "s");
                return false;
            }
            android.util.Log.w("IPTVQUAL", "lower grace-waived ch=" + c.name + " why=" + why
                    + " no-picture-yet");
        }
        if (now - lastQualityLowerAt < QUALITY_LOWER_COOLDOWN_MS) {
            return false;
        }
        String cur = c.source(sourceIndex).label();
        int curRank = c.source(sourceIndex).effectiveRank();
        int cap = QualityCap.lowerBelow(this, c, curRank);
        if (cap == Integer.MAX_VALUE) {
            // 已经是能识别的最低档，没有再降的余地，交给普通换线
            return false;
        }
        // 降档后必须在「新上限内」真的找到一条线路，否则不能宣布降档成功：
        // 若直接退回 index 0，很可能又落回那条 1080，等于没降还白换一次线。
        int next = quickestSource(c);
        if (next < 0) {
            // 上限内一条可用的都没有。撤掉刚写入的上限，让普通换线去处理，
            // 免得把该频道锁在"无线路可选"的状态里。
            android.util.Log.w("IPTVQUAL", "lower ch=" + c.name + " why=" + why
                    + " but no source under cap=" + cap + ", revert");
            QualityCap.clear(this, c.name);
            return false;
        }
        qualityLoweredThisSession = true;
        lastQualityLowerAt = now;
        android.util.Log.w("IPTVQUAL", "lower ch=" + c.name + " why=" + why
                + " from=" + (cur == null ? "自动" : cur) + " capRank=" + cap
                + " to=" + c.source(next).label() + " idx=" + next);
        // 降到新档位后重新挑：quickestSource 会按新上限取最高可播档
        sourceIndex = next;
        startPlayback(c);
        return true;
    }

    /** 当前线路彻底失败后重新探测换线，跳过已经失败的那一路。 */
    private void repickSource() {
        // 重挑线路永远是给"正在播放的那个台"换，而不是列表焦点行
        final Channel c = playingChannel();
        if (c == null) {
            return;
        }
        final int failed = sourceIndex;
        final String chName = c.name;
        // 失败线路不能再当"记忆线路"，否则下次换台还会先踩一次坑
        SourceMemory.remove(this, c.name);
        loading.setVisibility(View.VISIBLE);
        speed.reset();
        LiveSpeedMeter.INSTANCE.reset();
        SourceProber.pickExcluding(this, c, -1, failed, picked -> {
            // 期间换台了就丢弃（按名字比，不按下标）
            if (!chName.equals(playingName)) {
                return;
            }
            if (picked < 0) {
                // 全部线路都不可用，留在原地等它自己恢复
                loading.setVisibility(View.GONE);
                return;
            }
            sourceIndex = picked;
            lastChunkSeq = -1;
            chunkSkips = 0;
            startPlayback(c);
        });
    }

    /* ---------------- 频道选择 ---------------- */

    private void select(int position) {
        if (position < 0 || position >= channels.size()) {
            return;
        }
        sourceIndex = 0;
        retryRound = 0;
        play(position);
        // 选台后收起整个菜单（规则 1：抽屉和清晰度面板同进同退）
        closeMenu();
    }

    /** 长按频道行：收藏 + 手动选线路。 */
    private void showChannelMenu(int position) {
        if (position < 0 || position >= channels.size()) {
            return;
        }
        final Channel c = channels.get(position);
        boolean fav = favoritesSet().contains(c.name);

        // 收藏项 + 立即播放 + 每一条线路，用户自己决定用哪条
        String[] items = new String[c.sources.size() + 2];
        items[0] = fav ? "取消收藏" : "加入收藏";
        items[1] = "立即播放（自动选线）";
        for (int i = 0; i < c.sources.size(); i++) {
            items[i + 2] = "线路 " + (i + 1) + "  " + c.sources.get(i).label();
        }

        new AlertDialog.Builder(this)
                .setTitle(c.name)
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        toggleFavorite(c);
                        return;
                    }
                    if (which == 1) {
                        select(position);
                        return;
                    }
                    // 指定线路：先切到该频道，再固定用这一路
                    current = position;
                    select(position);
                    ui.postDelayed(() -> switchQuality(which - 2), 300);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /* ---------------- 设置与刷新 ---------------- */

    /** 设置弹窗：刷新数据、修改源地址都在这里。 */
    private void showSettings() {
        String updated = store.lastUpdated() == 0 ? "从未更新"
                : new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
                .format(new Date(store.lastUpdated()));
        String source = store.customSource().isEmpty()
                ? "默认（GitHub / jsDelivr / gh 代理）" : store.customSource();
        String[] items = {
                "立即刷新频道数据",
                "修改数据源地址",
                "恢复默认数据源",
                "信息：上次更新 " + updated,
                "信息：当前源 " + source,
        };
        // 调试包才多一项：虚拟遥控器开关。release 包里 available() 恒为 false，
        // 菜单结构和用户看到的完全一致，不会多出一个灰掉的死条目
        if (VirtualRemote.available()) {
            String[] withRemote = java.util.Arrays.copyOf(items, items.length + 1);
            withRemote[items.length] = VirtualRemote.isOpen()
                    ? "隐藏虚拟遥控（调试）" : "显示虚拟遥控（调试）";
            items = withRemote;
        }
        final int remoteIndex = VirtualRemote.available() ? items.length - 1 : -1;
        new AlertDialog.Builder(this)
                .setTitle("设置")
                .setItems(items, (d, which) -> {
                    if (which == remoteIndex) {
                        VirtualRemote.setOpen(!VirtualRemote.isOpen());
                        showHint(VirtualRemote.isOpen() ? "虚拟遥控已显示" : "虚拟遥控已隐藏");
                    } else if (which == 0) {
                        manualRefresh();
                    } else if (which == 1) {
                        askSource();
                    } else if (which == 2) {
                        store.setCustomSource("");
                        showHint("已恢复默认数据源");
                        manualRefresh();
                    }
                })
                .show();
    }

    /**
     * 规则 6：每次打开 App 都在后台刷新频道列表。
     *
     * <p>原来只在缓存过期（24 小时）时才拉，意味着用户一天内第二次打开
     * 完全不刷新，坏掉的线路永远留在列表里。现在<b>每次启动都拉</b>：
     * 拉取在后台线程，不挡起播；失败就静默保留本地列表。
     *
     * <p>刷新后除了换新数据，还会走 {@link ChannelStore#load()}，
     * 那里会按 {@link DeadSources} 剔除已验证的坏线路。
     */
    private void refreshInBackground() {
        final String keep = playingName;
        new Thread(() -> {
            try {
                final int n = store.refresh();
                android.util.Log.i("IPTVSTORE", "auto-refresh ok channels=" + n);
                ui.post(() -> reloadKeeping(keep));
            } catch (Exception e) {
                // 静默失败：本地列表本来就能用，不该用"更新失败"吓用户
                android.util.Log.i("IPTVSTORE", "auto-refresh skipped: " + e);
                ui.post(() -> reloadKeeping(keep));
            }
        }, "iptv-startup-refresh").start();
    }

    /** 刷新数据后重新加载列表，尽量停留在同一个频道。 */
    private void reloadKeeping(String name) {
        List<Channel> loaded;
        try {
            loaded = store.load();
        } catch (Exception e) {
            return;
        }
        ChannelStore.sort(loaded);
        master.clear();
        master.addAll(loaded);
        rebuildGroups();

        if (master.isEmpty()) {
            return;
        }
        if (TextUtils.isEmpty(name)) {
            // 首次拉到数据，接着播第一个频道
            selectGroupOf(master.get(0).group);
            applyGroup(currentGroup);
            sourceIndex = 0;
            play(0);
            return;
        }
        selectGroupOf(channelGroup(name));
        applyGroup(currentGroup);
    }

    private String channelGroup(String name) {
        for (Channel c : master) {
            if (c.name.equals(name)) {
                return c.group;
            }
        }
        return master.get(0).group;
    }

    /** 手动刷新：只给成功的轻提示，失败静默，避免"刷新失败"这类负面字眼。 */
    private void manualRefresh() {
        final String keep = playingName;
        new Thread(() -> {
            try {
                store.refresh();
                ui.post(() -> {
                    reloadKeeping(keep);
                    showHint("频道列表已更新");
                });
            } catch (Exception e) {
                // 静默：本地列表仍可用
            }
        }, "iptv-manual-refresh").start();
    }

    /** 允许把数据源换成自建或镜像地址，输入为空则恢复默认。 */
    private void askSource() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setSingleLine(true);
        input.setText(store.customSource());
        input.setHint(ChannelStore.DEFAULT_SOURCE);

        new AlertDialog.Builder(this)
                .setTitle("数据源地址（留空用默认）")
                .setView(input)
                .setPositiveButton("保存并刷新", (d, w) -> {
                    store.setCustomSource(input.getText().toString().trim());
                    manualRefresh();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /* ---------------- 抽屉 ---------------- */

    /**
     * 把当前频道行滚到列表垂直正中（规则 5）。
     *
     * <p>居中而不是"刚好露出来"，有两个理由：
     * <ol>
     *   <li><b>用户要看得到下一个频道。</b>行贴着列表底部时，下面全是空白，
     *       根本不知道再按一下会切到哪一台。</li>
     *   <li><b>不居中会卡焦点。</b>默认布局管理器把下一行滚到只露出十几像素
     *       就收手，那一行既"算可见"又没完整布局，焦点搜索找得到却跳不过去，
     *       连续按 DOWN 到第 5 行之后就再也动不了（真机、模拟器都能复现）。</li>
     * </ol>
     *
     * <p>滚动的唯一入口是 {@link CenteringLayoutManager#centerOn(int)}；
     * 系统自己那套"滚到可见"已经在布局管理器里关掉了，避免两套滚动打架。
     */
    private void scrollCurrentIntoComfort(int position) {
        if (position < 0) {
            return;
        }
        list.post(() -> {
            if (listManager == null) {
                list.scrollToPosition(position);
                return;
            }
            listManager.centerOn(position);
        });
    }

    private void updateDrawerInfo() {
        String name = groupNames.isEmpty() ? "" : groupNames.get(currentGroup);
        // 用户不需要知道数据源，只关心当前分组和列表更新时间
        long t = store.lastUpdated();
        String when = t == 0 ? "本地列表"
                : new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(t)) + " 更新";
        drawerSub.setText(ChannelGroups.displayName(name) + " · " + channels.size()
                + "\n共 " + master.size() + " 个频道 · " + when);
    }

    /**
     * 打开<b>整个菜单</b>：左抽屉 + 右清晰度面板 + 右下角信息条。
     *
     * <p>规则 1/3 要求这两块是<b>同一个菜单</b>，同生同灭。所以只能有
     * 这一个入口——早期拆成 {@code openDrawer()} / {@code openQualityPanel()}
     * 两个方法，结果出现过"只有右边没有左边"的半套界面，
     * 用户按上下键时分不清自己到底在选频道还是选清晰度。
     *
     * <p>焦点按规则 2 落进<b>频道列表</b>：菜单打开时上下键是"在菜单内选"，
     * 默认从频道开始，按右键跳到清晰度，按左键跳回来（规则 3）。
     */
    private void openMenu() {
        if (isMenuOpen()) {
            return;
        }
        // 顶栏让位，避免和抽屉头部文字叠在一起
        topBar.setVisibility(View.GONE);
        ui.removeCallbacks(hideBar);
        if (listManager != null) {
            // 上次收起菜单时可能有个滚动过渡没跑完，残留的 translationY
            // 会让重新出现的列表整体偏移半行，像"第一行被裁掉一截"
            listManager.cancelScrollAnim();
        }
        drawer.setVisibility(View.VISIBLE);
        // "正在播放"标记按频道名刷新，和列表选中行无关
        adapter.setPlaying(playingName);
        groupAdapter.setCurrent(currentGroup);
        qualityPanel.setVisibility(View.VISIBLE);
        adaptQualityScrollHeight();
        // 面板贴右缘，调试用的虚拟遥控器默认也在右下角，会压住档位。
        // 告诉它让出面板占的宽度，它自己横移到左边那段空隙里
        VirtualRemote.setReservedRight(UiScale.pctW(RIGHT_PANEL_PCT));

        // 规则 5：先把当前频道行滚进视口，再落焦；
        // 顺序反了会被焦点系统的自动滚动覆盖掉，当前行可能被裁在视口外
        if (current >= 0) {
            scrollCurrentIntoComfort(current);
        }
        // 规则 3 的前提是"菜单里有焦点"，所以这里**必须**落焦，不能因为
        // isTouchMode() 就跳过。
        //
        // 之前写成 `if (!isTouchMode()) focusDrawerList(0)`，本意是不给触摸屏
        // 留无用的高亮，结果点屏（虚拟遥控器也是点屏）后窗口一直在 touch mode，
        // 菜单打开了却没有任何 View 持有焦点 —— 上下左右键全部失效，
        // 用户看到的就是"按 OK 后方向键压根没法切换"。
        // 触摸模式下仍需落焦，只是改用 requestFocusFromTouch() 绕开限制。
        focusDrawerList(0);
    }

    /** 收起整个菜单：三块一起走，不留半套 UI（规则 1）。 */
    private void closeMenu() {
        if (!isMenuOpen()) {
            // 菜单本来就关着：仍要把调试遥控器的让位复位，
            // 否则它停在"左移让开清晰度面板"的位置，菜单一关就悬在画面中间
            VirtualRemote.setReservedRight(0);
            return;
        }
        drawer.setVisibility(View.GONE);
        qualityPanel.setVisibility(View.GONE);
        VirtualRemote.setReservedRight(0);
        if (listManager != null) {
            // 收起时若有过渡在跑，立刻停下并把行放回真实位置，
            // 否则下次打开会带着残留平移量出现
            listManager.cancelScrollAnim();
        }
        findViewById(R.id.root).requestFocus();
        showBar();
    }

    /**
     * 把焦点落进抽屉的频道列表，带重试。
     *
     * <p>抽屉刚 {@code setVisibility(VISIBLE)} 时还没布局：{@code list} 宽高为 0，
     * {@code requestFocus()} 会静默失败，而 {@code findViewByPosition()} 也返回 null
     * （行还没创建）。只试一次的结果就是"抽屉打开了但方向键没反应"——
     * 实测左键打开后焦点仍停在 {@code root} 上。
     *
     * <p>所以按帧重试几次，每次都重新取当前行；取不到就退回落焦列表本身，
     * 再由焦点系统自己找到第一个可聚焦的行。
     */
    private void focusDrawerList(int attempt) {
        if (drawer.getVisibility() != View.VISIBLE) {
            return;
        }
        View target = null;
        if (list.getLayoutManager() != null && current >= 0) {
            target = list.getLayoutManager().findViewByPosition(current);
        }
        boolean ok = target != null && requestFocusRobust(target);
        if (ok) {
            // 布局刚完成时行高可能还没量准，下一帧再居中一次
            ui.postDelayed(() -> {
                if (current >= 0 && drawer.getVisibility() == View.VISIBLE) {
                    scrollCurrentIntoComfort(current);
                }
            }, 80);
            return;
        }
        if (attempt >= 6) {
            // 真的取不到具体行：落焦列表本身，方向键至少能用
            boolean listOk = requestFocusRobust(list);
            android.util.Log.w("IPTVFOCUS", "drawer-focus fallback current=" + current
                    + " ok=" + listOk);
            return;
        }
        // 布局还没完成，下一帧再试
        ui.postDelayed(() -> focusDrawerList(attempt + 1), 40);
    }

    /**
     * 落焦，能穿过 touch mode 的限制。
     *
     * <p>窗口一旦进入 touch mode（点过屏幕，包括点虚拟遥控器），普通的
     * {@link View#requestFocus()} 会被<b>静默拒绝</b>——返回 false，焦点不动。
     * 界面就成了"菜单打开了，但方向键没有任何反应"。
     *
     * <p>{@link View#requestFocusFromTouch()} 是系统给触摸场景留的通道：
     * 它不仅会落焦，还会主动让窗口退出 touch mode，之后方向键就正常了。
     * 电视应用的菜单本质上是"遥控器导航"，不该因为用户点过一次屏幕就残废。
     */
    private static boolean requestFocusRobust(View v) {
        if (v == null) {
            return false;
        }
        if (v.requestFocus()) {
            return true;
        }
        return v.requestFocusFromTouch();
    }

    /** 有触摸能力且最近没有按键输入时按触摸屏处理。 */
    private boolean isTouchMode() {
        return getPackageManager().hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
                && System.currentTimeMillis() - lastKeyAt > 3000;
    }

    /** 顶栏在抽屉打开时保持隐藏，避免和抽屉头部重叠。 */
    private void showBar() {
        if (drawer.getVisibility() == View.VISIBLE) {
            return;
        }
        topBar.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideBar);
        ui.postDelayed(hideBar, HIDE_BAR_MS);
    }

    private void showHint(String text) {
        hint.setText(text);
        hint.setMaxWidth(UiScale.pctW(62));
        hint.setVisibility(View.VISIBLE);
        placeHint();
        // 信息条刚显示时还没测到高度，布局后再摆一次
        hint.post(this::placeHint);
        hint.bringToFront();
        ui.removeCallbacks(hideHint);
        ui.postDelayed(hideHint, 5000);
    }

    /**
     * 提示浮层的竖直位置。
     *
     * <p>台标已移到左上角顶栏，不再占右下角，所以这里不需要为它让位，
     * 固定贴着底边往上抬一点即可。
     */
    private void placeHint() {
        ViewGroup.LayoutParams lp = hint.getLayoutParams();
        if (!(lp instanceof ViewGroup.MarginLayoutParams)) {
            return;
        }
        ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
        int target = UiScale.pxH(18);
        if (m.bottomMargin != target) {
            m.bottomMargin = target;
            hint.setLayoutParams(m);
        }
    }

    private void dismissHint() {
        ui.removeCallbacks(hideHint);
        hint.setVisibility(View.GONE);
    }

    /* ---------------- 按键 ---------------- */

    /**
     * 遥控器按键分流。
     *
     * <p>规则 1/2/3 的核心是「菜单打开」这一个状态：菜单没出现时上下键换台，
     * 菜单出现后上下左右键全部用于在菜单内选择。所以这里只有一个
     * {@code menuOpen} 判据，不再分抽屉/清晰度面板两套。
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        boolean menuOpen = isMenuOpen();
        lastKeyAt = System.currentTimeMillis();
        // 菜单关闭时任何按键都亮出顶栏（台标 + 台名），静置后自动收起。
        // 台标现在就在顶栏里，不再有独立的一套显隐计时。
        if (!menuOpen && keyCode != KeyEvent.KEYCODE_BACK) {
            showBar();
        }

        // 数字键直接换台，1.5 秒内连续输入有效（菜单打开时不响应）
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9 && !menuOpen) {
            ui.removeCallbacks(clearNumber);
            ui.postDelayed(clearNumber, NUMBER_WINDOW_MS);
            if (numberBuf.length() >= 3) {
                numberBuf.setLength(0);
            }
            numberBuf.append((char) ('0' + (keyCode - KeyEvent.KEYCODE_0)));
            showHint("跳转到 " + numberBuf);
            int n = Integer.parseInt(numberBuf.toString());
            // 数字是"正在看的这个分组的第 n 个台"。若用户刚在菜单里浏览过
            // 别的分组，channels 已经不是正在看的那一组，下标会指到别的台，
            // 所以先把分组切回正在播的那一组（和 stepFromPlaying 同理）。
            ensurePlayingGroup();
            if (n >= 1 && n <= channels.size()) {
                stepChannel(n - 1);
            }
            return true;
        }

        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (menuOpen) {
                    // 规则 1/3：菜单内 OK 交给焦点项（选台 / 选清晰度）
                    return super.onKeyDown(keyCode, event);
                }
                // 规则 1：OK 一次叫出左边频道列表 + 右边清晰度面板
                openMenu();
                return true;

            case KeyEvent.KEYCODE_DPAD_UP:
                if (menuOpen) {
                    // 规则 3：菜单打开时上下键在菜单内移动焦点。
                    // 焦点在右侧档位 / 左侧频道列表时都自己算下一项，不交给 super：
                    // 焦点导航只认几何相邻、且"露出一点就算可见"，
                    // 走到边界会窜到另一个面板（实测落到 id/metaScroll），
                    // 或者干脆卡住不动（实测连续 DOWN 到第 5 行后 bounds 完全不变）。
                    if (inQualityPanel()) {
                        return stepQualityFocus(-1);
                    }
                    if (inDrawerList()) {
                        return stepChannelFocus(-1);
                    }
                    if (inGroupList()) {
                        return stepGroupFocus(-1);
                    }
                    return super.onKeyDown(keyCode, event);
                }
                // 规则 2：菜单没出现时上下键换台
                stepFromPlaying(-1);
                return true;

            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (menuOpen) {
                    if (inQualityPanel()) {
                        return stepQualityFocus(1);
                    }
                    if (inDrawerList()) {
                        return stepChannelFocus(1);
                    }
                    if (inGroupList()) {
                        return stepGroupFocus(1);
                    }
                    return super.onKeyDown(keyCode, event);
                }
                stepFromPlaying(1);
                return true;

            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_PAGE_UP:
                if (!menuOpen) {
                    stepFromPlaying(1);
                    return true;
                }
                return super.onKeyDown(keyCode, event);

            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_PAGE_DOWN:
                if (!menuOpen) {
                    stepFromPlaying(-1);
                    return true;
                }
                return super.onKeyDown(keyCode, event);

            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (!menuOpen) {
                    // 规则 1：左右键不再单独打开面板
                    return true;
                }
                if (inQualityPanel()) {
                    // 规则 3：从右侧档位跳回左侧频道列表
                    focusDrawerList(0);
                    return true;
                }
                // 焦点已在左侧：交给焦点导航（频道列表 → 分组栏）
                return super.onKeyDown(keyCode, event);

            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (!menuOpen) {
                    // 规则 1：左右键不再单独打开面板，菜单外一律不响应
                    return true;
                }
                if (inQualityPanel()) {
                    // 已经在最右侧，再按右没有去处
                    return true;
                }
                // 规则 3：从左侧跳到右侧清晰度档位
                focusQualityPanel();
                return true;

            case KeyEvent.KEYCODE_BACK:
                if (menuOpen) {
                    // 规则 1：BACK 收起整个菜单（不论焦点在哪一块）
                    closeMenu();
                    return true;
                }
                // 台标已并入左上角顶栏，跟着顶栏一起收起，不再单独一档
                if (topBar.getVisibility() == View.VISIBLE) {
                    ui.removeCallbacks(hideBar);
                    topBar.setVisibility(View.GONE);
                    return true;
                }
                return super.onKeyDown(keyCode, event);

            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_INFO:
                openMenu();
                return true;

            case KeyEvent.KEYCODE_Z:
                toggleAspect();
                return true;

            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    /**
     * 在频道列表里上下移动<b>焦点</b>（不切台，切台要按 OK）。
     *
     * <p>自己算下一行而不是交给 {@code super}，原因是系统的焦点导航
     * 只按几何相邻找控件、并且"露出一点就算可见"：
     * <ul>
     *   <li>行贴到视口底部时，下一行只露出十几像素，焦点搜索找得到却跳不过去，
     *       连续按 DOWN 到第 5 行后就彻底卡死（真机、模拟器都能复现）；</li>
     *   <li>走到列表首/尾还可能横向窜到左栏分组导航上去。</li>
     * </ul>
     * 这里按 adapter 下标直接算目标行，并同步把目标行滚到垂直正中，
     * 于是"再按一下会切到哪个台"始终看得见。
     *
     * @param delta -1 向上 / +1 向下
     * @return 恒为 true（列表内的上下键一律由这里接管）
     */
    private boolean stepChannelFocus(int delta) {
        if (listManager == null || channels.isEmpty()) {
            return true;
        }
        int from = listManager.focusedPosition(getCurrentFocus());
        if (from < 0) {
            // 焦点行被 RecyclerView 回收掉时 getCurrentFocus() 已经不可靠。
            // 不能用 current（那是"正在播放的台"）：用户可能从第 1 行往下
            // 移焦点但并没有换台，用 current 当基准会一直算出同一行、原地打转。
            from = lastFocusPos >= 0 ? lastFocusPos : Math.max(0, current);
        }
        int to = Math.max(0, Math.min(channels.size() - 1, from + delta));
        if (to == from) {
            return true;
        }
        moveChannelFocusTo(to);
        return true;
    }

    /**
     * 把焦点移到第 {@code position} 行，并把它滚到垂直正中（带滚动过渡）。
     *
     * <p>用 {@link CenteringLayoutManager#smoothCenterOn(int)} 而不是
     * {@code centerOn()}：后者是硬切，按一下上下键画面"突然换一屏"，
     * 用户看不出是滚过去的，只会觉得闪了一下。前者会补一段位移动画，
     * 配合边缘透明度渐变，视觉上是内容在连续滚动。
     *
     * <p>顺序是「先落焦，再滚动」：落焦会触发系统要求"把这一行滚进可见区"，
     * 那一步已经在 {@code requestChildRectangleOnScreen} 里被拦掉了，
     * 所以随后的 {@code smoothCenterOn} 才是唯一决定位置的一方。
     */
    private void moveChannelFocusTo(int position) {
        if (position < 0 || position >= channels.size()) {
            return;
        }
        lastFocusPos = position;
        ui.post(() -> {
            View row = listManager == null ? null : listManager.findViewByPosition(position);
            if (row == null) {
                // 行不在窗口里（跨了多行）：先把它带进来。
                // 这一刻画面本来就是整屏换掉，补动画没有意义。
                if (listManager != null) {
                    listManager.centerOn(position);
                }
                ui.post(() -> {
                    View again = listManager == null
                            ? null : listManager.findViewByPosition(position);
                    if (again != null) {
                        requestFocusRobust(again);
                    }
                });
                return;
            }
            smoothScrollPending = true;
            requestFocusRobust(row);
            if (listManager != null) {
                listManager.smoothCenterOn(position);
            }
            ui.postDelayed(() -> smoothScrollPending = false, 300);
        });
    }

    /** 遥控器焦点是否落在左侧分组导航里。 */
    private boolean inGroupList() {
        if (drawer.getVisibility() != View.VISIBLE) {
            return false;
        }
        View f = getCurrentFocus();
        while (f != null) {
            if (f == groupList) {
                return true;
            }
            Object p = f.getParent();
            f = p instanceof View ? (View) p : null;
        }
        return false;
    }

    /**
     * 在分组导航里上下移动焦点。
     *
     * <p>和 {@link #stepChannelFocus(int)} 同理：交给系统做的话，
     * 走到最后一个分组再按 DOWN 会横向窜到右侧清晰度面板
     * （实测落到 576P / 更多线路），菜单内的"上下选分组"就断了。
     *
     * <p><b>焦点移到哪个分组，右侧频道列表就立刻跟着切过去</b>——
     * 用户要的是"翻着看"，而不是每换一个分组还要再按一次 OK 确认。
     * 切换只换列表内容，不打断正在播放的画面（{@link #applyGroup(int)}
     * 本身只管列表），也不关闭菜单。
     */
    private boolean stepGroupFocus(int delta) {
        int from = -1;
        View f = getCurrentFocus();
        while (f != null) {
            if (f.getParent() == groupList) {
                from = groupList.getChildAdapterPosition(f);
                break;
            }
            Object p = f.getParent();
            f = p instanceof View ? (View) p : null;
        }
        if (from < 0) {
            from = currentGroup;
        }
        int to = Math.max(0, Math.min(groupNames.size() - 1, from + delta));
        if (to == from) {
            return true;
        }
        final int target = to;
        ui.post(() -> {
            // 列表跟随焦点切换（不关菜单、不切台）
            if (target != currentGroup) {
                applyGroup(target);
            }
            View row = groupList.getLayoutManager() == null ? null
                    : groupList.getLayoutManager().findViewByPosition(target);
            if (row != null) {
                requestFocusRobust(row);
            }
            groupAdapter.setCurrent(target);
        });
        return true;
    }

    /** 遥控器焦点是否落在左侧频道列表里。 */
    private boolean inDrawerList() {
        if (drawer.getVisibility() != View.VISIBLE) {
            return false;
        }
        View f = getCurrentFocus();
        while (f != null) {
            if (f == list) {
                return true;
            }
            Object p = f.getParent();
            f = p instanceof View ? (View) p : null;
        }
        return false;
    }

    /** 遥控器焦点是否落在右侧清晰度面板里。 */
    private boolean inQualityPanel() {
        if (qualityPanel.getVisibility() != View.VISIBLE) {
            return false;
        }
        // 焦点停在档位项（或它的子 TextView）上，都不是 qualityPanel 本身，
        // 所以必须沿父链比较，不能写成 f == qualityPanel。
        View f = getCurrentFocus();
        while (f != null) {
            if (f == qualityPanel) {
                return true;
            }
            Object p = f.getParent();
            f = p instanceof View ? (View) p : null;
        }
        return false;
    }

    /** 换台统一入口，顺带重置失败与数字输入状态。 */
    private void stepChannel(int index) {
        if (channels.isEmpty()) {
            return;
        }
        int size = channels.size();
        int next = ((index % size) + size) % size;
        sourceIndex = 0;
        retryRound = 0;
        if (isMenuOpen()) {
            closeMenu();
        }
        dismissHint();
        play(next);
    }

    /**
     * 菜单关闭时按上下键换台：<b>基准必须是"现在正在看的那台"</b>。
     *
     * <p>不能直接用 {@code current + delta}。{@code current} 是**列表焦点行**，
     * 用户可能刚刚在菜单里翻到别的分组看了一眼就按 BACK 收起（没有选台），
     * 这时 {@code current} 指向的是那个分组的第一行，而不是正在播的台。
     * 实测症状（{@code build/t_step_base.py}）：正在播「延边卫视」，
     * 打开菜单翻到「央视频道」再收起，按一次 DOWN 直接跳到了「北京卫视」——
     * 用户眼里就是"按一下换台，跳了一个不相干的台"。
     *
     * <p>所以这里先按 {@link #playingName} 把基准找回来：
     * <ul>
     *   <li>正在播的台在<b>当前</b>分组 → 直接用它的行号；</li>
     *   <li>不在（用户浏览过别的分组）→ 先把分组切回它所在的那一组，
     *       再以它为基准上下走。用户按上下键的语义始终是
     *       "在当前正在看的这个分组里换下一个台"。</li>
     * </ul>
     *
     * @param delta +1 下一个 / -1 上一个
     */
    private void stepFromPlaying(int delta) {
        if (master.isEmpty()) {
            return;
        }
        // 先切回正在播的那一组，**再**判断列表是否为空。
        // 顺序不能反：用户从空收藏分组收起菜单时 channels 是空的，
        // 若先判 channels.isEmpty() 就直接返回，分组永远切不回去，
        // 之后每次上下键都毫无反应（实测 `build/t_base2.py` 场景 C/D）。
        ensurePlayingGroup();
        if (channels.isEmpty()) {
            return;
        }
        int base = indexOfName(playingName);
        if (base < 0) {
            // 还没播过任何台（playingName 为空）：按列表焦点行作基准。
            // stepChannel 内部会对下标取模，-1 会自然回绕到最后一个台。
            base = Math.max(0, current);
        }
        stepChannel(base + delta);
    }

    /**
     * 把列表切回「正在播放的那个台所在的分组」。
     *
     * <p>用户可能在菜单里翻到别的分组看了一眼就按 BACK 收起（没有选台），
     * 此时 {@link #channels} 装的是那个分组的内容，而正在播的台在另一组。
     * 菜单关闭后所有"按列表下标"的操作（上下换台、数字键跳台）都必须先
     * 把列表切回来，否则下标指到的是用户"看过但没选"的那一组。
     */
    private void ensurePlayingGroup() {
        if (master.isEmpty() || TextUtils.isEmpty(playingName)) {
            return;
        }
        if (indexOfName(playingName) >= 0) {
            return;   // 正在播的台就在当前列表里，不用动
        }
        int want = groupNames.indexOf(channelGroup(playingName));
        if (want >= 0 && want != currentGroup) {
            applyGroup(want);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (player != null) {
            player.setPlayWhenReady(true);
        }
    }

    @Override
    protected void onStop() {
        if (player != null) {
            player.setPlayWhenReady(false);
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        VirtualRemote.release(this);
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }
}
