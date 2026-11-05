#!/usr/bin/env bash
# Android payload 适配补丁重打脚本（dsh 0.2.x）
#
# 用途：每次更换 dsh payload（升级官方 APK 后重新解出 assets/payload.zip）都要跑一遍，
#       否则引擎会在启动期崩，或者日志里一直刷插件加载失败。
#       补丁原理见 docs/patches/ANDROID-PAYLOAD-PATCHES.md。
#
# 用法：
#   ADB_SERIAL=100.102.144.35:5555 ./scripts/apply-android-patches.sh
#   ./scripts/apply-android-patches.sh --dry-run
#   ./scripts/apply-android-patches.sh --payload /data/data/cc.bestly.dsh/files/payload
#
# 前置：手机已 root（Magisk），adb 已连接，payload 已解压到位。
#
# 设计取舍：文本补丁一律走「pull → 主机 python 改 → push → chown/chcon」，
# 不在 adb shell 里拼 sed。多层 su -c 引号嵌套会把正则吃掉，实测能把脚本卡死，
# 而且失败时静默不报错 —— 这是用一晚上换来的教训。
#
# 补丁清单（dsh 0.2.1-rc.2 实测）：
#   1. dsh-web-app/lib/startup.js              解除 --host 通配地址封锁
#   2. dsh-host-webserver/lib/index.js         解除第二道 isWildcardAddress 封锁
#   3. koffi/index.{cjs,js} → koffi-stub.cjs   payload 不带原生 koffi
#   4. restructure/index.js → dist/main.cjs    payload 漏打包 src/ 目录

set -euo pipefail

ADB_SERIAL="${ADB_SERIAL:-100.102.144.35:5555}"
PKG="${PKG:-cc.bestly.dsh}"
PAYLOAD="${PAYLOAD:-/data/data/${PKG}/files/payload}"
DRY_RUN=0
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

while [ $# -gt 0 ]; do
  case "$1" in
    --payload) PAYLOAD="$2"; shift 2 ;;
    --serial)  ADB_SERIAL="$2"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    -h|--help) sed -n '2,25p' "$0"; exit 0 ;;
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

APP_UID="$(run_root "stat -c %u $PAYLOAD")"
say "== payload 属主: $APP_UID"
# app 私有目录的 SELinux category 跟 uid 绑定（c<uid-10000>），不能写死
CAT="c$((APP_UID - 10000))"
say "== SELinux category: $CAT"

NM="$PAYLOAD/dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

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
  run_root "chown $APP_UID:$APP_UID $f"
  run_root "chcon u:object_r:app_data_file:s0:$CAT,c256,c512,c768 $f"
  return 0
}

# pull → 主机改 → push 回；$1=远端路径 $2=python 脚本
patch_remote_file() {
  local remote="$1" transform="$2" name
  name="$(basename "$remote")"
  if [ "$DRY_RUN" = 1 ]; then say "   [dry] 修改 $remote"; return 0; fi
  run_root "cp $remote /data/local/tmp/$name.before-patch"
  run_root "chmod 666 /data/local/tmp/$name.before-patch"
  "${ADB[@]}" pull "/data/local/tmp/$name.before-patch" "$TMP/$name" >/dev/null 2>&1
  python3 "$transform" "$TMP/$name"
  "${ADB[@]}" push "$TMP/$name" "/data/local/tmp/$name" >/dev/null
  run_root "cp /data/local/tmp/$name $remote"
  fix_meta "$remote"
  say "   OK（$name）"
}

# ---------------------------------------------------------------- 补丁 1+2：host 封锁
say ""
say "== [1/2] 解除 --host 通配地址封锁（startup.js + webserver）"

cat > "$TMP/unlock_host.py" <<'PYEOF'
import sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()
lines = s.split('\n')
hit = 0
for i, ln in enumerate(lines):
    if ln.lstrip().startswith('//'):
        continue
    if 'isWildcardHost(options.host)' in ln or 'isWildcardAddress(parsed)) throw' in ln:
        lines[i] = '// [patch-android] ' + ln.strip()
        hit += 1
open(p, 'w', encoding='utf-8').write('\n'.join(lines))
if hit == 0:
    sys.exit("未匹配到 host 校验行 —— payload 结构变了，请手工检查")
print(f"   注释 {hit} 行")
PYEOF

for f in "$NM/@deepseek-ai/dsh-web-app/lib/startup.js" \
         "$NM/@deepseek-ai/dsh-host-webserver/lib/index.js"; do
  if [ "$(run_root "[ -f $f ] && echo yes")" = "yes" ]; then
    if [ "$(run_root "grep -q patch-android $f && echo patched")" = "patched" ]; then
      say "   已打过，跳过 ${f##*/}"
    else
      backup_once "$f"
      patch_remote_file "$f" "$TMP/unlock_host.py"
    fi
  else
    say "   !! 找不到 $f"
  fi
done

# ---------------------------------------------------------------- 补丁 3：koffi
say ""
say "== [3/4] koffi：payload 不带原生模块，转发到 stub"
KOFFI="$NM/koffi"
if [ "$(run_root "[ -d $KOFFI ] && echo yes")" = "yes" ]; then
  if [ "$(run_root "grep -q koffi-stub $KOFFI/index.cjs && echo patched")" = "patched" ]; then
    say "   已打过，跳过"
  else
    if [ "$DRY_RUN" = 1 ]; then
      say "   [dry] 写入 koffi-stub.cjs + 改两个入口"
    else
      if [ ! -f "$REPO_ROOT/docs/patches/koffi-stub.android.cjs" ]; then
        say "   !! 找不到 docs/patches/koffi-stub.android.cjs"; exit 1
      fi
      backup_once "$KOFFI/index.cjs"
      backup_once "$KOFFI/index.js"
      "${ADB[@]}" push "$REPO_ROOT/docs/patches/koffi-stub.android.cjs" /data/local/tmp/koffi-stub.cjs >/dev/null
      run_root "cp /data/local/tmp/koffi-stub.cjs $KOFFI/koffi-stub.cjs"
      fix_meta "$KOFFI/koffi-stub.cjs"
      printf '%s\n' '// [patch-android] payload 不带 src/koffi，转发到本地 stub' \
                    'module.exports = require("./koffi-stub.cjs");' > "$TMP/koffi-index.cjs"
      printf '%s\n' '// [patch-android] ESM 入口' \
                    'import stub from "./koffi-stub.cjs";' \
                    'export default stub;' > "$TMP/koffi-index.js"
      for f in koffi-index.cjs koffi-index.js; do
        "${ADB[@]}" push "$TMP/$f" /data/local/tmp/$f >/dev/null
        target="$KOFFI/${f#koffi-}"
        run_root "cp /data/local/tmp/$f $target"
        fix_meta "$target"
      done
      say "   OK"
    fi
  fi
else
  say "   !! 找不到 $KOFFI"
fi

# ---------------------------------------------------------------- 补丁 4：restructure
say ""
say "== [4/4] restructure：payload 漏打包 src/，改从 dist/main.cjs 重新导出"
REST="$NM/restructure"
if [ "$(run_root "[ -d $REST ] && echo yes")" = "yes" ]; then
  if [ "$(run_root "grep -q patch-android $REST/index.js && echo patched")" = "patched" ]; then
    say "   已打过，跳过"
  else
    if [ "$DRY_RUN" = 1 ]; then
      say "   [dry] 覆盖 $REST/index.js"
    else
      if [ ! -f "$REPO_ROOT/docs/patches/restructure-index.android.js" ]; then
        say "   !! 找不到 docs/patches/restructure-index.android.js"; exit 1
      fi
      backup_once "$REST/index.js"
      "${ADB[@]}" push "$REPO_ROOT/docs/patches/restructure-index.android.js" /data/local/tmp/restructure-index.js >/dev/null
      run_root "cp /data/local/tmp/restructure-index.js $REST/index.js"
      fix_meta "$REST/index.js"
      say "   OK"
    fi
  fi
else
  say "   !! 找不到 $REST（这个 payload 版本可能不需要该补丁）"
fi

say ""
say "== 完成。重启 App 使补丁生效："
say "   ${ADB[*]} shell am force-stop $PKG"
say "   ${ADB[*]} shell am start -n $PKG/.MainActivity"
