# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

基于 Selenium 的淘宝/京东自动抢购工具。通过 Flask Web 界面控制 Chrome 浏览器自动化操作，用户在浏览器中手动完成登录和购物车选择，程序在到达设定时间后自动提交订单。

## Commands

```bash
# 安装依赖（推荐 uv）
uv sync

# 启动 Web 应用
uv run python app.py
# 或直接
python app.py

# 命令行模式（跳过 Web 界面）
uv run python seckill.py jd --time "2025-03-19 11:00:00.000000"
uv run python seckill.py tb --time "2025-03-19 11:00:00.000000"

# 启动后访问
# http://localhost:5000
```

Windows 用户可直接双击 `start.bat` 一键启动。

## Architecture

### 后端两层结构

**`app.py`** — Flask Web 层
- 提供页面路由（`/`, `/help`）和 REST API
- `TaskManager` 类管理多任务生命周期（创建、状态追踪、日志缓存、停止/清理）
- SSE (`/api/tasks/<id>/logs`) 实现实时日志推送到前端
- 每个抢购任务在独立 daemon 线程中运行，通过 `run_seckill_task()` 桥接到 `SeckillWorker`

**`seckill.py`** — 核心自动化引擎（可独立运行）
- `PlatformConfig` — dataclass，定义每个平台的 URL、按钮选择器、购物车地址等
- `BrowserManager` — Chrome 驱动创建、反检测（隐藏 `navigator.webdriver`）、遮罩层移除脚本注入
- `TimeManager` — 从京东/淘宝 API 获取服务器时间戳，用于精准卡点
- `SeckillWorker` — 主工作器，串联完整抢购流程：
  1. 创建浏览器 → 导航到平台首页
  2. 等待用户手动扫码登录 → 前端"确认登录"按钮
  3. 等待用户手动进入购物车/结算页 → 前端"确认购物车"按钮
  4. 轮询网络时间等待到达目标时间（提前15秒开始刷新商品状态）
  5. 循环点击结算/提交按钮直到成功或超时（300次重试）
  6. `_verify_order_submitted()` 通过 URL 跳转和页面文本双重验证订单是否提交成功

### 前端

- `templates/index.html` — 主页面：侧边栏（平台选择 + 操作说明）、时间设置区、5步进度条（驱动检查→登录确认→购物车确认→执行抢购→完成）、实时日志区
- `static/js/seckill.js` — 页面加载时预检查 ChromeDriver 下载；通过 SSE 监听后端日志流；根据日志关键词自动推进进度条步骤；启用/禁用确认按钮
- `static/css/style.css` — 页面样式

### 数据流

```
用户操作 → fetch API → Flask route → 创建 Task → daemon 线程运行 SeckillWorker
                                                      ↓
浏览器 ← SSE ← TaskManager.add_log() ←────────── log_callback
```

用户确认（登录/购物车）通过 `POST /api/tasks/<id>/confirm` 设置 `SeckillWorker._confirm_states` 字典，worker 的 `_wait_for_user_confirm()` 轮询该字典。

### 关键细节

- 驱动通过 `webdriver-manager` 自动管理，优先使用本地缓存 (`~/.wdm/drivers/chromedriver/win64/`)，匹配本机 Chrome 大版本号
- 浏览器窗口定位到屏幕右侧（`set_window_position(1400, 0)`），避免遮挡前端界面
- 淘宝平台在购物车确认前不会导航到购物车页，而是让用户手动从首页进入购物车再点结算；京东会直接导航到 `trade.jd.com` 订单页
- 遮罩层移除脚本在页面加载时注入 (`Page.addScriptToEvaluateOnNewDocument`) 并每 500ms 循环执行
- 通过 CDP 隐藏 `navigator.webdriver` 属性以规避反爬检测
