package io.github.offlineglass.hook.adapters.mi_market

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.offlineglass.config.ConfigContract
import io.github.offlineglass.config.GlassConfig
import io.github.offlineglass.hook.HookCoordinator
import io.github.offlineglass.hook.adapters.TargetAdapter
import io.github.offlineglass.hook.adapters.AppNavigationState
import io.github.offlineglass.targets.TargetSpec

/** Xiaomi Market's native-tab binding and settings synchronization. */
internal object MiMarketAdapter : TargetAdapter {
    override val key = "mi_market"
    override val ownsNavigationDrawing = true
    override val ownsNavigationTap = true
    override val hideRedrawnNavigationSource = true
    override val usesContentDarkMode = true
    override fun createNavigationState(context: android.content.Context): AppNavigationState =
        MiMarketNavigationState(context)
    override val managesNativeTabVisibility = true
    override fun normalizeConfig(config: GlassConfig): GlassConfig =
        config.copy(hiddenMask = 0, iconOnly = false)
    @Volatile private var syncingMiMarketNativePrefs = false
    @Volatile private var miMarketContext: android.content.Context? = null
    private val miMarketManagedPreferenceKeys = setOf("pref_key_short_play", "pref_key_mini_game")

    // The real tab container's children may not yet be clickable on first layout.
    override fun estimateSlotCount(group: ViewGroup, spec: TargetSpec): Int? =
        if (resourceEntryName(group) == "tab_container") spec.preferredSlots.first.coerceIn(2, 7) else null

    override fun adjustNavigationScore(view: ViewGroup, idName: String?, score: Int): Int =
        if (idName == "tab_container") score + 180 else score

    override fun resolveNestedNavigation(navigation: ViewGroup): Pair<ViewGroup, Int>? {
        if (resourceEntryName(navigation) == "tab_container") return null
        val stack = ArrayDeque<Pair<View, Int>>()
        stack += navigation to 0
        while (stack.isNotEmpty()) {
            val (view, depth) = stack.removeLast()
            if (view is ViewGroup && resourceEntryName(view) == "tab_container") return view to 4
            if (view is ViewGroup && depth < 5) {
                for (index in 0 until view.childCount) {
                    stack += view.getChildAt(index) to (depth + 1)
                }
            }
        }
        return null
    }

    override fun installHooks(lpparam: XC_LoadPackage.LoadPackageParam, spec: TargetSpec) {
        installMiMarketNavigationHooks(lpparam, spec)
    }

    private fun resourceEntryName(view: View): String? = runCatching {
        if (view.id == View.NO_ID) null else view.resources.getResourceEntryName(view.id)
    }.getOrNull()

    override fun suppressNativeBottomChrome(navigation: ViewGroup) {
        navigation.background = ColorDrawable(Color.TRANSPARENT)
        navigation.backgroundTintList = null
        var shell: ViewGroup? = navigation
        repeat(4) {
            val current = shell ?: return@repeat
            if (resourceEntryName(current) == "tab_container_layout") return@repeat
            shell = current.parent as? ViewGroup
        }
        val root = shell?.takeIf { resourceEntryName(it) == "tab_container_layout" }
            ?: (navigation.parent as? ViewGroup)
            ?: return
        root.background = ColorDrawable(Color.TRANSPARENT)
        root.backgroundTintList = null
        root.clipChildren = false
        root.clipToPadding = false

        var scanRoot = root
        repeat(3) {
            scanRoot = (scanRoot.parent as? ViewGroup) ?: scanRoot
        }
        val chromeIds = setOf(
            "tab_blur_bg",
            "tab_shadow_bg",
            "dark_navigation_bar_spacing",
        )
        extendFeedIntoGestureArea(root)
        val stack = ArrayDeque<Pair<View, Int>>()
        stack += scanRoot to 0
        while (stack.isNotEmpty()) {
            val (view, depth) = stack.removeLast()
            if (resourceEntryName(view) in chromeIds) {
                view.background = ColorDrawable(Color.TRANSPARENT)
                view.backgroundTintList = null
                view.visibility = View.INVISIBLE
            }
            if (view is ViewGroup && depth < 9) {
                for (index in 0 until view.childCount) {
                    stack += view.getChildAt(index) to (depth + 1)
                }
            }
        }
        root.invalidate()
    }

    /** Remove only Market's reserved gesture strip; keep its tab controller intact. */
    private fun extendFeedIntoGestureArea(tabShell: ViewGroup) {
        val fragmentRoot = (tabShell.parent as? ViewGroup)
            ?.takeIf { resourceEntryName(it) == "fragment_root_view" } ?: return
        val outer = (fragmentRoot.parent as? ViewGroup)
            ?.takeIf { resourceEntryName(it) == "root_view" } ?: return

        val rootParams = fragmentRoot.layoutParams as? ViewGroup.MarginLayoutParams
        if (rootParams != null &&
            (rootParams.height != ViewGroup.LayoutParams.MATCH_PARENT || rootParams.bottomMargin != 0)
        ) {
            rootParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            rootParams.bottomMargin = 0
            fragmentRoot.layoutParams = rootParams
        }
        if (outer.paddingBottom != 0) {
            outer.setPadding(outer.paddingLeft, outer.paddingTop, outer.paddingRight, 0)
        }

        // Market reasserts this sibling's height during layout. INVISIBLE
        // hides its pixels but still shortens fragment_root_view by its height.
        for (index in 0 until outer.childCount) {
            val spacer = outer.getChildAt(index)
            if (resourceEntryName(spacer) != "navigation_bar_placeholder") continue
            if (spacer.visibility != View.GONE) spacer.visibility = View.GONE
            val params = spacer.layoutParams ?: continue
            if (params is ViewGroup.MarginLayoutParams) {
                if (params.height != 0 || params.topMargin != 0 || params.bottomMargin != 0) {
                    params.height = 0
                    params.topMargin = 0
                    params.bottomMargin = 0
                    spacer.layoutParams = params
                }
            } else if (params.height != 0) {
                params.height = 0
                spacer.layoutParams = params
            }
        }
    }

    override fun suppressNativeBottomChromeOnRebind(navigation: ViewGroup) =
        suppressNativeBottomChrome(navigation)

    private fun installMiMarketNavigationHooks(
        lpparam: XC_LoadPackage.LoadPackageParam,
        spec: TargetSpec,
    ) {
        installMiMarketPreferenceSyncHooks(lpparam)
        val wrapperClass = runCatching {
            XposedHelpers.findClass(
                "com.xiaomi.market.ui.DoubleTabProxyActivityWrapper",
                lpparam.classLoader,
            )
        }.getOrNull() ?: return

        val dispatchBottomTab: (Any?) -> Unit = { wrapper ->
            if (wrapper != null) {
                val bottomTab = runCatching {
                    readInstanceField(wrapper, "bottomTabLayout") as? View
                }.getOrNull()
                if (bottomTab != null) {
                    miMarketContext = bottomTab.context
                    syncMiMarketNativePreferences(lpparam.classLoader, bottomTab.context)
                    HookCoordinator.onNavigationView(bottomTab, spec)
                } else {
                    val activity = runCatching {
                        readInstanceField(wrapper, "mActivity") as? Activity
                    }.getOrNull()
                    if (activity != null) {
                        miMarketContext = activity
                        syncMiMarketNativePreferences(lpparam.classLoader, activity)
                        HookCoordinator.onActivityResumed(activity, spec)
                    }
                }
            }
        }

        // These are the exact writers that construct or restore Xiaomi
        // Market's native bottom chrome. Hooking after them provides the real
        // BottomTabLayout directly and reapplies suppression after blur/theme
        // refreshes, without touching any other target application.
        listOf("setTabContainer", "onBottomTabBlurSwitch", "onResume").forEach { method ->
            runCatching {
                XposedBridge.hookAllMethods(wrapperClass, method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        dispatchBottomTab(param.thisObject)
                    }
                })
            }
        }
    }

    private fun installMiMarketPreferenceSyncHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        val prefUtilsClass = runCatching {
            XposedHelpers.findClass("com.xiaomi.market.util.PrefUtils", lpparam.classLoader)
        }.getOrNull() ?: run {
            XposedBridge.log("[OfflineGlass][MiMarket] PrefUtils not found; tab setting sync skipped")
            return
        }
        runCatching {
            XposedBridge.hookAllMethods(Activity::class.java, "onCreate", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    miMarketContext = activity
                    syncMiMarketNativePreferences(lpparam.classLoader, activity)
                }
            })
            XposedBridge.hookAllMethods(Activity::class.java, "onResume", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    miMarketContext = activity
                    syncMiMarketNativePreferences(lpparam.classLoader, activity)
                }
            })
            XposedBridge.hookAllMethods(prefUtilsClass, "getBoolean", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val key = param.args.firstOrNull() as? String ?: return
                    if (key !in miMarketManagedPreferenceKeys) return
                    readMiMarketTabEnabledFromModule(key)?.let { param.result = it }
                }
            })
            XposedBridge.hookAllMethods(prefUtilsClass, "setBoolean", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (syncingMiMarketNativePrefs) return
                    val key = param.args.firstOrNull() as? String ?: return
                    if (key !in miMarketManagedPreferenceKeys) return
                    val enabled = param.args.getOrNull(1) as? Boolean ?: return
                    writeMiMarketTabEnabledToModule(key, enabled)
                }
            })
            XposedBridge.log("[OfflineGlass][MiMarket] tab setting sync hooks installed")
        }.onFailure {
            XposedBridge.log("[OfflineGlass][MiMarket] tab setting sync hook failed: $it")
        }
    }

    private fun syncMiMarketNativePreferences(classLoader: ClassLoader, context: android.content.Context) {
        if (syncingMiMarketNativePrefs) return
        syncingMiMarketNativePrefs = true
        try {
            val prefUtilsClass = runCatching {
                XposedHelpers.findClass("com.xiaomi.market.util.PrefUtils", classLoader)
            }.getOrNull() ?: return
            val prefFileClass = runCatching {
                XposedHelpers.findClass("com.xiaomi.market.util.PrefUtils\$PrefFile", classLoader)
            }.getOrNull() ?: return
            val emptyPrefFiles = java.lang.reflect.Array.newInstance(prefFileClass, 0)
            miMarketManagedPreferenceKeys.forEach { key ->
                val enabled = readMiMarketTabEnabledFromModule(context, key) ?: return@forEach
                runCatching {
                    prefUtilsClass.methods.firstOrNull { method ->
                        method.name == "setBoolean" && method.parameterTypes.size == 3
                    }?.invoke(null, key, enabled, emptyPrefFiles)
                }
            }
        } finally {
            syncingMiMarketNativePrefs = false
        }
    }

    private fun readMiMarketTabEnabledFromModule(key: String): Boolean? =
        readMiMarketTabEnabledFromModule(miMarketContext ?: return null, key)

    private fun readMiMarketTabEnabledFromModule(context: android.content.Context, key: String): Boolean? {
        return runCatching {
            context.contentResolver.call(
                ConfigContract.URI,
                ConfigContract.METHOD_MI_MARKET_TAB_GET,
                key,
                null,
            )?.getBoolean(ConfigContract.KEY_MI_MARKET_TAB_ENABLED)
        }.getOrNull()
    }

    private fun writeMiMarketTabEnabledToModule(key: String, enabled: Boolean) {
        val context = miMarketContext ?: return
        runCatching {
            val extras = android.os.Bundle().apply {
                putBoolean(ConfigContract.KEY_MI_MARKET_TAB_ENABLED, enabled)
            }
            context.contentResolver.call(
                ConfigContract.URI,
                ConfigContract.METHOD_MI_MARKET_TAB_SET,
                key,
                extras,
            )
        }
    }

    private fun readInstanceField(instance: Any, name: String): Any? {
        var type: Class<*>? = instance.javaClass
        while (type != null) {
            val field = runCatching { type.getDeclaredField(name) }.getOrNull()
            if (field != null) {
                field.isAccessible = true
                return field.get(instance)
            }
            type = type.superclass
        }
        return null
    }

}
