#!/usr/bin/env bash
# AutoBuy 真机测试夹具（京东）
#
# 目的：不必每次到处找"抢购商品"。固定一个便宜、长期在售的京东自营 SKU 当测试品，
# 用「现在+1分」当抢购时间，跑完整流程（就位 → 结算 → 确认页 → 到点提交）。
#
# 用法：
#   scripts/android-seckill-test.sh prepare      # 把测试商品加进购物车（会自动勾选）
#   scripts/android-seckill-test.sh run          # 跑一轮，到点前 8 秒自动停（不会下单）
#   scripts/android-seckill-test.sh run --at "12:00:00"   # 把目标时间设成某个开售时间（到点前停）
#   scripts/android-seckill-test.sh run --submit --at "12:00:00"  # 跑到点（会生成真实订单，自己取消）
#   scripts/android-seckill-test.sh logs         # 只看 AutoBuy 日志
#
# 前置：手机已装 AutoBuy 0.3.8+、无障碍已开启、京东已登录。
# 注意：`run --submit` 会真的下单，用测试品（十几块）并事后取消。
set -uo pipefail

ADB="${ADB:-${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb}"
PKG="com.autobuy.app"
JD_PKG="com.jingdong.app.mall"

# 测试品：维达抽纸 3 层 100 抽*10 包（京东自营/京东超市，常年有货，¥17.9）
FIXTURE_SKU="100021015676"
DEEP_LINK_PRODUCT="openapp.jdmobile://virtual?params=%7B%22category%22%3A%22jump%22%2C%22des%22%3A%22productDetail%22%2C%22skuId%22%3A%22${FIXTURE_SKU}%22%7D"
DEEP_LINK_CART="openapp.jdmobile://virtual?params=%7B%22category%22%3A%22jump%22%2C%22des%22%3A%22cart%22%7D"

say() { printf '\n\033[1;34m== %s\033[0m\n' "$*"; }
die() { printf '\033[1;31m!! %s\033[0m\n' "$*" >&2; exit 1; }

need_device() {
  $ADB devices | grep -q "device$" || die "没有连接设备（adb devices）"
}

# 屏幕尺寸（用于按比例点击）
screen_size() {
  $ADB shell wm size | awk -F'[:x ]+' '/Physical|Override/ {print $(NF-1), $NF; exit}'
}

# 从 uiautomator dump 里找文字节点中心坐标：ui_text_center "加入购物车"
ui_text_center() {
  local want="$1" tmp
  tmp="$(mktemp)"
  $ADB shell uiautomator dump /sdcard/_ab.xml >/dev/null 2>&1
  $ADB pull /sdcard/_ab.xml "$tmp" >/dev/null 2>&1
  python3 - "$tmp" "$want" <<'PY'
import re, sys
xml = open(sys.argv[1], encoding='utf-8', errors='ignore').read()
want = sys.argv[2]
for tag in re.findall(r'<node[^>]*>', xml):
    m = re.search(r'text="([^"]*)"', tag)
    if not m or m.group(1) != want:
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

tap_text() {
  local xy
  xy="$(ui_text_center "$1")"
  [ -n "$xy" ] || return 1
  # shellcheck disable=SC2086
  $ADB shell input tap $xy
}

app_version() {
  $ADB shell dumpsys package "$PKG" 2>/dev/null | awk -F= '/versionName/ {print $2; exit}' | tr -d ' \r'
}

a11y_ready() {
  $ADB shell settings get secure enabled_accessibility_services | grep -q "$PKG"
}

# 临时把本服务加进无障碍列表（ROM 会在 force-stop/更新后把它踢掉）
enable_a11y() {
  local cur
  cur="$($ADB shell settings get secure enabled_accessibility_services | tr -d '\r')"
  case "$cur" in
    *"$PKG"*) return 0 ;;
  esac
  say "把 AutoBuy 加进无障碍服务列表（临时手段，正式使用请在系统设置里手动开）"
  $ADB shell settings put secure enabled_accessibility_services \
    "${cur:+$cur:}$PKG/$PKG.accessibility.AutoBuyAccessibilityService"
  sleep 3
}

# 启动 App 并确认无障碍状态已刷新（刚开启时首屏可能还是旧状态，回一次桌面再进）
open_app_ready() {
  $ADB shell am start -n "$PKG/.MainActivity" >/dev/null
  sleep 3
  if [ -n "$(ui_text_center "还不能开始")" ]; then
    say "无障碍状态未刷新，切后台再进入一次"
    $ADB shell input keyevent KEYCODE_HOME
    sleep 1
    $ADB shell am start -n "$PKG/.MainActivity" >/dev/null
    sleep 3
  fi
  [ -z "$(ui_text_center "还不能开始")" ] || die "App 仍显示「还不能开始」：请在系统设置里手动开启 AutoBuy 的无障碍服务"
}

# ------------------------------------------------------------------ prepare
prepare() {
  need_device
  say "打开测试商品（SKU $FIXTURE_SKU）"
  $ADB shell am start -a android.intent.action.VIEW -d "$DEEP_LINK_PRODUCT" >/dev/null
  sleep 5

  say "加入购物车"
  tap_text "加入购物车" || die "没找到「加入购物车」按钮，检查页面是否加载完"
  sleep 3

  # 多规格商品会弹规格面板：底部通栏红色「确定」按比例点（没有文字节点）
  read -r W H <<<"$(screen_size)" || die "读不到屏幕尺寸"
  say "规格面板「确定」（${W}x${H}）"
  $ADB shell input tap $((W / 2)) $((H * 93 / 100))
  sleep 2

  say "回到购物车"
  $ADB shell am start -a android.intent.action.VIEW -d "$DEEP_LINK_CART" >/dev/null
  sleep 3
  $ADB exec-out screencap -p > /tmp/autobuy-cart.png
  say "已打开购物车，截图：/tmp/autobuy-cart.png（确认商品在购物车里、且是勾选状态）"
}

# ---------------------------------------------------------------------- run
run() {
  local submit=0 at=""
  while [ $# -gt 0 ]; do
    case "$1" in
      --submit) submit=1; shift ;;
      --at) at="${2:-}"; shift 2 ;;
      *) shift ;;
    esac
  done

  # --at：把「现在+1分」对准到指定开售时间。App 只有「现在+1分」按钮，
  # 所以先睡到 (开售时间 - 60s) 再点，目标时间就正好等于开售时间。
  if [ -n "$at" ]; then
    local target_epoch now_epoch wait_s
    target_epoch="$(date -d "$at" +%s 2>/dev/null)" || die "--at 格式应为 HH:MM 或 HH:MM:SS"
    now_epoch="$(date +%s)"
    wait_s=$(( target_epoch - 60 - now_epoch ))
    [ "$wait_s" -gt 0 ] || die "开售时间 $at 距现在不到 1 分钟，来不及设置"
    say "等到开售前 60 秒（目标 $at），约 $((wait_s / 60)) 分 $((wait_s % 60)) 秒"
    sleep "$wait_s"
  fi
  need_device
  [ -n "$(app_version)" ] || die "设备上没装 $PKG"
  a11y_ready || enable_a11y
  a11y_ready || die "无障碍服务没开启：先打开 AutoBuy → 指引，或手动在系统设置里开启"
  say "AutoBuy 版本 $(app_version)"

  $ADB logcat -c
  say "启动 AutoBuy 并设置 京东 / 现在+1分 / 开始抢购"
  open_app_ready
  tap_text "京东" || die "没找到「京东」"
  sleep 1
  tap_text "现在+1分" || die "没找到「现在+1分」"
  sleep 1
  tap_text "开始抢购" || die "没找到「开始抢购」，确认无障碍已开启（否则顶部有警告条，布局下移）"
  sleep 2

  say "切到京东购物车"
  $ADB shell am start -a android.intent.action.VIEW -d "$DEEP_LINK_CART" >/dev/null

  # 目标时间 = 启动 + 60s；就位在 T-20s，这里 T-15s 读日志
  if [ "$submit" = "1" ]; then
    say "跑到点（会真实下单），T 后 20 秒收日志"
    sleep 80
  else
    say "等到就位阶段（约 45 秒），然后停止"
    sleep 45
  fi

  say "AutoBuy 日志"
  $ADB logcat -d -s AutoBuy | tail -25

  if [ "$submit" != "1" ]; then
    $ADB shell am force-stop "$PKG"
    say "已在到点前停掉 AutoBuy（不会下单）"
  fi
}

logs() { $ADB logcat -d -s AutoBuy | tail -40; }

need_device
case "${1:-}" in
  prepare) prepare ;;
  run) shift; run "$@" ;;
  logs) logs ;;
  *) echo "用法: $0 {prepare|run [--submit] [--at HH:MM[:SS]]|logs}"; exit 1 ;;
esac
