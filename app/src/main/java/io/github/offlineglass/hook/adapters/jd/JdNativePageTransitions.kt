package io.github.offlineglass.hook.adapters.jd

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.compat.XposedHelpers
import io.github.offlineglass.hook.GlassHostLayout

/** JDTaskModule's common bottom-tab transaction, not cart-specific routing. */
internal object JdNativePageTransitions {
    private val owner = ThreadLocal<GlassHostLayout>()
    private val transactions = ThreadLocal<Int>()
    private val hiddenBars = java.util.WeakHashMap<ViewGroup, java.lang.ref.WeakReference<GlassHostLayout>>()

    internal fun ownsCurrentClick(): Boolean =
        Looper.myLooper() == Looper.getMainLooper() && owner.get() != null

    fun bindHiddenBar(source: ViewGroup, host: GlassHostLayout) {
        if (hiddenBars[source]?.get() === host) return
        val row = JdNavigationBinding.resolve(source) ?: return
        hiddenBars[source] = java.lang.ref.WeakReference(host)
        for (slot in row.slots) {
            runCatching { XposedHelpers.callMethod(slot.icon, "pauseAnimation") }
        }
    }

    private fun isOwnedHiddenIcon(view: View): Boolean {
        var ancestor: View? = view
        repeat(10) {
            val node = ancestor
            if (node is ViewGroup) hiddenBars[node]?.get()?.let { host ->
                if (host.isAttachedToWindow && node.alpha == 0f) return true
            }
            ancestor = ancestor?.parent as? View ?: return false
        }
        return false
    }

    private fun isTab(fragment: Any?): Boolean {
        var type: Class<*>? = fragment?.javaClass
        while (type != null) {
            if (type.name == "com.jingdong.common.unification.navigationbar.JDTabFragment") return true
            type = type.superclass
        }
        return false
    }

    fun click(host: GlassHostLayout, action: () -> Unit) {
        val previous = owner.get()
        owner.set(host)
        try { JdFragmentProbeReuse.withinClick(host, action) } finally {
            if (previous == null) owner.remove() else owner.set(previous)
        }
    }

    fun install(loader: ClassLoader) {
        runCatching { Class.forName("com.airbnb.lottie.LottieAnimationView", false, loader) }.getOrNull()?.let { lottie ->
            for (name in listOf("playAnimation", "resumeAnimation")) XposedBridge.hookAllMethods(lottie, name, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (Looper.myLooper() != Looper.getMainLooper()) return
                    val view = param.thisObject as? View ?: return
                    if (isOwnedHiddenIcon(view)) runCatching { XposedHelpers.callMethod(view, "pauseAnimation") }
                }
            })
        }
        val task = runCatching { Class.forName("com.jingdong.app.mall.basic.JDTaskModule", false, loader) }
            .getOrNull() ?: return
        // The inspected native resolver retries with Thread.sleep(50) when its
        // global Activity provider is temporarily empty. Our owned navigation
        // already has the actual live Activity; use it only in that transaction.
        // No provider fields/classes or obfuscated names are needed.
        runCatching {
            val base = Class.forName("com.jingdong.common.BaseActivity", false, loader)
            val resolvers = task.declaredMethods.filter {
                it.name == "getFrameActivity" && it.parameterCount == 0 && it.returnType == base &&
                    !java.lang.reflect.Modifier.isStatic(it.modifiers)
            }
            check(resolvers.size == 1) { "native task Activity resolver candidates=${resolvers.size}" }
            XposedBridge.hookMethod(resolvers.single(), object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (Looper.myLooper() != Looper.getMainLooper() || (transactions.get() ?: 0) == 0) return
                    val host = owner.get() ?: return
                    if (!host.isAttachedToWindow || !host.hasWindowFocus()) return
                    val activity = host.activityForAdapter() ?: return
                    if (base.isInstance(activity) && !activity.isFinishing && !activity.isDestroyed)
                        param.result = activity
                }
            })
        }.onFailure { XposedBridge.log("LiquidTab JD native Activity contract unavailable: $it") }
        val record = runCatching { Class.forName("androidx.fragment.app.BackStackRecord", false, loader) }
            .getOrNull() ?: return
        val fragmentType = runCatching { Class.forName("androidx.fragment.app.Fragment", false, loader) }
            .getOrNull() ?: return
        // JD uses both replace and its built-in retained-page hide/show path.
        // Scope batching to either owned tab transaction; the former hook
        // silently missed addAndroidCommit and its intermediate lifecycle work.
        for (method in listOf("replaceAndCommit", "addAndroidCommit")) {
        val candidates = task.declaredMethods.filter {
            it.name == method && it.returnType == Void.TYPE &&
                !java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType, fragmentType, String::class.java))
        }
        if (candidates.size != 1) {
            XposedBridge.log("LiquidTab JD $method transaction contract candidates=${candidates.size}")
            continue
        }
        XposedBridge.hookMethod(candidates.single(), object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (param.args.size != 3 || owner.get()?.isAttachedToWindow != true) return
                if (isTab(param.args[1])) transactions.set((transactions.get() ?: 0) + 1)
            }
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.args.size != 3 || owner.get() == null || !isTab(param.args[1])) return
                val depth = transactions.get() ?: return
                if (depth <= 1) transactions.remove() else transactions.set(depth - 1)
            }
        })
        }
        XposedBridge.hookAllMethods(record, "commitAllowingStateLoss", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (Looper.myLooper() != Looper.getMainLooper() || (transactions.get() ?: 0) == 0) return
                val host = owner.get() ?: return
                if (!host.isAttachedToWindow || !host.hasWindowFocus()) return
                // Keep JD's animation resources and lifecycle callbacks. Only
                // eliminate intermediate operations in the same native batch.
                runCatching {
                    XposedHelpers.callMethod(param.thisObject, "setReorderingAllowed", true)
                }
            }
        })
    }
}
