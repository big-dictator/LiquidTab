package io.github.offlineglass.hook.adapters.meituan_main

import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/** Stable native artwork API + platform selection + a unique logical cell binding. */
internal object MeituanMainNativeTabs {
    data class Cell(val role: Int, val slot: View, val glyph: View, val button: View)
    data class Binding(val row: ViewGroup, val cells: List<Cell>) {
        fun valid(): Boolean {
            var index = 0
            for (position in 0 until row.childCount) {
                val slot = row.getChildAt(position)
                if (slot.visibility == View.GONE) continue
                val cell = cells.getOrNull(index++) ?: return false
                if (slot !== cell.slot || !cell.glyph.isAttachedToWindow ||
                    !cell.button.isAttachedToWindow || !below(cell.glyph, slot, rejectGone = true) ||
                    !below(cell.button, slot) || !cell.button.hasOnClickListeners()) return false
            }
            return index == cells.size
        }
    }
    private data class Failure(val reason: String, val childCount: Int, val nextProbe: Long)
    private val contracts = ConcurrentHashMap<Class<*>, Boolean>()
    private val bindings = WeakHashMap<ViewGroup, Binding>()
    private val observers = WeakHashMap<ViewGroup, Triple<android.view.ViewTreeObserver, android.view.ViewTreeObserver.OnGlobalLayoutListener, View.OnAttachStateChangeListener>>()
    private val active = WeakHashMap<ViewGroup, Boolean>()
    private val restorers = WeakHashMap<ViewGroup, () -> Unit>()
    private val failures = WeakHashMap<ViewGroup, Failure>()
    private fun below(child: View, parent: View, rejectGone: Boolean = false): Boolean {
        var current: View? = child
        while (current != null) {
            if (rejectGone && current.visibility == View.GONE) return false
            if (current === parent) return true
            current = current.parent as? View
        }
        return false
    }
    fun isTab(view: View): Boolean = contracts.getOrPut(view.javaClass) {
        MeituanMainTabContract.matches(view.javaClass, View::class.java, Drawable::class.java)
    }
    private fun slotTree(root: View): List<View> {
        val result = ArrayList<View>(); val queue = ArrayDeque<View>(); queue += root
        while (queue.isNotEmpty() && result.size < 64) {
            val view = queue.removeFirst()
            // Native scroll animations may hide a slot without changing its capability.
            result += view
            if (view is ViewGroup) for (i in 0 until view.childCount) queue += view.getChildAt(i)
        }
        return if (queue.isEmpty()) result else emptyList()
    }
    fun bind(row: ViewGroup?): Binding? {
        row ?: return null
        bindings[row]?.takeIf { it.valid() }?.let { return it }
        bindings.remove(row)
        if (!row.isAttachedToWindow || row.width <= 0 || row.height <= 0 ||
            row.childCount !in 4..MeituanMainTabContract.SLOT_COUNT) return null
        val now = SystemClock.uptimeMillis()
        val previous = failures[row]
        if (previous?.childCount == row.childCount && now < previous.nextProbe) return null
        val slots = (0 until row.childCount).map(row::getChildAt).filter { it.visibility != View.GONE }
        val cells = slots.mapNotNull { slot ->
            val nodes = slotTree(slot)
            val glyph = MeituanMainTabContract.unique(nodes.filter {
                it !is ViewGroup && it.width > 0 && it.height > 0 && isTab(it) &&
                    below(it, slot, rejectGone = true)
            }) ?: return@mapNotNull null
            // A wrapper listener is allowed only on the glyph's ancestor chain.
            // Nested competing listeners are ambiguous, never first-match fallbacks.
            val targets = nodes.filter { it.javaClass.name.startsWith("com.meituan.android.pt.homepage.tab.") && it.hasOnClickListeners() && below(glyph, it) }
            val target = MeituanMainTabContract.unique(targets) ?: return@mapNotNull null
            val title = runCatching { glyph.javaClass.getMethod("getTitle").invoke(glyph) as? String }.getOrNull()
            Cell(MeituanMainTabContract.role(title), slot, glyph, target)
        }
        val roles = cells.map { it.role }
        if (cells.size != slots.size || !MeituanMainTabContract.validRoles(roles)) {
            val reason = "unique navigation binding unavailable: slots=${slots.size}, roles=$roles"
            if (previous?.reason != reason) Log.w("MeituanBinding", reason)
            failures[row] = Failure(reason, row.childCount, now + 100L)
            return null
        }
        if (observers[row]?.first?.isAlive != true) {
            observers.remove(row)?.let { (_, _, detach) -> row.removeOnAttachStateChangeListener(detach) }
            val tree = row.viewTreeObserver
            val listener = android.view.ViewTreeObserver.OnGlobalLayoutListener {
                bindings[row]?.let { if (!it.valid()) bindings.remove(row) }
            }
            val detach = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) = Unit
                override fun onViewDetachedFromWindow(view: View) { release(row) }
            }
            tree.addOnGlobalLayoutListener(listener); row.addOnAttachStateChangeListener(detach)
            observers[row] = Triple(tree, listener, detach)
        }
        return Binding(row, cells).also { bindings[row] = it; failures.remove(row) }
    }
    fun row(block: ViewGroup): ViewGroup? {
        if (!isBlock(block)) return null
        return (0 until block.childCount).mapNotNull { block.getChildAt(it) as? ViewGroup }
            .filter { bind(it) != null }.singleOrNull()
    }
    private fun isBlock(view: View) =
        view.javaClass.name.startsWith("com.meituan.android.pt.homepage.tab.") &&
            runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull() == "bottom_tab_block"
    fun isNavigationRow(source: ViewGroup): Boolean =
        (source.parent as? ViewGroup)?.let { row(it) === source } == true
    fun activated(row: ViewGroup?) = row != null && active[row] == true
    /** The content sibling of the same verified navigation block, excluding its artwork. */
    fun content(row: ViewGroup?): View? {
        if (bind(row) == null) return null
        val block = row?.parent as? ViewGroup ?: return null
        if (!isBlock(block)) return null
        val shell = block.parent as? ViewGroup ?: return null
        return (0 until shell.childCount).map(shell::getChildAt).filter {
            it !== block && runCatching { it.resources.getResourceEntryName(it.id) }.getOrNull() == "main"
        }.singleOrNull()
    }
    fun onRestore(row: ViewGroup, restore: () -> Unit) { restorers[row] = restore }
    fun activate(row: ViewGroup, enabled: Boolean) {
        active[row] = enabled
        if (!enabled) restorers.remove(row)?.invoke()
    }
    fun release(row: ViewGroup?, restoreNative: Boolean = false) {
        row ?: return
        if (restoreNative) activate(row, false)
        else restorers.remove(row)
        bindings.remove(row); active.remove(row); failures.remove(row)
        observers.remove(row)?.let { (tree, listener, detach) ->
            if (tree.isAlive) tree.removeOnGlobalLayoutListener(listener)
            row.removeOnAttachStateChangeListener(detach)
        }
    }
}
