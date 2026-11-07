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
            android.util.Log.i("IPTVPLAY", "state=" + player.getPlaybackState()
                    + " supp=" + player.getPlaybackSuppressionReason()
                    + " err=" + (player.getPlayerError() == null ? "-" : player.getPlayerError().errorCode)
                    + " buffered=" + player.getTotalBufferedDuration()
                    + " pos=" + player.getCurrentPosition()
                    + " speed=" + player.getPlaybackParameters().speed
                    + " vol=" + player.getVolume()
                    + " url=" + (player.getCurrentMediaItem() == null ? "-"
                            : player.getCurrentMediaItem().localConfiguration.uri)
                    + " audio[" + audio + "]");
        } catch (Throwable ignored) {
        }
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
                    // 真的掉帧才会让人看出"卡了一下"，重叠帧剔除不算在内
                    android.util.Log.i("IPTVVIDEO", "dropped=" + droppedFrames
                            + " elapsedMs=" + elapsedMs
                            + " buffered=" + player.getTotalBufferedDuration());
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
                        rebufferStart = 0;
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
    }

    /** infoGroup 是可选视图，取不到时不影响其他适配。 */
    private TextView infoGroupView() {
        View v = findViewById(R.id.infoGroup);
        return v instanceof TextView ? (TextView) v : null;
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

    /** 换台入口：先亮界面，再并发挑一路能用的源。 */
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
        final int want = sourceIndex;
        SourceProber.pick(this, c, want, picked -> {
            // 探测期间用户可能已经换台，旧结果直接丢弃
            if (current != index) {
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

    /** 真正起播：此时源已确认可达。预览模式下只刷新界面。 */
    private void startPlayback(Channel c) {
        if (player == null) {
            loading.setVisibility(View.GONE);
            renderInfoBar(c);
            return;
        }
        player.setMediaItem(PlaybackTuning.liveItem(c.url(sourceIndex)));
        player.prepare();
        player.setPlayWhenReady(true);
        renderInfoBar(c);
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
