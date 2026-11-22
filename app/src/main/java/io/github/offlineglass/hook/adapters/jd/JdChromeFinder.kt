package io.github.offlineglass.hook.adapters.jd

import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.adapters.AdapterOwnedOverlay

/** JD floating Flash controls and cart back-to-top discovery, bounded and app-owned. */
internal class JdChromeFinder(private val host: GlassHostLayout) {
    fun findFlashBottomControls(root: ViewGroup, rootWidth: Int, rootHeight: Int): Pair<View?, View?> {

        val stack = ArrayDeque<View>()

        stack += root

        var visited = 0

        var banner: View? = null

        var bannerBottom = -1

        var cart: View? = null

        var cartBottom = -1

        val loc = IntArray(2)

        while (stack.isNotEmpty() && visited++ < 1200) {

            val view = stack.removeLast()

            if (view === host || view is GlassHostLayout ||

                view is AdapterOwnedOverlay

            ) continue

            val name = view.javaClass.simpleName

            // Prune the feed's subtrees: the staggered-grid RecyclerView and

            // the inner pagers hold hundreds of TNViewGroup items and would

            // exhaust the visit budget before the floating controls (which

            // sit OUTSIDE them, as direct children of the fragment view) are

            // ever reached.

            if (view is ViewGroup &&

                (name.contains("RecyclerView") || name.contains("ViewPager"))

            ) continue

            if (view.visibility != View.VISIBLE || view.alpha <= 0.05f) continue

            if (view is ViewGroup) {

                if (name == "HourlyGoScreenBottomBarView" &&

                    view.width >= rootWidth * 0.98f && view.height in 80..200

                ) {

                    view.getLocationInWindow(loc)

                    val bottom = loc[1] + view.height

                    if (bottom > bannerBottom && !isInsideScrollContainer(view)) {

                        banner = view

                        bannerBottom = bottom

                    }

                    // Do not descend into the banner: its inner TN wrappers

                    // share the strip shape.

                    continue

                }

                for (index in view.childCount - 1 downTo 0) {

                    stack += view.getChildAt(index)

                }

            }

            // Floating cart FAB: near-square image view (90�?80 px) hugging

            // the right edge in the lower half, outside scroll containers.

            // Its parent container (the FAB's hit area) is lifted instead.

            if (view is android.widget.ImageView && view.width in 90..180 &&

                view.height in 90..180 &&

                kotlin.math.abs(view.width - view.height) <= 28

            ) {

                view.getLocationInWindow(loc)

                val right = loc[0] + view.width

                val bottom = loc[1] + view.height

                if (right >= rootWidth - 220 && bottom >= rootHeight * 0.55f &&

                    bottom <= rootHeight + 60 &&

                    !isInsideScrollContainer(view) && !isInsideRecyclerOrPager(view)

                ) {

                    val parent = view.parent as? ViewGroup

                    if (parent != null && parent.width in 90..220 && parent.height in 90..220) {

                        parent.getLocationInWindow(loc)

                        val parentBottom = loc[1] + parent.height

                        if (parentBottom > cartBottom) {

                            cart = parent

                            cartBottom = parentBottom

                        }

                    }

                }

            }

        }

        val sb = StringBuilder(

            "JdFlash find: root=${rootWidth}x$rootHeight banner=" +

                (banner?.let { b ->

                    b.getLocationInWindow(loc)

                    "${b.javaClass.simpleName} h=${b.height} y=${loc[1]}"

                } ?: "null") +

                " cart=" +

                (cart?.let { c ->

                    c.getLocationInWindow(loc)

                    "${c.javaClass.simpleName} h=${c.height} y=${loc[1]}"

                } ?: "null"),

        )

        android.util.Log.i("JdGlassDiag", sb.toString())

        return banner to cart

    }



    /**

     * Structural signature of the cart's floating back-to-top control: a

     * near-square ViewGroup (90�?80 px, width≈height) hugging the right edge,

     * in the lower half of the window, outside every scroll container. Feed

     * cards are 1161 px wide, thumbnails live inside the scroll view, and the

     * checkout strip is full-width �?none of them match.

     */

    fun findBackToTopButtons(root: ViewGroup, rootWidth: Int, rootHeight: Int): List<View> {
        val stack = ArrayDeque<View>()
        stack += root
        val candidates = ArrayList<View>()
        val location = IntArray(2)
        var visited = 0
        while (stack.isNotEmpty() && visited++ < 900) {
            val view = stack.removeLast()
            if (view === host || view is GlassHostLayout || view is AdapterOwnedOverlay ||
                view.visibility != View.VISIBLE || view.alpha <= .05f) continue
            if (view is ViewGroup) {
                val name = view.javaClass.simpleName
                if (name.contains("Scroll") || name.contains("Refresh") || name.contains("Recycler")) continue
                if (view.width in 90..180 && view.height in 90..180 &&
                    kotlin.math.abs(view.width - view.height) <= 24) {
                    view.getLocationInWindow(location)
                    if (location[0] + view.width >= rootWidth - 80 &&
                        location[1] + view.height >= rootHeight * .5f && !isInsideScrollContainer(view)) {
                        candidates += view
                        // A nested wrapper is the same button, not another slot.
                        continue
                    }
                }
                for (i in view.childCount - 1 downTo 0) stack += view.getChildAt(i)
            }
        }
        return candidates.sortedBy { it.getLocationInWindow(location); location[1] }
            .takeLast(2)
    }

    private fun isInsideScrollContainer(view: View): Boolean {
        var node = view
        var guard = 0
        while (guard++ < 12) {
            val parent = node.parent as? ViewGroup ?: return false
            val name = parent.javaClass.simpleName
            if (name.contains("Scroll") || name.contains("Refresh")) return true
            node = parent
        }
        return false
    }

    private fun isInsideRecyclerOrPager(view: View): Boolean {
        var node = view
        var guard = 0
        while (guard++ < 14) {
            val parent = node.parent as? ViewGroup ?: return false
            val name = parent.javaClass.simpleName
            if (name.contains("RecyclerView") || name.contains("ViewPager")) return true
            node = parent
        }
        return false
    }
}

