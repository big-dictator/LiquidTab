package io.github.offlineglass.hook.adapters.jd

import android.app.Activity
import android.os.Looper
import io.github.libxposed.api.XposedInterface
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.compat.XposedBridge

/** Reuse only the untouched tab instance JD just constructed to test availability. */
internal object JdFragmentProbeReuse {
    private class Click(val host: GlassHostLayout) {
        var helper: Any? = null
        var setting: Any? = null
        var name: String? = null
        var candidate: Any? = null
        var available = false
        var phase = 0 // 1=original availability, 2=original factory
    }
    private var click: Click? = null // confined to the main thread
    private val mainThread get() = Looper.getMainLooper().thread

    fun withinClick(host: GlassHostLayout, action: () -> Unit) {
        if (Thread.currentThread() !== mainThread) { action(); return }
        val previous = click
        click = Click(host)
        try { action() } finally { click = previous }
    }

    fun install(loader: ClassLoader) {
        runCatching {
            val helper = Class.forName("com.jingdong.jdsdk.auraSetting.AuraFragmentHelper", false, loader)
            val fragment = Class.forName("androidx.fragment.app.Fragment", false, loader)
            val tab = Class.forName("com.jingdong.common.unification.navigationbar.JDTabFragment", false, loader)
            val binding = JdAvailabilityProbeContract.resolve(helper, Activity::class.java, fragment)
            val factory = helper.getDeclaredMethod("newFragment", Activity::class.java, String::class.java)
            val getView = fragment.getMethod("getView")
            val getActivity = fragment.getMethod("getActivity")
            val isAdded = fragment.getMethod("isAdded")
            val framework = XposedBridge.framework()
            fun untouched(value: Any): Boolean = tab.isInstance(value) && getView.invoke(value) == null &&
                getActivity.invoke(value) == null && isAdded.invoke(value) == false
            val handles = ArrayList<XposedInterface.HookHandle>()
            try {
                handles += framework.hook(Class::class.java.getDeclaredMethod("newInstance"))
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept { chain ->
                        val state = if (Thread.currentThread() === mainThread) click else null
                        val type = chain.thisObject as Class<*>
                        val matches = state != null && state.phase != 0 && type.name == state.name
                        val cached = if (matches && state!!.phase == 2 && state.available) state.candidate else null
                        if (cached != null && cached.javaClass === type &&
                            runCatching { untouched(cached) }.getOrDefault(false)) {
                            state!!.candidate = null // one use, within this click only
                            if (android.os.Trace.isEnabled()) android.os.Trace.beginSection("LiquidTab.JD.reuseProbeFragment")
                            try { cached } finally { if (android.os.Trace.isEnabled()) android.os.Trace.endSection() }
                        } else {
                            val result = chain.proceed()
                            if (matches && state!!.phase == 1 && result != null && result.javaClass === type &&
                                runCatching { untouched(result) }.getOrDefault(false)) state.candidate = result
                            result
                        }
                    }
                handles += framework.hook(binding.probe()).setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept { chain ->
                    val state = if (Thread.currentThread() === mainThread) click else null
                    if (state == null || state.phase != 0 || !state.host.config.enabled ||
                        !state.host.isAttachedToWindow || !state.host.hasWindowFocus()) chain.proceed()
                    else {
                        state.helper = chain.thisObject
                        state.setting = runCatching { binding.setting().get(chain.thisObject) }.getOrNull()
                        state.name = chain.getArg(1) as? String
                        state.candidate = null
                        state.available = false
                        state.phase = 1
                        try {
                            chain.proceed().also { state.available = it == true && state.candidate != null }
                        } finally {
                            state.phase = 0
                            if (!state.available) state.candidate = null
                        }
                    }
                }
                handles += framework.hook(factory).setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept { chain ->
                    val state = if (Thread.currentThread() === mainThread) click else null
                    if (state == null || !state.available || state.phase != 0 || state.helper !== chain.thisObject ||
                        state.name != chain.getArg(1) || state.host.activityForAdapter() !== chain.getArg(0) ||
                        !state.host.config.enabled || !state.host.isAttachedToWindow || !state.host.hasWindowFocus() ||
                        state.setting == null || runCatching { binding.setting().get(chain.thisObject) }.getOrNull() !== state.setting) chain.proceed()
                    else {
                        state.phase = 2
                        try { chain.proceed() } finally {
                            state.phase = 0
                            state.available = false
                            state.candidate = null
                        }
                    }
                }
            } catch (error: Throwable) {
                handles.asReversed().forEach { runCatching { it.unhook() } }
                throw error
            }
        }.onFailure { XposedBridge.log("LiquidTab JD probe-instance reuse unavailable: $it") }
    }
}
