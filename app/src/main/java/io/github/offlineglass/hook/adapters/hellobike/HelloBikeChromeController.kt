package io.github.offlineglass.hook.adapters.hellobike

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.adapters.AppNativeChromeController

/** HelloBike bottom shell removal, scoped to one glass host. */
internal class HelloBikeChromeController : AppNativeChromeController {
    private var helloLastBgClearTime = 0L
    override fun update(host: View, source: ViewGroup?) {

        if (source == null) return

        if (source.alpha != 0f) source.alpha = 0f

        source.background = null

        source.backgroundTintList = null



        // Hide siblings (split_line, arc decorations)

        val parent = source.parent as? ViewGroup

        if (parent != null) {

            for (i in 0 until parent.childCount) {

                val sibling = parent.getChildAt(i)

                if (sibling !== source && sibling.visibility == View.VISIBLE) {

                    sibling.alpha = 0f

                    sibling.background = null

                    sibling.backgroundTintList = null

                    sibling.foreground = null

                }

            }

        }



        // Hide ancestor containers in bottom half

        val screenHeight = (host.rootView as? ViewGroup)?.height ?: return

        val topThreshold = screenHeight / 2

        var ancestor = parent

        var hops = 0

        while (ancestor != null && ancestor !is GlassHostLayout && hops < 6) {

            val loc = IntArray(2)

            ancestor.getLocationOnScreen(loc)

            if (loc[1] > topThreshold) {

                if (ancestor.alpha != 0f) ancestor.alpha = 0f

                ancestor.background = null

                ancestor.backgroundTintList = null

                ancestor.foreground = null

                if (ancestor.elevation > 0f) ancestor.elevation = 0f

                if (ancestor.translationZ > 0f) ancestor.translationZ = 0f

            }

            ancestor = ancestor.parent as? ViewGroup

            hops++

        }



        // Root tree walk for residual elements

        val now = SystemClock.uptimeMillis()

        if (now - helloLastBgClearTime < 1000L) return

        helloLastBgClearTime = now



        val root = source.rootView as? ViewGroup ?: return

        val barTop = screenHeight - source.height - 50

        val stack = ArrayDeque<Pair<View, Int>>()

        stack += root to 0

        while (stack.isNotEmpty()) {

            val (v, d) = stack.removeLast()

            if (v.visibility == View.VISIBLE && v.width > 0 && v.height > 0) {

                val loc = IntArray(2)

                v.getLocationOnScreen(loc)

                if (loc[1] > barTop && v !== source && v !== host &&

                    !isDescendantOf(v, source) && !isDescendantOf(v, host)

                ) {

                    if (v.background != null) v.background = null

                    if (v.backgroundTintList != null) v.backgroundTintList = null

                    if (v.foreground != null) v.foreground = null

                    if (v.elevation > 0f) v.elevation = 0f

                    if (v.translationZ > 0f) v.translationZ = 0f

                }

            }

            if (v is ViewGroup && d < 12) {

                for (i in 0 until v.childCount) stack += v.getChildAt(i) to (d + 1)

            }

        }

    }




    private fun isDescendantOf(view: View, ancestor: View): Boolean {
        var current = view.parent
        while (current is View) {
            if (current === ancestor) return true
            current = current.parent
        }
        return false
    }
}
