package io.github.offlineglass.hook.adapters.mihome

import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.RelativeLayout
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.globalGlassBottomGapPx
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.max

private data class MiHomeLayoutState(
    val baseTranslations: java.util.WeakHashMap<View, Float> = java.util.WeakHashMap(),
    val scrollPaddings: java.util.WeakHashMap<View, IntArray> = java.util.WeakHashMap(),
    var liftedAction: View? = null,
    var lastProbeAt: Long = 0L,
    var editControlIds: List<Pair<Int, Int>>? = null,
)
private val layoutStates = java.util.WeakHashMap<GlassHostLayout, MiHomeLayoutState>()
private val GlassHostLayout.miHomeState get() = layoutStates.getOrPut(this) { MiHomeLayoutState() }
private val GlassHostLayout.miHomeDensity get() = resources.displayMetrics.density
private val GlassHostLayout.miHomeBottomActionBaseTranslations get() = miHomeState.baseTranslations
private val GlassHostLayout.miHomeScrollBasePaddings get() = miHomeState.scrollPaddings
private var GlassHostLayout.miHomeLiftedBottomAction: View?
    get() = miHomeState.liftedAction
    set(value) { miHomeState.liftedAction = value }
private var GlassHostLayout.lastMiHomeBottomActionProbe: Long
    get() = miHomeState.lastProbeAt
    set(value) { miHomeState.lastProbeAt = value }

private const val MI_HOME_PACKAGE = "com.xiaomi.smarthome"
private const val MI_HOME_BOTTOM_NAV_CLASS = "com.xiaomi.smarthome.newui.buttomtab.TabPageIndicatorNew"
private const val MI_HOME_VIEW_PAGER_CLASS = "com.xiaomi.smarthome.ui.LinearViewPager"
// Obfuscated resource names differ between APK versions. Match complete editor pairs.
private val MI_HOME_EDIT_CONTROL_NAMES = listOf("aal" to "ar3", "aau" to "ard")
private const val MI_HOME_ACTION_PROBE_INTERVAL_MS = 160L
private const val MI_HOME_ACTION_MAX_HEIGHT_DP = 128f
private const val MI_HOME_ACTION_MIN_HEIGHT_DP = 36f
private const val MI_HOME_STORE_INDEX = 3
private const val MI_HOME_MINE_SCROLL_ID = "cp2"
private const val MI_HOME_PRODUCT_INDEX = 2
private val MI_HOME_ACTION_ID_MARKERS = arrayOf("bottom", "action", "buy", "cart", "shop", "bar")
private val MI_HOME_ACTION_TEXT_MARKERS = arrayOf("立即购买", "加入购物车", "购买", "购物车", "去购买")

private fun GlassHostLayout.findMiHomeBottomNav(): ViewGroup? {
    var ancestor: View? = navigationSource
    repeat(6) {
        if (ancestor?.javaClass?.name == MI_HOME_BOTTOM_NAV_CLASS) return ancestor as? ViewGroup
        ancestor = ancestor?.parent as? View
    }
    val root = rootView as? ViewGroup ?: return null
    val stack = ArrayDeque<Pair<View, Int>>(); stack += root to 0
    while (stack.isNotEmpty()) {
        val (view, depth) = stack.removeLast()
        if (view is ViewGroup && view.javaClass.name == MI_HOME_BOTTOM_NAV_CLASS) return view
        if (view is ViewGroup && depth < 12) for (index in 0 until view.childCount) stack += view.getChildAt(index) to depth + 1
    }
    return null
}

internal fun GlassHostLayout.suppressNativeMiHomeBottomBar(source: ViewGroup?) {

        if (context.packageName != MI_HOME_PACKAGE || source == null) return

        val bottomNav = findMiHomeBottomNav() ?: source

        if (source.alpha != 0f) source.alpha = 0f

        if (bottomNav.alpha != 0f) bottomNav.alpha = 0f

        source.background = null

        source.backgroundTintList = null

        bottomNav.background = null

        bottomNav.backgroundTintList = null

        bottomNav.clipChildren = false

        bottomNav.clipToPadding = false



        val parent = bottomNav.parent as? ViewGroup

        parent?.clipChildren = false

        parent?.clipToPadding = false

        if (parent is RelativeLayout) {

            for (index in 0 until parent.childCount) {

                val sibling = parent.getChildAt(index)

                if (sibling === bottomNav) continue

                if (sibling.javaClass.name == MI_HOME_VIEW_PAGER_CLASS) {

                    val params = sibling.layoutParams as? RelativeLayout.LayoutParams ?: continue

                    params.removeRule(RelativeLayout.ABOVE)

                    params.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)

                    if (params.bottomMargin != 0) params.bottomMargin = 0

                    sibling.layoutParams = params

                    sibling.requestLayout()

                }

            }

        }



        var ancestor = parent

        repeat(3) {

            ancestor?.clipChildren = false

            ancestor?.clipToPadding = false

            ancestor = ancestor?.parent as? ViewGroup

        }

    }



    /**

     * Xiaomi Home keeps its main Activity and pager alive while device-card edit mode is open,

     * so the ordinary navigation-source checks cannot distinguish it from the home page.  The

     * editor does, however, mount two dedicated native views for its whole lifetime: the top

     * "done" action and the bottom edit-action panel.  Checking their ids is O(1), avoids a

     * per-frame hierarchy walk, and becomes false on the first traversal after leaving edit mode.

     */

internal fun GlassHostLayout.isMiHomeDeviceEditMode(): Boolean {

        if (context.packageName != MI_HOME_PACKAGE) return false

        val ids = miHomeState.editControlIds ?: MI_HOME_EDIT_CONTROL_NAMES.map { (done, actions) ->
            resources.getIdentifier(done, "id", MI_HOME_PACKAGE) to
                resources.getIdentifier(actions, "id", MI_HOME_PACKAGE)
        }.filter { (done, actions) -> done != 0 && actions != 0 }
            .also { miHomeState.editControlIds = it }
        val root = rootView ?: return false
        return ids.any { (done, actions) ->
            root.findViewById<View>(done)?.isShown == true &&
                root.findViewById<View>(actions)?.isShown == true
        }

    }



    /**

     * Keep Xiaomi Community's floating compose actions above the glass bar,

     * generically across every forum sub-page. Two paths by page type:

     * native feeds (热搜/推荐) expose a real compact view �?detected purely

     * geometrically (bottom-right, compact, clickable, intersecting the glass

     * zone) and lifted via translationY; H5 board pages (everything else)

     * render #addPost inside the WebView DOM, so an idempotent script lifts

     * it there (see the injection block below). Neither path keys on page

     * ids or class names, so new sub-pages need no adaptation.

     */

internal fun GlassHostLayout.adjustMiHomeBottomActions() {

        if (context.packageName != MI_HOME_PACKAGE || width <= 0 || height <= 0) return

        val now = SystemClock.uptimeMillis()

        if (now - lastMiHomeBottomActionProbe < MI_HOME_ACTION_PROBE_INTERVAL_MS) return

        lastMiHomeBottomActionProbe = now



        val root = rootView as? ViewGroup ?: return

        val bottomNav = findMiHomeBottomNav()

        val hostRect = Rect()

        if (!getGlobalVisibleRect(hostRect)) return

        val gap = globalGlassBottomGapPx(this)

        val targetBottom = hostRect.top - gap

        val selected = (adapterResolvedSelection() ?: selectedIndex)

            .coerceIn(0, (adapterSlotCount - 1).coerceAtLeast(0))



        // The last Xiaomi Home page is a full-height ScrollView. Its stock

        // bottom inset disappears together with the stock tab row, so restore

        // an equivalent scroll range for the floating glass plus both safety

        // gaps. This lets the final action row scroll completely above glass.

        // The 产品 page reserves the same room (user request): its product

        // list is also a full-height scroller with no native bar inset left.

        val scrollSafetyPage = selected == adapterSlotCount - 1 ||

            selected == MI_HOME_PRODUCT_INDEX

        val requiredBottomPadding = hostRect.height() + gap * 2

        val scrollCandidates = ArrayList<ViewGroup>()

        val scrollStack = ArrayDeque<Pair<View, Int>>()

        scrollStack += root to 0

        while (scrollStack.isNotEmpty()) {

            val (view, depth) = scrollStack.removeLast()

            if (view === this || view === bottomNav) continue

            if (view is ViewGroup) {

                val className = view.javaClass.name

                val resourceName = viewResourceEntryName(view).orEmpty()

                val scrollLike = resourceName == MI_HOME_MINE_SCROLL_ID ||

                    className.contains("ScrollView") ||

                    className.contains("RecyclerView") ||

                    className.contains("NestedScroll")

                if (scrollLike && view.visibility == View.VISIBLE) {

                    val rect = Rect()

                    if (view.getGlobalVisibleRect(rect) && rect.top < hostRect.top &&

                        rect.bottom > hostRect.top

                    ) {

                        scrollCandidates += view

                    }

                }

                if (depth < 28) {

                    for (index in view.childCount - 1 downTo 0) {

                        scrollStack += view.getChildAt(index) to (depth + 1)

                    }

                }

            }

        }

        for (view in scrollCandidates) {

            val base = miHomeScrollBasePaddings.getOrPut(view) {

                intArrayOf(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom)

            }

            val wantedBottom = if (scrollSafetyPage) {

                max(base[3], requiredBottomPadding)

            } else {

                base[3]

            }

            if (view.paddingLeft != base[0] || view.paddingTop != base[1] ||

                view.paddingRight != base[2] || view.paddingBottom != wantedBottom

            ) {

                view.setPadding(base[0], base[1], base[2], wantedBottom)

                view.clipToPadding = false

                view.requestLayout()

            }

        }



        // Only the five-tab Store page owns the fixed orange purchase strip.

        // Locate that real bottom overlay geometrically and move it as a unit;

        // never translate product rows or the scrolling page itself.

        val storeSelected = adapterSlotCount == 5 && selected == MI_HOME_STORE_INDEX

        if (!storeSelected) {

            miHomeLiftedBottomAction?.let { old ->

                miHomeBottomActionBaseTranslations[old]?.let { base -> old.translationY = base }

            }

            miHomeLiftedBottomAction = null

            return

        }



        val minimumWidth = root.width * 0.72f

        val maximumHeight = (MI_HOME_ACTION_MAX_HEIGHT_DP * miHomeDensity).roundToInt()

        val minimumHeight = (MI_HOME_ACTION_MIN_HEIGHT_DP * miHomeDensity).roundToInt()

        // Once the actual fixed strip has been identified, keep that exact

        // instance for the lifetime of this Store page. Product tiles move in

        // and out of the same bottom band while scrolling and must never win a

        // later geometry probe.

        var bestTarget: ViewGroup? = (miHomeLiftedBottomAction as? ViewGroup)

            ?.takeIf { it.isAttachedToWindow && it.visibility == View.VISIBLE }

        var bestScore = Int.MIN_VALUE

        if (bestTarget == null) {

            val stack = ArrayDeque<Pair<View, Int>>()

            stack += root to 0

            while (stack.isNotEmpty()) {

                val (view, depth) = stack.removeLast()

                if (view === this || view === bottomNav) continue

                if (view is ViewGroup && view.visibility == View.VISIBLE &&

                    !isDescendantOf(view, bottomNav)

                ) {

                    val className = view.javaClass.name

                    val rect = Rect()

                    val geometryMatches = view.alpha > 0.05f &&

                        view.getGlobalVisibleRect(rect) &&

                        rect.width() >= minimumWidth &&

                        rect.height() in minimumHeight..maximumHeight &&

                        rect.top >= hostRect.top - maximumHeight &&

                        rect.bottom > hostRect.top &&

                        !className.contains("Scroll") &&

                        !className.contains("Recycler") &&

                        !className.contains("ViewPager") &&

                        !className.contains("TabPageIndicator")

                    if (geometryMatches) {

                        val idName = viewResourceEntryName(view).orEmpty().lowercase()

                        val idBonus = if (MI_HOME_ACTION_ID_MARKERS.any(idName::contains)) 600 else 0

                        val bottomDistance = abs(rect.bottom - root.height)

                        val score = idBonus + rect.width() - rect.height() - bottomDistance / 2

                        if (score > bestScore) {

                            bestScore = score

                            bestTarget = view

                        }

                    }

                }

                if (view is ViewGroup && depth < 32) {

                    for (index in view.childCount - 1 downTo 0) {

                        stack += view.getChildAt(index) to (depth + 1)

                    }

                }

            }

        }

        val target = bestTarget ?: return

        if (miHomeLiftedBottomAction !== target) {

            miHomeLiftedBottomAction?.let { old ->

                miHomeBottomActionBaseTranslations[old]?.let { base -> old.translationY = base }

            }

            miHomeLiftedBottomAction = target

        }

        val base = miHomeBottomActionBaseTranslations.getOrPut(target) { target.translationY }

        if (target.width <= 0 || target.height <= 0) return

        val location = IntArray(2).also(target::getLocationInWindow)

        val currentBottom = location[1] + target.height

        val baseBottom = currentBottom - (target.translationY - base).roundToInt()

        val wanted = base + (targetBottom - baseBottom)

        if (abs(target.translationY - wanted) > 0.5f) target.translationY = wanted

        var ancestor = target.parent as? ViewGroup

        repeat(4) {

            ancestor?.clipChildren = false

            ancestor?.clipToPadding = false

            ancestor = ancestor?.parent as? ViewGroup

        }

    }



    /**

     * Mi Health's four main pages (健康/运动/设备/我的) are full-height

     * scrollables whose stock bottom insets vanish together with the native

     * tab row, so their final action rows scroll to a stop underneath the

     * floating glass. Ported from Mi Home's 我的-page treatment.

     *

     * Two hard lessons from the 设备 page (its main scroller also hosts nested

     * RecyclerViews for tab sections that ride past the bar top while the

     * outer list is scrolled down):

     *  - Only the OUTERMOST intersecting scrollable owns the reservation.

     *    Padding a nested list as well compounds both paddings into a tall

     *    blank strip below the last row (device_rv_content 344px +

     *    device_rv_tab_content 344px).

     *  - The reservation is anchored to the container's OWN bottom edge, not

     *    to the screen: required = rect.bottom - (barTop - gap). Containers

     *    that already stop above the bar need proportionally less (or no)

     *    padding, which keeps 健康/运动/设备/我的 from over-scrolling.

     */

    /** Keep the native mini-player layout intact and only replace its chrome. */





