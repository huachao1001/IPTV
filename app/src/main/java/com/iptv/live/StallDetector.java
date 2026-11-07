package com.iptv.live;

/**
 * 静默卡死判定：播放器状态上「在播」(READY + playWhenReady、不缓冲、无错误)，
 * 但画面实际上不再更新。
 *
 * <p>这是现有看门狗都覆盖不到的故障形态：
 * <ul>
 *   <li>{@code startupWatchdog} 只管「还没出过画面」；</li>
 *   <li>{@code stallWatchdog} 只在 {@code STATE_BUFFERING} 时启动。</li>
 * </ul>
 * 一旦停在 READY 而画面不动，两个都不会触发，用户看到的是永久定格。
 *
 * <p><b>必须同时看位置和帧数，只看位置会漏检。</b>
 * 真机实测（build/verify3.log，11:25:04~11:25:09）出现过这种形态：
 * <pre>
 *   pos:  16162 → 13161 → 15167 → 17387     ← 位置一直在走
 *   fps:   29.5 →   0.0 →   3.0 →  23.5     ← 画面却几秒没更新
 * </pre>
 * 音频主时钟还在推进，所以位置照走、状态仍是 READY，但视频渲染器
 * 三秒半没送出一帧。只按位置判定的话这里永远判不出来。
 *
 * <p><b>但触发动作必须比重连慢得多。</b>这类停帧多数是<b>自愈</b>的
 * （实测 2 秒内自己回到满帧，见 {@link #FRAME_THRESHOLD_MS}），
 * 阈值定太紧会把小抖动升级成换线，反而更久放不出来。
 *
 * <p><b>倒退不算进展。</b>直播流的 {@code getCurrentPosition()} 是
 * 「相对窗口起点」的位置，每当一个分片滑出窗口（实测 5 秒一片），
 * 整个位置会一下子退后 5000ms 再继续往前走，形态是
 * {@code -3606 → -9006 → -14306 …}。若按「位置变了就算在播」判定，
 * 每次倒跳都会重置计时，看门狗永远凑不满阈值。
 *
 * <p>纯逻辑类：采样由调用方驱动（传入当前时间与帧数），便于单测注入时钟。
 */
final class StallDetector {

    /**
     * 位置停滞多久判定为卡死。
     *
     * <p>取 10 秒：比实测的最长停帧（3.5 秒）宽裕得多，避免把一次
     * 短暂追帧误判成卡死而白重连一次；又能赶在用户失去耐心前兜住真正的定格。
     */
    private static final long POS_THRESHOLD_MS = 10_000L;

    /**
     * 画面多久没有新帧才判定为卡死。
     *
     * <p><b>必须给自愈留出时间：取 10 秒。</b>
     * 真机实测（build/verify8.log，12:04:58~12:05:04）这类停帧是<b>自愈</b>的：
     * <pre>
     *   12:04:58  calls=237 rendered=75    ← 开始掉帧
     *   12:05:00  calls=198 rendered=0     ← 完全停住
     *   12:05:02  calls=257 rendered=60    ← 自己回来了
     *   12:05:04  calls=297 rendered=100   ← 恢复满帧 50fps
     * </pre>
     * 全程约 2 秒。早先按 3 秒判定，看门狗在 12:05:00 就把这种小抖动
     * 升级成「换线 + seek」，恢复代价从 2 秒变成 10 秒以上，最后还起不来。
     * 换句话说：阈值太低会让「能自愈的抖动」变成「真的播不出来」。
     *
     * <p>内容本身是 50 场/秒，10 秒零帧一定是真故障，用户也已经明显感知到，
     * 这时才值得付出重建管线的代价。
     */
    private static final long FRAME_THRESHOLD_MS = 10_000L;

    /** 上一次见到的位置；{@link Long#MIN_VALUE} 表示还没有基线。 */
    private long lastPos = Long.MIN_VALUE;
    /** 最后一次「位置向前进展」的时刻。 */
    private long lastAdvanceAt = 0L;

    /** 上一次见到的已渲染帧数；-1 表示还没有基线。 */
    private long lastFrames = -1L;
    /** 最后一次「帧数增加」的时刻。 */
    private long lastFrameAt = 0L;

    /** 最近一次触发时所处的停滞时长（位置口径），供调用方打日志。 */
    private long triggerPosMs = 0L;
    /** 最近一次触发时所处的停滞时长（画面口径），供调用方打日志。 */
    private long triggerFrameMs = 0L;

    /**
     * 采样一次（只判位置）。
     *
     * @param eligible 形式上在播（已出画面 && isPlaying && !buffering && 无错误）
     * @param position 当前播放位置（直播下可能为负、可能倒跳）
     * @param now      当前时间（{@link System#currentTimeMillis()} 或 uptime，由调用方统一）
     * @return true 表示判定为卡死（调用方应重连）；触发后内部计时自动重置，避免连环触发
     */
    boolean sample(boolean eligible, long position, long now) {
        return sample(eligible, position, -1L, now);
    }

    /**
     * 采样一次。
     *
     * @param eligible  形式上在播
     * @param position  当前播放位置（直播下可能为负、可能倒跳）
     * @param frames    已渲染帧数；传负数表示本次不参与帧判定
     * @param now       当前时间（调用方统一时钟）
     * @return true 表示判定为卡死
     */
    boolean sample(boolean eligible, long position, long frames, long now) {
        if (!eligible) {
            // 暂停/缓冲/错误/未起播：这些状态下不动是正常的，重置基线不计时
            lastPos = position;
            lastAdvanceAt = now;
            if (frames >= 0) {
                lastFrames = frames;
                lastFrameAt = now;
            }
            return false;
        }

        boolean posStalled = false;
        if (lastPos == Long.MIN_VALUE || position > lastPos) {
            // 只有向前走才算真实进展（或首次建立基线）→ 刷新计时
            lastPos = position;
            lastAdvanceAt = now;
        } else {
            // 倒退或原地：更新基线但不重置计时，此后真恢复（前进）才算进展
            lastPos = position;
            posStalled = now - lastAdvanceAt >= POS_THRESHOLD_MS;
        }

        boolean frameStalled = false;
        if (frames >= 0) {
            if (lastFrames < 0 || frames > lastFrames) {
                lastFrames = frames;
                lastFrameAt = now;
            } else {
                // 帧数没有增加：画面没更新。这条能在"位置照走"时抓到停帧。
                lastFrames = frames;
                frameStalled = now - lastFrameAt >= FRAME_THRESHOLD_MS;
            }
        }

        if (posStalled || frameStalled) {
            // 先把触发时的真实停滞时长记下来给调用方打日志，
            // 再重置计时。反过来会读到自己刚清零的值，日志永远是 0（踩过）。
            triggerPosMs = now - lastAdvanceAt;
            triggerFrameMs = now - lastFrameAt;
            // 触发后重置，给重连留出时间，不要每次采样都触发
            lastAdvanceAt = now;
            lastFrameAt = now;
            return true;
        }
        return false;
    }

    /** 当前已持续停滞多久（位置口径）。未建立基线时为零。 */
    long stalledForMs(long now) {
        return lastAdvanceAt == 0L ? 0L : Math.max(0L, now - lastAdvanceAt);
    }

    /** 最近一次触发时的停滞时长（位置口径）。比 {@link #stalledForMs} 可靠，后者触发后已清零。 */
    long lastTriggerPosMs() {
        return triggerPosMs;
    }

    /** 最近一次触发时的画面停滞时长。 */
    long lastTriggerFrameMs() {
        return triggerFrameMs;
    }

    /** 当前已多久没有新帧（画面口径）。未建立基线时为零。 */
    long frameStalledForMs(long now) {
        return lastFrameAt == 0L ? 0L : Math.max(0L, now - lastFrameAt);
    }

    /** 切台/重连/重建播放器后调用：清空基线，新会话重新计时。 */
    void reset() {
        lastPos = Long.MIN_VALUE;
        lastAdvanceAt = 0L;
        lastFrames = -1L;
        lastFrameAt = 0L;
    }
}
