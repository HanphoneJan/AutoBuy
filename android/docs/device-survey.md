# 真机勘察结论（淘宝）

## 设备
| 项 | 值 |
|----|----|
| 机型 | Redmi Note 12 Turbo（`marble` / 23049RAD8C） |
| 系统 | Android 15（SDK 35），MIUI/HyperOS V816 |
| 分辨率 | 1080 x 2400，密度 440 |
| ABI | arm64-v8a |
| 淘宝 | `com.taobao.taobao` 已安装且已登录 |
| 运行时 | AutoJs6 v6.7.0（arm64-v8a） |

## 方法
`adb` + `uiautomator dump` 导出无障碍树（与 Auto.js `selector()` 同一棵树），
并用 AutoJs6 实机运行脚本验证定位与点击。

```
adb shell uiautomator dump /sdcard/dump.xml && adb pull /sdcard/dump.xml
```

## 页面可用性（实测）

| 页面 | 根节点 / Activity | 可用方式 | 关键元素 |
|------|------------------|---------|---------|
| 首页 | `homepage_root_layout` | ✅ text | 底部 Tab（首页/视频/消息/购物车/我的淘宝） |
| 我的淘宝 | `Welcome` | ✅ text | 我的订单 / 待付款 / 待发货… |
| 购物车 | `icart_weex_root_view` | ❌ 无障碍（**Weex**） | 只能坐标/图像：全选≈(170,2140)、结算≈(875,2142) |
| 商品详情 | `NewDetailActivity` | ❌（H5/Weex，dump 停在"加载中."） | — |
| **确认订单** | `TBBuyActivity` | ✅ **content-desc** | `立即支付￥xxx`（实测可定位） |

## 实测验证
1. `text("购物车").findOne()` 成功，坐标点击后 `icart_weex_root_view` 出现 → 无障碍导航可用。
2. 购物车是 Weex，无障碍读不到内容；用坐标点击「全选」「结算」成功进入确认订单页。
3. 确认订单页 `descContains("立即支付").findOne()` 成功，返回 `立即支付￥4707.04 @ 540,2284`。
   **提交按钮可用无障碍 content-desc 定位**（未点击，避免下单）。
4. 网络时间校准成功（实测偏移 −62ms），到点触发准确（目标前 3 秒进入窗口）。

## 结论：可行的混合方案
- **购物车（Weex）**：坐标点击「结算」；目标商品建议**运行前手动勾选**（脚本读不到商品，无法自动选中）。
- **确认订单页**：`descContains("立即支付")` 无障碍点击提交，稳定且不依赖坐标。
- 首页/账户页：无障碍 text，可用于导航；"我的淘宝 → 待付款"可做订单结果校验。

## 已知限制
- 购物车 Weex 内容不可读 → 无法自动定位并勾选指定商品，需人工预选。
- 坐标依赖分辨率/布局，换机型需重录；可用模板图（`image`）替代。
- 尚未验证淘宝**秒杀频道**页（可能同为 H5）。

> 勘察时间：2026-10-09。淘宝版本更新后需重新勘察。
