package com.jingxin.lincarinfo;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 空调状态提示：文字从透明到不透明再到透明，红色，整个动画周期5秒。
 * 调用 show("空调开") 触发一次动画。快速连发时排队依次播放，不互相打断。
 */
public class HvacToastView extends View {

    private static final int COLOR = 0xFFFF5A5A;    // 红色，日夜统一
    private static final long DURATION_TOTAL = 5000L;  // 整个动画周期 5 秒

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private String text = "";
    private float progress = 0f;    // 0~1，单次动画进度
    private float maxTextSize = 0f; // 最终字号 px，由外部定位时设置
    private ValueAnimator anim;
    private boolean glow;
    private final Deque<String> queue = new ArrayDeque<>();

    public HvacToastView(Context c) { this(c, null); }

    public HvacToastView(Context c, AttributeSet a) {
        super(c, a);
        glow = c.getResources().getBoolean(R.bool.glow_enabled);
        paint.setTypeface(Typeface.create("monospace", Typeface.BOLD));
        paint.setColor(COLOR);
        paint.setTextAlign(Paint.Align.CENTER);
    }

    /** 设置最大字号（px），由 positionCarTags 调用 */
    public void setMaxTextSize(float px) {
        this.maxTextSize = px;
    }

    /** 触发一次提示动画（排队，快速连发时依次播放） */
    public void show(String msg) {
        queue.offer(msg);
        if (anim != null && anim.isRunning()) return;  // 当前动画进行中，入队等播完
        playNext();
    }

    private void playNext() {
        String next = queue.poll();
        if (next == null) return;
        text = next;
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(DURATION_TOTAL);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(an -> {
            progress = (float) an.getAnimatedValue();
            invalidate();
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) {
                text = "";
                invalidate();
                playNext();  // 播放下一条
            }
        });
        anim.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (text.isEmpty() || maxTextSize <= 0) return;
        // 透明度曲线：0→0.8→0，正弦上半波×0.8
        float alpha = (float) Math.sin(progress * (float) Math.PI) * 0.8f;  // 0→0.8→0
        // 缩放曲线：0.5→1.0→0.5，与透明度同步（正弦上半波映射到0.5~1.0）
        float scale = 0.5f + 0.5f * (float) Math.sin(progress * (float) Math.PI);  // 0.5→1.0→0.5

        paint.setTextSize(maxTextSize * scale);
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255f)));
        int c = (a << 24) | (COLOR & 0xFFFFFF);
        paint.setColor(c);
        if (glow) {
            paint.setShadowLayer(6f * getResources().getDisplayMetrics().density, 0, 0, c);
        } else {
            paint.setShadowLayer(0f, 0, 0, 0);
        }

        float cx = getWidth() / 2f;
        // 文字底部贴近 View 底部（即空调区顶部）
        float baseline = getHeight() - paint.getFontMetrics().descent;
        canvas.drawText(text, cx, baseline, paint);
    }
}
