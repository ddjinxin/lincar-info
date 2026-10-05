package com.jingxin.lincarinfo;

/**
 * Activity 向 Service 注册的数据回调接口。
 * onVehicleDataChanged：全量快照变化通知（传感器/_vendor/YF/mock 汇聚后）
 * onHvacChanged：HVAC 原始推送事件（Toast 动画需要 prop/area 粒度）
 */
public interface VehicleDataListener {
    void onVehicleDataChanged(VehicleData data);
    void onHvacChanged(int propId, int areaId, String value);
}
