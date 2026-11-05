#!/usr/bin/env bash
# Android payload 适配补丁重打脚本
#
# 用途：每次更换 dsh payload（升级官方 APK 后重新解出 assets/payload.zip）都要跑一遍，
#       否则引擎会在启动期崩。补丁原理见 docs/patches/ANDROID-PAYLOAD-PATCHES.md。
#
# 用法：
#   ADB_SERIAL=100.102.144.35:5555 ./scripts/apply-android-patches.sh
#   ./scripts/apply-android-patches.sh --dry-run
#   ./scripts/apply-android-patches.sh --payload /data/data/cc.bestly.dsh/files/payload
#
# 前置：手机已 root（Magisk），adb 已连接，payload 已解压到位。
#
# 注意：所有 shell 命令经 `adb shell "su -c '...'"` 下发，
#       因此命令串里**不能出现单引号**（路径按无空格处理）。

set -euo pipefail

ADB_SERIAL="${ADB_SERIAL:-100.102.144.35:5555}"
PKG="${PKG:-cc.bestly.dsh}"
PAYLOAD="${PAYLOAD:-/data/data/${PKG}/files/payload}"
DRY_RUN=0

while [ $# -gt 0 ]; do
  case "$1" in
    --payload) PAYLOAD="$2"; shift 2 ;;
    --serial)  ADB_SERIAL="$2"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    -h|--help) sed -n '2,18p' "$0"; exit 0 ;;
    *) echo "未知参数: $1" >&2; exit 2 ;;
  esac
done

ADB=(adb -s "$ADB_SERIAL")
say() { printf '%s\n' "$*"; }

# 在手机 root 身份下执行（命令串不能含单引号）
run_root() { "${ADB[@]}" shell "su -c '$*'" 2>/dev/null | tr -d '\r'; }

say "== payload: $PAYLOAD"
if [ "$(run_root "[ -d $PAYLOAD ] && echo yes")" != "yes" ]; then
  say "!! payload 目录不存在（或 root 不可用），先解压 payload.zip"; exit 1
fi

UID_GID="$(run_root "stat -c %u:%g $PAYLOAD")"
say "== payload 属主: $UID_GID"

NM="$PAYLOAD/dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules"

backup_once() {
  local f="$1"
  if [ "$(run_root "[ -e ${f}.bak-android-patch ] && echo yes")" != "yes" ]; then
    if [ "$DRY_RUN" = 1 ]; then say "   [dry] 备份 $f"; else
      run_root "cp -p $f ${f}.bak-android-patch"
      say "   备份 → ${f##*/}.bak-android-patch"
    fi
  fi
}

fix_meta() {
  local f="$1"
  if [ "$DRY_RUN" = 1 ]; then say "   [dry] 修属主/context $f"; return; fi
  local ctx
  ctx="$(run_root "ls -Z $f" | awk '{print $1}')"
  run_root "chown $UID_GID $f"
  [ -n "$ctx" ] && run_root "chcon $ctx $f"
  return 0
}

# ---------------------------------------------------------------- 补丁 1
STARTUP="$NM/@deepseek-ai/dsh-web-app/lib/startup.js"
say ""
say "== [1/3] startup.js：解除 --host 0.0.0.0 封锁"
if [ "$(run_root "[ -f $STARTUP ] && echo yes")" = "yes" ]; then
  if [ "$(run_root "grep -q patch-android $STARTUP && echo patched")" = "patched" ]; then
    say "   已打过，跳过"
  else
    backup_once "$STARTUP"
    if [ "$DRY_RUN" = 1 ]; then
      say "   [dry] 注释掉 options.host === 0.0.0.0 那一行"
    else
      # v1.14.0 写法：拦死 0.0.0.0
      run_root "sed -i s|^\\\\(.*options\\\\.host.*0\\\\.0\\\\.0\\\\.0.*\\\\)\\$|// [patch-android] \\\\1| $STARTUP"
      # v1.20.1+ 写法：isWildcardHost(options.host) —— 仍拦 0.0.0.0，但放行了具体 IP
      run_root "sed -i s|^\\\\(.*isWildcardHost(options\\\\.host).*\\\\)\\$|// [patch-android] \\\\1| $STARTUP"
      fix_meta "$STARTUP"
      if [ "$(run_root "grep -q patch-android $STARTUP && echo patched")" = "patched" ]; then
        say "   OK"
      else
        say "   !! 未匹配到目标行，请手工检查 $STARTUP"; exit 1
      fi
    fi
  fi
else
  say "   !! 找不到 $STARTUP（payload 结构变了？）"
fi

# ---------------------------------------------------------------- 补丁 2
KOFFI_DIR="$NM/koffi"
say ""
say "== [2/3] koffi 入口：转发到 dsh 自带 stub"
if [ "$(run_root "[ -d $KOFFI_DIR ] && echo yes")" = "yes" ]; then
  if [ "$(run_root "grep -q koffi-stub $KOFFI_DIR/index.cjs && echo patched")" = "patched" ]; then
    say "   已打过，跳过"
  else
    backup_once "$KOFFI_DIR/index.cjs"
    backup_once "$KOFFI_DIR/index.js"
    if [ "$DRY_RUN" = 1 ]; then
      say "   [dry] 写入 index.cjs / index.js 转发"
    else
      run_root "printf '%s\\\\n' '// [patch-android] payload 不带 .node，转发到 dsh 自带占位实现' 'module.exports = require(\"../@deepseek-ai/dsh-subprocess-local/lib/koffi-stub.cjs\");' > $KOFFI_DIR/index.cjs"
      run_root "printf '%s\\\\n' '// [patch-android] 同上，ESM 入口' 'import stub from \"../@deepseek-ai/dsh-subprocess-local/lib/koffi-stub.cjs\";' 'export default stub;' > $KOFFI_DIR/index.js"
      fix_meta "$KOFFI_DIR/index.cjs"; fix_meta "$KOFFI_DIR/index.js"
      say "   OK"
    fi
  fi
else
  say "   !! 找不到 $KOFFI_DIR"
fi

# ---------------------------------------------------------------- 补丁 3
STUB="$NM/@deepseek-ai/dsh-subprocess-local/lib/koffi-stub.cjs"
say ""
say "== [3/3] koffi-stub：补 ABI size（win32-process 加载期校验）"
if [ "$(run_root "[ -f $STUB ] && echo yes")" = "yes" ]; then
  if [ "$(run_root "grep -q DSH_STARTUPINFOW $STUB && echo patched")" = "patched" ]; then
    say "   已打过，跳过"
  else
    SRC="$(cd "$(dirname "$0")/.." && pwd)/docs/patches/koffi-stub.android.cjs"
    if [ ! -f "$SRC" ]; then say "   !! 找不到 $SRC"; exit 1; fi
    if [ "$DRY_RUN" = 1 ]; then
      say "   [dry] 覆盖 $STUB ← docs/patches/koffi-stub.android.cjs"
    else
      backup_once "$STUB"
      "${ADB[@]}" push "$SRC" /data/local/tmp/koffi-stub.android.cjs >/dev/null
      run_root "cp /data/local/tmp/koffi-stub.android.cjs $STUB"
      fix_meta "$STUB"
      say "   OK（已覆盖并修属主）"
    fi
  fi
else
  say "   !! 找不到 $STUB"
fi

say ""
say "== 完成。重启引擎使其生效："
say "   ${ADB[*]} shell am force-stop $PKG"
say "   ${ADB[*]} shell am start -n $PKG/.MainActivity"
