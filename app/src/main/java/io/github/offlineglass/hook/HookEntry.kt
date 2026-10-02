package io.github.offlineglass.hook

import android.app.Activity
import android.app.Application
import android.content.Context
import android.view.ViewGroup
import android.view.MotionEvent
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.offlineglass.targets.AppCatalog
import io.github.offlineglass.hook.adapters.TargetAdapterRegistry

class HookEntry : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val spec = AppCatalog.forPackage(lpparam.packageName) ?: return
        if (lpparam.processName != lpparam.packageName &&
            spec.uiProcessSuffixes.none { lpparam.processName == "${lpparam.packageName}:$it" }
        ) return
        android.util.Log.i(
            "LiquidTabInject",
            "HOOK-ENTRY pkg=${lpparam.packageName} key=${spec.key}",
        )

        var initialized = false
        XposedBridge.hookAllMethods(Application::class.java, "attach", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (initialized) return
                val context = param.args.firstOrNull() as? Context ?: return
                initialized = true
                val config = HookConfigReader.readFresh(context, spec.packageName)
                android.util.Log.i("LiquidTabInject", "CONFIG pkg=${spec.packageName} enabled=${config.enabled}")
                if (!config.enabled) {
                    XposedBridge.log(
                        "[OfflineGlass] disabled ${spec.packageName}; no target hooks installed",
                    )
                    return
                }
                installEnabledHooks(lpparam, spec)
                android.util.Log.i("LiquidTabInject", "INSTALLED pkg=${spec.packageName}")
            }
        })
    }

    private fun installEnabledHooks(
        lpparam: XC_LoadPackage.LoadPackageParam,
        spec: io.github.offlineglass.targets.TargetSpec,
    ) {
        if (spec.key == SYSTEM_PICKER_KEY) {
            installSystemPickerHooks(lpparam, spec)
            return
        }
        runCatching {
            val adapter = TargetAdapterRegistry.forSpec(spec)
            TargetHooks.install(lpparam, spec)
            XposedBridge.hookAllMethods(Activity::class.java, "onPostResume", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    if (spec.covers(activity.packageName)) {
                        AppMasterSwitchReceiver.ensureRegistered(activity, spec.packageName)
                        if (adapter?.isBlockingOverlayActivity(activity) == true) {
                            GlassInstaller.setAdapterBlockingOverlayVisible(activity.packageName, true)
                        }
                        HookCoordinator.onActivityResumed(activity, spec)
                    }
                }
            })
            XposedBridge.hookAllMethods(Activity::class.java, "onStop", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    if (adapter?.isBlockingOverlayActivity(activity) == true) {
                        GlassInstaller.setAdapterBlockingOverlayVisible(activity.packageName, false)
                    }
                }
            })
            XposedBridge.hookAllMethods(Activity::class.java, "onDestroy", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    (param.thisObject as? Activity)?.let { activity ->
                        if (adapter?.isBlockingOverlayActivity(activity) == true) {
                            GlassInstaller.setAdapterBlockingOverlayVisible(activity.packageName, false)
                        }
                        HookCoordinator.onActivityDestroyed(activity)
                    }
                }
            })
            // Adapter-owned window gesture observation without replacing target listeners.
            if (TargetAdapterRegistry.forSpec(spec)?.observeWindowTouches == true) {
                XposedBridge.hookAllMethods(Activity::class.java, "dispatchTouchEvent", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        val event = param.args.firstOrNull() as? MotionEvent ?: return
                        GlassInstaller.notifyWindowTouch(activity, event)
                    }
                })
            }
            // Hook Activity.finish() — intercept back-from-video on the main
            // tab activity only, so sub-activities (post detail, etc.) can
            // finish normally.
            XposedBridge.hookAllMethods(Activity::class.java, "finish", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.args.isNotEmpty()) return
                    val activity = param.thisObject as? Activity ?: return
                    if (TargetAdapterRegistry.forPackage(activity.packageName)?.isBackPeekActivity(activity) == true) {
                        if (GlassInstaller.isAdapterBackPeekTabActive(activity)) {
                            HookCoordinator.onActivityBackPressed(activity)
                            param.setResult(null)
                        }
                    }
                }
            })
            XposedBridge.hookAllMethods(Activity::class.java, "moveTaskToBack", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    if (TargetAdapterRegistry.forPackage(activity.packageName)?.isBackPeekActivity(activity) == true) {
                        if (GlassInstaller.isAdapterBackPeekTabActive(activity)) {
                            HookCoordinator.onActivityBackPressed(activity)
                            param.setResult(false)
                        }
                    }
                }
            })
            XposedBridge.log("[OfflineGlass] loaded ${spec.packageName} via ${spec.source}")
        }.onFailure {
            XposedBridge.log("[OfflineGlass] hook install failed for ${spec.packageName}: $it")
        }
    }

    /**
     * Lightweight handling for the system "open with / share" chooser card.
     * This spec carries no liquid-bar adapter; it only re-seats the card's
     * bottom gesture bar (undoing any immersive/climbing effect) so the white
     * scrive sits flush against the screen. The module switch toggles whether
     * this process is hooked at all, so flipping it is enough to enable or
     * disable the behaviour during development.
     */
    private fun installSystemPickerHooks(
        lpparam: XC_LoadPackage.LoadPackageParam,
        spec: io.github.offlineglass.targets.TargetSpec,
    ) {
        XposedBridge.hookAllMethods(Activity::class.java, "onPostResume", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as? Activity ?: return
                if (spec.covers(activity.packageName)) {
                    AppMasterSwitchReceiver.ensureRegistered(activity, spec.packageName)
                    GlassInstaller.applySystemPickerBottomBar(activity)
                }
            }
        })
        XposedBridge.log("[OfflineGlass] loaded ${spec.packageName} (system picker lightweight)")
    }

    private fun findViewByClass(root: android.view.View, className: String): android.view.View? {
        if (root.javaClass.simpleName == className) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val found = findViewByClass(root.getChildAt(i), className)
                if (found != null) return found
            }
        }
        return null
    }

    private companion object {
        const val SYSTEM_PICKER_KEY = "system_picker"
    }
}
