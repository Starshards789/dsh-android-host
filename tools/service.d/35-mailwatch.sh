#!/system/bin/sh
unset LD_LIBRARY_PATH LD_PRELOAD LD_DEBUG 2>/dev/null
# 35-mailwatch.sh | zero-one v5 (2026-10-30) | 零八收件箱值守 —— 新壳版
#
# v5 变更（换壳配套，2026-10-30）：
#   旧壳 com.deepseek.harness:3080  →  新壳 cc.bestly.dsh（端口/地址动态读 runtime/）
#   token 来源：旧壳要 grep dsh-web.log 的 ?token=（日志里有明文）
#              新壳直接读 files/runtime/token（引擎写好的，更可靠）
#
# ★ 不硬编码端口和地址：一律读新壳的 runtime/{host,port,token}
#   原因：新壳引擎僵死重启时会避让到下一个端口（实测 13081 → 13082），
#        写死端口会在那种时刻静默失效 —— 而且不会有任何报错，
#        只表现为「信箱永远提示没新信」。
#
# 自检：sh /data/adb/service.d/35-mailwatch.sh once
PATH=/system/bin:/system/xbin:$PATH
LOG=/data/adb/mailwatch.log
SIDF=/data/adb/dsh-tools/.mail_session
RT=/data/user/0/cc.bestly.dsh/files/runtime
JAR=/data/local/tmp/.mw.jar

push_once() {
  [ -f "$SIDF" ] || { echo "$(date +%F' '%T) no session id" >> $LOG; return 0; }

  sh /data/adb/dsh-tools/mb check >/dev/null 2>&1
  RC=$?
  [ "$RC" -eq 0 ] || {
    if [ "$RC" -eq 2 ]; then echo "$(date +%F' '%T) mailbox UNREACHABLE (rc=2), cannot verify" >> $LOG
    else echo "$(date +%F' '%T) no new mail, skip" >> $LOG; fi
    return 0
  }

  # 引擎运行态一律从 runtime/ 读，不猜
  T=$(cat $RT/token 2>/dev/null | tr -d '\r\n')
  P=$(cat $RT/port  2>/dev/null | tr -d '\r\n')
  H=$(cat $RT/host  2>/dev/null | tr -d '\r\n')
  [ -n "$H" ] || H=127.0.0.1
  [ -n "$P" ] || P=13081
  [ -n "$T" ] || { echo "$(date +%F' '%T) token unavailable (engine down?), retry next cycle" >> $LOG; return 0; }

  # 用 token 换 cookie（新壳引擎要求 303 + Set-Cookie）
  C=$(curl -s -m 10 -c $JAR -o /dev/null -w '%{http_code}' "http://$H:$P/?token=$T")
  [ "$C" = "303" ] || { echo "$(date +%F' '%T) cookie fail (http=$C host=$H port=$P), retry next cycle" >> $LOG; rm -f $JAR; return 0; }

  SID=$(cat $SIDF)
  RID="mw-$(date +%s)"
  BODY="{\"type\":\"client-request\",\"rpcId\":\"$RID\",\"method\":\"session/prompt\",\"payload\":{\"args\":{\"request\":{\"requestId\":\"$RID\",\"sessionId\":\"$SID\",\"mode\":\"queue\",\"content\":[{\"type\":\"text\",\"text\":\"【信箱 · 值守提醒】你的收件箱有新来信待取。请取信（su -c \\\"sh /data/adb/dsh-tools/mb\\\"）并按约定处理；回信投 inbox-01。\"}]}}}}"

  R=$(curl -s -m 20 -b $JAR -H 'content-type: application/json' -X POST --data-binary "$BODY" "http://$H:$P/api/session/prompt")
  case "$R" in
    *'"accepted":true'*) echo "$(date +%F' '%T) pushed ok (host=$H port=$P)" >> $LOG ;;
    *) echo "$(date +%F' '%T) push fail: $(echo $R | head -c 140)" >> $LOG ;;
  esac
  rm -f $JAR
}

case "$1" in
  once) push_once; echo "rc=$?" >> $LOG; exit 0 ;;
esac

if [ -f /data/adb/.mailwatch.pid ] && kill -0 "$(cat /data/adb/.mailwatch.pid)" 2>/dev/null; then
  exit 0
fi
echo $$ > /data/adb/.mailwatch.pid
echo "$(date +%F' '%T) mailwatch v5 start (pid $$) —— 新壳 cc.bestly.dsh，动态读 runtime/" >> $LOG
while true; do
  push_once
  /data/adb/dsh-tools/zsleep 1800 || sleep 1800
done
