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
import android.view.animation.LinearInterpolator;

/**
 * 圆形门窗/后备箱标签：100dp 直径圆形，异常状态才绘制。
 *
 * 动画与 TireCircleView 一致：
 * 1. 呼吸扩散环：外扩 1.0→1.5x + 渐隐，正常 2.2s/异常 1.1s
 * 2. 边框渐隐渐显：透明度 40%→100%→40% 脉冲
 * 3. 文字渐隐渐显：alpha 0→255→0 循环（所有异常状态都有）
 *    门+窗都开时，在 alpha=0 时刻交替切换"门开"/"窗开"
 *
 * 正常状态（门窗户全关）不绘制任何内容，View 空白。
 */
public class DoorCircleView extends View {

    /** 圆形直径 dp：竖屏横屏统一 90dp，由 circleScale + 宽度约束缩放 */
    private static final float DIAMETER_DP_PORTRAIT = 90f;
    private static final float DIAMETER_DP_LANDSCAPE = 90f;

    private static final float BREATH_MAX = 1.5f;
    private static final long PERIOD_NORMAL = 2200L;
    private static final long PERIOD_ALERT = 1100L;
    /** 文字渐隐渐显周期 ms */
    private static final long PERIOD_TEXT = 2000L;
    private static final float RING_ALPHA_LOW = 0.40f;
    private static final float RING_ALPHA_HIGH = 1.00f;

    /** dp 缩放比例（由 MainActivity.applyFontScale 设置，随屏宽等比缩放） */
    private float dpScale = 1f;
    public void setDpScale(float scale) { this.dpScale = scale; }
    public float getDpScale() { return dpScale; }

    private final Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint breathPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** 显示模式 */
    private static final int MODE_HIDDEN = 0;      // 正常，不绘制
    private static final int MODE_STATIC = 1;      // 单一文字，渐隐渐显
    private static final int MODE_ALTERNATE = 2;    // 门开/窗开交替渐隐渐显
    private static final int MODE_TRUNK = 3;       // 后备箱开

    private int mode = MODE_HIDDEN;
    private String textA = "门开";   // 交替模式第一文本
    private String textB = "窗开";   // 交替模式第二文本
    private String staticText = "";  // 静态模式文本
    private int color = 0xFFFF5A5A;  // 异常红
    /** 发光阴影开关：夜间开/白天关，构造时缓存 */
    private final boolean glow;

    private ValueAnimator breathAnim;
    private ValueAnimator textAnim;
    private float breathPhase = 0f;
    /** 文字 alpha 0→1（映射 0→255） */
    private float textPhase = 0f;
    /** 交替模式下当前显示 A 还是 B */
    private boolean showA = true;
    private boolean running = false;

    public DoorCircleView(Context c) { this(c, null); }

    public DoorCircleView(Context c, AttributeSet a) {
        super(c, a);
        glow = c.getResources().getBoolean(R.bool.glow_enabled);
        circlePaint.setStyle(Paint.Style.FILL);
        circlePaint.setColor(c.getResources().getColor(R.color.circle_bg));   // 圆底日夜自适应
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(1.5f * density());
        breathPaint.setStyle(Paint.Style.STROKE);
        breathPaint.setStrokeWidth(1.5f * density());
        textPaint.setTypeface(Typeface.create("monospace", Typeface.BOLD));
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setColor(color);
    }

    private float density() { return getResources().getDisplayMetrics().density; }

    // ========== 数据更新 ==========

    /** 门窗标签更新 */
    public void update(boolean doorOpen, boolean windowOpen) {
        boolean alert = doorOpen || windowOpen;
        if (!alert) {
            setMode(MODE_HIDDEN);
            return;
        }
        color = getResources().getColor(R.color.state_alert);
        if (doorOpen && windowOpen) {
            setMode(MODE_ALTERNATE);
        } else if (doorOpen) {
            setModeStatic("门开");
        } else {
            setModeStatic("窗开");
        }
    }

    /** 后备箱标签更新 */
    public void updateTrunk(boolean open) {
        if (!open) {
            setMode(MODE_HIDDEN);
            return;
        }
        color = getResources().getColor(R.color.state_alert);
        setModeStatic("后备箱开");
    }

    private void setMode(int newMode) {
        if (newMode == mode && newMode == MODE_HIDDEN) return;
        mode = newMode;
        if (newMode == MODE_HIDDEN) {
            stopAll();
        } else {
            restartAnimals();
        }
        invalidate();
    }

    private void setModeStatic(String text) {
        boolean changed = mode != MODE_STATIC || !text.equals(staticText);
        staticText = text;
        if (changed) {
            mode = MODE_STATIC;
            restartAnimals();
            invalidate();
        }
    }

    // ========== 动画管理 ==========

    private void restartAnimals() {
        stopAll();
        if (!running) running = true;
        startBreath();
        startTextAnim();
    }

    private void startBreath() {
        if (breathAnim != null && breathAnim.isRunning()) return;
        breathAnim = ValueAnimator.ofFloat(0f, 1f);
        breathAnim.setDuration(PERIOD_ALERT);   // 门窗异常总是红色快节奏
        breathAnim.setRepeatCount(ValueAnimator.INFINITE);
        breathAnim.setInterpolator(new LinearInterpolator());
        breathAnim.addUpdateListener(an -> {
            breathPhase = (float) an.getAnimatedValue();
            invalidate();
        });
        breathAnim.start();
    }

    private void startTextAnim() {
        if (textAnim != null && textAnim.isRunning()) return;
        textAnim = ValueAnimator.ofFloat(0f, 1f);
        textAnim.setDuration(PERIOD_TEXT);
        textAnim.setRepeatCount(ValueAnimator.INFINITE);
        textAnim.setInterpolator(new LinearInterpolator());
        textAnim.addUpdateListener(an -> {
            textPhase = (float) an.getAnimatedValue();
            // 交替模式：在 alpha≈0 的时刻（phase=0.5）切换文本
            if (mode == MODE_ALTERNATE && textPhase > 0.48f && textPhase < 0.52f) {
                showA = !showA;
            }
            invalidate();
        });
        textAnim.start();
    }

    private void stopAll() {
        running = false;
        if (breathAnim != null) { breathAnim.cancel(); breathAnim = null; }
        if (textAnim != null) { textAnim.cancel(); textAnim = null; }
        breathPhase = 0f;
        textPhase = 0f;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mode != MODE_HIDDEN) restartAnimals();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopAll();
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
        if (mode == MODE_HIDDEN) return;   // 正常状态不绘制
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float cx = w / 2f, cy = h / 2f;
        float d = density();
        float r = Math.min(w, h) / 2f - 1.5f * d;

        // ---- 1. 呼吸扩散环 ----
        float scale = 1f + (BREATH_MAX - 1f) * breathPhase;
        float alpha = (1f - breathPhase) * 0.55f;
        int base = color & 0xFFFFFF;
        breathPaint.setColor((Math.round(alpha * 255f) << 24) | base);
        canvas.drawCircle(cx, cy, r * scale, breathPaint);

        // ---- 2. 圆底 + 边框渐隐渐显 ----
        canvas.drawCircle(cx, cy, r, circlePaint);
        float ringT = breathPhase < 0.5f ? breathPhase * 2f : (1f - breathPhase) * 2f;
        float ringAlpha = RING_ALPHA_LOW + (RING_ALPHA_HIGH - RING_ALPHA_LOW) * ringT;
        ringPaint.setColor((Math.round(ringAlpha * 255f) << 24) | base);
        canvas.drawCircle(cx, cy, r, ringPaint);

        // ---- 3. 内侧微光晕 ----
        Paint innerGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
        innerGlow.setShader(new RadialGradient(cx, cy, r,
                (color & 0x00FFFFFF) | 0x1A000000, 0x00000000, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, r, innerGlow);

        // ---- 4. 文字渐隐渐显 ----
        // alpha 曲线：0→1→0（正弦上半波），phase 0=透明 0.5=最亮 1=透明
        float textAlpha = (float) Math.sin(textPhase * (float) Math.PI);   // 0→1→0
        int textAlphaInt = Math.max(0, Math.min(255, Math.round(textAlpha * 255f)));

        String display;
        if (mode == MODE_ALTERNATE) {
            display = showA ? textA : textB;
        } else {
            display = staticText;
        }

        float maxTextW = r * 2f * 0.78f;
        float maxValPx = (Math.min(w, h) / d) * 0.26f * d;
        float textSize = fitTextSize(textPaint, display, maxTextW, maxValPx);
        textPaint.setTextSize(textSize);
        int cFull = (textAlphaInt << 24) | (color & 0xFFFFFF);
        textPaint.setColor(cFull);
        if (glow) {
            textPaint.setShadowLayer(4f * d, 0, 0, cFull);   // LED 发光（夜间）
        } else {
            textPaint.setShadowLayer(0f, 0, 0, 0);           // 白天关发光
        }

        float valBaseline = cy - (textPaint.getFontMetrics().ascent + textPaint.getFontMetrics().descent) / 2f;
        canvas.drawText(display, cx, valBaseline, textPaint);
    }

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
