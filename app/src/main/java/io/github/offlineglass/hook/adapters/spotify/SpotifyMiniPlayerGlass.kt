package io.github.offlineglass.hook.adapters.spotify

import io.github.offlineglass.rendering.setFloatUniformExact

import android.graphics.*
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.config.GlassConfig
import io.github.offlineglass.hook.setBottomBarSquircle
import io.github.offlineglass.rendering.KYANT_LENS_SHADER
import io.github.offlineglass.rendering.NativeBackdropBlur
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

/** Skin the native MotionLayout background; retain every native player control. */
internal class SpotifyMiniPlayerGlass {
    companion object {
        private val owned = java.util.WeakHashMap<View, Drawable>()
        private val hiddenProgress = java.util.WeakHashMap<View, Float>()
        private var guardInstalled = false
        private fun guard() {
            if (guardInstalled) return
            XposedBridge.hookAllMethods(View::class.java, "setBackgroundDrawable", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val replacement = owned[param.thisObject] ?: return
                    param.args[0] = replacement
                }
            })
            XposedBridge.hookAllMethods(View::class.java, "setAlpha", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (hiddenProgress.containsKey(param.thisObject)) param.args[0] = 0f
                }
            })
            guardInstalled = true
        }
    }
    private var player = WeakReference<View>(null)
    private var original: Drawable? = null
    private var material: PlayerMaterial? = null
    private var progress = WeakReference<View>(null)
    private var geometryShell = WeakReference<ViewGroup>(null)
    private var originalMargins: IntArray? = null
    private var originalTranslationY = 0f
    private val clipping = java.util.WeakHashMap<ViewGroup, Triple<Boolean, Boolean, Boolean>>()
    private var originalHeight: Int? = null
    private var compactHeight = 0
    private var originalMinimumHeight = 0
    private data class CoverState(val outline: ViewOutlineProvider?, val clipped: Boolean,
        val x: Float, val y: Float)
    private val covers = java.util.WeakHashMap<View, CoverState>()
    private val circularCoverOutline = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            val side = minOf(view.width, view.height)
            val left = (view.width - side) / 2
            val top = (view.height - side) / 2
            outline.setOval(left, top, left + side, top + side)
        }
    }
    private val buttonScales = java.util.WeakHashMap<View, Pair<Float, Float>>()
    private val resourceIds = HashMap<String, Int>()
    private fun resourceId(view: View, name: String): Int = resourceIds.getOrPut(name) {
        view.resources.getIdentifier(name, "id", "com.spotify.music")
    }
    private val buttonNames = arrayOf("connect_destination_button", "skippable_ad_view_stub", "add_to_button", "play_pause_button")
    private val coverSizes = java.util.WeakHashMap<View, Long>()
    private val hostLocation = IntArray(2)
    private val playerLocation = IntArray(2)
    private val shellLocation = IntArray(2)
    private val parentLocation = IntArray(2)
    private val coverLocation = IntArray(2)
    private val rootLocation = IntArray(2)

    private fun compactNativeLayout(candidate: View, config: GlassConfig, barHeight: Int) {
        if (barHeight <= 0) return
        val params = candidate.layoutParams ?: return
        if (originalHeight == null) {
            val nativeHeight = if (params.height > 0) params.height else candidate.height
            if (nativeHeight <= 0) return
            originalHeight = params.height
            originalMinimumHeight = candidate.minimumHeight
        }
        compactHeight = (barHeight * 0.8f).roundToInt().coerceAtLeast(1)
        if (candidate.minimumHeight != 0) candidate.minimumHeight = 0
        if (params.height != compactHeight) {
            params.height = compactHeight
            candidate.layoutParams = params
        }
        // Native cover and carousel are constrained to both parent edges, so they
        // reflow without scaling text. Fit fixed buttons uniformly only when
        // the configured bar height leaves less room than their native size.
        for (name in buttonNames) {
            val id = resourceId(candidate, name)
            candidate.findViewById<View>(id)?.let { button ->
                val old = buttonScales.getOrPut(button) { button.scaleX to button.scaleY }
                val fit = if (button.height > 0) minOf(1f, compactHeight.toFloat() / button.height) else 1f
                if (button.scaleX != old.first * fit) button.scaleX = old.first * fit
                if (button.scaleY != old.second * fit) button.scaleY = old.second * fit
            }
        }
        val coverId = resourceId(candidate, "cover_image")
        candidate.findViewById<View>(coverId)?.let { cover ->
            covers.getOrPut(cover) { CoverState(cover.outlineProvider, cover.clipToOutline,
                cover.translationX, cover.translationY) }
            if (cover.outlineProvider !== circularCoverOutline) cover.outlineProvider = circularCoverOutline
            if (!cover.clipToOutline) cover.clipToOutline = true
            val size = (cover.width.toLong() shl 32) or cover.height.toLong()
            if (coverSizes[cover] != size) {
                coverSizes[cover] = size
                cover.invalidateOutline()
            }
            if (cover.width > 0 && cover.height > 0 && candidate.height == compactHeight) {
                candidate.getLocationInWindow(playerLocation)
                cover.getLocationInWindow(coverLocation)
                val centerX = candidate.height * config.cornerRadiusPercent / 100f
                val wantedX = centerX - (coverLocation[0] - playerLocation[0] -
                    cover.translationX + cover.width / 2f)
                val wantedY = candidate.height / 2f - (coverLocation[1] - playerLocation[1] -
                    cover.translationY + cover.height / 2f)
                // Window locations round to pixels; avoid chasing half-pixel noise.
                if (kotlin.math.abs(wantedX - cover.translationX) > 0.5f) cover.translationX = wantedX
                if (kotlin.math.abs(wantedY - cover.translationY) > 0.5f) cover.translationY = wantedY
            }
        }
    }

    private fun alignGeometry(host: io.github.offlineglass.hook.GlassHostLayout, candidate: View) {
        val id = resourceId(host, "now_playing_view_container")
        val shell = host.rootView.findViewById<ViewGroup>(id) ?: return
        val params = shell.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (originalMargins == null) {
            geometryShell = WeakReference(shell)
            originalMargins = intArrayOf(params.leftMargin, params.rightMargin, params.marginStart, params.marginEnd)
            originalTranslationY = shell.translationY
        }
        if (host.width <= 0 || candidate.width <= 0) return
        host.getLocationInWindow(hostLocation)
        candidate.getLocationInWindow(playerLocation)
        shell.getLocationInWindow(shellLocation)
        val parent = shell.parent as? View ?: return
        parent.getLocationInWindow(parentLocation)
        // Preserve the native Compose-to-MotionLayout inset instead of stacking
        // a second horizontal margin around it.
        val insetLeft = playerLocation[0] - shellLocation[0]
        val insetRight = shell.width - insetLeft - candidate.width
        // Keep the approved five-tab width even when individual navigation tabs
        // are disabled. Player width is independent of the visible tab count.
        val root = host.rootView
        root.getLocationInWindow(rootLocation)
        val fixedWidth = io.github.offlineglass.hook.glassHostWidthPx(root, 5,
            host.config, root.resources.displayMetrics.density)
        val fixedLeft = rootLocation[0] + (root.width - fixedWidth) / 2
        val left = (fixedLeft - parentLocation[0] - insetLeft).coerceAtLeast(0)
        val right = (parent.width - (fixedLeft - parentLocation[0] + fixedWidth) - insetRight).coerceAtLeast(0)
        if (params.leftMargin != left || params.rightMargin != right) {
            params.leftMargin = left
            params.rightMargin = right
            params.marginStart = left
            params.marginEnd = right
            shell.layoutParams = params
        }
        val gap = io.github.offlineglass.hook.globalGlassBottomGapPx(host) / 2f
        val nativeBottom = playerLocation[1] + candidate.height - shell.translationY
        val wanted = hostLocation[1] - gap - nativeBottom
        if (kotlin.math.abs(shell.translationY - wanted) > 0.5f) shell.translationY = wanted
        var ancestor: View? = candidate
        while (ancestor != null && ancestor !== host.rootView) {
            if (ancestor is ViewGroup) {
                val group = ancestor
                clipping.getOrPut(group) { Triple(group.clipChildren, group.clipToPadding, group.clipToOutline) }
                if (group.clipChildren) group.clipChildren = false
                if (group.clipToPadding) group.clipToPadding = false
                if (group.clipToOutline) group.clipToOutline = false
            }
            ancestor = ancestor.parent as? View
        }
    }

    fun update(host: io.github.offlineglass.hook.GlassHostLayout, scene: View?): View? {
        val root = host.rootView
        val id = resourceId(root, "now_playing_bar_layout")
        val candidate = player.get()?.takeIf { it.isAttachedToWindow && it.rootView === root }
            ?: if (id != 0) root.findViewById<View>(id) else null
        val config = host.config
        val enabled = config.enabled && scene != null &&
            root.resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE
        if (!enabled || candidate !== player.get()) restore()
        if (!enabled || candidate == null) return candidate
        if (material == null) {
            guard()
            original = candidate.background
            val skin = PlayerMaterial(candidate, host)
            val nativeFilter = original?.colorFilter
            nativeFilter?.let { filter ->
                // getColor is present at runtime but omitted from SDK stubs.
                runCatching { filter.javaClass.getMethod("getColor").invoke(filter) as Int }
                    .getOrNull()?.let { skin.setTint(it) }
            }
            material = skin
            player = WeakReference(candidate)
            owned[candidate] = skin
            candidate.background = skin
        }
        material?.update(scene!!, config)
        // Player height, like its horizontal margins, is independent of how
        // many navigation entries are visible. Keep the approved five-tab
        // reference while still following the manager's height setting.
        val standardBarHeight = io.github.offlineglass.hook.glassHostHeightPx(
            host.resources.displayMetrics.density, config, "com.spotify.music", 5)
        compactNativeLayout(candidate, config, standardBarHeight)
        val progressId = resourceId(candidate, "progress_bar")
        candidate.findViewById<View>(progressId)?.let { bar ->
            if (!hiddenProgress.containsKey(bar)) hiddenProgress[bar] = bar.alpha
            progress = WeakReference(bar)
            if (bar.alpha != 0f) bar.alpha = 0f
        }
        SpotifyPlaybackHooks.bind(candidate,candidate.findViewById(progressId))
        material?.bindProgress(candidate.findViewById(progressId),
            candidate.findViewById(resourceId(candidate, "cover_image")), config.spotifyProgressFill)
        alignGeometry(host, candidate)
        return candidate
    }
    fun restore() {
        player.get()?.let { view ->
            SpotifyPlaybackHooks.unbind(view)
            originalHeight?.let { height ->
                view.layoutParams?.let { params -> params.height = height; view.layoutParams = params }
                view.minimumHeight = originalMinimumHeight
            }
        }
        originalHeight = null
        compactHeight = 0
        covers.forEach { (view, old) ->
            view.outlineProvider = old.outline; view.clipToOutline = old.clipped
            view.translationX = old.x; view.translationY = old.y
        }
        covers.clear()
        coverSizes.clear()
        buttonScales.forEach { (view, old) -> view.scaleX = old.first; view.scaleY = old.second }
        buttonScales.clear()
        progress.get()?.let { bar ->
            val oldAlpha = hiddenProgress.remove(bar)
            if (oldAlpha != null) bar.alpha = oldAlpha
        }
        progress.clear()
        geometryShell.get()?.let { shell ->
            val margins = originalMargins
            val params = shell.layoutParams as? ViewGroup.MarginLayoutParams
            if (margins != null && params != null) {
                params.leftMargin = margins[0]; params.rightMargin = margins[1]
                params.marginStart = margins[2]; params.marginEnd = margins[3]
                shell.layoutParams = params
            }
            shell.translationY = originalTranslationY
        }
        clipping.forEach { (view, old) ->
            view.clipChildren = old.first; view.clipToPadding = old.second; view.clipToOutline = old.third
        }
        clipping.clear()
        geometryShell.clear()
        originalMargins = null
        player.get()?.let { view ->
            owned.remove(view)
            if (view.background === material) view.background = original
        }
        material?.release()
        player.clear()
        original = null
        material = null
    }

    private class PlayerMaterial(player: View, host: io.github.offlineglass.hook.GlassHostLayout) : Drawable() {
        private val host = WeakReference(host)
        private val target = WeakReference(player)
        private val progressFill = SpotifyProgressFill(target)
        private var scene = WeakReference<View>(null)
        private var config = GlassConfig()
        private val node = RenderNode("SpotifyMiniPlayerBackdrop")
        private val shader = io.github.offlineglass.rendering.PostureGlass.shader(
            io.github.offlineglass.rendering.FlowingLiquid.kyantLensSource(KYANT_LENS_SHADER))
        private val nodeField = View::class.java.getDeclaredField("mRenderNode").apply { isAccessible = true }
        private val path = Path()
        private val rect = RectF()
        private val surfacePaint=Paint(Paint.ANTI_ALIAS_FLAG)
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.TRANSPARENT
        }
        private val sourceLocation = IntArray(2)
        private val targetLocation = IntArray(2)
        private val sourceTransform = Matrix()
        private val targetTransform = Matrix()
        private var effectKey = ""
        private var appliedFlowX = Float.NaN
        private var appliedFlowY = Float.NaN
        private var motionWasActive = false
        private var cachedBlurKey = Float.NaN
        private var cachedBlurEffect: RenderEffect? = null
        private var pathKey = ""
        private var glowDensity = Float.NaN
        private var glowOpacity = -1
        private var opacity = 255
        fun update(view: View, options: GlassConfig) {
            if (scene.get() !== view) scene = WeakReference(view)
            config = options
            invalidateSelf()
        }
        fun bindProgress(bar: android.widget.ProgressBar?, cover: android.widget.ImageView?, enabled: Boolean) {
            progressFill.bind(bar, cover, enabled)
        }
        fun release() { progressFill.release(); scene.clear(); node.discardDisplayList() }
        override fun draw(canvas: Canvas) {
            val player = target.get() ?: return
            val source = scene.get() ?: return
            if (!canvas.isHardwareAccelerated || bounds.isEmpty || !source.isShown) return
            val sourceNode = nodeField.get(source) as? RenderNode ?: return
            if (!sourceNode.hasDisplayList()) return
            val density = player.resources.displayMetrics.density
            val w = bounds.width()
            val h = bounds.height()
            val blur = config.blurRadius * density
            val pad = NativeBackdropBlur.padding(blur, 24f * density * config.refractionPaddingScale).toInt()
            val radius = h * config.cornerRadiusPercent / 100f
            rect.set(bounds)
            val newPathKey = "$bounds:$radius:${config.cornerSmoothing}"
            if (pathKey != newPathKey) {
                path.setBottomBarSquircle(rect, radius, config.cornerSmoothing)
                pathKey = newPathKey
            }
            // Match Spotify's navigation glow: a broad, faint, zero-offset shadow.
            if (glowDensity != density || glowOpacity != opacity) {
                glowPaint.setShadowLayer(18f * density, 0f, 0f,
                    Color.argb(14 * opacity / 255, 220, 235, 255))
                glowDensity = density
                glowOpacity = opacity
            }
            val glowSave = canvas.save()
            canvas.clipOutPath(path)
            if (config.postureEffectsEnabled && config.liquidGlassEnabled && !config.solidBarEnabled)
                io.github.offlineglass.rendering.PostureGlass.shadow(canvas, player, rect) {
                    canvas.drawPath(path, glowPaint)
                } else canvas.drawPath(path, glowPaint)
            canvas.restoreToCount(glowSave)
            // View RenderNodes are positioned in their parent's coordinates.
            (source.parent as? View ?: source).getLocationInWindow(sourceLocation)
            player.getLocationInWindow(targetLocation)
            val recording = node.beginRecording(w + pad * 2, h + pad * 2)
            recording.drawColor(0xFF121212.toInt())
            if (SpotifyBackdropSampling.transform(source.parent as? View ?: source,
                    player, sourceTransform, targetTransform)) {
                recording.translate(pad.toFloat(), pad.toFloat())
                recording.concat(sourceTransform)
            } else {
                recording.translate((sourceLocation[0] - targetLocation[0] + pad).toFloat(),
                    (sourceLocation[1] - targetLocation[1] + pad).toFloat())
            }
            recording.drawRenderNode(sourceNode)
            node.endRecording()
            node.setPosition(0, 0, w + pad * 2, h + pad * 2)
            val flowEnabled = config.postureEffectsEnabled && config.liquidGlassEnabled && !config.solidBarEnabled
            io.github.offlineglass.rendering.PostureGlass.track(player, flowEnabled)
            io.github.offlineglass.rendering.LiquidMotion.configure(player,
                config.flowHorizontalStrength, config.flowVerticalStrength)
            val motion = io.github.offlineglass.rendering.LiquidMotion.sample(player, config.liquidViscosity, flowEnabled)
            val pose = motion.pose
            val motionChanged = motion.active || motionWasActive
            motionWasActive = motion.active
            val key = "$w:$h:$pad:$radius:$blur:${config.liquidGlassEnabled}:$flowEnabled:${config.refractionStrength}"
            val poseChanged = (kotlin.math.abs(pose[0] - appliedFlowX) > 0.0005f ||
                kotlin.math.abs(pose[1] - appliedFlowY) > 0.0005f)
            if (key != effectKey || poseChanged || motionChanged) {
                if (cachedBlurEffect == null || cachedBlurKey != blur) {
                    cachedBlurEffect = SpotifyBackdropSampling.effect(blur)
                    cachedBlurKey = blur
                }
                var effect = requireNotNull(cachedBlurEffect)
                if (config.liquidGlassEnabled) {
                    // Keep source artwork fixed; only the shader's sample field flows.
                    shader.setFloatUniform("postureTilt", 0f, 0f)
                    shader.setFloatUniform("flowTilt", pose[0], pose[1])
                    shader.setFloatUniform("flowRipple", motion.rippleAmplitude, motion.rippleAge,
                        motion.rippleX, motion.rippleY)
                    shader.setFloatUniform("flowRebound", motion.reboundX, motion.reboundY)
                    shader.setFloatUniformExact("flowMotionEnabled", 1f)
                    shader.setFloatUniform("fullSurfaceWeight", if (flowEnabled) 1f else 0f)
                    shader.setFloatUniform("size", w.toFloat(), h.toFloat())
                    shader.setFloatUniform("offset", -pad.toFloat(), -pad.toFloat())
                    shader.setFloatUniform("safeSampleBounds", pad + 0.5f, pad + 0.5f,
                        pad + w - 0.5f, pad + h - 0.5f)
                    shader.setFloatUniform("cornerRadii", radius, radius, radius, radius)
                    // Compact (<5-tab) bars also reduce the player's height.
                    // Keep opposite refraction bands away from the centre:
                    // its signed-distance normal flips there and would sample
                    // two different scene rows, producing a horizontal seam.
                    shader.setFloatUniform("refractionHeight", minOf(24f * density, h * 0.45f))
                    shader.setFloatUniform("refractionAmount", -24f * density * config.refractionScale)
                    shader.setFloatUniform("depthEffect", 0f)
                    effect = RenderEffect.createChainEffect(
                        RenderEffect.createRuntimeShaderEffect(shader, "content"), effect)
                }
                node.setRenderEffect(effect)
                effectKey = key
                appliedFlowX = pose[0]
                appliedFlowY = pose[1]
            }
            val saved = if (opacity == 255) canvas.save() else canvas.saveLayerAlpha(rect, opacity)
            canvas.clipPath(path)
            canvas.clipRect(rect)
            canvas.translate(bounds.left - pad.toFloat(), bounds.top - pad.toFloat())
            canvas.drawRenderNode(node)
            canvas.restoreToCount(saved)
            val tint=config.surfaceTintColor(true)
            surfacePaint.color=tint
            surfacePaint.alpha=(Color.alpha(tint)*config.spotifyBarOpacity*opacity/255f).roundToInt().coerceIn(0,255)
            canvas.drawPath(path,surfacePaint)
            progressFill.draw(canvas, rect, path, opacity, density)
            val highlightSave = if (opacity == 255) canvas.save() else canvas.saveLayerAlpha(rect, opacity)
            host.get()?.drawAccessoryPanelHighlight(canvas, rect, radius, path)
            canvas.restoreToCount(highlightSave)
        }
        override fun setAlpha(alpha: Int) { opacity = alpha.coerceIn(0, 255); invalidateSelf() }
        override fun setColorFilter(filter: ColorFilter?) = Unit
        override fun setTint(tintColor: Int) {
            target.get()?.let { SpotifyPlaybackHooks.tint(it,tintColor) }
            progressFill.setThemeColor(tintColor)
        }
        override fun setTintList(tint: android.content.res.ColorStateList?) {
            tint?.let { setTint(it.getColorForState(state, it.defaultColor)) }
        }
        @Deprecated("Drawable opacity")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
