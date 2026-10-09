# 真机勘察结论（淘宝）

## 设备
| 项 | 值 |
|----|----|
| 机型 | Redmi Note 12 Turbo（`marble` / 23049RAD8C） |
| 系统 | Android 15（SDK 35），MIUI/HyperOS V816 |
| 分辨率 | 1080 x 2400，密度 440 |
| ABI | arm64-v8a |
| 淘宝 | `com.taobao.taobao` 已安装且已登录 |
| 无障碍服务 | 勘察时未启用 |

## 方法
通过 `adb` + `uiautomator dump` 导出无障碍树（与 Auto.js `selector()` 看到的是同一棵树）。

```
adb shell uiautomator dump /sdcard/dump.xml && adb pull /sdcard/dump.xml
```

## 结果

| 页面 | Activity / 根节点 | 无障碍可用性 | 说明 |
|------|------------------|:---:|------|
| 首页 | `com.taobao.tao.welcome.Welcome` / `homepage_root_layout` | ✅ | 商品卡、底部 Tab（首页/视频/消息/购物车/我的淘宝）都有 text |
| 我的淘宝 | `Welcome` | ✅ | "我的订单 / 待付款 / 待发货 / 待收货…" 均可读 |
| 购物车 | `icart_weex_root_view` | ❌ | **Weex 渲染，树里只有容器，读不到商品/全选/结算** |
| 商品详情 | `NewDetailActivity` | ❌ | H5/Weex，dump 长期停在"加载中."，无有效节点 |

## 结论

1. **淘宝购买流程页面（购物车、商品详情）无法用无障碍节点定位** —— 纯 `text()/id()` 的
   Auto.js 脚本在淘宝行不通。
2. 可行形态是**图像识别 + 坐标点击**（Auto.js 的 `images.findImage` / `click(x, y)`），
   无障碍仅用于：打开购物车 Tab、进入"我的淘宝 → 待付款"做**订单结果校验**。
3. 因此 `lib/ui.js` 同时提供 text / point / image 三种动作；淘宝流程以 image/point 为主。

## 待验证
- 确认订单页（`确认订单` / 提交订单按钮）是否同样为 H5/Weex。
- 淘宝秒杀频道页的无障碍可用性。
- AutoX.js 运行时实测（本勘察用 adb，未安装 AutoX.js）。

> 勘察时间：2026-10-09。淘宝版本更新后需重新勘察。
