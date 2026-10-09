"use strict";
/**
 * 悬浮窗日志：抢购时实时观察进度。
 * 悬浮窗创建失败时自动退化为 console.log，不影响主流程。
 *
 * 注意：下面使用了 Auto.js 专有的 XML 布局字面量语法（<frame>/<text>），
 * 普通 JS 解析器（node --check / eslint）无法解析，属正常现象，仅在 Auto.js 运行时有效。
 */

var win = null;
var label = null;
var lines = [];
var MAX_LINES = 12;

function pad(n) { return n < 10 ? "0" + n : "" + n; }

function formatTime(ms) {
    var d = new Date(ms);
    return pad(d.getHours()) + ":" + pad(d.getMinutes()) + ":" + pad(d.getSeconds());
}

function ensureWindow() {
    if (win) return true;
    try {
        win = floaty.window(
            <frame gravity="start" bg="#cc000000" padding="8">
                <text id="log" text="" textColor="#ffffff" textSize="12sp" maxLines="12" />
            </frame>
        );
        win.setPosition(20, 320);
        label = win.log;
        return true;
    } catch (e) {
        win = null;
        return false;
    }
}

function log(msg) {
    var line = "[" + formatTime(Date.now()) + "] " + msg;
    lines.push(line);
    if (lines.length > MAX_LINES) lines.shift();
    console.log(line);
    if (ensureWindow() && label) {
        var text = lines.join("\n");
        ui.run(function () { label.setText(text); });
    }
}

function close() {
    try { if (win) win.close(); } catch (e) {}
    win = null;
    label = null;
}

module.exports = { log: log, close: close };
