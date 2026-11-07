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
 *
 * <p>唯一的例外是<b>时间轴整体重来</b>，用两个口径识别（都不是「重复内容」）：
 * <ul>
 *   <li>回退距离超过一个分片（{@link #FREEZE_LIMIT_US}）——重复下发不会这么长；</li>
 *   <li>连续丢弃满 4 秒墙钟仍追不上（{@link #FREEZE_LIMIT_MS}）——
 *       那段 PTS 永远不会来了，再等下去就是几十秒定格。</li>
 * </ul>
 * 两种情况都就地从当前帧续播。判定要严，因为一旦放宽，
 * 用户看到的就是「同一段内容又放一遍」。
 */
@UnstableApi
public final class LiveVideoRenderer extends MediaCodecVideoRenderer {

    /**
     * 判定「时间轴整体重来」的回退距离。
     *
     * <p>节目单里确实有整段分片重复下发的情况，那种重复长度不超过一个分片
     * （实测 5 秒），所以 8 秒的回退距离能把重复内容全部挡住，同时又能识别出
     * 「PTS 真的从头开始」这种大回退。
     *
     * <p>原值是 60 秒，实测代价太大：一旦时间轴从 16.5 秒之前重来，
     * 15 秒的回退距离凑不满 60 秒的阈值，画面就一直冻着，
     * 直到播放位置自己追上来（build/verify8.log 12:05:07~12:05:17
     * 连续 5 个窗口 {@code rendered=0}）。
     */
    private static final long FREEZE_LIMIT_US = 8_000_000L;

    /**
     * 判定「时间轴整体重来」的连续丢弃时长（墙钟）。
     *
     * <p>回退距离还不够：真正的重复内容会被<b>瞬间</b>跳完（{@code skipOutputBuffer}
     * 立即返回），墙钟上几乎不耗时；而「PTS 待在后面追不上」时，帧仍然按内容帧率
     * 一帧一帧送进来、一帧一帧被丢，墙钟会真实累积。所以连续丢满
     * {@code 4} 秒仍未追上，就说明前面那段 PTS 永远不会来了，必须就地从当前帧续播，
     * 否则用户面对的是几十秒定格。
     */
    private static final long FREEZE_LIMIT_MS = 4_000L;

    /**
     * 同一帧反复被父类「等会儿再送」的容忍时长。
     *
     * <p>真机实测的停帧（build/verify10.log 12:28:55~12:29:05）形态是：
     * <pre>
     *   calls=198  proc=0  held=198   ← 每 2 秒整整 198 次重试，一帧都出不去
     *   highWater=1000169766 纹丝不动   ← 卡在同一个 PTS 上
     * </pre>
     * 也就是说<b>帧已经拿到了</b>，只是父类每轮都说「还不到送显时刻」。
     * media3 对此没有任何超时，一帧能挡住整条管线十几秒。
     * 到期必须我们主动把这一帧放掉，否则用户看到的就是长时间定格。
     */
    private static final long HOLD_GIVE_UP_MS = 4_000L;

    /** 已播帧的最大 PTS；{@link C#TIME_UNSET} 表示还没有基准。 */
    private long highWaterUs = C.TIME_UNSET;

    /** 当前被父类扣住不放的那一帧的 PTS，用来识别「卡在同一个 PTS 上」。 */
    private long holdPtsUs = C.TIME_UNSET;
    /** 这一帧开始被扣住的墙钟时刻。 */
    private long holdSinceMs = 0L;
    /** 上一次打印 held-stuck 的时刻，避免刷屏。 */
    private long holdLogAtMs = 0L;
    /** 是否已经观察到「正常送显」过；用于区分启动阶段与播放中途卡死。 */
    private boolean sawHealthyFrame = false;
    /** 上一帧被处理掉的 PTS，用于发现「PTS 向前大跳」。 */
    private long lastProcPtsUs = C.TIME_UNSET;
    /** 本轮连续丢弃的起始 PTS，用于判断回退幅度。 */
    private long dropSinceUs = C.TIME_UNSET;
    /** 本轮连续丢弃的开始时刻（墙钟），用于判断是否永远追不上。 */
    private long dropSinceAtMs = 0L;
    private long skippedFrames = 0;
    private long resets = 0;

    /*
     * ---- 渲染线程侧的统计 ----
     * 主线程读 DecoderCounters 只能看到"总共送显了多少帧"，
     * 分不清"解码器没给帧"和"给了但被父类 skip/drop"。
     * 这里在渲染线程上计数，每 2 秒打一条，两者一眼可分：
     *
     *   实测意义（本机内容为 576i25，50 场/秒）：
     *     calls≈300  proc=100/2s  ptsSpanMs≈1980  dedupDropped=0  -> 完全正常
     *     calls≈200  proc=0        ptsSpanMs=0     -> 解码器不给输出帧
     *     calls≈300  proc=0        ptsSpanMs≈2000 -> 给了但全被判迟到丢弃
     *     dedupDropped 激增                          -> PTS 高水位线误伤
     *
     * 注意 proc 含父类主动 skip/drop 的帧，不是送显数；
     * 送显数看 IPTVPLAY 的 renderedOutputBufferCount。
     */
    private long statRendered = 0;
    private long statDropped = 0;
    private long statCalls = 0;
    private long statHeld = 0;
    private long statDecodeOnly = 0;
    private long statWindowStartMs = 0;
    private long statMinPtsUs = Long.MAX_VALUE;
    private long statMaxPtsUs = Long.MIN_VALUE;

    /**
     * 一次「输出帧被处理完」的记账（父类返回 true 即处理完）。
     *
     * <p>注意它<b>不等于送显</b>：media3 判迟到时走 {@code skipOutputBuffer} /
     * {@code dropOutputBuffer}，同样返回 true。所以这里的计数用来判断
     * 「解码器到底有没有持续给帧」，判断送显要看 IPTVPLAY 的 renderedOutputBufferCount。
     */
    private void noteRender(long ptsUs) {
        statRendered++;
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
                + " calls=" + statCalls
                // proc = 本窗口被处理掉的输出帧数，含父类主动 skip/drop 的迟到帧，
                // 因此**不等于送显数**。真正的送显数看 IPTVPLAY 里的
                // renderedOutputBufferCount（它只统计 renderOutputBuffer）。
                + " proc=" + statRendered
                + " held=" + statHeld
                + " decodeOnly=" + statDecodeOnly
                + " fps=" + String.format(java.util.Locale.US, "%.1f", fps)
                + " dedupDropped=" + statDropped
                + " ptsSpanMs=" + (spanUs / 1000)
                // ptsSpan 明显小于窗口时长，说明时间轴上有空洞（片间不连续）
                + " highWater=" + (highWaterUs == C.TIME_UNSET ? "-" : String.valueOf(highWaterUs / 1000)));
        statRendered = 0;
        statDropped = 0;
        statCalls = 0;
        statDecodeOnly = 0;
        statHeld = 0;
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
        // IPTVVIDEO；calls 能区分「父类一次都没调用过来」（解码侧断供）
        // 与「调用过来了但没有 output buffer 可送」（buffer 队列空了）。
        flushStats();
        statCalls++;
        if (isDecodeOnlyBuffer) {
            // 解码器给出的 buffer 被标记为"只解码不显示"。整段都是这种标记时，
            // 渲染循环会一直空转（calls 很高）却一帧都不送显 —— 实测形态。
            statDecodeOnly++;
        }

        // 结尾帧必须交给父类，否则流结束时的 flush 流程会卡住
        if (codec != null && !isDecodeOnlyBuffer && !isLastBuffer
                && highWaterUs != C.TIME_UNSET) {
            long back = highWaterUs - bufferPresentationTimeUs;
            if (back > 0) {
                if (dropSinceUs == C.TIME_UNSET) {
                    dropSinceUs = bufferPresentationTimeUs;
                    dropSinceAtMs = android.os.SystemClock.elapsedRealtime();
                }
                // 两种情形说明时间轴整体重来（而不是同一段内容重复下发），
                // 必须就地从当前帧续播，否则用户面对的是几十秒定格：
                //   1) 回退距离超过一个分片 —— 重复下发不会这么长
                //   2) 已经连续丢了 4 秒墙钟还追不上 —— 中间那段 PTS 永远不会来了
                boolean timelineRestart =
                        highWaterUs - dropSinceUs > FREEZE_LIMIT_US
                                || android.os.SystemClock.elapsedRealtime() - dropSinceAtMs
                                > FREEZE_LIMIT_MS;
                if (timelineRestart) {
                    android.util.Log.w("IPTVVIDEO", "timeline-restart reset backUs=" + back
                            + " dropped=" + skippedFrames
                            + " afterMs=" + (android.os.SystemClock.elapsedRealtime()
                            - dropSinceAtMs));
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
            // 迟到量不在这里算：media3 自己用 videoFrameProcessingOffsetUs 记账
            // （见 DecoderCounters），在渲染线程外重算会得到恒定偏置的假象。
            noteRender(bufferPresentationTimeUs);
            holdPtsUs = C.TIME_UNSET;
            holdSinceMs = 0L;
            if (lastProcPtsUs != C.TIME_UNSET
                    && bufferPresentationTimeUs - lastProcPtsUs > 1_000_000L) {
                // 处理掉的相邻两帧 PTS 相差超过 1 秒：中间那一段画面根本没进过渲染器。
                // 这是判断「解码器丢画面」还是「排期卡住」的关键旁证。
                android.util.Log.w("IPTVVIDEO", "pts-gap jumpMs="
                        + ((bufferPresentationTimeUs - lastProcPtsUs) / 1000)
                        + " from=" + lastProcPtsUs + " to=" + bufferPresentationTimeUs);
            }
            lastProcPtsUs = bufferPresentationTimeUs;
            // 只有真正往前推进了才抬高水位线。父类判迟到时只是把 buffer 丢掉，
            // 内容并没有播出去，抬高水位线会让下一批正常帧反而被判成"重复"。
            if (highWaterUs == C.TIME_UNSET || bufferPresentationTimeUs > highWaterUs) {
                highWaterUs = bufferPresentationTimeUs;
            }
        } else if (!handled && !isDecodeOnlyBuffer) {
            // 拿到了帧但父类说"等会儿再送"（FRAME_RELEASE_TRY_AGAIN_LATER /
            // 排期未到）。这类帧会卡在同一位置反复被重试，一帧都出不去，
            // 表现为画面定格而 calls 照涨。与"解码器不给帧"必须分开看。
            //
            // 光看 held 数量分不出两种病，必须把被扣住那一帧的 PTS 打出来：
            //   ptsUs 只比 positionUs 早几十毫秒  -> 排期条件卡住，可强制放行
            //   ptsUs 比 positionUs 早好几秒      -> 解码器真丢了这几秒画面，
            //                                        强制放行会造成音画不同步
            long now = android.os.SystemClock.elapsedRealtime();
            if (holdPtsUs != bufferPresentationTimeUs) {
                holdPtsUs = bufferPresentationTimeUs;
                holdSinceMs = now;
            }
            statHeld++;
            long heldMs = now - holdSinceMs;
            if (heldMs >= 500 && now - holdLogAtMs >= 1000) {
                holdLogAtMs = now;
                android.util.Log.w("IPTVVIDEO", "held-stuck heldMs=" + heldMs
                        + " ptsUs=" + bufferPresentationTimeUs
                        + " posUs=" + positionUs
                        + " earlyUs=" + (bufferPresentationTimeUs - positionUs)
                        + " offsetUs=" + getOutputStreamOffsetUs()
                        + " highWaterUs=" + (highWaterUs == C.TIME_UNSET ? "-" : highWaterUs)
                        + " calls=" + statCalls);
            }
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
        dropSinceAtMs = 0L;
        holdPtsUs = C.TIME_UNSET;
        holdSinceMs = 0L;
        holdLogAtMs = 0L;
        lastProcPtsUs = C.TIME_UNSET;
        statWindowStartMs = 0;
        statRendered = 0;
        statDropped = 0;
        statCalls = 0;
        statDecodeOnly = 0;
        statHeld = 0;
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
