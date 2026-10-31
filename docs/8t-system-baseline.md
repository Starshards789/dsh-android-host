# 8T 系统与权限基线（含换系统评估）

> 定稿：2026-11-01
> 来源：本机 adb 只读实测 + AOSP 官方文档 + OPPO 官方白皮书 + 第三方逆向项目
> 定位：这是「8T 到底是什么、权限怎么走、要不要换系统」的共同基准。
> 任何后续设计（权限桥、能力层、换系统）都以本文档为起点。
> 全部结论标注来源类型：`[实测]` `[AOSP官方]` `[OPPO官方]` `[逆向]` `[推断]`

---

## 0. 一页速览

| 问题 | 结论 |
|---|---|
| 8T 系统身份 | OnePlus 8T KB2000 / Android 14 (API 34) / ColorOS 14.0.0.300 (CN01) / 安全补丁 2024-01-05 已停更 |
| 第三方 App 能拿到的权限 | 43 个实用的 dangerous + 16 个 normal；111 个 signature/privileged 永远拿不到 |
| 权限怎么落地 | 两条路：老式 Linux 资源（gid+内核，root 能绕）/ HAL 服务资源（binder+服务端，root 绕不了） |
| 新壳 cc.bestly.dsh 现状 | 骨架强，能力面 100% 依附 root，权限桥未建 |
| ColorOS 加的最狠的一刀 | Athena/Hans 进程管控 **绕过 AMS 直接写 cgroup** |
| 应用冻结是谁的 | 内核机制是 AOSP 标准；控制策略是 OPPO 私有 |
| 能不能换系统 | 能，LineageOS 官方支持 KB2000；代价是 48MP + 快充 65W→30W |
| 换系统的真正价值 | 从「黑盒 + 逆向」变成「白盒 + 源码」 |

---

## 1. 系统身份 `[实测]`

| 项 | 值 |
|---|---|
| 机型 | OnePlus 8T KB2000（国行） |
| SoC | Snapdragon 865（kona） |
| 内核 | 4.19.157-perf+ |
| Android | 14（API 34） |
| ROM | ColorOS 14.0.0.300（CN01），V14.0.0 |
| 构建 | user / release-keys / debuggable=0 |
| SELinux | Enforcing |
| 安全补丁 | 2024-01-05（截至 2026-11 已停更约 22 个月） |

**血统**：2020.10 发布，国行出厂氢OS（HydrogenOS），国际版 OxygenOS（Android 11）→ 2021 一加与 OPPO 整合 → OTA 至 ColorOS 12 → 13 → 14。**Android 14 是终版。**

**含义**：这台机器是「有防 Root 架构、但没有后续补丁」的状态，已经脱离官方安全模型。不要把系统自身防护当安全屏障。

---

## 2. 权限体系

### 2.1 总量 `[实测]`

| 类别 | 数量 | 第三方能否拿到 |
|---|---|---|
| normal（安装即给） | 16 | ✅ 自动 |
| dangerous（用户点头） | 121 | ✅ 能给 |
| signature / privileged / internal | 111 | ❌ 永不可得 |

121 个 dangerous 里：Health Connect 健康数据 71 个、汽车 3、位置 1、短信 2、高通 1 → **真正实用 43 个**。

43 个实用危险权限覆盖：相机（CAMERA）· 麦克风（RECORD_AUDIO）· 定位（FINE/COARSE/BACKGROUND）· 传感器（BODY_SENSORS、ACTIVITY_RECOGNITION）· 蓝牙/近场（BLUETOOTH_SCAN/CONNECT/ADVERTISE、NEARBY_WIFI_DEVICES、UWB_RANGING）· 存储媒体（READ/WRITE_EXTERNAL_STORAGE、READ_MEDIA_IMAGES/VIDEO/AUDIO、READ_MEDIA_VISUAL_USER_SELECTED、ACCESS_MEDIA_LOCATION）· 通讯录日历 · 电话短信 · 通知（POST_NOTIFICATIONS、GET_INSTALLED_APPS）。

### 2.2 三层结构

**① 声明层**：`AndroidManifest.xml` 的 `<uses-permission>` → `PackageManagerService` 解析入库
**② 授予层**：按 `protectionLevel` 四档 —— normal（自动）/ dangerous（运行时弹窗）/ signature（同签名或系统）/ special-appop（跳设置页，如悬浮窗、使用情况访问、无障碍）
**③ 执行层**：见 §2.3，**不是单一机制**

### 2.3 权限的两条执行路径（关键判据）

| 类型 | 权限怎么生效 | 例子 | root 能绕吗 |
|---|---|---|---|
| **老式 Linux 资源** | 权限 → gid → 内核 DAC/SELinux 检查 | 网络 `inet`、蓝牙 `net_bt`、存储、`input` 注入、`wakelock` | ✅ 能（换 uid 即通吃） |
| **HAL 服务资源** | 权限 → binder → 服务端校验调用方 | 相机、录音、定位、传感器、通知 | ❌ 不能 |

**证据** `[实测]`：Telegram X `CAMERA: granted=true`，但 `gids=[3002, 3003]` —— 只有 net_bt + inet，**没有 1007(camera)**。一个真拿到相机权限的 App，进程组里根本没有 camera。

**`platform.xml` 的 28 个 gid** `[实测]`：

```
audio bluetooth cache camera diag inet input log loop_radio media
mtp net_admin net_bt net_bt_admin net_bw_acct net_bw_stats net_raw
readproc reserved_disk security_log_writer system_ucenter uhid
vendor_launcher vendor_saures vpn wakelock
```

**关键澄清**：`audio` 这个 gid 在 platform.xml 里是分配给 **`audioserver`** 的，不是给普通 App：
```xml
<assign-permission name="android.permission.MODIFY_AUDIO_SETTINGS" uid="audioserver" />
```
所以正确的音频链路是：
```
App（无 audio gid）→ binder → audioserver(uid 1047, 有 audio gid) → /dev/snd
                              ↑ 权限在这里被检查
```
相机同理：`/dev/video0` 是给 `cameraserver` 用的。

### 2.4 权限到硬件的实际链路

**播放声音（零敏感权限）**：
```
AudioFocus → INTERNET/ACCESS_NETWORK_STATE（normal，自动）
→ MediaCodec/Codec2 解码 → PCM → AudioTrack.write()
→ binder → AudioFlinger（audioserver uid 1047）→ Audio HAL → ALSA /dev/snd/pcmC0D0p → 功放 → 喇叭
```
App 全程不碰喇叭。**播放不需要任何危险权限。**

**录音（需要 RECORD_AUDIO）**：`AudioRecord` → binder → audioserver → `/dev/snd/pcmC0D0c`

---

## 3. 正常 App 的完整生命周期 `[实测]`

### 阶段 1：安装，写三张表
以 Telegram X 为例：
```
install permissions（安装时定）:
  MODIFY_AUDIO_SETTINGS: granted=true   (normal)
  FLASHLIGHT: granted=true              (normal)
  FOREGROUND_SERVICE_MICROPHONE: true
  SYSTEM_ALERT_WINDOW: granted=false    (special，需用户去设置开)

runtime permissions（用户点头）:
  CAMERA: granted=true ✅
  RECORD_AUDIO: granted=false ❌
  READ_CONTACTS: granted=true ✅
  ACCESS_FINE_LOCATION: granted=false ❌
```
同时分配 `uid=10290`，建 `/data/user/0/<pkg>`（权限 700）。

### 阶段 2：启动，建立身份
```
Launcher Intent → AMS 查包 → Zygote fork
→ setresuid(10290) / setresgid / setgroups([3002,3003])
→ SELinux untrusted_app 域 + MCS 类别
→ 加载 dex → Application.onCreate
```

### 阶段 3：用相机（从不碰 /dev/video0）
```
① CameraManager.openCamera()
② binder → cameraserver（独立进程）
③ cameraserver 查三件事：
   · uid 有无 CAMERA（查 PMS 数据库）
   · AppOps 模式（实测 "CAMERA: foreground" = 仅前台可用）
   · 包是否在前台
④ 全过 → 返回句柄
⑤ SystemUI 画绿点
```

### 阶段 4：审计
`[实测]` `CAMERA: allow; time=+29d12h13m47s626ms ago; duration=+6s22ms`
→ 29 天前用过一次，6 秒 22 毫秒。系统记录完整。

**对照 root 截屏**：`su -c 'screencap -p ...'` → 无绿点、无通知、无带时间的记录。

### 阶段 5：权限是活的
| 机制 | 效果 |
|---|---|
| 手动撤销 | 改库，必要时杀进程 |
| 一次性授权（11+） | 退出 App 自动失效 |
| 长期不用自动重置（11+） | 几个月没用自动收回 |
| AppOps 单独掐 | 权限还在但行为被禁（比撤销更细） |
| 前台限定 | 切后台立刻断 |

---

## 4. 四种身份对照 `[实测]`

| 身份 | uid | SELinux 域 | 强在哪 | 弱在哪 |
|---|---|---|---|---|
| **App 自己** | 10295 | `untrusted_app_27` + MCS | 系统承认，公开 API 全可调，能参与 Intent 生态 | 碰不到别的 App，截屏要弹窗 |
| **`su 10295`** | 10295 | **`magisk:s0`** | 能碰文件，绕 DAC | 系统不认它是那个 App，API 调不了 |
| **纯 root** | 0 | `magisk:s0` | 改文件/设置/装卸包/看所有进程/静默截屏 | 无包身份，拿不到相机、定位、传感器 |
| **shell（adb/Shizuku）** | 2000 | `shell:s0` | 有 input、readproc 组 | 无 camera(1007)/audio(1005) 组 |

**核心结论**：**uid 相同 ≠ 身份相同。** 系统服务检查 SELinux 域 + MCS + 包身份注册。
**比喻**：App 身份是工作证，root 是会撬锁。设备认卡不认撬锁。

---

## 5. 新壳 cc.bestly.dsh 现状 `[实测]`

### 5.1 权限面
- Manifest 声明 **11 个**：INTERNET、前台服务、开机自启（normal）；通知、读/写存储（dangerous）
- **无** CAMERA、**无** RECORD_AUDIO、无定位、无震动、无 MODIFY_AUDIO_SETTINGS
- 引擎进程：`uid=10295`，`Groups=3003 9997 20295 50295`（inet、everybody、共享存储、缓存）
- SELinux：`u:r:untrusted_app_27:s0:c39,c257,c512,c768`

### 5.2 7 个系统工具的真实状态
| 工具 | 需要 | 实测 |
|---|---|---|
| android_usage | PACKAGE_USAGE_STATS（special） | ❌ 压根没申请 |
| android_overlay | SYSTEM_ALERT_WINDOW（special） | ❌ default，曾被拒一次 |
| android_package / app / setting / screenshot / input | root 或 Shizuku | ✅ 可用，**全走 root** |

### 5.3 3 个插件全废
- tool-accessibility ❌ 无障碍未启用（`enabled_accessibility_services=null`）
- tool-vscreen ❌ 依赖无障碍
- tool-shizuku ❌ Shizuku 未安装

### 5.4 配置面
`cordis.patch.yml`：`defaultPreset: danger-full-access`、`sandbox: danger-full-access`、`approval: never`

### 5.5 结论
> **手机操作能力 100% 依附 root（无一个例外）。**

- **骨架**：✅ 比社区壳强 —— 三层看护（崩溃 51s / 重启 2m39s 恢复）、Ed25519 升级闸门、数据与代码分离、回滚 16 秒、0 个「声明未授予」权限
- **能力面**：❌ 一片空白，且从未进入路线表（M1 升级闸门 → M2 插件体系 → M3 刷机，没有「能力」这一栏）
- **准确表述**：是「**没长**」，不是「做残」

**若没有 root**：只剩「聊天 + 读写共享存储 + 联网 + 通知 + 保活」。
**若 Shell 身份**：也拿不到 input/camera/audio 组，照样拍不了录不了。

---

## 6. ColorOS 增量层 `[OPPO官方]`

来源：《ColorOS 14 安全技术白皮书》（2024-01，与机器补丁日期对应）

| 层 | 内容 |
|---|---|
| **硬件** | TEE、Secure Boot、**Anti-Rollback** |
| **系统** | AVB 可信启动、SELinux、**内核防 Root 动态防御**（官方原话：「根据内核非法 Root 后的特征行为设计了动态防御机制」）、KASLR、PAN、PXN、CFI、Hypervisor 内核完整性、MTE |
| **隐私（AOSP 没有）** | 应用行为记录（30 天）· 敏感权限提醒（绿点）· **隐私替身**（给权限仍返回空白通讯录/短信/通话/日程）· **应用间跳转拦截**（A→B 弹框，可取消并中断）· 剪贴板管控 |
| **进程管控** | 七层开关 + OplusHansManager |

**七层后台开关**：省电模式 → 智能耗电保护 → 自定义耗电保护（允许后台运行，含关掉后台冻结/异常耗电优化/深度睡眠）→ 智能省电场景/睡眠模式 → 应用速冻 → 应用管理允许自启动 → 最近任务锁定

**OPPO 私有面**：21 个私有权限（`com.oplus.permission.safe.CAMERA` 等）、`privapp-permissions-oplus.xml`（WRITE_SECURE_SETTINGS/REBOOT/MANAGE_USERS/STOP_APP_SWITCHES/STATUS_BAR 仅系统 App）、自定义 gid（vendor_launcher、vendor_saures）、硬件服务（cameraMDM、oplus power/performance/powermonitor）

**⚠️ 已纠正**：Anti-Rollback 与 8T **无关**。ARB 是 ColorOS **16.0.3.500/501/503** 才引入的（一加 13、13T、15、Ace 5）。8T 停在 14.0.0.300，**没有 ARB**。
检测法（第三方硬分析 `AdaUnlocked/Oplus-OTA-ARB-Fuse-Analysis`）：`sectools secure-image --inspect xbl_config.img` 看 Anti-Rollback Version，`0x0` = 未熔断。OPPO 屏蔽了 `fastboot getvar anti` / `rollback_index`。

---

## 7. Oplus Hans 机制（本轮新挖）`[实测]` + `[逆向]`

### 7.1 三个组件
| 组件 | 是什么 | 实测 |
|---|---|---|
| **`hans`** | **独立 native 守护进程**，PPID=1（init 拉起），user=system | pid 9346 |
| **`com.oplus.athena`** | 策略读取 + 执行者，**PERSISTENT**（常驻，杀不掉） | `/system_ext/app/Athena` v4.1.20, pid 4210 |
| **`com.oplus.battery`** | 自启动 / 电池策略 | `/system_ext/app/Battery` v1.2.1, pid 5899 |

**Athena 暴露的接口即能力清单**：
```
IAthenaKillerManager      ← 进程杀手
service.MEMCOLLECT        ← 内存回收
RemoteGuardElfService     ← 远程守护
provider.dexopt           ← 管编译优化
permission.STATE_PROVIDER / ABNORMAL_PROVIDER
database                  ← 策略存在数据库
```
→ **后台管控不是两个开关，是一整套资源管家系统。**

### 7.2 策略字段全清单（两个独立逆向项目交叉验证）
来源：XDA `OplusConfigHook`（OxygenOS 15）+ GitHub `whitewhale0612/Oplus-Hans-Policy`（LSPosed 模块，27 个 Hook）

| 分组 | 字段 | 作用 |
|---|---|---|
| 全局/时序 | `enableConfig` `lcdOffConfig` `lcdOnConfig` | 熄屏/亮屏状态转换 |
| **快冻** | `ffConfig` **`ffTimeout`** `ffPkg` | 熄屏后加速收敛；`ffPkg type="black"` 可排除指定 App |
| **白名单** | `DoNotFreeze` `whitePkg` `imPkg` `cpuCtlWhiteList` `appCard.ignoreApp` | 优先级 / 豁免 / 通知可靠性 |
| 场景治理 | `SysBlackPolicy` `SysBlackRestrictMask` `SysBlackApp`（lcdon / lcdoff / **night**） | 分场景强管控 |
| **代理/批量** | `proxyConfig` + `proxySensor` `proxyGps` `proxyBtScan` `proxyBinder` `defaultProxyBc` | **把后台唤醒「代理」掉**：传感器、定位、蓝牙扫描、binder 回调、广播 |
| **解冻/流量** | `serviceBumpUnFzReasonList` **`trafficConfig`** `prevent` `allow` | 减少误冻、定义恢复路径 |
| 高负载 | `high-loading` `strictMode` `android-freeze` `appQuotaConfig` `cpuCtlRus` | 极端省电 |

### 7.3 这张表解释了我们实测到的三件事
1. **「只有真实网络 I/O 才能续上豁免」** = `trafficConfig`
2. **豁免衰减界 (36s, 5m36s]** = `ffTimeout`（快冻超时）—— 我们测出的边界，原来它有名字
3. **「命令级白名单全部无效」** = 白名单字段在 **Athena 的数据库**里，不在系统设置里

### 7.4 `settings get secure no_frozen` 的真相 `[实测]`
```
com.coloros.video, com.oppo.book, com.oppo.reader, com.nearme.gamecenter,
com.oppo.community, com.coloros.weather, com.android.calculator2,
com.coloros.compass, com.nearme.note, com.redteamobile.roa ...
```
**全是 OPPO 自家 App。** 往这里加自己的包名，等于往厂商根本不看的表里写。

---

## 8. AOSP 标准层 vs OPPO 私有层（关键判据）`[AOSP官方]` + `[实测]`

### 8.1 AOSP 官方有《缓存的应用冻结器》文档
原文要点：
> Android 11 QPR3 或更高版本支持缓存的应用冻结器……**利用内核 cgroup v2 冻结器**
> 验证：**检查是否存在 `/sys/fs/cgroup/uid_0/cgroup.freeze` 文件**
> 验证：`dumpsys activity | grep "Apps frozen:"`
> 开关：`device_config put activity_manager_native_boot use_freezer true`

**我们 skill 里用了半年的 `/sys/fs/cgroup/uid_XXXXX/cgroup.freeze` 路径，就是 AOSP 官方文档写的路径。**

### 8.2 实测矛盾（决定性证据）`[实测]`
| 检查项 | 结果 | 含义 |
|---|---|---|
| `dumpsys activity` → `Apps frozen:` | **0** | AOSP 记账说：一个都没冻 |
| 直接扫 cgroup | **7 个 uid freeze=1**（10108 / 10121 / 10177 / 10234 / 10240 / 10287 / 10290） | 内核说：实际冻了 7 个 |
| `device_config get activity_manager_native_boot use_freezer` | **null** | OPPO 根本没用 AOSP 那个开关 |
| `settings get global cached_apps_freezer` | null | 开发者选项也没设 |

> **结论：OPPO 绕过了 AOSP 的 ActivityManager，直接写 cgroup。**

### 8.3 正确的四层划分
```
① 内核机制      cgroup v2 freezer         ← AOSP 标准（有官方文档）
② 记账 / API    AMS setProcessFrozen      ← AOSP 标准（OPPO 绕过了这一层）
③ 控制策略      冻谁、何时冻、豁免谁      ← OPPO 私有（Athena / Hans）
④ 额外动作      销毁该 uid 全部 socket   ← OPPO 私有（AOSP 没这回事）
```
**我们踩的坑，约三成在 ①，七成在 ③④。**

### 8.4 标准 vs 8T 实测对照
| 我们踩的坑 | AOSP 标准 | 8T 上差在哪 |
|---|---|---|
| 引擎不自启 | 无 systemd，靠 init.rc / 前台服务 | **一致** |
| 被冻结 | cached app freezer（官方功能） | OPPO 直接写 cgroup，绕过 AMS |
| 冻结时 loopback 不通 | 冻结只是停止调度 | **OPPO 额外销毁全部 socket** |
| 权限四档 | protectionLevel | **一致** |
| 权限→gid | platform.xml | 一致 + OPPO 加 vendor_launcher / vendor_saures |
| 隐私替身 | 无 | **OPPO 独有** |
| 应用间跳转拦截 | 无 | **OPPO 独有** |
| SELinux 域限制 | sepolicy | 一致 + OPPO 加防 Root |

### 8.5 AOSP 官方文档地图（查资料的入口）
`source.android.com/docs/core`（2025-03 更新，有中文版）

**基本信息**：架构 · 连接设备 · 电源 · 输入和传感器 · **摄像头** · 自定义设置 · 字体
**媒体展示**：**音频** · 显示 · 图形 · 媒体播放
**系统运行状况**：**性能优化（含冻结器）** · ART · OTA · 测试
**数据存储访问**：数据使用 · 数据存储 · **访问权限** · 虚拟化
**另有**：安全（含 SELinux）· 构建 · 兼容性（CDD/CTS/VTS）· 调试

**源码**：`cs.android.com`（代码搜索）/ `android.googlesource.com`（git，可按 API 34 tag）

**知识来源可靠性排序**：AOSP 文档/源码（一手）> 厂商白皮书（一手但只讲自家）> 逆向与实测（只能看现象）

---

## 9. 换系统评估：LineageOS `[官方]`

### 9.1 官方支持状态（wiki: devices/kebab/variant1）
| 项 | 值 |
|---|---|
| 支持型号 | **KB2000 ✅** · KB2001 · KB2003 · KB2005 |
| 当前版本 | **24.0（Android 17）**；**install 指南对应 23.2（Android 16）** |
| 维护者 | LuK1337、mikeioannina |
| 内核 | 4.19（与现有一致） |
| 历史版本 | 18.1 → 24.0 连续支持 |

### 9.2 官方安装硬要求
| 要求 | 我们的状态 |
|---|---|
| 型号在支持列表 | ✅ KB2000 |
| 解锁 bootloader | ✅ 已解锁 |
| **原厂固件 Android 13/14** | ✅ 14.0.0.300 |
| stock OS 至少启动一次并验证短信/通话/VoLTE | ⚠️ 需确认 |
| 移除所有 Google 账号（防 FRP 锁） | 待做 |

⚠️ 官方警告：固件版本不对 → “failure to install LineageOS, unexpected crashes post-installation, or **permanent damage to your device**”

### 9.3 关键认知：LineageOS 保留原厂 vendor
> 官方原文：**Firmware refers to a device-specific set of images that are included in, and updated by the stock OS**
> LineageOS builds require an **Android 13/14 version of the stock OS** prior to installation

```
ColorOS：    framework(OPPO)  +  vendor/firmware(OPPO)  +  boot(OPPO)
LineageOS：  framework(AOSP)  +  vendor/firmware(OPPO)  ← 同一套，原样保留  +  boot(Lineage)
```
**LineageOS 换的是 framework 层，不是 vendor 层。**

### 9.4 得失：七维度打分
| # | 维度 | ColorOS | LineageOS | 变化 |
|---|---|---|---|---|
| 1 | 改系统行为 | 10%（只能逆向绕） | 85%（开源可编可改） | ⬆️⬆️⬆️ |
| 2 | 行为可预测 | 30%（Athena 绕 AMS、隐私替身、跳转拦截） | 95%（= AOSP 源码） | ⬆️⬆️⬆️ |
| 3 | 安全补丁 | 停更 22 个月 | Android 17 + 月度更新 | ⬆️⬆️⬆️ |
| 4 | 长期自主 | 0（OPPO 说停就停，已停） | 高 | ⬆️⬆️⬆️ |
| 5 | 硬件榨取 | 100%（原厂 ISP/快充/屏幕调校） | **~55%** | ⬇️⬇️ |
| 6 | App 生态 | 100% | **~70%**（Play Integrity + 部分国产 App） | ⬇️ |
| 7 | 自救能力 | 低（等 OTA） | 高（源码 + 可回滚） | ⬆️ |

**净结果：可控性全面上升，体验明确下降。**

### 9.5 具体损失（有据可查）
| 项 | ColorOS | LineageOS | 证据 |
|---|---|---|---|
| **相机分辨率** | 48MP（Quad Bayer remosaic） | **只有 12MP** | Reddit 实测；remosaic 需厂商私有 HAL 接口 |
| 相机录像 | OOS Cam | 明显弱于原厂 | XDA：“Video quality isn't as strong as OOS Cam” |
| **快充** | **65W Warp Charge，39 分钟充满** | **30W USB PD，约 70~90 分钟** | **LineageOS 官方 GitLab issue #6457**，另有 #7895 |
| 音频后处理 | 杜比等 | 部分丢失 | — |

### 9.6 丢失发生在哪一层 → 能不能移植
| 原厂组件 | 层 | 换系统后 | 移植可行性 |
|---|---|---|---|
| 相机 HAL、传感器驱动、指纹固件、modem、触摸固件 | **vendor/firmware** | ✅ **保留（官方流程本就用原厂）** | 不用移植 |
| OPPO CameraEngine（把 HAL 包成好用的 API） | framework | ❌ 丢 | ❌ 极难（私有服务 + 签名） |
| **OPPO 充电状态机**（把 Warp Charge 用起来） | framework | ❌ 丢 | ⚠️ 理论可行，**目前没人做** |
| OPPO 显示调校服务 | framework | ❌ 丢 | ⚠️ 部分可 |
| 音频后处理 | vendor/framework 混合 | ⚠️ 部分丢 | ⚠️ Magisk 模块可补 |
| **原厂相机 App** | APK | ⚠️ | ✅ **有社区 port 版** |

**为什么相机只剩 12MP**：vendor 的 HAL 在，但 AOSP 的 Camera HAL 接口不暴露 remosaic 路径。
**为什么快充 30W**：vendor 的充电 IC 驱动在，但 framework 层的 OPPO 充电状态机不在，只能退回通用 PD。

### 9.7 更划算的思路：换算法而非移植原厂
| 原厂能力 | 替代 | 效果 |
|---|---|---|
| OPPO 相机算法 | **GCam 移植版**（cpkelley94 GCam 8 Config for Lineage/AOSP） | IMX586 上 Google 计算摄影往往更强 |
| 原厂音频 EQ / 杜比 | Magisk 模块（JamesDSP / Viper4Android） | 通常更强 |
| 原厂显示调校 | LineageOS 自带色彩模式 + LiveDisplay | 够用 |

> 失去的是「OPPO 的算法」，换来的是「Google 的算法 + 社区调音」，很多场景是升级。

### 9.8 换系统的隐性成本
1. **清数据不可逆**，`/data/adb/dsh-tools/`（ov/pw/mb/tgsend/watchtower/zsleep/engine-guard…）、`.active_shell`、`.ovkey` 全丢
2. **八零八的身份与会话**（AGENTS.md、sessions）在 App 私有目录，也丢
3. **我积累的 8T 生存知识大半作废**（OplusHansManager 那两百多条实测），要重新踩坑
4. **清机后 App uid 会重新分配**（10295 不是固定值）→ 所有写死 uid 的脚本（host-guard、service.d、watchtower）全要改
5. 约 1~2 天重搭

### 9.9 换系统的真正价值
> **从「黑盒 + 逆向」变成「白盒 + 源码」。**
> 现在对 ColorOS 的一切了解全是逆向和实测倒出来的；LineageOS 每个行为都能查源码，改不了的地方能自己编。

---

## 10. 本会话纠正过的错误（留档，防止再犯）

| # | 错误说法 | 推翻它的证据 | 正确说法 |
|---|---|---|---|
| 1 | 「CAMERA 权限变成 camera gid，能打开 /dev/video0」 | Telegram X `CAMERA: granted=true` 但 `gids=[3002,3003]` 无 1007 | 相机走 `cameraserver` binder 服务端校验，不靠 App 的 gid |
| 2 | 「Intent 生态做了就能操作 App」 | OPPO 白皮书明写「应用间跳转拦截」 | ColorOS 会弹框拦截，用户可取消并中断 |
| 3 | 「Anti-Rollback 意味着 8T 不能降级」 | ARB 是 ColorOS 16.0.3.500/501/503 才有的，机型为一加 13/13T/15/Ace 5 | **8T 没有 ARB，可降级** |
| 4 | 「整个应用冻结都是厂商黑魔法」 | AOSP 官方有《缓存的应用冻结器》文档，路径与验证法完全一致 | 内核机制是 AOSP 标准；OPPO 改的是控制策略 + 绕过 AMS 记账 + 加 socket 销毁 |

---

## 11. 待决问题（重新讨论的入口）

### A. 换不换系统
| # | 问题 | 判据 |
|---|---|---|
| A1 | 48MP 高像素模式平时用不用？ | 不用 → 损失比想象小 |
| A2 | 快充 39 分钟变 80 分钟忍不忍得了？ | 忍不了 → 先别换 |
| A3 | 选 23.2（Android 16）还是 24.0（Android 17）？ | 23.2 有社区 KernelSU 内核，更稳 |
| A4 | 换之前要不要先跑一段时间原厂（验证 VoLTE/通话）？ | 官方要求 stock OS 至少启动一次 |

### B. 不换系统的话，壳怎么补
| # | 问题 |
|---|---|
| B1 | 收窄 root 成白名单 helper（`priv shot/input/pkg/setting`）—— CT110 事故教训，纯降风险 |
| B2 | 加 CAMERA / 定位 / 无障碍？顺序？ |
| B3 | `approval: never` 要不要改成分类审批（读不问 / 写问 / 敏感必问） |
| B4 | 权限桥怎么设计（L0 标准权限层 / L1 特权白名单 / L2 审批层）|
| B5 | `android_usage`、`android_overlay` 两条 `appops set` 能立刻救活，做不做 |

### C. 长期路线
| # | 问题 |
|---|---|
| C1 | 要不要走 LineageOS（白盒 vs 体验的交换） |
| C2 | KernelSU / SukiSU 内核能不能解决 `magisk:s0` 域限制 |
| C3 | 换系统后 8T 生存知识体系要重建（新 skill） |

---

## 12. 资料源清单

| 资料 | 类型 | 位置/说明 |
|---|---|---|
| ColorOS 14 安全技术白皮书（2024-01） | OPPO 官方 | `privacy.oppo.com` PDF，本机缓存 `/root/.hermes/cache/web/privacy.oppo.com-10435d87af.md` |
| AOSP 官方文档 | 一手 | `source.android.com/docs/core`（中文可用） |
| AOSP 缓存应用冻结器 | 一手 | `docs/core/perf/cached-apps-freezer` |
| AOSP binder-freezer | 一手 | `docs/core/architecture/ipc/binder-freezer` |
| AOSP 源码搜索 | 一手 | `cs.android.com` |
| `AdaUnlocked/Oplus-OTA-ARB-Fuse-Analysis` | 逆向 | ARB 熔断机制 + Sectools 检测法 |
| `whitewhale0612/Oplus-Hans-Policy` | 逆向 | 27 个 Hook、策略字段、cgroup 验证法 |
| XDA `OplusConfigHook` | 逆向 | freeze stack 字段全清单、athena/battery 分工 |
| Appium 社区 + appium#18183 | 第三方实测 | OPlusHansManager 冻结其他 App 的证据 |
| LineageOS wiki kebab variant1 | 官方 | 支持型号、版本、install 指南 |
| LineageOS GitLab issue #6457 / #7895 | 官方 issue | Warp Charge 65W 不工作，只走 30W PD |
| 欧加真ROM站 `yun.daxiaamu.com` | 资源 | 8T 全版本官方 ROM |
| 本机权限清单 `/root/allperms.txt` | 实测 | `pm list permissions` 全量 |
