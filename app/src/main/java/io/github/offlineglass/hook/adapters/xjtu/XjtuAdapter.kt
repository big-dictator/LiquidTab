package io.github.offlineglass.hook.adapters.xjtu

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.widget.ImageView
import android.widget.TextView
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.offlineglass.hook.NavigationCandidate
import io.github.offlineglass.hook.adapters.AdapterNavigationSearch
import io.github.offlineglass.hook.adapters.AppNavigationState
import io.github.offlineglass.hook.adapters.TargetAdapter
import io.github.offlineglass.targets.TargetSpec

/** DCloud's native five-tab campus row. Never fall back to arbitrary WebView controls. */
internal object XjtuAdapter : TargetAdapter {
    private val originalRowAlpha = java.util.WeakHashMap<ViewGroup, Float>()
    override val key = "xjtu"
    override val ownsNavigationFinding = true
    override val nativeSelectionReliable = true
    // UI pre-draw builds the cached artwork consumed by the compositor.
    override val hasPerFrameScene = true
    override val forceLightMode = true
    override fun isBackPeekTab(selectedIndex: Int) = selectedIndex == 1
    override val backPeekDurationMs = 2_000L
    override fun isBackPeekActivity(activity: android.app.Activity) =
        activity.javaClass.name in (0..4).map { "io.dcloud.feature.sdk.multi.DCUniMPTopActivity$it" }
    override fun hiddenBarTranslationPx(hostHeight: Int, density: Float) =
        maxOf(hostHeight * .12f, 10f * density)
    // DCloud pages are Chromium WebViews. Replay/canvas drawing can disturb their
    // display lists; sample compositor pixels through the shared isolated Surface instead.
    override val allowsSystemBackgroundBlur = false
    override val usesOpticalSurfacePipeline = true
    override val keepsOpticalSurfaceDuringBarAnimation = true
    override val deferOpticalRenderDuringBarAnimation = true
    override val reuseSurfaceBlurDuringBarAnimation = true
    override val requestedFrameRateMax = 120f
    override val observeWindowTouches = true
    override fun onWindowTouch(host: View, event: MotionEvent) {
        (host as? io.github.offlineglass.hook.GlassHostLayout)
            ?.appNavigationState?.let { (it as? XjtuNavigationState)?.onWindowTouch(event) }
    }
    override fun isOpticalSurfaceTab(selectedIndex: Int) = selectedIndex in 0..4
    override val nativeNavigationDrawsInSourceSpace = true
    override val skipNavigationSnapshot = true
    override val preserveNativeSourceMetrics = true
    override val stagedNavigationSnapshot = true
    override val trackSourceDrawableIdentity = true
    override val navigationImmersionOverride = true
    override fun immersionWasReset(immersiveStub: Any?): Boolean = immersiveStub != null &&
        runCatching { XposedHelpers.callMethod(immersiveStub, "isForceImmersive") as? Boolean }
            .getOrNull() == false
    override fun configureNavigationInsets(decor: View, enabled: Boolean) =
        XjtuInsets.configure(decor, enabled)
    override fun installHooks(lpparam: XC_LoadPackage.LoadPackageParam, spec: TargetSpec) {
        XjtuInsets.install()
        XjtuContent.install(lpparam.classLoader)
    }

    override fun findNavigation(root: View, spec: TargetSpec): NavigationCandidate? {
        val bar = AdapterNavigationSearch.findFirst(root) {
            it.javaClass.name == TAB_BAR_CLASS && it.isShown && it.width > 0 && it.height > 0 &&
                tabItems(it) != null
        } ?: return null
        // rv fills the whole window. Only its b row is navigation artwork/geometry.
        val row = tabRow(bar) ?: return null
        if (!row.isShown || row.width <= 0 || row.height <= 0) return null
        return NavigationCandidate(row, TAB_COUNT, 120)
    }

    override fun acceptsNavigationSource(navigation: ViewGroup) =
        nativeBar(navigation) != null && tabItems(navigation) != null

    override fun estimateSlotCount(group: ViewGroup, spec: TargetSpec): Int? =
        if (acceptsNavigationSource(group)) TAB_COUNT else null

    override fun dispatchNavigationTap(source: ViewGroup?, index: Int, slotCount: Int): Boolean {
        if (slotCount != TAB_COUNT || index !in 0 until TAB_COUNT) return false
        // performClick invokes rv.H: updates selection AND tells TabBarWebview to switch pages.
        // Calling rv.b(index) directly would only update the artwork.
        return source?.let(::tabItems)?.getOrNull(index)?.performClick() == true
    }

    override fun createNavigationState(context: Context): AppNavigationState = XjtuNavigationState()

    override fun suppressNativeBottomChrome(navigation: ViewGroup) {
        if (!acceptsNavigationSource(navigation)) return
        val bar = nativeBar(navigation) ?: return
        // The glass host draws a cached copy of the original row. Hide only
        // the native row itself so any DCloud surface fill cannot cover content.
        originalRowAlpha.putIfAbsent(navigation, navigation.alpha)
        navigation.alpha = 0f
        // Clear only native chrome. Never traverse page content or a glass host.
        clearChrome(bar)
        clearContainers(navigation)
        for (index in 0 until bar.childCount) {
            val child = bar.getChildAt(index)
            if (child !is ViewGroup && child !is ImageView && child !is TextView) clearChrome(child)
        }
    }

    internal fun restoreNativeRow(bar: ViewGroup?) {
        val row = bar?.let(::tabRow) ?: return
        originalRowAlpha.remove(row)?.let { row.alpha = it }
    }

    private fun clearContainers(view: View) {
        // Badge TextViews keep their own drawable; ImageViews keep icons and red dots.
        if (view is ImageView || view is TextView) return
        clearChrome(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) clearContainers(view.getChildAt(index))
        }
    }

    private fun clearChrome(view: View) {
        if (view.background != null) view.background = null
        if (view.backgroundTintList != null) view.backgroundTintList = null
        if (view.foreground != null) view.foreground = null
        if (view.elevation != 0f) view.elevation = 0f
        if (view.translationZ != 0f) view.translationZ = 0f
    }

    internal fun nativeBar(source: ViewGroup): ViewGroup? = when {
        source.javaClass.name == TAB_BAR_CLASS -> source
        (source.parent as? ViewGroup)?.javaClass?.name == TAB_BAR_CLASS -> source.parent as ViewGroup
        else -> null
    }

    private fun tabRow(bar: ViewGroup): ViewGroup? {
        if (bar.javaClass.name != TAB_BAR_CLASS) return null
        return runCatching { bar.javaClass.getField("b").get(bar) as? ViewGroup }.getOrNull()
    }

    internal fun tabItems(source: ViewGroup): List<View>? {
        val bar = nativeBar(source) ?: return null
        val row = (0 until bar.childCount).map(bar::getChildAt)
            .filterIsInstance<ViewGroup>().firstOrNull { candidate ->
                candidate.childCount == TAB_COUNT && (0 until TAB_COUNT).all {
                    candidate.getChildAt(it).tag == it && candidate.getChildAt(it).hasOnClickListeners()
                }
            } ?: return null
        if (row !== tabRow(bar) || (source !== bar && source !== row)) return null
        val items = (0 until TAB_COUNT).map(row::getChildAt)
        // Other uni-apps inside the campus shell may have their own unrelated five-tab bar.
        val labels = targetSpec.entryLabels
        if (items.indices.any { index ->
                val item = items[index]
                val label = AdapterNavigationSearch.findFirst(item) { group ->
                    (0 until group.childCount).any {
                        val child = group.getChildAt(it)
                        child is TextView && child.text.toString() == labels[index]
                    }
                }
                label == null
            }) return null
        return items
    }

    private const val TAB_BAR_CLASS = "supwisdom.rv"
    private const val TAB_COUNT = 5
}
