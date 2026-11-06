package cc.bestly.dsh.a11y

import android.os.Environment
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * 截图工具。
 *
 * 为什么不用 MediaProjection：那要用户每次授权、还要常驻一个前台服务，
 * 对「AI 偶尔看一眼屏幕」这个场景太重。这里直接走 root 的 screencap ——
 * 本机已有 Magisk，一条命令的事。
 *
 * ★ 落盘位置必须是共享目录：插件在引擎进程里 readFile(path)，如果写到
 *   /data/user/0/<pkg>/ 这种私有目录，跨 UID 读会 EACCES（插件代码里专门
 *   写了这条报错提示）。所以统一放 /sdcard。
 */
object ScreenshotUtil {

    private const val TAG = "DshA11yShot"

    /** 截图目录。和 AI 的工作区放一起，方便它自己也能找到。 */
    private fun dir(): File {
        val d = File(Environment.getExternalStorageDirectory(), "DSH-Workspace/.tmp")
        if (!d.exists()) d.mkdirs()
        return d
    }

    /**
     * 截一张图，返回 {ok, path, screenW, screenH, imageW, imageH}。
     *
     * screenW/H 是屏幕物理尺寸，imageW/H 是图片实际尺寸 —— 插件会用这两个值
     * 换算「AI 看到的图」和「真实点击坐标」的比例，不能搞混。
     */
    fun capture(grid: Boolean = false): String {
        val out = File(dir(), "screen-${System.currentTimeMillis()}.png")
        return try {
            val proc = ProcessBuilder("su", "-c", "screencap -p ${out.absolutePath}")
                .redirectErrorStream(true)
                .start()
            val finished = proc.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                return err("截图超时")
            }
            if (proc.exitValue() != 0 || !out.exists() || out.length() == 0L) {
                val msg = try { proc.inputStream.bufferedReader().readText().take(200) } catch (_: Exception) { "" }
                return err("screencap 失败 rc=${proc.exitValue()} $msg")
            }

            // 读图片真实尺寸（只读头，不解码全图）。
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(out.absolutePath, opts)
            val imgW = opts.outWidth
            val imgH = opts.outHeight

            val dm = android.content.res.Resources.getSystem().displayMetrics
            JSONObject()
                .put("ok", true)
                .put("path", out.absolutePath)
                .put("screenW", dm.widthPixels)
                .put("screenH", dm.heightPixels)
                .put("imageW", imgW)
                .put("imageH", imgH)
                .toString()
        } catch (e: Exception) {
            Log.w(TAG, "截图异常: ${e.message}")
            err("截图异常: ${e.message}")
        }
    }

    /** 清理旧截图，避免 /sdcard 越堆越多。保留最近 20 张。 */
    fun cleanup(keep: Int = 20) {
        try {
            val files = dir().listFiles { f -> f.isFile && f.name.startsWith("screen-") }
                ?.sortedByDescending { it.lastModified() } ?: return
            files.drop(keep).forEach { it.delete() }
        } catch (e: Exception) {
            Log.w(TAG, "清理截图失败: ${e.message}")
        }
    }

    private fun err(msg: String) = JSONObject().put("ok", false).put("error", msg).toString()
}
