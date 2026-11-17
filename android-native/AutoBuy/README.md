# AutoBuy（原生 Android）

淘宝/京东**官方 App** 抢购自动化，原生 Android 实现（Kotlin + Jetpack Compose + Material 3）。
是仓库根目录 PC 版（Selenium）的原生替代。早期真机勘察结论见 [`docs/device-survey.md`](docs/device-survey.md)。

> ⚠️ 仅用于学习交流。自动化操作官方 App 可能违反平台服务条款，账号风险自负，请勿商用或分发。

## 技术方案
- **无障碍服务**（`AutoBuyAccessibilityService`）：
  - 按 `text` / `contentDescription` 定位并点击节点（如确认订单页的「立即支付」）；
  - 坐标手势点击（`dispatchGesture`）用于 Weex/自绘页面；
  - **无障碍截图**（`AccessibilityService.takeScreenshot`，Android 11+）——无需 MediaProjection。
- **图像识别**（`ImageMatcher`，纯 Kotlin，无 OpenCV）：纯色区域质心，定位「结算/去结算」「提交」按钮。
  购物车勾选**不再做图像猜测**：复选框和商品图里都有圆，分不清就会点错商品（#29），商品改由用户自己勾选。
- **时间校准**（`TimeSync`）：对齐淘宝服务器时间戳。
- **前台服务**（`SeckillService`）：保证抢购期间进程存活。

## 移动端抢购流程（区别于网页端）
1. **准备**：校准网络时间，等到 `T-navigateLead`（默认提前 20s）开始就位。
2. **就位（自动化）**：自动切到购物车 → 校验你已勾选的商品（淘宝读底部「合计」；京东购物车无文字，直接交给「去结算」判断）→ 点「结算 / 去结算」→ 进入确认订单页。
3. **刷新**：开卖前 5 秒起，在确认订单页用「号码保护」开关刷新开卖状态（默认 **250ms/次**）。
4. **提交**：到点快速连点「立即支付 / 提交订单」（默认 **150ms/次**）；仍显示未开售就留在确认页继续用
   「号码保护」刷新，**不退回购物车重进**；只有本轮从没进过确认页才会重新就位，直到成功或超出窗口。
5. **回查**：提交后确认是否进入支付/待付款页，否则打开订单列表核对。

全程有 App 内「指引」页（区分「你要做的」和「App 自动做的」）；授权「显示在其他应用上层」后，
抢购时在目标 App 之上显示**悬浮指引条**（当前阶段 + 提示，不可触摸不挡点击）。

## 功能
- 底部导航：**抢购 / 指引 / 日志 / 我的**。
- 抢购页：平台选择、抢购时间（含「今天 12:00」快捷）、商品关键词、开始/停止、当前阶段。
- 日志页：完整日志（等宽字体）+ **导出**（txt，可分享）。
- 我的页：**检查更新**（GitHub Release）、项目仓库、问题反馈、免责声明。
- 结果回查：提交后核对订单。

## 构建
```bash
cd android-native/AutoBuy
# 需要 Android SDK（platform 35、build-tools 35）与 JDK 17+
./gradlew :app:assembleDebug     # 调试包
./gradlew :app:assembleRelease   # 发布包（用 debug 密钥签名，便于直接安装升级）
```

## 安装与使用
1. 安装 APK。
2. 打开 AutoBuy → 「指引」页按步骤准备：开启**无障碍服务**（必需）、可选开启**悬浮指引**。
3. 在淘宝/京东里登录，把商品加进购物车**并保持勾选**；购物车只留要抢的商品（其他已勾选的会一起结算），预约/预售商品请在开售后自己勾上。
4. 「抢购」页选平台、设时间（可用「今天 12:00」）→ 开始抢购。
5. 切到目标 App 的购物车页并保持屏幕常亮；到点后自动：就位 → 刷新 → 提交。

## 注意事项
- **无障碍服务可能被系统关闭**，抢购前请确认 App 内显示「已开启」。
- **抢购前不要重装/强制停止** AutoBuy，否则无障碍会掉。

## 现状
- [x] 工程骨架 + Material 3（对齐 hanphone-blog/android 的原生设计语言）
- [x] 无障碍服务（text/desc 点击、坐标手势、无障碍截图）
- [x] 图像识别（纯色按钮质心）+ 时间校准
- [x] 移动端流程：准备 → 就位 → 刷新（号码保护）→ 提交 → 回查
- [x] 淘宝 / 京东；预售/预约/定时开卖（到点重试结算与提交，商品需开售后自己勾选）
- [x] 底部导航四页 + 步骤式「指引」 + 悬浮指引条
- [x] 日志导出、检查更新（GitHub Release）
- [ ] 定时待命（到点自动唤醒执行）
- [ ] 看门狗（无障碍掉线自愈）+ 常亮（WakeLock）

## 目录
```
android-native/AutoBuy/
├── app/src/main/java/com/autobuy/app/
│   ├── MainActivity.kt
│   ├── accessibility/AutoBuyAccessibilityService.kt
│   ├── core/{LogBus,LogExporter,TimeSync,Updater,ImageMatcher,Permissions}.kt
│   ├── seckill/{SeckillConfig,SeckillEngine,SeckillService}.kt
│   └── ui/{App,MainTab,SeckillScreen,GuideScreen,LogsScreen,MineScreen,GuidanceOverlay}.kt
│       + ui/components/{Common,PermissionState}.kt + ui/theme/{Color,Theme,Type}.kt
└── app/src/main/res/                # 图标/字符串/无障碍配置/FileProvider
```

## 系统要求
- **最低 Android 7.0（API 24）**；推荐 **Android 11+（API 30）**。
- 原因：坐标点击需 API 24+；**按颜色识别「结算/提交」按钮依赖无障碍截图，需 Android 11+**（淘宝 Weex 的结算按钮读不到文字，Android 11 以下基本不可用）。

## 架构（类地图）
- `accessibility/AutoBuyAccessibilityService` — 无障碍：按 text/desc 找点、坐标手势、无障碍截图、返回
- `core/ImageMatcher` — 纯色按钮质心（结算 / 提交）
- `core/TimeSync` — 对齐淘宝服务器时间
- `seckill/SeckillEngine` — 抢购流程（见上）；`seckill/SeckillService` — 前台服务
- `core/LogBus` + `FileLogger` + `LogExporter` — 日志/持久化/诊断导出
- `core/Updater` — 检查更新；`core/ThemePref` + `ui/theme/*` — 主题
- `ui/*` — 底部导航四页 + `GuidanceOverlay` 悬浮指引 + `components/*` 通用组件

## 扩展
- **加平台**：在 `SeckillConfig.Platform` 加枚举；在 `SeckillEngine.run()` 的 `when` 里给出包名、结算文案与按钮颜色。
- **改刷新方式**：`SeckillConfig.refreshIntervalMs`；「号码保护」关键词在 `SeckillEngine.privacyKeywords`。

## 已知问题
- **无障碍服务会被系统关闭**（重装/更新 App 后尤其明显）；抢购前请确认 App 内显示「已开启」，且**不要重装/强制停止** App。
- **不做自动勾选**：识别分不清复选框和商品图里的圆，会选错商品；商品由用户自己勾选。
- **未实现**：定时待命（到点自动唤醒）、看门狗（无障碍掉线自愈）、常亮（WakeLock）。
- 详见仓库根 `AGENTS.md` 的「Android 原生 App」章节。

