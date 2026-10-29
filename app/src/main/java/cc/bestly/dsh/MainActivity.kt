package cc.bestly.dsh

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.bestly.dsh.engine.EngineLauncher
import cc.bestly.dsh.engine.EngineState

/**
 * P1.1 —— 宿主骨架
 *
 * 验收标准：点「启动引擎」→ App 自己 fork 出 dsh 引擎 → 拿到 port + token
 *          → 写入 files/runtime/port 和 files/runtime/token → 外部 curl 能连通。
 *
 * 这一版还不做 WebView（P1.2），也不做 socket 协议（P1.2），目的只有一个：
 * 证明「App 能把引擎拉起来，且状态能被外部稳定观测」。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    EngineScreen(this)
                }
            }
        }
    }
}

@Composable
fun EngineScreen(activity: ComponentActivity) {
    val state by EngineLauncher.state.collectAsState()
    val log by EngineLauncher.log.collectAsState()
    val scope = rememberCoroutineScope()
    val missing = remember { EngineLauncher.preflight(activity) }
    val running = state is EngineState.Running || state is EngineState.Starting

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("dsh host", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("P1.1 · 宿主骨架", fontSize = 12.sp, color = Color.Gray)
        Spacer(Modifier.height(14.dp))

        // ---------- 状态卡 ----------
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = stateColor(state))
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = stateTitle(state),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(Modifier.height(4.dp))
                Text(stateSubtitle(state), fontSize = 12.sp, color = Color.White.copy(alpha = 0.85f))

                val s = state
                if (s is EngineState.Running) {
                    Spacer(Modifier.height(10.dp))
                    Text("PID    ${s.pid}", fontSize = 12.sp, color = Color.White, fontFamily = FontFamily.Monospace)
                    Text("PORT   ${s.port}", fontSize = 12.sp, color = Color.White, fontFamily = FontFamily.Monospace)
                    Text("TOKEN  ${s.token.take(16)}…", fontSize = 12.sp, color = Color.White, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "files/runtime/port 与 /token 已写入",
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // ---------- 环境自检 ----------
        if (missing.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE))
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("环境不全，无法启动：", fontSize = 12.sp, color = Color(0xFFC62828), fontWeight = FontWeight.Bold)
                    missing.forEach {
                        Text("· $it", fontSize = 11.sp, color = Color(0xFFC62828))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // ---------- 按钮 ----------
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = { EngineLauncher.start(activity, scope) },
                enabled = !running && missing.isEmpty(),
                modifier = Modifier.weight(1f)
            ) {
                Text(if (running) "运行中" else "启动引擎")
            }
            OutlinedButton(
                onClick = { EngineLauncher.stop() },
                enabled = running,
                modifier = Modifier.weight(1f)
            ) {
                Text("停止")
            }
        }

        Spacer(Modifier.height(16.dp))

        // ---------- 日志 ----------
        Text("引擎日志（${log.size} 行）", fontSize = 12.sp, color = Color.Gray)
        Spacer(Modifier.height(6.dp))
        val listState = rememberLazyListState()
        LaunchedEffect(log.size) {
            if (log.isNotEmpty()) listState.animateScrollToItem(log.size - 1)
        }
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(340.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
        ) {
            if (log.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(12.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("还没有日志", fontSize = 12.sp, color = Color(0xFF888888))
                    Text("点「启动引擎」试试", fontSize = 11.sp, color = Color(0xFF666666))
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(8.dp)
                ) {
                    items(log) { line ->
                        Text(
                            text = line,
                            fontSize = 10.sp,
                            color = if (line.startsWith("===")) Color(0xFF7FD1FF) else Color(0xFFD4D4D4),
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(vertical = 1.dp)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "payload: " + EngineLauncher.payloadDir(activity).absolutePath,
            fontSize = 9.sp,
            color = Color.Gray
        )
    }
}

private fun stateTitle(s: EngineState): String = when (s) {
    is EngineState.Idle -> "未启动"
    is EngineState.Starting -> "启动中…"
    is EngineState.Running -> "运行中"
    is EngineState.Failed -> "启动失败"
}

private fun stateSubtitle(s: EngineState): String = when (s) {
    is EngineState.Idle -> "点「启动引擎」开始 · 端口 13081"
    is EngineState.Starting -> "正在 fork node 并等待引擎自报就绪"
    is EngineState.Running -> "引擎已就绪，端口与 token 可被外部读取"
    is EngineState.Failed -> s.reason
}

private fun stateColor(s: EngineState): Color = when (s) {
    is EngineState.Idle -> Color(0xFF546E7A)
    is EngineState.Starting -> Color(0xFFF9A825)
    is EngineState.Running -> Color(0xFF2E7D32)
    is EngineState.Failed -> Color(0xFFC62828)
}
