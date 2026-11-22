package io.github.offlineglass.hook.adapters.huawei_health

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.TextView
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.compat.XposedHelpers
import io.github.offlineglass.hook.compat.callbacks.XC_LoadPackage
import io.github.offlineglass.config.GlassConfig
import io.github.offlineglass.hook.NavigationCandidate
import io.github.offlineglass.hook.adapters.AdapterNavigationSearch
import io.github.offlineglass.hook.adapters.TargetAdapter
import io.github.offlineglass.targets.TargetSpec

/**
 * Huawei Health (com.huawei.health) native bottom bar.
 *
 * MainActivity.setContentView(layout_activity_mainui) hosts R.id.hw_main_tabs, an
 * instance of com.huawei.ui.commonui.scrollview.HealthBottomView which extends
 * com.huawei.uikit.phone.hwbottomnavigationview.widget.HwBottomNavigationView ->
 * com.huawei.uikit.hwbottomnavigationview.widget.HwBottomNavigationView (a LinearLayout
 * implementing HwBlurable). Every direct child is a BottomNavigationItemView (LinearLayout
 * holding start/top/single icon ImageViews + a HwTextView label + a red-dot badge), so the
 * child index maps 1:1 to the tab index and icons come from MenuItem.getIcon(). Only
 * discovery and native-chrome stripping are app-owned; the shared renderer is reused.
 *
 * Layout facts (layout_activity_mainui.xml):
 *   HealthRelativeLayout (hw_health_main_layout)
 *     ├── HealthViewPager (hw_health_main_viewpager)  layout_above=hw_main_tabs_layout
 *     └── LinearLayout (hw_main_tabs_layout, alignParentBottom)
 *         ├── FrameLayout (extend_pic_padding, 4dp, colour background)
 *         └── HealthBottomView (hw_main_tabs)
 *
 * The feed is therefore ANCHORED ABOVE the tab container: hiding the bar alone keeps the
 * container's reserved strip painted with the window background, so the feed never reaches
 * the screen bottom and a solid bar sits under the glass host. The fix strips the strip
 * container's chrome AND drops the ViewPager's layout_above rule so the feed extends
 * edge-to-edge under the floating glass bar, restoring the spec bottom gap.
 */
internal object HuaweiHealthAdapter : TargetAdapter {
    override val key = "huawei_health"
    override val ownsNavigationFinding = true
    // Huawei paints its selected pill through the blur engine, outside ordinary
    // child backgrounds. The shared host redraws captured icons and labels.
    override val hideRedrawnNavigationSource = true
    // Route the shared installer through the adapter-owned immersion path so the
    // window keeps LAYOUT_HIDE_NAVIGATION and the gesture pill stays flush with
    // the screen bottom on every adapted page (see HuaweiHealthInsets).
    override val navigationImmersionOverride = true

    private const val HEALTH_BOTTOM_VIEW = "com.huawei.ui.commonui.scrollview.HealthBottomView"
    private const val MAIN_TABS_ID = "hw_main_tabs"
    private const val TAB_STRIP_ID = "hw_main_tabs_layout"
    private const val VIEW_PAGER_ID = "hw_health_main_viewpager"
    private const val HW_BAR_LEAF = "widget.HwBottomNavigationView"

    override fun configureNavigationInsets(decor: View, enabled: Boolean) =
        HuaweiHealthInsets.configure(decor, enabled)

    // User-requested artwork sizing (2026-10-10): keep the icon-label gap
    // untouched while enlarging tab artwork. Icon scaling is view scale (pivot
    // centre), so the icon's layout box and thus the label gap stay constant;
    // the label keeps its fixed slot frame so the enlarged text stays centred.
    // Latest round: icons frozen at 1.2*1.2; text enlarged once more and nudged
    // down a touch (positive translationY) per user request.
    override val sourceIconScale = 1.2f * 1.2f
    override val sourceTextTranslationDp = 2f
    override fun configureSourceText(view: TextView, config: GlassConfig, density: Float): Boolean {
        // HwTextView drives its label through Huawei's auto-size engine
        // (setAutoTextSize), which recomputes the text size on every layout or
        // mode flip and silently reverts any one-shot value we pin here.
        // Disable the engine first (both the standard TextView autosize and
        // the HwTextView-specific one), then pin the enlarged fixed size.
        runCatching { view.setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE) }
        runCatching { XposedHelpers.callMethod(view, "setAutoTextSize", 0, 0f) }
        view.textSize = config.textSize * 1.1f * 1.1f * 1.1f
        return true
    }

    override fun createNavigationState(
        context: android.content.Context,
        host: io.github.offlineglass.hook.GlassHostLayout,
    ): io.github.offlineglass.hook.adapters.AppNavigationState? =
        HuaweiHealthNavigationState(host)

    override fun installHooks(lpparam: XC_LoadPackage.LoadPackageParam, spec: TargetSpec) {
        // Zero the navigation-bar bottom insets on module-managed windows so the
        // content lays out under the system gesture pill (which then rests flat
        // on the screen bottom instead of being lifted above it).
        HuaweiHealthInsets.install()
        // The host keeps re-asserting setBlurEnable(true) so a single disable in
        // suppressNativeBottomChrome is defeated by every later call. Instead, hook the
        // blur setter and forcibly short-circuit each invocation to keep the native
        // overlay/blur colour block off the content stream for good.
        runCatching {
            val barBase = XposedHelpers.findClass(
                "com.huawei.uikit.hwbottomnavigationview.widget.HwBottomNavigationView",
                lpparam.classLoader,
            )
            XposedBridge.hookAllMethods(barBase, "setBlurEnable", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    // setBlurEnable is void; clearing the result skips the native body,
                    // so the engine is never told to paint its blur/overlay layer.
                    param.setResult(null)
                }
            })
        }
        // The host ALSO enables the SYSTEM navigation-bar blur: MainActivity's yka
        // helper wraps huawei.android.widget.HwImmersiveMode and calls
        // setNavigationBarBlurEnable(true) on every initNavigationBarView pass. That
        // paints a frosted strip behind the system gesture pill and lifts the pill
        // ("小白条") off the screen bottom on every adapted page. Short-circuit the
        // setter process-wide so the pill stays flat against the screen bottom.
        runCatching {
            val immersive = XposedHelpers.findClass(
                "huawei.android.widget.HwImmersiveMode",
                lpparam.classLoader,
            )
            XposedBridge.hookAllMethods(
                immersive,
                "setNavigationBarBlurEnable",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.setResult(null)
                    }
                },
            )
        }
        // HwTextView's auto-size engine (setAutoTextSize) is re-asserted by the
        // tab item on every label-mode flip (HwBottomNavigationView item code),
        // which re-enables auto sizing and reverts the pinned enlarged label
        // size. Short-circuit the setter ONLY for labels inside the bottom
        // navigation bar: other HwTextViews in the app keep their engine.
        runCatching {
            val hwText = XposedHelpers.findClass(
                "com.huawei.uikit.hwtextview.widget.HwTextView",
                lpparam.classLoader,
            )
            XposedBridge.hookAllMethods(hwText, "setAutoTextSize", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    var node = param.thisObject as? View ?: return
                    var depth = 0
                    while (depth < 8) {
                        if (node.javaClass.name.contains("hwbottomnavigationview")) {
                            param.setResult(null)
                            return
                        }
                        node = node.parent as? View ?: return
                        depth++
                    }
                }
            })
        }
        super.installHooks(lpparam, spec)
    }

    override fun findNavigation(root: View, spec: TargetSpec): NavigationCandidate? {
        val navigation = AdapterNavigationSearch.findFirst(root) { view ->
            val name = view.javaClass.name
            val isBarClass = name == HEALTH_BOTTOM_VIEW ||
                (name.contains("hwbottomnavigationview") && name.endsWith(HW_BAR_LEAF))
            isBarClass &&
                view.isAttachedToWindow && view.visibility == View.VISIBLE &&
                view.width > 0 && view.height > 0
        }
        if (navigation == null) {
            Log.i("HealthGlassDiag", "find: bottom bar not ready; will retry")
            return null
        }
        return NavigationCandidate(navigation, navigation.childCount.coerceIn(2, 7), 1_000)
    }

    override fun estimateSlotCount(group: ViewGroup, spec: TargetSpec): Int? {
        val slots = group.childCount
        return if (slots in 2..7) slots else null
    }

    override fun suppressNativeBottomChrome(navigation: ViewGroup) {
        // Huawei's HwBottomNavigationView implements HwBlurable; the host turns its
        // native background blur on, which paints a solid/gradient block that hides the
        // content stream behind the floating glass bar. Switch it off before stripping.
        runCatching { XposedHelpers.callMethod(navigation, "setBlurEnable", false) }
        stripBackgrounds(navigation)
        suppressTabStripAndExtendFeed(navigation)
        navigation.invalidate()
    }

    override fun suppressNativeBottomChromeOnRebind(navigation: ViewGroup) {
        // Night-mode/theme rebinds can restore stripped chrome and re-fit the feed
        // layout; the whole pass is idempotent so simply run it again.
        suppressTabStripAndExtendFeed(navigation)
    }

    /**
     * The bar sits inside hw_main_tabs_layout (alignParentBottom), and the feed
     * (hw_health_main_viewpager) is anchored layout_above that strip. Making only the
     * bar transparent still leaves the strip reserving screen space, which paints the
     * window background as a solid bar under the glass host and keeps the feed from
     * reaching the screen bottom. Strip the strip's chrome and drop the feed's ABOVE
     * rule so it lays out full-height under the floating glass bar; the glass host's
     * bottom margin then shows the design-spec gap instead of the native strip.
     */
    private fun suppressTabStripAndExtendFeed(navigation: ViewGroup) {
        val strip = navigation.parent as? ViewGroup ?: return
        if (resourceEntryName(strip) != TAB_STRIP_ID) return
        strip.background = ColorDrawable(Color.TRANSPARENT)
        strip.clipChildren = false
        strip.clipToPadding = false
        for (index in 0 until strip.childCount) {
            val child = strip.getChildAt(index) ?: continue
            if (child === navigation) continue
            child.background = ColorDrawable(Color.TRANSPARENT)
            child.backgroundTintList = null
        }
        val root = strip.parent as? ViewGroup ?: navigation.rootView as? ViewGroup ?: return
        var feed: View? = null
        for (index in 0 until root.childCount) {
            val child = root.getChildAt(index) ?: continue
            if (resourceEntryName(child) == VIEW_PAGER_ID) {
                feed = child
                break
            }
        }
        if (feed == null) return
        val params = feed.layoutParams as? RelativeLayout.LayoutParams ?: return
        // layout_above=hw_main_tabs_layout pins the feed on top of the tab strip.
        // Removing the rule and matching the parent height extends the feed under
        // the floating glass bar all the way to the screen bottom.
        params.removeRule(RelativeLayout.ABOVE)
        params.height = RelativeLayout.LayoutParams.MATCH_PARENT
        feed.layoutParams = params
    }

    /**
     * Recursively clear every solid/divider background inside the native bar so no
     * colour block remains over the content feed, while leaving the icon drawables
     * (ImageView.setImageDrawable) and the HwTextView labels untouched for the shared
     * renderer to re-project from source.
     */
    private fun stripBackgrounds(root: ViewGroup) {
        val stack = ArrayDeque<View>()
        stack += root
        while (stack.isNotEmpty()) {
            val view = stack.removeLast()
            if (view.background != null) {
                view.background = ColorDrawable(Color.TRANSPARENT)
                view.backgroundTintList = null
            }
            if (view is ViewGroup) {
                view.clipChildren = false
                view.clipToPadding = false
                for (index in view.childCount - 1 downTo 0) {
                    stack += view.getChildAt(index)
                }
            }
        }
    }

    private fun resourceEntryName(view: View): String? = runCatching {
        if (view.id == View.NO_ID) null else view.resources.getResourceEntryName(view.id)
    }.getOrNull()
}
