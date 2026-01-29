from flask import Flask, render_template, request, jsonify, Response
import threading
import logging
from datetime import datetime
import time
import os
import json
from collections import deque

from seckill import SeckillWorker

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


# 统一抢购逻辑
def run_seckill_task(task_id, platform, target_time=None, login_wait=15):
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

    def log_callback(message):
        task_manager.add_log(task_id, message)

    worker = None
    try:
        worker = SeckillWorker(platform, log_callback=log_callback)
        worker.start_seckill(target_time=target_time, login_wait=login_wait)
        task['status'] = 'success'
    except Exception as e:
        task_manager.add_log(task_id, f"错误：{str(e)}")
        task['status'] = 'error'
    finally:
        task['running'] = False
        if worker:
            worker.stop()


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
        driver_manager = ChromeDriverManager()
        driver_path = driver_manager.install()

        logger.info(f"使用驱动路径: {driver_path}")

        return jsonify({
            'success': True,
            'message': '驱动准备完成',
            'path': driver_path
        })
    except Exception as e:
        logger.error(f"下载驱动失败: {e}")
        return jsonify({
            'success': False,
            'message': f'下载失败: {str(e)}'
        }), 500


# API 路由
@app.route('/api/jd/start', methods=['POST'])
def start_jd():
    data = request.json
    target_time = data.get('target_time')

    if not target_time:
        return jsonify({'error': '请设置抢购时间'}), 400

    task_id = task_manager.create_task('jd')
    thread = threading.Thread(target=run_seckill_task, args=(task_id, 'jd', target_time, 25))
    thread.daemon = True
    thread.start()

    return jsonify({'task_id': task_id, 'status': 'started'})


@app.route('/api/tb/start', methods=['POST'])
def start_tb():
    task_id = task_manager.create_task('tb')
    thread = threading.Thread(target=run_seckill_task, args=(task_id, 'tb', None, 15))
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
