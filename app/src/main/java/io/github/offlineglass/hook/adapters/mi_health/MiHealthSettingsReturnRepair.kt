package io.github.offlineglass.hook.adapters.mi_health

import android.app.Activity
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.offlineglass.targets.TargetSpec

/** Preserved, currently dormant settings-return repair; not auto-installed. */
internal object MiHealthSettingsReturnRepair {
    @Volatile private var miHealthDeviceSettingsReturnPending = false
    /**
     * Xiaomi Fitness keeps the device-page setting rows in an
     * ObservableArrayList owned by DeviceTabV4ViewModel. Opening the native
     * "More settings" page pauses the retained DeviceTabFragmentV4; on some
     * builds its final row is removed while the child Activity is active, but
     * the fragment's normal onResume path only refreshes badges and never
     * rebuilds that list. The same stale return also leaves the retained
     * TabLayout proxy in an unreliable touch state.
     *
     * Track this exact child page and run the app's own loadDevice(false) once
     * when its device fragment resumes. This is intentionally narrower than a
     * generic Activity-resume refresh: other device controls and other apps do
     * not pay for, or inherit side effects from, the repair.
     */
    fun install(
        lpparam: XC_LoadPackage.LoadPackageParam,
        spec: TargetSpec,
        dispatch: (Any?, TargetSpec) -> Unit,
    ) {
        val settingsClass = runCatching {
            XposedHelpers.findClass(
                "com.xiaomi.fitness.device.manager.ui.tab.DeviceSettingListFragment",
                lpparam.classLoader,
            )
        }.getOrNull() ?: return
        val deviceClass = runCatching {
            XposedHelpers.findClass(
                "com.xiaomi.fitness.device.manager.ui.tab.DeviceTabFragmentV4",
                lpparam.classLoader,
            )
        }.getOrNull() ?: return

        runCatching {
            // DeviceSettingListFragment does not declare onResume (the method
            // is inherited), so hook its concrete creation callback instead.
            // hookAllMethods only scans methods declared by the supplied class.
            XposedBridge.hookAllMethods(settingsClass, "onViewCreated", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    miHealthDeviceSettingsReturnPending = true
                }
            })
            XposedBridge.hookAllMethods(deviceClass, "onResume", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!miHealthDeviceSettingsReturnPending) return
                    miHealthDeviceSettingsReturnPending = false
                    val fragment = param.thisObject ?: return
                    val activity = runCatching {
                        XposedHelpers.callMethod(fragment, "getActivity") as? Activity
                    }.getOrNull()
                    val refresh = Runnable {
                        runCatching {
                            val viewModel = XposedHelpers.callMethod(fragment, "getMViewModel")
                            XposedHelpers.callMethod(viewModel, "loadDevice", false)
                            dispatch(fragment, spec)
                            XposedBridge.log(
                                "[OfflineGlass][MiHealth] rebuilt device settings after More settings",
                            )
                        }.onFailure {
                            XposedBridge.log(
                                "[OfflineGlass][MiHealth] device settings return repair failed: $it",
                            )
                        }
                    }
                    val root = activity?.window?.decorView
                    if (root != null) root.post(refresh) else refresh.run()
                }
            })
            XposedBridge.log("[OfflineGlass][MiHealth] device settings return repair installed")
        }
    }


}
