from flask import Flask, render_template, request, jsonify, Response
import threading
import logging
from datetime import datetime, timedelta
import time
import requests
import os
from selenium import webdriver
from selenium.webdriver.common.by import By
from selenium.webdriver.support.ui import WebDriverWait
from selenium.webdriver.support import expected_conditions as EC
from selenium.webdriver.chrome.options import Options
from selenium.webdriver.chrome.service import Service as ChromeService
from webdriver_manager.chrome import ChromeDriverManager
from selenium.common.exceptions import (
    NoSuchElementException, TimeoutException
)
import json
from collections import deque

# 设置项目根目录
PROJECT_DIR = os.path.dirname(os.path.abspath(__file__))

app = Flask(__name__)

# 配置日志
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s',
    datefmt='%Y-%m-%d %H:%M:%S'
)
logger = logging.getLogger(__name__)

# 全局状态管理
class TaskManager:
    def __init__(self):
        self.tasks = {}
        self.task_counter = 0

    def create_task(self, platform):
        self.task_counter += 1
        task_id = f"task_{self.task_counter}"
        self.tasks[task_id] = {
            'id': task_id,
            'platform': platform,
            'status': 'pending',
            'logs': deque(maxlen=100),
            'driver': None,
            'running': False,
            'thread': None,
            'target_time': None
        }
        return task_id

    def get_task(self, task_id):
        return self.tasks.get(task_id)

    def add_log(self, task_id, message):
        if task_id in self.tasks:
            timestamp = datetime.now().strftime('%H:%M:%S')
            self.tasks[task_id]['logs'].append({
                'time': timestamp,
                'message': message
            })

    def stop_task(self, task_id):
        if task_id in self.tasks:
            self.tasks[task_id]['running'] = False
            self.tasks[task_id]['status'] = 'stopped'

    def remove_task(self, task_id):
        if task_id in self.tasks:
            task = self.tasks[task_id]
            if task['driver']:
                try:
                    task['driver'].quit()
                except:
                    pass
            del self.tasks[task_id]

task_manager = TaskManager()


# 京东抢购逻辑
def run_jd_task(task_id, target_time):
    task = task_manager.get_task(task_id)
    if not task:
        return

    task['status'] = 'running'
    task['running'] = True
    driver = None

    try:
        task_manager.add_log(task_id, "初始化浏览器...")
        options = webdriver.ChromeOptions()
        options.add_argument("--disable-blink-features=AutomationControlled")
        options.add_argument("--disable-gpu")
        options.add_argument("--no-sandbox")
        options.add_argument("--incognito")
        options.add_experimental_option("excludeSwitches", ["enable-automation"])
        user_agent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0 Safari/537.36"
        options.add_argument(f'user-agent={user_agent}')

        # 设置驱动下载到项目目录
        driver_path = ChromeDriverManager(path=os.path.join(PROJECT_DIR, 'drivers')).install()
        driver = webdriver.Chrome(service=ChromeService(driver_path), options=options)
        task['driver'] = driver

        task_manager.add_log(task_id, "导航到京东首页...")
        driver.get("https://www.jd.com")
        time.sleep(2)

        task_manager.add_log(task_id, "请在浏览器中扫码登录...")
        driver.find_element("link text", "你好，请登录").click()
        time.sleep(25)

        task_manager.add_log(task_id, "等待用户准备商品...")
        time.sleep(60)

        task_manager.add_log(task_id, "跳转到订单确认页面...")
        driver.get("https://trade.jd.com/shopping/order/getOrderInfo.action")
        time.sleep(15)

        # 测试页面加载时间
        task_manager.add_log(task_id, "测试页面加载性能...")
        num_tests = 3
        total_load_time = 0
        for i in range(num_tests):
            start_time = time.time()
            driver.refresh()
            WebDriverWait(driver, 2).until(EC.presence_of_element_located((By.CLASS_NAME, 'checkout-submit')))
            end_time = time.time()
            load_time = end_time - start_time
            total_load_time += load_time
            task_manager.add_log(task_id, f'第{i+1}次加载时间：{load_time:.2f}秒')
            time.sleep(15)

        average_load_time = total_load_time / num_tests
        task_manager.add_log(task_id, f'平均加载时间：{average_load_time:.2f}秒')
        if average_load_time < 0.5:
            average_load_time = 0.5

        # 调整目标时间
        mstime_datetime = datetime.strptime(target_time, "%Y-%m-%d %H:%M:%S.%f")
        mstime_datetime = mstime_datetime - timedelta(seconds=average_load_time - 0.1)
        mstime = mstime_datetime.strftime('%Y-%m-%d %H:%M:%S.%f')
        task['target_time'] = mstime
        task_manager.add_log(task_id, f'调整后的抢购时间：{mstime}')

        # 时间校准
        def jd_time():
            url = 'https://api.m.jd.com'
            resp = requests.get(url, verify=False)
            request_id = resp.headers.get('X-API-Request-Id')
            if not request_id:
                raise Exception('无法获取京东服务器时间')
            jd_timestamp = int(request_id[-13:])
            return jd_timestamp

        def local_time():
            return round(time.time() * 1000)

        def local_jd_time_diff():
            jd_ts = jd_time()
            local_ts = local_time()
            return local_ts - jd_ts

        diff = local_jd_time_diff()
        task_manager.add_log(task_id, f"时间差（毫秒）：{diff}")

        if diff > 1000 or diff < -1000:
            mstime_datetime = mstime_datetime - timedelta(milliseconds=diff - 100)
            mstime = mstime_datetime.strftime('%Y-%m-%d %H:%M:%S.%f')
            task['target_time'] = mstime
            task_manager.add_log(task_id, f'校准后抢购时间：{mstime}')

        # 等待抢购时间
        task_manager.add_log(task_id, f"等待到达抢购时间 {mstime}...")
        i = 0
        while task['running']:
            now = datetime.now().strftime('%Y-%m-%d %H:%M:%S.%f')
            if now >= mstime:
                break
            time.sleep(0.1)

        # 开始抢购
        task_manager.add_log(task_id, "开始抢购！")
        success = False
        while task['running'] and not success:
            try:
                driver.find_element(By.CLASS_NAME, "checkout-submit").click()
                task_manager.add_log(task_id, "✓ 抢购成功！请尽快付款")
                now = datetime.now().strftime('%Y-%m-%d %H:%M:%S.%f')
                task_manager.add_log(task_id, f"抢购时间：{now}")
                success = True
                task['status'] = 'success'
            except Exception as e:
                if i < 10:
                    i += 1
                    time.sleep(0.1)
                    if i % 5 == 0:
                        task_manager.add_log(task_id, f"尝试中... 第{i}次")
                else:
                    task_manager.add_log(task_id, "✗ 抢购失败，已停止")
                    task['status'] = 'failed'
                    break

        if success:
            task_manager.add_log(task_id, "20秒后自动关闭浏览器")
            time.sleep(20)

    except Exception as e:
        task_manager.add_log(task_id, f"错误：{str(e)}")
        task['status'] = 'error'
    finally:
        task['running'] = False
        if driver:
            try:
                driver.quit()
            except:
                pass
            task['driver'] = None


# 淘宝抢购逻辑
def run_tb_task(task_id):
    task = task_manager.get_task(task_id)
    if not task:
        return

    task['status'] = 'running'
    task['running'] = True
    driver = None
    main_thread = None
    confirm_thread = None

    def start_confirm_thread():
        def confirm_task():
            while task['running'] and driver:
                try:
                    submit_btn = WebDriverWait(driver, 1, 0.01).until(
                        EC.element_to_be_clickable((By.CSS_SELECTOR, ".go-btn"))
                    )
                    for _ in range(3):
                        try:
                            if driver:
                                driver.execute_script("arguments[0].click();", submit_btn)
                                task_manager.add_log(task_id, "✓ 高频线程: 成功点击提交订单按钮")
                                break
                        except:
                            pass
                except (NoSuchElementException, TimeoutException):
                    pass
                except Exception as e:
                    task_manager.add_log(task_id, f"高频线程错误: {str(e)}")

        nonlocal confirm_thread
        if not confirm_thread or not confirm_thread.is_alive():
            confirm_thread = threading.Thread(target=confirm_task)
            confirm_thread.daemon = True
            confirm_thread.start()
            task_manager.add_log(task_id, "订单确认线程已启动")

    def main_monitor_task():
        while task['running']:
            if not driver:
                break
            try:
                settle_btn = driver.find_element(By.CLASS_NAME, "btn--QDjHtErD")
                btn_text = settle_btn.text.strip()
                task_manager.add_log(task_id, f"当前按钮状态: {btn_text}")

                try:
                    driver.find_element(By.CSS_SELECTOR, ".go-btn")
                    start_confirm_thread()
                    continue
                except NoSuchElementException:
                    pass

                if btn_text in ["结算", "立即购买"]:
                    task_manager.add_log(task_id, f"尝试点击{btn_text}按钮")
                    for _ in range(2):
                        try:
                            settle_btn.click()
                            task_manager.add_log(task_id, "✓ 主线程: 成功点击结算按钮")
                            break
                        except:
                            try:
                                driver.execute_script("arguments[0].click();", settle_btn)
                                task_manager.add_log(task_id, "✓ 主线程: 成功点击结算按钮(JS)")
                                break
                            except Exception as e:
                                task_manager.add_log(task_id, f"点击失败: {str(e)}")

            except NoSuchElementException:
                time.sleep(0.1)
            except Exception as e:
                task_manager.add_log(task_id, f"主线程错误: {str(e)}")

    try:
        task_manager.add_log(task_id, "初始化浏览器...")
        chrome_options = Options()
        chrome_options.add_experimental_option("excludeSwitches", ["enable-automation"])
        chrome_options.add_experimental_option("useAutomationExtension", False)
        chrome_options.add_argument("--disable-blink-features=AutomationControlled")
        chrome_options.add_argument("--disable-gpu")
        chrome_options.add_argument("--no-sandbox")

        # 设置驱动下载到项目目录
        driver_path = ChromeDriverManager(path=os.path.join(PROJECT_DIR, 'drivers')).install()
        driver = webdriver.Chrome(service=ChromeService(driver_path), options=chrome_options)
        task['driver'] = driver

        driver.execute_cdp_cmd("Page.addScriptToEvaluateOnNewDocument", {
            "source": """
                Object.defineProperty(navigator, 'webdriver', {
                    get: () => undefined
                })
            """
        })
        driver.maximize_window()
        task_manager.add_log(task_id, "浏览器初始化完成")

        task_manager.add_log(task_id, "导航到淘宝购物车...")
        driver.get("https://cart.taobao.com/cart.htm")

        task_manager.add_log(task_id, "请登录浏览器并勾选商品，然后点击'开始抢购'按钮...")
        while task['running'] and not task.get('ready', False):
            time.sleep(0.5)

        if not task['running']:
            return

        task_manager.add_log(task_id, "开始页面预热...")
        for i in range(2):
            driver.refresh()
            try:
                WebDriverWait(driver, 2).until(EC.presence_of_element_located((By.CLASS_NAME, 'btn--QDjHtErD')))
            except TimeoutException:
                task_manager.add_log(task_id, f"第{i+1}次刷新后未找到结算按钮")
            task_manager.add_log(task_id, f"第{i+1}次刷新完成")
            time.sleep(2)

        task_manager.add_log(task_id, "抢购监控已启动，等待点击结算按钮...")
        main_thread = threading.Thread(target=main_monitor_task)
        main_thread.daemon = True
        main_thread.start()
        task['thread'] = main_thread

        while task['running']:
            time.sleep(0.5)

    except Exception as e:
        task_manager.add_log(task_id, f"错误：{str(e)}")
        task['status'] = 'error'
    finally:
        task['running'] = False
        if main_thread and main_thread.is_alive():
            main_thread.join(timeout=2)
        if confirm_thread and confirm_thread.is_alive():
            confirm_thread.join(timeout=2)
        if driver:
            try:
                driver.quit()
            except:
                pass
            task['driver'] = None


# 路由定义
@app.route('/')
def index():
    return render_template('index.html')


@app.route('/help')
def help_page():
    return render_template('help.html')


# API 路由
@app.route('/api/jd/start', methods=['POST'])
def start_jd():
    data = request.json
    target_time = data.get('target_time')

    if not target_time:
        return jsonify({'error': '请设置抢购时间'}), 400

    task_id = task_manager.create_task('jd')
    thread = threading.Thread(target=run_jd_task, args=(task_id, target_time))
    thread.daemon = True
    thread.start()

    return jsonify({'task_id': task_id, 'status': 'started'})


@app.route('/api/tb/start', methods=['POST'])
def start_tb():
    task_id = task_manager.create_task('tb')
    thread = threading.Thread(target=run_tb_task, args=(task_id,))
    thread.daemon = True
    thread.start()

    return jsonify({'task_id': task_id, 'status': 'started'})


@app.route('/api/tb/ready', methods=['POST'])
def ready_tb():
    data = request.json
    task_id = data.get('task_id')
    task = task_manager.get_task(task_id)

    if task:
        task['ready'] = True
        task_manager.add_log(task_id, "用户已确认准备就绪，开始抢购流程")
        return jsonify({'status': 'ok'})

    return jsonify({'error': '任务不存在'}), 404


@app.route('/api/tasks/<task_id>/status')
def get_task_status(task_id):
    task = task_manager.get_task(task_id)
    if not task:
        return jsonify({'error': '任务不存在'}), 404

    return jsonify({
        'id': task['id'],
        'platform': task['platform'],
        'status': task['status'],
        'running': task['running'],
        'target_time': task.get('target_time'),
        'logs': list(task['logs'])
    })


@app.route('/api/tasks/<task_id>/stop', methods=['POST'])
def stop_task(task_id):
    task = task_manager.get_task(task_id)
    if not task:
        return jsonify({'error': '任务不存在'}), 404

    task_manager.stop_task(task_id)
    task_manager.add_log(task_id, "用户请求停止任务")

    return jsonify({'status': 'stopped'})


@app.route('/api/tasks/<task_id>/logs')
def stream_logs(task_id):
    def generate():
        last_log_count = 0
        while True:
            task = task_manager.get_task(task_id)
            if not task:
                yield f"data: {json.dumps({'error': '任务不存在'})}\n\n"
                break

            logs = list(task['logs'])
            if len(logs) > last_log_count:
                for log in logs[last_log_count:]:
                    yield f"data: {json.dumps(log)}\n\n"
                last_log_count = len(logs)

            if not task['running'] and task['status'] in ['success', 'failed', 'error', 'stopped']:
                break

            time.sleep(0.5)

    return Response(generate(), mimetype='text/event-stream')


if __name__ == '__main__':
    app.run(debug=True, host='0.0.0.0', port=5000)
