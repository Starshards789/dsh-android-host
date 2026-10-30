#!/bin/bash
# dsh 迁移一周后验收 —— 2026-11-06（运行时不需要参数）
#
# 零一的规矩：「验法要能报错，不能只能点头」
#   → 月记验收必须 wc -l / wc -c 直读比数字，绝不用检索结果倒推
#   → 「搜不到」不等于「没写」（零八 2026-10-30 亲自教过一次：搜索没命中就断言
#      「37 天没写记忆」，实际文件有 45 行 / 11,841 字节）
#
# 这个脚本只做三件事：量数字、看状态、给结论。不做任何破坏性操作。
# 卸载旧壳这种不可逆的事，留给人拍板。

set +e

MONTHLY=/opt/docker/openviking/data/data/viking/zero-one/user/zero-eight/memories/日记/2026-10.md
SEP=202609=/opt/docker/openviking/data/data/viking/zero-one/user/zero-eight/memories/日记/2026-09.md

# ★ 基线说明（重要，别改回去）：
#   2026-10-30 20:33 时的真实读数是 45 行 / 11,841 字节 —— 那是「任务收尾三问」
#   上线前的状态。但那天晚间零一自己做了迁移验证，往日记里写了测试记录
#   （写记忆能力验证 +140 字节），所以到 21:23 已经变成 53 行 / 12,025 字节。
#
#   如果仍用 45/11841 当基线，11-06 那天会算出「+8 行达标」——
#   而其中 8 行没有一行是零八自己想起来写的，全是零一的测试产物。
#   那就是一个会撒谎的验法，违反「验法要能报错，不能只能点头」。
#
#   所以基线取「零一停止测试之后」的读数：53 行 / 12,025 字节（2026-10-30 21:23）。
#   这个数字以后不能再因为零一的测试而变 —— 要测记忆写入，另开测试文件，别写日记。
BASE_LINES=53
BASE_BYTES=12025

echo "📋 dsh 迁移一周后验收 · $(date '+%Y-%m-%d %H:%M')"
echo ""

# ---------- ① 记忆增长（核心指标）----------
echo "━━ ① 零八月记增长（核心）━━"
if [ -f "$MONTHLY" ]; then
  NOW_LINES=$(wc -l < "$MONTHLY")
  NOW_BYTES=$(wc -c < "$MONTHLY")
  D_LINES=$((NOW_LINES - BASE_LINES))
  D_BYTES=$((NOW_BYTES - BASE_BYTES))
  echo "  基线: ${BASE_LINES} 行 / ${BASE_BYTES} 字节  （2026-10-30 21:23，零一停止测试后）"
  echo "  现在: ${NOW_LINES} 行 / ${NOW_BYTES} 字节"
  echo "  增长: +${D_LINES} 行 / +${D_BYTES} 字节"
  if [ "$D_LINES" -ge 3 ]; then
    echo "  ✅ 达标（判据：≥3 行）"
  elif [ "$D_LINES" -gt 0 ]; then
    echo "  ⚠️ 有增长但不足 3 行 —— 触发器可能不够强，考虑升级方案 B（订阅 turn/end）"
  else
    echo "  ❌ 没长 —— 第 1 步「任务收尾三问」无效，按 B → C → D 升级"
  fi
  echo ""
  echo "  最近 5 条记录的开头（人工看质量，别只看数字）:"
  grep -E "^## 2026|^### 2026|^- \[2026" "$MONTHLY" 2>/dev/null | tail -5 | cut -c1-90 | sed 's/^/    /'
else
  echo "  ❌ 文件不存在: $MONTHLY"
  echo "     （先确认路径对不对，别急着下结论 —— 见「搜不到≠没有」）"
fi
echo ""

# ---------- ② 新壳健康 ----------
echo "━━ ② 新壳健康 ━━"
ADB="adb -s 100.93.28.42:5555"
NEW=$(timeout 20 $ADB shell "su -c 'curl -s -o /dev/null -w \"%{http_code}\" -m 5 http://100.93.28.42:13081/'" 2>/dev/null | tr -d '\r\n')
echo "  引擎 13081 = ${NEW:-000}  $([ "$NEW" = "401" ] && echo '✅' || echo '❌')"

HB=$(timeout 20 $ADB shell "su -c 'head -1 /data/user/0/cc.bestly.dsh/files/runtime/heartbeat 2>/dev/null'" 2>/dev/null | tr -d '\r\n')
if [ -n "$HB" ]; then
  NOW_MS=$(date +%s%3N)
  AGE=$(( (NOW_MS - HB) / 1000 ))
  echo "  心跳年龄 = ${AGE}s  $([ "$AGE" -lt 300 ] && echo '✅' || echo '⚠️ 看护可能已死')"
else
  echo "  心跳 ❌ 读不到"
fi

PS=$(timeout 20 $ADB shell "su -c 'ps -A -o ARGS'" 2>/dev/null | grep -c "dsh-tools/host-guard-v2")
echo "  host-guard-v2 = ${PS} 个  $([ "$PS" -ge 1 ] && echo '✅' || echo '❌ 外部看护没了')"

MW=$(timeout 20 $ADB shell "su -c 'ps -A -o ARGS'" 2>/dev/null | grep -c "35-mailwatch.sh")
echo "  mailwatch v5   = ${MW} 个  $([ "$MW" -ge 1 ] && echo '✅' || echo '❌ 信箱通道断了')"

RELAY=$(systemctl is-active tg8-relay 2>/dev/null)
echo "  中继 = ${RELAY}  $([ "$RELAY" = "active" ] && echo '✅' || echo '❌')"
echo ""

# ---------- ③ 旧壳现状（决定能不能删）----------
echo "━━ ③ 旧壳现状 ━━"
DIS=$(timeout 20 $ADB shell "su -c 'pm list packages -d'" 2>/dev/null | grep -c "com.deepseek.harness")
echo "  禁用状态 = $([ "$DIS" -ge 1 ] && echo 'disabled ✅' || echo '未禁用 ⚠️')"
OLD=$(timeout 20 $ADB shell "su -c 'du -sh /data/data/com.deepseek.harness/files/payload/dshhome 2>/dev/null | cut -f1'" 2>/dev/null | tr -d '\r\n')
echo "  数据 = ${OLD:-读不到}"
OLDENG=$(timeout 20 $ADB shell "su -c 'curl -s -o /dev/null -w \"%{http_code}\" -m 5 http://127.0.0.1:3080/'" 2>/dev/null | tr -d '\r\n')
echo "  引擎 3080 = ${OLDENG:-000}  $([ "$OLDENG" = "000" ] && echo '（已停，符合预期）' || echo '⚠️ 又跑起来了')"
echo ""

# ---------- ④ 结论 ----------
echo "━━ 结论 ━━"
if [ "$D_LINES" -ge 3 ] && [ "$NEW" = "401" ] && [ "$MW" -ge 1 ] && [ "$RELAY" = "active" ]; then
  echo "  ✅ 新壳稳定一周 + 记忆验收达标 → 可以执行卸载旧壳（要人拍板）"
  echo ""
  echo "  下一步（需 456 批准，脚本不自动做）："
  echo "    1. 备份旧壳数据到 CT106:  ~/backups/oldshell-20261106/"
  echo "    2. adb shell su -c 'pm uninstall com.deepseek.harness'"
  echo "    3. rm /data/adb/service.d/*.disabled-oldshell-*"
  echo "    4. rm /data/adb/{engine-guard,sentinel-guard,host-guard,tgprobe}.log 的旧壳部分"
elif [ "$NEW" != "401" ] || [ "$MW" -lt 1 ] || [ "$RELAY" != "active" ]; then
  echo "  ⚠️ 新壳有健康问题 —— 先修，别删旧壳（旧壳还是退路）"
else
  echo "  ⚠️ 记忆没长够 —— 先解决记忆触发器，别急着删旧壳"
  echo "     按 docs/memory-design.md 的 B → C → D 方案升级"
fi
