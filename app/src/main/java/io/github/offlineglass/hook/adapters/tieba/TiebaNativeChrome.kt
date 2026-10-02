package io.github.offlineglass.hook.adapters.tieba

import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.TextView
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.adapters.AdapterOwnedOverlay
import kotlin.math.abs

private val insetSignatures = java.util.WeakHashMap<GlassHostLayout, Long>()
private var GlassHostLayout.tiebaNativeInsetSignature: Long
    get() = insetSignatures[this] ?: Long.MIN_VALUE
    set(value) { insetSignatures[this] = value }
private val GlassHostLayout.tiebaDensity: Float get() = resources.displayMetrics.density
private val TIEBA_NAV_LABELS = setOf("首页", "进吧", "小卖部", "消息", "我的")
private const val TIEBA_SOURCE_LABEL_SP = 10f

/** Tieba-only native chrome removal and full-height content repair. */
internal fun GlassHostLayout.suppressNativeTiebaBottomBar(source: ViewGroup?) {
    if (source == null) return
    clearTiebaSurface(source)
    suppressTiebaNativeTabVisualTree(source)
    var shell = source.parent as? ViewGroup
    var hops = 0
    while (shell != null && hops < 5 && shell !is GlassHostLayout) {
        clearTiebaSurface(shell)
        shell.clipChildren = false
        shell.clipToPadding = false
        shell = shell.parent as? ViewGroup
        hops++
    }
    removeTiebaNativeTabInset(source)
}

private fun GlassHostLayout.removeTiebaNativeTabInset(source: ViewGroup) {

        val root = rootView as? ViewGroup ?: return

        val rootHeight = rootView.height

        val rootWidth = rootView.width

        if (rootHeight <= 0 || rootWidth <= 0) return



        // Layout-position bar top in screen coordinates.

        val sourceParent = source.parent as? ViewGroup

        val barTop = if (sourceParent != null) {

            val parentLoc = IntArray(2).also { sourceParent.getLocationOnScreen(it) }

            parentLoc[1] + source.top

        } else {

            -1

        }

        if (barTop <= 0) return



        // Neither the source row NOR any of its ancestors may be touched.

        // Once the window chain is stretched full-height the bar shell

        // itself becomes a short, full-width, bottom-flush view - exactly

        // what the scrim rule below hunts - and hiding it collapsed

        // isShown() across the whole strip, the glass panel self-hid and

        // the bottom bar vanished entirely (v7 regression).

        val barChain = HashSet<View>()

        var chainNode: View? = source

        while (chainNode != null) {

            barChain += chainNode

            chainNode = chainNode.parent as? View

        }



        // Tieba re-applies parts of its bottom-bar layout during scroll and

        // theme changes.  Mutating margins/heights on every pre-draw feeds

        // that loop and makes the glass host jump vertically.  Do the

        // structural inset fix only when the measured geometry changes, while

        // still clearing visual leftovers every frame.

        val signature = ((((((rootWidth.toLong() * 31L + rootHeight.toLong()) * 31L +

            barTop.toLong()) * 31L + source.width.toLong()) * 31L +

            source.height.toLong()) * 31L) + (sourceParent?.height ?: 0).toLong())

        val needsStructuralPass = signature != tiebaNativeInsetSignature



        if (needsStructuralPass) {

            // 1. Stretch the window chain: Tieba's root chain stops ~65 px above

            // the screen bottom (nav-bar inset), leaving the flat scrim zone

            // visible under the glass panel. Never touch GlassHostLayout itself.

            var ancestor: ViewGroup? = source.parent as? ViewGroup

            while (ancestor != null && ancestor !== root) {

                if (ancestor.height > 1000 && ancestor.height < rootHeight - 20) {

                    if (ancestor.paddingBottom > 40) {

                        ancestor.setPadding(

                            ancestor.paddingLeft,

                            ancestor.paddingTop,

                            ancestor.paddingRight,

                            0,

                        )

                    }

                    val lp = ancestor.layoutParams

                    if (lp != null) {

                        var changed = false

                        if (lp is ViewGroup.MarginLayoutParams && lp.bottomMargin != 0) {

                            lp.bottomMargin = 0

                            changed = true

                        }

                        if (lp.height != ViewGroup.LayoutParams.MATCH_PARENT) {

                            lp.height = ViewGroup.LayoutParams.MATCH_PARENT

                            changed = true

                        }

                        if (changed) ancestor.layoutParams = lp

                    }

                }

                ancestor = ancestor.parent as? ViewGroup

            }

            if (root.paddingBottom > 40) {

                root.setPadding(root.paddingLeft, root.paddingTop, root.paddingRight, 0)

            }



            stretchTiebaContentToBottom(root, source, barTop, rootHeight, barChain)

            tiebaNativeInsetSignature = signature

        }



        suppressTiebaNativeBottomArtifacts(root, source, barTop, rootHeight, barChain)

    }



internal fun GlassHostLayout.clearTiebaSurface(view: View) {

        view.background = null

        view.backgroundTintList = null

        view.foreground = null

        view.elevation = 0f

        view.translationZ = 0f

        view.stateListAnimator = null

    }



private fun GlassHostLayout.clearTiebaSurfaceRecursive(view: View) {

        clearTiebaSurface(view)

        if (view is ViewGroup) {

            for (i in 0 until view.childCount) {

                clearTiebaSurfaceRecursive(view.getChildAt(i))

            }

        }

    }



private fun GlassHostLayout.carriesTiebaNavigationContent(view: View): Boolean {

        if (view is android.widget.ImageView || view is android.widget.TextView) return true

        if (view !is ViewGroup) return false

        for (i in 0 until view.childCount) {

            if (carriesTiebaNavigationContent(view.getChildAt(i))) return true

        }

        return false

    }



private fun GlassHostLayout.suppressTiebaNativeTabVisualTree(source: ViewGroup) {

        // Tieba still provides the icon/text artwork that the glass bar samples

        // and re-renders.  Do not fade the whole subtree out, otherwise the

        // glass layer loses the native icon payload too.  Instead, keep the

        // content visible for sampling and only strip backgrounds / divider

        // carriers that cause the old native bar to bleed through.

        source.isHorizontalFadingEdgeEnabled = false

        source.isVerticalFadingEdgeEnabled = false

        source.isHorizontalScrollBarEnabled = false

        source.isVerticalScrollBarEnabled = false

        clearTiebaSurface(source)



        val stack = ArrayDeque<View>()

        for (i in 0 until source.childCount) {

            stack += source.getChildAt(i)

        }

        while (stack.isNotEmpty()) {

            val view = stack.removeLast()

            clearTiebaSurface(view)

            if (view is TextView && view.text?.toString() in TIEBA_NAV_LABELS) {

                // Tieba may recreate or re-theme its labels after switching

                // pages. Keep the sampled source at 10sp; the retained 1.10x

                // projection then matches Bilibili's ~11sp glass labels while

                // leaving Tieba's approved icon size unchanged.

                val desiredPx = TypedValue.applyDimension(

                    TypedValue.COMPLEX_UNIT_SP,

                    TIEBA_SOURCE_LABEL_SP,

                    resources.displayMetrics,

                )

                if (abs(view.textSize - desiredPx) > 0.5f) {

                    view.setTextSize(TypedValue.COMPLEX_UNIT_SP, TIEBA_SOURCE_LABEL_SP)

                }

            }

            if (view is ViewGroup) {

                view.isHorizontalFadingEdgeEnabled = false

                view.isVerticalFadingEdgeEnabled = false

                view.isHorizontalScrollBarEnabled = false

                view.isVerticalScrollBarEnabled = false

                for (i in 0 until view.childCount) {

                    stack += view.getChildAt(i)

                }

            }

        }

    }



private fun GlassHostLayout.stretchTiebaContentToBottom(

        root: ViewGroup,

        source: ViewGroup,

        barTop: Int,

        rootHeight: Int,

        barChain: Set<View>,

    ) {

        val stack = ArrayDeque<Pair<View, Int>>()

        stack += root to 0

        while (stack.isNotEmpty()) {

            val (view, depth) = stack.removeLast()

            if (depth > 24) continue

            if (view in barChain) continue

            // Skip the native bar subtree too - its state must stay native

            // for the glass renderer, which draws it via source.draw(canvas).

            var p: View? = view.parent as? View

            var inside = false

            while (p != null) {

                if (p === source) {

                    inside = true

                    break

                }

                p = p.parent as? View

            }

            if (inside) continue

            if (view is ViewGroup) {

                for (i in 0 until view.childCount) {

                    stack += view.getChildAt(i) to (depth + 1)

                }

            }

            if (view === this || view is GlassHostLayout || view is AdapterOwnedOverlay) continue

            val loc = IntArray(2).also { view.getLocationOnScreen(it) }

            val bottom = loc[1] + view.height

            if (view.height <= 1000) continue

            val stopsAtBar = bottom in (barTop - 30)..(barTop + 160)

            val stopsAtStripEnd = bottom in (rootHeight - 130)..(rootHeight - 20)

            if (!stopsAtBar && !stopsAtStripEnd) continue

            val lp = view.layoutParams ?: continue

            val parentView = view.parent as? ViewGroup ?: continue

            var changed = false

            if (lp is RelativeLayout.LayoutParams) {

                if (lp.rules[RelativeLayout.ABOVE] != 0) {

                    lp.removeRule(RelativeLayout.ABOVE)

                    changed = true

                }

                if (lp.height != RelativeLayout.LayoutParams.MATCH_PARENT &&

                    parentView.height > view.height

                ) {

                    lp.height = RelativeLayout.LayoutParams.MATCH_PARENT

                    changed = true

                }

            } else if (lp.height != ViewGroup.LayoutParams.MATCH_PARENT &&

                parentView.height > view.height

            ) {

                // Fixed/wrap-height content wrapper in a taller parent

                // (FrameLayout / LinearLayout page shells, the WebView itself).

                lp.height = ViewGroup.LayoutParams.MATCH_PARENT

                changed = true

            }

            if (lp is ViewGroup.MarginLayoutParams && lp.bottomMargin != 0) {

                lp.bottomMargin = 0

                changed = true

            }

            if (changed && parentView.height > view.height) {

                view.layoutParams = lp

            }

        }

    }



private fun GlassHostLayout.suppressTiebaNativeBottomArtifacts(

        root: ViewGroup,

        source: ViewGroup,

        barTop: Int,

        rootHeight: Int,

        barChain: Set<View>,

    ) {

        val hostLoc = IntArray(2).also { getLocationOnScreen(it) }

        val hostTop = hostLoc[1]

        val hostBottom = hostTop + height

        val bandPad = (10f * tiebaDensity).toInt()

        val nativeBandTop = minOf(barTop, hostTop) - bandPad

        val nativeBandBottom = maxOf(rootHeight, hostBottom) + bandPad

        val maxNativeBarArtifactHeight = source.height + (42f * tiebaDensity).toInt()

        val stack = ArrayDeque<Pair<View, Int>>()

        stack += root to 0

        while (stack.isNotEmpty()) {

            val (view, depth) = stack.removeLast()

            if (depth > 24) continue

            if (view in barChain || view === this || view is GlassHostLayout ||

                view is AdapterOwnedOverlay

            ) continue

            if (isDescendantOf(view, source)) continue

            if (view is ViewGroup) {

                for (i in 0 until view.childCount) {

                    stack += view.getChildAt(i) to (depth + 1)

                }

            }

            if (view.visibility != View.VISIBLE) continue

            val loc = IntArray(2).also { view.getLocationOnScreen(it) }

            val top = loc[1]

            val bottom = top + view.height

            val nearBarTop = abs(top - barTop) <= 14 || abs(bottom - barTop) <= 14

            val fullWidth = view.width > root.width * 0.82f

            val overlapsNativeBarBand = bottom >= nativeBandTop && top <= nativeBandBottom



            // Native top divider: a 1-4 px full-width line at the original tab

            // shell's upper edge.  Leave ordinary list separators alone by

            // requiring proximity to the measured native tab top.

            if (fullWidth && view.height in 1..8 && (nearBarTop || overlapsNativeBarBand)) {

                view.visibility = View.INVISIBLE

                continue

            }



            // Nav-bar / tab-background scrim: short, full-width view pinned to

            // the very bottom.  This is visual-only cleanup, safe to repeat.

            if (fullWidth && bottom >= rootHeight - 12) {

                view.visibility = View.INVISIBLE

                continue

            }



            // Some selected-state backgrounds are drawn by sibling wrappers of

            // the native tab row rather than by the row itself.  They occupy the

            // same bottom band as the old bar, so suppress only their drawing

            // while preserving layout/touch state.

            if (fullWidth &&

                overlapsNativeBarBand &&

                view.height in 8..maxNativeBarArtifactHeight

            ) {

                val hadSurface = view.background != null || view.foreground != null

                clearTiebaSurface(view)

                val viewLooksLikeResidualShell =

                    view is ViewGroup || hadSurface

                if (viewLooksLikeResidualShell && view.alpha != 0f) {

                    view.alpha = 0f

                }

                continue

            }



            // Some Tieba builds draw the divider as a transparent container with

            // only a background/foreground.  Clear just those surfaces near the

            // native tab top without changing geometry or touch state.

            // Catch-all: any view with elevation/translationZ in the bottom band

            if (overlapsNativeBarBand && (view.elevation != 0f || view.translationZ != 0f)) {

                view.elevation = 0f

                view.translationZ = 0f

                clearTiebaSurface(view)

            }

            if (nearBarTop && fullWidth && view.height <= 24) {

                clearTiebaSurface(view)

            }

        }



        // Home tab now cleans up correctly, but non-home Tieba tabs still keep

        // their selected-state shell pieces inside the native tab subtree

        // itself. The outer pass above intentionally skips source descendants so

        // we do not disturb the icon/text payload the glass bar samples. Run a

        // second, source-scoped pass that only hides shell/background carriers

        // and divider lines, never the real icon/text content.

        val sourceStack = ArrayDeque<View>()

        for (i in 0 until source.childCount) {

            sourceStack += source.getChildAt(i)

        }

        while (sourceStack.isNotEmpty()) {

            val view = sourceStack.removeLast()

            if (view is ViewGroup) {

                for (i in 0 until view.childCount) {

                    sourceStack += view.getChildAt(i)

                }

            }

            if (view.visibility != View.VISIBLE) continue

            val loc = IntArray(2).also { view.getLocationOnScreen(it) }

            val top = loc[1]

            val bottom = top + view.height

            val nearBarTop = abs(top - barTop) <= 14 || abs(bottom - barTop) <= 14

            val overlapsNativeBarBand = bottom >= nativeBandTop && top <= nativeBandBottom

            val carriesContent = carriesTiebaNavigationContent(view)

            val hasOwnSurface =

                view.background != null ||

                    view.foreground != null ||

                    view.elevation != 0f ||

                    view.translationZ != 0f

            val looksLikeResidualShell =

                overlapsNativeBarBand &&

                    view.height in 8..maxNativeBarArtifactHeight &&

                    (view is ViewGroup || hasOwnSurface)

            if (looksLikeResidualShell) {

                clearTiebaSurface(view)

                // For wrapper groups that still carry the native icon/text payload,

                // strip only the shell/background.  Never alpha them out, otherwise

                // the sampled Tieba navigation content disappears together with the

                // stale native bar.

                if (!carriesContent && view.alpha != 0f) {

                    view.alpha = 0f

                }

                continue

            }

            if (!carriesContent &&

                nearBarTop &&

                view.height in 1..8 &&

                view.width > source.width * 0.78f

            ) {

                view.visibility = View.INVISIBLE

                continue

            }

            if (!carriesContent &&

                nearBarTop &&

                view.height <= 24

            ) {

                clearTiebaSurface(view)

            }

        }

    }




