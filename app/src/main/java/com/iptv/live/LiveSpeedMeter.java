package com.iptv.live;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

/**
 * 实时网速表。
 *
 * <p>播放器的 {@code onLoadCompleted} 只在整段分片下完才回调一次，
 * 缓冲期间完全没有读数，界面上只能显示"—"。这里挂在 {@link TransferListener} 上，
 * 每次读到一块字节就累计，网速随下载实时变化。
 */
@UnstableApi
public final class LiveSpeedMeter implements TransferListener {

    public static final LiveSpeedMeter INSTANCE = new LiveSpeedMeter();

    /** 结算粒度。太细读数抖得看不清，太粗反应迟钝。 */
    private static final long BUCKET_MS = 400;

    private final Object lock = new Object();
    private long bucketStart;
    private long bucketBytes;
    private long rate;
    private long lastAt;

    private LiveSpeedMeter() {
    }

    @Override
    public void onTransferInitializing(DataSource source, DataSpec dataSpec, boolean isNetwork) {
    }

    @Override
    public void onTransferStart(DataSource source, DataSpec dataSpec, boolean isNetwork) {
    }

    /** 每读到一块就累加；跨桶时结算出新的速率。 */
    @Override
    public void onBytesTransferred(DataSource source, DataSpec dataSpec, boolean isNetwork,
                                   int bytesTransferred) {
        if (!isNetwork || bytesTransferred <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        synchronized (lock) {
            if (bucketStart == 0) {
                bucketStart = now;
            }
            bucketBytes += bytesTransferred;
            long span = now - bucketStart;
            if (span >= BUCKET_MS) {
                long v = bucketBytes * 1000L / span;
                // 轻度平滑：既跟得上变化，又不会数字乱跳
                rate = rate == 0 ? v : (rate + v * 2) / 3;
                lastAt = now;
                bucketStart = now;
                bucketBytes = 0;
            }
        }
    }

    @Override
    public void onTransferEnd(DataSource source, DataSpec dataSpec, boolean isNetwork) {
    }

    /** 当前速率（byte/s）。分片间隙里保留上一份读数，避免界面闪成破折号。 */
    public long bytesPerSecond() {
        synchronized (lock) {
            return rate;
        }
    }

    /** 换台或重连时清零，别把上一路流的速率带过来。 */
    public void reset() {
        synchronized (lock) {
            bucketStart = 0;
            bucketBytes = 0;
            rate = 0;
            lastAt = 0;
        }
    }

    /** 是否有过读数。 */
    public boolean hasSample() {
        synchronized (lock) {
            return lastAt > 0;
        }
    }
}
