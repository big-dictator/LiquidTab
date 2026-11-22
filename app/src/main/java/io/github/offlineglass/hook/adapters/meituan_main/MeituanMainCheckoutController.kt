package io.github.offlineglass.hook.adapters.meituan_main

import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.util.Log
import io.github.offlineglass.hook.compat.XposedHelpers
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.adapters.AdapterOwnedOverlay
import java.util.WeakHashMap

/** MSC 12.66.404 flattens the footer into sibling native views under render.rn.i.
 * Binding follows that verified structure; no geometry-based ancestor guesses. */
internal class MeituanMainCheckoutController(private val binding: MeituanMainBinding) {
    private var backTop: View? = null
    private var backTopRevision = -1
    private var source: ViewGroup? = null
    private var sourceAlpha = 1f
    private var buttons: MeituanMainCartLiquidButtons? = null
    private var nextFind = 0L
    private var active = false
    private var bindingIssue: String? = null
    private var layoutObserver: ViewTreeObserver? = null
    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { nextFind = 0L }
    private val backTopTranslations = WeakHashMap<View, Float>()
    private val position = IntArray(2)

    private fun walk(view: View, visit: (View) -> Unit) { binding.tree(view).forEach(visit) }
    private fun nativeText(view: View): CharSequence? = binding.text(view)

    private fun bindingFailed(reason: String): Boolean {
        if (bindingIssue != reason) Log.w("MeituanCart", "binding unavailable: $reason")
        bindingIssue = reason
        return false
    }

    private fun find(glass: GlassHostLayout): Boolean {
        val eventRoot = binding.page(glass.rootView) ?: return bindingFailed("unique MSC content root missing")
        data class Candidate(val row: ViewGroup, val all: View, val pay: View)
        val candidates = (0 until eventRoot.childCount).mapNotNull { index ->
            val row = eventRoot.getChildAt(index) as? ViewGroup ?: return@mapNotNull null
            val labels = binding.tree(row).filter { view -> nativeText(view) != null &&
                (view !is ViewGroup || (0 until view.childCount).none { nativeText(view.getChildAt(it)) != null }) }
            val all = labels.filter { nativeText(it)?.toString()?.trim() == "全选" }
            val pay = labels.filter { val text = nativeText(it)?.toString()?.trim().orEmpty()
                text.startsWith("结算") || text.startsWith("去结算") }
            if (all.size == 1 && pay.size == 1) Candidate(row, all.single(), pay.single()) else null
        }
        val candidate = MeituanMainTabContract.unique(candidates)
            ?: return bindingFailed("checkout footer candidates=${candidates.size}, expected exactly one")
        val row = candidate.row
        val selectLabel = candidate.all
        val payLabel = candidate.pay
        fun branch(view: View): View? {
            var result = view
            while (result.parent !== row) result = result.parent as? View ?: return null
            return result
        }
        val selectText = branch(selectLabel) ?: return bindingFailed("select branch changed")
        val payText = branch(payLabel) ?: return bindingFailed("checkout branch changed")
        val children = (0 until row.childCount).map(row::getChildAt)
        // MSC keeps the check ImageView and its label as separate siblings.
        val check = children.filterIsInstance<ImageView>().filter {
            it.right <= selectText.left && kotlin.math.abs(it.top + it.height / 2 - selectText.top - selectText.height / 2) < row.height / 2
        }.singleOrNull() ?: return bindingFailed("select image sibling missing or ambiguous")
        val amounts = children.filter { child ->
            if (child === selectText || child === payText) return@filter false
            var text = false
            walk(child) { if (nativeText(it)?.isNotEmpty() == true) text = true }
            text
        }
        val amountLabels = amounts.flatMap { binding.tree(it) }.mapNotNull { nativeText(it)?.toString() }
        if (amounts.isEmpty() || amountLabels.none { it.contains("合计") } ||
            amountLabels.none { it.contains("¥") || it.contains("￥") })
            return bindingFailed("native total title/currency relationship missing")
        val parent = glass.parent as? FrameLayout ?: return false
        releaseBinding()
        val value = MeituanMainCartLiquidButtons(glass.context, glass, row,
            listOf(check, selectText), amounts, listOf(payText), eventRoot, eventRoot)
        parent.addView(value, parent.indexOfChild(glass).coerceAtLeast(0),
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (88f * glass.resources.displayMetrics.density).toInt()))
        source = row; sourceAlpha = row.alpha; buttons = value
        row.alpha = 0f
        value.updateGeometry()
        bindingIssue = null
        Log.i("MeituanCart", "bound footer=${row.javaClass.name} children=${row.childCount} root=${eventRoot.javaClass.name}")
        return true
    }

    private fun showPending(glass: GlassHostLayout, previewScene: View?) {
        if (buttons == null) {
            val parent = glass.parent as? FrameLayout ?: return
            val mainId = glass.resources.getIdentifier("main", "id", glass.context.packageName)
            val scene = previewScene ?: glass.rootView.findViewById<View>(mainId) ?: return
            // No invented price or checkout target. The existing renderer's
            // pending art is visible immediately and has no business binding.
            val unbound = FrameLayout(glass.context)
            val value = MeituanMainCartLiquidButtons(glass.context, glass, unbound,
                emptyList(), emptyList(), emptyList(), scene, unbound)
            value.setBusinessTouchEnabled(false)
            parent.addView(value, parent.indexOfChild(glass).coerceAtLeast(0),
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    (88f * glass.resources.displayMetrics.density).toInt()))
            buttons = value
        }
        buttons?.let { it.visibility = View.VISIBLE; it.refresh() }
    }

    fun update(glass: GlassHostLayout, index: Int, preview: Boolean, previewScene: View? = null) {
        if (index != 3) {
            stopWatchingLayout()
            if (active) {
                active = false
                buttons?.visibility = View.INVISIBLE
                restoreBackTop()
            }
            // Keep our bound source hidden while off-page; it is restored on release.
            source?.let { if (it.alpha != 0f) it.alpha = 0f }
            return
        }
        if (layoutObserver?.isAlive != true) {
            layoutObserver = glass.rootView.viewTreeObserver.also {
                it.addOnGlobalLayoutListener(layoutListener)
            }
        }
        if (!active) { active = true; nextFind = 0L }
        val bound = source?.let { it.isAttachedToWindow && it.isShown } == true && buttons?.validBinding() == true
        if (!bound) {
            // Do not leave an obsolete native footer hidden if rebinding fails.
            if (source != null) releaseBinding()
            val now = SystemClock.uptimeMillis()
            if (now < nextFind) { if (preview) showPending(glass, previewScene); return }
            nextFind = now + 250L
            if (!find(glass)) { if (preview) showPending(glass, previewScene) else releaseBinding(); return }
        }
        source?.let { if (it.alpha != 0f) it.alpha = 0f }
        buttons?.let { value ->
            // The row is present even during the cart preview, but cached
            // price/controls cannot submit actions until the live page takes over.
            value.visibility = View.VISIBLE
            value.setBusinessTouchEnabled(!preview)
            value.refresh()
            if (!preview) liftBackTop(value, glass) else restoreBackTop()
        }
    }

    private fun liftBackTop(value: MeituanMainCartLiquidButtons, glass: GlassHostLayout) {
        val row = source ?: return
        val page = row.parent as? ViewGroup ?: return
        val d = glass.resources.displayMetrics.density
        val targetBottom = value.capsuleTopInWindow() - 8f * d
        if (backTopRevision != binding.revision || backTop?.isAttachedToWindow == false) {
            restoreBackTop()
            backTop = binding.backTop(page, row)
            backTopRevision = binding.revision
        }
        val view = backTop ?: return
        view.getLocationInWindow(position)
        val base = backTopTranslations.getOrPut(view) { view.translationY }
        val nativeBottom = position[1] + view.height - view.translationY + base
        val wanted = base - (nativeBottom - targetBottom).coerceAtLeast(0f)
        if (kotlin.math.abs(view.translationY - wanted) > .5f) view.translationY = wanted
    }

    private fun restoreBackTop() {
        backTopTranslations.forEach { (view, translation) -> view.translationY = translation }
        backTopTranslations.clear()
    }

    private fun releaseBinding() {
        buttons?.let { it.release(); (it.parent as? ViewGroup)?.removeView(it) }
        buttons = null
        source?.let { it.alpha = sourceAlpha }
        source = null
        restoreBackTop(); backTop = null; backTopRevision = -1
    }

    private fun stopWatchingLayout() {
        layoutObserver?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(layoutListener)
        layoutObserver = null
    }

    fun release() { stopWatchingLayout(); releaseBinding(); active = false; nextFind = 0L }
}
