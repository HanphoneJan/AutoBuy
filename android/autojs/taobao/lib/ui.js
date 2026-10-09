"use strict";
/**
 * 定位与点击：四种模式，按页面可用性选择。
 *   text  —— 无障碍节点文字（首页/我的淘宝等原生页可用）
 *   desc  —— 无障碍 contentDescription（淘宝确认订单页的「立即支付」等）
 *   image —— 模板图匹配（购物车/详情等 Weex/H5 页推荐）
 *   point —— 固定坐标（购物车 Weex 页兜底，如「全选/结算」）
 */

var log = require("./log.js");

/** 按 contentDescription 点击，contains=true 时模糊匹配（默认） */
function clickDesc(values, contains, timeout) {
    var list = Array.isArray(values) ? values : [values];
    var fuzzy = (contains === undefined) ? true : contains;
    for (var i = 0; i < list.length; i++) {
        try {
            var node = fuzzy
                ? descContains(list[i]).findOne(timeout || 800)
                : desc(list[i]).findOne(timeout || 800);
            if (node) {
                var b = node.bounds();
                click(b.centerX(), b.centerY());
                log.log("点击描述：" + list[i]);
                return true;
            }
        } catch (e) {}
    }
    return false;
}

/** 按文字点击，支持多个候选文字，返回是否点到 */
function clickText(texts, timeout) {
    var list = Array.isArray(texts) ? texts : [texts];
    for (var i = 0; i < list.length; i++) {
        try {
            var node = text(list[i]).findOne(timeout || 800);
            if (node) {
                var b = node.bounds();
                click(b.centerX(), b.centerY());
                log.log("点击文字：" + list[i]);
                return true;
            }
        } catch (e) {}
    }
    return false;
}

/** 判断文字是否存在 */
function hasText(value, timeout) {
    try { return text(value).findOne(timeout || 500) !== null; } catch (e) { return false; }
}

/** 固定坐标点击 */
function clickPoint(x, y) {
    click(x, y);
    log.log("点击坐标：(" + x + ", " + y + ")");
    return true;
}

/**
 * 模板图匹配后点击。
 * @param {string} path 模板图路径（相对脚本目录）
 * @param {array}  region 可选，限定搜索区域 [x, y, w, h]
 * @param {number} threshold 相似度阈值，默认 0.8
 */
function clickImage(path, region, threshold) {
    var screen = null, tmpl = null;
    try {
        screen = captureScreen();
        tmpl = images.read(path);
        if (!tmpl) {
            log.log("模板图不存在：" + path);
            return false;
        }
        var opts = { threshold: threshold || 0.8 };
        if (region) opts.region = region;
        var p = images.findImage(screen, tmpl, opts);
        if (p) {
            click(p.x + Math.floor(tmpl.width / 2), p.y + Math.floor(tmpl.height / 2));
            log.log("点击模板图：" + path);
            return true;
        }
        return false;
    } catch (e) {
        log.log("模板图匹配失败：" + e);
        return false;
    } finally {
        if (screen) screen.recycle();
        if (tmpl) tmpl.recycle();
    }
}

/** 执行一个步骤对象，返回是否成功 */
function runAction(step) {
    if (step.action === "text") return clickText(step.value, step.timeout || 300);
    if (step.action === "desc") return clickDesc(step.value, step.contains, step.timeout || 300);
    if (step.action === "image") return clickImage(step.value, step.region, step.threshold);
    if (step.action === "point") return clickPoint(step.x, step.y);
    log.log("未知动作：" + step.action);
    return false;
}

module.exports = {
    clickText: clickText,
    hasText: hasText,
    clickDesc: clickDesc,
    clickPoint: clickPoint,
    clickImage: clickImage,
    runAction: runAction
};
