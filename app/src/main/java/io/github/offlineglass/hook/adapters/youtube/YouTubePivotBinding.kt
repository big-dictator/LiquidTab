package io.github.offlineglass.hook.adapters.youtube

import android.view.View
import android.graphics.Rect
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import io.github.offlineglass.hook.adapters.AdapterNavigationSearch

/** Owns the same verified native cells for artwork, selection and dispatch. */
internal class YouTubePivotBinding private constructor(
    val source: ViewGroup,
    private val row: ViewGroup,
    private val entries: List<Entry>,
) {
    data class Entry(val slot: Int, val cell: ViewGroup, val icon: ImageView, val label: String, val text: TextView?)

    fun matches(source: ViewGroup): Boolean = this.source === source && source.isAttachedToWindow &&
        row.parent === source && entries.size == row.childCount && entries.all { it.cell.parent === row }

    fun entry(index: Int): Entry? = entries.firstOrNull { it.slot == index }
    fun hasVisibleCell(bounds: Rect): Boolean = source.isShown && row.height > 0 && entries.any {
        it.cell.height > 0 && it.cell.width > 0 && it.cell.isShown && it.cell.getGlobalVisibleRect(bounds)
    }
    fun selected(): Int? = entries.singleOrNull {
        it.slot != 2 && (it.cell.isSelected || it.cell.isActivated || it.icon.isSelected || it.icon.isActivated)
    }?.slot

    companion object {
        private fun name(view: View) = AdapterNavigationSearch.resourceEntryName(view)

        fun resolve(source: ViewGroup): YouTubePivotBinding? {
            if (!source.isAttachedToWindow || name(source) != "pivot_bar" ||
                (source.parent as? View)?.let(::name) != "bottom_bar_container") return null
            var match: YouTubePivotBinding? = null
            for (index in 0 until source.childCount) {
                val row = source.getChildAt(index) as? ViewGroup ?: continue
                val entries = readRow(row) ?: continue
                if (match != null) return null
                match = YouTubePivotBinding(source, row, entries)
            }
            return match
        }

        private fun readRow(row: ViewGroup): List<Entry>? {
            if (row.childCount !in 4..5) return null
            val present = BooleanArray(5)
            val entries = ArrayList<Entry>(row.childCount)
            for (index in 0 until row.childCount) {
                val cell = row.getChildAt(index) as? ViewGroup ?: return null
                if (!cell.hasOnClickListeners()) return null
                val text = child(cell, "text") as? TextView
                val label = text?.text?.toString()?.takeIf(String::isNotBlank)
                    ?: cell.contentDescription?.toString()?.takeIf(String::isNotBlank)
                    ?: return null
                val slot = YouTubePivotRoles.slot(label)
                if (slot < 0 || present[slot]) return null
                val image = child(cell, "image") as? ImageView
                val avatar = (child(cell, "thumbnail_layout") as? ViewGroup)
                    ?.let { child(it, "pivot_bar_thumbnail") as? ImageView }
                val icon = (if (slot == 4) avatar ?: image else image) ?: return null
                present[slot] = true
                entries += Entry(slot, cell, icon, label, text)
            }
            return entries.takeIf { YouTubePivotRoles.complete(present) }
        }

        fun child(group: ViewGroup, resource: String): View? {
            var match: View? = null
            for (index in 0 until group.childCount) {
                val child = group.getChildAt(index)
                if (name(child) != resource) continue
                if (match != null) return null
                match = child
            }
            return match
        }
    }
}
