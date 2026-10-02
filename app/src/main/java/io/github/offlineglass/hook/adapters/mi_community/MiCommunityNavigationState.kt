package io.github.offlineglass.hook.adapters.mi_community

import android.content.Context
import android.os.SystemClock
import android.webkit.WebView
import de.robv.android.xposed.XposedBridge
import kotlin.math.abs
import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import de.robv.android.xposed.XposedHelpers
import io.github.offlineglass.hook.adapters.AdapterNavigationFrame
import io.github.offlineglass.hook.adapters.AppNavigationState
import kotlin.math.roundToInt

/** Per-host Xiaomi Community artwork and native item access. */
internal class MiCommunityNavigationState(private val context: Context) : AppNavigationState {
    private val miCommunityFloatingActionBaseTranslations = java.util.WeakHashMap<View, Float>()
    private var lastMiCommunityFabProbe = 0L
    private var miCommunityFabFastUntil = 0L
    private var source: ViewGroup? = null
    private val miCommunityIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val miCommunityLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
    }
    override fun prepare(source: ViewGroup?, schedule: (Long, () -> Unit) -> Unit) {
        this.source = source
    }
    override fun performTap(host: View, source: ViewGroup?, index: Int, slotCount: Int): Boolean {
        this.source = source
        val bottomNav = bottomNav()
        val item = navItems().getOrNull(index)
        if (item?.performClick() == true) return true
        return bottomNav?.let { nav ->
            runCatching {
                XposedHelpers.callMethod(nav, "setCurrentItem", index)
                readInstanceField(nav, "selectedListener")?.let { listener ->
                    XposedHelpers.callMethod(listener, "onNavigationItemSelected", index)
                }
                true
            }.getOrDefault(false)
        } ?: false
    }
    override fun onTapSucceeded(requestRefresh: (Long) -> Unit) {
        requestRefresh(0L)
        requestRefresh(80L)
    }
    override fun resolveSelectedIndex(source: ViewGroup?, slotCount: Int): Int? {
        this.source = source
        val selected = bottomNav()?.let { nav ->
            runCatching { (XposedHelpers.callMethod(nav, "getSelectPosition") as? Number)?.toInt() }
                .getOrNull()
        }
        return selected?.takeIf { it in 0 until slotCount }
    }
    override fun draw(canvas: Canvas, frame: AdapterNavigationFrame) = with(frame) {

        if (slotCount != 5 || width <= 0 || height <= 0) return

        val liveItems = navItems()

        val slotWidth = slotWidth

        val horizontalPadding = 4f * density

        val iconSize = MI_COMMUNITY_ICON_SIZE_DP * density

        val dark = darkGlass

        val selectedColor = if (dark) Color.WHITE else MI_COMMUNITY_SELECTED_LIGHT

        val normalColor = if (dark) MI_COMMUNITY_NORMAL_DARK else MI_COMMUNITY_NORMAL_LIGHT

        val itemScale = iconScale * extraScale

        miCommunityLabelPaint.textSize =

            MI_COMMUNITY_LABEL_TEXT_SP * scaledDensity

        // Build one measured icon-gap-label silhouette and centre that whole

        // group. This mirrors Bilibili while guaranteeing that an enlarged

        // live icon can never consume the label's reserved gap.

        val labelMetrics = miCommunityLabelPaint.fontMetrics

        val labelHeight = labelMetrics.bottom - labelMetrics.top

        val contentGap = MI_COMMUNITY_ICON_LABEL_GAP_DP * density

        val groupHeight = iconSize + contentGap + labelHeight

        // A small optical lift matches the perceived centre used by the

        // Bilibili tabs; keep the measured icon/label geometry intact.

        val groupTop = (height - groupHeight) / 2f - MI_COMMUNITY_CONTENT_LIFT_DP * density

        val iconCenterY = groupTop + iconSize / 2f

        val labelBaseline = groupTop + iconSize + contentGap - labelMetrics.top



        for ((visualIndex, index) in enabledIndices.withIndex()) {

            val selected = index == selectedIndex

            val centerX = horizontalPadding + (visualIndex + 0.5f) * slotWidth

            val color = if (selected) selectedColor else normalColor

            canvas.save()

            canvas.scale(itemScale, itemScale, centerX, height / 2f)



            val liveIcon = liveItems.getOrNull(index)?.let(::findMiCommunityIconView)

            val drawable = liveIcon?.drawable

            if (drawable != null) {

                // Server-delivered menu artwork can differ from the APK's

                // fallback vectors. Draw that exact ImageView drawable into

                // our fixed slot and tint only its alpha, never its layout.

                // The outer drawable is scaled separately because its nested

                // child keeps an intrinsic size. Pre-compensate its lower edge

                // so the scaled result still ends at the measured icon bound.

                val boxBottom = iconCenterY + iconSize / (2f * MI_COMMUNITY_LIVE_ICON_SCALE)

                val boxSize = MI_COMMUNITY_SOURCE_ICON_BOX_DP * density

                val destination = RectF(

                    centerX - boxSize / 2f,

                    boxBottom - boxSize,

                    centerX + boxSize / 2f,

                    boxBottom,

                )

                val layerPaint = miCommunityIconPaint

                layerPaint.colorFilter = BlendModeColorFilter(color, BlendMode.SRC_IN)

                val layerBounds = RectF(

                    centerX + (destination.left - centerX) * MI_COMMUNITY_LIVE_ICON_SCALE,

                    iconCenterY + (destination.top - iconCenterY) * MI_COMMUNITY_LIVE_ICON_SCALE,

                    centerX + (destination.right - centerX) * MI_COMMUNITY_LIVE_ICON_SCALE,

                    iconCenterY + (destination.bottom - iconCenterY) * MI_COMMUNITY_LIVE_ICON_SCALE,

                )

                val layer = canvas.saveLayer(layerBounds, layerPaint)

                val scaledContent = canvas.save()

                // Some server drawables keep an intrinsic-size child inside

                // their bounds. Scaling only the bounds therefore changes no

                // visible pixels; scale the rendered content itself instead.

                canvas.scale(

                    MI_COMMUNITY_LIVE_ICON_SCALE,

                    MI_COMMUNITY_LIVE_ICON_SCALE,

                    centerX,

                    iconCenterY,

                )

                val oldBounds = Rect(drawable.bounds)

                drawable.bounds = Rect(

                    destination.left.roundToInt(),

                    destination.top.roundToInt(),

                    destination.right.roundToInt(),

                    destination.bottom.roundToInt(),

                )

                drawable.draw(canvas)

                drawable.bounds = oldBounds

                canvas.restoreToCount(scaledContent)

                canvas.restoreToCount(layer)

                layerPaint.colorFilter = null

            } else {

                miCommunityIconPaint.color = color

                miCommunityIconPaint.alpha = 255

                canvas.save()

                canvas.translate(centerX - iconSize / 2f, iconCenterY - iconSize / 2f)

                val vectorScale = iconSize / MI_COMMUNITY_ICON_VIEWPORT

                canvas.scale(vectorScale, vectorScale)

                canvas.drawPath(MiCommunityArtwork.paths[index], miCommunityIconPaint)

                canvas.restore()

            }



            miCommunityLabelPaint.color = color

            miCommunityLabelPaint.alpha = 255

            canvas.drawText(

                MiCommunityArtwork.labels[index],

                centerX,

                labelBaseline,

                miCommunityLabelPaint,

            )

            canvas.restore()

        }

        miCommunityIconPaint.colorFilter = null

    }




    /** Keep Xiaomi Community's controller alive while removing its complete shell. */

    override fun suppressNativeChrome(source: ViewGroup?) {
        this.source = source

        if (source == null) return

        val bottomNav = bottomNav() ?: source

        if (source.alpha != 0f) source.alpha = 0f

        if (bottomNav.alpha != 0f) bottomNav.alpha = 0f

        source.background = null

        bottomNav.background = null

        bottomNav.clipChildren = false

        bottomNav.clipToPadding = false



        // HomeFrameActivity deliberately leaves 56 dp below its ViewPager for

        // the stock navigation. Once the stock shell is transparent that

        // reserved strip becomes the solid rectangle visible below the glass.

        // Extend only Xiaomi Community's pager into that strip; the retained

        // BottomNavView still owns selection and lifecycle callbacks.

        (bottomNav.parent as? ViewGroup)?.let { container ->

            container.clipChildren = false

            container.clipToPadding = false

            for (index in 0 until container.childCount) {

                val sibling = container.getChildAt(index)

                if (resourceEntryName(sibling) == "pager") {

                    val params = sibling.layoutParams as? ViewGroup.MarginLayoutParams

                    if (params != null && params.bottomMargin != 0) {

                        params.bottomMargin = 0

                        sibling.layoutParams = params

                        sibling.requestLayout()

                    }

                }

            }

        }



        // The pager's own margin is only half the story: content_layout (the

        // pager's parent, direct child of android:id/content) is itself inset

        // by the navigation-bar band and ends ~65 px above the window bottom.

        // With the feed cut there, the window background shows through as the

        // solid block under the floating glass bar (the "elevated pill" band).

        // Restore full-bleed height the same way Meituan Takeout's

        // pager_container is treated. The glass host is a content sibling

        // anchored by a screen-relative margin, so the bar's position is

        // untouched; the suppressed BottomNavView slides down with the layout

        // but painting, clicks (performClick) and selection

        // (getSelectPosition) are all position-independent.

        var extender = bottomNav.parent as? ViewGroup

        var extenderGuard = 0

        while (extender != null && extender.id != android.R.id.content && extenderGuard++ < 6) {

            val extenderParent = extender.parent as? ViewGroup

            if (extenderParent != null && extenderParent.id == android.R.id.content) {

                val params = extender.layoutParams as? ViewGroup.MarginLayoutParams

                if (params != null) {

                    var changed = false

                    if (params.height != ViewGroup.LayoutParams.MATCH_PARENT) {

                        params.height = ViewGroup.LayoutParams.MATCH_PARENT

                        changed = true

                    }

                    if (params.bottomMargin != 0) {

                        params.bottomMargin = 0

                        changed = true

                    }

                    if (changed) {

                        extender.layoutParams = params

                        extender.requestLayout()

                        extenderParent.requestLayout()

                        android.util.Log.i(

                            "McGlassDiag",

                            "contentLayout full-bleed applied h=" + params.height +

                                " bottomMargin=" + params.bottomMargin,

                        )

                    }

                }

            }

            extender = extender.parent as? ViewGroup

        }



        val stack = ArrayDeque<Pair<View, Int>>()

        stack += bottomNav to 0

        while (stack.isNotEmpty()) {

            val (view, depth) = stack.removeLast()

            if (resourceEntryName(view) == "bg") {

                view.visibility = View.INVISIBLE

                view.background = null

            }

            if (view is ViewGroup && depth < 4) {

                view.clipChildren = false

                view.clipToPadding = false

                for (index in view.childCount - 1 downTo 0) {

                    stack += view.getChildAt(index) to (depth + 1)

                }

            }

        }

        var parent = bottomNav.parent as? ViewGroup

        repeat(3) {

            parent?.clipChildren = false

            parent?.clipToPadding = false

            parent = parent?.parent as? ViewGroup

        }

    }



    /** Keep Xiaomi Home's tab controller, but remove its 60 dp visual shell. */


    private fun resourceEntryName(view: View): String? = runCatching {
        if (view.id == View.NO_ID) null else view.resources.getResourceEntryName(view.id)
    }.getOrNull()
    private fun readInstanceField(instance: Any, name: String): Any? {
        var type: Class<*>? = instance.javaClass
        while (type != null) {
            val field = runCatching { type.getDeclaredField(name) }.getOrNull()
            if (field != null) {
                field.isAccessible = true
                return runCatching { field.get(instance) }.getOrNull()
            }
            type = type.superclass
        }
        return null
    }
    private fun bottomNav(): ViewGroup? {
        var ancestor: View? = source
        repeat(8) {
            if (ancestor?.javaClass?.name == BOTTOM_NAV_CLASS ||
                ancestor?.let(::resourceEntryName) == "tab_indicator") return ancestor as? ViewGroup
            ancestor = ancestor?.parent as? View
        }
        val root = source ?: return null
        val stack = ArrayDeque<Pair<View, Int>>()
        stack += root to 0
        while (stack.isNotEmpty()) {
            val (view, depth) = stack.removeLast()
            if (view.javaClass.name == BOTTOM_NAV_CLASS || resourceEntryName(view) == "tab_indicator")
                return view as? ViewGroup
            if (view is ViewGroup && depth < 7) {
                for (index in view.childCount - 1 downTo 0) stack += view.getChildAt(index) to (depth + 1)
            }
        }
        return null
    }
    private fun navItems(): List<View> {
        val bottomNav = bottomNav() ?: return emptyList()
        val direct = (readInstanceField(bottomNav, "navItems") as? List<*>)
            ?.filterIsInstance<View>().orEmpty()
        if (direct.size >= 5) return direct.take(5)
        return (0 until bottomNav.childCount).map(bottomNav::getChildAt)
            .filter { it.javaClass.name == NAV_ITEM_CLASS }.take(5)
    }
    private fun findMiCommunityIconView(item: View): ImageView? {
        val stack = ArrayDeque<Pair<View, Int>>()
        stack += item to 0
        while (stack.isNotEmpty()) {
            val (view, depth) = stack.removeLast()
            if (view is ImageView && resourceEntryName(view) == "nav_item_icon") return view
            if (view is ViewGroup && depth < 4) {
                for (index in view.childCount - 1 downTo 0) stack += view.getChildAt(index) to (depth + 1)
            }
        }
        return null
    }
    override fun adjustFloatingActions(host: View, source: ViewGroup?) {
        this.source = source
        val density = host.resources.displayMetrics.density

        if (!host.isAttachedToWindow) return

        val now = SystemClock.uptimeMillis()

        val throttledProbeDue = now - lastMiCommunityFabProbe >= MI_COMMUNITY_FAB_PROBE_INTERVAL_MS

        if (now < miCommunityFabFastUntil) {

            // Fresh change: re-check every frame so the settle frame of a page

            // slide / FAB entrance animation is never skipped by the throttle.

        } else if (!throttledProbeDue) {

            return

        }

        lastMiCommunityFabProbe = now

        val root = host.rootView as? ViewGroup ?: return

        val hostLocation = IntArray(2).also(host::getLocationOnScreen)

        val hostTop = hostLocation[1]

        if (hostTop <= 0 || host.width <= 0 || host.height <= 0) return

        val bottomGap = ((host.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0)

            .coerceAtLeast((12f * density).roundToInt())

        val desiredBottom = hostTop - bottomGap

        // 32-80dp covers a bare FAB as well as the FAB+badge wrapper some

        // board pages use (pencil compose button with a draft count).

        val minimumSize = (32f * density).roundToInt()

        val maximumSize = (80f * density).roundToInt()

        val rightEdge = root.width * 0.68f

        val touched = HashSet<View>()

        val webViews = ArrayList<android.webkit.WebView>()

        

        val stack = ArrayDeque<Pair<View, Int>>()

        stack += root to 0

        while (stack.isNotEmpty()) {

            val (view, depth) = stack.removeLast()

            if (view === host || view === source) continue

            if (view.visibility != View.VISIBLE || view.alpha <= 0f) continue

            if (view is android.webkit.WebView) {

                webViews += view

            }



            val compact = view.width in minimumSize..maximumSize &&

                view.height in minimumSize..maximumSize

            if (compact && view.isClickable && view.isShown) {

                val location = IntArray(2).also(view::getLocationOnScreen)

                val base = miCommunityFloatingActionBaseTranslations.getOrPut(view) { view.translationY }

                val appliedDelta = view.translationY - base

                val originalBottom = location[1] + view.height - appliedDelta

                val originalTop = originalBottom - view.height

                val rightAligned = location[0] + view.width / 2f >= rightEdge

                val intersectsSafetyZone = originalBottom > desiredBottom && originalTop < hostTop + host.height

                if (rightAligned && intersectsSafetyZone) {

                    val lift = originalBottom - desiredBottom

                    val wantedTranslation = base - lift

                    if (abs(view.translationY - wantedTranslation) > 0.5f) {

                        view.translationY = wantedTranslation

                        miCommunityFabFastUntil = now + 1500L

                    }

                    touched += view

                    var ancestor = view.parent as? ViewGroup

                    repeat(4) {

                        ancestor?.clipChildren = false

                        ancestor?.clipToPadding = false

                        ancestor = ancestor?.parent as? ViewGroup

                    }

                }

            }

            if (view is ViewGroup && depth < 40) {

                for (index in view.childCount - 1 downTo 0) {

                    stack += view.getChildAt(index) to (depth + 1)

                }

            }

        }



        miCommunityFloatingActionBaseTranslations.entries.toList().forEach { (view, base) ->

            if (view !in touched && view.isAttachedToWindow && view.translationY != base) {

                view.translationY = base

            }

        }



        // H5 sub-pages (every forum board page except the native 热搜/推荐

        // feeds) render the compose button inside the WebView DOM �?there is

        // no Android view to translate. All of them share the mifans

        // framework's #addPost id, so one script covers every current and

        // future sub-page. v197 rewrote the element's inline transform every

        // probe with the residual offset; on returning from the compose page

        // the framework re-runs its entrance animation (inline transform

        // writes each frame), so the two writers fought at probe cadence and

        // the residual math �?which assumed no pre-existing transform �?        // converged to a wrong target: the button bounced and ended

        // un-lifted. Now the script derives the transform-free layout bottom

        // by subtracting the current computed transform, computes the

        // absolute target once, and publishes it as a document-level

        // stylesheet rule. A static !important rule beats the framework's

        // per-frame inline writes (nothing to fight over) and survives

        // element re-mounts: an SPA route back re-applies the lift from the

        // first paint, before the next probe even runs. Run only on

        // throttled probes so the post-lift fast window stays native-only.

        if (!throttledProbeDue) return

        for (webView in webViews) {

            val rect = Rect()

            if (!webView.getGlobalVisibleRect(rect) || rect.height() <= 0) continue

            if (rect.bottom <= desiredBottom) continue

            val script = "(function(){try{var el=document.getElementById('addPost');" +

                "if(!el)return 'no-el';var vh=window.innerHeight;if(!vh)return 'no-vh';" +

                "var k=vh/${rect.height()},want=(${desiredBottom - rect.top})*k," +

                "r=el.getBoundingClientRect();if(r.height<=0)return 'hidden';" +

                "var cur=0;try{var t=getComputedStyle(el).transform;" +

                "if(t&&t!=='none'){if(t.indexOf('matrix3d')===0)" +

                "cur=parseFloat(t.slice(9,-1).split(',')[13]);" +

                "else if(t.indexOf('matrix')===0)" +

                "cur=parseFloat(t.slice(7,-1).split(',')[5]);}}catch(e){}" +

                "var need=want-(r.bottom-cur);" +

                "if(!isFinite(need)||need>-1){" +

                "var s0=document.getElementById('liquidtab-lift');" +

                "if(s0&&need>4){s0.parentNode.removeChild(s0);" +

                "el.style.removeProperty('transform');return 'reset'}return 'ok'}" +

                "var v=Math.round(need*10)/10," +

                "rule='#addPost{transform:translateY('+v+'px)!important}'," +

                "s=document.getElementById('liquidtab-lift');" +

                "if(!s){s=document.createElement('style');s.id='liquidtab-lift';" +

                "(document.head||document.documentElement).appendChild(s);" +

                "s.textContent=rule;el.style.removeProperty('transform');" +

                "return 'lift:'+Math.round(-need);}" +

                "if(s.textContent!==rule){s.textContent=rule;" +

                "el.style.removeProperty('transform');return 'lift:'+Math.round(-need);}" +

                "return 'ok';}catch(e){return 'err';}})()"

            runCatching {

                webView.evaluateJavascript(script) { result ->

                    if (result != null && result.startsWith("\"lift:")) {

                        XposedBridge.log("[McFabH5] addPost $result")

                    }

                }

            }

        }

    }




    private companion object {
        const val MI_COMMUNITY_FAB_PROBE_INTERVAL_MS = 160L
        const val BOTTOM_NAV_CLASS = "com.xiaomi.vipaccount.ui.widget.tab.BottomNavView"
        const val NAV_ITEM_CLASS = "com.xiaomi.vipaccount.ui.widget.tab.NavItemView"
        const val MI_COMMUNITY_ICON_SIZE_DP = 32f
        const val MI_COMMUNITY_ICON_LABEL_GAP_DP = 3f
        const val MI_COMMUNITY_CONTENT_LIFT_DP = 1.5f
        const val MI_COMMUNITY_LABEL_TEXT_SP = 11f
        const val MI_COMMUNITY_ICON_VIEWPORT = 24f
        const val MI_COMMUNITY_SOURCE_ICON_BOX_DP = 48f
        const val MI_COMMUNITY_LIVE_ICON_SCALE = 1.35f
        const val MI_COMMUNITY_SELECTED_LIGHT = 0xFF0E151C.toInt()
        const val MI_COMMUNITY_NORMAL_LIGHT = 0xFF6F7377.toInt()
        const val MI_COMMUNITY_NORMAL_DARK = 0xFFD9D9D9.toInt()
    }
}
