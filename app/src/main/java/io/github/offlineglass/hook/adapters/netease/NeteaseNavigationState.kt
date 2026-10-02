package io.github.offlineglass.hook.adapters.netease

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.os.SystemClock
import io.github.offlineglass.hook.GlassHostLayout
import de.robv.android.xposed.XposedHelpers
import io.github.offlineglass.hook.adapters.AdapterNavigationFrame
import io.github.offlineglass.hook.adapters.AppNavigationState

/** Per-host NetEase navigation artwork and geometry. */
internal class NeteaseNavigationState(
    private val context: Context,
) : AppNavigationState {
    internal val playerState = NeteasePlayerState()
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private var navigationHeightPx = 0

    override fun onHostPreDraw(
        host: View,
        source: ViewGroup?,
        selected: () -> Int,
        enabled: Boolean,
        
        density: Float,
        barHeightPx: Int,
        surfaceColor: () -> Int,
    ) {
        navigationHeightPx = barHeightPx
        (host as? GlassHostLayout)?.let {
            activeHost = it
            playerState.bind(it)
            it.adjustNeteaseMiniPlayer()
            it.suppressNativeNeteaseBottomChrome(source)
        }
    }

    override fun syncVisibilityCompanion(host: View, selectedIndex: Int): Boolean {
        val glassHost = host as? GlassHostLayout ?: return false
        glassHost.neteaseRuntime.playerTouchShield?.let { shield ->
            shield.alpha = 1f
            shield.visibility = if (!glassHost.neteaseRuntime.sideDrawerOpenCached && selectedIndex != 0) {
                View.VISIBLE
            } else View.INVISIBLE
        }
        return true
    }

    override fun beforeHostDraw(host: View, source: ViewGroup?): Boolean {
        val glassHost = host as? GlassHostLayout ?: return true
        glassHost.suppressNativeNeteaseBottomChrome(source)
        return glassHost.neteaseRuntime.hasNavigationBar
    }

    override fun hostChromeAlpha(host: View, selectedIndex: Int): Float =
        (host as? GlassHostLayout)?.neteaseHomeFade() ?: 1f


    override fun drawAdditionalAmbientShadow(canvas: Canvas, dark: Boolean, alphaScale: Float) {
        // host is supplied through the draw frame lifecycle; the renderer itself has no global state.
        activeHost?.drawNeteasePlayerAmbientShadow(canvas, dark, alphaScale)
    }

    private var activeHost: GlassHostLayout? = null

    override fun beginOverlayTouch(host: View, event: MotionEvent): Boolean {
        val glassHost = host as? GlassHostLayout ?: return false
        activeHost = glassHost
        return glassHost.beginNeteasePlayerTouch(event)
    }

    override fun hasActiveOverlayTouch() = activeHost?.neteaseRuntime?.playerTouchTarget != null

    override fun forwardOverlayTouch(host: View, event: MotionEvent) {
        (host as? GlassHostLayout)?.forwardNeteasePlayerTouch(event)
    }

    override fun clearOverlayTouch() {
        activeHost?.neteaseRuntime?.playerTouchTarget = null
        activeHost?.neteaseRuntime?.playerTouchRect = null
    }

    override fun navigationBarGeometry(): android.graphics.RectF? =
        activeHost?.neteaseRuntime?.bottomBarRect?.takeUnless { it.isEmpty }

    override fun allowsHostTouch() = activeHost?.neteaseRuntime?.hasNavigationBar != false
    override fun drawsNavigationContent() = activeHost?.neteaseRuntime?.hasNavigationBar != false
    override fun suppressRedrawnSource() = activeHost?.neteaseRuntime?.hasNavigationBar
    override fun forceDarkMode(selectedIndex: Int): Boolean? =
        if (selectedIndex == 0 && activeHost?.neteaseRuntime?.heartbeatActive == true) true
        else (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    override fun resolveSelectedIndex(source: ViewGroup?, slotCount: Int): Int? {
        if (source?.javaClass?.name != "com.netease.cloudmusic.theme.ui.NavigationTabLayout") return null
        return runCatching {
            (XposedHelpers.callMethod(source, "getSelectedTabPosition") as? Number)?.toInt()
        }.getOrNull()?.takeIf { it in 0 until slotCount }
    }

    override fun pageAllowsNavigation(root: View?): Boolean? {
        val glassHost = activeHost ?: return null
        return !glassHost.isNeteaseSideDrawerOpen()
    }

    override fun resolveBackdropScene(host: View): View? {
        val glassHost = host as? GlassHostLayout ?: return null
        glassHost.neteaseRuntime.miniPlayerScene?.takeIf {
            it.isAttachedToWindow && it.isShown &&
                runCatching { glassHost.resources.getResourceEntryName(it.id) }
                    .getOrNull() == "mainActivityViewPager"
        }?.let { return it }
        val id = glassHost.resources.getIdentifier(
            "mainActivityViewPager", "id", "com.netease.cloudmusic",
        )
        return if (id != 0) glassHost.rootView.findViewById<View>(id)?.also {
            glassHost.neteaseRuntime.miniPlayerScene = it
        } else null
    }

    override fun backdropSceneBaseColor(scene: View, fallback: Int) =
        neteaseSceneBaseColor(scene, fallback)

    override fun onHostTouchDown(host: View, selectedIndex: Int) {
        val glassHost = host as? GlassHostLayout ?: return
        if (selectedIndex != 0 || !glassHost.neteaseRuntime.heartbeatActive) return
        glassHost.neteaseRuntime.homeLastInteractionMs = SystemClock.uptimeMillis()
        glassHost.removeCallbacks(glassHost.neteaseRuntime.homeIdleHideRunnable)
        glassHost.postDelayed(
            glassHost.neteaseRuntime.homeIdleHideRunnable,
            NETEASE_HOME_OUTLINE_IDLE_MS + 32L,
        )
    }

    override fun onHostDetached() {
        activeHost?.removeCallbacks(playerState.homeIdleHideRunnable)
        activeHost = null
    }

    override fun draw(canvas: Canvas, frame: AdapterNavigationFrame) {
        if (frame.slotCount != LABELS.size || frame.width <= 0 || frame.height <= 0) return
        val navHeight = navigationHeightPx.takeIf { it > 0 }?.toFloat() ?: frame.height.toFloat()
        val navTop = (frame.height - navHeight).coerceAtLeast(0f)
        val horizontalPadding = 4f * frame.density
        val itemScale = frame.iconScale * frame.extraScale
        labelPaint.textSize = LABEL_TEXT_SP * LABEL_SCALE * frame.scaledDensity
        val metrics = labelPaint.fontMetrics
        val baseline = navTop + navHeight / 2f - (metrics.ascent + metrics.descent) / 2f
        val normalColor = if (frame.darkGlass) NeteaseStyle.inactiveLabelDark else Color.BLACK
        frame.enabledIndices.forEachIndexed { visualIndex, index ->
            val centerX = horizontalPadding + (visualIndex + 0.5f) * frame.slotWidth
            labelPaint.color = if (index == frame.selectedIndex) frame.accentColor else normalColor
            labelPaint.alpha = 255
            canvas.save()
            canvas.scale(itemScale, itemScale, centerX, navTop + navHeight / 2f)
            canvas.drawText(LABELS.getOrElse(index) { "" }, centerX, baseline, labelPaint)
            canvas.restore()
        }
    }

    private companion object {
        const val LABEL_TEXT_SP = 16.8f
        const val LABEL_SCALE = 1.1f
        val LABELS = arrayOf("首页", "搜索", "笔记", "我的")
    }
}
