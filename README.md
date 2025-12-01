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
| Python 版本 | 3.12（推荐）                            |
| 浏览器      | Google Chrome（最新版）                 |
| 浏览器驱动  | ChromeDriver（需与Chrome版本匹配）      |



### 依赖安装

```bash
# 安装核心依赖库
pip install selenium
```

### 驱动下载

- [ChromeDriver 官方下载](https://googlechromelabs.github.io/chrome-for-testing/)（需匹配Chrome版本）
- [EdgeDriver 备用下载](https://developer.microsoft.com/en-us/microsoft-edge/tools/webdriver/?form=MA13LH)（若使用Edge浏览器）

> 💡 提示：驱动下载后需放入Python安装目录，或在代码中指定驱动路径

## 📦 代码打包

如需生成可执行文件（.exe），执行以下命令：

```bash
# 1. 安装打包工具
pip install pyinstaller

# 2. 无图标打包（淘宝抢购）
pyinstaller -F TB.py

# 3. 无图标打包（京东抢购）
pyinstaller -F JD.py

# 4. 带图标打包（替换为你的图标路径）
pyinstaller -F -i your_icon.ico TB.py
```

打包完成后，可执行文件将生成在 `dist` 目录下。

## 🚀 使用教程

> ⚠️ 重要提醒：请提前 5 分钟启动程序！程序运行期间请勿关闭窗口，遇到滑块验证等人工验证需手动完成。

### 🔴 淘宝抢购步骤

1. 双击打开 `淘宝抢购.exe` 文件
2. 按照提示输入抢购时间（严格遵循格式）：
   - 格式：`YYYY-MM-DD HH:MM:SS.ffffff`
   - 示例：`2024-01-01 01:34:00.000000`
   - 注意：月份/日期/小时/分钟需补零（如 01 月、09 日）
3. 程序自动打开 Chrome 浏览器并跳转淘宝登录页，**15秒内扫码登录**
4. 登录后自动进入购物车，手动选中要抢购的商品（20秒后自动结算）
5. 到达预定时间，程序自动提交订单
6. 及时在网页中输入密码完成付款

### 🟡 京东抢购步骤

1. 双击打开 `京东抢购.exe` 文件
2. 按照提示输入抢购时间（格式同上）
3. 程序自动打开 Chrome 浏览器并跳转京东登录页，**尽快扫码登录**
4. 🔑 关键步骤：
   - 提前在另一浏览器打开京东，选好商品并进入「提交订单页」
   - 复制该页面网址，粘贴到程序打开的 Chrome 地址栏并访问
5. 到达预定时间，程序自动提交订单
6. 及时在网页中输入密码完成付款

## ⚠️ 注意事项

1. 浏览器需保持最新版本，驱动与浏览器版本必须匹配，否则会报错
2. 抢购时间建议提前校准电脑系统时间（避免网络延迟）
3. 京东购物车存在反爬虫机制，务必按步骤提前进入提交订单页
4. 若遇到网页加载缓慢，可手动刷新页面（不影响定时逻辑）
5. 本工具仅用于学习交流，请勿用于恶意抢购或商业用途
6. 部分商品可能有平台风控限制，抢购成功率不保证

## 🐛 问题反馈

若使用过程中遇到bug或有功能建议，欢迎通过以下方式反馈：

- GitHub Issues：[https://github.com/HanphoneJan/AutoBuy/issues](https://github.com/HanphoneJan/AutoBuy/issues)
- 项目仓库：[https://github.com/HanphoneJan/AutoBuy](https://github.com/HanphoneJan/AutoBuy)

---

<p align="center">
  <sub>🌟 觉得有用？欢迎 Star 支持一下～</sub>
</p>
