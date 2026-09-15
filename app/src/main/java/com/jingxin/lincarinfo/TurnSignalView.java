package com.jingxin.lincarinfo;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * 转向灯指示器：箭头形闪烁灯，左/右方向。
 * 亮：金黄色箭头 0.5s 亮/0.5s 灭闪烁，灭/关时不绘制。
 */
public class TurnSignalView extends View {

    private static final int COLOR_ON = 0xFFFFB300;       // 金黄色，日夜统一
    private static final long BLINK_PERIOD = 1000L;       // 1s 一个周期（0.5s 亮 + 0.5s 灭）

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrowPath = new Path();

    private boolean left = false;          // true=左箭头 false=右箭头
    private boolean on = false;            // 是否点亮
    private float blinkPhase = 0f;         // 0~1，>0.5 时灭
    private ValueAnimator blinkAnim;

    public TurnSignalView(Context c) { this(c, null); }

    public TurnSignalView(Context c, AttributeSet a) {
        super(c, a);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(COLOR_ON);
    }

    /** 设置方向并更新点亮状态（值 3=亮 / 1=灭 / 0=关） */
    public void update(int signalValue, boolean isLeft) {
        left = isLeft;
        boolean shouldOn = (signalValue == 3);
        if (shouldOn == on) return;
        on = shouldOn;
        if (on) {
            startBlink();
        } else {
            stopBlink();
        }
        invalidate();
    }

    private void startBlink() {
        if (blinkAnim != null && blinkAnim.isRunning()) return;
        blinkAnim = ValueAnimator.ofFloat(0f, 1f);
        blinkAnim.setDuration(BLINK_PERIOD);
        blinkAnim.setRepeatCount(ValueAnimator.INFINITE);
        blinkAnim.setInterpolator(new LinearInterpolator());
        blinkAnim.addUpdateListener(an -> {
            blinkPhase = (float) an.getAnimatedValue();
            invalidate();
        });
        blinkAnim.start();
    }

    private void stopBlink() {
        if (blinkAnim != null) { blinkAnim.cancel(); blinkAnim = null; }
        blinkPhase = 0f;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (on) startBlink();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopBlink();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!on) return;
        // 闪烁：前半周期亮，后半周期灭
        if (blinkPhase > 0.5f) return;

        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        // 箭头路径（以中心为原点）
        float cx = w / 2f, cy = h / 2f;
        float size = Math.min(w, h) * 0.7f;
        float half = size / 2f;
        float stemW = size * 0.28f;   // 箭杆宽度

        arrowPath.reset();
        if (left) {
            // 左箭头 ←：箭头在左侧，杆在右侧
            // 箭头三角顶点
            arrowPath.moveTo(cx - half, cy);
            // 箭头上翼
            arrowPath.lineTo(cx - half + size * 0.4f, cy - half);
            // 箭头上翼到杆
            arrowPath.lineTo(cx - half + size * 0.4f, cy - stemW / 2f);
            // 杆右端上
            arrowPath.lineTo(cx + half, cy - stemW / 2f);
            // 杆右端下
            arrowPath.lineTo(cx + half, cy + stemW / 2f);
            // 杆到箭头下翼
            arrowPath.lineTo(cx - half + size * 0.4f, cy + stemW / 2f);
            // 箭头下翼
            arrowPath.lineTo(cx - half + size * 0.4f, cy + half);
        } else {
            // 右箭头 →：箭头在右侧，杆在左侧
            arrowPath.moveTo(cx + half, cy);
            arrowPath.lineTo(cx + half - size * 0.4f, cy - half);
            arrowPath.lineTo(cx + half - size * 0.4f, cy - stemW / 2f);
            arrowPath.lineTo(cx - half, cy - stemW / 2f);
            arrowPath.lineTo(cx - half, cy + stemW / 2f);
            arrowPath.lineTo(cx + half - size * 0.4f, cy + stemW / 2f);
            arrowPath.lineTo(cx + half - size * 0.4f, cy + half);
        }
        arrowPath.close();

        canvas.drawPath(arrowPath, paint);
    }
}
