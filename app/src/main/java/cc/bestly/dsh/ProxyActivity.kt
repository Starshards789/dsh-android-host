package cc.bestly.dsh

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 代理管理页 —— 阶段 1：状态 + 启停
 *
 * 设计约束（见 docs/design/proxy-ui.md）：
 *  - 代理独立于 App：App 关了 mihomo 继续跑
 *  - 本页只是控制面板，不管理进程生命周期
 *  - 端口/路径/版本一律运行时查，不写死
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

@Composable
fun ProxyScreen(onBack: () -> Unit) {
    var status by remember { mutableStateOf<ProxyStatus?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    suspend fun refresh() {
        status = probeStatus()
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
            refresh()
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
                Text("阶段 1 · 状态 + 启停", fontSize = 12.sp, color = Color.Gray)
            }
            TextButton(onClick = onBack) { Text("返回", fontSize = 11.sp) }
        }
        Spacer(Modifier.height(14.dp))

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
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    when {
                        s == null -> "正在读取代理状态"
                        s.running -> "mihomo 进程存活，可被 App / 系统分流使用"
                        else -> "mihomo 未运行，所有流量直连"
                    },
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.85f)
                )

                if (s != null && s.running) {
                    Spacer(Modifier.height(10.dp))
                    if (s.pid.isNotBlank()) {
                        Text("PID      ${s.pid}", fontSize = 12.sp, color = Color.White, fontFamily = FontFamily.Monospace)
                    }
                    if (s.version.isNotBlank()) {
                        Text("VERSION  ${s.version}", fontSize = 12.sp, color = Color.White, fontFamily = FontFamily.Monospace)
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

        Spacer(Modifier.height(14.dp))

        val s0 = status
        val running = s0?.running == true
        val scope = androidx.compose.runtime.rememberCoroutineScope()

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { scope.launch { refresh() } },
                enabled = !busy
            ) { Text("刷新", fontSize = 13.sp) }
            Button(
                onClick = { scope.launch { control(start = !running) } },
                enabled = !busy
            ) {
                Text(if (running) "停止" else "启动", fontSize = 13.sp)
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "阶段 1 只做状态显示与启停；节点切换、App 分流在后续阶段接入。",
            fontSize = 11.sp,
            color = Color.Gray
        )

        if (message.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(message, fontSize = 11.sp, color = Color(0xFF90CAF9))
        }
    }
}

// ---------------- 后端操作（全部走 root） ----------------

private const val PROXY_DIR = "/data/adb/proxy"
private const val MIHOMO = "$PROXY_DIR/mihomo"
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

private suspend fun probeStatus(): ProxyStatus = withContext(Dispatchers.IO) {
    try {
        val pid = rootExec("pidof mihomo")
        if (pid.isBlank()) {
            return@withContext ProxyStatus(running = false)
        }
        val version = rootExec("$MIHOMO -v 2>/dev/null | head -1")
        val config = rootExec("ls $PROXY_DIR/config.yaml 2>/dev/null")
        ProxyStatus(
            running = true,
            pid = pid.split(" ").first(),
            version = version,
            configPath = config
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
    kotlinx.coroutines.delay(2500)
    if (rootExec("pidof mihomo").isNotBlank()) "已启动" else "启动失败（看 /data/adb/proxy 日志）"
}

private suspend fun stopProxy(): String = withContext(Dispatchers.IO) {
    val pid = rootExec("pidof mihomo")
    if (pid.isBlank()) return@withContext "本来就没在跑"
    rootExec("kill $pid")
    kotlinx.coroutines.delay(800)
    if (rootExec("pidof mihomo").isBlank()) "已停止（开机自启脚本还在，重启会再起来）"
    else "停止失败（PID $pid 仍存活）"
}
