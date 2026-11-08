package com.iptv.live.play;

import android.content.Context;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
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
     * 直播同步允许的调速范围：±3%。
     *
     * <p>这是 media3 的默认值，也是直播播放器的通行做法。留出这点余量后，
     * 延迟漂移可以被"极轻微的变速"慢慢收敛，而不是攒到上限后一次性跳转——
     * 后者正是画面上能看见的"突然跳一下"。3% 的幅度人眼无法分辨。
     */
    private static final float SPEED_MIN = 0.97f;
    private static final float SPEED_MAX = 1.03f;

    /**
     * 缓冲参数的唯一权威。
     *
     * <p>原来这里写死一套数字（起播 4 秒、落后直播 10 秒）。写死只对某一类线路成立：
     * 守得太紧，码率稍高就播十几秒后缓冲耗尽、进一次 {@code STATE_BUFFERING}，
     * 用户看到的就是<b>缓冲圈一直在闪</b>；守得太松，网络明明很好也要干等。
     * 现在交给 {@link BufferGovernor} 按实测证据逐级调整。
     */
    @Nullable
    private static volatile BufferGovernor governor;

    /** 最近创建的渲染器，换流时要通知它丢掉上一路的显示时间基准。 */
    @Nullable
    private static volatile LiveVideoRenderer lastRenderer;

    private PlaybackTuning() {
    }

    /** 当前生效的缓冲管家；还没创建播放器时返回 {@code null}。 */
    @Nullable
    public static BufferGovernor governor() {
        return governor;
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

        governor = new BufferGovernor(ctx);

        return new ExoPlayer.Builder(ctx, renderers)
                .setTrackSelector(selector)
                // 网络栈换成"带磁盘缓存"的版本：来回切台、播放器抖动重开同一条
                // 线路时，分片直接从磁盘读，不必重新下载
                .setMediaSourceFactory(new DefaultMediaSourceFactory(PlaybackCache.factory(ctx))
                        .setLoadErrorHandlingPolicy(new FastFailPolicy()))
                .setLoadControl(governor)
                .build();
    }

    /**
     * 换流时清掉视频渲染器的基准，并让缓冲管家重新起算短窗口。
     *
     * <p>{@link LiveVideoRenderer} 用"已播最大 PTS"过滤重复帧，这个基准是按
     * <b>单条流</b>的时间轴建立的。换到另一个频道后 PTS 会重新开始，
     * 若不清基准，新流的每一帧都会被判成"见过"而丢弃，画面要冻到兜底阈值
     * （60 秒）才恢复——这正是换台后长时间黑屏/卡死的来由。
     *
     * <p>缓冲管家的"闪缓冲"短窗口也要清：新台起播攒缓冲是正常流程，
     * 算进旧台的账会让刚换台就升档，延迟白白变大。
     * 每路新流准备播放时调用一次。
     */
    public static void resetStreamState() {
        LiveVideoRenderer r = lastRenderer;
        if (r != null) {
            r.resetForNewStream();
        }
        BufferGovernor g = governor;
        if (g != null) {
            g.onStreamChanged();
        }
    }

    /** 供渲染器工厂登记自己创建的实例。 */
    static void setLastRenderer(LiveVideoRenderer renderer) {
        lastRenderer = renderer;
    }

    /**
     * 直播流参数：偏移量夹在窗口内的窄区间。
     *
     * <p>偏移上下限决定什么时候往回退。速度区间<b>不再钉死在 1.0</b>：
     * 固定 1.0 意味着播放器完全没有调速余量，一旦直播延迟漂到上限，
     * 只能靠一次<b>跳转</b>把位置拽回来，观感就是画面"突然跳一下"。
     * 放到 ±3%（media3 的默认值，也是各家直播播放器的通行做法）后，
     * 它会用极轻微的加速/减速把延迟慢慢收敛到目标值，人眼完全看不出。
     *
     * <p><b>目标偏移和缓冲上限都由 {@link BufferGovernor} 给</b>：
     * 它们必须联动——直播能缓冲多深取决于"落后直播多少"（数据到直播边缘就没了），
     * 只加厚缓冲而不放大偏移，等于把 {@code maxBuffer} 调到一堵够不着的墙，
     * 播放器会不停"缓满/追直播"。两者的上下限都在 25 秒窗口内，
     * 不会因反复越界而跳动。
     */
    public static MediaItem liveItem(String url) {
        BufferGovernor g = governor;
        int offset = g != null ? g.offsetMs() : 10_000;
        // 上限收到偏移之上一点点、下限在偏移之下：区间太宽会让位置在窗口里
        // 大幅滑来滑去，太窄则每次都要靠跳转纠正。这里保持 ±2 秒的窄区间。
        int min = Math.max(3_000, offset - 2_000);
        int max = Math.min(20_000, offset + 2_000);
        return new MediaItem.Builder()
                .setUri(url)
                .setLiveConfiguration(new MediaItem.LiveConfiguration.Builder()
                        .setTargetOffsetMs(offset)
                        .setMinOffsetMs(min)
                        .setMaxOffsetMs(max)
                        .setMinPlaybackSpeed(SPEED_MIN)
                        .setMaxPlaybackSpeed(SPEED_MAX)
                        .build())
                .build();
    }
}
