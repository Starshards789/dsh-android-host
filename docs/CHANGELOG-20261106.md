# 2026-11-06 夜间迭代记录

LineageOS 24 刷机后，dsh 宿主壳（`cc.bestly.dsh`）从 0.9.x 一路修到 0.9.9 的完整过程。

时间均为 CST（UTC+8）。所有版本都由 GitHub Actions 构建，手机装的是 CI 产物，不是本地打包。

---

## 一、版本总表

| 版本 | 提交 | 构建 | 结论 | 解决的问题 |
|---|---|---|---|---|
| 0.9.2 | `b60dd07` | 42099306934 | success | 对话页触摸全失效（WebView 实例泄漏） |
| 0.9.3 | `f1143e4` | 42100169441 | success | 双地址复制 + 键盘遮挡按钮 |
| 0.9.4 | `ffa7d9e` | 42101317302 | success | 尝试修弹窗塌陷（未成，见 0.9.5） |
| 0.9.5 | `167e5e0` | 42101926165 | success | 弹窗高度塌陷（真正的修法） |
| 0.9.6 | `138c655` | 42103469184 | success | 状态卡可折叠 + 三态主题 |
| 0.9.7 | `4ca3925` | 42104586612 | success | 日志 token 打码 + 深色卡降饱和 |
| 0.9.8 | `764fb05`→`189b6d2`→`89c3ccd` | 42105440134 / 42105889519 / 42106220362 | 失败→失败→success | 启动时扫描并脱敏残留 token |
| 0.9.9 | `49815dc` | 42106787379 | success | 引擎就绪后再脱敏一次（补上漏网的那一轮） |

---

## 二、逐个说明

### 0.9.2 — 对话页点不动

**现象**：原生按钮（返回、对话）能点，WebView 里什么都点不动。

**定位**：`uiautomator` 里看到两个同坐标的 WebView 节点上下叠着（`[0,304][1080,2332]` 和 `[0,304][1080,2333]`）。用 CDP 往 WebView 注入 touch / click / mouse 监听，再 `input tap`：堆叠态三个计数全是 0；App 重启只剩一个实例时全是 1。

**根因**：`AndroidView` 不回收 WebView，每次进出对话页都新建一个，旧实例留在视图树里叠在顶上，上层那个空的把触摸全吃掉。

**修法**：`AndroidView` 加 `onRelease` —— `stopLoading()` + `loadUrl("about:blank")` + `destroy()`。

**验证**：连续进出 3 轮，每轮进入时 WebView 数 = 1、退出后残留 = 0。

---

### 0.9.3 — 双地址 + 键盘遮挡

**背景**：App 内走 `127.0.0.1`（OAuth 回调只认回环），外部设备走 Tailscale。原实现把 `0.0.0.0` 直接拼进 URL，复制出来谁都用不了。

**改动**：状态卡改两个独立复制按钮，各自标注用途；`copiedHint` 从 Boolean 改成 Int，两个按钮的提示互不干扰。Manifest 加 `adjustResize`。

**顺带确认**：CDP 实测页面 URL 确实是 `http://127.0.0.1:13081/`，不是 Tailscale。

---

### 0.9.4 — 一次失败的尝试

**假设**：`useWideViewPort` + `loadWithOverviewMode` 导致 WebView 视口计算异常。

**结果**：关掉这两个开关后，`100vh` 依然归零。**假设错误，白改一轮。**

**留下的教训**：要往某个开关上试之前，先花十秒测一下 `100vh` 到底是不是 0，别凭直觉改。

---

### 0.9.5 — 弹窗只显示一个输入框

**现象**：进对话页后，弹窗只有标题和输入框，说明文字和「稍后配置 / 保存并继续」两个按钮完全看不见。

**定位过程**：
1. 先查是不是渲染慢 —— 不是，DOM 里元素齐全，`curl` 资源全 200
2. CDP 量几何 —— 弹窗容器 `[24, 337, 336, **48**]`，而内容 `scrollHeight` 是 **244**
3. 量视口单位 —— `window.innerHeight` = 721（正常），但 `100vh` = **0**，`100dvh` / `100svh` 也是 **0**
4. 用系统浏览器（Jelly，同样是 WebView 内核）打开同一页面 —— **显示完整**

**根因**：dsh 的弹窗用 `max-height: calc(-48px + 100vh)` 控高。`100vh` 是 0，这个值就成了非法值，容器塌成 48px，子元素被 `overflow:hidden` 裁掉 —— 只剩恰好落在容器内的那个输入框。

**修法**：`onPageFinished` 注入补偿样式，用 `innerHeight` 换算成 px，覆盖 CSS 里全部 4 条 vh 规则（`jLrgrW_content` / `_dialog_` / `_scrollable_` / `_confirmation_`）。

**验证**：注入后弹窗从 48px 变 244px，两个按钮归位；装机后与浏览器截图一致。

---

### 0.9.6 — 折叠 + 主题

**两个需求**：

1. **状态卡折叠** —— 双地址按钮一加，卡片占掉大半屏。右上角加「收起 ▲ / 展开 ▼」，收起后只留状态标题和副标题，PID / PORT / TOKEN / 地址按钮全部隐藏。
2. **主题切换** —— 标题栏加三态按钮：跟随系统 → 浅色 → 深色，存 `SharedPreferences`，重启保持。

**实现要点**：`setContent` 的 lambda 没有 receiver，里面的 `this` 指向 Activity；`Surface` 必须显式给 `color = MaterialTheme.colorScheme.background`，否则深色模式下只黑一半。

---

### 0.9.7 — token 打码 + 深色降饱和

**token 打码**：`appendLog` 入口统一过正则 `(?i)(token=)[A-Za-z0-9_\-]{6,}`，UI 列表和 `engine-host.log` 两处都拿不到明文。

**深色降饱和**：`stateColor` 加 `dark` 参数，四个状态色各压暗一档，Running 从 `2E7D32` 改成 `1B4D2E` 墨绿。

**发现的问题**：`engine.log`（引擎自己写的）里还有历史明文 token，7 行。已脱敏，备份文件也删了。

---

### 0.9.8 — 两次构建失败

**目标**：每轮启动前扫一遍 `runtime/*.log`，把残留 token 脱敏。

**第一次失败**（`764fb05`）：一堆莫名其妙的错 —— `EngineState` 未解析、`Missing '}'`、`Unclosed comment`，指向根本没改过的行和别的文件。

**根因**：KDoc 里写了 `runtime/*.log`。Kotlin 的**块注释可嵌套**，那个 `/*` 开了一层新注释，从这里一路吃到文件尾，后面的 `sealed interface` 全部消失，引用它的文件跟着全红。

**第二次失败**（`189b6d2`）：改成 lambda 之后还是同样的错。

**根因**：`"$1***"` 里 `$1` 撞上 Kotlin 字符串模板 —— `1` 不是合法标识符，解析器崩。

**第三次成功**（`89c3ccd`）：注释改成「runtime 目录下的 *.log」，正则替换改用 `regex.replace(text) { it.groupValues[1] + "***" }`。

**这两类错误的共同点**：报错位置**全是假的**。看到「一堆文件同时 Unresolved」，先怀疑某一个文件的语法错误，别去追那些符号的定义。

---

### 0.9.9 — 补上漏网的那一轮

**发现**：0.9.8 装完后，`engine.log` 里还是有明文 token。

**原因**：`sanitizeRuntimeLogs` 只在 `start()` **开头**跑 —— 清的是上一轮留下的；引擎启动后自己又会写一行带 token 的访问 URL。

**修法**：在 `EngineState.Running` 设好之后（引擎已就绪）再跑一次。

**验证**：删掉 `engine.log` 重启，新版本写出来的内容是：

```
dsh web: http://127.0.0.1:13081/?token=*** (LAN: http://100.102.144.35:13081/?token=***)
```

`grep -rlE "token=[A-Za-z0-9_-]{20,}" runtime/` → **ALL-CLEAN**。

---

## 三、三个值得记住的根因

1. **WebView 里 `100vh` 算成 0** —— 而 `window.innerHeight` 和 `documentElement.clientHeight` 都正常。同一页面在系统浏览器里正常。这是容器侧问题，不是页面 bug。诊断方法：插一个普通块元素 `height:100vh` 读 `getComputedStyle().height`。
2. **`AndroidView` 不回收 WebView** —— 同坐标叠多层，上层空实例吞掉所有触摸。原生控件正常、WebView 内全无响应，就是这个特征。
3. **Kotlin 块注释可嵌套** —— 注释里出现 `/*` 会让整个文件从那里展开，报错位置全在别处。

---

## 四、当前状态

- 壳版本：**0.9.9 / versionCode 23**
- 引擎：payload v1.14.0，绑 `0.0.0.0:13081`，iptables 只放行 `lo` / `tun1`
- 双地址：`127.0.0.1:13081`（App 内 / 本机浏览器，登录填 Key 用这个）、`100.102.144.35:13081`（其他设备，Tailscale）
- 日志：UI / `engine-host.log` / `engine.log` 三处 token 全部 `***`；状态卡上的 TOKEN 保留明文供复制
- 未做：payload 三处补丁还没有纳入 CI 自动重打（目前靠 `scripts/apply-android-patches.sh` 手动执行）
