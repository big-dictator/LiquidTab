package io.github.offlineglass.hook.adapters.netease

import android.view.View
import android.content.Context
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.offlineglass.hook.NavigationCandidate
import io.github.offlineglass.hook.adapters.AdapterNavigationSearch
import io.github.offlineglass.hook.adapters.TargetAdapter
import io.github.offlineglass.hook.adapters.TargetHookSignal
import io.github.offlineglass.targets.TargetSpec

/** CloudMusic 9.5.x navigation, mini-player sub-pages, and VIP banner hooks. */
internal object NeteaseAdapter : TargetAdapter {
    private val viewportHookedClasses = HashSet<Class<*>>()
    override val key = "netease"
    // The mini-player skin and 心动-page state are maintained from the host's
    // pre-draw callback. Keep that callback enabled after moving them here.
    override val hasPerFrameScene = true
    override val allowRenderNodeRefresh = true
    override val suppressPlatformElevation = true
    override val ownsNavigationFinding = true
    override val ownsNavigationDrawing = true
    override val keepNavigationSourceVisible = true

    override fun createNavigationState(context: Context) = NeteaseNavigationState(context)

    override fun findNavigation(root: View, spec: TargetSpec): NavigationCandidate? {
        val navigation = AdapterNavigationSearch.findFirst(root) { view ->
            view.javaClass.name == "com.netease.cloudmusic.theme.ui.NavigationTabLayout" &&
                view.isAttachedToWindow && view.visibility == View.VISIBLE &&
                view.width > 0 && view.height > 0 && view.childCount in 3..6
        }
        if (navigation != null) {
            return NavigationCandidate(navigation, navigation.childCount.coerceIn(2, 7), 1_000)
        }
        val miniPlayerIds = setOf("minPlayerBarContainer", "minPlayerBar", "miniPlayBarReallyRoot")
        val miniPlayer = AdapterNavigationSearch.findFirst(root) { view ->
            view.isAttachedToWindow && view.visibility == View.VISIBLE &&
                view.width > 0 && view.height > 0 &&
                AdapterNavigationSearch.resourceEntryName(view) in miniPlayerIds
        }
        return miniPlayer?.let { NavigationCandidate(it, 4, 900) }
    }

    override fun hookSignals() = listOf(
        TargetHookSignal("com.netease.cloudmusic.theme.ui.NavigationTabLayout", listOf("onAttachedToWindow", "t")),
        TargetHookSignal("com.netease.cloudmusic.activity.MainActivity", listOf("onDrawerOpened", "onDrawerClosed")),
        TargetHookSignal("com.netease.cloudmusic.music.biz.recentplay.ui.activity.MyRecentPlayActivity", listOf("onCreate", "onResume")),
        TargetHookSignal("com.netease.cloudmusic.music.biz.rn.activity.MainProcessRNActivity", listOf("onCreate", "onResume")),
    )

    override fun installHooks(lpparam: XC_LoadPackage.LoadPackageParam, spec: TargetSpec) {
        // These two exact promo views own the image/colour plate above the
        // mini-player. Hiding only their text leaves that plate behind.
        val promoTypes = listOf(
            "com.netease.cloudmusic.module.minibar.widget.VipRenewPopupMinibarGuideView",
            "com.netease.cloudmusic.ui.ad.dslview.common.AdMinibarGuideDSLView",
        ).mapNotNull { name -> runCatching { Class.forName(name, false, lpparam.classLoader) }.getOrNull() }
        runCatching {
            XposedBridge.hookAllMethods(View::class.java, "onAttachedToWindow", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    if (!NeteasePromoVisibilityGuard.owns(view) && promoTypes.none { it.isInstance(view) }) return
                    if (promoTypes.any { it.isInstance(view) }) {
                        NeteasePromoVisibilityGuard.hideAdContainer(view)
                    } else NeteasePromoVisibilityGuard.hide(view)
                }
            })
            XposedBridge.hookAllMethods(View::class.java, "setVisibility", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    if (NeteasePromoVisibilityGuard.owns(view) || promoTypes.any { it.isInstance(view) }) {
                        param.args[0] = View.GONE
                    }
                }
            })
        }
        // Reject renewal slots before they create their image-backed popup.
        for (pluginName in listOf("vh0.d0", "vh0.e")) runCatching {
            val plugin = Class.forName(pluginName, false, lpparam.classLoader)
            plugin.declaredMethods.filter {
                it.returnType == Boolean::class.javaPrimitiveType && it.parameterTypes.size == 1 &&
                    (it.parameterTypes[0].simpleName == "MinibarUiResourceState" ||
                        it.parameterTypes[0] == Any::class.java)
            }.map { it.name }.distinct().forEach { method ->
                XposedBridge.hookAllMethods(plugin, method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) { param.result = false }
                })
            }
        }
        runCatching {
            val guide = Class.forName(
                "com.netease.cloudmusic.module.minibar.widget.VipRenewPopupMinibarGuideView",
                false, lpparam.classLoader,
            )
            guide.declaredMethods.filter {
                it.returnType == Void.TYPE && it.parameterTypes.size == 1 &&
                    it.parameterTypes[0].simpleName == "MinibarVipRenewPopupGuideResource"
            }.map { it.name }.distinct().forEach { method ->
                XposedBridge.hookAllMethods(guide, method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        (param.thisObject as? View)?.let {
                            it.animate().cancel()
                            it.visibility = View.GONE
                            it.background = null
                            it.foreground = null
                        }
                        param.result = null
                    }
                })
            }
        }
        runCatching {
            val container = XposedHelpers.findClass(
                "com.netease.cloudmusic.module.minibar.widget.MiniBarContainer",
                lpparam.classLoader,
            )
            XposedBridge.hookAllMethods(container, "setMinibarVipTextVisibility", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.args[0] = View.GONE
                }
            })
        }
        runCatching {
            val banner = XposedHelpers.findClass(
                "com.netease.cloudmusic.ui.ad.dslview.common.AdMinibarGuideDSLView",
                lpparam.classLoader,
            )
            XposedBridge.hookAllMethods(banner, "setVisibility", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.args[0] = View.GONE
                }
            })
            XposedBridge.hookAllMethods(banner, "onAttachedToWindow", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    (param.thisObject as? View)?.let(NeteasePromoVisibilityGuard::hideAdContainer)
                }
            })
            // GaiaX binds the ad after the wrapper has been created. Its
            // MinibarNewStyleHintRootView can keep drawing a background even
            // when the nested AdMinibarGuideDSLView is GONE.
            XposedBridge.hookAllMethods(banner, "onBindData", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    (param.thisObject as? View)?.let(NeteasePromoVisibilityGuard::hideAdContainer)
                }
            })
        }
    }

    /** Keep the app's content viewport fitted without leaking NetEase classes into the host. */
    override fun suppressNativeBottomChrome(navigation: android.view.ViewGroup) {
        val ownerClass = (navigation.parent as? android.view.ViewGroup)?.javaClass ?: return
        synchronized(viewportHookedClasses) {
            if (!viewportHookedClasses.add(ownerClass)) return
        }
        fun extend(parent: android.view.ViewGroup, remeasure: Boolean) {
            val id = parent.resources.getIdentifier(
                "mainActivityViewPager", "id", "com.netease.cloudmusic",
            )
            if (id == 0) return
            val pager = parent.findViewById<View>(id) ?: return
            val wantedHeight = parent.measuredHeight - pager.top
            val wantedWidth = parent.measuredWidth - pager.left - parent.paddingRight
            if (wantedHeight <= 0 || wantedWidth <= 0) return
            if (remeasure && (pager.measuredHeight != wantedHeight || pager.measuredWidth != wantedWidth)) {
                pager.measure(
                    View.MeasureSpec.makeMeasureSpec(wantedWidth, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(wantedHeight, View.MeasureSpec.EXACTLY),
                )
            }
            if (!remeasure && (pager.height != wantedHeight || pager.width != wantedWidth)) {
                pager.layout(pager.left, pager.top, pager.left + wantedWidth, pager.top + wantedHeight)
            }
            parent.clipChildren = false
            parent.clipToPadding = false
        }
        XposedBridge.hookAllMethods(ownerClass, "onMeasure", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                (param.thisObject as? android.view.ViewGroup)?.let { extend(it, true) }
            }
        })
        XposedBridge.hookAllMethods(ownerClass, "onLayout", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                (param.thisObject as? android.view.ViewGroup)?.let { extend(it, false) }
            }
        })
    }
}

/** Only the identified mini-player promotion container is protected. */
internal object NeteasePromoVisibilityGuard {
    private val owned = java.util.WeakHashMap<View, Boolean>()

    fun own(view: View) { owned[view] = true }

    fun owns(view: View): Boolean = owned[view] == true

    fun hide(view: View) {
        own(view)
        if (view.visibility != View.GONE) view.visibility = View.GONE
        view.background = null
        view.foreground = null
    }

    fun hideAdContainer(adView: View) {
        hide(adView)
        var ancestor = adView.parent as? View
        var wrapper: View? = null
        repeat(8) {
            val current = ancestor ?: return@repeat
            when (current.javaClass.name) {
                "com.netease.cloudmusic.module.minibar.dsl.MinibarNewStyleHintRootView" -> {
                    hide(current)
                    return
                }
                "com.netease.luna.cm.dslwrapper.DSLWrapperViewV2" -> wrapper = current
                "com.netease.cloudmusic.module.minibar.widget.MiniBarContainer" -> {
                    wrapper?.let(::hide)
                    return
                }
            }
            ancestor = current.parent as? View
        }
        wrapper?.let(::hide)
    }
}
