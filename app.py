from flask import Flask, render_template, request, jsonify, Response
import threading
import logging
from datetime import datetime
import time
import os
import json
from collections import deque

from seckill import SeckillWorker, BrowserManager

# 设置项目根目录
PROJECT_DIR = os.path.dirname(os.path.abspath(__file__))
DRIVERS_DIR = os.path.join(PROJECT_DIR, 'drivers')

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

    def create_task(self, platform, target_time=None, product_keyword=None):
        self.task_counter += 1
        task_id = f"task_{self.task_counter}"
        self.tasks[task_id] = {
            'id': task_id,
            'platform': platform,
            'status': 'pending',
            'logs': deque(maxlen=1000),
            'log_seq': 0,
            'driver': None,
            'running': False,
            'thread': None,
            'target_time': target_time,
            'product_keyword': product_keyword
        }
        return task_id

    def get_task(self, task_id):
        return self.tasks.get(task_id)

    def add_log(self, task_id, message):
        if task_id in self.tasks:
            self.tasks[task_id]['log_seq'] += 1
            # 如果消息已包含网络时间戳，提取并使用它；否则使用本地时间作为后备
            import re
            network_time_match = re.match(r'^\[(\d{2}:\d{2}:\d{2})\]\s*', message)
            if network_time_match:
                timestamp = network_time_match.group(1)
            else:
                timestamp = datetime.now().strftime('%H:%M:%S')
            self.tasks[task_id]['logs'].append({
                'seq': self.tasks[task_id]['log_seq'],
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


# 统一抢购逻辑
def run_seckill_task(
    task_id,
    platform,
    target_time=None,
    login_wait=15,
    product_keyword=None
):
    """
    统一抢购任务
    :param task_id: 任务ID
    :param platform: 平台名称 (jd/tb/bb)
    :param target_time: 目标时间
    :param login_wait: 登录等待时间
    """
    task = task_manager.get_task(task_id)
    if not task:
        return

    task['status'] = 'running'
    task['running'] = True
    task['worker'] = None

    def log_callback(message):
        task_manager.add_log(task_id, message)

    worker = None
    try:
        worker = SeckillWorker(
            platform,
            log_callback=log_callback,
            product_keyword=product_keyword
        )
        task['worker'] = worker
        # 启用登录和购物车确认
        success = worker.start_seckill(
            target_time=target_time,
            login_wait=login_wait,
            wait_for_login_confirm=True,
            wait_for_cart_confirm=True
        )
        if task['status'] != 'stopped':
            task['status'] = 'success' if success else 'failed'
    except Exception as e:
        task_manager.add_log(task_id, f"错误：{str(e)}")
        task['status'] = 'error'
    finally:
        task['running'] = False


# 路由定义
@app.route('/')
def index():
    return render_template('index.html')


@app.route('/help')
def help_page():
    return render_template('help.html')


# API 路由
@app.route('/api/driver/download', methods=['POST'])
def download_driver():
    """下载驱动"""
    try:
        from webdriver_manager.chrome import ChromeDriverManager

        logger.info("开始检查 Chrome 浏览器版本...")
        # 优先使用本地缓存的驱动
        driver_path = BrowserManager._find_cached_driver()

        if driver_path:
            logger.info(f"使用本地缓存驱动: {driver_path}")
        else:
            logger.info("本地无缓存驱动，正在下载匹配的 ChromeDriver...")
            driver_manager = ChromeDriverManager()
            driver_path = driver_manager.install()

        logger.info(f"ChromeDriver 准备完成，路径: {driver_path}")

        return jsonify({
            'success': True,
            'message': '驱动准备完成',
            'path': driver_path
        })
    except Exception as e:
        import traceback
        error_detail = traceback.format_exc()
        logger.error(f"下载驱动失败: {e}\n{error_detail}")
        return jsonify({
            'success': False,
            'message': f'下载失败: {str(e)}'
        }), 500


# API 路由
@app.route('/api/jd/start', methods=['POST'])
def start_jd():
    data = request.json
    target_time = data.get('target_time')
    product_keyword = (data.get('product_keyword') or '').strip()

    if not target_time:
        return jsonify({'error': '请设置抢购时间'}), 400

    task_id = task_manager.create_task('jd', target_time, product_keyword)
    thread = threading.Thread(
        target=run_seckill_task,
        args=(task_id, 'jd', target_time, 25, product_keyword)
    )
    thread.daemon = True
    thread.start()

    return jsonify({'task_id': task_id, 'status': 'started'})


@app.route('/api/tb/start', methods=['POST'])
def start_tb():
    data = request.json
    target_time = data.get('target_time')

    if not target_time:
        return jsonify({'error': '请设置抢购时间'}), 400

    task_id = task_manager.create_task('tb', target_time)
    thread = threading.Thread(target=run_seckill_task, args=(task_id, 'tb', target_time, 15))
    thread.daemon = True
    thread.start()

    return jsonify({'task_id': task_id, 'status': 'started'})


@app.route('/api/tasks/<task_id>/confirm', methods=['POST'])
def confirm_stage(task_id):
    """用户确认当前阶段，进入下一步"""
    data = request.json
    stage = data.get('stage')  # 'login' 或 'cart'
    task = task_manager.get_task(task_id)

    if not task:
        return jsonify({'error': '任务不存在'}), 404

    if not task.get('worker'):
        return jsonify({'error': 'Worker未初始化'}), 400

    worker = task['worker']
    # 使用字典来设置确认状态
    if hasattr(worker, '_confirm_states'):
        worker._confirm_states[stage] = True
        logger.info(f"设置 {stage}_confirmed = True for task {task_id}")
        logger.info(f"当前确认状态: {worker._confirm_states}")
    else:
        logger.error(f"Worker 没有 _confirm_states 属性")

    task_manager.add_log(task_id, f"用户已确认{stage}阶段，继续下一步...")

    return jsonify({'status': 'ok'})


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
        'product_keyword': task.get('product_keyword'),
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


@app.route('/api/tasks/<task_id>/close-browser', methods=['POST'])
def close_browser(task_id):
    """关闭浏览器"""
    task = task_manager.get_task(task_id)
    if not task:
        return jsonify({'error': '任务不存在'}), 404

    worker = task.get('worker')
    if worker and hasattr(worker, 'driver') and worker.driver:
        try:
            worker.stop()
            task_manager.add_log(task_id, "浏览器已关闭")
            return jsonify({'status': 'ok'})
        except Exception as e:
            task_manager.add_log(task_id, f"关闭浏览器失败：{str(e)}")
            return jsonify({'error': str(e)}), 500
    else:
        return jsonify({'error': '浏览器未打开或已关闭'}), 400


@app.route('/api/tasks/<task_id>/logs')
def stream_logs(task_id):
    last_event_id = int(request.headers.get('Last-Event-ID', '0') or 0)

    def generate():
        last_seq = last_event_id
        while True:
            task = task_manager.get_task(task_id)
            if not task:
                yield f"data: {json.dumps({'error': '任务不存在'})}\n\n"
                break

            logs = list(task['logs'])
            new_logs = [
                log for log in logs
                if log.get('seq', 0) > last_seq
            ]
            if new_logs:
                for log in new_logs:
                    yield (
                        f"id: {log.get('seq', 0)}\n"
                        f"data: {json.dumps(log)}\n\n"
                    )
                last_seq = new_logs[-1].get('seq', last_seq)

            if not task['running'] and task['status'] in ['success', 'failed', 'error', 'stopped']:
                break

            time.sleep(0.5)

    return Response(generate(), mimetype='text/event-stream')


if __name__ == '__main__':
    port = int(os.environ.get('PORT', '5000'))
    app.run(debug=False, host='127.0.0.1', port=port, threaded=True, use_reloader=False)
