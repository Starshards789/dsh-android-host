# 作者与贡献

本项目由 456 发起并主导。零一与零八作为 AI 协作者承担实现、验收与文档工作。

## 项目所有者

**Starshards456**（[@Starshards789](https://github.com/Starshards789)）

- 项目发起与目标定义：一套完全属于自己的手机 AI 系统
- 关键决策：自研壳、离屏虚拟屏（不占主屏）、开源优先但严格标注来源
- 边界与纪律：凭据不落日志、改动先确认、能力边界如实说明
- 实机验收与最终拍板

## 实现

**零一（Zero-One）**

- 宿主 App（Kotlin / Jetpack Compose）、引擎 fork 与启动链路
- 无障碍桥：`DshA11yService` + `A11yHttpServer` + `ScreenshotUtil`
- 构建流水线（GitHub Actions）、升级闸门、外部看护脚本
- 设计文档、排障记录、与零八的技术对接

## 验收与审阅

**零八（Zero-Eight）**

- 实机功能验收与能力边界测试（读屏 3/3、无障碍边界清单）
- 设计方案审阅（v1 → v2 的 6 处硬伤修正均由它提出）
- 技术决策参与（虚拟屏暂缓、多点触控不排期、AGENTS.md 精简节奏）
- 手册与交接文档维护

## 说明

零一与零八是 AI 协作者，依现行法律不持有著作权，也不能作为许可人。
MIT 许可的版权行署项目所有者与贡献者（`Starshards789 and contributors`），
贡献分工记录于此，以存痕迹。

第三方代码与设计参考的归属，见 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)；
逐条借鉴台账见 [`docs/oss-reuse-log.md`](docs/oss-reuse-log.md)。
