package cc.bestly.dsh

import android.content.Intent
import android.os.Build
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.bestly.dsh.engine.EngineLauncher
import cc.bestly.dsh.engine.EngineState
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.viewinterop.AndroidView

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
        requestStoragePermissions()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    EngineScreen(this)
                }
            }
        }
    }

    /**
     * 存储权限（2026-10-30 补，零八在新壳里实测发现的第一个真缺陷）
     *
     * 症状：App 身份写 /sdcard/DSH-Workspace 报 Operation not permitted（读可以）；
     *      用 su -c 写可以，但那写出来的文件属主是 root，与 App 身份写的 u0_a295 混在一起，
     *      将来排查权限问题会多一个变量。
     *
     * 为什么必须在代码里申请：targetSdk=28 下这两条是危险权限，
     *      只在 Manifest 声明不生效，必须运行时 requestPermissions。
     *
     * 申请失败不阻塞启动：引擎本身只需要 INTERNET，存储权限只影响「用 App 身份
     *      读写工作区」。失败时引擎照常跑，用户之后在系统设置里给也行。
     */
    private fun requestStoragePermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val needed = listOf(
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ).filter {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), 1001)
        }
    }

}

@Composable
fun EngineScreen(activity: ComponentActivity) {
    val state by EngineLauncher.state.collectAsState()
    val log by EngineLauncher.log.collectAsState()
    val missing = remember { EngineLauncher.preflight(activity) }
    val running = state is EngineState.Running || state is EngineState.Starting
    var showWeb by remember { mutableStateOf(false) }
    val runningState = state as? EngineState.Running

    // 对话模式：全屏 WebView 直接加载引擎的 Web UI。
    // token 走 URL 参数 —— 这是 dsh 唯一的认证方式（没有 header 认证）。
    var copiedHint by remember { mutableStateOf(false) }

    if (showWeb && runningState != null) {
        EngineWebView(
            port = runningState.port,
            token = runningState.token,
            onBack = { showWeb = false }
        )
        return
    }

    // 打开 App 就自动拉起引擎 —— 这才是宿主该干的事。
    // P1.1 一开始做成手动点按钮，但 targetSdk 27 的兼容模式下 adb input tap
    // 的坐标不可靠（uiautomator 报的 bounds 和实际触摸目标不一致），反复点不中。
    // 而且真实产品形态本来就是：用户打开 App，引擎就该起来。
    LaunchedEffect(Unit) {
        // 引擎交给前台服务持有 —— 服务活着引擎才活着，切后台不会被系统回收。
        // 这里只负责把服务叫起来，不直接 fork 引擎。
        startEngineService(activity)
    }

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
                    // 完整 token：截断显示会让人无法在浏览器里完成首次认证
                    Text(
                        "TOKEN  ${s.token}",
                        fontSize = 11.sp,
                        color = Color.White,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(Modifier.height(8.dp))
                    // 首次访问必须带 token 才能种 cookie；一键复制省掉手拼长串
                    val ctxCopy = LocalContext.current
                    Button(
                        onClick = {
                            val url = "http://${s.host}:${s.port}/?token=${s.token}"
                            val cm = ctxCopy.getSystemService(
                                android.content.Context.CLIPBOARD_SERVICE
                            ) as android.content.ClipboardManager
                            cm.setPrimaryClip(
                                android.content.ClipData.newPlainText("dsh url", url)
                            )
                            copiedHint = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (copiedHint) "已复制，去浏览器粘贴" else "复制访问链接")
                    }
                    Spacer(Modifier.height(4.dp))
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
                onClick = { startEngineService(activity) },
                enabled = !running && missing.isEmpty(),
                modifier = Modifier.weight(1f)
            ) {
                Text(if (running) "运行中" else "启动引擎")
            }
            OutlinedButton(
                onClick = { stopEngineService(activity) },
                enabled = running,
                modifier = Modifier.weight(1f)
            ) {
                Text("停止")
            }
            // 引擎就绪后才能进对话 —— 没端口和 token 时 WebView 无从加载
            Button(
                onClick = { showWeb = true },
                enabled = runningState != null,
                modifier = Modifier.weight(1f)
            ) {
                Text("对话")
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
    is EngineState.Idle -> "点「启动引擎」开始 · 端口由系统分配"
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

/**
 * 内置 WebView 对话页。
 *
 * 直接加载引擎自己的 Web 界面 —— 不重写 UI。这是「官方内核零改动」原则的延伸：
 * 界面归 dsh 管，我们只负责把它装进自己的壳里，跟着官方升级自动变。
 *
 * token 只能走 URL 参数：dsh 的认证就是这一个通道，没有 header 认证。
 * 所以不要把 token 打到日志里，也不要在界面上显示完整 token。
 */
@Composable
private fun EngineWebView(port: Int, token: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("dsh 对话", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text(":$port", fontSize = 11.sp, color = Color.Gray, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onBack) {
                Text("返回", fontSize = 12.sp)
            }
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true      // dsh web 依赖 localStorage
                        databaseEnabled = true
                        cacheMode = WebSettings.LOAD_DEFAULT
                        useWideViewPort = true
                        loadWithOverviewMode = true
                    }
                    webViewClient = WebViewClient()
                    loadUrl("http://127.0.0.1:$port/?token=$token")
                }
            }
        )
    }
}

/**
 * 拉起前台服务。Android 8+ 必须用 startForegroundService，否则服务起来后
 * 没在 5 秒内 startForeground 会 ANR；这里 Service.onCreate 里就调了。
 */
private fun startEngineService(ctx: android.content.Context) {
    val svc = Intent(ctx, EngineService::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        ctx.startForegroundService(svc)
    } else {
        ctx.startService(svc)
    }
}

/**
 * 停下服务再停引擎。
 * 顺序不能反：先 stopService 才会走到 Service.onDestroy 取消 scope，
 * 否则服务里的看护协程会把刚停掉的引擎又拉起来。
 */
private fun stopEngineService(ctx: android.content.Context) {
    ctx.stopService(Intent(ctx, EngineService::class.java))
    EngineLauncher.stop()
}
