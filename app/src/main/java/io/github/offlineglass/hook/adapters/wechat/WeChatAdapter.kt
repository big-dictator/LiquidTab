package io.github.offlineglass.hook.adapters.wechat

import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.compat.XposedHelpers
import io.github.offlineglass.hook.compat.callbacks.XC_LoadPackage
import io.github.offlineglass.hook.HookCoordinator
import io.github.offlineglass.hook.adapters.TargetAdapter
import io.github.offlineglass.targets.TargetSpec

/** WeChat LauncherUI native frost only; chat surfaces remain native. */
internal object WeChatAdapter : TargetAdapter {
    override val key = "wechat"
    override val supportsNativeEnglishLocale = true
    override val allowRenderNodeRefresh = true
    override val skipBackdropStableDelay = true
    override val ownsHostNativeChrome = true
    // A non-null zero keeps parent/window inset compensation active without
    // adding any app-specific gap to the shared four-tab geometry.
    override val hostLiftDp = 0f
    override val hasPerFrameScene = true
    override val navigationImmersionOverride = true
    override fun configureNavigationInsets(decor: View, enabled: Boolean) =
        WeChatChatInsets.configure(decor, enabled)
    override val contentAncestorMaxDepth = 2
    override val sourceParentVisibilityOnly = true
    override val sourceSwapGraceMs = 1_200L
    override val synchronousConfigPolling = true
    override val nativeNavigationDrawsInSourceSpace = true
    override val foregroundNavigationOverSelection = true
    // Inset tap geometry is not compatible with this artwork pipeline yet.
    override val foregroundUsesRenderedSelectionOutline = true
    override val foregroundMaskInsetDp = 0f
    override val usesNavigationOpticalMix = true
    override val persistentNavigationOpticalMix = true
    // The host already draws an ambient glass shadow; avoid stacking the
    // platform elevation shadow beneath it.
    override val suppressPlatformElevation = true
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
    override val ownsNavigationFinding = true
    override fun findNavigation(root: View, spec: TargetSpec): io.github.offlineglass.hook.NavigationCandidate? {
        WeChatChatToolbarGradient.bindWindow(root)
        val queue = ArrayDeque<View>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 4000) {
            val view = queue.removeFirst()
            if (view is ViewGroup) {
                if (view.javaClass.name == bottomTabClass && view.isShown && view.width > 0 && view.height > 0)
                    return io.github.offlineglass.hook.NavigationCandidate(view, 4, 200)
                for (i in 0 until view.childCount) queue.add(view.getChildAt(i))
            }
        }
        return null
    }
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
        // Auxiliary native repairs must never prevent the navigation host from loading.
        listOf<Pair<String, () -> Unit>>(
            "chat insets" to { WeChatChatInsets.install(lpparam.classLoader) },
            "chat toolbar gradient" to { WeChatChatToolbarGradient.install(lpparam.classLoader) },
            "moments top gradient" to { WeChatMomentsTopGradient.install(lpparam.classLoader) },
            "header divider" to { WeChatHeaderDivider.install(lpparam.classLoader) },
            "status masks" to { WeChatStatusMaskBinding.install(lpparam.classLoader) },
            "main page top content" to { WeChatMainPageTopContent.install(lpparam.classLoader) },
            "contacts layout" to { WeChatContactsLayoutRepair.install(lpparam.classLoader) },
            "launcher pager transition" to { WeChatLauncherPages.installPagerTransition(lpparam.classLoader) },
            "fold banner" to { WeChatFoldPresentation.install(lpparam.classLoader) },
            "native frost" to { installWeChatNativeFrostSuppression(lpparam, spec) },
        ).forEach { (name, install) ->
            runCatching(install).onFailure {
                android.util.Log.e("LiquidTabWeChatTop", "WeChat $name contract installation failed", it)
                XposedBridge.log("[OfflineGlass][WeChat] optional $name hook failed: $it")
            }
        }
    }

    private val frostProperties = listOf(
        Triple("getFrostedEnabled", "setFrostedEnabled", false),
        Triple("getFrostedColor", "setFrostedColor", 0),
        Triple("getBottomBlurAreaHeight", "setBottomBlurAreaHeight", 0),
        Triple("getTopBlurAreaHeight", "setTopBlurAreaHeight", 0),
    )

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
            var changed = false
            for ((getter, setter, value) in frostProperties) {
                runCatching {
                    if (XposedHelpers.callMethod(view, getter) != value) {
                        XposedHelpers.callMethod(view, setter, value)
                        changed = true
                    }
                }
            }
            if (changed) (view as View).invalidate()
        }
        for ((_, setter, value) in frostProperties) {
            XposedBridge.hookAllMethods(frostedClass, setter, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (isWeChatLauncherPageFrost(param.thisObject) && param.args.isNotEmpty()) {
                        param.args[0] = value
                    }
                }
            })
        }
        // LauncherUI can restore its 171 px native blur strip after the module's
        // normal pre-draw pass. Clear it both at the writer and immediately
        // before FrostedContentView records its RenderNode.
        // Preserve WeChat's native a(ZIF) geometry and color update exactly as
        // decompiled; clear only its resulting native frost after it returns.
        // Mutating translationY here corrupts WeChat's computed clip and can
        // leave a stale RenderNode until a theme-triggered relayout.
        XposedBridge.hookAllMethods(frostedClass, "dispatchDraw", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                clearBottomArea(param.thisObject)
            }
        })
        XposedBridge.log("[OfflineGlass][WeChatFrostHook] installed")
        android.util.Log.i("LiquidTabInject", "WECHAT-FROST-HOOKS-INSTALLED class=${frostedClass.name}")

        // LauncherUIBottomTabView.onLayout forwards native-tab geometry to each
        // direct page child. If Contacts is added after that callback, its
        // FrostedContentView keeps the old bottomBlurAreaHeight until the next
        // theme/configuration relayout. dispatchDraw then paints frostedColor
        // into that stale bottom clip. Repair the page immediately after the
        // native callback using the verified public setter.
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

    private fun isWeChatLauncherPageFrost(instance: Any?): Boolean =
        WeChatLauncherPages.owns(instance)




}


