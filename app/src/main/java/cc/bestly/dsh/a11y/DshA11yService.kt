package cc.bestly.dsh.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * 屏幕助手（AccessibilityService）。
 *
 * 为什么要有它：AI 靠 root 只能「跑命令、截图、点坐标」，看不见界面结构 ——
 * 截图上那个「发送」按钮是哪个像素，root 不知道。无障碍服务把系统推来的控件树
 * 交给 AI，它才能按文字找按钮、读输入框、判断点击有没有生效。
 *
 * ★ 与 dsh-tool-accessibility 插件的契约（不能改，改了插件就读不到）：
 *   插件不直接绑这个服务，而是通过本地 HTTP（127.0.0.1:3181）请求，
 *   由 A11yHttpServer 把请求转给这里。所以本类的输出字段必须和插件
 *   期望的 JSON 结构严格一致：text/desc/cls/x/y/w/h/clickable/input/
 *   checked/selected/scrollable/depth。
 *
 * ★ 单例：A11yHttpServer 在同一进程里，通过 instance 直接拿服务引用。
 *   服务断开时必须置空，否则 HTTP 侧会对着一个已死的服务发请求。
 */
class DshA11yService : AccessibilityService() {

    companion object {
        private const val TAG = "DshA11y"

        /** HTTP 桥用的引用。onServiceConnected 时赋值，onDestroy/onUnbind 时清空。 */
        @Volatile
        var instance: DshA11yService? = null
            private set

        /** 单次 dump 最多返回多少节点。防止超大界面（长列表）把响应撑爆。 */
        private const val MAX_NODES = 1200

        fun isRunning(): Boolean = instance != null
    }

    /** 最近一次事件里的前台包名。插件用它判断「现在在哪个 App」。 */
    @Volatile
    private var foregroundPackage: String = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "无障碍服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 只关心窗口状态变化，用来更新前台包名。不做任何重活 —— 这个回调很频繁。
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.let { foregroundPackage = it.toString() }
        }
    }

    override fun onInterrupt() {
        // 系统要求实现，无需处理。
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        Log.i(TAG, "无障碍服务已断开")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ───────────────────────────── 读屏 ─────────────────────────────

    /**
     * 把当前窗口的控件树压平成节点数组。
     *
     * 压平的理由：插件要按「文字」找按钮，树形结构对模型不友好；
     * 一维数组 + depth 字段已经够表达层级，且更容易做「按坐标找最深的节点」。
     */
    fun dump(maxNodes: Int = MAX_NODES): JSONObject {
        val root = rootInActiveWindow
            ?: return JSONObject()
                .put("ok", false)
                .put("error", "无障碍服务拿不到窗口（可能界面正在切换，或前台是受保护界面）")

        val nodes = JSONArray()
        var total = 0
        var truncated = false

        // 用显式栈做深度优先，避免深层嵌套递归爆栈。
        // 用 Pair 而不是局部 data class —— Kotlin 不允许在函数内定义 data class。
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        stack.addLast(root to 0)
        val rect = Rect()

        while (stack.isNotEmpty()) {
            val (node, depth) = stack.removeLast()
            total++

            if (nodes.length() >= maxNodes) {
                truncated = true
                break
            }

            node.getBoundsInScreen(rect)
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            val cls = node.className?.toString().orEmpty()

            // 过滤：既没文字也没描述、且不可点击的纯布局容器，对 AI 没有信息量。
            // 但保留可滚动容器（滚动要靠它判断边界）。
            val worthKeeping = text.isNotEmpty() || desc.isNotEmpty() ||
                node.isClickable || node.isEditable || node.isScrollable

            if (worthKeeping && rect.width() > 0 && rect.height() > 0) {
                val o = JSONObject()
                o.put("text", text)
                o.put("desc", desc)
                o.put("cls", cls)
                o.put("x", rect.left)
                o.put("y", rect.top)
                o.put("w", rect.width())
                o.put("h", rect.height())
                o.put("clickable", node.isClickable)
                o.put("input", node.isEditable)
                o.put("checked", node.isChecked)
                o.put("selected", node.isSelected)
                o.put("scrollable", node.isScrollable)
                o.put("depth", depth)
                nodes.put(o)
            }

            // 子节点倒序入栈，保证出栈顺序和屏幕顺序一致（左上优先）。
            for (i in node.childCount - 1 downTo 0) {
                node.getChild(i)?.let { stack.addLast(it to (depth + 1)) }
            }
        }

        return JSONObject()
            .put("ok", true)
            .put("package", foregroundPackage.ifEmpty { root.packageName?.toString().orEmpty() })
            .put("count", nodes.length())
            .put("truncated", truncated)
            .put("nodes", nodes)
    }

    /** 状态查询。nodeCount 让插件能区分「服务连着但界面是空的」和「服务根本没连」。 */
    fun status(): JSONObject {
        val root = rootInActiveWindow
        val count = if (root == null) 0 else countNodes(root, 2000)
        return JSONObject()
            .put("ok", true)
            .put("running", true)
            .put("package", foregroundPackage.ifEmpty { root?.packageName?.toString().orEmpty() })
            .put("nodeCount", count)
            .put("apiLevel", Build.VERSION.SDK_INT)
            .put("selfPackage", packageName)
    }

    private fun countNodes(node: AccessibilityNodeInfo, limit: Int): Int {
        var n = 1
        for (i in 0 until node.childCount) {
            if (n >= limit) break
            node.getChild(i)?.let { n += countNodes(it, limit - n) }
        }
        return n
    }

    // ───────────────────────────── 找节点 ─────────────────────────────

    /** 按文字精确匹配，找不到再做包含匹配。返回第一个命中的可点击节点（或它自己）。 */
    fun findByText(needle: String, useDesc: Boolean = false): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val exact = ArrayList<AccessibilityNodeInfo>()
        val partial = ArrayList<AccessibilityNodeInfo>()
        collectMatches(root, needle, useDesc, exact, partial, 0)
        val hit = exact.firstOrNull() ?: partial.firstOrNull() ?: return null
        return clickableAncestor(hit) ?: hit
    }

    private fun collectMatches(
        node: AccessibilityNodeInfo,
        needle: String,
        useDesc: Boolean,
        exact: MutableList<AccessibilityNodeInfo>,
        partial: MutableList<AccessibilityNodeInfo>,
        depth: Int,
    ) {
        if (depth > 60) return
        val hay = (if (useDesc) node.contentDescription else node.text)?.toString().orEmpty()
        if (hay.isNotEmpty()) {
            if (hay == needle) exact.add(node)
            else if (hay.contains(needle, ignoreCase = true)) partial.add(node)
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectMatches(it, needle, useDesc, exact, partial, depth + 1) }
        }
    }

    /** 从节点往上找第一个可点击的祖先 —— 文字节点本身常常不可点。 */
    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        var hops = 0
        while (cur != null && hops < 8) {
            if (cur.isClickable) return cur
            cur = cur.parent
            hops++
        }
        return null
    }

    // ───────────────────────────── 动作 ─────────────────────────────

    /** 点坐标。坐标是屏幕物理像素。 */
    fun tapAt(x: Int, y: Int): Boolean = gestureTap(x.toFloat(), y.toFloat())

    /** 点节点中心。 */
    fun tapNode(node: AccessibilityNodeInfo): Boolean {
        // 优先用无障碍原生 ACTION_CLICK：比手势更准，且不受输入法/浮层干扰。
        var cur: AccessibilityNodeInfo? = node
        var hops = 0
        while (cur != null && hops < 8) {
            if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            cur = cur.parent
            hops++
        }
        // 没有可点击祖先就退回手势点中心。
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() <= 0 || rect.height() <= 0) return false
        return gestureTap(rect.exactCenterX(), rect.exactCenterY())
    }

    private fun gestureTap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 60)
        return dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(), null, null
        )
    }

    /** 长按：同一个点按住 durationMs 毫秒。 */
    fun longPressAt(x: Int, y: Int, durationMs: Long = 800): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val safe = durationMs.coerceIn(200L, 10_000L)
        val stroke = GestureDescription.StrokeDescription(path, 0, safe)
        return dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(), null, null
        )
    }

    /** 滑动 / 滚动。durationMs 越小越「甩」，越大越「拖」。 */
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 300): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val safe = durationMs.coerceIn(50L, 5000L)
        val stroke = GestureDescription.StrokeDescription(path, 0, safe)
        return dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(), null, null
        )
    }

    /**
     * 往当前焦点输入框写字。
     *
     * 用 ACTION_SET_TEXT 而不是模拟按键：中文和表情用按键序列根本打不出来。
     * mode=paste 目前和普通输入走同一条路（ACTION_SET_TEXT 本身就是「粘贴」语义），
     * 保留参数是为了和插件契约对齐。
     */
    fun inputText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findFirstEditable(root)
            ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findFirstEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var guard = 0
        while (stack.isNotEmpty() && guard++ < 1500) {
            val n = stack.removeLast()
            if (n.isEditable) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.addLast(it) }
        }
        return null
    }

    /**
     * 滚一屏。direction = up/down/left/right（up 表示「内容向上翻」= 手指往下划）。
     * 优先用节点 ACTION_SCROLL：比手势稳，不会滑到一半被拦截。
     */
    fun scroll(direction: String): Boolean {
        val root = rootInActiveWindow ?: return false
        // 优先找可滚动的焦点容器，其次找任意可滚动节点。
        val target = findScrollable(root) ?: return false
        val action = when (direction.lowercase()) {
            "up", "backward" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            "down", "forward" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else -> return false
        }
        return target.performAction(action)
    }

    private fun findScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var guard = 0
        var biggest: AccessibilityNodeInfo? = null
        var biggestArea = 0
        while (stack.isNotEmpty() && guard++ < 1500) {
            val n = stack.removeLast()
            if (n.isScrollable) {
                val r = Rect().also { n.getBoundsInScreen(it) }
                val area = r.width() * r.height()
                if (area > biggestArea) {
                    biggestArea = area
                    biggest = n
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.addLast(it) }
        }
        return biggest
    }
}
