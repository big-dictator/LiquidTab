package io.github.offlineglass.hook

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import io.github.offlineglass.hook.adapters.TargetAdapterRegistry
import io.github.offlineglass.targets.TargetSpec
import java.lang.ref.WeakReference
import java.util.WeakHashMap

object HookCoordinator {
    private val sessions = WeakHashMap<Activity, ScannerSession>()
    private val pendingNavigationScans = WeakHashMap<ViewGroup, Any>()
    private var predictiveBackHookAttempted = false
    private var lastResumedActivity: WeakReference<Activity>? = null

    /**
     * System back comes through two channels: the legacy Activity.onBackPressed
     * hook (installed in HookEntry) and, once the app opts into the predictive
     * back gesture on API 33+, androidx's OnBackPressedDispatcher, which the
     * framework calls straight from OnBackInvokedCallback. The androidx class
     * is only loadable from the host app's own class loader after it
     * bootstraps, so retry the dispatcher hook on every resume until it sticks.
     */
    fun onActivityBackPressed(activity: Activity): Boolean {
        if (activity.isFinishing || activity.isDestroyed) return false
        return GlassInstaller.notifyBackPressed(activity)
    }

    @Synchronized
    private fun maybeHookPredictiveBackDispatcher(activity: Activity) {
        if (predictiveBackHookAttempted) return
        val dispatcherClass = runCatching {
            io.github.offlineglass.hook.compat.XposedHelpers.findClass(
                "androidx.activity.OnBackPressedDispatcher",
                activity.classLoader,
            )
        }.getOrNull() ?: return
        predictiveBackHookAttempted = true
        runCatching {
            io.github.offlineglass.hook.compat.XposedBridge.hookAllMethods(
                dispatcherClass,
                "onBackPressed",
                object : io.github.offlineglass.hook.compat.XC_MethodHook() {
                    override fun beforeHookedMethod(param: io.github.offlineglass.hook.compat.XC_MethodHook.MethodHookParam) {
                        val current = lastResumedActivity?.get() ?: return
                        if (current.isFinishing || current.isDestroyed) return
                        val dispatcher = runCatching {
                            io.github.offlineglass.hook.compat.XposedHelpers.callMethod(
                                current,
                                "getOnBackPressedDispatcher",
                            )
                        }.getOrNull() ?: return
                        if (dispatcher !== param.thisObject) return
                        if (onActivityBackPressed(current)) {
                            param.setResult(null)
                        }
                    }
                },
            )
        }.onFailure {
            predictiveBackHookAttempted = false
        }
    }

    @Synchronized
    fun onActivityResumed(activity: Activity, spec: TargetSpec) {
        lastResumedActivity = WeakReference(activity)
        maybeHookPredictiveBackDispatcher(activity)
        if (!matchesActivity(activity, spec)) return
        val config = HookConfigReader.read(activity, spec.packageName)
        if (!config.enabled) return

        val session = sessions.getOrPut(activity) { ScannerSession(activity, spec) }
        session.scheduleScans()
        HookConfigReader.reportActive(activity, spec.packageName)
    }

    fun onNavigationView(view: View, spec: TargetSpec) {
        val navigation = view as? ViewGroup ?: return
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            navigation.post { onNavigationView(navigation, spec) }
            return
        }
        val activity = findActivity(view.context) ?: return
        if (!spec.covers(activity.packageName) || activity.isFinishing || activity.isDestroyed) return
        // One burst owns an initial probe plus its terminal geometry checks.
        // Repeated setTabs/selection notifications share that batch instead of
        // multiplying config reads, hierarchy discovery and installation work.
        if (pendingNavigationScans.containsKey(navigation)) return
        val batch = Any()
        pendingNavigationScans[navigation] = batch
        listOf(0L, 80L, 260L).forEach { delay ->
            navigation.postDelayed({
                if (pendingNavigationScans[navigation] !== batch) return@postDelayed
                if (delay == 260L) pendingNavigationScans.remove(navigation)
                if (activity.isFinishing || activity.isDestroyed) return@postDelayed
                if (!navigation.isAttachedToWindow || navigation.parent == null) return@postDelayed
                val work: () -> Unit = work@ {
                if (!navigation.isAttachedToWindow || activity.isFinishing || activity.isDestroyed) return@work
                val config = HookConfigReader.read(activity, spec.packageName)
                if (!config.enabled) return@work
                val slots = NavigationFinder.estimateSlotCount(navigation, spec)
                // A mid-inflate row reports 0 (e.g. Meituan Takeout's
                // server-driven tabs); installing that partial count would
                // lock it in because install() is idempotent per source view.
                if (slots < 2) return@work
                if (GlassInstaller.install(activity, navigation, slots, config, spec)) {
                    HookConfigReader.reportActive(activity, spec.packageName)
                }
                }
                if (!AnimationResourceGuard.defer(navigation, work)) work()
            }, delay)
        }
    }

    private fun findActivity(context: Context?): Activity? {
        var current = context
        repeat(12) {
            when (current) {
                is Activity -> return current
                is ContextWrapper -> current = current.baseContext
                else -> return null
            }
        }
        return null
    }

    private fun matchesActivity(activity: Activity, spec: TargetSpec): Boolean {
        if (spec.activityHints.isEmpty()) return true
        val name = activity.javaClass.name
        return spec.activityHints.any { hint -> name == hint || name.endsWith(hint.substringAfterLast('.')) }
    }

    @Synchronized
    fun onActivityDestroyed(activity: Activity) {
        sessions.remove(activity)?.dispose()
    }
}

private class ScannerSession(activity: Activity, private val spec: TargetSpec) {
    private val activityRef = WeakReference(activity)
    private var installed = false
    private var lastScan = 0L
    private var recoveryScanPending = false
    private var listener: ViewTreeObserver.OnGlobalLayoutListener? = null

    fun scheduleScans() {
        val activity = activityRef.get() ?: return
        val root = activity.window?.decorView ?: return
        // Navigation roots can be recreated after drawer/back/configuration changes.
        installed = false
        listOf(0L, 180L, 650L, 1_500L, 3_000L).forEach { delay ->
            root.postDelayed({ scan() }, delay)
        }
        if (listener == null) {
            listener = ViewTreeObserver.OnGlobalLayoutListener {
                val current = activityRef.get() ?: return@OnGlobalLayoutListener
                if (installed && TargetAdapterRegistry.forSpec(spec)?.recheckNavigationBindingOnLayout == true &&
                    !GlassInstaller.hasActiveNavigationBinding(current)) installed = false
                if (!installed && SystemClock.uptimeMillis() - lastScan > 300L) {
                    scan()
                } else if (!installed && !recoveryScanPending &&
                    TargetAdapterRegistry.forSpec(spec)?.recheckNavigationBindingOnLayout == true
                ) {
                    // A final layout inside the throttle window still deserves
                    // one trailing scan; no polling loop when no tab is present.
                    recoveryScanPending = true
                    root.postDelayed({
                        recoveryScanPending = false
                        if (listener != null) scan()
                    }, 310L)
                }
            }.also { root.viewTreeObserver.addOnGlobalLayoutListener(it) }
        }
    }

    private fun scan() {
        if (installed) return
        lastScan = SystemClock.uptimeMillis()
        val activity = activityRef.get() ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        val config = HookConfigReader.read(activity, spec.packageName)
        if (!config.enabled) return

        val root = activity.window?.decorView ?: return
        val candidate = NavigationFinder.find(root, spec)
        // App-specific side surfaces are owned by their adapter, not this scanner.
        TargetAdapterRegistry.forSpec(spec)?.beforeNavigationScan(activity)
        if (candidate == null) return
        installed = GlassInstaller.install(activity, candidate.view, candidate.slotCount, config, spec)
    }

    fun dispose() {
        val root = activityRef.get()?.window?.decorView ?: return
        listener?.let { if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnGlobalLayoutListener(it) }
        listener = null
    }
}
