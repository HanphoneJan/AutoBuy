# AutoBuy（原生 Android）

淘宝/京东**官方 App** 抢购自动化，原生 Android 实现（Kotlin + Jetpack Compose + Material 3）。
是仓库根目录 PC 版（Selenium）与 `android/autojs` 原型的原生替代。

> ⚠️ 仅用于学习交流。自动化操作官方 App 可能违反平台服务条款，账号风险自负，请勿商用或分发。

## 技术方案
- **无障碍服务**（`AutoBuyAccessibilityService`）：
  - 按 `text` / `contentDescription` 定位并点击节点（如确认订单页的「立即支付」）；
  - 坐标手势点击（`dispatchGesture`）用于 Weex 页面；
  - **无障碍截图**（`AccessibilityService.takeScreenshot`，Android 11+）——无需 MediaProjection，
    也就没有 Auto.js 版"每次运行都要授权屏幕共享"的痛点。
- **图像识别**（`ImageMatcher`，纯 Kotlin，无 OpenCV）：
  - 灰度 SAD 模板匹配（按设备宽度缩放、比例区域、取最上方）；
  - 纯色区域质心（用于「结算」这类纯橙色按钮）。
- **时间校准**（`TimeSync`）：对齐淘宝服务器时间戳。
- **前台服务**（`SeckillService`）：保证抢购期间进程存活。

## 淘宝流程（与 Auto.js 版一致，已真机验证）
```
图像识别勾选购物车第一个未选中商品 → 颜色识别点击「结算」 → content-desc 点击「立即支付」
```

## 构建
```bash
cd android-native/AutoBuy
# 需要 Android SDK（platform 35、build-tools 35）与 JDK 17+
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

## 安装与使用
1. `adb install -r app/build/outputs/apk/debug/app-debug.apk`
2. 打开 AutoBuy → 点「去开启」开启无障碍服务。
3. 把淘宝 App 停在购物车页，并**手动勾选好目标商品**（或让脚本按位置自动勾选第一个）。
4. 填写抢购时间（`yyyy-MM-dd HH:mm:ss`）→ 开始抢购。
5. 到点后自动：勾选 → 结算 → 立即支付（提交订单，不代付款）。

## 现状
- [x] 工程骨架 + Material 3 主题（参考 hanphone-blog/android 的组织方式）
- [x] 无障碍服务（text/desc 点击、坐标手势、无障碍截图）
- [x] 图像识别（模板匹配 + 纯色质心）+ 时间校准
- [x] 前台服务 + Compose 主界面（权限状态 / 配置 / 日志）
- [ ] 京东流程
- [ ] 真机端到端验证

## 目录
```
android-native/AutoBuy/
├── app/src/main/java/com/autobuy/app/
│   ├── MainActivity.kt
│   ├── accessibility/AutoBuyAccessibilityService.kt
│   ├── core/{LogBus,TimeSync,ImageMatcher,Permissions}.kt
│   ├── seckill/{SeckillConfig,SeckillEngine,SeckillService}.kt
│   └── ui/{App,SeckillScreen}.kt + ui/theme/{Color,Theme,Type}.kt
├── app/src/main/assets/templates/   # 图像识别模板
└── app/src/main/res/                # 图标/字符串/无障碍配置
```
