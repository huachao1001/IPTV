package com.iptv.live;

import android.content.Context;
import android.os.Handler;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer;
import androidx.media3.exoplayer.video.VideoRendererEventListener;

import java.nio.ByteBuffer;

/**
 * 直播专用视频渲染器：显示时间严格单调，绝不重播已放过的画面。
 *
 * <p>节目单里的 HLS 按 GOP 切分片，新分片的 IDR 往往比上一片末尾的 B 帧还早，
 * 更糟的是部分机房会把整段分片重复下发。播放器按 PTS 排期，
 * 于是同一段内容会被放第二遍，观感就是"画面倒退、来回跳"。
 *
 * <p>判据是<b>高水位线</b>：只要这一帧的 PTS 不晚于已播过的最大 PTS，
 * 它的内容就一定见过，无条件丢弃，不设次数上限、不设回退幅度上限。
 * 宁可短暂定格，也绝不让同一段内容再出现一次。
 *
 * <p>基准只在<b>换流</b>时重建（渲染器重新启用/停用）。刻意不在
 * {@link #onPositionReset} 里重建：那会让任何一次向后跳转都变成完整重播。
 * 唯一的例外是连续丢弃超过 {@link #FREEZE_LIMIT_US}，
 * 说明时间轴确实整体重来（而不是重复内容），此时兜底放行避免永久冻屏。
 */
@UnstableApi
public final class LiveVideoRenderer extends MediaCodecVideoRenderer {

    /** 连续丢弃超过这么久就认为时间轴真的重来了，兜底重建基准。 */
    private static final long FREEZE_LIMIT_US = 60_000_000L;

    /** 已播帧的最大 PTS；{@link C#TIME_UNSET} 表示还没有基准。 */
    private long highWaterUs = C.TIME_UNSET;
    /** 本轮连续丢弃的起始 PTS，用于判断是否已经冻屏太久。 */
    private long dropSinceUs = C.TIME_UNSET;
    private long skippedFrames = 0;
    private long resets = 0;

    public LiveVideoRenderer(
            Context context,
            MediaCodecSelector mediaCodecSelector,
            long allowedJoiningTimeMs,
            boolean enableDecoderFallback,
            @Nullable Handler eventHandler,
            @Nullable VideoRendererEventListener eventListener,
            int maxDroppedFramesToNotify) {
        super(context, mediaCodecSelector, allowedJoiningTimeMs, enableDecoderFallback,
                eventHandler, eventListener, maxDroppedFramesToNotify);
    }

    @Override
    protected boolean processOutputBuffer(
            long positionUs,
            long elapsedRealtimeUs,
            @Nullable MediaCodecAdapter codec,
            @Nullable ByteBuffer buffer,
            int bufferIndex,
            int bufferFlags,
            int sampleCount,
            long bufferPresentationTimeUs,
            boolean isDecodeOnlyBuffer,
            boolean isLastBuffer,
            Format format) throws ExoPlaybackException {

        // 结尾帧必须交给父类，否则流结束时的 flush 流程会卡住
        if (codec != null && !isDecodeOnlyBuffer && !isLastBuffer
                && highWaterUs != C.TIME_UNSET) {
            long back = highWaterUs - bufferPresentationTimeUs;
            if (back > 0) {
                if (dropSinceUs == C.TIME_UNSET) {
                    dropSinceUs = bufferPresentationTimeUs;
                }
                // 时间轴整体重来（回退量巨大且迟迟追不上）时才兜底，
                // 正常情况下宁可用定格换"绝不重播"
                if (highWaterUs - dropSinceUs > FREEZE_LIMIT_US) {
                    android.util.Log.w("IPTVVIDEO", "freeze-limit reset backUs=" + back
                            + " dropped=" + skippedFrames);
                    resetBaseline();
                    resets++;
                } else {
                    skippedFrames++;
                    if (skippedFrames <= 30 || skippedFrames % 100 == 0) {
                        android.util.Log.i("IPTVVIDEO", "dup backUs=" + back
                                + " posUs=" + positionUs + " ptsUs=" + bufferPresentationTimeUs
                                + " total=" + skippedFrames);
                    }
                    skipOutputBuffer(codec, bufferIndex, bufferPresentationTimeUs);
                    return true;
                }
            } else {
                dropSinceUs = C.TIME_UNSET;
            }
        }

        boolean handled = super.processOutputBuffer(
                positionUs, elapsedRealtimeUs, codec, buffer, bufferIndex, bufferFlags,
                sampleCount, bufferPresentationTimeUs, isDecodeOnlyBuffer, isLastBuffer, format);
        if (handled && !isDecodeOnlyBuffer) {
            // 内容进度推进到这一帧（含被父类判为过晚丢弃的帧）
            highWaterUs = bufferPresentationTimeUs;
        }
        return handled;
    }

    /**
     * 跳转时<b>不</b>重建基准。
     * 直播里任何向后跳都意味着重播旧内容，门槛一放开就会整段重复；
     * 向前跳不受影响（PTS 高于基准，正常放行）。
     */
    @Override
    protected void onPositionReset(long positionUs, boolean joining) throws ExoPlaybackException {
        super.onPositionReset(positionUs, joining);
    }

    @Override
    protected void onEnabled(boolean joining, boolean mayRenderStartOfStream)
            throws ExoPlaybackException {
        super.onEnabled(joining, mayRenderStartOfStream);
        resetBaseline();
    }

    @Override
    protected void onDisabled() {
        super.onDisabled();
        resetBaseline();
    }

    private void resetBaseline() {
        highWaterUs = C.TIME_UNSET;
        dropSinceUs = C.TIME_UNSET;
    }

    /** 已丢弃的重复帧总数，排查用。 */
    public long skippedFrames() {
        return skippedFrames;
    }

    /** 时间轴重建次数，排查用。 */
    public long resetCount() {
        return resets;
    }
}
