# 🛒 淘宝京东自动抢购工具

[![GitHub Stars](https://img.shields.io/github/stars/HanphoneJan/AutoBuy?style=for-the-badge&color=FFD700&logo=github)](https://github.com/HanphoneJan/AutoBuy)
[![GitHub License](https://img.shields.io/github/license/HanphoneJan/AutoBuy?style=for-the-badge&color=4169E1&logo=github)](https://github.com/HanphoneJan/AutoBuy/blob/main/LICENSE)
[![Update Time](https://img.shields.io/badge/Last%20Update-2024.11.18-FF6347?style=for-the-badge&logo=clock)](https://github.com/HanphoneJan/AutoBuy/commits/main)

## 📌 项目简介

一款基于 Selenium 开发的 **Windows 网页端抢购工具**，支持淘宝、京东平台商品定时自动提交订单，解放双手，助力高效抢购～

### 核心特性

✅ 支持淘宝/京东双平台抢购
✅ 现代化 Web 界面，实时日志显示
✅ 驱动自动下载检测，无需手动配置
✅ 步骤式进度条，用户完全手动控制
✅ 自动移除淘宝反爬虫遮罩层
✅ 无侵入式网页操作，安全稳定
⚠️ 注意：仅支持可添加购物车/进入提交订单页的商品，需手动完成付款步骤

## 🌐 支持平台

- 淘宝移动端网页：[https://main.m.taobao.com/](https://main.m.taobao.com/)
- 京东移动端网页：[https://m.jd.com/](https://m.jd.com/)

## 🛠️ 环境配置

### 基础环境

| 配置项      | 要求                                    |
| ----------- | --------------------------------------- |
| 操作系统    | Windows 10 / Windows 11                 |
| 开发工具    | PyCharm (Professional) / 任意Python IDE |
| Python 版本 | >=3.8（推荐 3.12）                     |
| 浏览器      | Google Chrome（最新版）                 |
| 包管理器    | uv（推荐）/ pip                        |

### 依赖安装

#### 方式一：使用 uv（推荐）

```bash
# 1. 安装 uv（如果尚未安装）
pip install uv

# 2. 使用 uv 同步项目依赖
uv sync

# 3. 激活虚拟环境（uv 自动创建）
# Windows:
.venv\Scripts\activate
# 或使用 uv run:
uv run python app.py
```

#### 方式二：使用 pip（兼容）

```bash
# 1. 创建虚拟环境（推荐）
python -m venv venv

# 2. 激活虚拟环境
# Windows:
venv\Scripts\activate
# Linux/Mac:
source venv/bin/activate

# 3. 安装依赖
pip install -r requirements.txt
```

### 浏览器驱动

项目使用 `webdriver-manager` 自动管理 ChromeDriver，**无需手动下载和配置**。

首次启动 Web 应用时，会自动：
1. 检测 Chrome 浏览器版本
2. 下载匹配的 ChromeDriver
3. 显示详细的检查和下载过程日志

> 💡 提示：
> - 驱动下载在页面加载时自动进行，并在实时日志中显示
> - Chrome 更新后会自动下载新版本驱动
> - 驱动缓存目录由 webdriver-manager 自动管理

### 启动 Web 应用

```bash
# 使用 uv 启动
uv run python app.py

# 或使用 pip 启动
python app.py

# 启动后访问
http://localhost:5000
```

## 🚀 使用教程

### 快速启动（推荐新手）

**Windows 用户双击 `start.bat` 即可自动启动！**

该脚本会自动：
- ✓ 检查 Python 环境
- ✓ 检查并安装依赖
- ✓ 检查 Chrome 浏览器
- ✓ 启动 Web 应用

**注意**：
- 首次启动需要安装依赖，请保持网络连接
- 启动后浏览器会自动打开 `http://localhost:5000`
- 请勿关闭黑色命令行窗口，否则服务将停止

### 详细启动步骤

如果使用快速启动脚本遇到问题，可以按照以下步骤手动启动：

#### 1. 安装 Python（如果尚未安装）
   - 下载：https://www.python.org/downloads/
   - 安装时勾选 "Add Python to PATH"

#### 2. 安装 Chrome 浏览器（如果尚未安装）
   - 下载：https://www.google.com/chrome/

#### 3. 安装依赖
   ```bash
   # 使用 uv（推荐）
   uv sync

   # 或使用 pip
   pip install -r requirements.txt
   ```

#### 4. 启动应用
   ```bash
   # 使用 uv
   uv run python app.py

   # 或使用 pip
   python app.py
   ```

#### 5. 访问应用
   - 浏览器打开：`http://localhost:5000`

> ⚠️ 重要提醒：
> - 请提前 5 分钟启动程序！
> - 程序运行期间请勿关闭窗口
> - 遇到滑块验证等人工验证需手动完成

### 使用步骤

1. **启动应用**
   - 双击 `start.bat`（Windows）或按照上面的步骤手动启动

2. **访问应用**
   - 浏览器会自动打开 `http://localhost:5000`
   - 页面加载时会自动检查并下载 ChromeDriver（在日志中查看进度）

3. **选择平台**
   - 在页面左侧选择淘宝或京东

4. **设置抢购时间**
   - 京东：必须设置具体的抢购时间
   - 淘宝：可选设置时间，也可直接开始

5. **开始抢购流程**
   - 点击「开始抢购」按钮
   - 程序会打开 Chrome 浏览器
   - 扫码登录后，点击界面上的「确认登录」按钮
   - 手动进入购物车，选中商品后点击「确认购物车」按钮
   - 到达设定时间自动点击提交订单
   - 在浏览器中手动输入密码完成付款
   - 任务完成后点击「关闭浏览器」按钮

6. **查看进度**
   - 实时日志：页面底部显示所有操作日志
   - 进度条：显示当前抢购阶段（驱动检查→登录确认→购物车确认→执行抢购→抢购完成）

## ⚠️ 注意事项

1. 浏览器需保持最新版本，webdriver-manager 会自动匹配驱动版本
2. 抢购时间建议提前校准电脑系统时间（避免网络延迟）
3. Web 界面会自动移除淘宝的反爬虫遮罩层
4. 若遇到网页加载缓慢，可手动刷新页面（不影响定时逻辑）
5. 本工具仅用于学习交流，请勿用于恶意抢购或商业用途
6. 部分商品可能有平台风控限制，抢购成功率不保证
7. 使用 Web 界面时，请勿关闭浏览器窗口或刷新页面
8. 浏览器窗口会自动定位到屏幕右侧，避免遮挡前端界面

## 🐛 问题反馈

若使用过程中遇到bug或有功能建议，欢迎通过以下方式反馈：

- GitHub Issues：[https://github.com/HanphoneJan/AutoBuy/issues](https://github.com/HanphoneJan/AutoBuy/issues)
- 项目仓库：[https://github.com/HanphoneJan/AutoBuy](https://github.com/HanphoneJan/AutoBuy)

---

<p align="center">
  <sub>🌟 觉得有用？欢迎 Star 支持一下～</sub>
</p>
