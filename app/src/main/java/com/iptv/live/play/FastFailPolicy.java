package com.iptv.live.play;

import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;

/**
 * 直播换源策略：加载失败立刻上报，交给上层换下一路。
 * ExoPlayer 默认对直播重试 6 次并指数退避，死源会白等 20 多秒。
 */
public final class FastFailPolicy extends DefaultLoadErrorHandlingPolicy {

    /** 只给一次极短重试，容忍网络抖动但不等死源。 */
    private static final int RETRY_COUNT = 1;

    public FastFailPolicy() {
        super(RETRY_COUNT);
    }

    @Override
    public long getRetryDelayMsFor(LoadErrorInfo info) {
        // 不做指数退避，失败就尽快交给换源逻辑
        return 300;
    }

    @Override
    public int getMinimumLoadableRetryCount(int dataType) {
        return RETRY_COUNT;
    }
}
