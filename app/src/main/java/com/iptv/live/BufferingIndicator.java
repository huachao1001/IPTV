package com.iptv.live;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * 缓冲指示器：旋转进度环 + 缓存百分比 + 实时网速。
 *
 * <p>用自绘而不用 ProgressBar，是为了同时表达"还在转""已经缓存了多少"
 * 和"网络现在有多快"。缓冲时用户最关心的就是这三件事。
 */
public class BufferingIndicator extends View {

    private static final int TRACK_COLOR = 0x33FFFFFF;
    private static final int ARC_COLOR = 0xFF3D8BFD;
    private static final int SPEED_COLOR = 0xB3FFFFFF;

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint speedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();

    /** 0~1；小于 0 表示进度未知，只转圈。 */
    private float progress = -1f;
    /** 不确定进度时，弧线绕圈的起始角度。 */
    private float spin = 0f;
    /** 网速文案，空串表示不显示。 */
    private String speedText = "";

    public BufferingIndicator(Context ctx) {
        this(ctx, null);
    }

    public BufferingIndicator(Context ctx, @Nullable AttributeSet attrs) {
        super(ctx, attrs);
        track.setStyle(Paint.Style.STROKE);
        track.setColor(TRACK_COLOR);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeCap(Paint.Cap.ROUND);
        arc.setColor(ARC_COLOR);
        label.setTextAlign(Paint.Align.CENTER);
        label.setColor(0xFFFFFFFF);
        label.setFakeBoldText(true);
        label.setTextSize(UiScale.pxH(30));
        speedPaint.setTextAlign(Paint.Align.CENTER);
        speedPaint.setColor(SPEED_COLOR);
        speedPaint.setTextSize(UiScale.pxH(24));
    }

    /** @param p 0~1 的缓存进度；传负数表示未知，只显示旋转。 */
    public void setProgress(float p) {
        float v = p < 0 ? -1f : Math.max(0f, Math.min(1f, p));
        if (Math.abs(v - progress) < 0.005f && v >= 0 && progress >= 0) {
            return;
        }
        progress = v;
        invalidate();
    }

    /** 设置网速文案；传 null 或空串则不显示这一行。 */
    public void setSpeedText(@Nullable String text) {
        String t = text == null ? "" : text;
        if (t.equals(speedText)) {
            return;
        }
        speedText = t;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        float stroke = UiScale.pxH(7);
        // 环放在上部正方形区域，网速文案占下方一条
        float ringSize = Math.min(w, h);
        boolean withSpeed = h > ringSize + stroke;
        if (withSpeed) {
            ringSize = Math.min(w, h - UiScale.pxH(34));
        }
        float r = ringSize / 2f - stroke;
        float cx = w / 2f;
        float cy = ringSize / 2f;
        oval.set(cx - r, cy - r, cx + r, cy + r);

        track.setStrokeWidth(stroke);
        arc.setStrokeWidth(stroke);

        c.drawCircle(cx, cy, r, track);

        if (progress >= 0) {
            // 已知进度：从 12 点方向顺时针长出，至少留 3 度让 0% 也有反馈
            c.drawArc(oval, -90f, Math.max(3f, progress * 360f), false, arc);
            String text = Math.round(progress * 100) + "%";
            float ty = cy - (label.descent() + label.ascent()) / 2f;
            c.drawText(text, cx, ty, label);
        } else {
            // 未知进度：一段 90 度的弧绕圈转
            spin = (SystemClock.uptimeMillis() / 4f) % 360f;
            c.drawArc(oval, spin, 90f, false, arc);
            postInvalidateOnAnimation();
        }

        if (withSpeed && !speedText.isEmpty()) {
            // 文字基线落在环下方，留一点间距
            float ty = ringSize + UiScale.pxH(26);
            c.drawText(speedText, cx, ty, speedPaint);
        }
    }

    @Override
    protected void onVisibilityChanged(View changed, int visibility) {
        super.onVisibilityChanged(changed, visibility);
        if (visibility == VISIBLE) {
            invalidate();
        }
    }
}
