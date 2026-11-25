#!/usr/bin/env bash
# AutoBuy 真机测试夹具
#
# 两种测试品，覆盖两类场景：
#   1) 定时开售（淘宝「即将开始，M.DD HH:MM开售」）：验证 时间校准 → 就位 → 开售刷新 → 提交。
#      夹具由 scripts/fixtures.py 收集，放在 fixtures/*.json。
#   2) 长期在售（京东维达抽纸）：随时可跑，验证 就位 → 结算 → 确认页 → 提交。
#
# 用法：
#   scripts/android-seckill-test.sh list                          # 看有哪些夹具
#   scripts/android-seckill-test.sh prepare --fixture tb-1103296796879   # 打开商品并加购
#   scripts/android-seckill-test.sh run --fixture tb-1103296796879       # 跑到就位前停（不下单）
#   scripts/android-seckill-test.sh run --fixture tb-1103296796879 --at +3m   # 用「现在+3分」当目标时间
#   scripts/android-seckill-test.sh run --fixture ... --at "$SALE" --submit-order --cancel-order  # 真下单并自动取消
#   scripts/android-seckill-test.sh orders                        # 打开「待付款」（只读核对）
#   scripts/android-seckill-test.sh cart                          # 打开购物车
#   scripts/android-seckill-test.sh logs                          # 只看 AutoBuy 日志
#
# 前置：手机已装 AutoBuy、无障碍已开启、淘宝/京东已登录、商品已加进购物车。
# 注意：`--submit-order` 会生成**真实待付款订单**（不付款不扣钱），跑完用 `--cancel-order` 取消。
set -uo pipefail

ADB="${ADB:-${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PKG="com.autobuy.app"
JD_PKG="com.jingdong.app.mall"
TB_PKG="com.taobao.taobao"

# 默认测试品：维达抽纸 3 层 100 抽*10 包（京东自营，常年有货，¥17.9）
FIXTURE_SKU="100021015676"
JD_DEEP_LINK_PRODUCT="openapp.jdmobile://virtual?params=%7B%22category%22%3A%22jump%22%2C%22des%22%3A%22productDetail%22%2C%22skuId%22%3A%22${FIXTURE_SKU}%22%7D"
JD_DEEP_LINK_CART="openapp.jdmobile://virtual?params=%7B%22category%22%3A%22jump%22%2C%22des%22%3A%22cart%22%7D"

FX_ID=""; FX_PLATFORM=""; FX_ITEMID=""; FX_DEEPLINK=""; FX_SALETIME=""; FX_TITLE=""; FX_KIND=""

say() { printf '\n\033[1;34m== %s\033[0m\n' "$*"; }
ok()  { printf '\033[1;32m  ✓ %s\033[0m\n' "$*"; }
bad() { printf '\033[1;31m  ✗ %s\033[0m\n' "$*"; }
die() { printf '\033[1;31m!! %s\033[0m\n' "$*" >&2; exit 1; }

need_device() {
  $ADB devices | grep -q "device$" || die "没有连接设备（adb devices）"
}

screen_size() {
  $ADB shell wm size | awk -F'[:x ]+' '/Physical|Override/ {print $(NF-1), $NF; exit}'
}

# 从 uiautomator dump 里找节点中心坐标。
#   ui_center <文字>        文本精确匹配
#   ui_center -d <desc>     content-desc 精确匹配
#   ui_center -c <片段>     文本或 desc 包含该片段
ui_center() {
  local mode="text" want="$1"
  if [ "${1:-}" = "-d" ]; then mode="desc"; want="$2"; elif [ "${1:-}" = "-c" ]; then mode="contains"; want="$2"; fi
  local tmp; tmp="$(mktemp)"
  $ADB shell uiautomator dump /sdcard/_ab.xml >/dev/null 2>&1
  $ADB exec-out cat /sdcard/_ab.xml > "$tmp" 2>/dev/null
  python3 - "$tmp" "$mode" "$want" <<'PY'
import re, sys
xml = open(sys.argv[1], encoding='utf-8', errors='ignore').read()
mode, want = sys.argv[2], sys.argv[3]
for tag in re.findall(r'<node[^>]*>', xml):
    t = (re.search(r'text="([^"]*)"', tag) or [None, ''])[1]
    d = (re.search(r'content-desc="([^"]*)"', tag) or [None, ''])[1]
    t = t.replace('&amp;', '&'); d = d.replace('&amp;', '&')
    hit = (t == want) if mode == 'text' else (d == want) if mode == 'desc' else (want in (t + d))
    if not hit:
        continue
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
    if not b:
        continue
    x1, y1, x2, y2 = map(int, b.groups())
    print((x1 + x2) // 2, (y1 + y2) // 2)
    break
PY
  rm -f "$tmp"
}

tap_text() { local xy; xy="$(ui_center "$1")"; [ -n "$xy" ] || return 1; $ADB shell input tap $xy; }
tap_desc() { local xy; xy="$(ui_center -d "$1")"; [ -n "$xy" ] || return 1; $ADB shell input tap $xy; }
tap_contains() { local xy; xy="$(ui_center -c "$1")"; [ -n "$xy" ] || return 1; $ADB shell input tap $xy; }

tap_any() { tap_text "$1" || tap_desc "$1" || tap_contains "$1"; }

app_version() {
  $ADB shell dumpsys package "$PKG" 2>/dev/null | awk -F= '/versionName/ {print $2; exit}' | tr -d ' \r'
}

a11y_ready() {
  $ADB shell settings get secure enabled_accessibility_services | grep -q "$PKG"
}

enable_a11y() {
  local cur
  cur="$($ADB shell settings get secure enabled_accessibility_services | tr -d '\r')"
  case "$cur" in *"$PKG"*) return 0 ;; esac
  say "把 AutoBuy 加进无障碍服务列表（临时手段，正式使用请在系统设置里手动开）"
  $ADB shell settings put secure enabled_accessibility_services \
    "${cur:+$cur:}$PKG/$PKG.accessibility.AutoBuyAccessibilityService"
  sleep 3
}

open_app_ready() {
  $ADB shell am start -n "$PKG/.MainActivity" >/dev/null
  sleep 3
  if [ -n "$(ui_center "还不能开始")" ]; then
    say "无障碍状态未刷新，切后台再进入一次"
    $ADB shell input keyevent KEYCODE_HOME; sleep 1
    $ADB shell am start -n "$PKG/.MainActivity" >/dev/null; sleep 3
  fi
  [ -z "$(ui_center "还不能开始")" ] || die "App 仍显示「还不能开始」：请在系统设置里手动开启 AutoBuy 的无障碍服务"
}

# ------------------------------------------------------------------ 夹具
load_fixture() {
  local want="${1:-}" f
  [ -n "$want" ] || die "需要 --fixture <id|itemId>"
  f="$want"
  [ -f "$f" ] || f="$(ls "$ROOT"/fixtures/*"$want"*.json 2>/dev/null | head -1)"
  [ -n "$f" ] && [ -f "$f" ] || die "找不到夹具：fixtures/*$want*.json（用 list 看看有什么）"
  eval "$(python3 - "$f" <<'PY'
import json, sys, shlex
d = json.load(open(sys.argv[1], encoding='utf-8'))
def line(k, v):
    v = '' if v is None else str(v)
    print(f"{k}={shlex.quote(v)}")
print(f"# {d.get('title','')}")
for k, v in (("FX_ID", d.get("id")), ("FX_PLATFORM", d.get("platform")),
             ("FX_ITEMID", d.get("itemId")), ("FX_DEEPLINK", d.get("deepLink")),
             ("FX_SALETIME", d.get("saleTime")), ("FX_TITLE", d.get("title")),
             ("FX_KIND", d.get("kind"))):
    line(k, v)
PY
)"
  case "$FX_PLATFORM" in
    taobao) FX_PKG="$TB_PKG"; FX_LABEL="淘宝" ;;
    jd|jingdong) FX_PKG="$JD_PKG"; FX_LABEL="京东" ;;
    *) die "夹具 platform 不认识：$FX_PLATFORM" ;;
  esac
}

# 当前前台 Activity（淘宝购物车是自绘页、零文字节点，只能靠它判断）
top_activity() {
  $ADB shell dumpsys activity activities 2>/dev/null | grep -m1 topResumedActivity
}

# 当前界面有多少个带文字的无障碍节点（0 表示这页对无障碍不可见）
a11y_text_count() {
  $ADB shell uiautomator dump /sdcard/_ab.xml >/dev/null 2>&1
  $ADB exec-out cat /sdcard/_ab.xml 2>/dev/null | grep -oE '(text|content-desc)="[^"]+"' | wc -l | tr -d ' '
}

# ------------------------------------------------------------------ 打开 App
open_cart() {
  case "$FX_PLATFORM" in
    taobao)
      # 淘宝购物车零文字节点，只能按 Activity 判断；冷启动有 Welcome 页，所以重试
      $ADB shell monkey -p "$TB_PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
      local i
      for i in 1 2 3 4 5; do
        top_activity | grep -q "CartActivity" && sleep 2 && return 0
        tap_desc "购物车" || tap_contains "购物车" || true
        sleep 3
      done
      die "没进到淘宝购物车（前台：$(top_activity)）" ;;
    *)
      $ADB shell am start -a android.intent.action.VIEW -d "$JD_DEEP_LINK_CART" >/dev/null
      sleep 3 ;;
  esac
}

open_orders() {
  case "$FX_PLATFORM" in
    taobao)
      $ADB shell monkey -p "$TB_PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
      sleep 4
      tap_desc "我的淘宝" || tap_contains "我的淘宝" || die "找不到「我的淘宝」"
      sleep 3
      tap_contains "待付款" || die "「我的淘宝」里没找到待付款入口"
      sleep 3 ;;
    *)
      $ADB shell monkey -p "$JD_PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
      sleep 4
      tap_contains "我的" || die "找不到京东「我的」"
      sleep 2
      tap_contains "待付款" || die "京东「我的」里没找到待付款"
      sleep 3 ;;
  esac
}

# ------------------------------------------------------------------ prepare
prepare() {
  local fx=""
  while [ $# -gt 0 ]; do case "$1" in --fixture) fx="${2:-}"; shift 2 ;; *) shift ;; esac; done
  need_device

  if [ -n "$fx" ]; then
    load_fixture "$fx"
    say "打开夹具 ${FX_ID}（${FX_LABEL}）：${FX_TITLE}"
    $ADB shell am start -a android.intent.action.VIEW -d "$FX_DEEPLINK" >/dev/null
    sleep 6
    read -r W H <<<"$(screen_size)" || die "读不到屏幕尺寸"
    if tap_text "加入购物车"; then
      sleep 3
      # 多规格商品会弹规格面板：底部通栏「确定」（没有文字节点时按比例点）
      tap_text "确定" || { say "规格面板按比例点「确定」（${W}x${H}）"; $ADB shell input tap $((W / 2)) $((H * 93 / 100)); }
      sleep 2
      tap_text "确定" >/dev/null 2>&1
    else
      say "没找到「加入购物车」：可能已加购、或该商品开售前不允许加购"
    fi
    open_cart
    $ADB exec-out screencap -p > /tmp/autobuy-cart.png
    say "已停在购物车，截图：/tmp/autobuy-cart.png"
    if [ "$(a11y_text_count)" = "0" ]; then
      say "注意：淘宝购物车对无障碍不可见（零文字节点），程序只能靠颜色/坐标兜底；勾选状态请用截图确认"
    fi
    say "确认：商品在列表里、处于勾选状态（预售商品开售前勾不上属正常，程序到点会重试）"
    return 0
  fi

  say "打开京东测试商品（SKU $FIXTURE_SKU）"
  $ADB shell am start -a android.intent.action.VIEW -d "$JD_DEEP_LINK_PRODUCT" >/dev/null
  sleep 5
  say "加入购物车"
  tap_text "加入购物车" || die "没找到「加入购物车」按钮，检查页面是否加载完"
  sleep 3
  read -r W H <<<"$(screen_size)" || die "读不到屏幕尺寸"
  say "规格面板「确定」（${W}x${H}）"
  $ADB shell input tap $((W / 2)) $((H * 93 / 100))
  sleep 2
  say "回到购物车"
  $ADB shell am start -a android.intent.action.VIEW -d "$JD_DEEP_LINK_CART" >/dev/null
  sleep 3
  $ADB exec-out screencap -p > /tmp/autobuy-cart.png
  say "已打开购物车，截图：/tmp/autobuy-cart.png（确认商品在购物车里、且是勾选状态）"
}

# ------------------------------------------------------------------ 断言
assert_log() { # <正则> <说明>
  if $ADB logcat -d -s AutoBuy | grep -qE "$1"; then ok "$2"; else bad "$2"; FAIL=$((FAIL + 1)); fi
}

check_logs() {
  FAIL=0
  say "断言"
  assert_log "网络时间校准" "网络时间校准"
  assert_log "点击结算 @|点击「" "点结算"
  assert_log "确认订单页已就位|确认页已就位" "确认订单页就位"
  if [ "${1:-}" = "submit" ]; then
    assert_log "点击提交（第|已点击" "点提交"
    assert_log "收银台|确认付款|支付订单|订单提交成功" "进收银台/提交成功"
  fi
  [ "$FAIL" -eq 0 ] && ok "全部通过" || bad "$FAIL 项未通过"
}

# ------------------------------------------------------------------ 取消订单（best effort）
cancel_order() {
  say "尝试自动取消待付款订单（淘宝：我的淘宝 → 待付款 → 取消订单）"
  open_orders
  $ADB exec-out screencap -p > /tmp/autobuy-orders.png
  if tap_contains "取消订单"; then
    sleep 2
    tap_contains "确认取消" || tap_contains "确定" || true
    sleep 2
    if ui_center -c "已取消" >/dev/null 2>&1; then ok "订单已取消"; else
      say "已点「取消订单」，请看一眼截图确认：/tmp/autobuy-orders.png"
    fi
  else
    bad "没找到「取消订单」按钮"
    say "手动取消：/tmp/autobuy-orders.png 这张截图就是待付款列表，点进订单取消即可"
  fi
}

# ------------------------------------------------------------------ run
run() {
  local submit=0 at="" fx="" cancel=0 wait_limit=600
  while [ $# -gt 0 ]; do
    case "$1" in
      --submit-order|--submit) submit=1; shift ;;
      --cancel-order) cancel=1; shift ;;
      --at) at="${2:-}"; shift 2 ;;
      --fixture) fx="${2:-}"; shift 2 ;;
      --wait-limit) wait_limit="${2:-600}"; shift 2 ;;
      *) shift ;;
    esac
  done

  if [ -n "$fx" ]; then
    load_fixture "$fx"
    [ -n "$at" ] || at="$FX_SALETIME"
    say "夹具 ${FX_ID}（${FX_LABEL} / ${FX_KIND}）"
    [ -n "$FX_TITLE" ] && say "商品：${FX_TITLE}"
  else
    FX_PLATFORM="jd"; FX_PKG="$JD_PKG"; FX_LABEL="京东"
  fi
  [ -n "$at" ] || at="+1m"

  # --at 支持：HH:MM[:SS] / ISO 时间 / +Nm（现在 + N 分钟）
  local target_epoch now_epoch wait_s rel
  now_epoch="$(date +%s)"
  case "$at" in
    +*m) rel="${at#+}"; rel="${rel%m}"; target_epoch=$(( now_epoch + 60 * rel )) ;;
    +*s) rel="${at#+}"; rel="${rel%s}"; target_epoch=$(( now_epoch + rel )) ;;
    *)   target_epoch="$(date -d "$at" +%s 2>/dev/null)" || die "--at 格式：HH:MM[:SS] / ISO 时间 / +3m" ;;
  esac
  # App 只有「现在+1分」，所以睡到 T-60 再点，目标时间正好落在 T
  wait_s=$(( target_epoch - 60 - now_epoch ))
  [ "$wait_s" -gt 0 ] || die "目标时间距现在不足 60 秒（$at），来不及设置"
  if [ "$wait_limit" -gt 0 ] && [ "$wait_s" -gt "$wait_limit" ]; then
    die "目标时间还有 $((wait_s / 60)) 分钟（上限 ${wait_limit}s）。到点前再跑，或 --wait-limit 0 让它一直等到那时"
  fi

  need_device
  [ -n "$(app_version)" ] || die "设备上没装 $PKG"
  a11y_ready || enable_a11y
  a11y_ready || die "无障碍服务没开启：先打开 AutoBuy → 指引，或手动在系统设置里开启"
  say "AutoBuy 版本 $(app_version)"

  say "等到目标时间前 60 秒（目标 $(date -d @"$target_epoch" '+%m-%d %H:%M:%S')），约 $((wait_s / 60)) 分 $((wait_s % 60)) 秒"
  sleep "$wait_s"

  $ADB logcat -c
  say "启动 AutoBuy：${FX_LABEL} / 现在+1分 / 开始抢购"
  open_app_ready
  tap_text "$FX_LABEL" || die "没找到平台按钮「${FX_LABEL}」"
  sleep 1
  tap_text "现在+1分" || die "没找到「现在+1分」"
  sleep 1
  tap_text "开始抢购" || die "没找到「开始抢购」，确认无障碍已开启（否则顶部有警告条，布局下移）"
  sleep 2

  say "切到购物车（${FX_LABEL}）"
  open_cart

  local rest=$(( target_epoch - $(date +%s) ))
  if [ "$submit" = "1" ]; then
    say "跑到点会真实下单（不付款不扣钱）；T 后 30 秒收日志"
    sleep $(( rest > 0 ? rest + 30 : 30 ))
    check_logs submit
    $ADB exec-out screencap -p > /tmp/autobuy-after-submit.png
    say "截图：/tmp/autobuy-after-submit.png"
    [ "$cancel" = "1" ] && cancel_order
  else
    say "跑到就位阶段（T-20s）后停掉，不会下单"
    sleep 50
    check_logs
    $ADB exec-out screencap -p > /tmp/autobuy-before-submit.png
    $ADB shell am force-stop "$PKG"
    say "已在到点前停掉 AutoBuy；截图：/tmp/autobuy-before-submit.png"
  fi
  say "AutoBuy 日志"
  $ADB logcat -d -s AutoBuy | tail -25
}

logs() { $ADB logcat -d -s AutoBuy | tail -40; }

cart() {
  FX_PLATFORM="${FX_PLATFORM:-}"; need_device
  local fx=""
  while [ $# -gt 0 ]; do case "$1" in --fixture) fx="${2:-}"; shift 2 ;; *) shift ;; esac; done
  [ -n "$fx" ] && load_fixture "$fx" || { FX_PLATFORM="jd"; FX_PKG="$JD_PKG"; }
  open_cart
  $ADB exec-out screencap -p > /tmp/autobuy-cart.png
  local n; n="$(a11y_text_count)"
  say "购物车截图：/tmp/autobuy-cart.png（无障碍文字节点：$n）"
  [ "$n" = "0" ] && say "这一页对无障碍不可见：程序的「合计/勾选」判断拿不到数据，只能靠颜色与坐标兜底"
}

list_fixtures() { python3 "$ROOT/scripts/fixtures.py" list; }

need_device
case "${1:-}" in
  prepare) shift; prepare "$@" ;;
  run) shift; run "$@" ;;
  cart) shift; cart "$@" ;;
  orders) shift; load_fixture "${2:-}"; open_orders; say "待付款截图：/tmp/autobuy-orders.png" ;;
  cancel-order) shift; load_fixture "${2:-}"; cancel_order ;;
  list) list_fixtures ;;
  logs) logs ;;
  *) echo "用法: $0 {list|prepare [--fixture X]|run [--fixture X] [--at T] [--submit-order] [--cancel-order]|cart|orders|cancel-order|logs}"; exit 1 ;;
esac
