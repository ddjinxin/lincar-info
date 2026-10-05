package com.jingxin.lincarinfo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 开机自启接收器 — 监听系统 BOOT_COMPLETED，开关开时按 run_mode 决定启动方式。
 *
 * SP 键：
 *  - auto_start：开机自启开关（长按主界面切换）
 *  - run_mode：运行模式（0=带界面 1=纯服务，由乐酷驾驶设置页切换）
 *
 * 启动逻辑：
 *  - auto_start=false → 不启动
 *  - auto_start=true + run_mode=0 → 启动 MainActivity
 *  - auto_start=true + run_mode=1 → 启动 VehicleDataService（纯服务，无界面）
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "LinCarInfo";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        android.content.SharedPreferences prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE);
        boolean autoStart = prefs.getBoolean("auto_start", false);
        int runMode = prefs.getInt(VehicleDataService.KEY_RUN_MODE, 0); // 0=带界面（默认）
        Log.i(TAG, "[Boot] 收到开机广播, auto_start=" + autoStart + " run_mode=" + runMode);
        if (!autoStart) return;

        if (runMode == 1) {
            // 纯服务模式：直接启动 Service，无界面
            try {
                Intent svc = new Intent(context, VehicleDataService.class);
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    context.startForegroundService(svc);
                } else {
                    context.startService(svc);
                }
                Log.i(TAG, "[Boot] 开机自启: 已启动纯服务模式");
            } catch (Throwable e) {
                Log.w(TAG, "[Boot] 启动服务失败: " + e);
            }
        } else {
            // 带界面模式：启动 Activity
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
}
