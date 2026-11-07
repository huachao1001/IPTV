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

    /*
     * ---- 渲染线程侧的权威统计（诊断用） ----
     * 主线程读 DecoderCounters 只能看到"总共渲染了多少帧"，
     * 分不清"没帧可渲染"和"帧被丢弃"。这里在真正送显的线程上计数，
     * 每 2 秒打一条：查清画面停顿时到底是没数据、被去重丢掉、还是被判迟到。
     */
    private long statRendered = 0;
    private long statTooLate = 0;
    private long statDropped = 0;
    private long statWindowStartMs = 0;
    private long statMinPtsUs = Long.MAX_VALUE;
    private long statMaxPtsUs = Long.MIN_VALUE;

    /** 单次送显出口，统一记账，避免各分支漏统计。 */
    private void noteRender(long ptsUs, boolean tooLate) {
        statRendered++;
        if (tooLate) {
            statTooLate++;
        }
        if (ptsUs < statMinPtsUs) {
            statMinPtsUs = ptsUs;
        }
        if (ptsUs > statMaxPtsUs) {
            statMaxPtsUs = ptsUs;
        }
    }

    /** 每 2 秒输出一次渲染侧统计，定位"画面停顿"的真实成因。 */
    private void flushStats() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (statWindowStartMs == 0) {
            statWindowStartMs = now;
            return;
        }
        long span = now - statWindowStartMs;
        if (span < 2000) {
            return;
        }
        double fps = statRendered * 1000.0 / span;
        long spanUs = statMaxPtsUs > statMinPtsUs ? statMaxPtsUs - statMinPtsUs : 0;
        android.util.Log.i("IPTVVIDEO", "render win=" + span + "ms"
                + " rendered=" + statRendered
                + " fps=" + String.format(java.util.Locale.US, "%.1f", fps)
                + " tooLate=" + statTooLate
                + " dedupDropped=" + statDropped
                + " ptsSpanMs=" + (spanUs / 1000)
                // ptsSpan 明显小于窗口时长，说明时间轴上有空洞（片间不连续）
                + " highWater=" + (highWaterUs == C.TIME_UNSET ? "-" : String.valueOf(highWaterUs / 1000)));
        statRendered = 0;
        statTooLate = 0;
        statDropped = 0;
        statMinPtsUs = Long.MAX_VALUE;
        statMaxPtsUs = Long.MIN_VALUE;
        statWindowStartMs = now;
    }

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

        // 每进入渲染线程一次就推进一次统计窗口。帧流正常时每 2 秒必有一条
        // IPTVVIDEO；某段时间完全没有这条日志，就说明渲染线程根本没拿到输出帧
        // （解码侧断供），和"拿到了却被丢弃"是两种完全不同的病因。
        flushStats();

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
                    // 有数据但被去重丢弃 —— 与"压根没数据"要分开统计
                    statDropped++;
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
            // 内容进度推进到这一帧（含被父类判为过晚丢弃的帧）。
            // tooLate 用来区分"正常送显"与"迟到被父类丢掉"：
            // 迟到帧一多，画面就是持续的顿挫，而平均 fps 可能仍是满的。
            noteRender(bufferPresentationTimeUs, bufferPresentationTimeUs < positionUs);
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

    /**
     * 换流时主动清基准。
     *
     * <p>渲染器实例在同一个播放器里是复用的，换频道不一定触发
     * {@link #onEnabled}{@code /}{@link #onDisabled}，基准就会带着上一条流的
     * PTS 留下来。新流 PTS 从头开始，于是每一帧都小于基准、全被判成重复帧丢掉，
     * 画面一直冻到 {@link #FREEZE_LIMIT_US} 兜底才恢复。
     * 因此每路新流准备播放前必须显式清一次。
     */
    public void resetForNewStream() {
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
        statWindowStartMs = 0;
        statRendered = 0;
        statTooLate = 0;
        statDropped = 0;
        statMinPtsUs = Long.MAX_VALUE;
        statMaxPtsUs = Long.MIN_VALUE;
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
