package io.github.offlineglass.hook.adapters.chatgpt

import android.app.Activity
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.compat.XposedHelpers
import java.util.WeakHashMap

/** ChatGPT-specific binding. Native Compose owns geometry and foreground drawing. */
internal enum class ChatGptSurface { COMPOSER, SEARCH, SIDEBAR, ACTIONS, POPUP }

internal object ChatGptComposerGlass {
    private const val PACKAGE = "com.openai.chatgpt"
    private val attached = WeakHashMap<Activity, MutableMap<View, Controller>>()
    private val pending = WeakHashMap<Activity, Boolean>()
    private var settingsEnabled: Boolean? = null
    private var lastDiag = 0L

    fun install(loader: ClassLoader) {

        // Only window-owner lifecycle can introduce/remove a glass target.
        // Chat content creates ordinary Views while scrolling; scanning the
        // full semantics tree for each child attachment is quadratic work.
        val composeOwner = loader.loadClass("androidx.compose.ui.platform.AndroidComposeView")
        for (method in listOf("onAttachedToWindow", "onDetachedFromWindow")) XposedBridge.hookAllMethods(composeOwner, method, object : io.github.offlineglass.hook.compat.XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                if (view.context.packageName != PACKAGE) return
                val activity = ChatGptWindows.activity(view.context) ?: return
                requestScan(activity)
            }
        })
        val activityClass = runCatching { loader.loadClass("android.app.Activity") }.getOrNull() ?: return
        XposedBridge.hookAllMethods(activityClass, "onPostResume", object : io.github.offlineglass.hook.compat.XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as? Activity ?: return
                if (activity.packageName == PACKAGE && !activity.isFinishing && !activity.isDestroyed) {
                    requestScan(activity)
                }
            }
        })
        XposedBridge.hookAllMethods(activityClass, "onDestroy", object : io.github.offlineglass.hook.compat.XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                (param.thisObject as? Activity)?.let {
                    ChatGptBottomBar.remove(it)
                    attached.remove(it)?.values?.forEach { controller -> controller.dispose() }
                }
            }
        })
    }

    fun requestScan(activity: Activity) {
        val decor = activity.window?.decorView ?: return
        if (pending.put(activity, true) == true) return
        val posted = decor.post {
            pending.remove(activity)
            if (!activity.isDestroyed && !activity.isFinishing) scan(activity)
        }
        if (!posted) pending.remove(activity)
    }

    fun scan(activity: Activity) {
        if (!ChatGptBindings.ensure(activity)) return
        if (settingsEnabled == null) settingsEnabled =
            io.github.offlineglass.hook.HookConfigReader.read(activity, PACKAGE).enabled
        ChatGptLiveConfig.install(activity)
        val windows = ChatGptWindows.roots(activity)
        val all = windows.flatMap { root ->
            findControls(root, root !== activity.window.decorView) { anchor, tabs ->
                ChatGptBottomBar.update(activity, anchor, tabs)
            }
        }
        val groups = all.groupBy { it.anchor }
        val controllers = attached.getOrPut(activity) { linkedMapOf() }
        val extraWindow = windows.any { it !== activity.window.decorView }
        (controllers.keys - groups.keys).forEach { anchor ->
            // Popup semantics can temporarily hide the main window's controls.
            // Keep its producer alive while a separate owned window consumes it.
            val live = controllers[anchor]?.hasLiveBindings() == true
            if (!(anchor.isAttachedToWindow && windows.any { it === anchor.rootView } && (extraWindow || live)))
                controllers.remove(anchor)?.dispose()
        }
        groups.forEach { (anchor, candidates) ->
            val old = controllers[anchor]
            if (old != null) old.update(candidates)
            else controllers[anchor] = Controller(anchor, candidates).also { it.install() }
        }
        ChatGptDiagnostics.report(activity, "binding", "continuous-contour-v1; " + all.joinToString { "${it.surface}:${it.bounds}" })
    }

    fun scanBottomBar(activity: Activity, anchor: View) {
        if (anchor.isAttachedToWindow && anchor.isShown) {
            findControls(anchor, tabsOnly = true, onTabs = { owner, tabs ->
                ChatGptBottomBar.update(activity, owner, tabs)
            })
            return
        }
        val root = activity.window?.decorView ?: return
        findControls(root, tabsOnly = true, onTabs = { owner, tabs ->
            ChatGptBottomBar.update(activity, owner, tabs)
        })
    }

    private data class ComposerCandidate(val anchor: View, val bounds: Rect, val semanticsId: Int, val surface: ChatGptSurface = ChatGptSurface.COMPOSER)

    /** Android Compose exposes virtual semantics through AccessibilityNodeProvider, not View children. */
    private fun findControls(
        root: View, popup: Boolean = false, tabsOnly: Boolean = false, onTabs: (View, ChatGptBottomBar.Candidate?) -> Unit = { _, _ -> },
    ): List<ComposerCandidate> {
        val header = linkedMapOf<ChatGptSurface, ComposerCandidate>()
        var best: ComposerCandidate? = null
        var bestArea = Int.MAX_VALUE
        fun walk(view: View, depth: Int) {
            if (depth > 24 || !view.isShown || view.width <= 0 || view.height <= 0) return
            val provider = runCatching { view.accessibilityNodeProvider }.getOrNull()
            if (provider != null) {
                data class Entry(val id: Int, val node: AccessibilityNodeInfo, val parent: Entry?)
                val entries = mutableListOf<Entry>()
                val pending = ArrayDeque<Entry>()
                runCatching { provider.createAccessibilityNodeInfo(View.NO_ID) }
                    .getOrNull()?.let { pending.add(Entry(View.NO_ID, it, null)) }
                val visited = HashSet<Int>()
                var count = 0
                val bottomLimit = if (tabsOnly) 256 else 1000
                val viewOrigin = IntArray(2).also(view::getLocationOnScreen)
                val bottomStart = viewOrigin[1] + view.height * .65f
                while (pending.isNotEmpty() && count++ < bottomLimit) {
                    val entry = pending.removeFirst()
                    entries.add(entry)
                    val node = entry.node
                    try {
                        if (tabsOnly) {
                            val area = Rect().also(node::getBoundsInScreen)
                            if (!area.isEmpty && area.bottom < bottomStart) continue
                        }
                        val label = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
                            .joinToString(" ").lowercase()
                        if (!tabsOnly && (label.contains("回复 chatgpt") || label.contains("reply to chatgpt") ||
                            label.contains("ask anything") || label.contains("有问题，随便问") || label.contains("询问 chatgpt") || label.contains("ask chatgpt") || node.isEditable)) {
                            val inner = Rect().also(node::getBoundsInScreen)
                            var ancestor: Entry? = entry
                            while (ancestor != null) {
                                val outer = Rect().also(ancestor.node::getBoundsInScreen)
                                // First wider branching ancestor owns the native controls.
                                if (ancestor.node.childCount > 1 && outer.contains(inner) &&
                                    (outer.width() > inner.width() || ancestor.node.childCount >= 3) && outer.height() >= inner.height() &&
                                    outer.width() >= view.width * .60f && outer.width() <= view.width && outer.height() < view.height / 2) {
                                    val area = outer.width() * outer.height()
                                    if (area < bestArea) {
                                        bestArea = area
                                        best = ComposerCandidate(view, outer, ancestor.id)
                                    }
                                    break
                                }
                                ancestor = ancestor.parent
                            }
                        }
                        for (i in 0 until node.childCount) {
                            // Provider nodes have no service connection: getChild() is null.
                            val id = runCatching {
                                val childId = XposedHelpers.callMethod(node, "getChildId", i) as Long
                                (childId ushr 32).toInt()
                            }.getOrNull() ?: continue
                            if (visited.add(id)) runCatching { provider.createAccessibilityNodeInfo(id) }
                                .getOrNull()?.let { pending.add(Entry(id, it, entry)) }
                        }
                    } catch (_: Throwable) { /* Semantics may change during traversal. */ }
                }
                fun label(e: Entry) = (e.node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                    ?: e.node.text?.toString().orEmpty()).trim().lowercase()
                fun bounds(e: Entry) = Rect().also(e.node::getBoundsInScreen)
                fun ancestry(e: Entry): List<Entry> {
                    val result = mutableListOf<Entry>()
                    var current: Entry? = e
                    while (current != null) { result.add(current); current = current.parent }
                    return result
                }
                fun inside(entry: Entry, parent: Entry) = ancestry(entry).any { it === parent }
                if (!popup && ChatGptBindings.method("owner.nodes").declaringClass.isInstance(view)) {
                    val previousSelection = ChatGptBottomBar.verifiedSelection(view)
                    val candidates = entries.mapNotNull { row ->
                        val area = bounds(row)
                        if (area.isEmpty || area.width() < view.width * .65f || area.height() > view.height * .13f) return@mapNotNull null
                        if (area.top < view.height * .72f || row.node.childCount != 3) return@mapNotNull null
                        val slots = entries.filter { it.parent === row }.sortedBy { bounds(it).left }
                        if (slots.size != 3 ||
                            slots.any { (!it.node.isClickable && !it.node.isSelected) || !area.contains(bounds(it)) }) return@mapNotNull null
                        val selected = slots.indices.singleOrNull { slots[it].node.isSelected }
                            ?: previousSelection ?: return@mapNotNull null
                        val labels = listOf(setOf("chat", "聊天", "對話"), setOf("dot", "dots"), setOf("explore", "探索"))
                        val density = view.resources.displayMetrics.density
                        val icons = slots.mapIndexed { index, slot ->
                            val slotBounds = bounds(slot)
                            entries.asSequence().filter { it !== slot && inside(it, slot) && label(it) in labels[index] }
                                .map { ChatGptBottomBar.Icon(it.id, bounds(it)) }
                                .filter { (_, icon) ->
                                    slotBounds.contains(icon) && icon.width() in (20f * density).toInt()..(28f * density).toInt() &&
                                        kotlin.math.abs(icon.height() - icon.width()) <= 2
                                }
                                .singleOrNull()
                        }
                        val labelled = icons.count { it != null }
                        if (labelled < 3 && (previousSelection == null || labelled < 2)) return@mapNotNull null
                        val decor = entries.filter { entry ->
                            val rect = bounds(entry)
                            entry.parent != null && inside(row, entry.parent) && !inside(entry, row) &&
                                entry.node.childCount == 0 && !entry.node.isClickable && !entry.node.isFocusable &&
                                label(entry).isEmpty() && rect.width() >= view.width - 4
                        }
                        val fill = decor.singleOrNull { entry ->
                            val rect = bounds(entry)
                            kotlin.math.abs(rect.top - area.top) <= 4 &&
                                rect.bottom > area.bottom && rect.height() < view.height * .2f
                        }
                        val gradient = fill?.let {
                            // MainTabBar's alternate chrome draws a separate 20 dp fade.
                            decor.singleOrNull { entry ->
                                val rect = bounds(entry)
                                entry !== fill && kotlin.math.abs(rect.height() - 20f * density) <= 4 &&
                                    kotlin.math.abs(rect.bottom - area.top) <= 4 &&
                                    entry.parent === fill.parent
                            }
                        }
                        val chrome = listOfNotNull(fill, gradient).map { ChatGptBottomBar.Chrome(it.id, bounds(it)) }
                        ChatGptBottomBar.Candidate(row.id, area, slots.map { it.id },
                            selected, icons, chrome)
                    }
                    val tabs = candidates.singleOrNull()
                    onTabs(view, tabs)
                    if (!tabsOnly && tabs?.selected == 2) {
                        val search = entries.asSequence().filter {
                            it.node.className == "android.widget.TextView" && label(it) in setOf("search", "搜索", "搜尋")
                        }.flatMap { ancestry(it).asSequence() }.filter { entry ->
                            val area = bounds(entry)
                            val gap = tabs.bounds.top - area.bottom
                            entry.node.isClickable && !entry.node.isEditable &&
                                area.width() >= view.width * .65f && area.height() in
                                    (32f * view.resources.displayMetrics.density).toInt()..
                                    (72f * view.resources.displayMetrics.density).toInt() &&
                                gap in 0..(24f * view.resources.displayMetrics.density).toInt()
                        }.distinctBy { it.id }.toList().singleOrNull()
                        search?.let {
                            header[ChatGptSurface.SEARCH] = ComposerCandidate(view, bounds(it), it.id, ChatGptSurface.SEARCH)
                        }
                    }
                }
                if (tabsOnly) return
                if (popup) {
                    val named = setOf("分享", "置顶", "添加到项目", "已上传文件", "在聊天中查找", "添加到主屏幕", "归档", "删除",
                        "share", "pin", "add to project", "uploaded files", "search in chat", "archive", "delete")
                    val rows = entries.filter { label(it) in named && !bounds(it).isEmpty }
                        .distinctBy(::label)
                    if (rows.size >= 2) {
                        val sets = rows.map { ancestry(it).map { ancestor -> ancestor.id }.toSet() }
                        ancestry(rows.first()).firstOrNull { e ->
                            val r = bounds(e)
                            sets.all { e.id in it } && !r.isEmpty && r.width() < view.width * .92f &&
                                r.height() < view.height * .93f && r.width() > 100 * view.resources.displayMetrics.density &&
                                r.height() > 80 * view.resources.displayMetrics.density
                        }?.let { e -> header[ChatGptSurface.POPUP] = ComposerCandidate(view, bounds(e), e.id, ChatGptSurface.POPUP) }
                    }
                }
                val topEntries = entries.filter { val r = bounds(it); !r.isEmpty && r.top < view.height * .25f }
                val sidebar = topEntries.singleOrNull {
                    label(it) in setOf("菜单", "menu", "打开侧边栏", "open sidebar", "导航菜单", "導覽選單", "navigation menu")
                }
                sidebar?.let { menu ->
                    ancestry(menu).filter {
                        val r = bounds(it)
                        r.width() >= 32 * view.resources.displayMetrics.density &&
                            kotlin.math.abs(r.width() - r.height()) <= 3 && r.height() < view.height * .12f
                    }.minByOrNull { val r = bounds(it); r.width() * r.height() }?.let { e -> header[ChatGptSurface.SIDEBAR] = ComposerCandidate(view, bounds(e), e.id, ChatGptSurface.SIDEBAR) }
                }
                val newChat = topEntries.firstOrNull { label(it) in setOf("新聊天", "新话题", "new chat", "new conversation") }
                val edit = topEntries.firstOrNull { label(it) in setOf("编辑菜单", "edit menu", "更多选项", "more options", "设置", "settings") }
                if (newChat != null && edit != null) {
                    val editAncestors = ancestry(edit).map { it.id }.toSet()
                    ancestry(newChat).firstOrNull {
                        val r = bounds(it)
                        it.id in editAncestors && r.width() > r.height() * 1.3f &&
                            r.width() < view.width * .6f && r.height() < view.height * .12f
                    }?.let { e -> header[ChatGptSurface.ACTIONS] = ComposerCandidate(view, bounds(e), e.id, ChatGptSurface.ACTIONS) }
                }
                // Nova omits descriptions on the two native icon buttons.
                // Use the same header row as its explicitly named drawer control.
                if (!popup && sidebar != null && ChatGptSurface.ACTIONS !in header &&
                    label(sidebar) in setOf("navigation menu", "导航菜单", "導覽選單")) {
                    val row = ancestry(sidebar).firstOrNull {
                        val area = bounds(it)
                        area.width() >= view.width * .85f && area.height() < view.height * .23f
                    }
                    if (row != null) {
                        val groups = entries.filter { it.parent === row }.filter { group ->
                            val area = bounds(group)
                            if (area.isEmpty || area.left < view.width * .5f || area.width() !in
                                (area.height() * 1.6f).toInt()..(area.height() * 2.35f).toInt()) return@filter false
                            val buttons = entries.filter { inside(it, group) && it.node.className == "android.widget.Button" }
                                .map(::bounds).filter { !it.isEmpty }.distinct().sortedBy { it.left }
                            buttons.size == 2 && buttons[0].right <= buttons[1].left &&
                                buttons.all { it.width() in (area.height() * .8f).toInt()..(area.height() * 1.17f).toInt() }
                        }
                        groups.singleOrNull()?.let {
                            header[ChatGptSurface.ACTIONS] = ComposerCandidate(view, bounds(it), it.id, ChatGptSurface.ACTIONS)
                        }
                    }
                }
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i), depth + 1)
        }
        walk(root, 0)
        return listOfNotNull(best) + header.values
    }

    fun refreshConfig(appearanceChanged: Boolean = false) {
        val activities = attached.keys.toList()
        val activity = activities.firstOrNull() ?: return
        val enabled = io.github.offlineglass.hook.HookConfigReader.read(activity, PACKAGE).enabled
        val reenable = enabled && settingsEnabled != true
        settingsEnabled = enabled
        ChatGptBottomBar.refreshConfig(appearanceChanged)
        attached.values.flatMap { it.values.toList() }.forEach { it.refreshConfig() }
        if (reenable) activities.forEach(::requestScan)
    }

    fun composerBounds(activity: Activity, anchor: View): Rect? =
        attached[activity]?.get(anchor)?.composerBounds()

    private class Controller(val anchor: View, private val initial: List<ComposerCandidate>) {
        private val materials = ChatGptSurface.entries.associateWith { ChatGptNativeMaterial(anchor, it) }
        private var backgrounds: Set<Any> = emptySet()
        private var boundRoles: Map<Any,ChatGptSurface> = emptyMap()
        private val lastBounds = java.util.WeakHashMap<Any,Rect>()
        private fun attached(node: Any) = ChatGptBindings.attached(node)
        fun composerBounds(): Rect? {
            val composer = boundRoles.entries.singleOrNull { it.value == ChatGptSurface.COMPOSER }?.key
                ?: return null
            return composer.takeIf(::attached)?.let { ChatGptComposeBackground.screenBounds(it, anchor) }
                ?.takeUnless(Rect::isEmpty)
        }
        fun hasLiveBindings() = backgrounds.any(::attached)
        private var lastPose = Long.MIN_VALUE
        private var moving = false
        private var candidateKey = ""
        private var wasEnabled = true
        private val popup = initial.any { it.surface == ChatGptSurface.POPUP }
        private var missingCandidates = false
        private var headerProbe = 0
        private val retryHeader = Runnable {
            if (!disposed && wasEnabled && anchor.isAttachedToWindow) rebind.run()
        }
        private var disposed = false
        private var nextProbeAt = 0L
        private var probeDelay = 100L
        private val rebind = Runnable {
            if (!disposed && wasEnabled && anchor.isAttachedToWindow) {
                candidateKey = ""
                val activity = ChatGptWindows.activity(anchor.context)
                update(findControls(anchor.rootView, popup) { owner, tabs ->
                    if (activity != null) ChatGptBottomBar.update(activity, owner, tabs)
                })
            }
        }
        private fun requestRebind(now: Long) {
            if (!wasEnabled || now < nextProbeAt) return
            nextProbeAt = now + probeDelay
            probeDelay = (probeDelay * 2).coerceAtMost(2_000L)
            anchor.removeCallbacks(rebind)
            anchor.post(rebind)
        }
        private var lastSourceCheck = 0L
        private val frameListener = android.view.ViewTreeObserver.OnPreDrawListener {
            val config = io.github.offlineglass.hook.HookConfigReader.read(anchor.context, PACKAGE)
            if (config.enabled != wasEnabled) refreshConfig()
            val now = android.os.SystemClock.uptimeMillis()
            if (config.enabled && backgrounds.any { !attached(it) }) requestRebind(now)
            if (config.enabled && now-lastSourceCheck >= 250L) {
                lastSourceCheck = now
                if (backgrounds.isNotEmpty() && backgrounds.all(::attached) &&
                    !ChatGptPageSource.bindingsHealthy(backgrounds,anchor)) {
                    if (ChatGptPageSource.bindAll(backgrounds,anchor))
                        ChatGptComposeBackground.invalidate(backgrounds)
                }
            }
            materials.values.forEach { it.syncShadowPosition() }
            val moved = backgrounds.filter { node ->
                val bounds = ChatGptComposeBackground.screenBounds(node,anchor) ?: return@filter false
                val old = lastBounds.put(node,bounds)
                old != bounds
            }.toSet()
            if (moved.isNotEmpty()) ChatGptComposeBackground.invalidate(moved)
            val enabledPose = config.enabled && config.postureEffectsEnabled
            val revision = io.github.offlineglass.rendering.PostureGlass.revision(anchor, enabledPose)
            val motion = io.github.offlineglass.rendering.LiquidMotion.sample(anchor, config.liquidViscosity, enabledPose)
            if (revision != lastPose || motion.active || moving) ChatGptComposeBackground.invalidate(backgrounds)
            lastPose = revision
            moving = motion.active
            true
        }
        private val layoutListener = android.view.ViewTreeObserver.OnGlobalLayoutListener {
            // IME and typing change placement/size, not target identity. Native
            // draw geometry follows those changes without a full semantics walk.
            if (missingCandidates || backgrounds.isEmpty() || backgrounds.any { !attached(it) })
                requestRebind(android.os.SystemClock.uptimeMillis())
        }
        fun install() {
            anchor.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
            anchor.viewTreeObserver.addOnPreDrawListener(frameListener)
            update(initial)
        }
        fun update(candidates: List<ComposerCandidate>) {
            val enabled = io.github.offlineglass.hook.HookConfigReader.read(anchor.context,PACKAGE).enabled
            wasEnabled = enabled
            if (!enabled) {
                ChatGptComposeBackground.replace(backgrounds,emptySet())
                backgrounds = emptySet()
                boundRoles = emptyMap()
                lastBounds.clear()
                candidateKey = ""
                anchor.removeCallbacks(rebind)
                materials.values.forEach { it.suspendRendering() }
                io.github.offlineglass.rendering.PostureGlass.track(anchor,false)
                return
            }
            val key = candidates.joinToString { "${it.semanticsId}/${it.surface}" }
            if (key == candidateKey && !missingCandidates && backgrounds.isNotEmpty() && backgrounds.all(::attached)) return
            candidateKey = key
            val bindings = linkedMapOf<Any, ChatGptSurface>()
            val localCandidates = candidates.filter { it.anchor === anchor }
            localCandidates.forEach { candidate ->
                ChatGptComposeBackground.find(anchor, candidate.semanticsId, candidate.bounds, candidate.surface in setOf(ChatGptSurface.COMPOSER, ChatGptSurface.SEARCH, ChatGptSurface.POPUP),
                    dock = candidate.surface == ChatGptSurface.COMPOSER || candidate.surface == ChatGptSurface.SEARCH)
                    .forEach { bindings[it] = candidate.surface }
            }
            missingCandidates = localCandidates.any { it.surface !in bindings.values }
            // Accessibility can temporarily omit the editor during IME focus
            // handoff. Keep attached native nodes instead of reverting to solid.
            boundRoles.forEach { (node,role) ->
                if (attached(node) && role !in bindings.values && localCandidates.none { it.surface == role })
                    bindings[node] = role
            }
            ChatGptDiagnostics.report(anchor.context, "candidates", candidates.joinToString { "${it.surface}:${it.bounds}" })
            val next = bindings.keys.toSet()
            ChatGptComposeBackground.replace(backgrounds, next) { node, canvas, shape ->
                val screen = ChatGptComposeBackground.screenBounds(node, anchor)
                val material = bindings[node]?.let(materials::get)
                screen != null && material != null && material.draw(canvas, node, screen, shape)
            }
            boundRoles.forEach { (node, role) ->
                if (bindings[node] != role) materials[role]?.suspendRendering()
            }
            if (next.isNotEmpty() && !missingCandidates) {
                probeDelay = 100L
                nextProbeAt = 0L
            }
            backgrounds = next
            boundRoles = bindings.toMap()
            ChatGptPageSource.bindAll(next, anchor)
            anchor.removeCallbacks(retryHeader)
            // The native header actions can enter semantics after the drawer
            // button and editor. A partial first scan must not end discovery.
            val waitingForHeader = !popup && ChatGptSurface.COMPOSER in bindings.values &&
                ChatGptSurface.SIDEBAR in bindings.values && ChatGptSurface.ACTIONS !in bindings.values
            if (waitingForHeader && headerProbe < 5) {
                anchor.postDelayed(retryHeader, 120L shl headerProbe++)
            } else if (!waitingForHeader) headerProbe = 0
            ChatGptDiagnostics.report(anchor.context, "controls", bindings.values.joinToString())
        }
        fun refreshConfig() {
            val enabled = io.github.offlineglass.hook.HookConfigReader.read(anchor.context, PACKAGE).enabled
            if (wasEnabled != enabled) {
                if (enabled) {
                    wasEnabled = true
                    ChatGptWindows.activity(anchor.context)?.let(::requestScan)
                } else update(emptyList())
            }
            ChatGptComposeBackground.invalidate(backgrounds)
            anchor.invalidate()
        }
        fun dispose() {
            disposed = true
            anchor.removeCallbacks(rebind)
            anchor.removeCallbacks(retryHeader)
            ChatGptComposeBackground.replace(backgrounds, emptySet())
            backgrounds = emptySet()
            materials.values.forEach { it.close() }
            if (anchor.viewTreeObserver.isAlive) {
                anchor.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
                anchor.viewTreeObserver.removeOnPreDrawListener(frameListener)
            }
        }
    }
}
