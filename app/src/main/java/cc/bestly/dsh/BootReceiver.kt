package cc.bestly.dsh

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * 开机自启。
 *
 * 只做一件事：把 EngineService 拉起来，引擎由服务负责。
 *
 * 注意 Android 的几条限制：
 *   - Android 8+ 必须用 startForegroundService，普通 startService 会抛异常
 *   - Android 12+ 限制后台启动前台服务，但 BOOT_COMPLETED 是豁免场景之一
 *   - 需要 RECEIVE_BOOT_COMPLETED 权限，且用户必须至少手动打开过一次 App
 *     （Android 的 stopped state 规则），所以"装完没开过就重启"不会自启
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        Log.i(TAG, "boot event: $action, starting EngineService")
        val svc = Intent(context, EngineService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(svc)
        } else {
            context.startService(svc)
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
