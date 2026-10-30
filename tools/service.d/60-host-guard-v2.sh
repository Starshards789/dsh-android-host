#!/system/bin/sh
# 开机自启：新壳引擎外部看护（host-guard v2）
#
# 为什么单独一个文件：service.d 里的脚本由 Magisk 在 late_start 阶段自动执行，
# 崩溃或重启后都会被重新拉起，比手动 nohup 可靠。
#
# 注意：这里必须用 nohup + & 让看护脱离本脚本，否则 Magisk 执行完这个脚本
# 就会连带结束子进程。

GUARD=/data/adb/dsh-tools/host-guard-v2
LOG=/data/adb/host-guard-v2.log

# 等系统起来一点再跑，避免开机瞬间各种服务还没就绪
sleep 30

# 单实例：已有在跑就不重复启动
if [ -f /data/adb/.host-guard-v2.lock ]; then
  opid=$(cat /data/adb/.host-guard-v2.lock 2>/dev/null)
  case "$opid" in
    ''|*[!0-9]*) ;;
    *)
      if kill -0 "$opid" 2>/dev/null; then
        echo "$(date '+%m-%d %H:%M:%S') 开机自启：已有实例 pid=$opid，跳过" >> "$LOG"
        exit 0
      fi
      ;;
  esac
fi

echo "$(date '+%m-%d %H:%M:%S') 开机自启：启动 host-guard v2（间隔 60s）" >> "$LOG"
nohup sh "$GUARD" 60 >> "$LOG" 2>&1 &
