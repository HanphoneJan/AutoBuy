# AGENTS.md

This file provides guidance to AI coding agents (Claude Code, opencode, Cursor, etc.) working with code in this repository.

## 仓库总览

AutoBuy 是一个**淘宝/京东自动抢购**项目，仓库里有两套实现：

| 目录 | 形态 | 状态 |
|------|------|------|
| `android-native/AutoBuy/` | **原生 Android App**（Kotlin + Jetpack Compose + Material 3），无障碍 + 图像识别自动化**官方 App** | **主线 / 推荐** |
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
    ├── accessibility/AutoBuyAccessibilityService.kt   # 无障碍：找/点节点、坐标手势、截图（含失败码）、返回
    ├── core/
    │   ├── LogBus.kt              # 内存日志 + 运行状态 + 阶段（StateFlow），并写文件
    │   ├── FileLogger.kt          # 日志持久化到 logs/autobuy.log（超限轮转）
    │   ├── LogExporter.kt         # 导出「诊断报告」（设备/系统/权限 + 完整日志）→ FileProvider 分享
    │   ├── TimeSync.kt            # 对齐淘宝服务器时间戳
    │   ├── Updater.kt             # 查 GitHub Release 是否有新版
    │   ├── ThemePref.kt           # 主题模式（system/light/dark）持久化
    │   ├── Permissions.kt         # 无障碍/悬浮窗状态与跳转
    │   └── ImageMatcher.kt        # 掩码灰度 SAD 多尺度模板匹配（全部候选/多半径环形校验/勾选变色判定）+ 纯色质心
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
2. **NAVIGATE（就位）**：确保目标 App 在前台 → 无障碍点「购物车」→ 图像识别勾选商品（多候选按 y 逐个试 + 环形校验 + **点后用底部「合计」金额验证**，误进商品详情页会自动退回）→ 点「结算/去结算」→ **校验确认订单页真的出现**（没出现就判失败）。`fast` 模式用于到点后的快速重试。
3. **REFRESH（刷新）**：**开卖前 5 秒起**，确认订单页若有「号码保护」开关，则以 `refreshIntervalMs`（默认 250ms）切换它刷新开卖状态；没有则跳过。
4. **SUBMIT（提交）**：到点快速连点「立即支付/提交订单」（150ms）。兜底策略：
   - 在确认页但显示「暂时不能购买/￥0」→ **留在确认页**继续用「号码保护」刷新开卖状态（**不退回购物车重进**，重进要 2~3s 会错过开卖瞬间）；
   - 只有本轮**从没进过确认页**且持续 4s 不在确认页 → 快速重新就位（预约/预售商品到点才可选）；
   - 只有「按钮可用（非未开售/￥0）」的点击 + 限时 5s 内离开确认页，才判定为已提交，直到超出 `windowSeconds` 窗口。
5. **VERIFY（回查）**：只有进入「收银台/确认付款」才算成功；否则打开「我的 → 待付款」，要求列表里出现订单条目（填了关键词还要命中），空态/仍是确认页一律判失败（#26 假成功）。
- 平台差异用参数：包名、勾选模板、结算文案与颜色（淘宝橙 `#FF6A00`，京东红 `#E1251B`）。

### 关键实现细节
- **无障碍**（`AutoBuyAccessibilityService`）：遍历 `AccessibilityNodeInfo` 找/点节点（text/desc）、`dispatchGesture` 坐标点击、`performGlobalAction` 返回；**截图用 `AccessibilityService.takeScreenshot`（Android 11+）**，无需 MediaProjection。同进程静态 `instance` 供引擎与 UI 使用。
- **图像识别**（`ImageMatcher`，纯 Kotlin 无 OpenCV）：
  - 掩码灰度 SAD 多尺度匹配（默认 0.65/0.75/0.85/0.95/1.1/1.25）：**只对模板里有内容的像素算分**，否则大片白底会和稀疏的灰环模板拿到 90%+ 的高分（#27 曾点在标题栏上）；
  - `findAll` 返回**全部**候选（按 y 排序、相邻合并），勾选时逐个点、用「合计」金额验证：商品图里也有圆（镜头/旋钮），只取最上方那一个会点进商品详情页（#29）；
  - 比例区域 + `isRingLike` 16 方向校验（中心亮、环低饱和灰且更暗），**环半径按模板宽度的 0.30~0.48 试**：只用单一半径时真复选框被漏掉、商品图里的圆反而通过；
  - 勾选阈值 **0.90**，低于阈值输出「未命中 + 屏幕/区域/模板/最高分」日志；
  - `findColorCentroid` 找纯色按钮（注意：淘宝结算按钮在不可用时仍是橙色、京东「去结算」也永远是红色，**不能用来判断是否可点**）。
- **无障碍截图**：`takeScreenshot` 系统限频 **≥333ms**（引擎按 360ms 节流并失败重试）；失败码会写日志（无权限/限频/安全窗口/ROM 缓冲转换失败）。Android 11 以下不可用。
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

调试机（可用 adb 直接验证）：Redmi Note 12 Turbo（23049RAD8C），1080x2400，Android 15，装有淘宝/京东。
`adb shell am start -a android.intent.action.VIEW -d "openapp.jdmobile://virtual?params=%7B%22category%22%3A%22jump%22%2C%22des%22%3A%22cart%22%7D"` 可直接打开京东购物车；**跑抢购任务有真实下单风险，只在确认购物车里的商品不可购买时测试**。

### 已知问题 / 注意事项（重要）
- **无障碍服务会被系统关闭**（尤其**重装/更新 App 后**、被省电清理、重启后）。抢购前必须确认 App 内显示「已开启」；**抢购前不要重装或强制停止 App**。
- 就绪判断用**服务实例**（`AutoBuyAccessibilityService.instance`）而非读 `Settings`（MIUI 下不准）。用 adb 写 `enabled_accessibility_services` 只是临时手段，**用户需在系统设置里手动开启**才稳。
- **勾选不能盲点最上方的圆**：京东购物车商品图里有圆（镜头/旋钮），只取 topmost 会点进商品详情页，然后在详情页和购物车之间反复跳（#29）。现在是「全部候选逐个试 → 点完看是否进了详情页（进了就退回并记住这个坐标）→ 读底部合计 > 0 才算勾上」。
- **结算前必须确认合计有金额**：京东「去结算」不管有没有选中都是红色、淘宝结算按钮不可用时也仍是橙色，颜色判断不可靠；合计 ¥0.00 时**不要点结算**，否则只是空转。
- **商品本来就勾着时不要再点**：再点一下会取消勾选（#27 用户说的「提前勾选反而正常」）。先读合计，已有选中就跳过勾选；因此**购物车只留要抢的商品**（否则会把别的商品一起结算）。
- **京东购物车没有任何文字节点**（`uiautomator dump` 只有 184 个无文字节点），所以：找不到「去结算」文字、读不到「合计」；勾选结果只能靠**复选框变色**（白底灰环 → 实心品牌色）判断，见 `ImageMatcher.isCheckedMark`。
- **平台会弹风控/安全验证**（京东「检测到安全风险/快速验证」、淘宝滑块），自动化点不动；`SeckillEngine` 识别到就记一条日志并停手，需人工过验证后重开。
- 图像模板与购物车布局强相关，App 改版后可能需重录模板；识别只按"最上方（或候选顺序）的复选框"，**不能按关键词精确选品**；最上方可能是「店铺」复选框，会选中该店铺下多件商品。
- 真机调试抢购任务会**真实下单**：测试时必须在 `targetTime` 前至少 15 秒停掉 App，否则会生成待付款订单。
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
- `SeckillWorker` — 流程：建浏览器 → 扫码登录（前端"确认登录"）→ 可选商品关键词 + 停留在购物车（"确认购物车"）→ 网络时间卡点 → 自动刷新购物车（淘宝每 1.5s，长时间未解锁放慢到 5s；京东每轮）→ 按关键词（或最上方商品）勾选并校验状态，未勾上就刷新重试（#21）→ 点结算 → 循环点提交（300 次，带随机抖动退避）→ `_verify_order_submitted()` 以跳转支付/收银台为硬成功信号，并回查订单列表，避免假成功（#11）

**前端**：`templates/index.html`（侧边栏 + 时间设置 + 5 步进度条 + 实时日志）、`static/js/seckill.js`（SSE 监听、按日志关键词推进进度条）、`static/css/style.css`。

**数据流**：`用户操作 → fetch → Flask → Task → daemon 线程 SeckillWorker`；`浏览器 ← SSE ← TaskManager.add_log() ← log_callback`。用户确认通过 `POST /api/tasks/<id>/confirm` 写 `_confirm_states`，worker 轮询。

### 关键细节
- 驱动优先用本地缓存 (`~/.wdm/drivers/chromedriver/win64/`)，匹配本机 Chrome 大版本
- 浏览器窗口定位屏幕右侧 (`set_window_position(1400, 0)`)
- 淘宝在购物车确认前不导航，让用户手动进购物车；京东直接导航 `trade.jd.com`
- 购物车自动勾选：`_auto_check_cart_item()` 按关键词（留空取最上方商品）定位复选框，点击后重新校验 `checked/aria-checked/class`，未生效则重试；`_perform_tb_seckill()` 失败时刷新购物车再勾（开售状态只有刷新才更新）
- 遮罩移除脚本注入 (`Page.addScriptToEvaluateOnNewDocument`) 并每 500ms 循环
- CDP 隐藏 `navigator.webdriver`

---

## 开发约定
- 遵循现有代码风格与命名；保持架构分层清晰。
- 改 `PlatformConfig` 选择器时需同时验证淘宝/京东。
- 网页版进度条依赖后端日志关键词推进，新增日志时同步 `static/js/seckill.js`。
- **Android 改动**：改配色必须与 `hanphone-blog/android` 逐值一致；改流程注意 Stage/日志与悬浮指引一致；改模板后要真机验证；**提交前先在调试机安装验证**，发布走 Release 流程。
