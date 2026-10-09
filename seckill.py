"""
统一抢购逻辑模块
支持京东、淘宝、哔哩哔哩等多个平台的抢购
"""

import time
import datetime
import random
import platform
import re
import requests
import logging
from typing import Callable, Any
from dataclasses import dataclass
from selenium.webdriver.support.ui import WebDriverWait
from selenium.webdriver.support import expected_conditions as EC
from selenium import webdriver
from selenium.webdriver.common.by import By
from selenium.webdriver.chrome.service import Service as ChromeService
from webdriver_manager.chrome import ChromeDriverManager
from selenium.common.exceptions import (
    NoSuchElementException, TimeoutException
)
from selenium.webdriver.chrome.options import Options
import os

# 设置项目根目录
PROJECT_DIR = os.path.dirname(os.path.abspath(__file__))

# 持久化浏览器用户目录：保存 Cookie/历史，避免每次都是"全新登录痕迹"的异常画像
PROFILE_DIR = os.path.join(PROJECT_DIR, 'chrome_profile')

# 配置日志
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s',
    datefmt='%Y-%m-%d %H:%M:%S'
)
logger = logging.getLogger(__name__)


@dataclass
class PlatformConfig:
    """平台配置"""
    name: str
    url: str
    login_text: str
    cart_url: str
    settle_button_class: str
    submit_button_css: str
    confirm_button_css: str | None = None
    # 订单列表地址（用于提交成功后交叉验证，避免 #11 的假成功）
    order_list_url: str = ''


# 平台配置
PLATFORM_CONFIGS = {
    'jd': PlatformConfig(
        name='京东',
        url='https://www.jd.com',
        login_text='你好，请登录',
        cart_url='https://trade.jd.com/shopping/order/getOrderInfo.action',
        settle_button_class='checkout-submit',
        submit_button_css='.checkout-submit',
        order_list_url='https://order.jd.com/center/list.action'
    ),
    'tb': PlatformConfig(
        name='淘宝',
        url='https://www.taobao.com',
        login_text='亲，请登录',
        cart_url='https://cart.taobao.com/cart.htm',
        settle_button_class='btn--QDjHtErD',
        submit_button_css='.go-btn',
        confirm_button_css='.go-btn',
        order_list_url='https://buyertrade.taobao.com/trade/itemlist/list_bought_items.htm'
    ),
    'bb': PlatformConfig(
        name='哔哩哔哩',
        url='https://www.bilibili.com',
        login_text='登录',
        cart_url='',
        settle_button_class='btn--Jy7gBgTJ undefined',
        submit_button_css='.btn--Jy7gBgTJ.undefined',
        confirm_button_css='btn--QDjHtErD'
    )
}


class BrowserManager:
    """浏览器管理器"""

    # 反自动化检测脚本（在页面加载前注入，对抗淘宝/京东的 bot 检测）
    STEALTH_SCRIPT = """
        (function() {
            var define = function(obj, prop, value) {
                try { Object.defineProperty(obj, prop, { get: function() { return value; }, configurable: true }); } catch (e) {}
            };

            // 隐藏 webdriver 痕迹
            define(navigator, 'webdriver', undefined);
            define(navigator, 'languages', ['zh-CN', 'zh', 'en']);
            define(navigator, 'language', 'zh-CN');

            // 删除 ChromeDriver 注入的 CDC 调试标记（键名随机，需动态清理）
            try {
                Object.keys(window).forEach(function(key) {
                    if (/^(cdc_|\\$cdc_)/.test(key)) { try { delete window[key]; } catch (e) {} }
                });
            } catch (e) {}

            // 伪造 plugins，伪装成真实 Chrome 内置 PDF 插件（必须带 name/filename/description）
            var pluginSpecs = [
                { name: 'PDF Viewer', filename: 'internal-pdf-viewer', description: 'Portable Document Format' },
                { name: 'Chrome PDF Viewer', filename: 'internal-pdf-viewer', description: 'Portable Document Format' },
                { name: 'Chromium PDF Viewer', filename: 'internal-pdf-viewer', description: 'Portable Document Format' },
                { name: 'Microsoft Edge PDF Viewer', filename: 'internal-pdf-viewer', description: 'Portable Document Format' },
                { name: 'WebKit built-in PDF', filename: 'internal-pdf-viewer', description: 'Portable Document Format' }
            ];
            var plugins = pluginSpecs.map(function(spec) {
                return { name: spec.name, filename: spec.filename, description: spec.description, length: 1 };
            });
            plugins.item = function(i) { return this[i] || null; };
            plugins.namedItem = function(name) {
                for (var i = 0; i < this.length; i++) { if (this[i].name === name) return this[i]; }
                return null;
            };
            plugins.refresh = function() {};
            define(navigator, 'plugins', plugins);

            // 确保 chrome 对象存在且形态正常
            if (!window.chrome) { window.chrome = {}; }
            if (!window.chrome.runtime) { window.chrome.runtime = {}; }
            if (!window.chrome.app) {
                window.chrome.app = {
                    isInstalled: false,
                    InstallState: { DISABLED: 'disabled', INSTALLED: 'installed', NOT_INSTALLED: 'not_installed' },
                    RunningState: { CANNOT_RUN: 'cannot_run', READY_TO_RUN: 'ready_to_run', RUNNING: 'running' }
                };
            }
            if (!window.chrome.csi) { window.chrome.csi = function() { return {}; }; }
            if (!window.chrome.loadTimes) { window.chrome.loadTimes = function() { return {}; }; }
        })();
    """

    OVERLAY_REMOVAL_SCRIPT = """
        (function() {
            var isLoginElement = function(el) {
                // 检查是否是登录相关元素，不能误删
                var html = (el.innerHTML || '').toLowerCase();
                if (html.indexOf('登录') !== -1 || html.indexOf('扫码') !== -1 ||
                    html.indexOf('password') !== -1 || html.indexOf('qrcode') !== -1 ||
                    html.indexOf('iframe') !== -1) return true;
                // 包含交互控件的不能删
                if (el.querySelectorAll('input, button, a, iframe, img[src*="qrcode"], img[src*="qr"], [class*="login"], [class*="qrcode"]').length > 0) return true;
                return false;
            };

            var removeOverlay = function() {
                // --- 淘宝专用 ---
                var tw = document.querySelector('.J_MIDDLEWARE_FRAME_WIDGET');
                if (tw && !isLoginElement(tw)) tw.remove();
                document.querySelectorAll('[style*="z-index: 2147483647"]').forEach(function(el) {
                    if (!isLoginElement(el)) el.remove();
                });

                // --- 京东专用 ---
                document.querySelectorAll('.op-c5, .ui-widget-overlay').forEach(function(el) {
                    if (!isLoginElement(el)) el.remove();
                });

                // --- 通用全屏遮罩检测（只移除无交互内容的纯遮罩层） ---
                document.querySelectorAll('div, section, span').forEach(function(el) {
                    var style = getComputedStyle(el);
                    var zIndex = parseInt(style.zIndex, 10);
                    if (zIndex > 9999 &&
                        style.position === 'fixed' &&
                        style.top === '0px' &&
                        style.left === '0px' &&
                        el.offsetWidth >= window.innerWidth * 0.9 &&
                        el.offsetHeight >= window.innerHeight * 0.9) {
                        // 跳过包含交互内容或登录元素的弹窗
                        if (isLoginElement(el)) return;
                        var bg = style.backgroundColor;
                        if (bg === 'rgba(0, 0, 0, 0)' ||
                            bg === 'transparent' ||
                            (bg.indexOf('rgba') !== -1 && bg.indexOf(', 0)') !== -1) ||
                            bg === 'rgba(0, 0, 0, 0.5)' ||
                            bg === 'rgba(0, 0, 0, 0.6)' ||
                            bg === 'rgba(0, 0, 0, 0.7)' ||
                            bg === 'rgba(0, 0, 0, 0.8)') {
                            el.remove();
                        }
                    }
                });
            };
            removeOverlay();
            setInterval(removeOverlay, 500);
        })();
    """

    @staticmethod
    def _get_chrome_major_version() -> str:
        """获取本机 Chrome 主版本号，用于让 UA 与真实浏览器保持一致"""
        try:
            from webdriver_manager.core.os_manager import OperationSystemManager, ChromeType
            version = OperationSystemManager().get_browser_version_from_os(ChromeType.GOOGLE)
            if version:
                return version.split(".")[0]
        except Exception:
            pass
        return "148"

    @staticmethod
    def create_options(headless: bool = False, use_profile: bool = True) -> Options:
        """创建浏览器选项"""
        options = Options()
        if headless:
            options.add_argument("--headless=new")
        options.add_argument("--disable-gpu")
        # --no-sandbox 在 Windows 桌面并非必要，且是容器化/自动化环境的典型特征，仅在非 Windows 下保留
        if platform.system() != "Windows":
            options.add_argument("--no-sandbox")
        options.add_argument("--disable-blink-features=AutomationControlled")
        options.add_argument("--disable-features=Translate,OptimizationHints,MediaRouter,DialMediaRouteProvider")
        options.add_experimental_option("excludeSwitches", ["enable-automation"])
        options.add_experimental_option("useAutomationExtension", False)
        # 关闭"Chrome 正受到自动测试软件的控制"提示条
        options.add_argument("--disable-infobars")

        # UA 的平台与版本必须和真实浏览器一致，否则与 Client Hints 对不上，反而更容易被识别
        system = platform.system()
        if system == "Windows":
            platform_token = "Windows NT 10.0; Win64; x64"
        elif system == "Darwin":
            platform_token = "Macintosh; Intel Mac OS X 10_15_7"
        else:
            platform_token = "X11; Linux x86_64"
        major_version = BrowserManager._get_chrome_major_version()
        user_agent = (
            f"Mozilla/5.0 ({platform_token}) AppleWebKit/537.36 "
            f"(KHTML, like Gecko) Chrome/{major_version}.0.0.0 Safari/537.36"
        )
        options.add_argument(f'user-agent={user_agent}')

        # 使用持久化用户目录：保留 Cookie/历史，避免"全新 profile 立刻登录下单"的异常画像
        if use_profile:
            os.makedirs(PROFILE_DIR, exist_ok=True)
            options.add_argument(f"--user-data-dir={PROFILE_DIR}")
        return options

    @staticmethod
    def _find_cached_driver() -> str | None:
        """在本地缓存中查找与当前 Chrome 版本匹配的 chromedriver"""
        from webdriver_manager.core.os_manager import OperationSystemManager, ChromeType
        from packaging.version import parse as parse_version

        # 获取本机 Chrome 浏览器版本（无需联网）
        try:
            os_mgr = OperationSystemManager()
            browser_version = os_mgr.get_browser_version_from_os(ChromeType.GOOGLE)
        except Exception:
            return None

        if not browser_version:
            return None

        logger.info(f"本机 Chrome 浏览器版本: {browser_version}")
        major_version = browser_version.split(".")[0]

        cache_dir = os.path.join(os.path.expanduser("~"), ".wdm", "drivers", "chromedriver", "win64")
        if not os.path.isdir(cache_dir):
            return None

        # 在缓存目录中查找与当前浏览器主版本号匹配的驱动，选最新版本
        best_match = None
        best_version = None
        for entry in os.listdir(cache_dir):
            entry_path = os.path.join(cache_dir, entry)
            if not os.path.isdir(entry_path):
                continue
            # 版本目录名如 "148.0.7778.167"
            if not entry.startswith(major_version + "."):
                continue
            # 在版本目录下查找 chromedriver.exe
            for root, dirs, files in os.walk(entry_path):
                if "chromedriver.exe" in files:
                    try:
                        entry_ver = parse_version(entry)
                    except Exception:
                        entry_ver = None
                    if entry_ver and (best_version is None or entry_ver > best_version):
                        best_version = entry_ver
                        best_match = os.path.join(root, "chromedriver.exe")
                    break

        return best_match

    @staticmethod
    def create_driver(options: Options | None = None,
                      log_callback: Callable[[str], None] | None = None) -> webdriver.Chrome:
        """创建驱动"""
        log = log_callback or logger.info

        if options is None:
            options = BrowserManager.create_options()

        log("检查 Chrome 浏览器驱动...")
        # 优先使用本地缓存的驱动，避免不必要的网络请求
        driver_path = BrowserManager._find_cached_driver()
        if driver_path:
            log(f"使用本地缓存驱动: {driver_path}")
        else:
            log("本地无缓存驱动，在线下载中...")
            driver_path = ChromeDriverManager().install()
        log(f"驱动检查成功！驱动路径: {driver_path}")

        driver = webdriver.Chrome(service=ChromeService(driver_path), options=options)

        # 注入反检测脚本和遮罩移除脚本（页面加载前执行）
        driver.execute_cdp_cmd("Page.addScriptToEvaluateOnNewDocument", {
            "source": BrowserManager.STEALTH_SCRIPT + BrowserManager.OVERLAY_REMOVAL_SCRIPT
        })

        # 设置窗口大小和位置，避免遮挡前端界面
        # 将浏览器窗口放在屏幕右侧，避免遮挡前端界面
        driver.set_window_size(1200, 800)
        driver.set_window_position(1400, 0)
        log("浏览器初始化完成")
        return driver


class TimeManager:
    """时间管理器"""

    @staticmethod
    def get_jd_time() -> int:
        """获取京东服务器时间戳"""
        try:
            url = 'https://api.m.jd.com'
            resp = requests.get(url, verify=False, timeout=5)
            request_id = resp.headers.get('X-API-Request-Id')
            if request_id:
                return int(request_id[-13:])
            raise Exception('无法获取京东服务器时间')
        except Exception as e:
            logger.warning(f"获取京东时间失败: {e}")
            return round(time.time() * 1000)

    @staticmethod
    def get_tb_time() -> int:
        """获取淘宝服务器时间戳"""
        # 尝试多个淘宝时间API
        urls = [
            'https://acs.m.taobao.com/gw/mtop.common.getTimestamp/',
            'http://api.m.taobao.com/rest/api3.do?api=mtop.common.getTimestamp',
        ]

        for url in urls:
            try:
                resp = requests.get(url, timeout=5, allow_redirects=True)
                data = resp.json()
                if data.get('data') and data['data'].get('t'):
                    return int(data['data']['t'])
            except:
                continue

        logger.warning("获取淘宝时间失败，使用本地时间")
        return round(time.time() * 1000)

    @staticmethod
    def get_network_time(platform: str) -> int:
        """获取网络时间戳（毫秒）"""
        if platform == 'jd':
            return TimeManager.get_jd_time()
        elif platform == 'tb':
            return TimeManager.get_tb_time()
        return round(time.time() * 1000)

    @staticmethod
    def get_network_time_str(platform: str, fmt: str = '%H:%M:%S') -> str:
        """获取网络时间格式化字符串"""
        timestamp_ms = TimeManager.get_network_time(platform)
        return datetime.datetime.fromtimestamp(timestamp_ms / 1000).strftime(fmt)


class SeckillWorker:
    """抢购工作器"""

    def __init__(self, platform: str, log_callback: Callable[[str], None] | None = None):
        """
        初始化抢购工作器
        :param platform: 平台名称 (jd/tb/bb)
        :param log_callback: 日志回调函数
        """
        self.platform: str = platform
        config = PLATFORM_CONFIGS.get(platform)
        if not config:
            raise ValueError(f"不支持的平台: {platform}")
        self.config: PlatformConfig = config

        self.driver: webdriver.Chrome | None = None
        self.running: bool = False
        # 使用字典来存储确认状态，避免属性访问问题
        self._confirm_states = {}
        self.log_callback: Callable[[str], None] = log_callback or logger.info
        # 抢购前记录的待付款订单基线，用于提交成功后的订单列表交叉验证
        self._order_baseline: int | None = None
        # 订单列表交叉验证的节流状态，避免每次提交尝试都开标签页
        self._last_order_check_at: float = 0.0
        self._last_order_check_result: bool = False

    def log(self, message: str):
        """记录日志"""
        self.log_callback(message)

    def _navigate_and_login(self, login_wait: int = 15):
        """导航到平台并等待登录"""
        self.log(f"正在导航到{self.config.name}首页...")
        if self.driver:
            self.driver.get(self.config.url)
        time.sleep(2)

        # 移除遮罩层
        if self.driver:
            try:
                self.driver.execute_script(BrowserManager.OVERLAY_REMOVAL_SCRIPT)
            except Exception as e:
                self.log(f"移除遮罩层脚本执行失败: {e}")

        self.log("请在浏览器中扫码登录，登录完成后请点击页面上的'确认登录'按钮...")
        if self.driver:
            try:
                self.driver.find_element("link text", self.config.login_text).click()
            except NoSuchElementException:
                self.log("未找到登录按钮，可能已登录")

        self.log("等待用户确认登录...")

    def _navigate_to_cart(self) -> float:
        """导航到购物车或订单页面，返回页面加载时间"""
        load_time = 0.5
        if self.config.cart_url and self.driver:
            self.log(f"导航到{self.config.name}购物车...")
            start_time = time.time()
            self.driver.get(self.config.cart_url)
            time.sleep(2)
            end_time = time.time()
            load_time = end_time - start_time
            self.log(f"购物车页面加载时间：{load_time:.2f}秒")

            # 测试结算按钮加载时间
            try:
                btn_start = time.time()
                WebDriverWait(self.driver, 3).until(
                    EC.presence_of_element_located((By.CLASS_NAME, self.config.settle_button_class))
                )
                btn_load_time = time.time() - btn_start
                self.log(f"结算按钮加载时间：{btn_load_time:.2f}秒")
                load_time = max(load_time, btn_load_time)
            except TimeoutException:
                self.log("结算按钮加载超时，使用默认加载时间")

            # 移除遮罩层
            try:
                self.driver.execute_script(BrowserManager.OVERLAY_REMOVAL_SCRIPT)
            except Exception as e:
                pass  # 静默失败，不影响主流程

        return load_time

    def _test_page_load_time(self, num_tests: int = 3) -> float:
        """测试页面加载时间"""
        if not self.config.settle_button_class or not self.driver:
            return 0.5

        self.log("测试页面加载性能...")
        total_load_time = 0

        for i in range(num_tests):
            start_time = time.time()
            self.driver.refresh()
            try:
                WebDriverWait(self.driver, 2).until(
                    EC.presence_of_element_located((By.CLASS_NAME, self.config.settle_button_class))
                )
            except TimeoutException:
                pass

            end_time = time.time()
            load_time = end_time - start_time
            total_load_time += load_time
            self.log(f'第{i+1}次加载时间：{load_time:.2f}秒')
            time.sleep(2)

        average_load_time = total_load_time / num_tests
        self.log(f'平均加载时间：{average_load_time:.2f}秒')
        return max(average_load_time, 0.5)

    def _click_element_safely(self, element: Any) -> bool:
        """安全点击元素"""
        if not self.driver:
            return False
        for _ in range(3):
            try:
                self.driver.execute_script("arguments[0].click();", element)
                return True
            except:
                try:
                    element.click()
                    return True
                except:
                    time.sleep(0.1)
        return False

    @staticmethod
    def _human_pause(low: float = 0.15, high: float = 0.4):
        """在两次操作之间插入随机停顿，避免固定节奏的机械连点"""
        time.sleep(random.uniform(low, high))

    def _wait_for_target_time(self, target_time: str):
        """等待到达目标时间（使用网络时间校准的本地时间）"""
        target_dt = self._parse_time_string(target_time)
        if target_dt is None:
            self.log(f"错误：无法解析目标时间 {target_time}")
            return

        # 一次校准：获取网络时间与本地时间的偏移量（避免循环内反复 HTTP 请求）
        network_timestamp_ms = TimeManager.get_network_time(self.platform)
        offset_ms = network_timestamp_ms - round(time.time() * 1000)
        network_time = datetime.datetime.fromtimestamp(network_timestamp_ms / 1000)
        network_time_str = network_time.strftime('%H:%M:%S')
        self.log(f"[{network_time_str}] 等待到达抢购时间 {target_time}...")

        last_log_time = 0
        last_calibrate = time.time()
        warmup_logged = False

        while self.running:
            # 使用本地时间 + 偏移量代替网络请求（消除 HTTP 延迟）
            now_local = time.time()
            calibrated_ms = round(now_local * 1000) + offset_ms
            network_time = datetime.datetime.fromtimestamp(calibrated_ms / 1000)

            # 每30秒重新校准一次偏移量，防止时钟漂移
            if now_local - last_calibrate > 30:
                try:
                    fresh_network_ms = TimeManager.get_network_time(self.platform)
                    offset_ms = fresh_network_ms - round(time.time() * 1000)
                    last_calibrate = time.time()
                except Exception:
                    pass

            if network_time >= target_dt:
                network_time_str = network_time.strftime('%H:%M:%S')
                self.log(f"[{network_time_str}] 抢购时间已到！")
                break

            time_left_seconds = (target_dt - network_time).total_seconds()

            # 开抢前只做一次提示。
            # 注意：不要在此处高频切换/点击页面控件——开抢前 7 秒每 50ms 的机械操作
            # 是最典型的机器行为特征，正是"抢购前一瞬弹滑块"的主要诱因。
            if time_left_seconds <= 7 and not warmup_logged:
                network_time_str = network_time.strftime('%H:%M:%S')
                self.log(f"[{network_time_str}] 即将开抢，保持页面就绪，等待时间到达...")
                warmup_logged = True

            # 每10秒输出一次等待日志
            if now_local - last_log_time >= 10:
                time_left = self._calculate_time_left_dt(target_dt, network_time)
                network_time_str = network_time.strftime('%H:%M:%S')
                self.log(f"[{network_time_str}] 距离抢购还有 {time_left}...")
                last_log_time = now_local
            time.sleep(0.1)

    def _parse_time_string(self, time_str: str) -> datetime.datetime | None:
        """解析时间字符串为datetime对象，支持多种格式"""
        formats = [
            "%Y-%m-%d %H:%M:%S.%f",  # 完整格式：2025-03-19 11:00:00.000000
            "%Y-%m-%d %H:%M:%S",      # 无微秒：2025-03-19 11:00:00
            "%Y-%m-%d %H:%M",         # 无秒：2025-03-19 11:00
        ]
        for fmt in formats:
            try:
                return datetime.datetime.strptime(time_str, fmt)
            except ValueError:
                continue
        return None

    def _calculate_time_left_dt(self, target_dt: datetime.datetime, now_dt: datetime.datetime) -> str:
        """使用datetime对象计算剩余时间"""
        diff = target_dt - now_dt
        if diff.total_seconds() <= 0:
            return "0秒"
        hours = int(diff.total_seconds() // 3600)
        minutes = int((diff.total_seconds() % 3600) // 60)
        seconds = int(diff.total_seconds() % 60)
        if hours > 0:
            return f"{hours}小时{minutes}分{seconds}秒"
        elif minutes > 0:
            return f"{minutes}分{seconds}秒"
        else:
            return f"{seconds}秒"

    def _perform_seckill(self, max_retries: int = 300):
        """执行抢购"""
        self.log("开始抢购！")
        retry = 0
        consecutive_misses = 0

        while self.running and retry < max_retries and self.driver:
            try:
                btn = self.driver.find_element(By.CLASS_NAME, self.config.settle_button_class)
                consecutive_misses = 0

                # 检查按钮是否被禁用（灰色不可点击状态）
                disabled = btn.get_attribute('disabled')
                aria_disabled = btn.get_attribute('aria-disabled')
                classes = btn.get_attribute('class') or ''
                if disabled is not None or aria_disabled == 'true' or 'disabled' in classes.lower() or 'unable' in classes.lower():
                    retry += 1
                    self._human_pause(0.12, 0.3)
                    continue

                self.log("检测到结算按钮已激活，点击提交...")
                url_before = self.driver.current_url
                if self._click_element_safely(btn):
                    # 点击后验证订单是否真正提交成功（传入点击前 URL，避免把结算页误判为成功）
                    if self._verify_order_submitted(url_before):
                        self.log("✓ 抢购成功！请尽快付款")
                        now = TimeManager.get_network_time_str(self.platform, '%Y-%m-%d %H:%M:%S.%f')
                        self.log(f"抢购时间：{now}")
                        return True
                    else:
                        retry += 1
                        # 点击后给页面的响应/跳转留出随机缓冲，避免立刻重复点击
                        self._human_pause(0.35, 0.8)
                        if retry % 10 == 0:
                            self.log(f"订单提交未确认... 第{retry}次")
                else:
                    retry += 1
                    self._human_pause(0.15, 0.4)
            except NoSuchElementException:
                retry += 1
                consecutive_misses += 1
                # 结算按钮长时间缺失时逐步拉长间隔，避免无意义的密集轮询
                if consecutive_misses > 30:
                    self._human_pause(0.5, 1.2)
                else:
                    self._human_pause(0.15, 0.4)
                if retry % 10 == 0:
                    self.log(f"等待结算按钮出现... 第{retry}次")
            except Exception:
                retry += 1
                self._human_pause(0.15, 0.4)
                if retry % 10 == 0:
                    self.log(f"尝试中... 第{retry}次")

        self.log("抢购结束，未成功")
        return False

    def _verify_order_submitted(self, url_before: str | None = None) -> bool:
        """验证订单是否真正提交成功——检查页面跳转和内容"""
        if not self.driver:
            return False

        # 等待页面响应（跳转或弹窗），加入随机抖动
        time.sleep(random.uniform(0.6, 1.2))

        try:
            current_url = self.driver.current_url

            # 检查页面上是否有失败/错误提示
            page_text = self.driver.execute_script("return document.body.innerText || '';")
            error_keywords = [
                '抢光', '已抢光', '已售罄', '下单失败', '网络繁忙',
                '人数过多', '没抢到', '已下架', '库存不足', '活动太火爆',
                '该商品已下架', '商品已卖完', '再接再厉', '下单人数过多',
                '很遗憾', '暂时无法', '已失效', '已抢完',
            ]
            for kw in error_keywords:
                if kw in page_text:
                    self.log(f"检测到失败提示: {kw}")
                    return False

            # 明确的成功指标 —— 跳转到支付/收银台。
            # 关键修复（issue #11）：不能匹配结算页自身的 URL
            # （京东 getOrderInfo、淘宝 buy.taobao 结算页），否则会把"还在结算页"误判为成功。
            success_url_markers = {
                'jd': ['pay.jd.com', 'cashier.jd.com', 'succeed=true'],
                'tb': ['cashier.', 'alipay.com', 'trade_detail'],
                'bb': ['pay.bilibili.com', 'cashier'],
            }
            markers = success_url_markers.get(self.platform, [])
            for marker in markers:
                if marker in current_url and (url_before is None or current_url != url_before):
                    self.log(f"页面已跳转到支付/订单页: {current_url}")
                    return True

            # 弱信号：页面出现疑似成功文案时不再直接判定成功，改用订单列表交叉验证
            weak_success_texts = ['下单成功', '订单提交成功', '等待支付', '请尽快付款']
            if any(text in page_text for text in weak_success_texts):
                if self._has_new_pending_order():
                    self.log("订单列表校验通过：检测到新的待付款订单")
                    return True
                self.log("页面出现疑似成功文案，但订单列表未确认，判定为未成功")

        except Exception:
            pass

        return False

    def _read_order_list_text(self) -> str | None:
        """在新标签页读取订单列表文本，避免干扰当前结算页"""
        if not self.driver or not self.config.order_list_url:
            return None

        original_handle = self.driver.current_window_handle
        opened = False
        try:
            self.driver.switch_to.new_window('tab')
            opened = True
            self.driver.get(self.config.order_list_url)
            try:
                WebDriverWait(self.driver, 5).until(
                    lambda d: d.execute_script("return document.readyState") == "complete"
                )
            except TimeoutException:
                pass
            time.sleep(random.uniform(0.8, 1.5))
            return self.driver.execute_script("return document.body.innerText || '';")
        except Exception:
            return None
        finally:
            try:
                if opened:
                    self.driver.close()
            except Exception:
                pass
            try:
                self.driver.switch_to.window(original_handle)
            except Exception:
                pass

    @staticmethod
    def _count_order_rows(text: str) -> int:
        """粗略统计订单列表中的订单标识数量（长数字串），用于前后对比"""
        return len(re.findall(r'\d{10,}', text or ''))

    def _capture_order_baseline(self):
        """在抢购前记录待付款订单基线，供成功后订单列表校验"""
        text = self._read_order_list_text()
        self._order_baseline = self._count_order_rows(text) if text else None
        if self._order_baseline is not None:
            self.log(f"已记录订单基线：{self._order_baseline} 条（用于校验抢购结果）")

    def _has_new_pending_order(self) -> bool:
        """通过对比抢购前后订单列表，判断是否真的新增了订单"""
        if self._order_baseline is None:
            return False

        # 节流：最快每 2 秒查一次订单列表，避免每次提交尝试都开标签页造成密集操作
        now = time.time()
        if now - self._last_order_check_at < 2.0:
            return self._last_order_check_result
        self._last_order_check_at = now

        text = self._read_order_list_text()
        if not text:
            return self._last_order_check_result

        self._last_order_check_result = self._count_order_rows(text) > self._order_baseline
        return self._last_order_check_result

    def start_seckill(
        self,
        target_time: str | None = None,
        login_wait: int = 15,
        test_load_time: bool = True,
        wait_for_login_confirm: bool = True,
        wait_for_cart_confirm: bool = True
    ):
        """
        启动抢购流程
        :param target_time: 目标时间 (YYYY-MM-DD HH:MM:SS.ffffff)
        :param login_wait: 登录等待时间（秒）
        :param test_load_time: 是否测试页面加载时间
        :param wait_for_login_confirm: 是否等待登录确认
        :param wait_for_cart_confirm: 是否等待购物车确认
        """
        self.running = True

        try:
            # 初始化浏览器
            self.log("初始化浏览器...")
            self.driver = BrowserManager.create_driver(log_callback=self.log)

            # 导航并登录（等待用户确认）
            self._navigate_and_login(login_wait)
            if wait_for_login_confirm and not self._wait_for_user_confirm("login"):
                return

            # 等待用户确认购物车
            load_time = 0.5
            if wait_for_cart_confirm:
                if not self.config.cart_url:
                    self.log("当前平台无需购物车确认，直接开始抢购")
                else:
                    self.log("登录成功！")
                    self.log("等待购物车确认...")
                    self.log("请手动进入购物车，勾选需要抢购的商品，点击结算按钮进入结算界面")
                    self.log("然后点击页面上的'确认购物车'按钮...")
                    if not self._wait_for_user_confirm("cart"):
                        return
                    self.log("购物车已确认，准备等待抢购时间...")
                    # 在购物车确认后测试当前页面（结算页）的加载时间
                    # 注意：不刷新页面，只检查结算按钮响应时间
                    if test_load_time and target_time and self.driver:
                        try:
                            start = time.time()
                            WebDriverWait(self.driver, 2).until(
                                EC.presence_of_element_located((By.CLASS_NAME, self.config.settle_button_class))
                            )
                            load_time = time.time() - start
                            self.log(f"结算按钮响应时间：{load_time:.2f}秒")
                        except TimeoutException:
                            self.log("结算按钮检测超时，使用默认加载时间")
                            load_time = 0.5

                    # 记录抢购前待付款订单基线，供提交成功后校验真实结果（issue #11）
                    if self.config.order_list_url:
                        self._capture_order_baseline()

            # 等待到达目标时间（使用网络时间）
            if target_time:
                self.log(f'目标抢购时间：{target_time}')
                self._wait_for_target_time(target_time)

            # 执行抢购
            success = self._perform_seckill()

            if success:
                self.log("抢购成功！请尽快完成付款")
                self.log("任务已完成，请手动关闭浏览器或点击页面上的'关闭浏览器'按钮")

        except Exception as e:
            import traceback
            self.log(f"错误：{str(e)}")
            self.log(f"错误详情：{traceback.format_exc()}")

    def _wait_for_user_confirm(self, stage: str) -> bool:
        """
        等待用户确认
        :param stage: 当前阶段（登录/购物车）
        :return: True 表示用户已确认，False 表示取消
        """
        self.log(f"等待{stage}确认...")
        # 使用字典存储确认状态
        self._confirm_states[stage] = False
        self.log(f"初始化 {stage}_confirmed = False")

        count = 0
        while self.running:
            count += 1
            confirmed = self._confirm_states.get(stage, False)
            if count % 10 == 0:  # 每5秒输出一次调试信息
                self.log(f"等待中... {stage}_confirmed = {confirmed}, count = {count}")

            if confirmed:
                self.log(f"检测到 {stage}_confirmed 变为 True")
                break
            time.sleep(0.5)

        # 检查是否已确认
        final_confirmed = self._confirm_states.get(stage, False)
        self.log(f"最终{stage}确认状态: {final_confirmed}")
        if final_confirmed:
            self.log(f"{stage}确认成功，继续下一步...")
            return True
        else:
            self.log(f"任务已取消或停止")
            return False

    def stop(self):
        """停止抢购并清理资源"""
        self.running = False
        if self.driver:
            try:
                self.log("关闭浏览器...")
                self.driver.quit()
            except:
                pass
            self.driver = None
        self.log("抢购程序已结束")


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(description='统一抢购工具')
    parser.add_argument('platform', choices=['jd', 'tb', 'bb'], help='平台名称')
    parser.add_argument('--time', help='抢购时间 (YYYY-MM-DD HH:MM:SS.ffffff)')
    parser.add_argument('--login-wait', type=int, default=15, help='登录等待时间（秒）')

    _ = parser.parse_args()
    _ = _  # Mark as unused

    args = parser.parse_args()

    def console_log(message):
        now = TimeManager.get_network_time_str(args.platform, '%H:%M:%S')
        print(f"[{now}] {message}")

    worker = SeckillWorker(args.platform, log_callback=console_log)
    worker.start_seckill(target_time=args.time, login_wait=args.login_wait)
