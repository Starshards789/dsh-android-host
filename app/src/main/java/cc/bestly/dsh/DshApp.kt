package cc.bestly.dsh

import android.app.Application
import android.content.Context
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
 * 背景：覆盖安装 / 重装 APK 后，Android 会自动撤销无障碍授权
 * （`enabled_accessibility_services` 被清空），零八就读不到屏幕、点不了按钮，
 * 需要人去「设置 → 无障碍」手动重开。
 *
 * 本类在进程启动最早时机检测一次，未授权就用 root 补回来。
 *
 * 三条纪律（2026-11-10 与零八约定）：
 *   1. 幂等 —— 已授权直接返回，不反复写
 *   2. 异步 —— su 调用会阻塞，必须在子线程
 *   3. 静默失败 —— a11y 补不上绝不能影响 App 启动（catch Throwable）
 *
 * 另加 10 分钟节流：失败后不刷日志、不反复 spawn su。
 */
class DshApp : Application() {

    override fun onCreate() {
        super.onCreate()
        ensureA11yAsync()
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
            // 只信 Settings 这一级。
            //
            // 为什么不用 AccessibilityManager 二次确认：本方法在
            // Application.onCreate 里同步调用，此时 AMS 还没绑定我们的
            // 服务，getEnabledAccessibilityServiceList 必然返回空 ——
            // 二次确认会永远失败，导致每次冷启动都重写一遍（实测日志
            // 反复出现 "a11y self-heal attempted"）。
            //
            // 而「Settings 有配置」时，系统在 App 启动过程中会自行绑定
            // 无障碍服务，不需要我们干预。真出现「配置有但没连」（服务
            // 崩溃/被系统杀），重写 settings 也救不回来，得靠用户手动
            // 开关一次，所以二级确认没有实际收益。
            val configured = android.provider.Settings.Secure.getString(
                contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            val result = hasOurService(configured)
            Log.i(TAG, "a11y check: configured=[$configured] hasOurs=$result")
            result
        } catch (t: Throwable) {
            // 检测异常按「未开启」处理：宁可多补一次，不可漏补
            Log.w(TAG, "a11y check failed: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    /**
     * 判断 Settings 里的服务列表是否包含本壳的 a11y 服务。
     *
     * 必须用 ComponentName 归一化比较：系统里可能存短名
     * （`cc.bestly.dsh/.a11y.DshA11yService`）也可能存全名
     * （`cc.bestly.dsh/cc.bestly.dsh.a11y.DshA11yService`），
     * 直接字符串比会漏判，导致每次启动都重复写。
     */
    private fun hasOurService(configured: String): Boolean {
        if (configured.isBlank()) return false
        return configured.split(':').any { entry ->
            val cn = android.content.ComponentName.unflattenFromString(entry)
            cn != null &&
                cn.packageName == packageName &&
                cn.className == A11Y_SERVICE_CLASS
        }
    }

    companion object {
        private const val TAG = "DshApp"

        /** 组件的扁平写法：包名/完整类名 */
        private const val A11Y_COMPONENT =
            "cc.bestly.dsh/cc.bestly.dsh.a11y.DshA11yService"

        /** 仅类名，用于 ComponentName 归一化比较 */
        private const val A11Y_SERVICE_CLASS = "cc.bestly.dsh.a11y.DshA11yService"

        /** 失败后 10 分钟内不再重试，避免刷日志 */
        private const val RETRY_INTERVAL_MS = 10L * 60L * 1000L

        private val lastAttempt = AtomicLong(0L)
    }
}
