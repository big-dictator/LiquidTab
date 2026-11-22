package io.github.offlineglass.hook.adapters.jd

import android.view.View
import android.graphics.Rect
import io.github.libxposed.api.XposedInterface
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.compat.XposedHelpers
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** JD native lists can move children without updating the window scroll position. */
internal object JdScrollSignals {
    private val states = WeakHashMap<View, WeakReference<JdNavigationState>>()
    private var installed = false
    private val visibleRect = Rect()

    fun install(loader: ClassLoader) {
        if (installed) return
        val recycler = runCatching {
            XposedHelpers.findClass("androidx.recyclerview.widget.RecyclerView", loader)
        }.getOrNull() ?: return
        runCatching {
            val method = recycler.getDeclaredMethod("dispatchOnScrolled", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            check(method.returnType == Void.TYPE && !java.lang.reflect.Modifier.isStatic(method.modifiers))
            XposedBridge.framework().hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept { chain ->
                val result = chain.proceed()
                // A pager may keep its offscreen RecyclerViews VISIBLE. Their
                // scroll callbacks must not perpetually restart foreground sampling.
                if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper() &&
                    (chain.getArg(0) as Int != 0 || chain.getArg(1) as Int != 0)) {
                    val view = chain.thisObject as View
                    if (view.isAttachedToWindow && view.isShown && view.getGlobalVisibleRect(visibleRect))
                        states[view.rootView]?.get()?.onContentScroll()
                }
                result
            }
            installed = true
        }.onFailure { XposedBridge.log("LiquidTab JD scroll dispatch contract unavailable: $it") }
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
