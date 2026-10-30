# >>> CROSS_SHELL_GATE v2 (2026-10-30) — 防止「两个零八」同时在群里说话 >>>
# 起因：tgsend 是 root 侧工具（755），新旧两个壳的 AI 都能执行。
#       迁移切换期间如果两边都能发言，456 分不清哪个是真的零八 ——
#       这比任何技术故障都糟，因为它破坏的是「我在跟谁说话」这件事本身。
#
# ★ v1 用进程树判断，实测失效：`su` 会把自己的父进程改成 magiskd(pid 1001)，
#   原始调用链完全丢掉 —— sh → magiskd → init，追不到引擎。
#
# ★ v2 改用环境变量（实测有效）：
#   从 App 内部（bash 工具）调 su 时，DSH_HOME 等变量会被 su 保留下来：
#     DSH_HOME=/data/user/0/cc.bestly.dsh/files/dshhome     ← 新壳
#     DSH_HOME=/data/data/com.deepseek.harness/.../dshhome  ← 旧壳
#   （注：从 adb shell 调 su 时这些变量为空 —— 那属于人工调用，放行是对的）
#
# 活跃壳登记在 /data/adb/.active_shell（内容为包名）。
#   · 文件不存在 → 默认旧壳（最保守，切换前跑的就是旧壳）
#   · 判断不出来源（人工调用）→ 放行
# 退出码 9 = 被门拒绝（区别于 1 网络失败 / 2 凭据失败 / 3 空正文 / 4 超时）
_owner=""
case "${DSH_HOME:-}" in
  *cc.bestly.dsh*)        _owner="cc.bestly.dsh" ;;
  *com.deepseek.harness*) _owner="com.deepseek.harness" ;;
esac
if [ -z "$_owner" ]; then
  case "${DSH_WEB_URL:-}" in
    *cc.bestly.dsh*|*:13081*)                 _owner="cc.bestly.dsh" ;;
    *com.deepseek.harness*|*:3080*)           _owner="com.deepseek.harness" ;;
  esac
fi
if [ -z "$_owner" ]; then
  case "${DSH_PROFILE_DIR:-}" in
    *cc.bestly.dsh*)        _owner="cc.bestly.dsh" ;;
    *com.deepseek.harness*) _owner="com.deepseek.harness" ;;
  esac
fi
_active=$(cat /data/adb/.active_shell 2>/dev/null | tr -d '\r\n ')
[ -n "$_active" ] || _active="com.deepseek.harness"
if [ -n "$_owner" ] && [ "$_owner" != "$_active" ]; then
  echo "tgsend: 拒绝（exit 9）—— 当前活跃壳是 $_active，本次调用来自 $_owner"
  echo "        切换期间只允许活跃壳发言，防止出现两个零八。"
  echo "        如需切换：echo <包名> > /data/adb/.active_shell"
  exit 9
fi
# <<< CROSS_SHELL_GATE v2 <<<
