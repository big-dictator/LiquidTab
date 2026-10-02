package io.github.offlineglass.hook.adapters.tieba

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.offlineglass.hook.adapters.TargetAdapter
import io.github.offlineglass.hook.adapters.AppNavigationState
import android.content.Context
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.targets.TargetSpec

/** Tieba-only native chrome and feed prompt hooks. */
internal object TiebaAdapter : TargetAdapter {
    override val key = "tieba"
    override val sourceTextSizeSp = 10f
    override val projectedNavigationScale = 1.10f
    override fun createNavigationState(context: Context, host: GlassHostLayout): AppNavigationState =
        TiebaNavigationState(host)
    private const val TIEBA_PACKAGE = "com.baidu.tieba"
    private const val SIDEBAR_ACTIVITY = "com.baidu.tieba.sidebar.SideBarAssistActivity"
    @Volatile private var sidebarVisible = false

    override fun isBlockingOverlayActivity(activity: Activity) =
        activity.packageName == TIEBA_PACKAGE && activity.javaClass.name == SIDEBAR_ACTIVITY

    override fun setBlockingOverlayVisible(visible: Boolean) { sidebarVisible = visible }
    override fun isBlockingOverlayVisible() = sidebarVisible

    override fun prepareContentForInstall(content: ViewGroup) {
        if (android.os.Build.VERSION.SDK_INT < 21) return
        content.setOnApplyWindowInsetsListener { _, insets ->
            val bottom = insets.systemWindowInsetBottom
            if (bottom in 1..200) {
                insets.replaceSystemWindowInsets(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    0,
                )
            } else {
                insets
            }
        }
    }
    override fun installHooks(lpparam: XC_LoadPackage.LoadPackageParam, spec: TargetSpec) {
        installTiebaNavigationBarBgSuppression(lpparam)
        installTiebaFeedPromptSuppression(lpparam)
    }

    /** Prevent Tieba from restoring its native tab-row background. */
    private fun installTiebaNavigationBarBgSuppression(
        lpparam: XC_LoadPackage.LoadPackageParam,
    ) {
        val candidates = listOf(
            "com.baidu.tbadk.core.tabHost.FragmentTabWidget",
            "com.baidu.tieba.tbui.widget.FragmentTabWidget",
            "com.baidu.tieba.widget.FragmentTabWidget",
        )
        var hooked: Class<*>? = null
        for (name in candidates) {
            hooked = runCatching {
                XposedHelpers.findClass(name, lpparam.classLoader)
            }.getOrNull()
            if (hooked != null) break
        }
        val widgetClass = hooked ?: return
        runCatching {
            XposedBridge.hookAllMethods(
                widgetClass,
                "setNavigationBarBg",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = null
                    }
                },
            )
            XposedBridge.log("[OfflineGlass][Tieba] setNavigationBarBg suppressed on ${widgetClass.name}")
        }
    }

    /** Route the recommendation through Tieba's own hide branch, which hides its whole container. */
    private fun installTiebaFeedPromptSuppression(lpparam: XC_LoadPackage.LoadPackageParam) {
        val controller = runCatching {
            XposedHelpers.findClass(
                "com.baidu.tieba.homepage.personalize.controller.FeedPromptBarController",
                lpparam.classLoader,
            )
        }.getOrNull() ?: return
        runCatching {
            XposedBridge.hookAllMethods(
                controller,
                "k",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (param.args.size == 1 && param.args[0] == true) param.args[0] = false
                    }
                },
            )
            XposedBridge.log("[OfflineGlass][Tieba] FeedPromptBarController visibility hook installed")
        }.onFailure {
            XposedBridge.log("[OfflineGlass][Tieba] FeedPromptBarController hook failed: $it")
        }
    }

}
