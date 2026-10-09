"use strict";
/**
 * AutoBuy 安卓原型（淘宝）
 * 入口：读配置 → 校准网络时间 → 等到目标时间 → 执行步骤 → 提示结果。
 *
 * 运行前请确认：已开启 AutoX.js 无障碍服务；淘宝 App 已登录并停在购物车/结算页。
 */

auto.waitFor();

var log = require("./lib/log.js");
var time = require("./lib/time.js");
var flow = require("./lib/flow.js");

/** 读取脚本同目录下的 config.json */
function loadConfig() {
    var candidates = ["./config.json"];
    try {
        var dir = files.dirname(engines.myEngine().source);
        candidates.unshift(files.join(dir, "config.json"));
    } catch (e) {}
    for (var i = 0; i < candidates.length; i++) {
        try {
            if (files.exists(candidates[i])) {
                return JSON.parse(files.read(candidates[i]));
            }
        } catch (e) {}
    }
    return null;
}

var cfg = loadConfig();
if (!cfg) {
    log.log("读取 config.json 失败，请确认脚本目录");
    exit();
}

log.log("AutoBuy 安卓原型启动");

if (cfg.wake) {
    try { device.wakeUp(); } catch (e) {}
}

if (time.calibrate()) {
    log.log("网络时间校准成功，偏移 " + time.offset() + "ms");
} else {
    log.log("网络时间校准失败，改用手机本地时间");
}

var targetMs = time.parseTarget(cfg.target_time);
if (!targetMs) {
    log.log("目标时间格式错误：" + cfg.target_time);
    exit();
}

if (cfg.keyword) log.log("目标商品关键词：" + cfg.keyword);

flow.waitUntil(targetMs, cfg.lead_seconds || 3);

var deadline = targetMs + (cfg.window_seconds || 20) * 1000;
var ok = flow.runSteps(cfg.steps || [], deadline);

log.log(ok ? "✓ 已执行完抢购步骤，请尽快确认订单并付款" : "✗ 步骤未全部完成，请手动检查");

// 保留悬浮窗一段时间便于查看结果
sleep(60000);
log.close();
