# 第三方组件与设计来源声明

本仓库整体采用 **MIT 许可**（见 [`LICENSE`](LICENSE)），版权归项目所有者与贡献者。
本文件列出的第三方组件、架构借鉴与设计调研，按各自许可与归属分别说明。

分三类，**请分别对待**：

- **A 类：随 APK 分发** —— 有许可义务，必须附许可文本
- **B 类：架构借鉴** —— 未复制代码，但设计受其启发，如实标注
- **C 类：设计调研参考** —— 只读过、讨论过，未使用代码；按项目纪律仍然标注

---

## A. 随 APK 分发的第三方组件

APK 的 `payload/dshroot/lib/node_modules/@deepseek-ai/` 下随包分发以下组件，
版权归 **DeepSeek**，均为 **MIT**：

| 包 | 版本 | 许可 | 包内是否有许可文本 |
|---|---|---|---|
| `@deepseek-ai/dsh` | 0.2.1.2 | MIT | ✅ 有 `LICENSE` |
| `@deepseek-ai/dsh-tool-accessibility` | 0.1.0 | MIT | ⚠️ 仅 `package.json` 字段，需在 App 内附文本 |
| `@deepseek-ai/dsh-tool-android` | 0.1.0 | MIT | ⚠️ 同上 |
| `@deepseek-ai/dsh-tool-shizuku` | 0.1.0 | MIT | ⚠️ 同上 |
| `@deepseek-ai/dsh-tool-vscreen` | 0.1.0 | MIT | ⚠️ 同上 |

**待办**：把上述四个插件的 MIT 文本放进 App 的「关于 → 开源许可」页
（或 `app/src/main/assets/licenses/`），以满足 MIT「许可声明须随所有副本」的要求。

许可文本副本见 [`licenses/MIT-deepseek-harness.txt`](licenses/MIT-deepseek-harness.txt)。

---

## B. 架构借鉴（未复制代码）

**deepseek-harness-android-app** — 作者 [woaiys3](https://github.com/woaiys3)

- 仓库：`https://github.com/woaiys3/deepseek-harness-android-app`（原仓库已不可访问，
  现存镜像：`https://github.com/evgenmay1978-del/deepseek-harness-android-app`）
- 许可：**MIT**，Copyright (c) 2026 woaiys3
- 借鉴内容：宿主层结构（App 壳 + 前台服务 + WebView）、payload 打包与解包方式、
  权限引导流程、引擎以子进程 fork 的模式、"数据与运行时分离"的目录布局
- 代码使用情况：**未复制**。2026-11-06 审计：本项目 7 个 Kotlin 文件（2,476 行）
  与该仓库 13 个 Java 文件做行级比对，相似度 4.6%–17.4%（均为 Android 通用样板）；
  最长连续相同代码块 < 6 行，无连续复制片段。实现为独立重写。

许可文本副本见 [`licenses/MIT-woaiys3.txt`](licenses/MIT-woaiys3.txt)。

---

## C. 设计调研参考（未使用代码）

以下项目在设计与选型阶段被阅读、讨论、对照过，**未复制任何代码**。
按本项目纪律（凡借鉴学习即标注），逐项列出。

### 手机端 AI Agent

| 项目 | 许可 | 参考内容 |
|---|---|---|
| [AAswordman/Operit](https://github.com/AAswordman/Operit) | LGPL-3.0 | 虚拟屏模块 `shower` 的进程分离与 Binder 架构；工具生态（`core/tools`）的组织方式；悬浮球与保活设计 |
| [AAswordman/Operit2](https://github.com/AAswordman/Operit2) | 见仓库 | 跨平台运行时（Rust + Flutter）的分层思路 |
| [guzhou079-arch/deepseek-harness-android](https://github.com/guzhou079-arch/deepseek-harness-android) | MIT（vscreen 部分 LGPL-3.0） | 离屏虚拟屏实现（`ImageReader` 直接作 Surface）、`LocalHttpFence` 本地鉴权、`Workarounds` 兼容补丁 |
| [guzhou079-arch/dsh-plugin-android-vscreen](https://github.com/guzhou079-arch/dsh-plugin-android-vscreen) | LGPL-3.0 | 通过 adb 驱动虚拟屏的插件形态 |

### 电脑 / 云驱动手机

| 项目 | 许可 | 参考内容 |
|---|---|---|
| [droidrun/mobilerun](https://github.com/droidrun/mobilerun) | MIT | 手机 agent 的工具抽象与 UI 状态表示 |
| [X-PLUG/MobileAgent](https://github.com/X-PLUG/MobileAgent) | MIT | GUI agent 的截图→理解→动作循环 |
| [zai-org/Open-AutoGLM](https://github.com/zai-org/Open-AutoGLM) | Apache-2.0 | 手机 agent 的部署形态与 ADB 交互方式 |

### Termux / Android 适配

| 项目 | 许可 | 参考内容 |
|---|---|---|
| [Vengisk/deepseek-harness-termux](https://github.com/Vengisk/deepseek-harness-termux) | MIT | dsh 在 Termux 的 5 处兼容补丁 |
| [lilyco-42/dsh-termux](https://github.com/lilyco-42/dsh-termux) | MIT | 一键安装器与 node-gyp 目标参数 |
| [thness/dsh-mobile](https://github.com/thness/dsh-mobile) | MIT | 内嵌 Node.js 运行时的独立 APK 形态 |
| [FunnelCakes/deepseek-harness-android](https://github.com/FunnelCakes/deepseek-harness-android) | 见仓库 | Termux 一键部署脚本 |
| [dphmoblie/deepseek-harness-android](https://github.com/dphmoblie/deepseek-harness-android) | 见仓库 | DSH + Ubuntu 环境 + Shizuku/无障碍的整合形态 |

### 记忆系统

见 [`docs/research/industry-survey.md`](docs/research/industry-survey.md) 与
[`docs/research/portable-implementations.md`](docs/research/portable-implementations.md)，
涉及 MemGPT/Letta、Mem0、Zep、A-MEM、Cognee 等，均为机制层面调研，未使用代码。

### 虚拟屏底层

| 项目 | 许可 | 参考内容 |
|---|---|---|
| [Genymobile/scrcpy](https://github.com/Genymobile/scrcpy) | Apache-2.0 | `VirtualDisplay` 创建流程、多显示器输入路由、兼容 workaround（`NewDisplay.java`、`NewDisplayCapture.java`） |

---

## 许可文本

| 文件 | 用途 |
|---|---|
| [`LICENSE`](LICENSE) | 本项目 MIT |
| [`licenses/MIT-deepseek-harness.txt`](licenses/MIT-deepseek-harness.txt) | 随包分发的 DeepSeek 组件 |
| [`licenses/MIT-woaiys3.txt`](licenses/MIT-woaiys3.txt) | 架构借鉴的上游壳 |

Apache-2.0 与 LGPL-3.0 全文暂未附带——本项目目前**未使用**这两类许可的代码。
若将来引入（例如直接移植 scrcpy 或 Operit 的代码），必须同时补全文并更新本文件。

---

## 维护纪律

1. 新增任何第三方代码或设计借鉴，**同一次提交**内更新本文件与
   [`docs/oss-reuse-log.md`](docs/oss-reuse-log.md)
2. 直接复制的代码，源文件头必须带来源与许可注释
3. 不确定来源的代码不合并；宁可重写
