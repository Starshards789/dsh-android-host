# 8T 侧工具与自愈机制

> 2026-11-10 建立。记录三个「一次做好、长期有效」的机制。
> 每一条都经过实测，不是推断。

## 一、git wrapper

### 问题

dsh 打包的 git（Termux 移植版 v2.55.0）需要 **4 个条件**才能正常工作，缺一个就报不同的错：

| # | 条件 | 缺了报什么 |
|---|---|---|
| 1 | `GIT_EXEC_PATH` | `remote-https is not a git command` |
| 2 | `CURL_CA_BUNDLE` | `error adding trust anchors` |
| 3 | `LD_LIBRARY_PATH` | `CANNOT LINK EXECUTABLE ... libpcre2-8.so not found` |
| 4 | `PATH` 含 `payload/bin` | `unable to find remote helper for 'https'` |

**第 4 个最隐蔽**：git 调用 remote helper 时 `argv[0]="git"`（不是绝对路径），必须在 `PATH` 里能找到 `git` 自己。否则 `execve` 直接失败，trace2 里表现为 `"pid":-1`，最终报 `unable to find remote helper` —— 看起来像「helper 缺失」，实际是「git 找不到自己」。

### 解法

部署两份（root 一份 + App 身份一份）：

- `/data/adb/dsh-tools/git`
- `/data/data/cc.bestly.dsh/files/dsh-tools/git`（`chown u0_a177`，755）

```sh
#!/system/bin/sh
PAYLOAD="/data/data/cc.bestly.dsh/files/payload"

# 1) 动态库（缺了 libpcre2-8.so not found）
[ -d "$PAYLOAD/runtime/lib" ] && export LD_LIBRARY_PATH="$PAYLOAD/runtime/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"

# 2) PATH 必须含 payload/bin（remote helper 靠它找到 git 自己）
case ":$PATH:" in *":$PAYLOAD/bin:"*) ;; *) export PATH="$PAYLOAD/bin:$PATH" ;; esac

# 3) git 子命令目录 + 模板
export GIT_EXEC_PATH="$PAYLOAD/git/libexec/git-core"
[ -d "$PAYLOAD/git/templates" ] && export GIT_TEMPLATE_DIR="$PAYLOAD/git/templates"

# 4) CA bundle
export CURL_CA_BUNDLE="$PAYLOAD/runtime/etc/cacert.pem"
export GIT_SSL_CAINFO="$CURL_CA_BUNDLE"

exec "$PAYLOAD/bin/git" "$@"
```

**注意**：

- **DNS**：`su 10177` 是裸 shell 没有 DNS（bionic 靠 netd）；App 进程有，所以 wrapper 在零八的进程里正常，在 `su 10177 -c` 里会报 `Could not resolve host`
- **无参数运行**会打印当前环境，便于排查

### 验证

```bash
# 零八身份（App 进程，有 DNS）
/data/data/cc.bestly.dsh/files/dsh-tools/git clone --depth 1 \
  https://github.com/octocat/Hello-World /data/data/cc.bestly.dsh/files/dshhome/_test
# 期望：Cloning into ... rc=0，目录里有 README
```

**2026-11-10 实测**：零八本人跑通（`rc=0`，README 内容在）。

---

## 二、a11y 壳自愈

### 问题

重装 / 覆盖安装 APK 后，Android 可能撤销无障碍授权，零八读不到屏幕、点不了按钮，需要人去「设置 → 无障碍」手动重开。

### 解法

`DshApp.kt`（Application 子类，Manifest 注册 `android:name=".DshApp"`）：

```
onCreate → postDelayed 5 秒 → ensureA11yAsync()
         → isA11yEnabled()? 已开 → 返回
         → 未开 → 子线程 su 写 settings
```

**三条纪律**（与零八 2026-11-10 约定）：

1. **幂等**：已开启就返回，不反复写
2. **异步**：`su` 调用会阻塞，必须在子线程
3. **静默失败**：`catch (Throwable)`，补不上绝不能让 App 起不来

**10 分钟节流**：失败后不反复 spawn su、不刷日志。

### 检测方式的三个实测反例

| 方式 | 结果 |
|---|---|
| `Settings.Secure.getString(ENABLED_ACCESSIBILITY_SERVICES)` | **恒返回空**（Android 17 App 进程；日志 `configured=[]`，同一时刻 `adb shell settings get` 有值） |
| `AccessibilityManager.getEnabledAccessibilityServiceList()` 在 `onCreate` 同步调用 | **必然返回空**（AMS 未完成绑定） |
| 同上，延迟 5 秒后 | **仍返回空**（AMS 绑定比预想慢） |

**结论：每次都补。**

值相同 → 幂等无害；2 次 `su` 调用 → 开销可忽略。
**「宁可多补，不可漏补」** —— 用 2 次 su 换「不漏补」，值。

### 验证

```bash
# 1. 清空（模拟重装后的状态）
adb shell "/debug_ramdisk/magisk su -c 'settings put secure enabled_accessibility_services \"\"; settings put secure accessibility_enabled 0'"

# 2. 重启 App
adb shell "am force-stop cc.bestly.dsh"
adb shell "am start -n cc.bestly.dsh/.MainActivity"
sleep 18

# 3. 期望
adb shell "dumpsys accessibility | grep 'Enabled services'"
# → Enabled services:{{cc.bestly.dsh/cc.bestly.dsh.a11y.DshA11yService}}
```

**2026-11-10 实测**：两次连续重启都恢复 ✓；零八复核 `running=true`、壳 `1.4.4 / 39`、引擎 401。

---

## 三、GEOSITE 数据文件

**2026-11-10 检查结果**：CT106 新下载的 `geosite.dat` / `geoip.metadb` 与手机上 **sha256 逐位相同**：

```
geosite.dat   85073a00a3c3eb80ac76b97df2f97f7dab00cb36c8b5e7ed2354cbd016f8b088
geoip.metadb  f0508298d0295ce4ae0d6d185ba24605b6ec1ef9c42409dec3271e6525b5486e
```

MetaCubeX 的 `latest` 从 11-09 到 11-10 未变，**无需更新**。

**什么时候需要更新**：

- 某个国内网站走了代理（`GEOSITE,cn` 没收录）
- 某个国内 IP 走了代理（`GEOIP,CN` 没收录）

**更新方法**：见 `docs/design/proxy-ui.md` §5.4。

---

## 四、来源标注

| 内容 | 来源 |
|---|---|
| git 环境变量前两条（`GIT_EXEC_PATH` / `CURL_CA_BUNDLE`） | 零八 2026-11-08 排查 |
| `LD_LIBRARY_PATH` + `PATH` 含 `payload/bin` | 零一 2026-11-10 trace2 定位 |
| `argv[0]="git"` 机制说明 | 零一 2026-11-10 `GIT_TRACE2_EVENT` 分析 |
| a11y 自愈方案框架（D 方案） | 零八 2026-11-10 提出 |
| 三个检测反例 | 零一 2026-11-10 实测 |
| 尾网节点删除决策 | 456 2026-11-10「只留公网，之前的废弃东西和现在的新东西没关系」 |
