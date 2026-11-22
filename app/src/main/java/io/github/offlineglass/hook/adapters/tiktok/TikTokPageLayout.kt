package io.github.offlineglass.hook.adapters.tiktok

import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.adapters.AdapterNavigationSearch
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import java.util.WeakHashMap
import java.lang.reflect.Field
import kotlin.math.roundToInt

/** The feed contract is the native pager, touch canvas and action hierarchy. */
internal class TikTokPageLayout {
    companion object {
        private val assistantViews = WeakHashMap<View, Boolean>()

        fun observeAssistantInflation() {
            XposedBridge.hookMethod(ViewStub::class.java.getMethod("inflate"), object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val stub = param.thisObject as? ViewStub ?: return
                    if (stub.context.packageName != "com.zhiliaoapp.musically") return
                    val inflated = param.result as? View ?: return
                    when (AdapterNavigationSearch.resourceEntryName(stub)) {
                        "vs_tako_entrance" -> assistantViews[inflated] = false
                        "vs_tako_entrance_fixed" -> assistantViews[inflated] = true
                    }
                }
            })
        }
    }
    private data class Saved(
        var height: Int, var bottom: Int?, var weight: Float?, var padding: Int,
        var writtenHeight: Int? = null, var writtenBottom: Int? = null,
        var writtenWeight: Float? = null, var writtenPadding: Int? = null,
        var top: Int? = null, var writtenTop: Int? = null,
    )
    private val saved = WeakHashMap<View, Saved>()
    private var root: View? = null
    private var pager: ViewGroup? = null
    private var reservation: View? = null
    private var feedColumn: LinearLayout? = null
    private var strips: List<View> = emptyList()
    private var cells: List<View> = emptyList()
    private data class ProgressBinding(
        val wrapper: View, val bar: android.widget.SeekBar, val shade: View?, val accessories: List<View>,
    )
    private data class ControlGroup(val controls: List<View>, val page: View?)
    private data class Translation(var nativeY: Float, var appliedY: Float)
    private val controlTranslations = WeakHashMap<View, Translation>()
    private val seekShadeAlpha = WeakHashMap<View, Float>()
    private var progress: List<ProgressBinding> = emptyList()
    private val trackRectFields = HashMap<Class<*>, Field?>()
    private var mixedControls: List<ControlGroup> = emptyList()
    private var mediaSpacers: List<View> = emptyList()
    private var liveMedia: List<View> = emptyList()
    private var dirty = true
    private var lastScan = 0L
    private var pagerSignature = 0
    private val controlBottomAnchors = WeakHashMap<View, List<View>>()
    private var diagnostic = "not scanned"
    private val listener = android.view.ViewTreeObserver.OnGlobalLayoutListener { dirty = true }

    fun overlayVisible(root: View?): Boolean = root != null &&
        (TikTokAssistantVisibility.visible(root) || TikTokCommentVisibility.visible(root))

    fun update(host: View, row: ViewGroup, selected: Int) {
        if (selected !in 0..1 || overlayVisible(host.rootView)) { restore(); return }
        val window = host.rootView
        if (root !== window) {
            root?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(listener)
            restore()
            root = window
            window.viewTreeObserver.addOnGlobalLayoutListener(listener)
            dirty = true
        }
        val now = SystemClock.uptimeMillis()
        val boundPager = pager
        if (boundPager?.isAttachedToWindow != true || childSignature(boundPager) != pagerSignature ||
            (dirty && now-lastScan >= 250L) || now-lastScan >= 1500L) {
            dirty = false; lastScan = now
            bindFeed(window)
        }
        val feed = pager?.takeIf { it.isAttachedToWindow && it.rootView === window } ?: return
        val parent = host.parent as? View ?: return
        val parentPosition = IntArray(2).also(parent::getLocationInWindow)
        val column = feedColumn ?: return
        val position = IntArray(2).also(column::getLocationInWindow)
        val hostPosition = IntArray(2).also(host::getLocationInWindow)
        val windowPosition = IntArray(2).also(window::getLocationInWindow)
        // Match Douyin's measured gap at rest. Keep that spacing when the
        // bar is translated so the controls follow its movement one-for-one.
        val layoutBottom = parentPosition[1] + host.bottom - parent.scrollY
        val windowBottom = windowPosition[1] + window.height
        val bottomGap = (windowBottom - layoutBottom).coerceAtLeast(0)
        val controlsGap = 2 * (bottomGap / 2)
        val controlsClearance = hostPosition[1] - controlsGap
        // Like Douyin, measure the track from the floating bar and use half
        // the space below the bar as the clearance above it.
        val progressGap = (windowBottom - hostPosition[1] - host.height).coerceAtLeast(0) / 2
        val progressClearance = hostPosition[1] - progressGap
        progress.forEach { binding ->
            binding.shade?.takeIf { it.isAttachedToWindow }?.let { shade ->
                seekShadeAlpha.getOrPut(shade) { shade.alpha }
                if (shade.alpha != 0f) shade.alpha = 0f
            }
            val target = binding.wrapper
            val seek = binding.bar
            if (!target.isAttachedToWindow || !seek.isShown) {
                binding.accessories.forEach(::restoreTranslation)
                return@forEach
            }
            if (target.isLayoutRequested) return@forEach
            val p = target.layoutParams as? ViewGroup.MarginLayoutParams ?: return@forEach
            val xy = IntArray(2).also(seek::getLocationInWindow)
            val delta = xy[1] + progressTrackBottom(seek) - progressClearance
            val margin = (p.bottomMargin+delta).coerceAtLeast(0)
            val original = remember(target)
            geometry(target, original.height, margin)
            val accessoryShift = (original.bottom ?: p.bottomMargin) - margin
            binding.accessories.forEach { accessory ->
                if (accessory.isAttachedToWindow && accessory.isShown) translateControl(accessory, accessoryShift.toFloat())
                else restoreTranslation(accessory)
            }
        }
        mediaSpacers.forEach { if (it.isAttachedToWindow) geometry(it, 0, 0) }
        liveMedia.forEach { view ->
            if (!view.isAttachedToWindow) return@forEach
            val p = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return@forEach
            val state = remember(view)
            if (state.writtenTop == null) state.top = p.topMargin
            geometry(view, ViewGroup.LayoutParams.MATCH_PARENT, 0)
            TikTokControlMarginGuard.ownGeometry(view, ViewGroup.LayoutParams.MATCH_PARENT, 0, null, 0)
            if (p.topMargin != 0) { p.topMargin = 0; view.layoutParams = p }
            state.writtenTop = 0
        }
        reservation?.takeIf { it.isAttachedToWindow }?.let { geometry(it, 0, 0) }
        clearBottomPadding(feed)
        cells.forEach { cell ->
            if (cell.isAttachedToWindow) {
                geometry(cell, ViewGroup.LayoutParams.MATCH_PARENT, 0)
                clearBottomPadding(cell)
            }
        }
        val location = IntArray(2)
        val feedTop = feed.getLocationInWindow(location).let { location[1] }
        mixedControls.forEach { group ->
            if (group.page == null && TikTokFeedVisibility.isVisible(window) == false) {
                group.controls.forEach(::restoreTranslation)
                return@forEach
            }
            val pageOffset = group.page?.let { page ->
                page.getLocationInWindow(location)
                location[1] - feedTop
            } ?: 0
            val visibleControls = group.controls.filter { it.isAttachedToWindow && it.isShown && it.height > 0 }
            val bottom = visibleControls.maxOfOrNull { control ->
                control.getLocationInWindow(location)
                val previous = controlTranslations[control]?.takeIf { it.appliedY == control.translationY }
                location[1] + control.height - pageOffset -
                    (previous?.let { it.appliedY.roundToInt() - it.nativeY.roundToInt() } ?: 0)
            }
            val shift = bottom?.let { (controlsClearance - it).coerceAtMost(0).toFloat() } ?: 0f
            group.controls.forEach { control ->
                if (control in visibleControls) translateControl(control, shift) else restoreTranslation(control)
            }
        }
        strips.forEach { overlay ->
            if (!overlay.isAttachedToWindow || overlay.isLayoutRequested) return@forEach
            val params = overlay.layoutParams as? ViewGroup.MarginLayoutParams ?: return@forEach
            val page = feedPage(overlay, feed) ?: return@forEach
            page.getLocationInWindow(location)
            val pageOffset = location[1] - feedTop
            // Use the actual control edge. Some native overlays already leave
            // 47–49dp below their controls inside a full-height container.
            val nativeBottom = controlBottomAnchors[overlay].orEmpty()
                .filter { it.isShown && it.height > 0 && !it.isLayoutRequested }
                .maxOfOrNull { anchor ->
                    anchor.getLocationInWindow(location)
                    location[1] + anchor.height + params.bottomMargin - pageOffset
                }
            val lift = if (nativeBottom != null) (nativeBottom - controlsClearance).coerceAtLeast(0)
                else (position[1] + column.height - controlsClearance).coerceAtLeast(0)
            val height = remember(overlay).height.let {
                if (it == ViewGroup.LayoutParams.WRAP_CONTENT) it else ViewGroup.LayoutParams.MATCH_PARENT
            }
            TikTokControlMarginGuard.ownGeometry(overlay, height, lift, null)
            geometry(overlay, height, lift)
        }
    }

    private fun progressTrackBottom(seek: android.widget.SeekBar): Int {
        val drawable = seek.progressDrawable ?: return seek.height
        val bounds = drawable.bounds
        if (bounds.isEmpty) return seek.height
        // Native TikTok's draw() leaves its unique reusable RectF holding
        // the filled track. Its Drawable bounds include extra vertical space.
        val type = drawable.javaClass
        if (!trackRectFields.containsKey(type)) {
            trackRectFields[type] = runCatching {
                type.getDeclaredMethod("draw", android.graphics.Canvas::class.java)
                type.declaredFields.singleOrNull {
                    it.type == RectF::class.java && !java.lang.reflect.Modifier.isStatic(it.modifiers)
                }?.apply { isAccessible = true }
            }.getOrNull()
        }
        val field = trackRectFields[type]
        val ink = runCatching { field?.get(drawable) as? RectF }.getOrNull()?.takeIf {
            it.left == 0f && it.right in 0f..bounds.width().toFloat() &&
                it.top >= bounds.top && it.bottom <= bounds.bottom && it.bottom > it.top
        }
        return (seek.paddingTop + (ink?.bottom?.roundToInt() ?: bounds.bottom)).coerceIn(0, seek.height)
    }
    private fun childSignature(view: ViewGroup): Int {
        var value = view.childCount
        for (i in 0 until view.childCount) value = value * 31 + System.identityHashCode(view.getChildAt(i))
        return value
    }
    private fun bindFeed(window: View) {
        val previousPager = pager
        val previousReservation = reservation
        val previousColumn = feedColumn
        val previousCells = cells
        val previousStrips = strips
        pager = null; reservation = null; feedColumn = null
        strips = emptyList(); cells = emptyList()
        diagnostic = "no feed pager"
        val stack = ArrayDeque<View>()
        stack.add(window)
        var visited = 0
        val pagerCandidates = ArrayList<String>()
        while (stack.isNotEmpty() && visited++ < 8000) {
            val view = stack.removeLast()
            if (view is GlassHostLayout || view.visibility == View.GONE) continue
            if (AdapterNavigationSearch.resourceEntryName(view) == "viewpager" && pagerCandidates.size < 8)
                pagerCandidates += "${view.javaClass.name}:${view.width}x${view.height}:pager=${isPager(view)}"
            if (view is ViewGroup && isPager(view) &&
                view.width >= window.width * .8f && view.height >= window.height * .7f) {
                val roots = ArrayList<View>()
                val anchors = ArrayList<View>()
                val seekBars = ArrayList<View>()
                val spacers = ArrayList<View>()
                val searchLabels = ArrayList<TextView>()
                val suggestionId = view.resources.getIdentifier("tv_search_sug_word", "id", view.context.packageName)
                var widgets = 0
                val descendants = ArrayDeque<Pair<View, Int>>()
                descendants.add(view to 0)
                var count = 0
                while (descendants.isNotEmpty() && count++ < 3000) {
                    val (child, depth) = descendants.removeLast()
                    if (child is android.widget.SeekBar) seekBars += child
                    if (suggestionId != 0 && child.id == suggestionId && child is TextView) searchLabels += child
                    if (child is LinearLayout && child.orientation == LinearLayout.VERTICAL &&
                        AdapterNavigationSearch.resourceEntryName(child) in setOf("video_container_area", "video_sticker_container_area")) {
                        val weighted = (0 until child.childCount).map(child::getChildAt).filter {
                            AdapterNavigationSearch.resourceEntryName(it) in setOf("video_visible_area_container", "video_visible_area_container_sticker")
                        }
                        if (weighted.size == 1) for (i in 0 until child.childCount) {
                            val spacer = child.getChildAt(i)
                            if (spacer.javaClass == View::class.java && !spacer.isClickable) spacers += spacer
                        }
                    }
                    if (AdapterNavigationSearch.resourceEntryName(child) == "view_rootview") {
                        roots += child
                        anchors.addAll(findLiveInteractionLayers(child as? ViewGroup))
                    }
                    if (AdapterNavigationSearch.resourceEntryName(child) == "widget_container" &&
                        child is ViewGroup && child.childCount > 0) {
                        widgets++
                        anchors.add(child)
                    }
                    if (child is ViewGroup && depth < 12) for (i in 0 until child.childCount)
                        descendants.add(child.getChildAt(i) to depth+1)
                }
                pagerCandidates += "feed:${view.width}x${view.height}:roots=${roots.size}:widgets=$widgets:strips=${anchors.size}:depthCount=$count"
                val nativeReservation = findNativeReservation(view)
                if (roots.isNotEmpty() && anchors.isNotEmpty() && nativeReservation != null) {
                    diagnostic = pagerCandidates.joinToString()
                    pager = view; cells = roots
                    TikTokFeedVisibility.bind(window, view)
                    val previousProgress = progress
                    progress = findFeedProgress(window, view)
                    previousProgress.forEach { previous ->
                        if (progress.none { it.wrapper === previous.wrapper }) {
                            saved.remove(previous.wrapper)?.let { restoreGeometry(previous.wrapper, it) }
                        }
                        previous.shade?.takeIf { shade -> progress.none { it.shade === shade } }?.let { shade ->
                            seekShadeAlpha.remove(shade)?.let { if (shade.alpha == 0f) shade.alpha = it }
                        }
                    }
                    val seekViews = progress.flatMap { listOf(it.wrapper) + it.accessories + listOfNotNull(it.shade) } + seekBars
                    val overlayRoots = anchors.distinct().filter { candidate ->
                        anchors.none { it !== candidate && isInside(candidate, it) }
                    }
                    val stacked = overlayRoots.filterIsInstance<RelativeLayout>().mapNotNull { overlay ->
                        val page = feedPage(overlay, view) ?: return@mapNotNull null
                        val controls = findStackedControls(overlay).filter { control ->
                            seekViews.none { isInside(it, control) || isInside(control, it) }
                        }
                        if (controls.isEmpty()) null else overlay to ControlGroup(controls, page)
                    }.toMap()
                    val (mixed, independent) = overlayRoots.filterNot(stacked::containsKey).partition { candidate ->
                        seekViews.any { isInside(it, candidate) }
                    }
                    strips = independent
                    previousStrips.filterNot(strips::contains).forEach { old ->
                        saved.remove(old)?.let { restoreGeometry(old, it) }
                    }
                    controlBottomAnchors.clear()
                    val assistants = assistantViews.filterKeys { it.isAttachedToWindow && it.rootView === window }
                    val searchBars = roots.filterIsInstance<ViewGroup>().mapNotNull { cell ->
                        searchLabels.mapNotNull { findSearchRecommendation(cell, it) }.distinct().singleOrNull()
                    }.distinct()
                    val pageControls = searchBars + assistants.filterValues { !it }.keys.filter { isInside(it, view) }
                    strips.filterIsInstance<ViewGroup>().forEach { overlay ->
                        controlBottomAnchors[overlay] = findControlAnchors(overlay) +
                            pageControls.filter { isInside(it, overlay) }
                    }
                    val mixedBound = stacked.values.flatMap { it.controls }.toHashSet()
                    mixedControls = stacked.values.toList() + mixed.filterIsInstance<ViewGroup>().mapNotNull { overlay ->
                        val page = feedPage(overlay, view) ?: return@mapNotNull null
                        val controls = (findControlAnchors(overlay) + pageControls.filter { isInside(it, overlay) })
                            .distinct().filter { control ->
                                seekViews.none { isInside(it, control) || isInside(control, it) }
                            }
                        val outer = controls.filter { candidate ->
                            controls.none { it !== candidate && isInside(candidate, it) }
                        }
                        mixedBound.addAll(outer)
                        if (outer.isEmpty()) null else ControlGroup(outer, page)
                    } + pageControls.distinct().mapNotNull { control ->
                        if (strips.any { isInside(control, it) } || mixedBound.any { isInside(control, it) } ||
                            seekViews.any { isInside(it, control) || isInside(control, it) }) return@mapNotNull null
                        feedPage(control, view)?.let { ControlGroup(listOf(control), it) }
                    } + listOfNotNull(assistants.filterValues { it }.keys.singleOrNull { assistant ->
                        // b8c/b8d: fixed button is a sibling of the verified feed column.
                        assistant.parent === nativeReservation.second.parent
                    }?.let { ControlGroup(listOf(it), null) })
                    val translated = (progress.flatMap { it.accessories } + mixedControls.flatMap { it.controls }).toSet()
                    controlTranslations.keys.toList().filterNot(translated::contains).forEach(::restoreTranslation)
                    liveMedia = roots.filterIsInstance<ViewGroup>().flatMap { cell ->
                        val nickname = cell.resources.getIdentifier("tv_live_nickname", "id", cell.context.packageName)
                        val tips = cell.resources.getIdentifier("tv_live_tips", "id", cell.context.packageName)
                        if (nickname == 0 || tips == 0 || cell.findViewById<View>(nickname) == null || cell.findViewById<View>(tips) == null)
                            return@flatMap emptyList<View>()
                        val layers = ArrayList<View>()
                        val queue = ArrayDeque<View>()
                        queue.add(cell)
                        var n = 0
                        while (queue.isNotEmpty() && n++ < 1500) {
                            val v = queue.removeLast()
                            if (v is android.view.SurfaceView || v is android.view.TextureView) {
                                var parent = v.parent as? View
                                while (parent != null && parent !== cell) { layers += parent; parent = parent.parent as? View }
                            } else if (v is ViewGroup) for (i in 0 until v.childCount) queue.add(v.getChildAt(i))
                        }
                        layers.distinct()
                    }
                    pagerSignature = childSignature(view)
                    reservation = nativeReservation.first; feedColumn = nativeReservation.second
                    val media = (view.parent as? FrameLayout)?.takeIf { it.parent === feedColumn } ?: view
                    val before = feedColumn!!.indexOfChild(media)
                    val topSpacers = (0 until before).map(feedColumn!!::getChildAt).filter {
                        it.javaClass == View::class.java && !it.isClickable && !it.isFocusable
                    }
                    mediaSpacers = spacers + topSpacers.takeIf { it.size == 1 }.orEmpty() + findFeedBottomDecoration(view)
                    if (previousPager !== view) android.util.Log.i("TikTokGlassDiag",
                        "feed bound pages=${roots.size} reservation=${reservation?.height} column=${feedColumn?.height}")
                    return
                }
            }
            if (view is ViewGroup) for (i in view.childCount-1 downTo 0) stack.add(view.getChildAt(i))
        }
        if (previousPager?.isAttachedToWindow == true && previousPager.rootView === window &&
            (previousStrips.any { it.isAttachedToWindow } || mixedControls.any { group ->
                group.controls.any { it.isAttachedToWindow }
            })) {
            pager = previousPager; reservation = previousReservation; feedColumn = previousColumn
            cells = previousCells; strips = previousStrips
            pagerSignature = childSignature(previousPager)
        }
        diagnostic = "visited=$visited candidates=$pagerCandidates"
    }
    private fun feedPage(view: View, feed: ViewGroup): View? {
        var page = view
        while (page.parent is View && page.parent !== feed) page = page.parent as View
        return page.takeIf { it.parent === feed }
    }

    private fun findStackedControls(overlay: RelativeLayout): List<View> {
        // The feed interaction layout places its main controls above a
        // separate bottom row. Use the native relative rules even when the
        // app uses its optimized RelativeLayout implementation.
        if (AdapterNavigationSearch.resourceEntryName(overlay) != "widget_container") return emptyList()
        val res = overlay.resources
        val pkg = overlay.context.packageName
        val top = uniqueChild(overlay, res.getIdentifier("top_widget_container", "id", pkg))
            as? RelativeLayout ?: return emptyList()
        val topParams = top.layoutParams as? RelativeLayout.LayoutParams ?: return emptyList()
        val row = uniqueChild(overlay, topParams.getRule(RelativeLayout.ABOVE)) as? LinearLayout
            ?: return emptyList()
        val rowParams = row.layoutParams as? RelativeLayout.LayoutParams ?: return emptyList()
        if (row.orientation != LinearLayout.VERTICAL || rowParams.height != ViewGroup.LayoutParams.WRAP_CONTENT ||
            rowParams.getRule(RelativeLayout.ALIGN_PARENT_BOTTOM) == 0) return emptyList()

        val avatarId = res.getIdentifier("user_avatar", "id", pkg)
        val musicId = res.getIdentifier("videomusiccoverblock", "id", pkg)
        val children = (0 until top.childCount).map(top::getChildAt).filterIsInstance<ViewGroup>()
        val avatarColumn = children.filterIsInstance<LinearLayout>().singleOrNull {
            it.orientation == LinearLayout.VERTICAL && avatarId != 0 && it.findViewById<View>(avatarId) != null
        }
        val music = uniqueChild(top, musicId)
        if (avatarColumn == null && music == null) return emptyList()

        val controls = children.filter { child ->
            child === avatarColumn || child === music ||
                (child.layoutParams.height == ViewGroup.LayoutParams.WRAP_CONTENT && isBottomAnchored(child, top))
        }
        return controls + row
    }

    private fun isBottomAnchored(view: View, parent: RelativeLayout): Boolean {
        var candidate = view
        repeat(parent.childCount) {
            val params = candidate.layoutParams as? RelativeLayout.LayoutParams ?: return false
            if (params.getRule(RelativeLayout.ALIGN_PARENT_BOTTOM) != 0) return true
            candidate = uniqueChild(parent, params.getRule(RelativeLayout.ABOVE)) ?: return false
        }
        return false
    }

    private fun findControlAnchors(overlay: ViewGroup): List<View> {
        val anchors = listOf(
            "videomusiccoverblock", "video_caption_layout", "vs_tako_entrance",
            "tv_live_nickname", "tv_live_title", "tv_live_tips",
            "live_ad_card_container", "ad_live_product_banner_container", "ad_feed_card_container_v2",
        ).mapNotNull { name ->
            val id = overlay.resources.getIdentifier(name, "id", overlay.context.packageName)
            if (id == 0) null else overlay.findViewById<View>(id)?.takeUnless { it === overlay }
        }
        // d46/d4p include the native wrap-content interaction frame at the
        // bottom of a RelativeLayout, without a separate caption anchor.
        if (AdapterNavigationSearch.resourceEntryName(overlay) != "widget_container") return anchors
        val queue = ArrayDeque<ViewGroup>()
        queue.add(overlay)
        val bottomFrames = ArrayList<View>()
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 128) {
            val parent = queue.removeFirst()
            for (index in 0 until parent.childCount) {
                val child = parent.getChildAt(index)
                val params = child.layoutParams as? RelativeLayout.LayoutParams
                if (child is ViewGroup &&
                    child.javaClass.name == "com.ss.android.ugc.feed.platform.cell.interact.view.InteractFrameLayout" &&
                    params?.height == ViewGroup.LayoutParams.WRAP_CONTENT &&
                    (params.getRule(RelativeLayout.ALIGN_PARENT_BOTTOM) != 0 || params.getRule(RelativeLayout.ABOVE) > 0)
                ) {
                    bottomFrames += child
                } else if (child is ViewGroup && child.layoutParams?.height == ViewGroup.LayoutParams.MATCH_PARENT) {
                    queue.add(child)
                }
            }
        }
        return (anchors + listOfNotNull(bottomFrames.singleOrNull())).distinct()
    }

    private fun findSearchRecommendation(cell: ViewGroup, suggestion: TextView): ViewGroup? {
        if (!isInside(suggestion, cell)) return null
        val parent = suggestion.parent as? ViewGroup ?: return null
        // ezy: the word is in a horizontal content cluster beside a separate
        // search action. Lift the containing row, including its entire hit area.
        if (parent is LinearLayout && parent.orientation == LinearLayout.HORIZONTAL) {
            val row = parent.parent as? LinearLayout ?: return null
            val actionId = cell.resources.getIdentifier("tv_bar_search", "id", cell.context.packageName)
            return row.takeIf {
                it !== cell && isInside(it, cell) && it.orientation == LinearLayout.HORIZONTAL &&
                    uniqueChild(it, actionId) is TextView
            }
        }
        // dvx: the word and search icon share a single clickable constraint row.
        return parent.takeIf {
            it !== cell && isConstraintLayout(it) && it.isClickable && suggestion.maxLines == 1
        }
    }

    private fun translateControl(view: View, shift: Float) {
        if (shift == 0f) {
            restoreTranslation(view)
            return
        }
        val state = controlTranslations.getOrPut(view) { Translation(view.translationY, view.translationY) }
        if (view.translationY != state.appliedY) state.nativeY = view.translationY
        state.appliedY = state.nativeY + shift
        if (view.translationY != state.appliedY) view.translationY = state.appliedY
    }

    private fun restoreTranslation(view: View) {
        val state = controlTranslations.remove(view) ?: return
        if (view.translationY == state.appliedY) view.translationY = state.nativeY
    }
    private fun isInside(view: View, ancestor: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === ancestor) return true
            current = current.parent as? View
        }
        return false
    }
    private fun findFeedProgress(window: View, feed: ViewGroup): List<ProgressBinding> {
        val result = ArrayList<ProgressBinding>()
        val queue = ArrayDeque<View>()
        queue.add(window)
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 8000) {
            val view = queue.removeLast()
            if (view is GlassHostLayout || view.visibility == View.GONE) continue
            if (view.javaClass.name == "com.ss.android.ugc.aweme.feed.ui.seekbar.VideoSeekBarMaskView" &&
                (isInside(feed, view) || isInside(view, feed))) {
                runCatching {
                    val wrapper = view.javaClass.getMethod("getVideoSeekBar").invoke(view) as? android.widget.LinearLayout ?: return@runCatching
                    val seek = view.javaClass.getMethod("getMutableSeekBar").invoke(view) as? android.widget.SeekBar ?: return@runCatching
                    var ancestor = seek.parent as? View
                    while (ancestor != null && ancestor !== wrapper) ancestor = ancestor.parent as? View
                    if (ancestor === wrapper) {
                        val parent = wrapper.parent as? ViewGroup
                        val maskId = view.resources.getIdentifier("video_seek_duration_mask", "id", view.context.packageName)
                        val durationId = view.resources.getIdentifier("video_seek_duration", "id", view.context.packageName)
                        val thumbnailId = view.resources.getIdentifier("video_seek_thum", "id", view.context.packageName)
                        val shade = uniqueChild(parent, maskId)
                        val duration = uniqueChild(parent, durationId) as? RelativeLayout
                        // Current XML places this noninteractive gradient beside
                        // the native seek wrapper and duration text, not around
                        // the player or seek thumb. Never suppress their parent.
                        val verifiedShade = shade?.takeIf { it.parent === parent && duration?.parent === parent &&
                            it !== wrapper && it is android.widget.ImageView && !it.isClickable && !it.isFocusable }
                        // bnu keeps preview and time beside the wrapper. In
                        // cea/ceb the preview sits beside the native mask view
                        // in FragmentTabHost. An ambiguous preview is skipped.
                        val previewHost = (view.parent as? ViewGroup)?.takeIf {
                            parent === view && it.javaClass.name == "com.ss.android.ugc.aweme.ui.FragmentTabHost"
                        }
                        val thumbnails = listOfNotNull(uniqueChild(parent, thumbnailId), uniqueChild(previewHost, thumbnailId))
                        result += ProgressBinding(wrapper, seek, verifiedShade, listOfNotNull(duration, thumbnails.singleOrNull()))
                    }
                }
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) queue.add(view.getChildAt(i))
        }
        return result
    }

    private fun uniqueChild(parent: ViewGroup?, id: Int): View? {
        if (parent == null || id == 0) return null
        return (0 until parent.childCount).asSequence().map(parent::getChildAt)
            .filter { it.id == id }.take(2).toList().singleOrNull()
    }
    private fun findNativeReservation(feed: ViewGroup): Pair<View?, LinearLayout>? {
        val media = (feed.parent as? FrameLayout)?.takeIf { parent -> parent.parent is LinearLayout } ?: feed
        val column = media.parent as? LinearLayout ?: return null
        if (column.orientation != LinearLayout.VERTICAL) return null
        val mediaParams = media.layoutParams as? LinearLayout.LayoutParams ?: return null
        if (mediaParams.weight <= 0f) return null
        val mediaIndex = column.indexOfChild(media)
        if (mediaIndex < 0) return null
        val candidates = ((mediaIndex+1) until column.childCount).map(column::getChildAt).filter { child ->
            child.javaClass == View::class.java && !child.isClickable && !child.isFocusable &&
                child.width >= column.width * .8f && child.layoutParams is LinearLayout.LayoutParams
        }
        if (candidates.size > 1) return null
        if (candidates.isEmpty() && column.childCount != 1) return null
        return candidates.singleOrNull() to column
    }
    /** b8b/b8f: a bottom-gravity plain color View overlays the feed branch. */
    private fun findFeedBottomDecoration(feed: View): List<View> {
        val result = ArrayList<View>()
        var branch = feed
        for (depth in 0..8) {
            val parent = branch.parent as? ViewGroup ?: break
            if (parent is FrameLayout) {
                val candidates = (0 until parent.childCount).map(parent::getChildAt).filter { sibling ->
                    val p = sibling.layoutParams as? FrameLayout.LayoutParams
                    sibling !== branch && sibling.javaClass == View::class.java &&
                        !sibling.isClickable && !sibling.isFocusable && p != null &&
                        p.width == ViewGroup.LayoutParams.MATCH_PARENT &&
                        p.gravity and android.view.Gravity.VERTICAL_GRAVITY_MASK == android.view.Gravity.BOTTOM
                }
                if (candidates.size == 1) result += candidates.single()
            }
            branch = parent
        }
        return result
    }
    /** dfy/dkd: media and the full-height ConstraintLayout controls are siblings. */
    private fun findLiveInteractionLayers(cell: ViewGroup?): List<View> {
        cell ?: return emptyList()
        fun marker(name: String): View? {
            val id = cell.resources.getIdentifier(name, "id", cell.context.packageName)
            return if (id != 0) cell.findViewById(id) else null
        }
        // dke/dkx and the current device tree expose these stable business anchors.
        if (marker("tv_live_nickname") != null && marker("tv_live_tips") != null) {
            val branches = listOf("tv_live_nickname", "tv_live_title", "tv_live_tips", "live_ad_card_container", "ad_live_product_banner_container")
                .mapNotNull(::marker).mapNotNull { anchor ->
                    var branch = anchor
                    while (branch.parent is View && branch.parent !== cell) branch = branch.parent as View
                    branch.takeIf { it.parent === cell && it is ViewGroup }
                }.distinct()
            if (branches.isNotEmpty()) return branches
        }
        val candidates = (0 until cell.childCount).map(cell::getChildAt).filter { layer ->
            if (layer !is ViewGroup || !isConstraintLayout(layer) ||
                layer.layoutParams.height != ViewGroup.LayoutParams.MATCH_PARENT) return@filter false
            (0 until layer.childCount).any { index ->
                val bottom = layer.getChildAt(index)
                val p = bottom.layoutParams
                isConstraintLayout(bottom) && p.width == ViewGroup.LayoutParams.MATCH_PARENT &&
                    p.height == ViewGroup.LayoutParams.WRAP_CONTENT && runCatching {
                        p.javaClass.getField("bottomToBottom").getInt(p) == 0
                    }.getOrDefault(false)
            }
        }
        return listOfNotNull(candidates.singleOrNull())
    }
    private fun isConstraintLayout(view: View): Boolean {
        var type: Class<*>? = view.javaClass
        while (type != null) {
            if (type.name == "androidx.constraintlayout.widget.ConstraintLayout") return true
            type = type.superclass
        }
        return false
    }

    private fun isPager(view: View): Boolean {
        var type: Class<*>? = view.javaClass
        while (type != null) {
            if (type.name == "androidx.viewpager.widget.ViewPager" ||
                type.name == "com.ss.android.ugc.aweme.common.widget.VerticalViewPager") return true
            type = type.superclass
        }
        return false
    }

    private fun remember(view: View): Saved = saved.getOrPut(view) {
        val params = view.layoutParams
        Saved(params.height, (params as? ViewGroup.MarginLayoutParams)?.bottomMargin,
            (params as? LinearLayout.LayoutParams)?.weight, view.paddingBottom)
    }

    private fun geometry(view: View, height: Int, bottom: Int) {
        val params = view.layoutParams ?: return
        val state = remember(view)
        if (state.writtenHeight != null && params.height != state.writtenHeight) state.height = params.height
        if (params is ViewGroup.MarginLayoutParams && state.writtenBottom != null && params.bottomMargin != state.writtenBottom)
            state.bottom = params.bottomMargin
        TikTokControlMarginGuard.ownGeometry(view, height, bottom, null)
        var changed = false
        if (params.height != height) { params.height = height; changed = true }
        state.writtenHeight = height
        if (params is ViewGroup.MarginLayoutParams) {
            if (params.bottomMargin != bottom) { params.bottomMargin = bottom; changed = true }
            state.writtenBottom = bottom
        }
        if (changed) { view.layoutParams = params; TikTokVideoCenter.changed(view) }
    }

    private fun clearBottomPadding(view: View) {
        val state = remember(view)
        if (state.writtenPadding != null && view.paddingBottom != state.writtenPadding) state.padding = view.paddingBottom
        if (view.paddingBottom != 0) view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, 0)
        state.writtenPadding = 0
    }

    private fun restore() {
        controlTranslations.keys.toList().forEach(::restoreTranslation)
        seekShadeAlpha.forEach { (view, alpha) -> if (view.alpha == 0f) view.alpha = alpha }
        seekShadeAlpha.clear()
        saved.forEach { (view, state) -> restoreGeometry(view, state) }
        saved.clear(); pager = null; reservation = null; feedColumn = null; pagerSignature = 0
        strips = emptyList(); cells = emptyList(); progress = emptyList(); mixedControls = emptyList()
        mediaSpacers = emptyList(); liveMedia = emptyList()
        controlBottomAnchors.clear()
    }

    private fun restoreGeometry(view: View, state: Saved) {
        TikTokControlMarginGuard.release(view)
        val params = view.layoutParams ?: return
        var changed = false
        if (state.writtenHeight != null && params.height == state.writtenHeight) { params.height = state.height; changed = true }
        if (params is ViewGroup.MarginLayoutParams && state.bottom != null && params.bottomMargin == state.writtenBottom) {
            params.bottomMargin = state.bottom!!; changed = true
        }
        if (params is ViewGroup.MarginLayoutParams && state.top != null && params.topMargin == state.writtenTop) {
            params.topMargin = state.top!!; changed = true
        }
        if (params is LinearLayout.LayoutParams && state.weight != null && params.weight == state.writtenWeight) {
            params.weight = state.weight!!; changed = true
        }
        if (changed) view.layoutParams = params
        if (state.writtenPadding != null && view.paddingBottom == state.writtenPadding)
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, state.padding)
    }

    fun dispose() {
        root?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(listener)
        root = null
        restore()
    }
}
