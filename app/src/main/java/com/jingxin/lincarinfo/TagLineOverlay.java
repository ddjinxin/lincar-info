package com.jingxin.lincarinfo;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import java.util.ArrayList;
import java.util.List;

/**
 * 车图标注层：标注点 + 光影流动连接线。
 * 线体为半透明底色，一段亮色光斑从车图端流向标签端循环往复（光影动画线）；
 * 正常=青色光流，异常=红色光流。标注点保留红点。
 * 通过 setLink(dotX, dotY, tag, alert) 传入配对；标签布局完成后调 apply() 触发重绘。
 */
public class TagLineOverlay extends View {

    /** 一对连接：标注点(车图上) —— 标签(线终点为标签中心) */
    private static class Link {
        final float dotX, dotY;
        final View tag;
        boolean alert;
        final boolean lineAlways;   // true=连线始终可见（胎压），false=仅异常时可见（门窗/后备箱）
        Link(float dotX, float dotY, View tag, boolean lineAlways) {
            this.dotX = dotX; this.dotY = dotY; this.tag = tag; this.lineAlways = lineAlways;
        }
    }

    private final List<Link> links = new ArrayList<>();
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);   // 线底色
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);   // 流动光斑
    private boolean ready;
    private ValueAnimator flowAnim;
    /** 流动进度 0→1（一条线内的相位） */
    private float flow = 0f;

    private static final int COLOR_NORMAL = 0xFF00E5FF;   // 青色
    private static final int COLOR_ALERT = 0xFFFF5A5A;   // 红色
    private static final int LINE_NORMAL = 0x5900E5FF;   // 线底：低透明青
    private static final int LINE_ALERT = 0x59FF5A5A;    // 线底：低透明红
    /** 光斑覆盖长度占线段比例 */
    private static final float GLOW_SPAN = 0.28f;
    /** 流动周期 ms */
    private static final long PERIOD = 1800L;

    public TagLineOverlay(Context c, AttributeSet a) {
        super(c, a);
        basePaint.setStyle(Paint.Style.STROKE);
        basePaint.setStrokeWidth(2f);
        basePaint.setPathEffect(new DashPathEffect(new float[]{6f, 6f}, 0f));
        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeWidth(3f);          // 光斑略粗，压出高亮感
        glowPaint.setStrokeCap(Paint.Cap.ROUND);
        dotPaint.setStyle(Paint.Style.FILL);
    }

    public void clear() {
        links.clear();
        ready = false;
        invalidate();
    }

    /** 传入一对连接（红点坐标相对本 view） */
    public void setLink(float dotX, float dotY, View tag, boolean alert) {
        setLink(dotX, dotY, tag, alert, false);
    }

    /** 传入一对连接，lineAlways=true 时连线始终可见（胎压用） */
    public void setLink(float dotX, float dotY, View tag, boolean alert, boolean lineAlways) {
        for (Link l : links) {
            if (l.tag == tag) {   // 已有配对：只更新异常标记
                l.alert = alert;
                return;
            }
        }
        Link l = new Link(dotX, dotY, tag, lineAlways);
        l.alert = alert;
        links.add(l);
    }

    /** 全部配对完成后调用，触发重绘 */
    public void apply() {
        ready = true;
        startFlow();
        invalidate();
    }

    private void startFlow() {
        if (flowAnim != null && flowAnim.isRunning()) return;
        flowAnim = ValueAnimator.ofFloat(0f, 1f);
        flowAnim.setDuration(PERIOD);
        flowAnim.setRepeatCount(ValueAnimator.INFINITE);
        flowAnim.setInterpolator(new LinearInterpolator());
        flowAnim.addUpdateListener(an -> {
            flow = (float) an.getAnimatedValue();
            invalidate();
        });
        flowAnim.start();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (ready) startFlow();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (flowAnim != null) {
            flowAnim.cancel();
            flowAnim = null;
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!ready) return;
        float dotR = 1.25f * getResources().getDisplayMetrics().density;
        for (Link l : links) {
            if (l.tag == null || l.tag.getWidth() == 0) continue;
            // 标注点始终可见
            float cx = l.tag.getLeft() + l.tag.getWidth() / 2f;
            float cy = l.tag.getTop() + l.tag.getHeight() / 2f;
            int cFull = l.alert ? COLOR_ALERT : COLOR_NORMAL;
            int cBase = l.alert ? LINE_ALERT : LINE_NORMAL;

            // 连线：胎压始终可见，门窗/后备箱仅异常时可见
            boolean showLine = l.alert || l.lineAlways;
            if (showLine) {
                // 线底：半透明虚线
                basePaint.setColor(cBase);
                canvas.drawLine(l.dotX, l.dotY, cx, cy, basePaint);

                // 流动光斑
                float glowHead = flow * (1f + GLOW_SPAN) - GLOW_SPAN;
                float t0 = glowHead, t1 = glowHead + GLOW_SPAN;
                t0 = Math.max(0f, Math.min(1f, t0));
                t1 = Math.max(0f, Math.min(1f, t1));
                if (t1 - t0 > 0.001f) {
                    float sx = l.dotX + (cx - l.dotX) * t0;
                    float sy = l.dotY + (cy - l.dotY) * t0;
                    float ex = l.dotX + (cx - l.dotX) * t1;
                    float ey = l.dotY + (cy - l.dotY) * t1;
                    int tail = (cFull & 0x00FFFFFF);
                    glowPaint.setShader(new LinearGradient(
                            sx, sy, ex, ey,
                            new int[]{cFull, cFull, tail},
                            new float[]{0f, 0.65f, 1f}, Shader.TileMode.CLAMP));
                    canvas.drawLine(sx, sy, ex, ey, glowPaint);
                    glowPaint.setShader(null);
                }
            }

            // 标注点（始终绘制）
            dotPaint.setColor(cFull);
            canvas.drawCircle(l.dotX, l.dotY, dotR, dotPaint);
        }
    }
}
