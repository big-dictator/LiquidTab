package io.github.offlineglass.hook

import android.app.Activity
import android.app.Application
import android.content.Context
import android.view.ViewGroup
import android.view.MotionEvent
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.compat.callbacks.XC_LoadPackage
import io.github.offlineglass.targets.AppCatalog
import io.github.offlineglass.hook.adapters.TargetAdapterRegistry

class HookEntry {
    fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val spec = AppCatalog.forPackage(lpparam.packageName) ?: return
        if (!spec.hooksAllProcesses && lpparam.processName != lpparam.packageName &&
            spec.uiProcessSuffixes.none { lpparam.processName == "${lpparam.packageName}:$it" }
        ) return
        android.util.Log.i(
            "LiquidTabInject",
            "HOOK-ENTRY pkg=${lpparam.packageName} key=${spec.key}",
        )

        val gate = StartupConfigGate()
        val syncNativeLocale = lpparam.processName == spec.packageName &&
            TargetAdapterRegistry.forSpec(spec)?.supportsNativeEnglishLocale == true
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var foreground: java.lang.ref.WeakReference<Activity>? = null
        var retryScheduled = false
        var startupReceiver: android.content.BroadcastReceiver? = null
        var startupReceiverContext: Context? = null

        fun applyVerified(context: Context): Boolean {
            val config = HookConfigReader.startupSnapshot(spec.packageName)
            if (config == null) return gate.isResolved
            if (syncNativeLocale) {
                runCatching {
                    TargetAppLanguageSync.apply(context, spec.packageName,
                        config.enabled && config.managerEnglishNavigationLabels)
                }.onFailure { android.util.Log.w("LiquidTabLanguage", "App locale sync failed for ${spec.packageName}", it) }
            }
            // Continue applying locale changes from configuration pushes even
            // after the UI hook gate has already been resolved for this process.
            if (gate.isResolved) return true
            if (!gate.resolve(config.enabled)) return true
            if (!syncNativeLocale) {
                startupReceiver?.let { receiver -> runCatching { startupReceiverContext?.unregisterReceiver(receiver) } }
                startupReceiver = null
                startupReceiverContext = null
            }
            android.util.Log.i("LiquidTabStartup", "READY pkg=${spec.packageName} enabled=${config.enabled} " +
                "detached=${config.detachedPublish} blur=${config.blurRadius}")
            runCatching {
                if (!config.enabled) {
                    if (spec.processHooksAlwaysEnabled) spec.processHookInstaller?.invoke(context, lpparam.classLoader)
                } else {
                    val resumed = foreground?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
                    TargetAdapterRegistry.forSpec(spec)?.onConfigurationReady(resumed ?: context, lpparam)
                    installEnabledHooks(context, lpparam, spec)
                    // Rebind an already resumed page, without fabricating lifecycle events.
                    resumed?.let { activity ->
                        if (spec.installUiHooks && !spec.ownsFloatingWindowHooks) {
                            if (spec.key == SYSTEM_PICKER_KEY) GlassInstaller.applySystemPickerBottomBar(activity)
                            else HookCoordinator.onActivityResumed(activity, spec)
                        }
                    }
                }
            }.onFailure { error ->
                android.util.Log.e("LiquidTabStartup", "Initialization failed pkg=${spec.packageName}", error)
                XposedBridge.log("[LiquidTabStartup] initialization failed ${spec.packageName}: $error")
            }
            return true
        }

        lateinit var resolveStartup: (Context) -> Unit
        resolveStartup = resolve@{ context ->
            if (gate.isResolved) return@resolve
            HookConfigReader.readStartupConfig(context, spec.packageName)
            if (applyVerified(context)) return@resolve
            android.util.Log.i("LiquidTabStartup", "WAITING pkg=${spec.packageName}; defaults not applied")
            HookConfigReader.requestConfigSync(context, spec.packageName) {
                applyVerified(context)
            }
            if (!retryScheduled && foreground?.get() != null) {
                retryScheduled = true
                handler.postDelayed({
                    retryScheduled = false
                    foreground?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }?.let(resolveStartup)
                }, 5_000L)
            }
        }

        // Lightweight lifecycle observation only; no target layout mutation
        // before a verified config, even if Provider/LSPosed reads are unavailable.
        XposedBridge.hookAllMethods(Activity::class.java, "onPostResume", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as? Activity ?: return
                if (!spec.covers(activity.packageName)) return
                foreground = java.lang.ref.WeakReference(activity)
                AppMasterSwitchReceiver.ensureRegistered(activity, spec.packageName)
                if (!gate.isResolved) resolveStartup(activity)
                else if (syncNativeLocale) {
                    val appContext = activity.applicationContext
                    HookConfigReader.requestConfigSync(appContext, spec.packageName) {
                        applyVerified(appContext)
                    }
                }
            }
        })
        XposedBridge.hookAllMethods(Activity::class.java, "onStop", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (foreground?.get() === param.thisObject) foreground = null
            }
        })

        // Bootstrap implementations may register observers/compatibility only.
        TargetAdapterRegistry.forSpec(spec)?.installBootstrapHooks(lpparam)
        var attached = false
        XposedBridge.hookAllMethods(Application::class.java, "attach", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (attached) return
                val context = param.args.firstOrNull() as? Context ?: return
                attached = true
                ModernHookDiagnostics.report(context)
                // Receive a normal module push even before a GlassHost exists.
                // Otherwise a failed initial request leaves no listener at all.
                val receiver = object : android.content.BroadcastReceiver() {
                    override fun onReceive(ctx: Context?, intent: android.content.Intent?) {
                        val extras = intent?.extras ?: return
                        val contract = io.github.offlineglass.config.ConfigContract
                        val sourcePackage = extras.getString(contract.KEY_PACKAGE)
                        if (sourcePackage != spec.packageName && sourcePackage != context.packageName) return
                        if (!extras.containsKey(contract.KEY_REVISION)) return
                        HookConfigReader.acceptPushedConfig(spec.packageName,
                            io.github.offlineglass.config.GlassConfig.fromBundle(extras),
                            revision = extras.getLong(contract.KEY_REVISION, -1L))
                        applyVerified(context)
                    }
                }
                runCatching {
                    context.registerReceiver(receiver,
                        android.content.IntentFilter(io.github.offlineglass.config.ConfigContract.ACTION_CONFIG_RESPONSE),
                        io.github.offlineglass.config.ConfigContract.CONFIG_SYNC_PERMISSION,
                        handler, Context.RECEIVER_EXPORTED)
                    startupReceiver = receiver
                    startupReceiverContext = context
                }.onFailure { android.util.Log.w("LiquidTabStartup", "Startup listener unavailable", it) }
                resolveStartup(context)
            }
        })
    }

    private fun installEnabledHooks(
        context: Context,
        lpparam: XC_LoadPackage.LoadPackageParam,
        spec: io.github.offlineglass.targets.TargetSpec,
    ) {
        spec.processHookInstaller?.invoke(context, lpparam.classLoader)
        if (!spec.installUiHooks || spec.ownsFloatingWindowHooks) return
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
