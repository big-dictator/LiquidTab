package io.github.offlineglass.hook.adapters.jd

import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** JD native lists can move children without updating the window scroll position. */
internal object JdScrollSignals {
    private val states = WeakHashMap<View, WeakReference<JdNavigationState>>()
    private var installed = false

    fun install(loader: ClassLoader) {
        if (installed) return
        val recycler = runCatching {
            XposedHelpers.findClass("androidx.recyclerview.widget.RecyclerView", loader)
        }.getOrNull() ?: return
        runCatching {
            val hooks = XposedBridge.hookAllMethods(recycler, "dispatchOnScrolled", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    // Zero-delta layout notifications are not content motion.
                    val dx = param.args.getOrNull(0) as? Int ?: 0
                    val dy = param.args.getOrNull(1) as? Int ?: 0
                    if (dx == 0 && dy == 0) return
                    val view = param.thisObject as? View ?: return
                    if (!view.isAttachedToWindow || !view.isShown) return
                    states[view.rootView]?.get()?.onContentScroll()
                }
            })
            installed = hooks.isNotEmpty()
        }
    }

    fun bind(root: View, state: JdNavigationState) {
        if (states[root]?.get() === state) return
        states[root] = WeakReference(state)
    }

    fun unbind(state: JdNavigationState) {
        val iterator = states.entries.iterator()
        while (iterator.hasNext()) {
            val value = iterator.next().value.get()
            if (value == null || value === state) iterator.remove()
        }
    }
}
