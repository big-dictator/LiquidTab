package io.github.offlineglass.hook.adapters.meituan_main

import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.adapters.AppNavigationState
import io.github.offlineglass.hook.adapters.meituan_main.MeituanMainTabContract.SLOT_COUNT
import io.github.offlineglass.hook.adapters.meituan_main.MeituanMainTabContract.VIDEO

/** Cart geometry and native tab artwork belong exclusively to Meituan main. */
internal class MeituanMainNavigationState(private val pageHost: GlassHostLayout? = null) : AppNavigationState {
    private val binding = MeituanMainBinding()
    private val checkout = MeituanMainCheckoutController(binding)
    private val sceneSelector = MeituanMainSceneSelector(binding)
    private val profileSafety = MeituanMainProfileSafety(binding)
    private val profileWindow = MeituanMainProfileWindow()

    private var nativeScene: View? = null

    private var contentMotionUntil = 0L
    private var scrollObserver: android.view.ViewTreeObserver? = null
    private val scrollListener = android.view.ViewTreeObserver.OnScrollChangedListener { onContentScroll() }
    private val backdropState = MeituanMainBackdropState()
    private var lastScene: View? = null
    private var lastSceneRevision = -1
    private var sceneGeometry = ""
    private val scenePosition = IntArray(2)
    private val hostPosition = IntArray(2)
    private var sceneDx = Int.MIN_VALUE
    private var sceneDy = Int.MIN_VALUE

    override fun resolveSelectedIndex(source: ViewGroup?, slotCount: Int): Int? =
        nativeSelected(source, slotCount)

    private fun nativeSelected(source: ViewGroup?, slotCount: Int): Int? {
        if (slotCount != SLOT_COUNT) return null
        val native = MeituanMainNativeTabs.bind(source) ?: return null
        return native.cells.singleOrNull { it.glyph.isSelected }?.role
    }

    override fun performTap(host: View, source: ViewGroup?, index: Int, slotCount: Int): Boolean =
        dispatchLive(source, index, slotCount)

    private fun dispatchLive(source: ViewGroup?, index: Int, count: Int): Boolean {
        if (count != SLOT_COUNT || index !in 0 until count || index == VIDEO) return false
        val button = MeituanMainNativeTabs.bind(source)?.cells?.firstOrNull { it.role == index }?.button
            ?.takeIf { it.isAttachedToWindow && it.isEnabled } ?: return false

        val handled = button.performClick()
        sceneSelector.onFocusChanged(true)

        return handled
    }

    override fun resolveBackdropScene(host: View): View? = nativeScene ?: sceneSelector.samplingRoot
    override fun skipLiveBackdrop(selectedIndex: Int): Boolean = nativeScene == null
    override fun needsFreshSoftwareBackdrop(): Boolean =
        nativeScene == null && sceneSelector.samplingRoot?.let {
            it.isAttachedToWindow && backdropState.needsSample(it.isDirty)
        } == true
    override fun onSoftwareBackdropRecorded() = backdropState.recorded()
    override fun onLiveBackdropRecorded(selectedIndex: Int) = backdropState.recorded()
    override fun backdropSceneViews(content: ViewGroup, hostIndex: Int): List<View>? =
        (nativeScene)?.let { listOf(it) }

    override fun onHostFrame(host: View) {
        val glass = host as? GlassHostLayout ?: return
        val committed = nativeSelected(glass.adapterNavigationSource, glass.adapterSlotCount)
        if (committed != null && backdropState.select(committed)) {
            glass.invalidateOpticalSceneForAdapter()
            glass.invalidateOpticalBackdropForAdapter()
        }
        val index = committed ?: glass.navigationIndex
        nativeScene = sceneSelector.pick(glass, index, unsafeChannel = false)

        if (scrollObserver == null) scrollObserver = glass.rootView.viewTreeObserver.also {
            it.addOnScrollChangedListener(scrollListener)
        }
        val scene = nativeScene
        scene?.getLocationInWindow(scenePosition)
        glass.getLocationInWindow(hostPosition)
        val dx = scenePosition[0] - hostPosition[0]
        val dy = scenePosition[1] - hostPosition[1]
        if (sceneDx == Int.MIN_VALUE || kotlin.math.abs(dx - sceneDx) > 1) sceneDx = dx
        if (sceneDy == Int.MIN_VALUE || kotlin.math.abs(dy - sceneDy) > 1) sceneDy = dy
        val geometry = "${glass.width}:${glass.height}:${scene?.width}:${scene?.height}:$sceneDx:$sceneDy"
        if (scene !== lastScene || lastSceneRevision != index || geometry != sceneGeometry) {
            lastScene = scene; lastSceneRevision = index; sceneGeometry = geometry
            glass.invalidateOpticalSceneForAdapter()
        }

    }

    override fun onContentScroll() {
        contentMotionUntil = android.os.SystemClock.uptimeMillis()
        pageHost?.postInvalidateOnAnimation()
    }

    override fun onHostWindowFocusChanged(hasFocus: Boolean) {
        sceneSelector.onFocusChanged(hasFocus)

    }

    override fun onHostDetached() {
        backdropState.reset()
        profileWindow.restore()
        profileSafety.restore()
        scrollObserver?.takeIf { it.isAlive }?.removeOnScrollChangedListener(scrollListener)
        scrollObserver = null
        sceneSelector.detach(); nativeScene = null
        MeituanMainNativeTabs.release(takeoverRow); takeoverRow = null
        checkout.release(); binding.release()
    }


    override fun suppressNativeChromeEarly(source: ViewGroup?) {
        val host = pageHost ?: return
        if (!tabs.ready) return
        checkout.update(host, host.adapterSelectedIndex, false,
            null)
    }

    override fun adjustFloatingActions(host: View, source: ViewGroup?) {
        val glass = host as? GlassHostLayout ?: return
        if (!tabs.ready) return
        checkout.update(glass, glass.adapterSelectedIndex, false,
            null)
    }

    private val tabs = MeituanMainStateArtwork()
    private var takeoverRow: ViewGroup? = null
    override val readyForDrawing: Boolean get() = tabs.ready
    override fun drawsNavigationContent(): Boolean = tabs.ready
    override fun allowsHostTouch(): Boolean = tabs.ready
    override fun beforeHostDraw(host: View, source: ViewGroup?): Boolean = tabs.ready
    override fun hostChromeAlpha(host: View, selectedIndex: Int): Float = if (tabs.ready) 1f else 0f
    override fun suppressRedrawnSource(): Boolean = true
    private fun prepareTakeover(source: ViewGroup?): Boolean {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) return false
        // Feed relayout is not a user disable. Keep ownership and the last
        // verified artwork while waiting for a replacement source.
        if (source == null || MeituanMainNativeTabs.bind(source) == null) return false
        takeoverRow = source
        MeituanMainNativeTabs.activate(source, true)
        return tabs.prepare(source)
    }
    override fun prepare(source: ViewGroup?, schedule: (Long, () -> Unit) -> Unit) {
        prepareTakeover(source)
    }

    override fun draw(canvas: Canvas, frame: io.github.offlineglass.hook.adapters.AdapterNavigationFrame) {
        tabs.draw(canvas, frame, active = frame.hideSelectedForOptics)
    }

    override fun drawForeground(canvas: Canvas, frame: io.github.offlineglass.hook.adapters.AdapterNavigationFrame,
        selectionPath: android.graphics.Path, drawSource: () -> Unit): Boolean {
        val artworkFrame = frame.copy(extraScale = frame.extraScale *
            (frame.height / (64f * frame.density)).coerceIn(.875f, 1f))
        if (selectionPath.isEmpty) { tabs.draw(canvas, artworkFrame, active = false); return true }
        val outside = canvas.save()
        canvas.clipOutPath(selectionPath)
        // Foreground state depends on the lens geometry, never on the delayed
        // native page selection or the optical-source capture flag.
        tabs.draw(canvas, artworkFrame, active = false)
        canvas.restoreToCount(outside)
        val plain = 1f - frame.navigationOpticalMix.coerceIn(0f, 1f)
        if (plain > 0f) {
            val inside = canvas.save()
            canvas.clipPath(selectionPath)
            canvas.saveLayerAlpha(0f, 0f, frame.width.toFloat(), frame.height.toFloat(), (plain * 255).toInt())
            tabs.draw(canvas, artworkFrame, active = true)
            canvas.restoreToCount(inside)
        }
        return true
    }

    override fun includeNavigationInIndicatorScene(surfaceActive: Boolean) = true
    private var currentSelection = -1
    override fun onHostPreDraw(host: View, source: ViewGroup?, selected: () -> Int,
        enabled: Boolean, density: Float, barHeightPx: Int, surfaceColor: () -> Int) {
        if (!enabled) {
            (source ?: takeoverRow)?.let {
                MeituanMainNativeTabs.release(it, restoreNative = true)
                it.alpha = 1f
            }
            takeoverRow = null; tabs.clear()
            return
        }
        currentSelection = selected()
        val changed = prepareTakeover(source)
        profileSafety.update(host, enabled && tabs.ready && currentSelection == 4)
        if (changed) (host as? GlassHostLayout)?.invalidateProjectionCache()
    }
    override fun forceNavigationBarImmersed(host: GlassHostLayout): Boolean? {
        // Window fitting belongs to the committed native page, not the pending
        // preview target: never resize the window in the middle of the spring.
        val committed = nativeSelected(host.adapterNavigationSource, host.adapterSlotCount)
            ?: host.adapterSelectedIndex
        val profile = host.config.enabled && tabs.ready &&
            committed == 4
        profileWindow.update(host, profile)
        return if (profile) true else null
    }

    override fun dispose() {
        profileWindow.restore()
        profileSafety.restore()
        scrollObserver?.takeIf { it.isAlive }?.removeOnScrollChangedListener(scrollListener)
        scrollObserver = null
        sceneSelector.detach(); nativeScene = null
        checkout.release(); tabs.clear(); binding.release()
        MeituanMainNativeTabs.release(pageHost?.adapterNavigationSource,
            restoreNative = pageHost?.config?.enabled == false)
    }

}




