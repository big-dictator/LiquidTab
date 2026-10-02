package io.github.offlineglass.hook.adapters.xjtu

import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.BlendMode
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.widget.ImageView
import android.widget.TextView
import io.github.offlineglass.hook.adapters.AppNavigationState
import io.github.offlineglass.hook.GlassHostLayout
import java.lang.ref.WeakReference

internal class XjtuNavigationState : AppNavigationState {
    override val allowsSoftwareBackdropCapture = false
    override fun skipLiveBackdrop(selectedIndex: Int) = true
    private var compositorInitialized = false
    private var surfaceRedrawPending = true
    private var wasAnimating = false
    private var motionUntil = 0L
    private var lastContentTabIndex = -1
    private var activeHost = WeakReference<GlassHostLayout>(null)
    private var serviceVisibleUntil = 0L
    private var serviceTouching = false
    private val hideServiceBar = Runnable { activeHost.get()?.postInvalidateOnAnimation() }
    private fun currentTab(host: GlassHostLayout): Int =
        nativeBar?.get()?.let { resolveSelectedIndex(it, 5) } ?: host.adapterSelectedIndex
    private fun showServiceBar(host: GlassHostLayout) {
        serviceVisibleUntil = SystemClock.uptimeMillis() + 2_000L
        host.removeCallbacks(hideServiceBar)
        host.postDelayed(hideServiceBar, 2_000L)
        host.postInvalidateOnAnimation()
    }
    override fun hostPageAllowsNavigation(host: GlassHostLayout, root: View?): Boolean? {
        if (currentTab(host) != 1) return true
        return serviceTouching || SystemClock.uptimeMillis() < serviceVisibleUntil
    }
    @Volatile private var artworkBitmap: Bitmap? = null
    private var artworkSignature = Long.MIN_VALUE
    override fun contentMotionHoldUntil() = motionUntil
    fun onWindowTouch(event: MotionEvent) {
        if (event.actionMasked in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP)) {
            motionUntil = SystemClock.uptimeMillis() + 750L
        }
        val host = activeHost.get()
        if (host != null && currentTab(host) == 1 && event.actionMasked in listOf(
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL)) {
            val released = event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            serviceTouching = !released
            showServiceBar(host)
        }
    }
    override fun onOpticalSurfaceFrameInvalidated() { surfaceRedrawPending = true }
    override fun shouldQueueOpticalRenderFromUi(animating: Boolean): Boolean {
        val queue = animating || wasAnimating || surfaceRedrawPending
        wasAnimating = animating
        surfaceRedrawPending = false
        return queue
    }

    override fun updateBackdropRendererMode(host: GlassHostLayout): Boolean {
        if (!compositorInitialized) {
            host.clearLiveBackdropForAdapter()
            compositorInitialized = true
        }
        host.disableOpticalSurfaceNativeBlurForAdapter()
        host.enableOpticalSurfaceRendererForAdapter()
        return true
    }

    override fun onHostFrame(host: View) {
        (host as? GlassHostLayout)?.let {
            activeHost = WeakReference(it)
            updateBackdropRendererMode(it)
            val bar = nativeBar?.get()
            XjtuContent.ensureNoBottomReservation(bar)
            val scene = bar?.let(XjtuContent::scene)
            val tab = currentTab(it)
            if (tab != lastContentTabIndex) {
                XjtuContent.refreshAllTabViewports(bar)
                serviceTouching = false
                if (tab == 1) showServiceBar(it)
                else { it.removeCallbacks(hideServiceBar); serviceVisibleUntil = 0L }
                lastContentTabIndex = tab
            }
            XjtuScheduleViewport.update(it, scene, tab == 3)
            XjtuServiceViewport.update(scene, tab)
            if (tab == 0) XjtuFloatingControl.update(it, scene, 0)
        }
    }
    private var nativeBar: WeakReference<ViewGroup>? = null

    override fun handleSystemBack(host: View): Boolean {
        val glass = host as? GlassHostLayout ?: return false
        if (currentTab(glass) != 1) return false
        serviceTouching = false
        showServiceBar(glass)
        return true
    }

    override fun prepare(source: ViewGroup?, schedule: (Long, () -> Unit) -> Unit) {
        bind(source)
    }

    override fun onHostPreDraw(
        host: View, source: ViewGroup?, selected: () -> Int,
        enabled: Boolean, density: Float, barHeightPx: Int, surfaceColor: () -> Int,
    ) {
        if (enabled) {
            bind(source)
            if (source?.let(::refreshArtwork) == true) {
                surfaceRedrawPending = true
                host.postInvalidateOnAnimation()
            }
        }
    }

    private fun bind(source: ViewGroup?) {
        val bar = source?.let(XjtuAdapter::nativeBar) ?: return
        val previous = nativeBar?.get()
        if (previous !== bar) {
            previous?.let(XjtuContent::release)
            nativeBar = WeakReference(bar)
            XjtuContent.acquire(bar)
        }
    }

    override fun resolveSelectedIndex(source: ViewGroup?, slotCount: Int): Int? {
        val bar = source?.let(XjtuAdapter::nativeBar) ?: return null
        if (slotCount != 5) return null
        return runCatching { (bar.javaClass.getField("o").get(bar) as? String)?.toIntOrNull() }
            .getOrNull()?.takeIf { it in 0..4 }
    }

    override fun resolveBackdropScene(host: View): View? = nativeBar?.get()?.let(XjtuContent::scene)

    override fun drawNativeNavigation(canvas: Canvas, source: ViewGroup, density: Float): Boolean {
        val bitmap = artworkBitmap?.takeUnless(Bitmap::isRecycled) ?: return false
        canvas.drawBitmap(bitmap, 0f, (source.height - bitmap.height) / 2f, null)
        return true
    }

    private fun refreshArtwork(source: ViewGroup): Boolean {
        val items = XjtuAdapter.tabItems(source) ?: return false
        data class Parts(val item: View, val icon: ImageView, val label: TextView,
                         val gap: Float, val badges: List<View>)
        val parts = items.map { item ->
            val icon = descendant<ImageView>(item, "tabIV") ?: return false
            val label = descendant<TextView>(item, "tabTV") ?: return false
            val iconPos = IntArray(2).also(icon::getLocationInWindow)
            val labelPos = IntArray(2).also(label::getLocationInWindow)
            val gap = (labelPos[1] - iconPos[1] - icon.height -
                4f * source.resources.displayMetrics.density).coerceAtLeast(0f)
            Parts(item, icon, label, gap, listOfNotNull(
                descendant<View>(item, "itemDot"), descendant<View>(item, "itemBadge")))
        }
        var signature = 17L
        val selected = resolveSelectedIndex(source, 5) ?: -1
        signature = signature * 31 + selected
        for (part in parts) {
            signature = signature * 31 + System.identityHashCode(part.icon.drawable)
            signature = signature * 31 + part.icon.visibility
            signature = signature * 31 + part.icon.width
            signature = signature * 31 + part.icon.height
            signature = signature * 31 + part.label.text.hashCode()
            signature = signature * 31 + part.label.currentTextColor
            signature = signature * 31 + part.label.textSize.toBits()
            signature = signature * 31 + part.gap.toBits()
            for (badge in part.badges) {
                signature = signature * 31 + badge.visibility
                if (badge is TextView) signature = signature * 31 + badge.text.hashCode()
            }
        }
        if (signature == artworkSignature && artworkBitmap?.isRecycled == false) return false
        val imageHeight = maxOf(source.height, parts.maxOf {
            kotlin.math.ceil(it.icon.height * ICON_SCALE + it.gap + it.label.height * TEXT_SCALE).toInt()
        })
        if (source.width <= 0 || imageHeight <= 0) return false
        val next = Bitmap.createBitmap(source.width, imageHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(next)
        canvas.drawColor(Color.TRANSPARENT, BlendMode.CLEAR)
        val sourcePos = IntArray(2).also(source::getLocationInWindow)
        parts.forEach { part ->
            val item = part.item
            val itemPos = IntArray(2).also(item::getLocationInWindow)
            val itemX = itemPos[0] - sourcePos[0]
            val icon = part.icon
            val label = part.label
            if (icon.visibility != View.VISIBLE || label.visibility != View.VISIBLE ||
                icon.width <= 0 || label.height <= 0) return@forEach
            val iconPos = IntArray(2).also(icon::getLocationInWindow)
            val iconTop = iconPos[1] - sourcePos[1]
            val gap = part.gap
            val enlargedIconHeight = icon.height * ICON_SCALE
            val enlargedLabelHeight = label.height * TEXT_SCALE
            val top = (imageHeight - enlargedIconHeight - gap - enlargedLabelHeight) / 2f
            val centerX = itemX + item.width / 2f
            drawLeaf(canvas, icon, centerX, top, ICON_SCALE)
            drawLeaf(canvas, label, centerX, top + enlargedIconHeight + gap, TEXT_SCALE)
            val originalIconRight = iconPos[0] - sourcePos[0] + icon.width
            val newIconRight = centerX + icon.width * ICON_SCALE / 2f
            val dx = newIconRight - originalIconRight
            val dy = top - iconTop
            for (badge in part.badges) {
                if (badge.visibility != View.VISIBLE) continue
                val pos = IntArray(2).also(badge::getLocationInWindow)
                drawLeaf(canvas, badge, pos[0] - sourcePos[0] + badge.width / 2f + dx,
                    pos[1] - sourcePos[1] + dy, 1f)
            }
        }
        val old = artworkBitmap
        artworkBitmap = next
        artworkSignature = signature
        if (old != null && old !== next) Handler(Looper.getMainLooper()).postDelayed({ old.recycle() }, 2_000L)
        return true
    }

    private inline fun <reified T : View> descendant(root: View, name: String): T? {
        val stack = ArrayDeque<View>(); stack += root
        while (stack.isNotEmpty()) {
            val view = stack.removeLast()
            if (view is T && view.id != View.NO_ID &&
                runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull() == name) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) stack += view.getChildAt(i)
        }
        return null
    }

    private fun drawLeaf(canvas: Canvas, view: View, centerX: Float, top: Float, scale: Float) {
        canvas.save()
        canvas.translate(centerX - view.width * scale / 2f, top)
        canvas.scale(scale, scale)
        view.draw(canvas)
        canvas.restore()
    }

    private companion object {
        const val ICON_SCALE = 1.35f
        const val TEXT_SCALE = 1.2f
    }

    override fun dispose() {
        activeHost.get()?.removeCallbacks(hideServiceBar)
        activeHost.clear()
        XjtuScheduleViewport.restore()
        nativeBar?.get()?.let(XjtuAdapter::restoreNativeRow)
        nativeBar?.get()?.let(XjtuContent::release)
        nativeBar = null
        artworkBitmap?.let { bitmap -> Handler(Looper.getMainLooper()).postDelayed({ bitmap.recycle() }, 2_000L) }
        artworkBitmap = null
    }
}
