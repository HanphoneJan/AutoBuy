"use strict";
/**
 * 定位与点击：四种模式，按页面可用性选择。全部与分辨率无关。
 *   text  —— 无障碍节点文字（首页/我的淘宝等原生页可用）
 *   desc  —— 无障碍 contentDescription（淘宝确认订单页的「立即支付」等）
 *   image —— 模板图匹配（购物车/详情等 Weex/H5 页）；模板按设备宽度缩放，区域用比例
 *   point —— 固定坐标（仅在极端兜底时使用，非通用）
 */

var log = require("./log.js");
var screen = require("./screen.js");

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

/** 判断文字是否存在 */
function hasText(value, timeout) {
    try { return text(value).findOne(timeout || 500) !== null; } catch (e) { return false; }
}

/** 固定坐标点击（非通用，仅兜底） */
function clickPoint(x, y) {
    click(x, y);
    log.log("点击坐标：(" + x + ", " + y + ")");
    return true;
}

/** 相对路径按脚本目录解析 */
function resolvePath(p) {
    if (!p || p.charAt(0) === "/") return p;
    try {
        var dir = files.dirname(engines.myEngine().source);
        return files.join(dir, p);
    } catch (e) {
        return p;
    }
}

/** 读取模板图并按设备宽度缩放 */
function loadScaledTemplate(path) {
    var tmpl = images.read(resolvePath(path));
    if (!tmpl) return null;
    var s = screen.scale();
    if (Math.abs(s - 1) > 0.02) {
        try {
            var scaled = images.scale(tmpl, s, s);
            tmpl.recycle();
            return scaled;
        } catch (e) {
            return tmpl;
        }
    }
    return tmpl;
}

/** 收集所有匹配点（相对整屏） */
function collectPoints(screenImg, tmpl, threshold) {
    var opts = { threshold: threshold || 0.8, max: 50 };
    // Auto.js 4.x/6.x 推荐用 matchTemplate，可返回多个结果
    if (images.matchTemplate) {
        try {
            var res = images.matchTemplate(screenImg, tmpl, opts);
            var out = [];
            if (res) {
                var ms = res.matches || [];
                for (var i = 0; i < ms.length; i++) {
                    var pt = ms[i].point || ms[i];
                    if (pt) out.push({ x: pt.x, y: pt.y });
                }
                if (!out.length && res.points) {
                    for (var j = 0; j < res.points.length; j++) out.push(res.points[j]);
                }
            }
            if (out.length) return out;
        } catch (e) {
            log.log("matchTemplate 失败，回退 findImage：" + e);
        }
    }
    if (images.findAllImages) {
        try { return images.findAllImages(screenImg, tmpl, opts) || []; } catch (e) {}
    }
    var p = images.findImage(screenImg, tmpl, opts);
    return p ? [p] : [];
}

function centerInRegion(p, tmpl, regionPx) {
    if (!regionPx) return true;
    var cx = p.x + tmpl.width / 2, cy = p.y + tmpl.height / 2;
    return cx >= regionPx[0] && cx <= regionPx[0] + regionPx[2] &&
           cy >= regionPx[1] && cy <= regionPx[1] + regionPx[3];
}

/**
 * 模板图匹配，返回命中中心点 {x, y}，不点击。
 * @param {string}  path        模板图路径（相对脚本目录）
 * @param {array}   regionFrac  可选，比例区域 [left, top, right, bottom]（0..1）
 * @param {number}  threshold   相似度阈值，默认 0.8
 * @param {boolean} topmost     命中多个时取最上方（用于“第一个商品”）
 */
function findImageCenter(path, regionFrac, threshold, topmost) {
    var screenImg = null, tmpl = null;
    try {
        screenImg = captureScreen();
        tmpl = loadScaledTemplate(path);
        if (!tmpl) {
            log.log("模板图不存在：" + path);
            return null;
        }
        var regionPx = regionFrac ? screen.region(regionFrac) : null;
        var pts = collectPoints(screenImg, tmpl, threshold).filter(function (p) {
            return centerInRegion(p, tmpl, regionPx);
        });
        if (!pts.length) return null;
        if (topmost) pts.sort(function (a, b) { return a.y - b.y; });
        var p = pts[0];
        return { x: p.x + Math.floor(tmpl.width / 2), y: p.y + Math.floor(tmpl.height / 2) };
    } catch (e) {
        log.log("模板图匹配失败：" + e);
        return null;
    } finally {
        if (screenImg) screenImg.recycle();
        if (tmpl) tmpl.recycle();
    }
}

/** 模板图匹配后点击 */
function clickImage(path, regionFrac, threshold, topmost) {
    var c = findImageCenter(path, regionFrac, threshold, topmost);
    if (!c) return false;
    click(c.x, c.y);
    log.log("点击模板图：" + path + (topmost ? "（最上方）" : "") + " @ " + c.x + "," + c.y);
    return true;
}

/** 执行一个步骤对象，返回是否成功 */
function runAction(step) {
    if (step.action === "text") return clickText(step.value, step.timeout || 300);
    if (step.action === "desc") return clickDesc(step.value, step.contains, step.timeout || 300);
    if (step.action === "image") return clickImage(step.value, step.region, step.threshold, step.pick === "topmost");
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
    findImageCenter: findImageCenter,
    runAction: runAction
};
