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
2. **NAVIGATE（就位）**：**主动拉起目标 App**（`launchTargetApp`：`getLaunchIntentForPackage` + `NEW_TASK`，无障碍服务在后台启动豁免内，不再靠用户手动切）→ 等不到才提示手动切 → 无障碍点「购物车」→ **只校验用户已勾选商品，不替用户勾选**（淘宝读底部「合计」，¥0.00 提示先勾选；京东购物车无文字，直接点「去结算」由平台判断）→ 点「结算/去结算」→ **校验确认订单页真的出现**（没出现就判失败并提示检查勾选/在售）。`fast` 模式用于到点后的快速重试。**不在目标包名内一律不点**（`submitWithRetry` 先判包名再判页面，否则会把本应用自己的宽按钮当提交键盲点）。
3. **REFRESH（刷新）**：**开卖前 5 秒起**，确认订单页若有「号码保护」开关，则以 `refreshIntervalMs`（默认 250ms）切换它刷新开卖状态；没有则跳过。
4. **SUBMIT（提交）**：到点快速连点「立即支付/提交订单」（150ms）。兜底策略：
   - 在确认页但显示「暂时不能购买/￥0」→ **留在确认页**继续用「号码保护」刷新开卖状态（**不退回购物车重进**，重进要 2~3s 会错过开卖瞬间）；
   - 只有本轮**从没进过确认页**且持续 4s 不在确认页 → 快速重新就位（预约/预售商品到点才可选）；
   - 只有「按钮可用（非未开售/￥0）」的点击 + 限时 5s 内离开确认页，才判定为已提交，直到超出 `windowSeconds` 窗口。
5. **VERIFY（回查）**：只有进入「收银台/确认付款」才算成功；否则打开「我的 → 待付款」，要求列表里出现订单条目（填了关键词还要命中），空态/仍是确认页一律判失败（#26 假成功）。淘宝收银台是支付宝容器（`MspContainerActivity`），文字只有「使用密码 / 付款 / ￥xxx」，所以 `payPageKeywords` 收了「使用密码」；打不开「待付款」（收银台占屏）时报「请手动核对」，不报「没订单」。
- 平台差异用参数：包名、结算文案与颜色（淘宝橙 `#FF6A00`，京东红 `#E1251B`）。

### 关键实现细节
- **无障碍**（`AutoBuyAccessibilityService`）：遍历 `AccessibilityNodeInfo` 找/点节点（text/desc）、`dispatchGesture` 坐标点击、`performGlobalAction` 返回；**截图用 `AccessibilityService.takeScreenshot`（Android 11+）**，无需 MediaProjection。同进程静态 `instance` 供引擎与 UI 使用。
- **图像识别**（`ImageMatcher`，纯 Kotlin 无 OpenCV）：只剩 `findColorCentroid`（右下区域找纯色按钮质心，淘宝橙 / 京东红，用于「结算」「提交」的坐标兜底）和 `longestColorRun`（底部最长连续同色段占比，区分「去结算 0.25」与「立即支付 0.87」）。
  - `longestColorRun` 已拆成纯函数 `longestRunInBox(width, height, regionFrac, pixelAt, target, tolerance)`（不依赖 `android.graphics`），单测在 `app/src/test/.../ImageMatcherTest.kt`，覆盖 1.0 / 0.25 / 0.87 / 跨行残段不累加 / tolerance 边界。**改阈值或改扫描范围必须先跑单测**：`./gradlew :app:testDebugUnitTest`。
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
./gradlew :app:testDebugUnitTest # 单元测试（ImageMatcher 等纯逻辑，无需设备）
```
发布：改 `versionName`/`versionCode` → `assembleRelease` → 用 GitHub API 建 Release（tag `vX.Y.Z`，target `main`）并上传 `app-release.apk`。
App 内「检查更新」读取 `https://api.github.com/repos/HanphoneJan/AutoBuy/releases/latest`，与 `BuildConfig.VERSION_NAME` 比较。

调试机（可用 adb 直接验证）：Redmi Note 12 Turbo（23049RAD8C），1080x2400，Android 15，装有淘宝/京东。

**真机自测夹具**：
- 夹具目录 `fixtures/*.json`（以淘宝定时开售为主）+ 工具 `scripts/fixtures.py`：
  - `resolve <短链|深链|裸 id>`：`https://c.tb.cn/h.xxx?tk=…` 是**分享短链**（落地页 `bizCode="tbshare"`），HTML 里直接写着 `item.htm?id=…`，**curl 即可解析，不需要登录、不需要 App**；
  - `probe <id|链接>`：打开商品抽 开售时间/价格/出售状态 → 写 `fixtures/tb-<itemId>.json`，dump+截图存 `testdata/dumps/` 当证据；
  - `ingest [文件]`（不给文件读 stdin）：一次吃进一批群分享文案 / 淘口令 / 短链 → 逐条 probe。**最省的来源**，人已经帮你筛过一轮；
  - `harvest --source home`：翻首页推荐流，卡片标题带 `{预售}` 前缀的进详情判定（首页流**有**文字节点）；
  - `harvest --source search --query 预售 --sort 上新 --blind`：搜索结果的商品区是自定义渲染、**没有文字节点**，只能按网格盲点，靠回退 + `itemId` 去重；
  - `audit` 逐条重开标 `live/on_sale/changed/gone`；`list` 看目录与倒计时。
- **判定锚点（全部取自详情页 dump 文本，不猜图）**：

  | 锚点 | 含义 |
  |---|---|
  | `即将开始，11.25 16:00开售` | 全款定时开售 ← **最理想的夹具** |
  | `预售:出售状态` + `预售，200天内发货` | 全款预售，现在就能买（**没有定时**） |
  | `定金` + `尾款` | 定金预售（另一种形态） |
  | `访问太频繁` / `验证一下，购物无忧` | 频控 / 风控，退避 150s |

- 实测夹具 `fixtures/tb-1103296796879.json`（Meki 咩绮 BJD 限定吊卡）：`即将开始，11.25 16:00开售`、¥258、`超100人加购`、`预售:出售状态`、承诺 120 天发货 → **0 定金、开售前可加购、时间精确到分钟**。
- **纠正 2026-11 的旧结论**：「定时 = 预售 = 要真付定金」不成立。淘宝当前主流定时形态是**限量全款预售**：全款、无定金、开售前可加购但结算不可用。它的定时以**天**计，不是分钟级。
- **开售前购物车里勾不上**（预约/预售都一样），开售后要人勾一次；程序只会在窗口内反复重试结算与提交。真正秒级的限量品走购物车几乎无解，详情页「立即购买」到点才可用（**未支持**，属独立功能）。
- 脚本 `scripts/android-seckill-test.sh`：
  - `list` / `prepare --fixture <id>`：打开商品 → 加购 → 停在购物车；
  - `run --fixture <id>`：按夹具 `saleTime` 自动睡到 T-60 再启动，**到点前停掉、不会下单**；
  - `run --fixture <id> --at +3m`：用「现在+3分」当目标时间，不必等真实开售；
  - `run --fixture <id> --submit-order [--cancel-order]`：真下单；**创建订单不付款不扣钱**，`--cancel-order` 会去「待付款」自动取消；
  - `logs` / `cart` / `orders`。脚本会自动把本服务补进 `enabled_accessibility_services`（ROM 会在 force-stop/更新后踢掉）。
- 不带 `--fixture` 时仍是原来的京东长期在售夹具（维达抽纸 SKU `100021015676`，约 ¥17.9），随时可跑「就位 → 确认页 → 提交」。
- 预期日志：`网络时间校准成功` → `点击结算 @ x,y` → `确认订单页已就位…` → 到点 `点击提交（第 N 次）` → `收银台 / 确认付款`。
- **京东秒杀走的是秒杀页直购**（场次 tab + 「抢」按钮 + 「立即购买」倒计时），不进购物车，因此测不到本应用的购物车流程；要支持它得单独做「秒杀页直购」功能。
- **淘宝「聚划算」是每天固定的到点开售入口**：两个场次 **早 10:00 / 晚 20:30**（页面显示「晚8点半抢 · N 小时后开始」）。场次商品可加购；但**场次清单开场前 1~2 小时才放出**，之前点进场次 tab 显示「已没有内容啦」，所以无法提前加购。
- 淘宝有频控：短时间连续开深链/点页面会返回「亲，访问太频繁，请稍后重试」，冷却约 2 分钟；脚本化测试要控制节奏。
- 京东秒杀（场次 10/12/16/18/20 点）与「大牌1元抢新」都是**直购**：按钮是「立即抢 / 提醒我」，**没有加购入口**，无法用它测购物车流程。
- 坐标必须用 `uiautomator dump` 的 bounds 换算：**截图在聊天窗口里是缩放过的，肉眼估坐标会偏 150px 以上**（踩过）。
- 购物车「合计」不含运费（测试单 ¥17.9，下单后变成 ¥23.9，含 ¥6 运费），这是京东正常行为，不是程序算错。
`adb shell am start -a android.intent.action.VIEW -d "openapp.jdmobile://virtual?params=%7B%22category%22%3A%22jump%22%2C%22des%22%3A%22cart%22%7D"` 可直接打开京东购物车；**跑抢购任务有真实下单风险，只在确认购物车里的商品不可购买时测试**。

### 已知问题 / 注意事项（重要）
- **无障碍服务会被系统关闭**（尤其**重装/更新 App 后**、被省电清理、重启后）。抢购前必须确认 App 内显示「已开启」；**抢购前不要重装或强制停止 App**。
- 就绪判断用**服务实例**（`AutoBuyAccessibilityService.instance`）而非读 `Settings`（MIUI 下不准）。用 adb 写 `enabled_accessibility_services` 只是临时手段，**用户需在系统设置里手动开启**才稳。
- **自动勾选已移除**（0.3.7）：复选框和商品图里都有圆，图像识别分不清是哪一个——点商品图会跳详情页来回跳（#29），点到别的商品会把它带进结算。现在**商品由用户自己在购物车里勾选**，程序只负责结算与提交；**不要**再把"自动勾选/按关键词选品"加回来，除非能真正识别商品标题。注意淘宝购物车已读不到「合计」（见下），这道校验只能靠用户自律。
- **购物车只留要抢的商品**：程序结算的是用户已勾选的商品，其他已勾选商品会一起进订单。
- **预约/预售**：开售前勾不上，需要用户在开售后自己勾；程序只会在窗口内反复重试结算与提交。
- **发现层实测（2026-11-22）：没有凭证就做不到「自动批量找定时商品」**。逐个入口验过：

  | 入口 | 结果 |
  |---|---|
  | 京东 H5 搜索 `so.m.jd.com` | 反爬，返回 2.7KB「京东验证」页 |
  | 京东 App 搜索深链（keyword/keyWord/words/searchKey/searchList 五种拼写） | 都只落到 `SearchActivity` 搜索首页，**关键词不注入**；连开 5 次直接弹 `JDRiskHandleActivity` 滑块风控 |
  | 京东首页 | 91 个文字节点里**没有「预售」入口** |
  | 京东底部「新品」页 | 306 个文字节点、含「¥9.9预约」，但列表是**虚拟节点 `bounds=[0,0][0,0]`**，点不进去 |
  | 淘宝搜索页（`s.taobao.com` 深链） | AI 优先界面，商品区自绘、**0 文字节点**；`查看全部` 只展开摘要 |
  | 淘宝搜索**分享短链**（`m.tb.cn/h.xxx` → `s.m.taobao.com/h5?…`） | 同样落到 `com.taobao.search.uniform.SearchActivity`，dump 只有「关键词 + AI助手」两个文字节点（2026-11-24 实测，`from=input` 与 `tb2025+layeredSrp` 两种参数看到的结果页一模一样）；浏览器打开则要求登录 |
  | 淘宝收藏夹 | `WeexV2Activity`，**0 节点** |
  | 淘宝聚划算 | `BrowserActivity`(WebView)，**0 内容节点** |
  | 淘宝首页推荐流 | ✅ 有文字、可点，但**翻 10 屏只出 1 条真预售、0 条「即将开售」** |
  | 淘宝商品详情页 | ✅ 完美（判定层，含精确开售时间） |

  结论：**可自动枚举的面只有淘宝首页流**（密度约 1 条预售/10 屏，且基本不含定时）；其余全靠 `ingest` 吃人给的链接。想做真正的批量发现，只有开放平台/联盟 API 一条路（需实名凭证）。
  诊断工具：`scripts/fixtures.py probe-surface {tb-favorite|tb-shop|jd-reserve|jd-channel}`，输出「文字节点/卡片/开售/预售/定金」五项，别再用肉眼看截图猜。
- **`channelSrp` 的省钱卡值会把搜索接口清成 0 条**（2026-11-24 实测，直接重放 mtop）：`mtop.relationrecommend.WirelessRecommend.recommend` 里传 `"channelSrp":"shengqianka_gmv_open"`（开卡）或 `shengqianka_gmv_unopen`（未开卡），**任何关键词**（灵魂倒影吊卡 / 抽纸 / 纸巾）都是 `totalResults=0` + 空态模板「没有搜索结果」；把该字段删掉、或换成 `shengqianka` / 空串，立刻恢复（抽纸 27 万条）。所以「从省钱卡渠道搜到自己的商品」在接口层就是死路，省钱卡页面的商品流来自另一套接口，SRP 拿不到。复现要点：`sign = md5(_m_h5_tk 的 token & t & appKey & data)`，改 data 必须重算 sign，接口不额外校验 header。
- **淘宝省钱卡券的可买范围是「当天券页白名单」**（社区 2026-08 汇总，平台未公开规则）：券页有足迹列表（灰度测试、仅受邀可见、算法推）时能买「自己浏览收藏过的宝贝」，没有就只能用页面推的推荐商品；7 月底起改为「**仅当前页可用**」，旧的「加购过可凑」作废。结论：白名单外的商品任何刷法都没用，判断该商品能不能用券要看**结算页券能否出现**，不是看频道页面能不能刷到。
- **淘宝购物车对无障碍完全不可见**（2026-11 实测，`CartActivity`）：整页只有 15 个 `FrameLayout/LinearLayout` 容器节点，**0 个文字节点、0 个可点节点**（自绘列表）。后果：引擎读不到「合计」，也无法校验「商品是否已勾选、是不是 ¥0」。真机日志表现为 `点击结算 @ x,y` → `没有进入确认订单页：…合计 未读到`。
  - 现存兜底：结算按钮靠 `ImageMatcher.findColorCentroid` 找淘宝橙；进没进确认页靠确认页文案。
  - **因此「购物车只留要抢的商品」从建议变成硬要求**：金额校验这道保险在淘宝侧已经失效。
  - 测试脚本用前台 `Activity`（`CartActivity`）判断是否在购物车，不依赖文字。
- **淘宝搜索结果页是「AI 优先」界面**：`taobao://s.taobao.com/search?q=…` 能打开 `SearchActivity`，但商品列表是自定义渲染、**没有文字节点**，`查看全部` 只是展开 AI 摘要 —— 所以**不能靠搜索页枚举定时商品**（`fixtures.py` 的 `--blind` 网格盲点是兜底，命中率低）。
  - 目前可枚举的文字节点只有：首页推荐流、商品详情页。`ingest`（吃群分享）仍是最省的入口。
- **京东购物车和确认订单页也没有文字节点**（`uiautomator dump` 零文字）：读不到「合计」，也认不出「提交订单」。确认页靠**底部按钮形状**判断：京东确认页是**整行通栏**的「立即支付」（最长同色横线约占 0.87 屏宽），购物车「去结算」只在右下角（约 0.25）——见 `ImageMatcher.longestColorRun` + `bottomButtonBand`。**真机事故**：0.3.7 及以前认不出确认页，重试时把确认页的「立即支付」当结算按钮点了，直接生成订单并拉起支付。现在 `clickSettle` 发现通栏按钮就拒绝点击，且京东提交只点一次。
- **平台会弹风控/安全验证**（京东「检测到安全风险/快速验证」、淘宝滑块），自动化点不动；`SeckillEngine` 识别到就记一条日志并停手，需人工过验证后重开。
  - 淘宝确认订单页的滑块变体是 `com.alibaba.wireless.security...Ext2ContainerActivity` 里的 WebView，标题「**验证码拦截**」+「亲，请拖动下方滑块完成验证」+「向右滑动验证」，文字节点**有**，但 WebView 文字树比页面出现晚 1~2 秒。2026-11-25 真机：确认页显示「提交订单 ￥0」时连点 80 次触发滑块，判定「已提交」抢在滑块文字渲染之前，于是误报 submitted；靠 `verifyOrder` 回查「待付款」两次无订单兜住，**没有产生订单**。关键字已补上「验证码拦截」。
  - 教训：淘宝确认页长时间显示「￥0 / 暂时不能购买」时连点，会直接招来滑块；这类情况更适合停手等人工。
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
