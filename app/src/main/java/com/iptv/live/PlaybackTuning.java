package com.iptv.live;

import android.content.Context;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;

/**
 * 播放器参数：解码优先走硬件，缓冲按直播调优，网络失败快速超时。
 */
@UnstableApi
public final class PlaybackTuning {

    /**
     * 缓冲区上限：两个半分片。
     *
     * <p>关键约束：直播能缓冲到的最长长度就是{@link #TARGET_OFFSET_MS}，
     * 因为数据到直播边缘为止。所以要缓冲更深，只能让位置离直播更远。
     * 上限必须明显小于目标偏移（否则反复"缓满/追直播"），
     * 也必须能装下两个 5 秒分片——只装得下一片时，
     * 每片下完正好播空，表现为每分钟固定缓冲一次。
     */
    private static final int BUFFER_MAX_MS = 14_000;
    /**
     * 目标落后直播 10 秒。
     *
     * <p>直播窗口是 5 片 × 5 秒 = 25 秒。落后 10 秒意味着离窗口左边界还有 15 秒，
     * 既留足抗抖动余量（能攒两片以上），又不会滑出窗口。
     * <b>这个值绝不能接近窗口长度</b>：一旦越过左边界，HLS 再也拿不到数据，
     * 播放器会安静地停在 STATE_BUFFERING、位置持续往负方向增长，永远恢复不了。
     *
     * <p>从 12 秒收到 10 秒：越靠近直播边缘，起播时要补的历史分片越少，
     * 首帧更快；同时观众看到的延迟也更小。
     */
    private static final int TARGET_OFFSET_MS = 10_000;
    /** 允许的偏移下限；低于它就往直播边缘靠，但不会退回窗口外。 */
    private static final int MIN_OFFSET_MS = 7_000;
    /**
     * 偏移上限：超过它就跳到目标位置。
     * 必须大于缓冲上限，否则反复触发追直播；更要远小于窗口（25 秒），
     * 否则一跳就越过左边界，直接变成永久缓冲。
     */
    private static final int MAX_OFFSET_MS = 14_000;
    /**
     * 起播门槛。
     *
     * <p>原来等到 6 秒（一个完整分片）才开播，换台时光这一段空等就有明显体感。
     * 这里降到 4 秒：TS 是流式可解析的，缓冲量随字节到达增长，
     * 攒到 4 秒已经能稳定出画面，剩下的分片在播放中补齐。
     * 再低就会"开头那点数据播完等下个分片"，反而出现起播即卡。
     */
    private static final int BUFFER_FOR_PLAYBACK_MS = 4_000;
    /**
     * 重缓冲后的起播门槛，比首次起播略高。
     * 卡过一次说明网络有抖动，多攒一点再恢复，避免"开播—又卡"的死循环。
     */
    private static final int BUFFER_FOR_REBUFFER_MS = 5_000;
    /** 最小缓冲量，必须 ≥ 起播门槛。 */
    private static final int BUFFER_MIN_MS = 8_000;

    /**
     * 直播同步允许的调速范围：±3%。
     *
     * <p>这是 media3 的默认值，也是直播播放器的通行做法。留出这点余量后，
     * 延迟漂移可以被"极轻微的变速"慢慢收敛，而不是攒到上限后一次性跳转——
     * 后者正是画面上能看见的"突然跳一下"。3% 的幅度人眼无法分辨。
     */
    private static final float SPEED_MIN = 0.97f;
    private static final float SPEED_MAX = 1.03f;

    /** 最近创建的渲染器，换流时要通知它丢掉上一路的显示时间基准。 */
    @Nullable
    private static volatile LiveVideoRenderer lastRenderer;

    private PlaybackTuning() {
    }

    public static ExoPlayer create(Context ctx) {
        // 硬件解码优先，硬解不支持时才回退软解；视频渲染器换成直播专用版本
        LiveRenderersFactory renderers = new LiveRenderersFactory(ctx);
        renderers.setEnableDecoderFallback(true)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF);

        DefaultTrackSelector selector = new DefaultTrackSelector(ctx);
        selector.setParameters(new DefaultTrackSelector.Parameters.Builder(ctx)
                // 电视面板就那么大，不必解超过屏幕的码流
                .setViewportSizeToPhysicalDisplaySize(ctx, true)
                .setConstrainAudioChannelCountToDeviceCapabilities(true)
                .setAllowVideoMixedDecoderSupportAdaptiveness(true)
                // 本机没有 MP2 解码器。若仍把 MP2 音轨交给它，解码器会在
                // queueInputBuffer 处崩掉，整条流跟着失败；宁可先只要画面
                .setExceedRendererCapabilitiesIfNecessary(false)
                .build());

        return new ExoPlayer.Builder(ctx, renderers)
                .setTrackSelector(selector)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(httpFactory())
                        .setLoadErrorHandlingPolicy(new FastFailPolicy()))
                .setLoadControl(liveLoadControl())
                .build();
    }

    /**
     * 换流时清掉视频渲染器的显示时间基准。
     *
     * <p>{@link LiveVideoRenderer} 用"已播最大 PTS"过滤重复帧，这个基准是按
     * <b>单条流</b>的时间轴建立的。换到另一个频道后 PTS 会重新开始，
     * 若不清基准，新流的每一帧都会被判成"见过"而丢弃，画面要冻到兜底阈值
     * （60 秒）才恢复——这正是换台后长时间黑屏/卡死的来由。
     * 每路新流准备播放时调用一次。
     */
    public static void resetStreamState() {
        LiveVideoRenderer r = lastRenderer;
        if (r != null) {
            r.resetForNewStream();
        }
    }

    /** 供渲染器工厂登记自己创建的实例。 */
    static void setLastRenderer(LiveVideoRenderer renderer) {
        lastRenderer = renderer;
    }

    /**
     * 直播缓冲。节目单里的 HLS 普遍是 5 秒一个分片，
     * 起播门槛取 4 秒，既能出画面又不必空等一整片。
     */
    private static DefaultLoadControl liveLoadControl() {
        // 参数顺序是 (minBufferMs, maxBufferMs, bufferForPlaybackMs,
        // bufferForPlaybackAfterRebufferMs)，且必须满足
        // bufferForPlayback <= minBuffer <= maxBuffer，写反会直接构造失败。
        return new DefaultLoadControl.Builder()
                .setBufferDurationsMs(BUFFER_MIN_MS, BUFFER_MAX_MS,
                        BUFFER_FOR_PLAYBACK_MS, BUFFER_FOR_REBUFFER_MS)
                .setPrioritizeTimeOverSizeThresholds(true)
                .setBackBuffer(0, false)
                .build();
    }

    /** 频道地址多为小机房，超时设短一点，坏源能立刻跳过而不是干等。 */
    private static DefaultHttpDataSource.Factory httpFactory() {
        return new DefaultHttpDataSource.Factory()
                .setUserAgent("IptvLive/1.0")
                .setConnectTimeoutMs(5_000)
                .setReadTimeoutMs(8_000)
                .setAllowCrossProtocolRedirects(true)
                // 逐块回调，缓冲期间的网速才能实时更新
                .setTransferListener(LiveSpeedMeter.INSTANCE);
    }

    /**
     * 直播流参数：偏移量夹在窗口内的窄区间。
     *
     * <p>偏移上下限决定什么时候往回退。速度区间<b>不再钉死在 1.0</b>：
     * 固定 1.0 意味着播放器完全没有调速余量，一旦直播延迟漂到
     * {@link #MAX_OFFSET_MS}，只能靠一次<b>跳转</b>把位置拽回来，
     * 观感就是画面"突然跳一下"。放到 ±3%（media3 的默认值，
     * 也是各家直播播放器的通行做法）后，它会用极轻微的加速/减速
     * 把延迟慢慢收敛到 {@link #TARGET_OFFSET_MS}，人眼完全看不出。
     *
     * <p>曾经把上限开到 1.08，那个幅度是能明显看出来的"快进"，
     * 现在的 1.03 只有它的三分之一多一点，不会产生画面突变。
     */
    public static MediaItem liveItem(String url) {
        return new MediaItem.Builder()
                .setUri(url)
                .setLiveConfiguration(new MediaItem.LiveConfiguration.Builder()
                        .setTargetOffsetMs(TARGET_OFFSET_MS)
                        // 夹住偏移区间：太靠近直播就后退缓冲，落后太多就往直播靠，
                        // 上下限都在窗口内，不会因反复越界而跳动
                        .setMinOffsetMs(MIN_OFFSET_MS)
                        .setMaxOffsetMs(MAX_OFFSET_MS)
                        .setMinPlaybackSpeed(SPEED_MIN)
                        .setMaxPlaybackSpeed(SPEED_MAX)
                        .build())
                .build();
    }
}
