package cc.bestly.dsh

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 代理管理页 —— 阶段 1 + 2
 *
 *  阶段 1：状态（PID / 版本 / 配置）+ 启停
 *  阶段 2：节点分组列表 + 切换 + 测速
 *
 * 设计约束（docs/design/proxy-ui.md）：
 *  - 代理独立于 App：App 关了 mihomo 继续跑
 *  - 本页只是控制面板，不管理进程生命周期
 *  - 端口 / 路径 / 版本运行时查，不写死
 *  - 状态从 mihomo API 拿，不在 UI 里重算
 */
class ProxyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("dsh_prefs", MODE_PRIVATE)
        val dark = when (prefs.getInt("theme_mode", 0)) {
            1 -> false
            2 -> true
            else -> (resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        setContent {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ProxyScreen(onBack = { finish() })
                }
            }
        }
    }
}

/** 代理运行状态（全部来自实测，不缓存猜测） */
data class ProxyStatus(
    val running: Boolean,
    val pid: String = "",
    val version: String = "",
    val configPath: String = "",
    val error: String = ""
)

/** 一个代理分组（Selector / Fallback / URLTest） */
data class ProxyGroup(
    val name: String,
    val type: String,
    val now: String,
    val members: List<String>
)

/** 某个 App 的分流状态 */
data class ProxyApp(
    val pkg: String,
    val uid: Int,
    val enabled: Boolean
)

@Composable
fun ProxyScreen(onBack: () -> Unit) {
    var status by remember { mutableStateOf<ProxyStatus?>(null) }
    var groups by remember { mutableStateOf<List<ProxyGroup>>(emptyList()) }
    var apps by remember { mutableStateOf<List<ProxyApp>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    // key = 节点名 / 分组名, value = 毫秒（<0 表示失败）
    val delays = remember { mutableStateMapOf<String, Int>() }
    val scope = rememberCoroutineScope()

    suspend fun refresh() {
        status = probeStatus()
        groups = if (status?.running == true) fetchGroups() else emptyList()
        apps = if (status?.running == true) fetchApps() else emptyList()
    }

    suspend fun control(start: Boolean) {
        busy = true
        message = ""
        try {
            message = if (start) startProxy() else stopProxy()
        } catch (e: Exception) {
            message = "失败：${e.message}"
        } finally {
            busy = false
            delay(300)
            refresh()
        }
    }

    suspend fun switchNode(group: String, node: String) {
        busy = true
        message = ""
        try {
            val body = JSONObject().put("name", node).toString()
            message = apiPut("/proxies/${URLEncoder.encode(group, "UTF-8")}", body)
            delay(300)
            refresh()
        } catch (e: Exception) {
            message = "切换失败：${e.message}"
        } finally {
            busy = false
        }
    }

    suspend fun testAll() {
        busy = true
        message = "测速中…"
        try {
            val groupNames = groups.map { it.name }.toSet()
            val realNodes = groups
                .flatMap { it.members }
                .distinct()
                .filter { it !in groupNames && it != "DIRECT" && it != "REJECT" }
            realNodes.forEach { delays[it] = apiDelay(it) }
            // 顺带测分组（取组内代表延迟由内核决定，这里只标 API 可达）
            message = "测速完成（${realNodes.size} 个节点）"
        } catch (e: Exception) {
            message = "测速失败：${e.message}"
        } finally {
            busy = false
        }
    }

    suspend fun toggleApp(app: ProxyApp) {
        busy = true
        message = ""
        try {
            val tool = "/data/adb/dsh-tools/proxy-app"
            val verb = if (app.enabled) "off" else "on"
            val out = rootExec("$tool $verb ${app.pkg}")
            message = out.trim().ifBlank {
                if (app.enabled) "已关闭 ${app.pkg}" else "已开启 ${app.pkg}"
            }
            apps = fetchApps()
        } catch (e: Exception) {
            message = "切换失败：${e.message}"
        } finally {
            busy = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("代理", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("阶段 1+2+3 · 状态 / 节点 / App 分流", fontSize = 12.sp, color = Color.Gray)
            }
            TextButton(onClick = onBack) { Text("返回", fontSize = 11.sp) }
        }
        Spacer(Modifier.height(14.dp))

        // ---------- 状态卡 ----------
        val s = status
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    s == null -> Color(0xFF555555)
                    s.running -> Color(0xFF2E7D32)
                    else -> Color(0xFF8D4A4A)
                }
            )
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    when {
                        s == null -> "探测中…"
                        s.running -> "运行中"
                        else -> "已停止"
                    },
                    fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    when {
                        s == null -> "正在读取代理状态"
                        s.running -> "mihomo 进程存活，可被 App / 系统分流使用"
                        else -> "mihomo 未运行，所有流量直连"
                    },
                    fontSize = 12.sp, color = Color.White.copy(alpha = 0.85f)
                )
                if (s != null && s.running) {
                    Spacer(Modifier.height(10.dp))
                    if (s.pid.isNotBlank()) {
                        Text("PID      ${s.pid}", fontSize = 12.sp, color = Color.White, fontFamily = FontFamily.Monospace)
                    }
                    if (s.version.isNotBlank()) {
                        Text("VERSION  ${s.version}", fontSize = 11.sp, color = Color.White, fontFamily = FontFamily.Monospace)
                    }
                    if (s.configPath.isNotBlank()) {
                        Text("CONFIG   ${s.configPath}", fontSize = 11.sp, color = Color.White, fontFamily = FontFamily.Monospace)
                    }
                }
                if (s != null && s.error.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(s.error, fontSize = 11.sp, color = Color(0xFFFFCDD2))
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { scope.launch { refresh() } }, enabled = !busy) {
                Text("刷新", fontSize = 13.sp)
            }
            Button(
                onClick = { scope.launch { control(start = !(s?.running == true)) } },
                enabled = !busy
            ) {
                Text(if (s?.running == true) "停止" else "启动", fontSize = 13.sp)
            }
            OutlinedButton(onClick = { scope.launch { testAll() } }, enabled = !busy && groups.isNotEmpty()) {
                Text("测速", fontSize = 13.sp)
            }
        }

        // ---------- 节点分组 ----------
        if (groups.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            Text("节点", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))

            groups.forEach { g ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(g.name, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text("  [${g.type}]", fontSize = 10.sp, color = Color.Gray)
                        }
                        Text("当前: ${g.now}", fontSize = 11.sp, color = Color.Gray)
                        Spacer(Modifier.height(8.dp))
                        g.members.forEach { m ->
                            val selected = (m == g.now)
                            // Fallback / URLTest 组由内核自动选，手动 PUT 会被健康检查覆盖
                            val canSwitch = (g.type == "Selector")
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !busy && !selected && canSwitch) {
                                        scope.launch { switchNode(g.name, m) }
                                    }
                                    .padding(vertical = 6.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .background(
                                            if (selected) Color(0xFF4CAF50) else Color(0xFF666666),
                                            CircleShape
                                        )
                                )
                                Text(
                                    "  $m",
                                    fontSize = 13.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (canSwitch) MaterialTheme.colorScheme.onSurface
                                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                                if (!canSwitch) {
                                    Text("  自动", fontSize = 10.sp, color = Color.Gray)
                                }
                                val d = delays[m]
                                if (d != null) {
                                    Text(
                                        if (d >= 0) "  ${d}ms" else "  失败",
                                        fontSize = 11.sp,
                                        color = if (d >= 0) Color(0xFF81C784) else Color(0xFFE57373),
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text("App 分流", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(2.dp))
        Text(
            "开关立即生效，只对选中的 App 走代理（iptables 按 UID）",
            fontSize = 11.sp, color = Color.Gray
        )
        Spacer(Modifier.height(8.dp))
        if (apps.isEmpty()) {
            Text("（未读到第三方 App，或代理未运行）", fontSize = 11.sp, color = Color.Gray)
        } else {
            apps.forEach { app ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(app.pkg, fontSize = 12.sp)
                        Text(
                            "uid ${app.uid}",
                            fontSize = 10.sp, color = Color.Gray, fontFamily = FontFamily.Monospace
                        )
                    }
                    Switch(
                        checked = app.enabled,
                        enabled = !busy,
                        onCheckedChange = { scope.launch { toggleApp(app) } }
                    )
                }
            }
        }

        if (message.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(message, fontSize = 11.sp, color = Color(0xFF90CAF9))
        }
    }
}

// ---------------- 后端（全部走 root / 本地 API） ----------------

private const val PROXY_DIR = "/data/adb/proxy"
private const val MIHOMO = "$PROXY_DIR/mihomo"
private const val CONFIG = "$PROXY_DIR/config.yaml"
private const val SERVICE_SCRIPT = "/data/adb/service.d/30-proxy.sh"

/** 执行 root 命令，返回 stdout（失败返回空串） */
private suspend fun rootExec(cmd: String): String = withContext(Dispatchers.IO) {
    try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        val out = BufferedReader(InputStreamReader(p.inputStream)).use { it.readText() }
        p.waitFor()
        out.trim()
    } catch (e: Exception) {
        ""
    }
}

/**
 * 从 config.yaml 读控制 API 地址与 secret。
 * 注意：YAML 里的值可能带引号（实测 secret 带引号会让 Bearer 校验失败），必须剥掉。
 */
private suspend fun apiEndpoint(): Pair<String, String> = withContext(Dispatchers.IO) {
    val ctrlRaw = rootExec("sed -n 's/^external-controller: *//p' $CONFIG")
    val secretRaw = rootExec("sed -n 's/^secret: *//p' $CONFIG")
    val strip = { v: String -> v.replace("\"", "").replace("'", "").replace("\r", "").trim() }
    val ctrl = strip(ctrlRaw)
    val secret = strip(secretRaw)
    val host = if (ctrl.isBlank()) "127.0.0.1:9090" else ctrl
    (if (host.startsWith("http")) host else "http://$host") to secret
}

private suspend fun apiGet(path: String): String = withContext(Dispatchers.IO) {
    val (base, secret) = apiEndpoint()
    val conn = (URL("$base$path").openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 5000
        readTimeout = 10000
        setRequestProperty("Authorization", "Bearer $secret")
    }
    try {
        conn.inputStream.bufferedReader().use { it.readText() }
    } finally {
        conn.disconnect()
    }
}

private suspend fun apiPut(path: String, body: String): String = withContext(Dispatchers.IO) {
    val (base, secret) = apiEndpoint()
    val conn = (URL("$base$path").openConnection() as HttpURLConnection).apply {
        requestMethod = "PUT"
        connectTimeout = 5000
        readTimeout = 10000
        doOutput = true
        setRequestProperty("Authorization", "Bearer $secret")
        setRequestProperty("Content-Type", "application/json")
    }
    try {
        conn.outputStream.use { it.write(body.toByteArray()) }
        val code = conn.responseCode
        if (code in 200..299) "已切换" else "HTTP $code"
    } finally {
        conn.disconnect()
    }
}

/** 拉取所有分组（Selector / Fallback / URLTest / LoadBalance） */
private suspend fun fetchGroups(): List<ProxyGroup> = withContext(Dispatchers.IO) {
    try {
        val obj = JSONObject(apiGet("/proxies")).getJSONObject("proxies")
        val result = mutableListOf<ProxyGroup>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val p = obj.getJSONObject(name)
            val type = p.optString("type")
            if (type == "Selector" || type == "Fallback" || type == "URLTest" || type == "LoadBalance") {
                val members = mutableListOf<String>()
                val all = p.optJSONArray("all")
                if (all != null) for (i in 0 until all.length()) members.add(all.getString(i))
                result.add(ProxyGroup(name, type, p.optString("now", "-"), members))
            }
        }
        // PROXY 最上，GLOBAL 最下，其余居中
        result.sortedBy {
            when (it.name) {
                "PROXY" -> 0
                "GLOBAL" -> 2
                else -> 1
            }
        }
    } catch (e: Exception) {
        emptyList()
    }
}

/** 第三方 App 及其分流状态（UID 从 iptables 规则里读） */
private suspend fun fetchApps(): List<ProxyApp> = withContext(Dispatchers.IO) {
    try {
        val pkgs = rootExec("pm list packages -3 -U")
        val rules = rootExec("iptables -t nat -L OUTPUT -n 2>/dev/null | grep -F 'com.dsh.proxy'")
        val enabledUids = Regex("UID match (\\d+)")
            .findAll(rules).map { it.groupValues[1] }.toSet()
        val rx = Regex("package:(\\S+)\\s+uid:(\\d+)")
        pkgs.lineSequence().mapNotNull { line ->
            val m = rx.find(line) ?: return@mapNotNull null
            ProxyApp(
                pkg = m.groupValues[1],
                uid = m.groupValues[2].toIntOrNull() ?: return@mapNotNull null,
                enabled = m.groupValues[2] in enabledUids
            )
        }.sortedBy { it.pkg }.toList()   // 固定按包名排序：动态重排会让用户点错行
    } catch (e: Exception) {
        emptyList()
    }
}

/** 测单节点延迟；失败返回 -1 */
private suspend fun apiDelay(node: String): Int = withContext(Dispatchers.IO) {
    try {
        val testUrl = "https://www.gstatic.com/generate_204"
        val path = "/proxies/${URLEncoder.encode(node, "UTF-8")}/delay" +
                "?timeout=5000&url=${URLEncoder.encode(testUrl, "UTF-8")}"
        val obj = JSONObject(apiGet(path))
        if (obj.has("delay")) obj.getInt("delay") else -1
    } catch (e: Exception) {
        -1
    }
}

private suspend fun probeStatus(): ProxyStatus = withContext(Dispatchers.IO) {
    try {
        val pid = rootExec("pidof mihomo")
        if (pid.isBlank()) return@withContext ProxyStatus(running = false)
        ProxyStatus(
            running = true,
            pid = pid.split(" ").first(),
            version = rootExec("$MIHOMO -v 2>/dev/null | head -1"),
            configPath = rootExec("ls $CONFIG 2>/dev/null")
        )
    } catch (e: Exception) {
        ProxyStatus(running = false, error = "探测失败：${e.message}")
    }
}

private suspend fun startProxy(): String = withContext(Dispatchers.IO) {
    if (rootExec("pidof mihomo").isNotBlank()) return@withContext "已在运行，无需启动"
    if (rootExec("ls $SERVICE_SCRIPT 2>/dev/null").isBlank()) {
        return@withContext "缺少启动脚本 $SERVICE_SCRIPT"
    }
    rootExec("sh $SERVICE_SCRIPT >/dev/null 2>&1 &")
    delay(2500)
    if (rootExec("pidof mihomo").isNotBlank()) "已启动" else "启动失败（看 $PROXY_DIR/mihomo.log）"
}

private suspend fun stopProxy(): String = withContext(Dispatchers.IO) {
    val pid = rootExec("pidof mihomo")
    if (pid.isBlank()) return@withContext "本来就没在跑"
    rootExec("kill $pid")
    delay(800)
    if (rootExec("pidof mihomo").isBlank()) "已停止（开机自启脚本还在，重启会再起来）"
    else "停止失败（PID $pid 仍存活）"
}
