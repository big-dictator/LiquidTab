package io.github.offlineglass.hook.adapters.taobao

import android.os.SystemClock
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.widget.TextView
import io.github.offlineglass.hook.GlassHostLayout
import kotlin.math.abs
import kotlin.math.roundToInt

/** Owns Taobao homepage coupon positioning independently of the shared glass host. */
internal class TaobaoPageChrome {
    private val taobaoCouponBaseTranslations = java.util.WeakHashMap<View, Float>()
    private var taobaoCouponStrip: View? = null
    private var lastTaobaoCouponProbe = 0L
    private val taobaoFlashSaleControlBaseTranslations = java.util.WeakHashMap<View, Float>()
    private val taobaoFlashSaleViewportBaseMargins = java.util.WeakHashMap<View, IntArray>()
    private var taobaoFlashSaleCoupon: View? = null
    private var taobaoFlashSaleActionRail: View? = null
    private var taobaoFlashSaleViewport: View? = null
    private val flashViewportPaddings = java.util.WeakHashMap<View, IntArray>()
    private var lastTaobaoFlashSaleControlProbe = 0L
    private val taobaoCartBasePaddings = java.util.WeakHashMap<View, IntArray>()
    private var taobaoCartRoot: ViewGroup? = null
    private var taobaoCartFeed: java.lang.ref.WeakReference<View>? = null
    private var lastTaobaoCartFeedProbe = 0L
    private var cartInitialLayoutRequested: View? = null
    private var gestureDownY = Float.NaN
    private var gestureActive = false

    fun onWindowTouch(host: View, event: MotionEvent, flashSaleActive: Boolean) {
        if (!flashSaleActive) return
        val glassHost = host as? GlassHostLayout ?: return
        val density = host.resources.displayMetrics.density
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureDownY = event.rawY
                gestureActive = false
            }
            MotionEvent.ACTION_MOVE -> {
                val downY = gestureDownY
                if (!downY.isNaN() && abs(event.rawY - downY) >= 8f * density) {
                    gestureActive = true
                    glassHost.adapterShowDuringScroll()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (gestureActive) glassHost.adapterHideAfter(3_000L)
                gestureDownY = Float.NaN
                gestureActive = false
            }
        }
    }

    fun adjustCoupon(host: View, navigationSource: ViewGroup?, flashSaleActive: Boolean) {
        val density = host.resources.displayMetrics.density

        if (!host.isAttachedToWindow) return

        // Flash Sale owns a different coupon/action overlay. Do not let the

        // generic homepage structural fallback capture one of those views.

        if (flashSaleActive) return

        val root = host.rootView as? ViewGroup ?: return

        val hostLocation = IntArray(2).also(host::getLocationOnScreen)

        val hostTop = hostLocation[1]

        if (hostTop <= 0 || host.width <= 0 || host.height <= 0) return

        // Keep only a small visual gap above the glass. Reusing the host's

        // screen-bottom margin here lifted the coupon strip much too far.

        val desiredBottom = hostTop - (TAOBAO_COUPON_GLASS_GAP_DP * density).roundToInt()

        val now = SystemClock.uptimeMillis()



        var strip = taobaoCouponStrip?.takeIf { it.isAttachedToWindow && it.isShown }

        // The homepage recreates this view during a tab switch. Resolve its

        // stable id on the very first pre-draw instead of waiting for the

        // throttled hierarchy fallback; that wait exposed one native-position

        // frame and looked like an up/down jump.

        if (strip == null) {

            val popViewId = runCatching {

                host.resources.getIdentifier("homepage_pop_view", "id", host.context.packageName)

            }.getOrDefault(0)

            if (popViewId != 0) {

                strip = root.findViewById<View>(popViewId)

                    ?.takeIf { it.isAttachedToWindow && it.isShown }

                if (strip != null) taobaoCouponStrip = strip

            }

        }

        if (strip == null && now - lastTaobaoCouponProbe >= TAOBAO_COUPON_PROBE_INTERVAL_MS) {

            lastTaobaoCouponProbe = now

            // Taobao exposes the complete bottom coupon operation module as

            // homepage_pop_view. Prefer this stable container over campaign

            // copy/geometry: its descendants are DX Views and their labels can

            // change or be inaccessible, while moving a child leaves the pink

            // full-width background at the screen bottom.

            var claimView: View? = null

            val stack = ArrayDeque<Pair<View, Int>>()

            if (strip == null) stack += root to 0

            while (stack.isNotEmpty() && claimView == null) {

                val (candidate, depth) = stack.removeLast()

                if (candidate === host || candidate === navigationSource) continue

                val label = when (candidate) {

                    is TextView -> candidate.text?.toString().orEmpty()

                    else -> candidate.contentDescription?.toString().orEmpty()

                }

                if (candidate.isShown && label.contains("\u7acb\u5373\u9886\u53d6")) {

                    claimView = candidate

                    break

                }

                if (candidate is ViewGroup && depth < 15) {

                    for (index in candidate.childCount - 1 downTo 0) {

                        stack += candidate.getChildAt(index) to (depth + 1)

                    }

                }

            }



            if (strip == null) claimView?.let { claim ->

                var current: View? = claim

                var best: View? = null

                repeat(7) {

                    val candidate = current ?: return@repeat

                    val wideEnough = candidate.width >= root.width * 0.68f

                    val compactEnough = candidate.height in 1..(140f * density).roundToInt()

                    if (wideEnough && compactEnough) best = candidate

                    current = candidate.parent as? View

                }

                strip = best ?: claim

                taobaoCouponStrip = strip

            }



            // Current Taobao campaign strips are often a single DX-rendered

            // View with no text/contentDescription in the Android hierarchy.

            // Its stable geometry is a compact full-width row ending exactly

            // at the native TabWidget boundary. Use that structure as the

            // fallback instead of depending on campaign copy.

            if (strip == null) {

                val source = navigationSource

                val sourceLocation = source?.let { IntArray(2).also(it::getLocationOnScreen) }

                val sourceTop = sourceLocation?.get(1) ?: Int.MAX_VALUE

                var bestCandidate: View? = null

                var bestScore = Int.MAX_VALUE

                val structuralStack = ArrayDeque<Pair<View, Int>>()

                structuralStack += root to 0

                while (structuralStack.isNotEmpty()) {

                    val (candidate, depth) = structuralStack.removeLast()

                    if (candidate !== host && candidate !== source && candidate.isShown &&

                        candidate.width >= root.width * 0.92f &&

                        candidate.height in (28f * density).roundToInt()..(100f * density).roundToInt()

                    ) {

                        val location = IntArray(2).also(candidate::getLocationOnScreen)

                        val top = location[1]

                        val bottom = top + candidate.height

                        val nearNativeBoundary = top < sourceTop &&

                            bottom in (sourceTop - (8f * density).roundToInt())..

                                (sourceTop + (14f * density).roundToInt())

                        if (nearNativeBoundary) {

                            val score = abs(bottom - sourceTop) +

                                if (candidate is ViewGroup) 0 else 20

                            if (score < bestScore) {

                                bestScore = score

                                bestCandidate = candidate

                            }

                        }

                    }

                    if (candidate is ViewGroup && depth < 15) {

                        for (index in candidate.childCount - 1 downTo 0) {

                            structuralStack += candidate.getChildAt(index) to (depth + 1)

                        }

                    }

                }

                strip = bestCandidate

                taobaoCouponStrip = strip

            }

        }



        val resolvedStrip = strip ?: return

        val base = taobaoCouponBaseTranslations.getOrPut(resolvedStrip) { resolvedStrip.translationY }

        val location = IntArray(2).also(resolvedStrip::getLocationOnScreen)

        val appliedDelta = resolvedStrip.translationY - base

        val originalBottom = location[1] + resolvedStrip.height - appliedDelta

        val wantedTranslation = base + (desiredBottom - originalBottom)

        if (abs(resolvedStrip.translationY - wantedTranslation) > 0.5f) {

            resolvedStrip.translationY = wantedTranslation

        }

        var ancestor = resolvedStrip.parent as? ViewGroup

        repeat(6) {

            ancestor?.clipChildren = false

            ancestor?.clipToPadding = false

            ancestor = ancestor?.parent as? ViewGroup

        }

    }




    fun adjustFlashSaleControls(host: View, navigationSource: ViewGroup?, flashSaleActive: Boolean) {
        val density = host.resources.displayMetrics.density

        if (!host.isAttachedToWindow) return

        if (!flashSaleActive) {

            restoreTaobaoFlashSaleBottomControls()

            return

        }

        val root = host.rootView as? ViewGroup ?: return

        val hostLocation = IntArray(2).also(host::getLocationOnScreen)

        val hostTop = hostLocation[1]

        if (hostTop <= 0 || root.width <= 0 || root.height <= 0) return

        val now = SystemClock.uptimeMillis()



        var coupon = taobaoFlashSaleCoupon?.takeIf {

            it.isAttachedToWindow && it.isShown && it.width >= root.width * 0.75f

        }

        var actionRail = taobaoFlashSaleActionRail?.takeIf {

            it.isAttachedToWindow && it.isShown

        }

        if ((coupon == null || actionRail == null) &&

            now - lastTaobaoFlashSaleControlProbe >= TAOBAO_FLASH_CONTROL_PROBE_INTERVAL_MS

        ) {

            lastTaobaoFlashSaleControlProbe = now

            var bestCouponScore = Int.MIN_VALUE

            var bestRailScore = Int.MIN_VALUE

            val stack = ArrayDeque<Pair<View, Int>>()

            stack += root to 0

            var visited = 0

            while (stack.isNotEmpty() && visited++ < TAOBAO_FLASH_MAX_VIEW_COUNT) {

                val (view, depth) = stack.removeLast()

                if (view === host || view === navigationSource || !view.isShown || view.alpha <= 0.02f) continue

                if (view.javaClass.name == "me.ele.taobao.flash.sale.themis.webview.InterceptFrameLayout" &&
                    view.width >= root.width * 0.75f) {
                    val visible = Rect()
                    if (view.getGlobalVisibleRect(visible) && visible.width() >= root.width * 0.75f) {
                        taobaoFlashSaleViewport = view
                    }
                }

                val rect = Rect()

                if (view.getGlobalVisibleRect(rect) && rect.width() > 0 && rect.height() > 0) {

                    val heightDp = view.height / density

                    val bottomZone = rect.bottom >= root.height * 0.80f

                    if (coupon == null && bottomZone &&

                        rect.width() >= root.width * 0.78f && heightDp in 24f..96f

                    ) {

                        val text = collectTaobaoOverlayText(view, 4)

                        val semantic = TAOBAO_FLASH_COUPON_MARKERS.count(text::contains)

                        val score = semantic * 1_000 + rect.bottom + rect.width() - rect.height()

                        if ((semantic > 0 || rect.bottom >= root.height * 0.94f) && score > bestCouponScore) {

                            bestCouponScore = score

                            coupon = view

                        }

                    }

                    if (actionRail == null && view is ViewGroup &&

                        rect.left >= root.width * 0.70f && rect.right >= root.width * 0.92f &&

                        rect.bottom > hostTop && heightDp in 88f..520f &&

                        rect.width() in (36f * density).roundToInt()..(150f * density).roundToInt()

                    ) {

                        val clickable = countCompactClickableDescendants(view, 5)

                        if (clickable >= 2) {

                            val score = clickable * 500 - rect.width() + rect.height()

                            if (score > bestRailScore) {

                                bestRailScore = score

                                actionRail = view

                            }

                        }

                    }

                }

                if (view is ViewGroup && depth < 24) {

                    for (index in view.childCount - 1 downTo 0) stack += view.getChildAt(index) to (depth + 1)

                }

            }

            taobaoFlashSaleCoupon = coupon

            taobaoFlashSaleActionRail = actionRail

        }



        // Coupon visually kisses the glass with a deliberately smaller gap.

        coupon?.let { moveTaobaoFlashControlAbove(it, hostTop, TAOBAO_FLASH_COUPON_GAP_DP, density) }

        taobaoFlashSaleViewport?.takeIf { it.isAttachedToWindow && it.isShown }?.let { viewport ->
            val base = flashViewportPaddings.getOrPut(viewport) {
                intArrayOf(viewport.paddingLeft, viewport.paddingTop, viewport.paddingRight, viewport.paddingBottom)
            }
            val location = IntArray(2).also(viewport::getLocationOnScreen)
            // Anchor to the resting bar, not its animated translation. Keeping
            // viewport geometry fixed during a drag prevents repeated engine resize.
            val restingTop = hostTop - host.translationY
            val bottom = location[1] + viewport.height
            val reserve = (bottom - restingTop + TAOBAO_FLASH_ACTION_GAP_DP * density)
                .roundToInt().coerceAtLeast(base[3]).coerceAtMost((viewport.height / 3).coerceAtLeast(base[3]))
            if (viewport.paddingBottom != reserve) {
                viewport.setPadding(base[0], base[1], base[2], reserve)
            }
        }

        // Shopping cart, campaign bubble and related controls keep their native

        // internal spacing because their shared rail is translated as one unit.

        actionRail?.let { moveTaobaoFlashControlAbove(it, hostTop, TAOBAO_FLASH_ACTION_GAP_DP, density) }

    }



    private fun moveTaobaoFlashControlAbove(view: View, hostTop: Int, gapDp: Float, density: Float) {

        val base = taobaoFlashSaleControlBaseTranslations.getOrPut(view) { view.translationY }

        val location = IntArray(2).also(view::getLocationOnScreen)

        val applied = view.translationY - base

        val naturalBottom = location[1] + view.height - applied

        val desiredBottom = hostTop - gapDp * density

        val wanted = base + (desiredBottom - naturalBottom).coerceAtMost(0f)

        if (abs(view.translationY - wanted) > 0.5f) view.translationY = wanted

        (view.parent as? ViewGroup)?.let {

            it.clipChildren = false

            it.clipToPadding = false

        }

    }



    private fun restoreTaobaoFlashSaleBottomControls() {
        flashViewportPaddings.forEach { (view, base) ->
            if (view.paddingBottom != base[3]) view.setPadding(base[0], base[1], base[2], base[3])
        }
        flashViewportPaddings.clear()

        taobaoFlashSaleControlBaseTranslations.entries.toList().forEach { (view, base) ->

            if (abs(view.translationY - base) > 0.5f) view.translationY = base

        }

        taobaoFlashSaleControlBaseTranslations.clear()

        taobaoFlashSaleCoupon = null

        taobaoFlashSaleActionRail = null

        taobaoFlashSaleViewportBaseMargins.entries.toList().forEach { (view, base) ->

            val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return@forEach

            if (params.leftMargin != base[0] || params.topMargin != base[1] ||

                params.rightMargin != base[2] || params.bottomMargin != base[3]

            ) {

                params.setMargins(base[0], base[1], base[2], base[3])

                view.layoutParams = params

            }

        }

        taobaoFlashSaleViewportBaseMargins.clear()

        taobaoFlashSaleViewport = null

    }



    private fun collectTaobaoOverlayText(view: View, maxDepth: Int): String {

        val result = StringBuilder()

        val stack = ArrayDeque<Pair<View, Int>>()

        stack += view to 0

        while (stack.isNotEmpty()) {

            val (candidate, depth) = stack.removeLast()

            if (candidate is TextView) result.append(candidate.text).append('|')

            candidate.contentDescription?.let { result.append(it).append('|') }

            if (candidate is ViewGroup && depth < maxDepth) {

                for (index in candidate.childCount - 1 downTo 0) stack += candidate.getChildAt(index) to (depth + 1)

            }

        }

        return result.toString()

    }



    private fun countCompactClickableDescendants(group: ViewGroup, maxDepth: Int): Int {

        var count = 0

        val stack = ArrayDeque<Pair<View, Int>>()

        for (index in group.childCount - 1 downTo 0) stack += group.getChildAt(index) to 0

        while (stack.isNotEmpty() && count < 8) {

            val (view, depth) = stack.removeLast()

            if (view.isShown && view.isClickable && view.width > 0 && view.height > 0) count++

            if (view is ViewGroup && depth < maxDepth) {

                for (index in view.childCount - 1 downTo 0) stack += view.getChildAt(index) to (depth + 1)

            }

        }

        return count

    }





    /**

     * The current cart is a full-screen Weex scene. Its checkout action bar is

     * fixed to the Weex viewport bottom, so translating Android descendants is

     * ineffective and leaves the entire grey/orange bar inside our backdrop.

     * Reserve the exact glass+safe-gap inset on the cart viewport itself; Weex

     * then lays its native checkout bar immediately above the glass.

     */



    fun adjustCart(host: View, selectedIndex: Int) {
        val density = host.resources.displayMetrics.density

        if (!host.isAttachedToWindow) return
        if (selectedIndex != 3) return

        val root = host.rootView as? ViewGroup ?: return

        var cartRoot = taobaoCartRoot?.takeIf { it.isAttachedToWindow && it.isShown }

        if (cartRoot == null) {

            val cartRootId = runCatching {

                host.resources.getIdentifier("icart_weex_root_view", "id", host.context.packageName)

            }.getOrDefault(0)

            cartRoot = if (cartRootId != 0) {

                root.findViewById<View>(cartRootId) as? ViewGroup

            } else {

                null

            }

            cartRoot = cartRoot?.takeIf { it.isAttachedToWindow && it.isShown }

            taobaoCartRoot = cartRoot

        }

        val resolvedRoot = cartRoot ?: return
        if (resolvedRoot.width <= 0 || resolvedRoot.height <= 0) {
            // The engine deliberately starts with unmeasured children. Ask for
            // one native measure pass; do not assign dimensions or padding.
            if (cartInitialLayoutRequested !== resolvedRoot) {
                cartInitialLayoutRequested = resolvedRoot
                resolvedRoot.post {
                    if (resolvedRoot.isAttachedToWindow) {
                        resolvedRoot.requestLayout()
                        (resolvedRoot.parent as? View)?.requestLayout()
                    }
                }
            }
            return
        }
        val contentReady = (0 until resolvedRoot.childCount).any {
            val child = resolvedRoot.getChildAt(it)
            child.visibility == View.VISIBLE && child.width > 0 && child.height > 0
        }
        if (!contentReady) return

        val base = taobaoCartBasePaddings.getOrPut(resolvedRoot) {

            intArrayOf(

                resolvedRoot.paddingLeft,

                resolvedRoot.paddingTop,

                resolvedRoot.paddingRight,

                resolvedRoot.paddingBottom,

            )

        }

        val hostLocation = IntArray(2).also(host::getLocationOnScreen)

        val cartLocation = IntArray(2).also(resolvedRoot::getLocationOnScreen)

        val desiredCheckoutBottom = hostLocation[1] - host.translationY.roundToInt()

        val cartBottom = cartLocation[1] + resolvedRoot.height

        val requiredBottomPadding = (cartBottom - desiredCheckoutBottom)

            .coerceAtLeast(base[3])

            .coerceAtMost((resolvedRoot.height * 0.45f).roundToInt())

        if (resolvedRoot.paddingLeft != base[0] || resolvedRoot.paddingTop != base[1] ||

            resolvedRoot.paddingRight != base[2] || resolvedRoot.paddingBottom != requiredBottomPadding

        ) {

            resolvedRoot.setPadding(base[0], base[1], base[2], requiredBottomPadding)

            resolvedRoot.requestLayout()

            resolvedRoot.invalidate()

        }

        resolvedRoot.clipChildren = false

        resolvedRoot.clipToPadding = false

        // Native engine owns RecyclerView sizing, including its 0x0 bootstrap.
        // Never compete with that layout by stretching its feed on every draw.

    }



    /**

     * The cart feed is inset by the Weex viewport's bottom padding and stops

     * at the checkout bar. Stretch the page's scrollable itself down to the

     * real window bottom so the feed keeps scrolling under the glass bar,

     * where the gradient veil can blur it.

     */

    private fun extendTaobaoCartFeedUnderGlass(cartRoot: ViewGroup) {

        val rootHeight = cartRoot.height

        if (rootHeight <= 0) return

        val now = SystemClock.uptimeMillis()

        var feed = taobaoCartFeed?.get()?.takeIf { it.isAttachedToWindow && it.isShown }

        if (feed == null && now - lastTaobaoCartFeedProbe >= TAOBAO_COUPON_PROBE_INTERVAL_MS) {

            lastTaobaoCartFeedProbe = now

            var best: View? = null

            var bestHeight = 0

            val stack = ArrayDeque<Pair<View, Int>>()

            stack += cartRoot to 0

            while (stack.isNotEmpty()) {

                val (view, depth) = stack.removeLast()

                if (view === cartRoot || view.visibility != View.VISIBLE) continue

                val name = view.javaClass.name

                val scrollable = name.contains("Scroll", true) || name.contains("Recycler", true)

                if (scrollable && view.width >= cartRoot.width * 0.85f && view.height > bestHeight) {

                    best = view

                    bestHeight = view.height

                }

                if (view is ViewGroup && depth < 14) {

                    for (index in 0 until view.childCount) {

                        stack += view.getChildAt(index) to (depth + 1)

                    }

                }

            }

            if (bestHeight < rootHeight * 0.3f) return

            feed = best

            taobaoCartFeed = java.lang.ref.WeakReference(best)

        }

        val resolvedFeed = feed ?: return

        val rootLoc = IntArray(2).also(cartRoot::getLocationOnScreen)

        val feedLoc = IntArray(2).also(resolvedFeed::getLocationOnScreen)

        val desiredHeight = (rootLoc[1] + rootHeight) - feedLoc[1]

        if (desiredHeight <= resolvedFeed.height) return

        val params = resolvedFeed.layoutParams

        if (params.height != desiredHeight) {

            params.height = desiredHeight

            resolvedFeed.layoutParams = params

        }

        var ancestor = resolvedFeed.parent as? ViewGroup

        repeat(8) {

            ancestor?.let {

                it.clipChildren = false

                it.clipToPadding = false

            }

            ancestor = ancestor?.parent as? ViewGroup

        }

    }



    fun dispose() {
        cartInitialLayoutRequested = null
        restoreTaobaoFlashSaleBottomControls()
        taobaoCouponBaseTranslations.forEach { (view, base) -> if (view.isAttachedToWindow) view.translationY = base }
        taobaoCouponBaseTranslations.clear()
        taobaoCouponStrip = null
    }

    private companion object {
        const val TAOBAO_COUPON_GLASS_GAP_DP = 4f
        const val TAOBAO_COUPON_PROBE_INTERVAL_MS = 500L
        const val TAOBAO_FLASH_CONTROL_PROBE_INTERVAL_MS = 160L
        const val TAOBAO_FLASH_COUPON_GAP_DP = 3f
        const val TAOBAO_FLASH_ACTION_GAP_DP = 10f
        const val TAOBAO_FLASH_MAX_VIEW_COUNT = 1600
        val TAOBAO_FLASH_COUPON_MARKERS = arrayOf("优惠券", "红包", "去使用", "立即领取")
    }
}

