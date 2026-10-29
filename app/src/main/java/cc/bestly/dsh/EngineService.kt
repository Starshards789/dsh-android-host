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
import kotlinx.coroutines.launch

/**
 * 引擎前台服务。
 *
 * 为什么要它：引擎是 App 的子进程，App 一被系统回收引擎就没了。之前 P1.1/P1.2
 * 都只在 Activity 里拉引擎，切后台一段时间就被杀 —— 这是和旧壳（靠 root 侧
 * engine-guard 每 60 秒拉起）最大的能力差距。
 *
 * 三件事：
 *   ① startForeground + 常驻通知 → 进程被归为前台优先级，不参与常规回收
 *   ② START_STICKY → 系统杀了它也会自己重启
 *   ③ 内部看护 → 引擎状态变 Failed 时等一会儿再拉一次（不依赖外部 root）
 *
 * 注意：不要在这里做"无限重试"。失败就等 15 秒重试一次，状态机保持在 Failed
 * 可见状态，让人能从通知和界面看出问题，而不是悄悄循环。
 */
class EngineService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watcher: Job? = null
    private var lastText = ""

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification("引擎启动中…"))

        EngineLauncher.start(this, scope)

        // 看护：Failed 时重试一次，Running 时更新通知
        watcher = scope.launch {
            EngineLauncher.state.collect { st ->
                updateNotification(describe(st))
                if (st is EngineState.Failed) {
                    delay(RETRY_DELAY_MS)
                    if (EngineLauncher.state.value is EngineState.Failed) {
                        Log.i(TAG, "engine failed, retrying once")
                        EngineLauncher.start(this@EngineService, scope)
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 引擎已经在跑就不用重复启动（EngineLauncher.start 内部也会挡住）
        if (EngineLauncher.state.value is EngineState.Idle) {
            EngineLauncher.start(this, scope)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        watcher?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    // ---------- 通知 ----------

    private fun describe(st: EngineState): String = when (st) {
        is EngineState.Idle -> "未启动"
        is EngineState.Starting -> "启动中…"
        is EngineState.Running -> "运行中 · 端口 ${st.port}"
        is EngineState.Failed -> "启动失败"
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
        private const val RETRY_DELAY_MS = 15_000L
    }
}
