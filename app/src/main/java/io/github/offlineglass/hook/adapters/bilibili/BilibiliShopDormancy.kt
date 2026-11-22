package io.github.offlineglass.hook.adapters.bilibili

import android.os.Looper
import android.os.Trace
import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.compat.XposedHelpers
import io.github.offlineglass.rendering.DormantPageController
import java.lang.ref.WeakReference

/** Verified native shop-page lifecycle. Never intercept data/payment requests. */
internal object BilibiliShopDormancy {
    private val pages = DormantPageController()
    private var host = WeakReference<GlassHostLayout>(null)
    private var installed = false
    private val dormantRequests = java.util.WeakHashMap<ViewGroup, Any>()
    private val activationTrace = ThreadLocal<Boolean>()
    fun bind(value: GlassHostLayout) {
        if (host.get() !== value) host = WeakReference(value)
        if (!value.config.enabled) pages.releaseAll()
    }
    fun release() {
        host.get()?.let { io.github.offlineglass.hook.AnimationResourceGuard.end(it) }
        dormantRequests.clear(); pages.releaseAll(); host.clear()
    }

    fun install(loader: ClassLoader) {
        if (installed) return
        // Stable business type + page-selection interface + full public lifecycle
        // signatures. No current-build short symbol or version decides binding.
        val type = XposedHelpers.findClass("com.mall.ui.page.home.abhome.AbHomeFragment", loader)
        check(type.interfaces.any { it.name == "com.bilibili.lib.homepage.startdust.OnPageSelectChangeListener" })
        val selected = type.getDeclaredMethod("onPageSelected", Map::class.java)
        val unselected = type.getDeclaredMethod("onPageUnselected")
        val destroyed = type.getDeclaredMethod("onDestroyView")
        val getter = type.getMethod("getView")
        check(View::class.java.isAssignableFrom(getter.returnType) &&
            listOf(selected, unselected, destroyed).all { it.returnType == Void.TYPE })
        fun root(instance: Any): ViewGroup? {
            if (Looper.myLooper() != Looper.getMainLooper()) return null
            val owner = host.get()?.takeIf { it.config.enabled && it.isAttachedToWindow } ?: return null
            return (getter.invoke(instance) as? ViewGroup)?.takeIf { it.rootView === owner.rootView }
        }
        XposedBridge.hookMethod(selected, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (io.github.offlineglass.BuildConfig.DEBUG && Trace.isEnabled()) {
                    Trace.beginSection("LiquidTab.ShopActivation")
                    activationTrace.set(true)
                }
                // Restoration must still run if the module was disabled or its
                // host detached while this native page remained retained.
                if (Looper.myLooper() != Looper.getMainLooper()) return
                runCatching { (getter.invoke(param.thisObject) as? ViewGroup)?.let {
                    dormantRequests.remove(it)
                    io.github.offlineglass.hook.AnimationResourceGuard.cancel(it)
                    val reused = pages.wake(it)
                    Trace.beginSection("LiquidTab.ShopWake.reused=$reused"); Trace.endSection()
                } }
            }
            override fun afterHookedMethod(param: MethodHookParam) {
                if (activationTrace.get() == true) {
                    activationTrace.remove(); Trace.endSection()
                }
            }
        })
        XposedBridge.hookMethod(unselected, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                runCatching { root(param.thisObject)?.let {
                    val token = Any()
                    dormantRequests[it] = token
                    val work = {
                        root(param.thisObject)?.let { page ->
                            if (dormantRequests[page] === token) {
                                dormantRequests.remove(page); pages.sleep(page)
                            }
                        }; Unit
                    }
                    if (!io.github.offlineglass.hook.AnimationResourceGuard.defer(it, work)) work()
                    Trace.beginSection("LiquidTab.ShopDormant"); Trace.endSection()
                } }
            }
        })
        XposedBridge.hookMethod(destroyed, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                runCatching { (getter.invoke(param.thisObject) as? ViewGroup)?.let {
                    dormantRequests.remove(it)
                    io.github.offlineglass.hook.AnimationResourceGuard.cancel(it)
                    pages.wake(it, false)
                } }
            }
        })
        installed = true
    }
}
