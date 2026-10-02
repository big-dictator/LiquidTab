package io.github.offlineglass.hook

import android.app.Activity
import android.content.res.Configuration
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.offlineglass.hook.adapters.TargetAdapterRegistry
import io.github.offlineglass.hook.adapters.TargetHookSignalsRegistry
import io.github.offlineglass.hook.adapters.TargetHookSignal
import io.github.offlineglass.targets.TargetSpec

private typealias Signal = TargetHookSignal

/** Navigation signals used by target-specific adapters. */
object TargetHooks {


    fun install(lpparam: XC_LoadPackage.LoadPackageParam, spec: TargetSpec) {
        val adapter = TargetAdapterRegistry.forSpec(spec)
        adapter?.installHooks(lpparam, spec)
        // Do not force DeviceTabV4ViewModel.loadDevice(false) on return. That
        // rebuild resets the outer device list scroll position and moves the
        // still-present "More settings" row back behind the floating bar. The
        // actual return failure is handled at the navigation binding layer.
        (signalsFor(spec.key) + adapter?.hookSignals().orEmpty()).forEach { signal ->
            val targetClass = runCatching {
                XposedHelpers.findClass(signal.className, lpparam.classLoader)
            }.getOrNull() ?: return@forEach
            signal.methods.forEach { method ->
                runCatching {
                    XposedBridge.hookAllMethods(targetClass, method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            dispatch(param.thisObject, spec)
                        }
                    })
                }
            }
        }
    }



    private fun dispatch(instance: Any?, spec: TargetSpec) {
        when (instance) {
            is View -> HookCoordinator.onNavigationView(instance, spec)
            is Activity -> HookCoordinator.onActivityResumed(instance, spec)
            null -> Unit
            else -> runCatching {
                (XposedHelpers.callMethod(instance, "getActivity") as? Activity)
                    ?.let { HookCoordinator.onActivityResumed(it, spec) }
            }
        }
    }

    private fun signalsFor(key: String): List<Signal> = if (key == "qq") listOf(
            Signal("com.tencent.mobileqq.activity.home.MainFragment", listOf("onViewCreated", "onTabChanged", "b9", "onResume")),
            Signal("com.tencent.mobileqq.activity.SplashActivity", listOf("openMainFragment", "updateMain", "onWindowFocusChanged")),
        ) else TargetHookSignalsRegistry.forKey(key)

}
