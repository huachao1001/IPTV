package com.iptv.live.play;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/**
 * 滑动窗口网速统计。
 *
 * <p>播放器每个分片才产生一次加载回调，直接看单次读数会跳得厉害；
 * 这里按最近几秒的媒体分片累计折算。播放列表、密钥这类小请求不计入，
 * 否则几 KB 的文件会把平均值拉到一个明显偏低的数字上。
 */
public final class LoadSpeedMeter {

    /** 统计窗口，覆盖一个 HLS 分片的时长。 */
    private static final long WINDOW_MS = 4_000L;
    /** 小于这个体积的多半是 m3u8、密钥等控制请求，不代表带宽。 */
    private static final long MIN_MEDIA_BYTES = 64 * 1024L;

    private static final class Sample {
        final long at;
        final long bytes;
        final long durationMs;

        Sample(long at, long bytes, long durationMs) {
            this.at = at;
            this.bytes = bytes;
            this.durationMs = durationMs;
        }
    }

    private final Deque<Sample> samples = new ArrayDeque<>();

    /** 记录一次完成的加载；非媒体分片直接忽略。 */
    public synchronized void record(long bytes, long durationMs) {
        if (bytes < MIN_MEDIA_BYTES) {
            return;
        }
        long now = System.currentTimeMillis();
        samples.addLast(new Sample(now, bytes, Math.max(1, durationMs)));
        trim(now);
    }

    /**
     * 当前估算速率，单位 byte/s；还没有媒体分片完成时返回 0。
     *
     * <p>用"总字节 / 总下载耗时"而不是除以窗口时长：一个 5 秒分片往往只下 1 秒，
     * 除以窗口会把空闲时间也算进去，读数会低三四倍。
     */
    public synchronized long bytesPerSecond() {
        long now = System.currentTimeMillis();
        trim(now);
        long bytes = 0;
        long ms = 0;
        for (Sample s : samples) {
            bytes += s.bytes;
            ms += s.durationMs;
        }
        if (ms <= 0) {
            return 0;
        }
        return bytes * 1000L / ms;
    }

    public synchronized void reset() {
        samples.clear();
    }

    private void trim(long now) {
        while (!samples.isEmpty() && now - samples.peekFirst().at > WINDOW_MS) {
            samples.removeFirst();
        }
    }

    /** 转成便于阅读的字符串，如 "12.7 MB/s"；无数据时给一个占位的破折号。 */
    public static String format(long bytesPerSecond) {
        if (bytesPerSecond <= 0) {
            return "—";
        }
        double kb = bytesPerSecond / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.CHINA, "%.0f KB/s", kb);
        }
        return String.format(Locale.CHINA, "%.1f MB/s", kb / 1024.0);
    }
}
