# SELinux：`magisk` 域写 `app_data_file` 不稳定（2026-11-08 实录）

## 现象

用 `adb shell /debug_ramdisk/magisk su -c` 对 `dshhome/memory/`
（`u:object_r:app_data_file:s0:c177,c256,c512,c768`）操作时：

| 操作 | 结果 |
|---|---|
| `ls -laZ <dir>`（第一次） | ✅ 成功 |
| `ls -Zd <dir>`（几秒后） | ❌ `Permission denied` |
| `printf ... > <dir>/config.json` | ❌ `Permission denied` |

**同一个域、同一个目标、几秒之隔，结果不同。**

## 不是 DAC 配置错

- 目标目录属主 `u0_a177:u0_a177`，mode `777`
- 目标目录上下文完整：`app_data_file:s0:c177,c256,c512,c768`
- `/data/adb` 侧上下文正常（`magisk_file`）

**DAC 全开也过不了 MAC** —— 这是 SELinux 层。

## 判断

**策略时序 / AVC 缓存**问题，不是静态规则缺失。`magisk` 域对 `app_data_file` 的
**写**权限不在 allow 规则里；**读**可能靠缓存窗口偶然通过（所以第一次成功、第二次失败）。

## 绕法（已验证）

**不要在 root 域写 App 私有目录。** 让 App 身份自己写：

```bash
# 以 App 身份执行（引擎工具 / 零八自己 / run-as 可用的 debuggable 包）
cp /sdcard/DSH-Workspace/memory-config.json "$DSH_HOME/memory/config.json"
chmod 600 "$DSH_HOME/memory/config.json"
```

**或者**：写到 `/sdcard` 中转，让 App 自己搬过去。

## 三条判据

1. **`ls` 成功 ≠ `write` 成功** —— SELinux 的 read / write / search 是**分开授权**的
2. **同一操作重复结果不同** → 先怀疑**策略时序或 AVC 缓存**，不要先怀疑 DAC
3. **`su <uid>` 只换 uid，不换 SELinux 域** → 不能用来「以 App 身份」操作；
   `su 10177 -c` 仍是 `magisk` 域，照样被拒

## 反例（当天踩的）

```bash
# ❌ 期望：root 能写 App 目录
$ adb shell /debug_ramdisk/magisk su -c 'echo x > /data/data/cc.bestly.dsh/files/dshhome/memory/config.json'
/system/bin/sh: can't create ...: Permission denied

# ❌ 期望：切 uid 就行
$ adb shell /debug_ramdisk/magisk su 10177 -c 'cd /sdcard/DSH-Workspace/scripts'
/system/bin/sh: cd: /sdcard/DSH-Workspace/scripts: Permission denied
# 原因：域还是 magisk，不是 untrusted_app
```

## 相关

- 记忆系统配置注入：`scripts/memory/README.md`「OpenViking 地址（环境变量）」
- App 私有目录读写策略：`docs/reference/8t-current-baseline.md` 第 6 节
