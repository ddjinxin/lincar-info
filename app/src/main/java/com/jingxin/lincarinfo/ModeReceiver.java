package com.jingxin.lincarinfo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 模式切换接收器 — 监听乐酷驾驶发来的 SET_MODE 广播。
 *
 * 收到广播后：
 *  - mode=0（带界面）：写 SP + 启动 MainActivity
 *  - mode=1（纯服务）：写 SP + 启动 VehicleDataService + finish Activity（如有）
 *
 * 广播 action：com.jingxin.lincarinfo.SET_MODE
 * extra：mode（int，0=带界面 1=纯服务）
 */
public class ModeReceiver extends BroadcastReceiver {
    private static final String TAG = "LinCarInfo";
    public static final String ACTION_SET_MODE = "com.jingxin.lincarinfo.SET_MODE";
    public static final String EXTRA_MODE = "mode";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_SET_MODE.equals(intent.getAction())) return;
        int mode = intent.getIntExtra(EXTRA_MODE, -1);
        if (mode != 0 && mode != 1) return;

        Log.i(TAG, "[Mode] 收到模式切换: mode=" + (mode == 0 ? "带界面" : "纯服务"));

        // 写 SP
        context.getSharedPreferences("ui", Context.MODE_PRIVATE)
                .edit().putInt(VehicleDataService.KEY_RUN_MODE, mode).apply();

        if (mode == 0) {
            // 带界面：启动 Activity
            try {
                Intent launch = new Intent(context, MainActivity.class);
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(launch);
                Log.i(TAG, "[Mode] 已启动主界面");
            } catch (Throwable e) {
                Log.w(TAG, "[Mode] 启动主界面失败: " + e);
            }
        } else {
            // 纯服务：隐含开机自启（无界面时用户无长按入口，模式本身就意味着常驻）
            context.getSharedPreferences("ui", Context.MODE_PRIVATE)
                    .edit().putBoolean(MainActivity.KEY_AUTO_START, true).apply();
            Log.i(TAG, "[Mode] 纯服务模式：已隐含开启开机自启");

            // 启动 Service（Activity 会在乐酷驾驶侧 finish 或用户自行退出）
            try {
                Intent svc = new Intent(context, VehicleDataService.class);
                // Android 12+ 后台启动前台 Service 受限（ForegroundServiceStartNotAllowedException），
                // 降级链：startForegroundService → startService；均失败时 SP 已写入，
                // 模式在下次开机自启或下次打开界面时生效。8.1 车机无此限制直接成功
                try {
                    if (android.os.Build.VERSION.SDK_INT >= 26) {
                        context.startForegroundService(svc);
                    } else {
                        context.startService(svc);
                    }
                } catch (Throwable inner) {
                    Log.w(TAG, "[Mode] 前台启动受限，降级 startService: " + inner);
                    context.startService(svc);
                }
                Log.i(TAG, "[Mode] 已启动纯服务模式");
            } catch (Throwable e) {
                Log.w(TAG, "[Mode] 启动服务失败: " + e);
            }
        }
    }
}
