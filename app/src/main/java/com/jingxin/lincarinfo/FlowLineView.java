package com.jingxin.lincarinfo;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * 流光动画线：一条细线上有亮光段从中心向两边运动，循环播放。
 * 夜间绿色、白天蓝色通过 R.color.flow_line 资源自适应。
 */
public class FlowLineView extends View {

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float progress = 0f; // 0→1 循环
    private int flowColor;
    private boolean glow;

    public FlowLineView(Context context) {
        this(context, null);
    }

    public FlowLineView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public FlowLineView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        flowColor = getContext().getResources().getColor(R.color.flow_line);
        glow = getContext().getResources().getBoolean(R.bool.glow_enabled);
        glowPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_OVER));
        startAnimation();
    }

    private void startAnimation() {
        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(2400);
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.setRepeatMode(ValueAnimator.RESTART);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            invalidate();
        });
        anim.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        // 底线：极低透明度的同色
        int lineColor = (flowColor & 0x00FFFFFF) | 0x1A000000;
        linePaint.setColor(lineColor);
        linePaint.setStrokeWidth(1f);
        float cy = h / 2f;
        canvas.drawLine(0, cy, w, cy, linePaint);

        // 流光段：从中心向两边扩散
        // progress 0→0.5: 宽度从0增长到最大; 0.5→1: 宽度从最大缩回0
        float halfMax = w / 2f;
        float expand;
        if (progress < 0.5f) {
            expand = (progress / 0.5f) * halfMax;
        } else {
            expand = ((1f - progress) / 0.5f) * halfMax;
        }
        float cx = w / 2f;
        float left = cx - expand;
        float right = cx + expand;
        if (right - left < 1f) return;

        // 渐变：两端透明→中心亮
        int transparent = flowColor & 0x00FFFFFF;
        LinearGradient gradient = new LinearGradient(
                left, cy, right, cy,
                new int[]{transparent, flowColor, transparent},
                new float[]{0f, 0.5f, 1f},
                Shader.TileMode.CLAMP);
        glowPaint.setShader(gradient);
        glowPaint.setStrokeWidth(h > 4 ? 3f : 2f);

        if (glow) {
            glowPaint.setShadowLayer(8f, 0, 0, flowColor);
        } else {
            glowPaint.setShadowLayer(0, 0, 0, 0);
        }

        canvas.drawLine(left, cy, right, cy, glowPaint);
        glowPaint.setShader(null);
        glowPaint.setShadowLayer(0, 0, 0, 0);
    }
}
