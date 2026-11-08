package com.iptv.live.ui;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 调试用虚拟遥控器：方向键 + OK + 返回，浮在画面右下角。
 *
 * <p>模拟器上调试只能靠 {@code adb shell input keyevent}，每换一次按键就要
 * 重连一次，验证"面板打开时按上下键"这类焦点行为尤其费劲。这里直接在画面上
 * 叠一组按键，点一下就走 {@link Activity#dispatchKeyEvent(KeyEvent)} ——
 * 和实体遥控器<b>完全同一条代码路径</b>（onKeyDown / 焦点系统 / 长按），
 * 测出来的行为才算数，所以不要改成直接调 {@code openDrawer()} 之类的方法。
 *
 * <p>只在 debuggable 包（{@code assembleDebug}）里安装：release 包
 * 在 {@link #attach} 第一行就返回，连视图都不会创建，用户机上不会出现。
 *
 * <p>面板可以按住标题栏拖动：调试时它可能挡住右侧清晰度面板，
 * 拖开即可，不用先去收起。
 */
public final class VirtualRemote {

    /** 与 colors.xml 的 accent 保持一致。 */
    private static final int ACCENT = 0xFF3D8BFD;
    private static final int ACCENT_PRESSED = 0xFF5AA0FF;
    private static final int PANEL_BG = 0xE60B0F16;
    private static final int KEY_BG = 0x26FFFFFF;
    private static final int KEY_PRESSED = 0xFF3D8BFD;
    private static final int EDGE = 0x33FFFFFF;

    private final Activity activity;
    private final ViewGroup parent;
    /** 外层容器：宽高跟随当前显示的子视图（面板或收起后的胶囊）。 */
    private final FrameLayout box;
    private final LinearLayout pad;
    private final TextView chip;

    /** 用户拖动过就不再自动回到右下角，免得一按键又跳回去。 */
    private boolean dragged;

    /** 当前 Activity 上挂着的那一个，供设置面板里的开关调用。 */
    private static VirtualRemote current;

    /**
     * 右侧需要让出的宽度（像素）。
     *
     * <p>清晰度面板贴右缘且垂直居中，默认停在右下角的遥控器必然会压住它。
     * MainActivity 在面板开合时把面板宽度告诉这里，遥控器就横移到面板左侧、
     * 正好落进"频道列表右缘 ~ 清晰度面板左缘"那段空隙里。
     */
    private int reservedRight;

    private VirtualRemote(Activity activity, ViewGroup parent) {
        this.activity = activity;
        this.parent = parent;
        Context ctx = activity;

        box = new FrameLayout(ctx);
        box.setClipChildren(false);
        box.setClipToPadding(false);

        // 收起后留下的小胶囊：再点一次把面板叫回来
        chip = new TextView(ctx);
        chip.setText("遥控器");
        chip.setTextColor(0xFFFFFFFF);
        chip.setGravity(Gravity.CENTER);
        chip.setTypeface(chip.getTypeface(), Typeface.BOLD);
        UiScale.text(chip, 20);
        UiScale.padding(chip, 22, 12, 22, 12);
        chip.setBackground(round(0xCC0B0F16, UiScale.px(20), UiScale.pxW(1), EDGE));
        chip.setClickable(true);
        chip.setFocusable(false);
        chip.setOnClickListener(v -> toggle(true));
        chip.setVisibility(View.GONE);
        box.addView(chip, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        pad = buildPad(ctx);
        box.addView(pad, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /**
     * 调试包往根布局挂一个虚拟遥控器；release 包什么也不做。
     *
     * <p>{@code FLAG_DEBUGGABLE} 就是 buildType 的 debuggable 开关，
     * 比引 {@code BuildConfig.DEBUG} 少改一处 gradle 配置。
     */
    public static void attach(Activity activity, ViewGroup root) {
        if (activity == null || root == null) {
            return;
        }
        boolean debug = (activity.getApplicationInfo().flags
                & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        if (!debug) {
            return;
        }
        if (current != null) {
            current.detach();
        }
        current = new VirtualRemote(activity, root);
        current.install();
    }

    /**
     * 调试包才有虚拟遥控器；设置面板据此决定要不要显示开关。
     *
     * <p>release 包返回 false，设置里那一项<b>根本不会出现</b>，
     * 而不是出现一个点了没反应的死开关。
     */
    public static boolean available() {
        return current != null;
    }

    /** 当前是否展开。 */
    public static boolean isOpen() {
        return current != null && current.pad.getVisibility() == View.VISIBLE;
    }

    /** 供设置面板切换显示状态。 */
    public static void setOpen(boolean open) {
        if (current != null) {
            current.toggle(open);
        }
    }

    /**
     * 告诉遥控器右侧被谁占了多少像素。
     *
     * <p>清晰度面板打开时传面板宽度，遥控器会横移到它左边；
     * 关掉时传 0，回到屏幕右下角。用户手动拖过就没必要再挪——
     * 那时位置是他自己选的，自动跳回去反而烦。
     */
    public static void setReservedRight(int px) {
        if (current == null || current.reservedRight == px) {
            return;
        }
        current.reservedRight = px;
        if (!current.dragged) {
            current.place();
        }
    }

    private void detach() {
        if (box.getParent() instanceof ViewGroup) {
            ((ViewGroup) box.getParent()).removeView(box);
        }
    }

    /**
     * Activity 销毁时清掉静态引用。
     *
     * <p>{@link #current} 是 static 的（设置面板要跨实例开关），
     * 不清就会把整条 Activity 泄漏到下一次启动。
     */
    public static void release(Activity activity) {
        if (current == null) {
            return;
        }
        if (activity == null || current.activity == activity) {
            current.detach();
            current = null;
        }
    }

    private void install() {
        // 最后 addView ⇒ 处在 z 序最上层：抽屉、清晰度面板都能盖住，
        // 否则抽屉打开后按键被它挡住，正需要按键导航时反而点不到
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        parent.addView(box, lp);
        place();
    }

    /* ---------------- 面板 ---------------- */

    private LinearLayout buildPad(Context ctx) {
        final int keyW = UiScale.px(84);
        final int keyH = UiScale.px(74);
        final int gap = UiScale.px(10);

        LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.setBackground(round(PANEL_BG, UiScale.px(14), UiScale.pxW(1), EDGE));
        UiScale.padding(panel, 16, 12, 16, 16);

        // 标题栏兼拖拽把手
        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(0, 0, 0, UiScale.pxH(8));

        TextView title = new TextView(ctx);
        title.setText("虚拟遥控");
        title.setTextColor(0xFFB8C2D4);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        title.setLetterSpacing(0.12f);
        UiScale.text(title, 18);
        head.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView hide = new TextView(ctx);
        hide.setText("收起");
        hide.setTextColor(0xFFB8C2D4);
        UiScale.text(hide, 18);
        UiScale.padding(hide, 12, 4, 12, 4);
        hide.setClickable(true);
        hide.setFocusable(false);
        hide.setOnClickListener(v -> toggle(false));
        head.addView(hide, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        panel.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        head.setOnTouchListener(new DragHandle());

        LinearLayout upRow = centeredRow(ctx);
        upRow.addView(key(ctx, "▲", KeyEvent.KEYCODE_DPAD_UP, keyW, keyH, false),
                cell(keyW, keyH, 0, 0));
        panel.addView(upRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout midRow = centeredRow(ctx);
        midRow.addView(key(ctx, "◀", KeyEvent.KEYCODE_DPAD_LEFT, keyW, keyH, false),
                cell(keyW, keyH, 0, gap));
        midRow.addView(key(ctx, "OK", KeyEvent.KEYCODE_DPAD_CENTER, keyW, keyH, true),
                cell(keyW, keyH, 0, gap));
        midRow.addView(key(ctx, "▶", KeyEvent.KEYCODE_DPAD_RIGHT, keyW, keyH, false),
                cell(keyW, keyH, 0, 0));
        panel.addView(midRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout downRow = centeredRow(ctx);
        downRow.addView(key(ctx, "▼", KeyEvent.KEYCODE_DPAD_DOWN, keyW, keyH, false),
                cell(keyW, keyH, 0, 0));
        panel.addView(downRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        int wide = keyW * 3 + gap * 2;
        LinearLayout backRow = centeredRow(ctx);
        backRow.addView(key(ctx, "返回", KeyEvent.KEYCODE_BACK, wide, UiScale.px(62), false),
                cell(wide, UiScale.px(62), 0, 0));
        panel.addView(backRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        return panel;
    }

    private static LinearLayout centeredRow(Context ctx) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_HORIZONTAL);
        return row;
    }

    private static LinearLayout.LayoutParams cell(int w, int h, int left, int right) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.leftMargin = left;
        lp.rightMargin = right;
        return lp;
    }

    private TextView key(Context ctx, String label, final int keyCode,
                         int w, int h, boolean accent) {
        TextView v = new TextView(ctx);
        v.setText(label);
        v.setTextColor(0xFFFFFFFF);
        v.setGravity(Gravity.CENTER);
        v.setTypeface(v.getTypeface(), Typeface.BOLD);
        UiScale.text(v, 24);
        v.setBackground(keyBackground(accent));
        // 不可聚焦：否则方向键会把焦点吸到按键上，测出来的焦点行为就不是 App 的了
        v.setFocusable(false);
        v.setClickable(true);
        v.setOnClickListener(x -> send(keyCode));
        return v;
    }

    /** OK 键常亮主色，其余键半透明；按下统一变亮蓝，点击有反馈。 */
    private static StateListDrawable keyBackground(boolean accent) {
        StateListDrawable d = new StateListDrawable();
        d.addState(new int[]{android.R.attr.state_pressed},
                round(accent ? ACCENT_PRESSED : KEY_PRESSED, UiScale.px(8), 0, 0));
        d.addState(new int[]{},
                round(accent ? ACCENT : KEY_BG, UiScale.px(8), 0, 0));
        return d;
    }

    private static GradientDrawable round(int color, int radius, int strokeW, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(color);
        g.setCornerRadius(radius);
        if (strokeW > 0) {
            g.setStroke(strokeW, strokeColor);
        }
        return g;
    }

    /* ---------------- 按键分发 ---------------- */

    /**
     * 按键分发，等价于实体遥控器按住再松开。
     *
     * <p>必须成对发 DOWN/UP：只发 DOWN 会让 {@code onKeyDown} 收不到
     * 收尾状态，长按重复也会一直挂着。
     *
     * <p><b>不能只调 {@code Activity.dispatchKeyEvent()}。</b>那条路只走到
     * {@code onKeyDown}，完全绕过了 {@code ViewRootImpl} 的
     * {@code performFocusNavigation()}——而<b>方向键的焦点跳动正发生在那里</b>。
     * 后果是：真遥控器在清晰度面板里按上下能换档，虚拟遥控器按上下毫无反应。
     *
     * <p>实测证据（模拟器，焦点停在 576P）：
     * <pre>
     *   adb shell input keyevent 19   → 焦点移动到 720P   ✅ 真按键
     *   点虚拟遥控器 UP                → 焦点仍在 576P    ❌ 假按键
     * </pre>
     *
     * <p>本项目 {@code onKeyDown} 对"菜单内的上下键"是
     * {@code return super.onKeyDown(...)}（交还给焦点系统），
     * 所以少了焦点导航这一步，虚拟遥控器就等于半个残废。
     * 这种"测试工具比真机弱"的偏差最危险——它会让人误以为功能是好的。
     *
     * <p>因此这里按 {@code ViewRootImpl.ViewPostImeInputStage} 的真实顺序分发：
     * <ol>
     *   <li>视图树先消费（{@code DecorView.superDispatchKeyEvent}）；</li>
     *   <li>没人要才交给 {@code Activity.onKeyDown}；</li>
     *   <li>仍没人要，就做焦点导航——和系统一样用 {@code focusSearch} +
     *       {@code requestFocus}。</li>
     * </ol>
     */
    private void send(int keyCode) {
        long now = SystemClock.uptimeMillis();
        KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0);
        KeyEvent up = new KeyEvent(now, now + 10, KeyEvent.ACTION_UP, keyCode, 0);
        dispatchLikeRealRemote(down);
        dispatchLikeRealRemote(up);
    }

    /** 单条事件按系统输入链路分发，见 {@link #send(int)}。 */
    private void dispatchLikeRealRemote(KeyEvent event) {
        // 先交给 Activity（内部会到 onKeyDown 分流：开菜单、换台、
        // 面板间跳焦点……这些都返回 true，就不会再走焦点导航，和真机一致）
        if (activity.dispatchKeyEvent(event)) {
            return;
        }
        // 没人消费的方向键 DOWN：补上 ViewRootImpl 的焦点导航
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            navigateFocus(event);
        }
    }

    /**
     * 补上 {@code ViewRootImpl.performFocusNavigation()} 那一步。
     *
     * <p>方向键在系统里从来不是"直接调 onKeyDown"就完事的：视图树和
     * Activity 都不消费时，由 ViewRootImpl 沿 {@code focusSearch()} 找下一个
     * 可聚焦控件再 {@code requestFocus()}。少了它，菜单内的上下键不生效。
     *
     * <p>触摸模式下普通 {@code requestFocus()} 会被静默拒绝（点过虚拟遥控器
     * 就处于这个状态），所以失败时回退 {@code requestFocusFromTouch()}——
     * 真遥控器按键本来就会把窗口带出触摸模式，这里补齐同样的效果。
     */
    private void navigateFocus(KeyEvent event) {
        int direction;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP:
                direction = View.FOCUS_UP;
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                direction = View.FOCUS_DOWN;
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                direction = View.FOCUS_LEFT;
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                direction = View.FOCUS_RIGHT;
                break;
            default:
                return;
        }
        View focused = activity.getCurrentFocus();
        if (focused == null) {
            return;
        }
        View next = focused.focusSearch(direction);
        if (next == null || next == focused) {
            return;
        }
        if (!next.requestFocus(direction)) {
            next.requestFocusFromTouch();
        }
    }

    /* ---------------- 显示 / 位置 ---------------- */

    private void toggle(boolean show) {
        pad.setVisibility(show ? View.VISIBLE : View.GONE);
        chip.setVisibility(show ? View.GONE : View.VISIBLE);
        // 两种形态宽高差很多，重新摆一次，否则展开后会掉到屏幕外
        place();
    }

    /** 默认停在右下角、底栏上方；拖动过就只做边界钳制。 */
    private void place() {
        if (dragged) {
            clamp();
            return;
        }
        if (!(box.getLayoutParams() instanceof FrameLayout.LayoutParams)) {
            return;
        }
        // 用 BOTTOM|END 锚定，而不是自己量宽高算 leftMargin：
        // 刚 addView 时 getWidth()/getHeight() 还是 0，算出来的位置会飘到画面中间。
        // 锚在右下角则无论面板和胶囊怎么切换尺寸都自动贴边。
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) box.getLayoutParams();
        lp.gravity = Gravity.BOTTOM | Gravity.END;
        lp.leftMargin = 0;
        lp.topMargin = 0;
        // reservedRight 是清晰度面板占掉的宽度：面板贴右缘时遥控器要横移出去，
        // 否则必然压住面板下半截
        lp.rightMargin = UiScale.pxW(24) + reservedRight;
        // 留出底部信息条的高度，别把"台标 + 台名"整个盖住
        lp.bottomMargin = UiScale.pxH(150);
        box.setLayoutParams(lp);
    }

    private void clamp() {
        if (!(box.getLayoutParams() instanceof FrameLayout.LayoutParams)) {
            return;
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) box.getLayoutParams();
        int maxLeft = Math.max(0, parent.getWidth() - box.getWidth());
        int maxTop = Math.max(0, parent.getHeight() - box.getHeight());
        lp.leftMargin = Math.min(Math.max(0, lp.leftMargin), maxLeft);
        lp.topMargin = Math.min(Math.max(0, lp.topMargin), maxTop);
        box.setLayoutParams(lp);
    }

    /** 按住标题栏拖动整个面板，避免挡住右侧清晰度面板。 */
    private final class DragHandle implements View.OnTouchListener {
        private float downRawX;
        private float downRawY;
        private int startLeft;
        private int startTop;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            if (!(box.getLayoutParams() instanceof FrameLayout.LayoutParams)) {
                return false;
            }
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) box.getLayoutParams();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    // 默认锚在 BOTTOM|END，拖动前先换算成左上角绝对坐标，
                    // 否则改 leftMargin 完全看不出效果（边距是从右/下算的）
                    dragged = true;
                    lp.gravity = Gravity.TOP | Gravity.START;
                    lp.leftMargin = box.getLeft();
                    lp.topMargin = box.getTop();
                    lp.rightMargin = 0;
                    lp.bottomMargin = 0;
                    box.setLayoutParams(lp);
                    downRawX = e.getRawX();
                    downRawY = e.getRawY();
                    startLeft = lp.leftMargin;
                    startTop = lp.topMargin;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    lp.leftMargin = startLeft + (int) (e.getRawX() - downRawX);
                    lp.topMargin = startTop + (int) (e.getRawY() - downRawY);
                    box.setLayoutParams(lp);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    clamp();
                    return true;
                default:
                    return false;
            }
        }
    }
}
