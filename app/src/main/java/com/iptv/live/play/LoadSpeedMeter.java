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

    /**
     * 统计窗口。
     *
     * <p>必须<b>长于一个分片周期</b>，否则分片间隙窗口会被排空，
     * 读数掉回 0/破折号。实测这些源分片 5~6 秒一个，取 12 秒
     * 能稳住 2 个分片的样本。
     */
    private static final long WINDOW_MS = 12_000L;
    /** 小于这个体积的多半是 m3u8、密钥等控制请求，不代表带宽。 */
    private static final long MIN_MEDIA_BYTES = 64 * 1024L;

    private static final class Sample {
        final long at;
        final long bytes;
        final long durationMs;
        /** 这个分片覆盖的媒体时长，用来算这路流的码率需求；0 表示不知道。 */
        final long mediaMs;

        Sample(long at, long bytes, long durationMs, long mediaMs) {
            this.at = at;
            this.bytes = bytes;
            this.durationMs = durationMs;
            this.mediaMs = mediaMs;
        }
    }

    private final Deque<Sample> samples = new ArrayDeque<>();
    /** 上一次算出的下载速率，窗口排空时沿用（见 {@link #bytesPerSecond()}）。 */
    private long lastSpeed;
    /** 上一次算出的码率需求，窗口排空时沿用。 */
    private long lastNeed;

    /** 记录一次完成的加载；非媒体分片直接忽略。 */
    public synchronized void record(long bytes, long durationMs) {
        record(bytes, durationMs, 0);
    }

    /**
     * 记录一次完成的加载，附带该分片覆盖的媒体时长。
     *
     * @param mediaMs 分片本身的播放时长（如 5 秒片就是 5000）。
     *                有它才能算出「这路流需要多少码率」，从而判断带宽有没有余量。
     *                拿不到就传 0。
     */
    public synchronized void record(long bytes, long durationMs, long mediaMs) {
        if (bytes < MIN_MEDIA_BYTES) {
            return;
        }
        long now = System.currentTimeMillis();
        samples.addLast(new Sample(now, bytes, Math.max(1, durationMs),
                Math.max(0, mediaMs)));
        trim(now);
    }

    /**
     * 当前估算速率，单位 byte/s；还没有媒体分片完成时返回 0。
     *
     * <p>用"总字节 / 总下载耗时"而不是除以窗口时长：一个 5 秒分片往往只下 1 秒，
     * 除以窗口会把空闲时间也算进去，读数会低三四倍。
     *
     * <p>窗口内一条样本都不剩时<b>返回上一次的读数</b>而不是 0：
     * 0 会被上层当成"带宽归零"从而立刻关掉体检，而实际上
     * 只是下一个分片还没开始下。真断流由播放状态（state / buffered）反映，
     * 不需要这里再报一次。
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
            return lastSpeed;
        }
        lastSpeed = bytes * 1000L / ms;
        return lastSpeed;
    }

    public synchronized void reset() {
        samples.clear();
        lastSpeed = 0;
        lastNeed = 0;
    }

    /**
     * 这路流需要多少码率（byte/s）；样本里没有媒体时长时返回 0。
     *
     * <p>算法是「分片字节数 / 分片媒体时长」，不是「字节数 / 下载耗时」——
     * 后者是<b>已经达成的速度</b>，前者才是<b>要稳定播下去必须达到的速度</b>。
     * 两者相比才知道带宽有没有余量：刚够 1.0 倍就是在刀尖上走，
     * 分片一到边界必然播空。
     *
     * <p>同样保留上一次读数：分片间隙里没有新样本，
     * 不代表这路流的码率突然变成 0（那是源决定的，跟此刻有没有在下无关）。
     */
    public synchronized long requiredBytesPerSecond() {
        long now = System.currentTimeMillis();
        trim(now);
        long bytes = 0;
        long ms = 0;
        for (Sample s : samples) {
            if (s.mediaMs <= 0) {
                continue;
            }
            bytes += s.bytes;
            ms += s.mediaMs;
        }
        if (ms <= 0) {
            return lastNeed;
        }
        lastNeed = bytes * 1000L / ms;
        return lastNeed;
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
