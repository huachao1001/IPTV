package com.iptv.live;

import android.content.Context;

import androidx.media3.common.MediaItem;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;

/**
 * 播放器参数：解码优先走硬件，缓冲按直播调优，网络失败快速超时。
 */
public final class PlaybackTuning {

    /**
     * 缓冲区上限：两个分片。
     *
     * <p>关键约束：直播能缓冲到的最长长度就是{@link #TARGET_OFFSET_MS}，
     * 因为数据到直播边缘为止。所以要缓冲更深，只能让位置离直播更远。
     * 上限必须明显小于目标偏移（否则反复"缓满/追直播"），
     * 也必须能装下两个 5 秒分片——只装得下一片时，
     * 每片下完正好播空，表现为每分钟固定缓冲一次。
     */
    private static final int BUFFER_MAX_MS = 10_000;
    /**
     * 目标落后直播 12 秒。
     *
     * <p>直播窗口是 5 片 × 5 秒 = 25 秒。落后 12 秒意味着离窗口左边界还有 13 秒，
     * 既留足抗抖动余量（能攒两片以上），又不会滑出窗口。
     * <b>这个值绝不能接近窗口长度</b>：一旦越过左边界，HLS 再也拿不到数据，
     * 播放器会安静地停在 STATE_BUFFERING、位置持续往负方向增长，永远恢复不了。
     */
    private static final int TARGET_OFFSET_MS = 12_000;
    /** 允许的偏移下限；低于它就往直播边缘靠，但不会退回窗口外。 */
    private static final int MIN_OFFSET_MS = 8_000;
    /**
     * 偏移上限：超过它就跳到目标位置。
     * 必须大于缓冲上限，否则反复触发追直播；更要远小于窗口（25 秒），
     * 否则一跳就越过左边界，直接变成永久缓冲。
     */
    private static final int MAX_OFFSET_MS = 16_000;

    private PlaybackTuning() {
    }

    public static ExoPlayer create(Context ctx) {
        // 硬件解码优先，硬解不支持时才回退软解；视频渲染器换成直播专用版本
        DefaultRenderersFactory renderers = new LiveRenderersFactory(ctx)
                .setEnableDecoderFallback(true)
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
     * 直播缓冲。
     * 节目单里的 HLS 普遍是 5 秒一个分片，起播门槛必须大于一个分片，
     * 否则边下边播，播完手里那点数据就得停下等下个分片，表现为周期性卡顿。
     */
    private static DefaultLoadControl liveLoadControl() {
        // DefaultLoadControl 要求 bufferForPlaybackMs <= minBufferMs <= maxBufferMs，
        // 三者写反会直接构造失败。
        //
        // 起播和重缓冲都要等 6 秒：一片就是 5 秒，门槛低于一片时，
        // 手里只有一个分片就开播，播完正好卡在下一次下载上。
        // LoadControl 会把门槛夹到 targetOffset/2（这里 6 秒），刚好对齐。
        return new DefaultLoadControl.Builder()
                .setBufferDurationsMs(6_000, BUFFER_MAX_MS, 6_000, 6_000)
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
     * 直播流参数：固定 1.0 倍速、偏移量夹在窗口内的窄区间。
     * HLS 默认会在缓冲富余时加速到 1.08 倍去追直播，观感就是画面"突然快进"；
     * 偏移上下限则决定什么时候往回退，两者都限制住才不会有画面突变。
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
                        .setMinPlaybackSpeed(1f)
                        .setMaxPlaybackSpeed(1f)
                        .build())
                .build();
    }
}
