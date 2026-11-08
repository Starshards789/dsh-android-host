# Changelog

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 与语义化版本。

## [1.3.3] - 2026-11-08

### Fixed

- **双引擎竞态**：外部 restart 信号与健康检查失败两条路径同时进入 `start()`，
  各自启动一个引擎（实测 16:55:40 同秒双触发 → 13081 + 13082 各一个 node 进程）。
  根因是 `_state` 守卫是「读-判断-写」，非原子。
  - 新增 `startGuard`（`AtomicBoolean.compareAndSet`）：同秒双触发只放一个过去，
    被拦时 `host.log` 记一行 `start 并发调用被拦（已有启动在执行）`
  - 新增 `adoptExistingEngine()`：启动前认领已存活实例，判据三层
    （状态 Running + `runtime/pid` 进程存活 + 命令行含 `bin.js web`），壳重启后不再多起一份
  - `healthProblem()` / `restart()` 补 `File("/proc/<pid>").exists()` 检查，
    覆盖「认领来的实例没有 Process 对象」的情况
- 实测验证：单实例 13682@13081；kill 后 40s 自愈；App 重启后认领不重复。
  同秒双触发未复现（restart 轮询 15s / 健康检查 60s，人工造不出），
  下次真触发时以日志那行为准。

## [1.3.2] - 2026-11-06

### Fixed

- `/status` 补 `canScreenshot` 能力位。插件读不到该字段会恒判「截图不可用」，
  导致能力位与实际 `/screenshot` 成功互相矛盾。

## [1.3.1] - 2026-11-06

### Fixed

- 引擎令牌注入顺序：先剥掉继承来的密钥类变量、再写本应用要注入的变量。
  原实现末尾的「密钥脱敏」按变量名含 `TOKEN` 把刚注入的 `APP_LOCAL_TOKEN` 自己删掉，
  插件因此永远鉴权失败。
- 截图坐标系：`screenW/H` 改用物理显示尺寸，与 `screencap` 图片一致。
  原实现取 `displayMetrics` 可用高度（2209 vs 2400），Y 坐标系统性偏 8%。

## [1.3.0] - 2026-11-06

### Added

- 无障碍屏幕助手：`DshA11yService`（AccessibilityService）、`A11yHttpServer`（本地 HTTP 桥，
  `X-DSH-Token` 鉴权）、`ScreenshotUtil`（root `screencap`）。
- 端点：`/status`、`/dump`、`/screenshot`；节点树压平为 JSON。
- 与官方 `dsh-tool-accessibility` 插件契约对齐，实机验收 3/3 通过。

## [1.0.0 – 1.2.2] - 2026-10-29 ~ 2026-11-06

早期迭代，完整记录见 [`docs/history/CHANGELOG-20261106.md`](docs/history/CHANGELOG-20261106.md)
与 git 历史。主要节点：

- 宿主骨架：Kotlin + Compose、前台服务、开机自启、引擎 fork 与端口探测
- 引擎链路：bionic node、`NODE_COMPILE_CACHE`、PATH 修正、工作区外置
- 保活与看护：三层看护、host-guard v2、冻结自愈
- 升级闸门：能看 / 能升 / 能退 / 能锁（Ed25519 信任根）
- WebView 兼容：`window.open`、vh 高度修复、页面故障自愈、深浅色主题
- payload 适配：三处 Android 补丁 + 幂等重打脚本

## 版本对照

| versionName | versionCode | 日期 |
|---|---|---|
| 1.3.2 | 33 | 2026-11-06 |
| 1.3.1 | 32 | 2026-11-06 |
| 1.3.0 | 31 | 2026-11-06 |
| 1.2.2 | 30 | 2026-11-06 之前 |
| 1.2.1 | 29 | 同上 |
| 1.2.0 | 28 | 同上 |
| 1.1.1 | 27 | 同上 |
| 1.1.0 | 26 | 同上 |
| 1.0.1 | 25 | 同上 |
| 1.0.0 | 24 | 2026-10-29 前后 |
