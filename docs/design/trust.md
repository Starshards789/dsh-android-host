# 信任根（P2「能锁」）

## 为什么需要它

npm 自带的 `integrity`（sha512）能证明「下载的 tarball 是 registry 说的那个」，
但它的信任根是 registry 本身。一旦 registry 被劫持、镜像被投毒、或者 DNS 被劫持，
这层校验就形同虚设。

这里建立一条**属于我们自己的信任链**：

```
私钥（只在本机 / Vaultwarden，永不进设备、永不进仓库）
  └─ 签 dsh-manifest.json（版本 + 关键文件哈希 + patch 记录）
       └─ 公钥（部署到设备 /data/adb/dsh-tools/dsh-trust.pub）验签
```

## 关键设计

| 决定 | 理由 |
|---|---|
| **Ed25519** | node v26 原生支持，无需第三方库；签名只有 64 字节 |
| **只哈希关键文件** | 458M / 44507 文件全扫太慢；真正决定「这是什么版本」的是 `package.json` 与入口文件 |
| **patch 单独列账** | Android 适配 patch 会改变文件哈希，必须记为「受信任的修改」而非「篡改」 |
| **签名前 JSON 规范化** | 键排序 + 无空白，否则同样内容不同排版就验不过 |
| **验签失败是硬失败** | 不允许「警告后继续」 |
| **私钥绝不上设备** | 签名在本机做，设备只持有公钥（只能验、不能签） |

## 密钥

| 项 | 位置 | 说明 |
|---|---|---|
| 私钥 | `/root/dsh-trust/dsh-trust.key`（600） | **必须存入 Vaultwarden**；丢失即无法再签新版本 |
| 公钥 | `/root/dsh-trust/dsh-trust.pub` | 部署到设备 `/data/adb/dsh-tools/dsh-trust.pub` |
| 公钥指纹 | SHA256 of SPKI DER | `688b1b91f0167bebc0745391667f8b42fcd2d9e90b52c7b65bbf627799e9afa8` |

`.gitignore` 已排除 `*.key`、`dsh-trust/`、`dsh-trust.key`。

## 用法

### 校验（设备上，只需要公钥）

```sh
dsh-upgrade verify                    # 校验当前 dshroot
dsh-upgrade verify <目录>             # 校验指定目录
```

输出示例：

```
✓ 信任链通过
  版本: 0.2.1-rc.2
  签名: Ed25519 验证通过
  关键文件: 3 个哈希一致
  patch: 1 条哈希一致
  签发时间: 2026-10-29T10:23:47.432Z
```

### 补签（开发机上，需要私钥）

**升级后的新版本必然处于「未签名」状态**，因为私钥不在设备上。补签流程：

```sh
# 1) 把关键文件取回本机（App 私有目录 adb pull 读不了，必须用 su cat）
P=/data/user/0/cc.bestly.dsh/files/payload
S=/tmp/trust-stage
mkdir -p $S/lib/node_modules/@deepseek-ai/dsh/lib
mkdir -p $S/lib/node_modules/@deepseek-ai/dsh/node_modules/node-addon-require-builtin/lib

adb shell "su -c 'cat $P/dshroot/lib/node_modules/@deepseek-ai/dsh/package.json'" \
  > $S/lib/node_modules/@deepseek-ai/dsh/package.json
adb shell "su -c 'cat $P/dshroot/lib/node_modules/@deepseek-ai/dsh/lib/bin.js'" \
  > $S/lib/node_modules/@deepseek-ai/dsh/lib/bin.js
adb shell "su -c 'cat $P/dshroot/lib/node_modules/@deepseek-ai/dsh/lib/profile-boot.js'" \
  > $S/lib/node_modules/@deepseek-ai/dsh/lib/profile-boot.js
adb shell "su -c 'cat $P/dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/node-addon-require-builtin/lib/index.js'" \
  > $S/lib/node_modules/@deepseek-ai/dsh/node_modules/node-addon-require-builtin/lib/index.js

# 2) 本机签名
node tools/dsh-trust.js sign $S <版本> --key /root/dsh-trust/dsh-trust.key

# 3) 推回设备
adb push $S/dsh-manifest.json /sdcard/dsh-manifest.json
adb push $S/dsh-manifest.sig  /sdcard/dsh-manifest.sig
adb shell "su -c 'cp /sdcard/dsh-manifest.json /sdcard/dsh-manifest.sig \
  $P/dshroot/ && chown u0_a293:u0_a293 $P/dshroot/dsh-manifest* \
  && chcon u:object_r:app_data_file:s0:c37,c257,c512,c768 $P/dshroot/dsh-manifest*'"

# 4) 设备上确认
dsh-upgrade verify
```

## 已验证的攻击场景（2026-10-29）

| 场景 | 做法 | 结果 |
|---|---|---|
| 篡改关键文件 | 往 `bin.js` 追加一行 | ✗ 报「被改」并列出期望/实际哈希 |
| 伪造清单版本号 | `sed` 把 `rc.2` 改成 `rc.9` | ✗ 报「验签失败」 |
| 恢复后 | 还原两个文件 | ✓ 信任链重新通过，引擎无影响 |

## 已知边界

- **不保护整棵树**：只校验 3 个关键文件 + 1 个 patch。理论上攻击者可以改其他
  依赖包而不被发现。**取舍**：全树校验要扫 44507 个文件，代价过高。真正需要时
  可以扩展 `KEY_FILES` 列表。
- **不防内存攻击**：运行中的进程被注入不在保护范围内。
- **私钥丢失 = 无法再签**：所以必须存 Vaultwarden。丢了只能重新生成密钥对并
  重新部署公钥（旧的签名全部作废）。
