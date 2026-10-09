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
var MAX_LINES = 6;

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
                <text id="log" text="" textColor="#ffffff" textSize="12sp" maxLines="6" />
            </frame>
        );
        win.setPosition(0, 0);
        // 不拦截触摸：悬浮窗若可触摸会挡住 App 上的点击坐标
        try { win.setTouchable(false); } catch (e) {}
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
        try {
            if (typeof ui !== "undefined" && ui && typeof ui.run === "function") {
                ui.run(function () { label.setText(text); });
            } else {
                label.setText(text);
            }
        } catch (e) {
            try { label.setText(text); } catch (e2) {}
        }
    }
}

function close() {
    try { if (win) win.close(); } catch (e) {}
    win = null;
    label = null;
}

module.exports = { log: log, close: close };
