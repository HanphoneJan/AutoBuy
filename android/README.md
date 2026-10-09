# AutoBuy Android（Auto.js 原型）

淘宝/京东**官方 App** 的自动化原型，基于 **Auto.js / AutoX.js**（无障碍 + 图像识别），
作为主仓库 PC 版（Selenium）的移动端补充。

> ⚠️ 仅用于学习交流。自动化操作官方 App 可能违反平台服务条款，账号风险自负，请勿商用或分发。

## 为什么先做 Auto.js 而不是原生 App
先用最低成本验证"安卓自动化链路是否可行"，再决定是否投入原生开发。
真机勘察结论见 [`docs/device-survey.md`](docs/device-survey.md)，要点：

- 淘宝**首页 / 我的淘宝**是原生页，无障碍可读；
- 淘宝**购物车 / 商品详情**是 Weex/H5，**无障碍树读不到内容**；
- 所以淘宝流程必须以**图像识别 + 坐标点击**为主，无障碍只用于导航和订单校验。

## 环境
- 闲置安卓真机（建议非 root；本仓库勘察机为 Redmi Note 12 Turbo / Android 15）
- AutoX.js（或 AutoJs6）运行时
- 系统权限：无障碍服务、悬浮窗、后台运行、自启动

## 安装（设备侧）
1. 安装 AutoX.js 运行时 APK。
2. 设置 → 无障碍 → 开启 AutoX.js。
3. 授予悬浮窗、后台弹窗、自启动权限（MIUI/HyperOS 还需允许"后台弹出界面"）。
4. 把本仓库 `android/autojs/` 目录导入设备（AutoX.js 打开 `taobao/main.js` 即可）。

## 使用
1. 编辑 [`autojs/taobao/config.json`](autojs/taobao/config.json)：
   - `target_time`：抢购时间，`YYYY-MM-DD HH:MM:SS.mmm`
   - `keyword`：目标商品关键词（日志/校验用）
   - `steps`：到点后依次执行的动作（`text` / `desc` / `image` / `point`）
2. 提前把淘宝 App 停在**购物车页，并手动勾选好目标商品**（购物车是 Weex，脚本读不到商品，无法自动选中）。
3. 在设备上运行 `autojs/taobao/main.js`，观察悬浮窗日志。

### 实测可用的淘宝流程（本机 1080x2400）
```
购物车（人工预选商品） --坐标点击「结算」(875,2142)--> 确认订单页
确认订单页 --content-desc 点击「立即支付」--> 提交
```
- 购物车「结算」用 `point`（Weex 无无障碍）；确认订单页「立即支付」用 `desc`（无障碍可读），
  比坐标稳定得多。
- 详见 [`docs/device-survey.md`](docs/device-survey.md)。

### 记录坐标 / 模板图
因为淘宝购物车不可无障碍读取，需要你**在设备上录制**：
- 坐标：用 Auto.js 的"悬浮窗 → 坐标"或开发者选项"指针位置"，把"全选/结算/提交订单"
  的屏幕坐标填进 `config.json` 的 `point` 步骤；
- 模板图：截图裁出按钮小图，放到 `autojs/taobao/images/`，用 `image` 步骤引用。

## 目录结构
```
android/
├── README.md                 # 本文件
├── docs/
│   └── device-survey.md      # 真机勘察结论（含证据）
└── autojs/
    └── taobao/
        ├── main.js           # 入口：读配置 → 校准时间 → 等待 → 执行步骤
        ├── config.json       # 用户配置
        ├── images/           # 按钮模板图（自备）
        └── lib/
            ├── time.js       # 网络时间校准
            ├── log.js        # 悬浮窗日志
            ├── ui.js         # text / point / image 三种定位与点击
            └── flow.js       # 等待目标时间 + 步骤状态机
```

## 现状
- [x] 真机勘察，确认技术路线（购物车坐标 + 确认页 content-desc 的混合方案）
- [x] 脚本框架（时间校准 / 日志 / 定位 / 步骤状态机）
- [x] AutoJs6 实机跑通（时间校准、悬浮窗、text/desc/point 定位与点击）
- [x] 确认订单页可用性验证（`descContains("立即支付")` 可定位）
- [ ] 完整抢购演练（真时间点、真商品）
- [ ] 京东适配

## 免责声明
本目录代码仅供学习交流，不对抢购结果、账号安全或平台风控作任何保证。
