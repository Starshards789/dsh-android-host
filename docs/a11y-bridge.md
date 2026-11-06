# 无障碍桥（DshA11yService + A11yHttpServer）

> 状态：**已打通并验收**（2026-11-06，壳 v1.3.2 / versionCode 33）
> 目的：让引擎里的 agent（零八）能读屏、点按、输入、截图——即「看见并操作手机」。

## 1. 三层结构

```
dsh-tool-accessibility（引擎进程里的 JS 插件）
  → HTTP 127.0.0.1:3181/<endpoint>，头 X-DSH-Token: $APP_LOCAL_TOKEN
  → A11yHttpServer（App 进程，ServerSocket 只绑回环）
  → DshA11yService（AccessibilityService，同进程直接方法调用）
```

为什么不让插件直接 bind 服务：插件的 JS 拿不到 `AccessibilityNodeInfo`，它只认「本地 HTTP + JSON」这个契约；桥的作用就是把 Android 对象翻译成 JSON。

为什么必须校验 token：Android 10 起本机任意应用都能连 localhost，只绑回环不是访问控制。

## 2. 端点

| 端点 | 参数 | 状态 |
|---|---|---|
| `/status` | — | ✅ 含 `canScreenshot` |
| `/dump` | — | ✅ 节点：text/desc/cls/x/y/w/h/clickable/input/checked/selected/scrollable/depth |
| `/tap` | `text`/`desc`/`x+y`/`fx+fy` | ✅ 优先 ACTION_CLICK，退回 dispatchGesture |
| `/hold` | `x` `y` `duration` `finger` | ✅ |
| `/input` | `text` | ✅ ACTION_SET_TEXT（中文/表情可用） |
| `/scroll` | `direction` | ✅ 优先 ACTION_SCROLL |
| `/swipe` | `x1 y1 x2 y2 duration finger` | ✅ |
| `/screenshot` | `grid=4` | ✅ 返回**文件路径**（共享目录），不是 base64 |
| `/touch` `/touch-status` `/gesture` | — | ❌ 返回「暂未实现」，插件走降级路径 |

## 3. 三个已经踩过的坑（都会伪装成别的问题）

### 3.1 令牌被自己的脱敏逻辑删掉（最隐蔽）

`EngineLauncher` 先 `put("APP_LOCAL_TOKEN", ...)`，末尾再执行「密钥脱敏」：

```kotlin
keys.filter { it.contains("TOKEN", true) || ... }.forEach { remove(it) }
```

`APP_LOCAL_TOKEN` 的键名含 `TOKEN` → **刚注入的值被自己删掉**。表现：

- App 侧 HTTP 桥一切正常（端口在听、无 token 正确返「鉴权失败」）
- 引擎 `/proc/<pid>/environ` 里只有 `APP_A11Y_PORT`，没有 `APP_LOCAL_TOKEN`
- 插件每次调用都返回「鉴权失败」，看起来像插件没配对

**修法**：脱敏必须先做（只剥继承来的变量），再 put 本应用要注入的变量。顺序反了就是自伤。

### 3.2 `/screenshot` 的 screenH 取错坐标系

`Resources.displayMetrics.heightPixels` 是**应用可用高度**（实测 1080×2209），物理屏是 1080×2400。插件用 `scaleY = screenH / imageH` 换算 → 0.92，截图上量到的 Y 坐标被系统性压掉 8%，越往下点越偏。

**修法**：`screenW/H` 直接用 screencap 图片尺寸（物理像素，scale 恒为 1）。

### 3.3 `canScreenshot` 能力位没实现

插件 `android_a11y_status` 读 `v.canScreenshot`，服务端 `status()` 没返回该字段 → 恒为 false → agent 以为「截图不可用」，而 `android_see` 实际能出图（零八验收时专门指出）。

**修法**：`status()` 补 `canScreenshot`，用 su 二进制路径存在性判断（**不执行 su**，否则每次 status 都弹一次 Magisk Toast）。

## 4. 验收记录（2026-11-06 22:4x，零八实机）

只读三项全过：

- `android_a11y_status` → `running:true`，前台包名、可读控件 44、API 37、读屏 ✓ / 手势注入 ✓
- `android_screen` → 38 节点，带文字与 bounds，能按「文字 + 坐标」定位按钮
- `android_see` → 1080×2400，`scaleX=scaleY=1`

App 侧独立验：

- `dumpsys package` 有 `cc.bestly.dsh/.a11y.DshA11yService`、`BIND_ACCESSIBILITY_SERVICE`
- `Bound services` 里 `capabilities=33`（1=读屏 + 32=手势）
- 无 token → 鉴权失败；带 token → `/status` ok
- `/screenshot` 返回路径可读，尺寸与屏幕一致

## 5. 已知边界（零八判定，短期不解）

| 场景 | 限制 | 说明 |
|---|---|---|
| WebView 内部 | 只暴露容器节点，DOM 文本读不到 | 无障碍 API 的边界；只能截图 + 坐标 |
| 游戏（Unity/UE） | 无控件树 | 靠 `android_see` + 4×4 网格 |
| 多点触控 / 连续节奏 | 手势逐次注入，有 per-call 延迟 | `/touch` `/gesture` 未实现；连招/惯性滑动/高频点击不可用 |
| 长列表 | 一次只读当前屏 | 跨屏需多轮 scroll 拼 |
| 密码框 | 值被系统遮蔽 | 安全特性，不绕过 |
| 虚拟屏（vscreen） | 本桥看主屏 | 需单独验证 `tool-vscreen` |

## 6. 开启方式（换机 / 重装后）

服务需系统设置里启用。root 可直接写（免手点）：

```bash
su -c 'settings put secure enabled_accessibility_services cc.bestly.dsh/.a11y.DshA11yService'
su -c 'settings put secure accessibility_enabled 1'
```

验证：`dumpsys accessibility | grep -A2 'Bound services'` 应出现
`Service[label=DeepSeek Harness 屏幕助手, ... capabilities=33 ...]`。

## 7. 调试捷径

服务只绑回环，从电脑侧看：

```bash
adb forward tcp:13181 tcp:3181
T=$(...)   # /data/user/0/cc.bestly.dsh/shared_prefs/dsh_a11y.xml 里的 local_token
curl -H "X-DSH-Token: $T" http://127.0.0.1:13181/status
```

token 持久化在 `dsh_a11y.xml`，引擎重启不变；App 数据被清才会换新。
