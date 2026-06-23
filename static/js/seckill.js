let currentTaskId = null;
let eventSource = null;
let currentPlatform = 'jd';
const seenLogKeys = new Set();

function getLogKey(log) {
    if (log.seq) return `seq:${log.seq}`;
    return `${log.time || ''}|${log.message || ''}`;
}

function appendLogIfNew(log) {
    const key = getLogKey(log);
    if (seenLogKeys.has(key)) return;
    seenLogKeys.add(key);
    addLog(log.message, log.time);
}

// 确保驱动已下载（带重试机制）
async function ensureDriver() {
    const logContainer = document.getElementById('logContainer');
    const MAX_RETRIES = 3;
    const RETRY_DELAY = 3000; // 3秒后重试

    // 移除初始提示
    const initialLog = logContainer.querySelector('.log-entry:first-child');
    if (initialLog && initialLog.textContent.includes('等待开始...')) {
        initialLog.remove();
    }

    for (let attempt = 0; attempt < MAX_RETRIES; attempt++) {
        const isRetry = attempt > 0;
        const driverMessage = document.createElement('div');
        driverMessage.className = 'log-entry';
        if (isRetry) {
            driverMessage.innerHTML = `<span class="log-time">${getCurrentTime()}</span>检查 Chrome 浏览器驱动...（第${attempt + 1}次尝试）`;
        } else {
            driverMessage.innerHTML = `<span class="log-time">${getCurrentTime()}</span>检查 Chrome 浏览器驱动...`;
        }
        logContainer.appendChild(driverMessage);
        logContainer.scrollTop = logContainer.scrollHeight;

        try {
            const response = await fetch('/api/driver/download', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json'
                }
            });

            const data = await response.json();

            if (data.success) {
                // 添加下载中的提示
                const downloadingMessage = document.createElement('div');
                downloadingMessage.className = 'log-entry';
                downloadingMessage.innerHTML = `<span class="log-time">${getCurrentTime()}</span>正在下载匹配的 ChromeDriver...`;
                logContainer.appendChild(downloadingMessage);
                logContainer.scrollTop = logContainer.scrollHeight;

                // 延迟显示成功消息
                await new Promise(resolve => setTimeout(resolve, 500));

                const successMessage = document.createElement('div');
                successMessage.className = 'log-entry';
                successMessage.innerHTML = `<span class="log-time">${getCurrentTime()}</span>✓ ChromeDriver 准备完成`;
                logContainer.appendChild(successMessage);
                logContainer.scrollTop = logContainer.scrollHeight;

                if (data.path) {
                    const pathMessage = document.createElement('div');
                    pathMessage.className = 'log-entry';
                    pathMessage.style.color = '#1976D2';
                    pathMessage.innerHTML = `<span class="log-time">${getCurrentTime()}</span>驱动路径: ${data.path}`;
                    logContainer.appendChild(pathMessage);
                    logContainer.scrollTop = logContainer.scrollHeight;
                }

                // 更新进度条到步骤1
                updateSteps(1);
                return; // 成功，退出重试循环
            } else {
                const errorMessage = document.createElement('div');
                errorMessage.className = 'log-entry';
                errorMessage.style.color = '#FF5252';
                errorMessage.innerHTML = `<span class="log-time">${getCurrentTime()}</span>✗ 驱动准备失败: ${data.message}`;
                logContainer.appendChild(errorMessage);
                logContainer.scrollTop = logContainer.scrollHeight;
            }
        } catch (error) {
            const errorMessage = document.createElement('div');
            errorMessage.className = 'log-entry';
            errorMessage.style.color = '#FF5252';
            errorMessage.innerHTML = `<span class="log-time">${getCurrentTime()}</span>✗ 驱动准备失败: ${error.message}`;
            logContainer.appendChild(errorMessage);
            logContainer.scrollTop = logContainer.scrollHeight;
        }

        // 如果不是最后一次尝试，等待后重试
        if (attempt < MAX_RETRIES - 1) {
            const retryMsg = document.createElement('div');
            retryMsg.className = 'log-entry';
            retryMsg.innerHTML = `<span class="log-time">${getCurrentTime()}</span>${RETRY_DELAY / 1000}秒后重试...`;
            logContainer.appendChild(retryMsg);
            logContainer.scrollTop = logContainer.scrollHeight;
            await new Promise(resolve => setTimeout(resolve, RETRY_DELAY));
        }
    }
}

async function startTask() {
    currentPlatform = document.querySelector('input[name="platform"]:checked').value;

    if (currentPlatform === 'jd') {
        const targetTime = getFormattedTime();
        if (!targetTime) {
            return;
        }
        const productKeyword = (
            document.getElementById('jdProductKeyword')?.value || ''
        ).trim();

        try {
            const response = await fetch('/api/jd/start', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                },
                body: JSON.stringify({
                    target_time: targetTime,
                    product_keyword: productKeyword
                })
            });

            const data = await response.json();

            if (response.ok) {
                currentTaskId = data.task_id;
                sessionStorage.setItem('autobuyTaskId', currentTaskId);
                sessionStorage.setItem('autobuyPlatform', currentPlatform);
                sessionStorage.setItem('autobuyJdProductKeyword', productKeyword);
                document.getElementById('startBtn').disabled = true;
                document.getElementById('stopBtn').disabled = false;
                disableTimeInputs(true);
                document.querySelectorAll('input[name="platform"]').forEach(radio => radio.disabled = true);
                updateStatus('running');
                updateSteps(1);
                startLogStream();
            } else {
                alert(data.error || '启动失败');
            }
        } catch (error) {
            alert('请求失败：' + error.message);
        }
    } else if (currentPlatform === 'tb') {
        const targetTime = getFormattedTime();
        if (!targetTime) {
            return;
        }

        try {
            const response = await fetch('/api/tb/start', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                },
                body: JSON.stringify({ target_time: targetTime })
            });

            const data = await response.json();

            if (response.ok) {
                currentTaskId = data.task_id;
                sessionStorage.setItem('autobuyTaskId', currentTaskId);
                sessionStorage.setItem('autobuyPlatform', currentPlatform);
                document.getElementById('startBtn').disabled = true;
                document.getElementById('stopBtn').disabled = false;
                disableTimeInputs(true);
                document.querySelectorAll('input[name="platform"]').forEach(radio => radio.disabled = true);
                updateStatus('running');
                updateSteps(1);
                startLogStream();
            } else {
                alert(data.error || '启动失败');
            }
        } catch (error) {
            alert('请求失败：' + error.message);
        }
    }
}

async function stopTask() {
    if (!currentTaskId) return;

    try {
        const response = await fetch(`/api/tasks/${currentTaskId}/stop`, {
            method: 'POST'
        });

        if (response.ok) {
            addLog('用户请求停止任务');
            resetUI();
        }
    } catch (error) {
        alert('停止失败：' + error.message);
    }
}

async function closeBrowser() {
    if (!currentTaskId) return;

    try {
        const response = await fetch(`/api/tasks/${currentTaskId}/close-browser`, {
            method: 'POST'
        });

        const data = await response.json();

        if (response.ok) {
            addLog('浏览器已关闭');
            document.getElementById('closeBrowserBtn').disabled = true;
        } else {
            alert(data.error || '关闭浏览器失败');
        }
    } catch (error) {
        alert('关闭浏览器失败：' + error.message);
    }
}

async function resetTask() {
    if (!currentTaskId) {
        resetUI();
        return;
    }

    if (!confirm('确定要重置任务吗？这将停止当前任务并清除所有日志。')) {
        return;
    }

    try {
        const response = await fetch(`/api/tasks/${currentTaskId}/stop`, {
            method: 'POST'
        });

        if (response.ok) {
            addLog('任务已重置');
            clearLogs();
            resetUI();
        }
    } catch (error) {
        // 如果停止失败，仍然重置UI
        clearLogs();
        resetUI();
    }
}

function clearLogs() {
    seenLogKeys.clear();
    const logContainer = document.getElementById('logContainer');
    if (logContainer) {
        logContainer.innerHTML = '<div class="log-entry"><span class="log-time">--:--:--</span>等待开始...</div>';
    }
}

function startLogStream() {
    if (eventSource) {
        eventSource.close();
    }

    eventSource = new EventSource(`/api/tasks/${currentTaskId}/logs`);

    eventSource.onmessage = function(event) {
        try {
            const log = JSON.parse(event.data);
            appendLogIfNew(log);

            // 根据日志内容更新步骤和确认按钮状态
            // 步骤1: 初始化浏览器
            if (log.message.includes('初始化浏览器') || log.message.includes('正在导航到')) {
                updateSteps(1);
            }

            // 步骤2: 等待登录确认
            if (log.message.includes('等待用户确认登录') || log.message.includes('请点击页面上的')) {
                const confirmLoginBtn = document.getElementById('confirmLoginBtn');
                console.log('检测到登录确认日志，confirmLoginBtn:', confirmLoginBtn);
                if (confirmLoginBtn) {
                    confirmLoginBtn.disabled = false;
                    confirmLoginBtn.textContent = '确认登录';
                    console.log('已启用登录确认按钮');
                }
                updateSteps(2);
            }

            // 步骤3: 等待购物车确认
            if (log.message.includes('请手动在浏览器中进入购物车') ||
                log.message.includes('等待购物车确认') ||
                log.message.includes('然后点击页面上的')) {
                const confirmCartBtn = document.getElementById('confirmCartBtn');
                console.log('检测到购物车确认日志，confirmCartBtn:', confirmCartBtn);
                if (confirmCartBtn) {
                    confirmCartBtn.disabled = false;
                    confirmCartBtn.textContent = '确认购物车';
                    console.log('已启用购物车确认按钮');
                }
                updateSteps(3);
            }

            // 步骤4: 执行抢购
            if (log.message.includes('开始抢购') || log.message.includes('测试页面加载性能')) {
                updateSteps(4);
            }

            // 步骤5: 抢购完成
            if (log.message.includes('抢购成功') || log.message.includes('任务已完成')) {
                const closeBrowserBtn = document.getElementById('closeBrowserBtn');
                if (closeBrowserBtn) {
                    closeBrowserBtn.disabled = false;
                }
                updateSteps(5);
            }

            if (log.message.includes('用户已确认') || log.message.includes('继续下一步')) {
                // 确认按钮状态由confirmStage函数控制
            }

            if (log.message.includes('订单确认线程已启动') || log.message.includes('抢购监控已启动')) {
                updateSteps(4);
            }

            if (log.message.includes('抢购成功') || log.message.includes('任务已完成')) {
                const closeBrowserBtn = document.getElementById('closeBrowserBtn');
                if (closeBrowserBtn) {
                    closeBrowserBtn.disabled = false;
                }
            }

            if (log.error) {
                addLog('错误：' + log.error);
            }
        } catch (e) {
            addLog(event.data);
        }
    };

    eventSource.onerror = function() {
        // EventSource 会自动重连；不要在短暂断线时永久关闭日志流。
        console.warn('日志连接暂时中断，正在自动重连...');
    };
}

function addLog(message, time = null) {
    const logContainer = document.getElementById('logContainer');

    // 检测消息是否以 [网络时间] 开头，如 "[10:59:52] 距离抢购还有 8秒..."
    const networkTimeMatch = message.match(/^\[(\d{2}:\d{2}:\d{2})\]\s*/);
    let timestamp;
    let displayMessage;

    if (networkTimeMatch) {
        // 使用消息中嵌入的网络时间
        timestamp = networkTimeMatch[1];
        displayMessage = message.substring(networkTimeMatch[0].length);
    } else {
        // 使用后端传入的时间或本地时间
        timestamp = time || getCurrentTime();
        displayMessage = message;
    }

    const logEntry = document.createElement('div');
    logEntry.className = 'log-entry';
    logEntry.innerHTML = `<span class="log-time">${timestamp}</span>${displayMessage}`;

    logContainer.appendChild(logEntry);
    logContainer.scrollTop = logContainer.scrollHeight;

    // 移除初始提示
    const initialLog = logContainer.querySelector('.log-entry:first-child');
    if (initialLog && initialLog.textContent.includes('等待开始...')) {
        initialLog.remove();
    }
}

function getCurrentTime() {
    const now = new Date();
    const hours = String(now.getHours()).padStart(2, '0');
    const minutes = String(now.getMinutes()).padStart(2, '0');
    const seconds = String(now.getSeconds()).padStart(2, '0');
    return `${hours}:${minutes}:${seconds}`;
}

function updateStatus(status) {
    const statusBadge = document.getElementById('status');
    statusBadge.textContent = getStatusText(status);
    statusBadge.className = 'status-badge status-' + status;

    // 任务完成后不再自动重置UI，让用户手动点击重置按钮
}

function getStatusText(status) {
    const statusMap = {
        'pending': '等待中',
        'running': '运行中',
        'success': '成功',
        'failed': '失败',
        'error': '错误',
        'stopped': '已停止'
    };
    return statusMap[status] || status;
}

function updateSteps(currentStep) {
    const progressFill = document.getElementById('progressFill');
    const totalSteps = 5;
    const percentage = ((currentStep - 1) / (totalSteps - 1)) * 100;
    progressFill.style.width = percentage + '%';

    // 更新步骤样式
    for (let i = 1; i <= totalSteps; i++) {
        const step = document.getElementById('step' + i);
        if (step) {
            if (i <= currentStep) {
                step.classList.add('active');
            } else {
                step.classList.remove('active');
            }
        }
    }
}

function disableTimeInputs(disabled) {
    const inputs = [
        'targetDate',
        'targetHour',
        'targetMinute',
        'targetSecond',
        'targetMicrosecond',
        'jdProductKeyword'
    ];
    inputs.forEach(id => {
        const el = document.getElementById(id);
        if (el) el.disabled = disabled;
    });
}

function resetUI() {
    document.getElementById('startBtn').disabled = false;
    document.getElementById('stopBtn').disabled = true;
    const closeBrowserBtn = document.getElementById('closeBrowserBtn');
    if (closeBrowserBtn) {
        closeBrowserBtn.disabled = true;
    }

    // 重置确认按钮
    const confirmLoginBtn = document.getElementById('confirmLoginBtn');
    if (confirmLoginBtn) {
        confirmLoginBtn.disabled = true;
        confirmLoginBtn.textContent = '确认登录';
    }

    const confirmCartBtn = document.getElementById('confirmCartBtn');
    if (confirmCartBtn) {
        confirmCartBtn.disabled = true;
        confirmCartBtn.textContent = '确认购物车';
    }

    document.getElementById('startBtn').disabled = false;
    document.getElementById('stopBtn').disabled = true;
    disableTimeInputs(false);
    document.querySelectorAll('input[name="platform"]').forEach(radio => radio.disabled = false);
    updateSteps(0);
    updateStatus('pending');

    if (eventSource) {
        eventSource.close();
        eventSource = null;
    }

    currentTaskId = null;
    sessionStorage.removeItem('autobuyTaskId');
    sessionStorage.removeItem('autobuyPlatform');
    sessionStorage.removeItem('autobuyJdProductKeyword');
}

async function restoreTask() {
    let savedTaskId = sessionStorage.getItem('autobuyTaskId');
    let savedPlatform = sessionStorage.getItem('autobuyPlatform');

    if (!savedTaskId) {
        const candidates = await Promise.all(
            Array.from({ length: 20 }, async (_, index) => {
                const taskId = `task_${index + 1}`;
                try {
                    const response = await fetch(`/api/tasks/${taskId}/status`);
                    if (!response.ok) return null;
                    const data = await response.json();
                    return data.running ? data : null;
                } catch {
                    return null;
                }
            })
        );
        const runningTasks = candidates.filter(Boolean);
        if (!runningTasks.length) return;
        const discovered = runningTasks[runningTasks.length - 1];
        savedTaskId = discovered.id;
        savedPlatform = discovered.platform;
        sessionStorage.setItem('autobuyTaskId', savedTaskId);
        sessionStorage.setItem('autobuyPlatform', savedPlatform);
    }

    try {
        const response = await fetch(`/api/tasks/${savedTaskId}/status`);
        if (!response.ok) {
            sessionStorage.removeItem('autobuyTaskId');
            sessionStorage.removeItem('autobuyPlatform');
            return;
        }

        const data = await response.json();
        currentTaskId = savedTaskId;
        currentPlatform = savedPlatform || data.platform;

        const platformRadio = document.querySelector(
            `input[name="platform"][value="${currentPlatform}"]`
        );
        if (platformRadio) platformRadio.checked = true;
        const keywordInput = document.getElementById('jdProductKeyword');
        if (keywordInput) {
            keywordInput.value = (
                data.product_keyword ||
                sessionStorage.getItem('autobuyJdProductKeyword') ||
                ''
            );
        }

        document.getElementById('startBtn').disabled = true;
        document.getElementById('stopBtn').disabled = !data.running;
        disableTimeInputs(true);
        document.querySelectorAll('input[name="platform"]').forEach(
            radio => radio.disabled = true
        );
        updatePlatform();
        updateStatus(data.status);

        clearLogs();
        data.logs.forEach(appendLogIfNew);

        if (data.running) {
            updateSteps(3);
            startLogStream();
        }
    } catch (error) {
        console.warn('恢复任务状态失败：', error);
    }
}

// 页面加载完成后检查驱动
if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', ensureDriver);
} else {
    ensureDriver();
}

// 定期检查任务状态
setInterval(async () => {
    if (currentTaskId) {
        try {
            const response = await fetch(`/api/tasks/${currentTaskId}/status`);
            const data = await response.json();
            if (Array.isArray(data.logs)) {
                data.logs.forEach(appendLogIfNew);
            }

            if (data.status && data.status !== 'running') {
                updateStatus(data.status);
                if (eventSource) {
                    eventSource.close();
                    eventSource = null;
                }
            }
        } catch (error) {
            console.error('检查状态失败：', error);
        }
    }
}, 2000);

window.addEventListener('load', restoreTask);
