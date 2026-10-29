# dsh-android-host

Android 宿主 App for [dsh](https://github.com/deepseek-ai/dsh) —— 给自建 AI 系统一个自己的手机身体。

**Kotlin + Jetpack Compose** · 包名 `cc.bestly.dsh` · minSdk 24 / targetSdk 28

---

## 现在是什么状态

**P1.2 完成** —— App 已经能自己把 dsh 引擎拉起来并对外提供服务。

```
App 启动 → LaunchedEffect → EngineLauncher.start()
  → probePorts() 选一个真能 bind 的端口
  → ProcessBuilder(payload/runtime/bin/node
                   --expose-internals dsh/lib/bin.js web ...)
  → 显式构造 6 个环境变量，并剥离子进程里所有密钥类变量
  → 从 stdout 抓 http://127.0.0.1:<port>/?token=<token>
  → 写 files/runtime/{port, token, pid}
```

**App 用自己的 uid 跑引擎，不需要 root。** 和社区壳的做法同构，但引擎内核一行没改。

### 实测（Android 14 / arm64）

| 项 | 值 |
|---|---|
| 引擎进程 | `node --expose-internals .../dsh/lib/bin.js web --host 127.0.0.1 --no-open --port <p>` |
| 内存 | ≈ 330 MB |
| 端口 | 写入 `runtime/port`，LISTEN 确认 |
| 鉴权 | 无 token → `401`，带 token → `303` |
| 启动到可读状态文件 | < 10 秒 |

---

## ⚠️ 为什么 targetSdk 必须是 28

本项目最反直觉的一条约束，来自 `/system/etc/selinux/plat_seapp_contexts`：

```
minTargetSdkVersion=34 → domain=untrusted_app      levelFrom=all
minTargetSdkVersion=29 → domain=untrusted_app_29   levelFrom=all
minTargetSdkVersion=28 → domain=untrusted_app_27   levelFrom=all    ← 只有这条两边都满足
minTargetSdkVersion=26 → domain=untrusted_app_27   levelFrom=user
```

**28 是唯一同时满足两件事的取值：**

- `untrusted_app_27` 兼容域 → 允许从 App 私有目录 `exec` 打包进来的 node 二进制
- `levelFrom=all` → 拿到完整 MCS category，允许 bind TCP 端口

取 27：能 exec，但 category 不全、绑不了端口（Node 报 `EACCES`，很像权限问题）。
取 29+：能绑端口，但失去 exec 权限（`error=13, Permission denied`）。

**改这个值之前先读这一段。**

## 另外两条不能忘

- **`INTERNET` 权限必须有。** 缺了它内核直接拒绝 `socket()`：Kotlin 侧报 `EPERM`，Node 侧报 `EACCES` —— 两个错误都长得像 SELinux 问题，很容易查错方向。
- **签名必须显式固定**（`signingConfigs` 里指定 `storeFile`/`keyAlias`/密码）。只把 keystore 放到 `~/.android/debug.keystore` 不算数：workflow 里的指纹可能完全正确，而 APK 里装的却是另一个证书。用 `apksigner --print-certs` 验产物。

---

## 构建

推到 `main` 自动构建，或手动触发 Actions。APK 作为 artifact 上传（保留 30 天）。

本地构建需要 Android SDK + Gradle 8.9 + JDK 17。

## 路线

| 阶段 | 内容 | 状态 |
|---|---|---|
| P1.0 | 构建链（GitHub Actions → 可安装 APK） | ✅ 完成 |
| P1.1 | 宿主骨架：fork 引擎 + 环境变量 + 稳定状态文件 | ✅ 完成 |
| P1.2 | 端口探测 + 就绪判定（放弃 IPC，理由见下） | ✅ 完成 |
| P1.3 | 内置 WebView，App 内直接对话 | 进行中 |
| P2 | 升级闸门：清单签名 + 白名单 + 回滚 | 待办 |
| P3 | 保活：前台服务 + 开机自启 + 冻结检测 | 待办 |
| P4 | 插件体系 | 待办 |

### 为什么 P1.2 没做 Unix socket IPC

官方文档说「Node IPC 承载启动注入、就绪、致命错误与关闭」，但**该协议的实现位于 `dsh-desktop-host` 包**，而 App 侧使用的 runtime payload 里并不包含这个包。`dsh web` 的 CLI 也没有传入 token 的参数 —— token 由内核自动生成，**stdout 是官方唯一对外暴露的通道**。

所以改为：端口先探测再指定、`--no-open` 消除噪音、就绪判定附加一次探测（但探测仅作诊断，不作判定依据）。

## 设计原则

- **官方内核零改动**：dsh 只当依赖，不打补丁，定制全部走插件。
- **四层独立更新**：Node 运行时 / dsh 内核 / 插件 / 数据记忆。
- **引擎绝不打进 APK**：否则每次升级 dsh 都要重装 App。
- **探针坏了不许连累主流程**：引擎能打印带 token 的 URL 本身就是就绪信号。

## 许可

MIT
