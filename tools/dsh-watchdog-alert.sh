#!/bin/bash
# ============================================================================
# dsh-watchdog-alert.sh —— 新壳健康巡检（给零一/外部用的报警器）
#
# 解决什么问题（看门狗悖论）：
#   App 内部有心跳，外部有 host-guard，但**如果它们全死了，没人知道**。
#   这个脚本就是那个「站在最外面的人」：定期从 CT106 往手机里看。
#
# 设计原则：
#   · 正常时静默（exit 0 + 无输出）—— cronjob 的 no_agent 模式下空输出不发消息
#   · 异常时输出人话（谁、什么、多严重、怎么处理）
#   · 分级：能自愈的不吵，自愈不了的才喊
#
# 退出码约定：
#   0 = 一切正常（静默）
#   1 = 有异常但服务可能还在
#   2 = 服务确定不可用（需要立刻处理）
#
# ★ 引号嵌套铁律（2026-10-30 踩了两次）：
#   runsh 内部是 `adb shell "su -c '...'"`，命令里再出现任何引号都会被吃掉一层。
#   实测 grep '...' 和 awk '/.../' 两种写法都读回 0，导致误报「看护没在跑」。
#   所以：任何需要过滤/解析的操作，一律把原始输出取回本机再处理。
#   宁可多传几 KB 文本，也不要跟两层 shell 的引号搏斗。
# ============================================================================

ADB_TARGET="100.93.28.42:5555"
ENGINE_URL="http://100.93.28.42:13081/"
PKG="cc.bestly.dsh"
RT="/data/user/0/$PKG/files/runtime"

# 阈值
HB_WARN=300        # 心跳超过这个秒数报警（正常 30s 一次）
HB_CRIT=900        # 超过这个基本判定看护已死

runsh() { timeout 20 adb -s "$ADB_TARGET" shell "su -c '$1'" 2>/dev/null | tr -d '\r'; }

issues=""
critical=0

# ---------- 1. 手机在不在 ----------
if ! timeout 10 adb devices 2>/dev/null | grep -q "${ADB_TARGET}[[:space:]]*device"; then
    timeout 20 adb connect "$ADB_TARGET" >/dev/null 2>&1
    sleep 4
    if ! timeout 10 adb devices 2>/dev/null | grep -q "${ADB_TARGET}[[:space:]]*device"; then
        echo "🔴 手机离线 —— adb 连不上 $ADB_TARGET"
        echo "   可能原因：WiFi 断了 / Tailscale 掉了 / 手机重启中"
        exit 2
    fi
    issues="${issues}"$'\n'"· ⚠️ 手机曾离线，已自动重连"
fi

# ---------- 2. 心跳（App 看护还活着吗）----------
HB=$(runsh "head -1 $RT/heartbeat 2>/dev/null")
NOW_MS=$(date +%s%3N)
if [ -n "$HB" ] && [ "$HB" -gt 1000000000000 ] 2>/dev/null; then
    AGE=$(( (NOW_MS - HB) / 1000 ))
    if [ "$AGE" -ge "$HB_CRIT" ]; then
        issues="${issues}"$'\n'"· 🔴 App 看护心跳 ${AGE}s 未更新（阈值 ${HB_CRIT}s）—— 看护进程基本已死"
        critical=1
    elif [ "$AGE" -ge "$HB_WARN" ]; then
        issues="${issues}"$'\n'"· ⚠️ App 看护心跳 ${AGE}s 未更新（阈值 ${HB_WARN}s）"
    fi
else
    issues="${issues}"$'\n'"· ⚠️ 读不到心跳文件 $RT/heartbeat"
fi

# ---------- 3. 引擎端口 ----------
CODE=$(curl -s -o /dev/null -w '%{http_code}' --max-time 8 "$ENGINE_URL" 2>/dev/null)
if [ "$CODE" != "401" ]; then
    PORT=$(runsh "cat $RT/port 2>/dev/null")
    HOST=$(runsh "cat $RT/host 2>/dev/null")
    issues="${issues}"$'\n'"· 🔴 引擎端口 $CODE（期望 401），runtime/port=${PORT:-?} host=${HOST:-?}"
    critical=1
fi

# ---------- 4. 外部看护进程（本机过滤，避免引号嵌套）----------
GUARD_N=$(runsh "ps -A -o PID,ARGS 2>/dev/null" | grep 'host-guard-v2 60' | grep -v grep | wc -l | tr -d ' ')
if [ "${GUARD_N:-0}" = "0" ]; then
    issues="${issues}"$'\n'"· 🔴 外部看护 host-guard v2 没在跑（引擎挂死后没人救）"
    critical=1
elif [ "$GUARD_N" != "1" ]; then
    issues="${issues}"$'\n'"· ⚠️ 外部看护有 ${GUARD_N} 个实例（期望 1，可能锁失效）"
fi

# ---------- 5. 冻结状态 ----------
FROZEN=$(runsh "cat /sys/fs/cgroup/uid_10295/cgroup.freeze 2>/dev/null")
[ "$FROZEN" = "1" ] && issues="${issues}"$'\n'"· ⚠️ App 当前被 ColorOS 冻结（freeze=1），看护正在处理"

# ---------- 输出 ----------
if [ -z "$issues" ]; then
    exit 0    # 静默：一切正常
fi

if [ "$critical" = "1" ]; then
    echo "🔴 dsh 新壳异常（$(date '+%H:%M')）"
else
    echo "⚠️ dsh 新壳告警（$(date '+%H:%M')）"
fi
echo "$issues"
echo ""
echo "快速自查："
echo "  curl -o /dev/null -w '%{http_code}' $ENGINE_URL"
echo "  adb -s $ADB_TARGET shell \"su -c 'cat $RT/heartbeat'\""
echo "  恢复手段：写 $RT/restart 文件（不亮屏）或 adb shell am start -n $PKG/.MainActivity"

[ "$critical" = "1" ] && exit 2
exit 1
