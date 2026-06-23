"""
统一抢购逻辑模块
支持京东、淘宝、哔哩哔哩等多个平台的抢购
"""

import time
import datetime
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


# 平台配置
PLATFORM_CONFIGS = {
    'jd': PlatformConfig(
        name='京东',
        url='https://www.jd.com',
        login_text='你好，请登录',
        cart_url='https://cart.jd.com/cart_index',
        settle_button_class='',
        submit_button_css='.checkout-submit'
    ),
    'tb': PlatformConfig(
        name='淘宝',
        url='https://www.taobao.com',
        login_text='亲，请登录',
        cart_url='https://cart.taobao.com/cart.htm',
        settle_button_class='btn--QDjHtErD',
        submit_button_css='.go-btn',
        confirm_button_css='.go-btn'
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
            // 隐藏 webdriver 痕迹
            Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
            // 删除 CDC 调试标记
            delete window.cdc_adoQpoasnfa76pfcZLmcfl_Array;
            delete window.cdc_adoQpoasnfa76pfcZLmcfl_Promise;
            delete window.cdc_adoQpoasnfa76pfcZLmcfl_Symbol;
            // 伪造 plugins（正常 Chrome 有 5 个内置插件，自动化浏览器为空）
            Object.defineProperty(navigator, 'plugins', {
                get: () => {
                    var plugins = [1, 2, 3, 4, 5];
                    plugins.item = function(i) { return this[i]; };
                    plugins.namedItem = function(name) { return null; };
                    plugins.refresh = function() {};
                    return plugins;
                }
            });
            // 确保 chrome 对象存在
            if (!window.chrome) {
                window.chrome = { runtime: {}, loadTimes: function() {}, csi: function() {}, app: {} };
            }
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
    def create_options(headless: bool = False) -> Options:
        """创建浏览器选项"""
        options = Options()
        if headless:
            options.add_argument("--headless=new")
        options.add_argument("--disable-gpu")
        options.add_argument("--no-sandbox")
        options.add_argument("--disable-blink-features=AutomationControlled")
        options.add_argument("--disable-features=Translate,OptimizationHints,MediaRouter,DialMediaRouteProvider")
        options.add_experimental_option("excludeSwitches", ["enable-automation"])
        options.add_experimental_option("useAutomationExtension", False)
        # 关闭"Chrome 正受到自动测试软件的控制"提示条
        options.add_argument("--disable-infobars")

        # 使用较新的 Chrome 版本 UA，匹配本地浏览器版本
        user_agent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36"
        options.add_argument(f'user-agent={user_agent}')
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
        driver.set_page_load_timeout(12)

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

    def __init__(
        self,
        platform: str,
        log_callback: Callable[[str], None] | None = None,
        product_keyword: str | None = None
    ):
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
        # 京东预约商品在开售前无法勾选。按商品标题定位，避免误选购物车中的其他商品。
        self.jd_product_keyword = (
            product_keyword or os.environ.get("JD_PRODUCT_KEYWORD", "")
        ).strip()

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

    def _ensure_active_window(self) -> bool:
        """当前标签被手动关闭时，自动接管同一自动化浏览器中的剩余标签。"""
        if not self.driver:
            return False
        try:
            _ = self.driver.current_url
            return True
        except Exception:
            pass

        try:
            handles = self.driver.window_handles
            if not handles:
                self.log("自动化浏览器中已没有可用标签页")
                return False
            self.driver.switch_to.window(handles[-1])
            self.log("检测到原控制标签已关闭，已自动接管剩余标签页")
            return True
        except Exception as e:
            self.log(f"恢复浏览器标签失败：{e}")
            return False

    def _refresh_item_status(self):
        """通过切换号码保护复选框刷新商品状态"""
        if not self.driver:
            return

        try:
            # 查找号码保护复选框 - 使用稳定的 class 选择器
            checkbox_input = self.driver.find_element(
                By.CSS_SELECTOR,
                '.settlementOption--bLHTJgxx .ant-checkbox-input'
            )

            # 获取当前状态
            is_checked = checkbox_input.is_selected()

            # 切换状态：如果选中则取消，如果未选中则选中
            self.driver.execute_script("arguments[0].click();", checkbox_input)

            # 短暂等待后恢复原来状态
            time.sleep(0.05)
            if self.driver:
                checkbox_input = self.driver.find_element(
                    By.CSS_SELECTOR,
                    '.settlementOption--bLHTJgxx .ant-checkbox-input'
                )
                current_checked = checkbox_input.is_selected()
                if current_checked == is_checked:
                    # 状态没变，再点击一次切换
                    self.driver.execute_script("arguments[0].click();", checkbox_input)

        except Exception:
            # 元素不存在或操作失败，静默处理
            pass

    def _page_text(self) -> str:
        if not self.driver:
            return ""
        try:
            return self.driver.execute_script(
                "return document.body ? document.body.innerText : '';"
            ) or ""
        except Exception:
            return ""

    def _find_failure_keyword(self, page_text: str) -> str | None:
        failure_keywords = [
            '购买超出限制', '超出购买限制', '超出限购', '已达购买上限',
            '抢光', '已抢光', '已售罄', '下单失败', '网络繁忙',
            '人数过多', '没抢到', '已下架', '库存不足', '活动太火爆',
            '该商品已下架', '商品已卖完', '再接再厉', '下单人数过多',
            '很遗憾', '暂时无法', '已失效', '已抢完',
        ]
        return next((kw for kw in failure_keywords if kw in page_text), None)

    def _find_action_button(self, action: str):
        """按可见文字定位最像操作按钮的元素，兼容频繁变化的动态 class。"""
        if not self.driver:
            return None
        script = """
            const action = arguments[0];
            const visible = (el) => {
                if (!el) return false;
                const rect = el.getBoundingClientRect();
                const style = getComputedStyle(el);
                return rect.width > 0 && rect.height > 0 &&
                    style.display !== 'none' && style.visibility !== 'hidden';
            };
            const textOf = (el) => (
                el.innerText || el.value || el.textContent || ''
            ).replace(/\\s+/g, ' ').trim();
            const disabled = (el) => {
                const text = textOf(el);
                return Boolean(
                    el.disabled ||
                    el.getAttribute('aria-disabled') === 'true' ||
                    /disabled|unable/.test(String(el.className || '').toLowerCase()) ||
                    /\\(0\\)|（0）/.test(text)
                );
            };
            const matches = (text) => {
                if (action === 'submit') return text.includes('提交订单');
                if (action === 'checkout') {
                    return text.startsWith('结算') || text.startsWith('去结算');
                }
                return false;
            };
            const nodes = Array.from(document.querySelectorAll(
                'button, a, [role="button"], input[type="button"], ' +
                'input[type="submit"], div, span'
            ));
            const candidates = [];
            const seen = new Set();
            for (const node of nodes) {
                if (!visible(node)) continue;
                const text = textOf(node);
                if (!matches(text) || text.length > 60) continue;
                const target = node.closest(
                    'button, a, [role="button"], input[type="button"], input[type="submit"]'
                ) || node;
                if (!visible(target) || disabled(target) || seen.has(target)) continue;
                seen.add(target);
                const rect = target.getBoundingClientRect();
                let score = 0;
                if (/^(BUTTON|A|INPUT)$/.test(target.tagName)) score += 30;
                if (action === 'submit' && text === '提交订单') score += 30;
                if (action === 'checkout' && /^(去)?结算/.test(text)) score += 20;
                if (rect.width >= 80 && rect.width <= 500) score += 10;
                if (rect.height >= 28 && rect.height <= 100) score += 10;
                score += Math.min(rect.left / 100, 15);
                score += Math.min(rect.top / 100, 10);
                score -= Math.min((rect.width * rect.height) / 100000, 20);
                candidates.push({ target, score });
            }
            candidates.sort((a, b) => b.score - a.score);
            return candidates.length ? candidates[0].target : null;
        """
        try:
            return self.driver.execute_script(script, action)
        except Exception:
            return None

    def _wait_for_document_ready(self, timeout: float = 5.0):
        if not self.driver:
            return
        try:
            WebDriverWait(self.driver, timeout).until(
                lambda d: d.execute_script(
                    "return document.readyState"
                ) in ('interactive', 'complete')
            )
        except Exception:
            pass

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
        refresh_started = False

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

            # 提前7秒开始刷新商品状态
            if time_left_seconds <= 7 and not refresh_started:
                network_time_str = network_time.strftime('%H:%M:%S')
                self.log(f"[{network_time_str}] 开始刷新商品状态...")
                refresh_started = True

            # 刷新状态期间，每50ms刷新一次
            if refresh_started and time_left_seconds > 0:
                # 淘宝仅在购物车页通过控件触发状态更新；确认订单页无需刷新。
                if self.platform == 'tb' and self.driver:
                    try:
                        if 'cart.taobao.com' in self.driver.current_url:
                            self._refresh_item_status()
                    except Exception:
                        pass
                time.sleep(0.05)
                continue

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
        if self.platform == 'jd':
            return self._perform_jd_seckill(max_retries=max_retries)
        if self.platform == 'tb':
            return self._perform_tb_seckill()

        self.log("开始抢购！")
        retry = 0

        while self.running and retry < max_retries and self.driver:
            try:
                btn = self.driver.find_element(By.CLASS_NAME, self.config.settle_button_class)

                # 检查按钮是否被禁用（灰色不可点击状态）
                disabled = btn.get_attribute('disabled')
                aria_disabled = btn.get_attribute('aria-disabled')
                classes = btn.get_attribute('class') or ''
                if disabled is not None or aria_disabled == 'true' or 'disabled' in classes.lower() or 'unable' in classes.lower():
                    retry += 1
                    time.sleep(0.05)
                    continue

                self.log("检测到结算按钮已激活，点击提交...")
                if self._click_element_safely(btn):
                    # 点击后验证订单是否真正提交成功
                    if self._verify_order_submitted():
                        self.log("✓ 抢购成功！请尽快付款")
                        now = TimeManager.get_network_time_str(self.platform, '%Y-%m-%d %H:%M:%S.%f')
                        self.log(f"抢购时间：{now}")
                        return True
                    else:
                        retry += 1
                        time.sleep(0.2)
                        if retry % 10 == 0:
                            self.log(f"订单提交未确认... 第{retry}次")
                else:
                    retry += 1
                    time.sleep(0.1)
            except NoSuchElementException:
                retry += 1
                time.sleep(0.1)
                if retry % 10 == 0:
                    self.log(f"等待结算按钮出现... 第{retry}次")
            except Exception:
                retry += 1
                time.sleep(0.1)
                if retry % 10 == 0:
                    self.log(f"尝试中... 第{retry}次")

        self.log("抢购结束，未成功")
        return False

    def _perform_tb_seckill(self, max_wait_seconds: float = 75):
        """淘宝：根据当前页面状态执行购物车结算或确认页提交。"""
        if not self._ensure_active_window():
            return False

        self.log("开始淘宝抢购，按当前页面状态执行...")
        deadline = time.monotonic() + max_wait_seconds
        last_status_log = 0.0
        clicked_submit = False

        while self.running and self.driver and time.monotonic() < deadline:
            try:
                if not self._ensure_active_window():
                    time.sleep(0.2)
                    continue

                current_url = self.driver.current_url
                page_text = self._page_text()
                failure = self._find_failure_keyword(page_text)
                if failure:
                    self.log(f"淘宝下单失败：检测到“{failure}”")
                    return False

                if any(marker in current_url.lower() for marker in (
                    'alipay', 'cashier', 'trade_detail', 'pay.htm', 'payment'
                )):
                    self.log(f"已进入付款页面：{current_url}")
                    return True
                if any(text in page_text for text in (
                    '等待支付', '请尽快付款', '订单提交成功', '订单号'
                )):
                    self.log("检测到淘宝订单已提交，请尽快付款")
                    return True

                on_confirm_page = (
                    'confirm_order' in current_url or
                    'buy.tmall.com' in current_url or
                    'buy.taobao.com' in current_url or
                    '确认订单' in page_text
                )
                if on_confirm_page:
                    submit = self._find_action_button('submit')
                    if submit:
                        if self._click_element_safely(submit):
                            if not clicked_submit:
                                self.log("已点击淘宝“提交订单”，等待下单结果...")
                                clicked_submit = True
                            time.sleep(0.4)
                            continue
                elif 'cart.taobao.com' in current_url:
                    checkout = self._find_action_button('checkout')
                    if checkout and self._click_element_safely(checkout):
                        self.log("已点击淘宝结算，等待进入确认订单页...")
                        time.sleep(0.5)
                        continue
                else:
                    # 页面 URL 改版时仍尝试按按钮文字执行。
                    submit = self._find_action_button('submit')
                    if submit and self._click_element_safely(submit):
                        self.log("已点击淘宝“提交订单”，等待下单结果...")
                        clicked_submit = True
                        time.sleep(0.4)
                        continue

                now = time.monotonic()
                if now - last_status_log >= 5:
                    state = "确认订单页" if on_confirm_page else "等待可操作页面"
                    self.log(f"淘宝仍在{state}，继续重试...")
                    last_status_log = now
                time.sleep(0.1)
            except Exception as e:
                now = time.monotonic()
                if now - last_status_log >= 5:
                    self.log(f"淘宝页面操作异常，继续重试：{e}")
                    last_status_log = now
                time.sleep(0.2)

        self.log("淘宝抢购超时，未检测到订单提交成功")
        return False

    def _perform_jd_seckill(self, max_retries: int = 600):
        """京东预约商品：刷新购物车直到解锁，再勾选、结算并提交。"""
        if not self._ensure_active_window():
            return False

        if not self.jd_product_keyword:
            self.log("请先设置京东商品关键字，用于定位需要抢购的购物车商品")
            return False

        self.log(f"开始京东抢购，目标商品关键字：{self.jd_product_keyword}")
        checkbox_script = """
            const keyword = (arguments[0] || '').toLowerCase();
            const visible = (el) => {
                if (!el) return false;
                const rect = el.getBoundingClientRect();
                const style = getComputedStyle(el);
                return rect.width > 0 && rect.height > 0 &&
                    style.display !== 'none' && style.visibility !== 'hidden';
            };
            const textOf = (el) => (el.innerText || '').replace(/\\s+/g, ' ').trim();
            const rows = Array.from(document.querySelectorAll('div, li, tr, section'))
                .filter((el) => {
                    const rect = el.getBoundingClientRect();
                    return visible(el) && rect.width > 500 && rect.height >= 80 &&
                        rect.height <= 500 && textOf(el).toLowerCase().includes(keyword);
                })
                .sort((a, b) => a.getBoundingClientRect().height - b.getBoundingClientRect().height);

            for (const row of rows) {
                const scopes = [];
                let scope = row;
                for (let i = 0; i < 8 && scope; i++, scope = scope.parentElement) {
                    scopes.push(scope);
                }
                for (const scope of scopes) {
                    if (!scope) continue;
                    const candidates = Array.from(scope.querySelectorAll(
                        'input[type="checkbox"], [role="checkbox"], label[class*="check"], ' +
                        '[class*="checkbox"], [class*="check-box"], [class*="jdcheckbox"], ' +
                        '[class*="cart-checkbox"], [class*="select"]'
                    )).filter(visible);
                    for (const candidate of candidates) {
                        const candidateText = textOf(candidate).toLowerCase();
                        if (candidateText.includes('全选')) continue;
                        return candidate;
                    }
                }
            }
            return null;
        """
        checkout_script = """
            const visible = (el) => {
                if (!el) return false;
                const rect = el.getBoundingClientRect();
                const style = getComputedStyle(el);
                return rect.width > 0 && rect.height > 0 &&
                    style.display !== 'none' && style.visibility !== 'hidden';
            };
            const nodes = Array.from(document.querySelectorAll('button, a, div, span'))
                .filter((el) => visible(el) && /^去结算/.test((el.innerText || '').trim()))
                .sort((a, b) => a.getBoundingClientRect().width - b.getBoundingClientRect().width);
            for (const node of nodes) {
                const button = node.closest('button, a, [role="button"]') || node;
                const text = (button.innerText || '').trim();
                const disabled = button.disabled ||
                    button.getAttribute('aria-disabled') === 'true' ||
                    /disabled|unable/.test(button.className || '') ||
                    /\\(0\\)|（0）/.test(text);
                if (!disabled) return button;
            }
            return null;
        """

        checkout_clicked = False
        deadline = time.monotonic() + 90
        refresh_count = 0
        last_state_log = 0.0

        while self.running and self.driver and time.monotonic() < deadline:
            if not self.running or not self.driver:
                break

            try:
                if not self._ensure_active_window():
                    time.sleep(0.2)
                    continue
                current_url = self.driver.current_url
                if 'getOrderInfo' in current_url:
                    checkout_clicked = True
                    break

                if 'cart.jd.com' not in current_url:
                    self.driver.get(self.config.cart_url)
                    self._wait_for_document_ready()

                # 京东预约状态不会自行更新；每轮都真实刷新购物车。
                try:
                    self.driver.refresh()
                except TimeoutException:
                    try:
                        self.driver.execute_script("window.stop();")
                    except Exception:
                        pass
                self._wait_for_document_ready()
                refresh_count += 1

                checkbox = self.driver.execute_script(
                    checkbox_script,
                    self.jd_product_keyword
                )
                if checkbox:
                    disabled = self.driver.execute_script(
                        """
                        const el = arguments[0];
                        return Boolean(
                            el.disabled ||
                            el.getAttribute('aria-disabled') === 'true' ||
                            /disabled|unable/.test(String(el.className || '').toLowerCase()) ||
                            (el.parentElement &&
                                /disabled|unable/.test(String(el.parentElement.className || '').toLowerCase()))
                        );
                        """,
                        checkbox
                    )
                    selected = self.driver.execute_script(
                        """
                        const el = arguments[0];
                        return Boolean(
                            el.checked ||
                            el.getAttribute('aria-checked') === 'true' ||
                            /checked|selected/.test(el.className || '') ||
                            (el.parentElement && /checked|selected/.test(el.parentElement.className || ''))
                        );
                        """,
                        checkbox
                    )
                    if not disabled and not selected:
                        self._click_element_safely(checkbox)
                        time.sleep(0.15)

                checkout = self.driver.execute_script(checkout_script)
                if checkout and self._click_element_safely(checkout):
                    self.log("已勾选目标商品并点击去结算，等待订单确认页...")
                    checkout_clicked = True
                    for _ in range(60):
                        if not self.driver:
                            break
                        if (
                            'getOrderInfo' in self.driver.current_url or
                            self.driver.find_elements(By.CSS_SELECTOR, self.config.submit_button_css)
                        ):
                            break
                        time.sleep(0.15)
                    if 'getOrderInfo' in self.driver.current_url:
                        break
                    checkout_clicked = False

                now = time.monotonic()
                if now - last_state_log >= 5:
                    page_text = self._page_text()
                    if '购物车跑丢了' in page_text:
                        state = "购物车暂时加载为空"
                    elif checkbox and disabled:
                        state = "目标商品仍未解锁"
                    elif not checkbox:
                        state = "尚未定位到目标商品复选框"
                    else:
                        state = "目标商品已出现，等待结算按钮"
                    self.log(
                        f"京东已刷新 {refresh_count} 次：{state}，继续重试..."
                    )
                    last_state_log = now
            except Exception as e:
                now = time.monotonic()
                if now - last_state_log >= 5:
                    self.log(f"京东购物车尝试中：{str(e)}")
                    last_state_log = now
            time.sleep(0.2)

        if not checkout_clicked or not self.driver:
            self.log("未能进入京东订单确认页")
            return False

        self.log("已进入订单确认页，准备提交订单...")
        for retry in range(100):
            if not self.running or not self.driver:
                break
            try:
                buttons = self.driver.find_elements(
                    By.CSS_SELECTOR,
                    self.config.submit_button_css
                )
                action_button = self._find_action_button('submit')
                if action_button:
                    buttons.insert(0, action_button)
                for button in buttons:
                    if not button.is_displayed():
                        continue
                    disabled = (
                        button.get_attribute('disabled') is not None or
                        button.get_attribute('aria-disabled') == 'true' or
                        'disabled' in (button.get_attribute('class') or '').lower()
                    )
                    if disabled:
                        continue
                    if self._click_element_safely(button):
                        self.log("已点击提交订单，正在确认结果...")
                        if self._verify_order_submitted():
                            self.log("✓ 京东订单提交成功，请尽快付款")
                            return True
            except Exception:
                pass
            time.sleep(0.1)

        self.log("京东订单提交未确认")
        return False

    def _verify_order_submitted(self) -> bool:
        """验证订单是否真正提交成功——检查页面跳转和内容"""
        if not self.driver:
            return False

        # 等待页面响应（跳转或弹窗）
        time.sleep(1)

        try:
            current_url = self.driver.current_url

            page_text = self.driver.execute_script("return document.body.innerText || '';")
            failure = self._find_failure_keyword(page_text)
            if failure:
                self.log(f"检测到失败提示: {failure}")
                return False

            # 检查成功指标 —— URL 跳转
            success_url_markers = {
                'jd': ['pay', 'success', 'cashier'],
                'tb': ['cashier', 'alipay', 'trade_detail', 'payment'],
                'bb': ['pay', 'order', 'success'],
            }
            markers = success_url_markers.get(self.platform, ['pay', 'order', 'success'])
            for marker in markers:
                if marker in current_url:
                    self.log(f"页面已跳转: {current_url}")
                    return True

            # 检查成功指标 —— 页面内容
            success_texts = ['下单成功', '订单提交成功', '恭喜', '等待支付', '请尽快付款', '订单号']
            for text in success_texts:
                if text in page_text:
                    self.log(f"检测到成功提示: {text}")
                    return True

        except Exception:
            pass

        return False

    def start_seckill(
        self,
        target_time: str | None = None,
        login_wait: int = 15,
        test_load_time: bool = True,
        wait_for_login_confirm: bool = True,
        wait_for_cart_confirm: bool = True
    ) -> bool:
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
                return False

            # 等待用户确认购物车
            load_time = 0.5
            if wait_for_cart_confirm:
                if not self.config.cart_url:
                    self.log("当前平台无需购物车确认，直接开始抢购")
                else:
                    self.log("登录成功！")
                    self.log("等待购物车确认...")
                    if self.platform == 'jd':
                        target_tip = (
                            f"目标商品“{self.jd_product_keyword}”"
                            if self.jd_product_keyword else
                            "需要抢购的目标商品"
                        )
                        self.log(
                            f"请停留在京东购物车，并确认{target_tip}可见；"
                            "预约商品现在无需勾选，开售后程序会自动勾选并结算"
                        )
                    else:
                        self.log("请手动进入购物车，勾选需要抢购的商品，点击结算按钮进入结算界面")
                    self.log("然后点击页面上的'确认购物车'按钮...")
                    if not self._wait_for_user_confirm("cart"):
                        return False
                    self.log("购物车已确认，准备等待抢购时间...")
                    # 在购物车确认后测试当前页面（结算页）的加载时间
                    # 注意：不刷新页面，只检查结算按钮响应时间
                    if test_load_time and target_time and self.driver and self.platform != 'jd':
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

            # 等待到达目标时间（使用网络时间）
            if target_time:
                self.log(f'目标抢购时间：{target_time}')
                self._wait_for_target_time(target_time)

            # 执行抢购
            success = self._perform_seckill()

            if success:
                self.log("抢购成功！请尽快完成付款")
                self.log("任务已完成，请手动关闭浏览器或点击页面上的'关闭浏览器'按钮")
            return success

        except Exception as e:
            import traceback
            self.log(f"错误：{str(e)}")
            self.log(f"错误详情：{traceback.format_exc()}")
            return False

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
