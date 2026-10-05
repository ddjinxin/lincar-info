package com.jingxin.lincarinfo;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.SpannableString;
import android.text.style.RelativeSizeSpan;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.HashMap;

/**
 * 纯 UI 外壳 — 所有数据逻辑由 VehicleDataService 承载。
 *
 * 绑定 Service 后通过 VehicleDataListener 回调刷新界面：
 *  - onVehicleDataChanged：全量快照 → updateDashboard
 *  - onHvacChanged：HVAC 推送 → showHvacToastIfNeeded
 *
 * Service 独立存活，Activity 退出后 Service 继续广播给乐酷驾驶。
 */
public class MainActivity extends Activity implements VehicleDataListener {

    private static final String TAG = "LinCarInfo";

    // ========== UI 控件 ==========
    private static final float DESIGN_HEIGHT_DP = 640f;

    private TextView tvGear, tvRange, tvOdo, tvOil, tvFuel, tvSpeed;
    private TextView tvRangeUnit, tvOdoUnit;
    private TextView tvTempDriver, tvPm, tvTempPassenger;

    private DoorCircleView tagLFDw, tagRFDw, tagLRDw, tagRRDw;
    private TireCircleView tagLFTire, tagRFTire, tagLRTire, tagRRTire;
    private DoorCircleView tagTrunk;
    private TurnSignalView turnLeft, turnRight;
    private HvacToastView hvacToast;
    private ImageView ivCar;
    private View carZone;
    private TagLineOverlay tagLines;

    // ========== 当前数据快照（Service 回调更新） ==========
    private VehicleData currentData;
    private int[] tireAreas = {1, 2, 4, 8};

    // ========== HVAC Toast 基线（变化检测弹动画） ==========
    private Integer lastCcm = null;
    private Integer lastFanLevel = null;
    private Integer lastAc = null;
    private Integer lastRecycle = null;

    // ========== Service 绑定 ==========
    private VehicleDataService dataService;
    private boolean serviceBound = false;
    private ServiceConnection serviceConnection;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // ========== 日夜模式 ==========
    private static final String PREF_UI = "ui";
    private static final String KEY_DAY_MODE = "day_mode";
    private static final String KEY_AMAP_TRIGGERED = "amap_triggered";
    static final String KEY_AUTO_START = "auto_start";
    static final int MODE_NIGHT = 0;
    static final int MODE_DAY = 1;
    private int uiModePref = MODE_NIGHT;
    private long lastAmapThemeTime = 0;
    private BroadcastReceiver amapThemeReceiver;
    // 模式切换接收（乐酷驾驶发 SET_MODE 广播，mode=1 时 Activity 自动退出）
    private BroadcastReceiver modeReceiver;

    // 高德导航日夜模式广播
    private static final String ACTION_AUTONAVI = "AUTONAVI_STANDARD_BROADCAST_SEND";
    private static final int KEY_TYPE_DAY_NIGHT = 10019;
    private static final int EXTRA_STATE_DAY = 37;
    private static final int EXTRA_STATE_NIGHT = 38;

    private android.content.SharedPreferences uiPrefs() {
        return getSharedPreferences(PREF_UI, MODE_PRIVATE);
    }

    // ========== Service 绑定 ==========

    private void bindDataService() {
        serviceConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                VehicleDataService.LocalBinder binder = (VehicleDataService.LocalBinder) service;
                dataService = binder.getService();
                serviceBound = true;
                dataService.registerListener(MainActivity.this);
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                dataService = null;
                serviceBound = false;
            }
        };
        Intent intent = new Intent(this, VehicleDataService.class);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
        // 同时 startService 确保 Service 独立存活（Activity 退出后不销毁）；
        // API 26+ 用 startForegroundService，Service 内部 startForeground 保活
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    // ========== VehicleDataListener 回调 ==========

    @Override
    public void onVehicleDataChanged(VehicleData data) {
        this.currentData = data;
        // 初始化 HVAC Toast 基线（首次拿到数据时）
        if (lastCcm == null && data.acPower >= 0) {
            lastCcm = data.acPower;
            lastFanLevel = data.fanLevel;
            lastAc = data.acOn;
            lastRecycle = data.acRecycle;
        }
        runOnUiThread(() -> {
            try { updateDashboard(); } catch (Throwable e) { Log.w(TAG, "[UI] updateDashboard 失败: " + e); }
        });
    }

    @Override
    public void onHvacChanged(int propId, int areaId, String value) {
        runOnUiThread(() -> showHvacToastIfNeeded(propId, areaId, value));
    }

    // ========== 生命周期 ==========

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(base);
        try {
            applyDayNightOverride(getResources());
        } catch (Throwable e) {
            Log.w(TAG, "日夜模式覆盖失败", e);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 全局崩溃捕获
        final Thread.UncaughtExceptionHandler def = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            Log.e(TAG, "*** 崩溃 *** 线程=" + thread.getName() + " " + throwable.getClass().getName());
            if (isBadTokenChain(throwable)) {
                restartProcess();
            } else {
                if (def != null) def.uncaughtException(thread, throwable);
            }
        });

        try {
            setContentView(R.layout.activity_main);
        } catch (Throwable e) {
            Log.e(TAG, "[UI] setContentView 失败: " + e);
            throw e;
        }

        initUi();

        // 绑定数据服务
        bindDataService();
    }

    @Override protected void onStart() { super.onStart(); }
    @Override protected void onResume() { super.onResume(); }
    @Override protected void onPause() { super.onPause(); }
    @Override protected void onStop() { super.onStop(); }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyFontScale();
        safeLoadCarImage();
        clearShadow(findViewById(R.id.root));
        scaleBannerByHeight();
        scaleAcCardByHeight();
        layoutCarTags();
        updateDashboard();
    }

    @Override
    protected void onDestroy() {
        if (serviceBound && serviceConnection != null) {
            if (dataService != null) dataService.unregisterListener(this);
            try { unbindService(serviceConnection); } catch (Throwable ignored) {}
            serviceBound = false;
        }
        unregisterAmapThemeReceiver();
        unregisterModeReceiver();
        super.onDestroy();
    }

    /** 监听乐酷驾驶的 SET_MODE 广播：mode=1（纯服务）时 Activity 自行退出，服务继续广播车况 */
    private void registerModeReceiver() {
        modeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null) return;
                String action = intent.getAction();
                if (action == null) return;
                if (ModeReceiver.ACTION_SET_MODE.equals(action)) {
                    int mode = intent.getIntExtra(ModeReceiver.EXTRA_MODE, -1);
                    if (mode == 1) {
                        Log.i(TAG, "[Mode] 收到纯服务模式切换，Activity 退出");
                        finish();
                    }
                }
            }
        };
        IntentFilter filter = new IntentFilter(ModeReceiver.ACTION_SET_MODE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                Method m = Context.class.getMethod("registerReceiver",
                        BroadcastReceiver.class, IntentFilter.class, int.class);
                m.invoke(this, modeReceiver, filter, Context.RECEIVER_EXPORTED);
            } catch (Throwable e) {
                registerReceiver(modeReceiver, filter);
            }
        } else {
            registerReceiver(modeReceiver, filter);
        }
    }

    private void unregisterModeReceiver() {
        if (modeReceiver != null) {
            try { unregisterReceiver(modeReceiver); } catch (Throwable ignored) {}
            modeReceiver = null;
        }
    }

    private static boolean pipSupported = true;

    @Override
    @SuppressWarnings("deprecation")
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (!pipSupported) return;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26 && checkPiPSupported()) {
                enterPictureInPictureMode();
            }
        } catch (Throwable e) {
            pipSupported = false;
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPiP, android.content.res.Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPiP, newConfig);
    }

    private boolean checkPiPSupported() {
        try {
            if (!getPackageManager().hasSystemFeature("android.software.picture_in_picture"))
                return false;
            android.content.pm.ActivityInfo ai = getPackageManager()
                    .getActivityInfo(new android.content.ComponentName(getPackageName(), getClass().getName()), 0);
            try {
                java.lang.reflect.Field f = android.content.pm.ActivityInfo.class.getField("supportsPictureInPicture");
                return f.getBoolean(ai);
            } catch (NoSuchFieldException nsf) {
                return true;
            }
        } catch (Throwable e) {
            return false;
        }
    }

    // ========== 日夜模式 ==========

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

    private void safeRecreate() {
        if (isFinishing() || isDestroyed()) return;
        recreate();
    }

    private static boolean isBadTokenChain(Throwable t) {
        while (t != null) {
            if (t instanceof android.view.WindowManager.BadTokenException) return true;
            t = t.getCause();
        }
        return false;
    }

    private void restartProcess() {
        try {
            Intent i = getBaseContext().getPackageManager()
                    .getLaunchIntentForPackage(getBaseContext().getPackageName());
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            }
        } catch (Throwable ignored) {}
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    private void toggleDayNight() {
        int next = uiModePref == MODE_DAY ? MODE_NIGHT : MODE_DAY;
        uiPrefs().edit()
                .putInt(KEY_DAY_MODE, next)
                .putBoolean(KEY_AMAP_TRIGGERED, false)
                .apply();
        safeRecreate();
    }

    private void toggleAutoStart() {
        boolean next = !uiPrefs().getBoolean(KEY_AUTO_START, false);
        uiPrefs().edit().putBoolean(KEY_AUTO_START, next).apply();
        android.widget.Toast.makeText(this,
                "开机自启：" + (next ? "开" : "关"), android.widget.Toast.LENGTH_SHORT).show();
    }

    private void registerAmapThemeReceiver() {
        amapThemeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!ACTION_AUTONAVI.equals(intent.getAction())) return;
                int keyType = intent.getIntExtra("KEY_TYPE", -1);
                if (keyType != KEY_TYPE_DAY_NIGHT) return;
                int extraState = intent.getIntExtra("EXTRA_STATE", -1);
                if (extraState != EXTRA_STATE_DAY && extraState != EXTRA_STATE_NIGHT) return;
                long now = System.currentTimeMillis();
                if (now - lastAmapThemeTime < 500) return;
                lastAmapThemeTime = now;
                boolean isNight = (extraState == EXTRA_STATE_NIGHT);
                int targetMode = isNight ? MODE_NIGHT : MODE_DAY;
                if (targetMode == uiModePref) return;
                uiPrefs().edit()
                        .putInt(KEY_DAY_MODE, targetMode)
                        .putBoolean(KEY_AMAP_TRIGGERED, true)
                        .apply();
                runOnUiThread(() -> safeRecreate());
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_AUTONAVI);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                Method m = Context.class.getMethod("registerReceiver",
                        BroadcastReceiver.class, IntentFilter.class, int.class);
                m.invoke(this, amapThemeReceiver, filter, Context.RECEIVER_EXPORTED);
            } catch (Throwable e) {
                registerReceiver(amapThemeReceiver, filter);
            }
        } else {
            registerReceiver(amapThemeReceiver, filter);
        }
        queryAmapDayNight();
    }

    private void queryAmapDayNight() {
        try {
            Intent query = new Intent("AUTONAVI_STANDARD_BROADCAST_GET");
            query.putExtra("KEY_TYPE", KEY_TYPE_DAY_NIGHT);
            sendBroadcast(query);
        } catch (Throwable ignored) {}
    }

    private void unregisterAmapThemeReceiver() {
        if (amapThemeReceiver != null) {
            try { unregisterReceiver(amapThemeReceiver); } catch (Throwable ignored) {}
            amapThemeReceiver = null;
        }
    }

    // ========== UI 初始化 ==========

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
        tagLFTire = findViewById(R.id.tagLFTire); tagRFTire = findViewById(R.id.tagRFTire);
        tagLRTire = findViewById(R.id.tagLRTire); tagRRTire = findViewById(R.id.tagRRTire);
        tagTrunk = findViewById(R.id.tagTrunk);
        ivCar = findViewById(R.id.ivCar);
        carZone = findViewById(R.id.carZone);
        tagLines = findViewById(R.id.tagLines);
        turnLeft = findViewById(R.id.turnLeft);
        turnRight = findViewById(R.id.turnRight);
        hvacToast = findViewById(R.id.hvacToast);
        View root = findViewById(R.id.root);
        if (root != null) {
            root.setOnClickListener(v -> toggleDayNight());
            root.setOnLongClickListener(v -> {
                toggleAutoStart();
                return true;
            });
        }
        registerAmapThemeReceiver();
        registerModeReceiver();
        safeLoadCarImage();
        applyFontScale();
        clearShadow(findViewById(R.id.root));
        scaleBannerByHeight();
        scaleAcCardByHeight();
        layoutCarTags();
    }

    private void clearShadow(View v) {
        if (v instanceof TextView) {
            ((TextView) v).setShadowLayer(0, 0, 0, 0);
        } else if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) clearShadow(g.getChildAt(i));
        }
    }

    // ========== 字号缩放 ==========

    private boolean isLandscape() {
        android.content.res.Configuration cfg = getResources().getConfiguration();
        return cfg.screenWidthDp > cfg.screenHeightDp * 1.2f;
    }

    private void applyFontScale() {
        try {
            android.content.res.Configuration cfg = getResources().getConfiguration();
            boolean landscape = isLandscape();
            float refDp = landscape ? cfg.screenWidthDp : cfg.screenHeightDp;
            float scale = Math.min(refDp / DESIGN_HEIGHT_DP, 1.5f);
            scale = Math.max(scale, 0.5f);
            View root = findViewById(R.id.root);
            scaleTextSize(root, scale);
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
            Log.w(TAG, "[UI] applyFontScale 失败: " + e);
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
                        } else {
                            float valSize = bh * 0.42f;
                            float lblSize = bh * 0.15f;
                            scaleBannerText(banner, valSize, lblSize);
                            fitBannerWidth(banner, bw);
                        }
                    } catch (Throwable e) {
                        banner.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                    }
                }
            });
    }

    private void fitBannerWidth(View banner, int bannerWidth) {
        if (!(banner instanceof android.view.ViewGroup)) return;
        android.view.ViewGroup group = (android.view.ViewGroup) banner;
        int contentWidth = 0;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            child.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            android.widget.LinearLayout.LayoutParams lp = (android.widget.LinearLayout.LayoutParams) child.getLayoutParams();
            contentWidth += child.getMeasuredWidth() + lp.leftMargin + lp.rightMargin;
        }
        if (contentWidth <= bannerWidth) return;
        float ratio = (float) bannerWidth / contentWidth;
        scaleBannerTextByRatio(banner, ratio);
        scaleBannerMarginByRatio(group, ratio);
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

    private void scaleBannerText(View v, float valSize, float lblSize) {
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            int id = tv.getId();
            float size;
            if (id == R.id.tvRangeUnit || id == R.id.tvOdoUnit) {
                size = valSize * 0.5f;
            } else if (id != View.NO_ID) {
                size = valSize;
            } else {
                size = lblSize;
            }
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
        } else if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) scaleBannerText(g.getChildAt(i), valSize, lblSize);
        }
    }

    private void fitAcCardWidth(View acCard) {
        if (!(acCard instanceof android.view.ViewGroup)) return;
        android.view.ViewGroup cardOuter = (android.view.ViewGroup) acCard;
        if (cardOuter.getChildCount() == 0) return;
        View cardRow = cardOuter.getChildAt(0);
        if (!(cardRow instanceof android.view.ViewGroup)) return;
        android.view.ViewGroup row = (android.view.ViewGroup) cardRow;
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (!(child instanceof android.view.ViewGroup)) continue;
            android.view.ViewGroup zone = (android.view.ViewGroup) child;
            int zoneW = zone.getWidth() - zone.getPaddingLeft() - zone.getPaddingRight();
            if (zoneW <= 0) continue;
            int maxTextW = 0;
            for (int j = 0; j < zone.getChildCount(); j++) {
                View tv = zone.getChildAt(j);
                if (tv instanceof TextView) {
                    tv.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
                    maxTextW = Math.max(maxTextW, ((TextView) tv).getMeasuredWidth());
                }
            }
            if (maxTextW > zoneW && maxTextW > 0) {
                float ratio = (float) zoneW / maxTextW;
                scaleBannerTextByRatio(zone, ratio);
            }
        }
    }

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
                            acCard.setPadding(dip(10), dip(1), dip(10), dip(1));
                            LinearLayout.LayoutParams acLp = (LinearLayout.LayoutParams) acCard.getLayoutParams();
                            acLp.bottomMargin = dip(3);
                            acCard.setLayoutParams(acLp);
                            android.view.ViewGroup cardOuter = (android.view.ViewGroup) acCard;
                            android.view.ViewGroup cardRow = (android.view.ViewGroup) cardOuter.getChildAt(0);
                            for (int i = 0; i < cardRow.getChildCount(); i++) {
                                View child = cardRow.getChildAt(i);
                                if (child instanceof LinearLayout) {
                                    LinearLayout zone = (LinearLayout) child;
                                    zone.setOrientation(LinearLayout.VERTICAL);
                                    zone.setPadding(dip(3), dip(8), dip(3), dip(8));
                                    for (int j = 0; j < zone.getChildCount(); j++) {
                                        View tv = zone.getChildAt(j);
                                        if (tv instanceof TextView) {
                                            ((TextView) tv).setPadding(0, 0, 0, 0);
                                        }
                                    }
                                }
                            }
                            android.view.ViewGroup zone = (android.view.ViewGroup) cardRow.getChildAt(0);
                            int zoneH = zone.getHeight();
                            if (zoneH > 0) {
                                int usable = zoneH - dip(16);
                                float textTotal = usable / 1.25f;
                                float valSize = textTotal * 0.7f;
                                float lblSize = textTotal * 0.3f;
                                scaleBannerText(acCard, valSize, lblSize);
                            }
                        } else {
                            float valSize = ah * 0.45f;
                            float lblSize = ah * 0.15f;
                            scaleBannerText(acCard, valSize, lblSize);
                            fitAcCardWidth(acCard);
                        }
                    } catch (Throwable e) {
                        acCard.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                    }
                }
            });
    }

    // ========== 标签定位 ==========

    private void layoutCarTags() {
        if (carZone == null || ivCar == null) return;
        carZone.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    int pass = 0;
                    @Override
                    public void onGlobalLayout() {
                        try {
                            boolean needRelayout = positionCarTags();
                            if (needRelayout && pass < 2) {
                                pass++;
                                carZone.requestLayout();
                            } else {
                                carZone.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                            }
                        } catch (Throwable e) {
                            carZone.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        }
                    }
                });
    }

    private boolean positionCarTags() {
        int zw = carZone.getWidth(), zh = carZone.getHeight();
        if (zw <= 0 || zh <= 0) return false;

        float tireEdgeX = zw * 0.14f;
        float dwEdgeX = zw * 0.06f;

        float[] r = carImgRect(zw, zh);
        float axisY = r[1] + r[3] * 0.525f;

        float carBodyLeft = r[0] + r[2] * 0.219f;
        float carBodyRight = r[0] + r[2] * 0.781f;
        float dwBaseDp = 90f;
        float density = getResources().getDisplayMetrics().density;
        float dwAvail = carBodyLeft - dwEdgeX;
        float widthScale = dwAvail / (dwBaseDp * density);
        widthScale = Math.max(widthScale, 0.3f);

        android.content.res.Configuration cfg = getResources().getConfiguration();
        float refDp = isLandscape() ? cfg.screenWidthDp : cfg.screenHeightDp;
        float heightScale = Math.min(refDp / 540f, 1f);
        heightScale = Math.max(heightScale, 0.5f);

        float finalScale = Math.min(heightScale, widthScale);
        boolean needRelayout = applyCircleScaleIfNeeded(finalScale);

        float tireTopY = r[1] - dip(6);
        placeCornerTag(tagLFTire, tireEdgeX, tireTopY, true, false);
        placeCornerTag(tagRFTire, zw - tireEdgeX, tireTopY, false, false);

        float cFL = tireTopY + (tagLFTire != null ? tagLFTire.getHeight() : 0) / 2f;
        float cRL = 2f * axisY - cFL;
        centerTagAt(tagLRTire, tireEdgeX, cRL, true);
        float stepL = (cRL - cFL) / 3f;
        centerTagAt(tagLFDw, dwEdgeX, cFL + stepL, true);
        centerTagAt(tagLRDw, dwEdgeX, cFL + stepL * 2f, true);

        float cFR = tireTopY + (tagRFTire != null ? tagRFTire.getHeight() : 0) / 2f;
        float cRR = 2f * axisY - cFR;
        centerTagAt(tagRRTire, zw - tireEdgeX, cRR, false);
        float stepR = (cRR - cFR) / 3f;
        centerTagAt(tagRFDw, zw - dwEdgeX, cFR + stepR, false);
        centerTagAt(tagRRDw, zw - dwEdgeX, cFR + stepR * 2f, false);

        if (tagTrunk != null) {
            int w = tagTrunk.getWidth(), h = tagTrunk.getHeight();
            android.widget.FrameLayout.LayoutParams lp =
                    (android.widget.FrameLayout.LayoutParams) tagTrunk.getLayoutParams();
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

        if (tvSpeed != null) {
            float carBodyTop = r[1] + r[3] * 0.114f;
            float gap = dip(15);
            float availH = carBodyTop - gap - gap;
            if (availH > 0) {
                float textSize = availH / 1.25f;
                float maxTextSize = 60f * getResources().getDisplayMetrics().density;
                textSize = Math.min(textSize, maxTextSize);
                tvSpeed.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize);
                tvSpeed.setHeight(Math.round(textSize * 1.25f));
                android.widget.FrameLayout.LayoutParams lp =
                        (android.widget.FrameLayout.LayoutParams) tvSpeed.getLayoutParams();
                lp.topMargin = Math.round(gap);
                tvSpeed.setLayoutParams(lp);
            }
        }

        if (turnLeft != null && turnRight != null && tvSpeed != null) {
            float carBodyTop = r[1] + r[3] * 0.114f;
            float gap = dip(15);
            float availH = carBodyTop - gap - gap;
            float arrowSize = availH > 0 ? availH * 0.8f : dip(30);
            float maxArrow = 60f * getResources().getDisplayMetrics().density * 1.25f * 0.8f;
            arrowSize = Math.min(arrowSize, maxArrow);
            int sizePx = Math.round(arrowSize);
            float speedTopY = gap;
            tvSpeed.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            int speedW = tvSpeed.getMeasuredWidth();
            float speedCenterX = zw / 2f;
            float offset = speedW / 2f + dip(8);
            android.widget.FrameLayout.LayoutParams lpL =
                    (android.widget.FrameLayout.LayoutParams) turnLeft.getLayoutParams();
            lpL.width = sizePx;
            lpL.height = sizePx;
            lpL.leftMargin = Math.round(speedCenterX - offset - sizePx);
            lpL.topMargin = Math.round(speedTopY - sizePx * 0.15f);
            turnLeft.setLayoutParams(lpL);
            android.widget.FrameLayout.LayoutParams lpR =
                    (android.widget.FrameLayout.LayoutParams) turnRight.getLayoutParams();
            lpR.width = sizePx;
            lpR.height = sizePx;
            lpR.leftMargin = Math.round(speedCenterX + offset);
            lpR.topMargin = Math.round(speedTopY - sizePx * 0.15f);
            turnRight.setLayoutParams(lpR);
            turnLeft.bringToFront();
            turnRight.bringToFront();
        }

        if (hvacToast != null) {
            float carBodyBottom = r[1] + r[3] * 0.887f;
            float zoneBottom = zh;
            float centerAreaH = zoneBottom - carBodyBottom;
            float toastH = centerAreaH * 0.6f;
            if (toastH > 0) {
                float textSize = toastH / 1.25f;
                hvacToast.setMaxTextSize(textSize);
                android.widget.FrameLayout.LayoutParams lpToast =
                        (android.widget.FrameLayout.LayoutParams) hvacToast.getLayoutParams();
                lpToast.width = zw;
                lpToast.height = Math.round(toastH);
                lpToast.leftMargin = 0;
                lpToast.topMargin = Math.round(zoneBottom - toastH);
                hvacToast.setLayoutParams(lpToast);
                hvacToast.bringToFront();
            }
        }

        linkTagDots();
        return needRelayout;
    }

    private boolean applyCircleScaleIfNeeded(float finalScale) {
        boolean changed = false;
        if (tagLFTire != null && finalScale < tagLFTire.getDpScale()) { tagLFTire.setDpScale(finalScale); changed = true; }
        if (tagRFTire != null && finalScale < tagRFTire.getDpScale()) { tagRFTire.setDpScale(finalScale); changed = true; }
        if (tagLRTire != null && finalScale < tagLRTire.getDpScale()) { tagLRTire.setDpScale(finalScale); changed = true; }
        if (tagRRTire != null && finalScale < tagRRTire.getDpScale()) { tagRRTire.setDpScale(finalScale); changed = true; }
        if (tagLFDw != null && finalScale < tagLFDw.getDpScale()) { tagLFDw.setDpScale(finalScale); changed = true; }
        if (tagRFDw != null && finalScale < tagRFDw.getDpScale()) { tagRFDw.setDpScale(finalScale); changed = true; }
        if (tagLRDw != null && finalScale < tagLRDw.getDpScale()) { tagLRDw.setDpScale(finalScale); changed = true; }
        if (tagRRDw != null && finalScale < tagRRDw.getDpScale()) { tagRRDw.setDpScale(finalScale); changed = true; }
        if (tagTrunk != null && finalScale < tagTrunk.getDpScale()) { tagTrunk.setDpScale(finalScale); changed = true; }
        return changed;
    }

    private float[] carImgRect(int zw, int zh) {
        float ratio = 1333f / 1000f;
        int mL = dip(30) + dip(6), mR = dip(30) + dip(6);
        int mT = dip(24) + dip(6), mB = dip(24) + dip(6);
        int availW = Math.max(1, zw - mL - mR);
        int availH = Math.max(1, zh - mT - mB);
        float fitW, fitH;
        if (availW * ratio >= availH) { fitH = availH; fitW = fitH / ratio; }
        else { fitW = availW; fitH = fitW * ratio; }
        float left = (zw - fitW) / 2f, top = (zh - fitH) / 2f;
        return new float[]{left, top, fitW, fitH};
    }

    private void linkTagDots() {
        if (tagLines == null || currentData == null) return;
        int zw = carZone.getWidth(), zh = carZone.getHeight();
        float[] r = carImgRect(zw, zh);
        float left = r[0], top = r[1], fitW = r[2], fitH = r[3];
        float bodyL = left + fitW * 0.269f, bodyR = left + fitW * 0.731f;
        float tireFrontY = top + fitH * 0.290f, tireRearY = top + fitH * 0.732f;
        float tireLX = left + fitW * 0.240f, tireRX = left + fitW * 0.758f;
        float doorFrontY = top + fitH * 0.460f, doorRearY = top + fitH * 0.590f;
        float tailX = left + fitW * 0.50f, tailY = top + fitH * 0.860f;

        tagLines.clear();
        // 胎压 4 点
        tagLines.setLink(tireLX, tireFrontY, tagLFTire, tireAlert(0), true);
        tagLines.setLink(tireRX, tireFrontY, tagRFTire, tireAlert(1), true);
        tagLines.setLink(tireLX, tireRearY, tagLRTire, tireAlert(2), true);
        tagLines.setLink(tireRX, tireRearY, tagRRTire, tireAlert(3), true);
        // 门窗 4 点
        tagLines.setLink(bodyL, doorFrontY, tagLFDw, doorAlert(currentData.doorLB, currentData.winDriver), false);
        tagLines.setLink(bodyR, doorFrontY, tagRFDw, doorAlert(currentData.doorRB, currentData.winPassenger), false);
        tagLines.setLink(bodyL, doorRearY, tagLRDw, doorAlert(currentData.doorLR, currentData.winLeftRear), false);
        tagLines.setLink(bodyR, doorRearY, tagRRDw, doorAlert(currentData.doorRR, currentData.winRightRear), false);
        // 尾部
        tagLines.setLink(tailX, tailY, tagTrunk, currentData.trunkIn > 0 || currentData.trunkOut > 0);
        tagLines.apply();
    }

    /** 胎压异常判定：<200kPa（无效值 65533 视为无数据不报警） */
    private boolean tireAlert(int idx) {
        if (currentData == null) return false;
        float p = currentData.tirePressure[idx];
        return p > 0 && p < 200f && p < 65533f;
    }

    /** 门窗异常判定：门开或窗未关 */
    private boolean doorAlert(int doorState, int winState) {
        return doorState > 0 || (winState >= 0 && winState != 1);
    }

    private void centerTagAt(View tag, float x, float centerY, boolean atLeft) {
        if (tag == null) return;
        int w = tag.getWidth(), h = tag.getHeight();
        android.widget.FrameLayout.LayoutParams lp =
                (android.widget.FrameLayout.LayoutParams) tag.getLayoutParams();
        lp.leftMargin = Math.round(atLeft ? x : x - w);
        lp.topMargin = Math.round(centerY - h / 2f);
        tag.setLayoutParams(lp);
    }

    private void placeCornerTag(View tag, float x, float y, boolean atLeft, boolean anchorBottom) {
        if (tag == null) return;
        int w = tag.getWidth(), h = tag.getHeight();
        android.widget.FrameLayout.LayoutParams lp =
                (android.widget.FrameLayout.LayoutParams) tag.getLayoutParams();
        if (atLeft) {
            lp.leftMargin = Math.round(x);
        } else {
            lp.leftMargin = Math.round(x - w);
        }
        if (anchorBottom) {
            lp.topMargin = Math.round(y - h);
        } else {
            lp.topMargin = Math.round(y);
        }
        tag.setLayoutParams(lp);
    }

    private int dip(int dp) {
        float d = getResources().getDisplayMetrics().density;
        return Math.round(dp * d);
    }

    // ========== 车图安全加载 ==========

    private void safeLoadCarImage() {
        if (ivCar == null) return;
        try {
            int carRes = R.drawable.car_top_sel;
            android.graphics.BitmapFactory.Options opt = new android.graphics.BitmapFactory.Options();
            opt.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeResource(getResources(), carRes, opt);
            int bw = opt.outWidth, bh = opt.outHeight;

            DisplayMetrics dm = getResources().getDisplayMetrics();
            float density = dm.density;
            int sampleSize = 1;
            while ((long)(bw / sampleSize) * (bh / sampleSize) * 4 * (long)(density) * (long)(density) > 80L * 1024 * 1024) {
                sampleSize *= 2;
            }

            opt = new android.graphics.BitmapFactory.Options();
            opt.inSampleSize = sampleSize;
            opt.inScaled = false;
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeResource(
                    getResources(), carRes, opt);
            if (bmp != null) {
                ivCar.setImageBitmap(bmp);
            }
        } catch (Throwable e) {
            Log.w(TAG, "[UI] 车图安全加载失败: " + e);
        }
    }

    // ========== 仪表盘刷新 ==========

    private void updateDashboard() {
        if (currentData == null) return;

        // ---- 顶部横幅 ----
        tvGear.setText(gearStr(currentData.gear));
        if (currentData.range < 0) {
            tvRange.setText("--");
        } else {
            tvRange.setText(String.format("%.0f", Math.max(currentData.range, 0)));
        }
        if (tvRangeUnit != null) tvRangeUnit.setText(currentData.mileageUnit);
        if (currentData.odometer < 0 || currentData.odometer == 1.6777215E7f) {
            tvOdo.setText("--");
        } else {
            tvOdo.setText(String.format("%.0f", currentData.odometer));
        }
        if (tvOdoUnit != null) tvOdoUnit.setText(currentData.mileageUnit);
        tvOil.setText(currentData.oilLife < 0 ? "--" : setUnitSmall(currentData.oilLife + "%", "%"));
        tvFuel.setText(currentData.fuelPct < 0 ? "--" : setUnitSmall(String.format("%.0f%%", Math.max(currentData.fuelPct, 0)), "%"));

        // ---- 速度 ----
        tvSpeed.setText(currentData.speed < 0 ? "--" : String.format("%.0f", Math.max(currentData.speed, 0)));

        // ---- 转向灯 ----
        if (turnLeft != null) turnLeft.update(currentData.turnLeft, true);
        if (turnRight != null) turnRight.update(currentData.turnRight, false);

        // ---- 四轮胎压 ----
        updateTireCircle(tagLFTire, 0);
        updateTireCircle(tagRFTire, 1);
        updateTireCircle(tagLRTire, 2);
        updateTireCircle(tagRRTire, 3);

        // ---- 门窗 ----
        updateDoorCircle(tagLFDw, currentData.doorLB, currentData.winDriver);
        updateDoorCircle(tagRFDw, currentData.doorRB, currentData.winPassenger);
        updateDoorCircle(tagLRDw, currentData.doorLR, currentData.winLeftRear);
        updateDoorCircle(tagRRDw, currentData.doorRR, currentData.winRightRear);
        boolean trunkOpen = currentData.trunkIn > 0 || currentData.trunkOut > 0;
        if (tagTrunk != null) tagTrunk.updateTrunk(trunkOpen);

        // ---- 空调卡 ----
        String tempDriver = formatTemp(currentData.tempDriver);
        String tempPassenger = formatTemp(currentData.tempPassenger);
        tvTempDriver.setText(tempDriver == null ? "--" : setUnitSmall(tempDriver + "℃", "℃"));
        tvTempPassenger.setText(tempPassenger == null
                ? (tempDriver == null ? "--" : setUnitSmall(tempDriver + "℃", "℃"))
                : setUnitSmall(tempPassenger + "℃", "℃"));
        tvPm.setText(currentData.pm25 >= 0 ? setUnitSmall(currentData.pm25 + " μg/m³", " μg/m³") : "--");
        tvPm.setTextColor(pmColor(currentData.pm25));

        linkTagDots();
    }

    private String formatTemp(float temp) {
        if (temp == Float.MIN_VALUE || temp <= 0) return null;
        return String.format("%.1f", temp);
    }

    private void updateTireCircle(TireCircleView v, int idx) {
        if (v == null) return;
        float pressure = currentData.tirePressure[idx];
        int state = currentData.tireState[idx];
        if (pressure < 0) {
            v.update("--", "kPa", TireCircleView.colorInvalid(this), false);
            return;
        }
        int color;
        boolean alert;
        if (pressure >= 65533f) {
            color = TireCircleView.colorInvalid(this); alert = false;
        } else if (state > 250) {
            color = TireCircleView.colorAlert(this); alert = true;
        } else if (state > 150 || state > 75) {
            color = TireCircleView.colorWarn(this); alert = false;
        } else {
            color = TireCircleView.colorOk(this); alert = false;
        }
        v.update(pressureNum(pressure),
                currentData.tireUnit.isEmpty() ? "--" : currentData.tireUnit, color, alert);
    }

    private String pressureNum(float raw) {
        if (raw >= 65533f) return "--";
        String unit = currentData != null ? currentData.tireUnit : "";
        if ("bar".equals(unit)) {
            float f = raw;
            if (f % 5.0f == 0.0f) f += 1.0f;
            return String.format("%.2f", f * 0.01f);
        }
        if ("psi".equals(unit)) return String.format("%.0f", raw * 0.14504f);
        if ("kPa".equals(unit)) {
            int v = (int) raw;
            int f2 = v % 10;
            if (f2 <= 2) return String.valueOf(v / 10 * 10);
            if (f2 >= 8) return String.valueOf((v / 10 + 1) * 10);
            return String.valueOf(v / 10 * 10 + 5);
        }
        return String.valueOf((int) raw);
    }

    private void updateDoorCircle(DoorCircleView v, int doorState, int winState) {
        if (v == null) return;
        boolean doorOpen = doorState > 0;
        boolean winOpen = winState >= 0 && winState != 1;
        v.update(doorOpen, winOpen);
    }

    // ========== HVAC Toast ==========

    private void showHvacToastIfNeeded(int prop, int area, String vs) {
        if (hvacToast == null) return;
        if (isFinishing() || isDestroyed()) return;
        try {
            int val = parseHvacInt(vs);
            if (prop == 16387 && area == 8) {
                if (lastCcm == null || lastCcm != val) {
                    if (lastCcm != null) {
                        if (val == 1) hvacToast.show("空调开");
                        else hvacToast.show("空调关");
                    }
                    lastCcm = val;
                }
            } else if (prop == 16388) {
                if (lastFanLevel == null || lastFanLevel != val) {
                    if (lastFanLevel != null && val > 0) {
                        hvacToast.show("风量" + val);
                    }
                    lastFanLevel = val;
                }
            } else if (prop == 16393 && area == 8) {
                if (lastAc == null || lastAc != val) {
                    if (lastAc != null) {
                        if (val == 1) hvacToast.show("制冷开");
                        else hvacToast.show("制冷关");
                    }
                    lastAc = val;
                }
            } else if (prop == 16395 && area == 8) {
                if (lastRecycle == null || lastRecycle != val) {
                    if (lastRecycle != null) {
                        if (val == 1) hvacToast.show("内循环开");
                        else hvacToast.show("内循环关");
                    }
                    lastRecycle = val;
                }
            }
        } catch (Throwable ignored) {}
    }

    private int parseHvacInt(String v) {
        if (v == null) return -1;
        try { return Integer.parseInt(v.replace("f", "")); } catch (NumberFormatException e) { return -1; }
    }

    // ========== 格式化辅助 ==========

    private SpannableString setUnitSmall(String fullText, String unit) {
        SpannableString span = new SpannableString(fullText);
        int idx = fullText.indexOf(unit);
        if (idx >= 0) {
            span.setSpan(new RelativeSizeSpan(0.5f), idx, idx + unit.length(),
                    SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return span;
    }

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

    private static int pmLevel(int pm) {
        if (pm < 0) return 0;
        if (pm <= 35) return 1;
        if (pm <= 75) return 2;
        if (pm <= 115) return 3;
        if (pm <= 150) return 4;
        if (pm <= 250) return 5;
        return pm <= 500 ? 6 : 0;
    }

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
            default: return v < 0 ? "--" : "值=" + v;
        }
    }
}
