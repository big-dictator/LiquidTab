package io.github.offlineglass.hook

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.TextView
import io.github.offlineglass.hook.adapters.TargetAdapterRegistry
import io.github.offlineglass.targets.TargetSpec
import kotlin.math.abs

data class NavigationCandidate(val view: ViewGroup, val slotCount: Int, val score: Int)

object NavigationFinder {
    fun estimateSlotCount(group: ViewGroup, spec: TargetSpec): Int {
        val density = group.resources.displayMetrics.density
        TargetAdapterRegistry.forSpec(spec)?.estimateSlotCount(group, spec)?.let { return it }
        val measured = horizontalSlotCount(group, spec, density)
        if (measured in 2..7) return measured
        return spec.preferredSlots.first.coerceIn(2, 7)
    }

    fun find(root: View, spec: TargetSpec): NavigationCandidate? {
        if (root.width <= 0 || root.height <= 0) return null
        TargetAdapterRegistry.forSpec(spec)?.let { adapter ->
            val match = adapter.findNavigation(root, spec)
            if (adapter.ownsNavigationFinding || match != null) return match
        }
        val density = root.resources.displayMetrics.density
        val rootRect = Rect().also(root::getGlobalVisibleRect)
        val candidates = ArrayList<NavigationCandidate>()


        walk(root, 0) { view, depth ->
            if (view !is ViewGroup || depth > 14 || !view.isShown || view.width <= 0 || view.height <= 0) return@walk
            val rect = Rect()
            if (!view.getGlobalVisibleRect(rect)) return@walk
            val heightDp = view.height / density
            val widthRatio = view.width.toFloat() / root.width
            val bottomRatio = (rect.bottom - rootRect.top).toFloat() / root.height
            if (heightDp !in 42f..190f || widthRatio < 0.52f || bottomRatio < 0.66f) return@walk

            val idName = resourceEntryName(view)
            val slots = TargetAdapterRegistry.forSpec(spec)?.estimateSlotCount(view, spec)
                ?: horizontalSlotCount(view, spec, density)
            val className = view.javaClass.name
            val text = collectText(view, 3)
            var score = 0

            if (bottomRatio > 0.82f) score += 22
            if (widthRatio > 0.78f) score += 10
            if (heightDp in 56f..118f) score += 12
            if (slots in spec.preferredSlots) score += 35 else if (slots in 2..7) score += 18
            if (idName != null) {
                spec.idHints.forEach { hint ->
                    if (idName.equals(hint, true)) score += 42
                    else if (idName.contains(hint, true)) score += 18
                }
                if (listOf("bottom", "navigation", "tab", "footer").any { idName.contains(it, true) }) score += 14
            }
            spec.classHints.forEach { hint -> if (className.contains(hint, true)) score += 35 }
            spec.textHints.forEach { hint -> if (text.contains(hint)) score += 7 }
            if (className.contains("BottomNavigation", true) || className.contains("NavigationBar", true)) score += 30
            if (spec.key == "qq") {
                // QQ 9.3.30 keeps the opaque bottom chrome (kmr) beside the
                // actual three-tab HorizontalScrollView. Prefer the latter;
                // installing on the chrome leaves QQ's native bar untouched.
                if (view is HorizontalScrollView) score += 90
                if (idName == "kmr") score -= 90
            }
            score = TargetAdapterRegistry.forSpec(spec)?.adjustNavigationScore(view, idName, score) ?: score
            if (view.isClickable) score += 3

            if (score >= 48 && slots >= 2) candidates += NavigationCandidate(view, slots, score)
        }
        TargetAdapterRegistry.forSpec(spec)?.onNavigationCandidates(candidates)
        return candidates.maxWithOrNull(
            compareBy<NavigationCandidate> { it.score }
                .thenBy { it.view.width * it.view.height }
        )
    }

    private fun horizontalSlotCount(group: ViewGroup, spec: TargetSpec, density: Float): Int {
        val groups = ArrayList<View>()
        fun collect(view: View, depth: Int) {
            if (!view.isShown || view.width < 26f * density || view.height < 24f * density) return
            if (view !== group && (view.isClickable || view.isSelected || view.isActivated)) groups += view
            if (view is ViewGroup && depth < 4) {
                for (i in 0 until view.childCount) collect(view.getChildAt(i), depth + 1)
            }
        }
        collect(group, 0)

        fun distinctCenters(items: List<View>): Int {
            val centers = items.map { item ->
                val a = IntArray(2); val b = IntArray(2)
                item.getLocationInWindow(a); group.getLocationInWindow(b)
                a[0] - b[0] + item.width / 2
            }.sorted()
            val threshold = 30f * density
            val distinct = ArrayList<Int>()
            centers.forEach { center -> if (distinct.none { abs(it - center) < threshold }) distinct += center }
            return distinct.size
        }

        val clickable = distinctCenters(groups)
        if (clickable in spec.preferredSlots) return clickable

        val directVisible = (0 until group.childCount)
            .map(group::getChildAt)
            .filter { it.isShown && it.width >= 32f * density }
        val direct = distinctCenters(directVisible)
        if (direct in 2..7) return direct

        val labels = ArrayList<TextView>()
        walk(group, 0) { view, depth ->
            if (depth <= 5 && view is TextView && view.text?.isNotBlank() == true) labels += view
        }
        return distinctCenters(labels).coerceIn(0, 7)
    }

    private fun collectText(root: View, maxDepth: Int): String = buildString {
        walk(root, 0) { view, depth ->
            if (depth <= maxDepth && view is TextView) append(view.text).append('|')
        }
    }

    private fun resourceEntryName(view: View): String? = runCatching {
        if (view.id == View.NO_ID) null else view.resources.getResourceEntryName(view.id)
    }.getOrNull()

    private inline fun walk(root: View, startDepth: Int, crossinline block: (View, Int) -> Unit) {
        val stack = ArrayDeque<Pair<View, Int>>()
        stack += root to startDepth
        var visited = 0
        while (stack.isNotEmpty() && visited++ < 4_000) {
            val (view, depth) = stack.removeLast()
            block(view, depth)
            if (view is ViewGroup && depth < 16) {
                for (i in view.childCount - 1 downTo 0) stack += view.getChildAt(i) to (depth + 1)
            }
        }
    }

}
