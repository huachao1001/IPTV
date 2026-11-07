package com.iptv.live;

import android.content.Context;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/**
 * 电视尺寸换算：以屏幕物理像素为基准，完全绕开 density 上报。
 * 同样是 1920x1080 的电视，有的上报 240dpi、有的 320dpi，按 dp 算会差 33%。
 */
public final class UiScale {

    /** 设计基准：1920x1080。所有 px(x) 的 x 都按这个分辨率给出。 */
    private static final float BASE_W = 1920f;
    private static final float BASE_H = 1080f;

    private static int screenW = 1920;
    private static int screenH = 1080;
    /** 横向、纵向各自独立缩放，超宽屏不会被压扁。 */
    private static float fx = 1f;
    private static float fy = 1f;

    private UiScale() {
    }

    public static void init(Context ctx) {
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        screenW = dm.widthPixels > 0 ? dm.widthPixels : 1920;
        screenH = dm.heightPixels > 0 ? dm.heightPixels : 1080;
        fx = screenW / BASE_W;
        fy = screenH / BASE_H;
    }

    public static int screenWidth() {
        return screenW;
    }

    public static int screenHeight() {
        return screenH;
    }

    /**
     * 按 1080p 基准换算像素：短边取横向、长边取纵向，cell 尺寸这类
     * 要同时适配两个方向的值用它。
     */
    public static int px(float at1080) {
        return Math.round(at1080 * Math.min(fx, fy));
    }

    /** 只按宽度换算，横向排列的间距、宽度用它。 */
    public static int pxW(float at1920) {
        return Math.round(at1920 * fx);
    }

    /** 只按高度换算，行高、字号、纵向间距用它。 */
    public static int pxH(float at1080) {
        return Math.round(at1080 * fy);
    }

    /** 屏幕宽度百分比。 */
    public static int pctW(float percent) {
        return Math.round(screenW * percent / 100f);
    }

    /** 屏幕高度百分比。 */
    public static int pctH(float percent) {
        return Math.round(screenH * percent / 100f);
    }

    /** 字号走纵向基准，保证和行高一起涨落。 */
    public static void text(TextView v, float at1080) {
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, pxH(at1080));
    }

    /** 同时设置宽高（1080p 基准，短边对齐）。 */
    public static void size(View v, float w, float h) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        lp.width = px(w);
        lp.height = px(h);
        v.setLayoutParams(lp);
    }

    /** 设置四边内边距，横向按宽、纵向按高分别换算。 */
    public static void padding(View v, float l, float t, float r, float b) {
        v.setPadding(pxW(l), pxH(t), pxW(r), pxH(b));
    }

    /** 设置外边距。 */
    public static void margin(View v, float l, float t, float r, float b) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
            m.leftMargin = pxW(l);
            m.topMargin = pxH(t);
            m.rightMargin = pxW(r);
            m.bottomMargin = pxH(b);
            v.setLayoutParams(m);
        }
    }

    /** 只改宽（横向铺满的元素用它，比如左栏分组导航）。 */
    public static void widthPct(View v, float percent) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        lp.width = pctW(percent);
        v.setLayoutParams(lp);
    }
}
