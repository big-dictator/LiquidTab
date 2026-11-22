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

    // The native resource is declared in current public.xml. Do not guess a
    // campaign from its size or an arbitrary matching offer in the product list.
    private var couponId: Int? = null
    private var couponTree: android.view.ViewTreeObserver? = null
    private var couponWindow = java.lang.ref.WeakReference<ViewGroup>(null)
    private var couponDirty = true
    private val couponLayout = android.view.ViewTreeObserver.OnGlobalLayoutListener {
        couponDirty = true
    }
    private val couponLocation = IntArray(2)
    private val couponHostLocation = IntArray(2)

    private fun watchChromeLayout(root: ViewGroup) {
        if (couponWindow.get() !== root || couponTree?.isAlive != true) {
            couponTree?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(couponLayout)
            couponWindow = java.lang.ref.WeakReference(root)
            couponTree = root.viewTreeObserver.also { it.addOnGlobalLayoutListener(couponLayout) }
            couponDirty = true
        }
    }

    fun adjustCoupon(host: View, navigationSource: ViewGroup?, flashSaleActive: Boolean) {
        if (!host.isAttachedToWindow || flashSaleActive) return
        val root = host.rootView as? ViewGroup ?: return
        watchChromeLayout(root)
        var strip = taobaoCouponStrip?.takeIf { it.isAttachedToWindow && it.isShown && it.rootView === root }
        if (couponDirty) {
            couponDirty = false
            val id = couponId ?: host.resources.getIdentifier("homepage_pop_view", "id", host.context.packageName).also { couponId = it }
            strip = null
            if (id != 0) {
                var matches = 0
                val stack = ArrayDeque<Pair<View, Int>>()
                stack += root to 0
                var visited = 0
                while (stack.isNotEmpty() && visited++ < 1600) {
                    val (view, depth) = stack.removeLast()
                    if (view === host || view === navigationSource || !view.isShown) continue
                    if (view.id == id) { matches++; strip = view }
                    if (view is ViewGroup && depth < 24)
                        for (i in 0 until view.childCount) stack += view.getChildAt(i) to (depth + 1)
                }
                // Reject duplicate IDs and a truncated search rather than
                // arbitrarily moving the first native view with the same ID.
                if (matches != 1 || stack.isNotEmpty()) strip = null
            }
            taobaoCouponStrip = strip
        }
        val target = strip ?: return
        val density = host.resources.displayMetrics.density
        host.getLocationOnScreen(couponHostLocation)
        if (couponHostLocation[1] <= 0 || host.width <= 0 || host.height <= 0) return
        val base = taobaoCouponBaseTranslations.getOrPut(target) { target.translationY }
        target.getLocationOnScreen(couponLocation)
        val originalBottom = couponLocation[1] + target.height - (target.translationY - base)
        val wanted = base + couponHostLocation[1] - (TAOBAO_COUPON_GLASS_GAP_DP * density).roundToInt() - originalBottom
        if (abs(target.translationY - wanted) > 0.5f) target.translationY = wanted
        var ancestor = target.parent as? ViewGroup
        repeat(6) {
            ancestor?.let { if (it.clipChildren) it.clipChildren = false; if (it.clipToPadding) it.clipToPadding = false }
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
            // Full feed viewport is independent of coupon lifecycle/style success.
            // Do not reintroduce the old blank band when a remote resource changes.
            val reserve = 0
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
        if (selectedIndex != 3 || !host.hasWindowFocus()) { TaobaoCartNativeBridge.suspend(); return }

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

        (host as? GlassHostLayout)?.let { TaobaoCartInitialResource.prepareRoot(resolvedRoot, it) }
        if (TaobaoCartInitialResource.owns(resolvedRoot)) {
            ((host as? GlassHostLayout)?.appNavigationState as? TaobaoNavigationState)?.bindCartScene(resolvedRoot)
            (host as? GlassHostLayout)?.let {
                TaobaoCartInitialResource.bindNativeButtons(resolvedRoot, it)
            }
            if (resolvedRoot.paddingLeft != base[0] || resolvedRoot.paddingTop != base[1] ||
                resolvedRoot.paddingRight != base[2] || resolvedRoot.paddingBottom != base[3]) {
                resolvedRoot.setPadding(base[0], base[1], base[2], base[3])
            }
            resolvedRoot.clipChildren = false
            resolvedRoot.clipToPadding = false
            return
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
        couponTree?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(couponLayout)
        couponTree = null; couponWindow.clear(); couponDirty = true
        TaobaoCartNativeBridge.clear()
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

