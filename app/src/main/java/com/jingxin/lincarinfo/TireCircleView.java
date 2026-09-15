package com.jingxin.lincarinfo;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;

/**
 * 圆形胎压标签：50dp 直径圆形，内部两行文字垂直居中——
 * 第一行胎压值（字号自适应圆宽，保证不折行不溢出），第二行单位（字号=胎压值÷3）。
 * 
 * 三层动画（只改绘制参数，不碰 View 尺寸，规避车机 RenderThread 崩溃）：
 * 1. 呼吸扩散环：从圆边向外放大（1.0→1.5x）同时透明度渐隐，循环往复；
 *    正常状态青色 2.2s/次，异常状态红色 1.1s/次（节奏加倍强化警示）。
 * 2. 边框渐隐渐显：固定描边透明度随呼吸周期脉冲（40%→100%→40%），与呼吸环同步呼吸。
 * 3. 文字抖动：胎压值在水平+垂直方向做微小抖动（±1.5dp），400ms 周期，异常状态才触发；
 *    正常状态不抖动（避免视觉疲劳）。
 * 4. 数值更新动画：胎压值变化时新数值从 1.4x 缩放到 1.0x（减速落定），仅动绘制参数。
 */
public class TireCircleView extends View {

    /** 圆形直径 dp：竖屏横屏统一 80dp，由 circleScale + 宽度约束缩放 */
    private static final float DIAMETER_DP_PORTRAIT = 80f;
    private static final float DIAMETER_DP_LANDSCAPE = 80f;
    /** 呼吸环最大外扩比例 */
    private static final float BREATH_MAX = 1.5f;
    /** 正常呼吸周期 ms */
    private static final long PERIOD_NORMAL = 2200L;
    /** 异常呼吸周期 ms（加倍警示） */
    private static final long PERIOD_ALERT = 1100L;
    /** 文字抖动周期 ms */
    private static final long PERIOD_SHAKE = 400L;
    /** 文字抖动幅度 dp */
    private static final float SHAKE_AMP = 1.5f;
    /** 数值更新动画：起始缩放 1.4x，时长 400ms */
    private static final float POP_FROM = 1.4f;
    private static final long PERIOD_POP = 400L;
    /** 边框透明度范围：低 40% ↔ 高 100% */
    private static final float RING_ALPHA_LOW = 0.40f;
    private static final float RING_ALPHA_HIGH = 1.00f;

    /** dp 缩放比例（由 MainActivity.applyFontScale 设置，随屏宽等比缩放） */
    private float dpScale = 1f;
    public void setDpScale(float scale) { this.dpScale = scale; }
    public float getDpScale() { return dpScale; }

    /** 颜色：正常绿 / 无效灰 / 琥珀 / 异常红（从资源读，日夜自适应） */
    public static int colorOk(Context c) { return c.getResources().getColor(R.color.state_ok); }
    public static int colorInvalid(Context c) { return c.getResources().getColor(R.color.state_invalid); }
    public static int colorWarn(Context c) { return c.getResources().getColor(R.color.state_warn); }
    public static int colorAlert(Context c) { return c.getResources().getColor(R.color.state_alert); }

    private final Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);   // 圆底
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);    // 固定描边
    private final Paint breathPaint = new Paint(Paint.ANTI_ALIAS_FLAG);   // 呼吸扩散环
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);      // 胎压值
    private final Paint unitPaint = new Paint(Paint.ANTI_ALIAS_FLAG);      // 单位

    private String pressure = "--";
    private String unit = "kPa";
    private int color = 0xFF00FF9D;
    private boolean alert = false;
    /** 发光阴影开关：夜间开（LED感）/白天关（高对比实色），构造时缓存 */
    private final boolean glow;
    private ValueAnimator breathAnim;
    private ValueAnimator shakeAnim;
    /** 数值更新缩放动画 */
    private ValueAnimator popAnim;
    /** 呼吸动画进度 0→1 */
    private float breathPhase = 0f;
    /** 抖动动画进度 0→1（用正弦波算偏移） */
    private float shakePhase = 0f;
    /** 数值缩放进度 0→1（1+POP_FROM → 1，绘制时插值） */
    private float popScale = 1f;
    private boolean running = false;

    public TireCircleView(Context c) { this(c, null); }

    public TireCircleView(Context c, AttributeSet a) {
        super(c, a);
        glow = c.getResources().getBoolean(R.bool.glow_enabled);
        color = colorOk(c);
        circlePaint.setStyle(Paint.Style.FILL);
        circlePaint.setColor(c.getResources().getColor(R.color.circle_bg));   // 圆底（与 bg_tag 一致，日夜自适应）
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(1.5f * density());  // 1dp 描边
        breathPaint.setStyle(Paint.Style.STROKE);
        breathPaint.setStrokeWidth(1.5f * density());
        textPaint.setTypeface(Typeface.create("monospace", Typeface.BOLD));
        textPaint.setTextAlign(Paint.Align.CENTER);
        unitPaint.setTypeface(Typeface.create("monospace", Typeface.BOLD));
        unitPaint.setTextAlign(Paint.Align.CENTER);
    }

    private float density() { return getResources().getDisplayMetrics().density; }

    /** 更新数据：胎压值、单位、状态色、是否报警（驱动呼吸节奏/颜色 + 抖动开关） */
    public void update(String pressure, String unit, int color, boolean alert) {
        boolean valueChanged = !eq(this.pressure, pressure);
        boolean changed = valueChanged || !eq(this.unit, unit)
                || this.color != color || this.alert != alert;
        this.pressure = pressure == null ? "--" : pressure;
        this.unit = unit == null ? "" : unit;
        this.color = color;
        this.alert = alert;
        ringPaint.setColor(color);
        breathPaint.setColor(color);
        if (changed) {
            restartBreath();
            restartShake();
            if (valueChanged) startPop();   // 数值变化：新值从 1.4x 缩回 1.0x
            invalidate();
        }
    }

    /** 数值更新动画：从 POP_FROM 缩回 1.0，减速插值落定 */
    private void startPop() {
        if (popAnim != null) popAnim.cancel();
        popAnim = ValueAnimator.ofFloat(0f, 1f);
        popAnim.setDuration(PERIOD_POP);
        popAnim.setInterpolator(new DecelerateInterpolator());
        popAnim.addUpdateListener(an -> {
            popScale = 1f + (POP_FROM - 1f) * (1f - (float) an.getAnimatedValue());
            invalidate();
        });
        popAnim.start();
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    // ========== 动画管理 ==========

    /** 启动/重启呼吸动画（数据或状态变化时按新节奏走） */
    private void restartBreath() {
        if (breathAnim != null) {
            breathAnim.cancel();
            breathAnim = null;
        }
        startBreath();   // startBreath 内部设 running=true
    }

    public void startBreath() {
        running = true;
        if (breathAnim != null && breathAnim.isRunning()) return;
        breathAnim = ValueAnimator.ofFloat(0f, 1f);
        breathAnim.setDuration(alert ? PERIOD_ALERT : PERIOD_NORMAL);
        breathAnim.setRepeatCount(ValueAnimator.INFINITE);
        breathAnim.setInterpolator(new LinearInterpolator());
        breathAnim.addUpdateListener(an -> {
            breathPhase = (float) an.getAnimatedValue();
            invalidate();
        });
        breathAnim.start();
    }

    public void stopBreath() {
        running = false;
        if (breathAnim != null) {
            breathAnim.cancel();
            breathAnim = null;
        }
    }

    /** 抖动动画：仅异常状态启动，正常/无效/琥珀状态不抖 */
    private void restartShake() {
        stopShake();
        if (running && alert) startShake();
    }

    private void startShake() {
        if (shakeAnim != null && shakeAnim.isRunning()) return;
        shakeAnim = ValueAnimator.ofFloat(0f, 1f);
        shakeAnim.setDuration(PERIOD_SHAKE);
        shakeAnim.setRepeatCount(ValueAnimator.INFINITE);
        shakeAnim.setInterpolator(new LinearInterpolator());
        shakeAnim.addUpdateListener(an -> {
            shakePhase = (float) an.getAnimatedValue();
            invalidate();
        });
        shakeAnim.start();
    }

    private void stopShake() {
        if (shakeAnim != null) {
            shakeAnim.cancel();
            shakeAnim = null;
        }
        shakePhase = 0f;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startBreath();
        if (alert) startShake();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopBreath();
        stopShake();
        if (popAnim != null) { popAnim.cancel(); popAnim = null; }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        boolean landscape = getResources().getConfiguration().screenWidthDp
                > getResources().getConfiguration().screenHeightDp * 1.2f;
        float dp = (landscape ? DIAMETER_DP_LANDSCAPE : DIAMETER_DP_PORTRAIT) * dpScale;
        int size = Math.round(dp * density());
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float cx = w / 2f, cy = h / 2f;
        float d = density();
        float r = Math.min(w, h) / 2f - 1.5f * d;   // 固定圆半径（留描边位）

        // ---- 1. 呼吸扩散环：1.0→1.5 半径外扩，alpha 高→0 渐隐 ----
        float scale = 1f + (BREATH_MAX - 1f) * breathPhase;
        float alpha = (1f - breathPhase) * 0.55f;           // 峰值 55% 透明度起
        int base = color & 0xFFFFFF;
        breathPaint.setColor((Math.round(alpha * 255f) << 24) | base);
        canvas.drawCircle(cx, cy, r * scale, breathPaint);

        // ---- 2. 圆底 + 边框渐隐渐显（透明度随呼吸周期脉冲） ----
        canvas.drawCircle(cx, cy, r, circlePaint);
        // 边框透明度：呼吸前半段渐亮(40%→100%)，后半段渐暗(100%→40%)
        float ringT = breathPhase < 0.5f
                ? breathPhase * 2f                    // 0→1（渐亮）
                : (1f - breathPhase) * 2f;             // 1→0（渐暗）
        float ringAlpha = RING_ALPHA_LOW + (RING_ALPHA_HIGH - RING_ALPHA_LOW) * ringT;
        ringPaint.setColor((Math.round(ringAlpha * 255f) << 24) | base);
        canvas.drawCircle(cx, cy, r, ringPaint);

        // ---- 内侧微光晕（中心向边的径向渐变，增强 LED 感） ----
        Paint innerGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
        innerGlow.setShader(new RadialGradient(cx, cy, r,
                (color & 0x00FFFFFF) | 0x1A000000, 0x00000000, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, r, innerGlow);

        // ---- 3. 文字：胎压值（上）+ 单位（下），垂直居中 ----
        float maxTextW = r * 2f * 0.78f;                    // 圆内可用文字宽（弦长≈78%）
        // 胎压值字号上限按圆直径比例：50dp→15dp, 70dp→21dp，保持视觉占比一致
        float maxValPx = (Math.min(w, h) / d) * 0.30f * d;
        float valSize = fitTextSize(textPaint, pressure, maxTextW, maxValPx);
        float unitSize = valSize * 0.40f;
        textPaint.setTextSize(valSize);
        textPaint.setColor(color);
        if (glow) textPaint.setShadowLayer(4f * d, 0, 0, color);      // LED 发光（夜间）
        unitPaint.setTextSize(unitSize);
        unitPaint.setColor(color);
        if (glow) unitPaint.setShadowLayer(2f * d, 0, 0, color);

        float rowGap = 2f * d;
        float valH = textPaint.getFontMetrics().bottom - textPaint.getFontMetrics().top;
        float unitH = unitPaint.getFontMetrics().bottom - unitPaint.getFontMetrics().top;
        float totalH = valH + unitH + rowGap;
        float topY = cy - totalH / 2f;
        float valBaseline = topY - textPaint.getFontMetrics().top;
        float unitBaseline = topY + valH + rowGap - unitPaint.getFontMetrics().top;

        // 异常状态下胎压值抖动：正弦波 × 振幅（水平+垂直双方向抖动）
        float shakeX = 0f, shakeY = 0f;
        if (alert && shakeAnim != null && shakeAnim.isRunning()) {
            double wave = Math.sin(shakePhase * 2f * Math.PI);   // -1→1 正弦
            shakeX = (float) wave * SHAKE_AMP * d;
            shakeY = (float) Math.cos(shakePhase * 2f * Math.PI) * SHAKE_AMP * d * 0.5f;  // 垂直幅度减半
        }
        // 数值更新动画：以文字中心为锚点缩放（1.4x→1.0x）
        if (popAnim != null && popAnim.isRunning() && popScale != 1f) {
            float valCenterY = topY + valH / 2f;
            canvas.save();
            canvas.scale(popScale, popScale, cx, valCenterY);
            canvas.drawText(pressure, cx + shakeX, valBaseline + shakeY, textPaint);
            canvas.restore();
        } else {
            canvas.drawText(pressure, cx + shakeX, valBaseline + shakeY, textPaint);
        }
        canvas.drawText(unit, cx, unitBaseline, unitPaint);
    }

    /** 计算让文本恰好不折行的最大字号：从 maxPx 起，按实测宽逐步缩 */
    private float fitTextSize(Paint p, String text, float maxWidth, float maxPx) {
        float size = maxPx;
        p.setTextSize(size);
        while (p.measureText(text) > maxWidth && size > 4f) {
            size *= 0.92f;
            p.setTextSize(size);
        }
        return size;
    }
}
