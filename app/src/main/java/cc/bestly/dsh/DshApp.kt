package cc.bestly.dsh

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityManager
import android.accessibilityservice.AccessibilityServiceInfo
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * DshApp —— 壳的 Application 入口。
 *
 * 目前只做一件事：**无障碍自愈**。
 *
 * 背景：覆盖安装 / 重装 APK 后可能丢失无障碍授权，零八就读不到屏幕、
 * 点不了按钮，需要人去「设置 → 无障碍」手动重开。
 *
 * 三条纪律（2026-11-10 与零八约定）：
 *   1. 幂等 —— 已授权直接返回，不反复写
 *   2. 异步 —— su 调用会阻塞，必须在子线程
 *   3. 静默失败 —— a11y 补不上绝不能影响 App 启动（catch Throwable）
 *
 * 检测方式的演进（重要，别再走回头路）：
 *   · `Settings.Secure.getString(ENABLED_ACCESSIBILITY_SERVICES)`
 *     在 Android 17 的 App 进程里**恒返回空**（实测 configured=[]，
 *     而 `adb shell settings get` 同一时刻有值）——不可用。
 *   · `AccessibilityManager.getEnabledAccessibilityServiceList()`
 *     在 Application.onCreate 同步调用时 AMS 尚未绑定，**必然返回空**。
 *   · 因此：延迟到主线程 5 秒后，再用 AccessibilityManager 检测。
 *     此时 AMS 已完成启动期绑定，结果准确且支持幂等。
 */
class DshApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // 等 AMS 完成启动期绑定再检测（onCreate 当下查必然为空）
        Handler(Looper.getMainLooper()).postDelayed({ ensureA11yAsync() }, DETECT_DELAY_MS)
    }

    private fun ensureA11yAsync() {
        if (isA11yEnabled()) return

        val now = System.currentTimeMillis()
        val prev = lastAttempt.get()
        if (now - prev < RETRY_INTERVAL_MS) return
        if (!lastAttempt.compareAndSet(prev, now)) return

        Executors.newSingleThreadExecutor().execute {
            try {
                execSu("settings put secure enabled_accessibility_services $A11Y_COMPONENT")
                execSu("settings put secure accessibility_enabled 1")
                Log.i(TAG, "a11y self-heal attempted")
            } catch (t: Throwable) {
                // 静默：补不上也要让 App 正常起来
                Log.w(TAG, "a11y self-heal failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    private fun execSu(cmd: String) {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        p.waitFor()
    }

    private fun isA11yEnabled(): Boolean {
        return try {
            val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            val enabled = am.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            ).any { info -> info.resolveInfo?.serviceInfo?.packageName == packageName }
            Log.i(TAG, "a11y check: enabled=$enabled")
            enabled
        } catch (t: Throwable) {
            // 检测异常按「未开启」处理：宁可多补一次，不可漏补
            Log.w(TAG, "a11y check failed: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    companion object {
        private const val TAG = "DshApp"

        /** 组件的扁平写法：包名/完整类名 */
        private const val A11Y_COMPONENT =
            "cc.bestly.dsh/cc.bestly.dsh.a11y.DshA11yService"

        /** 启动后延迟多久再检测（等 AMS 完成绑定） */
        private const val DETECT_DELAY_MS = 5_000L

        /** 失败后 10 分钟内不再重试，避免刷日志 */
        private const val RETRY_INTERVAL_MS = 10L * 60L * 1000L

        private val lastAttempt = AtomicLong(0L)
    }
}
