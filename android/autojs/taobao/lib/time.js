"use strict";
/**
 * 网络时间校准：对齐淘宝服务器时间，避免依赖手机本地时钟。
 * 与 PC 版 seckill.py 的 TimeManager 思路一致。
 */

var TB_TIME_URLS = [
    "https://acs.m.taobao.com/gw/mtop.common.getTimestamp/",
    "http://api.m.taobao.com/rest/api3.do?api=mtop.common.getTimestamp"
];

var offsetMs = 0;
var calibrated = false;

function fetchServerMs() {
    for (var i = 0; i < TB_TIME_URLS.length; i++) {
        try {
            var resp = http.get(TB_TIME_URLS[i], { timeout: 5000 });
            if (resp && resp.statusCode === 200) {
                var data = resp.body.json();
                if (data && data.data && data.data.t) {
                    return parseInt(data.data.t, 10);
                }
            }
        } catch (e) {
            // 尝试下一个时间源
        }
    }
    return null;
}

/** 校准一次，返回是否成功 */
function calibrate() {
    var serverMs = fetchServerMs();
    if (serverMs === null) {
        calibrated = false;
        return false;
    }
    offsetMs = serverMs - Date.now();
    calibrated = true;
    return true;
}

/** 当前校准后的时间戳（毫秒） */
function now() {
    return Date.now() + offsetMs;
}

/** 解析目标时间字符串，返回毫秒时间戳；失败返回 null */
function parseTarget(str) {
    var m = String(str).match(
        /^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,3}))?$/
    );
    if (!m) return null;
    var ms = m[7] ? parseInt((m[7] + "000").slice(0, 3), 10) : 0;
    return new Date(
        parseInt(m[1], 10), parseInt(m[2], 10) - 1, parseInt(m[3], 10),
        parseInt(m[4], 10), parseInt(m[5], 10), parseInt(m[6], 10), ms
    ).getTime();
}

module.exports = {
    calibrate: calibrate,
    now: now,
    parseTarget: parseTarget,
    offset: function () { return offsetMs; },
    isCalibrated: function () { return calibrated; }
};
