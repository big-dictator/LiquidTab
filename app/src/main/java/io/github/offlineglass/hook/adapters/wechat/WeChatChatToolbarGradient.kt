package io.github.offlineglass.hook.adapters.wechat

import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.compat.XposedHelpers
import io.github.offlineglass.hook.HookConfigReader
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Native header material and the verified chat content reservation share one binding. */
internal object WeChatChatToolbarGradient {
    private val states = WeakHashMap<ViewGroup, State>()
    private val containers = WeakHashMap<View, State>()
    private val layers = WeakHashMap<View, State>()
    private val primaryColors = WeakHashMap<View, Int>()
    private val statusOwners = WeakHashMap<View, State>()
    private val clippingOwners = WeakHashMap<ViewGroup, State>()
    private val paddingOwners = WeakHashMap<View, State>()
    private val lastWindowBind = WeakHashMap<View, Long>()
    private var applying = false
    private var bindingTypes: Array<Class<*>>? = null

    fun bindWindow(window: View) {
        val types = bindingTypes ?: return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - (lastWindowBind[window] ?: 0L) < 400L) return
        lastWindowBind[window] = now
        val queue = ArrayDeque<View>()
        queue += window
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 384) {
            val view = queue.removeFirst()
            if (types[0].isInstance(view)) {
                val parent = view.parent as? ViewGroup ?: continue
                val launcher = isLauncherToolbar(parent)
                if (launcher || containsMessages(parent, types[2]))
                    refresh(parent, types[0], types[1], types[2], launcher)
            }
            if (view is android.widget.AbsListView) continue
            if (view is ViewGroup) for (i in 0 until view.childCount) queue += view.getChildAt(i)
        }
    }

    private class State(root: ViewGroup, container: ViewGroup, toolbar: ViewGroup, val launcher: Boolean) {
        val root = WeakReference(root)
        val container = WeakReference(container)
        val toolbar = WeakReference(toolbar)
        val originals = WeakHashMap<View, Drawable?>()
        val originalClips = WeakHashMap<ViewGroup, Pair<Boolean, Boolean>>()
        val originalTopPadding = WeakHashMap<View, Int>()
        val originalBodyMargins = WeakHashMap<View, Int>()
        var originalContainerBackground: Drawable? = container.background
        var enabled = true
        var baseColor = themeBackground(root)
        val pullMaterial = WeChatTopPullMaterialState()
        val blur = WeChatTopBlurMaterial(root, container, baseColor, pageLocal = true)
        val gradient = blur.drawable(colors(baseColor), status = false)
        val statusGradient = blur.drawable(colors(baseColor), status = true)
        val originalElevation = container.elevation
        val originalTranslationZ = container.translationZ
        val originalOutline = container.outlineProvider
        var lastConfigCheck = 0L
        var layoutLogged = false
        var evidenceWritten = false
        val gradientOrigin = IntArray(2)
        val gradientPosition = IntArray(2)
        var gradientTop = Int.MIN_VALUE
        var gradientHeight = 0
        var gradientStatusHeight = 0
        var gradientColor = 0

        fun updateColor(color: Int) {
            if (Color.alpha(color) < 224 || baseColor == color) return
            baseColor = color
            blur.color = color
            updateGradientGeometry(this)
        }

        fun valid(root: ViewGroup): Boolean = container.get()?.parent === root &&
            toolbar.get()?.parent === container.get()
    }

    private fun colors(color: Int, start: Float = 0f, end: Float = 1f): IntArray {
        return floatArrayOf(start, (start + end) / 2f, end).map { position ->
            val alpha = (200f * (1f - position.coerceIn(0f, 1f))).toInt()
            Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
        }.toIntArray()
    }

    private fun updateGradientGeometry(state: State) {
        val container = state.container.get() ?: return
        val window = state.root.get() ?: return
        window.getLocationInWindow(state.gradientOrigin)
        container.getLocationInWindow(state.gradientPosition)
        val top = (state.gradientPosition[1] - state.gradientOrigin[1]).coerceAtLeast(0)
        val bottom = (top + container.height).coerceAtLeast(1)
        val statusHeight = WeChatStatusGradientSurface.statusHeight(window)
        if (state.gradientTop == top && state.gradientHeight == container.height &&
            state.gradientStatusHeight == statusHeight && state.gradientColor == state.baseColor) return
        state.gradientTop = top
        state.gradientHeight = container.height
        state.gradientStatusHeight = statusHeight
        state.gradientColor = state.baseColor
        val fraction = top.toFloat() / bottom
        state.gradient.colors = colors(state.baseColor, fraction, 1f)
        state.statusGradient.colors = colors(state.baseColor, 0f, statusHeight.toFloat() / bottom)
    }

    private fun applyStatus(state: State) {
        val container = state.container.get() ?: return
        updatePullMaterial(state)
        val status = state.root.get() ?: return
        if (!headerVisible(container)) {
            if (statusOwners[status] === state) {
                statusOwners.remove(status)
                WeChatStatusGradientSurface.hide(status)
            }
            return
        }
        updateGradientGeometry(state)
        statusOwners[status] = state
        WeChatStatusGradientSurface.show(container, state.statusGradient, status)
    }

    private fun updatePullMaterial(state: State) {
        val container = state.container.get() ?: return
        if (!state.pullMaterial.update(state.launcher, container.translationY)) return
        state.blur.hideMaterial = state.pullMaterial.isHidden
        // Translation normally moves a cached RenderNode without recording its
        // background again. Retire that cache on BOTH pull entry and return.
        state.gradient.invalidateSelf()
        state.statusGradient.invalidateSelf()
        container.invalidate()
    }

    private fun headerVisible(view: View): Boolean {
        if (!view.isShown) return false
        val bounds = android.graphics.Rect()
        if (!view.getGlobalVisibleRect(bounds) || bounds.height() < view.height * .5f) return false
        var current: View? = view
        while (current != null) {
            if (current.alpha <= .01f) return false
            current = current.parent as? View
        }
        return true
    }

    private fun themeBackground(view: View): Int {
        val value = TypedValue()
        return if (view.context.theme.resolveAttribute(android.R.attr.colorBackground, value, true) &&
            value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) value.data
        else if (view.resources.configuration.uiMode and 0x30 == 0x20) 0xFF191919.toInt()
        else 0xFFEDEDED.toInt()
    }

    private fun nativeBackgroundColor(background: Drawable?): Int? {
        if (background == null) return null
        if (background is ColorDrawable) return background.color
        // AppCompat wraps its primary background. Sample that drawable alone,
        // without drawing chat content or reading private/obfuscated fields.
        return runCatching {
            val pixel = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            try {
                background.draw(Canvas(pixel))
                pixel.getPixel(0, 0).takeIf { Color.alpha(it) >= 224 }
            } finally { pixel.recycle() }
        }.getOrNull()
    }

    fun install(loader: ClassLoader) {
        WeChatStatusGradientSurface.install(loader)
        XposedBridge.hookAllMethods(View::class.java, "setTranslationY", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val state = containers[param.thisObject] ?: return
                if (state.enabled && state.launcher) updatePullMaterial(state)
            }
        })
        // Descriptive, unobfuscated native business type + verified AppCompat
        // structure/back navigation/message list, never resource-number hints.
        val chatType = XposedHelpers.findClass("com.tencent.mm.pluginsdk.ui.chat.ChattingUILayout", loader)
        val containerType = XposedHelpers.findClass("androidx.appcompat.widget.ActionBarContainer", loader)
        val toolbarType = XposedHelpers.findClass("androidx.appcompat.widget.Toolbar", loader)
        val messageType = XposedHelpers.findClass("com.tencent.mm.ui.chatting.view.MMChattingListView", loader)
        bindingTypes = arrayOf(containerType, toolbarType, messageType)
        XposedBridge.hookAllMethods(android.app.Activity::class.java, "onPostResume", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as? android.app.Activity ?: return
                if (activity.packageName != "com.tencent.mm") return
                val decor = activity.window.decorView
                decor.post { bindWindow(decor) }
                decor.postDelayed({ bindWindow(decor) }, 400)
            }
        })
        var owner: Class<*>? = chatType
        while (owner != null && owner.declaredMethods.none {
            it.name == "onLayout" && it.parameterTypes.contentEquals(arrayOf(
                Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType))
        }) owner = owner.superclass
        val layoutOwner = owner ?: error("Chat layout has no ViewGroup layout contract")
        XposedBridge.hookAllMethods(layoutOwner, "onLayout", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val root = param.thisObject as? ViewGroup ?: return
                if (!chatType.isInstance(root)) return
                refresh(root, containerType, toolbarType, messageType)
            }
        })
        // The launcher and conversations own separate AppCompat containers.
        // Bind at that common semantic lifecycle rather than assuming both
        // live under ChattingUILayout (the launcher does not).
        var containerOwner: Class<*>? = containerType
        while (containerOwner != null && containerOwner.declaredMethods.none {
            it.name == "onLayout" && it.parameterTypes.contentEquals(arrayOf(
                Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType))
        }) containerOwner = containerOwner.superclass
        XposedBridge.hookAllMethods(containerOwner ?: error("Missing AppCompat layout contract"), "onLayout", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val container = param.thisObject as? ViewGroup ?: return
                if (!containerType.isInstance(container)) return
                var ancestor = container.parent as? View
                while (ancestor != null && !chatType.isInstance(ancestor)) ancestor = ancestor.parent as? View
                if (ancestor is ViewGroup) {
                    refresh(ancestor, containerType, toolbarType, messageType)
                    return
                }
                val parent = container.parent as? ViewGroup ?: return
                val existing = states[parent]?.takeIf { it.valid(parent) }
                if (existing != null) {
                    refresh(parent, containerType, toolbarType, messageType, existing.launcher)
                } else if (isLauncherToolbar(parent)) {
                    refresh(parent, containerType, toolbarType, messageType, launcher = true)
                } else if (containsMessages(parent, messageType)) {
                    // Standalone chats place the AppCompat bar beside, rather
                    // than inside, ChattingUILayout. Same semantic content and
                    // back control contract, discovered from the common owner.
                    refresh(parent, containerType, toolbarType, messageType)
                }
            }
        })
        XposedBridge.hookAllMethods(containerType, "setPrimaryBackground", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (applying) return
                val target = param.thisObject as? View ?: return
                val native = param.args.firstOrNull() as? Drawable
                (native as? ColorDrawable)?.let { primaryColors[target] = it.color }
                val state = containers[param.thisObject] ?: return
                if (!state.enabled) return
                if (native === state.gradient) return
                (native as? ColorDrawable)?.let { state.updateColor(it.color) }
            }
        })
        XposedBridge.hookAllMethods(View::class.java, "setBackgroundDrawable", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (applying) return
                val view = param.thisObject as? View ?: return
                containers[view]?.let { state ->
                    if (state.enabled) {
                        val native = param.args.firstOrNull() as? Drawable
                        if (native !== state.gradient) {
                            state.originalContainerBackground = native
                            (native as? ColorDrawable)?.let { state.updateColor(it.color) }
                            param.args[0] = state.gradient
                        }
                    }
                    return
                }
                val state = layers[view] ?: return
                if (!state.enabled) return
                val native = param.args.firstOrNull() as? Drawable
                state.originals[view] = native
                (native as? ColorDrawable)?.let { state.updateColor(it.color) }
                param.args[0] = null
            }
        })
        for (method in listOf("setClipToPadding", "setClipChildren")) {
            XposedBridge.hookAllMethods(ViewGroup::class.java, method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (applying) return
                    val group = param.thisObject as? ViewGroup ?: return
                    val state = clippingOwners[group]?.takeIf { it.enabled } ?: return
                    val native = param.args.firstOrNull() as? Boolean ?: return
                    val original = state.originalClips[group] ?: return
                    state.originalClips[group] = if (method == "setClipChildren") native to original.second
                        else original.first to native
                    param.args[0] = false
                }
            })
        }
        XposedBridge.hookAllMethods(View::class.java, "setPadding", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (applying) return
                val view = param.thisObject as? View ?: return
                val state = paddingOwners[view]?.takeIf { it.enabled } ?: return
                state.originalTopPadding[view] = param.args[1] as Int
                param.args[1] = 0
            }
        })
        WeChatTaskbarTitleGuard.install(loader)
    }

    private fun isLauncherToolbar(parent: ViewGroup): Boolean {
        var context = parent.context
        while (context is android.content.ContextWrapper && context !is android.app.Activity) context = context.baseContext
        if (context.javaClass.name != "com.tencent.mm.ui.LauncherUI") return false
        val pending = ArrayDeque<Pair<View, Int>>()
        pending += parent to 0
        var visited = 0
        while (pending.isNotEmpty() && visited++ < 256) {
            val (view, depth) = pending.removeFirst()
            if (WeChatLauncherPages.isMainPager(view)) return true
            // Navigation structure is above all list rows. Do not inspect them.
            if (view is android.widget.AbsListView || view.javaClass.name.contains("RecyclerView")) continue
            if (view is ViewGroup && depth < 10) for (i in 0 until view.childCount) pending += view.getChildAt(i) to depth + 1
        }
        return false
    }

    private fun refresh(root: ViewGroup, containerType: Class<*>, toolbarType: Class<*>, messageType: Class<*>, launcher: Boolean = false) {
        if (root.width <= 0 || root.height <= 0) return
        var state = states[root]?.takeIf { it.valid(root) }
        if (state == null) {
            val candidates = (0 until root.childCount).mapNotNull { root.getChildAt(it) as? ViewGroup }
                .filter { containerType.isInstance(it) }
            val container = candidates.singleOrNull() ?: return
            val toolbar = (0 until container.childCount).mapNotNull { container.getChildAt(it) as? ViewGroup }
                .filter { toolbarType.isInstance(it) }.singleOrNull() ?: return
            val backId = root.resources.getIdentifier("actionbar_up_indicator", "id", "com.tencent.mm")
            if (!launcher && (backId == 0 || toolbar.findViewById<View>(backId) == null)) return
            // Bounded subtree validation, excluding all message/item children.
            val pending = ArrayDeque<View>()
            pending += root
            var visited = 0
            var messageFound = launcher
            while (!messageFound && pending.isNotEmpty() && visited++ < 512) {
                val view = pending.removeFirst()
                if (messageType.isInstance(view)) { messageFound = true; break }
                if (view is ViewGroup) for (i in 0 until view.childCount) pending += view.getChildAt(i)
            }
            if (!messageFound) return
            state = State(root, container, toolbar, launcher)
            states[root] = state
            containers[container] = state
            // Observe the actual native background writer to capture its current
            // primary drawable without inspecting private AppCompat fields.
            val background = toolbar.background as? ColorDrawable
            background?.let { state.updateColor(it.color) }
            primaryColors[container]?.let(state::updateColor)
            nativeBackgroundColor(state.originalContainerBackground)?.let(state::updateColor)
            root.viewTreeObserver.addOnPreDrawListener {
                val current = states[root]
                if (current != null && root.isShown) {
                    if (current.enabled) {
                        applying = true
                        try { applyStatus(current) } finally { applying = false }
                        if (!current.evidenceWritten) {
                            current.evidenceWritten = true
                            val report = StringBuilder("header=${current.container.get()?.background?.javaClass?.name}\n")
                            val pending = ArrayDeque<View>(); pending += root
                            var visited = 0
                            while (pending.isNotEmpty() && visited++ < 384) {
                                val view = pending.removeFirst()
                                if (view.javaClass.name in setOf("com.tencent.mm.ui.conversation.ConversationListView", "com.tencent.mm.ui.chatting.view.MMChattingListView")) {
                                    var node: View? = view
                                    while (node != null) {
                                        val bg = node.background
                                        report.append("${node.javaClass.name} top=${node.top} height=${node.height} pad=${node.paddingTop} clip=${node.clipBounds} bg=${bg?.javaClass?.name} color=${(bg as? ColorDrawable)?.color} flags=${(node as? ViewGroup)?.clipChildren}/${(node as? ViewGroup)?.clipToPadding}\n")
                                        node = node.parent as? View
                                    }
                                    break
                                }
                                if (view is android.widget.AbsListView) continue
                                if (view is ViewGroup) for (i in 0 until view.childCount) pending += view.getChildAt(i)
                            }
                            val context = root.context.applicationContext
                            val content = report.toString()
                            if (io.github.offlineglass.BuildConfig.DEBUG) Thread {
                                runCatching { java.io.File(context.getExternalFilesDir(null), "liquidtab-wechat-top-evidence.txt").writeText(content) }
                            }.start()
                        }
                    }
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - current.lastConfigCheck >= 1000L) {
                        current.lastConfigCheck = now
                        val config = HookConfigReader.read(root.context, "com.tencent.mm")
                        val blur = config.enabled && config.wechatTopBarReconstruction && config.wechatTopBlurGradient
                        if (current.blur.enabled != blur) {
                            current.blur.enabled = blur
                            current.gradient.invalidateSelf()
                            current.statusGradient.invalidateSelf()
                        }
                        setEnabled(current, config.enabled && config.wechatTopBarReconstruction)
                    }
                }
                true
            }
        }
        val bound = state
        bindContentClipping(root, bound)
        val toolbar = bound.toolbar.get() ?: return
        // Only full-toolbar covering backgrounds; button ripples, title and
        // menu artwork remain native. Cache these bindings across frames.
        val pending = ArrayDeque<Pair<View, Int>>()
        pending += toolbar to 0
        var visited = 0
        while (pending.isNotEmpty() && visited++ < 128) {
            val (view, depth) = pending.removeFirst()
            if (view === toolbar || (view.width >= toolbar.width * .85f && view.height >= toolbar.height * .85f)) {
                if (!bound.originals.containsKey(view)) {
                    bound.originals[view] = view.background
                    (view.background as? ColorDrawable)?.let { bound.updateColor(it.color) }
                    layers[view] = bound
                }
            }
            if (view is ViewGroup && depth < 4) for (i in 0 until view.childCount) pending += view.getChildAt(i) to depth + 1
        }
        HookConfigReader.read(root.context, "com.tencent.mm").let { setEnabled(bound, it.enabled && it.wechatTopBarReconstruction) }
    }

    private fun bindContentClipping(root: ViewGroup, state: State) {
        val pending = ArrayDeque<Pair<View, Int>>()
        pending += root to 0
        var visited = 0
        while (pending.isNotEmpty() && visited++ < 384) {
            val (view, depth) = pending.removeFirst()
            val role = view.javaClass.name
            val matchingList = if (state.launcher) role == "com.tencent.mm.ui.conversation.ConversationListView"
                else role == "com.tencent.mm.ui.chatting.view.MMChattingListView"
            if (matchingList) {
                if (state.launcher && view is android.widget.ListView) state.blur.bindConversations(view)
                if (!state.layoutLogged && view.height > 0) {
                    state.layoutLogged = true
                    val chain = ArrayList<String>()
                    var node: View? = view
                    var limit = 0
                    while (node != null && limit++ < 18) {
                        val params = node.layoutParams
                        chain += "${node.javaClass.simpleName}:top=${node.top},h=${node.height},pad=${node.paddingTop},fit=${node.fitsSystemWindows},lp=${params?.height},margin=${(params as? ViewGroup.MarginLayoutParams)?.topMargin}"
                        if (node === root) break
                        node = node.parent as? View
                    }
                    android.util.Log.i("LiquidTabWeChatTop", "layout launcher=${state.launcher} ${chain.joinToString("|")}")
                }
                var current: View? = view
                while (current != null) {
                    if (current.parent === root && current !== state.container.get()) state.blur.bind(current)
                    if (current is ViewGroup && !state.originalClips.containsKey(current)) {
                        state.originalClips[current] = current.clipChildren to current.clipToPadding
                        clippingOwners[current] = state
                    }
                    if (!state.launcher && current is ViewGroup) bindChatReservation(current, state)
                    if (current === root) break
                    current = current.parent as? View
                }
                if (view is ViewGroup) for (i in 0 until view.childCount) {
                    val list = view.getChildAt(i)
                    if (list is android.widget.AbsListView && !state.originalClips.containsKey(list)) {
                        state.originalClips[list] = list.clipChildren to list.clipToPadding
                        clippingOwners[list] = state
                    }
                }
                continue
            }
            if (state.launcher && role == "com.tencent.mm.pluginsdk.ui.chat.ChattingUILayout") continue
            if (view is android.widget.AbsListView || role.contains("RecyclerView")) continue
            if (view is ViewGroup && depth < 14) for (i in 0 until view.childCount) pending += view.getChildAt(i) to depth + 1
        }
    }

    private fun bindChatReservation(group: ViewGroup, state: State) {
        val header = state.container.get() ?: return
        val chat = (0 until group.childCount).map(group::getChildAt).singleOrNull {
            it.javaClass.name == "com.tencent.mm.pluginsdk.ui.chat.ChattingUILayout"
        }
        // ss.xml has a RelativeLayout owning ChattingUILayout; MMActivityController
        // adds the actionbar reservation to that content root, not the message list.
        if (chat != null && group !== state.root.get() && !state.originalTopPadding.containsKey(group)) {
            state.originalTopPadding[group] = group.paddingTop
            paddingOwners[group] = state
        }
        if (group.javaClass.name != "com.tencent.mm.pluginsdk.ui.chat.ChattingUILayout" || header.parent !== group) return
        // Embedded chats inflate their header stub before the MATCH_PARENT body
        // in a vertical LinearLayout. A negative body margin removes exactly that
        // header's consumed space; the body grows upward while its footer stays put.
        val bodies = (0 until group.childCount).map(group::getChildAt).filterIsInstance<ViewGroup>()
            .filter { candidate -> candidate !== header && candidate.layoutParams.height == ViewGroup.LayoutParams.MATCH_PARENT &&
                containsRole(candidate, "com.tencent.mm.pluginsdk.ui.chat.ChattingScrollLayout") }
        val body = bodies.singleOrNull() ?: return
        val params = body.layoutParams as? android.widget.LinearLayout.LayoutParams ?: return
        state.originalBodyMargins.putIfAbsent(body, params.topMargin)
    }

    private fun containsRole(root: ViewGroup, role: String): Boolean {
        val pending = ArrayDeque<View>()
        pending += root
        var visited = 0
        while (pending.isNotEmpty() && visited++ < 64) {
            val view = pending.removeFirst()
            if (view.javaClass.name == role) return true
            if (view is android.widget.AbsListView) continue
            if (view is ViewGroup) for (i in 0 until view.childCount) pending += view.getChildAt(i)
        }
        return false
    }

    internal fun nativeMainHeaderVisible(window: View): Boolean = states.values.any { state ->
        state.launcher && state.enabled && state.container.get()?.let {
            it.rootView === window && headerVisible(it)
        } == true
    }

    internal fun managesHeader(root: ViewGroup): Boolean = states[root]?.enabled == true

    private fun containsMessages(root: ViewGroup, type: Class<*>): Boolean {
        val pending = ArrayDeque<View>()
        pending += root
        var visited = 0
        while (pending.isNotEmpty() && visited++ < 256) {
            val view = pending.removeFirst()
            if (type.isInstance(view)) return true
            if (view is android.widget.AbsListView) continue
            if (view is ViewGroup) for (i in 0 until view.childCount) pending += view.getChildAt(i)
        }
        return false
    }

    private fun setEnabled(state: State, enabled: Boolean) {
        val container = state.container.get() ?: return
        applying = true
        try {
            if (enabled) {
                if (!state.enabled) {
                    // Native backgrounds can change while top reconstruction is
                    // disabled (for example a theme switch). Adopt those current
                    // values before reclaiming the material/restore ownership.
                    state.originalContainerBackground = container.background
                    nativeBackgroundColor(container.background)?.let(state::updateColor)
                    state.originals.keys.toList().forEach { view ->
                        state.originals[view] = view.background
                        (view.background as? ColorDrawable)?.let { state.updateColor(it.color) }
                    }
                }
                state.enabled = true
                updateGradientGeometry(state)
                container.elevation = 0f
                container.outlineProvider = null
                container.translationZ = 1f
                state.originalTopPadding.keys.toList().forEach { view ->
                    if (view.paddingTop != 0) view.setPadding(view.paddingLeft, 0, view.paddingRight, view.paddingBottom)
                }
                state.originalBodyMargins.forEach { (body, original) ->
                    val params = body.layoutParams as? android.widget.LinearLayout.LayoutParams ?: return@forEach
                    val wanted = original - container.bottom
                    if (params.topMargin != wanted) { params.topMargin = wanted; body.layoutParams = params }
                }
                state.originals.keys.toList().forEach { view -> if (view.background != null) view.background = null }
                state.originalClips.keys.toList().forEach { group ->
                    if (group.clipChildren) group.clipChildren = false
                    if (group.clipToPadding) group.clipToPadding = false
                }
                if (container.background !== state.gradient) container.background = state.gradient
                applyStatus(state)
            } else if (state.enabled) {
                state.enabled = false
                state.blur.enabled = false
                state.originals.forEach { (view, original) -> view.background = original }
                state.originalClips.forEach { (group, flags) ->
                    group.clipChildren = flags.first; group.clipToPadding = flags.second
                }
                container.background = state.originalContainerBackground
                container.elevation = state.originalElevation
                container.translationZ = state.originalTranslationZ
                container.outlineProvider = state.originalOutline
                state.originalTopPadding.forEach { (view, top) ->
                    view.setPadding(view.paddingLeft, top, view.paddingRight, view.paddingBottom)
                }
                state.originalBodyMargins.forEach { (body, top) ->
                    val params = body.layoutParams as? android.widget.LinearLayout.LayoutParams ?: return@forEach
                    params.topMargin = top; body.layoutParams = params
                }
                val status = state.root.get() ?: return
                if (statusOwners[status] === state) {
                    WeChatStatusGradientSurface.hide(status)
                    statusOwners.remove(status)
                }
            }
        } finally { applying = false }
    }
}
