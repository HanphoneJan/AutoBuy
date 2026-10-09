"use strict";
/**
 * 屏幕/分辨率适配：模板图按参考分辨率采集，运行时按设备宽度缩放，
 * 区域用比例表示，避免写死像素坐标。
 */

var REF_WIDTH = 1080;   // 模板图采集时的参考宽度

function w() { return device.width; }
function h() { return device.height; }

/** 当前设备相对参考分辨率的缩放比 */
function scale() { return device.width / REF_WIDTH; }

/**
 * 比例区域 -> 像素区域 [x, y, width, height]
 * @param {array} frac [left, top, right, bottom]，取值 0..1
 */
function region(frac) {
    var x0 = Math.round(frac[0] * device.width);
    var y0 = Math.round(frac[1] * device.height);
    var x1 = Math.round(frac[2] * device.width);
    var y1 = Math.round(frac[3] * device.height);
    return [x0, y0, x1 - x0, y1 - y0];
}

module.exports = { w: w, h: h, scale: scale, region: region, REF_WIDTH: REF_WIDTH };
