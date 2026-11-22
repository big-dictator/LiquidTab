package io.github.offlineglass.hook.adapters.weibo

import android.graphics.Rect
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** DMMessageBoxFragment's verified layout contract, independent of its hosting Activity. */
internal object WeiboMessageBinding {
    private val lists = WeakHashMap<View, WeakReference<ViewGroup>>()
    private var installed = false

    fun install() {
        if (installed) return
        val inflate = LayoutInflater::class.java.getMethod("inflate", Int::class.javaPrimitiveType,
            ViewGroup::class.java, Boolean::class.javaPrimitiveType)
        XposedBridge.hookMethod(inflate, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val inflater = param.thisObject as? LayoutInflater ?: return
                val id = param.args.firstOrNull() as? Int ?: return
                val isMessage = runCatching {
                    inflater.context.packageName == "com.sina.weibo" &&
                        inflater.context.resources.getResourceEntryName(id) == "dm_fragment_message_box"
                }.getOrDefault(false)
                if (isMessage) (param.result as? ViewGroup)?.let(::register)
            }
        })
        installed = true
    }

    private fun register(root: ViewGroup) {
        val resources = root.resources
        val pull = resources.getIdentifier("pull_down_view", "id", "com.sina.weibo")
        val recycler = resources.getIdentifier("recycler_view", "id", "com.sina.weibo")
        if (pull == 0 || recycler == 0 || root.id != pull) return
        val list = root.findViewById<View>(recycler) as? ViewGroup ?: return
        if (list.parent !== root) return
        // Public AndroidX contract; manager/adapter are populated after inflation.
        if (!runCatching {
            list.javaClass.getMethod("getAdapter")
            list.javaClass.getMethod("getLayoutManager")
            true
        }.getOrDefault(false)) return
        if (lists[root]?.get() === list) return
        lists[root] = WeakReference(list)
        WeiboMessageProbe.record(root.context, "native message layout registered; recycler public API verified")
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { lists[v] = WeakReference(list) }
            override fun onViewDetachedFromWindow(v: View) { lists.remove(v) }
        })
    }

    /** Startup config may arrive after the native layout: bounded, event-driven recovery. */
    fun discover(root: View) {
        val pull = root.resources.getIdentifier("pull_down_view", "id", "com.sina.weibo")
        if (pull == 0) return
        val stack = ArrayDeque<View>()
        stack += root
        var visited = 0
        while (stack.isNotEmpty() && visited++ < 1024) {
            val view = stack.removeLast()
            if (view is GlassHostLayout) continue
            if (view is ViewGroup) {
                if (view.id == pull) register(view)
                else for (i in 0 until view.childCount) stack += view.getChildAt(i)
            }
        }
        WeiboMessageProbe.record(root.context, "bounded recovery visited=$visited registered=${lists.size}")
    }

    fun visibleList(host: GlassHostLayout): ViewGroup? {
        val visible = ArrayList<ViewGroup>()
        val rect = Rect()
        lists.values.mapNotNull { it.get() }.forEach { view ->
            if (view.isAttachedToWindow && view.rootView === host.rootView && view.isShown &&
                view.width > 0 && view.height > 0 && view.getGlobalVisibleRect(rect) &&
                rect.width() >= view.width * .9f) visible += view
        }
        return visible.singleOrNull()
    }
}
