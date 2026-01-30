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
        cart_url='https://trade.jd.com/shopping/order/getOrderInfo.action',
        settle_button_class='checkout-submit',
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

    @staticmethod
    def create_options(headless: bool = False) -> Options:
        """创建浏览器选项"""
        options = Options()
        if headless:
            options.add_argument("--headless")
        options.add_argument("--disable-gpu")
        options.add_argument("--no-sandbox")
        options.add_argument("--incognito")
        options.add_argument("--disable-blink-features")
        options.add_argument("--disable-blink-features=AutomationControlled")
        options.add_experimental_option("excludeSwitches", ["enable-automation"])
        options.add_experimental_option("useAutomationExtension", False)

        user_agent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0 Safari/537.36"
        options.add_argument(f'user-agent={user_agent}')
        return options

    @staticmethod
    def create_driver(options: Options | None = None) -> webdriver.Chrome:
        """创建驱动"""
        if options is None:
            options = BrowserManager.create_options()

        driver_path = ChromeDriverManager().install()
        driver = webdriver.Chrome(service=ChromeService(driver_path), options=options)

        # 移除 webdriver 特征
        driver.execute_cdp_cmd("Page.addScriptToEvaluateOnNewDocument", {
            "source": """
                Object.defineProperty(navigator, 'webdriver', {
                    get: () => undefined
                })
            """
        })

        driver.maximize_window()
        logger.info("浏览器初始化完成")
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
    def get_local_time() -> int:
        """获取本地时间戳"""
        return round(time.time() * 1000)

    @staticmethod
    def get_time_diff(platform: str) -> int:
        """获取本地与服务器时间差（毫秒）"""
        if platform == 'jd':
            jd_time = TimeManager.get_jd_time()
            local_time = TimeManager.get_local_time()
            return local_time - jd_time
        return 0

    @staticmethod
    def adjust_target_time(target_time: str, platform: str, load_time: float = 0.5) -> str:
        """根据平台调整目标时间"""
        try:
            mstime_datetime = datetime.datetime.strptime(target_time, "%Y-%m-%d %H:%M:%S.%f")

            # 减去页面加载时间
            mstime_datetime = mstime_datetime - datetime.timedelta(seconds=load_time - 0.1)

            # 时间校准
            time_diff = TimeManager.get_time_diff(platform)
            if abs(time_diff) > 1000:
                mstime_datetime = mstime_datetime - datetime.timedelta(milliseconds=time_diff - 100)
                logger.info(f"时间校准：调整 {time_diff} 毫秒")

            return mstime_datetime.strftime('%Y-%m-%d %H:%M:%S.%f')
        except Exception as e:
            logger.error(f"调整时间失败: {e}")
            return target_time


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
        self.log_callback: Callable[[str], None] = log_callback or logger.info

    def log(self, message: str):
        """记录日志"""
        self.log_callback(message)

    def _navigate_and_login(self, login_wait: int = 15):
        """导航到平台并等待登录"""
        self.log(f"正在导航到{self.config.name}首页...")
        if self.driver:
            self.driver.get(self.config.url)
        time.sleep(2)

        self.log("请在浏览器中扫码登录，登录完成后请点击页面上的'确认登录'按钮...")
        if self.driver:
            try:
                self.driver.find_element("link text", self.config.login_text).click()
            except NoSuchElementException:
                self.log("未找到登录按钮，可能已登录")

        self.log("等待用户确认登录...")

    def _navigate_to_cart(self):
        """导航到购物车或订单页面"""
        if self.config.cart_url and self.driver:
            self.log(f"导航到{self.config.name}购物车...")
            self.driver.get(self.config.cart_url)
            time.sleep(2)

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

    def _wait_for_target_time(self, target_time: str):
        """等待到达目标时间"""
        self.log(f"等待到达抢购时间 {target_time}...")
        while self.running:
            now = datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S.%f')
            if now >= target_time:
                break
            time.sleep(0.1)

    def _perform_seckill(self, max_retries: int = 10):
        """执行抢购"""
        self.log("开始抢购！")
        success = False
        i = 0

        while self.running and not success and i < max_retries and self.driver:
            try:
                btn = self.driver.find_element(By.CLASS_NAME, self.config.settle_button_class)
                if self._click_element_safely(btn):
                    self.log("✓ 抢购成功！请尽快付款")
                    now = datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S.%f')
                    self.log(f"抢购时间：{now}")
                    success = True
            except Exception:
                i += 1
                time.sleep(0.1)
                if i % 5 == 0:
                    self.log(f"尝试中... 第{i}次")

        return success

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
            self.driver = BrowserManager.create_driver()

            # 导航并登录（等待用户确认）
            self._navigate_and_login(login_wait)
            if wait_for_login_confirm and not self._wait_for_user_confirm("登录"):
                return

            # 导航到购物车
            self._navigate_to_cart()

            # 等待用户确认购物车
            if wait_for_cart_confirm:
                if not self.config.cart_url:
                    self.log("当前平台无需购物车确认，直接开始抢购")
                else:
                    self.log("请在浏览器中选中要抢购的商品，然后点击页面上的'下一步'按钮...")
                    if not self._wait_for_user_confirm("购物车"):
                        return

            # 测试页面加载时间
            load_time = 0.5
            if test_load_time and target_time:
                load_time = self._test_page_load_time()

            # 调整目标时间
            if target_time:
                target_time = TimeManager.adjust_target_time(target_time, self.platform, load_time)
                self.log(f'调整后的抢购时间：{target_time}')

                # 等待目标时间
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
        # 检查 running 状态，用户可以通过外部修改此状态来继续
        while self.running and not getattr(self, f'{stage}_confirmed', False):
            time.sleep(0.5)
        return self.running

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
        print(f"[{datetime.datetime.now().strftime('%H:%M:%S')}] {message}")

    worker = SeckillWorker(args.platform, log_callback=console_log)
    worker.start_seckill(target_time=args.time, login_wait=args.login_wait)
