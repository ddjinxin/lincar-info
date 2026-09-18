---
AIGC:
  ContentProducer: '001191110102MAD55U9H0F10002'
  ContentPropagator: '001191110102MAD55U9H0F10002'
  Label: '1'
  ProduceID: '96c75a32-86e1-4267-8619-89d36192ba98'
  PropagateID: '96c75a32-86e1-4267-8619-89d36192ba98'
  ReservedCode1: 'e53e249d-1d76-433f-840e-612a410379fa'
  ReservedCode2: 'e53e249d-1d76-433f-840e-612a410379fa'
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
├── MainActivity.java      # 主界面 + 胎压回调注册
├── DoorCircleView.java    # 车门状态圆形视图
├── TireCircleView.java    # 胎压圆形视图
├── TurnSignalView.java    # 转向灯视图
├── HvacToastView.java     # 空调状态提示
├── FlowLineView.java      # 流光动画线
└── TagLineOverlay.java    # 标签线条覆盖层
```

## 已知限制

- 档位映射（D2~D8）为推算值，未经实车验证，实车可能存在差异
- 需飞思平台签名，否则共享 UID 不匹配会报 SecurityException

## License

保留所有权利，未经授权请勿用于商业用途。

> AI生成