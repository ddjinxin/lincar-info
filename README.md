---
AIGC:
  ContentProducer: '001191110102MAD55U9H0F10002'
  ContentPropagator: '001191110102MAD55U9H0F10002'
  Label: '1'
  ProduceID: 'ab62128b-2f54-4c37-b9e2-21b42deeecf6'
  PropagateID: 'ab62128b-2f54-4c37-b9e2-21b42deeecf6'
  ReservedCode1: 'a45bb30c-e564-4e5b-921e-1a60a34fe70a'
  ReservedCode2: 'a45bb30c-e564-4e5b-921e-1a60a34fe70a'
---

# LinCarInfo - 林肯车机信息

车载车况诊断应用，通过 Android Car 服务直读车机传感器数据，在首页一屏展示车辆实时状态。适配高分辨率车机，支持日夜双模式与分屏布局。

## 功能特性

- **车况诊断**：系统签名直读车型 / 传感器（车门、门窗、车灯状态）
- **胎压监测**：注册 VendorExtensionCallback 监听胎压属性变化自动刷新，冷启动延迟重试，解决 TPMS 未就绪时首次读取失败问题
- **空调状态提示**：HvacToastView 浮层提示空调开合状态动画
- **转向灯指示**：金黄闪烁转向灯效果
- **日夜双模式**：径向渐变背景 + 流光动画线，随高德导航广播自动切换
- **高分辨率适配**：UI 按屏高像素纯比例缩放，超宽屏宽高比修正，适配 1080P 及以上车机
- **分屏 / 多窗口**：按高度缩放，宽度不足自动兜底缩防止溢出

## 技术要点

- 系统签名（飞思 platform 签名）读取车机系统属性，需 `android.uid.system` 平台签名权限
- 使用车机系统内置 `android.car` 库，`compileOnly` 不打包进 APK
- 无第三方依赖，纯原生 Android 实现

## 环境要求

- Android Studio（JBR JDK）
- SDK：minSdk 23、targetSdk 34、Java 1.8
- 车机系统：Android 8.1 ~ 15

## 构建

```bash
./gradlew assembleDebug
```

编译产物为 debug 变体（signingConfig 指向 release），随后使用飞思签名工具对 APK 重新签名后安装至车机。

## 签名与安装

应用需以飞思平台签名安装到车机：

```bash
zipalign -f 4 app/build/outputs/apk/debug/app-debug.apk aligned.apk
apksigner sign --key 飞思.pk8 --cert 飞思.x509.pem aligned.apk
adb install -r aligned.apk
```

> 注意：使用 `adb install -r` 覆盖安装，勿卸载重装；桌面根目录的 APK 不会自动更新，编译后需重新复制。

## 目录结构

```
app/src/main/java/com/jingxin/lincarinfo/
├── MainActivity.java          # 纯 UI 外壳，绑定 Service 刷新仪表盘
├── VehicleDataService.java    # 车况数据服务（双模式：带界面/纯服务，前台保活）
├── VehicleData.java           # 车况数据快照（Service → Activity 回调载体）
├── VehicleDataListener.java   # 数据回调接口
├── ModeReceiver.java          # 运行模式切换广播（乐酷驾驶 SET_MODE）
├── BootReceiver.java          # 开机自启（按 run_mode 决定启动 Activity 或 Service）
├── UpdateHelper.java          # Gitee 在线更新
├── DoorCircleView.java        # 车门状态圆形视图
├── TireCircleView.java        # 胎压圆形视图
├── TurnSignalView.java        # 转向灯视图
├── HvacToastView.java         # 空调状态提示
├── FlowLineView.java          # 流光动画线
└── TagLineOverlay.java        # 标签线条覆盖层
```

## 已知限制

- 档位映射（D2~D8）为推算值，未经实车验证，实车可能存在差异
- 需飞思平台签名，否则共享 UID 不匹配会报 SecurityException

## 更新日志

### v1.02（2026-10-05）

- **架构重构**：车况数据逻辑从 MainActivity 抽离至 `VehicleDataService`，MainActivity 瘦身为纯 UI 外壳（~2800 行 → ~524 行）
- **纯服务模式**：新增 `run_mode` SP 键（0=带界面 1=纯服务），由乐酷驾驶设置页 `SET_MODE` 广播切换；纯服务模式无界面、仅广播车况数据给乐酷驾驶
- **前台保活**：纯服务模式启动前台 Service（dataSync 类型 + 低优先级通知），避免被系统回收
- **开机自启升级**：BootReceiver 按 `run_mode` 决定启动 Activity 或 Service
- **日志精简**：清理全部 `Log.d` 调试日志，功能性日志降级为 `Log.i`，删除无调用的 `crashLog` 与死代码

### v1.01

- 开机自启 + 车况广播 + 空调温度 float + 总里程整数显示
- 胎压推送后刷新 UI + HVAC 提示基线初始化 + 绘制性能优化
- 基于 Gitee 的在线更新 + 转向灯顶对齐
- 胎压回调注册 + 延迟重试 + 空调状态提示 + 门窗 90dp + 横屏两行 + 速度转向灯封顶 + BadToken 修复 + 分屏布局优化
- 日夜双模式 + 多分辨率适配
- 仪表盘 UI 重构：V12 环绕布局 + 档位精简 + 总里程字号统一

## License

保留所有权利，未经授权请勿用于商业用途。

> AI生成