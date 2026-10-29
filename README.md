# dsh-android-host

Android 宿主 App for [dsh](https://github.com/deepseek-ai/dsh) —— 给自建 AI 系统一个自己的手机身体。

**Kotlin + Jetpack Compose** · 包名 `cc.bestly.dsh` · compileSdk 34

## 现在是什么状态

**P1.0 —— 构建链验证。** 这一版只有一个 Hello World 页面，作用是证明：

- GitHub Actions 能拉 Android SDK
- Gradle + AGP + Kotlin 版本组合能编译
- 产出的 APK 能装到设备上并正常运行

**不碰 dsh、不碰 payload。** 构建失败也不会影响任何现有部署。

## 构建

推到 `main` 自动构建，或手动触发 Actions。APK 作为 artifact 上传（保留 30 天）。

本地构建需要 Android SDK + Gradle 8.9 + JDK 17，本机磁盘不够，只走云端。

## 路线

| 阶段 | 内容 | 状态 |
|---|---|---|
| P1.0 | 构建链验证（Hello World） | 进行中 |
| P1.1 | 宿主骨架：fork 引擎 + 环境变量构造 + Unix socket | 待办 |
| P1.2 | WebView 接入 + token 注入 | 待办 |
| P2 | 升级闸门：清单签名 + 白名单 + 回滚 | 待办 |
| P3 | 保活：前台服务 + 自启 + 冻结检测 | 待办 |
| P4 | 插件体系 | 待办 |

## 设计文档

完整设计方案（架构 / 启动链 / 升级闸门 / 回滚 / 安全）不在此仓库公开讨论范围之外的部分，见项目内部文档。

## 许可

MIT
