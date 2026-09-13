package com.jingxin.lincarinfo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * 车图标注层：在车图实体上画标注点，并用虚线连到对应信息标签。
 * 正常=青色，异常=红色（胎压<200kPa、门窗开、后备箱开）。
 * 通过 setLink(dotX, dotY, tag, alert) 传入配对；标签布局完成后调 apply() 触发重绘。
 */
public class TagLineOverlay extends View {

    /** 一对连接：标注点(车图上) —— 标签(虚线终点为标签中心) */
    private static class Link {
        final float dotX, dotY;
        final View tag;
        boolean alert;
        Link(float dotX, float dotY, View tag) {
            this.dotX = dotX; this.dotY = dotY; this.tag = tag;
        }
    }

    private final List<Link> links = new ArrayList<>();
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean ready;

    private static final int COLOR_NORMAL = 0xFF00E5FF;   // 青色
    private static final int COLOR_ALERT = 0xFFFF5A5A;   // 红色
    private static final int LINE_NORMAL = 0x9900E5FF;
    private static final int LINE_ALERT = 0x99FF5A5A;

    public TagLineOverlay(Context c, AttributeSet a) {
        super(c, a);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(2f);
        linePaint.setPathEffect(new DashPathEffect(new float[]{6f, 6f}, 0f));
        dotPaint.setStyle(Paint.Style.FILL);
    }

    public void clear() {
        links.clear();
        ready = false;
        invalidate();
    }

    /** 传入一对连接（红点坐标相对本 view） */
    public void setLink(float dotX, float dotY, View tag, boolean alert) {
        for (Link l : links) {
            if (l.tag == tag) {   // 已有配对：只更新异常标记
                l.alert = alert;
                return;
            }
        }
        Link l = new Link(dotX, dotY, tag);
        l.alert = alert;
        links.add(l);
    }

    /** 全部配对完成后调用，触发一次重绘 */
    public void apply() {
        ready = true;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!ready) return;
        float dotR = 1.25f * getResources().getDisplayMetrics().density;
        for (Link l : links) {
            if (l.tag == null || l.tag.getWidth() == 0) continue;
            // 标签中心（相对 carZone，本 view 铺满 carZone）
            float cx = l.tag.getLeft() + l.tag.getWidth() / 2f;
            float cy = l.tag.getTop() + l.tag.getHeight() / 2f;
            linePaint.setColor(l.alert ? LINE_ALERT : LINE_NORMAL);
            dotPaint.setColor(l.alert ? COLOR_ALERT : COLOR_NORMAL);
            // 虚线：标注点 → 标签中心
            canvas.drawLine(l.dotX, l.dotY, cx, cy, linePaint);
            // 标注点（后画，压在线上）
            canvas.drawCircle(l.dotX, l.dotY, dotR, dotPaint);
        }
    }
}
