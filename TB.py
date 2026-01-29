import threading
import logging
from selenium import webdriver
from selenium.webdriver.common.by import By
from selenium.webdriver.support.ui import WebDriverWait
from selenium.webdriver.support import expected_conditions as EC
from selenium.common.exceptions import (
    NoSuchElementException, TimeoutException,
    ElementNotVisibleException, ElementClickInterceptedException,
    StaleElementReferenceException
)
from selenium.webdriver.chrome.options import Options
from selenium.webdriver.chrome.service import Service as ChromeService
from webdriver_manager.chrome import ChromeDriverManager
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


class TaobaoSeckill:
    def __init__(self):
        self.driver = None
        self.main_thread = None
        self.confirm_thread = None
        self.running = False  # 运行状态标志
        self.lock = threading.Lock()  # 线程锁确保安全

    def _init_browser(self):
        """初始化浏览器配置，增强反检测能力"""
        chrome_options = Options()
        # 禁用自动化控制特征
        chrome_options.add_experimental_option("excludeSwitches", ["enable-automation"])
        chrome_options.add_experimental_option("useAutomationExtension", False)
        chrome_options.add_argument("--disable-blink-features=AutomationControlled")
        # 浏览器参数配置
        chrome_options.add_argument("--disable-gpu")
        chrome_options.add_argument("--no-sandbox")
        # chrome_options.add_argument("--incognito")
        # 设置驱动下载到项目目录
        driver_path = ChromeDriverManager(path=os.path.join(PROJECT_DIR, 'drivers')).install()
        self.driver = webdriver.Chrome(service=ChromeService(driver_path), options=chrome_options)
        # 移除webdriver特征
        self.driver.execute_cdp_cmd("Page.addScriptToEvaluateOnNewDocument", {
            "source": """
                Object.defineProperty(navigator, 'webdriver', {
                    get: () => undefined
                })
            """
        })
        self.driver.maximize_window()
        logger.info("浏览器初始化完成")

    def _start_confirm_thread(self):
        """启动确认订单高频点击线程"""

        def confirm_task():
            while self._is_running():
                try:
                    # 尝试定位提交订单按钮
                    submit_btn = WebDriverWait(self.driver, 1, 0.01).until(
                        EC.element_to_be_clickable((By.CSS_SELECTOR, ".go-btn"))
                    )
                    # 多方式尝试点击
                    for _ in range(3):
                        try:
                            self.driver.execute_script("arguments[0].click();", submit_btn)
                            logger.info("高频线程: 成功点击提交订单按钮")
                            break
                        except:
                            pass
                except (NoSuchElementException, TimeoutException):
                    pass
                except (ElementNotVisibleException, ElementClickInterceptedException):
                    logger.warning("高频线程: 提交按钮不可见或被遮挡")
                except StaleElementReferenceException:
                    logger.warning("高频线程: 提交按钮元素已失效，重新查找")
                except Exception as e:
                    logger.error(f"高频线程错误: {str(e)}")

        with self.lock:
            if not self.confirm_thread or not self.confirm_thread.is_alive():
                self.confirm_thread = threading.Thread(target=confirm_task)
                self.confirm_thread.daemon = True
                self.confirm_thread.start()
                logger.info("订单确认线程已启动")

    def _main_monitor_task(self):
        """主监控线程: 负责结算按钮检测与点击"""
        while self._is_running():
            try:
                # 尝试定位结算按钮
                settle_btn = self.driver.find_element(By.CLASS_NAME, "btn--QDjHtErD")
                btn_text = settle_btn.text.strip()
                logger.info(f"主线程: 当前结算按钮状态 - {btn_text}")

                # 检测是否已进入订单确认页
                try:
                    self.driver.find_element(By.CSS_SELECTOR, ".go-btn")
                    self._start_confirm_thread()
                    continue
                except NoSuchElementException:
                    pass

                # 尝试点击结算按钮
                if btn_text in ["结算", "立即购买"]:
                    logger.info(f"主线程: 尝试点击{btn_text}按钮")
                    for _ in range(2):
                        try:
                            settle_btn.click()
                            logger.info("主线程: 成功点击结算按钮(常规方式)")
                            break
                        except:
                            try:
                                self.driver.execute_script("arguments[0].click();", settle_btn)
                                logger.info("主线程: 成功点击结算按钮(JS方式)")
                                break
                            except Exception as e:
                                logger.warning(f"主线程: 点击失败重试 - {str(e)}")

            except NoSuchElementException:
                logger.warning("主线程: 未找到结算按钮，可能页面未加载")
            except StaleElementReferenceException:
                logger.warning("主线程: 结算按钮元素已失效，重新查找")
            except Exception as e:
                logger.error(f"主线程错误: {str(e)}")

    def _is_running(self):
        """线程安全地检查运行状态"""
        with self.lock:
            return self.running

    def start(self):
        """启动抢购流程"""
        # 初始化浏览器
        self._init_browser()

        # 导航到购物车页面
        self.driver.get("https://cart.taobao.com/cart.htm")
        logger.info("已导航到淘宝购物车页面")

        # 用户准备阶段
        input("请在浏览器中完成登录并勾选需要抢购的商品，准备就绪后按回车键开始抢购...")

        # 预热页面
        logger.info("开始页面预热...")
        for i in range(2):
            self.driver.refresh()
            try:
                WebDriverWait(self.driver, 2).until(EC.presence_of_element_located((By.CLASS_NAME, 'btn--QDjHtErD')))
            except TimeoutException:
                logger.warning(f"第{i + 1}次刷新后未找到结算按钮")
            logger.info(f"第{i + 1}次刷新完成")

        # 启动抢购线程
        with self.lock:
            self.running = True

        self.main_thread = threading.Thread(target=self._main_monitor_task)
        self.main_thread.daemon = True
        self.main_thread.start()
        logger.info("抢购监控线程已启动")

        # 等待用户终止
        input("抢购程序已启动，按回车键停止...")
        self.stop()

    def stop(self):
        """停止所有任务并清理资源"""
        logger.info("开始停止所有任务...")
        with self.lock:
            self.running = False

        # 等待线程结束
        if self.main_thread and self.main_thread.is_alive():
            self.main_thread.join(timeout=2)
        if self.confirm_thread and self.confirm_thread.is_alive():
            self.confirm_thread.join(timeout=2)

        self._tear_down()

    def _tear_down(self):
        """清理浏览器资源"""
        if self.driver:
            logger.info("关闭浏览器...")
            self.driver.quit()
        logger.info("抢购程序已结束")


if __name__ == "__main__":
    try:
        seckill = TaobaoSeckill()
        seckill.start()
    except Exception as e:
        logger.critical(f"程序崩溃: {str(e)}", exc_info=True)
        if 'seckill' in locals() and seckill.driver:
            seckill.driver.quit()
