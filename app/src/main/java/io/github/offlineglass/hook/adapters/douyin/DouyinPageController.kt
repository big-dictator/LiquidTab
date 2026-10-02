package io.github.offlineglass.hook.adapters.douyin

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.SeekBar
import io.github.offlineglass.hook.GlassInstaller
import io.github.offlineglass.hook.GlassHostLayout
import java.lang.ref.WeakReference
import java.util.ArrayDeque
import java.util.LinkedHashMap
import java.util.WeakHashMap
import kotlin.math.roundToInt

/** Douyin-only page geometry and control avoidance. */
internal class DouyinPageController(private val context: Context) {
    private val feedCornerMasks = DouyinFeedCornerMasks()
    // Classification only: refreshed every layout-maintenance pass so newly
    // attached players and page changes never inherit an old negative result.
    private val videoContainerProbe = java.util.IdentityHashMap<View, Boolean>()
    private val density = context.resources.displayMetrics.density
    private var currentRoot: ViewGroup? = null
    private var currentBarHeightPx = 0
    private var classicNavigationLift = false
    private val douyinVideoDiagBuffer = StringBuilder(1024)
    private var lastDouyinVideoDiagFlushAt = 0L
    private var douyinLastLayoutAdjustAt = 0L

    private var douyinFeedPager: WeakReference<View>? = null

    private var douyinBottomSpace: WeakReference<View>? = null
    private var douyinShopBackToTopRef: WeakReference<View>? = null
    private var douyinShopBackToTopBaseTranslation = 0f
    private var douyinShopBackToTopLastSearchAt = 0L
    private var douyinShopPageActive = false
    val shopPageActive: Boolean get() = douyinShopPageActive
    private var douyinSpecialPageLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var douyinCouponPageRef: WeakReference<View>? = null
    private var douyinLocationCommentRef: WeakReference<ViewGroup>? = null
    private var douyinGroupBuyBackToTopRef: WeakReference<View>? = null
    private var douyinGroupBuyBackToTopBaseTranslation = 0f
    private var douyinGroupBuyClipParentRef: WeakReference<ViewGroup>? = null
    private var douyinGroupBuyClipParentBottomPadding = 0
    private var douyinGroupBuyClipParentClipToPadding = true

    private val douyinControlBaseTranslations = java.util.WeakHashMap<View, Float>()

private var douyinCachedOverlayRef: WeakReference<View>? = null

private var douyinCachedProgressRef: WeakReference<View>? = null

private var douyinCachedChipRef: WeakReference<View>? = null
private val douyinPaddingInsetLock = HashMap<View, Int>()



private val douyinMarginTargets = HashMap<View, Int>()
private val douyinMessageBaselineTops = java.util.WeakHashMap<View, Int>()
private val douyinMessageListMotion = java.util.WeakHashMap<View, Pair<Int, Long>>()



private val douyinExpandedPagers = HashSet<Int>()
private val douyinPageControlsCache = HashMap<View, View>()
private val douyinAdvertisementCache = java.util.WeakHashMap<View, Pair<Long, Boolean>>()
    private var douyinExpandedPager: WeakReference<View>? = null

    // Padding-based feed avoidance (DouyinLiquidGlass style): lift the feed

    // overlay's own bottom boundary instead of translating individual controls.

    private val douyinPaddingTargets = java.util.WeakHashMap<View, Int>()

    private val douyinContentLines = java.util.WeakHashMap<View, Int>()

    private val douyinPaddingLastSeen = java.util.WeakHashMap<View, Long>()

    private var lastDouyinFallbackLogAt = 0L

    private var lastDouyinLiftLogAt = 0L

    private var lastDouyinDiagLogAt = 0L

    private var lastDouyinDiagFileAt = 0L

    fun update(host: View, barHeightPx: Int) {
        currentBarHeightPx = barHeightPx
        val classicNavigation = (host as? GlassHostLayout)?.config?.classicNavigation == true
        if (classicNavigationLift != classicNavigation) {
            classicNavigationLift = classicNavigation
            // The 10/16dp adjustment is below the existing lock threshold.
            // Invalidate targets on switch changes so old heights cannot stick.
            douyinMarginTargets.keys.forEach(DouyinControlMarginGuard::release)
            douyinMarginTargets.clear()
        }

        runCatching {

        // The host itself is permanently GONE on Douyin (non-animated hide
        // path), so it is never laid out and its own size stays 0. Gate on the
        // root view instead — the feed geometry must not depend on a view that
        // only ever exists off-screen.
        val root = host.rootView as? ViewGroup ?: return
        currentRoot = root

        if (root.width <= 0 || root.height <= 0) return

        // Douyin may restore its navigation-bar inset while changing feed tabs.

        // Keep every liquid-bar page edge-to-edge; this does not depend on

        // whether the liquid host itself is temporarily visible.

        context.findActivity()?.let(GlassInstaller::ensureNavigationBarImmersion)

        val now = SystemClock.uptimeMillis()

        if (now - douyinLastLayoutAdjustAt < DOUYIN_LAYOUT_ADJUST_INTERVAL_MS) return

        douyinLastLayoutAdjustAt = now
        videoContainerProbe.clear()



        var bottomSpace = douyinBottomSpace?.get()?.takeIf { it.isAttachedToWindow }

        var pager = douyinFeedPager?.get()?.takeIf { it.isAttachedToWindow }

        // target -> visual anchor used to calculate its original bottom edge.

        // Some interactive children (notably the SeekBar) must not themselves

        // be translated because Douyin rewrites their properties while used.

        val controlsRoots = LinkedHashMap<View, View>()
        val relatedSearchChips = LinkedHashSet<View>()

        // Top edge of the hidden liquid bar area: controls are lifted until

        // their bottom clears this line, releasing the bar's space for the

        // video feed. The host itself is permanently GONE on Douyin, so the

        // clearance is derived from the configured bar height instead.

        val clearanceTop = (root.height - barHeightPx)

            .coerceAtLeast(0)

        adjustDouyinShopBackToTop(root, clearanceTop)

        val douyinPagerCandidates = ArrayList<View>()

        var progressControl: View? = null

        var visited = 0

        val stack = ArrayDeque<Pair<View, Int>>()

        stack += root to 0

        douyinShopPageActive = false
        val rootScreen = IntArray(2).also(root::getLocationOnScreen)

        while (stack.isNotEmpty() && visited++ < DOUYIN_LAYOUT_MAX_VIEWS) {

            val (view, depth) = stack.removeLast()

            val idName = viewResourceEntryName(view)


            if (douyinIsFeaturedFeedRoot(view)) {
                (view.parent as? ViewGroup)?.let { parent ->
                    if (parent.paddingBottom != 0) {
                        parent.setPadding(parent.paddingLeft, parent.paddingTop,
                            parent.paddingRight, 0)
                    }
                    if (parent.clipToPadding) parent.clipToPadding = false
                }
                val params = view.layoutParams as? ViewGroup.MarginLayoutParams
                if (params != null &&
                    (params.bottomMargin != 0 || params.height != ViewGroup.LayoutParams.MATCH_PARENT)
                ) {
                    params.bottomMargin = 0
                    params.height = ViewGroup.LayoutParams.MATCH_PARENT
                    view.layoutParams = params
                    view.requestLayout()
                }
                douyinMarginTargets.remove(view)
                // The Featured feed keeps its own pre-tab-bar height after the outer
                // page has grown. Extend only its refresh/list chain; card children
                // and the seek bar must retain their original layout.
                val featuredIds = setOf("dly", "diw", "dk2", "dk1")
                var featuredParent: ViewGroup = view as ViewGroup
                for (featuredId in featuredIds) {
                    val child = (0 until featuredParent.childCount)
                        .map(featuredParent::getChildAt)
                        .firstOrNull { viewResourceEntryName(it) == featuredId }
                        ?: break
                    if (featuredParent.paddingBottom != 0) {
                        featuredParent.setPadding(featuredParent.paddingLeft,
                            featuredParent.paddingTop, featuredParent.paddingRight, 0)
                    }
                    if (featuredParent.clipToPadding) featuredParent.clipToPadding = false
                    val childParams = child.layoutParams as? ViewGroup.MarginLayoutParams
                    if (childParams != null && (childParams.bottomMargin != 0 ||
                            childParams.height != ViewGroup.LayoutParams.MATCH_PARENT)) {
                        childParams.bottomMargin = 0
                        childParams.height = ViewGroup.LayoutParams.MATCH_PARENT
                        child.layoutParams = childParams
                        child.requestLayout()
                    }
                    featuredParent = child as? ViewGroup ?: break
                }
            }

            if (idName == "s+i" &&
                view.javaClass.simpleName == "SkyLightTouchEventFrameLayout") {
                (view.parent as? ViewGroup)?.let { parent ->
                    if (parent.paddingBottom != 0) {
                        parent.setPadding(parent.paddingLeft, parent.paddingTop,
                            parent.paddingRight, 0)
                    }
                    if (parent.clipToPadding) parent.clipToPadding = false
                }
                val params = view.layoutParams as? ViewGroup.MarginLayoutParams
                if (params != null &&
                    (params.bottomMargin != 0 || params.height != ViewGroup.LayoutParams.MATCH_PARENT)
                ) {
                    params.bottomMargin = 0
                    params.height = ViewGroup.LayoutParams.MATCH_PARENT
                    view.layoutParams = params
                    view.requestLayout()
                }
                douyinMarginTargets.remove(view)
            }

            if ((idName == "s3m" || idName == "i=-") &&
                !douyinIsUnderLocalPage(view)) {
                val params = view.layoutParams as? ViewGroup.MarginLayoutParams
                if (params != null &&
                    (params.bottomMargin != 0 || params.height != ViewGroup.LayoutParams.MATCH_PARENT)
                ) {
                    params.bottomMargin = 0
                    params.height = ViewGroup.LayoutParams.MATCH_PARENT
                    view.layoutParams = params
                    view.requestLayout()
                }
                douyinMarginTargets.remove(view)
            }

            if (idName == "pmr" && view.isShown && view.width >= root.width * 0.85f) {
                val shopScreen = IntArray(2).also(view::getLocationOnScreen)
                if (kotlin.math.abs(shopScreen[0] - rootScreen[0]) < root.width * 0.5f) {
                    douyinShopPageActive = true
                }
            }

            if (view.javaClass.simpleName == "0wNK" && view.isShown &&
                view.width >= root.width * 0.85f) {
                douyinCouponPageRef = WeakReference(view)
            }

            if (idName == DOUYIN_BOTTOM_SPACE_ID) {
                bottomSpace = view
                feedCornerMasks.suppressAbove(view)
            }

            // Direct pager identification: if bottom_space is missing or
            // renamed in a Douyin build, expanding the feed pager directly
            // still removes the black gap at the screen bottom.
            if (view is ViewGroup) {
                val pagerClass = view.javaClass.name
                if ((pagerClass.contains("ViewPager") || pagerClass.contains("Pager")) &&
                    view.isAttachedToWindow && view.width >= root.width * 0.9f
                ) {
                    douyinPagerCandidates.add(view)
                }
            }

            // ViewPager keeps the current and adjacent video pages alive, and

            // replaces/reuses these roots while swiping or changing tabs. Keep

            // every instance so both pages remain aligned during the gesture.

            val overlayRoot = douyinIsVideoControlsRoot(view)

            val progressId = idName == DOUYIN_PROGRESS_ID ||

                idName == DOUYIN_PROGRESS_ID_ALT ||

                idName in DOUYIN_PROGRESS_NAME_SET

            if (overlayRoot && view.isAttachedToWindow) {

                controlsRoots[view] = view

            }

val progressLikeClass = !progressId && view.javaClass.name.let { name ->

    (name.contains("Progress") || name.contains("SeekBar") ||

        name.contains("Scrubber")) && view.width > 0 &&

    view.height <= (40f * density).toInt() && view.width >= root.width * 0.25f

}
if ((progressId || view is SeekBar || progressLikeClass) && view.isAttachedToWindow) {

                // Douyin fades the progress line out shortly after playback

                // starts; force it back so it stays visible.

                if (view.visibility != View.VISIBLE) view.visibility = View.VISIBLE

                if (view.alpha != 1f) view.alpha = 1f

                val stableParent = view.parent as? View

                val progressTarget = stableParent ?: view

                controlsRoots[progressTarget] = view

                progressControl = progressTarget

            }

            val label = when (view) {

                is TextView -> view.text?.toString()

                else -> view.contentDescription?.toString()

            }.orEmpty()

val relatedHit = DOUYIN_RELATED_SEARCH_TEXT.any { label.contains(it) }

if (relatedHit && view.isAttachedToWindow) {

                // Include the pill/background rather than moving only its text.

                var chip: View = view

                repeat(4) {

                    val candidate = chip.parent as? View ?: return@repeat

                    if (candidate.width > 0 && candidate.height > 0 &&

                        candidate.width < root.width * 0.9f &&

                        candidate.height < root.height * 0.25f

                    ) chip = candidate

                }

                controlsRoots[chip] = chip
                relatedSearchChips += chip

            }

            if (view is ViewGroup && depth < DOUYIN_LAYOUT_MAX_DEPTH) {

                for (index in 0 until view.childCount) stack += view.getChildAt(index) to (depth + 1)

            }

        }



        bottomSpace?.let { spacer ->

            douyinBottomSpace = WeakReference(spacer)

            if (spacer.alpha != 0f) spacer.alpha = 0f

            val parent = spacer.parent as? ViewGroup

            if (pager == null && parent != null) {

                pager = (0 until parent.childCount)

                    .map(parent::getChildAt)

                    .firstOrNull { candidate ->

                        candidate !== spacer &&

                            viewResourceEntryName(candidate) == DOUYIN_FEED_PAGER_ID &&

                            candidate.width >= parent.width * 0.9f &&

                            candidate.bottom <= spacer.top + DOUYIN_EDGE_TOLERANCE_PX

                    }

            }

        }

        if (pager == null && douyinPagerCandidates.isNotEmpty()) {

            // bottom_space was not found (renamed id or different layout
            // branch): pick the widest pager-like container whose bottom
            // reaches the screen bottom, then expand it below.
            pager = douyinPagerCandidates
                .filter { it.isAttachedToWindow && it.width >= root.width * 0.9f }
                .minByOrNull { candidate ->
                    val p = IntArray(2).also { candidate.getLocationOnScreen(it) }
                    val r = IntArray(2).also { root.getLocationOnScreen(it) }
                    (root.height - (p[1] - r[1] + candidate.height)).coerceAtLeast(0)
                }
                ?.takeIf { candidate ->
                    val p = IntArray(2).also { candidate.getLocationOnScreen(it) }
                    val r = IntArray(2).also { root.getLocationOnScreen(it) }
                    p[1] - r[1] + candidate.height >= root.height - (40f * density).toInt()
                }

            if (pager != null) douyinFeedPager = WeakReference(pager!!)

        }

        val pagerSet = LinkedHashSet<View>()



        pager?.let { pagerSet.add(it) }



        douyinPagerCandidates



            .filter { it.isAttachedToWindow && it.width >= root.width * 0.9f }



            .forEach { pagerSet.add(it) }



        val aliveCodes = HashSet<Int>()



        for (feed in pagerSet) {



            val code = System.identityHashCode(feed)



            aliveCodes.add(code)



            douyinExpandedPagers.add(code)



            val params = feed.layoutParams



            if (params is RelativeLayout.LayoutParams) {



                if (params.getRule(RelativeLayout.ABOVE) != 0 ||
                    params.getRule(RelativeLayout.ALIGN_PARENT_BOTTOM) == 0 ||
                    params.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                    params.removeRule(RelativeLayout.ABOVE)
                    params.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
                    params.height = ViewGroup.LayoutParams.MATCH_PARENT
                    feed.layoutParams = params
                    feed.requestLayout()
                    (feed.parent as? View)?.requestLayout()
                }



            } else if (params is ViewGroup.MarginLayoutParams) {



                if (params.bottomMargin != 0 ||
                    params.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                    params.bottomMargin = 0
                    params.height = ViewGroup.LayoutParams.MATCH_PARENT
                    feed.layoutParams = params
                    feed.requestLayout()
                }



            }

            // Photo/text posts keep an additional full-width page root at the
            // old native-tab height even after the outer feed pager is made
            // edge-to-edge. Video ignores that root because its Surface fills
            // the pager, which is why only video previously reached the
            // navigation-bar area. Expand the page root as well, but leave ad
            // cards untouched as requested.
            val pageHost = feed as? ViewGroup
            if (pageHost != null) {
                for (index in 0 until pageHost.childCount) {
                    val page = pageHost.getChildAt(index)
                    if (!page.isAttachedToWindow || page.width < root.width * 0.85f ||
                        douyinPageLooksLikeAdvertisement(page) ||
                        douyinPageContainsCouponRoot(page)
                    ) continue
                    val pageParams = page.layoutParams
                    if (pageParams is ViewGroup.MarginLayoutParams) {
                        var changed = false
                        if (pageParams.bottomMargin != 0) {
                            pageParams.bottomMargin = 0
                            changed = true
                        }
                        if (pageParams.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                            pageParams.height = ViewGroup.LayoutParams.MATCH_PARENT
                            changed = true
                        }
                        if (changed) {
                            page.layoutParams = pageParams
                            page.requestLayout()
                        }
                    }
                    extendDouyinPhotoContentToBottom(page, root)
                }
            }



        }



        douyinExpandedPagers.retainAll(aliveCodes)

        adjustDouyinCouponPage(root, clearanceTop)
        adjustDouyinGroupBuyPage(root)
        adjustDouyinMessagePage(root)
        adjustDouyinLocationCommentGap(root)

        // Resource names on the per-video controls root are obfuscated and
        // rotate between Douyin releases. When i7/ja no longer exists, locate
        // the controls layer inside every live pager page by geometry.
        run {
            for (feed in pagerSet) {
                val pageHost = feed as? ViewGroup ?: continue
                for (index in 0 until pageHost.childCount) {
                    val page = pageHost.getChildAt(index)
                    // Nested pagers inside a controls root do not own a
                    // second lift. In early layout they can otherwise cache
                    // only the footer (cne), leaving sibling controls behind.
                    var ancestor = page.parent as? View
                    var ownsAncestor = false
                    while (ancestor != null && ancestor !== root) {
                        if (douyinIsVideoControlsRoot(ancestor)) { ownsAncestor = true; break }
                        ancestor = ancestor.parent as? View
                    }
                    if (ownsAncestor) {
                        douyinPageControlsCache.remove(page)?.let { stale ->
                            if (!douyinIsVideoControlsRoot(stale)) {
                                DouyinControlMarginGuard.release(stale)
                                val previousLift = douyinMarginTargets.remove(stale)
                                val params = stale.layoutParams as? ViewGroup.MarginLayoutParams
                                if (previousLift != null && params?.bottomMargin == previousLift) {
                                    params.bottomMargin = 0
                                    stale.requestLayout()
                                }
                            }
                        }
                        continue
                    }
                    // A named root on an off-screen subpage must not disable
                    // structural recognition on this independently owned page.
                    if (controlsRoots.keys.any { known ->
                            douyinIsVideoControlsRoot(known) && douyinContainsView(page, known)
                        }) continue
                    val candidate = douyinPageControlsCache[page]
                        ?.takeIf { it.isAttachedToWindow && !douyinIsProgressContainer(it) &&
                            !douyinContainerHostsVideoSurface(it, root) &&
                            !douyinIsFeedContentFrame(it) &&
                            !douyinContainsFullWidthPhoto(it, root) &&
                            !douyinContainsGroupBuyContent(it) }
                        ?: findPageControlsContainer(page, root)?.takeUnless {
                            douyinIsFeedContentFrame(it) || douyinContainsFullWidthPhoto(it, root) ||
                                douyinContainsGroupBuyContent(it)
                        }?.also {
                            douyinPageControlsCache[page] = it
                        }
                    if (candidate != null && candidate !== progressControl &&
                !douyinIsProgressContainer(candidate)
                    ) {
                        controlsRoots[candidate] = candidate
                    }
                }
            }
            douyinPageControlsCache.entries.removeAll { (page, controls) ->
                !page.isAttachedToWindow || !controls.isAttachedToWindow
            }
        }



        // ---- Video controls: the per-page overlay root (i7) carries the
        // caption, action column and related-search pill inside each video
        // page. Raising it by margin lifts every control while keeping the
        // page-relative position fixed, so swiping slides the controls
        // together with the video exactly like the native feed.

        for (target in controlsRoots.keys) {
            val name = viewResourceEntryName(target)
            val namedRoot = douyinIsVideoControlsRoot(target)
            // Some native video items give their controls an independent
            // MeasureOnce layer. Raise that layer, not both it and its wrapper.
            if (controlsRoots.keys.any { other ->
                    other !== target && (douyinIsVideoControlsRoot(other) ||
                        douyinPageControlsCache.values.any { it === other }) &&
                        other.visibility == View.VISIBLE && douyinContainsView(target, other)
                }) continue
            val structuralRoot = douyinPageControlsCache.values.any { it === target } && target !== progressControl &&
                !douyinIsProgressContainer(target) &&
                (target !in relatedSearchChips || douyinPageControlsCache.values.any { it === target })
            if ((namedRoot || structuralRoot) &&
                !douyinContainerHostsVideoSurface(target, root) &&
                !douyinIsFeedContentFrame(target) &&
                !douyinContainsFullWidthPhoto(target, root) &&
                !douyinContainsGroupBuyContent(target)
            ) {
                liftDouyinControlByMargin(
                    target,
                    root,
                    clearanceTop,
                    ((DOUYIN_CONTROLS_EXTRA_LIFT_DP +
                        if (classicNavigationLift) DOUYIN_CLASSIC_CONTROLS_EXTRA_DP else 0f) * density).roundToInt(),
                )
            }
        }

        // ---- Progress bar: fixed overlay layer above the tab bar; margin
        // lift is self-stable because it is computed from live positions.

        var progress: View? = douyinCachedProgressRef?.get()?.takeIf { it.isAttachedToWindow }

        if (progress == null && progressControl != null) {
            progress = progressControl
            douyinCachedProgressRef = WeakReference(progress)
        }

        if (progress != null) {
            val progressRoot = progress!!
            // wb9 is the visible track inside the full-height DProSeekBar.
            // Anchor the track, rather than the invisible wrapper's bottom.
            val track = (progressRoot as? ViewGroup)?.let { group ->
                (0 until group.childCount).asSequence().map(group::getChildAt)
                    .firstOrNull { it.javaClass.simpleName == "ProSeekBarView" && it.height > 0 }
            }
            val extra = if (classicNavigationLift) {
                (DOUYIN_PROGRESS_EXTRA_LIFT_DP * density).roundToInt()
            } else if (track != null) {
                // Existing floating bar top is clearanceTop - 21dp (also
                // used by coupon avoidance). Keep the visible track 10dp above.
                ((21f + 10f - DOUYIN_CONTROL_SAFE_GAP_DP) * density).roundToInt() -
                    (progressRoot.height - track.bottom)
            } else 0
            liftDouyinControlByMargin(progressRoot, root, clearanceTop, extra)
        }

        // ---- Related-search pill: independent container lifted by margin.

        var chip: View? = douyinCachedChipRef?.get()?.takeIf {
            it.isAttachedToWindow && it in relatedSearchChips && !douyinContainsGroupBuyContent(it)
        }

        if (chip == null) {
            for (target in relatedSearchChips) {
                if (target === progressControl) continue
                if (douyinContainsGroupBuyContent(target)) continue
                val name = viewResourceEntryName(target)
                if (name == DOUYIN_CONTROLS_ROOT_ID || name == DOUYIN_CONTROLS_ROOT_ID_ALT) continue
                chip = target
                douyinCachedChipRef = WeakReference(target)
                break
            }
        }

        // A structural controls root is already raised above with its extra
        // clearance. Never apply the independent-pill lift to that same View.
        if (chip != null && douyinPageControlsCache.values.none { it === chip }) {
            liftDouyinControlByMargin(chip!!, root, clearanceTop)
        }

        val diagNow = SystemClock.uptimeMillis()

        if (diagNow - lastDouyinDiagLogAt >= 2000L) {

            lastDouyinDiagLogAt = diagNow

            val pagerInfo = pager?.let { "${it.javaClass.simpleName} x${(it as? ViewGroup)?.childCount ?: 0}" } ?: "null"

            val liftInfo = controlsRoots.keys.joinToString { c ->
                val lp = c.layoutParams as? ViewGroup.MarginLayoutParams
                "${c.javaClass.simpleName} m=${lp?.bottomMargin}"
            }.ifEmpty { "none" }

            android.util.Log.i(

                "LiquidTab",

                "douyin diag root=${root.javaClass.simpleName} ${root.width}x${root.height} " +

                    "pager=$pagerInfo controls=${controlsRoots.size} " +

                    "overlayRoots=${controlsRoots.size} progress=${progressControl != null} " +

                    "clear=$clearanceTop lifts=[$liftInfo]",

            )

        }

        douyinWriteDiagDump(root, pager, controlsRoots.keys, progressControl, controlsRoots, clearanceTop)

        // Video Surface read-back was removed as unstable; the glass now carries

        // only the overlay controls through the live RenderNode scene.

        }.onFailure { error ->

            android.util.Log.e("LiquidTab", "douyin adjust failed: ${error.message}", error)

        }

    }

    private fun douyinPageLooksLikeAdvertisement(page: View): Boolean {
        val now = SystemClock.uptimeMillis()
        douyinAdvertisementCache[page]?.let { (checkedAt, advertisement) ->
            val ttl = if (advertisement) 750L else 250L
            if (now - checkedAt < ttl) return advertisement
        }
        val stack = ArrayDeque<Pair<View, Int>>()
        stack += page to 0
        var visited = 0
        while (stack.isNotEmpty() && visited++ < 1200) {
            val (view, depth) = stack.removeLast()
            val labels = arrayOf(
                (view as? TextView)?.text?.toString().orEmpty().trim(),
                view.contentDescription?.toString().orEmpty().trim(),
            )
            if (labels.any { label ->
                    label == "广告" || label.startsWith("广告·") ||
                        label.startsWith("广告 ") ||
                        (label.length <= 24 && label.contains("广告")) ||
                        label.equals("Ad", ignoreCase = true) ||
                        label.contains("Sponsored", ignoreCase = true) ||
                        label.contains("赞助")
                }) {
                douyinAdvertisementCache[page] = now to true
                return true
            }
            // This Douyin build hosts the ad action row under u11/u10. The
            // marker is supplementary to the visible advertisement label.
            if (viewResourceEntryName(view) == "u11" && view is ViewGroup &&
                (0 until view.childCount).any { index ->
                    viewResourceEntryName(view.getChildAt(index)) == "u10"
                }) {
                douyinAdvertisementCache[page] = now to true
                return true
            }
            if (view is ViewGroup && depth < 20) {
                for (index in 0 until view.childCount) {
                    stack += view.getChildAt(index) to (depth + 1)
                }
            }
        }
        douyinAdvertisementCache[page] = now to false
        return false
    }

    private fun extendDouyinPhotoContentToBottom(page: View, root: ViewGroup) {
        val hasVideoSurface = douyinContainerHostsVideoSurface(page, root)
        val stack = ArrayDeque<Pair<View, Int>>()
        stack += page to 0
        var visited = 0
        var largestImage: ImageView? = null
        var imagePostContent: ImageView? = null
        var largestArea = 0L
        while (stack.isNotEmpty() && visited++ < 320) {
            val (view, depth) = stack.removeLast()
            if (view is ImageView && view.isAttachedToWindow && view.isShown &&
                view.width >= root.width * 0.72f && view.height >= root.height * 0.35f
            ) {
                if (viewResourceEntryName(view) == "mia") imagePostContent = view
                val area = view.width.toLong() * view.height.toLong()
                if (area > largestArea) {
                    largestArea = area
                    largestImage = view
                }
            }
            if (view is ViewGroup && depth < 9) {
                for (index in 0 until view.childCount) stack += view.getChildAt(index) to (depth + 1)
            }
        }
        // Some image posts also contain an inactive SurfaceView. The current
        // Douyin image-post artwork is `mia`; its presence takes precedence
        // over the Surface when choosing which content should reach the edge.
        val image = imagePostContent ?: (if (!hasVideoSurface) largestImage else null) ?: return
        val rootLocation = IntArray(2).also(root::getLocationOnScreen)
        var current: View? = image
        while (current != null && current !== page) {
            if (current.width >= root.width * 0.72f &&
                current.height >= root.height * 0.35f) {
                val location = IntArray(2).also(current::getLocationOnScreen)
                val top = location[1] - rootLocation[1]
                val desiredHeight = root.height - top
                val params = current.layoutParams as? ViewGroup.MarginLayoutParams
                if (params != null && desiredHeight > current.height + (12f * density).roundToInt() &&
                    desiredHeight > 0) {
                    params.bottomMargin = 0
                    params.height = desiredHeight
                    douyinMarginTargets.remove(current)
                    current.layoutParams = params
                    current.requestLayout()
                }
            }
            current = current.parent as? View
        }
        if (image.scaleType != ImageView.ScaleType.CENTER_CROP) {
            image.scaleType = ImageView.ScaleType.CENTER_CROP
        }
    }

    private fun douyinContainsFullWidthPhoto(container: View, root: View): Boolean {
        val stack = ArrayDeque<Pair<View, Int>>()
        stack += container to 0
        var visited = 0
        while (stack.isNotEmpty() && visited++ < 320) {
            val (view, depth) = stack.removeLast()
            if (view is ImageView && view.isAttachedToWindow && view.isShown &&
                view.width >= root.width * 0.72f && view.height >= root.height * 0.35f
            ) return true
            if (view is ViewGroup && depth < 9) {
                for (index in 0 until view.childCount) stack += view.getChildAt(index) to (depth + 1)
            }
        }
        return false
    }

    private fun douyinIsFeedContentFrame(view: View): Boolean =
        view.javaClass.simpleName == "MonitorScrollFrameLayout"

    private fun douyinIsVideoControlsRoot(view: View): Boolean {
        val name = viewResourceEntryName(view)
        if (name == DOUYIN_CONTROLS_ROOT_ID || name == DOUYIN_CONTROLS_ROOT_ID_ALT) return true
        // 40.6.0 runtime records identify i6 as the actual controls root.
        // Its loading children (e.g. i-6) must never become a second lift owner.
        val parent = view.parent as? View ?: return false
        if (name == "i-6" && view.javaClass.simpleName == "MeasureOnceCheckDrawChildRelativeLayout" &&
            viewResourceEntryName(parent) == "i6" &&
            parent.javaClass.simpleName == "PenetrateTouchRelativeLayout") {
            val frame = parent.parent as? View ?: return false
            return frame.javaClass.simpleName == "MonitorScrollFrameLayout" &&
                (frame.parent as? View)?.javaClass?.simpleName == "VideoViewHolderRootView"
        }
        return name == "i6" && view.javaClass.simpleName == "PenetrateTouchRelativeLayout" &&
            parent.javaClass.simpleName == "MonitorScrollFrameLayout" &&
            (parent.parent as? View)?.javaClass?.simpleName == "VideoViewHolderRootView"
    }

    private fun douyinIsFeaturedFeedRoot(view: View): Boolean {
        val parent = view.parent as? View ?: return false
        return viewResourceEntryName(view) == "dir" &&
            viewResourceEntryName(parent) == "i=+" &&
            view.width >= (currentRoot?.width ?: 0) * 0.8f &&
                view.height >= (currentRoot?.height ?: 0) * 0.5f
    }

    private fun douyinIsUnderFeaturedFeedRoot(view: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (douyinIsFeaturedFeedRoot(current)) return true
            current = current.parent as? View
        }
        return false
    }

    private fun douyinIsUnderMessagePage(view: View): Boolean {
        var current: View? = view
        var hasMessageHost = false
        while (current != null) {
            when (viewResourceEntryName(current)) {
                "p0+" -> hasMessageHost = true
                "wt4" -> if (hasMessageHost) return true
            }
            current = current.parent as? View
        }
        return false
    }

    private fun douyinIsUnderLocalPage(view: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current.javaClass.simpleName == "NearbySkyLightTouchEventFrameLayout") return true
            current = current.parent as? View
        }
        return false
    }

    private fun douyinIsLocalEmptyContent(view: View): Boolean {
        val root = currentRoot ?: return false
        if (view.width < root.width * 0.8f || view.height < root.height * 0.5f) {
            return false
        }
        var current: View? = view.parent as? View
        while (current != null) {
            if (current.javaClass.simpleName == "NearbyEmptyView") return true
            current = current.parent as? View
        }
        return false
    }

    private fun adjustDouyinShopBackToTop(
        root: ViewGroup,
        clearanceTop: Int,
        forceSearch: Boolean = false,
    ) {
        fun inShopContainer(view: View): Boolean {
            var current: View? = view
            while (current != null && current !== root) {
                if (viewResourceEntryName(current) == "pmr") return true
                current = current.parent as? View
            }
            return false
        }

        val previous = douyinShopBackToTopRef?.get()
        var button = previous?.takeIf { it.isAttachedToWindow && it.isShown &&
            inShopContainer(it) }
        val now = SystemClock.uptimeMillis()
        if (button == null && !forceSearch && now - douyinShopBackToTopLastSearchAt < 400L) {
            if (previous != null && previous.isAttachedToWindow) {
                previous.translationY = douyinShopBackToTopBaseTranslation
            }
            douyinShopBackToTopRef = null
            return
        }
        if (button == null) {
            douyinShopBackToTopLastSearchAt = now
            val stack = ArrayDeque<Pair<View, Int>>()
            stack += root to 0
            var visited = 0
            while (stack.isNotEmpty() && visited++ < DOUYIN_LAYOUT_MAX_VIEWS) {
                val (view, depth) = stack.removeLast()
                if (view.isShown && view.contentDescription?.toString()?.contains("返回顶部") == true &&
                    view.width < root.width * 0.2f && inShopContainer(view)
                ) {
                    button = view.parent as? View ?: view
                    break
                }
                if (view is ViewGroup && depth < 24) {
                    for (index in 0 until view.childCount) stack += view.getChildAt(index) to (depth + 1)
                }
            }
        }

        if (previous != null && previous !== button && previous.isAttachedToWindow &&
            kotlin.math.abs(previous.translationY - douyinShopBackToTopBaseTranslation) > 1f
        ) {
            previous.translationY = douyinShopBackToTopBaseTranslation
        }
        if (button == null) {
            douyinShopBackToTopRef = null
            return
        }
        if (button !== previous) {
            douyinShopBackToTopRef = WeakReference(button)
            douyinShopBackToTopBaseTranslation = button.translationY
        }

        val rootPos = IntArray(2).also(root::getLocationOnScreen)
        val buttonPos = IntArray(2).also(button::getLocationOnScreen)
        val originalBottom = buttonPos[1] - rootPos[1] + button.height -
            (button.translationY - douyinShopBackToTopBaseTranslation)
        val targetBottom = clearanceTop - (36f * density).roundToInt()
        val targetTranslation = douyinShopBackToTopBaseTranslation +
            (targetBottom - originalBottom).coerceAtMost(0f)
        if (kotlin.math.abs(button.translationY - targetTranslation) > 1f) {
            button.translationY = targetTranslation
        }
    }

    private fun douyinPageContainsCouponRoot(page: View): Boolean {
        var current = douyinCouponPageRef?.get()
            ?.takeIf { it.isAttachedToWindow && it.isShown }
        while (current != null) {
            if (current === page) return true
            current = current.parent as? View
        }
        return false
    }

    private fun douyinContainsGroupBuyContent(container: View): Boolean {
        val stack = ArrayDeque<Pair<View, Int>>()
        stack += container to 0
        var visited = 0
        while (stack.isNotEmpty() && visited++ < 350) {
            val (view, depth) = stack.removeLast()
            if (viewResourceEntryName(view) == "ehh") return true
            if (view is ViewGroup && depth < 12) {
                for (index in 0 until view.childCount) stack += view.getChildAt(index) to (depth + 1)
            }
        }
        return false
    }

    private fun adjustDouyinGroupBuyPage(root: ViewGroup) {
        var selected = false
        var content: View? = null
        var backToTop: View? = null
        val stack = ArrayDeque<Pair<View, Int>>()
        stack += root to 0
        var visited = 0
        while (stack.isNotEmpty() && visited++ < DOUYIN_LAYOUT_MAX_VIEWS) {
            val (view, depth) = stack.removeLast()
            if (view.contentDescription?.toString()?.contains("已选中，团购") == true) {
                selected = true
            }
            if (viewResourceEntryName(view) == "ehh" && view.isShown &&
                view.width >= root.width * 0.9f) content = view
            if (viewResourceEntryName(view) == "jwn" && view.isShown && view.isClickable &&
                view.width in 1..(root.width * 0.2f).toInt()) {
                backToTop = view
            }
            if (view is ViewGroup && depth < DOUYIN_LAYOUT_MAX_DEPTH) {
                for (index in 0 until view.childCount) stack += view.getChildAt(index) to (depth + 1)
            }
        }
        if (!selected) {
            douyinGroupBuyClipParentRef?.get()?.takeIf { it.isAttachedToWindow }?.let { parent ->
                if (parent.paddingBottom != douyinGroupBuyClipParentBottomPadding) {
                    parent.setPadding(parent.paddingLeft, parent.paddingTop, parent.paddingRight,
                        douyinGroupBuyClipParentBottomPadding)
                }
                parent.clipToPadding = douyinGroupBuyClipParentClipToPadding
            }
            douyinGroupBuyClipParentRef = null
            douyinGroupBuyBackToTopRef?.get()?.takeIf { it.isAttachedToWindow }?.let {
                it.translationY = douyinGroupBuyBackToTopBaseTranslation
            }
            douyinGroupBuyBackToTopRef = null
            return
        }
        val previousButton = douyinGroupBuyBackToTopRef?.get()
        if (backToTop !== previousButton) {
            if (previousButton != null && previousButton.isAttachedToWindow) {
                previousButton.translationY = douyinGroupBuyBackToTopBaseTranslation
            }
            douyinGroupBuyBackToTopRef = backToTop?.let { WeakReference(it) }
            douyinGroupBuyBackToTopBaseTranslation = backToTop?.translationY ?: 0f
        }
        backToTop?.let { button ->
            val rootPos = IntArray(2).also(root::getLocationOnScreen)
            val buttonPos = IntArray(2).also(button::getLocationOnScreen)
            val originalBottom = buttonPos[1] - rootPos[1] + button.height -
                (button.translationY - douyinGroupBuyBackToTopBaseTranslation)
            val visibleBarTop = root.height -
                currentBarHeightPx -
                (21f * density).roundToInt()
            val targetBottom = visibleBarTop - (8f * density).roundToInt()
            val targetTranslation = douyinGroupBuyBackToTopBaseTranslation +
                (targetBottom - originalBottom).coerceAtMost(0f)
            if (kotlin.math.abs(button.translationY - targetTranslation) > 1f) {
                button.translationY = targetTranslation
            }
        }
        val groupBuy = content ?: return
        (groupBuy.parent as? ViewGroup)?.let { parent ->
            val previousParent = douyinGroupBuyClipParentRef?.get()
            if (parent !== previousParent) {
                douyinGroupBuyClipParentRef = WeakReference(parent)
                douyinGroupBuyClipParentBottomPadding = parent.paddingBottom
                douyinGroupBuyClipParentClipToPadding = parent.clipToPadding
            }
            if (parent.paddingBottom != 0) {
                parent.setPadding(parent.paddingLeft, parent.paddingTop, parent.paddingRight, 0)
            }
            if (parent.clipToPadding) parent.clipToPadding = false
        }
        val rootPos = IntArray(2).also(root::getLocationOnScreen)
        val contentPos = IntArray(2).also(groupBuy::getLocationOnScreen)
        val oldBottom = contentPos[1] - rootPos[1] + groupBuy.height
        if (oldBottom >= root.height - (8f * density).roundToInt()) return

        fun fillToBottom(view: View) {
            val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
            val pos = IntArray(2).also(view::getLocationOnScreen)
            val desired = root.height - (pos[1] - rootPos[1])
            if (desired > 0 && (params.height != desired || params.bottomMargin != 0)) {
                params.height = desired
                params.bottomMargin = 0
                douyinMarginTargets.remove(view)
                view.layoutParams = params
                view.requestLayout()
            }
        }

        // The current group-buy page has two reserved native-tab gaps: its
        // outer frame ends at 2334px and the actual list ends at 2175px.
        // Expand the page frame and every full-width list layer sharing the
        // latter edge, without touching video controls or the progress bar.
        val outer = groupBuy.parent as? View
        if (outer != null && outer.width >= root.width * 0.9f) fillToBottom(outer)
        val descendants = ArrayDeque<Pair<View, Int>>()
        descendants += groupBuy to 0
        var count = 0
        while (descendants.isNotEmpty() && count++ < 500) {
            val (view, depth) = descendants.removeLast()
            val pos = IntArray(2).also(view::getLocationOnScreen)
            val bottom = pos[1] - rootPos[1] + view.height
            if (view.width >= root.width * 0.9f && view.height >= root.height * 0.25f &&
                kotlin.math.abs(bottom - oldBottom) <= (3f * density).roundToInt()) {
                fillToBottom(view)
            }
            if (view is ViewGroup && depth < 12) {
                for (index in 0 until view.childCount) descendants += view.getChildAt(index) to (depth + 1)
            }
        }
    }

    private fun adjustDouyinMessagePage(root: ViewGroup) {
        var messageTitle: View? = null
        var messageList: View? = null
        val stack = ArrayDeque<Pair<View, Int>>()
        stack += root to 0
        var visited = 0
        while (stack.isNotEmpty() && visited++ < DOUYIN_LAYOUT_MAX_VIEWS) {
            val (view, depth) = stack.removeLast()
            when (viewResourceEntryName(view)) {
                "tv_title" -> if (view is android.widget.TextView &&
                    view.text?.toString() == "消息" && view.isShown) messageTitle = view
                "zny" -> if (view.isShown && view.width >= root.width * 0.9f &&
                    view.height >= root.height * 0.4f) messageList = view
            }
            if (view is ViewGroup && depth < DOUYIN_LAYOUT_MAX_DEPTH) {
                for (index in 0 until view.childCount) stack += view.getChildAt(index) to (depth + 1)
            }
        }
        val list = messageList ?: return
        val title = messageTitle ?: return
        fun underMessageHost(view: View): Boolean {
            var current: View? = view
            while (current != null && current !== root) {
                if (viewResourceEntryName(current) == "p0+") return true
                current = current.parent as? View
            }
            return false
        }
        if (!underMessageHost(title) || !underMessageHost(list)) return
        val rootPos = IntArray(2).also(root::getLocationOnScreen)
        val firstRow = (list as? ViewGroup)?.getChildAt(0)
        if (firstRow != null) {
            val rowTop = IntArray(2).also(firstRow::getLocationOnScreen)[1] - rootPos[1]
            val previous = douyinMessageListMotion[list]
            val now = SystemClock.uptimeMillis()
            if (previous == null || kotlin.math.abs(previous.first - rowTop) >
                (2f * density).roundToInt()) {
                douyinMessageListMotion[list] = rowTop to now
                if (previous != null) return
            } else if (now - previous.second < 180L) {
                return
            }
        }
        val chain = ArrayList<View>(6)
        var current: View? = list
        while (current != null && viewResourceEntryName(current) != "p0+") {
            val parent = current.parent as? ViewGroup ?: break
            if (current.width >= root.width * 0.9f) {
                val position = IntArray(2).also(current::getLocationOnScreen)
                val currentTop = position[1] - rootPos[1]
                val baselineTop = douyinMessageBaselineTops.getOrPut(current) { currentTop }
                // Pull-to-refresh moves this chain temporarily. Let Douyin own
                // those frames instead of changing its height every pre-draw.
                if (kotlin.math.abs(currentTop - baselineTop) >
                    (3f * density).roundToInt()) return
                chain += current
            }
            current = parent
        }
        for (view in chain) {
            val parent = view.parent as? ViewGroup ?: continue
            val baselineTop = douyinMessageBaselineTops[view] ?: continue
                val desiredHeight = root.height - baselineTop
                val params = view.layoutParams as? ViewGroup.MarginLayoutParams
                if (params != null && desiredHeight > 0 &&
                    (params.height != desiredHeight || params.bottomMargin != 0)) {
                    params.height = desiredHeight
                    params.bottomMargin = 0
                    view.layoutParams = params
                    view.requestLayout()
                }
                if (parent.paddingBottom != 0) {
                    parent.setPadding(parent.paddingLeft, parent.paddingTop, parent.paddingRight, 0)
                }
                if (parent.clipToPadding) parent.clipToPadding = false
        }
    }

    private fun adjustDouyinLocationCommentGap(root: ViewGroup) {
        fun isLocationComment(content: ViewGroup): Boolean {
            if (viewResourceEntryName(content) != "q68") return false
            var parent: View? = content.parent as? View
            while (parent != null) {
                if (parent.javaClass.simpleName == "CommentNestedLayout" &&
                    viewResourceEntryName(parent) == "root_layout") return true
                parent = parent.parent as? View
            }
            return false
        }
        fun children(content: ViewGroup): Triple<View, View, View>? {
            var card: View? = null
            var tabs: View? = null
            var pager: View? = null
            for (i in 0 until content.childCount) {
                val child = content.getChildAt(i)
                when (viewResourceEntryName(child)) {
                    "fqc" -> card = child
                    "fr_" -> tabs = child
                    "x7f" -> pager = child
                }
            }
            return if (card != null && tabs != null && pager != null) Triple(card, tabs, pager)
                else null
        }
        val rootPos = IntArray(2).also(root::getLocationOnScreen)
        var content = douyinLocationCommentRef?.get()?.takeIf { candidate ->
            candidate.isAttachedToWindow && candidate.isShown &&
                isLocationComment(candidate) && children(candidate) != null &&
                IntArray(2).also(candidate::getLocationOnScreen)[1] - rootPos[1] < root.height
        }
        if (content == null) {
            val stack = ArrayDeque<Pair<View, Int>>()
            stack += root to 0
            var visited = 0
            while (stack.isNotEmpty() && visited++ < DOUYIN_LAYOUT_MAX_VIEWS) {
                val (view, depth) = stack.removeLast()
                if (view is ViewGroup && view.isShown && isLocationComment(view) &&
                    children(view) != null &&
                    IntArray(2).also(view::getLocationOnScreen)[1] - rootPos[1] < root.height) {
                    content = view
                    douyinLocationCommentRef = WeakReference(view)
                    break
                }
                if (view is ViewGroup && depth < DOUYIN_LAYOUT_MAX_DEPTH) {
                    for (i in 0 until view.childCount) stack += view.getChildAt(i) to (depth + 1)
                }
            }
        }
        val (card, tabs, _) = children(content ?: return) ?: return
        val params = card.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val cardTop = IntArray(2).also(card::getLocationOnScreen)[1]
        var visibleContentBottom = 0
        val descendants = ArrayDeque<Pair<View, Int>>()
        descendants += card to 0
        var examined = 0
        while (descendants.isNotEmpty() && examined++ < 120) {
            val (child, depth) = descendants.removeLast()
            if (child !== card && child.isShown && child.width > 0 && child.height > 0) {
                val meaningful = when (child) {
                    is android.widget.TextView -> !child.text.isNullOrBlank()
                    is android.widget.ImageView -> child.drawable != null
                    else -> false
                }
                if (meaningful) {
                    val bottom = IntArray(2).also(child::getLocationOnScreen)[1] + child.height
                    visibleContentBottom = maxOf(visibleContentBottom, bottom)
                }
            }
            if (child is ViewGroup && depth < 8) {
                for (i in 0 until child.childCount) descendants += child.getChildAt(i) to (depth + 1)
            }
        }
        val normalGap = (24f * density).roundToInt()
        val excessiveGap = (96f * density).roundToInt()
        if (visibleContentBottom > cardTop &&
            cardTop + card.height - visibleContentBottom > excessiveGap) {
            val desiredHeight = visibleContentBottom - cardTop + normalGap
            params.height = desiredHeight
            card.layoutParams = params
            card.requestLayout()
            douyinVideoDiag("location comment card ${card.height} -> $desiredHeight")
            return
        }
        val cardBottom = IntArray(2).also(card::getLocationOnScreen)[1] + card.height
        val tabsTop = IntArray(2).also(tabs::getLocationOnScreen)[1]
        val gap = tabsTop - cardBottom
        if (gap > excessiveGap && params.bottomMargin > excessiveGap) {
            params.bottomMargin = normalGap
            card.layoutParams = params
            card.requestLayout()
            douyinVideoDiag("location comment gap $gap -> $normalGap")
        }
    }

    private fun adjustDouyinCouponPage(
        root: ViewGroup,
        clearanceTop: Int,
        forceSearch: Boolean = false,
    ) {
        val rootLocation = IntArray(2).also(root::getLocationOnScreen)
        var coupon = douyinCouponPageRef?.get()?.takeIf { view ->
            view.isAttachedToWindow && view.isShown && view.width >= root.width * 0.85f &&
                IntArray(2).also(view::getLocationOnScreen).let { location ->
                    kotlin.math.abs(location[0] - rootLocation[0]) < root.width * 0.5f
                }
        }
        if (coupon == null && forceSearch) {
            val stack = ArrayDeque<Pair<View, Int>>()
            stack += root to 0
            var visited = 0
            while (stack.isNotEmpty() && visited++ < DOUYIN_LAYOUT_MAX_VIEWS) {
                val (view, depth) = stack.removeLast()
                if (view.javaClass.simpleName == "0wNK" && view.isShown &&
                    view.width >= root.width * 0.85f) {
                    coupon = view
                    douyinCouponPageRef = WeakReference(view)
                    break
                }
                if (view is ViewGroup && depth < 24) {
                    for (index in 0 until view.childCount) stack += view.getChildAt(index) to (depth + 1)
                }
            }
        }
        val page = coupon ?: return
        val pageLocation = IntArray(2).also(page::getLocationOnScreen)
        val pageTop = pageLocation[1] - rootLocation[1]
        val barTop = clearanceTop - (21f * density).roundToInt()
        val desiredHeight = (barTop - pageTop).coerceAtLeast(1)
        val params = page.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (kotlin.math.abs(params.height - desiredHeight) > 1 || params.bottomMargin != 0) {
            params.height = desiredHeight
            params.bottomMargin = 0
            page.layoutParams = params
            page.requestLayout()
        }
    }



    /**

     * DouyinLiquidGlass-style padding avoidance: find the bottom-most compact

     * controls inside each overlay container, then grow the container's bottom

     * padding so the native content line clears the liquid bar's top edge.

     * Containers are measured once (cached) and restored after a retention

     * grace once a page gets recycled.

     */

    private fun douyinWriteDiagDump(
        root: ViewGroup,
        pager: View?,
        lifted: Collection<View>,
        progress: View?,
        overlayRoots: Map<View, View>,
        clearanceTop: Int,
    ) {
        val now = SystemClock.uptimeMillis()
        if (now - lastDouyinDiagFileAt < 3000L) return
        lastDouyinDiagFileAt = now
        try {
            fun rname(v: View): String = runCatching {
                if (v.id == 0) "-" else v.resources.getResourceEntryName(v.id)
            }.getOrDefault("-")

            fun line(v: View, prefix: String, withLoc: Boolean = true): String {
                val sb = StringBuilder(prefix)
                sb.append(v.javaClass.simpleName)
                sb.append(" ").append(v.width).append("x").append(v.height)
                if (withLoc) {
                    val loc = IntArray(2); v.getLocationOnScreen(loc)
                    sb.append(" top=").append(loc[1]).append(" bot=").append(loc[1] + v.height)
                }
                sb.append(" id=").append(rname(v))
                val lp = v.layoutParams as? ViewGroup.MarginLayoutParams
                if (lp != null) sb.append(" m=").append(lp.bottomMargin)
                if (v is android.widget.TextView) {
                    val t = v.text?.toString()?.take(12) ?: ""
                    if (t.isNotBlank()) sb.append(" txt=\"").append(t).append("\"")
                }
                return sb.toString()
            }

            fun ancestry(v: View): String {
                val chain = ArrayList<String>()
                var cur: android.view.ViewParent? = v.parent
                while (cur is View && chain.size < 8) {
                    val cv = cur as View
                    chain.add(cv.javaClass.simpleName + "/" + rname(cv))
                    cur = cur.parent
                }
                return chain.joinToString(" <- ")
            }

            val sb = StringBuilder()
            sb.append("feedCornerMasks suppressed=").append(feedCornerMasks.suppressedCount).append("\n")
            sb.append("== douyin diag root=").append(line(root, "", false))
                .append(" clear=").append(clearanceTop).append("\n")
            pager?.let { p ->
                sb.append("pager ").append(line(p, "", false)).append("\n")
                val pg = p as? ViewGroup
                val count = pg?.childCount ?: 0
                sb.append("  pages=").append(count).append("\n")
                for (i in 0 until count) {
                    val page = pg!!.getChildAt(i)
                    if (page.visibility != View.VISIBLE || page.width <= 0) continue
                    sb.append("  page").append(i).append(" ").append(line(page, ""))
                        .append(" ad=").append(douyinPageLooksLikeAdvertisement(page))
                        .append("\n")
                    if (page is ViewGroup) {
                        for (j in 0 until page.childCount.coerceAtMost(24)) {
                            val c = page.getChildAt(j)
                            sb.append("    c").append(j).append(" ").append(line(c, "")).append("\n")
                            if (c is ViewGroup) {
                                for (k in 0 until c.childCount.coerceAtMost(10)) {
                                    val cc = c.getChildAt(k)
                                    sb.append("      cc").append(k).append(" ").append(line(cc, "")).append("\n")
                                    if (cc is ViewGroup) {
                                        for (l in 0 until cc.childCount.coerceAtMost(8)) {
                                            val ccc = cc.getChildAt(l)
                                            sb.append("        ccc").append(l).append(" ").append(line(ccc, "")).append("\n")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            sb.append("lifted=").append(lifted.size).append("\n")
            lifted.forEach { l ->
                sb.append("  lift ").append(line(l, "")).append(" anc=[").append(ancestry(l)).append("]\n")
            }
            sb.append("progress=").append(progress?.let { line(it, "") + " anc=[" + ancestry(it) + "]" } ?: "null").append("\n")
            sb.append("overlayRoots=").append(overlayRoots.size).append("\n")
            overlayRoots.keys.forEach { o ->
                sb.append("  ov ").append(line(o, "")).append(" anc=[").append(ancestry(o)).append("]\n")
            }
            sb.append("==end==\n")
            val dir = context.getExternalFilesDir(null)
                ?: return
            java.io.File(dir, "dy_dump.txt").writeText(sb.toString())
        } catch (t: Throwable) {
        }
    }

    /**
     * File-backed diagnostics for the Douyin video sampling chain. Appends to
     * a capped in-memory buffer and flushes to dy_video_diag.txt in the app's
     * external files dir on a throttle, so the chain stays observable even
     * when the ROM clips the logcat main buffer.
     */
    private fun douyinVideoDiag(msg: String) {
        val now = SystemClock.uptimeMillis()
        try {
            if (douyinVideoDiagBuffer.length > 16_384) {
                douyinVideoDiagBuffer.setLength(0)
                douyinVideoDiagBuffer.append("(buffer reset)\n")
            }
            douyinVideoDiagBuffer.append(now).append(' ').append(msg).append('\n')
            if (now - lastDouyinVideoDiagFlushAt >= DOUYIN_VIDEO_DIAG_FLUSH_MS) {
                lastDouyinVideoDiagFlushAt = now
                val dir = context.getExternalFilesDir(null) ?: return
                java.io.File(dir, "dy_video_diag.txt").writeText(douyinVideoDiagBuffer.toString())
            }
        } catch (t: Throwable) {
        }
    }

    fun logDiagnostic(message: String) = douyinVideoDiag(message)

    private fun applyDouyinFeedPadding(containers: Collection<View>, clearanceTop: Int) {

        val root = currentRoot ?: return

        val now = SystemClock.uptimeMillis()

        val retained = HashSet<View>()

        containers.forEach { container ->

            if (!container.isAttachedToWindow) return@forEach

            retained.add(container)

            val original = douyinPaddingTargets.getOrPut(container) { container.paddingBottom }

            val cLoc = IntArray(2).also { container.getLocationOnScreen(it) }



            val rLoc = IntArray(2).also { root.getLocationOnScreen(it) }



            val line = cLoc[1] - rLoc[1] + container.height



            if (line <= 0) return@forEach

            val demand = (line - clearanceTop +



                (DOUYIN_PADDING_EXTRA_LIFT_DP * density).roundToInt())



                .coerceAtLeast(0)



            val lockedInset = douyinPaddingInsetLock[container]



            // Zero is never locked: a stale zero target (measured before

            // layout settled) must be recomputed every frame until the

            // controls actually lift.



            val requiredInset = if (lockedInset != null && lockedInset > 0 &&



                kotlin.math.abs(demand - lockedInset) < (24f * density).toInt()



            ) {



                lockedInset



            } else {



                demand.coerceIn(0, (220f * density).toInt())



            }



            douyinPaddingInsetLock[container] = requiredInset



            val target = original + requiredInset

            if (container.paddingBottom != target) {

                container.setPadding(

                    container.paddingLeft,

                    container.paddingTop,

                    container.paddingRight,

                    target,

                )

                val nowMs = SystemClock.uptimeMillis()

                if (nowMs - lastDouyinLiftLogAt >= 2000L) {

                    lastDouyinLiftLogAt = nowMs

                    android.util.Log.i(

                        "LiquidTab",

                        "douyin lift container=${container.javaClass.simpleName} " +

                            "${container.width}x${container.height} " +

                            "line=$line clearance=$clearanceTop " +

                            "pad=$original->$target",

                    )

                }

            }

            douyinPaddingLastSeen[container] = now

        }

        val iterator = douyinPaddingTargets.entries.iterator()

        while (iterator.hasNext()) {

            val (container, original) = iterator.next()

            if (container in retained) continue

            if (!container.isAttachedToWindow ||

                now - (douyinPaddingLastSeen[container] ?: 0L) > 2500L

            ) {

                if (container.isAttachedToWindow && container.paddingBottom != original) {

                    container.setPadding(

                        container.paddingLeft,

                        container.paddingTop,

                        container.paddingRight,

                        original,

                    )

                }

                douyinPaddingLastSeen.remove(container)



                douyinPaddingInsetLock.remove(container)



                douyinContentLines.remove(container)

                iterator.remove()

            }

        }

    }

    /** Translate a floating capsule (related-search pill etc.) up as a whole
     *  so its bottom clears the reserved space without changing its shape. */
    /** Raise a floating control by growing its bottom margin instead of

     *  translationY, so Douyin per-frame animations cannot overwrite it.

     *  The lift derives from the layout position without the current

     *  margin, which keeps the target self-stable frame to frame. */

    private fun liftDouyinControlByMargin(
        view: View,
        root: View,
        clearanceTop: Int,
        extraLiftPx: Int = 0,
        lockLift: Boolean = true,
    ) {

        if (            (douyinIsUnderFeaturedFeedRoot(view) || douyinIsUnderMessagePage(view))) {
            DouyinControlMarginGuard.release(view)
            val previousLift = douyinMarginTargets.remove(view)
            val params = view.layoutParams as? ViewGroup.MarginLayoutParams
            if (previousLift != null && params?.bottomMargin == previousLift) {
                params.bottomMargin = 0
                view.layoutParams = params
            }
            return
        }



        if (!view.isAttachedToWindow || view.width <= 0 || view.height <= 0 ||
            (                (viewResourceEntryName(view) in setOf("s3m", "i=-") ||
                    douyinIsFeaturedFeedRoot(view) ||
                    douyinIsLocalEmptyContent(view)))
        ) return



        val lp = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return



        val gap = (DOUYIN_CONTROL_SAFE_GAP_DP * density).roundToInt()



        val desiredBottom = clearanceTop - gap - extraLiftPx



        val pos = IntArray(2).also { view.getLocationOnScreen(it) }



        val rootPos = IntArray(2).also { root.getLocationOnScreen(it) }
        // Vertical offset of the video page owning this control (0 when the
        // page is settled in the viewport). Measuring against the settled
        // page instead of the live position keeps the lift margin constant
        // while the page scrolls, so the controls ride the video smoothly
        // instead of stepping down at every lock release mid-swipe.
        val pageOffset = douyinVideoPageOffset(view, rootPos)
        val ownedVideoControls = view !== douyinCachedProgressRef?.get() &&
            !douyinIsProgressContainer(view) && (douyinIsVideoControlsRoot(view) ||
                douyinPageControlsCache.values.any { it === view })





        val bottomNoMargin = if (lp.height == ViewGroup.LayoutParams.MATCH_PARENT) {
            // Full-height overlay: removing the margin restores the parent
            // bottom, so the lift target must be measured against the parent
            // edge instead of the current (already-margined) position.
            val parentBottom = (view.parent as? View)?.let { p ->
                val ploc = IntArray(2).also { p.getLocationOnScreen(it) }
                ploc[1] - rootPos[1] + p.height
            }
            (parentBottom ?: (pos[1] - rootPos[1] + view.height - lp.bottomMargin)) - pageOffset
        } else {
            // Bottom-anchored, fixed-height controls move up by their margin.
            // Add it back to recover the unlifted edge; subtracting it causes
            // a 0 -> lift -> 0 oscillation. Keep the progress path unchanged.
            val marginCorrection = if (ownedVideoControls) lp.bottomMargin else -lp.bottomMargin
            pos[1] - rootPos[1] + view.height + marginCorrection - pageOffset
        }



        val lift = (bottomNoMargin - desiredBottom).coerceAtLeast(0)



        val target = if (lift <= 0) 0 else lift.coerceAtMost((220f * density).toInt())



        val locked = douyinMarginTargets[view]



        // Zero is never locked: a stale zero target measured before layout

        // settled must be recomputed every frame until the control lifts.

        if (locked != null && locked > 0 &&

            kotlin.math.abs(target - locked) < (24f * density).toInt()

        ) {



            // Stay locked; re-apply only if the app reset the margin.
            if (ownedVideoControls) DouyinControlMarginGuard.own(view, locked)



            if (lp.bottomMargin != locked) {



                lp.bottomMargin = locked



                view.requestLayout()



            }



            return



        }



        if (ownedVideoControls) DouyinControlMarginGuard.own(view, target)
        if (lp.bottomMargin != target) {



            lp.bottomMargin = target



            view.requestLayout()



        }



        if (target > 0) douyinMarginTargets[view] = target



        else douyinMarginTargets.remove(view)



    }





    /**
     * Vertical offset of the video page that owns [control], relative to
     * the root: 0 when the page is settled in the viewport, non-zero
     * mid-swipe. Measuring against the settled page keeps the lift margin
     * constant while the page scrolls, so the controls ride the video
     * smoothly instead of stepping down at every lock release.
     */
    private fun douyinVideoPageOffset(control: View, rootPos: IntArray): Int {
        var page: View? = control
        var cur = control.parent
        while (cur is View) {
            val cv = cur
            if (cv is ViewGroup && (cv.javaClass.name.contains("ViewPager") ||
                cv.javaClass.name.contains("Pager"))
            ) {
                val loc = IntArray(2).also { page!!.getLocationOnScreen(it) }
                return loc[1] - rootPos[1]
            }
            page = cv
            cur = cv.parent
        }
        return 0
    }



    private fun liftDouyinControl(view: View, root: View, clearanceTop: Int) {

        if (!view.isAttachedToWindow || view.width <= 0 || view.height <= 0) return

        val pos = IntArray(2).also { view.getLocationOnScreen(it) }

        val rootPos = IntArray(2).also { root.getLocationOnScreen(it) }

        val gap = (DOUYIN_CONTROL_SAFE_GAP_DP * density).roundToInt()

        val desiredBottom = clearanceTop - gap

        val currentBottom = pos[1] - rootPos[1] + view.height

        val lift = (currentBottom - desiredBottom).coerceAtLeast(0)

        if (view.translationY != -lift.toFloat()) {

            view.translationY = -lift.toFloat()

        }

    }



    private fun measureDouyinContentLine(container: View, root: View): Int {

        if (root.width <= 0) return 0

        val rootPos = IntArray(2).also { root.getLocationOnScreen(it) }

        var line = 0

        var visited = 0

        val stack = ArrayDeque<Pair<View, Int>>()

        stack += container to 0

        while (stack.isNotEmpty() && visited++ < DOUYIN_LAYOUT_MAX_VIEWS) {

            val (view, depth) = stack.removeLast()

            if (view !== container && view.width > 0 && view.height > 0 && view.isShown) {

                val pos = IntArray(2).also { view.getLocationOnScreen(it) }

                val left = pos[0] - rootPos[0]

                // Ignore any translation so a padding-moved control is not

                // interpreted as the original content line again.

                val top = (pos[1] - rootPos[1] - view.translationY).roundToInt()

                val right = left + view.width

                val bottom = top + view.height

                val compact = view.height <= (220f * density).toInt()

                val narrow = view.width < root.width * 0.95f

                if (compact && narrow && left >= 0 && right <= root.width &&

                    top >= 0 && bottom <= root.height

                ) {

                    if (bottom > line) line = bottom

                }

            }

            if (view is ViewGroup && depth < DOUYIN_LAYOUT_MAX_DEPTH) {

                for (index in 0 until view.childCount) {

                    stack += view.getChildAt(index) to (depth + 1)

                }

            }

        }

        return line

    }



    private fun findDouyinFullSizeParent(view: View, root: View): View? {

        if (root.width <= 0) return null

        var current = view.parent as? View

        while (current != null && current !== root) {

            if (current.width >= root.width * 0.8f &&

                current.height >= root.height * 0.5f

            ) return current

            current = current.parent as? View

        }

        return null

    }



    /**

     * The ID-based control root sometimes also wraps the full-screen video

     * Surface. Padding it as a whole lifts the video with the controls, leaving

     * a void above the screen bottom. Drilling down to the innermost descendant

     * that still owns the bottom controls but excludes the video Surface keeps

     * the video pinned to the screen edge while only the controls rise.

     */

    private fun douyinVideoExcludedControlContainer(

        overlay: View,

        root: View,

        rootLoc: IntArray,

        maxCompact: Int,

    ): View? {

        if (root.width <= 0 || root.height <= 0) return null

        var best: View? = null

        var bestDepth = -1

        var bestArea = 0L

        val stack = ArrayDeque<Pair<View, Int>>()

        stack += overlay to 0

        var visited = 0

        while (stack.isNotEmpty() && visited++ < DOUYIN_LAYOUT_MAX_VIEWS) {

            val (view, depth) = stack.removeLast()

            if (view !== overlay && view is ViewGroup &&

                depth < DOUYIN_LAYOUT_MAX_DEPTH && view.visibility == View.VISIBLE &&

                view.alpha > 0.001f && view.width > 0 && view.height > 0 &&

                view.width >= root.width * 0.5f

            ) {

                val excluded = !douyinContainerHostsVideoSurface(view, root)

                val ownsCompact = douyinBottomCompactChildCount(view, root, rootLoc, maxCompact) >= 1

                if (excluded && ownsCompact) {

                    // Prefer the deepest such container so only the controls are

                    // lifted; tie-break on larger area for a stable full-width host.

                    val area = view.width.toLong() * view.height.toLong()

                    if (depth > bestDepth || (depth == bestDepth && area > bestArea)) {

                        bestDepth = depth

                        bestArea = area

                        best = view

                    }

                }

            }

            if (view is ViewGroup && depth < DOUYIN_LAYOUT_MAX_DEPTH) {

                for (i in 0 until view.childCount) stack += view.getChildAt(i) to (depth + 1)

            }

        }

        return best

    }



    /**

     * Geometry fallback for the overlay-control lift. Douyin's obfuscated

     * resource ids rotate between releases, so the id scan can miss entirely.

     * A full-size container that owns compact bottom controls and does not

     * host the video surface matches the feed overlay root's layout signature.

     */

    /**

     * Find the controls container inside one feed page: full-width,

     * bottom anchored, does not host the video surface, owns compact

     * bottom controls. Raising this container moves the controls within

     * the page coordinate space, so they stay glued to the video while

     * the page slides, preserving the native relative motion.

     */

    private fun findPageControlsContainer(page: View, root: View): View? {



        if (page !is ViewGroup || root.width <= 0 || root.height <= 0) return null



        val rootLoc = IntArray(2).also { root.getLocationInWindow(it) }



        val maxCompact = (220f * density).toInt()



        val anchored = (40f * density).toInt()



        var best: View? = null



        var bestScore = Int.MIN_VALUE



        val stack = ArrayDeque<Pair<View, Int>>()



        stack += page to 0



        var visited = 0



        while (stack.isNotEmpty() && visited++ < DOUYIN_LAYOUT_MAX_VIEWS) {



            val (view, depth) = stack.removeLast()



            if (view !== page && view is ViewGroup &&



                depth < DOUYIN_LAYOUT_MAX_DEPTH && view.visibility == View.VISIBLE &&



                view.alpha > 0.001f && view.width >= root.width * 0.9f &&



                !douyinIsProgressContainer(view) &&

                !douyinIsFeedContentFrame(view) &&



                view.javaClass.simpleName != "MainBottomTabContainer"



            ) {



                val loc = IntArray(2).also { view.getLocationInWindow(it) }



                val bottom = loc[1] - rootLoc[1] + view.height



                if (bottom < root.height - anchored) continue



                if (!douyinContainerHostsVideoSurface(view, root)) {



                    val count = douyinBottomCompactChildCount(view, root, rootLoc, maxCompact)



                    if (count >= 1) {



                        val score = count * 100 - depth



                        if (score > bestScore) {



                            bestScore = score



                            best = view



                        }



                    }



                }



            }



            if (view is ViewGroup && depth < DOUYIN_LAYOUT_MAX_DEPTH) {



                for (k in 0 until view.childCount) stack += view.getChildAt(k) to (depth + 1)



            }



        }



        return best



    }

    private fun douyinIsProgressContainer(view: View): Boolean {
        val name = view.javaClass.name
        if (view is SeekBar || name.contains("SeekBar", ignoreCase = true) ||
            name.contains("Progress", ignoreCase = true) ||
            name.contains("Scrubber", ignoreCase = true)
        ) return true

        val idName = viewResourceEntryName(view)
        return idName == DOUYIN_PROGRESS_ID || idName == DOUYIN_PROGRESS_ID_ALT ||
            idName in DOUYIN_PROGRESS_NAME_SET
    }




    /**

     * Geometry fallback for the overlay-control lift. Douyin obfuscates

     * resource ids between builds, so the id scan can miss entirely. A

     * full-size container that owns compact bottom controls and does not

     * host the video surface matches the feed overlay root signature.

     * Prefers a full-width sibling of the feed pager: page-agnostic, so it

     * is not rebuilt when the user swipes and the lift target stays fixed.

     */

    private fun findDouyinOverlayRootByGeometry(root: ViewGroup, pager: View?): View? {



        if (root.width <= 0 || root.height <= 0) return null



        val rootLoc = IntArray(2).also { root.getLocationInWindow(it) }



        val maxCompact = (220f * density).toInt()



        val anchored = (40f * density).toInt()



        val pagerParent = pager?.parent as? ViewGroup



        if (pagerParent != null) {



            val pagerIndex = pagerParent.indexOfChild(pager)



            for (i in pagerParent.childCount - 1 downTo 0) {



                if (i == pagerIndex) continue



                val child = pagerParent.getChildAt(i)



                if (child.visibility != View.VISIBLE || child.alpha <= 0.001f) continue



                if (child.width < root.width * 0.9f) continue



                if (douyinContainerHostsVideoSurface(child, root)) continue



                val loc = IntArray(2).also { child.getLocationInWindow(it) }



                val bottom = loc[1] - rootLoc[1] + child.height



                if (bottom < root.height - anchored) continue



                if (douyinBottomCompactChildCount(child, root, rootLoc, maxCompact) >= 1) {



                    return child



                }



            }



        }



        var best: View? = null



        var bestScore = Int.MIN_VALUE



        val stack = ArrayDeque<Pair<View, Int>>()



        stack += root to 0



        var visited = 0



        while (stack.isNotEmpty() && visited++ < DOUYIN_LAYOUT_MAX_VIEWS) {



            val (view, depth) = stack.removeLast()



            if (view !== root && view !== pager && view is ViewGroup &&



                depth < DOUYIN_LAYOUT_MAX_DEPTH && view.visibility == View.VISIBLE &&



                view.alpha > 0.001f && view.width >= root.width * 0.9f



            ) {



                val loc = IntArray(2).also { view.getLocationInWindow(it) }



                val bottom = loc[1] - rootLoc[1] + view.height



                if (bottom < root.height - anchored) continue



                if (!douyinContainerHostsVideoSurface(view, root)) {



                    val count = douyinBottomCompactChildCount(view, root, rootLoc, maxCompact)



                    if (count >= 1) {



                        val score = count * 100 - depth



                        if (score > bestScore) {



                            bestScore = score



                            best = view



                        }



                    }



                }



            }



            if (view is ViewGroup && depth < DOUYIN_LAYOUT_MAX_DEPTH) {



                for (k in 0 until view.childCount) stack += view.getChildAt(k) to (depth + 1)



            }



        }



        return best



    }



    /**

     * Geometry fallback for the overlay-control lift. Douyin obfuscates

     * resource ids between builds; a full-size container that owns compact

     * bottom controls and does not host the video surface matches the feed

     * overlay root layout signature. Kept as an additional safety net.

     */


    private fun findDouyinOverlayContainersByGeometry(root: ViewGroup): List<View> {

        if (root.width <= 0 || root.height <= 0) return emptyList()

        val rootLoc = IntArray(2).also { root.getLocationInWindow(it) }

        val maxCompact = (220f * density).toInt()

        val matches = mutableListOf<View>()

        val stack = ArrayDeque<Pair<View, Int>>()

        stack += root to 0

        var visited = 0

        while (stack.isNotEmpty() && visited++ < DOUYIN_LAYOUT_MAX_VIEWS) {

            val (view, depth) = stack.removeLast()

            if (view !== root && view is ViewGroup && depth < DOUYIN_LAYOUT_MAX_DEPTH &&

                view.visibility == View.VISIBLE && view.alpha > 0.001f &&

                view.width >= root.width * 0.85f && view.height >= root.height * 0.40f

            ) {

                val loc = IntArray(2).also { view.getLocationInWindow(it) }

                val top = loc[1] - rootLoc[1]

                val bottom = top + view.height

                val anchored = top <= root.height * 0.08f || bottom >= root.height * 0.92f

                if (anchored &&

                    !douyinContainerHostsVideoSurface(view, root) &&

                    douyinBottomCompactChildCount(view, root, rootLoc, maxCompact) >= 2

                ) {

                    matches += view

                }

            }

            if (view is ViewGroup && depth < DOUYIN_LAYOUT_MAX_DEPTH) {

                for (i in 0 until view.childCount) stack += view.getChildAt(i) to (depth + 1)

            }

        }

        // Keep only innermost matches: padding an ancestor that already wraps a

        // matched container would double-lift the same controls.

        return matches.filter { candidate ->

            matches.none { other -> other !== candidate && douyinContainsView(candidate, other) }

        }

    }



    private fun douyinContainerHostsVideoSurface(container: View, root: View): Boolean {
        return videoContainerProbe.getOrPut(container) { findVideoContentInContainer(container, root) }
    }

    private fun findVideoContentInContainer(container: View, root: View): Boolean {

        val stack = ArrayDeque<View>()

        stack += container

        var visited = 0

        while (stack.isNotEmpty() && visited++ < DOUYIN_LAYOUT_MAX_VIEWS) {

            val view = stack.removeLast()

            if ((view is SurfaceView || view is TextureView &&
                    view.javaClass.name == "com.ss.android.ugc.playerkit.videoview.KeepSurfaceTextureView") &&
                view.width >= root.width * 0.6f &&

                view.height >= root.height * 0.5f

            ) return true

            if (view is ViewGroup) {

                for (i in 0 until view.childCount) stack += view.getChildAt(i)

            }

        }

        return false

    }



    private fun douyinBottomCompactChildCount(

        container: View,

        root: View,

        rootLoc: IntArray,

        maxCompact: Int,

    ): Int {

        var count = 0

        val stack = ArrayDeque<Pair<View, Int>>()

        stack += container to 0

        var visited = 0

        while (stack.isNotEmpty() && visited++ < DOUYIN_LAYOUT_MAX_VIEWS) {

            val (view, depth) = stack.removeLast()

            if (view !== container && view.visibility == View.VISIBLE &&

                view.alpha > 0.001f && view.width > 0 && view.height > 0

            ) {

                if (view.height <= maxCompact && view.width < root.width * 0.95f) {

                    val loc = IntArray(2).also { view.getLocationInWindow(it) }

                    val bottom = loc[1] - rootLoc[1] + view.height

                    if (bottom >= root.height * 0.55f && bottom <= root.height) count++

                }

            }

            if (view is ViewGroup && depth < DOUYIN_LAYOUT_MAX_DEPTH) {

                for (i in 0 until view.childCount) stack += view.getChildAt(i) to (depth + 1)

            }

        }

        return count

    }



    private fun douyinContainsView(container: View, descendant: View): Boolean {

        var current: View? = descendant

        while (current != null) {

            if (current === container) return true

            current = current.parent as? View

        }

        return false

    }




    fun dispose() {
        douyinMarginTargets.keys.forEach(DouyinControlMarginGuard::release)
        videoContainerProbe.clear()
        feedCornerMasks.dispose()
        douyinSpecialPageLayoutListener?.let { listener ->
            runCatching { (douyinFeedPager?.get()?.rootView as? View)?.viewTreeObserver?.removeOnGlobalLayoutListener(listener) }
        }
        douyinSpecialPageLayoutListener = null
    }

    private fun viewResourceEntryName(view: View): String? = runCatching {
        if (view.id == View.NO_ID) null else view.resources.getResourceEntryName(view.id)
    }.getOrNull()

    private fun Context.findActivity(): Activity? {
        var current: Context? = this
        repeat(12) {
            when (current) {
                is Activity -> return current as Activity
                is ContextWrapper -> current = (current as ContextWrapper).baseContext
                else -> return null
            }
        }
        return null
    }

    private companion object {
        const val DOUYIN_LAYOUT_ADJUST_INTERVAL_MS = 32L
        const val DOUYIN_LAYOUT_MAX_VIEWS = 8_192
        const val DOUYIN_LAYOUT_MAX_DEPTH = 24
        const val DOUYIN_BOTTOM_SPACE_ID = "bottom_space"
        const val DOUYIN_FEED_PAGER_ID = "viewpager"
        const val DOUYIN_CONTROLS_ROOT_ID = "i7"
        const val DOUYIN_CONTROLS_ROOT_ID_ALT = "ja"
        const val DOUYIN_PROGRESS_ID = "wje"
        const val DOUYIN_PROGRESS_ID_ALT = "xw_"
        const val DOUYIN_CONTROLS_EXTRA_LIFT_DP = 40f
        const val DOUYIN_CLASSIC_CONTROLS_EXTRA_DP = 16f
        const val DOUYIN_PROGRESS_EXTRA_LIFT_DP = 10f
        const val DOUYIN_CONTROL_SAFE_GAP_DP = 3f
        const val DOUYIN_PADDING_EXTRA_LIFT_DP = 60f
        const val DOUYIN_VIDEO_DIAG_FLUSH_MS = 2_000L
        const val DOUYIN_EDGE_TOLERANCE_PX = 4
        val DOUYIN_PROGRESS_NAME_SET = setOf("cez", "v5j", "progress_bar")
        val DOUYIN_RELATED_SEARCH_TEXT = setOf("相关搜索", "推荐搜索", "大家都在搜", "搜一搜")
    }
}
