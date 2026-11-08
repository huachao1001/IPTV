package com.iptv.live.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * 让「获得焦点的那一行」停在列表<b>垂直正中</b>，切换时带<b>滚动 + 边缘淡出</b>的过渡。
 *
 * <p><b>为什么要自己滚，而不是交给焦点系统。</b>
 * 默认行为是「刚好露出来就够了」：焦点走到最下面一行时，RecyclerView
 * 把下一行滚到只露出一条边就停手。实测（模拟器，卫视分组 34 个频道）
 * 焦点连续按 DOWN 到第 5 行后就<b>再也走不动</b>：
 * <pre>
 *   DN5 -> bounds=[207,611][658,704]   ← 到底了
 *   DN6 -> bounds=[207,611][658,704]   ← 同一行，真按键也一样
 * </pre>
 * 下一行只露出十几像素：焦点搜索找得到它，系统却认为「已经可见」不再滚动，
 * 而那一行又没被完整布局，焦点于是跳不过去。
 *
 * <p><b>居中同时解决两件事：</b>
 * <ol>
 *   <li>焦点行上方和下方都还有完整的行，焦点导航不会卡在视口边缘；</li>
 *   <li>用户能提前看到「下一个是什么频道」——这是看电视列表最基本的需求。</li>
 * </ol>
 *
 * <h3>平滑滚动为什么不用现成的 API</h3>
 *
 * <p>试过两条路，都不行，记在这里免得再走一遍：
 * <ol>
 *   <li>{@code smoothScrollToPosition()} / {@link androidx.recyclerview.widget.LinearSmoothScroller}：
 *       它内置「够可见就不动」的启发式，连按 DOWN 时多数按键只<b>单帧跳 92px</b>，
 *       偶尔才有动画（逐帧追踪 build/scroll.mp4 的结论），用户看到「有时丝滑、有时硬切」。</li>
 *   <li>{@code ValueAnimator} + {@code RecyclerView.scrollBy()} 逐帧驱动：
 *       动画值确实在走（日志里 {@code dy=-204/-111/-18} 都算对了），
 *       但 {@code scrollBy} 会和 RecyclerView 的待布局请求打架——
 *       滚动量被随后的布局按锚点算回去，<b>画面一帧都不动</b>。</li>
 * </ol>
 *
 * <p><b>现在的做法：真实滚动瞬时完成，视觉位移用 {@code translationY} 补。</b>
 * <ol>
 *   <li>用 {@code scrollToPositionWithOffset()} 把目标行立刻摆到正中（一步到位，可靠）；</li>
 *   <li>在布局完成回调里算出「内容实际移动了多少」（{@code delta}）；</li>
 *   <li>把所有行先平移 {@code +delta}（看起来还在旧位置），再动画回 0。</li>
 * </ol>
 * 平移只影响绘制、不影响布局，因此不会被布局管线吞掉，
 * <b>每一次</b>按键都有同样的动画。滚动过程中每帧重算边缘透明度，
 * 行从上下边缘淡入、向边缘淡出，视觉上是连续的内容流而不是硬切。
 *
 * <p>实现上必须同时拦掉系统自己的滚动响应
 * （{@link #requestChildRectangleOnScreen} 返回 false），
 * 否则两套滚动会互相打架：系统滚一点、我们再补回去，画面来回抖。
 */
public final class CenteringLayoutManager extends LinearLayoutManager {

    /** 视口上下边缘的淡出区高度占视口的比例（1/6）。 */
    private static final int FADE_BAND_DIVISOR = 6;
    /** 边缘行的最低不透明度：再低就看不见"下一台是什么"了。 */
    private static final float EDGE_MIN_ALPHA = 0.30f;

    /**
     * 单次过渡的时长上限（毫秒）。
     *
     * <p>跨多行时不能无限拖长，否则连续按上下键会明显滞后、按键排不上队。
     */
    private static final int MAX_ANIM_MS = 260;
    /** 单次过渡的时长下限：太短会觉得是"跳"而不是"滚"。 */
    private static final int MIN_ANIM_MS = 120;

    /**
     * 每像素对应的过渡时长（毫秒）。
     *
     * <p><b>这是"看起来是否丝滑"的关键数字。</b>
     * 一行约 93px，{@code 1.5ms/px ≈ 140ms}，25fps 下约 4 帧，
     * 眼睛能明确看到"内容滚上去"；再短就退化成硬切。
     */
    private static final float MS_PER_PX = 1.5f;

    public CenteringLayoutManager(Context ctx) {
        super(ctx, VERTICAL, false);
    }

    /**
     * 拦掉系统"把请求的矩形滚进视口"的行为。
     *
     * <p>焦点系统移动焦点时会调用它，要求"把这个 View 滚到可见"。
     * 一律返回 false：可见性与滚动动画由本类统一负责，
     * 系统只需要知道"这次不用你滚"。
     */
    @Override
    public boolean requestChildRectangleOnScreen(@NonNull RecyclerView parent,
                                                 @NonNull View child,
                                                 @NonNull Rect rect,
                                                 boolean immediate) {
        return false;
    }

    /** 可视区高度（去掉上下 padding）。还没布局时退回屏幕高度。 */
    private int viewportHeight() {
        int h = getHeight() - getPaddingTop() - getPaddingBottom();
        return h > 0 ? h : UiScale.screenHeight();
    }

    /**
     * 把 {@code position} 那一行<b>直接</b>摆到列表垂直正中，不做过渡。
     *
     * <p>只用于结构性变化：打开菜单、切分组 —— 那种场景内容是整屏换掉的，
     * 逐行滚动没有意义，只会让人觉得卡。
     * 上下键移动焦点请用 {@link #smoothCenterOn(int)}。
     */
    public void centerOn(int position) {
        if (position < 0) {
            return;
        }
        cancelScrollAnim();
        pendingPosition = -1;
        scrollToPositionWithOffset(position, centerOffset(position));
    }

    /**
     * 平滑滚动，把 {@code position} 那一行移到列表垂直正中。
     *
     * <p>滚动本身瞬时完成，视觉位移在 {@link #onLayoutCompleted} 里用
     * {@code translationY} 补一段动画回来（原因见类注释）。
     *
     * <p>位移量用 {@link #contentOffset()}（首个子 View 的位置推算）的前后差值算，
     * <b>不用目标行的 {@code getTop()}</b>：刚移过焦点的那一帧，
     * {@code findViewByPosition(target)} 可能返回 null 或返回尚未重新布局的旧行，
     * 按行顶算会得到 {@code delta=0}，于是"有时候有动画、有时候没有"。
     *
     * <p>目标行还不在窗口里时不做过渡：那一刻画面本来就是整屏换掉，
     * 补一段"滚过去"的动画反而显得拖沓。
     */
    public void smoothCenterOn(int position) {
        if (position < 0) {
            return;
        }
        View row = findViewByPosition(position);
        if (row == null) {
            centerOn(position);
            return;
        }
        // 上一个过渡可能还没跑完。这里不停它，让 pendingOffsetBefore 记的是
        // "真实布局位置"，而 currentOffset 仍在更新——布局回调里用
        // currentOffset + delta 作新起点，位移于是从眼睛看到的当前位置接着走，
        // 不会先弹回去再前进。
        pendingPosition = position;
        pendingOffsetBefore = contentOffset();
        pendingAttempts = 0;
        scrollToPositionWithOffset(position, centerOffset(position));
    }

    /**
     * 估算当前"内容滚动偏移量"（px），用于算本次滚动了多少。
     *
     * <p><b>为什么不量目标行。</b>刚移过焦点的那一帧
     * {@code findViewByPosition(target)} 可能拿到尚未重新布局的旧视图，
     * 量出来的位移是 0，于是"有时候有动画、有时候没有"。
     * 这里改量<b>第一个子 View</b>：它一定存在，且
     * {@code 位置 × 行高 - top} 对均匀行高是严格单调的，
     * 一次滚动的前后差值就是真实位移。
     *
     * <p>{@code LinearLayoutManager.computeVerticalScrollOffset()} 需要传
     * {@code State}，布局回调外拿不到，所以自己算这一份。
     */
    private int contentOffset() {
        View first = getChildAt(0);
        if (first == null) {
            return 0;
        }
        int rowH = averageRowHeight();
        return getPosition(first) * rowH - getDecoratedTop(first);
    }

    /** 已布局行的平均高度；还没布局时按视口估一个。 */
    private int averageRowHeight() {
        int n = getChildCount();
        if (n <= 0) {
            return Math.max(1, viewportHeight() / 5);
        }
        int sum = 0;
        for (int i = 0; i < n; i++) {
            View c = getChildAt(i);
            sum += c == null ? 0 : (c.getBottom() - c.getTop());
        }
        return Math.max(1, sum / n);
    }

    /** 待补偿的瞬时滚动：目标行下标。 */
    private int pendingPosition = -1;
    /** 待补偿的瞬时滚动：滚动前的内容偏移量（px）。 */
    private int pendingOffsetBefore;
    /**
     * 已经等了几次布局。
     *
     * <p>{@code scrollToPositionWithOffset()} 只是<b>挂一个待处理的滚动</b>，
     * 它要到下一次布局才真正生效；而 {@code onLayoutCompleted} 可能先被
     * 一次无关的布局调用（比如落焦引起的重排），那时位移还是 0。
     * 早期版本在这里就把待补偿状态清掉了，导致后面的真实滚动没有任何动画
     * —— 这正是"多数按键硬切、偶尔才滚一下"的最后一块拼图。
     */
    private int pendingAttempts;
    /** 最多容忍几次空布局，超过就放弃补偿（避免状态永远挂着）。 */
    private static final int PENDING_MAX_ATTEMPTS = 4;

    /** 正在跑的位移补偿动画。 */
    @Nullable
    private ValueAnimator offsetAnim;
    /** 当前所有行统一的平移量（px），0 表示没有进行中的过渡。 */
    private float currentOffset;

    /**
     * 把列表内容从 {@code start} 平移量过渡回 0。
     *
     * <p>{@code start} 为正表示"行先往下挪、再回到真实位置"，
     * 对应内容往上滚（按 DOWN 时的情形）。
     */
    private void startOffsetAnim(float start) {
        stopOffsetAnim();
        currentOffset = start;
        // 必须<b>立刻</b>应用到这一帧，不能等 ValueAnimator 的第一次回调：
        // 它要等到下一个 vsync 才跑，于是本次布局的这一帧会先按"终态"画出来
        // （录屏里就是一帧 +94 的跳变），下一帧才补上位移，看起来像"先跳再倒回"。
        applyOffset(currentOffset);
        float total = Math.abs(start);
        if (total < 1f) {
            currentOffset = 0f;
            applyOffset(0f);
            return;
        }
        int ms = (int) Math.min(MAX_ANIM_MS,
                Math.max(MIN_ANIM_MS, total * MS_PER_PX));
        ValueAnimator anim = ValueAnimator.ofFloat(start, 0f);
        anim.setDuration(ms);
        // 减速插值：起手快、落点稳，是列表滚动最自然的手感
        anim.setInterpolator(new DecelerateInterpolator(1.6f));
        anim.addUpdateListener(a -> {
            currentOffset = (Float) a.getAnimatedValue();
            applyOffset(currentOffset);
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                if (offsetAnim == a) {
                    offsetAnim = null;
                    currentOffset = 0f;
                    applyOffset(0f);
                }
            }
        });
        offsetAnim = anim;
        anim.start();
    }

    /**
     * 把所有已布局的行统一平移 {@code dy} 像素，并按新位置重算边缘透明度。
     *
     * <p>平移量对所有行相同，所以行与行之间不会出现缝隙；
     * 视口之外的溢出部分由 RecyclerView 自己裁剪，看起来就是"滚动出界"。
     */
    private void applyOffset(float dy) {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child != null) {
                child.setTranslationY(dy);
            }
        }
        applyEdgeFade();
    }

    /** 只停动画，保留当前平移量（连按时从这里接着滚）。 */
    private void stopOffsetAnim() {
        if (offsetAnim != null) {
            ValueAnimator a = offsetAnim;
            offsetAnim = null;
            a.cancel();
        }
    }

    /**
     * 立即结束进行中的过渡，把行放回真实位置。
     *
     * <p>换分组、打开/关闭菜单这类"内容整体换掉"的操作前调用：
     * 残留的平移量会让新的列表整体错位。
     */
    public void cancelScrollAnim() {
        stopOffsetAnim();
        pendingPosition = -1;
        currentOffset = 0f;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child != null) {
                child.setTranslationY(0f);
            }
        }
    }

    /** 目标行居中时应使用的 {@code scrollToPositionWithOffset} 偏移量。 */
    private int centerOffset(int position) {
        int viewport = viewportHeight();
        View row = findViewByPosition(position);
        int rowH = row != null ? row.getHeight() : 0;
        if (rowH <= 0) {
            // 行还没布局：先按经验值估一个行高，焦点回调触发后会再校正一次
            rowH = Math.max(1, viewport / 5);
        }
        return Math.max(0, (viewport - rowH) / 2);
    }

    /**
     * 布局完成后开始位移补偿。
     *
     * <p>这里必须用 {@code onLayoutCompleted} 而不是自己 {@code post}：
     * 它发生在 {@code dispatchLayout()} 内部、绘制之前，
     * 设完 {@code translationY} 当帧就生效，<b>不会先闪一下终态</b>。
     */
    @Override
    public void onLayoutCompleted(RecyclerView.State state) {
        super.onLayoutCompleted(state);
        if (pendingPosition >= 0) {
            int pos = pendingPosition;
            int before = pendingOffsetBefore;
            int after = contentOffset();
            int delta = after - before;
            if (delta != 0) {
                pendingPosition = -1;
                // 内容已经按 delta 移动了（delta>0 表示内容向上滚）。
                // 要让这一帧"看起来还停在旧位置"，得把行<b>反向</b>平移同样的量：
                // 即 translationY = 旧位置 - 新位置 = -delta 的内容位移
                // → 用 +delta 的绘制位移抵消。然后动画回 0，
                // 视觉上就是内容从旧位置平滑地滑向新位置。
                startOffsetAnim(currentOffset + delta);
            } else if (++pendingAttempts >= PENDING_MAX_ATTEMPTS) {
                // 连续几次布局都没滚起来：目标可能就是当前位置，
                // 不再等下去，免得状态一直挂着影响后续按键。
                pendingPosition = -1;
            }
        } else if (currentOffset != 0f) {
            // 过渡途中又发生了一次布局（adapter 刷新等）：
            // 新绑定的行没有平移量，统一补上，避免画面被撕成两截。
            applyOffset(currentOffset);
            return;
        }
        applyEdgeFade();
    }

    /**
     * 按"离视口中心的距离"给每一行设透明度，做出上下边缘淡入淡出的效果。
     *
     * <p>中间 {@code 2/3} 视口保持全实，上下各 {@code 1/6} 线性淡到
     * {@link #EDGE_MIN_ALPHA}。中间留那么一大段是必要的：
     * 焦点行就停在正中，它周围必须完全清晰可读。
     *
     * <p>位置取的是<b>绘制位置</b>（{@code top + translationY}），
     * 所以过渡动画期间行是真正"滑进淡出区才变淡"，
     * 而不是位置和透明度各走各的。这是"滚动感"的主要来源。
     *
     * <p><b>焦点行永远不淡。</b>列表滚到首尾时焦点行无法居中、必然贴在边缘，
     * 这时把它淡掉等于"选中的那台看不清"，正相反于淡出的目的。
     */
    void applyEdgeFade() {
        int height = getHeight();
        if (height <= 0 || getChildCount() == 0) {
            return;
        }
        int top = getPaddingTop();
        int bottom = height - getPaddingBottom();
        if (bottom <= top) {
            return;
        }
        int band = Math.max(1, (bottom - top) / FADE_BAND_DIVISOR);
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child == null) {
                continue;
            }
            if (child.isFocused() || containsFocus(child)) {
                child.setAlpha(1f);
                continue;
            }
            int center = (child.getTop() + child.getBottom()) / 2
                    + Math.round(child.getTranslationY());
            float alpha = 1f;
            if (center < top + band) {
                float t = Math.max(0f, (center - top) / (float) band);
                alpha = EDGE_MIN_ALPHA + (1f - EDGE_MIN_ALPHA) * t;
            } else if (center > bottom - band) {
                float t = Math.max(0f, (bottom - center) / (float) band);
                alpha = EDGE_MIN_ALPHA + (1f - EDGE_MIN_ALPHA) * t;
            }
            child.setAlpha(Math.max(EDGE_MIN_ALPHA, Math.min(1f, alpha)));
        }
    }

    /** {@code row} 里（含它自己）有没有持有焦点。 */
    private static boolean containsFocus(View row) {
        if (row.hasFocus()) {
            return true;
        }
        if (!(row instanceof android.view.ViewGroup)) {
            return false;
        }
        View focus = row.findFocus();
        return focus != null;
    }

    /**
     * 当前焦点所在行的下标；焦点不在列表里时返回 -1。
     *
     * <p>焦点可能落在行内的子 View 上（列表是
     * {@code FOCUS_AFTER_DESCENDANTS}），所以要沿父链往上找到
     * 真正由本管理器布局的那一层。
     */
    public int focusedPosition(@Nullable View focused) {
        if (focused == null) {
            return -1;
        }
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child == null) {
                continue;
            }
            if (isSameOrAncestor(child, focused)) {
                return getPosition(child);
            }
        }
        return -1;
    }

    /** {@code candidate} 是不是 {@code v} 自己或它的祖先。 */
    private static boolean isSameOrAncestor(View candidate, View v) {
        View f = v;
        while (f != null) {
            if (f == candidate) {
                return true;
            }
            Object p = f.getParent();
            if (!(p instanceof View)) {
                return false;
            }
            f = (View) p;
        }
        return false;
    }
}
