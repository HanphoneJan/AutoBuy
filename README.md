# 🛒 AutoBuy · 淘宝京东自动抢购

[![GitHub Stars](https://img.shields.io/github/stars/HanphoneJan/AutoBuy?style=for-the-badge&color=FFD700&logo=github)](https://github.com/HanphoneJan/AutoBuy)
[![Release](https://img.shields.io/github/v/release/HanphoneJan/AutoBuy?style=for-the-badge&color=2563EB&logo=android)](https://github.com/HanphoneJan/AutoBuy/releases)

AutoBuy 提供两种抢购方式：

| 方式 | 说明 | 推荐度 |
|------|------|--------|
| 📱 **原生 Android App** | 直接自动化淘宝/京东**官方 App**，免电脑、免 Root | ⭐ **推荐** |
| 💻 电脑网页版 | 基于 Selenium 驱动 Chrome 网页端 | 备选 |

> ⚠️ 本工具仅用于学习交流，自动化操作可能违反平台服务条款，**账号风险自负**，请勿商用或分发。

---

## 📱 原生 Android App（推荐）

**下载安装**：[Releases](https://github.com/HanphoneJan/AutoBuy/releases) 里的 `AutoBuy-*.apk`（直接安装，无需电脑）。

> 系统要求：**最低 Android 7.0**，**推荐 Android 11+**（图像识别依赖无障碍截图，Android 11+ 才可用）。

### 核心特性
- ✅ 直接操作**官方 App**（淘宝 / 京东），无需电脑、无需 Root
- ✅ **无障碍 + 图像识别**双方案：能点按钮、能认商品
- ✅ **免截图授权**（用无障碍截图，不像网页方案每次弹窗）
- ✅ 全流程：到点自动**就位 → 刷新 → 提交 → 回查订单**
- ✅ 支持**预售/预约/定时开卖**（到点自动重试）
- ✅ 底部导航四页：**抢购 / 指引 / 日志 / 我的**，步骤式引导
- ✅ **悬浮指引条**：抢购时在淘宝上实时显示当前阶段
- ✅ **日志导出**（诊断报告，便于排查求助）+ **检查更新**
- ✅ 浅色/深色主题（跟随系统/手动）

### 快速上手
1. **下载安装** APK（见上方 Releases 链接）。
2. 打开 AutoBuy → **「指引」页**按步骤准备：
   - 点「去开启」，在系统设置里打开 **AutoBuy 抢购服务**（无障碍，必需）；
   - 可选：点「去授权」开启**悬浮指引**。
3. 在**淘宝/京东里登录**，把要抢的商品**加入购物车**（预约/预售商品请先完成预约）。
4. 回到 AutoBuy 的**「抢购」页**：选平台 → 设抢购时间（可用「今天 12:00」快捷）→ 点**开始抢购**。
5. **切到目标 App 的购物车页**，保持屏幕常亮。到点后 AutoBuy 自动完成下单，你只需**去付款**。

### 注意事项
- **抢购前请确认 App 内「无障碍服务」显示为已开启**（系统有时会关闭它）；掉了就在系统设置里重新打开。
- **抢购前不要重装或强制停止 AutoBuy**，否则无障碍会掉线。
- 请提前几分钟开始，让 App 进入待命。
- 提交成功后请尽快到「待付款」完成付款。
- 抢购失败可到 **「日志」页导出诊断日志**（含设备/系统/权限信息）发给作者求助。

### 从源码构建
```bash
cd android-native/AutoBuy
./gradlew :app:assembleDebug      # 调试包
./gradlew :app:assembleRelease    # 发布包
```
需要 Android SDK（platform 35、build-tools 35）与 JDK 17+。详见 [`android-native/AutoBuy/README.md`](android-native/AutoBuy/README.md)。

---

## 💻 电脑网页版（Selenium）

基于 Selenium 驱动 Chrome 网页端，通过 Flask Web 界面控制，用户在浏览器里手动登录并选中购物车商品，程序到点自动提交。

### 环境要求
| 配置项 | 要求 |
|--------|------|
| 操作系统 | Windows 10 / 11 |
| Python | >=3.10（推荐 3.13） |
| 浏览器 | Google Chrome（最新版） |
| 包管理器 | uv（推荐）/ pip |

### 安装与启动
```bash
# 使用 uv（推荐）
pip install uv
uv sync
uv run python app.py

# 或使用 pip
python -m venv venv
.venv\Scripts\activate
pip install -r requirements.txt
python app.py

# 启动后访问
http://localhost:5000
```
Windows 用户也可直接双击 **`start.bat`** 一键启动。

ChromeDriver 由 `webdriver-manager` 自动下载管理，无需手动配置。

### 使用步骤
1. 启动应用，浏览器打开 `http://localhost:5000`。
2. 选择平台（淘宝/京东），设置抢购时间。
3. 点「开始抢购」→ 程序打开 Chrome → **扫码登录** → 点「确认登录」。
4. 手动进入购物车、勾选商品 → 点「确认购物车」。
5. 到达设定时间自动提交订单；在浏览器中手动完成付款。

> 重要：请提前 5 分钟启动；运行期间勿关闭窗口；遇到滑块验证需手动完成。

### 命令行模式
```bash
uv run python seckill.py jd --time "2025-03-19 11:00:00.000000"
uv run python seckill.py tb --time "2025-03-19 11:00:00.000000"
```

### 注意事项
- 仅支持可加入购物车/进入提交订单页的商品，需手动完成付款。
- 部分商品有平台风控限制，抢购成功率不保证。
- Web 界面会自动移除淘宝的反爬虫遮罩层。

---

## 📂 仓库结构
```
AutoBuy/
├── android-native/AutoBuy/   # 📱 原生 Android App（Kotlin + Compose + Material 3）— 推荐
├── app.py / seckill.py       # 💻 电脑网页版（Selenium）
├── static/ templates/        # 网页版前端
└── README.md
```

## 🐛 问题反馈
- GitHub Issues：https://github.com/HanphoneJan/AutoBuy/issues
- 安卓 App 用户请尽量附上 **「日志」页导出的诊断日志**，便于定位。

---

## Star History

<a href="https://www.star-history.com/?repos=HanphoneJan%2FAutoBuy&type=date&legend=top-left">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/image?repos=HanphoneJan%2FAutoBuy&type=date&theme=dark&legend=top-left" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/image?repos=HanphoneJan%2FAutoBuy&type=date&theme=light&legend=top-left" />
   <img alt="Star History Chart" src="https://api.star-history.com/image?repos=HanphoneJan%2FAutoBuy&type=date&legend=top-left" />
 </picture>
</a>
