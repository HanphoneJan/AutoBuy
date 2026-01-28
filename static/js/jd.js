let currentTaskId = null;
let eventSource = null;

async function startJD() {
    const targetTime = getFormattedTime();

    if (!targetTime) {
        return;
    }

    try {
        const response = await fetch('/api/jd/start', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            },
            body: JSON.stringify({ target_time: targetTime })
        });

        const data = await response.json();

        if (response.ok) {
            currentTaskId = data.task_id;
            document.getElementById('startBtn').disabled = true;
            document.getElementById('stopBtn').disabled = false;
            disableTimeInputs(true);
            updateStatus('running');
            startLogStream();
        } else {
            alert(data.error || '启动失败');
        }
    } catch (error) {
        alert('请求失败：' + error.message);
    }
}

function disableTimeInputs(disabled) {
    document.getElementById('targetDate').disabled = disabled;
    document.getElementById('targetHour').disabled = disabled;
    document.getElementById('targetMinute').disabled = disabled;
    document.getElementById('targetSecond').disabled = disabled;
    document.getElementById('targetMicrosecond').disabled = disabled;
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

function startLogStream() {
    if (eventSource) {
        eventSource.close();
    }

    eventSource = new EventSource(`/api/tasks/${currentTaskId}/logs`);

    eventSource.onmessage = function(event) {
        try {
            const log = JSON.parse(event.data);
            addLog(log.message, log.time);

            if (log.error) {
                addLog('错误：' + log.error);
            }
        } catch (e) {
            addLog(event.data);
        }
    };

    eventSource.onerror = function() {
        eventSource.close();
        eventSource = null;
    };
}

function addLog(message, time = null) {
    const logContainer = document.getElementById('logContainer');
    const timestamp = time || getCurrentTime();

    const logEntry = document.createElement('div');
    logEntry.className = 'log-entry';
    logEntry.innerHTML = `<span class="log-time">${timestamp}</span>${message}`;

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

    if (status === 'success' || status === 'failed' || status === 'error' || status === 'stopped') {
        setTimeout(() => {
            resetUI();
        }, 5000);
    }
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

function resetUI() {
    document.getElementById('startBtn').disabled = false;
    document.getElementById('stopBtn').disabled = true;
    disableTimeInputs(false);

    if (eventSource) {
        eventSource.close();
        eventSource = null;
    }

    currentTaskId = null;
}

// 定期检查任务状态
setInterval(async () => {
    if (currentTaskId) {
        try {
            const response = await fetch(`/api/tasks/${currentTaskId}/status`);
            const data = await response.json();

            if (data.status && data.status !== 'running') {
                updateStatus(data.status);
            }
        } catch (error) {
            console.error('检查状态失败：', error);
        }
    }
}, 2000);
