# 🛒 淘宝京东自动抢购工具

[![GitHub Stars](https://img.shields.io/github/stars/HanphoneJan/AutoBuy?style=for-the-badge&color=FFD700&logo=github)](https://github.com/HanphoneJan/AutoBuy)
[![GitHub License](https://img.shields.io/github/license/HanphoneJan/AutoBuy?style=for-the-badge&color=4169E1&logo=github)](https://github.com/HanphoneJan/AutoBuy/blob/main/LICENSE)
[![Update Time](https://img.shields.io/badge/Last%20Update-2024.11.18-FF6347?style=for-the-badge&logo=clock)](https://github.com/HanphoneJan/AutoBuy/commits/main)

## 📌 项目简介

一款基于 Selenium 开发的 **Windows 网页端抢购工具**，支持淘宝、京东平台商品定时自动提交订单，解放双手，助力高效抢购～

### 核心特性

✅ 支持淘宝/京东双平台抢购  
✅ 无侵入式网页操作，安全稳定  
✅ 简单易用，无需复杂配置  
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

首次运行程序时，会自动检测 Chrome 版本并下载匹配的驱动到项目 `drivers/` 目录。

> 💡 提示：
> - 驱动下载目录：`drivers/`（自动创建）
> - Chrome 更新后会自动下载新版本驱动
> - `drivers/` 目录已在 `.gitignore` 中配置，不会被提交到仓库

### 启动 Web 应用

```bash
# 使用 uv 启动
uv run python app.py

# 或使用 pip 启动
python app.py

# 启动后访问
http://localhost:5000
```

### 打包为可执行文件

```bash
# 使用 uv 安装 PyInstaller
uv pip install pyinstaller

# 或使用 pip
pip install pyinstaller

# 打包淘宝抢购
pyinstaller -F TB.py

# 打包京东抢购
pyinstaller -F JD.py

# 带图标打包
pyinstaller -F -i logo.ico TB.py
```

打包完成后，可执行文件将生成在 `dist` 目录下。



## 🚀 使用教程

> ⚠️ 重要提醒：
> - 请提前 5 分钟启动程序！
> - 程序运行期间请勿关闭窗口
> - 遇到滑块验证等人工验证需手动完成

### 方式一：Web 界面使用（推荐）

1. **启动 Web 应用**
   ```bash
   # 使用 uv
   uv run python app.py

   # 或使用 pip
   python app.py
   ```

2. **访问应用**
   - 打开浏览器访问：`http://localhost:5000`
   - 在页面中选择抢购平台（淘宝/京东）
   - 设置抢购时间、刷新频率等参数
   - 点击「开始抢购」按钮

3. **操作步骤**
   - **淘宝**：程序自动打开淘宝登录页，扫码登录后进入购物车，手动选中商品等待抢购
   - **京东**：需提前在另一浏览器打开商品进入提交订单页，复制网址到程序打开的浏览器

4. **查看日志**
   - 页面底部实时显示操作日志
   - 可随时查看抢购进度

### 方式二：命令行使用

#### 🔴 淘宝抢购

```bash
# 使用 uv 运行
uv run python BB.py

# 或使用 pip 运行
python BB.py
```

**操作步骤：**
1. 按照提示输入抢购时间（严格遵循格式）：
   - 格式：`YYYY-MM-DD HH:MM:SS.ffffff`
   - 示例：`2024-01-01 01:34:00.000000`
   - 注意：月份/日期/小时/分钟需补零（如 01 月、09 日）
2. 程序自动打开 Chrome 浏览器并跳转淘宝登录页，**15秒内扫码登录**
3. 登录后自动进入购物车，手动选中要抢购的商品（20秒后自动结算）
4. 到达预定时间，程序自动提交订单
5. 及时在网页中输入密码完成付款

#### 🟡 京东抢购

```bash
# 使用 uv 运行
uv run python JD.py

# 或使用 pip 运行
python JD.py
```

**操作步骤：**
1. 按照提示输入抢购时间（格式同上）
2. 程序自动打开 Chrome 浏览器并跳转京东登录页，**尽快扫码登录**
3. 🔑 关键步骤：
   - 提前在另一浏览器打开京东，选好商品并进入「提交订单页」
   - 复制该页面网址，粘贴到程序打开的 Chrome 地址栏并访问
4. 到达预定时间，程序自动提交订单
5. 及时在网页中输入密码完成付款

### 方式三：使用打包的 .exe 文件

1. 双击打开对应的 `.exe` 文件（`淘宝抢购.exe` 或 `京东抢购.exe`）
2. 按照命令行方式的操作步骤使用

## ⚠️ 注意事项

1. 浏览器需保持最新版本，webdriver-manager 会自动匹配驱动版本
2. 抢购时间建议提前校准电脑系统时间（避免网络延迟）
3. 京东购物车存在反爬虫机制，务必按步骤提前进入提交订单页
4. 若遇到网页加载缓慢，可手动刷新页面（不影响定时逻辑）
5. 本工具仅用于学习交流，请勿用于恶意抢购或商业用途
6. 部分商品可能有平台风控限制，抢购成功率不保证
7. 使用 Web 界面时，请勿关闭浏览器窗口或刷新页面

## 🐛 问题反馈

若使用过程中遇到bug或有功能建议，欢迎通过以下方式反馈：

- GitHub Issues：[https://github.com/HanphoneJan/AutoBuy/issues](https://github.com/HanphoneJan/AutoBuy/issues)
- 项目仓库：[https://github.com/HanphoneJan/AutoBuy](https://github.com/HanphoneJan/AutoBuy)

---

<p align="center">
  <sub>🌟 觉得有用？欢迎 Star 支持一下～</sub>
</p>
