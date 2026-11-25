#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""AutoBuy 定时抢购夹具工具（淘宝）。

为什么需要它：
  平台的「能加购 + 开售前不能买 + 有精确开售时间」三者不会长期同时出现在一个
  固定商品上，所以每次测试都得重新找。这个工具把「找」固化成流水线：

    resolve   短链/深链 → itemId（纯 curl，不需要登录）
    probe     打开某个商品，抽取 开售时间/价格/出售状态 → 写 fixtures/*.json
    harvest   在首页推荐流 / 搜索结果里枚举候选，逐个 probe（带频控与退避）
    audit     逐条重开夹具，标记 live / on_sale / expired / changed
    capture   把当前屏幕 dump+截图存进 testdata/dumps/（离线回归用）
    list      看目录里有哪些夹具、什么时候开售

判定锚点全部来自真机 dump 文本，不猜图：
    「即将开始，11.25 16:00开售」 → 定时全款预售（0 定金，最理想的夹具）
    「预售，200天内发货」         → 全款预售，现在就能买（没有定时，不能当定时夹具）
    「定金」「尾款」              → 定金预售
    「访问太频繁」「验证」        → 频控/风控，退避或停手

用法：
    scripts/fixtures.py resolve <短链或带 id 的链接>
    scripts/fixtures.py probe 1103296796879
    scripts/fixtures.py harvest --source home --max 6 --scrolls 4
    scripts/fixtures.py harvest --source search --query 预售 --sort 上新 --blind --max 3
    scripts/fixtures.py audit
    scripts/fixtures.py capture tb-cart
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import time
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FIXTURE_DIR = ROOT / "fixtures"
DUMP_DIR = ROOT / "testdata" / "dumps"
TZ = timezone(timedelta(hours=8))  # 平台时间一律按北京时间

TAOBAO_PKG = "com.taobao.taobao"
JD_PKG = "com.jingdong.app.mall"
ADB = os.environ.get("ADB") or str(
    Path(os.environ.get("ANDROID_HOME", os.path.expanduser("~/Android/Sdk")))
    / "platform-tools"
    / "adb"
)

# 单件之间的停顿：实测一次深链就可能触发「访问太频繁」，慢比快重要
VISIT_GAP = float(os.environ.get("FIXTURE_VISIT_GAP", "8"))
# 命中频控后退避多久（实测冷却约 2 分钟）
THROTTLE_SLEEP = float(os.environ.get("FIXTURE_THROTTLE_SLEEP", "150"))

THROTTLE_WORDS = ("访问太频繁", "请稍后重试", "安全风险", "快速验证", "拖动滑块", "亲，")
NOISE_WORDS = (
    "天发货", "退货", "好评", "加购", "开售", "已售", "包邮", "运费", "运费险",
    "7天无理由", "极速退款", "假一赔", "店铺优惠", "优惠前", "已享受", "查看优惠",
    "查看物流", "宝贝评价", "查看全部", "共1款", "限时立减", "月销", "回头客",
)


# ---------------------------------------------------------------- 基础设施
def log(msg: str) -> None:
    print(msg, flush=True)


def warn(msg: str) -> None:
    print(f"!! {msg}", file=sys.stderr, flush=True)


def adb(*args: str, timeout: int = 40, binary: bool = False):
    cmd = [ADB, *args]
    try:
        out = subprocess.run(cmd, capture_output=True, timeout=timeout, check=False)
    except subprocess.TimeoutExpired:
        warn(f"adb 超时: {' '.join(args)}")
        return b"" if binary else ""
    if out.returncode != 0 and not binary:
        warn(f"adb 失败({out.returncode}): {' '.join(args)} {out.stderr.decode('utf-8', 'ignore')[:120]}")
    return out.stdout if binary else out.stdout.decode("utf-8", "ignore")


def dump_ui(retries: int = 3) -> str:
    """取当前界面 XML。uiautomator 偶发失败，重试几次。"""
    for _ in range(retries):
        adb("shell", "uiautomator", "dump", "/sdcard/_ab.xml", timeout=30)
        xml = adb("exec-out", "cat", "/sdcard/_ab.xml", timeout=30)
        if "<hierarchy" in xml:
            return xml
        time.sleep(1)
    return ""


NODE_RE = re.compile(r"<node\b[^>]*>")
BOUNDS_RE = re.compile(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')


def attr(tag: str, name: str) -> str:
    m = re.search(name + r'="([^"]*)"', tag)
    return (m.group(1) if m else "").strip()


def unescape(s: str) -> str:
    return (s.replace("&#10;", "\n").replace("&#9;", "\t")
             .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
             .replace("&quot;", '"').replace("&#39;", "'"))


def nodes(xml: str) -> list[dict]:
    out = []
    for tag in NODE_RE.findall(xml):
        m = BOUNDS_RE.search(tag)
        if not m:
            continue
        x1, y1, x2, y2 = map(int, m.groups())
        text = unescape(attr(tag, "text"))
        desc = unescape(attr(tag, "content-desc"))
        out.append({
            "bounds": (x1, y1, x2, y2),
            "cx": (x1 + x2) // 2, "cy": (y1 + y2) // 2,
            "text": text, "desc": desc, "label": text or desc,
            "clickable": attr(tag, "clickable") == "true",
        })
    return out


def texts(xml: str) -> list[str]:
    seen, out = set(), []
    for n in nodes(xml):
        for v in (n["text"], n["desc"]):
            if v and v not in seen:
                seen.add(v)
                out.append(v)
    return out


def find_node(xml: str, pattern: str, exact: bool = False) -> dict | None:
    rx = re.compile(pattern)
    for n in nodes(xml):
        if not n["label"]:
            continue
        hit = (n["label"] == pattern) if exact else bool(rx.search(n["label"]))
        if hit:
            return n
    return None


def flat(s: str) -> str:
    return re.sub(r"\s+", " ", s or "").strip()


def tap(x: int, y: int) -> None:
    adb("shell", "input", "tap", str(x), str(y))


def back() -> None:
    adb("shell", "input", "keyevent", "KEYCODE_BACK")


def swipe_up(times: int = 1, duration: int = 400) -> None:
    size = adb("shell", "wm", "size")
    m = re.search(r"(\d+)x(\d+)", size)
    w, h = (int(m.group(1)), int(m.group(2))) if m else (1080, 2400)
    for _ in range(times):
        adb("shell", "input", "swipe", str(w // 2), str(int(h * 0.78)),
            str(w // 2), str(int(h * 0.30)), str(duration))
        time.sleep(1.5)


def screen_size() -> tuple[int, int]:
    m = re.search(r"(\d+)x(\d+)", adb("shell", "wm", "size"))
    return (int(m.group(1)), int(m.group(2))) if m else (1080, 2400)


def is_throttled(xml: str) -> bool:
    return any(w in xml for w in THROTTLE_WORDS)


class Throttled(Exception):
    pass


def wait_page(keys: tuple[str, ...], timeout: float = 12.0) -> str:
    """等页面出现任意关键词，返回最后的 dump。"""
    end = time.time() + timeout
    xml = ""
    while time.time() < end:
        xml = dump_ui()
        if is_throttled(xml):
            raise Throttled()
        if any(k in xml for k in keys):
            return xml
        time.sleep(0.8)
    return xml


# ---------------------------------------------------------------- 链接解析
def resolve(target: str) -> str | None:
    """短链/深链/裸 id → itemId。

    c.tb.cn 是分享短链（bizCode=tbshare），落地页里直接写着
    `var url = 'https://item.taobao.com/item.htm?id=…'`，不需要登录、不需要 App。
    """
    target = target.strip()
    if re.fullmatch(r"\d{8,}", target):
        return target
    m = re.search(r"[?&]id=(\d{8,})", target)
    if m and "tb.cn" not in target:
        return m.group(1)
    if not target.startswith("http"):
        return None
    req = urllib.request.Request(target, headers={
        "User-Agent": "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 "
                      "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36",
    })
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            html = resp.read().decode("utf-8", "ignore")
            final = resp.geturl()
    except Exception as exc:  # noqa: BLE001
        warn(f"解析失败: {exc}")
        return None
    for pat in (r'item\.taobao\.com/item\.htm\?id=(\d{8,})',
                r'item\.tmall\.com/item\.htm\?id=(\d{8,})',
                r'[?&]id=(\d{8,})'):
        m = re.search(pat, html) or re.search(pat, final)
        if m:
            return m.group(1)
    return None


def deep_link(item_id: str) -> str:
    return f"taobao://item.taobao.com/item.htm?id={item_id}"


def search_link(keyword: str) -> str:
    return "taobao://s.taobao.com/search?q=" + urllib.parse.quote(keyword)


# ---------------------------------------------------------------- 详情页抽取
BANNER_RES = (
    # 即将开始，11.25 16:00开售 / 11月25日 16:00开售
    re.compile(r"(?:即将开始|即将开售|距开售)[，,：:\s]*"
               r"(?:(\d{1,2})\s*[.月]\s*(\d{1,2})\s*日?\s*)?"
               r"(\d{1,2}:\d{2})\s*开(?:售|抢)"),
    re.compile(r"(\d{1,2})\s*[.月]\s*(\d{1,2})\s*日?\s*(\d{1,2}:\d{2})\s*开(?:售|抢)"),
)
CART_COUNT_RE = re.compile(r"(?:超)?([\d.万+]+)人加购")
# 「本月平均3天内发货｜承诺120天内发货」两者都在页面上，承诺值才是真正的发货上限
SHIP_RE = re.compile(r"承诺\s*(\d+)\s*天内发货")
SHIP_FALLBACK_RE = re.compile(r"(\d+)\s*天内发货")
SOLD_RE = re.compile(r"已售\s*([\d.万+]+)")
PRICE_RE = re.compile(r"店铺优惠后\s*￥\s*([\d.]+)|￥\s*([\d.]+)")


def parse_sale_time(xml: str) -> tuple[str | None, str | None]:
    """返回 (ISO 时间, 原始文案)。年份缺失时按「跨年」推断。"""
    flat = xml.replace("&#10;", "\n")
    for rx in BANNER_RES:
        for m in rx.finditer(flat):
            raw = m.group(0)
            groups = [g for g in m.groups() if g]
            try:
                if len(groups) == 3:
                    mon, day, hhmm = int(groups[0]), int(groups[1]), groups[2]
                elif len(groups) == 1:
                    mon = day = None
                    hhmm = groups[0]
                else:
                    continue
                hh, mm = (int(x) for x in hhmm.split(":"))
                if mon is None:
                    # 只有时间、没有日期：当「今天/明天」
                    now = datetime.now(TZ)
                    cand = now.replace(hour=hh, minute=mm, second=0, microsecond=0)
                    if cand < now - timedelta(hours=1):
                        cand += timedelta(days=1)
                    return cand.isoformat(), raw.strip()
                now = datetime.now(TZ)
                cand = datetime(now.year, mon, day, hh, mm, tzinfo=TZ)
                if cand < now - timedelta(days=1):
                    cand = cand.replace(year=now.year + 1)
                return cand.isoformat(), raw.strip()
            except ValueError:
                continue
    return None, None


def classify(xml: str) -> dict:
    """从详情页 dump 判定商品类型和关键字段。"""
    sale_iso, banner = parse_sale_time(xml)
    raw = xml.replace("&#10;", "\n")
    has_deposit = ("定金" in raw) and ("尾款" in raw)
    is_presale_attr = bool(re.search(r"预售\s*[:：]\s*出售状态", raw))
    ship = SHIP_RE.search(raw) or SHIP_FALLBACK_RE.search(raw)
    cart = CART_COUNT_RE.search(raw)
    sold = SOLD_RE.search(raw)

    if sale_iso:
        kind = "timed_full"          # 定时开售 + 全款（最理想的夹具）
    elif has_deposit:
        kind = "deposit"             # 定金预售
    elif is_presale_attr:
        kind = "presale_now"         # 全款预售，现在就能买
    elif "已下架" in raw or "宝贝已下架" in raw:
        kind = "gone"
    else:
        kind = "on_sale"

    prices = []
    for m in PRICE_RE.finditer(raw):
        v = m.group(1) or m.group(2)
        try:
            prices.append(float(v))
        except (TypeError, ValueError):
            pass

    return {
        "kind": kind,
        "saleTime": sale_iso,
        "bannerText": banner,
        "hasDeposit": has_deposit,
        "presaleAttr": is_presale_attr,
        "shipPromise": (f"承诺{ship.group(1)}天内发货" if ship else None),
        "cartCount": (f"{cart.group(1)}人加购" if cart else None),
        "sold": (sold.group(1) if sold else None),
        "prices": sorted(set(prices)),
        "title": guess_title(xml),
    }


def guess_title(xml: str) -> str | None:
    """标题启发式：长文本里排除价格/属性/服务承诺等噪音，取最长的。"""
    best = None
    for t in texts(xml):
        t = t.strip()
        if len(t) < 8 or len(t) > 90:
            continue
        if t.startswith(("￥", "¥")) or "￥" in t:
            continue
        if any(w in t for w in NOISE_WORDS):
            continue
        if re.search(r"[:：]\S+$", t) and len(t) < 16:  # 形如「预售:出售状态」
            continue
        if not best or len(t) > len(best):
            best = t
    return best


# ---------------------------------------------------------------- itemId 抓取
def item_id_from_logcat(window: int = 400) -> str | None:
    """详情页一打开，淘宝会往 logcat 写带 itemId 的 URL（如 subItemId=…）。"""
    out = adb("logcat", "-d", "-t", str(window), timeout=30)
    cands = re.findall(r"(?:subItemId|itemId|item_id)=(\d{9,})", out)
    cands += re.findall(r"[?&]id=(\d{12,13})", out)
    if not cands:
        return None
    # 出现次数最多的那个最可能是当前商品
    return max(set(cands), key=cands.count)


# ---------------------------------------------------------------- 夹具读写
def fixture_path(item_id: str) -> Path:
    return FIXTURE_DIR / f"tb-{item_id}.json"


def load_fixtures() -> list[dict]:
    FIXTURE_DIR.mkdir(parents=True, exist_ok=True)
    out = []
    for p in sorted(FIXTURE_DIR.glob("tb-*.json")):
        try:
            out.append(json.loads(p.read_text(encoding="utf-8")))
        except json.JSONDecodeError:
            warn(f"跳过损坏的夹具: {p.name}")
    return out


def save_fixture(data: dict) -> Path:
    FIXTURE_DIR.mkdir(parents=True, exist_ok=True)
    path = fixture_path(data["itemId"])
    old = {}
    if path.exists():
        try:
            old = json.loads(path.read_text(encoding="utf-8"))
        except json.JSONDecodeError:
            old = {}
    merged = {**old, **{k: v for k, v in data.items() if v is not None}}
    merged.setdefault("collectedAt", datetime.now(TZ).isoformat(timespec="seconds"))
    merged["updatedAt"] = datetime.now(TZ).isoformat(timespec="seconds")
    path.write_text(json.dumps(merged, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return path


# ---------------------------------------------------------------- probe
def probe(item_id: str, evidence: bool = True, preloaded_xml: str | None = None) -> dict | None:
    """打开一个商品并抽取字段。返回夹具数据（不落盘）。"""
    adb("logcat", "-c")
    if preloaded_xml is None:
        adb("shell", "am", "start", "-a", "android.intent.action.VIEW",
            "-d", deep_link(item_id))
        try:
            xml = wait_page(("加入购物车", "领券购买", "立即购买", "开售", "已下架", "预售"))
        except Throttled:
            warn(f"{item_id}: 命中频控")
            return None
    else:
        xml = preloaded_xml

    if not xml:
        warn(f"{item_id}: 页面没打开")
        return None
    if is_throttled(xml):
        warn(f"{item_id}: 命中频控")
        return None

    info = classify(xml)
    captured = item_id_from_logcat() or item_id
    data = {
        "id": f"tb-{item_id}",
        "platform": "taobao",
        "itemId": item_id,
        "deepLink": deep_link(item_id),
        **{k: info[k] for k in ("kind", "saleTime", "bannerText", "title",
                                "shipPromise", "cartCount", "sold")},
        "presaleAttr": info["presaleAttr"],
        "prices": info["prices"] or None,
        "evidence": None,
        "state": "live" if info["kind"] in ("timed_full", "deposit", "presale_now") else info["kind"],
    }
    if evidence:
        shot = capture(f"tb-{item_id}-detail", raw=xml)
        try:
            data["evidence"] = str(shot.relative_to(ROOT))
        except ValueError:
            data["evidence"] = str(shot)
    if captured != item_id:
        warn(f"{item_id}: logcat 抓到的 id 是 {captured}（以入参为准）")
    return data


# ---------------------------------------------------------------- capture
def capture(name: str, raw: str | None = None) -> Path:
    DUMP_DIR.mkdir(parents=True, exist_ok=True)
    if raw is None:
        raw = dump_ui()
    xml_path = DUMP_DIR / f"{name}.xml"
    xml_path.write_text(raw, encoding="utf-8")
    png = adb("exec-out", "screencap", "-p", binary=True, timeout=60)
    if png:
        (DUMP_DIR / f"{name}.png").write_bytes(png)
    return xml_path


# ---------------------------------------------------------------- harvest
def collect_cards(xml: str) -> list[dict]:
    """在列表/信息流里找卡片：`商品图片` 的 bounds + 同列下方最近的标题文本。"""
    ns = nodes(xml)
    images = [n for n in ns if "商品图片" in n["desc"]]
    titles = [n for n in ns
              if len(n["text"]) >= 8 and not n["text"].startswith(("￥", "¥"))
              and "￥" not in n["text"]]
    cards = []
    for img in images:
        x1, y1, x2, y2 = img["bounds"]
        best = None
        for t in titles:
            tx1, ty1, tx2, ty2 = t["bounds"]
            # 同一列、在图片下方 240px 内、有横向重叠
            if tx1 >= x1 - 60 and tx2 <= x2 + 60 and 0 <= ty1 - y2 <= 240:
                if not best or ty1 < best["bounds"][1]:
                    best = t
        if not best:
            continue
        # 卡片上方的标签（预售/即将开始/定金）一般贴在图片上方或标题上方
        label = ""
        for n in ns:
            lx1, ly1, lx2, ly2 = n["bounds"]
            if not (x1 - 60 <= lx1 and lx2 <= x2 + 60):
                continue
            if not (y1 - 260 <= ly1 and ly2 <= best["bounds"][3]):
                continue
            if re.search(r"预售|定金|开售|即将|限量", n["label"]):
                label = n["label"]
                break
        cards.append({
            "title": best["text"],
            "label": label,
            "tap": (img["cx"], img["cy"]),
            "y": y1,
        })
    return cards


DETAIL_KEYS = ("加入购物车", "领券购买", "立即购买", "开售", "已下架", "预售")


def grid_points(rows: int = 3) -> list[tuple[int, int]]:
    """搜索结果/信息流的卡片网格：两列。商品区无文字节点时靠它盲点。"""
    w, h = screen_size()
    ys = [int(h * f) for f in (0.40, 0.58, 0.74, 0.90)][:rows]
    return [(int(w * 0.26), y) for y in ys] + [(int(w * 0.74), y) for y in ys]


def harvest(args) -> int:
    found, visited = 0, 0
    seen: set[str] = set()
    seen_ids: set[str] = set()
    source = args.source

    adb("shell", "am", "force-stop", TAOBAO_PKG)
    time.sleep(1)
    if source == "home":
        adb("shell", "monkey", "-p", TAOBAO_PKG, "-c", "android.intent.category.LAUNCHER", "1")
        wait_page(("搜索栏", "购物车", "推荐"), timeout=25)
    else:
        adb("shell", "am", "start", "-a", "android.intent.action.VIEW",
            "-d", search_link(args.query))
        wait_page(("综合", "销量"), timeout=25)
        if args.sort:
            xml = dump_ui()
            tab = None
            for n in nodes(xml):
                if n["label"].startswith(args.sort):
                    tab = n
                    break
            if tab:
                tap(tab["cx"], tab["cy"])
                log(f"已切到「{args.sort}」排序")
                time.sleep(4)
            else:
                warn(f"没找到「{args.sort}」排序 tab，按默认排序继续")

    for scroll in range(args.scrolls + 1):
        xml = dump_ui()
        if is_throttled(xml):
            warn(f"频控：退避 {THROTTLE_SLEEP:.0f}s")
            time.sleep(THROTTLE_SLEEP)
            continue

        targets: list[dict] = []
        if args.blind:
            for (x, y) in grid_points():
                targets.append({"title": f"#{x},{y}", "label": "", "tap": (x, y)})
        else:
            for c in collect_cards(xml):
                if flat(c["title"]) in seen:
                    continue
                targets.append(c)

        if not targets:
            log(f"  第 {scroll + 1} 屏：没有可点的卡片")

        for card in targets:
            if found >= args.max:
                break
            title = flat(card["title"])
            seen.add(title)
            if not args.blind and not args.all and not re.search(
                    r"预售|定金|开售|即将|限量", title + card["label"]):
                continue
            if not args.blind:
                log(f"[候选] {title[:40]}{('  ⟨' + flat(card['label'])[:16] + '⟩') if card['label'] else ''}")

            tap(*card["tap"])
            try:
                detail = wait_page(DETAIL_KEYS, timeout=args.wait)
            except Throttled:
                warn(f"频控，退避 {THROTTLE_SLEEP:.0f}s")
                time.sleep(THROTTLE_SLEEP)
                back()
                time.sleep(2)
                continue

            if not any(k in detail for k in ("加入购物车", "领券购买", "立即购买", "已下架")):
                # 点到的不是商品（会场/店铺/广告），回退
                if not args.blind:
                    log("         → 不是商品页，跳过")
                back()
                time.sleep(2)
                continue

            visited += 1
            info = classify(detail)
            tag = info["kind"] + (f" @ {info['saleTime']}" if info["saleTime"] else "")
            item_id = item_id_from_logcat() or item_id_from_logcat(3000)
            if item_id and item_id in seen_ids:
                log(f"{'[盲扫]' if args.blind else '       '} (已访问过 {item_id}，跳过)")
                back()
                time.sleep(2)
                continue
            if item_id:
                seen_ids.add(item_id)
            log(f"{'[盲扫]' if args.blind else '       '} {flat(info['title'] or '')[:34]}  → {tag}")
            back()
            time.sleep(2)

            keep = info["kind"] == "timed_full" or (args.keep_all and info["kind"] not in ("on_sale", "gone"))
            if not keep:
                time.sleep(args.gap)
                continue
            if not item_id:
                warn("         → 没抓到 itemId（logcat 无 subItemId），跳过")
                time.sleep(args.gap)
                continue
            data = probe(item_id, evidence=True, preloaded_xml=detail)
            if not data:
                time.sleep(args.gap)
                continue
            path = save_fixture(data)
            found += 1
            log(f"         ✓ 写入 {path.relative_to(ROOT)}  ({data['kind']} {data['saleTime'] or ''})")
            time.sleep(args.gap)

        if found >= args.max:
            break
        swipe_up(1)

    log(f"\n完成：访问 {visited} 个商品，产出/更新 {found} 条夹具")
    return 0 if found else 1


SHARE_URL_RE = re.compile(r"https?://[^\s\u4e00-\u9fff（）()【】]+")


def extract_targets(text: str) -> list[str]:
    """从一段文本（群里复制的淘口令、分享文案）里挑出链接或裸 itemId。"""
    found = SHARE_URL_RE.findall(text)
    if not found:
        found = re.findall(r"\b(\d{10,})\b", text)
    seen, uniq = set(), []
    for u in found:
        if u not in seen:
            seen.add(u)
            uniq.append(u)
    return uniq


def cmd_ingest(args) -> int:
    """吃进一批人给的链接：群里/微博/小红书贴过来的「【淘宝】https://c.tb.cn/…」都能直接喂。"""
    raw = Path(args.file).read_text(encoding="utf-8") if args.file else sys.stdin.read()
    targets = extract_targets(raw)
    if not targets:
        warn("没找到链接或 itemId")
        return 1
    log(f"共 {len(targets)} 条候选")
    added = 0
    for i, target in enumerate(targets, 1):
        item_id = resolve(target)
        if not item_id:
            warn(f"[{i}/{len(targets)}] 无法解析：{target[:60]}")
            continue
        data = probe(item_id, evidence=True)
        if not data:
            continue
        path = save_fixture(data)
        added += 1
        log(f"[{i}/{len(targets)}] {data['kind']:<12} {data['saleTime'] or '-':<26} {flat(data['title'] or '')[:40]}")
        log(f"        → {path.relative_to(ROOT)}")
        if i < len(targets):
            time.sleep(VISIT_GAP)
    log(f"\n完成：{added}/{len(targets)} 条写入 fixtures/")
    return 0 if added else 1


# ---------------------------------------------------------------- 发现入口探测
def launch(app: str) -> None:
    pkg = TAOBAO_PKG if app == "taobao" else JD_PKG
    adb("shell", "am", "force-stop", pkg)
    time.sleep(1)
    adb("shell", "monkey", "-p", pkg, "-c", "android.intent.category.LAUNCHER", "1")
    wait_page(("搜索栏", "购物车", "我的", "推荐"), timeout=25)


def tap_label(label: str, contains: bool = True, tries: int = 4, scroll: bool = True) -> bool:
    """找节点→点中心。优先精确匹配，其次前缀匹配，最后包含匹配。

    列表/标签页的文案常带后缀（「我的收藏,未选中」），所以不能只用 == ；
    但纯 contains 会误命中卡片里的长标题，所以按「匹配程度 + 文案长度」排序。
    """
    for _ in range(tries):
        xml = dump_ui()
        best, best_score = None, 99
        for n in nodes(xml):
            lb = n["label"]
            if not lb:
                continue
            if lb == label:
                score = 0
            elif lb.startswith(label):
                score = 1
            elif contains and label in lb:
                score = 2
            else:
                continue
            score = score * 100 + min(len(lb), 99)   # 同分时取更短的文案
            if score < best_score:
                best, best_score = n, score
        if best:
            tap(best["cx"], best["cy"])
            time.sleep(3)
            # 小游戏/广告弹窗会挡住后续操作
            for guard in ("关闭", "跳过", "以后再说"):
                for n in nodes(dump_ui()):
                    if n["label"] == guard:
                        tap(n["cx"], n["cy"])
                        time.sleep(1)
                        break
            return True
        if scroll:
            swipe_up(1)
        else:
            time.sleep(1.5)
    return False


def surface_metrics(tag: str) -> dict:
    xml = dump_ui()
    labels = texts(xml)
    uniq = list(dict.fromkeys(labels))
    return {
        "surface": tag,
        "textNodes": len(uniq),
        "cards": xml.count("商品图片"),
        "kaiShou": xml.count("开售"),
        "yuShou": xml.count("预售"),
        "deposit": xml.count("定金"),
        "sample": [flat(t)[:26] for t in uniq][:14],
        "throttled": is_throttled(xml),
    }


SURFACE_STEPS = {
    # 淘宝：我的淘宝 → 我的收藏（里面有「开售提醒/降价」分类）
    "tb-favorite": [("launch", "taobao"), ("tap", "我的淘宝"), ("tap", "我的收藏")],
    # 淘宝：商品详情 → 店铺
    "tb-shop": None,  # 需要 --item
    # 京东：我的 → 我的预约
    "jd-reserve": [("launch", "jd"), ("tap", "我的"), ("tap", "预约")],
    # 京东：首页「预售」频道（免输入）
    "jd-channel": [("launch", "jd"), ("tap", "预售")],
}


def probe_surface(args) -> int:
    tag = args.surface
    steps = SURFACE_STEPS.get(tag)
    if tag not in SURFACE_STEPS:
        warn(f"未知入口：{tag}，可选 {', '.join(SURFACE_STEPS)}")
        return 1

    if tag == "tb-shop":
        item = args.item or "1103296796879"
        adb("shell", "am", "start", "-a", "android.intent.action.VIEW", "-d", deep_link(item))
        wait_page(DETAIL_KEYS, timeout=20)
        if not tap_label("店铺"):
            warn("没找到「店铺」入口")
        time.sleep(2)
        m = surface_metrics(tag)
    else:
        for action, target in steps:
            if action == "launch":
                launch(target)
                log(f"  启动 {target}")
            else:
                ok = tap_label(target)
                log(f"  点「{target}」→ {'成功' if ok else '没找到'}")
                if not ok:
                    m = surface_metrics(f"{tag}-stuck")
                    report_surface(m)
                    return 1
                time.sleep(2)
        m = surface_metrics(tag)

    if args.evidence:
        capture(f"surface-{tag}")
    report_surface(m)
    return 0


def report_surface(m: dict) -> None:
    log("")
    log(f"{'入口':<14} {'文字节点':>8} {'卡片':>6} {'开售':>5} {'预售':>5} {'定金':>5}")
    log(f"{m['surface']:<14} {m['textNodes']:>8} {m['cards']:>6} {m['kaiShou']:>5} "
        f"{m['yuShou']:>5} {m['deposit']:>5}"
        + ("  [频控]" if m["throttled"] else ""))
    log("  样例：" + " | ".join(m["sample"][:10]))


# ---------------------------------------------------------------- audit
def audit(args) -> int:
    fixtures = load_fixtures()
    if not fixtures:
        warn("fixtures/ 里还没有夹具")
        return 1
    now = datetime.now(TZ)
    bad = 0
    for fx in fixtures:
        item_id = fx.get("itemId")
        if not item_id:
            continue
        data = probe(item_id, evidence=args.evidence)
        if not data:
            fx["state"] = "unreachable"
            save_fixture(fx)
            bad += 1
            log(f"{fx['id']:<24} unreachable（打开失败/频控）")
            continue
        state = "live"
        if data["kind"] in ("gone", "on_sale"):
            state = data["kind"]
        elif data["saleTime"]:
            if datetime.fromisoformat(data["saleTime"]) < now:
                state = "on_sale"      # 已经开售了
            elif fx.get("saleTime") and data["saleTime"] != fx["saleTime"]:
                state = "changed"
        save_fixture({**data, "state": state})
        if state in ("gone", "unreachable"):
            bad += 1
        dd = ""
        if data["saleTime"]:
            left = datetime.fromisoformat(data["saleTime"]) - now
            dd = f"{data['saleTime']}  (T{'+' if left.total_seconds() >= 0 else '-'}{abs(left).days}d)"
        log(f"{fx['id']:<24} {state:<12} {dd}")
        time.sleep(VISIT_GAP)
    return 0 if bad == 0 else 1


# ---------------------------------------------------------------- cli
def cmd_list(_args) -> int:
    fixtures = load_fixtures()
    if not fixtures:
        warn("fixtures/ 里还没有夹具")
        return 1
    now = datetime.now(TZ)
    for fx in sorted(fixtures, key=lambda f: f.get("saleTime") or "9"):
        st = fx.get("saleTime")
        if st:
            left = datetime.fromisoformat(st) - now
            when = f"{st}  (T{'+' if left.total_seconds() >= 0 else '-'}{abs(left).days}d)"
        else:
            when = "-"
        log(f"{fx['id']:<26} {fx.get('state', '?'):<10} {fx.get('kind', ''):<12} {when}")
        log(f"  {fx.get('title', '')[:60]}")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description="AutoBuy 定时抢购夹具工具")
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("resolve", help="短链/深链/裸 id → itemId")
    p.add_argument("target")

    p = sub.add_parser("probe", help="打开一个商品并写入夹具")
    p.add_argument("item")
    p.add_argument("--no-evidence", action="store_true", help="不保存 dump/截图")

    p = sub.add_parser("harvest", help="枚举候选并逐个 probe")
    p.add_argument("--source", choices=("home", "search"), default="home")
    p.add_argument("--query", default="预售", help="search 源的关键词")
    p.add_argument("--sort", default=None, help="切排序 tab：综合/销量/上新")
    p.add_argument("--blind", action="store_true",
                   help="网格盲扫（搜索结果商品区没有文字节点时用）")
    p.add_argument("--max", type=int, default=5, help="最多产出几条夹具")
    p.add_argument("--scrolls", type=int, default=3, help="往下翻几屏")
    p.add_argument("--gap", type=float, default=VISIT_GAP, help="两次访问之间的间隔秒数")
    p.add_argument("--wait", type=float, default=14.0, help="详情页等待上限秒数")
    p.add_argument("--all", action="store_true", help="不看卡片标签，所有卡片都进详情")
    p.add_argument("--keep-all", action="store_true", help="非定时但非在售的也写进夹具")

    p = sub.add_parser("ingest", help="吃进一批分享链接（短链/淘口令/深链，一行一条）")
    p.add_argument("file", nargs="?", help="文本文件；不给就读 stdin")

    p = sub.add_parser("probe-surface", help="探测发现入口是否有可枚举的文字节点")
    p.add_argument("surface", help="tb-favorite | tb-shop | jd-reserve | jd-channel")
    p.add_argument("--item", help="tb-shop 用的商品 id")
    p.add_argument("--evidence", action="store_true")

    sub.add_parser("audit", help="巡检已收集的夹具").add_argument("--evidence", action="store_true")
    sub.add_parser("list", help="列出夹具")

    p = sub.add_parser("capture", help="保存当前屏幕 dump/截图")
    p.add_argument("name")

    args = ap.parse_args()

    if args.cmd == "resolve":
        item_id = resolve(args.target)
        if not item_id:
            warn("没解析出 itemId")
            return 1
        log(item_id)
        return 0
    if args.cmd == "probe":
        item_id = resolve(args.item) or args.item
        data = probe(item_id, evidence=not args.no_evidence)
        if not data:
            return 1
        path = save_fixture(data)
        log(f"{data['kind']:<12} {data['saleTime'] or '-':<26} {flat(data['title'] or '')}")
        log(f"→ {path.relative_to(ROOT)}")
        return 0
    if args.cmd == "harvest":
        return harvest(args)
    if args.cmd == "ingest":
        return cmd_ingest(args)
    if args.cmd == "probe-surface":
        return probe_surface(args)
    if args.cmd == "audit":
        return audit(args)
    if args.cmd == "list":
        return cmd_list(args)
    if args.cmd == "capture":
        log(str(capture(args.name)))
        return 0
    return 1


if __name__ == "__main__":
    sys.exit(main())
