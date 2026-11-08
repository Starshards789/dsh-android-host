#!/system/bin/sh
# mem-collect-boot.sh — 开机跑一次采集器（放 /data/adb/service.d/，由 Magisk 开机以 root 执行）
# 目的：补上「重启后的冷启动」这一层，不依赖 agent 自觉
LOG=/storage/emulated/0/DSH-Workspace/memory-organize.log
N=/data/user/0/cc.bestly.dsh/files/payload/runtime/bin/node
LIB=/data/user/0/cc.bestly.dsh/files/payload/runtime/lib
S=/storage/emulated/0/DSH-Workspace/scripts/mem-collect.js

# 等 sdcard 可用（开机早期可能未挂载），最多 60 秒
i=0
while [ $i -lt 60 ]; do
  [ -d /storage/emulated/0/DSH-Workspace ] && break
  sleep 1; i=$((i+1))
done

# 单实例锁：防开机与 schedule 撞车（mkdir 是原子的，不依赖 flock）
LOCK=/data/local/tmp/mem-collect.lockd
mkdir "$LOCK" 2>/dev/null || { echo "$(date '+%F %T') boot-collect: another instance running, skip" >> "$LOG"; exit 0; }
trap 'rmdir "$LOCK"' EXIT

if [ ! -x "$N" ]; then echo "$(date '+%F %T') boot-collect: node missing" >> "$LOG"; exit 0; fi
if [ ! -f "$S" ]; then echo "$(date '+%F %T') boot-collect: script missing" >> "$LOG"; exit 0; fi

echo "$(date '+%F %T') boot-collect start" >> "$LOG"
LD_LIBRARY_PATH="$LIB" "$N" "$S" >> "$LOG" 2>&1
rc=$?
echo "$(date '+%F %T') boot-collect done rc=$rc" >> "$LOG"
exit 0
