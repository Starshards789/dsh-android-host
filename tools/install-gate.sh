#!/system/bin/sh
# 给 tgsend 插入「换壳门」——只插入，不重写原文件
#
# 教训：tgsend 的 Authorization 行在对话里显示为 `Bearer ***`
#       （凭据在显示层被脱敏），整份 write_file 会把 *** 字面写进去、毁掉凭据。
#       所以只能用「在固定锚点后插入一段」的方式，绝不重写已有内容。
set -e

T=/data/adb/dsh-tools/tgsend
TS=$(date +%Y%m%d-%H%M%S)
GATE=/data/local/tmp/tgsend-gate.sh

# 幂等：已经有门就退出
if grep -q 'CROSS_SHELL_GATE' "$T" 2>/dev/null; then
  echo "已经装过门，跳过"
  exit 0
fi

cp -a "$T" "/data/adb/dsh-tools/tgsend.bak-$TS"
echo "备份: /data/adb/dsh-tools/tgsend.bak-$TS ($(wc -c < "$T") 字节)"

# 用 awk 在锚点行后插入门代码，其余行原样输出（$0 不做任何转义处理）
awk -v gatefile="$GATE" '
  { print }
  /^unset LD_LIBRARY_PATH LD_PRELOAD LD_DEBUG$/ && !done {
    print ""
    while ((getline line < gatefile) > 0) print line
    close(gatefile)
    done = 1
  }
' "$T" > /data/local/tmp/tgsend.new

# 校验：新文件必须比旧文件长、且包含门和原始关键行
NEW=/data/local/tmp/tgsend.new
OLD_SIZE=$(wc -c < "$T")
NEW_SIZE=$(wc -c < "$NEW")
echo "大小: $OLD_SIZE → $NEW_SIZE 字节"

if ! grep -q 'CROSS_SHELL_GATE' "$NEW"; then echo "✗ 门没插进去"; exit 1; fi
if ! grep -q 'Authorization: Bearer' "$NEW"; then echo "✗ Authorization 行丢了"; exit 1; fi
if ! grep -q 'pw get' "$NEW"; then echo "✗ 凭据获取行丢了"; exit 1; fi
if ! grep -q 'curl: (28)' "$NEW"; then echo "✗ 超时分支丢了"; exit 1; fi
echo "✓ 完整性校验通过（门 + Authorization + 凭据获取 + 超时分支都在）"

chmod 755 "$NEW"
mv "$NEW" "$T"
echo "✓ 已替换"
ls -la "$T"
