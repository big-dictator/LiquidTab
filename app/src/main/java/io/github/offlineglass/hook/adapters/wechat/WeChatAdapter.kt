package io.github.offlineglass.hook.adapters.wechat

import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.offlineglass.hook.HookCoordinator
import io.github.offlineglass.hook.adapters.TargetAdapter
import io.github.offlineglass.targets.TargetSpec

/** WeChat LauncherUI native frost only; chat surfaces remain native. */
internal object WeChatAdapter : TargetAdapter {
    override val key = "wechat"
    override val allowRenderNodeRefresh = true
    override val ownsHostNativeChrome = true
    override val hostLiftDp = 5f
    override val hasPerFrameScene = true
    override val navigationImmersionOverride = false
    override val suppressPlatformElevation = true
    override val contentAncestorMaxDepth = 2
    override val sourceParentVisibilityOnly = true
    override val sourceSwapGraceMs = 1_200L
    override val panelShadowBlurDp = 4f
    override val synchronousConfigPolling = true
    override val nativeNavigationDrawsInSourceSpace = true
    override val indicatorInnerShadow = true
    override val themeProbeIntervalMs = 80L
    override val usesContentDarkMode = true
    override val preserveNativeParentBackground = true
    override val preserveNativeSourceMetrics = true
    override val logNavigationHierarchy = true
    override val clearSourceSelectionSurfaces = true
    override fun hostBottomMarginPx(view: View, defaultGap: Int): Int {
        if (view.height <= 0) return defaultGap
        val root = view.rootView ?: return defaultGap
        if (root.height <= 0) return defaultGap
        val parentLocation = IntArray(2).also(view::getLocationInWindow)
        val rootLocation = IntArray(2).also(root::getLocationInWindow)
        val parentBottom = parentLocation[1] + view.height
        val rootBottom = rootLocation[1] + root.height
        return defaultGap - (rootBottom - parentBottom).coerceAtLeast(0)
    }
    private const val bottomTabClass = "com.tencent.mm.ui.LauncherUIBottomTabView"
    override fun acceptsNavigationSource(navigation: ViewGroup): Boolean =
        navigation.javaClass.name == bottomTabClass

    override fun resolveNestedNavigation(navigation: ViewGroup): Pair<ViewGroup, Int>? {
        var owner = navigation.parent as? View
        repeat(10) {
            val candidate = owner as? ViewGroup ?: return@repeat
            if (candidate.javaClass.name == bottomTabClass) return candidate to 4
            owner = candidate.parent as? View
        }
        return null
    }
    override fun createNavigationState(context: android.content.Context): io.github.offlineglass.hook.adapters.AppNavigationState =
        WeChatNavigationState()
    override val hasTransientPageVisibility = true
    override fun navigationSurfaceColor(activity: android.app.Activity?, source: View?, dark: Boolean): Int =
        WeChatSurfaceColor.resolve(activity, source, dark)

    override fun installHooks(lpparam: XC_LoadPackage.LoadPackageParam, spec: TargetSpec) {
        installWeChatNativeFrostSuppression(lpparam, spec)
    }

    // Suppress only LauncherUI's native tab frost. ChatFooter remains untouched.
    private fun installWeChatNativeFrostSuppression(
        lpparam: XC_LoadPackage.LoadPackageParam,
        spec: TargetSpec,
    ) {
        val frostedClass = runCatching {
            XposedHelpers.findClass("com.tencent.mm.ui.FrostedContentView", lpparam.classLoader)
        }.getOrNull() ?: return
        val clearBottomArea: (Any) -> Unit = clear@ { view ->
            // FrostedContentView is also used by WeChat's chatting/detail
            // surfaces.  Suppressing every instance globally removes the
            // native backdrop behind the gesture-navigation area and leaves a
            // visibly different strip below the input panel.  The unwanted
            // native tab frost only belongs to pages hosted by LauncherUI's
            // main CustomViewPager, so leave secondary pages completely native.
            if (!isWeChatLauncherPageFrost(view)) return@clear
            // Set field 'h' (the color used by drawColor in dispatchDraw) to 0 (transparent).
            // Also set field 'm' (bottomBlurAreaHeight) to 0 to skip blur rendering.
            // And field 'i' (topBlurAreaHeight) to 0 as well.
            runCatching {
                val cls = view.javaClass
                // h = drawColor color
                val hField = cls.getDeclaredField("h")
                hField.isAccessible = true
                if (hField.getInt(view) != 0) hField.setInt(view, 0)
                // m = bottomBlurAreaHeight
                val mField = cls.getDeclaredField("m")
                mField.isAccessible = true
                if (mField.getInt(view) != 0) mField.setInt(view, 0)
                // i = topBlurAreaHeight
                val iField = cls.getDeclaredField("i")
                iField.isAccessible = true
                if (iField.getInt(view) != 0) iField.setInt(view, 0)
            }
            val enabled = runCatching {
                XposedHelpers.callMethod(view, "getFrostedEnabled") as? Boolean
            }.getOrNull()
            if (enabled == true) {
                runCatching { XposedHelpers.callMethod(view, "setFrostedEnabled", false) }
            }
        }
        // LauncherUI can restore its 171 px native blur strip after the module's
        // normal pre-draw pass. Clear it both at the writer and immediately
        // before FrostedContentView records its RenderNode.
        // Hook FrostedContentView.a(ZIF) — this is called from
        // LauncherUIBottomTabView.onLayout() with (frostedEnabled, height, translationY).
        // It calculates bottomBlurAreaHeight = height - translationY and stores
        // it in field 'm'. When translationY=0, m=height, causing a full-height
        // gray overlay in dispatchDraw. By replacing the translationY argument
        // with a very large value, m becomes <= 0, so dispatchDraw skips the
        // bottom blur rendering entirely.
        XposedBridge.hookAllMethods(frostedClass, "a", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (isWeChatLauncherPageFrost(param.thisObject) && param.args.size >= 3) {
                    param.args[2] = 100000.0f
                }
            }
            override fun afterHookedMethod(param: MethodHookParam) {
                clearBottomArea(param.thisObject)
            }
        })
        XposedBridge.hookAllMethods(frostedClass, "dispatchDraw", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                clearBottomArea(param.thisObject)
            }
        })
        XposedBridge.log("[OfflineGlass][WeChatFrostHook] installed")

        // Hook LauncherUIBottomTabView.onLayout to clear the gray ColorDrawable
        // background that WeChat sets on the child LinearLayout after each layout.
        // This is the same mechanism triggered by a theme switch.
        val tabViewClass = runCatching {
            XposedHelpers.findClass(
                "com.tencent.mm.ui.LauncherUIBottomTabView",
                lpparam.classLoader,
            )
        }.getOrNull()
        if (tabViewClass != null) {
            XposedBridge.hookAllMethods(tabViewClass, "onLayout", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? ViewGroup ?: return
                    // After each layout, WeChat restores alpha=1.0 and gray
                    // ColorDrawable backgrounds on the tab bar's children.
                    // Recursively clear them here — this runs after onLayout
                    // completes, before the next draw pass.
                    if (view.alpha != 0f) view.alpha = 0f
                    val stack = ArrayDeque<Pair<View, Int>>()
                    stack += view to 0
                    while (stack.isNotEmpty()) {
                        val (v, d) = stack.removeLast()
                        if (v.alpha != 0f) v.alpha = 0f
                        if (v.background != null) v.background = null
                        if (v.backgroundTintList != null) v.backgroundTintList = null
                        if (v is ViewGroup && d < 5) {
                            for (i in 0 until v.childCount) {
                                stack += v.getChildAt(i) to (d + 1)
                            }
                        }
                    }

                    // LauncherUI replaces this view in-place after returning
                    // from chat/background. ScannerSession has already marked
                    // the old source installed, so a global-layout scan alone
                    // cannot repair the binding. Dispatch the exact replacement
                    // owner; GlassInstaller keeps the host and atomically rebinds
                    // it once this layout is complete.
                    HookCoordinator.onNavigationView(view, spec)

                }
            })
            XposedBridge.log("[OfflineGlass][WeChatTabView] onLayout hook installed")
        }
    }

    private fun isWeChatLauncherPageFrost(instance: Any?): Boolean {
        var current = instance as? View ?: return false
        repeat(20) {
            if (current.javaClass.name == "com.tencent.mm.ui.base.CustomViewPager") return true
            current = current.parent as? View ?: return false
        }
        return false
    }

}
