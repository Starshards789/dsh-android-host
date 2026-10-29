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
    private val URL_REGEX = Regex("""http://127\.0\.0\.1:(\d+)/\?token=([A-Za-z0-9_\-]+)""")

    private var process: Process? = null
    private var readerJob: Job? = null

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

    fun start(ctx: Context, scope: CoroutineScope, port: Int = 3081) {
        if (_state.value is EngineState.Running || _state.value is EngineState.Starting) {
            Log.w(TAG, "already starting/running, ignore")
            return
        }

        val missing = preflight(ctx)
        if (missing.isNotEmpty()) {
            _state.value = EngineState.Failed("环境不全: " + missing.joinToString("; "))
            return
        }

        _state.value = EngineState.Starting
        appendLog("=== 启动引擎 (port=$port) ===")

        readerJob = scope.launch(Dispatchers.IO) {
            try {
                doStart(ctx, port)
            } catch (e: Exception) {
                Log.e(TAG, "start failed", e)
                _state.value = EngineState.Failed(e.message ?: e.toString())
                appendLog("启动异常: ${e.message}")
            }
        }
    }

    private suspend fun doStart(ctx: Context, port: Int) = withContext(Dispatchers.IO) {
        val payload = payloadDir(ctx)
        val dshHome = dshHomeDir(ctx).apply { mkdirs() }
        val runtime = runtimeDir(ctx).apply { mkdirs() }
        ipcDir(ctx).mkdirs()
        File(ctx.cacheDir, "tmp").mkdirs()

        val cmd = listOf(
            nodeBin(ctx).absolutePath,
            "--expose-internals",          // ← 缺这个引擎起不来
            dshEntry(ctx).absolutePath,
            "web",
            "--host", "127.0.0.1",
            "--port", port.toString(),
        )
        appendLog("cmd: ${cmd.joinToString(" ")}")

        val pb = ProcessBuilder(cmd)
        pb.directory(payload)
        pb.redirectErrorStream(true)

        // ★ 环境变量显式构造（不能靠继承）
        pb.environment().apply {
            put("PATH", "${payload}/bin:${payload}/runtime/bin:/system/bin")
            put("LD_LIBRARY_PATH", "${payload}/runtime/lib")
            put("TMPDIR", File(ctx.cacheDir, "tmp").absolutePath)
            put("OPENSSL_CONF", "${payload}/runtime/etc/openssl.cnf")
            put("SSL_CERT_FILE", "${payload}/runtime/etc/cacert.pem")
            put("DSH_HOME", dshHome.absolutePath)
            put("HOME", ctx.filesDir.absolutePath)
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

        val logFile = File(runtime, "engine.log")
        logFile.writeText("")   // 每次启动清空

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
                        gotUrl = true
                        val p = m.groupValues[1].toIntOrNull() ?: port
                        val token = m.groupValues[2]

                        // ★ 稳定状态文件：路径固定，内容可变。
                        // 外部订阅方（iptables 转发脚本、信箱直投）读这两个文件，
                        // 而不是去猜端口 —— 这是零八 2026-10-29 审阅时明确要求的。
                        File(runtime, "port").writeText(p.toString())
                        File(runtime, "token").writeText(token)
                        File(runtime, "pid").writeText(pid.toString())

                        _state.value = EngineState.Running(pid, p, token)
                        appendLog("=== 就绪: port=$p ===")
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
            if (_state.value is EngineState.Running || _state.value is EngineState.Starting) {
                _state.value = EngineState.Failed("进程退出 code=$exit（见下方日志）")
            }
            process = null
            try {
                File(runtime, "port").delete()
                File(runtime, "token").delete()
            } catch (_: Exception) {
            }
        }
    }

    // ---------- 停止 ----------

    fun stop() {
        val p = process ?: return
        appendLog("=== 停止引擎 ===")
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

    private fun appendLog(line: String) {
        _log.value = (_log.value + line).takeLast(400)
    }

    /** Process.pid() 要 API 26+，我们 minSdk 34 所以直接用 */
    private fun pidOf(p: Process): Int = try {
        p.pid().toInt()
    } catch (_: Throwable) {
        -1
    }
}

sealed interface EngineState {
    data object Idle : EngineState
    data object Starting : EngineState
    data class Running(val pid: Int, val port: Int, val token: String) : EngineState
    data class Failed(val reason: String) : EngineState
}
