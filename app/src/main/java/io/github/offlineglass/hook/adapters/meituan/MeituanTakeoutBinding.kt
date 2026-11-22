package io.github.offlineglass.hook.adapters.meituan

import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import io.github.offlineglass.hook.adapters.AdapterOwnedOverlay
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import io.github.offlineglass.hook.adapters.meituan.MeituanTakeoutContract.Role

/** One verified native graph for discovery, drawing, input, chrome and page geometry. */
internal class MeituanTakeoutBinding private constructor(
    val row: ViewGroup, val shell: ViewGroup, val pageContainer: ViewGroup, val slots: List<Slot>,
    val decoration: View?,
) {
    data class Slot(val root: ViewGroup, val normal: ViewGroup, val title: TextView,
        val icon: ImageView?, val promotion: ImageView?, val card: View?) {
        val activePromotion: Boolean get() = promotion?.isShown == true && normal.visibility != View.VISIBLE
        val role: Role get() = MeituanTakeoutContract.role(title.text.toString(),
            MeituanTakeoutContract.promotionSlot(activePromotion, promotion != null,
                icon?.drawable != null, root.hasOnClickListeners()))
        fun button(): View? = if (role == Role.PROMOTION) {
            root.takeIf { it.hasOnClickListeners() } ?: card?.takeIf { it.hasOnClickListeners() }
        } else normal.takeIf { it.hasOnClickListeners() }
    }
    data class Page(val root: ViewGroup, val profileScroll: ScrollView?, val orderChain: List<View>)
    private var observer: ViewTreeObserver? = null
    private var dirty = true
    private var pageIndex = -1
    private var nextPageProbe = 0L
    private var page: Page? = null
    private var issue = ""
    private var renderedDiagnostic = ""
    private var renderedDiagnosticWrites = 0
    fun describeBoundNavigation(host: io.github.offlineglass.hook.GlassHostLayout?) {
        if (renderedDiagnosticWrites >= 16) return
        val state = "hostSlots=${host?.adapterSlotCount} hiddenMask=${host?.config?.hiddenMask}\n" +
            slots.mapIndexed { index, cell ->
                "slot=$index role=${cell.role} root=${cell.root.visibility}:${cell.root.width}x${cell.root.height} " +
                    "normal=${cell.normal.visibility} title=${cell.title.text} " +
                    "promotion=${cell.promotion?.visibility}:${cell.promotion?.width}x${cell.promotion?.height} drawable=${cell.promotion?.drawable != null}"
            }.joinToString("\n")
        if (state == renderedDiagnostic) return
        renderedDiagnostic = state; renderedDiagnosticWrites++
        runCatching { java.io.File(row.context.getExternalFilesDir(null), "liquidtab-takeout-bound.txt")
            .writeText("pid=${android.os.Process.myPid()}\n$state\n") }
    }
    private val layout = ViewTreeObserver.OnGlobalLayoutListener { dirty = true }

    fun report(reason: String) {
        if (issue != reason) Log.w("TakeoutBinding", reason)
        issue = reason
    }
    fun index(role: Role): Int = MeituanTakeoutContract.uniqueRole(slots.map { it.role }, role)
    fun selected(): Int? = slots.indices.filter { slots[it].root.isSelected }.singleOrNull()
    fun valid(): Boolean = row.isAttachedToWindow && row.parent === shell &&
        pageContainer.parent === shell.parent && pageContainer.rootView === row.rootView && slots.size == row.childCount &&
        slots.withIndex().all { (index, cell) ->
            row.getChildAt(index) === cell.root && cell.root.tag == index &&
                cell.normal.tag == index &&
                cell.title.isAttachedToWindow && descendant(cell.title, cell.root) &&
                cell.normal.isAttachedToWindow && descendant(cell.normal, cell.root) &&
                (cell.icon == null || cell.icon.isAttachedToWindow && descendant(cell.icon, cell.root)) &&
                (cell.promotion == null || cell.promotion.isAttachedToWindow && descendant(cell.promotion, cell.root)) &&
                cell.button()?.isAttachedToWindow == true
        }

    fun page(index: Int): Page? {
        if (!valid() || selected() != index) return null
        if (observer?.isAlive != true) observer = row.viewTreeObserver.also {
            it.addOnGlobalLayoutListener(layout)
        }
        if (pageIndex != index || page?.root?.isShown == false) { dirty = true; page = null; pageIndex = index }
        if (page?.let { p ->
                p.profileScroll?.let { !it.isAttachedToWindow || !descendant(it, p.root) } == true ||
                    p.orderChain.any { !it.isAttachedToWindow || !descendant(it, p.root) }
            } == true) { dirty = true; page = null }
        if (!dirty || SystemClock.uptimeMillis() < nextPageProbe) return page
        nextPageProbe = SystemClock.uptimeMillis() + 250L
        dirty = false
        val roots = (0 until pageContainer.childCount).mapNotNull { pageContainer.getChildAt(it) as? ViewGroup }
            .filter { it.isShown && it.width >= pageContainer.width * .9f && it.height >= pageContainer.height * .5f }
        val root = roots.singleOrNull() ?: run { page = null; report("visible page owner absent or ambiguous"); return null }
        val nodes = tree(root, 1200, 18) ?: run { page = null; report("page discovery budget exhausted"); return null }
        val role = slots.getOrNull(index)?.role
        var scroll: ScrollView? = null
        var chain = emptyList<View>()
        if (role == Role.PROFILE) {
            val candidates = nodes.filterIsInstance<ScrollView>().filter {
                it.isShown && it.width >= root.width * .9f && it.height >= root.height * .5f &&
                    it.childCount == 1 && it.getChildAt(0).width >= it.width * .8f
            }
            scroll = candidates.filter { candidate -> candidates.none { it !== candidate && descendant(candidate, it) } }.singleOrNull()
            if (scroll == null) report("profile vertical scroll contract absent or ambiguous")
        }
        if (role == Role.ORDERS) {
            // AndroidX public inheritance/API contracts, not app implementation names.
            val pagers = nodes.filterIsInstance<ViewGroup>().filter {
                inherits(it, "androidx.viewpager.widget.ViewPager") && it.isShown && it.width >= root.width * .9f
            }
            val pager = pagers.singleOrNull()
            val viewport = pager?.parent as? ViewGroup
            if (pager != null && viewport != null) {
                val lists = tree(pager, 900, 12)?.filter { view ->
                    val visible = android.graphics.Rect()
                    inherits(view, "androidx.recyclerview.widget.RecyclerView") && view.isShown &&
                        view.getGlobalVisibleRect(visible) && visible.width() >= root.width * .8f &&
                        view.width >= root.width * .9f && view.height >= pager.height * .7f
                }.orEmpty()
                val list = lists.singleOrNull()
                if (list != null) {
                    val route = mutableListOf<View>(); var node: View? = list
                    while (node != null && node !== viewport && route.size < 12) { route += node; node = node.parent as? View }
                    if (node === viewport) { route += viewport; chain = route.asReversed() }
                }
            }
            if (chain.isEmpty()) report("order pager/list relationship absent or ambiguous")
        }
        page = Page(root, scroll, chain)
        return page
    }

    fun release() {
        observer?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(layout)
        observer = null; page = null; dirty = true
    }

    companion object {
        private val cache = WeakHashMap<View, WeakReference<MeituanTakeoutBinding>>()
        private val attempts = WeakHashMap<View, Long>()
        private var lastIssue = ""
        private var diagnostic = ""
        private var diagnosticWrites = 0
        private fun describeNavigation(root: View, nodes: List<View>?) {
            if (diagnosticWrites >= 16) return
            val rows = nodes.orEmpty().filterIsInstance<ViewGroup>().filter { name(it) == "pagerindicator" }
            val state = rows.joinToString("\n") { row ->
                "row children=${row.childCount} size=${row.width}x${row.height} visibility=${row.visibility}\n" +
                    (0 until row.childCount).joinToString("\n") { index ->
                        val cell = row.getChildAt(index)
                        "slot=$index tag=${cell.tag} visibility=${cell.visibility} " +
                            tree(cell, 80, 7).orEmpty().filter { name(it) in setOf("tab_title", "ll_normal", "iv_promotion", "icon_normal", "iv_mt_cardview") }
                                .joinToString { view -> "${name(view)}:v=${view.visibility},size=${view.width}x${view.height},click=${view.hasOnClickListeners()}" +
                                    (if (view is TextView) ",text=${view.text}" else "") +
                                    (if (view is ImageView) ",drawable=${view.drawable != null}" else "") }
                    }
            }.ifEmpty { "no navigation row; budgetExceeded=${nodes == null}" }
            if (diagnostic == state) return
            diagnostic = state; diagnosticWrites++
            // Bounded, navigation-only evidence on devices with disabled logcat.
            runCatching { java.io.File(root.context.getExternalFilesDir(null), "liquidtab-takeout-navigation.txt")
                .writeText("pid=${android.os.Process.myPid()}\n$state\n") }
        }
        fun name(view: View): String = runCatching { view.resources.getResourceEntryName(view.id) }.getOrDefault("")
        fun descendant(view: View, ancestor: View): Boolean {
            var node: View? = view
            repeat(32) { if (node === ancestor) return true; node = node?.parent as? View }
            return false
        }
        fun inherits(view: View, stableType: String): Boolean =
            generateSequence(view.javaClass as Class<*>?) { it.superclass }.any { it.name == stableType }
        fun tree(root: View, budget: Int, depth: Int, skipPages: Boolean = false): List<View>? {
            val nodes = ArrayList<View>(); val stack = ArrayDeque<Pair<View, Int>>(); stack += root to 0
            while (stack.isNotEmpty()) {
                val (view, level) = stack.removeLast()
                if (view is AdapterOwnedOverlay || view is io.github.offlineglass.hook.GlassHostLayout) continue
                if (nodes.size >= budget) return null
                nodes += view
                if (view is ViewGroup && level < depth && !(skipPages && name(view) == "pager_container"))
                    for (i in 0 until view.childCount) stack += view.getChildAt(i) to level + 1
            }
            return nodes
        }
        private fun child(root: View, resource: String): View? =
            tree(root, 80, 7)?.filter { name(it) == resource }?.singleOrNull()

        fun forRow(row: ViewGroup?): MeituanTakeoutBinding? {
            row ?: return null
            return discover(row.rootView)?.takeIf { it.row === row }
        }
        fun discover(root: View): MeituanTakeoutBinding? {
            cache[root]?.get()?.let { if (it.valid()) return it else it.release() }
            val now = SystemClock.uptimeMillis()
            if (now < (attempts[root] ?: 0L)) return null
            attempts[root] = now + 250L
            val content = root.findViewById<View>(android.R.id.content) ?: root
            val nodes = tree(content, 400, 12, skipPages = true)
            describeNavigation(content, nodes)
            val matches = nodes?.filterIsInstance<ViewGroup>()?.filter { name(it) == "pagerindicator" && it.isShown }
                ?.mapNotNull(::bind).orEmpty()
            val result = matches.singleOrNull()
            if (result == null) {
                val reason = if (nodes == null) "navigation search budget exhausted" else "navigation resource/roles/input contract: ${matches.size} complete candidates"
                if (reason != lastIssue) Log.w("TakeoutBinding", reason)
                lastIssue = reason; cache.remove(root)
                return null
            }
            lastIssue = ""; cache[root] = WeakReference(result)
            Log.i("TakeoutBinding", "bound native navigation slots=${result.slots.size} roles=${result.slots.map { it.role }}")
            return result
        }
        private fun bind(row: ViewGroup): MeituanTakeoutBinding? {
            if (row.childCount != 5 || row.width <= 0 || row.height <= 0 ||
                !row.javaClass.name.startsWith("com.sankuai.waimai.")) return null
            val shell = row.parent as? ViewGroup ?: return null
            if (name(shell) != "navigation_bar_margin_layout_container") return null
            val scope = shell.parent as? ViewGroup ?: return null
            val page = (0 until scope.childCount).map(scope::getChildAt)
                .filter { name(it) == "pager_container" }.singleOrNull() as? ViewGroup ?: return null
            val slots = (0 until row.childCount).map { i ->
                val cell = row.getChildAt(i) as? ViewGroup ?: return null
                val title = child(cell, "tab_title") as? TextView ?: return null
                val normal = child(cell, "ll_normal") as? ViewGroup ?: return null
                if (normal.tag != i) return null
                val slot = Slot(cell, normal, title, child(cell, "icon_normal") as? ImageView,
                    child(cell, "iv_promotion") as? ImageView, child(cell, "iv_mt_cardview"))
                if (!MeituanTakeoutContract.validCell(i, cell.tag, true, true,
                        slot.button()?.hasOnClickListeners() == true)) return null
                slot
            }
            val anchors = slots.map { it.role }.filter { it in setOf(Role.HOME, Role.ORDERS, Role.PROFILE) }.distinct()
            if (anchors.size < 2) return null
            if (slots.count { it.role == Role.PROMOTION } > 1) return null
            val decoration = (0 until scope.childCount).map(scope::getChildAt)
                .filter { name(it) == "indicator_bg_container" }.singleOrNull()
            return MeituanTakeoutBinding(row, shell, page, slots, decoration)
        }
    }
}
