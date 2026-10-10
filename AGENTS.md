# AGENTS.md

This file provides guidance to AI coding agents (Claude Code, opencode, Cursor, etc.) working with code in this repository.

## 仓库总览

AutoBuy 是一个**淘宝/京东自动抢购**项目，仓库里有两套实现：

| 目录 | 形态 | 状态 |
|------|------|------|
| `android-native/AutoBuy/` | **原生 Android App**（Kotlin + Jetpack Compose + Material 3），无障碍 + 图像识别自动化**官方 App** | **主线 / 推荐** |
| `android/` | 早期 Auto.js 原型（验证安卓无障碍链路） | 归档 |
| 根目录 `app.py` / `seckill.py` / `templates/` / `static/` | 电脑网页版（Python + Selenium + Flask） | 备选 |

> 新接手请优先阅读本文的 **「Android 原生 App」** 章节——那是当前主线。

---

## Android 原生 App（`android-native/AutoBuy/`）—— 主线

### 技术栈
Kotlin + Jetpack Compose + Material 3；Gradle 8.11.1 + AGP 8.7.3 + Kotlin 2.0.21；**minSdk 24（Android 7.0）/ compileSdk 35**。
> 功能范围：**安装**支持 Android 7.0+；但**图像识别依赖无障碍截图（Android 11+）**，Android 11 以下只能做 text/desc 点击，购物车（Weex）选品会不可用。
应用包名 `com.autobuy.app`；版本号在 `android-native/AutoBuy/app/build.gradle.kts`（`versionCode` / `versionName`）。

### 目录 / 类地图
```
android-native/AutoBuy/app/src/main/
├── AndroidManifest.xml            # 无障碍服务 / 前台服务 / FileProvider / 权限
├── assets/templates/              # 图像识别模板：taobao_cart_checkbox、taobao_cart_settle、jd_cart_checkbox
├── res/xml/accessibility_service_config.xml   # canTakeScreenshot / canPerformGestures 等
└── java/com/autobuy/app/
    ├── AutoBuyApp.kt              # Application，初始化 FileLogger
    ├── MainActivity.kt            # setContent { AutoBuyAppRoot() }
    ├── accessibility/AutoBuyAccessibilityService.kt   # 无障碍：找/点节点、坐标手势、截图、返回
    ├── core/
    │   ├── LogBus.kt              # 内存日志 + 运行状态 + 阶段（StateFlow），并写文件
    │   ├── FileLogger.kt          # 日志持久化到 logs/autobuy.log（超限轮转）
    │   ├── LogExporter.kt         # 导出「诊断报告」（设备/系统/权限 + 完整日志）→ FileProvider 分享
    │   ├── TimeSync.kt            # 对齐淘宝服务器时间戳
    │   ├── Updater.kt             # 查 GitHub Release 是否有新版
    │   ├── ThemePref.kt           # 主题模式（system/light/dark）持久化
    │   ├── Permissions.kt         # 无障碍/悬浮窗状态与跳转
    │   └── ImageMatcher.kt        # 灰度 SAD 模板匹配（缩放/比例区域/topmost/环形校验）+ 纯色质心
    ├── seckill/
    │   ├── SeckillConfig.kt       # Platform / SeckillConfig / Stage 枚举
    │   ├── SeckillEngine.kt       # 抢购引擎（核心流程）
    │   └── SeckillService.kt      # 前台服务，跑引擎 + 悬浮指引
    └── ui/
        ├── App.kt                 # 根：主题模式 → AutoBuyTheme；MainScaffold（底部导航 + NavHost）
        ├── MainTab.kt             # 底部导航枚举（抢购/指引/日志/我的）
        ├── SeckillScreen.kt       # 抢购页（配置 + 开始/停止 + 当前阶段）
        ├── GuideScreen.kt         # 指引页（你要做的 / App 自动做的）
        ├── LogsScreen.kt          # 日志页（导出 / 清空）
        ├── MineScreen.kt          # 我的页（外观主题 / 检查更新 / 仓库 / 免责声明）
        ├── GuidanceOverlay.kt     # 悬浮指引条（非触摸，主线程更新）
        ├── components/Common.kt   # AppCard/SectionTitle/SettingRow/CompactField/StatusPill/ScreenHeader
        ├── components/PermissionState.kt  # 无障碍/悬浮权限（回前台刷新）
        └── theme/{Color,Theme,Type}.kt    # 配色对齐 hanphone-blog（blue/slate，浅+深）
```

### 抢购流程（`SeckillEngine`，移动端与网页端不同）
`Stage`：`IDLE → PREPARE → NAVIGATE → REFRESH → SUBMIT → VERIFY → DONE`（`LogBus.setStage` 供 UI 与悬浮指引读取）。
1. **PREPARE**：`TimeSync` 校准网络时间偏移；等到 `目标 - navigateLeadSeconds`（默认 20s）。
2. **NAVIGATE（就位）**：确保目标 App 在前台 → 无障碍点「购物车」→ 图像识别勾选商品（模板 + 环形校验 + topmost）→ 点「结算/去结算」→ 进确认订单页。`fast` 模式用于到点后的快速重试。
3. **REFRESH（刷新）**：确认订单页若有「号码保护」开关，则以 `refreshIntervalMs`（默认 400ms）切换它刷新开卖状态；没有则跳过。
4. **SUBMIT（提交）**：到点快速连点「立即支付/提交订单」（150ms）。三种兜底：
   - 不在确认页 → **快速重新就位**（预约/预售商品到点才可选）；
   - 在确认页但显示「暂时不能购买/￥0」→ **退回购物车重进结算页刷新**；
   - 直到离开确认页（视为成功）或超出 `windowSeconds` 窗口。
5. **VERIFY（回查）**：进入支付/待付款页即成功；否则打开「我的 → 待付款」订单列表核对。
- 平台差异用参数：包名、勾选模板、结算文案与颜色（淘宝橙 `#FF6A00`，京东红 `#E1251B`）。

### 关键实现细节
- **无障碍**（`AutoBuyAccessibilityService`）：遍历 `AccessibilityNodeInfo` 找/点节点（text/desc）、`dispatchGesture` 坐标点击、`performGlobalAction` 返回；**截图用 `AccessibilityService.takeScreenshot`（Android 11+）**，无需 MediaProjection。同进程静态 `instance` 供引擎与 UI 使用。
- **图像识别**（`ImageMatcher`，纯 Kotlin 无 OpenCV）：灰度 SAD 模板匹配，支持按设备宽度缩放、比例区域、取最上方，以及 `isRingLike` 环形校验（排除商品图实心圆）；`findColorCentroid` 找纯色按钮。
- **悬浮指引**（`GuidanceOverlay`）：`TYPE_APPLICATION_OVERLAY` + `FLAG_NOT_TOUCHABLE`；**所有 View 操作必须切主线程**（否则 `CalledFromWrongThreadException` 崩溃）。
- **日志**：`LogBus.add()` → 内存（UI）+ `android.util.Log`（logcat 标签 `AutoBuy`）+ `FileLogger`（文件）。日志页可导出诊断报告。
- **主题**：`Color.kt` 取值必须与 `hanphone-blog/android` 的 `ui/theme/Color.kt` **逐值一致**；`ThemePref` 存 system/light/dark。

### 构建 / 发布
```bash
cd android-native/AutoBuy
export ANDROID_HOME=~/Android/Sdk
./gradlew :app:assembleDebug     # 调试包
./gradlew :app:assembleRelease   # 发布包（用 debug 密钥签名，便于覆盖安装）
```
发布：改 `versionName`/`versionCode` → `assembleRelease` → 用 GitHub API 建 Release（tag `vX.Y.Z`，target `main`）并上传 `app-release.apk`。
App 内「检查更新」读取 `https://api.github.com/repos/HanphoneJan/AutoBuy/releases/latest`，与 `BuildConfig.VERSION_NAME` 比较。

### 已知问题 / 注意事项（重要）
- **无障碍服务会被系统关闭**（尤其**重装/更新 App 后**、被省电清理、重启后）。抢购前必须确认 App 内显示「已开启」；**抢购前不要重装或强制停止 App**。
- 就绪判断用**服务实例**（`AutoBuyAccessibilityService.instance`）而非读 `Settings`（MIUI 下不准）。用 adb 写 `enabled_accessibility_services` 只是临时手段，**用户需在系统设置里手动开启**才稳。
- 图像模板与购物车布局强相关，App 改版后可能需重录模板；识别只按"最上方复选框"，**不能按关键词精确选品**。
- 网页端没有的「号码保护」是**确认订单页**（普通订单）的控件；淘宝预售确认页也有。
- 预约/预约商品开售前**不可勾选**，靠"到点重就位"。
- **未实现**：定时待命（到点自动唤醒执行）、看门狗（无障碍掉线自愈）、常亮 WakeLock。

---

## 电脑网页版（Selenium）—— 备选

### Commands
```bash
uv sync                                  # 安装依赖
uv run python app.py                     # 启动 Web 应用（http://localhost:5000）
uv run python seckill.py jd --time "2025-03-19 11:00:00.000000"   # 命令行模式
uv run python seckill.py tb --time "2025-03-19 11:00:00.000000"
```
Windows 用户可双击 `start.bat`。

### 环境要求
- Python **>=3.10**（用了 `str | None` 等 PEP 604 语法）；`.python-version` 锁定版本
- 浏览器：Google Chrome（最新版），ChromeDriver 由 `webdriver-manager` 自动管理

### Architecture
**`app.py`** — Flask Web 层：路由（`/`, `/help`）+ REST API；`TaskManager` 管理多任务（创建/状态/日志缓存/停止清理）；SSE (`/api/tasks/<id>/logs`) 实时日志；每个任务独立 daemon 线程，`run_seckill_task()` 桥接到 `SeckillWorker`。

**`seckill.py`** — 核心自动化引擎（可独立运行）：
- `PlatformConfig` — 各平台 URL、按钮选择器、购物车地址
- `BrowserManager` — 驱动创建、反检测（隐藏 `navigator.webdriver`、UA 对齐本机真实 Chrome、持久化用户目录）、遮罩移除脚本注入
- `TimeManager` — 京东/淘宝服务器时间戳
- `SeckillWorker` — 流程：建浏览器 → 扫码登录（前端"确认登录"）→ 手动进购物车（"确认购物车"）→ 网络时间卡点 → 循环点结算/提交（300 次，带随机抖动退避）→ `_verify_order_submitted()` 以跳转支付/收银台为硬成功信号，并回查订单列表，避免假成功（#11）

**前端**：`templates/index.html`（侧边栏 + 时间设置 + 5 步进度条 + 实时日志）、`static/js/seckill.js`（SSE 监听、按日志关键词推进进度条）、`static/css/style.css`。

**数据流**：`用户操作 → fetch → Flask → Task → daemon 线程 SeckillWorker`；`浏览器 ← SSE ← TaskManager.add_log() ← log_callback`。用户确认通过 `POST /api/tasks/<id>/confirm` 写 `_confirm_states`，worker 轮询。

### 关键细节
- 驱动优先用本地缓存 (`~/.wdm/drivers/chromedriver/win64/`)，匹配本机 Chrome 大版本
- 浏览器窗口定位屏幕右侧 (`set_window_position(1400, 0)`)
- 淘宝在购物车确认前不导航，让用户手动进购物车；京东直接导航 `trade.jd.com`
- 遮罩移除脚本注入 (`Page.addScriptToEvaluateOnNewDocument`) 并每 500ms 循环
- CDP 隐藏 `navigator.webdriver`

---

## 开发约定
- 遵循现有代码风格与命名；保持架构分层清晰。
- 改 `PlatformConfig` 选择器时需同时验证淘宝/京东。
- 网页版进度条依赖后端日志关键词推进，新增日志时同步 `static/js/seckill.js`。
- **Android 改动**：改配色必须与 `hanphone-blog/android` 逐值一致；改流程注意 Stage/日志与悬浮指引一致；改模板后要真机验证；**提交前先在调试机安装验证**，发布走 Release 流程。
