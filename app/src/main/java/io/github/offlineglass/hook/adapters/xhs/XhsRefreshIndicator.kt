package io.github.offlineglass.hook.adapters.xhs

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView

/** Only relocate the native circle; pulling distance, callbacks and content stay native. */
internal class XhsRefreshIndicator {
    private val originals = LinkedHashMap<View, Float>()
    private var circles = emptyList<View>()
    private val location = IntArray(2)
    private val rootLocation = IntArray(2)
    fun discover(root: View) {
        val found = ArrayList<View>()
        fun inherits(type: Class<*>, name: String) = generateSequence(type as Class<*>?) { it.superclass }.any { it.name == name }
        fun walk(view: View) {
            if (view is io.github.offlineglass.hook.adapters.AdapterOwnedOverlay || view is io.github.offlineglass.hook.GlassHostLayout) return
            if (view is ViewGroup && inherits(view.javaClass, "androidx.swiperefreshlayout.widget.SwipeRefreshLayout")) {
                val candidates = (0 until view.childCount).map(view::getChildAt).filter { child ->
                    child is ImageView && child.javaClass.name == "androidx.swiperefreshlayout.widget.CircleImageView" &&
                        child.drawable?.let { inherits(it.javaClass, "androidx.swiperefreshlayout.widget.CircularProgressDrawable") } == true
                }
                if (candidates.size == 1) found.add(candidates.single())
            }
            // No need to descend through feed cells to find a refresh container.
            if (inherits(view.javaClass, "androidx.recyclerview.widget.RecyclerView")) return
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        walk(root)
        originals.keys.toList().filter { it !in found }.forEach { old -> originals.remove(old)?.let { old.translationY = it } }
        circles = found
    }
    fun update(root: View, headerBottom: Int) {
        root.getLocationInWindow(rootLocation)
        val floor = headerBottom + 8f * root.resources.displayMetrics.density
        for (circle in circles) {
            if (!circle.isAttachedToWindow) continue
            val base = originals.getOrPut(circle) { circle.translationY }
            val parent = circle.parent as? View ?: continue
            parent.getLocationInWindow(location)
            val delta = (floor - (location[1] - rootLocation[1])).coerceAtLeast(0f)
            circle.getLocationInWindow(location)
            val unshiftedY = location[1] - rootLocation[1] - circle.translationY + base
            val offset = maxOf(delta, floor - unshiftedY)
            val wanted = base + offset
            if (kotlin.math.abs(circle.translationY - wanted) > .5f) circle.translationY = wanted
        }
    }
    fun restore() {
        originals.forEach { (view, translation) -> view.translationY = translation }
        originals.clear(); circles = emptyList()
    }
}
