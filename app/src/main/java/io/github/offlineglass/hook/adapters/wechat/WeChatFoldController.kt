package io.github.offlineglass.hook.adapters.wechat

import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import io.github.offlineglass.hook.compat.XposedBridge
import kotlin.math.abs
import kotlin.math.roundToInt

/** Owns the folded-pinned-chats lift and touch proxy for one WeChat host. */
internal class WeChatFoldController {
    private var fullWidth = 0
    private val weChatFoldBarBaseTranslations = java.util.WeakHashMap<View, Float>()
    private var managedRoot = java.lang.ref.WeakReference<View>(null)

    private fun nativeVisible(view: View): Boolean {
        if (!view.isShown || !view.getGlobalVisibleRect(Rect())) return false
        var ancestor: View? = view
        while (ancestor != null) {
            if (ancestor.alpha <= 0.05f || ancestor.visibility != View.VISIBLE) return false
            ancestor = ancestor.parent as? View
        }
        return true
    }

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

    }

    fun adjust(host: View, source: ViewGroup?, selected: Int, density: Float, recentVisible: Boolean) {
        (host as? io.github.offlineglass.hook.GlassHostLayout)?.let { glass ->
            val parent = glass.parent as? View
            if (parent != null) fullWidth = io.github.offlineglass.hook.symmetricGlassHostWidthPx(
                parent, density, glass.config, 4)
        }
        if (managedRoot.get() !== host.rootView) {
            managedRoot.get()?.let(WeChatFoldPresentation::release)
            managedRoot = java.lang.ref.WeakReference(host.rootView)
            WeChatFoldPresentation.manage(host.rootView)
        }

        if (host.width <= 0 || host.height <= 0) return
        val planB = (host as? io.github.offlineglass.hook.GlassHostLayout)?.config?.wechatFoldPlanB == true
        val showPlanBButton = planB && selected == 0
        if (!host.isShown || host.alpha <= 0.05f || recentVisible) {
            restoreAllWeChatFoldBarTranslations()
            hideWeChatFoldTouchProxy()
            return
        }

        if (showPlanBButton && host is io.github.offlineglass.hook.GlassHostLayout) {
            val root = host.rootView as? ViewGroup ?: return
            restoreAllWeChatFoldBarTranslations()
            weChatFoldBarTarget = null
            val action = WeChatFoldPresentation.nativeAction(root)
            val proxy = obtainProxy(root)
            proxy.setPlanB(true)
            proxy.configure(host, null)
            proxy.clickTarget = null
            proxy.beforeClick = null
            val collapsed = action?.state()
            proxy.setFoldState(collapsed)
            proxy.togglePinnedChats = if (collapsed != null) action::toggle else null
            positionButton(host, proxy)
            proxy.showAnimated()
            proxy.isEnabled = collapsed != null
            return
        }

        val now = SystemClock.uptimeMillis()

        if (selected != 0 || planB) {

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

        // Throttle the expensive view-tree search, but update the translation

        // every frame so the fold bar doesn't jump during list scrolling.

        val doSearch = now - lastWeChatFoldBarProbe >= FOLD_BAR_PROBE_INTERVAL_MS

        if (doSearch) lastWeChatFoldBarProbe = now



        val cachedTarget = weChatFoldBarTarget as? ViewGroup

        var target = cachedTarget

            ?.takeIf { it.isAttachedToWindow && nativeVisible(it) && WeChatFoldPresentation.isFloating(it) }

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

            target = WeChatFoldPresentation.nativeBanner(root)?.takeIf { nativeVisible(it) }

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



        val bar = target ?: run { hideWeChatFoldTouchProxy(); return }

        if (bar.width <= 0 || bar.height <= 0) return



        // Leave native geometry and clipping untouched: native visibility and
        // scrolling decide whether a replacement button may be presented.

        updateWeChatFoldTouchProxy(root, bar, host)

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

            hideAnimated()

            clickTarget = null
            togglePinnedChats = null

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

    private fun updateWeChatFoldTouchProxy(root: ViewGroup, bar: ViewGroup, host: View) {
        val glass = host as? io.github.offlineglass.hook.GlassHostLayout ?: return

        val proxy = obtainProxy(root)
        proxy.togglePinnedChats = null
        proxy.clickTarget = WeChatFoldTouchProxy.findClickTarget(bar)
        if (proxy.clickTarget == null) { hideWeChatFoldTouchProxy(); return }
        proxy.configure(glass, bar)
        proxy.setPlanB(glass.config.wechatFoldPlanB)

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

        positionButton(glass, proxy)

        proxy.showAnimated()

        if (root.indexOfChild(proxy) != root.childCount - 1) proxy.bringToFront()

    }

    private fun obtainProxy(root: ViewGroup): WeChatFoldTouchProxy {
        val proxy = weChatFoldBarTouchProxy ?: WeChatFoldTouchProxy(root.context).also {
            weChatFoldBarTouchProxy = it
        }
        if (proxy.parent !== root) {
            (proxy.parent as? ViewGroup)?.removeView(proxy)
            root.addView(proxy, ViewGroup.LayoutParams(1, 1))
        }
        if (root.indexOfChild(proxy) != root.childCount - 1) proxy.bringToFront()
        return proxy
    }

    private fun positionButton(host: io.github.offlineglass.hook.GlassHostLayout, proxy: WeChatFoldTouchProxy) {
        val root = proxy.parent as? View ?: return
        val origin = IntArray(2).also(root::getLocationInWindow)
        val location = IntArray(2).also(host::getLocationInWindow)
        val d = root.resources.displayMetrics.density
        val b = proxy.planB
        val w = if (b) host.height else fullWidth
        val h = if (b) host.height else (48f * d).roundToInt()
        val padding = proxy.shadowInset
        val params = proxy.layoutParams
        if (params.width != w + padding * 2 || params.height != h + padding * 2) {
            params.width = w + padding * 2; params.height = h + padding * 2; proxy.layoutParams = params
        }
        val window = host.rootView
        val windowLocation = IntArray(2).also(window::getLocationInWindow)
        val screenLeft = windowLocation[0] + (window.width - fullWidth) / 2
        // Same placement as X's publish button: right-aligned to the full
        // bar, with a 24 dp gap above it. Shadow padding is not part of the gap.
        proxy.translationX = screenLeft - origin[0] + (if (b) fullWidth - w else 0) - padding.toFloat() - proxy.left
        proxy.translationY = location[1] - origin[1] - h - (if (b) 24f else 8f) * d - padding - proxy.top
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
        managedRoot.get()?.let(WeChatFoldPresentation::release)
        managedRoot.clear()
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

