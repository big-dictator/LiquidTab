package io.github.offlineglass.hook.adapters.qqmusic

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.offlineglass.hook.HookConfigReader
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.NavigationCandidate
import io.github.offlineglass.config.GlassConfig
import io.github.offlineglass.hook.adapters.AdapterNavigationSearch
import io.github.offlineglass.hook.adapters.TargetAdapter
import io.github.offlineglass.targets.TargetSpec

/** QQMusic owns navigation discovery, artwork, player layout and native chrome. */
internal object QqMusicAdapter : TargetAdapter {
    override val key = "qqmusic"
    override val ownsNavigationFinding = true
    override val ownsNavigationDrawing = true
    override val skipNavigationSnapshot = true
    override val keepNavigationSourceVisible = true
    override val nativeSelectionReliable = true
    override val preserveNativeSourceMetrics = true
    override val managesNativeTabVisibility = true
    override val hasPerFrameScene = true
    override val suppressPlatformElevation = true
    override val foregroundNavigationOverSelection = true
    override val usesContentDarkMode = true
    override val liveBackdropFramePump = true
    override val requestedFrameRateMax = 120f
    override val sceneVisibilityAnimation = true
    override val refreshOpticsOnBarShow = true
    override fun hiddenBarTranslationPx(hostHeight: Int, density: Float) = maxOf(hostHeight * .12f, 10f * density)
    override fun scrollStopHideTab(selectedIndex: Int) = selectedIndex == 1
    override fun resetScrollStopHideOnOtherTabs() = true
    override fun isBackPeekTab(selectedIndex: Int) = selectedIndex == 1
    override val backPeekDurationMs = 1_500L
    override fun isBackPeekActivity(activity: Activity): Boolean {
        if (activity.javaClass.name != "com.tencent.qqmusic.activity.AppStarterActivity") return false
        val shell = AdapterNavigationSearch.findFirst(activity.window.decorView, QqMusicNativeChrome::isMainShell)
            ?: return false
        return QqMusicNativeChrome.navigation(shell)?.let(QqMusicNativeChrome::navigationPageVisible) == true
    }
    override val allowsSystemBackgroundBlur = false

    // The renderer uses the hardware live scene path; avoid software parent captures.
    override fun normalizeConfig(config: GlassConfig) = config.copy(backdropCapture = true, nativeBlur = false)

    override fun createNavigationState(context: Context, host: GlassHostLayout) = QqMusicNavigationState(host)

    override fun findNavigation(root: View, spec: TargetSpec): NavigationCandidate? {
        val shell = AdapterNavigationSearch.findFirst(root, QqMusicNativeChrome::isMainShell) ?: return null
        QqMusicNativeChrome.clear(shell, HookConfigReader.read(shell.context, spec.packageName))
        val navigation = QqMusicNativeChrome.navigation(shell) ?: return null
        val count = QqMusicNavigationState.cells(navigation).size
        return navigation.takeIf { it.isShown && it.width > 0 && it.height > 0 && count in 2..7 }
            ?.let { NavigationCandidate(it, count, 1_000) }
    }

    override fun estimateSlotCount(group: ViewGroup, spec: TargetSpec) =
        QqMusicNavigationState.cells(group).size.takeIf { it in 2..7 }

    override fun acceptsNavigationSource(navigation: ViewGroup) = QqMusicNativeChrome.isNavigation(navigation)

    override fun suppressNativeBottomChrome(navigation: ViewGroup) {
        val shell = navigation.parent as? ViewGroup ?: return
        if (QqMusicNativeChrome.isMainShell(shell)) {
            QqMusicNativeChrome.clear(shell, HookConfigReader.read(shell.context, targetSpec.packageName))
        }
    }

    override fun immersionWasReset(immersiveStub: Any?): Boolean = immersiveStub != null &&
        runCatching { XposedHelpers.callMethod(immersiveStub, "isForceImmersive") == false }.getOrDefault(false)

    override fun beforeNavigationScan(activity: Activity) {
        val shell = AdapterNavigationSearch.findFirst(activity.window.decorView) {
            QqMusicNativeChrome.isMainShell(it)
        } ?: return
        QqMusicNativeChrome.clear(shell, HookConfigReader.read(activity, targetSpec.packageName))
        QqMusicPlayerGlass.attach(shell)
    }

    override fun installHooks(lpparam: XC_LoadPackage.LoadPackageParam, spec: TargetSpec) {
        QqMusicPromoHooks.install(lpparam.classLoader)
        runCatching {
            val stackClass = XposedHelpers.findClass("com.tencent.qqmusic.activity.base.StackLayout", lpparam.classLoader)
            XposedBridge.hookAllMethods(stackClass, "drawMinibarTransitionLayer",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val stack = param.thisObject as? ViewGroup ?: return
                        if (!QqMusicNativeChrome.ownsTransitionLayer(stack)) return
                        if (!HookConfigReader.read(stack.context, spec.packageName).enabled) return
                        // APK dispatchDraw paints this gradient AFTER the information feed.
                        // The independent glass surfaces already supply both panel backgrounds.
                        param.setResult(null)
                    }
                }).also { check(it.isNotEmpty()) { "no transition background method" } }
        }.onFailure { XposedBridge.log("[OfflineGlass][QQMusic] transition background hook failed: $it") }
        runCatching {
            val stackClass = XposedHelpers.findClass("com.tencent.qqmusic.activity.base.StackLayout", lpparam.classLoader)
            XposedBridge.hookAllMethods(stackClass, "getMinibarContentHeight", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val stack = param.thisObject as? ViewGroup ?: return
                    val page = param.args.getOrNull(0) as? View ?: return
                    val pageName = AdapterNavigationSearch.resourceEntryName(page)
                    // Native shouldMarginForMiniBar is true only for a minibar Activity
                    // and a page tagged lc1=true. Use that contract for every secondary
                    // page, including pages whose root has no resource ID.
                    val minibarPage = param.args.getOrNull(2) == true
                    val mainPage = pageName == "g61" &&
                        AdapterNavigationSearch.resourceEntryName(stack) == "g65"
                    if (!minibarPage && !mainPage) return
                    if (!HookConfigReader.read(stack.context, spec.packageName).enabled) return
                    val height = param.args.getOrNull(1) as? Int ?: return
                    // onLayout already gives the page this full height. Measuring shorter
                    // leaves its children above an empty strip behind the floating player.
                    // Do not depend on isShown/laid-out dimensions during onMeasure.
                    param.setResult(height)
                }
            }).also { check(it.isNotEmpty()) { "no content height method" } }
        }.onFailure { XposedBridge.log("[OfflineGlass][QQMusic] content inset hook failed: $it") }
        runCatching {
            val shellClass = XposedHelpers.findClass(QqMusicNativeChrome.SHELL_CLASS, lpparam.classLoader)
            // APK source confirms dispatchDraw is declared here; onLayout is inherited.
            // Reassert cleanup before pixels are emitted, without a global View hook.
            XposedBridge.hookAllMethods(shellClass, "dispatchDraw", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val shell = param.thisObject as? ViewGroup ?: return
                    if (!QqMusicNativeChrome.isMainShell(shell)) return
                    val config = HookConfigReader.read(shell.context, spec.packageName)
                    if (!config.enabled) return
                    QqMusicNativeChrome.clear(shell, config)
                    QqMusicPlayerGlass.attach(shell)
                }
            }).also { hooks ->
                if (hooks.isEmpty()) XposedBridge.log("[OfflineGlass][QQMusic] no shell dispatchDraw hook; scanner cleanup only")
            }
        }.onFailure { XposedBridge.log("[OfflineGlass][QQMusic] cleanup hook failed: $it") }
    }
}
