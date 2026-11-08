#!/usr/bin/env bash
# sync-snapshot.sh — 把仓库快照推到 8T，供零八 review
#
# 背景：零八的手机出不了境（无代理），clone 不了 GitHub；只能由 CT106 打包推送。
#       手动 tar + adb push 容易忘（2026-11-08 出现过「快照对不上」），故脚本化。
#
# 用法（在 CT106 上跑，需 adb 已连接 8T）：
#   ./scripts/sync-snapshot.sh                    # 默认 serial
#   ./scripts/sync-snapshot.sh 100.x.x.x:5555     # 指定 serial
#
# 产物：
#   /sdcard/DSH-Workspace/dsh-repo-latest/        解压后的快照（固定路径，永远最新）
#   /sdcard/DSH-Workspace/dsh-snapshot-<TS>.tar.gz  带时间戳的包（留几个可回溯）
#
set -euo pipefail

SERIAL="${1:-100.100.161.114:5555}"
SRC="$(cd "$(dirname "$0")/.." && pwd)"
TS="$(date +%Y%m%d-%H%M)"
PKG="dsh-snapshot-$TS.tar.gz"
REMOTE_DIR="/sdcard/DSH-Workspace"
LATEST="$REMOTE_DIR/dsh-repo-latest"

cd "$SRC"

# 1) 状态检查
HEAD="$(git rev-parse --short HEAD)"
BRANCH="$(git rev-parse --abbrev-ref HEAD)"
DIRTY="$(git status --porcelain | head -5)"

echo "仓库：$SRC"
echo "分支：$BRANCH @ $HEAD"

if [ -n "$DIRTY" ]; then
  echo ""
  echo "⚠️  工作区有未提交改动 —— 快照会包含它们，但 git 里没有："
  echo "$DIRTY"
  echo ""
fi

# 2) 连设备
adb connect "$SERIAL" >/dev/null 2>&1 || true
STATE="$(adb -s "$SERIAL" get-state 2>/dev/null || echo "offline")"
if [ "$STATE" != "device" ]; then
  echo "❌ 设备不可用：$SERIAL ($STATE)"
  echo "   先确认：adb connect $SERIAL && adb devices"
  exit 1
fi
echo "设备：$SERIAL ($STATE)"

# 3) 打包（排除 .git 与依赖目录）
tar czf "/tmp/$PKG" \
  --exclude=.git \
  --exclude=node_modules \
  --exclude='*.apk' \
  -C "$SRC" .
SIZE="$(stat -c %s "/tmp/$PKG" 2>/dev/null || stat -f %z "/tmp/$PKG")"
echo "打包：/tmp/$PKG ($SIZE B)"

# 4) 推送 + 解压到固定路径
adb -s "$SERIAL" push "/tmp/$PKG" "$REMOTE_DIR/$PKG" >/dev/null
adb -s "$SERIAL" shell "
  cd $REMOTE_DIR
  rm -rf dsh-repo-latest.tmp
  mkdir -p dsh-repo-latest.tmp
  tar xzf $PKG -C dsh-repo-latest.tmp
  rm -rf dsh-repo-latest
  mv dsh-repo-latest.tmp dsh-repo-latest
"

# 5) 核对
MD_COUNT="$(adb -s "$SERIAL" shell "find $LATEST -name '*.md' | wc -l" | tr -d '\r')"
echo ""
echo "✅ 完成"
echo "   HEAD     $HEAD"
echo "   快照     $LATEST  ($MD_COUNT 个 .md)"
echo "   包       $REMOTE_DIR/$PKG"
echo ""
echo "提醒：告诉零八「快照在 $LATEST，对应 $HEAD」即可，不用报版本号。"

# 6) 清理旧包（只留最近 5 个）
adb -s "$SERIAL" shell "
  cd $REMOTE_DIR
  ls -1t dsh-snapshot-*.tar.gz 2>/dev/null | tail -n +6 | while read f; do rm -f \"\$f\"; done
" || true
