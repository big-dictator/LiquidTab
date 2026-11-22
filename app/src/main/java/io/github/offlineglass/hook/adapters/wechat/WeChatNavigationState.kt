package io.github.offlineglass.hook.adapters.wechat

import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.adapters.AppNavigationState

/** Mutable WeChat resources belong to one host, not the process-wide adapter. */
internal class WeChatNavigationState : AppNavigationState {
    override val persistentRestingIndicatorFill = true
    override val retainHiddenSourceFillWhileExpanded = false
    private val tabsRenderer = WeChatTabsRenderer()
    private var managerEnglishLabels = false
    private var selectedArtwork = false
    private var artworkDark = false
    private var artworkHost = java.lang.ref.WeakReference<io.github.offlineglass.hook.GlassHostLayout>(null)

    override fun includeNavigationInIndicatorScene(surfaceActive: Boolean): Boolean = true

    override fun drawForeground(canvas: Canvas, frame: io.github.offlineglass.hook.adapters.AdapterNavigationFrame,
        selectionPath: android.graphics.Path, drawSource: () -> Unit): Boolean {
        artworkDark = frame.darkGlass
        selectedArtwork = false
        if (selectionPath.isEmpty) { drawSource(); return true }
        val host = artworkHost.get()
        val opticalMix = if (canvas.isHardwareAccelerated && host?.hasUsableBackdrop() == true &&
            host.config.backdropCapture && !host.config.solidBarEnabled) frame.navigationOpticalMix else 0f
        val outside = canvas.save()
        try {
            canvas.clipOutPath(selectionPath)
            drawSource()
        } finally { canvas.restoreToCount(outside) }
        // The selected row is already inside the refracting RenderNode. Only
        // draw a direct fallback when the optical pass is unavailable/fading.
        if (opticalMix < 1f) {
            val inside = canvas.save()
            try {
                selectedArtwork = true
                canvas.clipPath(selectionPath)
                if (opticalMix > 0f) canvas.saveLayerAlpha(0f, 0f, frame.width.toFloat(), frame.height.toFloat(),
                    ((1f - opticalMix) * 255f).toInt())
                drawSource()
            } finally {
                selectedArtwork = false
                canvas.restoreToCount(inside)
            }
        }
        return true
    }
    private val frostController = WeChatNativeFrostController()
    private val miniProgramDetector = WeChatMiniProgramDetector()
    private val foldController = WeChatFoldController()
    private val contentTargetProbe = WeChatContentTargetProbe()
    private val veilController = WeChatVeilController()
    private val visualOrchestrator = WeChatVisualOrchestrator()
    private val recentDrawerDetector = WeChatRecentDrawerDetector()
    private var currentNavigationSource: ViewGroup? = null
    private var recentBackdropHeld = false
    private var recordedIndicatorDark: Boolean? = null
    private var recentVisible = false
    private var recentVisibleFraction = 0f
    private var recentGeometryMoving = false
    private var refreshBackdropUntil = 0L
    private var usePanelBackdrop = false

    override fun beforeHostDraw(host: View, source: ViewGroup?): Boolean {
        if (artworkHost.get() !== host) artworkHost = java.lang.ref.WeakReference(host as? io.github.offlineglass.hook.GlassHostLayout)
        currentNavigationSource = source
        // The taskbar header retains a visible sliver at list top. Its 2%
        // theme guard must not replace the resting lens's material input.
        // Only an expanded drawer or an actual bar transition needs the
        // panel-composed optical scene; ordinary scroll keeps the tinted row.
        usePanelBackdrop = recentVisible ||
            (host as? io.github.offlineglass.hook.GlassHostLayout)?.barVisibilityAnimating == true
        return true
    }

    override fun indicatorUsesPanelBackdrop(): Boolean = usePanelBackdrop

    override fun onHostPreDraw(
        host: View, source: ViewGroup?, selected: () -> Int,
        enabled: Boolean, density: Float,
        barHeightPx: Int,
        surfaceColor: () -> Int,
    ) {
        // WeChat draws its tab labels through drawNativeNavigation(), bypassing
        // the regular adapter draw(frame) callback. Refresh the language here
        // on the shared host's live pre-draw path so the switch takes effect
        // without restarting WeChat.
        managerEnglishLabels =
            (host as? io.github.offlineglass.hook.GlassHostLayout)
                ?.config?.managerEnglishNavigationLabels
                ?: managerEnglishLabels
        currentNavigationSource = source
        var context = host.context
        while (context is android.content.ContextWrapper && context !is android.app.Activity) {
            context = context.baseContext
        }
        // Hold theme classification before the 25% threshold starts the exit.
        // Native taskbar colours change as soon as the pull-down begins.
        val visibleFraction = recentDrawerDetector.visibleHeightFraction(context as? android.app.Activity)
        recentBackdropHeld = visibleFraction >= 0.02f
        val open = visibleFraction >= 0.25f
        recentGeometryMoving = visibleFraction != recentVisibleFraction
        recentVisibleFraction = visibleFraction
        val now = android.os.SystemClock.uptimeMillis()
        if (open != recentVisible) {
            recentVisible = open
            refreshBackdropUntil = now + 800L
        }
        if (now < refreshBackdropUntil && host.isShown) host.postInvalidateOnAnimation()
        foldController.adjust(host, source, selected(), density, recentVisible)
        visualOrchestrator.update(
            host, source, selected, enabled, density,
            foldController, contentTargetProbe, veilController, surfaceColor,
        )
    }

    override fun onHostFocusLost() {
        foldController.restoreAllWeChatFoldBarTranslations()
        foldController.weChatFoldBarTarget = null
        foldController.hideWeChatFoldTouchProxy()
    }

    override fun onHostDetached() {
        foldController.dispose()
        contentTargetProbe.clear()
        veilController.clearContacts()
        veilController.clearChatList()
        veilController.clearNavigationBlend()
    }

    override fun transientPageAllowsNavigation(activity: android.app.Activity?): Boolean =
        !recentDrawerDetector.isVisible(activity) && !miniProgramDetector.isVisible(activity)

    // Freeze theme classification only. Retaining a RenderNode does not freeze
    // its children, and keeping its old origin clips the incoming bar's bottom.
    override fun deferThemeDetection(now: Long): Boolean = recentBackdropHeld
    override fun forceDarkMode(selectedIndex: Int): Boolean? =
        if (recentBackdropHeld) recordedIndicatorDark else null

    override fun forceLiveBackdropFrame(selectedIndex: Int): Boolean =
        recentGeometryMoving || android.os.SystemClock.uptimeMillis() < refreshBackdropUntil


    // Record both optical scenes from the same live backdrop throughout entry
    // and exit, including final resting coordinates on otherwise static lists.
    override fun reuseCombinedIndicatorScene(surfaceActive: Boolean): Boolean = true
    override fun combinedIndicatorSceneNeedsRecord(
        backdrop: android.graphics.Bitmap?, navigation: android.graphics.Bitmap?, signature: Long,
        width: Int, height: Int, dark: Boolean, hasDisplayList: Boolean,
    ): Boolean = true

    override fun onCombinedIndicatorSceneRecorded(
        backdrop: android.graphics.Bitmap?, navigation: android.graphics.Bitmap?, signature: Long,
        width: Int, height: Int, dark: Boolean,
    ) {
        if (!recentBackdropHeld) recordedIndicatorDark = dark
    }
    override fun suppressHostNativeChrome(source: ViewGroup?, root: View, selected: Int) {
        frostController.suppress(source, root, selected)
    }

    override fun drawNativeNavigation(canvas: Canvas, source: ViewGroup, density: Float): Boolean {
        tabsRenderer.draw(canvas, source, density, selectedArtwork = selectedArtwork, dark = artworkDark,
            managerEnglishLabels = managerEnglishLabels,
            iconOnly = artworkHost.get()?.config?.let { it.bottomBarIconOnly || it.iconOnly } == true)
        return true
    }

    override fun drawNativeNavigation(canvas: Canvas, source: ViewGroup, density: Float, forOptics: Boolean): Boolean {
        tabsRenderer.draw(canvas, source, density, selectedArtwork = forOptics || selectedArtwork, dark = artworkDark,
            managerEnglishLabels = managerEnglishLabels,
            iconOnly = artworkHost.get()?.config?.let { it.bottomBarIconOnly || it.iconOnly } == true)
        return true
    }

    // The shared host routes adapters that own their artwork through draw(),
    // so this renderer must also be connected to that path. The native source
    // row remains attached for selection and badge state but is hidden beneath
    // the LiquidTab surface.
    override fun draw(canvas: Canvas, frame: io.github.offlineglass.hook.adapters.AdapterNavigationFrame) {
        managerEnglishLabels = frame.managerEnglishLabels
        val source = currentNavigationSource ?: return
        canvas.save()
        canvas.scale(frame.extraScale, frame.extraScale, frame.width / 2f, frame.height / 2f)
        tabsRenderer.draw(canvas, source, frame.density, frame.iconScale * 1.15f,
            frame.hideSelectedForOptics || selectedArtwork, frame.darkGlass, frame.managerEnglishLabels,
            iconOnly = frame.iconOnly, hostHeight = frame.height.toFloat(), hostWidth = frame.width)
        canvas.restore()
    }
}
