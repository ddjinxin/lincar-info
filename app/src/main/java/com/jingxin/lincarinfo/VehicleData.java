package com.jingxin.lincarinfo;

/**
 * 车况数据快照 — Service 向 Activity 和乐酷驾驶暴露的全部数据。
 * 字段与 sendVehicleBroadcast 的 extras 对齐，用 -1 / Float.MIN_VALUE 表示无数据。
 */
public class VehicleData {
    // 横幅
    public float speed = -1f;        // km/h
    public int gear = -1;           // 0=P/1=N/2=R/3=D...
    public float odometer = -1f;    // km
    public float range = -1f;       // km
    public float fuelPct = -1f;     // %
    public int fuelWarn = -1;       // 1=预警
    public int oilLife = -1;        // %
    public int ignition = -1;       // 点火状态
    public String mileageUnit = "km";
    // 转向灯
    public int turnLeft = -1;       // 3=亮/1=灭/0=关
    public int turnRight = -1;
    // 胎压
    public float[] tirePressure = new float[]{-1f, -1f, -1f, -1f};
    public float[] tireTemp = new float[]{-1f, -1f, -1f, -1f};
    public int[] tireState = new int[]{-1, -1, -1, -1};
    public String tireUnit = "";
    // 门窗
    public int doorLB = -1, doorRB = -1, doorLR = -1, doorRR = -1;
    public int trunkIn = -1, trunkOut = -1;
    // 车窗
    public int winDriver = -1, winPassenger = -1, winLeftRear = -1, winRightRear = -1;
    // 空调
    public float tempDriver = Float.MIN_VALUE;   // ℃
    public float tempPassenger = Float.MIN_VALUE;
    public int acPower = -1;       // 1=开 2=关
    public int fanLevel = -1;
    public int acRecycle = -1;
    public int acOn = -1;
    // 新风
    public int pm25 = -1;          // μg/m³
    // 状态
    public boolean hasRealData = false;
}
