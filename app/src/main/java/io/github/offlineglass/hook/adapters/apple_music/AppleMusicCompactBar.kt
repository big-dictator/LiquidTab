package io.github.offlineglass.hook.adapters.apple_music

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import io.github.offlineglass.config.GlassConfig
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.adapters.AppNavigationState
import io.github.offlineglass.hook.adapters.TargetAdapter
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/** One animation drives both the player and the two compact navigation actions. */
internal class AppleMusicCompactBar(
    private val host: GlassHostLayout,
    private val drawIcon: (Canvas, Boolean, Float, Float, Float, Float) -> Unit,
    private val click: (Boolean) -> Unit,
    private val tintFraction: () -> Float,
) {
    var progress = 0f
        private set
    private var target = 0f
    private var animator: ValueAnimator? = null
    private var opticalAnimator: ValueAnimator? = null
    var selectionOpticalFraction = 1f
        private set

    private fun animateSelectionOptics(next: Float) {
        opticalAnimator?.cancel()
        if (selectionOpticalFraction == next) return
        opticalAnimator = ValueAnimator.ofFloat(selectionOpticalFraction, next).apply {
            duration = (180f * abs(next - selectionOpticalFraction)).roundToInt().coerceAtLeast(80).toLong()
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener {
                selectionOpticalFraction = it.animatedValue as Float
                host.postInvalidateOnAnimation()
            }
            start()
        }
    }
    private var window: View? = null
    private var sides: List<Side> = emptyList()
    private var transitionLayer: View? = null
    private var iconLayer: View? = null
    private var iconLayerParent: ViewGroup? = null
    private var lastOverlay: OverlayFrame? = null
    private data class OverlayFrame(val progress: Float, val width: Int, val height: Int,
        val sideSize: Int, val dark: Boolean, val label: CharSequence?, val config: GlassConfig)
    val ownsMovingIcons get() = iconLayer?.visibility == View.VISIBLE
    val shapeTransitionActive get() = progress > 0f || animator?.isRunning == true
    private var tracking = false
    private var startX = 0f
    private var startY = 0f
    private var lastY = 0f
    private var travel = 0f
    private var vertical = false
    private val pos = IntArray(2)
    private val slop = ViewConfiguration.get(host.context).scaledTouchSlop
    private val density get() = host.resources.displayMetrics.density
    val gap get() = (10f * density).roundToInt()

    fun prepare(available: Boolean) {
        if (!available) {
            clear()
            return
        }
        if (window != null) return
        if (!installTouchHook()) return
        window = host.rootView.also { sessions[it] = WeakReference(this) }
    }

    private fun observe(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                host.getLocationOnScreen(pos)
                val topInset = host.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.statusBars())?.top ?: 0
                tracking = event.rawY in (topInset + 72f * density)..(pos[1] - host.height * (1f - progress))
                startX = event.rawX
                startY = event.rawY
                lastY = startY
                travel = 0f
                vertical = false
            }
            MotionEvent.ACTION_MOVE -> if (tracking) {
                if (!vertical) {
                    val dx = abs(event.rawX - startX)
                    val dy = abs(event.rawY - startY)
                    if (dx > slop && dx >= dy) tracking = false
                    if (dy <= slop || dy <= dx) return
                    vertical = true
                }
                val delta = lastY - event.rawY
                lastY = event.rawY
                if (travel * delta < 0f) travel = 0f
                travel += delta
                if (abs(travel) >= 12f * density) {
                    animateTo(if (travel > 0f) 1f else 0f)
                    travel = 0f
                }
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> tracking = false
        }
    }

    private fun animateTo(next: Float) {
        if (target == next) return
        target = next
        animator?.cancel()
        val startProgress = progress
        val startOpticalFraction = selectionOpticalFraction
        if (next == 1f) animateSelectionOptics(0f) else opticalAnimator?.cancel()
        animator = ValueAnimator.ofFloat(progress, next).apply {
            duration = (440f * abs(next - progress)).roundToInt().coerceIn(240, 460).toLong()
            interpolator = OvershootInterpolator(1.1f)
            addUpdateListener {
                progress = it.animatedValue as Float
                if (next == 0f) {
                    val restored = ((startProgress - progress) / startProgress.coerceAtLeast(0.001f))
                        .coerceIn(0f, 1f)
                    val blend = restored * restored * (3f - 2f * restored)
                    selectionOpticalFraction = startOpticalFraction + (1f - startOpticalFraction) * blend
                }
                host.postInvalidateOnAnimation()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    cancelled = true
                }
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (!cancelled && next == 0f) selectionOpticalFraction = 1f
                    host.postInvalidateOnAnimation()
                }
            })
            start()
        }
    }

    fun layout(sideSize: Int, currentLabel: CharSequence?, searchLabel: CharSequence?) {
        val parent = host.parent as? ViewGroup ?: return
        if (!shapeTransitionActive || sideSize <= 0) {
            sides.forEach { it.wrapper.visibility = View.GONE }
            transitionLayer?.visibility = View.GONE
            if (ownsMovingIcons) {
                iconLayer?.visibility = View.INVISIBLE
                host.invalidate()
            }
            lastOverlay = null
            host.alpha = 1f
            return
        }
        if (sides.isEmpty()) sides = listOf(Side(parent, false), Side(parent, true))
        host.alpha = (1f - progress).coerceIn(0f, 1f)
        sides.forEachIndexed { index, side ->
            val right = index == 1
            val x = if (right) host.x + host.width - sideSize else host.x
            side.layout(sideSize, x, host.y + (host.height - sideSize) / 2f,
                if (right) searchLabel else currentLabel)
        }
        val padding = (32f * density).roundToInt()
        val layer = transitionLayer ?: object : View(parent.context) {
            private val buttonAmbient = AppleMusicAmbientPaint(density)
            override fun onDraw(canvas: Canvas) {
                val fraction = progress.coerceIn(0f, 1f)
                val originX = host.x - x
                val originY = host.y - y
                if (fraction < 1f) {
                    val save = canvas.save()
                    canvas.translate(originX, originY)
                    host.drawAdapterAmbientShadow(canvas, 1f - fraction)
                    canvas.restoreToCount(save)
                }
                sides.forEachIndexed { index, side ->
                    val wrapper = side.wrapper
                    val cx = wrapper.x - x + wrapper.width / 2f
                    val cy = wrapper.y - y + wrapper.height / 2f
                    val save = canvas.save()
                    canvas.translate(cx, cy)
                    canvas.scale(wrapper.scaleX, wrapper.scaleY)
                    canvas.translate(-wrapper.width / 2f, -wrapper.height / 2f)
                    buttonAmbient.draw(canvas, side.panel.clipPath, host.isDarkGlass(), fraction)
                    canvas.restoreToCount(save)


                }
            }
        }.also {
            it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            parent.addView(it, FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.LEFT))
            transitionLayer = it
        }
        val width = host.width + 2 * padding
        val height = maxOf(host.height, sideSize) + 2 * padding
        if (layer.width != width || layer.height != height) {
            layer.layoutParams = FrameLayout.LayoutParams(width, height, Gravity.TOP or Gravity.LEFT)
            layer.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            layer.layout(0, 0, width, height)
        }
        layer.translationX = host.x - layer.left - padding
        layer.translationY = host.y - layer.top - padding
        layer.visibility = View.VISIBLE
        // A sibling can be covered by the player or reordered by native chrome.
        // Draw artwork above all children of this content container.
        if (iconLayerParent !== parent) detachIconLayer()
        val artwork = iconLayer ?: object : View(parent.context) {
            override fun onDraw(canvas: Canvas) {
                val fraction = progress.coerceIn(0f, 1f)
                val saved = canvas.save()
                canvas.translate(host.x - left, host.y - top)
                sides.forEachIndexed { index, side ->
                    val button = side.wrapper
                    drawIcon(canvas, index == 1,
                        button.x + button.width / 2f - host.x,
                        button.y + button.height / 2f - host.y,
                        button.height * button.scaleY, fraction)
                }
                canvas.restoreToCount(saved)
            }
        }.also {
            it.visibility = View.INVISIBLE
            parent.overlay.add(it)
            iconLayerParent = parent
            iconLayer = it
        }
        val left = (host.x - padding).roundToInt()
        val top = (host.y - padding).roundToInt()
        artwork.layout(left, top, left + width, top + height)
        if (!ownsMovingIcons) {
            artwork.visibility = View.VISIBLE
            host.invalidate()
        }
        val previous = lastOverlay
        val dark = host.isDarkGlass()
        if (previous == null || previous.progress != progress || previous.width != width ||
            previous.height != height || previous.sideSize != sideSize || previous.dark != dark ||
            previous.label != currentLabel || previous.config != host.config) {
            lastOverlay = OverlayFrame(progress, width, height, sideSize, dark, currentLabel, host.config)
            layer.invalidate()
            artwork.invalidate()
        }
    }

    private fun detachIconLayer() {
        iconLayer?.let { iconLayerParent?.overlay?.remove(it) }
        iconLayer = null
        iconLayerParent = null
    }

    fun clear() {
        if (window == null && sides.isEmpty()) return
        window?.let(sessions::remove)
        window = null
        tracking = false
        animator?.cancel()
        animator = null
        opticalAnimator?.cancel()
        opticalAnimator = null
        selectionOpticalFraction = 1f
        target = 0f
        progress = 0f
        sides.forEach { (it.wrapper.parent as? ViewGroup)?.removeView(it.wrapper) }
        sides = emptyList()
        transitionLayer?.let { (it.parent as? ViewGroup)?.removeView(it) }
        transitionLayer = null
        detachIconLayer()
        lastOverlay = null
        host.alpha = 1f
    }

    private inner class Side(parent: ViewGroup, private val right: Boolean) {
        val wrapper = FrameLayout(parent.context)
        private var size = 0
        private var appliedConfig = host.config
        private var lastTintFraction = Float.NaN
        private fun config() = host.config.copy(
            barHeight = size / density, cornerRadiusPercent = 50f, hiddenMask = 0,
            detachedPublish = false, iconOnly = true, selectedAccent = false,
        )
        val panel: GlassHostLayout
        private val globalPanelMatrix = Matrix()

        init {
            val state = object : AppNavigationState {
                override fun retainNavigationWithoutNativeSource(selectedIndex: Int) = true
                override fun hostPageAllowsNavigation(host: GlassHostLayout, root: View?) = progress > 0f
                override fun allowsHostTouch() = false
                override fun pauseBackdropWork(host: View) = progress == 0f
                override fun resolveAutoDarkMode(host: View) = this@AppleMusicCompactBar.host.isDarkGlass()
                override fun resolveBackdropScene(host: View): View? = scene
                override fun backdropSceneBaseColor(scene: View, fallback: Int) =
                    appleMusicBackdropBase(scene, fallback)
                override val allowsSoftwareBackdropCapture = false
                override fun forceLiveBackdropFrame(selectedIndex: Int) = animator?.isRunning == true
                override fun drawsNavigationContent() = false
                override fun surfaceTintFraction() = tintFraction()
                override fun drawsPanelAmbientShadow() = false
            }
            val adapter = object : TargetAdapter {
                override val key = "apple_music_compact_action"
                override val ownsHostLayout = true
                override val ownsNavigationDrawing = true
                override val skipNavigationSnapshot = true
                override val allowsSystemBackgroundBlur = false
                override val actionOnlyIndices = setOf(0)
                override val skipBackdropStableDelay = true
                // Both side buttons reference the same BlurTarget recorded for the bar.
                override fun liveBackdropTransform(parent: View, host: View, out: Matrix): Boolean =
                    io.github.offlineglass.hook.adapters.spotify.SpotifyBackdropSampling
                        .transform(parent, host, out, globalPanelMatrix)
                override fun normalizeConfig(config: GlassConfig) = this@Side.config()
                override fun pushedConfig() = this@Side.config()
                override fun createNavigationState(context: Context) = state
            }
            panel = GlassHostLayout(parent.context, host.config, 1, navigationSource = null,
                sliderEnabled = false, adapterOverride = adapter)
            panel.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            wrapper.addView(panel, FrameLayout.LayoutParams(-1, -1))
            wrapper.setOnClickListener {
                animateTo(0f)
                if (right) click(true)
            }
            parent.addView(wrapper, FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.LEFT))
        }

        fun layout(sideSize: Int, x: Float, y: Float, label: CharSequence?) {
            if (size != sideSize || appliedConfig != host.config) {
                size = sideSize
                appliedConfig = host.config
                wrapper.layoutParams = FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.LEFT)
                val spec = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
                wrapper.measure(spec, spec)
                wrapper.layout(0, 0, size, size)
                panel.refreshAdapterDerivedConfig()
            }
            val tint = tintFraction()
            if (lastTintFraction != tint) {
                lastTintFraction = tint
                panel.invalidate()
            }
            wrapper.visibility = View.VISIBLE
            wrapper.alpha = progress.coerceIn(0f, 1f)
            wrapper.translationX = x - wrapper.left + (if (right) -1 else 1) * size * .28f * (1f - progress)
            wrapper.translationY = y - wrapper.top
            wrapper.scaleX = .82f + .18f * progress
            wrapper.scaleY = wrapper.scaleX
            wrapper.isClickable = progress >= .65f
            if (wrapper.contentDescription != label) wrapper.contentDescription = label
        }
    }

    var scene: View? = null

    private companion object {
        val sessions = WeakHashMap<View, WeakReference<AppleMusicCompactBar>>()
        var hooked = false
        fun installTouchHook(): Boolean = runCatching {
            if (!hooked) {
                XposedBridge.hookMethod(Activity::class.java.getMethod("dispatchTouchEvent", MotionEvent::class.java),
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val activity = param.thisObject as Activity
                            val root = activity.window.peekDecorView() ?: return
                            sessions[root]?.get()?.observe(param.args[0] as MotionEvent)
                        }
                    })
                hooked = true
            }
            true
        }.getOrDefault(false)
    }
}
