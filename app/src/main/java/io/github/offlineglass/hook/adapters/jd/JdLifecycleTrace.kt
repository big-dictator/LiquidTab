package io.github.offlineglass.hook.adapters.jd

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import io.github.libxposed.api.XposedInterface
import io.github.offlineglass.hook.compat.XposedBridge

/** Trace-only attribution. No sampling, user content or altered lifecycle order. */
internal object JdLifecycleTrace {
    fun install(loader: ClassLoader) {
        runCatching {
            val fragment = Class.forName("androidx.fragment.app.Fragment", false, loader)
            val tab = Class.forName("com.jingdong.common.unification.navigationbar.JDTabFragment", false, loader)
            val methods = listOf(
                fragment.getDeclaredMethod("performCreate", Bundle::class.java) to "LiquidTab.JD.native.create",
                fragment.getDeclaredMethod("performCreateView", LayoutInflater::class.java, ViewGroup::class.java, Bundle::class.java) to "LiquidTab.JD.native.createView",
                fragment.getDeclaredMethod("performResume") to "LiquidTab.JD.native.resume",
            )
            for ((method, label) in methods) XposedBridge.framework().hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept { chain ->
                    if (!android.os.Trace.isEnabled() || !tab.isInstance(chain.thisObject)) chain.proceed()
                    else {
                        android.os.Trace.beginSection(label)
                        if (label == "LiquidTab.JD.native.create")
                            android.util.Log.i("JdColdInit", "native tab create: ${chain.thisObject.javaClass.name}")
                        try { chain.proceed() } finally { android.os.Trace.endSection() }
                    }
                }
        }.onFailure { XposedBridge.log("LiquidTab JD lifecycle trace unavailable: $it") }
    }
}
