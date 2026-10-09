package com.autobuy.app.core

import com.autobuy.app.seckill.Stage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 全局日志与运行状态，供前台服务写、Compose UI 读。 */
object LogBus {
    private const val MAX = 500
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs = _logs.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running = _running.asStateFlow()

    private val _stage = MutableStateFlow(Stage.IDLE)
    val stage = _stage.asStateFlow()

    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun add(message: String) {
        val line = "[${fmt.format(Date())}] $message"
        _logs.value = (_logs.value + line).takeLast(MAX)
        android.util.Log.d("AutoBuy", line)
    }

    fun clear() {
        _logs.value = emptyList()
    }

    fun setRunning(value: Boolean) {
        _running.value = value
    }

    fun setStage(value: Stage) {
        _stage.value = value
    }
}
