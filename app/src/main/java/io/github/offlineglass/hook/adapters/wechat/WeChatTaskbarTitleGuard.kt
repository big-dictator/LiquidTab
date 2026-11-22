package io.github.offlineglass.hook.adapters.wechat

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.compat.XposedHelpers
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** The taskbar owns a second native action bar, not a screenshot of the main bar. */
internal object WeChatTaskbarTitleGuard {
    private val bindings = WeakHashMap<ViewGroup, WeakReference<ViewGroup>>()
    private val copies = WeakHashMap<View, WeChatTaskbarTitleContract.PaintState>()
    private val observers = WeakHashMap<ViewGroup, Boolean>()

    private fun update(root: ViewGroup) {
        val wrapper = bindings[root]?.get() ?: return
        val state = copies[wrapper] ?: return
        val expanded = WeChatRecentDrawerDetector.isExpanded(root, root.rootView, .25f)
        if (!state.update(WeChatChatToolbarGradient.nativeMainHeaderVisible(root.rootView), expanded)) return
        // A suppressed wrapper can have a valid but EMPTY native RenderNode.
        // Releasing the hook alone does not make Android record that node again.
        var view: View? = wrapper
        while (view != null) {
            view.invalidate()
            if (view === root) break
            view = view.parent as? View
        }
    }

    private fun observe(root: ViewGroup) {
        if (observers.put(root, true) != null) return
        val listener = object : android.view.ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
            var observer: android.view.ViewTreeObserver? = null
            fun bind() {
                val next = root.viewTreeObserver
                if (observer === next) return
                observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
                observer = next
                next.addOnPreDrawListener(this)
            }
            override fun onPreDraw(): Boolean { update(root); return true }
            override fun onViewAttachedToWindow(view: View) = bind()
            override fun onViewDetachedFromWindow(view: View) {
                observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
                observer = null
            }
        }
        root.addOnAttachStateChangeListener(listener)
        listener.bind()
    }

    fun install(loader: ClassLoader) {
        val type = XposedHelpers.findClass("com.tencent.mm.plugin.taskbar.ui.TaskBarContainer", loader)
        var owner: Class<*>? = type
        while (owner != null && owner.declaredMethods.none {
            it.name == "onLayout" && it.parameterTypes.contentEquals(arrayOf(
                Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType))
        }) owner = owner.superclass
        XposedBridge.hookAllMethods(owner ?: error("Missing taskbar layout contract"), "onLayout", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val root = param.thisObject as? ViewGroup ?: return
                if (!type.isInstance(root)) return
                bindings[root]?.get()?.let { bound ->
                    var ancestor: View? = bound
                    while (ancestor != null && ancestor !== root) ancestor = ancestor.parent as? View
                    if (ancestor === root) { update(root); return }
                    copies.remove(bound)
                    bound.invalidate()
                }
                val plusId = root.resources.getIdentifier("plus_icon", "id", "com.tencent.mm")
                val backId = root.resources.getIdentifier("actionbar_up_indicator_btn", "id", "com.tencent.mm")
                if (plusId == 0 || backId == 0) return
                val pending = ArrayDeque<Pair<View, Int>>()
                pending += root to 0
                val titles = ArrayList<View>()
                val plus = ArrayList<View>()
                val back = ArrayList<View>()
                var visited = 0
                while (pending.isNotEmpty() && visited++ < 256) {
                    val (view, depth) = pending.removeFirst()
                    if (view is TextView && view.id == android.R.id.text1) titles += view
                    if (view.id == plusId) plus += view
                    if (view.id == backId) back += view
                    if (view is android.widget.AbsListView || view.javaClass.name.contains("RecyclerView")) continue
                    if (view is ViewGroup && depth < 12) for (i in 0 until view.childCount) pending += view.getChildAt(i) to depth + 1
                }
                // Never hide the complete mini-program desktop on an ambiguous
                // common ancestor: the validated copy is a header-sized strip.
                val maximum = (root.rootWindowInsets?.systemWindowInsetTop ?: 0) + 112f * root.resources.displayMetrics.density
                val bar = WeChatTaskbarTitleContract.select<View>(root, titles, plus, back,
                    object : WeChatTaskbarTitleContract.Tree<View> {
                        override fun parent(node: View): View? = node.parent as? View
                        override fun width(node: View): Int = node.width
                        override fun height(node: View): Int = node.height
                    }, root.width * .7f, maximum) as? ViewGroup ?: return
                // cxw inflates a wrapper with two siblings: TaskBarBottomView
                // paints the surface, the anchor-containing group paints controls.
                // Owning only the latter leaves the opaque native surface behind.
                val wrapper = bar.parent as? ViewGroup ?: return
                val surfaces = (0 until wrapper.childCount).map(wrapper::getChildAt).filter {
                    it.javaClass.name == "com.tencent.mm.plugin.taskbar.ui.TaskBarBottomView"
                }
                if (surfaces.size != 1 || wrapper.height <= 0 || wrapper.height > maximum ||
                    wrapper.width < root.width * .7f) return
                bindings[root] = WeakReference(wrapper)
                copies[wrapper] = WeChatTaskbarTitleContract.PaintState()
                observe(root)
                update(root)
            }
        })
        val paintHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                if (copies[view]?.isSuppressed != true) return
                // Keep native animation clocks/input/state intact. Only this
                // duplicate's paint is suppressed while the real bar is shown.
                if (param.args.size == 3) return
                param.result = null
            }
        }
        XposedBridge.hookAllMethods(View::class.java, "draw", paintHook)
        XposedBridge.hookAllMethods(ViewGroup::class.java, "dispatchDraw", paintHook)
    }
}
