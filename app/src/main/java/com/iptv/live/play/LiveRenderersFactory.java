package com.iptv.live.play;

import android.content.Context;
import android.os.Handler;

import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.video.VideoRendererEventListener;

import java.util.ArrayList;

/**
 * 把视频渲染器换成 {@link LiveVideoRenderer}，其余保持默认。
 *
 * <p>只在视频侧做分片重叠帧的剔除；音频、字幕等仍然走官方实现。
 */
@UnstableApi
public final class LiveRenderersFactory extends DefaultRenderersFactory {

    public LiveRenderersFactory(Context context) {
        super(context);
    }

    @Override
    protected void buildVideoRenderers(
            Context context,
            @ExtensionRendererMode int extensionRendererMode,
            MediaCodecSelector mediaCodecSelector,
            boolean enableDecoderFallback,
            @Nullable Handler eventHandler,
            @Nullable VideoRendererEventListener eventListener,
            long allowedVideoJoiningTimeMs,
            ArrayList<Renderer> out) {
        // 扩展渲染器模式本来就是 OFF，这里直接放自己的实现即可
        LiveVideoRenderer renderer = new LiveVideoRenderer(
                context,
                mediaCodecSelector,
                allowedVideoJoiningTimeMs,
                enableDecoderFallback,
                eventHandler,
                eventListener,
                MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY);
        // 登记实例，换流时由 PlaybackTuning.resetStreamState() 清显示时间基准
        PlaybackTuning.setLastRenderer(renderer);
        out.add(renderer);
    }
}
