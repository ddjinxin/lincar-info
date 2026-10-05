package com.jingxin.lincarinfo;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

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
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

/**
 * 车况数据服务 — 承载全部非 UI 逻辑，独立于 Activity 存活。
 *
 * 双模式架构：
 *  - run_mode=0（带界面，默认）：Activity bindService 获取数据回调刷新 UI
 *  - run_mode=1（纯服务）：BootReceiver 直接 startService，无界面，仅广播给乐酷驾驶
 *
 * 数据流向：Car API → Service 缓存 → publish() →
 *   ① 广播给乐酷驾驶（ACTION_VEHICLE_DATA）
 *   ② 通知已注册的 VehicleDataListener（Activity 在前台时）
 *
 * 拉模式：乐酷驾驶发 ACTION_VEHICLE_DATA_QUERY → Service 回发当前快照
 */
public class VehicleDataService extends Service {

    private static final String TAG = "LinCarInfo";
    private static final File BOOT_LOG = new File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "CarProbe/boot.log");

    // SP 文件名与键
    private static final String PREF_UI = "ui";
    static final String KEY_HVAC_TEMP = "hvac_temp";
    static final String KEY_RUN_MODE = "run_mode";   // 0=带界面 1=纯服务

    // ========== 常量（照抄 MainActivity） ==========

    private static final int SENSOR_ODOMETER = 4;
    private static final int SENSOR_SPEED = 2;
    private static final int SENSOR_GEAR = 7;
    private static final int SENSOR_IGNITION = 22;
    private static final int SENSOR_FUEL_WARNING = 25;
    private static final int SENSOR_RANGE = 26;
    private static final int SENSOR_FUEL_PCT = 27;
    private static final int SENSOR_OIL_LIFE = 51;
    private static final int SENSOR_DOOR_LB = 56;
    private static final int SENSOR_DOOR_RB = 57;
    private static final int SENSOR_DOOR_LR = 58;
    private static final int SENSOR_DOOR_RR = 59;
    private static final int SENSOR_TRUNK_IN = 60;
    private static final int SENSOR_DOOR_TRUNK_OUT = 61;
    private static final int SENSOR_TURN_LEFT = 68;
    private static final int SENSOR_TURN_RIGHT = 69;

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
        SENSOR_NAMES.put(SENSOR_TURN_LEFT, "左转向灯");
        SENSOR_NAMES.put(SENSOR_TURN_RIGHT, "右转向灯");
    }

    private static final HashMap<Integer, String> SENSOR_GUESS = new HashMap<>();
    static {
        SENSOR_GUESS.put(5,  "可能:行驶方向?");
        SENSOR_GUESS.put(9,  "未知9(全程=1,待验证)");
        SENSOR_GUESS.put(11, "挡位结合(0=P/31=行驶)");
        SENSOR_GUESS.put(52, "驻车驱动(待重验:拉手刹无变化)");
        SENSOR_GUESS.put(63, "非大灯(开关无反应)");
        SENSOR_GUESS.put(67, "电源档位(12=ON/0=OFF)");
        SENSOR_GUESS.put(68, "左转向灯(3=亮/1=灭/0=关)");
        SENSOR_GUESS.put(69, "右转向灯(3=亮/1=灭/0=关)");
        SENSOR_GUESS.put(72, "点火电源位(1=ON/0=LOCK)");
    }

    private static final HashSet<Integer> MONITORED_TYPES = new HashSet<>();
    static {
        MONITORED_TYPES.add(11);
        MONITORED_TYPES.add(52);
        MONITORED_TYPES.add(67);
        MONITORED_TYPES.add(68);
        MONITORED_TYPES.add(69);
        MONITORED_TYPES.add(72);
    }

    // VendorExtension property IDs
    private static final int PROP_TIRE_STATE = 658506770;
    private static final int PROP_TIRE_PRESSURE = 660603920;
    private static final int PROP_TIRE_TEMP = 660603921;
    private static final int PROP_MILEAGE_UNIT = 557843475;
    private static final int PROP_CAR_IDENTIFY = 117440591;
    private static final int[] TIRE_AREAS = {1, 2, 4, 8};

    // CarInfoManager property IDs
    private static final int INFO_CAR_TYPE = 117440513;
    private static final int INFO_CAR_STYLE = 117440515;
    private static final int INFO_CAR_PATTERN = 117440516;
    private static final int INFO_CAR_COLOR = 117440517;
    private static final int INFO_FUEL_TYPE = 117440545;
    private static final int INFO_AAR_CONFIG = 117440539;

    private static final String[] CAR_TYPE_NAMES = {
            "Default", "CD539", "C519", "D568", "CD391", "CX482", "CX483", "U625", "U611", "U540"};

    // HVAC 属性名
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

    // YF 常量
    private static final int YF_PM_NOW        = 289419786;
    private static final int YF_PM_DIAG       = 289419787;
    private static final int YF_SENSOR_MISS   = 289419790;
    private static final int YF_CCM_POWER     = 289419791;
    private static final int[] YF_PM_HISTORY = {
            289419776, 289419777, 289419778, 289419779, 289419780,
            289419781, 289419782, 289419783, 289419784, 289419785};
    private static final int YF_WIN_DRIVER  = 289437198;
    private static final int YF_WIN_PASSENGER = 289437200;
    private static final int YF_WIN_LEFT_REAR  = 289437199;
    private static final int YF_WIN_RIGHT_REAR = 289437201;
    private static final int YF_TIRE_UNIT_REQ = 292565248;

    // 广播 action
    public static final String ACTION_VEHICLE_DATA = "com.jingxin.lincarinfo.VEHICLE_DATA";
    public static final String ACTION_VEHICLE_DATA_QUERY = "com.jingxin.lincarinfo.VEHICLE_DATA_QUERY";
    private static final String TARGET_PKG = "com.jingxin.pandrive";
    private static final long VEHICLE_BC_MIN_INTERVAL = 300L;

    // 连接重试
    private static final int MSG_TRY_CONNECT = 1000;
    private static final int MSG_CHECK_CONNECTED = 1001;
    private static final int MSG_DISCONNECTED = 1002;

    // ========== 运行时状态 ==========

    private Object car;
    private Object sensorManager;
    private Object vendorManager;
    private Object infoManager;
    private Object yfManager;
    private Object hvacManager;
    private Object sensorListener;
    private Object vendorCallback;
    private Object yfCallback;
    private Object hvacCallback;

    private volatile boolean serviceConnected;
    private int tryConnectCount;
    private Handler carHandler;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // 传感器事件缓存
    private Object evOdometer, evSpeed, evGear, evRange, evFuelPct, evFuelWarn, evIgnition, evOilLife;
    private Object evDoorLB, evDoorRB, evDoorLR, evDoorRR, evTrunkIn, evTrunkOut;
    private Object evTurnLeft, evTurnRight;
    private final HashMap<Integer, Object> evOthers = new HashMap<>();
    private final HashMap<Integer, TireData> tireData = new HashMap<>();
    private final HashSet<Integer> firstDataLogged = new HashSet<>();
    private final HashMap<Integer, String> lastSensorValue = new HashMap<>();
    private final HashSet<Integer> registeredSensors = new HashSet<>();

    // HVAC
    private final TreeMap<Integer, String> hvacProps = new TreeMap<>();
    private String mileageUnit = "km";
    private Integer lastCcm = null;
    private Integer lastFanLevel = null;
    private Integer lastAc = null;
    private Integer lastRecycle = null;

    // AAR
    private int aarPowerBtn = -1;
    private int aarHvacCcm  = -1;
    private int aarSensorMiss = -1;
    private int aarPmNow     = -1;
    private int aarPmDiag    = -1;
    private int[] aarPmHistory = new int[10];
    private int winDriver = -1, winPassenger = -1, winLeftRear = -1, winRightRear = -1;
    private int aarState = 0;
    private String tireUnit = "";

    // 广播节流
    private long lastVehicleBcTime = 0L;
    private String lastVehicleBcFingerprint = "";

    // Listener（Activity 绑定时注册）
    private VehicleDataListener listener;

    // 车况查询接收器
    private BroadcastReceiver vehicleQueryReceiver;

    // ========== 生命周期 ==========

    @Override
    public void onCreate() {
        super.onCreate();
        bootLog("");
        bootLog("########################################################");
        bootLog("##### VehicleDataService 启动 " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date()) + " #####");
        bootLog("########################################################");

        registerVehicleQueryReceiver();

        if (!isCarApiAvailable()) {
            bootLog("[Service] 无 Car API — 模拟器模式：填模拟数据广播");
            loadMockData();
            return;
        }

        try {
            connectCarService();
        } catch (Throwable e) {
            bootLog("[Service] connectCarService 异常:\n" + stackTrace(e));
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        bootLog("[Service] onStartCommand");
        // 升级为前台 Service：Activity 退出后不被系统回收（模拟器 Android 14 实测 60 秒被杀）
        startAsForeground();
        return START_STICKY;
    }

    /** 低优先级通知常驻，保住进程存活 */
    private void startAsForeground() {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            String channelId = "vehicle_data";
            android.app.NotificationManager nm = (android.app.NotificationManager)
                    getSystemService(NOTIFICATION_SERVICE);
            android.app.NotificationChannel channel = new android.app.NotificationChannel(
                    channelId, "车况服务", android.app.NotificationManager.IMPORTANCE_MIN);
            channel.setShowBadge(false);
            nm.createNotificationChannel(channel);
            android.app.Notification.Builder nb;
            if (Build.VERSION.SDK_INT >= 26) {
                nb = new android.app.Notification.Builder(this, channelId);
            } else {
                nb = new android.app.Notification.Builder(this);
            }
            nb.setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
              .setContentTitle("车况服务运行中")
              .setContentText("正在为乐酷驾驶提供车机数据")
              .setOngoing(true)
              .setPriority(android.app.Notification.PRIORITY_MIN);
            startForeground(NOTIFICATION_ID, nb.build());
            bootLog("[Service] 已升级前台 Service");
        } catch (Throwable e) {
            bootLog("[Service] startForeground 失败: " + e);
        }
    }

    private static final int NOTIFICATION_ID = 2001;

    @Override
    public IBinder onBind(Intent intent) {
        bootLog("[Service] onBind — Activity 绑定");
        return binder;
    }

    private final IBinder binder = new LocalBinder();

    /** LocalBinder — Activity 绑定后通过 getService() 拿到 Service 实例 */
    public class LocalBinder extends android.os.Binder {
        VehicleDataService getService() {
            return VehicleDataService.this;
        }
    }

    @Override
    public boolean onUnbind(Intent intent) {
        bootLog("[Service] onUnbind — Activity 解绑");
        listener = null;
        return false;
    }

    @Override
    public void onDestroy() {
        bootLog("[Service] onDestroy");
        cleanupCarConnections();
        if (vehicleQueryReceiver != null) {
            try { unregisterReceiver(vehicleQueryReceiver); } catch (Throwable ignored) {}
            vehicleQueryReceiver = null;
        }
        if (carHandler != null) carHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ========== Binder ==========

    /**
     * 注册数据回调。Activity bindService 后调用此方法注册 listener。
     * 绑定后立即回调一次当前快照（播种 UI）。
     */
    public void registerListener(VehicleDataListener l) {
        this.listener = l;
        if (l != null) {
            handler.post(() -> {
                l.onVehicleDataChanged(getSnapshot());
            });
        }
    }

    public void unregisterListener(VehicleDataListener l) {
        if (this.listener == l) this.listener = null;
    }

    /** 获取当前快照（不限时也可调） */
    public VehicleData getSnapshot() {
        VehicleData d = new VehicleData();
        d.speed = getFloat(evSpeed);
        d.gear = getInt(evGear);
        d.odometer = getFloat(evOdometer);
        d.range = getFloat(evRange);
        d.fuelPct = getFloat(evFuelPct);
        d.fuelWarn = getInt(evFuelWarn);
        d.oilLife = getInt(evOilLife);
        d.ignition = getInt(evIgnition);
        d.mileageUnit = mileageUnit;
        d.turnLeft = getInt(evTurnLeft);
        d.turnRight = getInt(evTurnRight);
        for (int i = 0; i < TIRE_AREAS.length; i++) {
            TireData td = tireData.get(TIRE_AREAS[i]);
            if (td != null) {
                d.tirePressure[i] = td.pressure;
                d.tireTemp[i] = td.temp;
                d.tireState[i] = td.state;
            }
        }
        d.tireUnit = tireUnit;
        d.doorLB = getInt(evDoorLB);
        d.doorRB = getInt(evDoorRB);
        d.doorLR = getInt(evDoorLR);
        d.doorRR = getInt(evDoorRR);
        d.trunkIn = getInt(evTrunkIn);
        d.trunkOut = getInt(evTrunkOut);
        d.winDriver = winDriver;
        d.winPassenger = winPassenger;
        d.winLeftRear = winLeftRear;
        d.winRightRear = winRightRear;
        d.tempDriver = hvacAreaFloat(16385, 1);
        d.tempPassenger = hvacAreaFloat(16385, 4);
        d.acPower = hvacAreaInt(16387, 8) != null ? hvacAreaInt(16387, 8) : -1;
        d.fanLevel = hvacAreaInt(16388, 0) != null ? hvacAreaInt(16388, 0) : -1;
        d.acRecycle = hvacAreaInt(16395, 8) != null ? hvacAreaInt(16395, 8) : -1;
        d.acOn = hvacAreaInt(16393, 8) != null ? hvacAreaInt(16393, 8) : -1;
        d.pm25 = aarPmNow;
        d.hasRealData = hasRealData();
        return d;
    }

    // ========== 模拟数据 ==========

    private boolean hasRealData() {
        return evSpeed != null || evGear != null || evOdometer != null || evRange != null;
    }

    private void loadMockData() {
        evGear = mockEvent(SENSOR_GEAR, new int[]{0});
        evRange = mockEvent(SENSOR_RANGE, new float[]{503f});
        evOdometer = mockEvent(SENSOR_ODOMETER, new float[]{74595f});
        evOilLife = mockEvent(SENSOR_OIL_LIFE, new int[]{75});
        evFuelPct = mockEvent(SENSOR_FUEL_PCT, new float[]{81f});
        evSpeed = mockEvent(SENSOR_SPEED, new float[]{0f});
        evDoorLB = mockEvent(SENSOR_DOOR_LB, new int[]{1});
        evDoorRB = mockEvent(SENSOR_DOOR_RB, new int[]{0});
        evDoorLR = mockEvent(SENSOR_DOOR_LR, new int[]{0});
        evDoorRR = mockEvent(SENSOR_DOOR_RR, new int[]{1});
        evTrunkIn = mockEvent(SENSOR_TRUNK_IN, new int[]{0});
        evTrunkOut = mockEvent(SENSOR_DOOR_TRUNK_OUT, new int[]{0});
        int[] areas = {1, 2, 4, 8};
        float[] pressures = {220f, 220f, 220f, 225f};
        for (int i = 0; i < areas.length; i++) {
            tireData.put(areas[i], new TireData(50, pressures[i], 19.5f));
        }
        tireUnit = "kPa";
        winDriver = 0; winPassenger = 1; winLeftRear = 1; winRightRear = 1;
        hvacProps.put(16385, "area1=23.0;area4=23.0");
        hvacProps.put(16388, "area0=1");
        hvacProps.put(16393, "area8=0");
        hvacProps.put(16395, "area8=1");
        hvacProps.put(20481, "area1=0;area2=0");
        hvacProps.put(20487, "area8=2");
        hvacProps.put(20513, "area16777216=12");
        aarPmNow = 4;
        publish();
        bootLog("[模拟] 模拟数据已填入");
    }

    private Object mockEvent(int sensorType, Object values) {
        return new CarSensorEventMock(sensorType, values);
    }

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

    static class TireData {
        int state;
        float pressure;
        float temp;
        TireData(int state, float pressure, float temp) {
            this.state = state;
            this.pressure = pressure;
            this.temp = temp;
        }
    }

    // ========== Car 连接 ==========

    private boolean isCarApiAvailable() {
        try {
            Class.forName("android.car.Car");
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    private void connectCarService() {
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
                    Context.class, ServiceConnection.class, Handler.class);
            car = createCar.invoke(null, this, conn, carHandler);
            bootLog("[连接] createCar 返回: " + (car != null ? "非null" : "null"));
            if (car == null) {
                bootLog("[连接] createCar 返回 null");
                return;
            }
            car.getClass().getMethod("connect").invoke(car);
            bootLog("[连接] connect() 已调用，等待 onServiceConnected...");
        } catch (Throwable e) {
            bootLog("[连接] createCar/connect 异常:\n" + stackTrace(e));
        }
    }

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
                bootLog("[连接] 连接超时（isConnected 一直 false）");
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
                    Context.class, ServiceConnection.class, Handler.class);
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

        sensorManager = getManagerQuietly(getCarManager, "sensor", "CarSensorManager");
        vendorManager = getManagerQuietly(getCarManager, "vendor_extension", "VendorExtension");
        infoManager = getManagerQuietly(getCarManager, "info", "CarInfoManager");
        hvacManager = getManagerQuietly(getCarManager, "hvac", "CarHvacManager");
        yfManager = getManagerQuietly(getCarManager, "vendor_yfsetting", "CarYFSettingManager");

        if (sensorManager != null) registerSensors();
        else bootLog("[就绪] CarSensorManager 不可用");

        if (vendorManager != null) {
            registerVendorCallback();
            readTireData();
            handler.postDelayed(() -> {
                if (tireData.size() < TIRE_AREAS.length) {
                    tireLog("[胎压] 首次读取不完整（" + tireData.size() + "/4），5秒后重试");
                    readTireData();
                }
            }, 5000L);
        } else bootLog("[就绪] VendorExtension 不可用（胎压需此 Manager）");

        if (infoManager != null) readCarInfo();
        else bootLog("[就绪] CarInfoManager 不可用");

        if (hvacManager != null) readHvacCcm();
        else {
            restoreHvacTempFromSP();
            bootLog("[就绪] CarHvacManager 不可用（滤芯状态入参）");
        }

        if (yfManager != null) readAarData();
        else bootLog("[就绪] CarYFSettingManager 不可用（智能新风/滤芯/车窗）");

        publish();
        bootLog("[就绪] onCarServiceReady 完成");
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

    // ========== 传感器注册与回调 ==========

    private void registerSensors() {
        bootLog("[传感器] 开始注册");
        try {
            Class<?> listenerClass = Class.forName(
                    "android.car.hardware.CarSensorManager$OnSensorChangedListener");
            sensorListener = Proxy.newProxyInstance(
                    listenerClass.getClassLoader(),
                    new Class<?>[]{listenerClass},
                    (proxy, method, args) -> {
                        String m = method.getName();
                        if ("hashCode".equals(m)) return System.identityHashCode(proxy);
                        if ("equals".equals(m)) return proxy == args[0];
                        if ("toString".equals(m)) return "SensorListenerProxy@" + Integer.toHexString(System.identityHashCode(proxy));
                        if ("onSensorChanged".equals(m)) {
                            cacheSensorEvent(args[0]);
                            publish();
                        }
                        Class<?> rt = method.getReturnType();
                        if (rt == boolean.class) return Boolean.FALSE;
                        if (rt == int.class) return 0;
                        if (rt == long.class) return 0L;
                        if (rt == float.class) return 0f;
                        if (rt == double.class) return 0d;
                        return null;
                    });

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

            int[] hardcoded = {
                    SENSOR_ODOMETER, SENSOR_SPEED, SENSOR_GEAR, SENSOR_IGNITION,
                    SENSOR_FUEL_WARNING, SENSOR_RANGE, SENSOR_FUEL_PCT,
                    SENSOR_DOOR_LB, SENSOR_DOOR_RB, SENSOR_DOOR_LR, SENSOR_DOOR_RR,
                    SENSOR_TRUNK_IN, SENSOR_DOOR_TRUNK_OUT
            };
            for (int t : hardcoded) registeredSensors.add(t);
            bootLog("[传感器] 合并硬编码清单后共 " + registeredSensors.size() + " 个待注册");

            Method register = sensorManager.getClass()
                    .getMethod("registerListener", listenerClass, int.class, int.class);
            Method getLatest = sensorManager.getClass()
                    .getMethod("getLatestSensorEvent", int.class);

            int ok = 0, latestOk = 0;
            for (int type : registeredSensors) {
                String name = sensorName(type);
                try {
                    Object regResult = register.invoke(sensorManager, sensorListener,
                            type, Integer.valueOf(1));
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
                    bootLog("[传感器] " + name + "(" + type + ") 注册失败: " + rootCause(e));
                }
            }
            bootLog("[传感器] 注册完成: 成功" + ok + "/" + registeredSensors.size()
                    + ", 有当前值" + latestOk + "/" + registeredSensors.size());
        } catch (Throwable e) {
            bootLog("[传感器] 注册流程异常:\n" + stackTrace(e));
        }
    }

    private static String sensorName(int type) {
        String n = SENSOR_NAMES.get(type);
        if (n != null) return n;
        String g = SENSOR_GUESS.get(type);
        return g != null ? "未知" + type + "(" + g + ")" : "未知" + type;
    }

    private void cacheSensorEvent(Object ev) {
        if (ev == null) return;
        try {
            java.lang.reflect.Field typeField = ev.getClass().getField("sensorType");
            int type = typeField.getInt(ev);
            String summary = eventSummary(ev);
            if (firstDataLogged.add(type)) {
                bootLog("[数据] 首次收到 sensor type=" + type + ": " + summary);
            }
            if (MONITORED_TYPES.contains(type)) {
                String old = lastSensorValue.get(type);
                if (old == null || !old.equals(summary)) {
                    if (old != null) {
                        bootLog("[传感器] " + sensorName(type) + "(" + type + ") 变化: " + old + " → " + summary);
                    }
                    lastSensorValue.put(type, summary);
                }
            }
            switch (type) {
                case SENSOR_ODOMETER: evOdometer = ev; break;
                case SENSOR_SPEED: evSpeed = ev; break;
                case SENSOR_GEAR: evGear = ev; break;
                case SENSOR_RANGE: evRange = ev; break;
                case SENSOR_FUEL_PCT: evFuelPct = ev; break;
                case SENSOR_FUEL_WARNING: evFuelWarn = ev; break;
                case SENSOR_IGNITION: evIgnition = ev; break;
                case SENSOR_OIL_LIFE: evOilLife = ev; break;
                case SENSOR_DOOR_LB: evDoorLB = ev; break;
                case SENSOR_DOOR_RB: evDoorRB = ev; break;
                case SENSOR_DOOR_LR: evDoorLR = ev; break;
                case SENSOR_DOOR_RR: evDoorRR = ev; break;
                case SENSOR_TRUNK_IN: evTrunkIn = ev; break;
                case SENSOR_DOOR_TRUNK_OUT: evTrunkOut = ev; break;
                case SENSOR_TURN_LEFT: evTurnLeft = ev; break;
                case SENSOR_TURN_RIGHT: evTurnRight = ev; break;
                default: evOthers.put(type, ev); break;
            }
        } catch (Throwable ignored) {}
    }

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

    // ========== 胎压 + 里程单位 ==========

    private void registerVendorCallback() {
        try {
            Class<?> cbClass = Class.forName(
                    "android.car.hardware.CarVendorExtensionManager$CarVendorExtensionCallback");
            vendorCallback = Proxy.newProxyInstance(cbClass.getClassLoader(),
                    new Class<?>[]{cbClass},
                    (proxy, method, args) -> {
                        String m = method.getName();
                        if ("hashCode".equals(m)) return System.identityHashCode(proxy);
                        if ("equals".equals(m)) return proxy == args[0];
                        if ("toString".equals(m)) return "VendorCallbackProxy";
                        if ("onChangeEvent".equals(m) && args != null && args.length > 0) {
                            handleVendorEvent(args[0]);
                        }
                        if ("onErrorEvent".equals(m) && args != null && args.length >= 2) {
                            tireLog("[胎压] VendorExtension onErrorEvent propId=" + args[0] + " areaId=" + args[1]);
                        }
                        Class<?> rt = method.getReturnType();
                        if (rt == boolean.class) return Boolean.FALSE;
                        if (rt == int.class) return 0;
                        if (rt == long.class) return 0L;
                        if (rt == float.class) return 0f;
                        if (rt == double.class) return 0d;
                        return null;
                    });
            Method register = vendorManager.getClass().getMethod("registerCallback", cbClass);
            register.invoke(vendorManager, vendorCallback);
            tireLog("[胎压] VendorExtension 回调注册OK");
        } catch (Throwable e) {
            bootLog("[胎压] VendorExtension 回调注册失败: " + rootCause(e));
        }
    }

    private void handleVendorEvent(Object carPropertyValue) {
        try {
            Method getPropId = carPropertyValue.getClass().getMethod("getPropertyId");
            int propId = (Integer) getPropId.invoke(carPropertyValue);
            if (propId == PROP_TIRE_STATE || propId == PROP_TIRE_PRESSURE) {
                tireLog("[胎压] 收到推送 propId=0x" + Integer.toHexString(propId) + "，重读四轮");
                handler.post(() -> readTireData());
            } else if (propId == PROP_MILEAGE_UNIT) {
                Object v = carPropertyValue.getClass().getMethod("getValue").invoke(carPropertyValue);
                mileageUnit = Integer.valueOf(1).equals(v) ? "mi" : "km";
                tireLog("[胎压] 里程单位推送 propId=557843475 → " + mileageUnit);
                publish();
            } else {
                tireLog("[胎压] 其他推送 propId=" + propId + "（0x" + Integer.toHexString(propId) + "），忽略");
            }
        } catch (Throwable e) {
            tireLog("[胎压] VendorExtension 回调处理失败: " + rootCause(e));
        }
    }

    private void readTireData() {
        tireLog("[胎压] ==== 读取胎压（VendorExtension）====");
        Method getProperty;
        try {
            getProperty = vendorManager.getClass()
                    .getMethod("getProperty", Class.class, int.class, int.class);
        } catch (Throwable e) {
            tireLog("[胎压] getProperty 方法不存在:\n" + stackTrace(e));
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
                tireLog("[胎压] " + labels[i] + " state=" + state + " pressure=" + pressure + " temp=" + temp);
            } catch (Throwable e) {
                tireLog("[胎压] " + labels[i] + " 读取失败: " + rootCause(e));
            }
        }
        try {
            Object unit = getProperty.invoke(vendorManager, Integer.class, PROP_MILEAGE_UNIT, 0);
            tireLog("[车况] 里程单位(557843475)=" + unit);
            mileageUnit = Integer.valueOf(1).equals(unit) ? "mi" : "km";
        } catch (Throwable e) {
            tireLog("[车况] 里程单位读取失败: " + rootCause(e));
        }
        tireLog("[胎压] 刷新（四轮=" + tireData.size() + "/4）");
        publish();
    }

    // ========== HVAC ==========

    private void readHvacCcm() {
        bootLog("[HVAC] ==== 枚举 HVAC 属性（getPropertyList）====");

        java.util.List<?> cfgList = null;
        String[] listMethodNames = {"getPropertyList", "getPropertyConfigs", "getHvacPropertiesList", "getProperties"};
        for (String mn : listMethodNames) {
            try {
                Method getList = hvacManager.getClass().getMethod(mn);
                Object list = getList.invoke(hvacManager);
                bootLog("[HVAC] " + mn + "() 返回 " + (list == null ? "null" : list.getClass().getName()));
                if (list instanceof List && !((List<?>) list).isEmpty()) {
                    cfgList = (List<?>) list;
                    break;
                }
            } catch (Throwable e) {
                bootLog("[HVAC] " + mn + " 不存在或失败: " + rootCause(e));
            }
        }

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
                        hvacProps.put(propId, "area" + area + "=" + vs);
                    }
                    bootLog("[HVAC] " + hvacPropName(propId) + " area=" + area + " → " + vs);
                }
            }

            restoreHvacTempFromSP();
        }

        try {
            Method getInt = hvacManager.getClass().getMethod("getIntProperty", int.class, int.class);
            Object v = getInt.invoke(hvacManager, 16387, 8);
            aarHvacCcm = v instanceof Integer ? (Integer) v : -1;
            bootLog("[HVAC] CCM(16387,8)=" + aarHvacCcm + "（保底读取）");
            if (!hvacProps.containsKey(16387)) hvacProps.put(16387, "area8=" + aarHvacCcm);
        } catch (Throwable e) {
            bootLog("[HVAC] CCM 保底读取失败: " + rootCause(e));
        }

        if (hvacProps.isEmpty() || hvacProps.size() < 5) {
            blindScanHvac();
        }

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
            initHvacToastBaseline();
        } catch (Throwable e) {
            bootLog("[HVAC] 回调注册失败: " + rootCause(e));
        }
    }

    private Integer extractHvacPropId(Object cfg, boolean dumpIfFail) {
        String[] getters = {"getPropId", "getId", "getPropertyId", "getHvacPropId", "getType"};
        for (String g : getters) {
            try {
                Method m = cfg.getClass().getMethod(g);
                Object v = m.invoke(cfg);
                if (v instanceof Integer) return (Integer) v;
            } catch (Throwable ignored) {}
        }
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
        return null;
    }

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
                if (v instanceof List) {
                    StringBuilder as = new StringBuilder();
                    for (Object a : (List<?>) v) as.append(a).append(' ');
                    return as.toString().trim();
                }
            } catch (Throwable ignored) {}
        }
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
        return "0 8";
    }

    private void blindScanHvac() {
        bootLog("[HVAC] ==== 盲扫候选 ID ====");
        int hit = 0;
        int[] stdIds = {
                358614274, 358614275,
                356517120, 356517121,
                354419973, 354419984,
                356517135,
                291505923,
        };
        int[] vendorIds = new int[32];
        for (int i = 0; i < 32; i++) vendorIds[i] = 16384 + i;
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
        bootLog("[HVAC] 盲扫完成: 命中 " + hit + " 个");
    }

    private String readHvacPropQuiet(int propId, int area) {
        Object v = readHvacValueQuiet(propId, area);
        if (v == null) return null;
        return v instanceof Float ? ((Float) v) + "f" : String.valueOf(v);
    }

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
        return intVal;
    }

    private static String hvacPropName(int propId) {
        String n = HVAC_PROP_NAMES.get(propId);
        return n != null ? n : "HVAC属性" + propId;
    }

    private void handleHvacEvent(Object carPropertyValue) {
        try {
            Method getProp = carPropertyValue.getClass().getMethod("getPropertyId");
            Method getArea = carPropertyValue.getClass().getMethod("getAreaId");
            Method getVal = carPropertyValue.getClass().getMethod("getValue");
            int prop = (Integer) getProp.invoke(carPropertyValue);
            int area = (Integer) getArea.invoke(carPropertyValue);
            Object v = getVal.invoke(carPropertyValue);
            String vs = v == null ? "null" : v.toString();

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
            if (prop == 16387 || prop == 16388 || prop == 16393 || prop == 16395 || prop == 20481) {
                hvacLog("[HVAC] 推送 " + hvacPropName(prop) + " area=" + area + " → " + vs);
            }

            // 通知 Activity（HVAC Toast 动画）
            final int fProp = prop, fArea = area;
            final String fVs = vs;
            if (listener != null) {
                handler.post(() -> {
                    if (listener != null) {
                        listener.onHvacChanged(fProp, fArea, fVs);
                    }
                });
            }

            if (prop == 16387 && area == 8) {
                aarHvacCcm = v instanceof Integer ? (Integer) v : parseHvacInt(vs);
                computeAarState();
            }
            if (prop == 16385) {
                try {
                    float tv = v instanceof Float ? (Float) v : Float.parseFloat(vs.replace("f", ""));
                    if (tv > 0 && tv < 31.5f) {
                        getSharedPreferences(PREF_UI, MODE_PRIVATE)
                                .edit().putString(KEY_HVAC_TEMP, vs).apply();
                    }
                } catch (Throwable ignored) {}
            }
            publish();
        } catch (Throwable e) {
            bootLog("[HVAC] 推送解析失败: " + rootCause(e));
        }
    }

    private void initHvacToastBaseline() {
        lastCcm = baselineFromCacheOrEcu(16387, 8);
        lastFanLevel = baselineFromCacheOrEcu(16388, 0);
        if (lastFanLevel == null) lastFanLevel = baselineFromCacheOrEcu(16388, 8);
        lastAc = baselineFromCacheOrEcu(16393, 8);
        lastRecycle = baselineFromCacheOrEcu(16395, 8);
    }

    private Integer baselineFromCacheOrEcu(int propId, int area) {
        Integer v = hvacAreaInt(propId, area);
        if (v != null) return v;
        try {
            Method getInt = hvacManager.getClass().getMethod("getIntProperty", int.class, int.class);
            Object r = getInt.invoke(hvacManager, propId, area);
            if (r instanceof Integer) {
                int val = (Integer) r;
                String tag = "area" + area + "=" + val;
                String old = hvacProps.get(propId);
                hvacProps.put(propId, old == null ? tag : old + ";" + tag);
                return val;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private Integer hvacAreaInt(int propId, int wantArea) {
        String s = hvacProps.get(propId);
        if (s == null) return null;
        for (String part : s.split(";")) {
            String p = part.trim();
            if (p.startsWith("area" + wantArea + "=")) {
                String val = p.substring(p.indexOf('=') + 1).replace("f", "");
                try {
                    return Integer.parseInt(val);
                } catch (NumberFormatException e) {
                    try {
                        return (int) Float.parseFloat(val);
                    } catch (NumberFormatException e2) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    private Float hvacAreaFloat(int propId, int wantArea) {
        String s = hvacProps.get(propId);
        if (s == null) return Float.MIN_VALUE;
        for (String part : s.split(";")) {
            String p = part.trim();
            if (p.startsWith("area" + wantArea + "=")) {
                String val = p.substring(p.indexOf('=') + 1).replace("f", "");
                try {
                    return Float.parseFloat(val);
                } catch (NumberFormatException e) {
                    return Float.MIN_VALUE;
                }
            }
        }
        return Float.MIN_VALUE;
    }

    private int parseHvacInt(String v) {
        if (v == null) return -1;
        try { return Integer.parseInt(v.replace("f", "")); } catch (NumberFormatException e) { return -1; }
    }

    private void restoreHvacTempFromSP() {
        try {
            String saved = getSharedPreferences(PREF_UI, MODE_PRIVATE).getString(KEY_HVAC_TEMP, null);
            if (saved == null) return;
            StringBuilder sb = new StringBuilder();
            for (String tag : saved.split(";")) {
                int eq = tag.indexOf('=');
                if (eq <= 0) continue;
                String areaStr = tag.substring(0, eq);
                String val = tag.substring(eq + 1);
                float ecuVal = -1f;
                String cur = hvacProps.get(16385);
                if (cur != null) {
                    for (String part : cur.split(";")) {
                        if (part.startsWith(areaStr + "=")) {
                            try { ecuVal = Float.parseFloat(part.substring(part.indexOf('=') + 1).replace("f", "")); } catch (Throwable ignored2) {}
                            break;
                        }
                    }
                }
                float memVal = Float.parseFloat(val.replace("f", ""));
                if (ecuVal >= 31.5f || cur == null) {
                    if (sb.length() > 0) sb.append(";");
                    sb.append(areaStr).append("=").append(val);
                } else {
                    if (sb.length() > 0) sb.append(";");
                    sb.append(areaStr).append("=").append(ecuVal).append("f");
                }
            }
            if (sb.length() > 0) {
                hvacProps.put(16385, sb.toString());
                hvacLog("[HVAC] 温度记忆恢复: " + sb.toString());
            }
        } catch (Throwable ignored) {}
    }

    // ========== 智能新风 / AAR ==========

    private void readAarData() {
        bootLog("[新风] ==== 读取智能新风/AAR（vendor_yfsetting）====");
        try {
            Class<?> mgrClass = yfManager.getClass();
            Method getProp = mgrClass.getMethod("getGlobalProperty", Class.class, int.class);

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

            aarPmNow = readYfInt(getProp, YF_PM_NOW, "车内PM2.5");
            aarPmDiag = readYfInt(getProp, YF_PM_DIAG, "PM传感器诊断");
            aarSensorMiss = readYfInt(getProp, YF_SENSOR_MISS, "PM传感器缺失");
            aarPowerBtn = readYfInt(getProp, YF_CCM_POWER, "CCM功率按钮");
            winDriver = readYfInt(getProp, YF_WIN_DRIVER, "司机车窗");
            winPassenger = readYfInt(getProp, YF_WIN_PASSENGER, "副驾车窗");
            winLeftRear = readYfInt(getProp, YF_WIN_LEFT_REAR, "左后车窗");
            winRightRear = readYfInt(getProp, YF_WIN_RIGHT_REAR, "右后车窗");
            for (int i = 0; i < YF_PM_HISTORY.length; i++) {
                aarPmHistory[i] = readYfInt(getProp, YF_PM_HISTORY[i], "PM历史" + (i * 2 + 2) + "点");
            }

            try {
                Method setProp = mgrClass.getMethod("setGlobalProperty", Class.class, int.class, Object.class);
                byte[] req = {1, 0x09, 0x08, (byte) 0xF0, 0x00, 0x04};
                setProp.invoke(yfManager, byte[].class, YF_TIRE_UNIT_REQ, req);
                bootLog("[新风] 胎压单位请求已发送(292565248)");
            } catch (Throwable e) {
                bootLog("[新风] 胎压单位请求失败: " + rootCause(e));
            }

            computeAarState();
        } catch (Throwable e) {
            bootLog("[新风] AAR 读取流程异常:\n" + stackTrace(e));
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
                    bootLog("[新风] 司机车窗推送=" + winDriver);
                    break;
                case YF_WIN_PASSENGER:
                    winPassenger = val instanceof Integer ? (Integer) val : -1;
                    bootLog("[新风] 副驾车窗推送=" + winPassenger);
                    break;
                case YF_WIN_LEFT_REAR:
                    winLeftRear = val instanceof Integer ? (Integer) val : -1;
                    bootLog("[新风] 左后车窗推送=" + winLeftRear);
                    break;
                case YF_WIN_RIGHT_REAR:
                    winRightRear = val instanceof Integer ? (Integer) val : -1;
                    bootLog("[新风] 右后车窗推送=" + winRightRear);
                    break;
                default:
                    if (val instanceof byte[]) {
                        byte[] b = (byte[]) val;
                        if (b.length > 3 && (((b[0] & 0xFF) << 8) | (b[1] & 0xFF)) == 2312) {
                            int unit = ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
                            String u = unit == 0 ? "psi" : unit == 1 ? "kPa" : unit == 2 ? "bar" : "?" + unit;
                            tireUnit = u;
                            bootLog("[新风] 胎压单位推送: " + u);
                        }
                    }
                    break;
            }
            computeAarState();
            publish();
        } catch (Throwable e) {
            bootLog("[新风] YF 推送解析失败: " + rootCause(e));
        }
    }

    private void computeAarState() {
        if (aarPmNow < 0) return;
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

    private boolean aarFilterOff(int q, int r, int s, int t, int u) {
        if (q != 0) return false;
        if (r != 2 && r != 0) return false;
        if (s == 1) return true;
        if (s != 0) return false;
        if (t > 0 && t <= 509) return true;
        if (t == 510 || t == 511) return u == 4 || u == 0 || u == 5;
        return false;
    }

    private boolean aarFilterOn(int q, int r, int s, int t, int u) {
        if (q != 0 || r != 1) return false;
        if (s == 1) return true;
        if (s != 0) return false;
        if (t > 35 && t <= 509) return true;
        if (t == 510 || t == 511) return u == 4 || u == 0 || u == 5;
        return false;
    }

    private boolean aarFilterDone(int q, int r, int s, int t, int u) {
        return q == 0 && r == 1 && s == 0 && t <= 35;
    }

    private boolean aarFilterErr(int q, int r, int s, int t, int u, int prevState) {
        if (q != 0 || r != 1) {
            return q == 1 && r != 0 && prevState == 1;
        }
        if (t == 510 || t == 511) {
            return u == 3 || u == 6 || u == 2;
        }
        return false;
    }

    // ========== 车辆信息 ==========

    private void readCarInfo() {
        bootLog("[车况] ==== 读取车辆信息 ====");
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
            } catch (Throwable e) {
                bootLog("[车况] " + c[0] + " 读取失败: " + rootCause(e));
            }
        }
        try {
            Method getString = infoManager.getClass().getMethod("getStringProperty", int.class);
            Object identify = getString.invoke(infoManager, PROP_CAR_IDENTIFY);
            bootLog("[车况] 车辆标识(117440591): " + identify);
        } catch (Throwable e) {
            bootLog("[车况] 车辆标识读取失败: " + rootCause(e));
        }
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
                    }
                } catch (Throwable e) {
                    bootLog("[车况] " + names[i] + " 读取失败: " + rootCause(e));
                }
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

    // ========== publish()：数据汇聚出口 ==========

    /**
     * 数据变化时调用：
     * 1. 通知已注册的 VehicleDataListener（Activity 在前台时刷新 UI）
     * 2. 广播给乐酷驾驶（指纹+300ms节流）
     */
    private void publish() {
        float odometer = getFloat(evOdometer);
        float speed = getFloat(evSpeed);
        int gear = getInt(evGear);
        float range = getFloat(evRange);
        float fuelPct = getFloat(evFuelPct);
        int fuelWarn = getInt(evFuelWarn);
        int oilLife = getInt(evOilLife);
        int ignition = getInt(evIgnition);

        // 1. 通知 Activity
        if (listener != null) {
            final VehicleData snapshot = getSnapshot();
            handler.post(() -> {
                if (listener != null) listener.onVehicleDataChanged(snapshot);
            });
        }

        // 2. 广播给乐酷驾驶
        sendVehicleBroadcast(odometer, speed, gear, range, fuelPct, fuelWarn, oilLife, ignition);
    }

    private void sendVehicleBroadcast(float odometer, float speed, int gear,
                                      float range, float fuelPct, int fuelWarn,
                                      int oilLife, int ignition) {
        if (speed < 0 && gear < 0 && odometer < 0 && range < 0) return;
        long now = SystemClock.elapsedRealtime();
        StringBuilder fp = new StringBuilder(128);
        fp.append((int) (speed * 10)).append(',').append(gear).append(',')
          .append((int) (odometer * 10)).append(',').append((int) (range * 10)).append(',')
          .append((int) (fuelPct * 10)).append(',').append(fuelWarn).append(',')
          .append(oilLife).append(',').append(ignition).append('|');
        for (int area : TIRE_AREAS) {
            TireData td = tireData.get(area);
            fp.append(td == null ? "-" : (int) td.pressure + "/" + (int) td.temp + "/" + td.state).append(';');
        }
        fp.append(tireUnit).append('|');
        for (HashMap.Entry<Integer, String> e : hvacProps.entrySet()) {
            fp.append(e.getKey()).append('=').append(e.getValue()).append(';');
        }
        fp.append('|');
        fp.append(getInt(evTurnLeft)).append(',').append(getInt(evTurnRight)).append('|');
        fp.append(getInt(evDoorLB)).append(',').append(getInt(evDoorRB)).append(',')
          .append(getInt(evDoorLR)).append(',').append(getInt(evDoorRR)).append(',')
          .append(getInt(evTrunkIn)).append(',').append(getInt(evTrunkOut)).append('|');
        fp.append(winDriver).append(',').append(winPassenger).append(',')
          .append(winLeftRear).append(',').append(winRightRear).append('|');
        fp.append(aarPmNow).append('|');
        String fingerprint = fp.toString();
        if (fingerprint.equals(lastVehicleBcFingerprint)) return;
        if (now - lastVehicleBcTime < VEHICLE_BC_MIN_INTERVAL) return;
        lastVehicleBcTime = now;
        lastVehicleBcFingerprint = fingerprint;

        Intent intent = new Intent(ACTION_VEHICLE_DATA);
        intent.setPackage(TARGET_PKG);
        intent.putExtra("speed", speed);
        intent.putExtra("gear", gear);
        intent.putExtra("odometer", odometer);
        intent.putExtra("range", range);
        intent.putExtra("mileageUnit", mileageUnit);
        intent.putExtra("fuelPct", fuelPct);
        intent.putExtra("fuelWarn", fuelWarn);
        intent.putExtra("oilLife", oilLife);
        intent.putExtra("ignition", ignition);
        intent.putExtra("turnLeft", getInt(evTurnLeft));
        intent.putExtra("turnRight", getInt(evTurnRight));
        for (int i = 0; i < TIRE_AREAS.length; i++) {
            TireData td = tireData.get(TIRE_AREAS[i]);
            String p = "tire" + i;
            if (td != null) {
                intent.putExtra(p + "Pressure", td.pressure);
                intent.putExtra(p + "Temp", td.temp);
                intent.putExtra(p + "State", td.state);
            }
        }
        intent.putExtra("tireUnit", tireUnit);
        intent.putExtra("doorLB", getInt(evDoorLB));
        intent.putExtra("doorRB", getInt(evDoorRB));
        intent.putExtra("doorLR", getInt(evDoorLR));
        intent.putExtra("doorRR", getInt(evDoorRR));
        intent.putExtra("trunkIn", getInt(evTrunkIn));
        intent.putExtra("trunkOut", getInt(evTrunkOut));
        intent.putExtra("winDriver", winDriver);
        intent.putExtra("winPassenger", winPassenger);
        intent.putExtra("winLeftRear", winLeftRear);
        intent.putExtra("winRightRear", winRightRear);
        intent.putExtra("tempDriver", hvacAreaFloat(16385, 1));
        intent.putExtra("tempPassenger", hvacAreaFloat(16385, 4));
        intent.putExtra("acPower", hvacAreaInt(16387, 8) != null ? hvacAreaInt(16387, 8) : -1);
        intent.putExtra("fanLevel", hvacAreaInt(16388, 0) != null ? hvacAreaInt(16388, 0) : -1);
        intent.putExtra("acRecycle", hvacAreaInt(16395, 8) != null ? hvacAreaInt(16395, 8) : -1);
        intent.putExtra("acOn", hvacAreaInt(16393, 8) != null ? hvacAreaInt(16393, 8) : -1);
        intent.putExtra("pm25", aarPmNow);
        sendBroadcast(intent);
    }

    // ========== 拉模式查询 ==========

    private void registerVehicleQueryReceiver() {
        vehicleQueryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!ACTION_VEHICLE_DATA_QUERY.equals(intent.getAction())) return;
                bootLog("[数据桥] 收到乐酷查询 → 回发当前快照");
                lastVehicleBcFingerprint = "";
                lastVehicleBcTime = 0L;
                publish();
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_VEHICLE_DATA_QUERY);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                Method m = Context.class.getMethod("registerReceiver",
                        BroadcastReceiver.class, IntentFilter.class, int.class);
                m.invoke(this, vehicleQueryReceiver, filter, Context.RECEIVER_EXPORTED);
            } catch (Throwable e) {
                registerReceiver(vehicleQueryReceiver, filter);
            }
        } else {
            registerReceiver(vehicleQueryReceiver, filter);
        }
    }

    // ========== 清理 ==========

    private void cleanupCarConnections() {
        if (sensorManager != null && sensorListener != null) {
            try {
                Class<?> listenerClass = Class.forName(
                        "android.car.hardware.CarSensorManager$OnSensorChangedListener");
                Method unregister = sensorManager.getClass()
                        .getMethod("unregisterListener", listenerClass);
                unregister.invoke(sensorManager, sensorListener);
                bootLog("[清理] sensorListener 已注销");
            } catch (Throwable e) {
                bootLog("[清理] 注销 listener 失败: " + e);
            }
        }
        if (vendorManager != null && vendorCallback != null) {
            try {
                vendorManager.getClass().getMethod("unregisterCallback",
                        Class.forName("android.car.hardware.CarVendorExtensionManager$CarVendorExtensionCallback"))
                        .invoke(vendorManager, vendorCallback);
                bootLog("[清理] vendorCallback 已注销");
            } catch (Throwable e) {
                bootLog("[清理] 注销 vendorCallback 失败: " + e);
            }
        }
        if (yfManager != null && yfCallback != null) {
            try {
                yfManager.getClass().getMethod("unregisterCallback",
                        Class.forName("android.car.hardware.CarYFSettingManager$CarYFSettingCallback"))
                        .invoke(yfManager, yfCallback);
                bootLog("[清理] yfCallback 已注销");
            } catch (Throwable e) {
                bootLog("[清理] 注销 yfCallback 失败: " + e);
            }
        }
        if (hvacManager != null && hvacCallback != null) {
            try {
                hvacManager.getClass().getMethod("unregisterCallback",
                        Class.forName("android.car.hardware.hvac.CarHvacManager$CarHvacEventCallback"))
                        .invoke(hvacManager, hvacCallback);
                bootLog("[清理] hvacCallback 已注销");
            } catch (Throwable e) {
                bootLog("[清理] 注销 hvacCallback 失败: " + e);
            }
        }
        if (car != null) {
            try { car.getClass().getMethod("disconnect").invoke(car); bootLog("[清理] Car 已断开"); } catch (Throwable ignored) {}
        }
    }

    // ========== 日志 ==========

    private static void bootLog(String msg) {
        Log.i(TAG, msg);
    }

    private static void tireLog(String msg) {
        Log.i(TAG, msg);
        writeAppend(BOOT_LOG, msg);
    }

    private static void hvacLog(String msg) {
        Log.i(TAG, msg);
        writeAppend(BOOT_LOG, msg);
    }

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
            return false;
        }
    }

    private static String stackTrace(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private static String rootCause(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t.getClass().getName() + ": " + t.getMessage();
    }
}
