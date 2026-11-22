package io.github.offlineglass.hook.adapters.jd

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import io.github.libxposed.api.XposedInterface
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.compat.XposedBridge
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap

/** Stop JD's full-window blur only for its already-replaced navigation chrome. */
internal object JdHiddenNavigationBlur {
    private data class Api(val type: Class<*>, val degraded: Method, val setDegraded: Method)
    private data class Binding(val host: WeakReference<GlassHostLayout>, val originalDegraded: Boolean)
    private val bindings = WeakHashMap<View, Binding>()
    private var api: Api? = null

    fun install(loader: ClassLoader) {
        runCatching {
            val type = Class.forName("com.jingdong.common.unification.jdBlur.JdBlurView", false, loader)
            val degraded = type.getMethod("isBlurDegraded")
            val setDegraded = type.getMethod("setBlurDegraded", Boolean::class.javaPrimitiveType)
            val updated = type.getDeclaredMethod("onTargetSnapshotUpdated")
            check(ViewGroup::class.java.isAssignableFrom(type) && degraded.returnType == Boolean::class.javaPrimitiveType &&
                setDegraded.returnType == type && updated.returnType == Void.TYPE &&
                listOf(degraded, setDegraded, updated).none { Modifier.isStatic(it.modifiers) })
            XposedBridge.framework().hook(updated).setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    if (Looper.myLooper() != Looper.getMainLooper() || bindings.isEmpty()) return@intercept chain.proceed()
                    val view = chain.thisObject as View
                    val host = bindings[view]?.host?.get()
                    if (host?.isAttachedToWindow == true && host.config.enabled &&
                        (view.parent as? View)?.alpha == 0f) null else chain.proceed()
                }
            api = Api(type, degraded, setDegraded)
        }.onFailure { XposedBridge.log("LiquidTab JD hidden navigation blur contract unavailable: $it") }
    }

    fun suspend(decoration: View, host: GlassHostLayout) {
        val contract = api ?: return
        val group = decoration as? ViewGroup ?: return
        if (group.alpha != 0f || !host.config.enabled) return
        val blur = (0 until group.childCount).map(group::getChildAt)
            .filter(contract.type::isInstance).singleOrNull() ?: return
        val existing = bindings[blur]
        if (existing?.host?.get() === host) return
        runCatching {
            val degraded = existing?.originalDegraded ?: (contract.degraded.invoke(blur) as Boolean)
            contract.setDegraded.invoke(blur, true)
            bindings[blur] = Binding(WeakReference(host), degraded)
        }.onFailure { XposedBridge.log("LiquidTab JD hidden navigation blur rejected: $it") }
    }

    fun release(host: GlassHostLayout, retained: List<WeakReference<View>> = emptyList()) {
        val contract = api ?: return
        val iterator = bindings.entries.iterator()
        while (iterator.hasNext()) {
            val (view, binding) = iterator.next()
            if (binding.host.get() !== host && binding.host.get() != null) continue
            if (retained.any { it.get() === view.parent }) continue
            iterator.remove()
            runCatching { contract.setDegraded.invoke(view, binding.originalDegraded) }
        }
    }
}
