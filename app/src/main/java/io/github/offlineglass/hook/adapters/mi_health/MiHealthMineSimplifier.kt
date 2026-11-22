package io.github.offlineglass.hook.adapters.mi_health

import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.offlineglass.hook.HookConfigReader
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.WeakHashMap

/** Owns verified MyProfile sections and QuickActions, including their Yoga space. */
internal class MiHealthMineSimplifier {
    private val nativeVisibility = WeakHashMap<View, Int>()
    private val applied = ArrayList<Applied>()
    private var lastPoll = 0L
    private var lastScan = 0L
    private var needsScan = true
    private var observedScroll: WeakReference<ViewGroup>? = null
    private var observedTree: android.view.ViewTreeObserver? = null
    private val layoutListener = android.view.ViewTreeObserver.OnGlobalLayoutListener { needsScan = true }
    @Volatile private var generation = 0L
    @Volatile private var pending = false
    private var enabled = false
    private var reportedFailure = false
    private val targets = setOf("小米运动健康会员", "小习惯", "运动健康周报", "活力竞赛", "健康问诊")
    private val quickActions = setOf("我的活动", "我的课程", "我的亲友", "我的订单", "我的资产")
    private val protected = setOf("我的活动", "我的课程", "我的亲友", "我的订单", "我的资产", "我的路线库", "App 设置", "第三方数据管理")
    private data class Applied(val bridge: Bridge, val nodes: Map<Int, WeakReference<Any>>)

    fun update(host: View, scroll: ViewGroup?) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastPoll >= 600L) {
            lastPoll = now
            val cfg = HookConfigReader.read(host.context, host.context.packageName)
            val wanted = cfg.enabled && cfg.miHealthSimplifyMine
            if (wanted != enabled) {
                generation++; enabled = wanted; needsScan = true
                if (!wanted) restore()
            }
        }
        if (!enabled || pending || scroll?.isShown != true) return
        if (observedScroll?.get() !== scroll) {
            observedTree?.let { if (it.isAlive) it.removeOnGlobalLayoutListener(layoutListener) }
            observedScroll = WeakReference(scroll)
            observedTree = scroll.viewTreeObserver.also { if (it.isAlive) it.addOnGlobalLayoutListener(layoutListener) }
            needsScan = true
        }
        // Layout/mount changes bypass the maintenance interval. This runs from
        // the existing pre-draw hook, before newly mounted cards can be painted.
        if (!needsScan && now - lastScan < 600L) return
        needsScan = false; lastScan = now
        val content = scroll.getChildAt(0) as? ViewGroup ?: return
        val stack = ArrayDeque<View>(); stack += content
        val labels = ArrayList<TextView>(); val safe = ArrayList<TextView>(); val quick = ArrayList<TextView>()
        var visited = 0
        while (stack.isNotEmpty() && visited++ < 450) {
            val view = stack.removeLast()
            if (view is TextView) {
                val text = view.text?.toString()?.trim()
                if (text in targets) labels += view
                if (text in protected) safe += view
                if (text in quickActions) quick += view
            }
            if (view is ViewGroup) for (i in view.childCount - 1 downTo 0) stack += view.getChildAt(i)
        }
        // Require a complete bounded scan plus the native quick-actions/settings contract.
        val completeQuick = quick.size == quickActions.size && quick.map { it.text.toString().trim() }.toSet() == quickActions
        if (stack.isNotEmpty() || safe.map { it.text.toString().trim() }.toSet().intersect(protected).size < 3 || (labels.isEmpty() && !completeQuick)) return
        val safeAncestors = HashSet<View>()
        safe.forEach { text ->
            var view: View? = text
            while (view != null) { safeAncestors += view; if (view === content) break; view = view.parent as? View }
        }
        val legacyCards = labels.mapNotNull { label ->
            var view: View = label
            while (view.parent is ViewGroup && view.parent !== content && (view.parent as? View) !in safeAncestors) view = view.parent as ViewGroup
            (view as? ViewGroup)?.takeIf { it !== content && it !in safeAncestors }
        }
        // EntranceItem is a Pressable containing its image and label. Hide the
        // native entrance while the verified common Yoga row is collapsed.
        val quickEntrances = if (completeQuick) quick.mapNotNull { it.parent as? ViewGroup }
            .filter { parent -> safe.none { it !in quick && isInside(it, parent, content) } } else emptyList()
        val cards = (legacyCards + quickEntrances).distinct().filter { !nativeVisibility.containsKey(it) }
        if (cards.isEmpty()) return
        val bridge = runCatching { Bridge.bind(scroll.context) }.getOrElse { fail(it); return }
        val request = generation
        val anchorTags = labels.map { it.id }.filter { it > 0 }
        val safeTags = safe.map { it.id }.filter { it > 0 }
        val quickTags = if (completeQuick) quick.map { it.id }.filter { it > 0 }.distinct() else emptyList()
        val boundaryTag = content.id
        if (boundaryTag <= 0 || (anchorTags.isEmpty() && quickTags.size != quickActions.size)) return
        pending = true
        // Suppress first paint while the native-modules queue computes the matching Yoga transaction.
        cards.forEach { nativeVisibility[it] = it.visibility; it.visibility = View.INVISIBLE }
        val accepted = bridge.enqueue {
            val changed = LinkedHashMap<Int, WeakReference<Any>>()
            try {
                if (generation != request) return@enqueue
                val implementation = bridge.implementation.invoke(bridge.module)
                val boundary = bridge.resolve.invoke(implementation, boundaryTag) ?: error("Missing content shadow node")
                fun pathToContent(start: Any): List<Any> {
                    val path = ArrayList<Any>()
                    var node: Any? = start
                    while (node != null && path.size < 40) {
                        path += node
                        if (node === boundary) return path
                        node = bridge.parent.invoke(node)
                    }
                    error("Card/protected anchor outside the bound content tree")
                }
                val protectedNodes = safeTags.mapNotNull { bridge.resolve.invoke(implementation, it) }
                check(protectedNodes.size >= 3) { "Missing quick-action shadow anchors" }
                val blocked = protectedNodes.flatMap { pathToContent(it) }.map { bridge.tag(it) }.toHashSet()
                val selected = LinkedHashMap<Int, Any>()
                anchorTags.forEach { tag ->
                    val start = bridge.resolve.invoke(implementation, tag) ?: return@forEach
                    var candidate: Any? = null
                    for (node in pathToContent(start)) {
                        if (node === boundary || bridge.tag(node) in blocked) break
                        if (bridge.viewClass.invoke(node) == "RCTView") candidate = node
                    }
                    candidate?.let { selected[bridge.tag(it)] = it }
                }
                if (quickTags.size == quickActions.size) {
                    val paths = quickTags.map { tag ->
                        pathToContent(bridge.resolve.invoke(implementation, tag) ?: error("Missing QuickActions label"))
                    }
                    // QuickActions HStack is the lowest common RCTView of all
                    // five EntranceItems; never collapse their entire profile.
                    val row = paths.first().firstOrNull { node -> node !== boundary &&
                        bridge.viewClass.invoke(node) == "RCTView" && paths.all { path -> path.any { it === node } } }
                        ?: error("No exclusive QuickActions row")
                    check(protectedNodes.filter { bridge.tag(it) !in quickTags }
                        .none { node -> pathToContent(node).any { it === row } }) { "QuickActions row contains protected settings" }
                    selected[bridge.tag(row)] = row
                }
                check(selected.isNotEmpty()) { "No exclusive MyProfile shadow sections" }
                selected.forEach { (tag, node) ->
                    // Positive layout proves the existing Yoga display is flex, not native-business none.
                    val height = (bridge.layoutHeight.invoke(node) as Number).toFloat()
                    if (height > 0f) {
                        changed[tag] = WeakReference(node)
                        bridge.setDisplay(tag, "none")
                    }
                }
                bridge.flush.invoke(bridge.module)
                if (generation != request) bridge.restore(changed)
                else host.post { if (generation == request) applied += Applied(bridge, changed) else bridge.enqueue { bridge.restore(changed) } }
            } catch (error: Throwable) {
                runCatching { bridge.restore(changed) }
                host.post { cards.forEach { view -> nativeVisibility.remove(view)?.let { view.visibility = it } }; fail(error) }
            } finally { pending = false }
        }
        if (!accepted) {
            pending = false
            cards.forEach { view -> nativeVisibility.remove(view)?.let { view.visibility = it } }
        }
    }

    private fun isInside(view: View, parent: ViewGroup, boundary: ViewGroup): Boolean {
        var current: View? = view
        var depth = 0
        while (current != null && depth++ < 40) {
            if (current === parent) return true
            if (current === boundary) return false
            current = current.parent as? View
        }
        return false
    }

    private fun fail(error: Throwable) {
        if (!reportedFailure) { reportedFailure = true; Log.w("MiHealthMineSimplify", "Card contract unavailable", error) }
    }

    private fun restore() {
        nativeVisibility.forEach { (view, visibility) -> view.visibility = visibility }
        nativeVisibility.clear()
        applied.forEach { state -> state.bridge.enqueue { state.bridge.restore(state.nodes) } }
        applied.clear()
    }
    fun dispose() {
        observedTree?.let { if (it.isAlive) it.removeOnGlobalLayoutListener(layoutListener) }
        observedTree = null; observedScroll = null
        generation++; enabled = false; needsScan = true; restore()
    }

    private class Bridge(
        val context: Any, val module: Any, val queue: Method, val implementation: Method,
        val resolve: Method, val parent: Method, val getTag: Method, val viewClass: Method,
        val layoutHeight: Method, val update: Method, val flush: Method,
        val createMap: Method, val putString: Method,
    ) {
        fun tag(node: Any): Int = (getTag.invoke(node) as Number).toInt()
        fun enqueue(action: () -> Unit): Boolean = runCatching {
            queue.invoke(context, Runnable { runCatching { action() }.onFailure {
                Log.w("MiHealthMineSimplify", "Native layout transaction unavailable", it)
            } })
        }.onFailure { Log.w("MiHealthMineSimplify", "React queue unavailable", it) }.isSuccess
        fun setDisplay(tag: Int, display: String) {
            val map = createMap.invoke(null)
            putString.invoke(map, "display", display)
            update.invoke(module, tag, "RCTView", map)
        }
        fun restore(nodes: Map<Int, WeakReference<Any>>) {
            val impl = implementation.invoke(module)
            nodes.forEach { (tag, reference) ->
                val node = reference.get()
                if (node != null && resolve.invoke(impl, tag) === node) setDisplay(tag, "flex")
            }
            flush.invoke(module)
        }
        companion object {
            fun bind(initial: Context): Bridge {
                var ctx: Context = initial
                val loader = initial.classLoader
                val react = Class.forName("com.facebook.react.bridge.ReactContext", false, loader)
                var depth = 0
                while (!react.isInstance(ctx) && ctx is ContextWrapper && depth++ < 8) ctx = ctx.baseContext
                check(react.isInstance(ctx)) { "Not a React context" }
                val type = Class.forName("com.facebook.react.uimanager.UIManagerModule", false, loader)
                val module = react.getMethod("getNativeModule", Class::class.java).invoke(ctx, type) ?: error("No paper UIManager")
                val implementation = type.getMethod("getUIImplementation")
                val resolve = implementation.returnType.getMethod("resolveShadowNode", Int::class.javaPrimitiveType)
                val node = resolve.returnType
                val readable = Class.forName("com.facebook.react.bridge.ReadableMap", false, loader)
                val writable = Class.forName("com.facebook.react.bridge.WritableMap", false, loader)
                val arguments = Class.forName("com.facebook.react.bridge.Arguments", false, loader)
                return Bridge(ctx, module, react.getMethod("runOnNativeModulesQueueThread", Runnable::class.java), implementation,
                    resolve, node.getMethod("getParent"), node.getMethod("getReactTag"), node.getMethod("getViewClass"),
                    node.getMethod("getLayoutHeight"), type.getMethod("updateView", Int::class.javaPrimitiveType, String::class.java, readable),
                    type.getMethod("onBatchComplete"), arguments.getMethod("createMap"), writable.getMethod("putString", String::class.java, String::class.java))
            }
        }
    }
}
