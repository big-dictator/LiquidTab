package io.github.offlineglass.hook.adapters.amap

import io.github.offlineglass.rendering.setFloatUniformExact

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.BlendMode
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.RoundedRectBlurRegion
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.EditText
import io.github.offlineglass.config.GlassConfig
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.HookConfigReader
import io.github.offlineglass.hook.setBottomBarSquircle
import io.github.offlineglass.rendering.PostureGlass
import io.github.offlineglass.hook.compat.XposedHelpers
import java.util.WeakHashMap

/** Live Amap search glass. The map remains in its native SurfaceView compositor layer. */
internal object AmapSearchGlass {
    private val installed = WeakHashMap<View, Material>()
    private val secondaryTransitions = WeakHashMap<View, SecondaryTransition>()

    fun finishSecondary(ajxView: View) {
        secondaryTransitions[ajxView]?.contentReady()
    }

    fun installSecondary(pageRoot: ViewGroup) {
        if (Build.VERSION.SDK_INT < 37) return
        val preload = runCatching {
            XposedHelpers.callMethod(pageRoot, "getPreloadView") as? ViewGroup
        }.getOrNull() ?: return
        val content = runCatching {
            XposedHelpers.callMethod(pageRoot, "getContentView") as? ViewGroup
        }.getOrNull() ?: return
        val panel = runCatching {
            XposedHelpers.callMethod(pageRoot, "getContentRootContainer") as? ViewGroup
        }.getOrNull() ?: return
        val contentContainer = runCatching {
            XposedHelpers.callMethod(pageRoot, "getContentContainer") as? ViewGroup
        }.getOrNull() ?: return
        val ajxView = content.getChildAt(0) as? ViewGroup ?: return
        if (ajxView.javaClass.name != "com.autonavi.minimap.ajx3.views.AmapAjxView" ||
            preload.childCount != 1 || preload.getChildAt(0) !is ViewGroup ||
            contentContainer.parent !== panel || !HookConfigReader.read(
                pageRoot.context, pageRoot.context.packageName).enabled) return
        val transition = SecondaryTransition(pageRoot, preload, ajxView)
        secondaryTransitions[ajxView] = transition
        transition.start()
        val listener = object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (!pageRoot.isAttachedToWindow) return
                transition.installPreloadGlass()
                if (transition.installActualGlass()) {
                    pageRoot.viewTreeObserver.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(this)
                }
            }
        }
        pageRoot.viewTreeObserver.addOnGlobalLayoutListener(listener)
        pageRoot.post { listener.onGlobalLayout() }
    }

    private class SecondaryTransition(
        private val pageRoot: ViewGroup,
        private val preload: ViewGroup,
        private val ajxView: ViewGroup,
    ) : View.OnAttachStateChangeListener {
        private var finished = false
        private var ready = false
        private var preloadGlass: Material? = null
        private val beforeDraw = ViewTreeObserver.OnPreDrawListener {
            if (!finished) {
                installPreloadGlass()
                if (ready && installActualGlass()) reveal()
            }
            true
        }

        fun start() {
            pageRoot.addOnAttachStateChangeListener(this)
            pageRoot.viewTreeObserver.addOnPreDrawListener(beforeDraw)
        }

        fun installPreloadGlass() {
            if (finished || preloadGlass != null) return
            val card = preload.getChildAt(0) as? ViewGroup ?: return
            if (!card.isAttachedToWindow || card.width <= 0 || card.height <= 0 ||
                card.childCount < 2) return
            val material = Material(card, preload, preload, clearRoot = false,
                clearContainer = false,
                maxCornerRadiusDp = 24f)
            if (!material.bindSurface()) return
            synchronized(installed) { installed[card] = material }
            card.background = material
            material.start()
            preloadGlass = material
        }

        fun installActualGlass(): Boolean {
            val card = findSecondaryCard(pageRoot) ?: return false
            val inner = card.getChildAt(0) as? ViewGroup ?: return false
            if (inner.width < card.width * .9f || inner.height < card.height * .8f) return false
            synchronized(installed) {
                if (installed.containsKey(card)) return true
                val material = Material(card, inner, card, clearRoot = false,
                    maxCornerRadiusDp = 24f)
                if (!material.bindSurface()) return false
                installed[card] = material
                card.background = material
                inner.background = null
                material.start()
            }
            return true
        }

        fun contentReady() {
            if (finished) return
            ready = true
            if (installActualGlass()) reveal()
            else pageRoot.postDelayed({
                if (!finished) {
                    if (installActualGlass()) reveal() else reveal()
                }
            }, 500L)
        }

        private fun reveal() {
            if (finished) return
            finished = true
            pageRoot.viewTreeObserver.takeIf { it.isAlive }?.removeOnPreDrawListener(beforeDraw)
            preloadGlass?.stop()
            preloadGlass = null
            secondaryTransitions.remove(ajxView)
        }

        override fun onViewAttachedToWindow(v: View) = Unit
        override fun onViewDetachedFromWindow(v: View) {
            reveal()
            pageRoot.removeOnAttachStateChangeListener(this)
        }
    }

    private fun findSecondaryCard(root: ViewGroup): ViewGroup? {
        val stack = ArrayDeque<View>()
        stack += root
        var scanned = 0
        var card: ViewGroup? = null
        while (stack.isNotEmpty() && scanned++ < 4096) {
            val view = stack.removeLast()
            if (view is EditText && view.isShown) {
                var parent = view.parent as? ViewGroup
                while (parent != null && parent !== root) {
                    if (parent.width in (root.width * .8f).toInt()..root.width &&
                        parent.height in (view.height * 2)..(root.height / 3) &&
                        parent.top >= 0 && parent.isShown) card = parent
                    parent = parent.parent as? ViewGroup
                }
                if (card != null) return card
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) stack += view.getChildAt(i)
        }
        return null
    }

    fun install(activity: Activity) {
        if (Build.VERSION.SDK_INT < 37) return
        val root = activity.window?.decorView ?: return
        val id = activity.resources.getIdentifier("maphome_searchbar_bg", "id", activity.packageName)
        if (id != 0) {
            val bg = root.findViewById<View>(id)
            ((bg?.parent as? View)?.parent as? ViewGroup)?.let(::install)
        }
    }

    fun install(searchRoot: ViewGroup) {
        if (Build.VERSION.SDK_INT < 37) return
        val context = searchRoot.context
        val resources = context.resources
        val pkg = context.packageName
        val bgId = resources.getIdentifier("maphome_searchbar_bg", "id", pkg)
        val containerId = resources.getIdentifier("maphome_searchbar_container", "id", pkg)
        val contentId = resources.getIdentifier("layout_searchbar_content", "id", pkg)
        val iconId = resources.getIdentifier("btn_voice", "id", pkg)
        if (bgId == 0 || containerId == 0 || contentId == 0 || iconId == 0) return
        val container = searchRoot.findViewById<View>(containerId) as? ViewGroup ?: return
        val bg = container.findViewById<View>(bgId) as? ViewGroup ?: return
        if (searchRoot !is FrameLayout || container.parent !== searchRoot || bg.parent !== container ||
            bg.findViewById<View>(contentId) == null || bg.findViewById<View>(iconId) == null ||
            !bg.isClickable || bg.width <= 0 || bg.height <= 0 || !bg.isAttachedToWindow) return
        if (!HookConfigReader.read(context, pkg).enabled) return
        synchronized(installed) {
            installed[bg]?.let { existing ->
                if (bg.background !== existing) bg.background = existing
                return
            }
            val material = Material(bg, container, searchRoot)
            if (!material.bindSurface()) return
            installed[bg] = material
            bg.background = material
            container.background = null
            searchRoot.background = null
            material.start()
        }
    }

    private fun findMapSurface(owner: View): SurfaceView? {
        val root = owner.rootView as? ViewGroup ?: return null
        val stack = ArrayDeque<View>()
        stack += root
        var best: SurfaceView? = null
        var bestArea = 0L
        var scanned = 0
        while (stack.isNotEmpty() && scanned++ < 4096) {
            val view = stack.removeLast()
            if (view is SurfaceView && view.isShown && view.holder.surface.isValid &&
                view.width >= root.width * .7f && view.height >= root.height * .45f) {
                val area = view.width.toLong() * view.height
                if (area > bestArea) { bestArea = area; best = view }
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) stack += view.getChildAt(i)
        }
        return best
    }

    private class Material(
        private val owner: ViewGroup,
        private val container: ViewGroup,
        private val searchRoot: ViewGroup,
        private val clearRoot: Boolean = true,
        private val clearContainer: Boolean = true,
        private val maxCornerRadiusDp: Float = Float.POSITIVE_INFINITY,
    ) : Drawable(), View.OnAttachStateChangeListener {
        private val handler = Handler(Looper.getMainLooper())
        private var original = owner.background
        private val originalContainer = container.background
        private val originalRoot = searchRoot.background
        private var config = HookConfigReader.read(owner.context, owner.context.packageName)
        private val density = owner.resources.displayMetrics.density
        private fun cornerRadius(height: Int, settings: GlassConfig = config): Float =
            (height * settings.cornerRadiusPercent / 100f)
                .coerceIn(0f, minOf(height / 2f, maxCornerRadiusDp * density))
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply { blendMode = BlendMode.CLEAR }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val glassEdge = io.github.offlineglass.rendering.NativeGlassEdge()
        private val flow = Paint(Paint.ANTI_ALIAS_FLAG)
        private val shape = Path()
        private val rect = RectF()
        private val ownerLocation = IntArray(2)
        private val surfaceLocation = IntArray(2)
        private val node = RenderNode("LiquidTab-Amap-SearchFlow")
        private val lens = PostureGlass.shader(GlassHostLayout.ROUNDED_RECT_REFRACTION_SHADER)
        private var nodeWidth = 0
        private var nodeHeight = 0
        private var nodeDark = false
        private var nodeRefraction = Float.NaN
        private var edgeGradientWidth = 0
        private var edgeGradientHeight = 0
        private var edgeGradient: Shader? = null
        private var surface: SurfaceView? = null
        private var originalRegions: List<android.view.BlurRegion> = emptyList()
        private var appliedRegion: RoundedRectBlurRegion? = null
        private var regionBounds = Rect()
        private var regionBlur = Float.NaN
        private var regionRadius = Float.NaN
        private var released = false
        private var configAt = 0L
        private val probeOwnerLocation = IntArray(2)
        private val probeSurfaceLocation = IntArray(2)
        private var lastOwnerX = Int.MIN_VALUE
        private var lastOwnerY = Int.MIN_VALUE
        private var lastSurfaceX = Int.MIN_VALUE
        private var lastSurfaceY = Int.MIN_VALUE
        private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            ensureBackground()
            updateRegion()
        }
        private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
            if (config.enabled && !released) {
                ensureBackground()
                val map = surface
                if (map != null && owner.isShown) {
                    owner.getLocationInWindow(probeOwnerLocation)
                    map.getLocationInWindow(probeSurfaceLocation)
                    if (probeOwnerLocation[0] != lastOwnerX || probeOwnerLocation[1] != lastOwnerY ||
                        probeSurfaceLocation[0] != lastSurfaceX || probeSurfaceLocation[1] != lastSurfaceY) {
                        updateRegion(force = true)
                    }
                }
            }
            true
        }
        private val tick = object : Runnable {
            override fun run() {
                if (released || !owner.isAttachedToWindow) return
                val now = SystemClock.uptimeMillis()
                if (now - configAt >= 500L) {
                    val next = HookConfigReader.read(owner.context, owner.context.packageName)
                    if (next != config) {
                        if (next.blurRadius != config.blurRadius ||
                            next.cornerRadiusPercent != config.cornerRadiusPercent) updateRegion(force = true, next)
                        config = next
                        nodeWidth = 0
                        invalidateSelf()
                    }
                    configAt = now
                }
                if (!config.enabled) {
                    restore()
                } else {
                    ensureBackground()
                    if (owner.isShown && owner.hasWindowFocus()) updateRegion()
                    else clearRegion()
                }
                handler.postDelayed(this, 500L)
            }
        }

        fun bindSurface(): Boolean {
            val found = findMapSurface(owner) ?: return false
            if (found === surface) return true
            clearRegion()
            surface = found
            originalRegions = found.blurRegions.map { it.copy() }
            regionBlur = Float.NaN
            return true
        }

        fun start() {
            owner.addOnAttachStateChangeListener(this)
            owner.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
            owner.viewTreeObserver.addOnPreDrawListener(preDrawListener)
            updateRegion(force = true)
            handler.post(tick)
        }

        private fun ensureBackground() {
            if (owner.background !== this) {
                owner.background?.let { original = it }
                owner.background = this
            }
            if (clearContainer && container.background != null) container.background = null
            if (clearRoot && searchRoot.background != null) searchRoot.background = null
        }

        private fun updateRegion(force: Boolean = false, settings: GlassConfig = config) {
            if (released || !owner.isShown || !owner.hasWindowFocus()) return
            if (surface?.isAttachedToWindow != true || surface?.holder?.surface?.isValid != true) {
                if (!bindSurface()) return
            }
            val map = surface ?: return
            owner.getLocationInWindow(ownerLocation)
            map.getLocationInWindow(surfaceLocation)
            lastOwnerX = ownerLocation[0]; lastOwnerY = ownerLocation[1]
            lastSurfaceX = surfaceLocation[0]; lastSurfaceY = surfaceLocation[1]
            val left = ownerLocation[0] - surfaceLocation[0]
            val top = ownerLocation[1] - surfaceLocation[1]
            val bounds = Rect(left, top, left + owner.width, top + owner.height)
            if (bounds.isEmpty || !bounds.intersect(0, 0, map.width, map.height)) {
                clearRegion()
                return
            }
            val blur = (settings.blurRadius * density).coerceAtLeast(0f)
            val radius = cornerRadius(owner.height, settings)
            if (!force && bounds == regionBounds && blur == regionBlur && radius == regionRadius &&
                appliedRegion != null) return
            val region = RoundedRectBlurRegion(
                RectF(bounds), FloatArray(8) { radius }, .28f, blur,
            )
            runCatching { map.setBlurRegions(originalRegions + region) }.onSuccess {
                appliedRegion = region
                regionBounds = bounds
                regionBlur = blur
                regionRadius = radius
            }
        }

        private fun clearRegion() {
            val map = surface ?: return
            if (appliedRegion != null) runCatching { map.setBlurRegions(originalRegions) }
            appliedRegion = null
            regionBlur = Float.NaN
        }

        override fun draw(canvas: Canvas) {
            val w = bounds.width()
            val h = bounds.height()
            if (w <= 0 || h <= 0) return
            rect.set(bounds)
            shape.setBottomBarSquircle(rect, cornerRadius(h),
                config.cornerSmoothing)
            val saved = canvas.save()
            canvas.clipPath(shape)
            canvas.drawPath(shape, clear)
            val dark = when (config.themeMode) {
                GlassConfig.THEME_LIGHT -> false
                GlassConfig.THEME_DARK -> true
                else -> owner.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                    Configuration.UI_MODE_NIGHT_YES
            }
            if (canvas.isHardwareAccelerated && config.liquidGlassEnabled && !config.solidBarEnabled) {
                PostureGlass.sync(lens, owner, config.postureEffectsEnabled)
                if (nodeWidth != w || nodeHeight != h || nodeDark != dark || nodeRefraction != config.refractionStrength ||
                    PostureGlass.effectChanged(lens)) {
                    nodeWidth = w; nodeHeight = h; nodeDark = dark; nodeRefraction = config.refractionStrength
                    lens.setFloatUniform("size", w.toFloat(), h.toFloat())
                    lens.setFloatUniform("offset", 0f, 0f)
                    val radius = cornerRadius(h)
                    lens.setFloatUniform("cornerRadii", radius, radius, radius, radius)
                    lens.setFloatUniform("safeSampleBounds", .5f, .5f, w - .5f, h - .5f)
                    lens.setFloatUniform("refractionHeight", 12f * density)
                    lens.setFloatUniform("refractionAmount", -24f * density * config.refractionScale)
                    lens.setFloatUniform("depthEffect", 0f)
                    lens.setFloatUniform("detachedGeometry", 0f, 0f, 0f)
                    lens.setFloatUniformExact("flowingLiquidEnabled", 1f)
                    lens.setFloatUniformExact("unifiedDropletPilot", 0f)
                    lens.setFloatUniformExact("directionalRimPilot", 0f)
                    lens.setFloatUniformExact("monotoneFlowPilot", 0f)
                    lens.setFloatUniformExact("flowMotionEnabled", 1f)
                    lens.setFloatUniform("continuousShape", 0f)
                    node.setRenderEffect(RenderEffect.createRuntimeShaderEffect(lens, "content"))
                    node.setAlpha(.18f)
                    node.setPosition(0, 0, w, h)
                    val recording = node.beginRecording()
                    flow.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(),
                        intArrayOf(
                            if (dark) 0x66FFFFFF else 0x88FFFFFF.toInt(),
                            if (dark) 0x11FFFFFF else 0x22FFFFFF,
                            if (dark) 0x33000000 else 0x11000000,
                        ), floatArrayOf(0f, .52f, 1f), Shader.TileMode.CLAMP)
                    recording.drawRect(0f, 0f, w.toFloat(), h.toFloat(), flow)
                    node.endRecording()
                    flow.shader = null
                }
                canvas.drawRenderNode(node)
            } else {
                PostureGlass.track(owner, false)
            }
            val alpha = ((if (dark) config.darkAlpha else config.lightAlpha) * 255f)
                .toInt().coerceIn(0, 255)
            fill.color = if (dark) Color.argb(alpha, 33, 31, 38)
                else Color.argb(alpha, 243, 237, 247)
            canvas.drawPath(shape, fill)
            if (config.liquidGlassEnabled && !config.solidBarEnabled) {
                if (config.outlineEnabled) glassEdge.draw(canvas, shape, density, dark, 1.1f * config.darkBarHighlightStrength)
            } else {
            edge.strokeWidth = density * 1.1f
            if (edgeGradientWidth != w || edgeGradientHeight != h) {
                edgeGradientWidth = w; edgeGradientHeight = h
                edgeGradient = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(),
                    intArrayOf(0xBBFFFFFF.toInt(), 0x44FFFFFF, 0x99FFFFFF.toInt()),
                    floatArrayOf(0f, .52f, 1f), Shader.TileMode.CLAMP)
            }
            edge.shader = edgeGradient
            canvas.drawPath(shape, edge)
            edge.shader = null
            }
            canvas.restoreToCount(saved)
        }

        private fun restore() {
            clearRegion()
            PostureGlass.track(owner, false)
            if (owner.background === this) owner.background = original
            if (clearContainer && container.background == null) container.background = originalContainer
            if (clearRoot && searchRoot.background == null) searchRoot.background = originalRoot
        }

        override fun onViewAttachedToWindow(v: View) { handler.post(tick) }

        fun stop() {
            if (released) return
            released = true
            restore()
            handler.removeCallbacks(tick)
            owner.removeOnAttachStateChangeListener(this)
            owner.viewTreeObserver.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(layoutListener)
            owner.viewTreeObserver.takeIf { it.isAlive }?.removeOnPreDrawListener(preDrawListener)
            synchronized(installed) { installed.remove(owner) }
            node.discardDisplayList()
        }

        override fun onViewDetachedFromWindow(v: View) = stop()

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Drawable API")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
