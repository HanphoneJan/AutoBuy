"use strict";
/**
 * 流程控制：等待目标时间 + 执行步骤状态机。
 */

var time = require("./time.js");
var ui = require("./ui.js");
var log = require("./log.js");

/**
 * 等到目标时间（提前 leadSeconds 秒进入待命）。
 */
function waitUntil(targetMs, leadSeconds) {
    var start = targetMs - (leadSeconds || 0) * 1000;
    log.log("目标时间：" + new Date(targetMs).toLocaleString());
    while (true) {
        var remaining = start - time.now();
        if (remaining <= 0) break;
        if (remaining > 10000) {
            log.log("距抢购还有 " + Math.ceil(remaining / 1000) + "s");
            sleep(5000);
        } else {
            sleep(50);
        }
    }
    log.log("进入抢购窗口");
}

/**
 * 依次执行步骤；每一步在 deadline 之前循环重试。
 * 全部完成返回 true，任一步超时返回 false。
 */
function runSteps(steps, deadlineMs) {
    for (var i = 0; i < steps.length; i++) {
        var step = steps[i];
        var ok = false;
        while (!ok && time.now() < deadlineMs) {
            ok = ui.runAction(step);
            if (!ok) sleep(60);
        }
        log.log("步骤 " + (i + 1) + "/" + steps.length + " [" + step.action + "] " + (ok ? "完成" : "超时"));
        if (!ok) return false;
        sleep(step.after || 120);
    }
    return true;
}

module.exports = { waitUntil: waitUntil, runSteps: runSteps };
