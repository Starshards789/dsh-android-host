# 开源借鉴台账

> 纪律：**凡借鉴或学习，无论对方是否要求，都要记录**。
> 记录内容：读了什么、借鉴了什么、有没有使用代码、日期。
>
> 代码使用一栏的判定标准：
> - **无** = 只读文档/源码，未复制任何片段
> - **思路** = 借鉴架构或算法，代码独立实现
> - **片段** = 复制了具体代码片段（须在源文件头标注来源与许可）
> - **整文件** = 整文件引入（须保留原许可，并在 THIRD_PARTY_NOTICES 说明）

## 2026-11-06 · 仓库审计与虚拟屏选型

| 项目 | 许可 | 读/借鉴内容 | 代码使用 | 备注 |
|---|---|---|---|---|
| [evgenmay1978-del/deepseek-harness-android-app](https://github.com/evgenmay1978-del/deepseek-harness-android-app)（woaiys3 原仓库镜像） | MIT | 宿主层结构、payload 打包、权限引导、引擎 fork 模式；`vscreen/` 离屏实现（`ImageReader` + `createVirtualDisplay`，33,972B） | 思路 | 审计结论：本项目 Kotlin 代码与上游 Java 无连续 ≥6 行相同片段；若后续移植 `vscreen/`，按 MIT 保留版权声明 |
| [guzhou079-arch/deepseek-harness-android](https://github.com/guzhou079-arch/deepseek-harness-android) | MIT（vscreen 部分 LGPL-3.0） | 离屏 vscreen 的完整实现（`Main.java` 40,429B）、`LocalHttpFence` 鉴权、`Workarounds` | 无 | 若采用其 vscreen 部分需按 LGPL-3.0 |
| [AAswordman/Operit](https://github.com/AAswordman/Operit) | LGPL-3.0 | `shower` 虚拟屏模块（`ShowerController` / `ShowerServerManager` / `ShowerVideoRenderer` / `VirtualDisplayManager` / `showerclient`）；工具与插件体系 | 无 | 只读架构，不复制代码 |
| [Genymobile/scrcpy](https://github.com/Genymobile/scrcpy) | Apache-2.0 | `NewDisplay.java`、`NewDisplayCapture.java`、`VirtualDisplayListener.java`；虚拟屏创建与兼容 workaround | 无 | 若引用需保留 NOTICE 并声明修改 |
| [AAswordman/Operit2](https://github.com/AAswordman/Operit2) | 见仓库 | 跨平台运行时分层 | 无 | — |
| [guzhou079-arch/dsh-plugin-android-vscreen](https://github.com/guzhou079-arch/dsh-plugin-android-vscreen) | LGPL-3.0 | adb 驱动虚拟屏的插件形态 | 无 | — |

## 2026-11-06 · 手机智能体生态调研

| 项目 | 许可 | 读/借鉴内容 | 代码使用 | 备注 |
|---|---|---|---|---|
| [droidrun/mobilerun](https://github.com/droidrun/mobilerun) | MIT | 手机 agent 工具抽象、UI 状态表示 | 无 | — |
| [X-PLUG/MobileAgent](https://github.com/X-PLUG/MobileAgent) | MIT | 截图→理解→动作循环 | 无 | — |
| [zai-org/Open-AutoGLM](https://github.com/zai-org/Open-AutoGLM) | Apache-2.0 | ADB 驱动与部署形态 | 无 | — |
| [Vengisk/deepseek-harness-termux](https://github.com/Vengisk/deepseek-harness-termux) | MIT | dsh 在 Termux 的 5 处兼容补丁 | 无 | 同源问题已由本项目自有补丁解决 |
| [lilyco-42/dsh-termux](https://github.com/lilyco-42/dsh-termux) | MIT | 一键安装器、node-gyp 目标参数 | 无 | — |
| [thness/dsh-mobile](https://github.com/thness/dsh-mobile) | MIT | 内嵌 Node.js 的独立 APK 形态 | 无 | — |
| [FunnelCakes/deepseek-harness-android](https://github.com/FunnelCakes/deepseek-harness-android) | 见仓库 | Termux 部署脚本 | 无 | — |
| [dphmoblie/deepseek-harness-android](https://github.com/dphmoblie/deepseek-harness-android) | 见仓库 | DSH + Ubuntu + Shizuku/无障碍整合 | 无 | — |

## 2026-10 · 记忆系统调研

| 项目 | 许可 | 读/借鉴内容 | 代码使用 | 备注 |
|---|---|---|---|---|
| MemGPT / Letta | 见仓库 | 分层记忆与上下文换页 | 无 | 详见 `docs/research/industry-survey.md` |
| Mem0 | 见仓库 | 抽取式记忆写入 | 无 | 同上 |
| Zep | 见仓库 | 时序知识图谱 | 无 | 同上 |
| A-MEM | 见仓库 | 记忆网络与动态索引 | 无 | 同上 |
| Cognee | 见仓库 | 图 + 向量混合 | 无 | 同上 |

## 维护规则

- 新增条目写在对应日期小节末尾，不覆盖历史
- 「代码使用」为「片段」或「整文件」时，必须同时在源文件头加注释，并在 `THIRD_PARTY_NOTICES.md` 对应小节登记
- 审计方法见 `docs/history/CHANGELOG-20261106.md` 与本次审计记录
