package io.github.offlineglass.hook.adapters.zhihu

import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ScrollView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.offlineglass.hook.HookConfigReader
import java.util.WeakHashMap

/** Only MessagePage's RN viewport: the main native shell already fills the window. */
internal object ZhihuMessageContent {
    private val fragments = setOf(
        "com.zhihu.android.app.ui.fragment.notification.rn_message.MsgMainFragment",
        "com.zhihu.android.app.ui.fragment.notification.rn_message.OneMsgMainFragment")
    private val bound = WeakHashMap<View, Boolean>()
    fun install(loader: ClassLoader) {
        val owners = HashSet<Class<*>>()
        for (name in fragments) {
            var owner: Class<*>? = runCatching { XposedHelpers.findClass(name, loader) }.getOrNull()
            while (owner != null && owner.declaredMethods.none { it.name == "onViewCreated" }) owner = owner.superclass
            val type = owner ?: continue
            if (!owners.add(type)) continue
            XposedBridge.hookAllMethods(type, "onViewCreated", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.thisObject.javaClass.name !in fragments) return
                    val root = param.args.firstOrNull() as? ViewGroup ?: return
                    if (bound.put(root, true) != null) return
                    val state = MessageViewport(root)
                    root.addOnAttachStateChangeListener(state)
                    if (root.isAttachedToWindow) state.onViewAttachedToWindow(root)
                }
            })
        }
    }
}

private class MessageViewport(private val page: ViewGroup) : View.OnAttachStateChangeListener {
    private data class Extension(val view: View, val previousBottom: Int, val extendedBottom: Int)
    private val changed = ArrayList<Extension>()
    private var observer: ViewTreeObserver? = null
    private var enabled = true
    private var nextRead = 0L
    private var readPending = false
    private val location = IntArray(2)
    private val pageLocation = IntArray(2)
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        val now = android.os.SystemClock.uptimeMillis()
        if (!readPending && now >= nextRead) {
            readPending = true; nextRead = now + 2_000L
            HookConfigReader.readFreshAsync(page.context, "com.zhihu.android") {
                readPending = false
                if (it != null) enabled = it.enabled
            }
        }
        if (enabled && page.isShown) extend() else restore()
        true
    }

    private fun extend() {
        if (page.width <= 0 || page.height <= 0) return
        page.getLocationInWindow(pageLocation)
        val pageBottom = pageLocation[1] + page.height
        // Native hierarchy inspection: page FrameLayout -> ReactRootView ->
        // ReactViewGroup -> ReactViewGroup -> ReactScrollView. Only correct
        // full-width near-bottom ancestors on that exact scroll branch.
        val scrolls = ArrayList<ScrollView>()
        fun collect(view: View, depth: Int) {
            if (depth > 6 || view.visibility != View.VISIBLE) return
            // APK 11.10.0: ReactScrollViewManager.createViewInstance returns f.
            if (view is ScrollView && view.javaClass.name == "com.facebook.react.views.scroll.f") {
                scrolls += view
                return
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) collect(view.getChildAt(i), depth + 1)
        }
        collect(page, 0)
        val maxGap = (32f * page.resources.displayMetrics.density).toInt()
        for (scroll in scrolls) {
            val chain = ArrayList<View>()
            var view: View? = scroll
            while (view != null && view !== page && chain.size < 6) {
                chain += view
                view = view.parent as? View
            }
            if (view !== page) continue
            // Parents first: avoid clipping the newly extended list viewport.
            for (node in chain.asReversed()) {
                node.getLocationInWindow(location)
                val gap = pageBottom - location[1] - node.height
                if (gap !in 1..maxGap || node.width < page.width * 0.95f) continue
                val bottom = node.bottom
                val existing = changed.indexOfFirst { it.view === node }
                val update = Extension(node, bottom, bottom + gap)
                if (existing >= 0) changed[existing] = update else changed += update
                // Change bounds only, never translation, scaling, message
                // data, scroll position or RN child content dimensions.
                node.layout(node.left, node.top, node.right, bottom + gap)
                // f implements ReactClippingViewGroup; resize must propagate
                // its new viewport to the virtualized message content.
                if (node === scroll) runCatching { XposedHelpers.callMethod(scroll, "updateClippingRect") }
            }
        }
    }

    private fun restore() {
        for (entry in changed.asReversed()) {
            if (entry.view.bottom == entry.extendedBottom) {
                val v = entry.view
                v.layout(v.left, v.top, v.right, entry.previousBottom)
            }
        }
        changed.clear()
    }
    override fun onViewAttachedToWindow(v: View) {
        observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(preDraw)
        observer = page.viewTreeObserver.also { it.addOnPreDrawListener(preDraw) }
    }
    override fun onViewDetachedFromWindow(v: View) {
        observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(preDraw)
        observer = null
        restore()
    }
}
