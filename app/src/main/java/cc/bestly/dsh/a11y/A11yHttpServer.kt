package cc.bestly.dsh.a11y

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * 本地 HTTP 桥（127.0.0.1:3181），把 dsh-tool-accessibility 插件的请求
 * 转给 DshA11yService。
 *
 * 为什么要多一层 HTTP，而不是插件直接 bind 服务：
 * 插件的 JS 跑在引擎进程里，拿不到 Android 的 AccessibilityNodeInfo；
 * 它只认「本地 HTTP + JSON」这个契约。这层桥就是把 Android 对象翻译成
 * 插件能读的 JSON。
 *
 * ★ 只绑 127.0.0.1：本机任何应用都能连这个端口，所以 Token 是必须的
 *   （插件会带 X-DSH-Token 头）。没 Token 的 Android 版本靠端口隔离，
 *   Android 10 之后本机应用可以直接连 localhost，不鉴权就是后门。
 */
object A11yHttpServer {

    private const val TAG = "DshA11yHttp"

    /** 插件默认端口，通过 APP_A11Y_PORT 传给引擎。 */
    const val PORT = 3181

    private const val PREFS = "dsh_a11y"
    private const val KEY_TOKEN = "local_token"

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var running = false

    private val pool = Executors.newFixedThreadPool(4)

    /**
     * 取（必要时生成）本机令牌。
     *
     * 为什么持久化：引擎重启后插件会重新读 APP_LOCAL_TOKEN，
     * 如果每次 App 启动都换新值，必须重启引擎才能对齐 —— 那是无谓的耦合。
     * 存 SharedPreferences，App 和引擎都读同一个值。
     */
    @Synchronized
    fun token(ctx: Context): String {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_TOKEN, null)?.let { if (it.length >= 32) return it }
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val tok = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString(KEY_TOKEN, tok).apply()
        Log.i(TAG, "已生成本机令牌 len=${tok.length}")
        return tok
    }

    /** 启动。已启动则直接返回。 */
    @Synchronized
    fun start(ctx: Context) {
        if (running) return
        val tok = token(ctx)
        try {
            val ss = ServerSocket(PORT, 50, InetAddress.getByName("127.0.0.1"))
            serverSocket = ss
            running = true
            Thread {
                Log.i(TAG, "无障碍 HTTP 桥已启动 127.0.0.1:$PORT")
                while (running) {
                    val client = try {
                        ss.accept()
                    } catch (e: Exception) {
                        if (running) Log.w(TAG, "accept 失败: ${e.message}")
                        break
                    }
                    pool.execute { handle(client, tok) }
                }
            }.apply { isDaemon = true; name = "a11y-http" }.start()
        } catch (e: Exception) {
            Log.e(TAG, "启动失败（端口 $PORT 被占用？）: ${e.message}")
        }
    }

    @Synchronized
    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        Log.i(TAG, "无障碍 HTTP 桥已停止")
    }

    // ───────────────────────── 请求处理 ─────────────────────────

    private fun handle(client: Socket, expectedToken: String) {
        client.use { sock ->
            try {
                sock.soTimeout = 15_000
                val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))

                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val fullPath = parts[1]

                // 读头
                val headers = HashMap<String, String>()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0) {
                        headers[line.substring(0, idx).trim().lowercase()] =
                            line.substring(idx + 1).trim()
                    }
                }

                // 读 body（只有 POST /gesture 用到）
                var body = ""
                val cl = headers["content-length"]?.toIntOrNull() ?: 0
                if (cl > 0 && cl <= 65536) {
                    val buf = CharArray(cl)
                    var read = 0
                    while (read < cl) {
                        val n = reader.read(buf, read, cl - read)
                        if (n <= 0) break
                        read += n
                    }
                    body = String(buf, 0, read)
                }

                val resp: String = when {
                    method != "GET" && method != "POST" ->
                        err("只支持 GET/POST")

                    headers["x-dsh-token"] != expectedToken ->
                        err("鉴权失败：X-DSH-Token 不匹配")

                    !DshA11yService.isRunning() ->
                        err("无障碍服务未连接")

                    else -> route(method, fullPath, body)
                }

                writeJson(sock.getOutputStream(), resp)
            } catch (e: Exception) {
                Log.w(TAG, "处理请求异常: ${e.message}")
            }
        }
    }

    private fun route(method: String, fullPath: String, body: String): String {
        val path = fullPath.substringBefore('?')
        val query = parseQuery(fullPath.substringAfter('?', ""))

        when (path) {
            "/status" -> return DshA11yService.instance?.status()?.toString() ?: err("服务未连接")

            "/dump" -> return DshA11yService.instance?.dump()?.toString() ?: err("服务未连接")

            "/tap" -> {
                val svc = DshA11yService.instance ?: return err("服务未连接")
                // 按文字/描述找 -> 否则按坐标点。
                val text = query["text"]
                val desc = query["desc"]
                if (!text.isNullOrEmpty() || !desc.isNullOrEmpty()) {
                    val node = svc.findByText(text ?: desc!!, useDesc = text.isNullOrEmpty())
                        ?: return err("没找到匹配节点：${text ?: desc}")
                    val ok = svc.tapNode(node)
                    return JSONObject()
                        .put("ok", ok)
                        .put("found", true)
                        .put("method", "node")
                        .toString()
                }
                val x = query["x"]?.toIntOrNull()
                val y = query["y"]?.toIntOrNull()
                if (x == null || y == null) return err("需要 text / desc / x+y")
                val ok = svc.tapAt(x, y)
                return JSONObject().put("ok", ok).put("found", true).put("method", "gesture").toString()
            }

            "/hold" -> {
                val svc = DshA11yService.instance ?: return err("服务未连接")
                val x = query["x"]?.toIntOrNull() ?: return err("需要 x")
                val y = query["y"]?.toIntOrNull() ?: return err("需要 y")
                val dur = query["duration"]?.toLongOrNull() ?: 800L
                return JSONObject().put("ok", svc.longPressAt(x, y, dur)).toString()
            }

            "/input" -> {
                val svc = DshA11yService.instance ?: return err("服务未连接")
                val text = query["text"] ?: return err("需要 text")
                return JSONObject().put("ok", svc.inputText(text)).toString()
            }

            "/scroll" -> {
                val svc = DshA11yService.instance ?: return err("服务未连接")
                val dir = query["direction"] ?: query["dir"] ?: "down"
                return JSONObject().put("ok", svc.scroll(dir)).toString()
            }

            "/swipe" -> {
                val svc = DshA11yService.instance ?: return err("服务未连接")
                val x1 = query["x1"]?.toIntOrNull() ?: return err("需要 x1")
                val y1 = query["y1"]?.toIntOrNull() ?: return err("需要 y1")
                val x2 = query["x2"]?.toIntOrNull() ?: return err("需要 x2")
                val y2 = query["y2"]?.toIntOrNull() ?: return err("需要 y2")
                val dur = query["duration"]?.toLongOrNull() ?: 300L
                return JSONObject().put("ok", svc.swipe(x1, y1, x2, y2, dur)).toString()
            }

            // 多点触控（/touch + /touch-status）：插件契约里有，但实现复杂，
            // 先返回「不支持」，让插件走降级路径而不是报错中断。
            "/touch", "/touch-status", "/gesture" ->
                return JSONObject()
                    .put("ok", false)
                    .put("error", "多点触控暂未实现，请用 tap/swipe/scroll")
                    .toString()

            // 截图走 root screencap，返回 base64 PNG。
            "/screenshot" -> return ScreenshotUtil.capture()

            else -> return err("未知端点：$path")
        }
    }

    private fun parseQuery(q: String): Map<String, String> {
        if (q.isEmpty()) return emptyMap()
        return q.split('&').mapNotNull { pair ->
            val i = pair.indexOf('=')
            if (i <= 0) null
            else URLDecoder.decode(pair.substring(0, i), "UTF-8") to
                URLDecoder.decode(pair.substring(i + 1), "UTF-8")
        }.toMap()
    }

    private fun err(msg: String) = JSONObject().put("ok", false).put("error", msg).toString()

    private fun writeJson(out: OutputStream, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }
}
