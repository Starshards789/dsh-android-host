package cc.bestly.dsh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import cc.bestly.dsh.engine.EngineLauncher
import cc.bestly.dsh.engine.EngineState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 引擎前台服务。
 *
 * 为什么要它：引擎是 App 的子进程，App 一被系统回收引擎就没了。之前 P1.1/P1.2
 * 都只在 Activity 里拉引擎，切后台一段时间就被杀 —— 这是和旧壳（靠 root 侧
 * engine-guard 每 60 秒拉起）最大的能力差距。
 *
 * 五件事：
 *   ① startForeground + 常驻通知 → 进程被归为前台优先级，不参与常规回收
 *   ② START_STICKY → 系统杀了它也会自己重启
 *   ③ 内部看护 → 引擎状态变 Failed 时重试（带退避，不再是「只救一次」）
 *   ④ 心跳文件 → 外部能看出「看护本身还活着吗」（看门狗悖论）
 *   ⑤ 外部重启入口 → root 侧写一个文件就能救引擎，不需要抢前台
 *
 * ★ 2026-10-30 修的三个真 bug（都是实测撞出来的，不是推演）：
 *
 *   ① onStartCommand 只认 Idle，不认 Failed
 *      后果：引擎一旦卡进 Failed，之后谁再调 onStartCommand 都什么都不做，
 *            前台服务活着但永远救不回来。今天 18:42 就撞上了 ——
 *            kill 引擎后 watcher 的一次重试失败，App 从此卡死在 Failed，
 *            进程在、服务在、端口 000，没有任何东西能救它。
 *
 *   ② watcher 只重试一次
 *      后果：第一次重试撞上偶发失败（端口刚被占、payload 正在写），就再也不试。
 *            改成指数退避 + 上限，且成功后重置计数。
 *
 *   ③ appendLog 只写内存，不落文件
 *      后果：出事了从 shell/adb 侧完全看不到 App 内部发生了什么，只能靠猜。
 *            今天就是因为这个，把「watcher 有没有动作」的判断做错了一次。
 */
class EngineService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watcher: Job? = null
    private var heartbeat: Job? = null
    private var externalRestart: Job? = null
    private var healthWatch: Job? = null
    private var lastText = ""

    /** 连续失败次数，用于退避；成功一次就清零 */
    private var failCount = 0

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification("引擎启动中…"))
        hostLog("--- EngineService.onCreate ---")

        EngineLauncher.start(this, scope)

        // 看护：Failed 时按退避重试，不再是「只救一次」
        watcher = scope.launch {
            EngineLauncher.state.collect { st ->
                updateNotification(describe(st))
                hostLog("state -> ${describe(st)}")
                when (st) {
                    is EngineState.Running -> {
                        failCount = 0
                    }
                    is EngineState.Failed -> {
                        val attempt = failCount + 1
                        failCount = attempt
                        val waitMs = backoffMs(attempt)
                        hostLog("失败(第 $attempt 次)，${waitMs / 1000}s 后重试")
                        delay(waitMs)
                        // 期间状态若已自愈就不重试
                        if (EngineLauncher.state.value is EngineState.Failed) {
                            hostLog("开始第 $attempt 次重试")
                            EngineLauncher.start(this@EngineService, scope)
                        }
                    }
                    else -> {}
                }
            }
        }

        // 心跳：证明「看护本身还活着」。外部（CT106）读这个文件判断超时。
        heartbeat = scope.launch {
            val f = File(EngineLauncher.runtimeDir(this@EngineService), "heartbeat")
            while (isActive) {
                try {
                    f.writeText(
                        "${System.currentTimeMillis()}\n" +
                        "${describe(EngineLauncher.state.value)}\n"
                    )
                } catch (_: Exception) {
                }
                delay(HEARTBEAT_MS)
            }
        }

        // 外部重启入口：root 侧 create 这个文件即可救引擎，不用 am start 抢前台。
        // 一次性信号：读到就删掉，避免重复触发。
        externalRestart = scope.launch {
            val flag = File(EngineLauncher.runtimeDir(this@EngineService), "restart")
            while (isActive) {
                delay(RESTART_POLL_MS)
                if (flag.exists()) {
                    hostLog("检测到外部重启信号")
                    try {
                        flag.delete()
                    } catch (_: Exception) {
                    }
                    failCount = 0
                    EngineLauncher.restart(this@EngineService, scope)
                }
            }
        }

        // 健康检查：状态 Running ≠ 服务可用。每 60 秒真探一次端口，僵死就重启。
        // 零八在旧壳审出过同一个 bug（`ps | grep` 永远命中自己），这里不等它再犯。
        healthWatch = scope.launch {
            while (isActive) {
                delay(HEALTH_CHECK_MS)
                val problem = try {
                    EngineLauncher.healthProblem()
                } catch (e: Exception) {
                    "健康检查自身异常: ${e.message}"
                }
                if (problem != null) {
                    hostLog("健康检查失败: $problem → 重启引擎")
                    failCount = 0
                    EngineLauncher.restart(this@EngineService, scope)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ★ 修 bug ①：Idle 和 Failed 都要能拉起。
        //   Idle = 从未启动；Failed = 启动过但挂了（包括被外部 kill）。
        //   Running/Starting 时才跳过，避免重复起实例抢 DSH_HOME。
        when (EngineLauncher.state.value) {
            is EngineState.Idle, is EngineState.Failed -> {
                hostLog("onStartCommand: 状态=${describe(EngineLauncher.state.value)}，拉起引擎")
                failCount = 0
                EngineLauncher.start(this, scope)
            }
            else -> hostLog("onStartCommand: 状态=${describe(EngineLauncher.state.value)}，跳过")
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        hostLog("--- EngineService.onDestroy ---")
        watcher?.cancel()
        heartbeat?.cancel()
        externalRestart?.cancel()
        healthWatch?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    /** 指数退避：15s → 30s → 60s → 120s → 300s（封顶），避免坏环境里疯狂重试烧电 */
    private fun backoffMs(attempt: Int): Long = when {
        attempt <= 1 -> 15_000L
        attempt == 2 -> 30_000L
        attempt == 3 -> 60_000L
        attempt == 4 -> 120_000L
        else -> 300_000L
    }

    /** 落文件的日志 —— 修 bug ③。出事了从 adb shell 也能看到 App 内部发生了什么 */
    private fun hostLog(line: String) {
        try {
            val f = File(EngineLauncher.runtimeDir(this), "host.log")
            val ts = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
            f.appendText("$ts  $line\n")
            // 简单控大小：超过 256KB 就截掉前一半
            if (f.length() > 256 * 1024) {
                val keep = f.readLines().takeLast(1000)
                f.writeText(keep.joinToString("\n") + "\n")
            }
        } catch (_: Exception) {
        }
    }

    // ---------- 通知 ----------

    private fun describe(st: EngineState): String = when (st) {
        is EngineState.Idle -> "未启动"
        is EngineState.Starting -> "启动中…"
        is EngineState.Running -> "运行中 · 端口 ${st.port}"
        is EngineState.Failed -> "启动失败 · ${st.reason}"
    }

    private fun buildNotification(text: String): Notification {
        val mgr = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "dsh 引擎",
                NotificationManager.IMPORTANCE_LOW   // 常驻通知，静默不打扰
            ).apply {
                description = "保持 dsh 引擎在后台运行"
                setShowBadge(false)
            }
            mgr.createNotificationChannel(ch)
        }

        val open = Intent(this, MainActivity::class.java)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        } else {
            android.app.PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pi = android.app.PendingIntent.getActivity(this, 0, open, flags)

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("dsh host")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)          // 不可划掉 —— 划掉就等于变相退出
            .setContentIntent(pi)
            .build()
    }

    private fun updateNotification(text: String) {
        if (text == lastText) return
        lastText = text
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(text))
    }

    companion object {
        private const val TAG = "EngineService"
        private const val CHANNEL_ID = "dsh-engine"
        private const val NOTIF_ID = 1001

        /** 心跳间隔：外部超过 3 分钟没更新就算看护死了 */
        private const val HEARTBEAT_MS = 30_000L

        /** 外部重启信号的轮询间隔 */
        private const val RESTART_POLL_MS = 15_000L

        /** 健康检查间隔：状态 Running 时真探端口，僵死就重启 */
        private const val HEALTH_CHECK_MS = 60_000L
    }
}
