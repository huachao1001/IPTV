package com.iptv.live;

/**
 * 静默卡死判定：播放器状态「在播」(READY + playWhenReady、不缓冲、无错误)，
 * 但播放位置长时间纹丝不动 —— 即播放时钟停走（解码器/音频渲染器卡死等）。
 *
 * <p>这是第三种故障形态，现有的两个看门狗都覆盖不到：
 * <ul>
 *   <li>{@code startupWatchdog} 只管「还没出过画面」；</li>
 *   <li>{@code stallWatchdog} 只在 {@code STATE_BUFFERING} 时启动。</li>
 * </ul>
 * 一旦停在 READY 而时钟不走，两个都不会触发，用户看到的是永久定格，
 * 除非手动换台。
 *
 * <p><b>关键：倒退不算进展。</b>直播流的 {@code getCurrentPosition()} 是
 * 「相对窗口起点」的位置，每当一个分片滑出窗口（本机实测 5 秒一片），
 * 整个位置会一下子退后 5000ms 再继续往前走，形态是
 * {@code -3606 → -9006 → -14306 …}。若按「位置变了就算在播」判定，
 * 每次倒跳都会重置计时，看门狗永远凑不满阈值、永远漏检。
 * 所以只有「严格向前」才刷新计时。
 *
 * <p>纯逻辑类：采样由调用方驱动（传入当前时间），便于单测注入时钟。
 */
final class StallDetector {

    /** 位置连续停滞多久判定为卡死。 */
    private static final long THRESHOLD_MS = 8_000L;

    /** 上一次见到的位置；{@link Long#MIN_VALUE} 表示还没有基线。 */
    private long lastPos = Long.MIN_VALUE;
    /** 最后一次「向前进展」的时刻。 */
    private long lastAdvanceAt = 0L;

    /**
     * 采样一次。
     *
     * @param eligible 形式上在播（已出画面 && isPlaying && !buffering && 无错误）
     * @param position 当前播放位置（直播下可能为负、可能倒跳）
     * @param now      当前时间（{@link System#elapsedRealtime()} 或毫秒时间戳，由调用方统一）
     * @return true 表示判定为卡死（调用方应重连）；触发后内部计时自动重置，避免连环触发
     */
    boolean sample(boolean eligible, long position, long now) {
        if (!eligible) {
            // 暂停/缓冲/错误/未起播：这些状态下位置不动是正常的，重置基线不计时
            lastPos = position;
            lastAdvanceAt = now;
            return false;
        }
        if (lastPos == Long.MIN_VALUE || position > lastPos) {
            // 只有向前走才算真实进展（或首次建立基线）→ 刷新计时
            lastPos = position;
            lastAdvanceAt = now;
            return false;
        }
        // 倒退或原地：更新基线但不重置计时，此后真恢复（前进）才算进展
        lastPos = position;
        if (now - lastAdvanceAt >= THRESHOLD_MS) {
            // 触发后重置，给重连留出时间，不要每次采样都触发
            lastAdvanceAt = now;
            return true;
        }
        return false;
    }

    /** 当前已持续停滞多久（自最后一次「向前进展」起）。未建立基线时为零。 */
    long stalledForMs(long now) {
        return lastAdvanceAt == 0L ? 0L : Math.max(0L, now - lastAdvanceAt);
    }

    /** 切台/重连/重建播放器后调用：清空基线，新会话重新计时。 */
    void reset() {
        lastPos = Long.MIN_VALUE;
        lastAdvanceAt = 0L;
    }
}
