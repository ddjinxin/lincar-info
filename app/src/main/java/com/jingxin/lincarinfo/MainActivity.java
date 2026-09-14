package com.jingxin.lincarinfo;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.text.SpannableString;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.TreeMap;

/**
 * 系统签名直读版探针 — 照抄 CarLauncher 的 CarService 调用方式读取全部车辆数据。
 *
 * 全反射实现：编译期依赖 android.car.jar（compileOnly），运行时通过反射调用。
 * 注意：R8 会把 Class.forName("常量") 优化为类字面量，运行时抛 NoClassDefFoundError，
 * 因此所有 catch 必须用 Throwable（不能只 catch Exception）。
 *
 * V2 仪表盘 UI：深空黑底+青色发光仪表盘（档位/续航/里程/机油/燃油横幅、大车速、
 * 车俯视图+四轮胎压+门窗标签、空调卡片）。标签相对车图显示矩形按百分比定位，
 * 任意屏宽比例自适应；字号按屏宽dp等比缩放。无 Car API 时（模拟器）填模拟数据展示。
 *
 * 日志策略（一次上车全拿到）：
 *  - /sdcard/Download/CarProbe/boot.log — 全量过程日志（启动/连接/注册/数据/异常/崩溃）
 *  - /sdcard/Download/CarProbe/carinfo.log — 车况数据快照（5秒节流）
 *  - 崩溃时先写日志再转发系统处理器（避免 Activity 假死黑屏）
 */
public class MainActivity extends Activity {

    private static final String TAG = "LinCarInfo";
    private static final File BOOT_LOG = new File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "CarProbe/boot.log");

    // Sensor types —— 语义按 CarLauncher ei.java/er.java/eo.java/ep.java/eq.java 反编译实锚
    // 注意：车机是厂商定制小数字 ID，不是标准 PROPERTY_ID（大数字全部 invalid sensor type）
    private static final int SENSOR_ODOMETER = 4;          // 总里程 float，无效值哨兵 1.6777215E7
    private static final int SENSOR_SPEED = 2;            // 车速 float km/h
    private static final int SENSOR_GEAR = 7;             // 挡位！（er.java: getGearData().gear，非油量）
    private static final int SENSOR_IGNITION = 22;        // 点火状态 int（IGNITION_STATE_*）
    private static final int SENSOR_FUEL_WARNING = 25;    // 油量预警开关 int（eq.java: ==1 则报警）
    private static final int SENSOR_RANGE = 26;          // 续航里程 float km（eo.java: leftMile，非剩余油量%）
    private static final int SENSOR_FUEL_PCT = 27;        // 油量百分比 float %（ep.java: car_oil_percent）
    private static final int SENSOR_OIL_LIFE = 51;       // 机油寿命%（实锤：林肯之道APP显示75%完全吻合）
    private static final int SENSOR_DOOR_LB = 56;
    private static final int SENSOR_DOOR_RB = 57;
    private static final int SENSOR_DOOR_LR = 58;
    private static final int SENSOR_DOOR_RR = 59;
    private static final int SENSOR_TRUNK_IN = 60;
    private static final int SENSOR_DOOR_TRUNK_OUT = 61;
    // 标准大数字 PROPERTY_ID 传感器（挡位 289408000/手刹 287310850 等）已删——
    // 19:58 车机实测全部 invalid sensor type，车机只认厂商小数字 ID

    /** 传感器编号 → 名称（用于日志与显示） */
    private static final HashMap<Integer, String> SENSOR_NAMES = new HashMap<>();
    static {
        SENSOR_NAMES.put(SENSOR_SPEED, "车速");
        SENSOR_NAMES.put(SENSOR_ODOMETER, "总里程");
        SENSOR_NAMES.put(SENSOR_GEAR, "挡位");
        SENSOR_NAMES.put(SENSOR_IGNITION, "点火");
        SENSOR_NAMES.put(SENSOR_FUEL_WARNING, "油量预警");
        SENSOR_NAMES.put(SENSOR_RANGE, "续航");
        SENSOR_NAMES.put(SENSOR_FUEL_PCT, "油量百分比");
        SENSOR_NAMES.put(SENSOR_OIL_LIFE, "机油寿命");
        SENSOR_NAMES.put(SENSOR_DOOR_LB, "左前门");
        SENSOR_NAMES.put(SENSOR_DOOR_RB, "右前门");
        SENSOR_NAMES.put(SENSOR_DOOR_LR, "左后门");
        SENSOR_NAMES.put(SENSOR_DOOR_RR, "右后门");
        SENSOR_NAMES.put(SENSOR_TRUNK_IN, "后备箱内");
        SENSOR_NAMES.put(SENSOR_DOOR_TRUNK_OUT, "后备箱外");
        // 未知小数字 ID 保留原编号（车机 19:58 实测支持 5/9/11/49-52/62-72，待用户验证含义）
    }

    // VendorExtension property IDs
    private static final int PROP_TIRE_STATE = 658506770;
    private static final int PROP_TIRE_PRESSURE = 660603920;
    private static final int PROP_TIRE_TEMP = 660603921;
    private static final int PROP_MILEAGE_UNIT = 557843475;  // CarLauncher ej.java: 里程单位
    private static final int PROP_CAR_IDENTIFY = 117440591;  // CarLauncher em.java: 车辆标识
    private static final int[] TIRE_AREAS = {1, 2, 4, 8};

    // CarInfoManager property IDs（照抄 CarLauncher eh.n()/m()）
    private static final int INFO_CAR_TYPE = 117440513;   // carType 车型代码（bytes[0]+bytes[1] 为索引）
    private static final int INFO_CAR_STYLE = 117440515;  // carStyle
    private static final int INFO_CAR_PATTERN = 117440516; // carPattern/VsCode
    private static final int INFO_CAR_COLOR = 117440517;  // carColor
    private static final int INFO_FUEL_TYPE = 117440545;  // 燃油类型
    private static final int INFO_AAR_CONFIG = 117440539; // AAR 配置

    // 车型名称表（照抄 CarLauncher eh.h：索引 = bytes[0]+bytes[1]）
    private static final String[] CAR_TYPE_NAMES = {
            "Default", "CD539", "C519", "D568", "CD391", "CX482", "CX483", "U625", "U611", "U540"};

    // Car 实例和 Manager（全反射，Object 持有）
    private Object car;
    private Object sensorManager;
    private Object vendorManager;
    private Object infoManager;
    private Object yfManager;          // CarYFSettingManager（智能新风/滤芯/车窗/胎压单位）
    private Object hvacManager;        // CarHvacManager（滤芯状态机入参 r + HVAC 全属性）
    private Object sensorListener;
    private Object yfCallback;         // YF 属性推送回调（动态代理）
    private Object hvacCallback;       // HVAC 属性推送回调（动态代理）

    // ========== HVAC 扩展（CarHvacManager 全属性枚举直读） ==========
    // 车机 HVAC 是厂商小数字 ID。23:01 车机实测 + 用户操作确认（boot(10)/carinfo(7)）：
    //   16385=设定温度（float℃，单区，area1/area4 同值双报，area128 后排无推送）
    //   16388=风量档（1→2→3→4 递增，15=最大/除雾模式——按前窗最大加热键时系统自动设最大）
    //   16392=风向模式（值序列 3→2→1→0→6→5→4 两轮切换，具体模式名无实锤不猜）
    //   16393=A/C制冷开关（1=开 0=关，area8 前排）
    //   16395=内循环开关（1=开 0=关，与 16391/16396/20487 联动）
    //   16387=CCM功率（2=空调关闭 1=空调通电，滤芯状态机入参 r）
    //   20481=座椅通风（area1=主驾 area2=副驾，0=关 1=开 2=初始态）
    //   20487=进气模式（与内循环联动：1=内循环 2=外循环）
    //   20513=出风强度（随风量同步变化，疑似鼓风机功率/RPM，全局区 16777216）
    //   未实锚：16391/16394/16396/16397/16398/20489（与内循环/A/C 联动的开关类，待单独操作确认）
    /** HVAC 属性 ID → 名称（已实锤语义的才有名称，其余显示 propId 原值） */
    private static final HashMap<Integer, String> HVAC_PROP_NAMES = new HashMap<>();
    static {
        HVAC_PROP_NAMES.put(4, "温度单位(4)");
        HVAC_PROP_NAMES.put(16385, "设定温度℃(16385)");
        HVAC_PROP_NAMES.put(16387, "CCM功率(16387)");
        HVAC_PROP_NAMES.put(16388, "风量档(16388)");
        HVAC_PROP_NAMES.put(16392, "风向模式(16392)");
        HVAC_PROP_NAMES.put(16393, "A/C制冷(16393)");
        HVAC_PROP_NAMES.put(16395, "内循环(16395)");
        HVAC_PROP_NAMES.put(20481, "座椅通风(20481)");
        HVAC_PROP_NAMES.put(20487, "进气模式(20487)");
        HVAC_PROP_NAMES.put(20513, "出风强度(20513)");
    }
    /** 枚举到的 HVAC 属性：key=propId，value="区域列表|读到的值" */
    private final TreeMap<Integer, String> hvacProps = new TreeMap<>();
    /** HVAC 推送计数（propId → 次数），避免刷爆日志 */
    private final HashMap<Integer, Integer> hvacPushCount = new HashMap<>();
    /** 里程单位：0=km 1=mi（照抄 CarLauncher eh.w，由 PROP_MILEAGE_UNIT 读出） */
    private String mileageUnit = "km";

    // 连接重试控制（照抄 CarLauncher 的 CarConnectionController）
    private volatile boolean serviceConnected;
    private int tryConnectCount;
    private Handler carHandler;          // 车机连接回调 Handler（后台线程）
    private static final int MSG_TRY_CONNECT = 1000;
    private static final int MSG_CHECK_CONNECTED = 1001;
    private static final int MSG_DISCONNECTED = 1002;

    // ========== 仪表盘 UI 控件（V2） ==========
    // 旧版 tvStatus/tvTireInfo/tvLog 文本界面已废弃：ui()/setStatus() 降级为仅写日志，
    // 数据快照由 buildDataSnapshot() 构造文本写 carinfo.log。
    private static final float DESIGN_WIDTH_DP = 360f;   // 效果图 720px@2x → 360dp 基准

    private TextView tvGear, tvRange, tvOdo, tvOil, tvFuel, tvSpeed;
    private TextView tvRangeUnit, tvOdoUnit;
    private TextView tvTempDriver, tvPm, tvTempPassenger;

    private DoorCircleView tagLFDw, tagRFDw, tagLRDw, tagRRDw;
    private TireCircleView tagLFTire, tagRFTire, tagLRTire, tagRRTire;
    private DoorCircleView tagTrunk;
    private ImageView ivCar;
    private View carZone;
    private TagLineOverlay tagLines;
    /** 是否已连上车机数据（模拟器无 Car API 时用模拟数据展示界面） */
    private boolean hasRealData;

    // ========== 智能新风/AAR（CarYFSettingManager，服务名 vendor_yfsetting） ==========
    // 属性 ID 照抄 CarLauncher dn.java（AARDataController），全部只读（getGlobalProperty），
    // 788/789 是车机 set 下发的，不写
    private static final int YF_PM_NOW        = 289419786; // ID_PMCABN_D 车内 PM 浓度 μg/m³
    private static final int YF_PM_DIAG       = 289419787; // ID_PMSNSCABN_D_STAT 传感器诊断状态
    private static final int YF_SENSOR_MISS   = 289419790; // ID_PMSNSCABN_D 传感器缺失(1=缺Can)
    private static final int YF_CCM_POWER     = 289419791; // CCM 功率按钮（滤芯状态机入参 q）
    private static final int[] YF_PM_HISTORY = {
            289419776, 289419777, 289419778, 289419779, 289419780,
            289419781, 289419782, 289419783, 289419784, 289419785}; // PM 历史（2~20点，0起）
    // 四车窗状态（dn.java a(CarYFSettingManager)：v=198 司机窗 w=200 副驾窗 x=199 左后 y=201 右后）
    private static final int YF_WIN_DRIVER  = 289437198;
    private static final int YF_WIN_PASSENGER = 289437200;
    private static final int YF_WIN_LEFT_REAR  = 289437199;
    private static final int YF_WIN_RIGHT_REAR = 289437201;
    // 胎压单位请求（fk.java/fm.java：写 292565248 触发推送，byte[] 回调前2字节=2312 标识）
    private static final int YF_TIRE_UNIT_REQ = 292565248;

    /** AAR 最新缓存（dn.java 字段 q/r/s/t/u 照搬） */
    private int aarPowerBtn = -1;    // q: YF_CCM_POWER
    private int aarHvacCcm  = -1;   // r: HVAC getIntProperty(16387,8)，滤芯判定入参
    private int aarSensorMiss = -1; // s: YF_SENSOR_MISS
    private int aarPmNow     = -1;  // t: YF_PM_NOW
    private int aarPmDiag    = -1;  // u: YF_PM_DIAG
    private int[] aarPmHistory = new int[10]; // PM 历史（下标 0~9 对应 2~20 点钟）
    private int winDriver = -1, winPassenger = -1, winLeftRear = -1, winRightRear = -1;
    private int aarState = 0;      // o: 滤芯状态 1=开启 2=关闭 3=完成 4=异常
    private String tireUnit = "";   // 胎压单位（"psi"/"kPa"/"bar"），空=未取到

    /** 车机 UI 转义文本（照抄 dn.h()） */
    private static String aarStateStr(int s) {
        switch (s) {
            case 1: return "过滤开启";
            case 2: return "过滤关闭";
            case 3: return "过滤完成";
            case 4: return "异常(滤芯需更换)";
            default: return "未知(" + s + ")";
        }
    }

    /** 窗开关文本：0=开 1=关（dn.f(): 四窗全 1 才算完整工作） */
    private static String winStr(int v) {
        if (v < 0) return "N/A";
        return v == 1 ? "关" : "开";
    }
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final StringBuilder logBuilder = new StringBuilder();

    // 缓存最新传感器事件
    private Object evOdometer, evSpeed, evGear, evRange, evFuelPct, evFuelWarn, evIgnition, evOilLife;
    private Object evDoorLB, evDoorRB, evDoorLR, evDoorRR, evTrunkIn, evTrunkOut;
    /** 其余传感器（手刹/RPM等）最新事件，key=sensorType */
    private final HashMap<Integer, Object> evOthers = new HashMap<>();
    private final HashMap<Integer, TireData> tireData = new HashMap<>();
    /** 首次收到某 sensor 数据时记录一次完整内容 */
    private final HashSet<Integer> firstDataLogged = new HashSet<>();

    /** 需要记录值变化的传感器类型（仅待验证参数，已验证的不记） */
    private static final HashSet<Integer> MONITORED_TYPES = new HashSet<>();
    static {
        MONITORED_TYPES.add(11);                   // 11 挡位结合状态(0=P/31=行驶)【待验证：挂挡】
        MONITORED_TYPES.add(52);                   // 52 驻车/驱动状态【待重验：拉手刹无变化】
        MONITORED_TYPES.add(67);                   // 67 电源档位(12=ON/0=OFF)【已实锤】
        MONITORED_TYPES.add(68);                   // 68 左转向灯【已实锤，高频闪烁日志量大但短暂】
        MONITORED_TYPES.add(69);                   // 69 右转向灯【已实锤】
        MONITORED_TYPES.add(72);                   // 72 点火电源位(1=ON/0=LOCK)【已实锤】
    }
    /** 监控参数上次值（用于变化检测，key=sensorType） */
    private final HashMap<Integer, String> lastSensorValue = new HashMap<>();
    /** 已注册的传感器集合（动态枚举，车机只认厂商小数字 ID） */
    private final HashSet<Integer> registeredSensors = new HashSet<>();
    /** carinfo.log 节流 */
    private long lastFileWrite;

    // ========== 日夜模式（两态：白天 / 夜间，页面单击切换 + 高德导航广播自动切换） ==========
    /** SP 文件名与键 */
    private static final String PREF_UI = "ui";
    private static final String KEY_DAY_MODE = "day_mode";   // 0=夜间 1=白天（默认夜间）
    private static final String KEY_AMAP_TRIGGERED = "amap_triggered"; // 高德是否触发过
    static final int MODE_NIGHT = 0;
    static final int MODE_DAY = 1;
    private int uiModePref = MODE_NIGHT;

    // 高德导航日夜模式广播
    private static final String ACTION_AUTONAVI = "AUTONAVI_STANDARD_BROADCAST_SEND";
    private static final int KEY_TYPE_DAY_NIGHT = 10019;
    private static final int EXTRA_STATE_DAY = 37;
    private static final int EXTRA_STATE_NIGHT = 38;
    private long lastAmapThemeTime = 0;
    private BroadcastReceiver amapThemeReceiver;

    private android.content.SharedPreferences uiPrefs() {
        return getSharedPreferences(PREF_UI, MODE_PRIVATE);
    }

    /** 读取 SP 偏好并在 Resources 上强制应用 uiMode 覆盖（每次 attachBaseContext 调用） */
    private void applyDayNightOverride(android.content.res.Resources res) {
        uiModePref = uiPrefs().getInt(KEY_DAY_MODE, MODE_NIGHT);
        int nightMode = (res.getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK);
        int desired = uiModePref == MODE_DAY ? android.content.res.Configuration.UI_MODE_NIGHT_NO
                : android.content.res.Configuration.UI_MODE_NIGHT_YES;
        if (desired != nightMode) {
            android.content.res.Configuration cfg = new android.content.res.Configuration(res.getConfiguration());
            cfg.uiMode = (cfg.uiMode & ~android.content.res.Configuration.UI_MODE_NIGHT_MASK) | desired;
            res.updateConfiguration(cfg, res.getDisplayMetrics());
        }
    }

    /** 页面单击切换：夜间 ↔ 白天，SP 持久化 + recreate */
    private void toggleDayNight() {
        int next = uiModePref == MODE_DAY ? MODE_NIGHT : MODE_DAY;
        uiPrefs().edit()
                .putInt(KEY_DAY_MODE, next)
                .putBoolean(KEY_AMAP_TRIGGERED, false) // 手动切换，暂停高德同步
                .apply();
        bootLog("[UI] 日夜模式手动切换: " + (next == MODE_DAY ? "白天" : "夜间"));
        recreate();
    }

    /** 注册高德导航日夜模式广播 */
    private void registerAmapThemeReceiver() {
        amapThemeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!ACTION_AUTONAVI.equals(intent.getAction())) return;
                int keyType = intent.getIntExtra("KEY_TYPE", -1);
                if (keyType != KEY_TYPE_DAY_NIGHT) return;
                int extraState = intent.getIntExtra("EXTRA_STATE", -1);
                if (extraState != EXTRA_STATE_DAY && extraState != EXTRA_STATE_NIGHT) return;
                // 防抖：500ms 内不重复处理
                long now = System.currentTimeMillis();
                if (now - lastAmapThemeTime < 500) return;
                lastAmapThemeTime = now;
                boolean isNight = (extraState == EXTRA_STATE_NIGHT);
                int targetMode = isNight ? MODE_NIGHT : MODE_DAY;
                if (targetMode == uiModePref) return; // 已是目标模式，无需切换
                uiPrefs().edit()
                        .putInt(KEY_DAY_MODE, targetMode)
                        .putBoolean(KEY_AMAP_TRIGGERED, true) // 高德触发，标记自动同步
                        .apply();
                bootLog("[UI] 高德导航触发日夜切换: " + (isNight ? "夜间" : "白天"));
                runOnUiThread(() -> recreate());
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_AUTONAVI);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                Method m = Context.class.getMethod("registerReceiver",
                        BroadcastReceiver.class, IntentFilter.class, int.class);
                m.invoke(this, amapThemeReceiver, filter, Context.RECEIVER_EXPORTED);
            } catch (Throwable e) {
                Log.w(TAG, "反射注册高德广播失败，回退2参数: " + e);
                registerReceiver(amapThemeReceiver, filter);
            }
        } else {
            registerReceiver(amapThemeReceiver, filter);
        }
        bootLog("[UI] 高德导航日夜广播已注册");
        // 主动查询高德当前日夜状态（应用启动时高德不会主动发广播）
        queryAmapDayNight();
    }

    /** 主动向高德查询当前日夜模式 */
    private void queryAmapDayNight() {
        try {
            Intent query = new Intent("AUTONAVI_STANDARD_BROADCAST_GET");
            query.putExtra("KEY_TYPE", KEY_TYPE_DAY_NIGHT);
            sendBroadcast(query);
            bootLog("[UI] 已向高德查询当前日夜状态");
        } catch (Throwable e) {
            Log.w(TAG, "查询高德日夜状态失败: " + e);
        }
    }

    /** 注销高德导航日夜模式广播 */
    private void unregisterAmapThemeReceiver() {
        if (amapThemeReceiver != null) {
            try { unregisterReceiver(amapThemeReceiver); } catch (Throwable ignored) {}
            amapThemeReceiver = null;
        }
    }

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(base);
        // 日夜模式覆盖：必须在 super.onCreate 之前生效，否则 setContentView 解析的是旧资源
        try {
            applyDayNightOverride(getResources());
        } catch (Throwable e) {
            android.util.Log.w(TAG, "日夜模式覆盖失败", e);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 全局崩溃捕获：写文件后转发系统处理器（不吞异常，避免假死黑屏）
        final Thread.UncaughtExceptionHandler def = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            bootLog("*** 崩溃 *** 线程=" + thread.getName() + " " + throwable.getClass().getName());
            bootLog(stackTrace(throwable));
            if (def != null) def.uncaughtException(thread, throwable);
        });

        bootLog("");
        bootLog("########################################################");
        bootLog("##### 会话开始 " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date()) + " #####");
        bootLog("########################################################");
        logDeviceInfo();

        try {
            setContentView(R.layout.activity_main);
            bootLog("[UI] setContentView OK");
        } catch (Throwable e) {
            bootLog("[UI] setContentView 失败: " + stackTrace(e));
            throw e;
        }

        initUi();
        refreshDisplay();
        bootLog("[UI] 字号缩放+标签定位完成");

        ui("系统签名直读版探针启动");

        // 一次性探测车机 Car API 支持情况
        probeCarApiClasses();

        if (!isCarApiAvailable()) {
            bootLog("[onCreate] 此设备无 android.car.Car 类 — 模拟器模式：填模拟数据展示界面");
            loadMockData();
            bootLog("[onCreate] 结束: 无 Car API（模拟数据模式）");
            return;
        }

        try {
            connectCarService();
        } catch (Throwable e) {
            bootLog("[onCreate] connectCarService 异常:\n" + stackTrace(e));
            ui("连接异常: " + e.getMessage());
        }
        bootLog("[onCreate] 完成");
    }

    // ========== 仪表盘 UI 辅助（V2） ==========

    /** 判断当前是否横屏：用 Configuration 的 screenWidthDp/screenHeightDp（反映窗口实际可用尺寸），
     *  而非屏幕物理分辨率——分屏拖宽时 DisplayMetrics.widthPixels 不变，但 screenWidthDp 会变大。 */
    private boolean isLandscape() {
        android.content.res.Configuration cfg = getResources().getConfiguration();
        return cfg.screenWidthDp > cfg.screenHeightDp * 1.2f;
    }

    /** 获取窗口实际宽度 dp（用于字号缩放基准，分屏时随窗口宽度变化） */
    private float windowWidthDp() {
        android.content.res.Configuration cfg = getResources().getConfiguration();
        return cfg.screenWidthDp;
    }

    /** 字号等比缩放：布局按 360dp 基准设计，按 窗口宽度dp/360 缩放全部 TextView 字号。
     *  横屏(宽>高×1.2)时 scale=1.0 不放大。banner/空调卡由 scaleBannerByHeight/scaleAcCardByHeight 单独处理。 */
    private void applyFontScale() {
        try {
            float winW = windowWidthDp();
            float scale = winW / DESIGN_WIDTH_DP;
            boolean landscape = isLandscape();
            if (landscape) scale = 1.0f;
            bootLog("[UI] 窗口宽=" + winW + "dp → 字号缩放 " + String.format("%.2f", scale) + (landscape ? "(横屏不放大)" : ""));
            View root = findViewById(R.id.root);
            scaleTextSize(root, scale);
            // 圆形标签缩放：竖屏按屏宽/540dp，横屏按屏高/540dp，下限 0.5 防过小
            float refDp = landscape ? getResources().getConfiguration().screenHeightDp
                    : getResources().getConfiguration().screenWidthDp;
            float circleScale = Math.min(refDp / 540f, 1f);
            circleScale = Math.max(circleScale, 0.5f);
            if (tagLFTire != null) tagLFTire.setDpScale(circleScale);
            if (tagRFTire != null) tagRFTire.setDpScale(circleScale);
            if (tagLRTire != null) tagLRTire.setDpScale(circleScale);
            if (tagRRTire != null) tagRRTire.setDpScale(circleScale);
            if (tagLFDw != null) tagLFDw.setDpScale(circleScale);
            if (tagRFDw != null) tagRFDw.setDpScale(circleScale);
            if (tagLRDw != null) tagLRDw.setDpScale(circleScale);
            if (tagRRDw != null) tagRRDw.setDpScale(circleScale);
            if (tagTrunk != null) tagTrunk.setDpScale(circleScale);
        } catch (Throwable e) {
            bootLog("[UI] applyFontScale 失败: " + rootCause(e));
        }
    }

    private void scaleTextSize(View v, float scale) {
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, tv.getTextSize() * scale);
        } else if (v instanceof LinearLayout || v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) scaleTextSize(g.getChildAt(i), scale);
        }
    }

    /** banner 字号：横屏按高度比例覆盖；竖屏按宽度自适应防止折行 */
    private void scaleBannerByHeight() {
        final View banner = findViewById(R.id.banner);
        if (banner == null) return;
        banner.getViewTreeObserver().addOnGlobalLayoutListener(
            new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override public void onGlobalLayout() {
                    try {
                        int bh = banner.getHeight();
                        int bw = banner.getWidth();
                        if (bh <= 0 || bw <= 0) return;
                        banner.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        if (isLandscape()) {
                            float valSize = bh * 0.42f;
                            float lblSize = bh * 0.15f;
                            scaleBannerText(banner, valSize, lblSize);
                            bootLog("[UI] 横屏 banner高=" + bh + "px → 数值" + valSize + "px 标签" + lblSize + "px(按高度)");
                        } else {
                            // 竖屏：测量内容宽度，如溢出则等比缩小字号+间距
                            fitBannerWidth(banner, bw);
                            bootLog("[UI] 竖屏 banner高=" + bh + "px 宽=" + bw + "px → 自适应宽度");
                        }
                    } catch (Throwable e) {
                        bootLog("[UI] scaleBannerByHeight 失败: " + rootCause(e));
                        banner.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                    }
                }
            });
    }

    /**
     * 竖屏 banner 宽度自适应：测量所有子 View 内容宽度总和，
     * 如超出 banner 可用宽度，则等比缩小字号和间距。
     */
    private void fitBannerWidth(View banner, int bannerWidth) {
        if (!(banner instanceof android.view.ViewGroup)) return;
        android.view.ViewGroup group = (android.view.ViewGroup) banner;
        // 测量所有子 View 的总宽度（含 margin）
        int contentWidth = 0;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            child.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            android.widget.LinearLayout.LayoutParams lp = (android.widget.LinearLayout.LayoutParams) child.getLayoutParams();
            contentWidth += child.getMeasuredWidth() + lp.leftMargin + lp.rightMargin;
        }
        if (contentWidth <= bannerWidth) return; // 未溢出，无需缩放
        float ratio = (float) bannerWidth / contentWidth;
        // 缩小字号
        scaleBannerTextByRatio(banner, ratio);
        // 缩小间距
        scaleBannerMarginByRatio(group, ratio);
        bootLog("[UI] banner内容=" + contentWidth + "px > 可用=" + bannerWidth + "px → 缩放比例=" + String.format("%.2f", ratio));
    }

    private void scaleBannerTextByRatio(View v, float ratio) {
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, tv.getTextSize() * ratio);
        } else if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) scaleBannerTextByRatio(g.getChildAt(i), ratio);
        }
    }

    private void scaleBannerMarginByRatio(android.view.ViewGroup group, float ratio) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            android.widget.LinearLayout.LayoutParams lp = (android.widget.LinearLayout.LayoutParams) child.getLayoutParams();
            lp.leftMargin = Math.round(lp.leftMargin * ratio);
            lp.rightMargin = Math.round(lp.rightMargin * ratio);
            if (lp instanceof android.widget.LinearLayout.LayoutParams) {
                child.setLayoutParams(lp);
            }
            if (child instanceof android.view.ViewGroup) {
                scaleBannerMarginByRatio((android.view.ViewGroup) child, ratio);
            }
        }
    }

    /** 递归设 banner 内 TextView 字号：有 id 的设 valSize，无 id 的设 lblSize */
    private void scaleBannerText(View v, float valSize, float lblSize) {
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            float size = (tv.getId() != View.NO_ID) ? valSize : lblSize;
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
        } else if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) scaleBannerText(g.getChildAt(i), valSize, lblSize);
        }
    }


    /** 空调卡字号：横屏按高度比例覆盖；竖屏不处理（已由 applyFontScale 按宽度缩放） */
    private void scaleAcCardByHeight() {
        final View acCard = findViewById(R.id.acCard);
        if (acCard == null) return;
        acCard.getViewTreeObserver().addOnGlobalLayoutListener(
            new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override public void onGlobalLayout() {
                    try {
                        int ah = acCard.getHeight();
                        if (ah <= 0) return;
                        acCard.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        if (isLandscape()) {
                            // 横屏 acCard 高度小（~50px），压缩 padding 释放空间给文字
                            acCard.setPadding(dip(10), dip(1), dip(10), dip(1));
                            LinearLayout.LayoutParams acLp = (LinearLayout.LayoutParams) acCard.getLayoutParams();
                            acLp.bottomMargin = dip(3);
                            acCard.setLayoutParams(acLp);
                            android.view.ViewGroup cardOuter = (android.view.ViewGroup) acCard;
                            android.view.ViewGroup cardRow = (android.view.ViewGroup) cardOuter.getChildAt(0);
                            // 横屏：三个子区域改为一行显示（标签+数值水平排列）
                            for (int i = 0; i < cardRow.getChildCount(); i++) {
                                View child = cardRow.getChildAt(i);
                                if (child instanceof LinearLayout) {
                                    LinearLayout zone = (LinearLayout) child;
                                    zone.setOrientation(LinearLayout.HORIZONTAL);
                                    zone.setPadding(dip(2), dip(1), dip(2), dip(1));
                                    // 标签和数值之间加间距
                                    for (int j = 0; j < zone.getChildCount(); j++) {
                                        View tv = zone.getChildAt(j);
                                        if (tv instanceof TextView) {
                                            TextView t = (TextView) tv;
                                            if (j > 0) t.setPadding(dip(4), 0, 0, 0);
                                        }
                                    }
                                }
                            }
                            // 按子区域实际高度设字号（单行，字号=可用高度）
                            android.view.ViewGroup zone = (android.view.ViewGroup) cardRow.getChildAt(0);
                            int zoneH = zone.getHeight();
                            if (zoneH > 0) {
                                int usable = zoneH - dip(2);
                                float valSize = usable * 0.7f;   // 数值占可用高度 70%
                                float lblSize = usable * 0.35f;  // 标签占 35%
                                scaleBannerText(acCard, valSize, lblSize);
                                bootLog("[UI] 横屏 acCard高=" + ah + " zoneH=" + zoneH + " → 一行显示 数值" + valSize + "px 标签" + lblSize + "px");
                            }
                        } else {
                            bootLog("[UI] 竖屏 acCard高=" + ah + "px → 保持 applyFontScale 宽度缩放");
                        }
                    } catch (Throwable e) {
                        bootLog("[UI] scaleAcCardByHeight 失败: " + rootCause(e));
                        acCard.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                    }
                }
            });
    }


    /**
     * 车图区标签定位：锚点相对「车体实际矩形」+ 固定外扩间距。
     * 原理：fitCenter 缩放后车图矩形可算出（1000:1333 比例固定，4:3），
     * 车体在图内范围（像素分析实锚）：x 21.9%~78.0%、y 11.4%~88.7%。
     * 换算出车体矩形后，标签按"车体外缘外扩 gap"定位——
     * 任意屏宽比例下标签都围绕车体、保持呼吸距离，不挤不压。
     *
     * 布置（定稿）：
     *   胎压标签——对齐四轮胎心高度（y 前轮 36.5% 后轮 67%），x 在车体外左右两侧
     *   门窗标签——对齐前后轮之间偏上/偏下（y 30%/72%），x 在车体外左右两侧，与胎压标签错位
     *   后备箱——车体下方居中
     */
    private void layoutCarTags() {
        if (carZone == null || ivCar == null) return;
        carZone.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    int pass = 0;
                    @Override
                    public void onGlobalLayout() {
                        try {
                            // 第 1 遍布局量出标签尺寸，第 2 遍（margin 变更触发）再校准定位
                            positionCarTags();
                            if (++pass == 1) {
                                carZone.requestLayout();   // 触发第二遍，此时标签已量宽高
                            } else {
                                carZone.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                            }
                        } catch (Throwable e) {
                            bootLog("[UI] 标签定位失败: " + rootCause(e));
                            carZone.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        }
                    }
                });
    }

    private void positionCarTags() {
        int zw = carZone.getWidth(), zh = carZone.getHeight();
        if (zw <= 0 || zh <= 0) return;
        bootLog("[UI] carZone=" + zw + "x" + zh + " 镜像对称定位");

        // 横屏时增大 ImageView margin，使车图缩到较小边的 90%
        if (isLandscape() && ivCar != null) {
            // 原始 margin 30dp/24dp，横屏额外加 5% 空间（约 (100%-90%)/2 = 5%）
            int extraW = Math.round(zw * 0.05f);
            int extraH = Math.round(zh * 0.05f);
            android.widget.FrameLayout.LayoutParams lp = (android.widget.FrameLayout.LayoutParams) ivCar.getLayoutParams();
            lp.setMargins(dip(30) + extraW, dip(24) + extraH, dip(30) + extraW, dip(24) + extraH);
            ivCar.setLayoutParams(lp);
        }

        float tireEdgeX = zw * 0.14f;       // 胎压标签离左/右边缘 14%
        float dwEdgeX = zw * 0.06f;        // 门窗标签离左/右边缘 6%

        // 对称轴：前后门窗标注点中点 y=52.5%（相对车图 fitH）
        float[] r = carImgRect(zw, zh);
        float axisY = r[1] + r[3] * 0.525f;

        // 前胎压标签：跟随车图顶部，留一定间距
        float tireTopY = r[1] - dip(6);
        placeCornerTag(tagLFTire, tireEdgeX, tireTopY, true, false);
        placeCornerTag(tagRFTire, zw - tireEdgeX, tireTopY, false, false);

        // 左侧：前胎压中心 → 镜像后胎压中心 → 等间距放门窗
        float cFL = tireTopY + (tagLFTire != null ? tagLFTire.getHeight() : 0) / 2f;
        float cRL = 2f * axisY - cFL;
        centerTagAt(tagLRTire, tireEdgeX, cRL, true);
        float stepL = (cRL - cFL) / 3f;
        centerTagAt(tagLFDw, dwEdgeX, cFL + stepL, true);
        centerTagAt(tagLRDw, dwEdgeX, cFL + stepL * 2f, true);

        // 右侧：同理
        float cFR = tireTopY + (tagRFTire != null ? tagRFTire.getHeight() : 0) / 2f;
        float cRR = 2f * axisY - cFR;
        centerTagAt(tagRRTire, zw - tireEdgeX, cRR, false);
        float stepR = (cRR - cFR) / 3f;
        centerTagAt(tagRFDw, zw - dwEdgeX, cFR + stepR, false);
        centerTagAt(tagRRDw, zw - dwEdgeX, cFR + stepR * 2f, false);

        // 后备箱：水平中心对齐车图中心，垂直与后胎压标签中心对齐
        if (tagTrunk != null) {
            int w = tagTrunk.getWidth(), h = tagTrunk.getHeight();
            android.widget.FrameLayout.LayoutParams lp =
                    (android.widget.FrameLayout.LayoutParams) tagTrunk.getLayoutParams();
            // 水平中心对齐车图中心（非 carZone 中心），使连接线垂直
            float carCenterX = r[0] + r[2] / 2f;
            lp.leftMargin = Math.round(carCenterX - w / 2f);
            lp.topMargin = Math.round(cRL - h / 2f);
            tagTrunk.setLayoutParams(lp);
            tagTrunk.bringToFront();
        }
        for (View tag : new View[]{tagLFTire, tagRFTire, tagLRTire, tagRRTire,
                tagLFDw, tagRFDw, tagLRDw, tagRRDw}) {
            if (tag != null) tag.bringToFront();
        }
        if (tagTrunk != null) tagTrunk.bringToFront();

        // —— 9 个红点紧贴车图 + 虚线连到标签 ——
        linkTagDots();
    }


    /** 算车图 fitCenter 显示矩形（1000:1333 比例，4:3），返回 left/top/fitW/fitH。
     *  横屏(宽>高×1.2)时按较小边的 90% 缩放，给标签留更多空间。 */
    private float[] carImgRect(int zw, int zh) {
        float ratio = 1333f / 1000f; // 高/宽
        int mL = dip(30) + dip(6), mR = dip(30) + dip(6);
        int mT = dip(24) + dip(6), mB = dip(24) + dip(6);
        int availW = Math.max(1, zw - mL - mR);
        int availH = Math.max(1, zh - mT - mB);
        // 横屏按较小边的 90% 限制
        if (isLandscape()) {
            availW = (int) (availW * 0.9f);
            availH = (int) (availH * 0.9f);
        }
        float fitW, fitH;
        if (availW * ratio >= availH) { fitH = availH; fitW = fitH / ratio; }
        else { fitW = availW; fitH = fitW * ratio; }
        float left = (zw - fitW) / 2f, top = (zh - fitH) / 2f;
        return new float[]{left, top, fitW, fitH};
    }

    /** 9 个标注点标在车图实体上（轮胎心/车体侧缘/车尾），虚线连到对应标签中心；alert=true 红色，false 青色 */
    private void linkTagDots() {
        if (tagLines == null) return;
        int zw = carZone.getWidth(), zh = carZone.getHeight();
        float[] r = carImgRect(zw, zh);
        float left = r[0], top = r[1], fitW = r[2], fitH = r[3];
        // 车门高度车体实测边缘（y 43%/56% 处）：x 26.9% / 73.1%（21.9%/78.0% 是轮胎外缘，不是车体）
        float bodyL = left + fitW * 0.269f, bodyR = left + fitW * 0.731f;
        // 四轮胎心（像素实测：前轮 y 29%、后轮 y 73.2%，左轮 x 24%、右轮 x 75.8%）
        float tireFrontY = top + fitH * 0.290f, tireRearY = top + fitH * 0.732f;
        float tireLX = left + fitW * 0.240f, tireRX = left + fitW * 0.758f;
        // 车门位置（图内 y 46% / 59%；后门窗点下移，与前门窗点拉开足够间距）
        float doorFrontY = top + fitH * 0.460f, doorRearY = top + fitH * 0.590f;
        // 车尾（x 50%，y 86% 车体底部附近）
        float tailX = left + fitW * 0.50f, tailY = top + fitH * 0.860f;

        tagLines.clear();
        // 胎压 4 点 → 四轮胎心（异常=压力<200kPa）
        tagLines.setLink(tireLX, tireFrontY, tagLFTire, tireAlert(tireData.get(1)), true);
        tagLines.setLink(tireRX, tireFrontY, tagRFTire, tireAlert(tireData.get(2)), true);
        tagLines.setLink(tireLX, tireRearY, tagLRTire, tireAlert(tireData.get(4)), true);
        tagLines.setLink(tireRX, tireRearY, tagRRTire, tireAlert(tireData.get(8)), true);
        // 门窗 4 点 → 车体左右边缘车门处（异常=门开或窗开）
        tagLines.setLink(bodyL, doorFrontY, tagLFDw, doorAlert(evDoorLB, winDriver));
        tagLines.setLink(bodyR, doorFrontY, tagRFDw, doorAlert(evDoorRB, winPassenger));
        tagLines.setLink(bodyL, doorRearY, tagLRDw, doorAlert(evDoorLR, winLeftRear));
        tagLines.setLink(bodyR, doorRearY, tagRRDw, doorAlert(evDoorRR, winRightRear));
        // 尾部 1 点 → 车尾中央（异常=后备箱/尾门开）
        tagLines.setLink(tailX, tailY, tagTrunk,
                getInt(evTrunkIn) > 0 || getInt(evTrunkOut) > 0);
        tagLines.apply();
    }

    /** 胎压异常判定：<200kPa（无效值 65533 视为无数据不报警） */
    private boolean tireAlert(TireData td) {
        return td != null && td.pressure < 200f && td.pressure < 65533f;
    }

    /** 门窗异常判定：门开或窗未关（窗 1=关，其余有效值都算未关，-1 无数据。对齐 CarLauncher dn.f()） */
    private boolean doorAlert(Object doorEv, int winState) {
        return getInt(doorEv) > 0 || (winState >= 0 && winState != 1);
    }

    /**
     * 一侧 4 个标签垂直等间距：前胎压(顶部锚) → 门窗A → 门窗B → 后胎压(底部锚)
     * 前/后胎压位置固定，两个门窗标签按 1/3、2/3 分点垂直居中分布
     * @param x       门窗标签 x 锚点；atLeft=true 左对齐，false 右对齐
     */
    private void placeBetweenTires(View tireFront, View dwA, View dwB, View tireRear,
                                   float tireTopY, float tireBotY, float x, boolean atLeft) {
        if (tireFront == null || tireRear == null || dwA == null || dwB == null) return;
        float cFront = tireTopY + tireFront.getHeight() / 2f;   // 前胎压中心
        float cRear = tireBotY - tireRear.getHeight() / 2f;     // 后胎压中心
        float step = (cRear - cFront) / 3f;                     // 四标签三段等距
        centerTagAt(dwA, x, cFront + step, atLeft);
        centerTagAt(dwB, x, cFront + step * 2f, atLeft);
    }

    /** 标签垂直居中于 centerY；atLeft=true 水平左对齐 x，false 水平右对齐 x */
    private void centerTagAt(View tag, float x, float centerY, boolean atLeft) {
        if (tag == null) return;
        int w = tag.getWidth(), h = tag.getHeight();
        android.widget.FrameLayout.LayoutParams lp =
                (android.widget.FrameLayout.LayoutParams) tag.getLayoutParams();
        lp.leftMargin = Math.round(atLeft ? x : x - w);
        lp.topMargin = Math.round(centerY - h / 2f);
        tag.setLayoutParams(lp);
    }

    /**
     * 角标签定位（替代旧 placeSideTag）
     * @param x  目标 x（标签锚点的 x）
     * @param y  目标 y（标签锚点的 y）
     * @param atLeft  true=标签在左侧，x 为左边缘距离，水平左对齐到该 x
     *                false=标签在右侧，x 为右边缘距离，水平右对齐到该 x
     * @param anchorBottom true=标签底部对齐 y（用于下方标签），false=标签顶部对齐 y
     */
    private void placeCornerTag(View tag, float x, float y, boolean atLeft, boolean anchorBottom) {
        if (tag == null) return;
        int w = tag.getWidth(), h = tag.getHeight();
        android.widget.FrameLayout.LayoutParams lp =
                (android.widget.FrameLayout.LayoutParams) tag.getLayoutParams();
        if (atLeft) {
            lp.leftMargin = Math.round(x);           // 左对齐：leftMargin = x
        } else {
            lp.leftMargin = Math.round(x - w);       // 右对齐：标签右边贴 x
        }
        if (anchorBottom) {
            lp.topMargin = Math.round(y - h);        // 底部对齐 y
        } else {
            lp.topMargin = Math.round(y);            // 顶部对齐 y
        }
        tag.setLayoutParams(lp);
    }

    private int dip(int dp) {
        float d = getResources().getDisplayMetrics().density;
        return Math.round(dp * d);
    }

    /** 模拟器模式：模拟数据全套（用户定稿 V12 效果图数值）展示界面用 */
    private void loadMockData() {
        hasRealData = false;
        // 传感器类模拟事件
        evGear = mockEvent(SENSOR_GEAR, new int[]{0});                    // P 挡
        evRange = mockEvent(SENSOR_RANGE, new float[]{503f});             // 续航 503km
        evOdometer = mockEvent(SENSOR_ODOMETER, new float[]{74595f});     // 总里程
        evOilLife = mockEvent(SENSOR_OIL_LIFE, new int[]{75});            // 机油寿命 75%
        evFuelPct = mockEvent(SENSOR_FUEL_PCT, new float[]{81f});        // 燃油 81%
        evSpeed = mockEvent(SENSOR_SPEED, new float[]{0f});               // 车速 0
        evDoorLB = mockEvent(SENSOR_DOOR_LB, new int[]{1});               // 左前门开
        evDoorRB = mockEvent(SENSOR_DOOR_RB, new int[]{0});
        evDoorLR = mockEvent(SENSOR_DOOR_LR, new int[]{0});
        evDoorRR = mockEvent(SENSOR_DOOR_RR, new int[]{1});               // 右后门开
        evTrunkIn = mockEvent(SENSOR_TRUNK_IN, new int[]{0});
        evTrunkOut = mockEvent(SENSOR_DOOR_TRUNK_OUT, new int[]{0});
        // 四轮胎压 220/220/220/225 kPa、胎温 19.5℃
        int[] areas = {1, 2, 4, 8};
        float[] pressures = {220f, 220f, 220f, 225f};
        for (int i = 0; i < areas.length; i++) {
            tireData.put(areas[i], new TireData(50, pressures[i], 19.5f));
        }
        tireUnit = "kPa";
        // 车窗：左前开（与门对应），其余关
        winDriver = 0; winPassenger = 1; winLeftRear = 1; winRightRear = 1; // 0=开 1=关
        // HVAC：双区 23.0℃、风量1档、A/C关、内循环开、进气外循环、座椅通风关、出风强度12
        hvacProps.put(16385, "area1=23.0;area4=23.0");
        hvacProps.put(16388, "area0=1");
        hvacProps.put(16393, "area8=0");
        hvacProps.put(16395, "area8=1");
        hvacProps.put(20481, "area1=0;area2=0");
        hvacProps.put(20487, "area8=2");
        hvacProps.put(20513, "area16777216=12");
        aarPmNow = 4;
        refreshDisplay();
        bootLog("[模拟] 模拟数据已填入");
    }

    /** 构造与 CarSensorEvent 同构的模拟对象（floatValues/intValues/sensorType 公有字段） */
    private Object mockEvent(int sensorType, Object values) {
        // 简单匿名类模拟：CarSensorEvent 的字段是公有的，用反射读——
        // 模拟器上无真实类，这里用 HashMap 代替，getFloat/getInt 已兼容 Object：
        // 直接返回一个含公有 floatValues/intValues 字段的匿名子类
        return new CarSensorEventMock(sensorType, values);
    }

    /** 模拟事件：公有字段 floatValues/intValues 与真实 CarSensorEvent 结构一致，
     *  getFloat()/getInt() 的反射 getField 可直接读取 */
    public static class CarSensorEventMock {
        public final int sensorType;
        public final float[] floatValues;
        public final int[] intValues;
        CarSensorEventMock(int sensorType, Object values) {
            this.sensorType = sensorType;
            if (values instanceof float[]) {
                this.floatValues = (float[]) values;
                this.intValues = new int[0];
            } else {
                this.floatValues = new float[0];
                this.intValues = (int[]) values;
            }
        }
    }

    // ========== 生命周期 ==========

    @Override protected void onStart() { super.onStart(); bootLog("[生命周期] onStart"); }
    @Override protected void onResume() { super.onResume(); bootLog("[生命周期] onResume"); }
    @Override protected void onPause() { super.onPause(); bootLog("[生命周期] onPause"); }
    @Override protected void onStop() { super.onStop(); bootLog("[生命周期] onStop"); }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        bootLog("[UI] onConfigurationChanged — 重新布局");
        setContentView(R.layout.activity_main);
        initUi();
        refreshDisplay();
    }

    /** 进入画中画模式 */
    @Override
    @SuppressWarnings("deprecation")
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26 && checkPiPSupported()) {
                enterPictureInPictureMode();
                bootLog("[PiP] 进入画中画模式");
            }
        } catch (Throwable e) {
            bootLog("[PiP] 进入画中画失败: " + rootCause(e));
        }
    }

    /** 画中画模式变化 */
    @Override
    public void onPictureInPictureModeChanged(boolean isInPiP, android.content.res.Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPiP, newConfig);
        bootLog("[PiP] " + (isInPiP ? "已进入" : "已退出") + "画中画");
    }

    /** 检查是否支持画中画 */
    private boolean checkPiPSupported() {
        try {
            if (!getPackageManager().hasSystemFeature("android.software.picture_in_picture"))
                return false;
            // 部分车机 hasSystemFeature 返回 true 但实际不支持，
            // 再检查 ActivityInfo 是否声明了 supportsPictureInPicture
            android.content.pm.ActivityInfo ai = getPackageManager()
                    .getActivityInfo(new android.content.ComponentName(getPackageName(), getClass().getName()), 0);
            // supportsPictureInPicture 属性在 API 24+ 编译，但常量名因 API level 差异用反射取
            try {
                java.lang.reflect.Field f = android.content.pm.ActivityInfo.class.getField("supportsPictureInPicture");
                return f.getBoolean(ai);
            } catch (NoSuchFieldException nsf) {
                // 退而求其次：FLAG_SUPPORTS_PICTURE_IN_PICTURE 不存在时，信任 hasSystemFeature
                return true;
            }
        } catch (Throwable e) {
            return false;
        }
    }

    /** 安全加载车图：用 inSampleSize 降采样，防 density 缩放后 bitmap 过大崩溃。
     *  车图按日夜模式自动切换：夜间 car_top.webp / 白天 car_top_day.webp（资源别名 car_top_sel） */
    private void safeLoadCarImage() {
        if (ivCar == null) return;
        try {
            int carRes = R.drawable.car_top_sel;   // 日夜资源别名，values/values-notnight 各自指向
            // 先量原图边界
            android.graphics.BitmapFactory.Options opt = new android.graphics.BitmapFactory.Options();
            opt.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeResource(getResources(), carRes, opt);
            int bw = opt.outWidth, bh = opt.outHeight;
            bootLog("[UI] 车图原始: " + bw + "x" + bh);

            // Canvas 最大 bitmap 约 100MB（保守取 80MB → 2000万像素）
            // 算需要降采样到多少
            DisplayMetrics dm = getResources().getDisplayMetrics();
            float density = dm.density;
            int scaledW = Math.round(bw * density);
            int scaledH = Math.round(bh * density);
            long bitmapBytes = (long) scaledW * scaledH * 4;
            bootLog("[UI] 车图 density=" + density + " 缩放后=" + scaledW + "x" + scaledH + "=" + (bitmapBytes / 1048576) + "MB");

            int sampleSize = 1;
            while ((long)(bw / sampleSize) * (bh / sampleSize) * 4 * (long)(density) * (long)(density) > 80L * 1024 * 1024) {
                sampleSize *= 2;
            }
            if (sampleSize > 1) {
                bootLog("[UI] 车图降采样 sampleSize=" + sampleSize);
            }

            // 加载降采样后的 bitmap
            opt = new android.graphics.BitmapFactory.Options();
            opt.inSampleSize = sampleSize;
            opt.inScaled = false;  // 不按 density 缩放
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeResource(
                    getResources(), carRes, opt);
            if (bmp != null) {
                ivCar.setImageBitmap(bmp);
                bootLog("[UI] 车图加载完成: " + bmp.getWidth() + "x" + bmp.getHeight());
            }
        } catch (Throwable e) {
            bootLog("[UI] 车图安全加载失败: " + rootCause(e));
        }
    }

    /** UI 初始化：findViewById + 字号缩放 + 标签定位（onCreate 和 onConfigurationChanged 共用） */
    private void initUi() {
        tvGear = findViewById(R.id.tvGear);
        tvRange = findViewById(R.id.tvRange);
        tvOdo = findViewById(R.id.tvOdo);
        tvOil = findViewById(R.id.tvOil);
        tvFuel = findViewById(R.id.tvFuel);
        tvSpeed = findViewById(R.id.tvSpeed);
        tvRangeUnit = findViewById(R.id.tvRangeUnit);
        tvOdoUnit = findViewById(R.id.tvOdoUnit);
        tagLFDw = findViewById(R.id.tagLFDw); tagRFDw = findViewById(R.id.tagRFDw);
        tagLRDw = findViewById(R.id.tagLRDw); tagRRDw = findViewById(R.id.tagRRDw);
        tvTempDriver = findViewById(R.id.tvTempDriver);
        tvPm = findViewById(R.id.tvPm);
        tvTempPassenger = findViewById(R.id.tvTempPassenger);
        tagLFDw = findViewById(R.id.tagLFDw); tagRFDw = findViewById(R.id.tagRFDw);
        tagLRDw = findViewById(R.id.tagLRDw); tagRRDw = findViewById(R.id.tagRRDw);
        tagLFTire = findViewById(R.id.tagLFTire); tagRFTire = findViewById(R.id.tagRFTire);
        tagLRTire = findViewById(R.id.tagLRTire); tagRRTire = findViewById(R.id.tagRRTire);
        tagTrunk = findViewById(R.id.tagTrunk);
        ivCar = findViewById(R.id.ivCar);
        carZone = findViewById(R.id.carZone);
        tagLines = findViewById(R.id.tagLines);
        // 页面单击切换日夜模式（高德导航广播也可自动切换）
        View root = findViewById(R.id.root);
        if (root != null) root.setOnClickListener(v -> toggleDayNight());
        // 注册高德导航日夜模式广播
        registerAmapThemeReceiver();
        safeLoadCarImage();
        applyFontScale();
        // 清除 XML 布局中写死的文字光晕（日夜均关闭 shadowColor/shadowRadius）
        clearShadow(findViewById(R.id.root));
        scaleBannerByHeight();
        scaleAcCardByHeight();
        layoutCarTags();
    }

    /** 递归清除所有 TextView 的 shadowLayer（白天模式去光晕） */
    private void clearShadow(View v) {
        if (v instanceof TextView) {
            ((TextView) v).setShadowLayer(0, 0, 0, 0);
        } else if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) clearShadow(g.getChildAt(i));
        }
    }

    @Override
    protected void onDestroy() {
        bootLog("[生命周期] onDestroy — 写最终快照");
        try { logToFile(buildDataSnapshot(), true); } catch (Throwable ignored) {}
        if (carHandler != null) carHandler.removeCallbacksAndMessages(null);
        if (sensorManager != null && sensorListener != null) {
            try {
                Class<?> listenerClass = Class.forName(
                        "android.car.hardware.CarSensorManager$OnSensorChangedListener");
                Method unregister = sensorManager.getClass()
                        .getMethod("unregisterListener", listenerClass);
                unregister.invoke(sensorManager, sensorListener);
                bootLog("[onDestroy] sensorListener 已注销");
            } catch (Throwable e) {
                bootLog("[onDestroy] 注销 listener 失败: " + e);
            }
        }
        if (yfManager != null && yfCallback != null) {
            try {
                yfManager.getClass().getMethod("unregisterCallback",
                        Class.forName("android.car.hardware.CarYFSettingManager$CarYFSettingCallback"))
                        .invoke(yfManager, yfCallback);
                bootLog("[onDestroy] yfCallback 已注销");
            } catch (Throwable e) {
                bootLog("[onDestroy] 注销 yfCallback 失败: " + e);
            }
        }
        if (hvacManager != null && hvacCallback != null) {
            try {
                hvacManager.getClass().getMethod("unregisterCallback",
                        Class.forName("android.car.hardware.hvac.CarHvacManager$CarHvacEventCallback"))
                        .invoke(hvacManager, hvacCallback);
                bootLog("[onDestroy] hvacCallback 已注销");
            } catch (Throwable e) {
                bootLog("[onDestroy] 注销 hvacCallback 失败: " + e);
            }
        }
        if (car != null) {
            try { car.getClass().getMethod("disconnect").invoke(car); bootLog("[onDestroy] Car 已断开"); } catch (Throwable ignored) {}
        }
        unregisterAmapThemeReceiver();
        super.onDestroy();
    }
    // ========== 设备信息 & API 探测 ==========

    private void logDeviceInfo() {
        try {
            bootLog("[设备] PID=" + android.os.Process.myPid() + " UID=" + android.os.Process.myUid()
                    + " (UID=1000 表示系统签名生效)");
            bootLog("[设备] " + Build.MANUFACTURER + " " + Build.MODEL
                    + " (" + Build.DEVICE + ") Android " + Build.VERSION.RELEASE
                    + " API " + Build.VERSION.SDK_INT);
            bootLog("[设备] FINGERPRINT=" + Build.FINGERPRINT);
            DisplayMetrics dm = new DisplayMetrics();
            getWindowManager().getDefaultDisplay().getRealMetrics(dm);
            bootLog("[设备] 屏幕 " + dm.widthPixels + "x" + dm.heightPixels + " density=" + dm.densityDpi);
        } catch (Throwable e) {
            bootLog("[设备] 信息读取失败: " + e);
        }
    }

    /** 探测车机 android.car 各关键类是否存在，一次看清车机支持情况 */
    private void probeCarApiClasses() {
        bootLog("[探测] ==== Car API 类探测 ====");
        String[] classes = {
                "android.car.Car",
                "android.car.Car$CarServiceLifecycleListener",   // 新版 createCar (Android 10+)
                "android.car.Car$ServiceConnectionListener",    // 老版 createCar (Android 8/9)
                "android.car.hardware.CarSensorManager",
                "android.car.hardware.CarSensorManager$OnSensorChangedListener",
                "android.car.hardware.CarSensorEvent",
                "android.car.hardware.CarVendorExtensionManager",
                "android.car.CarInfoManager",
                "android.car.hardware.property.CarPropertyManager",
        };
        for (String name : classes) {
            boolean exists;
            try {
                Class.forName(name);
                exists = true;
            } catch (Throwable e) {
                exists = false;
            }
            bootLog("[探测] " + (exists ? "有" : "无") + " " + name);
        }
    }

    private boolean isCarApiAvailable() {
        try {
            Class.forName("android.car.Car");
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    // ========== Car 连接（照抄 CarLauncher dw.java：3参数 createCar + connect()）==========

    /**
     * 车机是 Android 8.1 定制 Car API：createCar(Context, ServiceConnection, Handler) + 手动 connect()。
     * ServiceConnection 是 Android 标准接口，直接实现，不用反射。
     */
    private void connectCarService() {
        setStatus("正在连接 Car Service...");
        bootLog("[连接] connectCarService 开始（3参数版 + connect()）");

        HandlerThread thread = new HandlerThread("CarConnection");
        thread.start();
        carHandler = new Handler(thread.getLooper()) {
            @Override
            public void handleMessage(android.os.Message msg) {
                switch (msg.what) {
                    case MSG_TRY_CONNECT:
                        if (!serviceConnected) tryCreateCar();
                        break;
                    case MSG_CHECK_CONNECTED:
                        checkCarConnected();
                        break;
                    case MSG_DISCONNECTED:
                        tryConnectCount = 0;
                        bootLog("[连接] Car Service 断开");
                        ui("Car Service 断开");
                        break;
                }
            }
        };

        ServiceConnection conn = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                serviceConnected = true;
                long delay = isConnected() ? 500 : 1000;
                bootLog("[回调] onServiceConnected, " + delay + "ms 后检查连接");
                carHandler.sendEmptyMessageDelayed(MSG_CHECK_CONNECTED, delay);
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                serviceConnected = false;
                carHandler.sendEmptyMessage(MSG_DISCONNECTED);
            }

            @Override
            public void onBindingDied(ComponentName name) {
                serviceConnected = false;
                carHandler.sendEmptyMessage(MSG_DISCONNECTED);
            }
        };

        try {
            Class<?> carClass = Class.forName("android.car.Car");
            Method createCar = carClass.getMethod("createCar",
                    android.content.Context.class, ServiceConnection.class, Handler.class);
            car = createCar.invoke(null, this, conn, carHandler);
            bootLog("[连接] createCar 返回: " + (car != null ? "非null" : "null"));
            if (car == null) {
                ui("createCar 返回 null");
                setStatus("连接失败");
                return;
            }
            // 手动 connect（照抄 CarLauncher）
            car.getClass().getMethod("connect").invoke(car);
            bootLog("[连接] connect() 已调用，等待 onServiceConnected...");
        } catch (Throwable e) {
            bootLog("[连接] createCar/connect 异常:\n" + stackTrace(e));
            ui("连接异常: " + rootCause(e));
            setStatus("连接失败");
        }
    }

    /** 检查 isConnected，最多重试 3 次（照抄 CarLauncher dz.java） */
    private void checkCarConnected() {
        try {
            boolean connected = isConnected();
            bootLog("[连接] 检查 isConnected=" + connected + ", 重试次数=" + tryConnectCount);
            if (connected) {
                onCarServiceReady();
            } else if (tryConnectCount <= 3) {
                tryConnectCount++;
                carHandler.sendEmptyMessageDelayed(MSG_CHECK_CONNECTED, 500);
            } else {
                ui("连接超时（isConnected 一直 false）");
                setStatus("连接失败");
            }
        } catch (Throwable e) {
            bootLog("[连接] checkCarConnected 异常: " + e);
        }
    }

    private boolean isConnected() {
        if (car == null) return false;
        try {
            Object r = car.getClass().getMethod("isConnected").invoke(car);
            return r instanceof Boolean && (Boolean) r;
        } catch (Throwable e) {
            return false;
        }
    }

    private void tryCreateCar() {
        bootLog("[连接] tryCreateCar（备用重连路径）");
        try {
            Class<?> carClass = Class.forName("android.car.Car");
            Method createCar = carClass.getMethod("createCar",
                    android.content.Context.class, ServiceConnection.class, Handler.class);
            car = createCar.invoke(null, this, (ServiceConnection) null, carHandler);
            if (car != null) car.getClass().getMethod("connect").invoke(car);
        } catch (Throwable e) {
            bootLog("[连接] tryCreateCar 异常: " + rootCause(e));
        }
    }

    // ========== Car Service 就绪 ==========

    private void onCarServiceReady() {
        bootLog("[就绪] onCarServiceReady 开始");
        Class<?> carClass;
        Method getCarManager;
        try {
            carClass = Class.forName("android.car.Car");
            getCarManager = carClass.getMethod("getCarManager", String.class);
        } catch (Throwable e) {
            bootLog("[就绪] 反射 getCarManager 失败:\n" + stackTrace(e));
            return;
        }

        // 照抄 CarLauncher dw.java g()：字符串服务名
        // sensor / vendor_extension / info / hvac / vendor_yfsetting / phev_extension
        sensorManager = getManagerQuietly(getCarManager, "sensor", "CarSensorManager");
        vendorManager = getManagerQuietly(getCarManager, "vendor_extension", "VendorExtension");
        infoManager = getManagerQuietly(getCarManager, "info", "CarInfoManager");
        hvacManager = getManagerQuietly(getCarManager, "hvac", "CarHvacManager");
        yfManager = getManagerQuietly(getCarManager, "vendor_yfsetting", "CarYFSettingManager");

        setStatus("Car Service 就绪");

        if (sensorManager != null) registerSensors();
        else ui("CarSensorManager 不可用");

        if (vendorManager != null) readTireData();
        else ui("VendorExtension 不可用（胎压需此 Manager）");

        if (infoManager != null) readCarInfo();
        else ui("CarInfoManager 不可用");

        if (hvacManager != null) readHvacCcm();
        else ui("CarHvacManager 不可用（滤芯状态入参）");

        if (yfManager != null) readAarData();
        else ui("CarYFSettingManager 不可用（智能新风/滤芯/车窗）");

        refreshDisplay();
        bootLog("[就绪] onCarServiceReady 完成");

        // 连接成功 30 秒后无条件写一次快照：没推送也能从 carinfo.log 判断出注册/数据状态
        final String snapshotText = buildDataSnapshot();
        final String snapshotHeader = "[连接30秒快照] 已注册=" + registeredSensors.size()
                + " 收到过数据的传感器=" + firstDataLogged.size() + "个"
                + (firstDataLogged.isEmpty() ? "（无任何推送→注册或权限问题）" : "")
                + (writeFailReason != null ? " 日志写入失败=" + writeFailReason : "");
        handler.postDelayed(() -> {
            try { logToFile(snapshotHeader + "\n" + snapshotText, true); } catch (Throwable ignored) {}
        }, 30000);
    }

    private Object getManagerQuietly(Method getCarManager, String serviceName, String label) {
        try {
            Object mgr = getCarManager.invoke(car, serviceName);
            bootLog("[就绪] " + label + " 获取" + (mgr != null ? "成功 (" + mgr.getClass().getName() + ")" : "返回null"));
            return mgr;
        } catch (Throwable e) {
            bootLog("[就绪] " + label + " 获取失败:\n" + stackTrace(e));
            return null;
        }
    }

    // ========== 传感器 ==========

    private void registerSensors() {
        bootLog("[传感器] 开始注册");
        try {
            Class<?> listenerClass = Class.forName(
                    "android.car.hardware.CarSensorManager$OnSensorChangedListener");
            sensorListener = Proxy.newProxyInstance(
                    listenerClass.getClassLoader(),
                    new Class<?>[]{listenerClass},
                    (proxy, method, args) -> {
                        // registerListener 内部会调 hashCode()/equals() 存集合，
                        // 基本类型返回 null 会拆箱炸 NPE（18:56 车机 43 个全失败的根因）
                        String m = method.getName();
                        if ("hashCode".equals(m)) return System.identityHashCode(proxy);
                        if ("equals".equals(m)) return proxy == args[0];
                        if ("toString".equals(m)) return "SensorListenerProxy@" + Integer.toHexString(System.identityHashCode(proxy));
                        if ("onSensorChanged".equals(m)) {
                            cacheSensorEvent(args[0]);
                            refreshDisplay();
                        }
                        Class<?> rt = method.getReturnType();
                        if (rt == boolean.class) return Boolean.FALSE;
                        if (rt == int.class) return 0;
                        if (rt == long.class) return 0L;
                        if (rt == float.class) return 0f;
                        if (rt == double.class) return 0d;
                        return null;
                    });

            // 1) 动态枚举车机支持的传感器（getSupportedSensors）——车机只认厂商小数字 ID，
            //    19:58 实测标准 PROPERTY_ID 大数字全部 invalid sensor type，不再合并硬编码大数字
            int[] supported = null;
            try {
                Method getSupported = sensorManager.getClass().getMethod("getSupportedSensors");
                supported = (int[]) getSupported.invoke(sensorManager);
                if (supported != null) {
                    bootLog("[传感器] 车机报告支持 " + supported.length + " 个传感器:");
                    for (int t : supported) {
                        bootLog("[传感器]   支持 type=" + t + " (" + sensorName(t) + ")");
                        registeredSensors.add(t);
                    }
                }
            } catch (Throwable e) {
                bootLog("[传感器] getSupportedSensors 失败: " + rootCause(e));
            }

            // 2) 硬编码补充：旧版车机可能枚举不到但实际可注册的（19:58 实测 31 个全部枚举到，仅作兆底）
            int[] hardcoded = {
                    SENSOR_ODOMETER, SENSOR_SPEED, SENSOR_GEAR, SENSOR_IGNITION,
                    SENSOR_FUEL_WARNING, SENSOR_RANGE, SENSOR_FUEL_PCT,
                    SENSOR_DOOR_LB, SENSOR_DOOR_RB, SENSOR_DOOR_LR, SENSOR_DOOR_RR,
                    SENSOR_TRUNK_IN, SENSOR_DOOR_TRUNK_OUT
            };
            for (int t : hardcoded) registeredSensors.add(t);
            bootLog("[传感器] 合并硬编码清单后共 " + registeredSensors.size() + " 个待注册");

            // 3) 逐个注册并读当前值
            // 反射参数必须显式装箱 Integer：车机老版方法签名收 Integer，
            // invoke 传 int 字面量会因拆箱 null 拋 NPE（18:56 车机实测 43 个全失败的原因）
            Method register = sensorManager.getClass()
                    .getMethod("registerListener", listenerClass, int.class, int.class);
            Method getLatest = sensorManager.getClass()
                    .getMethod("getLatestSensorEvent", int.class);

            int ok = 0, latestOk = 0;
            String firstFailReason = null;
            for (int type : registeredSensors) {
                String name = sensorName(type);
                try {
                    Object regResult = register.invoke(sensorManager, sensorListener,
                            type, Integer.valueOf(1)); // RATE_NORMAL，显式装箱
                    ok++;
                    Object ev = getLatest.invoke(sensorManager, type);
                    if (ev != null) {
                        cacheSensorEvent(ev);
                        latestOk++;
                        bootLog("[传感器] " + name + "(" + type + ") 注册OK 返回=" + regResult
                                + ", 当前值=" + eventSummary(ev));
                    } else {
                        bootLog("[传感器] " + name + "(" + type + ") 注册OK 返回=" + regResult + ", 当前无数据(等推送)");
                    }
                } catch (Throwable e) {
                    String reason = rootCause(e);
                    if (firstFailReason == null) firstFailReason = name + "(" + type + "): " + reason;
                    bootLog("[传感器] " + name + "(" + type + ") 注册失败: " + reason);
                }
            }
            bootLog("[传感器] 注册完成: 成功" + ok + "/" + registeredSensors.size()
                    + ", 有当前值" + latestOk + "/" + registeredSensors.size());
            ui("已注册 " + ok + "/" + registeredSensors.size() + " 个传感器");
            if (ok == 0 && firstFailReason != null) {
                ui("首个失败原因: " + firstFailReason);
            }
        } catch (Throwable e) {
            bootLog("[传感器] 注册流程异常:\n" + stackTrace(e));
            ui("传感器注册失败: " + e.getMessage());
        }
    }

    // 未知传感器编号 → 日志里给出推测标签（CarLauncher 未提及，待车机数据验证）
    private static final HashMap<Integer, String> SENSOR_GUESS = new HashMap<>();
    static {
        SENSOR_GUESS.put(5,  "可能:行驶方向?");
        SENSOR_GUESS.put(9,  "可能:安全带?");
        SENSOR_GUESS.put(11, "挡位结合(0=P/31=行驶)");
        SENSOR_GUESS.put(52, "驻车驱动(待重验:拉手刹无变化)");
        SENSOR_GUESS.put(63, "非大灯(开关无反应)");
        SENSOR_GUESS.put(67, "电源档位(12=ON/0=OFF)");
        SENSOR_GUESS.put(68, "左转向灯(3=亮/1=灭/0=关)");
        SENSOR_GUESS.put(69, "右转向灯(3=亮/1=灭/0=关)");
        SENSOR_GUESS.put(72, "点火电源位(1=ON/0=LOCK)");
    }

    private static String sensorName(int type) {
        String n = SENSOR_NAMES.get(type);
        if (n != null) return n;
        String g = SENSOR_GUESS.get(type);
        return g != null ? "未知" + type + "(" + g + ")" : "未知" + type;
    }

    /** 首次收到某类型数据时，把完整内容记录到日志；监控参数值变化时也记录 */
    private void cacheSensorEvent(Object ev) {
        if (ev == null) return;
        try {
            java.lang.reflect.Field typeField = ev.getClass().getField("sensorType");
            int type = typeField.getInt(ev);
            String summary = eventSummary(ev);
            if (firstDataLogged.add(type)) {
                bootLog("[数据] 首次收到 sensor type=" + type + ": " + summary);
            }
            // 监控参数值变化检测（排除车速type=2/总里程type=4等高频或极慢变化）
            if (MONITORED_TYPES.contains(type)) {
                String old = lastSensorValue.get(type);
                if (old == null || !old.equals(summary)) {
                    if (old != null) {  // 首次已有"首次收到"日志，跳过；仅记录后续变化
                        bootLog("[传感器] " + sensorName(type) + "(" + type + ") 变化: " + old + " → " + summary);
                    }
                    lastSensorValue.put(type, summary);
                }
            }
            switch (type) {
                case SENSOR_ODOMETER: evOdometer = ev; break;
                case SENSOR_SPEED: evSpeed = ev; break;
                case SENSOR_GEAR: evGear = ev; break;              // 挡位！（er.java: getGearData）
                case SENSOR_RANGE: evRange = ev; break;             // 续航 km（eo.java: leftMile）
                case SENSOR_FUEL_PCT: evFuelPct = ev; break;       // 油量百分比（ep.java）
                case SENSOR_FUEL_WARNING: evFuelWarn = ev; break;   // 油量预警开关（eq.java）
                case SENSOR_IGNITION: evIgnition = ev; break;
                case SENSOR_OIL_LIFE: evOilLife = ev; break;        // 机油寿命%（实锤）
                case SENSOR_DOOR_LB: evDoorLB = ev; break;
                case SENSOR_DOOR_RB: evDoorRB = ev; break;
                case SENSOR_DOOR_LR: evDoorLR = ev; break;
                case SENSOR_DOOR_RR: evDoorRR = ev; break;
                case SENSOR_TRUNK_IN: evTrunkIn = ev; break;
                case SENSOR_DOOR_TRUNK_OUT: evTrunkOut = ev; break;
                default: evOthers.put(type, ev); break;  // 未知/其余传感器
            }
        } catch (Throwable ignored) {}
    }

    /** 把 CarSensorEvent 的 floatValues/intValues 拼成可读文本 */
    private String eventSummary(Object ev) {
        try {
            StringBuilder sb = new StringBuilder();
            java.lang.reflect.Field ff = ev.getClass().getField("floatValues");
            float[] farr = (float[]) ff.get(ev);
            if (farr != null && farr.length > 0) {
                sb.append("float=[");
                for (float v : farr) sb.append(v).append(",");
                sb.append("] ");
            }
            java.lang.reflect.Field fi = ev.getClass().getField("intValues");
            int[] iarr = (int[]) fi.get(ev);
            if (iarr != null && iarr.length > 0) {
                sb.append("int=[");
                for (int v : iarr) sb.append(v).append(",");
                sb.append("]");
            }
            return sb.toString().trim();
        } catch (Throwable e) {
            return "(无法解析: " + e.getClass().getSimpleName() + ")";
        }
    }

    private float getFloat(Object ev) {
        if (ev == null) return -1f;
        try {
            java.lang.reflect.Field f = ev.getClass().getField("floatValues");
            float[] arr = (float[]) f.get(ev);
            if (arr != null && arr.length > 0) return arr[0];
        } catch (Throwable ignored) {}
        return -1f;
    }

    private int getInt(Object ev) {
        if (ev == null) return -1;
        try {
            java.lang.reflect.Field f = ev.getClass().getField("intValues");
            int[] arr = (int[]) f.get(ev);
            if (arr != null && arr.length > 0) return arr[0];
        } catch (Throwable ignored) {}
        return -1;
    }

    // ========== 胎压 + 里程单位（百度定制 VendorExtension）==========

    private void readTireData() {
        bootLog("[胎压] ==== 读取胎压（VendorExtension）====");
        ui("读取胎压中...");
        Method getProperty;
        try {
            getProperty = vendorManager.getClass()
                    .getMethod("getProperty", Class.class, int.class, int.class);
            bootLog("[胎压] getProperty 方法获取OK");
        } catch (Throwable e) {
            bootLog("[胎压] getProperty 方法不存在:\n" + stackTrace(e));
            ui("胎压 API 不可用");
            return;
        }
        String[] labels = {"左前", "右前", "左后", "右后"};
        for (int i = 0; i < TIRE_AREAS.length; i++) {
            int area = TIRE_AREAS[i];
            try {
                int state = (Integer) getProperty.invoke(vendorManager, Integer.class, PROP_TIRE_STATE, area);
                float pressure = (Float) getProperty.invoke(vendorManager, Float.class, PROP_TIRE_PRESSURE, area);
                float temp = (Float) getProperty.invoke(vendorManager, Float.class, PROP_TIRE_TEMP, area);
                tireData.put(area, new TireData(state, pressure, temp));
                bootLog("[胎压] " + labels[i] + " state=" + state + " pressure=" + pressure + " temp=" + temp);
                ui("胎压 " + labels[i] + " " + pressure + "kPa");
            } catch (Throwable e) {
                bootLog("[胎压] " + labels[i] + " 读取失败: " + rootCause(e));
                ui("胎压 " + labels[i] + " 失败");
            }
        }
        // 里程单位（照抄 CarLauncher ej.java）：0=km 1=mi，用于总里程/续航显示
        try {
            Object unit = getProperty.invoke(vendorManager, Integer.class, PROP_MILEAGE_UNIT, 0);
            bootLog("[车况] 里程单位(557843475)=" + unit);
            mileageUnit = Integer.valueOf(1).equals(unit) ? "mi" : "km";
            ui("里程单位: " + mileageUnit);
        } catch (Throwable e) {
            bootLog("[车况] 里程单位读取失败: " + rootCause(e));
        }
        // 胎压单位请求改在 readAarData() 中完成（服务名 vendor_yfsetting，dw.java:104）
    }

    // ========== 智能新风 / AAR（照抄 CarLauncher dn.java 状态机） ==========

    /** HVAC 全属性枚举直读：
     *  1) getPropertyList() 枚举车机支持的全部 HVAC 属性（含温度/风量，8.1 API）
     *  2) 每个 propId 的每个 areaId 各读一次 getIntProperty（读不出的记失败原因）
     *  3) 保留 16387 区 8 = CCM 功率读取（dn.java: this.r = eb.a().c()，滤芯状态机入参 r） */
    private void readHvacCcm() {
        bootLog("[HVAC] ==== 枚举 HVAC 属性（getPropertyList）====");

        // 1) 枚举属性清单：先试 getPropertyList，同时兼容老版可能的方法名（ getPropertyConfigs/ getHvacPropertiesList）
        java.util.List<?> cfgList = null;
        String[] listMethodNames = {"getPropertyList", "getPropertyConfigs", "getHvacPropertiesList", "getProperties"};
        for (String mn : listMethodNames) {
            try {
                Method getList = hvacManager.getClass().getMethod(mn);
                Object list = getList.invoke(hvacManager);
                bootLog("[HVAC] " + mn + "() 返回 " + (list == null ? "null" : list.getClass().getName()));
                if (list instanceof java.util.List && !((java.util.List<?>) list).isEmpty()) {
                    cfgList = (java.util.List<?>) list;
                    break;
                }
            } catch (Throwable e) {
                bootLog("[HVAC] " + mn + " 不存在或失败: " + rootCause(e));
            }
        }

        // 2) 逐个 config 解析：方法名/字段名多重兼容 + 首个失败时 dump 类结构
        if (cfgList != null) {
            int idx = 0, parsed = 0;
            for (Object cfg : cfgList) {
                idx++;
                Integer propId = extractHvacPropId(cfg, idx == 1);
                if (propId == null) continue;
                parsed++;
                String areaStr = extractHvacAreas(cfg);
                hvacProps.put(propId, areaStr);
                bootLog("[HVAC] 属性 propId=" + propId + " (" + hvacPropName(propId) + ") areas=[" + areaStr + "]");
            }
            ui("HVAC 枚举到 " + parsed + "/" + idx + " 个属性");

            // 3) 逐属性逐区域读当前值（读到 0 续试 Float；读到的写缓存，与推送路径格式统一）
            for (HashMap.Entry<Integer, String> entry : hvacProps.entrySet()) {
                int propId = entry.getKey();
                String[] areaStrs = entry.getValue().split(" ");
                for (String as : areaStrs) {
                    int area;
                    try { area = Integer.parseInt(as.trim()); } catch (NumberFormatException e) { continue; }
                    Object v = readHvacValue(propId, area);
                    if (v == null) continue;
                    String vs = v instanceof Float ? ((Float) v) + "f" : String.valueOf(v);
                    if (!entry.getValue().contains("=")) {
                        // config 提取的是纯区域串（无"="），读到首个值后整体替换为"areaN=值"；
                        // 其余区域值靠推送补齐（车机推送很快），避免多区域拼接出错
                        hvacProps.put(propId, "area" + area + "=" + vs);
                    }
                    bootLog("[HVAC] " + hvacPropName(propId) + " area=" + area + " → " + vs);
                }
            }
        }

        // 4) CCM 功率保底读取（滤芯状态机入参 r，独立于枚举——枚举失败也必须读）
        //    16387 区 8，CarLauncher eb.c() 实锚；22:37 实测曾有版本读到的就是它
        try {
            Method getInt = hvacManager.getClass().getMethod("getIntProperty", int.class, int.class);
            Object v = getInt.invoke(hvacManager, 16387, 8);
            aarHvacCcm = v instanceof Integer ? (Integer) v : -1;
            bootLog("[HVAC] CCM(16387,8)=" + aarHvacCcm + "（保底读取）");
            if (!hvacProps.containsKey(16387)) hvacProps.put(16387, "area8=" + aarHvacCcm);
        } catch (Throwable e) {
            bootLog("[HVAC] CCM 保底读取失败: " + rootCause(e));
        }

        // 5) 盲扫探测（枚举失败时的温度保险）：已知 ID 表全试一遍 + CarLauncher 实锚小数字 ID 扫段
        //    读值成功的写进 hvacProps 并记日志——温度/风量 ID 大概率在这里面被扫出来
        if (hvacProps.isEmpty() || hvacProps.size() < 5) {
            blindScanHvac();
        }

        // 6) HVAC 回调注册（推送驱动 UI 刷新，16387 区 8 更新滤芯入参 r）
        try {
            Class<?> cbClass = Class.forName("android.car.hardware.hvac.CarHvacManager$CarHvacEventCallback");
            hvacCallback = Proxy.newProxyInstance(cbClass.getClassLoader(),
                    new Class<?>[]{cbClass},
                    (proxy, method, args) -> {
                        String m = method.getName();
                        if ("hashCode".equals(m)) return System.identityHashCode(proxy);
                        if ("equals".equals(m)) return proxy == args[0];
                        if ("toString".equals(m)) return "HvacCallbackProxy";
                        if ("onChangeEvent".equals(m) && args != null && args.length > 0) {
                            handleHvacEvent(args[0]);
                        }
                        Class<?> rt = method.getReturnType();
                        if (rt == boolean.class) return Boolean.FALSE;
                        if (rt == int.class) return 0;
                        if (rt == long.class) return 0L;
                        if (rt == float.class) return 0f;
                        if (rt == double.class) return 0d;
                        return null;
                    });
            Method register = hvacManager.getClass().getMethod("registerCallback", cbClass);
            register.invoke(hvacManager, hvacCallback);
            bootLog("[HVAC] 回调注册OK");
        } catch (Throwable e) {
            bootLog("[HVAC] 回调注册失败: " + rootCause(e));
        }
    }

    /** 从 config 对象提取 propId：多方法名兼容（getPropId/getId/getPropertyId…）+ 字段反射兼底 + 首次 dump */
    private Integer extractHvacPropId(Object cfg, boolean dumpIfFail) {
        // 多个常见方法名依次试（新版 AOSP 与老版命名都在内）
        String[] getters = {"getPropId", "getId", "getPropertyId", "getHvacPropId", "getType"};
        for (String g : getters) {
            try {
                Method m = cfg.getClass().getMethod(g);
                Object v = m.invoke(cfg);
                if (v instanceof Integer) return (Integer) v;
            } catch (Throwable ignored) {}
        }
        // 字段兼底：propId / mPropId / mId / id 等
        String[] fields = {"propId", "mPropId", "mId", "id", "mPropertyId", "propertyId"};
        for (java.lang.reflect.Field f : cfg.getClass().getDeclaredFields()) {
            for (String fn : fields) {
                if (f.getName().equals(fn)) {
                    try {
                        f.setAccessible(true);
                        Object v = f.get(cfg);
                        if (v instanceof Integer) return (Integer) v;
                    } catch (Throwable ignored) {}
                }
            }
        }
        if (dumpIfFail) {
            bootLog("[HVAC] config 类结构 dump:\n" + dumpObjectStructure(cfg));
        }
        return null;
    }

    /** 从 config 提取区域列表（getAreaIdList 多命名兼容 + int[] 字段兼底），失败返回 "0 8" 兜底 */
    private String extractHvacAreas(Object cfg) {
        String[] getters = {"getAreaIdList", "getAreaIds", "getAreaList"};
        for (String g : getters) {
            try {
                Method m = cfg.getClass().getMethod(g);
                Object v = m.invoke(cfg);
                if (v instanceof int[]) {
                    StringBuilder as = new StringBuilder();
                    for (int a : (int[]) v) as.append(a).append(' ');
                    return as.toString().trim();
                }
                if (v instanceof java.util.List) {
                    StringBuilder as = new StringBuilder();
                    for (Object a : (java.util.List<?>) v) as.append(a).append(' ');
                    return as.toString().trim();
                }
            } catch (Throwable ignored) {}
        }
        // 字段兼底
        String[] fields = {"mAreaIdList", "areaIdList", "mAreaIds", "areaIds", "mAreaIdsArray"};
        for (java.lang.reflect.Field f : cfg.getClass().getDeclaredFields()) {
            for (String fn : fields) {
                if (f.getName().equals(fn)) {
                    try {
                        f.setAccessible(true);
                        Object v = f.get(cfg);
                        if (v instanceof int[]) {
                            StringBuilder as = new StringBuilder();
                            for (int a : (int[]) v) as.append(a).append(' ');
                            return as.toString().trim();
                        }
                    } catch (Throwable ignored) {}
                }
            }
        }
        return "0 8"; // 兼底：区 0（全局）和区 8（前排，CarLauncher 常用）各试一次
    }

    /** 盲扫：枚举彻底失败时，把已知候选 ID 全试一遍（读到的进 hvacProps）
     *  A. AOSP 标准大数字 ID：HVAC_TEMPERATURE_CURRENT/SET、FAN_SPEED、AC_ON…（javap 实锚）
     *  B. CarLauncher 实锚小数字：16386~16399 段 + 0~15 段
     *  C. 每个候选试多个 area（0 全局/8 前排/1 左/4 右——CarLauncher 用 8，胎压用 1/2/4/8） */
    private void blindScanHvac() {
        bootLog("[HVAC] ==== 盲扫候选 ID（枚举不足时的温度保险）====");
        int hit = 0;
        // A. AOSP 标准大数字（子区域类型的 HVAC 温度/风量都是这个系列）
        int[] stdIds = {
                358614274, 358614275,  // HVAC_TEMPERATURE_CURRENT / SET（摄氏）
                356517120, 356517121,  // HVAC_FAN_SPEED / FAN_DIRECTION
                354419973, 354419984,  // HVAC_AC_ON / HVAC_POWER_ON
                356517135,              // HVAC_ACTUAL_FAN_SPEED_RPM
                291505923,              // ENV_OUTSIDE_TEMPERATURE
        };
        // B. 厂商小数字段（CarLauncher 实锚 16387/16395，邻位大概率是温度/风量）
        int[] vendorIds = new int[32];
        for (int i = 0; i < 32; i++) vendorIds[i] = 16384 + i; // 16384~16415
        // C. 小数字段 0~15（温度单位 propId=4 就在这个段，附近可能有温度设置值）
        int[] tinyIds = new int[16];
        for (int i = 0; i < 16; i++) tinyIds[i] = i;

        int[][] areaSets = {{0}, {8}, {1, 4}, {0, 8}};
        for (int[] ids : new int[][]{stdIds, vendorIds, tinyIds}) {
            for (int propId : ids) {
                for (int[] areas : areaSets) {
                    for (int area : areas) {
                        String v = readHvacPropQuiet(propId, area);
                        if (v != null) {
                            hit++;
                            String tag = "area" + area + "=" + v;
                            hvacProps.merge(propId, tag, (o, n) -> o.contains(n) ? o : o + ";" + n);
                            bootLog("[HVAC] 盲扫命中 propId=" + propId + " area=" + area + " → " + v);
                        }
                    }
                }
            }
        }
        bootLog("[HVAC] 盲扫完成: 命中 " + hit + " 个（含重复区域）");
        if (hit > 0) ui("HVAC 盲扫命中 " + hvacProps.size() + " 个属性");
    }

    /** 盲扫专用读值：直接用 readHvacValue（不写失败日志——几百个组合的失败会刷爆日志） */
    private String readHvacPropQuiet(int propId, int area) {
        Object v = readHvacValueQuiet(propId, area);
        if (v == null) return null;
        return v instanceof Float ? ((Float) v) + "f" : String.valueOf(v);
    }

    /** 静默读原始值（盲扫用，不写日志；逻辑同 readHvacValue：真 0 返回 Int 0） */
    private Object readHvacValueQuiet(int propId, int area) {
        Object intVal = null;
        try {
            Method getInt = hvacManager.getClass().getMethod("getIntProperty", int.class, int.class);
            Object v = getInt.invoke(hvacManager, propId, area);
            if (v instanceof Integer) {
                if ((Integer) v != 0) return v;
                intVal = v;
            }
        } catch (Throwable ignored) {}
        try {
            Method getFloat = hvacManager.getClass().getMethod("getFloatProperty", int.class, int.class);
            Object v = getFloat.invoke(hvacManager, propId, area);
            if (v instanceof Float) return v;
        } catch (Throwable ignored) {}
        return intVal;
    }

    /** 读原始值：Int 成功且非 0 直接返回；Int 读到 0 时续试 Float（float 属性会被误读成 0），
     *  Float 也失败则返回原 Int 0（真 0 值，如风量关到 0）
     *  23:01 实测 bug：16385 是 float 属性，getIntProperty 返回 Integer 0 不抛异常，
     *  直接短路导致启动读不到 22.0℃ */
    private Object readHvacValue(int propId, int area) {
        Object intVal = null;
        try {
            Method getInt = hvacManager.getClass().getMethod("getIntProperty", int.class, int.class);
            Object v = getInt.invoke(hvacManager, propId, area);
            if (v instanceof Integer) {
                if ((Integer) v != 0) return v;
                intVal = v;
                bootLog("[HVAC] (" + propId + "," + area + ") Int=0，续试Float");
            }
        } catch (Throwable ignored) {
        }
        try {
            Method getFloat = hvacManager.getClass().getMethod("getFloatProperty", int.class, int.class);
            Object v = getFloat.invoke(hvacManager, propId, area);
            if (v instanceof Float) return v;
        } catch (Throwable floatFail) {
            bootLog("[HVAC] (" + propId + "," + area + ") 读值失败: " + rootCause(floatFail));
        }
        return intVal; // Int 0 是真 0（float 属性已被 Float 分支接管），否则 null
    }

    private int parseHvacInt(String v) {
        if (v == null) return -1;
        try { return Integer.parseInt(v.replace("f", "")); } catch (NumberFormatException e) { return -1; }
    }

    private static String hvacPropName(int propId) {
        String n = HVAC_PROP_NAMES.get(propId);
        return n != null ? n : "HVAC属性" + propId;
    }

    /** dump 对象全部方法+字段（老版类 API 差异时一次看清真实结构） */
    private static String dumpObjectStructure(Object o) {
        StringBuilder sb = new StringBuilder();
        try {
            Class<?> c = o.getClass();
            sb.append("类名: ").append(c.getName()).append("\n方法: ");
            for (Method m : c.getMethods()) sb.append(m.getName()).append("(")
                    .append(m.getParameterCount()).append(") ");
            sb.append("\n声明字段: ");
            for (java.lang.reflect.Field f : c.getDeclaredFields()) sb.append(f.getName())
                    .append(":").append(f.getType().getSimpleName()).append(" ");
        } catch (Throwable e) {
            sb.append("dump失败: ").append(e);
        }
        return sb.toString();
    }

    private static String safeToString(Object o) {
        try { return o == null ? "null" : o.toString(); } catch (Throwable e) { return "toString炸:" + e.getClass().getSimpleName(); }
    }

    /** HVAC 推送：全属性缓存到 hvacProps（区域|值），16387 区 8 更新滤芯入参 r（照抄 dn.a(CarPropertyValue)） */
    private void handleHvacEvent(Object carPropertyValue) {
        try {
            Method getProp = carPropertyValue.getClass().getMethod("getPropertyId");
            Method getArea = carPropertyValue.getClass().getMethod("getAreaId");
            Method getVal = carPropertyValue.getClass().getMethod("getValue");
            int prop = (Integer) getProp.invoke(carPropertyValue);
            int area = (Integer) getArea.invoke(carPropertyValue);
            Object v = getVal.invoke(carPropertyValue);
            String vs = v == null ? "null" : v.toString();

        // 缓存：key=propId，value="area=值"（同 prop 多区域用分号拼接，同 area 替换旧值）
        String old = hvacProps.containsKey(prop) ? hvacProps.get(prop) : "";
        String tag = "area" + area + "=" + vs;
        String prefix = "area" + area + "=";
        if (old.isEmpty()) {
            hvacProps.put(prop, tag);
        } else {
            String[] parts = old.split(";");
            StringBuilder sb = new StringBuilder();
            boolean found = false;
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) sb.append(";");
                if (parts[i].startsWith(prefix)) { sb.append(tag); found = true; }
                else sb.append(parts[i]);
            }
            if (!found) sb.append(";").append(tag);
            hvacProps.put(prop, sb.toString());
        }
        // 推送计数节流：每属性只记前 3 次到日志
        Integer cnt = hvacPushCount.get(prop);
        int c = cnt == null ? 1 : cnt + 1;
        hvacPushCount.put(prop, c);
        if (c <= 3) bootLog("[HVAC] 推送 " + hvacPropName(prop) + " area=" + area + " → " + vs);

        if (prop == 16387 && area == 8) {
            aarHvacCcm = v instanceof Integer ? (Integer) v : parseHvacInt(vs);
            computeAarState();
        }
        refreshDisplay();
        } catch (Throwable e) {
            bootLog("[HVAC] 推送解析失败: " + rootCause(e));
        }
    }

    /** 读 AAR 全套属性 + 注册 YF 回调（照抄 dn.a(CarYFSettingManager)） */
    private void readAarData() {
        bootLog("[新风] ==== 读取智能新风/AAR（vendor_yfsetting）====");
        try {
            Class<?> mgrClass = yfManager.getClass();
            Method getProp = mgrClass.getMethod("getGlobalProperty", Class.class, int.class);

            // 1) 回调注册（胎压单位/PM 推送）
            try {
                Class<?> cbClass = Class.forName("android.car.hardware.CarYFSettingManager$CarYFSettingCallback");
                yfCallback = Proxy.newProxyInstance(cbClass.getClassLoader(),
                        new Class<?>[]{cbClass},
                        (proxy, method, args) -> {
                            String m = method.getName();
                            if ("hashCode".equals(m)) return System.identityHashCode(proxy);
                            if ("equals".equals(m)) return proxy == args[0];
                            if ("toString".equals(m)) return "YfCallbackProxy";
                            if ("onChangeEvent".equals(m) && args != null && args.length > 0) {
                                handleYfEvent(args[0]);
                            }
                            Class<?> rt = method.getReturnType();
                            if (rt == boolean.class) return Boolean.FALSE;
                            if (rt == int.class) return 0;
                            if (rt == long.class) return 0L;
                            if (rt == float.class) return 0f;
                            if (rt == double.class) return 0d;
                            return null;
                        });
                Method register = mgrClass.getMethod("registerCallback", cbClass);
                register.invoke(yfManager, yfCallback);
                bootLog("[新风] YF 回调注册OK");
            } catch (Throwable e) {
                bootLog("[新风] YF 回调注册失败: " + rootCause(e));
            }

            // 2) 一次性读当前值（dn.a(CarYFSettingManager) 初始化顺序）
            aarPmNow = readYfInt(getProp, YF_PM_NOW, "车内PM2.5");
            aarPmDiag = readYfInt(getProp, YF_PM_DIAG, "PM传感器诊断");
            aarSensorMiss = readYfInt(getProp, YF_SENSOR_MISS, "PM传感器缺失");
            aarPowerBtn = readYfInt(getProp, YF_CCM_POWER, "CCM功率按钮");
            winDriver = readYfInt(getProp, YF_WIN_DRIVER, "司机车窗");
            winPassenger = readYfInt(getProp, YF_WIN_PASSENGER, "副驾车窗");
            winLeftRear = readYfInt(getProp, YF_WIN_LEFT_REAR, "左后车窗");
            winRightRear = readYfInt(getProp, YF_WIN_RIGHT_REAR, "右后车窗");
            // PM 历史（dn.d(): 10 条，2~20 点）
            for (int i = 0; i < YF_PM_HISTORY.length; i++) {
                aarPmHistory[i] = readYfInt(getProp, YF_PM_HISTORY[i], "PM历史" + (i * 2 + 2) + "点");
            }

            // 3) 请求胎压单位（照抄 fk.b()+fm: 写 292565248 触发推送）
            try {
                Method setProp = mgrClass.getMethod("setGlobalProperty", Class.class, int.class, Object.class);
                // 照抄 fm.java：{1, b(2312), b(USER_MASK=0xF000), 4} = {1,09,08,F0,00,04}
                byte[] req = {1, 0x09, 0x08, (byte) 0xF0, 0x00, 0x04};
                setProp.invoke(yfManager, byte[].class, YF_TIRE_UNIT_REQ, req);
                bootLog("[新风] 胎压单位请求已发送(292565248)");
            } catch (Throwable e) {
                bootLog("[新风] 胎压单位请求失败: " + rootCause(e));
            }

            // 4) 初始滤芯状态
            computeAarState();
            ui("智能新风读取完成 PM=" + (aarPmNow >= 0 ? aarPmNow + "μg/m³" : "N/A"));
        } catch (Throwable e) {
            bootLog("[新风] AAR 读取流程异常:\n" + stackTrace(e));
            ui("智能新风读取失败: " + e.getMessage());
        }
    }

    private int readYfInt(Method getProp, int propId, String label) {
        try {
            Object v = getProp.invoke(yfManager, Integer.class, propId);
            int r = v instanceof Integer ? (Integer) v : -1;
            bootLog("[新风] " + label + "(" + propId + ")=" + r);
            return r;
        } catch (Throwable e) {
            bootLog("[新风] " + label + "(" + propId + ")读取失败: " + rootCause(e));
            return -1;
        }
    }

    /** YF 回调推送（照抄 dr.java/fk+fl.java） */
    private void handleYfEvent(Object carPropertyValue) {
        try {
            Method getProp = carPropertyValue.getClass().getMethod("getPropertyId");
            int prop = (Integer) getProp.invoke(carPropertyValue);
            Method getVal = carPropertyValue.getClass().getMethod("getValue");
            Object val = getVal.invoke(carPropertyValue);
            switch (prop) {
                case YF_PM_NOW:
                    aarPmNow = val instanceof Integer ? (Integer) val : -1;
                    bootLog("[新风] PM2.5 推送=" + aarPmNow);
                    break;
                case YF_PM_DIAG:
                    aarPmDiag = val instanceof Integer ? (Integer) val : -1;
                    bootLog("[新风] PM诊断推送=" + aarPmDiag);
                    break;
                case YF_SENSOR_MISS:
                    aarSensorMiss = val instanceof Integer ? (Integer) val : -1;
                    bootLog("[新风] 传感器缺失推送=" + aarSensorMiss);
                    break;
                case YF_CCM_POWER:
                    aarPowerBtn = val instanceof Integer ? (Integer) val : -1;
                    bootLog("[新风] CCM功率推送=" + aarPowerBtn);
                    break;
                case YF_WIN_DRIVER:
                    winDriver = val instanceof Integer ? (Integer) val : -1;
                    bootLog("[新风] 司机车窗推送=" + winDriver + (winDriver == 0 ? "(开)" : winDriver == 1 ? "(关)" : "(?)"));
                    break;
                case YF_WIN_PASSENGER:
                    winPassenger = val instanceof Integer ? (Integer) val : -1;
                    bootLog("[新风] 副驾车窗推送=" + winPassenger + (winPassenger == 0 ? "(开)" : winPassenger == 1 ? "(关)" : "(?)"));
                    break;
                case YF_WIN_LEFT_REAR:
                    winLeftRear = val instanceof Integer ? (Integer) val : -1;
                    bootLog("[新风] 左后车窗推送=" + winLeftRear + (winLeftRear == 0 ? "(开)" : winLeftRear == 1 ? "(关)" : "(?)"));
                    break;
                case YF_WIN_RIGHT_REAR:
                    winRightRear = val instanceof Integer ? (Integer) val : -1;
                    bootLog("[新风] 右后车窗推送=" + winRightRear + (winRightRear == 0 ? "(开)" : winRightRear == 1 ? "(关)" : "(?)"));
                    break;
                    default:
                        // 胎压单位（fl.java: a(bArr,i)=(b[i]<<8)|b[i+1] 两字节大端；
                        // 标识 a(bArr,0)==2312，单位 a(bArr,2)：0=psi 1=kPa 2=bar）
                        if (val instanceof byte[]) {
                            byte[] b = (byte[]) val;
                            int len = Math.min(b.length, 16);
                            StringBuilder hex = new StringBuilder();
                            for (int i = 0; i < len; i++) hex.append(String.format("%02X ", b[i]));
                            if (b.length > 3 && (((b[0] & 0xFF) << 8) | (b[1] & 0xFF)) == 2312) {
                                int unit = ((b[2] & 0xFF) << 8) | (b[3] & 0xFF); // 两字节大端，非单字节
                                String u = unit == 0 ? "psi" : unit == 1 ? "kPa" : unit == 2 ? "bar" : "?" + unit;
                                tireUnit = u;
                                bootLog("[新风] 胎压单位推送: " + u + " bytes=[" + hex + "]");
                                ui("胎压单位: " + u);
                            } else {
                                bootLog("[新风] YF byte[] 推送 prop=" + prop + " bytes=[" + hex + "]");
                            }
                        }
                        break;
            }
            computeAarState();
            refreshDisplay();
        } catch (Throwable e) {
            bootLog("[新风] YF 推送解析失败: " + rootCause(e));
        }
    }

    /**
     * 滤芯状态机（照抄 dn.java 四函数）：
     * 入参：q=CCM功率按钮 r=HVAC CCM s=传感器缺失 t=PM浓度 u=诊断 o=上一次状态
     * o=1过滤开启 2过滤关闭 3过滤完成 4异常(滤芯需更换)
     */
    private void computeAarState() {
        if (aarPmNow < 0) return; // 数据未到不判定
        int q = Math.max(aarPowerBtn, 0), r = Math.max(aarHvacCcm, 0),
                s = Math.max(aarSensorMiss, 0), t = aarPmNow, u = Math.max(aarPmDiag, 0);
        int newState;
        if (aarFilterOff(q, r, s, t, u)) newState = 2;
        else if (aarFilterOn(q, r, s, t, u)) newState = 1;
        else if (aarFilterDone(q, r, s, t, u)) newState = 3;
        else if (aarFilterErr(q, r, s, t, u, aarState)) newState = 4;
        else newState = 0;
        aarState = newState;
    }

    /** dn.d(): 过滤关闭 — q=0 且 r∈{2,0} 且 (传感器缺失 或 PM 有效0<PM≤509 或 PM无效且诊断∈{4,0,5}) */
    private boolean aarFilterOff(int q, int r, int s, int t, int u) {
        if (q != 0) return false;
        if (r != 2 && r != 0) return false;
        if (s == 1) return true;
        if (s != 0) return false;
        if (t > 0 && t <= 509) return true;
        if (t == 510 || t == 511) return u == 4 || u == 0 || u == 5;
        return false;
    }

    /** dn.b(): 过滤开启 — q=1 且 r=1 且 (传感器缺失 或 35<PM≤509 或 PM无效且诊断∈{4,0,5}) */
    private boolean aarFilterOn(int q, int r, int s, int t, int u) {
        if (q != 0 || r != 1) return false;
        if (s == 1) return true;
        if (s != 0) return false;
        if (t > 35 && t <= 509) return true;
        if (t == 510 || t == 511) return u == 4 || u == 0 || u == 5;
        return false;
    }

    /** dn.c(): 过滤完成 — q=0 r=1 传感器正常 PM≤35 */
    private boolean aarFilterDone(int q, int r, int s, int t, int u) {
        return q == 0 && r == 1 && s == 0 && t <= 35;
    }

    /** dn.a(): 异常(滤芯需更换) — PM无效且诊断∈{3,6,2}；或上态为1时 q=1 r≠0 */
    private boolean aarFilterErr(int q, int r, int s, int t, int u, int prevState) {
        if (q != 0 || r != 1) {
            return q == 1 && r != 0 && prevState == 1;
        }
        if (t == 510 || t == 511) {
            return u == 3 || u == 6 || u == 2;
        }
        return false;
    }

    /** 车内 PM2.5 等级（照抄 dn.c(i) 分档，返回 1~6，0=无效） */
    private static int pmLevel(int pm) {
        if (pm < 0) return 0;
        if (pm <= 35) return 1;
        if (pm <= 75) return 2;
        if (pm <= 115) return 3;
        if (pm <= 150) return 4;
        if (pm <= 250) return 5;
        return pm <= 500 ? 6 : 0; // >500 视为无效（510/511 无效哨兵）
    }

    private static String pmLevelStr(int lv) {
        switch (lv) {
            case 1: return "优";
            case 2: return "良";
            case 3: return "轻度";
            case 4: return "中度";
            case 5: return "重度";
            case 6: return "严重";
            default: return "无效";
        }
    }

    // ========== 车辆信息（标准 API + 百度定制全套，照抄 CarLauncher eh.n()）==========

    private void readCarInfo() {
        bootLog("[车况] ==== 读取车辆信息 ====");
        ui("读取车辆信息...");
        // 1) 标准 API
        String[][] calls = {
                {"制造商", "getManufacturer"},
                {"型号", "getModel"},
                {"年份", "getModelYear"},
                {"VIN", "getVehicleId"},
                {"油箱容量", "getFuelCapacity"},
                {"EV电池容量", "getEvBatteryCapacity"},
        };
        for (String[] c : calls) {
            try {
                Object result = infoManager.getClass().getMethod(c[1]).invoke(infoManager);
                bootLog("[车况] " + c[0] + ": " + result);
                ui(c[0] + ": " + result);
            } catch (Throwable e) {
                bootLog("[车况] " + c[0] + " 读取失败: " + rootCause(e));
            }
        }
        // 车辆标识（照抄 CarLauncher em.java: getStringProperty(117440591)）
        try {
            Method getString = infoManager.getClass().getMethod("getStringProperty", int.class);
            Object identify = getString.invoke(infoManager, PROP_CAR_IDENTIFY);
            bootLog("[车况] 车辆标识(117440591): " + identify);
            ui("车辆标识: " + identify);
        } catch (Throwable e) {
            bootLog("[车况] 车辆标识读取失败: " + rootCause(e));
        }
        // 2) 百度定制 API（照抄 CarLauncher：车型全套 + 燃油类型 + AAR）
        try {
            Method getBytes = infoManager.getClass().getMethod("getBytesProprety", int.class);
            int[][] props = {
                    {INFO_CAR_TYPE, 0},
                    {INFO_CAR_STYLE, 0},
                    {INFO_CAR_PATTERN, 0},
                    {INFO_CAR_COLOR, 0},
                    {INFO_FUEL_TYPE, 0},
                    {INFO_AAR_CONFIG, 0},
            };
            String[] names = {"车型代码(117440513)", "车型款式(117440515)", "车型代码VsCode(117440516)", "车身颜色(117440517)", "燃油类型(117440545)", "AAR配置(117440539)"};
            for (int i = 0; i < props.length; i++) {
                try {
                    byte[] bytes = (byte[]) getBytes.invoke(infoManager, props[i][0]);
                    if (bytes != null && bytes.length > 0) {
                        StringBuilder hex = new StringBuilder();
                        for (byte b : bytes) hex.append(b & 0xFF).append(" ");
                        bootLog("[车况] " + names[i] + ": bytes=[" + hex + "] int=" + bytesToInt(bytes));
                        ui(names[i] + ": " + bytesToInt(bytes));
                    } else {
                        bootLog("[车况] " + names[i] + ": 返回空");
                    }
                } catch (Throwable e) {
                    bootLog("[车况] " + names[i] + " 读取失败: " + rootCause(e));
                }
            }
            // 车型名称（照抄 CarLauncher eh.m(): bytes[0]+bytes[1] 为索引查 h 数组）
            try {
                byte[] bytes = (byte[]) getBytes.invoke(infoManager, INFO_CAR_TYPE);
                if (bytes != null && bytes.length > 1) {
                    int idx = (bytes[0] & 0xFF) + (bytes[1] & 0xFF);
                    String carName = idx < CAR_TYPE_NAMES.length ? CAR_TYPE_NAMES[idx] : "Default";
                    bootLog("[车况] 车型名称: 索引=" + idx + " → " + carName);
                    ui("车型名称: " + carName);
                }
            } catch (Throwable e) {
                bootLog("[车况] 车型名称解析失败: " + rootCause(e));
            }
        } catch (Throwable e) {
            bootLog("[车况] getBytesProprety 方法不存在: " + rootCause(e));
        }
    }

    private int bytesToInt(byte[] b) {
        int r = 0;
        for (int i = 0; i < b.length; i++) r |= (b[i] & 0xFF) << (8 * i);
        return r;
    }

    /** 反射异常取根因（InvocationTargetException 里的真实异常） */
    private static String rootCause(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t.getClass().getName() + ": " + t.getMessage();
    }

    // ========== 显示 ==========

    private void refreshDisplay() {
        StringBuilder d = new StringBuilder();
        d.append("════════════════════════\n");
        d.append("  车况数据 (CarService直读)\n");
        d.append("════════════════════════\n");

        // 总里程：float，无效值哨兵 16777215（照抄 CarLauncher eh.i()）
        float odometer = getFloat(evOdometer);
        float speed = getFloat(evSpeed);
        int gear = getInt(evGear);
        float range = getFloat(evRange);
        float fuelPct = getFloat(evFuelPct);
        int fuelWarn = getInt(evFuelWarn);
        int ignition = getInt(evIgnition);

        d.append(String.format("总里程: %s %s\n",
                (odometer >= 0 && odometer != 1.6777215E7f) ? String.format("%.1f", odometer) : "N/A", mileageUnit));
        d.append(String.format("车速: %s km/h\n", speed >= 0 ? String.format("%.0f", speed) : "N/A"));
        d.append(String.format("挡位: %s\n", evGear == null ? "N/A(无数据)" : gearStr(gear)));
        d.append(String.format("油量: %s\n", fuelPct >= 0 ? String.format("%.0f%%", fuelPct) : "N/A"));
        d.append(String.format("续航: %s %s\n",
                range >= 0 ? String.format("%.0f", range) : "N/A", mileageUnit));
        d.append(String.format("油量预警: %s\n",
                evFuelWarn == null ? "N/A(无数据)" : (fuelWarn == 1 ? "触发" : "正常")));
        d.append(String.format("点火: %s\n", ignitionStr(ignition)));
        int oilLife = getInt(evOilLife);
        d.append(String.format("机油寿命: %s\n",
                evOilLife == null ? "N/A(无数据)" : oilLife + "%"));

        d.append("──────────────────────\n");
        d.append("车门:\n");
        d.append(String.format("  左前:%s 右前:%s\n", doorStr(evDoorLB), doorStr(evDoorRB)));
        d.append(String.format("  左后:%s 右后:%s\n", doorStr(evDoorLR), doorStr(evDoorRR)));
        d.append(String.format("  后备箱内:%s 外:%s\n", doorStr(evTrunkIn), doorStr(evTrunkOut)));

        d.append("──────────────────────\n");
        d.append("胎压");
        if (!tireUnit.isEmpty()) d.append("(单位: ").append(tireUnit).append(")");
        d.append(":\n");
        String[] labels = {"左前", "右前", "左后", "右后"};
        for (int i = 0; i < TIRE_AREAS.length; i++) {
            TireData td = tireData.get(TIRE_AREAS[i]);
            if (td != null) {
                d.append(String.format("  %s: %s %s %s\n",
                        labels[i],
                        pressureStr(td.pressure),
                        tempStr(td.temp),
                        tireStateStr(td.state)));
            } else {
                d.append("  " + labels[i] + ": N/A\n");
            }
        }

        // 智能新风/AAR（dn.java）
        d.append("──────────────────────\n");
        d.append("智能新风(AAR):\n");
        if (aarPmNow >= 0) {
            d.append(String.format("  车内PM2.5: %d μg/m³ (%s)\n", aarPmNow, pmLevelStr(pmLevel(aarPmNow))));
        } else {
            d.append("  车内PM2.5: N/A\n");
        }
        d.append(String.format("  滤芯状态: %s\n", aarPmNow >= 0 ? aarStateStr(aarState) : "N/A(无PM数据)"));
        if (aarPmDiag >= 0 || aarSensorMiss >= 0 || aarPowerBtn >= 0 || aarHvacCcm >= 0) {
            d.append(String.format("  诊断入参: q(CCM按钮)=%s r(实际功率)=%s s(传感器缺失)=%s u(诊断)=%s\n",
                    aarPowerBtn >= 0 ? aarPowerBtn + "" : "N/A",
                    aarHvacCcm >= 0 ? aarHvacCcm + "" : "N/A",
                    aarSensorMiss >= 0 ? aarSensorMiss + "" : "N/A",
                    aarPmDiag >= 0 ? aarPmDiag + "" : "N/A"));
        }
        d.append(String.format("  车窗: 左前%s 右前%s 左后%s 右后%s\n",
                winStr(winDriver), winStr(winPassenger), winStr(winLeftRear), winStr(winRightRear)));
        if (aarPmHistory.length > 0 && aarPmHistory[0] >= 0) {
            StringBuilder hist = new StringBuilder();
            for (int i = 0; i < aarPmHistory.length; i++) {
                if (aarPmHistory[i] < 0) continue;
                if (hist.length() > 0) hist.append(' ');
                hist.append(i * 2 + 2).append("点=").append(aarPmHistory[i]);
            }
            if (hist.length() > 0) d.append("  PM历史: ").append(hist).append("\n");
        }

        d.append("──────────────────────\n");
        d.append("HVAC空调(枚举属性):\n");
        if (hvacProps.isEmpty()) {
            d.append("  (未枚举到属性)\n");
        } else {
            // 实锤语义的属性置顶显示（温度/风量/风向），其余未知 ID 排后
            for (HashMap.Entry<Integer, String> e : hvacProps.entrySet()) {
                if (HVAC_PROP_NAMES.containsKey(e.getKey())) {
                    d.append("  ").append(hvacPropName(e.getKey()))
                      .append(": ").append(formatHvacValue(e.getKey(), e.getValue())).append("\n");
                }
            }
            for (HashMap.Entry<Integer, String> e : hvacProps.entrySet()) {
                if (!HVAC_PROP_NAMES.containsKey(e.getKey())) {
                    d.append("  ").append(hvacPropName(e.getKey()))
                      .append(": ").append(e.getValue()).append("\n");
                }
            }
        }

        d.append("──────────────────────\n");
        d.append("其他传感器（未知ID待验证）:\n");
        d.append(otherSensorsSummary());

        final String text = d.toString();

        // V2 仪表盘逐控件刷新（主线程）
        handler.post(() -> {
            try { updateDashboard(); } catch (Throwable e) { bootLog("[UI] updateDashboard 失败: " + rootCause(e)); }
        });

        // 数据快照写文件（5秒节流，避免高频回调刷爆磁盘）
        logToFile(text, false);
    }

    /** V2 仪表盘逐控件刷新（仅主线程调用） */
    private void updateDashboard() {
        float odometer = getFloat(evOdometer);
        float speed = getFloat(evSpeed);
        float range = getFloat(evRange);
        float fuelPct = getFloat(evFuelPct);
        int oilLife = getInt(evOilLife);

        // ---- 顶部横幅 ----
        tvGear.setText(evGear == null ? "--" : gearStr(getInt(evGear)));
        if (evRange == null) {
            tvRange.setText("--");
        } else {
            tvRange.setText(String.format("%.0f", Math.max(range, 0)));
        }
        if (tvRangeUnit != null) tvRangeUnit.setText(mileageUnit);
        if (evOdometer == null || odometer < 0 || odometer == 1.6777215E7f) {
            tvOdo.setText("--");
        } else {
            tvOdo.setText(String.format("%.0f", odometer));
        }
        if (tvOdoUnit != null) tvOdoUnit.setText(mileageUnit);
        tvOil.setText(evOilLife == null ? "--" : oilLife + "%");
        tvFuel.setText(evFuelPct == null ? "--" : String.format("%.0f%%", Math.max(fuelPct, 0)));

        // ---- 速度 ----
        tvSpeed.setText(evSpeed == null ? "--" : String.format("%.0f", Math.max(speed, 0)));

        // ---- 四轮胎压（圆形标签：值+单位，呼吸边框） ----
        updateTireCircle(tagLFTire, tireData.get(1));
        updateTireCircle(tagRFTire, tireData.get(2));
        updateTireCircle(tagLRTire, tireData.get(4));
        updateTireCircle(tagRRTire, tireData.get(8));

        // ---- 门窗（门+窗合显，任一开整框变红） ----
        updateDoorCircle(tagLFDw, evDoorLB, winDriver);
        updateDoorCircle(tagRFDw, evDoorRB, winPassenger);
        updateDoorCircle(tagLRDw, evDoorLR, winLeftRear);
        updateDoorCircle(tagRRDw, evDoorRR, winRightRear);
        boolean trunkOpen = getInt(evTrunkIn) > 0 || getInt(evTrunkOut) > 0;
        if (tagTrunk != null) tagTrunk.updateTrunk(trunkOpen);

        // ---- 空调卡 ----
        // 温度双区：area1=主驾 area4=副驾（16385）
        String tempDriver = hvacAreaValue(16385, "area1");
        String tempPassenger = hvacAreaValue(16385, "area4");
        tvTempDriver.setText(tempDriver == null ? "--" : tempDriver + "℃");
        tvTempPassenger.setText(tempPassenger == null ? tempDriver == null ? "--" : tempDriver + "℃"
                : tempPassenger + "℃");   // 单区车机：副驾跟主驾
        tvPm.setText(aarPmNow >= 0 ? aarPmNow + " μg/m³" : "--");
        tvPm.setTextColor(pmColor(aarPmNow));



        // 标注点颜色同步：正常青、异常红（胎压<200kPa、门窗开、后备箱开）
        linkTagDots();
    }

    /** 胎压圆形标签刷新：值+单位+状态色（驱动呼吸节奏），不显示温度；
     *  state 判定照抄 CarLauncher pc.d() 区间 */
    private void updateTireCircle(TireCircleView v, TireData td) {
        if (v == null) return;
        if (td == null) {
            v.update("--", "kPa", TireCircleView.colorInvalid(this), false);
            return;
        }
        int color;
        boolean alert;
        if (td.pressure >= 65533f) {
            color = TireCircleView.colorInvalid(this); alert = false;   // 无效值灰
        } else if (td.state > 250) {
            color = TireCircleView.colorAlert(this); alert = true;      // 异常红
        } else if (td.state > 150 || td.state > 75) {
            color = TireCircleView.colorWarn(this); alert = false;      // 高压/低压琥珀
        } else {
            color = TireCircleView.colorOk(this); alert = false;         // 正常绿
        }
        v.update(pressureNum(td.pressure), tireUnit, color, alert);
    }

    /** 胎压数值（不带单位）：pressureStr 的拆分版，圆形里单位独立一行 */
    private String pressureNum(float raw) {
        String s = pressureStr(raw);
        int cut = s.lastIndexOf(tireUnit);
        return cut > 0 ? s.substring(0, cut) : s;   // 未知单位双显时整体作数值
    }

    /** 门窗圆形标签刷新：传入门窗状态，DoorCircleView 内部决定显示/隐藏和动画。
     *  窗值对齐 CarLauncher dn.f()：1=关，其余有效值（0/2/3…）都算未关，-1 无数据 */
    private void updateDoorCircle(DoorCircleView v, Object doorEv, int winState) {
        if (v == null) return;
        boolean doorOpen = getInt(doorEv) > 0;
        boolean winOpen = winState >= 0 && winState != 1;   // 1=关，其余有效值=未关
        v.update(doorOpen, winOpen);
    }

    /** 从 hvacProps 里取指定 area 的值（16385 温度这类多区属性用） */
    private String hvacAreaValue(int propId, String areaPrefix) {
        String raw = hvacProps.get(propId);
        if (raw == null) return null;
        for (String tag : raw.split(";")) {
            if (tag.startsWith(areaPrefix + "=")) {
                String v = tag.substring(areaPrefix.length() + 1).trim();
                if (v.endsWith("f")) v = v.substring(0, v.length() - 1);
                return v;
            }
        }
        return null;
    }

    /** 取属性最新值（开关类：分号末段） */
    private String hvacLatestValue(int propId) {
        String raw = hvacProps.get(propId);
        if (raw == null) return null;
        String latest = raw.substring(raw.lastIndexOf(';') + 1).trim();
        int eq = latest.indexOf('=');
        String v = eq > 0 ? latest.substring(eq + 1).trim() : latest;
        if (v.endsWith("f")) v = v.substring(0, v.length() - 1);
        return v;
    }

    /** 胶囊芯片：文本 + 开启高亮（bg_chip_on）/关闭（bg_chip） */
    private void setChip(TextView chip, String text, boolean on) {
        chip.setText(text);
        setChipBg(chip, on);
    }

    private void setChipBg(TextView chip, boolean on) {
        chip.setBackgroundResource(on ? R.drawable.bg_chip_on : R.drawable.bg_chip);
        chip.setTextColor(on ? getResources().getColor(R.color.accent_cyan)
                : getResources().getColor(R.color.chip_off_text));
    }

    /** PM2.5 颜色：优绿/良青/中黄/差橙/差红/严重红/无效灰（日夜自适应） */
    private int pmColor(int pm) {
        android.content.res.Resources res = getResources();
        int lv = pmLevel(pm);
        switch (lv) {
            case 1: return res.getColor(R.color.pm_good);
            case 2: return res.getColor(R.color.pm_fair);
            case 3: return res.getColor(R.color.state_warn);
            case 4: return res.getColor(R.color.state_pm_4);
            case 5: return res.getColor(R.color.state_alert);
            case 6: return res.getColor(R.color.state_alert_deep);
            default: return res.getColor(R.color.state_invalid);
        }
    }

    /** HVAC 已实锤属性的值格式化：
     *  16385 设定温度 float℃（双区，area1=主驾 area4=副驾）
     *  16388 风量档（1→2→3→4 递增，15=最大/除雾模式）
     *  16393 A/C制冷（1=开 0=关）
     *  16395 内循环（1=开 0=关）
     *  20481 座椅通风（area1=主驾 area2=副驾，0=关 1=开 2=初始）
     *  20487 进气模式（1=内循环 2=外循环）
     *  其余原值显示 */
    private static String formatHvacValue(int propId, String raw) {
        try {
            if (propId == 16385) {
                // 双区温度：area1=主驾 area4=副驾，各自取最新值
                StringBuilder sb = new StringBuilder();
                for (String tag : raw.split(";")) {
                    int eq = tag.indexOf('=');
                    if (eq <= 0) continue;
                    String areaStr = tag.substring(0, eq).replace("area", "").trim();
                    String val = tag.substring(eq + 1).trim().replace("f", "");
                    sb.append(hvacAreaName(areaStr)).append(val).append("℃ ");
                }
                if (sb.length() > 0) return sb.toString().trim();
            }
            if (propId == 16388) {
                StringBuilder sb = new StringBuilder();
                for (String tag : raw.split(";")) {
                    int eq = tag.indexOf('=');
                    if (eq <= 0) continue;
                    String areaStr = tag.substring(0, eq).replace("area", "").trim();
                    String val = tag.substring(eq + 1).trim();
                    String areaName = hvacAreaName(areaStr);
                    int n = parseHvacIntStatic(val);
                    if (n == 15) sb.append(areaName).append("最大/除雾 ");
                    else if (n >= 0) sb.append(areaName).append(n).append("档 ");
                    else sb.append(areaName).append(val).append(" ");
                }
                if (sb.length() > 0) return sb.toString().trim();
            }
            if (propId == 16393 || propId == 16395 || propId == 20487) {
                // 开关类：取最新值（分号末段）
                String latest = raw.substring(raw.lastIndexOf(';') + 1).trim();
                int eq = latest.indexOf('=');
                String val = eq > 0 ? latest.substring(eq + 1).trim() : latest;
                int n = parseHvacIntStatic(val);
                if (propId == 20487) return n == 1 ? "内循环" : n == 2 ? "外循环" : val;
                return n == 1 ? "开" : n == 0 ? "关" : val;
            }
            if (propId == 20481) {
                // 座椅通风：area1=主驾 area2=副驾
                StringBuilder sb = new StringBuilder();
                for (String tag : raw.split(";")) {
                    int eq = tag.indexOf('=');
                    if (eq <= 0) continue;
                    String areaStr = tag.substring(0, eq).replace("area", "").trim();
                    String val = tag.substring(eq + 1).trim();
                    String areaName = "1".equals(areaStr) ? "主驾" : "2".equals(areaStr) ? "副驾" : "区" + areaStr;
                    int n = parseHvacIntStatic(val);
                    sb.append(areaName).append(n == 0 ? "关 " : n == 1 ? "开 " : n == 2 ? "初始 " : val + " ");
                }
                if (sb.length() > 0) return sb.toString().trim();
            }
            if (propId == 16387) {
                String latest = raw.substring(raw.lastIndexOf(';') + 1).trim();
                int eq = latest.indexOf('=');
                String val = eq > 0 ? latest.substring(eq + 1).trim() : latest;
                int n = parseHvacIntStatic(val);
                return n == 2 ? "空调关闭" : n == 1 ? "空调通电" : n == 0 ? "待机" : val;
            }
        } catch (Throwable ignored) {}
        return raw;
    }

    /** HVAC 区域 ID → 座位名（23:01 实测：1=主驾 4=副驾 128=后排 8=前排 全局） */
    private static String hvacAreaName(String area) {
        switch (area) {
            case "1":   return "主驾";
            case "4":   return "副驾";
            case "128": return "后排";
            case "8":   return "前排";
            case "0":   return "";
            default:    return "区" + area + " ";
        }
    }

    private static int parseHvacIntStatic(String v) {
        if (v == null) return -1;
        try { return Integer.parseInt(v.replace("f", "")); } catch (NumberFormatException e) { return -1; }
    }

    /** 点火状态映射（CarSensorEvent.IGNITION_STATE_*，javap 核实） */
    private String ignitionStr(int v) {
        switch (v) {
            case -1: return "N/A";
            case 0:  return "未知";
            case 1:  return "LOCK(锁止)";
            case 2:  return "OFF(关闭)";
            case 3:  return "ACC(通电)";
            case 4:  return "ON(发动)";
            case 5:  return "START(启动中)";
            default: return "值=" + v;
        }
    }

    /** 未知传感器汇总（首次收到的值已写 boot.log，这里给 UI 一份简表） */
    private String otherSensorsSummary() {
        if (evOthers.isEmpty()) return "  (无)\n";
        StringBuilder sb = new StringBuilder();
        for (HashMap.Entry<Integer, Object> e2 : evOthers.entrySet()) {
            String v = eventSummary(e2.getValue());
            if (v.isEmpty()) v = "(空)";
            sb.append("  type=").append(e2.getKey())
              .append(": ").append(v).append("\n");
        }
        return sb.toString();
    }

    /** V2 仪表盘的文本快照（写 carinfo.log 用）——沿用旧版文本结构 */
    private String buildDataSnapshot() {
        StringBuilder d = new StringBuilder();
        d.append("════════════════════════\n");
        d.append("  车况数据 (CarService直读)\n");
        d.append("════════════════════════\n");
        float odometer = getFloat(evOdometer);
        float speed = getFloat(evSpeed);
        int gear = getInt(evGear);
        float range = getFloat(evRange);
        float fuelPct = getFloat(evFuelPct);
        int fuelWarn = getInt(evFuelWarn);
        int ignition = getInt(evIgnition);
        d.append(String.format("总里程: %s %s\n",
                (odometer >= 0 && odometer != 1.6777215E7f) ? String.format("%.1f", odometer) : "N/A", mileageUnit));
        d.append(String.format("车速: %s km/h\n", speed >= 0 ? String.format("%.0f", speed) : "N/A"));
        d.append(String.format("挡位: %s\n", evGear == null ? "N/A(无数据)" : gearStr(gear)));
        d.append(String.format("油量: %s\n", fuelPct >= 0 ? String.format("%.0f%%", fuelPct) : "N/A"));
        d.append(String.format("续航: %s %s\n",
                range >= 0 ? String.format("%.0f", range) : "N/A", mileageUnit));
        d.append(String.format("油量预警: %s\n",
                evFuelWarn == null ? "N/A(无数据)" : (fuelWarn == 1 ? "触发" : "正常")));
        d.append(String.format("点火: %s\n", ignitionStr(ignition)));
        int oilLife = getInt(evOilLife);
        d.append(String.format("机油寿命: %s\n",
                evOilLife == null ? "N/A(无数据)" : oilLife + "%"));
        d.append("──────────────────────\n");
        d.append("车门/车窗:\n");
        d.append(String.format("  左前:门%s 窗%s  右前:门%s 窗%s\n",
                doorStr(evDoorLB), winStr(winDriver), doorStr(evDoorRB), winStr(winPassenger)));
        d.append(String.format("  左后:门%s 窗%s  右后:门%s 窗%s\n",
                doorStr(evDoorLR), winStr(winLeftRear), doorStr(evDoorRR), winStr(winRightRear)));
        d.append(String.format("  后备箱内:%s 外:%s\n", doorStr(evTrunkIn), doorStr(evTrunkOut)));
        d.append("──────────────────────\n");
        d.append("胎压");
        if (!tireUnit.isEmpty()) d.append("(单位: ").append(tireUnit).append(")");
        d.append(":\n");
        String[] labels = {"左前", "右前", "左后", "右后"};
        for (int i = 0; i < TIRE_AREAS.length; i++) {
            TireData td = tireData.get(TIRE_AREAS[i]);
            if (td != null) {
                d.append(String.format("  %s: %s %s %s\n",
                        labels[i], pressureStr(td.pressure), tempStr(td.temp), tireStateStr(td.state)));
            } else {
                d.append("  " + labels[i] + ": N/A\n");
            }
        }
        d.append("──────────────────────\n");
        d.append("空调/新风:\n");
        if (aarPmNow >= 0) {
            d.append(String.format("  车内PM2.5: %d μg/m³ (%s)\n", aarPmNow, pmLevelStr(pmLevel(aarPmNow))));
        } else {
            d.append("  车内PM2.5: N/A\n");
        }
        for (int propId : new int[]{16385, 16388, 16393, 16395, 20481, 20487, 20513}) {
            String raw = hvacProps.get(propId);
            if (raw != null) {
                d.append("  ").append(hvacPropName(propId)).append(": ")
                  .append(formatHvacValue(propId, raw)).append("\n");
            }
        }
        if (hvacProps.isEmpty()) d.append("  (HVAC 无数据)\n");
        d.append("其他传感器:\n");
        d.append(otherSensorsSummary());
        return d.toString();
    }

    private String doorStr(Object ev) {
        int s = getInt(ev);
        if (s < 0) return "N/A";
        return s == 0 ? "关" : "开";
    }

    /** 胎压状态判定：照抄 CarLauncher pc.d() 区间——state≤75 均正常档，>250 超高 */
    private String tireStateStr(int state) {
        if (state <= 75) return "正常";
        if (state <= 150) return "低压";
        return state <= 250 ? "高压" : "异常";
    }

    /** 胎压显示：照抄 CarLauncher pc.a(unit, value) — 65533~65535 厂商无效值显示"--"；
     *  单位已知则按单位换算（bar×0.01 / psi×0.14504 / kPa 十位取整），未知则原始值+kPa 换算双显 */
    private String pressureStr(float raw) {
        if (raw >= 65533f) return "--";           // 65533/65534/65535 厂商无效值
        if ("bar".equals(tireUnit)) {
            float f = raw;
            if (f % 5.0f == 0.0f) f += 1.0f;       // pc.a: bar 模式下 5 的倍数加 1（厂商魔法）
            return String.format("%.2f%s", f * 0.01f, tireUnit);
        }
        if ("psi".equals(tireUnit)) {
            return String.format("%.0f%s", raw * 0.14504f, tireUnit);
        }
        if ("kPa".equals(tireUnit)) {
            int v = (int) raw;
            int f2 = v % 10;
            int shown;
            if (f2 <= 2) shown = v / 10 * 10;             // 四舍五入到十位（pc.a）
            else if (f2 >= 8) shown = (v / 10 + 1) * 10;
            else shown = (v / 10 * 10 + 5);
            return shown + tireUnit;
        }
        // 单位未知：原始值（0.01bar≈kPa）+bar 双显，等胎压单位推送后自动切换
        return String.format("%.0fkPa(%.2fbar)", raw, raw * 0.01f);
    }

    /** 胎温显示：CarLauncher 不显示温度且厂商未提供换算，仅原始值留档（≥65533 为无效值） */
    private String tempStr(float raw) {
        if (raw >= 65533f) return "";
        return String.format("T(raw=%,.0f)", raw);
    }

    /** 挡位值映射（CarLauncher LauncherActivity.a(int,boolean) 铁证：百度自定义枚举）
     *  0=静止(P)、1=N、2=R、3~10=前进挡（D 及 1~8 挡细分），非标准 GEAR_* 位标志 */
    private String gearStr(int v) {
        switch (v) {
            case 0: return "P";
            case 1: return "N";
            case 2: return "R";
            case 3: return "D";
            case 4: return "D2";
            case 5: return "D3";
            case 6: return "D4";
            case 7: return "D5";
            case 8: return "D6";
            case 9: return "D7";
            case 10: return "D8";
            default: return "值=" + v;
        }
    }

    // ========== 日志文件 ==========

    /** 过程日志：写 boot.log，失败自动轮转新文件（兼容卸载重装 UID 变化）；首次失败上报 UI */
    private static volatile String writeFailReason;

    private static void bootLog(String msg) {
        Log.d(TAG, msg);
        writeAppend(BOOT_LOG, msg);
    }

    /** UI 日志：写 boot.log（屏上不再显示滚动日志） */
    private void ui(String text) {
        Log.d(TAG, text);
        logBuilder.append(text).append("\n");
        writeAppend(BOOT_LOG, "[UI] " + text);
    }

    /** 车况数据快照：写 carinfo.log（force=true 用于 onDestroy 最终快照），5秒节流 */
    private void logToFile(String text, boolean force) {
        if (!force) {
            long now = System.currentTimeMillis();
            if (now - lastFileWrite < 5000) return;
            lastFileWrite = now;
        }
        try {
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), "CarProbe");
            if (!dir.exists()) dir.mkdirs();
            String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date());
            boolean ok = writeAppendRaw(new File(dir, "carinfo.log"), ts, text);
            if (!ok) {
                String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(new Date());
                writeAppendRaw(new File(dir, "carinfo_" + stamp + ".log"), ts, text);
            }
        } catch (Throwable e) {
            Log.e(TAG, "logToFile fail", e);
        }
    }

    /** 追加写一行；失败时（EACCES 等）轮转到带时间戳的新文件 */
    private static void writeAppend(File f, String msg) {
        try {
            File dir = f.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            String ts = new SimpleDateFormat("HH:mm:ss.SSS", Locale.CHINA).format(new Date());
            if (writeAppendRaw(f, ts, msg)) return;
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(new Date());
            String name = f.getName();
            int dot = name.lastIndexOf('.');
            String base = dot > 0 ? name.substring(0, dot) : name;
            String ext = dot > 0 ? name.substring(dot) : ".log";
            writeAppendRaw(new File(dir, base + "_" + stamp + ext), ts, msg);
        } catch (Throwable ignored) {}
    }

    private static boolean writeAppendRaw(File f, String... lines) {
        try (PrintWriter pw = new PrintWriter(new FileOutputStream(f, true))) {
            for (String line : lines) pw.println(line);
            return true;
        } catch (Throwable e) {
            Log.w(TAG, "append fail " + f.getName() + ": " + e.getMessage());
            if (writeFailReason == null) writeFailReason = f.getName() + ": " + e.getMessage();
            return false;
        }
    }

    private static String stackTrace(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private void setStatus(String text) {
        Log.d(TAG, "[状态] " + text);
        writeAppend(BOOT_LOG, "[状态] " + text);
    }

    private static class TireData {
        int state;
        float pressure;
        float temp;
        TireData(int state, float pressure, float temp) {
            this.state = state;
            this.pressure = pressure;
            this.temp = temp;
        }
    }
}
