package cc.bestly.dsh.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket

/**
 * P1.1 —— 引擎启动器
 *
 * 目标：证明「宿主 App 自己能把 dsh 引擎 fork 起来，并拿到端口 + token」。
 *
 * 关键设计决定（每一条都是踩过坑才这么写的）：
 *
 * ① 引擎必须由 App 进程自己 fork
 *    2026-10-29 实测：root 用 `su 10284` 起的进程 SELinux 域是 u:r:magisk:s0，
 *    绑端口直接被拒（listen EACCES），runcon 切域也失败。
 *    只有 App 进程 fork 出来的子进程才在 u:r:untrusted_app_27 域里，才能绑端口。
 *
 * ② 环境变量必须显式构造，不能继承
 *    缺 LD_LIBRARY_PATH → node 直接起不来（找不到 libz.so.1）
 *    缺 --expose-internals → Cannot find module 'internal/modules/esm/loader'
 *
 * ③ payload 必须在本 App 私有目录里
 *    安卓沙盒：/data/user/0/<别的包名>/ 是 0700，跨 App 一律 Permission denied。
 *    所以 P1.1 开始前把旧壳的 payload 复制了一份到 files/payload（710M，不含 dshhome）。
 *
 * ④ 端口和 token 从引擎自己的 stdout 里读
 *    引擎启动成功会打印： dsh web: http://127.0.0.1:<port>/?token=<token>
 *    这一步才是「真的起来了」的证据 —— 进程存在不等于服务可用。
 */
object EngineLauncher {

    private const val TAG = "EngineLauncher"

    /** 引擎打印的启动 URL，用于抓端口和 token */
    private val URL_REGEX =
        Regex("""http://([0-9a-fA-F:.]+):(\d+)/\?token=([A-Za-z0-9_\-]+)""")

    private var process: Process? = null
    private var readerJob: Job? = null

    /** 最近一次 start 用的 Context，只用于「把日志落到文件」 */
    @Volatile
    private var lastCtx: Context? = null

    /**
     * 启动代号。每次 start() 递增。
     *
     * ★ 2026-10-30 新增，为了断掉一类竞态：
     *   restart() 会 cancel 掉旧的 readerJob，旧协程正在阻塞读 stdout，
     *   被取消时抛 "read interrupted"，它自己的 catch 会把它当成启动失败，
     *   从而把「已经启动成功的新实例」的状态覆盖成 Failed。
     *
     *   实测证据（v0.8.2 上）：
     *     19:08:43 强制重启 → 19:08:45 启动异常: read interrupted（旧协程）
     *     同一秒新引擎 pid=29221 已经在起，19:09:33 还真就绪了
     *     但界面/状态一直是「启动失败」，白等 15s 退避
     *
     *   有了代号，旧协程发现「我已经不是最新那次启动」就闭嘴。
     */
    @Volatile
    private var generation = 0

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    /** 最近若干行日志，UI 上直接看 */
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    // ---------- 路径 ----------

    fun payloadDir(ctx: Context): File = File(ctx.filesDir, "payload")
    fun dshHomeDir(ctx: Context): File = File(ctx.filesDir, "dshhome")
    fun runtimeDir(ctx: Context): File = File(ctx.filesDir, "runtime")
    fun ipcDir(ctx: Context): File = File(ctx.filesDir, "ipc")

    private fun nodeBin(ctx: Context) = File(payloadDir(ctx), "runtime/bin/node")
    private fun dshEntry(ctx: Context) =
        File(payloadDir(ctx), "dshroot/lib/node_modules/@deepseek-ai/dsh/lib/bin.js")

    // ---------- 启动前自检 ----------

    /**
     * 不启动，只检查环境齐不齐。
     * 这样 UI 能明确告诉用户「缺什么」，而不是点了没反应。
     */
    fun preflight(ctx: Context): List<String> {
        val missing = mutableListOf<String>()
        if (!nodeBin(ctx).exists()) missing.add("node 运行时: ${nodeBin(ctx)}")
        if (!dshEntry(ctx).exists()) missing.add("dsh 内核: ${dshEntry(ctx)}")
        if (!File(payloadDir(ctx), "runtime/lib").isDirectory) missing.add("runtime/lib (LD_LIBRARY_PATH)")
        if (!File(payloadDir(ctx), "runtime/etc/openssl.cnf").exists()) missing.add("openssl.cnf")
        if (!File(payloadDir(ctx), "runtime/etc/cacert.pem").exists()) missing.add("cacert.pem")
        return missing
    }

    // ---------- 启动 ----------

    /**
     * 找一个本进程能真正 bind 的本地端口。
     *
     * 为什么不直接用 `--port 0` 交给 OS 分配：实测在 App 域（untrusted_app_27）下用
     * `--port 0` 启动，dsh 从头到尾没有绑任何端口，也没写日志 —— 进程活着但服务从未
     * 起来。同样参数在 magisk 域下却能走到 bind（报 EACCES 带不出端口号）。
     * 结论：`--port 0` 这条路在 App 域不可靠，改回「先探测再指定」。
     *
     * 探测出来的端口含义是「Java 侧能绑」——Java 能绑，Node 才可能绑。
     */
    /**
     * 选绑定地址。
     *
     * dsh 的 --host **拒绝通配地址**（0.0.0.0 会被直接拒绝），只能绑某个具体接口地址。
     * 我们优先绑 Tailscale 的地址（tun*，CGNAT 段 100.64.0.0/10）：
     *   - 它是加密 mesh 内网，只有被授权的设备能连，比绑 WiFi 局域网安全
     *   - 绑上之后电脑/平板浏览器可直接访问 dsh 的完整 UI，绕开系统 WebView 版本限制
     * 拿不到 Tailscale（没开/掉线）则退回 127.0.0.1 —— 绝不退回 wlan0，
     * 否则等于把服务暴露给整个局域网。
     */
    /**
     * 设备是否有 root 通道。
     * 只做二进制存在性检查（不真跑 su）—— 真跑会拖慢启动，而插件只用它决定
     * 「是否注册 android_* 工具」，误判的代价很低（工具调用时还会再失败一次）。
     * 覆盖 Magisk / KernelSU / APatch 的常见路径。
     */
    private fun hasRootChannel(): Boolean = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/debug_ramdisk/su",
        "/data/adb/ksu/bin/su",
        "/data/adb/ap/bin/su",
    ).any { File(it).exists() }

    private fun pickBindHost(): Pair<String, String> {
        try {
            val nifs = java.net.NetworkInterface.getNetworkInterfaces()
            while (nifs.hasMoreElements()) {
                val nif = nifs.nextElement()
                val nm = nif.name.lowercase()
                if (!nif.isUp || nif.isLoopback) continue
                if (!(nm.startsWith("tun") || nm.contains("tailscale"))) continue
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (a is java.net.Inet4Address && !a.isLoopbackAddress) {
                        val h = a.hostAddress
                        if (h != null) return h to "tailscale:$nm"
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return "127.0.0.1" to "fallback:loopback"
    }

    private fun probePorts(host: String): Pair<Int?, List<String>> {
        val candidates = listOf(13081, 13082, 13091, 25080, 35080, 45678)
        val report = mutableListOf<String>()
        for (p in candidates) {
            try {
                java.net.ServerSocket(p, 8, java.net.InetAddress.getByName(host)).use { }
                report.add("$p OK")
                return p to report
            } catch (e: Exception) {
                report.add("$p FAIL ${e.javaClass.simpleName}")
            }
        }
        return null to report
    }

    /**
     * 启动引擎。
     *
     * 端口由 probePorts 先探再定。不用 `--port 0`：实测在 App 域下 dsh 会完全不绑端口
     * 且不写任何日志（进程活着但服务没起来）；固定端口则稳定可用。
     * 选端口时要避开旧壳占用的 3080/3081 —— 绑被占端口时 Node 报 EACCES 而非
     * EADDRINUSE，看起来像权限问题（踩过一次）。
     */
    fun start(ctx: Context, scope: CoroutineScope) {
        lastCtx = ctx
        if (_state.value is EngineState.Running || _state.value is EngineState.Starting) {
            Log.w(TAG, "already starting/running, ignore")
            return
        }

        val missing = preflight(ctx)
        if (missing.isNotEmpty()) {
            _state.value = EngineState.Failed("环境不全：" + missing.joinToString("；"))
            return
        }

        // 先定绑定地址，再用**同一个地址**探端口。
        // 顺序反了会误判：127.0.0.1 空闲不代表 Tailscale 地址也空闲，反之亦然。
        val (bindHost, hostWhy) = pickBindHost()
        appendLog("绑定地址 $bindHost ($hostWhy)")

        // 探端口：把真实可绑性写进日志，别再让 SELinux/权限问题伪装成端口冲突
        val (freePort, portReport) = probePorts(bindHost)
        appendLog("端口探测($bindHost): " + portReport.joinToString(" | "))
        if (freePort == null) {
            _state.value = EngineState.Failed(
                "本进程无法在 $bindHost 上绑定任何 TCP 端口\n" + portReport.joinToString("\n")
            )
            return
        }
        appendLog("选用端口 $freePort")

        _state.value = EngineState.Starting
        appendLog("=== 启动引擎 (host=$bindHost port=$freePort) ===")

        val myGen = ++generation
        readerJob = scope.launch(Dispatchers.IO) {
            try {
                doStart(ctx, freePort, bindHost, myGen)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 协程被主动取消（restart/stop）—— 这是正常控制流，绝不能当失败
                appendLog("启动协程被取消（gen=$myGen）")
                throw e
            } catch (e: Exception) {
                if (myGen != generation) {
                    appendLog("旧启动异常（gen=$myGen 已被 gen=$generation 取代，忽略）: ${e.message}")
                } else {
                    _state.value = EngineState.Failed("启动异常: ${e.message}")
                    appendLog("启动异常: ${e.message}")
                }
            }
        }
    }

    private suspend fun doStart(ctx: Context, port: Int, bindHost: String, myGen: Int) = withContext(Dispatchers.IO) {
        val payload = payloadDir(ctx)
        val dshHome = dshHomeDir(ctx).apply { mkdirs() }
        val runtime = runtimeDir(ctx).apply { mkdirs() }
        ipcDir(ctx).mkdirs()
        File(ctx.cacheDir, "tmp").mkdirs()

        val authority = "$bindHost:$port"
        val cmd = listOf(
            nodeBin(ctx).absolutePath,
            "--expose-internals",          // ← 缺这个引擎起不来
            dshEntry(ctx).absolutePath,
            "web",
            "--host", bindHost,            // 具体接口地址；通配地址会被 dsh 拒绝
            "--no-open",
            "--port", port.toString(),
            // /api 的浏览器信任栅栏默认只认回环；绑非回环地址时必须显式放行这个权威，
            // 否则浏览器端 API 请求会被拒（页面能开、功能不通）。
            "--trusted-host", authority,
        )
        appendLog("cmd: ${cmd.joinToString(" ")}")

        // 日志文件在 fork 之前就创建 —— 否则 ProcessBuilder 抛异常时什么都留不下
        val logFile = File(runtime, "engine.log")
        try {
            logFile.writeText("")
        } catch (_: IOException) {
        }

        val pb = ProcessBuilder(cmd)
        pb.directory(payload)
        pb.redirectErrorStream(true)

        // ★ 环境变量显式构造（不能靠继承）
        // TMPDIR 必须「当前用户所有 + 只允许自己读写执行」：
        // 否则 spill-local 会警告 unsafe root（祖先目录权限不达标）并跳过溢写。
        val tmpDir = File(ctx.cacheDir, "tmp").apply {
            mkdirs()
            setReadable(false, false); setReadable(true, true)
            setWritable(false, false); setWritable(true, true)
            setExecutable(false, false); setExecutable(true, true)
        }

        pb.environment().apply {
            put("PATH", "${payload}/bin:${payload}/runtime/bin:/system/bin")
            put("LD_LIBRARY_PATH", "${payload}/runtime/lib")
            put("TMPDIR", tmpDir.absolutePath)
            put("OPENSSL_CONF", "${payload}/runtime/etc/openssl.cnf")
            put("SSL_CERT_FILE", "${payload}/runtime/etc/cacert.pem")
            put("DSH_HOME", dshHome.absolutePath)
            put("HOME", ctx.filesDir.absolutePath)

            // ★ 安卓能力插件（tool-android / tool-vscreen）的安全设计：
            // 拿不到特权通道就不注册任何 android_* 工具，避免 AI 反复撞墙。
            // 所以这里必须如实告知 —— 用二进制存在性探测，不真跑 su（省启动时间）。
            if (hasRootChannel()) put("ROOT_AVAILABLE", "1")

            // AI 的文件/shell 操作根目录：App 私有 workspace。
            // 不设则回退引擎进程目录（= payload），在 payload 里写东西会污染内核树。
            val workspace = File(ctx.filesDir, "workspace").apply { mkdirs() }
            put("DSH_WORKSPACE", workspace.absolutePath)
            // 日志脱敏：绝不把密钥类变量透给子进程
            keys.filter { k ->
                k.contains("KEY", true) || k.contains("SECRET", true) ||
                    k.contains("TOKEN", true) || k.contains("PASSWORD", true)
            }.forEach { remove(it) }
        }

        val proc = pb.start()
        process = proc
        val pid = pidOf(proc)
        appendLog("pid=$pid")
        _state.value = EngineState.Starting

        val reader = proc.inputStream.bufferedReader()
        var gotUrl = false

        try {
            reader.forEachLine { line ->
                appendLog(line)
                try {
                    logFile.appendText(line + "\n")
                } catch (_: IOException) {
                }

                if (!gotUrl) {
                    URL_REGEX.find(line)?.let { m ->
                        val token = m.groupValues[3]
                        val p = m.groupValues[2].toIntOrNull()

                        if (p == null) {
                            appendLog("!! 日志里的端口解析失败: ${line.take(120)}")
                        } else {
                            // 抓到 URL 不等于服务真的能用 —— 再做一次 HTTP 确认。
                            // 这是「进程存在 ≠ 服务可用」那条教训的延伸：
                            // 日志打出 URL ≠ 端口真的在听（可能端口刚被抢、进程正在崩）。
                            // 探测只作诊断，不决定就绪。
                            // 引擎能打印带 token 的 URL 本身就是就绪信号（那是它在
                            // listen 成功之后才打的）。探测失败可能只是探针自身在 App
                            // 进程里的行为问题 —— 不能因为探针坏了就把好引擎判死。
                            val code = httpProbe(bindHost, p)
                            gotUrl = true

                            // ★ 稳定状态文件：路径固定，内容可变。
                            // 外部订阅方（iptables 转发脚本、信箱直投）读这两个文件，
                            // 而不是去猜端口 —— 这是零八 2026-10-29 审阅时明确要求的。
                            File(runtime, "port").writeText(p.toString())
                            File(runtime, "host").writeText(bindHost)
                            File(runtime, "token").writeText(token)
                            File(runtime, "pid").writeText(pid.toString())

                            _state.value = EngineState.Running(pid, p, token, bindHost)
                            appendLog("=== 就绪: port=$p probe=$code ===")
                        }
                    }
                }
            }
        } finally {
            val exit = try {
                proc.waitFor()
            } catch (_: InterruptedException) {
                -1
            }
            appendLog("引擎进程退出，code=$exit")
            // ★ 2026-10-30 修竞态：只有「退出的仍然是当前登记的进程」才做善后。
            //
            //   restart() 会先把 process 置空、再起新的，而旧引擎的 finally 会晚一步跑到这里。
            //   不加这个判断的话：
            //     ① 会把「正在启动的新引擎」误判成 Failed（状态被旧进程的死亡覆盖）
            //     ② 还会把 process 置空，让新引擎从此无人接管（健康检查也看不到了）
            //   实测证据：19:03:31 外部重启后立刻变成「启动失败」，
            //   白等了 15 秒退避才重新拉起 —— 就是这条。
            if (process === proc && myGen == generation) {
                if (_state.value is EngineState.Running || _state.value is EngineState.Starting) {
                    _state.value = EngineState.Failed("进程退出 code=$exit（见下方日志）")
                }
                process = null
                try {
                    File(runtime, "port").delete()
                    File(runtime, "host").delete()
                    File(runtime, "token").delete()
                } catch (_: Exception) {
                }
            } else {
                appendLog("（这是旧引擎的退出，当前已有新实例，忽略）")
            }
        }
    }

    // ---------- 停止 ----------

    fun stop() {
        val p = process ?: return
        generation++
        appendLog("=== 停止引擎（gen=$generation）===")
        try {
            p.destroy()                       // SIGTERM 先礼
            val exited = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            if (!exited) {
                appendLog("5 秒未退出，强杀")
                p.destroyForcibly()           // 再兵
            }
        } catch (e: Exception) {
            appendLog("停止异常: ${e.message}")
        }
        process = null
        readerJob?.cancel()
        _state.value = EngineState.Idle
    }

    // ---------- 辅助 ----------

    /**
     * 记录一行日志。
     *
     * ★ 2026-10-30 修：原来只写内存 LiveData（`_log.value`），从 adb shell 侧
     * 完全看不到 App 内部发生了什么 —— 今天因此把「watcher 到底有没有动作」
     * 判断错了一次，白绕一圈。现在同时追加到 `runtime/engine-host.log`。
     */
    private fun appendLog(line: String) {
        _log.value = (_log.value + line).takeLast(400)
        val ctx = lastCtx ?: return
        try {
            val f = File(runtimeDir(ctx), "engine-host.log")
            val ts = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date())
            f.appendText("$ts  $line\n")
            if (f.length() > 256 * 1024) {
                f.writeText(f.readLines().takeLast(1000).joinToString("\n") + "\n")
            }
        } catch (_: Exception) {
        }
    }

    /**
     * 强制重启：先杀掉当前引擎（不管它是死在半路还是卡住），重置状态再启动。
     *
     * ★ 2026-10-30 新增。为什么要它：
     *   `onStartCommand` 里「状态是 Failed 就不动」的旧逻辑，加上 watcher 只重试一次，
     *   会让 App 卡死在 Failed —— 进程活、服务活、端口 000、没有任何东西能救。
     *   这个方法给外部（EngineService 的文件轮询、UI 按钮）一个确定可用的重置入口。
     */
    fun restart(ctx: Context, scope: CoroutineScope) {
        // ★ 关键：先递增代号，把「所有在飞的旧启动」一次性作废。
        //   如果等到 start() 里才 ++，旧协程会在 cancel 之后、递增之前抢先跑 catch，
        //   那时它看到的 generation 还是自己那个号，就会把自己判成失败并把状态覆盖掉。
        //   实测证据（v0.8.3）：19:13:27 强制重启 → 19:13:30 仍报
        //   「启动失败 · 启动异常: read interrupted」，就是这个时序。
        generation++
        appendLog("=== 强制重启（外部请求，gen=$generation）===")
        val p = process
        process = null
        try {
            p?.destroy()
            if (p?.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) == false) {
                p.destroyForcibly()
                appendLog("旧引擎 2 秒未退出，已强杀")
            }
        } catch (e: Exception) {
            appendLog("杀旧引擎异常: ${e.message}")
        }
        readerJob?.cancel()
        readerJob = null
        // 关键：把状态复位成 Idle，否则 start() 的守卫会挡住
        _state.value = EngineState.Idle
        start(ctx, scope)
    }

    /**
     * 健康检查：状态是 Running 时，真的去探一下端口能不能应答。
     *
     * ★ 2026-10-30 新增。为什么需要它：
     *   `_state == Running` 只说明「当初起来过」，不说明「现在还活着」。
     *   进程被杀、被冻结太久导致事件循环停摆、端口被占 —— 这几种情况下
     *   状态还是 Running，但服务已经没了。零八在旧壳审出过同一个 bug：
     *   `ps | grep` 永远命中自己 → 永远判「在跑」。
     *
     * 返回 null 表示健康（或当前不是 Running，无需检查）；
     * 返回字符串表示不健康的原因。
     */
    fun healthProblem(): String? {
        val st = _state.value
        if (st !is EngineState.Running) return null
        // 进程层：Process 对象还在吗
        if (process?.isAlive != true) {
            return "进程已不在（状态仍为 Running）"
        }
        // 服务层：端口真的应答吗
        val code = httpProbe(st.host, st.port)
        if (code < 0) {
            return "端口 ${st.port} 无应答（进程在但服务僵死）"
        }
        return null
    }

    /**
     * 取子进程 pid。
     *
     * java.lang.Process.pid() 是 Java 9 才有的，安卓上没有 —— 直接用会
     * Unresolved reference。安卓的 ProcessImpl/UNIXProcess 有私有字段 pid，
     * 反射读它；读不到就返回 -1（pid 只用于展示和写 runtime/pid，缺了不影响启动）。
     */
    /**
     * 探测端口是否真的在服务。
     *
     * 用裸 Socket 发一个最小 HTTP 请求，不用 HttpURLConnection —— 实测后者在 App
     * 进程里对 127.0.0.1 探测会失败（引擎明明在 LISTEN、curl 也返回 401），
     * 却探不出来。裸 Socket 不经过任何网络栈配置，最接近内核事实。
     *
     * 返回状态码；-1 表示连不上。
     */
    private fun httpProbe(host: String, port: Int): Int = try {
        java.net.Socket().use { sock ->
            sock.connect(java.net.InetSocketAddress(host, port), 3000)
            sock.soTimeout = 3000
            sock.getOutputStream().apply {
                write("GET / HTTP/1.0\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray())
                flush()
            }
            val buf = ByteArray(64)
            val n = sock.getInputStream().read(buf)
            if (n <= 0) {
                -1
            } else {
                // 形如 "HTTP/1.1 401 Unauthorized"
                val head = String(buf, 0, n)
                if (!head.startsWith("HTTP/")) {
                    -1
                } else {
                    head.split(" ").getOrNull(1)?.toIntOrNull() ?: -1
                }
            }
        }
    } catch (_: Exception) {
        -1
    }

    private fun pidOf(p: Process): Int = try {
        val f = p.javaClass.getDeclaredField("pid")
        f.isAccessible = true
        f.getInt(p)
    } catch (_: Throwable) {
        -1
    }
}

sealed interface EngineState {
    data object Idle : EngineState
    data object Starting : EngineState
    data class Running(val pid: Int, val port: Int, val token: String, val host: String) : EngineState
    data class Failed(val reason: String) : EngineState
}
