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

/** 直播主界面：全屏播放 + 分组导航抽屉 + 清晰度落地窗。 */
public class MainActivity extends Activity {

    private static final long HIDE_BAR_MS = 6000;
    /** 底部信息条常驻时长：到时下滑隐藏，不长期占画面。 */
    private static final long HIDE_INFO_BAR_MS = 5000;
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
    private RecyclerView groupList;
    private View drawer;
    private View topBar;
    private View infoBar;
    private android.widget.ImageView infoLogo;
    private TextView infoName;
    private LinearLayout infoQualityRow;
    private android.widget.HorizontalScrollView infoScroll;
    private TextView nowName;
    private TextView nowMeta;
    private TextView clock;
    private TextView hint;
    private TextView drawerSub;
    private TextView emptyTip;

    /** 抽屉内焦点预览卡：显示目标频道的台标与名称。 */
    private View previewCard;
    private android.widget.ImageView previewLogo;
    private TextView previewName;
    private TextView previewMeta;

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
    /** 当前频道在 channels 里的下标，-1 表示本组内没有正在播放的频道。 */
    private int current = -1;
    /**
     * 换台令牌：每次 play() 自增。
     *
     * <p>异步回调（探测、精修）都带着发起时的令牌回来，
     * 令牌对不上说明期间又换过台，结果必须丢弃。
     * 只比 current 是不够的——用户可能切走又切回同一个台。
     */
    private int playToken = 0;
    /** 正在播放的频道名，切分组时用它找回高亮。 */
    private String playingName = "";
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

    private final Handler ui = new Handler(Looper.getMainLooper());
    /** 分片下载速率，缓冲时显示给用户看。 */
    private final LoadSpeedMeter speed = new LoadSpeedMeter();
    private final Runnable hideBar = () -> topBar.setVisibility(View.GONE);
    private final Runnable clearNumber = () -> numberBuf.setLength(0);
    private final Runnable hideHint = () -> hint.setVisibility(View.GONE);

    /** 底部信息条下滑隐藏：平移出屏幕，而不是突然消失。 */
    private final Runnable hideInfoBar = () -> {
        if (infoBar.getVisibility() != View.VISIBLE || inInfoBar()) {
            return;
        }
        if (hint.getVisibility() == View.VISIBLE) {
            return;
        }
        infoBar.animate()
                .translationY(infoBar.getHeight())
                .setDuration(220)
                .withEndAction(() -> {
                    infoBar.setVisibility(View.GONE);
                    infoBar.setTranslationY(0f);
                })
                .start();
    };

    /** 亮出底部信息条并重新计时；用户按键或换台都会调到。 */
    private void showInfoBar() {
        infoBar.animate().cancel();
        infoBar.setTranslationY(0f);
        infoBar.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideInfoBar);
        ui.postDelayed(hideInfoBar, HIDE_INFO_BAR_MS);
        if (hint.getVisibility() == View.VISIBLE) {
            hint.post(this::placeHint);
        }
    }

    /** 立刻收起底部信息条，抽屉打开时用，避免和面板叠在一起。 */
    private void hideInfoBarNow() {
        ui.removeCallbacks(hideInfoBar);
        infoBar.animate().cancel();
        infoBar.setTranslationY(0f);
        infoBar.setVisibility(View.GONE);
    }

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
            startPlayback(channels.get(current));
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
     * 静默卡死检测：状态是 READY、也"在播"，但播放时钟不再前进。
     *
     * <p>现有两个看门狗都盖不住这种形态——起播看门狗只管"还没出过画面"，
     * 卡顿看门狗只在 STATE_BUFFERING 下启动。一旦停在 READY 而时钟不走，
     * 日志里表现为 {@code state=3 pos=-42917} 一路往负方向滑、画面永久定格，
     * 两个看门狗都不触发，只能靠用户手动换台。
     *
     * <p>判定逻辑见 {@link StallDetector}：只有位置"严格向前"才算进展，
     * 因为直播位置在分片滑出窗口时会整体后退（实测每 5 秒退 5000ms），
     * 把"变了"当成"在走"就永远检测不到。
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
                if (stallDetector.sample(eligible, p.getCurrentPosition(), now)) {
                    onSilentStall(p);
                }
            }
            ui.postDelayed(this, SILENT_STALL_TICK_MS);
        }
    };

    /**
     * 状态 READY 但时钟停走：重开这一路流。
     *
     * <p>不解码器/音频渲染器卡死时，seek 和等待都没用，只有重建播放管线
     * 才能恢复（与 xplayer 的处理一致：位置停 6 秒就重连，连续两次还卡
     * 就换一条解码管线）。这里先重开同一路；连续多次仍卡才换线。
     */
    private void onSilentStall(ExoPlayer p) {
        if (current < 0 || current >= channels.size()) {
            return;
        }
        Channel c = channels.get(current);
        android.util.Log.w("IPTVSTALL", "silent-stall ch=" + c.name
                + " pos=" + p.getCurrentPosition()
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
            // 重开同一路救不回来，多半是这条线路的问题，换一条
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
            if (current < 0 || current >= channels.size()) {
                return;
            }
            Channel c = channels.get(current);
            Channel.Source s = c.source(sourceIndex);
            android.util.Log.w("IPTVSTALL", "startup-timeout ch=" + c.name
                    + " idx=" + sourceIndex + " waited=" + waited
                    + " buffered=" + player.getTotalBufferedDuration());
            // 这条线路起不来，记成喂不饱，下次不再优先选它
            s.starved = Boolean.TRUE;
            SourceMemory.remove(MainActivity.this, c.name);
            startupFailures++;
            if (startupFailures >= STARTUP_GIVE_UP_ROUNDS) {
                // 这个台的线路全都不行，别再耗下去，把状态清干净等用户换台
                android.util.Log.w("IPTVSTALL", "give up ch=" + c.name
                        + " after " + startupFailures + " tries");
                startupFailures = 0;
                loading.setVisibility(View.GONE);
                ui.removeCallbacks(startupWatchdog);
                showHint("该频道暂时无法播放，请换个台");
                return;
            }
            retryRound = 0;
            repickSource();
        }
    };

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
                if (chunkSkips >= STARVED_SKIP_LIMIT
                        && current >= 0 && current < channels.size()) {
                    Channel.Source s = channels.get(current).source(sourceIndex);
                    s.starved = Boolean.TRUE;
                    android.util.Log.w("IPTVLOAD", "starved url=" + s.url);
                    chunkSkips = 0;
                    lastChunkSeq = seq;
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
            // rendered 每秒应约等于内容的帧率（本机是 25fps 的 576i），
            // dropped/skipped 才是真正被丢掉的帧。三者都正常却仍看着不顺，
            // 就说明问题在显示侧（25fps 输出到 60Hz 面板的 3:2 下拉），
            // 不是解码/网络能修的。
            String frames = "-";
            androidx.media3.exoplayer.DecoderCounters vc = player.getVideoDecoderCounters();
            if (vc != null) {
                vc.ensureUpdated();
                long now = System.currentTimeMillis();
                long dt = lastFrameAt == 0 ? 0 : Math.max(1, now - lastFrameAt);
                int df = lastFrameCount < 0 ? 0 : vc.renderedOutputBufferCount - lastFrameCount;
                double fps = dt > 0 ? df * 1000.0 / dt : 0;
                // 帧释放迟到量：渲染器本该在某时刻送出这一帧，实际晚了多少。
                // 稳定在毫秒级说明排期正常；一旦周期性冲到几十毫秒，
                // 就是"看着一顿一顿"的直接证据（与平均帧率无关，平均值会掩盖它）。
                double lateMs = vc.videoFrameProcessingOffsetCount > 0
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
                        + " late=" + String.format(Locale.US, "%.1f", lateMs) + "ms"
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
        infoBar = findViewById(R.id.infoBar);
        infoLogo = findViewById(R.id.infoLogo);
        infoName = findViewById(R.id.infoName);
        infoQualityRow = findViewById(R.id.infoQualityRow);
        infoScroll = findViewById(R.id.infoScroll);
        nowName = findViewById(R.id.nowName);
        nowMeta = findViewById(R.id.nowMeta);
        clock = findViewById(R.id.clock);
        hint = findViewById(R.id.hint);
        drawerSub = findViewById(R.id.drawerSub);
        emptyTip = findViewById(R.id.emptyTip);
        list = findViewById(R.id.list);
        groupList = findViewById(R.id.groupList);
        previewCard = findViewById(R.id.previewCard);
        previewLogo = findViewById(R.id.previewLogo);
        previewName = findViewById(R.id.previewName);
        previewMeta = findViewById(R.id.previewMeta);

        // 左栏分组导航
        groupList.setLayoutManager(new LinearLayoutManager(this));
        groupAdapter = new GroupAdapter(groupNames, this::onGroupPicked);
        groupList.setAdapter(groupAdapter);

        // 右栏频道列表
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ChannelAdapter(channels, this::select);
        adapter.setOnLongPress(this::showChannelMenu);
        list.setAdapter(adapter);
        // 关掉条目动画：高亮切换的动画会触发 relayout，把滚动位置顶回原处
        list.setItemAnimator(null);
        // 让列表首尾留白，第一行不被标题压住，最后一行不被屏幕边缘切掉
        list.setClipToPadding(true);
        list.setPadding(UiScale.pxW(12), UiScale.pxH(26), UiScale.pxW(12), UiScale.pxH(24));
        setupFocusPreview();

        findViewById(R.id.btnSettings).setOnClickListener(v -> showSettings());
        findViewById(R.id.btnClose).setOnClickListener(v -> closeDrawer());
        findViewById(R.id.btnDrawerSettings).setOnClickListener(v -> showSettings());
        applyScaleToViews();
        setupGestures();

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
                        if (current >= 0 && current < channels.size()) {
                            Channel c = channels.get(current);
                            SourceMemory.put(MainActivity.this, c.name, c.url(sourceIndex));
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
            refreshIfStale();
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

        // 底部信息条
        UiScale.padding(infoBar, 34, 16, 34, 16);
        // 台标已归一化成 2:1；底板四边留等宽间隙，图标才能水平垂直都居中
        int logoH = UiScale.pxH(96);
        int logoW = logoH * 2;
        int logoPad = UiScale.pxH(8);
        View logoBox = findViewById(R.id.infoLogoBox);
        ViewGroup.LayoutParams boxLp = logoBox.getLayoutParams();
        boxLp.width = logoW + logoPad * 2;
        boxLp.height = logoH + logoPad * 2;
        logoBox.setLayoutParams(boxLp);
        ViewGroup.LayoutParams logoLp = infoLogo.getLayoutParams();
        logoLp.width = logoW;
        logoLp.height = logoH;
        infoLogo.setLayoutParams(logoLp);
        // 底板撑满容器，图标靠 layout_gravity=center 停在内框正中
        View plateV = findViewById(R.id.infoLogoPlate);
        ViewGroup.LayoutParams plp = plateV.getLayoutParams();
        plp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        plp.height = ViewGroup.LayoutParams.MATCH_PARENT;
        plateV.setLayoutParams(plp);

        infoName.setMaxWidth(UiScale.pctW(26));
        UiScale.text(infoName, 36);
        TextView infoGroup = infoGroupView();
        if (infoGroup != null) {
            UiScale.text(infoGroup, 19);
            UiScale.margin(infoGroup, 0, 3, 0, 0);
        }
        View nameBlock = findViewById(R.id.infoName);
        if (nameBlock != null && nameBlock.getParent() instanceof View) {
            UiScale.margin((View) nameBlock.getParent(), 22, 0, 0, 0);
        }
        View divider = findViewById(R.id.infoDivider);
        if (divider != null) {
            UiScale.margin(divider, 26, 8, 24, 8);
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

        // 预览卡：字号和台标尺寸都按屏幕高度走，大屏上不会缩成一小块
        UiScale.padding(previewCard, 26, 22, 34, 22);
        ViewGroup.LayoutParams plp2 = previewLogo.getLayoutParams();
        int pLogoH = UiScale.pxH(60);
        plp2.width = pLogoH * 2;
        plp2.height = pLogoH;
        previewLogo.setLayoutParams(plp2);
        UiScale.margin(previewLogo, 0, 0, 22, 0);
        UiScale.text(previewName, 30);
        UiScale.text(previewMeta, 18);
        ViewGroup.MarginLayoutParams cardLp =
                (ViewGroup.MarginLayoutParams) previewCard.getLayoutParams();
        cardLp.rightMargin = UiScale.pxW(56);
        cardLp.bottomMargin = UiScale.pxH(56);
        previewCard.setLayoutParams(cardLp);
    }

    /** infoGroup 是可选视图，取不到时不影响其他适配。 */
    private TextView infoGroupView() {
        View v = findViewById(R.id.infoGroup);
        return v instanceof TextView ? (TextView) v : null;
    }

    /* ---------------- 抽屉内焦点预览 ---------------- */

    /**
     * 焦点在频道列表上移动时，实时预览目标频道。
     *
     * <p>用户用遥控器在列表里上下走，往往要看清"这一行是哪个台"才敢按确定。
     * 光靠行内文字不够直观，所以在画面右下浮一张卡，显示台标 + 名称 + 分组。
     * 焦点离开列表就收起，真正切台后也收起，不干扰观看。
     */
    private void setupFocusPreview() {
        list.addOnChildAttachStateChangeListener(
                new RecyclerView.OnChildAttachStateChangeListener() {
                    @Override
                    public void onChildViewAttachedToWindow(@NonNull View view) {
                        // 新行滚进可视区时，如果它正好拿到焦点就补一次预览
                        if (view.hasFocus()) {
                            previewFor(view);
                        }
                    }

                    @Override
                    public void onChildViewDetachedFromWindow(@NonNull View view) {
                    }
                });
        // 用焦点变化广播而不是给每行设监听：行是复用的，逐行挂容易漏
        list.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        list.getViewTreeObserver().addOnGlobalFocusChangeListener((oldFocus, newFocus) -> {
            if (newFocus != null && isInside(newFocus, list)) {
                previewFor(newFocus);
            } else {
                hidePreviewCard();
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

    /** 按聚焦的行渲染预览卡。 */
    private void previewFor(View row) {
        int pos = list.getChildAdapterPosition(row);
        if (pos < 0 || pos >= channels.size()) {
            hidePreviewCard();
            return;
        }
        Channel c = channels.get(pos);
        renderPreviewCard(c);
    }

    private void renderPreviewCard(Channel c) {
        // 正在播放的台不用预览——用户已经在看了，浮一张卡只是噪音
        if (c.name.equals(playingName) && drawer.getVisibility() == View.VISIBLE) {
            hidePreviewCard();
            return;
        }
        previewName.setText(c.name);
        String group = ChannelGroups.displayName(c.group);
        // 顺手告诉用户这个台有几条线路，换台前心里有数
        int n = c.sources.size();
        previewMeta.setText(group + (n > 1 ? " · " + n + " 条线路" : ""));
        android.graphics.Bitmap bmp = ChannelAdapter.LogoCache.get(this, c.logoKey());
        if (bmp != null) {
            previewLogo.setImageBitmap(bmp);
            previewLogo.setVisibility(View.VISIBLE);
        } else {
            previewLogo.setImageDrawable(null);
            previewLogo.setVisibility(View.GONE);
        }
        if (previewCard.getVisibility() != View.VISIBLE) {
            previewCard.setVisibility(View.VISIBLE);
            previewCard.setAlpha(0f);
            previewCard.animate().alpha(1f).setDuration(120).start();
        }
    }

    private void hidePreviewCard() {
        if (previewCard.getVisibility() == View.VISIBLE) {
            previewCard.animate().cancel();
            previewCard.setVisibility(View.GONE);
        }
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
        // 遥控器把焦点顺到频道列表，省一次按键
        if (!isTouchMode() && !channels.isEmpty()) {
            list.requestFocus();
            ui.postDelayed(() -> {
                View target = list.getLayoutManager() == null ? null
                        : list.getLayoutManager().findViewByPosition(Math.max(0, current));
                if (target != null) {
                    target.requestFocus();
                }
            }, 60);
        }
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
        current = indexOfName(playingName);
        adapter.setCurrent(current);
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
                if (drawer.getVisibility() == View.VISIBLE) {
                    closeDrawer();
                } else {
                    openDrawer();
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
                    // 上滑下一个频道，下滑上一个
                    stepChannel(dy < 0 ? current + 1 : current - 1);
                    return true;
                }
                if (Math.abs(dx) > Math.abs(dy) && Math.abs(dx) > 80) {
                    if (drawer.getVisibility() == View.VISIBLE && dx < 0) {
                        closeDrawer();
                        return true;
                    }
                    toggleAspect();
                    return true;
                }
                return false;
            }
        });

        View layer = findViewById(R.id.gestureLayer);
        layer.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                showBar();
                if (drawer.getVisibility() != View.VISIBLE) {
                    showInfoBar();
                }
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

        // 名字和图标立刻显示，不让用户对着黑屏等探测
        nowName.setText(c.name);
        nowMeta.setText(ChannelGroups.displayName(c.group));
        renderInfoBar(c);
        adapter.setCurrent(index);
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
        int remembered = indexOfUrl(c, SourceMemory.get(this, c.name));
        if (remembered >= 0 && !Boolean.TRUE.equals(c.sources.get(remembered).starved)) {
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
                loading.setVisibility(View.GONE);
                return;
            }
            sourceIndex = picked;
            startPlayback(c);
        });
    }

    /**
     * 按节目单的 response-time 挑最快的未标记线路。
     *
     * <p>解析时已按响应耗时升序排过，所以顺序扫第一个可用的即可。
     * 全部被标记喂不饱时返回 -1，交给上层阻塞探测（它会清标记重试）。
     */
    private static int quickestSource(Channel c) {
        for (int i = 0; i < c.sources.size(); i++) {
            if (!Boolean.TRUE.equals(c.sources.get(i).starved)) {
                return i;
            }
        }
        return -1;
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
            if (token != playToken || current < 0 || current >= channels.size()) {
                return;
            }
            if (better < 0 || better == playing) {
                return;
            }
            // 正在播放的线路已经换了就不要用这份结果覆盖
            if (!playingUrl.equals(channels.get(current).url(sourceIndex))) {
                return;
            }
            android.util.Log.i("IPTVPLAY", "refine " + playing + " -> " + better + " " + c.name);
            sourceIndex = better;
            renderInfoBar(c);
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
            renderInfoBar(c);
            return;
        }
        // 换了一条新流：清掉视频渲染器的显示时间基准，
        // 否则新流 PTS 从头开始，会被判成"重复帧"整段丢掉（换台后长时间不动）
        PlaybackTuning.resetStreamState();
        // 新管线的时间轴也是全新的，静默卡死检测必须重新起算，
        // 否则换源瞬间的位置落差会被当成长时间停滞
        stallDetector.reset();
        // 帧率采样也重新起算，否则换源那一跳会被算成一次帧率突变
        lastFrameAt = 0L;
        lastFrameCount = -1;
        lastAudioCount = -1;
        player.setMediaItem(PlaybackTuning.liveItem(c.url(sourceIndex)));
        player.prepare();
        player.setPlayWhenReady(true);
        renderInfoBar(c);
        // 每次起播都重新计时：超时还没出画面就说明这条线路喂不饱
        armStartupWatchdog();
    }

    /** 底部信息条：logo + 频道名 + 分辨率胶囊，点胶囊即换到该清晰度的线路。 */
    private void renderInfoBar(Channel c) {
        infoName.setText(c.name);
        TextView infoGroup = infoGroupView();
        if (infoGroup != null) {
            infoGroup.setText(ChannelGroups.displayName(c.group));
        }
        android.graphics.Bitmap bmp = ChannelAdapter.LogoCache.get(this, c.logoKey());
        if (bmp != null) {
            infoLogo.setImageBitmap(bmp);
            infoLogo.setVisibility(View.VISIBLE);
        } else {
            infoLogo.setImageDrawable(null);
            infoLogo.setVisibility(View.GONE);
        }

        infoQualityRow.removeAllViews();
        String currentLabel = c.source(sourceIndex).label();

        // 已知分辨率去重后逐个列出；还没测出分辨率的线路折叠成一个"更多线路"
        Set<String> used = new LinkedHashSet<>();
        int firstUnknown = -1;
        int unknownCount = 0;
        for (int i = 0; i < c.sources.size(); i++) {
            Channel.Source s = c.sources.get(i);
            if (s.quality == null || s.quality.isEmpty()) {
                if (firstUnknown < 0) {
                    firstUnknown = i;
                }
                unknownCount++;
                continue;
            }
            if (!used.add(s.quality)) {
                continue;
            }
            addQualityPill(s.quality, i, s.quality.equals(currentLabel));
        }
        boolean onUnknown = c.source(sourceIndex).quality == null
                || c.source(sourceIndex).quality.isEmpty();
        if (firstUnknown >= 0) {
            // 未知线路没有区分信息，横排一行只会变成噪音
            int target = onUnknown ? nextUnknown(c, sourceIndex) : firstUnknown;
            addQualityPill("更多线路" + (unknownCount > 1 ? " " + unknownCount : ""),
                    target, onUnknown);
        }
        // 信息条常驻会挡住画面，渲染完就重新计时，5 秒后自动下滑
        showInfoBar();
    }

    /** 找下一个清晰度未知的线路，用来在"更多线路"上循环。 */
    private static int nextUnknown(Channel c, int from) {
        for (int k = 1; k <= c.sources.size(); k++) {
            int i = (from + k) % c.sources.size();
            Channel.Source s = c.sources.get(i);
            if (s.quality == null || s.quality.isEmpty()) {
                return i;
            }
        }
        return from;
    }

    /** 信息条上的一个分辨率胶囊，尺寸随屏幕高度缩放。 */
    private void addQualityPill(String text, int index, boolean active) {
        TextView pill = new TextView(this);
        pill.setText(text);
        UiScale.text(pill, 30);
        pill.setTextColor(0xFFFFFFFF);
        pill.setTypeface(pill.getTypeface(), android.graphics.Typeface.BOLD);
        pill.setGravity(android.view.Gravity.CENTER);
        pill.setBackgroundResource(R.drawable.quality_pill);
        int px = UiScale.pxW(30);
        int py = UiScale.pxH(13);
        pill.setPadding(px, py, px, py);
        pill.setSelected(active);
        pill.setClickable(true);
        pill.setFocusable(true);
        pill.setOnClickListener(v -> switchQuality(index));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = UiScale.pxW(16);
        infoQualityRow.addView(pill, lp);
    }

    /** 记下这路流实际输出的分辨率，下次显示真实清晰度。 */
    private void rememberRealQuality(int height) {
        String label = QualityMemory.labelOf(height);
        if (label == null || current < 0 || current >= channels.size()) {
            return;
        }
        Channel c = channels.get(current);
        if (c.sources.isEmpty()) {
            return;
        }
        Channel.Source s = c.source(sourceIndex);
        if (label.equals(s.quality)) {
            return;
        }
        s.quality = label;
        QualityMemory.put(this, s.url, label);
        renderInfoBar(c);
    }

    /** 焦点从分辨率胶囊收回播放画面，遥控器换台才能继续生效。 */
    private void hideQualitySheet() {
        findViewById(R.id.root).requestFocus();
    }

    /** 切换清晰度：用户明确点了某一路，就照播那一路，不再自动挑。 */
    private void switchQuality(int index) {
        if (current < 0 || current >= channels.size()) {
            return;
        }
        Channel c = channels.get(current);
        if (index < 0 || index >= c.sources.size()) {
            return;
        }
        sourceIndex = index;
        retryRound = 0;
        hasPlayed = false;
        rebufferStart = 0;
        loading.setVisibility(View.VISIBLE);
        speed.reset();
        LiveSpeedMeter.INSTANCE.reset();
        // 重新渲染信息条让选中态跟上用户的选择
        renderInfoBar(c);
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
            if (player == null || current < 0 || current >= channels.size()) {
                return;
            }
            int err = player.getPlayerError() == null ? 0 : player.getPlayerError().errorCode;
            if (err == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                // 窗口滑走只能回到直播边缘，重试同一地址即可
                retryRound = 0;
                startPlayback(channels.get(current));
                return;
            }
            // 其余错误多给几轮机会，节目单里的线路时常是短暂抽风
            if (retryRound < MAX_RETRY_ROUNDS) {
                retryRound++;
                startPlayback(channels.get(current));
                return;
            }
            // 原地重试都失败了，这条线路确实不可用。交给探测重新挑一条，
            // 而不是简单换下一条——节目单里连不上的线路很多，撞运气会黑屏很久
            retryRound = 0;
            repickSource();
        }
    };

    /** 当前线路彻底失败后重新探测换线，跳过已经失败的那一路。 */
    private void repickSource() {
        final Channel c = channels.get(current);
        final int failed = sourceIndex;
        final int index = current;
        // 失败线路不能再当"记忆线路"，否则下次换台还会先踩一次坑
        SourceMemory.remove(this, c.name);
        loading.setVisibility(View.VISIBLE);
        speed.reset();
        LiveSpeedMeter.INSTANCE.reset();
        SourceProber.pickExcluding(this, c, -1, failed, picked -> {
            if (current != index) {
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
        closeDrawer();
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
        new AlertDialog.Builder(this)
                .setTitle("设置")
                .setItems(items, (d, which) -> {
                    if (which == 0) {
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

    /** 缓存过期就静默更新，成功后用新列表刷新界面。 */
    private void refreshIfStale() {
        if (!store.isStale()) {
            return;
        }
        final String keep = playingName;
        new Thread(() -> {
            try {
                final int n = store.refresh();
                ui.post(() -> reloadKeeping(keep));
            } catch (Exception e) {
                // 静默失败：本地列表本来就能用，不该用"更新失败"吓用户
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
     * 把当前频道顶到列表首行，上方不留半截行。
     * 必须等下一次布局完成，否则位置按旧数据算，会滚偏。
     */
    private void scrollCurrentIntoComfort(int position) {
        list.post(() -> {
            RecyclerView.LayoutManager m = list.getLayoutManager();
            if (m instanceof LinearLayoutManager) {
                ((LinearLayoutManager) m).scrollToPositionWithOffset(position, 0);
            } else {
                list.scrollToPosition(position);
            }
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

    private void openDrawer() {
        hideQualitySheet();
        hideInfoBarNow();
        // 抽屉封顶到屏幕顶端，顶栏让位避免文字叠在一起
        topBar.setVisibility(View.GONE);
        ui.removeCallbacks(hideBar);
        drawer.setVisibility(View.VISIBLE);
        adapter.setCurrent(current);
        groupAdapter.setCurrent(currentGroup);
        if (current >= 0) {
            scrollCurrentIntoComfort(current);
        }
        final boolean touch = isTouchMode();
        // 遥控器把焦点落到当前频道；触摸屏不抢焦点，避免出现无用的高亮
        if (!touch) {
            list.requestFocus();
            ui.postDelayed(() -> {
                View target = list.getLayoutManager() == null ? null
                        : list.getLayoutManager().findViewByPosition(Math.max(0, current));
                if (target != null) {
                    target.requestFocus();
                }
                // 焦点系统会把行再滚一次以留出余量，这里覆盖回去，
                // 让当前行稳稳对齐列表首行，上方不残留半截行
                ui.postDelayed(() -> {
                    if (current >= 0) {
                        scrollCurrentIntoComfort(current);
                    }
                }, 80);
            }, 50);
        }
        showBar();
    }

    /** 有触摸能力且最近没有按键输入时按触摸屏处理。 */
    private boolean isTouchMode() {
        return getPackageManager().hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
                && System.currentTimeMillis() - lastKeyAt > 3000;
    }

    private void closeDrawer() {
        drawer.setVisibility(View.GONE);
        hidePreviewCard();
        findViewById(R.id.root).requestFocus();
        showInfoBar();
        showBar();
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

    /** 提示浮层不参与布局，自己让开信息条：条可见时抬到它上沿之上。 */
    private void placeHint() {
        ViewGroup.LayoutParams lp = hint.getLayoutParams();
        if (!(lp instanceof ViewGroup.MarginLayoutParams)) {
            return;
        }
        ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
        int bar = infoBar.getVisibility() == View.VISIBLE ? infoBar.getHeight() : 0;
        int target = UiScale.pxH(18) + bar;
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

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        boolean drawerOpen = drawer.getVisibility() == View.VISIBLE;
        lastKeyAt = System.currentTimeMillis();
        // 任何遥控器操作都重新亮出信息条，静置 5 秒后再自动收起；
        // BACK 例外，它是要把界面收干净的
        if (!drawerOpen && keyCode != KeyEvent.KEYCODE_BACK) {
            showInfoBar();
        }

        // 数字键直接换台，1.5 秒内连续输入有效
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9 && !drawerOpen) {
            ui.removeCallbacks(clearNumber);
            ui.postDelayed(clearNumber, NUMBER_WINDOW_MS);
            if (numberBuf.length() >= 3) {
                numberBuf.setLength(0);
            }
            numberBuf.append((char) ('0' + (keyCode - KeyEvent.KEYCODE_0)));
            showHint("跳转到 " + numberBuf);
            int n = Integer.parseInt(numberBuf.toString());
            if (n >= 1 && n <= channels.size()) {
                stepChannel(n - 1);
            }
            return true;
        }

        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (inInfoBar()) {
                    // 有焦点时交给胶囊自己处理点击
                    return super.onKeyDown(keyCode, event);
                }
                if (!drawerOpen) {
                    openDrawer();
                    return true;
                }
                return super.onKeyDown(keyCode, event);

            case KeyEvent.KEYCODE_DPAD_UP:
                if (inInfoBar()) {
                    hideQualitySheet();
                    return true;
                }
                if (!drawerOpen) {
                    stepChannel(current - 1);
                    return true;
                }
                return super.onKeyDown(keyCode, event);

            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (!drawerOpen) {
                    // 焦点下移到分辨率胶囊；已在胶囊上则由焦点系统继续横移
                    if (inInfoBar()) {
                        return super.onKeyDown(keyCode, event);
                    }
                    if (infoBar.getVisibility() == View.VISIBLE
                            && infoQualityRow.getChildCount() > 0) {
                        int idx = Math.min(sourceIndex, infoQualityRow.getChildCount() - 1);
                        infoQualityRow.getChildAt(idx).requestFocus();
                    }
                    return true;
                }
                return super.onKeyDown(keyCode, event);

            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_PAGE_UP:
                if (!drawerOpen) {
                    stepChannel(current + 1);
                    return true;
                }
                return super.onKeyDown(keyCode, event);

            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_PAGE_DOWN:
                if (!drawerOpen) {
                    stepChannel(current - 1);
                    return true;
                }
                return super.onKeyDown(keyCode, event);

            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (drawerOpen) {
                    // 抽屉内交给焦点导航：右栏向左可回到分组栏
                    return super.onKeyDown(keyCode, event);
                }
                if (inInfoBar()) {
                    // 焦点在分辨率胶囊上，交给焦点系统横向移动
                    return super.onKeyDown(keyCode, event);
                }
                stepChannel(current - 1);
                return true;

            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (drawerOpen || inInfoBar()) {
                    return super.onKeyDown(keyCode, event);
                }
                stepChannel(current + 1);
                return true;

            case KeyEvent.KEYCODE_BACK:
                // 返回键逐层退出：先收底栏，再关抽屉，最后才交回系统
                if (infoBar.getVisibility() == View.VISIBLE) {
                    hideInfoBarNow();
                    return true;
                }
                if (inInfoBar()) {
                    hideQualitySheet();
                    return true;
                }
                if (drawerOpen) {
                    closeDrawer();
                    return true;
                }
                return super.onKeyDown(keyCode, event);

            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_INFO:
                showBar();
                openDrawer();
                return true;

            case KeyEvent.KEYCODE_Z:
                toggleAspect();
                return true;

            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    /** 遥控器焦点是否落在底部信息条的分辨率胶囊上。 */
    private boolean inInfoBar() {
        View f = getCurrentFocus();
        while (f != null) {
            if (f == infoBar) {
                return true;
            }
            if (!(f.getParent() instanceof View)) {
                return false;
            }
            f = (View) f.getParent();
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
        hideQualitySheet();
        dismissHint();
        play(next);
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
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }
}
