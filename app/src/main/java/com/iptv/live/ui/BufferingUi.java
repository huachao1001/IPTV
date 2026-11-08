package com.iptv.live.ui;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;

import androidx.annotation.Nullable;

import com.iptv.live.play.BufferGovernor;
import com.iptv.live.play.LiveSpeedMeter;
import com.iptv.live.play.LoadSpeedMeter;

/**
 * 缓冲指示器的"开关管家"：决定什么时候该转圈、什么时候不该转。
 *
 * <p><b>它解决的是"缓冲圈一直闪"。</b>原实现是
 * {@code onPlaybackStateChanged(STATE_BUFFERING)} 里无条件
 * {@code loading.setVisibility(VISIBLE)}，{@code STATE_READY} 再隐藏。
 * 直播流的缓冲状态本来就会频繁进出：分片边界、追直播偏移、
 * 极短暂的预读空档都会切一次。一次 200~400 毫秒的缓冲也跟着弹一个转圈，
 * 于是屏幕上就是<b>一圈一圈地闪</b>——用户以为一直没播好，
 * 实际上画面根本没断。
 *
 * <p><b>规则一：短暂缓冲不显示。</b>已经出过画面之后进缓冲，先等
 * {@link #SHOW_DELAY_MS}。这期间回到 READY 就当没发生过，一个像素都不画。
 * 只有真的卡住了才显示——那才是用户需要看到"在转"的时刻。
 *
 * <p><b>规则二：起播必须立刻显示。</b>还没出过画面时，屏幕是黑的，
 * 不给任何反馈用户就以为按了没反应。这种"首次等待"要马上转圈。
 *
 * <p><b>规则三：进度分母跟着缓冲档位走。</b>环形进度以"当前档位攒够起播
 * 所需的缓冲"为 100%。原来写死 10 秒，档位降到 4 秒起播时进度条
 * 永远到不了满格、用户看着像卡住；档位升到 8 秒时又早早满格却不出画面。
 */
public class BufferingUi {

    private static final String TAG = "IPTVBUBBLE";

    /**
     * 出过画面之后，缓冲持续多久才显示转圈。
     *
     * <p>取 1.2 秒有实测依据：正常分片边界造成的空档在 400 毫秒以内，
     * 追直播的一次微调也在 1 秒以内；而真的"喂不动"必然是数秒级。
     * 1.2 秒既能把所有正常抖动挡在门外，又不会让真卡顿迟迟没反馈。
     */
    private static final long SHOW_DELAY_MS = 1_200L;

    /** 进度环的刷新间隔。200ms 对"还要等多久"这种人眼判断足够平滑。 */
    private static final long TICK_MS = 200L;

    private final Handler ui = new Handler(Looper.getMainLooper());
    @Nullable
    private final BufferingIndicator view;
    @Nullable
    private final BufferGovernor governor;

    /** 是否出过画面。第一次起播与后续重缓冲的显示策略不同。 */
    private boolean hasPlayed;
    /** 延迟显示的定时任务；{@code null} 表示没有排队的显示。 */
    @Nullable
    private Runnable pendingShow;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            BufferingIndicator v = view;
            if (v == null || v.getVisibility() != View.VISIBLE) {
                return;
            }
            v.setProgress(progressFraction());
            v.setSpeedText(speedText());
            ui.postDelayed(this, TICK_MS);
        }
    };

    public BufferingUi(@Nullable BufferingIndicator view, @Nullable BufferGovernor governor) {
        this.view = view;
        this.governor = governor;
    }

    /**
     * 进入缓冲态。
     *
     * @param firstPlay 还没出过画面（换台后的第一次起播）。为 {@code true} 时立刻显示
     */
    public void onBuffering(boolean firstPlay) {
        BufferingIndicator v = view;
        if (v == null) {
            return;
        }
        if (firstPlay) {
            // 屏幕还是黑的，必须立刻给反馈
            cancelPendingShow();
            v.setProgress(-1f);
            v.setVisibility(View.VISIBLE);
            ui.removeCallbacks(tick);
            ui.post(tick);
            Log.i(TAG, "show first-play");
            return;
        }
        if (v.getVisibility() == View.VISIBLE) {
            // 已经在显示了，继续刷进度即可，不要重新排一个延迟
            ui.removeCallbacks(tick);
            ui.post(tick);
            return;
        }
        if (pendingShow != null) {
            return;
        }
        // 短暂缓冲先不画：这一路很可能是分片边界的零点几秒空档，
        // 立刻回 READY 的话用户根本不该看到转圈
        pendingShow = () -> {
            pendingShow = null;
            BufferingIndicator target = view;
            if (target == null) {
                return;
            }
            target.setProgress(-1f);
            target.setVisibility(View.VISIBLE);
            ui.removeCallbacks(tick);
            ui.post(tick);
            Log.i(TAG, "show after " + SHOW_DELAY_MS + "ms");
        };
        ui.postDelayed(pendingShow, SHOW_DELAY_MS);
    }

    /** 回到可播状态：立刻取消待显示的转圈并隐藏。 */
    public void onReady() {
        boolean wasPending = pendingShow != null;
        boolean wasVisible = view != null && view.getVisibility() == View.VISIBLE;
        hasPlayed = true;
        cancelPendingShow();
        ui.removeCallbacks(tick);
        BufferingIndicator v = view;
        if (v != null) {
            v.setVisibility(View.GONE);
        }
        if (wasVisible) {
            Log.i(TAG, "hide");
        } else if (wasPending) {
            // 这一行是"缓冲圈不再闪"的直接证据：
            // 短暂缓冲被延迟窗口挡掉了，压根没有画出来
            Log.i(TAG, "suppressed short bubble");
        }
    }

    /** 换台/重新起播：重置为"还没出画面"，并清掉残留界面。 */
    public void reset() {
        hasPlayed = false;
        cancelPendingShow();
        ui.removeCallbacks(tick);
        BufferingIndicator v = view;
        if (v != null) {
            v.setVisibility(View.GONE);
            v.setProgress(-1f);
        }
    }

    /** 直接隐藏（放弃播放、出错等场景），不改变 {@link #hasPlayed}。 */
    public void hide() {
        cancelPendingShow();
        ui.removeCallbacks(tick);
        BufferingIndicator v = view;
        if (v != null) {
            v.setVisibility(View.GONE);
        }
    }

    /** 立刻显示（用户在设置里手动切清晰度等有明确等待意愿的场景）。 */
    public void showNow() {
        cancelPendingShow();
        BufferingIndicator v = view;
        if (v == null) {
            return;
        }
        v.setProgress(-1f);
        v.setVisibility(View.VISIBLE);
        ui.removeCallbacks(tick);
        ui.post(tick);
    }

    /** 退出界面时必须调，否则 Handler 会一直持有 View，泄漏整页。 */
    public void release() {
        cancelPendingShow();
        ui.removeCallbacks(tick);
    }

    public boolean hasPlayed() {
        return hasPlayed;
    }

    /**
     * 环形进度：以当前档位的起播门槛为 100%。
     *
     * <p>分母跟着 {@link BufferGovernor} 走，进度条才和"还要等多久"对得上。
     * 拿不到播放器或管家时返回 -1（只转圈、不显示比例），比给一个错的
     * 比例好——错的比例会让人以为快好了，结果一直不开始。
     */
    private float progressFraction() {
        PlayerSource src = player;
        if (src == null || governor == null) {
            return -1f;
        }
        long gate = hasPlayed ? governor.rebufferGateMs() : governor.playGateMs();
        if (gate <= 0) {
            return -1f;
        }
        long buffered = src.bufferedMs();
        if (buffered < 0) {
            return -1f;
        }
        if (buffered == 0) {
            return 0f;
        }
        // 封顶在 98%：真的攒够了播放器会立刻转 READY、转圈直接消失。
        // 若让它显示 100% 却还停在缓冲，用户看到的是一个静止的满环，
        // 反而像"卡死了"。
        return Math.min(0.98f, buffered / (float) gate);
    }

    /**
     * 缓冲环下方的文案：有实测速率就显示速率，刚开始连流时给一句可读的状态。
     *
     * <p>速率来自 {@link LiveSpeedMeter}（逐块回调，跨换台不重置），
     * 空闲样本由 {@link LoadSpeedMeter} 归一化成人能读的单位。
     */
    private String speedText() {
        LiveSpeedMeter m = LiveSpeedMeter.INSTANCE;
        long bps = m.bytesPerSecond();
        if (bps > 0) {
            return LoadSpeedMeter.format(bps);
        }
        return m.hasSample() ? "0 KB/s" : "连接中";
    }

    private void cancelPendingShow() {
        if (pendingShow != null) {
            ui.removeCallbacks(pendingShow);
            pendingShow = null;
        }
    }

    /**
     * 当前播放器。
     *
     * <p>这里不持有 {@code ExoPlayer} 引用，而是每次读一个回填进来的
     * {@link Provider}——播放器会在 {@code onDestroy} 里 release 并置空，
     * 管家若抓着旧引用，退出页面后 Handler 的一次 tick 就会碰到已释放的播放器。
     */
    @Nullable
    private PlayerSource player;

    /** 由宿主注入"怎么拿到播放器"。 */
    public interface PlayerSource {
        /** 当前缓冲时长（毫秒）；拿不到时返回 -1。 */
        long bufferedMs();
    }

    public void setPlayerSource(@Nullable PlayerSource source) {
        this.player = source;
    }
}
