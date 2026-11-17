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
    │   └── ImageMatcher.kt        # 纯色区域质心（结算/提交按钮；模板匹配已随「自动勾选」一起删除）
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
2. **NAVIGATE（就位）**：确保目标 App 在前台 → 无障碍点「购物车」→ **只校验用户已勾选商品，不替用户勾选**（淘宝读底部「合计」，¥0.00 提示先勾选；京东购物车无文字，直接点「去结算」由平台判断）→ 点「结算/去结算」→ **校验确认订单页真的出现**（没出现就判失败并提示检查勾选/在售）。`fast` 模式用于到点后的快速重试。
3. **REFRESH（刷新）**：**开卖前 5 秒起**，确认订单页若有「号码保护」开关，则以 `refreshIntervalMs`（默认 250ms）切换它刷新开卖状态；没有则跳过。
4. **SUBMIT（提交）**：到点快速连点「立即支付/提交订单」（150ms）。兜底策略：
   - 在确认页但显示「暂时不能购买/￥0」→ **留在确认页**继续用「号码保护」刷新开卖状态（**不退回购物车重进**，重进要 2~3s 会错过开卖瞬间）；
   - 只有本轮**从没进过确认页**且持续 4s 不在确认页 → 快速重新就位（预约/预售商品到点才可选）；
   - 只有「按钮可用（非未开售/￥0）」的点击 + 限时 5s 内离开确认页，才判定为已提交，直到超出 `windowSeconds` 窗口。
5. **VERIFY（回查）**：只有进入「收银台/确认付款」才算成功；否则打开「我的 → 待付款」，要求列表里出现订单条目（填了关键词还要命中），空态/仍是确认页一律判失败（#26 假成功）。
- 平台差异用参数：包名、结算文案与颜色（淘宝橙 `#FF6A00`，京东红 `#E1251B`）。

### 关键实现细节
- **无障碍**（`AutoBuyAccessibilityService`）：遍历 `AccessibilityNodeInfo` 找/点节点（text/desc）、`dispatchGesture` 坐标点击、`performGlobalAction` 返回；**截图用 `AccessibilityService.takeScreenshot`（Android 11+）**，无需 MediaProjection。同进程静态 `instance` 供引擎与 UI 使用。
- **图像识别**（`ImageMatcher`，纯 Kotlin 无 OpenCV）：只剩 `findColorCentroid`，在右下区域找纯色按钮的质心（淘宝橙 / 京东红），用于「结算」「提交」的坐标兜底。
  - 注意：淘宝结算按钮不可用时仍是橙色、京东「去结算」有没有选中都是红色，**颜色不能用来判断按钮是否可点**。
  - 购物车复选框的模板匹配已删除（2026-11，0.3.7）：复选框和商品图里都有圆（镜头/旋钮），识别分不清，会点在商品图上跳详情页、或点在别的商品上把人家带进结算（#29）。自动勾选已从产品里移除，改为「用户自己勾，程序只校验」。
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

**真机自测夹具（不必每次找"抢购商品"）**：
- 测试品固定为**维达抽纸 3 层 100 抽\*10 包（京东自营，SKU `100021015676`，约 ¥17.9，长期在售）**。
- 脚本 `scripts/android-seckill-test.sh`：
  - `prepare`：深链打开该 SKU → 点「加入购物车」→ 规格面板点底部通栏「确定」→ 打开购物车（加购即勾选）；
  - `run`：京东 +「现在+1分」+ 开始抢购 → 切购物车 → 到点前 8 秒停掉（**不会下单**）并打印日志；
  - `run --submit`：跑到点，会生成真实待付款订单（测完去取消）；
  - `logs`：只看 AutoBuy 日志。脚本会自动把本服务补进 `enabled_accessibility_services`（ROM 会在 force-stop/更新后踢掉）。
- 预期日志：`点击结算 @ x,y` → `确认订单页已就位…`（0.3.8 起京东也能认出确认页）→ 到点 `已点击京东「立即支付」`。
- 真实「京东秒杀」走秒杀页直接购买、不进购物车，和本应用的购物车流程不同；测试用普通商品 + 任意目标时间即可覆盖同一代码路径。
- 坐标必须用 `uiautomator dump` 的 bounds 换算：**截图在聊天窗口里是缩放过的，肉眼估坐标会偏 150px 以上**（踩过）。
- 购物车「合计」不含运费（测试单 ¥17.9，下单后变成 ¥23.9，含 ¥6 运费），这是京东正常行为，不是程序算错。
`adb shell am start -a android.intent.action.VIEW -d "openapp.jdmobile://virtual?params=%7B%22category%22%3A%22jump%22%2C%22des%22%3A%22cart%22%7D"` 可直接打开京东购物车；**跑抢购任务有真实下单风险，只在确认购物车里的商品不可购买时测试**。

### 已知问题 / 注意事项（重要）
- **无障碍服务会被系统关闭**（尤其**重装/更新 App 后**、被省电清理、重启后）。抢购前必须确认 App 内显示「已开启」；**抢购前不要重装或强制停止 App**。
- 就绪判断用**服务实例**（`AutoBuyAccessibilityService.instance`）而非读 `Settings`（MIUI 下不准）。用 adb 写 `enabled_accessibility_services` 只是临时手段，**用户需在系统设置里手动开启**才稳。
- **自动勾选已移除**（0.3.7）：复选框和商品图里都有圆，图像识别分不清是哪一个——点商品图会跳详情页来回跳（#29），点到别的商品会把它带进结算。现在**商品由用户自己在购物车里勾选**，程序只校验（淘宝读「合计」）并结算；**不要**再把"自动勾选/按关键词选品"加回来，除非能真正识别商品标题。
- **购物车只留要抢的商品**：程序结算的是用户已勾选的商品，其他已勾选商品会一起进订单。
- **预约/预售**：开售前勾不上，需要用户在开售后自己勾；程序只会在窗口内反复重试结算与提交。
- **京东购物车和确认订单页都没有文字节点**（`uiautomator dump` 零文字）：读不到「合计」，也认不出「提交订单」。确认页靠**底部按钮形状**判断：京东确认页是**整行通栏**的「立即支付」（最长同色横线约占 0.87 屏宽），购物车「去结算」只在右下角（约 0.25）——见 `ImageMatcher.longestColorRun` + `bottomButtonBand`。**真机事故**：0.3.7 及以前认不出确认页，重试时把确认页的「立即支付」当结算按钮点了，直接生成订单并拉起支付。现在 `clickSettle` 发现通栏按钮就拒绝点击，且京东提交只点一次。
- **平台会弹风控/安全验证**（京东「检测到安全风险/快速验证」、淘宝滑块），自动化点不动；`SeckillEngine` 识别到就记一条日志并停手，需人工过验证后重开。
- 真机调试抢购任务会**真实下单**：测试时必须在 `targetTime` 前至少 15 秒停掉 App，否则会生成待付款订单。
- 网页端没有的「号码保护」是**确认订单页**（普通订单）的控件；淘宝预售确认页也有。
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
