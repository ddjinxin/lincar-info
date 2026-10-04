package com.jingxin.lincarinfo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 开机自启接收器 — 监听系统 BOOT_COMPLETED，开关开时启动主界面。
 * 开关存储与 MainActivity 共用同一 SP 文件（"ui"）与键（"auto_start"）。
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "LinCarInfo";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        boolean autoStart = context.getSharedPreferences("ui", Context.MODE_PRIVATE)
                .getBoolean("auto_start", false);
        Log.i(TAG, "[Boot] 收到开机广播, auto_start=" + autoStart);
        if (!autoStart) return;
        try {
            Intent launch = new Intent(context, MainActivity.class);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(launch);
            Log.i(TAG, "[Boot] 开机自启: 已启动主界面");
        } catch (Throwable e) {
            Log.w(TAG, "[Boot] 开机自启失败: " + e);
        }
    }
}
