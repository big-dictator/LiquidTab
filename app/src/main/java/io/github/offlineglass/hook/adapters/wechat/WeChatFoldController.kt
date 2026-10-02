package io.github.offlineglass.hook.adapters.wechat

import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import de.robv.android.xposed.XposedBridge
import kotlin.math.abs
import kotlin.math.roundToInt

/** Owns the folded-pinned-chats lift and touch proxy for one WeChat host. */
internal class WeChatFoldController {
    private val weChatFoldBarBaseTranslations = java.util.WeakHashMap<View, Float>()
    var weChatFoldBarTarget: View? = null
    var weChatFoldBarTouchProxy: WeChatFoldTouchProxy? = null
    private var lastWeChatFoldDiagMessage: String? = null
    private var lastWeChatFoldDiagTime = 0L
    private var lastWeChatFoldBarProbe = 0L
    private var weChatFoldInteractionHoldUntil = 0L
    private companion object {
        const val FOLD_TO_GLASS_GAP_DP = -1f
        const val FOLD_BAR_PROBE_INTERVAL_MS = 120L
        const val FOLD_BAR_MIN_HEIGHT_DP = 32f
        const val FOLD_BAR_MAX_HEIGHT_DP = 64f
        const val FOLD_INTERACTION_SETTLE_MS = 420L
        val FOLD_BAR_ID_MARKERS = arrayOf("fold", "collapse", "top", "header", "footer")
    }

    fun adjust(host: View, source: ViewGroup?, selected: Int, density: Float) {

        if (host.width <= 0 || host.height <= 0) return

        val now = SystemClock.uptimeMillis()

        if (selected != 0) {

            restoreAllWeChatFoldBarTranslations()

            weChatFoldBarTarget = null

            hideWeChatFoldTouchProxy()

            return

        }

        if (now < weChatFoldInteractionHoldUntil) {

            // WeChat removes/inserts the pinned rows with RecyclerView item

            // animations. During that interval the old fold-header instance can

            // remain attached while already rebound as an ordinary chat row.

            // Never retain a translated/clickable reference across that reuse.

            restoreAllWeChatFoldBarTranslations()

            weChatFoldBarTarget = null

            hideWeChatFoldTouchProxy()

            return

        }



        val root = host.rootView as? ViewGroup ?: return

        val hostRect = Rect()

        if (!host.getGlobalVisibleRect(hostRect)) return

        val gap = (FOLD_TO_GLASS_GAP_DP * density).roundToInt()

        val targetBottom = hostRect.top - gap



        // Throttle the expensive view-tree search, but update the translation

        // every frame so the fold bar doesn't jump during list scrolling.

        val doSearch = now - lastWeChatFoldBarProbe >= FOLD_BAR_PROBE_INTERVAL_MS

        if (doSearch) lastWeChatFoldBarProbe = now



        val cachedTarget = weChatFoldBarTarget as? ViewGroup

        var target = cachedTarget

            ?.takeIf { it.isAttachedToWindow && it.visibility == View.VISIBLE }

        if (cachedTarget != null && target == null) {

            // A fold/unfold rebuild can detach kh7 and later recycle the same

            // instance as a normal conversation row. Restore even while it is

            // detached; checking isAttachedToWindow here leaves the negative

            // translation on the recycled row and creates permanent blank gaps.

            logWeChatFoldDiag("target LOST(detach/invisible) last=${cachedTarget.javaClass.name}")

            restoreWeChatFoldBarTranslation(cachedTarget)

            weChatFoldBarTarget = null

            hideWeChatFoldTouchProxy()

        }

        if (target != null &&

            (!hasWeChatFoldEdgeControls(target, root.width) || containsChatItemAvatar(target, density))

        ) {

            logWeChatFoldDiag("target LOST(no edge controls) last=${target.javaClass.name}")

            restoreWeChatFoldBarTranslation(target)

            weChatFoldBarTarget = null

            hideWeChatFoldTouchProxy()

            target = null

        }

        if (target == null && doSearch) {

            val minimumWidth = root.width * 0.82f

            val minimumHeight = (FOLD_BAR_MIN_HEIGHT_DP * density).roundToInt()

            val maximumHeight = (FOLD_BAR_MAX_HEIGHT_DP * density).roundToInt()

            var bestScore = Int.MIN_VALUE

            val stack = ArrayDeque<Pair<View, Int>>()

            stack += root to 0

            while (stack.isNotEmpty()) {

                val (view, depth) = stack.removeLast()

                if (view === host || view === source || view === weChatFoldBarTouchProxy) continue

                if (view is ViewGroup && view.visibility == View.VISIBLE &&

                    !isDescendantOf(view, source) &&

                    !isDescendantOf(source ?: host, view) &&

                    !isDescendantOf(host, view)

                ) {

                    val rect = Rect()

                    // Search by text content and dimensions only �?do NOT

                    // constrain by position relative to the glass host,

                    // because once the fold bar is elevated its on-screen

                    // rect moves above the host and would fail a positional

                    // re-search, causing the target to be lost.

                    if (view.alpha > 0.05f && view.getGlobalVisibleRect(rect) &&

                        rect.width() >= minimumWidth &&

                        view.height in minimumHeight..maximumHeight &&

                        rect.height() in minimumHeight..maximumHeight &&

                        hasWeChatFoldEdgeControls(view, root.width) &&

                        !containsChatItemAvatar(view, density)

                    ) {

                        val idName = viewResourceEntryName(view).orEmpty().lowercase()

                        val idBonus = if (FOLD_BAR_ID_MARKERS.any(idName::contains)) 700 else 0

                        val backgroundBonus = if (view.background != null) 180 else 0

                        val score = idBonus + backgroundBonus + rect.width() - rect.height()

                        if (score > bestScore) {

                            bestScore = score

                            target = view

                        }

                    }

                }

                if (view is ViewGroup && depth < 28) {

                    for (index in view.childCount - 1 downTo 0) {

                        stack += view.getChildAt(index) to (depth + 1)

                    }

                }

            }

            if (target != null) {

                val previous = weChatFoldBarTarget

                if (previous != null && previous !== target) {

                    restoreWeChatFoldBarTranslation(previous)

                }

                weChatFoldBarTarget = target

                weChatFoldBarBaseTranslations.getOrPut(target) { target.translationY }

                XposedBridge.log(

                    "[OfflineGlass][WeChatFoldBar] target FOUND=${target.javaClass.name} " +

                        "id=${viewResourceEntryName(target)} size=${target.width}x${target.height} " +

                        "translationY=${target.translationY}",

                )

            }

        }



        val bar = target ?: return

        if (bar.width <= 0 || bar.height <= 0) return



        // Calculate the fold bar's natural bottom (without our translation)

        // and set translationY so its bottom aligns with targetBottom.

        // This runs every frame to track the fold bar's scrolling position

        // without the 120ms jump caused by throttled recalculation.

        val base = weChatFoldBarBaseTranslations.getOrPut(bar) { bar.translationY }

        val location = IntArray(2).also(bar::getLocationInWindow)

        val currentBottom = location[1] + bar.height

        val baseBottom = currentBottom - (bar.translationY - base).roundToInt()

        val wanted = base + (targetBottom - baseBottom)

        if (abs(bar.translationY - wanted) > 0.5f) {

            logWeChatFoldDiag(

                "translate cur=${bar.translationY} wanted=$wanted " +

                    "hostTop=${hostRect.top} targetBottom=$targetBottom",

            )

            bar.translationY = wanted

        }

        var ancestor = bar.parent as? ViewGroup

        repeat(5) {

            ancestor?.clipChildren = false

            ancestor?.clipToPadding = false

            ancestor = ancestor?.parent as? ViewGroup

        }

        updateWeChatFoldTouchProxy(root, bar)

    }

    private fun restoreWeChatFoldBarTranslation(view: View?) {

        if (view == null) return

        val base = weChatFoldBarBaseTranslations.remove(view) ?: 0f

        if (abs(view.translationY - base) > 0.5f) view.translationY = base

    }

    private fun logWeChatFoldDiag(message: String) {

        val now = SystemClock.uptimeMillis()

        if (message == lastWeChatFoldDiagMessage &&

            now - lastWeChatFoldDiagTime < 4000L

        ) return

        lastWeChatFoldDiagMessage = message

        lastWeChatFoldDiagTime = now

        XposedBridge.log("[OfflineGlass][WeChatFoldDiag] $message")

    }

    fun restoreAllWeChatFoldBarTranslations() {

        val tracked = weChatFoldBarBaseTranslations.entries

            .map { (view, base) -> view to base }

        weChatFoldBarBaseTranslations.clear()

        tracked.forEach { (view, base) ->

            if (abs(view.translationY - base) > 0.5f) view.translationY = base

        }

    }

    fun hideWeChatFoldTouchProxy() {

        weChatFoldBarTouchProxy?.apply {

            visibility = View.GONE

            clickTarget = null

            beforeClick = null

        }

    }



    /**

     * WeChat performs hit testing for this strip using its unshifted layout

     * bounds. A transparent top-level proxy mirrors the lifted strip's exact

     * screen rectangle and invokes WeChat's real clickable wrapper. The visible

     * kh7 strip is presentation-only; its j8f ancestor owns the click listener.

     * Never redispatch through the root: the wrapper's original screen position

     * overlaps the liquid navigation host, which would make the navigation bar

     * consume the tap and switch pages instead of folding the pinned chats.

     */

    private fun updateWeChatFoldTouchProxy(root: ViewGroup, bar: ViewGroup) {

        var proxy = weChatFoldBarTouchProxy

        if (proxy == null) {

            proxy = WeChatFoldTouchProxy(root.context)

            weChatFoldBarTouchProxy = proxy

        }

        if (proxy.parent !== root) {

            (proxy.parent as? ViewGroup)?.removeView(proxy)

            root.addView(proxy, ViewGroup.LayoutParams(1, 1))

        }

        proxy.clickTarget = WeChatFoldTouchProxy.findClickTarget(bar)

        proxy.beforeClick = {

            // Native fold/unfold recycles several rows asynchronously. Restore

            // every tracked object (not only the visible header) and retire the

            // proxy before invoking WeChat, then wait for its item animator to

            // settle before discovering the replacement header. This prevents

            // a fast second tap from clicking a View that has become chat #1/#2/#3.

            weChatFoldInteractionHoldUntil = SystemClock.uptimeMillis() +

                FOLD_INTERACTION_SETTLE_MS

            restoreAllWeChatFoldBarTranslations()

            weChatFoldBarTarget = null

            hideWeChatFoldTouchProxy()

        }

        val rootLocation = IntArray(2).also(root::getLocationInWindow)

        val barLocation = IntArray(2).also(bar::getLocationInWindow)

        val left = barLocation[0] - rootLocation[0]

        val top = barLocation[1] - rootLocation[1]

        val params = proxy.layoutParams

        if (params.width != bar.width || params.height != bar.height) {

            params.width = bar.width

            params.height = bar.height

            proxy.layoutParams = params

        }

        // Translation participates in ViewGroup hit testing, unlike manually

        // calling layout(), whose bounds DecorView replaces on its next pass.

        proxy.translationX = left.toFloat()

        proxy.translationY = top.toFloat()

        proxy.visibility = View.VISIBLE

        if (root.indexOfChild(proxy) != root.childCount - 1) proxy.bringToFront()

    }

    private fun hasWeChatFoldEdgeControls(group: ViewGroup, rootWidth: Int): Boolean {

        // Only use text as the detection signal. The fold bar always

        // displays "置顶"/"折叠"/"个聊�? text; chat items never do.

        // Edge-control matching was removed because chat items also have

        // small icons near screen edges, causing false positives.

        return containsFoldBarText(group)

    }

    private fun containsFoldBarText(group: ViewGroup): Boolean {

        val stack = ArrayDeque<Pair<View, Int>>()

        for (index in group.childCount - 1 downTo 0) stack += group.getChildAt(index) to 0

        while (stack.isNotEmpty()) {

            val (view, depth) = stack.removeLast()

            if (view is TextView) {

                val text = view.text?.toString().orEmpty()

                // "个聊天" (not "置顶聊天"): the pinned-chats folder row at the

                // top of the conversation list is also labelled "置顶聊天";

                // matching on it drags that row down onto the liquid bar.

                if (text.contains("折叠") || text.contains("个聊天")) return true

            }

            if (view is ViewGroup && depth < 5) {

                for (index in view.childCount - 1 downTo 0) {

                    stack += view.getChildAt(index) to (depth + 1)

                }

            }

        }

        return false

    }



    /**

     * Chat list items have a large avatar (>= 40dp) on the left side.

     * The fold bar has no such avatar. This negative signal reliably

     * excludes chat items from being mistaken for the fold bar.

     */

    private fun containsChatItemAvatar(group: ViewGroup, density: Float): Boolean {

        val avatarMinPx = (40f * density).roundToInt()

        val stack = ArrayDeque<Pair<View, Int>>()

        for (index in group.childCount - 1 downTo 0) stack += group.getChildAt(index) to 0

        while (stack.isNotEmpty()) {

            val (view, depth) = stack.removeLast()

            if (view is ImageView && view.visibility == View.VISIBLE &&

                view.width >= avatarMinPx && view.height >= avatarMinPx

            ) {

                return true

            }

            if (view is ViewGroup && depth < 5) {

                for (index in view.childCount - 1 downTo 0) {

                    stack += view.getChildAt(index) to (depth + 1)

                }

            }

        }

        return false

    }



    /**

     * YouTube's pivot_bar row is a HorizontalScrollView of Buttons. Its native

     * per-button ripple/pill backgrounds and the scroll view's fading edges are

     * captured by the projection snapshot and would resurface on the glass as

     * grey residue. Strip only container surfaces: the icon/label content of

     * each Button is drawn by the Button itself and stays intact.

     */

    /**

     * Tieba's native bottom strip fights passive suppression: it re-asserts

     * the tab-row background through FragmentTabWidget.setNavigationBarBg on

     * tab switches and parks a full-width nav-bar scrim View below the app

     * root - each resurfacing as the leftover line / colour block around the

     * floating glass panel.

     *

     * v6 tried translating the whole bar shell off-screen (the WeChat

     * recipe) but the geometry change dragged the glass panel's sampling and

     * layout along with it and the bottom bar vanished entirely. v7 keeps

     * the strip exactly where it is - the background restore is neutralised

     * at the source by an Xposed hook on setNavigationBarBg (see

     * TargetHooks.installTiebaNavigationBarBgSuppression) - clears any

     * surfaces that still slip through every pre-draw, hides the scrim, and

     * stretches the window's own view chain to full height so page content,

     * not a flat background, shows behind the glass.

     */

    /**

     * Keep Home's bottom action controls above LiquidTab using the common safe

     * gap. Only the two confirmed floating controls are moved; feed cards and

     * RecyclerView content keep their native geometry.

     */

    private fun viewResourceEntryName(view: View): String? = runCatching {
        if (view.id == View.NO_ID) null else view.resources.getResourceEntryName(view.id)
    }.getOrNull()

    private fun isDescendantOf(view: View, ancestor: View?): Boolean {
        if (ancestor == null) return false
        var current = view.parent
        while (current is View) {
            if (current === ancestor) return true
            current = current.parent
        }
        return false
    }

    fun dispose() {
        weChatFoldBarTouchProxy?.let { proxy ->
            proxy.clickTarget = null
            proxy.beforeClick = null
            (proxy.parent as? ViewGroup)?.removeView(proxy)
        }
        weChatFoldBarTouchProxy = null
        restoreAllWeChatFoldBarTranslations()
        weChatFoldBarTarget = null
    }

}

