package cc.bestly.dsh

import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Message
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.viewinterop.AndroidView
import android.app.Activity
import android.view.ViewGroup
import android.widget.FrameLayout

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
        // 主题必须在 super.onCreate 之前定。
        // WebView 的 prefers-color-scheme 读的是 Activity 的 uiMode，
        // Compose 换 darkColorScheme 改不了它 —— 不换 window theme，
        // 内嵌页面就永远认为宿主是浅色，背景不跟主题走。
        val prefs0 = getSharedPreferences("dsh_prefs", MODE_PRIVATE)
        val darkNow = when (prefs0.getInt("theme_mode", 0)) {
            1 -> false
            2 -> true
            else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
        }
        setTheme(if (darkNow) R.style.Theme_DshHost else R.style.Theme_DshHost_Light)

        super.onCreate(savedInstanceState)
        requestStoragePermissions()
        setContent {
            // 主题：0 = 跟随系统，1 = 浅色，2 = 深色。
            // 持久化到 SharedPreferences —— 长期用下来多数场景希望固定深色。
            val prefs = remember { getSharedPreferences("dsh_prefs", MODE_PRIVATE) }
            var themeMode by remember { mutableStateOf(prefs.getInt("theme_mode", 0)) }
            val systemDark = isSystemInDarkTheme()
            val dark = when (themeMode) {
                1 -> false
                2 -> true
                else -> systemDark
            }
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    EngineScreen(
                        activity = this,
                        dark = dark,
                        themeMode = themeMode,
                        onThemeChange = { m ->
                            themeMode = m
                            prefs.edit().putInt("theme_mode", m).apply()
                            // window theme 只在 onCreate 生效 —— 必须重建 Activity
                            // 才能让 WebView 的 prefers-color-scheme 跟着变。
                            // 引擎在 EngineService 里，重建不影响它。
                            recreate()
                        }
                    )
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
        val needed = buildList {
            add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            // ★ Android 13+ 通知也是运行时权限（POST_NOTIFICATIONS）。
            // 只声明不申请的话，前台服务的常驻通知不会显示 —— 用户看不到引擎在跑，
            // 2026-11-06 在 LineageOS 24 上实测踩到（装完壳立刻弹的是存储权限，
            // 通知权限始终 granted=false，得靠 adb 手动补）。
            if (Build.VERSION.SDK_INT >= 33) {
                add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }.filter {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), 1001)
        }
    }

}

@Composable
fun EngineScreen(
    activity: ComponentActivity,
    dark: Boolean,
    themeMode: Int,
    onThemeChange: (Int) -> Unit
) {
    val state by EngineLauncher.state.collectAsState()
    val log by EngineLauncher.log.collectAsState()
    val missing = remember { EngineLauncher.preflight(activity) }
    val running = state is EngineState.Running || state is EngineState.Starting
    var showWeb by remember { mutableStateOf(false) }
    val runningState = state as? EngineState.Running

    // 对话模式：全屏 WebView 直接加载引擎的 Web UI。
    // token 走 URL 参数 —— 这是 dsh 唯一的认证方式（没有 header 认证）。
    var copiedHint by remember { mutableStateOf(0) }

    // 状态卡展开 / 收起。两个地址按钮加上以后卡片很高，收起时只留状态标题与端口。
    var cardExpanded by remember { mutableStateOf(true) }

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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("dsh host", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("P1.1 · 宿主骨架", fontSize = 12.sp, color = Color.Gray)
            }
            // 主题：跟随系统 → 浅色 → 深色 → 循环。选择存在 SharedPreferences 里。
            TextButton(onClick = { onThemeChange((themeMode + 1) % 3) }) {
                Text(
                    when (themeMode) {
                        1 -> "浅色"
                        2 -> "深色"
                        else -> "跟随系统"
                    },
                    fontSize = 11.sp
                )
            }
        }
        Spacer(Modifier.height(14.dp))

        // ---------- 状态卡 ----------
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = stateColor(state, dark))
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stateTitle(state),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stateSubtitle(state),
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.85f)
                        )
                    }
                    TextButton(onClick = { cardExpanded = !cardExpanded }) {
                        Text(
                            if (cardExpanded) "收起 ▲" else "展开 ▼",
                            fontSize = 11.sp,
                            color = Color.White
                        )
                    }
                }

                val s = state
                if (s is EngineState.Running && cardExpanded) {
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
                    // 两个地址分开给，用途不同，不能只给一个：
                    //   回环 —— App 内 WebView 与本机浏览器。登录 / 填 API Key 只有走它才行，
                    //           OAuth 回调只认 127.0.0.1 / localhost / [::1]。
                    //   Tailscale —— 其他设备访问页面。它不能完成登录与填 Key。
                    // 引擎绑的是 0.0.0.0（dsh ≥ 1.14 只接受这个或 127.0.0.1），
                    // 两条路天然都通；外部侧由 service.d 的 iptables 只放行 tun1 收口。
                    val ctxCopy = LocalContext.current
                    val cm = ctxCopy.getSystemService(
                        android.content.Context.CLIPBOARD_SERVICE
                    ) as android.content.ClipboardManager
                    val tsIp = remember { EngineLauncher.findTailscaleIpv4() }
                    val loopUrl = "http://127.0.0.1:${s.port}/?token=${s.token}"
                    val tsUrl = tsIp?.let { "http://$it:${s.port}/?token=${s.token}" }

                    Button(
                        onClick = {
                            cm.setPrimaryClip(
                                android.content.ClipData.newPlainText("dsh loopback", loopUrl)
                            )
                            copiedHint = 1
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (copiedHint == 1) "已复制本机地址" else "复制本机地址 127.0.0.1")
                    }
                    Text(
                        "App 内 / 本机浏览器。登录、填 API Key 必须用这个 —— OAuth 回调只认回环。",
                        fontSize = 10.sp,
                        color = Color.White.copy(alpha = 0.8f)
                    )

                    Spacer(Modifier.height(8.dp))

                    if (tsUrl != null) {
                        Button(
                            onClick = {
                                cm.setPrimaryClip(
                                    android.content.ClipData.newPlainText("dsh tailscale", tsUrl)
                                )
                                copiedHint = 2
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (copiedHint == 2) "已复制 Tailscale 地址" else "复制 Tailscale 地址 $tsIp")
                        }
                        Text(
                            "其他设备经 Tailscale 打开页面用。这里不能登录，也不能填 API Key。",
                            fontSize = 10.sp,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    } else {
                        Text(
                            "Tailscale 未连接，暂无外部地址（App 内不受影响）",
                            fontSize = 10.sp,
                            color = Color.White.copy(alpha = 0.8f)
                        )
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

// 深色模式下把四个状态色压暗一档 —— 高饱和色块在暗环境里太刺眼。
private fun stateColor(s: EngineState, dark: Boolean): Color = when (s) {
    is EngineState.Idle -> if (dark) Color(0xFF37474F) else Color(0xFF546E7A)
    is EngineState.Starting -> if (dark) Color(0xFF7A5B00) else Color(0xFFF9A825)
    is EngineState.Running -> if (dark) Color(0xFF1B4D2E) else Color(0xFF2E7D32)
    is EngineState.Failed -> if (dark) Color(0xFF7F1D1D) else Color(0xFFC62828)
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
                // 外面这层 FrameLayout 是给 window.open 的新窗口留的挂载点：
                // 登录页要真显示出来才走得通，而新窗口必须和主 WebView 共用一个容器。
                FrameLayout(ctx).apply {
                addView(WebView(ctx).apply {
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true      // dsh web 依赖 localStorage
                        databaseEnabled = true
                        cacheMode = WebSettings.LOAD_DEFAULT
                        // 千万不要开 useWideViewPort / loadWithOverviewMode。
                        // 实测（LineageOS 24 / WebView 156）这组设置会让 WebView 里
                        // 100vh / 100dvh / 100svh 全部算成 0，而 window.innerHeight 却是正常的 721。
                        // dsh 的弹窗用 max-height: calc(-48px + 100vh) 控制高度，
                        // 于是变成 calc(-48px + 0) = -48px 这个非法值，弹窗高度塌成 48px，
                        // 标题 / 说明 / 按钮全被 overflow:hidden 裁掉，画面上只剩一个输入框。
                        // 页面自带 <meta name="viewport" content="width=device-width, initial-scale=1">，
                        // 用 WebView 默认设置就能正确适配，不需要这两个开关。
                        setSupportZoom(false)
                        builtInZoomControls = false
                        displayZoomControls = false
                        // dsh 0.2.x 的「登录 DeepSeek 账号」用 window.open 开新窗口，
                        // target=_blank 的链接也一样。不开这个开关，WebView 会把当前页
                        // 直接导航成 about:blank —— 实测点登录后整页白屏就是这么来的。
                        setSupportMultipleWindows(true)
                        javaScriptCanOpenWindowsAutomatically = true
                    }
                    webChromeClient = object : WebChromeClient() {
                        /**
                         * 接住 window.open / target=_blank。
                         *
                         * 做法不是真开第二个窗口，而是在当前 WebView 里加载新窗口要打开的
                         * URL：套壳只有一个可视区域，多开一个 WebView 反而要额外管生命周期
                         * 和触摸归属（之前就栽在 WebView 泄漏吞触摸上）。
                         *
                         * 登录流程本身是 URL 跳转 -> DeepSeek 授权页 -> 回调 127.0.0.1:13081，
                         * 回到本机地址时当前 WebView 自然恢复成 dsh 页面，所以单窗口够用。
                         */
                        override fun onCreateWindow(
                            view: WebView,
                            isDialog: Boolean,
                            isUserGesture: Boolean,
                            resultMsg: Message
                        ): Boolean {
                            // dsh 点「登录 DeepSeek 账号」时用 window.open 开新窗口。
                            //
                            // 第一版做法是「不真开窗口，把新窗口的 URL 塞回主 WebView」，
                            // 实测失败：主 WebView 会先被 window.open 的占位导航带去
                            // about:blank，我们的 loadUrl 紧接着被它覆盖，屏幕只剩黑底。
                            //
                            // 正确做法是让新窗口真的存在并盖在主页面上：登录页自己完成
                            // 手机号 + 验证码，授权后回跳 127.0.0.1:13081，页面调
                            // window.close()，走 onCloseWindow 把这一层拆掉。
                            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                            val popup = WebView(view.context).apply {
                                settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    databaseEnabled = true
                                    cacheMode = WebSettings.LOAD_DEFAULT
                                    setSupportZoom(false)
                                    builtInZoomControls = false
                                    displayZoomControls = false
                                    setSupportMultipleWindows(true)
                                    javaScriptCanOpenWindowsAutomatically = true
                                }
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        v: WebView,
                                        request: WebResourceRequest
                                    ): Boolean {
                                        val scheme = request.url.scheme ?: return false
                                        if (scheme != "http" && scheme != "https") {
                                            try {
                                                v.context.startActivity(Intent(Intent.ACTION_VIEW, request.url))
                                            } catch (_: Exception) {
                                            }
                                            return true
                                        }
                                        return false
                                    }

                                    // 登录页是一层盖在主界面上的窗口，没有浏览器那种地址栏和关闭按钮。
                                    // 一旦它自己崩了（实测：授权回调页拉 chunk 被 WAF 429，
                                    // 页面渲染成「页面出现了一些异常」），用户就只剩「杀 App」一条路。
                                    // 所以每次加载完成都往右上角放一个 ✕，走 window.close()
                                    // 触发 onCloseWindow 把这一层拆掉。
                                    override fun onPageFinished(v: WebView?, url: String?) {
                                        super.onPageFinished(v, url)
                                        v?.evaluateJavascript(POPUP_CLOSE_BUTTON_JS, null)
                                    }

                                    // 渲染进程整个挂掉时 WebView 会变黑且不再响应任何操作。
                                    // 接管掉，重建 Activity（引擎跑在 Service 里，不受影响）。
                                    override fun onRenderProcessGone(
                                        v: WebView,
                                        detail: RenderProcessGoneDetail
                                    ): Boolean {
                                        (v.parent as? ViewGroup)?.removeView(v)
                                        v.destroy()
                                        (v.context as? Activity)?.recreate()
                                        return true
                                    }

                                    // 弹窗里加载失败也给一条重试路径，不然只能关掉重来
                                    override fun onReceivedError(
                                        v: WebView,
                                        request: WebResourceRequest,
                                        error: WebResourceError
                                    ) {
                                        super.onReceivedError(v, request, error)
                                        if (!request.isForMainFrame) return
                                        val desc = (error.description?.toString() ?: "未知错误")
                                            .replace("\\", "\\\\")
                                            .replace("'", "\\'")
                                        v.evaluateJavascript(ERROR_OVERLAY_JS.format(desc), null)
                                    }
                                }
                            }
                            transport.webView = popup
                            resultMsg.sendToTarget()
                            (view.parent as? ViewGroup)?.addView(
                                popup,
                                ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                            )
                            popup.bringToFront()
                            return true
                        }

                        override fun onCloseWindow(window: WebView) {
                            (window.parent as? ViewGroup)?.removeView(window)
                            window.destroy()
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest
                        ): Boolean {
                            // 非 http(s) 的 scheme（weixin://、mailto: 之类）WebView 处理不了，
                            // 丢给系统；其余一律让 WebView 自己加载。
                            val scheme = request.url.scheme ?: return false
                            if (scheme != "http" && scheme != "https") {
                                try {
                                    view.context.startActivity(Intent(Intent.ACTION_VIEW, request.url))
                                } catch (_: Exception) {
                                }
                                return true
                            }
                            return false
                        }

                        // 主文档加载失败时 WebView 只会留一片空白，没有任何可操作入口。
                        // 注入一个覆盖全屏的错误浮层，带「重试」按钮（location.reload）。
                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest,
                            error: WebResourceError
                        ) {
                            super.onReceivedError(view, request, error)
                            if (!request.isForMainFrame) return
                            val desc = (error.description?.toString() ?: "未知错误")
                                .replace("\\", "\\\\")
                                .replace("'", "\\'")
                            view.evaluateJavascript(ERROR_OVERLAY_JS.format(desc), null)
                        }

                        // 渲染进程崩溃（低内存时系统会杀 WebView 进程）→ 整页变黑且触摸全失效。
                        // 不自愈的话用户只能杀 App；这里重建 Activity 就够了。
                        override fun onRenderProcessGone(
                            view: WebView,
                            detail: RenderProcessGoneDetail
                        ): Boolean {
                            (view.parent as? ViewGroup)?.removeView(view)
                            view.destroy()
                            (view.context as? Activity)?.recreate()
                            return true
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            // WebView 里 100vh / 100dvh / 100svh 恒为 0（innerHeight 却是正常的，
                            // 同一页面在系统浏览器里也正常）。dsh 大量用 vh 控高：
                            //   弹窗   max-height: calc(-48px + 100vh)   → 塌成 48px 被裁
                            //   设置   height: min(800px, -48px + 100vh) → 算成 -48px 直接不显示
                            // 逐条覆盖已知选择器不可维护，改成遍历全部样式表把 vh 就地换算成 px。
                            //
                            // 两个必须注意的点（都踩过）：
                            //   1. 每条规则要单独 try/catch —— 一条抛异常会中断整轮遍历，
                            //      之前就是这样只跑到 81 条规则、改了 0 条，还不报错。
                            //   2. 判断嵌套用 r.cssRules && r.cssRules.length，不能用 if (r.cssRules)。
                            val js = """
                                (function(){
                                  var h = window.innerHeight;
                                  if (!h) return;
                                  var RE = /(-?\d*\.?\d+)(vh|dvh|svh|lvh)/g;
                                  function walk(rs) {
                                    for (var i = 0; i < rs.length; i++) {
                                      try {
                                        var r = rs[i];
                                        if (r.cssRules && r.cssRules.length) { walk(r.cssRules); continue; }
                                        if (!r.style) continue;
                                        var props = ['height','maxHeight','minHeight','maxWidth','minWidth','top','bottom'];
                                        for (var j = 0; j < props.length; j++) {
                                          var raw;
                                          try { raw = r.style[props[j]]; } catch (e) { continue; }
                                          if (!raw || raw.indexOf('vh') < 0) continue;
                                          r.style[props[j]] = raw.replace(RE, function(_, n){
                                            return (parseFloat(n) * h / 100) + 'px';
                                          });
                                        }
                                      } catch (e) {}
                                    }
                                  }
                                  for (var i = 0; i < document.styleSheets.length; i++) {
                                    try {
                                      var rules = document.styleSheets[i].cssRules;
                                      if (rules) walk(rules);
                                    } catch (e) {}
                                  }
                                })();
                            """.trimIndent()
                            view?.evaluateJavascript(js, null)
                        }
                    }
                    loadUrl("http://127.0.0.1:$port/?token=$token")
                }, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                ))
                }
            },
            onRelease = { root ->
                // WebView 不会被 Compose 自动回收。不写这段的话，每进出一次对话页
                // 就留下一个空 WebView 叠在顶上，它会吞掉之后所有触摸事件
                // （实测：两个 WebView 叠加时 input tap 的 touch/click 计数恒为 0，
                //  进程重启后只剩一个就立刻恢复正常）。
                if (root is ViewGroup) {
                    for (i in 0 until root.childCount) {
                        (root.getChildAt(i) as? WebView)?.let { wv ->
                            wv.stopLoading()
                            wv.loadUrl("about:blank")
                            wv.destroy()
                        }
                    }
                    root.removeAllViews()
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

/**
 * 弹窗（window.open 出来的那层）右上角的关闭按钮。
 *
 * 登录页没有浏览器地址栏，页面自己崩了就没有退路。这个 ✕ 走 window.close()，
 * WebView 会回调 onCloseWindow，把这一层从 FrameLayout 里移除并销毁。
 */
private val POPUP_CLOSE_BUTTON_JS = """
(function(){
  if (document.getElementById('__dsh_close')) return;
  var b = document.createElement('div');
  b.id = '__dsh_close';
  b.textContent = '\u2715';
  b.style.cssText = 'position:fixed;top:10px;right:10px;z-index:2147483647;width:36px;height:36px;' +
    'line-height:36px;text-align:center;border-radius:18px;background:rgba(255,255,255,0.16);' +
    'color:#fff;font:18px/36px sans-serif;cursor:pointer';
  b.onclick = function(){ window.close(); };
  document.body.appendChild(b);
})();
""".trimIndent()

/**
 * 加载失败覆盖层。参数是错误描述（已在 Kotlin 侧转义）。
 *
 * 用覆盖层而不是跳错误页，是为了保住当前页面：很多失败是资源被临时拒（WAF 429、
 * 断网重连），reload 一下就好，没必要把页面栈整个重建。
 */
private val ERROR_OVERLAY_JS = """
(function(m){
  if (document.getElementById('__dsh_err')) return;
  var d = document.createElement('div');
  d.id = '__dsh_err';
  d.style.cssText = 'position:fixed;inset:0;z-index:2147483646;background:#0d0d0d;color:#eee;' +
    'display:flex;flex-direction:column;align-items:center;justify-content:center;gap:14px;' +
    'padding:28px;text-align:center;font:14px/1.6 sans-serif';
  var t = document.createElement('div');
  t.textContent = '页面加载失败';
  t.style.cssText = 'font-size:17px;font-weight:600';
  var s = document.createElement('div');
  s.textContent = m;
  s.style.cssText = 'color:#8a8a8a;font-size:12px;max-width:78vw;word-break:break-all';
  var bt = document.createElement('button');
  bt.textContent = '重试';
  bt.style.cssText = 'margin-top:8px;padding:10px 30px;border:0;border-radius:20px;' +
    'background:#2b6cff;color:#fff;font-size:14px';
  bt.onclick = function(){ d.remove(); location.reload(); };
  d.appendChild(t); d.appendChild(s); d.appendChild(bt);
  document.body.appendChild(d);
})(%s)
""".trimIndent()
