# Changelog

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 与语义化版本。

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
