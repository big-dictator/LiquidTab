package io.github.offlineglass.hook.adapters.apple_music

import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import androidx.core.graphics.ColorUtils
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RecordingCanvas
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.view.View
import io.github.offlineglass.hook.SpringFloat
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.BloomTiltTracker
import io.github.offlineglass.hook.adapters.apple_music.AppleMusicArtistGlass.Role
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.setBottomBarSquircle
import io.github.offlineglass.rendering.ContinuousControlOptics
import io.github.offlineglass.rendering.NativeBackdropBlur
import io.github.offlineglass.rendering.PostureGlass
import io.github.offlineglass.rendering.setFloatUniformExact
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

internal fun albumHighlightStrength(slider: Float) = slider * .55f
internal fun curatorHighlightStrength(slider: Float) = slider * .66f

/** Uses the artwork layers already recorded by Apple Music's Haze modifier. */
internal object AppleMusicArtistOptics {
    internal object BackdropSampling {
        fun scale(toolbar: Boolean, blurPx: Float) = if (!toolbar && blurPx >= 4f) .5f else 1f
        fun extent(size: Float, padding: Int, scale: Float) = kotlin.math.ceil((size + padding * 2) * scale).toInt()
        fun inset(extent: Int, size: Float, scale: Float) = (extent - size * scale) / 2f
    }
    // Quantize the normalized highlight direction independently of the refraction
    // vector. The sensor smooths these two signals at different rates.
    fun lightDirection(owner: View, posture: Boolean): Int {
        var x = if (posture) BloomTiltTracker.gravityX else 0f
        var y = if (posture) BloomTiltTracker.gravityY else -1f
        when (owner.display?.rotation) {
            android.view.Surface.ROTATION_90 -> { val old = x; x = -y; y = old }
            android.view.Surface.ROTATION_180 -> { x = -x; y = -y }
            android.view.Surface.ROTATION_270 -> { val old = x; x = y; y = -old }
        }
        val lengthSquared = x * x + y * y
        if (lengthSquared > BloomTiltTracker.GRAVITY_DIR_THRESHOLD_SQ) {
            val length = kotlin.math.sqrt(lengthSquared)
            x /= length
            y /= length
        } else { x = 0f; y = -1f }
        return ((x * 256f).roundToInt() shl 16) or ((y * 256f).roundToInt() and 0xffff)
    }
    private val surfaces = WeakHashMap<Any, Surface>()
    private val covered = WeakHashMap<Any, Surface>()
    private class PlacementShift(val placement: AppleMusicArtistCompose.Placement, val requested: () -> Int,
        var applied: Int)
    private val shifts = WeakHashMap<Any, PlacementShift>()

    internal object ToolbarGeometry {
        fun oppositeSides(back: Int, more: Int, middle: Int) =
            (back < middle && more > middle) || (more < middle && back > middle)

        fun origin(center: Float, faceSize: Float) = center - faceSize / 2f
        fun offset(edge: Float, right: Boolean, inner: Boolean, diameter: Float, center: Float, applied: Int): Int {
            val inset = if (inner) 1.5f * diameter else .5f * diameter
            val target = if (right) edge - inset else edge + inset
            return (target - center + applied).roundToInt()
        }
    }
    private val artwork = WeakHashMap<Any, Surface>()
    private val toolbarItems = java.util.IdentityHashMap<Any, Surface>()
    private val movingLayouts = java.util.IdentityHashMap<Any, Surface>()
    private val nativePressLayouts = java.util.IdentityHashMap<Any, (Canvas) -> Int>()
    private val nativeToolbarFaces = java.util.IdentityHashMap<Any, () -> Boolean>()

    fun bindNativePress(layout: AppleMusicArtistCompose.PressLayer, scale: (Canvas) -> Int) {
        OverflowDraw.install(layout.draw)
        nativePressLayouts[layout.layout] = scale
    }

    fun releaseNativePress(layout: AppleMusicArtistCompose.PressLayer, scale: (Canvas) -> Int) {
        if (nativePressLayouts[layout.layout] === scale) nativePressLayouts.remove(layout.layout)
    }

    fun bindNativeFaces(draws: List<Pair<Any, AppleMusicArtistCompose.Draw>>, enabled: () -> Boolean) {
        draws.forEach { (node, draw) ->
            installDraw(draw)
            nativeToolbarFaces[node] = enabled
        }
    }

    fun releaseNativeFaces(draws: List<Pair<Any, AppleMusicArtistCompose.Draw>>, enabled: () -> Boolean) {
        draws.forEach { (node, _) ->
            if (nativeToolbarFaces[node] === enabled) nativeToolbarFaces.remove(node)
        }
    }
    private val canvasFields = object : ClassValue<java.lang.reflect.Field?>() {
        override fun computeValue(type: Class<*>) = type.declaredFields.singleOrNull {
            it.type == Canvas::class.java
        }?.apply { isAccessible = true }
    }
    fun scaleMovingLayout(layout: Any, wrapper: Any): Pair<Canvas, Int>? {
        val target = movingLayouts[layout]
        val native = nativePressLayouts[layout]
        if (target == null && native == null) return null
        val canvas = canvasFields.get(wrapper.javaClass)?.get(wrapper) as? Canvas ?: return null
        val count = target?.scaleLayout(canvas) ?: native?.invoke(canvas) ?: -1
        return if (count >= 0) canvas to count else null
    }
    private val layers = WeakHashMap<RenderNode, Layer>()
    private val hooks = HashSet<Method>()
    private val active = ThreadLocal<DrawFrame?>()
    private var rendererInstalled = false
    private var applyingEffect = false

    private class Layer(val surface: WeakReference<Surface>, val nativeEffect: RenderEffect) {
        var applied: RenderEffect = nativeEffect
    }
    private class DrawFrame(
        val surface: Surface, val canvas: Canvas, val previous: DrawFrame?,
        val save: Int, val scope: Any,
    ) {
        var finished = false
        var substituted = false

        fun finish() {
            if (finished) return
            finished = true
            active.set(previous)
            canvas.restoreToCount(save)
            if (substituted) {
                surface.drawTint(canvas)
                surface.drawRim(canvas)
            }
        }
    }

    private fun installDraw(bridge: AppleMusicArtistCompose.Draw) {
        hook(bridge.method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (nativeToolbarFaces[param.thisObject]?.invoke() == true) {
                    bridge.content.invoke(param.args[0])
                    param.result = null
                    return
                }
                val covering = covered[param.thisObject]
                if (covering?.enabled == true && (covering.highlightOnly || covering.native.solid || covering.grouped || covering.hasRendered)) {
                    bridge.content.invoke(param.args[0])
                    param.result = null
                    return
                }
                val target = surfaces[param.thisObject] ?: return
                if (!target.enabled) return
                val scope = param.args[0]
                val (canvas, width, height) = bridge.access.read(scope) ?: return
                // The follower has no material of its own, even while its
                // Compose draw bounds are changing during placement.
                if (target.groupBackgroundOwned) {
                    target.updateGeometry(width, height)
                    val save = target.scaleForPress(canvas)
                    try { bridge.content.invoke(scope) } finally { if (save >= 0) canvas.restoreToCount(save) }
                    param.result = null
                    return
                }
                if (!target.updateGeometry(width, height)) return
                if (target.highlightOnly) {
                    val save = target.scaleForPress(canvas)
                    try {
                        target.drawAlbumFace(canvas)
                        bridge.content.invoke(scope)
                        target.drawFinish(canvas)
                        target.onRendered()
                        param.result = null
                    } finally { if (save >= 0) canvas.restoreToCount(save) }
                    return
                }
                if (target.native.solid) {
                    val save = target.scaleForPress(canvas)
                    try {
                        val offset = target.groupLeft
                        if (offset == 0f) target.drawSolid(canvas) else {
                            val saved = canvas.save()
                            try {
                                canvas.translate(offset, 0f)
                                target.drawSolid(canvas)
                            } finally { canvas.restoreToCount(saved) }
                        }
                        bridge.content.invoke(scope)
                        param.result = null
                    } finally { if (save >= 0) canvas.restoreToCount(save) }
                    return
                }
                val frame = DrawFrame(target, canvas, active.get(), canvas.save(), scope)
                canvas.clipPath(target.contour)
                active.set(frame)
            }
            override fun afterHookedMethod(param: MethodHookParam) {
                val frame = active.get() ?: return
                if (frame.surface === surfaces[param.thisObject]) frame.finish()
            }
        })
    }

    private fun installHazeContent(bridge: AppleMusicArtistCompose.Draw) {
        hook(bridge.content, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val frame = active.get() ?: return
                if (frame.scope === param.thisObject) frame.finish()
            }
        })
    }

    fun bind(node: Any, surface: Surface): Boolean = runCatching {
        surface.pressLayer?.let { OverflowDraw.install(it.draw) }
        if (!surface.highlightOnly && !surface.native.solid) {
            installRenderer()
            installHazeContent(surface.native.draw)
        }
        installDraw(surface.native.draw)
        for ((node, draw) in surface.native.covered) {
            installDraw(draw)
            covered[node] = surface
        }
        surface.native.placement?.let { installPlacement(it) { if (surface.enabled) surface.shift else 0 } }
        surface.native.artwork?.takeIf { it.clip == null }?.let { image ->
            hook(image.draw, object : XC_MethodHook() {
                private val saved = java.util.IdentityHashMap<MethodHookParam, Pair<Canvas, Int>>()
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val target = artwork[param.thisObject] ?: return
                    val wrapper = param.args[0] ?: return
                    val canvas = wrapper.javaClass.declaredFields.singleOrNull { it.type == Canvas::class.java }
                        ?.apply { isAccessible = true }?.get(wrapper) as? Canvas ?: return
                    val outline = target.artworkContour ?: return
                    if (!target.enabled || !target.hasRendered || !canvas.isHardwareAccelerated) return
                    saved[param] = canvas to canvas.save()
                    canvas.clipPath(outline)
                }
                override fun afterHookedMethod(param: MethodHookParam) {
                    val (canvas, count) = saved.remove(param) ?: return
                    canvas.restoreToCount(count)
                }
            })
            artwork[image.layout] = surface
        }
        surfaces[node] = surface
        surface.attachShadow()
        surface.invalidateAppearance()
        true
    }.getOrElse {
        XposedBridge.log("[LiquidTab][AppleMusic][artist] optical binding rejected: ${it.message}")
        false
    }

    fun unbind(node: Any, surface: Surface) {
        surface.native.toolbarItem?.let { item ->
            if (toolbarItems[item.backdrop] === surface) {
                toolbarItems.remove(item.backdrop)
                item.invalidate()
            }
        }
        if (surfaces[node] === surface) surfaces.remove(node)
        surface.native.covered.forEach { (node, _) -> if (covered[node] === surface) covered.remove(node) }
        val iterator = layers.iterator()
        while (iterator.hasNext()) {
            val (layer, owner) = iterator.next()
            if (owner.surface.get() === surface) {
                if (owner.applied !== owner.nativeEffect) setEffect(layer, owner.nativeEffect)
                iterator.remove()
            }
        }
        surface.native.artwork?.let { if (artwork[it.layout] === surface) artwork.remove(it.layout) }
        surface.native.frameClip?.update(false)
        surface.native.artwork?.clip?.update(false)
        surface.native.placement?.let(::releasePlacement)
        surface.invalidate()
        surface.releaseResources()
        if (surfaces.values.none { it.sharesOwner(surface) }) surface.stopTracking()
    }

    fun bindToolbarPair(share: Surface, more: Surface) {
        val left = share.native.toolbarItem ?: return
        val right = more.native.toolbarItem ?: return
        if (left.backdrop === right.backdrop || left.draw != right.draw) return
        if (toolbarItems[left.backdrop] === share && toolbarItems[right.backdrop] === more) return
        OverflowDraw.install(left.draw)
        toolbarItems[left.backdrop] = share
        toolbarItems[right.backdrop] = more
        left.invalidate()
        right.invalidate()
        ArtistDiagnostics.log("claimed both native backdrop branches, foreground stages=${left.decorations.size}/${right.decorations.size}")
    }

    fun hidesNativeBackdrop(node: Any): Boolean = toolbarItems[node]?.enabled == true

    fun installPlacement(placement: AppleMusicArtistCompose.Placement, offset: () -> Int) {
        for (method in placement.methods) {
            hook(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val binding = shifts[param.thisObject] ?: return
                    val delta = binding.requested()
                    binding.applied = delta
                    if (delta == 0) return
                    val position = param.args[0] as Long
                    val x = (position shr 32).toInt() + delta
                    param.args[0] = (x.toLong() shl 32) or (position and 0xffffffffL)
                }
            })
        }
        shifts[placement.target] = PlacementShift(placement, offset, shifts[placement.target]?.applied ?: 0)
    }

    fun releasePlacement(placement: AppleMusicArtistCompose.Placement) {
        if (shifts[placement.target]?.placement !== placement) return
        shifts.remove(placement.target)
        runCatching { placement.request() }
    }

    private fun hook(method: Method, callback: XC_MethodHook) {
        if (method !in hooks) {
            XposedBridge.hookMethod(method, callback)
            hooks.add(method)
        }
    }

    private fun installRenderer() {
        if (rendererInstalled) return
        hook(RenderNode::class.java.getMethod("setRenderEffect", RenderEffect::class.java),
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (applyingEffect || android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) return
                    val layer = param.thisObject as RenderNode
                    val surface = active.get()?.surface
                    val effect = param.args[0] as? RenderEffect
                    if (surface == null || effect == null || !surface.accepts(layer)) {
                        layers.remove(layer)
                    } else {
                        layers[layer] = Layer(WeakReference(surface), effect)
                    }
                }
            })
        hook(RecordingCanvas::class.java.getMethod("drawRenderNode", RenderNode::class.java),
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) return
                    val layer = param.args[0] as RenderNode
                    val frame = active.get()
                    val bound = layers[layer] ?: frame?.takeIf {
                        param.thisObject === it.canvas && !it.substituted && it.surface.accepts(layer)
                    }?.surface?.let { surface ->
                        val native = runCatching { surface.native.cachedEffect() }.getOrNull() ?: return@let null
                        Layer(WeakReference(surface), native).also { layers[layer] = it }
                    } ?: return
                    val surface = bound.surface.get()
                    if (surface == null || frame?.surface !== surface || param.thisObject !== frame.canvas) {
                        applyLayer(layer, bound, bound.nativeEffect)
                        // A retained Compose layer may be drawn again without calling setRenderEffect.
                        if (surface == null) layers.remove(layer)
                        return
                    }
                    val replacement = runCatching { surface.effect(layer) }.getOrNull()
                    if (replacement == null) {
                        applyLayer(layer, bound, bound.nativeEffect)
                        return
                    }
                    applyLayer(layer, bound, replacement)
                    frame.substituted = true
                    surface.onRendered()
                }
            })
        rendererInstalled = true
    }

    private fun applyLayer(layer: RenderNode, binding: Layer, effect: RenderEffect) {
        if (binding.applied === effect) return
        setEffect(layer, effect)
        binding.applied = effect
    }

    private fun setEffect(layer: RenderNode, effect: RenderEffect) {
        applyingEffect = true
        try { layer.setRenderEffect(effect) } finally { applyingEffect = false }
    }

    internal class Surface(
        private val bottom: GlassHostLayout, private val owner: View, val role: Role,
        val native: AppleMusicArtistCompose.Material, val originalBounds: Rect,
        private val settingsBack: Boolean = false,
        private val albumDetail: Boolean = false,
        private val standaloneToolbar: Boolean = false,
        edgeShader: (() -> RuntimeShader)? = null,
        private val tintFraction: () -> Float = { 1f },
    ) {
        private val shader by lazy { PostureGlass.shader(ContinuousControlOptics.source, flowing = !role.fixed) }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var cachedBlurRadius = Float.NaN
        private var cachedBlur: RenderEffect? = null
        private val location = IntArray(2)
        private val density get() = owner.resources.displayMetrics.density
        private val config get() = bottom.config
        private val detailStrength get() = if (standaloneToolbar) curatorHighlightStrength(config.darkBarHighlightStrength)
            else albumHighlightStrength(config.darkBarHighlightStrength)
        private var ambient: AppleMusicControlAmbient? = null
        private val ambientFace = RectF()

        fun attachShadow() {
            if (role == Role.CARD) return
            ambient = AppleMusicControlAmbient(owner).also { it.attach() }
        }
        private var width = 0f
        private var height = 0f
        private var radius = 0f
        private var sampled = false
        private var sampledScale = 0f
        private var cachedKey: EffectKey? = null
        private var cachedEffect: RenderEffect? = null
        private var poseRevision = Long.MIN_VALUE
        private var lastConfig: io.github.offlineglass.config.GlassConfig? = null
        private var lastDark = false
        private var lastLight = Int.MIN_VALUE
        private var drawn = false
        private var groupPartner: Surface? = null
        private var groupLeader: Surface? = null
        var groupLeft = 0f
            private set
        private var faceWidth = 0f
        private val face = RectF()
        private var previousFaceX = Float.NaN
        private var previousFaceY = Float.NaN
        private var previousSpacing = Float.NaN

        private fun faceBounds(): RectF? {
            if (faceWidth <= 0f || height <= 0f) return native.bounds()
            native.drawBounds?.let { read ->
                val drawn = read() ?: return null
                if (abs(drawn.width() - faceWidth) <= 1f && abs(drawn.height() - height) <= 1f) return drawn
            }
            val cell = native.bounds() ?: return null
            val x = ToolbarGeometry.origin(cell.centerX(), faceWidth)
            val y = ToolbarGeometry.origin(cell.centerY(), height)
            face.set(x, y, x + faceWidth, y + height)
            return face
        }
        private var initialBackdropRetries = 0
        private var hasDrawnBackdrop = false

        fun groupWith(partner: Surface?) {
            if (groupPartner === partner) return
            groupPartner = partner
            ArtistDiagnostics.log("top group leader=$role partner=${partner?.role}")
            width = 0f
            cachedKey = null
            invalidateAppearance()
        }

        fun groupUnder(leader: Surface?) {
            if (groupLeader === leader) return
            groupLeader = leader
            invalidateAppearance()
        }

        val groupBackgroundOwned get() = groupLeader?.enabled == true
        val grouped get() = groupBackgroundOwned || groupPartner?.enabled == true
        val pressOwner get() = groupLeader ?: this
        var pressLayer: AppleMusicArtistCompose.PressLayer? = null
        val highlightOnly get() = when (role) {
            Role.INFO, Role.PLAY, Role.FAVORITE, Role.CARD, Role.CARD_ACTION,
            Role.SHUFFLE, Role.ALBUM_PLAY, Role.ALBUM_LIBRARY -> true
            else -> false
        }
        private var pressure = 0f
        private var lightX = 0f
        private var lightY = 0f
        private val lightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val pressSpring = lazy {
            SpringFloat(0f, 300f, .5f, .001f) { value, _ ->
                pressure = value
                updatePressedLayout(value)
                groupPartner?.updatePressedLayout(value)
            }
        }
        private fun updatePressedLayout(value: Float) {
            pressLayer?.let {
                if (value == 0f) movingLayouts.remove(it.layout) else movingLayouts[it.layout] = this
                it.invalidate()
            }
            refreshShadow(bottom.isDarkGlass())
            invalidate()
        }
        fun scaleLayout(canvas: Canvas): Int {
            val parent = pressLayer?.parentBounds?.invoke() ?: return -1
            val center = if (grouped || standaloneToolbar) pressOwner.faceBounds() else pressOwner.native.bounds()
            if (center == null) return -1
            val pivotX = if (grouped) center.left + pressOwner.groupLeft + pressOwner.width / 2f else center.centerX()
            return scale(canvas, pivotX - parent.left, center.centerY() - parent.top)
        }
        fun hit(x: Float, y: Float) = enabled && native.bounds()?.contains(x, y) == true
        fun press(x: Float, y: Float) {
            val bounds = if (grouped || standaloneToolbar) faceBounds() else native.bounds()
            if (bounds == null) return
            lightX = if (grouped) x - bounds.left - groupLeft else x - bounds.centerX() + width / 2f
            lightY = y - bounds.centerY() + height / 2f
            lightPaint.shader = android.graphics.RadialGradient(lightX, lightY,
                maxOf(height, width * .5f, 1f), 0x30ffffff, Color.TRANSPARENT,
                android.graphics.Shader.TileMode.CLAMP)
            owner.removeCallbacks(endPress)
            pressSpring.value.animateTo(1f)
        }
        private val endPress = Runnable { if (pressSpring.isInitialized()) pressSpring.value.animateTo(0f) }
        fun releasePress(delay: Long) {
            owner.removeCallbacks(endPress)
            if (delay > 0) owner.postDelayed(endPress, delay) else endPress.run()
        }
        fun scaleForPress(canvas: Canvas): Int {
            if (pressLayer != null) return -1
            val pivot = groupLeader?.let { width / 2f - (it.width - it.faceWidth) / 2f *
                (if (it.groupLeft < 0f) -1f else 1f) } ?: (groupLeft + width / 2f)
            return scale(canvas, pivot, height / 2f)
        }
        private fun scale(canvas: Canvas, pivotX: Float, pivotY: Float): Int {
            val scale = pressScale()
            if (scale == 1f) return -1
            val save = canvas.save()
            canvas.scale(scale, scale, pivotX, pivotY)
            return save
        }
        private fun pressScale(): Float =
            1f + (4f * density / maxOf(48f * density, height)) * pressOwner.pressure.coerceIn(-.2f, 1.2f)

        private fun refreshShadow(dark: Boolean) {
            val shadow = ambient ?: return
            val face = if (enabled && faceWidth > 0f && !groupBackgroundOwned) faceBounds()?.let {
                ambientFace.set(it.left + groupLeft, it.top, it.left + groupLeft + width, it.top + height)
                ambientFace
            } else null
            shadow.refresh(face, dark, pressScale(), contour)
        }
        private var sourceProvider = native.sources
        fun updateSources(provider: () -> List<AppleMusicArtistCompose.Source>) { sourceProvider = provider }
        val hasRendered get() = drawn
        val contour = Path()
        val artworkContour = native.artwork?.let { image ->
            val radius = maxOf(image.radiusX, image.radiusY)
            if (min(image.radiusX, image.radiusY) > 0f && radius > 0f) {
                // Scale each corner independently and leave its tangent center aligned
                // with the corresponding corner of the outer frame.
                val path = Path()
                val scale = image.radiusY / image.radiusX
                val rect = image.rect
                path.setBottomBarSquircle(RectF(0f, 0f, rect.width(), rect.height() / scale),
                    image.radiusX, -1f)
                path.transform(android.graphics.Matrix().apply {
                    setScale(1f, scale)
                    postTranslate(rect.left, rect.top)
                })
                path
            } else null
        }
        private val solidLayerState = lazy { RenderNode("LiquidTab-AppleMusic-artist-solid") }
        private val solidLayer by solidLayerState
        private var recordedSources: List<RecordedSource> = emptyList()
        private var lastSolidEffect: RenderEffect? = null
        private data class RecordedSource(val node: RenderNode, val x: Float, val y: Float,
            val width: Int, val height: Int)
        private val visualBounds = native.bounds()?.let(::RectF) ?: RectF(originalBounds)
        private val rimShader by lazy {
            edgeShader?.invoke() ?: io.github.offlineglass.rendering.GlassEdgeHighlight.shader()
        }
        private val rimRecording = lazy { RenderNode("LiquidTab-AppleMusic-artist-rim") }
        private var rimLight = Int.MIN_VALUE
        private var rimAlpha = -1f
        private var rimDark = false
        private var rimDensity = 0f
        private var rimGeometry = -1
        var shift = 0
            private set
        val enabled get() = owner.isAttachedToWindow && owner.isShown &&
            config.enabled && config.liquidGlassEnabled && config.backdropCapture

        private data class EffectKey(
            val width: Int, val height: Int, val blur: Float,
            val posture: Boolean, val refraction: Float, val scale: Float, val fixedPose: Long,
        )

        fun refresh(revision: Long = PostureGlass.revision(owner, config.postureEffectsEnabled),
            dark: Boolean = bottom.isDarkGlass(), light: Int = lightDirection(owner, config.postureEffectsEnabled)) {
            refreshShadow(dark)
            native.frameClip?.update(enabled)
            native.artwork?.clip?.update(enabled)
            val appearanceChanged = lastConfig !== config || lastDark != dark || lastLight != light
            val poseChanged = poseRevision != revision
            lastConfig = config
            lastDark = dark
            lastLight = light
            poseRevision = revision
            if (drawn && appearanceChanged) invalidateAppearance()
            else if (drawn && !highlightOnly && poseChanged && config.postureEffectsEnabled) invalidate()
            val placement = native.placement ?: return
            if (settingsBack) {
                if (width <= 0f) return
                val parent = bottom.parent as? View ?: return
                parent.getLocationOnScreen(location)
                val barWidth = io.github.offlineglass.hook.glassHostWidthPx(parent, 5, config, density)
                val left = location[0] + (parent.width - barWidth) / 2f
                val next = if (enabled) (left - (originalBounds.exactCenterX() - width / 2f)).roundToInt() else 0
                if (shift != next && abs(next) <= 32f * density) {
                    shift = next
                    runCatching { placement.request() }
                }
                return
            }
            if (!role.top || width == 0f || bottom.width <= 0 || !bottom.isShown) return
            bottom.getLocationOnScreen(location)
            val left = location[0]
            val right = left + bottom.width
            if (grouped || standaloneToolbar) {
                val frame = faceBounds() ?: return
                val partner = groupPartner
                val spacing = partner?.faceBounds()?.let { it.centerX() - frame.centerX() } ?: 0f
                if ((partner != null || standaloneToolbar) && (frame.left != previousFaceX || frame.top != previousFaceY ||
                        previousSpacing != spacing)) {
                    previousFaceX = frame.left
                    previousFaceY = frame.top
                    previousSpacing = spacing
                    invalidateAppearance()
                }
                val end = frame.centerX() >= (left + right) * .5f
                val applied = shifts[placement.target]?.applied ?: 0
                val next = if (enabled) ToolbarGeometry.offset((if (end) right else left).toFloat(),
                    end, partner != null, height, frame.centerX(), applied) else 0
                if (next != shift && abs(next) <= 32f * density) {
                    shift = next
                    runCatching { placement.request() }
                }
                return
            }
            val center = originalBounds.exactCenterX()
            val leftControl = center < (left + right) * 0.5f
            val next = if (!enabled) 0 else if (leftControl) {
                (left - (center - width / 2f)).roundToInt()
            } else {
                (right - (center + width / 2f)).roundToInt()
            }
            if (next != shift && abs(next) <= 32f * density) {
                shift = next
                runCatching { placement.request() }
            }
        }

        fun reset() {
            cachedKey = null
            poseRevision = Long.MIN_VALUE
            lastConfig = null
            invalidateAppearance()
        }

        fun releaseResources() {
            ambient?.close()
            ambient = null
            if (rimRecording.isInitialized()) rimRecording.value.discardDisplayList()
            pressLayer?.let { if (movingLayouts[it.layout] === this) movingLayouts.remove(it.layout) }
            owner.removeCallbacks(endPress)
            if (pressSpring.isInitialized()) pressSpring.value.cancel()
            pressure = 0f
            if (solidLayerState.isInitialized()) {
                solidLayer.setRenderEffect(null)
                solidLayer.discardDisplayList()
            }
            recordedSources = emptyList()
            lastSolidEffect = null
            cachedEffect = null
            cachedKey = null
        }

        fun stopTracking() = PostureGlass.track(owner, false)

        fun invalidate() {
            if (owner.isAttachedToWindow) runCatching {
                native.invalidate()
            }
        }

        fun invalidateAppearance() {
            invalidate()
            // The toolbar can retain its outer clipped recording even when
            // the inner background is dirty. A press also dirties this layer.
            if (role.top && owner.isAttachedToWindow) pressLayer?.invalidate?.invoke()
        }

        fun updateGeometry(nativeWidth: Float, h: Float): Boolean {
            val partner = groupPartner
            // Two equal-height actions share a capsule with one diameter
            // between icon centres. Placement moves the native hit region too.
            val originalW = visualBounds.width()
            val originalH = visualBounds.height()
            if (nativeWidth > originalW + 3 * density || h > originalH + 3 * density ||
                nativeWidth < originalW - (if (role == Role.CARD) 60 else 13) * density ||
                h < originalH - 15 * density
            ) return false
            faceWidth = nativeWidth
            val ownCenter = if (partner != null) faceBounds()?.centerX() else null
            val otherCenter = if (partner != null) partner.faceBounds()?.centerX() else null
            val extension = when {
                partner == null -> 0f
                ownCenter != null && otherCenter != null -> otherCenter - ownCenter
                else -> min(nativeWidth, h)
            }
            val w = nativeWidth + abs(extension)
            val offset = min(0f, extension)
            if (w != width || h != height || groupLeft != offset) {
                groupLeft = offset
                if (partner != null) ArtistDiagnostics.log("top capsule ${w}x$h extension=$extension")
                width = w
                height = h
                radius = when (role) {
                    Role.CARD -> 24f * density
                    Role.ALBUM_PLAY -> AppleMusicArtistCorners.albumPlayRadius(h)
                    else -> min(w, h) / 2
                }
                contour.rewind()
                val rect = RectF(0f, 0f, w, h)
                if (role == Role.CARD || role.albumAction || partner != null) contour.setBottomBarSquircle(rect, radius, -1f)
                else contour.addOval(rect, Path.Direction.CW)
                refreshShadow(bottom.isDarkGlass())
                cachedKey = null
                sampled = false
                if (role.top) owner.post {
                    if (surfaces[native.haze] === this) {
                        refresh()
                        invalidateAppearance()
                    }
                }
            }
            return true
        }

        fun accepts(layer: RenderNode): Boolean =
            width > 0 && height > 0 && layer.width >= width - 1 && layer.height >= height - 1 &&
                layer.width <= width + 100 * density && layer.height <= height + 100 * density

        fun effect(layer: RenderNode, scale: Float = 1f): RenderEffect? {
            if (!enabled || (scale == 1f && !accepts(layer))) return null
            val cfg = config
            val blur = blurPx() * scale
            val refraction = (if (role.fixed) 15f else 18f) * density * cfg.refractionScale * scale
            val key = EffectKey(layer.width, layer.height, blur, cfg.postureEffectsEnabled, refraction,
                scale, if (role.fixed) poseRevision else 0L)
            if (!sampled || sampledScale != scale) {
                if (role == Role.CARD) {
                    val path = if (scale == 1f) contour else Path().also {
                        contour.transform(android.graphics.Matrix().apply { setScale(scale, scale) }, it)
                    }
                    ContinuousControlOptics.bind(shader, path, radius * scale)
                }
                else shader.setFloatUniform("continuousShape", 0f)
                sampled = true
                sampledScale = scale
            }
            if (role.fixed) PostureGlass.sync(shader, owner, key.posture)
            else PostureGlass.sync(shader, owner, key.posture, cfg.liquidViscosity,
                cfg.flowHorizontalStrength, cfg.flowVerticalStrength)
            if (cachedKey == key && !PostureGlass.effectChanged(shader)) return cachedEffect
            shader.setFloatUniform("size", width * scale, height * scale)
            val padX = BackdropSampling.inset(layer.width, width, scale)
            val padY = BackdropSampling.inset(layer.height, height, scale)
            shader.setFloatUniform("offset", -padX, -padY)
            val r = radius * scale
            shader.setFloatUniform("cornerRadii", r, r, r, r)
            shader.setFloatUniform("detachedGeometry", 0f, 0f, 0f)
            shader.setFloatUniform("safeSampleBounds", .5f, .5f, layer.width - .5f, layer.height - .5f)
            shader.setFloatUniform("refractionHeight", min(height / 3, 11 * density) * scale)
            shader.setFloatUniform("refractionAmount", -refraction)
            shader.setFloatUniform("depthEffect", 0f)
            shader.setFloatUniformExact("unifiedDropletPilot", 0f)
            shader.setFloatUniformExact("directionalRimPilot", 0f)
            shader.setFloatUniformExact("monotoneFlowPilot", 0f)
            val lens = RenderEffect.createRuntimeShaderEffect(shader, "content")
            if (cachedBlurRadius != blur) {
                cachedBlurRadius = blur
                cachedBlur = if (blur > 0f) NativeBackdropBlur.effect(blur, 1.25f) else null
            }
            val result = cachedBlur?.let { RenderEffect.createChainEffect(lens, it) } ?: lens
            cachedEffect = result
            cachedKey = key
            return result
        }

        fun drawSolid(canvas: Canvas): Boolean {
            val bounds = if (groupPartner != null || standaloneToolbar) faceBounds()?.let {
                sampleBounds.set(it.left + groupLeft, it.top, it.left + groupLeft + width, it.top + height)
                sampleBounds
            } else native.bounds()
            val pad = 22f * density
            val layers = if (bounds == null) emptyList() else sourceProvider().asSequence().filter { area ->
                area.node.width > owner.width * 0.55f && area.x < bounds.left + width + pad &&
                    area.x + area.node.width > bounds.left - pad && area.y < bounds.top + height + pad &&
                    area.y + area.node.height > bounds.top - pad
            }.distinctBy { it.node }.toList()
            if (bounds != null && layers.isNotEmpty()) {
                drawSolidBackdrop(canvas, bounds, layers)
                hasDrawnBackdrop = true
            } else if (!hasDrawnBackdrop && initialBackdropRetries < 4) {
                initialBackdropRetries++
                owner.postOnAnimation {
                    if (surfaces[native.haze] === this && enabled) {
                        invalidate()
                        owner.invalidate()
                    }
                }
            }
            drawTint(canvas)
            drawFinish(canvas)
            onRendered()
            return true
        }

        fun drawFinish(canvas: Canvas) {
            drawRim(canvas)
            val pressLight = if (albumDetail) detailStrength else 1f
            if (pressure > .001f && pressLight > 0f) {
                val p = (pressure.coerceAtMost(1f) * pressLight).coerceAtMost(1f)
                val saved = canvas.save()
                try {
                    canvas.clipPath(contour)
                    canvas.drawColor(Color.argb((20 * p).roundToInt(), 255, 255, 255))
                    lightPaint.alpha = (255 * p).roundToInt()
                    canvas.drawRect(0f, 0f, width, height, lightPaint)
                } finally { canvas.restoreToCount(saved) }
            }
        }

        private fun blurPx() = (if (role.fixed) 4f else config.blurRadius) * density

        private var recordingScale = 0f
        private var recordedPageColor = Color.TRANSPARENT
        private val pageColorResource by lazy {
            owner.resources.getIdentifier("background_color", "color", targetSpec.packageName)
        }
        private var pageNightMode = -1
        private var nativePageColor = Color.WHITE
        private val sampleBounds = RectF()
        private fun drawSolidBackdrop(
            canvas: Canvas, bounds: RectF, layers: List<AppleMusicArtistCompose.Source>,
        ) {
            val padding = (22f * density).roundToInt()
            // Only already-blurred scenery may be resampled. Text, vector artwork,
            // edge light, clips and input all use the original coordinate system.
            val scale = BackdropSampling.scale(role.top, blurPx())
            val w = BackdropSampling.extent(width, padding, scale)
            val h = BackdropSampling.extent(height, padding, scale)
            val padX = BackdropSampling.inset(w, width, scale) / scale
            val padY = BackdropSampling.inset(h, height, scale) / scale
            solidLayer.setPosition(0, 0, w, h)
            val optical = effect(solidLayer, scale) ?: return
            // The page's Haze source records scrolling children on a
            // transparent layer. Rebuild the native page behind those children
            // in the sample so undistorted glyphs cannot show through the lens.
            val pageColor = if (standaloneToolbar) toolbarPageColor() else Color.TRANSPARENT
            val next = layers.map { area -> RecordedSource(area.node,
                area.x - bounds.left + padX - area.node.left,
                area.y - bounds.top + padY - area.node.top, area.node.width, area.node.height) }
            if (next != recordedSources || recordingScale != scale || recordedPageColor != pageColor ||
                !solidLayer.hasDisplayList()) {
                val recording = solidLayer.beginRecording(w, h)
                try {
                    if (standaloneToolbar) recording.drawColor(pageColor)
                    recording.scale(scale, scale)
                    for (area in next) {
                        val save = recording.save()
                        recording.translate(area.x, area.y)
                        recording.drawRenderNode(area.node)
                        recording.restoreToCount(save)
                    }
                } finally {
                    solidLayer.endRecording()
                }
                recordedSources = next
                recordingScale = scale
                recordedPageColor = pageColor
            }
            if (lastSolidEffect !== optical) {
                setEffect(solidLayer, optical)
                lastSolidEffect = optical
            }
            val saved = canvas.save()
            try {
                canvas.clipPath(contour)
                if (standaloneToolbar) canvas.drawColor(pageColor)
                canvas.translate(-padX, -padY)
                canvas.scale(1f / scale, 1f / scale)
                canvas.drawRenderNode(solidLayer)
            } finally {
                canvas.restoreToCount(saved)
            }

        }

        private fun toolbarPageColor(): Int {
            val nightMode = owner.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
            if (pageNightMode != nightMode) {
                pageNightMode = nightMode
                val fallback = if (nightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES) Color.BLACK else Color.WHITE
                val color = pageColorResource.takeIf { it != 0 }?.let {
                    runCatching { owner.context.getColor(it) }.getOrNull()
                }
                nativePageColor = color?.takeIf { Color.alpha(it) == 255 } ?: fallback
            }
            return nativePageColor
        }

        fun drawAlbumFace(canvas: Canvas) {
            if (!role.albumAction) return
            val color = native.color() ?: return
            tintPaint.color = color
            canvas.drawPath(contour, tintPaint)
        }

        fun drawTint(canvas: Canvas) {
            val cfg = config
            val isDark = bottom.isDarkGlass()
            if (standaloneToolbar) {
                tintPaint.color = cfg.surfaceTintColor(isDark)
                canvas.drawPath(contour, tintPaint)
                return
            }
            val nativeColor = if (role.fixed) native.color()?.let {
                val alpha = if (role == Role.PLAY) (Color.alpha(it) * .86f).roundToInt()
                    else if (Color.alpha(it) == 255) 166 else Color.alpha(it)
                Color.argb(alpha, Color.red(it), Color.green(it), Color.blue(it))
            } ?: Color.TRANSPARENT else {
                val base = if (isDark) Color.rgb(28, 28, 30) else Color.WHITE
                val amount = (if (isDark) cfg.darkAlpha else cfg.lightAlpha)
                    .let { if (settingsBack) it.coerceAtMost(.22f) else it }
                (base and 0x00ffffff) or ((amount * 255).roundToInt().coerceIn(0, 255) shl 24)
            }
            val color = if (role == Role.PLAY) nativeColor else ColorUtils.setAlphaComponent(nativeColor,
                (Color.alpha(nativeColor) * tintFraction().coerceIn(0f, 1f)).roundToInt())
            if (Color.alpha(color) == 0) return
            tintPaint.color = color
            canvas.drawPath(contour, tintPaint)
        }

        fun drawRim(canvas: Canvas) {
            if (!canvas.isHardwareAccelerated || ((!role.fixed || albumDetail) && !config.outlineEnabled)) return
            val dark = bottom.isDarkGlass()
            val strength = if (albumDetail) detailStrength
                else config.darkBarHighlightStrength
            if (albumDetail && strength <= 0f) return
            val alpha = ((if (role.fixed && !albumDetail) 1.16f else if (dark) .58f else .75f) *
                strength).coerceIn(0f, 1f)
            val direction = if (lastLight == Int.MIN_VALUE) lightDirection(owner, config.postureEffectsEnabled) else lastLight
            val layer = rimRecording.value
            if (rimLight != direction || rimAlpha != alpha || rimDark != dark || rimDensity != density ||
                rimGeometry != contour.generationId || !layer.hasDisplayList()) {
                layer.setPosition(0, 0, kotlin.math.ceil(width).toInt(), kotlin.math.ceil(height).toInt())
                val record = layer.beginRecording()
                try {
                    if (role == Role.CARD) {
                        // Inside the straight spans the edge-distance mask has
                        // zero coverage beyond its 4.25dp support.
                        val inset = 5f * density
                        if (width > 2 * (radius + inset) && height > 2 * inset) {
                            record.clipOutRect(radius + inset, inset, width - radius - inset, height - inset)
                        }
                    }
                    recordRim(record, dark, alpha, direction)
                } finally { layer.endRecording() }
                rimLight = direction
                rimAlpha = alpha
                rimDark = dark
                rimDensity = density
                rimGeometry = contour.generationId
            }
            canvas.drawRenderNode(layer)
        }

        private fun recordRim(canvas: Canvas, dark: Boolean, alpha: Float, direction: Int) {
            val rim = rimShader
            rim.setFloatUniform("halfView", width / 2, height / 2)
            rim.setFloatUniform("highlightAlpha", alpha)
            rim.setColorUniform("strokeColor", Color.WHITE)
            rim.setFloatUniform("strokeAlphaMul", .12f)
            val x = (direction shr 16) / 256f
            val y = direction.toShort() / 256f
            rim.setFloatUniform("lightDir1", .7071068f * (x + y), .7071068f * (y - x), -.05f)
            rim.setFloatUniform("lightDir2", 0f, .1f, -.5f)
            rim.setFloatUniform("lightIntensity1", 1f)
            rim.setFloatUniform("lightIntensity2", .4f)
            rim.setColorUniform("lightColor1", Color.WHITE)
            rim.setColorUniform("lightColor2", Color.WHITE)
            paint.shader = rim
            paint.strokeWidth = density
            io.github.offlineglass.rendering.GlassEdgeHighlight.draw(canvas, contour, paint, rim, density, dark, alpha > 0f)
            paint.shader = null
        }

        fun onRendered() {
            if (drawn) return
            drawn = true
            if (settingsBack) owner.post { if (surfaces[native.haze] === this) refresh() }
            if (role == Role.CARD) owner.post {
                if (surfaces[native.haze] === this) {
                    refresh()
                    invalidate()
                }
            }
            ArtistDiagnostics.log("rendered ${role.name} ${width}x${height} node=${System.identityHashCode(native.haze)}")
        }

        fun sharesOwner(other: Surface) = owner === other.owner
    }

}
