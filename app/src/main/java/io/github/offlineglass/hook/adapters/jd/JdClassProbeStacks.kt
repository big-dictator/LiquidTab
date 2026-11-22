package io.github.offlineglass.hook.adapters.jd

import android.app.Activity
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Only native navigation availability probes; never changes loader delegation. */
internal object JdClassProbeStacks {
    private val state = JdLookupProbeState()
    private val reporterTypes = HashMap<Class<*>, Boolean>()
    private val reporterMethods = HashSet<Method>()
    private var installed = false
    private var restoreFailureLogged = false

    private fun reportRestoreFailure(error: Throwable) {
        if (!restoreFailureLogged) {
            restoreFailureLogged = true
            XposedBridge.log("LiquidTab JD probe stack restoration failed: $error")
        }
    }

    private fun ensureReporter(binding: JdAvailabilityProbeContract.Binding, setting: Any): Boolean {
        reporterTypes[setting.javaClass]?.let { return it }
        if (reporterTypes.size >= 8) return false
        val success = runCatching {
            val method = JdAvailabilityProbeContract.reporterFor(binding, setting)
            if (reporterMethods.add(method)) {
                try {
                    XposedBridge.framework().hook(method)
                        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept { chain ->
                            if (state.active()) runCatching {
                                (chain.getArg(3) as? Throwable)?.let(state::observe)
                            }.onFailure(::reportRestoreFailure)
                            chain.proceed()
                        }
                } catch (error: Throwable) {
                    reporterMethods.remove(method)
                    throw error
                }
            }
        }.onFailure { XposedBridge.log("LiquidTab JD availability error-report contract unavailable: $it") }.isSuccess
        reporterTypes[setting.javaClass] = success
        return success
    }

    fun install(loader: ClassLoader) {
        if (installed) return
        val installedHooks = ArrayList<XposedInterface.HookHandle>()
        runCatching {
            val helper = Class.forName("com.jingdong.jdsdk.auraSetting.AuraFragmentHelper", false, loader)
            val fragment = Class.forName("androidx.fragment.app.Fragment", false, loader)
            val binding = JdAvailabilityProbeContract.resolve(helper, Activity::class.java, fragment)
            val dex = Class.forName("dalvik.system.BaseDexClassLoader", false, loader)
            // Resolve the actual platform bootstrap loader, never JD's private loader symbol.
            val boot = checkNotNull(Any::class.java.classLoader).javaClass
            val searches = setOf(dex, boot).map { type ->
                type.getDeclaredMethod("findClass", String::class.java).also {
                    check(it.returnType == Class::class.java && !Modifier.isStatic(it.modifiers))
                }
            }.distinct()
            val fill = Throwable::class.java.getDeclaredMethod("fillInStackTrace")
            check(fill.returnType == Throwable::class.java)
            val framework = XposedBridge.framework()

            // Direct modern hooks avoid the compatibility wrapper's argument
            // arrays and MethodHookParam allocation on every platform lookup.
            for (method in searches) installedHooks += framework.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept { chain ->
                    if (!state.active()) chain.proceed()
                    else {
                        state.searchEnter()
                        try { chain.proceed() } finally { state.searchExit() }
                    }
                }
            installedHooks += framework.hook(fill)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept { chain ->
                    val error = chain.thisObject as? Throwable
                    if (error != null && state.defer(error)) error else chain.proceed()
                }

            // Enter last. Failed capability installation rolls back all earlier hooks.
            installedHooks += framework.hook(binding.probe())
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept { chain ->
                    val enabled = !restoreFailureLogged && JdNativePageTransitions.ownsCurrentClick() && runCatching {
                        val setting = binding.setting().get(chain.thisObject)
                        setting != null && ensureReporter(binding, setting)
                    }.onFailure { XposedBridge.log("LiquidTab JD availability scope unavailable: $it") }.getOrDefault(false)
                    if (!enabled) chain.proceed()
                    else {
                        state.begin()
                        var escaping: Throwable? = null
                        try { chain.proceed() }
                        catch (error: Throwable) { escaping = error; throw error }
                        finally {
                            // A diagnostic failure must never replace JD's original result/error.
                            runCatching { state.end(escaping) }.onFailure(::reportRestoreFailure)
                        }
                    }
                }
            installed = true
        }.onFailure { error ->
            installedHooks.asReversed().forEach {
                runCatching { it.unhook() }.onFailure { failure ->
                    XposedBridge.log("LiquidTab JD probe hook rollback failed: $failure")
                }
            }
            XposedBridge.log("LiquidTab JD class-probe capability unavailable: $error")
        }
    }
}
