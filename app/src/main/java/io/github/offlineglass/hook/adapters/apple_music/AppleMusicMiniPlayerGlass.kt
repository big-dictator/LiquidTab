package io.github.offlineglass.hook.adapters.apple_music

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import io.github.offlineglass.config.GlassConfig
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.setBottomBarSquircle
import io.github.offlineglass.hook.adapters.AppNavigationState
import io.github.offlineglass.hook.adapters.TargetAdapter
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import java.lang.reflect.Method
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/** A backdrop for the compact card inside Apple Music's native player sheet. */
internal class AppleMusicMiniPlayerGlass(
    private val bottom: GlassHostLayout, private val tintFraction: () -> Float,
) {
    private fun id(name: String) = bottom.resources.getIdentifier(name, "id", targetSpec.packageName)
    private val blurId = id("mini_player_blur")
    private val sceneId = id("blur_target")
    private val coordinatorId = id("player_coordinator")
    private val sheetId = id("player_sheet_container")
    private val playerRootId = id("player_root")
    private val touchId = id("mini_player")
    private val cardId = id("mini_player_content")
    private val artworkId = id("video_surface_container")
    private val textId = id("miniplayer_text_container")
    private val titleId = id("mini_player_title")
    private val controlsId = id("buttons_container")
    private val playId = id("mini_player_play_btn")
    private val nextId = id("mini_player_next_btn")
    private val artworkLayerId = id("artwork_color_scheme_layer")
    private val materialLayerId = id("material_layer")
    private val gapId = bottom.resources.getIdentifier(
        "miniplayer_to_navtabs_spacing", "dimen", targetSpec.packageName,
    )
    private var content: ViewGroup? = null
    private var binding: Binding? = null
    private var nextProbeAt = 0L
    private var panel: GlassHostLayout? = null
    private var shadow: PlayerShadow? = null
    private var compactInput: CompactInput? = null
    private val rootPosition = IntArray(2)
    private var panelConfig: GlassConfig? = null
    private var sourceConfig: GlassConfig? = null
    private var visible = false
    private var lastTintFraction = Float.NaN
    private val position = IntArray(2)
    private var layoutObserver: ViewTreeObserver? = null
    private var initialProbes = 0
    private val layoutChanged = ViewTreeObserver.OnGlobalLayoutListener {
        if (binding == null) nextProbeAt = 0L
    }

    val compactHeight: Int
        get() = binding?.takeIf { visible && it.isCurrent() && it.atRest }?.card?.height ?: 0

    fun update(content: ViewGroup?, navigationPresented: Boolean, compact: Float = 0f, sideGap: Int = 0) {
        if (this.content !== content || binding?.isCurrent() == false) {
            clear()
            this.content = content
            layoutObserver = content?.viewTreeObserver?.also { it.addOnGlobalLayoutListener(layoutChanged) }
        }
        if (content == null) return
        if (binding == null) {
            val now = SystemClock.uptimeMillis()
            if (now < nextProbeAt) return
            binding = bind(content)
            val retryBeforeDraw = binding == null && navigationPresented && content.isShown && initialProbes < 4
            val delay = if (retryBeforeDraw) 16L else 250L
            nextProbeAt = now + delay
            if (retryBeforeDraw) {
                initialProbes++
                content.postInvalidateDelayed(delay)
            }
        }
        val current = binding ?: return
        val card = current.card
        current.align(navigationPresented, compact)
        current.resize(compact, sideGap)
        visible = current.blur.isShown && card.isShown && current.coordinator.alpha > 0.001f &&
            current.touch.alpha > 0.001f && card.width > 0 && card.height > 0
        if (!visible) {
            panel?.visibility = View.INVISIBLE
            shadow?.visibility = View.INVISIBLE
            compactInput?.visibility = View.GONE
            return
        }
        val heightDp = card.height / card.resources.displayMetrics.density
        val configChanged = sourceConfig != bottom.config || panelConfig?.barHeight != heightDp
        if (configChanged) {
            sourceConfig = bottom.config
            panelConfig = bottom.config.copy(detachedPublish = false, hiddenMask = 0, iconOnly = false,
                barHeight = heightDp, cornerRadiusPercent = 50f, selectedAccent = false)
        }
        val config = checkNotNull(panelConfig)
        val glass = panel ?: createPanel(current, config).also { panel = it }
        val dark = bottom.isDarkGlass()
        val halo = shadow ?: PlayerShadow(current.card.context).also {
            current.root.addView(it, current.root.indexOfChild(current.coordinator),
                ViewGroup.LayoutParams(1, 1))
            shadow = it
        }
        card.getLocationInWindow(position)
        current.root.getLocationInWindow(rootPosition)
        halo.place(position[0] - rootPosition[0], position[1] - rootPosition[1],
            card.width, card.height, config.cornerSmoothing, dark, card.alpha * current.touch.alpha)
        if (compact > 0f && current.atRest && navigationPresented) {
            val input = compactInput ?: CompactInput(current.touch).also {
                current.root.addView(it, ViewGroup.LayoutParams(1, 1))
                compactInput = it
            }
            input.place(position[0] - rootPosition[0], position[1] - rootPosition[1], card.width, card.height)
        } else {
            compactInput?.visibility = View.GONE
        }
        if (glass.width != card.width || glass.height != card.height) {
            val params = glass.layoutParams
            params.width = card.width
            params.height = card.height
            glass.layoutParams = params
            glass.measure(View.MeasureSpec.makeMeasureSpec(card.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(card.height, View.MeasureSpec.EXACTLY))
            glass.layout(0, 0, card.width, card.height)
        }
        glass.translationX = card.x - glass.left
        glass.translationY = card.y - glass.top
        glass.alpha = card.alpha
        glass.visibility = View.VISIBLE
        if (configChanged) glass.refreshAdapterDerivedConfig()
        val tint = tintFraction()
        if (lastTintFraction != tint) {
            lastTintFraction = tint
            glass.invalidate()
        }
        current.hideNativeMaterial()
    }

    private fun bind(root: ViewGroup): Binding? {
        if (gapId == 0 || materialLayerId == 0 || artworkLayerId == 0) return null
        val scene = root.uniqueChild(sceneId) as? ViewGroup ?: return null
        val blur = root.uniqueChild(blurId) ?: return null
        val coordinator = root.uniqueChild(coordinatorId) as? ViewGroup ?: return null
        val sheet = coordinator.uniqueChild(sheetId) as? ViewGroup ?: return null
        if (sheet.childCount != 1) return null
        val fragment = sheet.getChildAt(0) as? ViewGroup ?: return null
        val playerRoot = fragment.uniqueChild(playerRootId) as? ViewGroup ?: return null
        val touch = playerRoot.uniqueChild(touchId) as? FrameLayout ?: return null
        val card = touch.uniqueChild(cardId) as? ViewGroup ?: return null
        val text = card.uniqueChild(textId) as? ViewGroup ?: return null
        val title = text.uniqueChild(titleId) as? TextView ?: return null
        val controls = card.uniqueChild(controlsId) as? ViewGroup ?: return null
        val background = card.background as? LayerDrawable ?: return null
        if (card.uniqueChild(artworkId) !is ViewGroup || controls.uniqueChild(playId) !is ImageView ||
            controls.uniqueChild(nextId) !is ImageView || background.findDrawableByLayerId(materialLayerId) == null ||
            background.findDrawableByLayerId(artworkLayerId) == null ||
            scene.javaClass.name != "eightbitlab.com.blurview.BlurTarget" ||
            blur.javaClass.name != "eightbitlab.com.blurview.BlurView"
        ) return null
        val draw = runCatching { blur.javaClass.getDeclaredMethod("draw", Canvas::class.java) }
            .getOrNull()?.takeIf { it.returnType == Void.TYPE } ?: return null
        if (!NativeMiniPlayerBlur.install(draw)) return null
        return Binding(root, coordinator, sheet, fragment, playerRoot, touch, card, title, scene, blur)
    }

    private fun createPanel(current: Binding, config: GlassConfig): GlassHostLayout {
        val state = object : AppNavigationState {
            override fun retainNavigationWithoutNativeSource(selectedIndex: Int) = visible
            override fun hostPageAllowsNavigation(host: GlassHostLayout, root: View?) = visible
            override fun pauseBackdropWork(host: View) = !visible
            override fun allowsHostTouch() = false
            override fun drawsNavigationContent() = false
            override fun surfaceTintFraction() = tintFraction()
            override val allowsSoftwareBackdropCapture = false
            override fun resolveAutoDarkMode(host: View) =
                ColorUtils.calculateLuminance(current.title.currentTextColor) > 0.5
            override fun resolveBackdropScene(host: View): View = current.scene
            override fun backdropSceneBaseColor(scene: View, fallback: Int) =
                appleMusicBackdropBase(scene, fallback)
        }
        val adapter = object : TargetAdapter {
            override val key = "apple_music_mini_player"
            override val ownsHostLayout = true
            override val managesHostSiblingOrder = true
            override val contentAncestorMaxDepth = 0
            // The bar and player sample the same native BlurTarget. Its recorded
            // children stay live when the app redraws the feed.
            override val skipNavigationSnapshot = true
            override val skipBackdropStableDelay = true
            override val allowsSystemBackgroundBlur = false
            override val panelShadowAlphaScale = 0f
            override fun normalizeConfig(config: GlassConfig) = panelConfig ?: config
            override fun pushedConfig() = panelConfig
            override fun createNavigationState(context: Context) = state
        }
        return GlassHostLayout(current.card.context, config, 1,
            navigationSource = null, sliderEnabled = false, adapterOverride = adapter).apply {
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            current.touch.addView(this, 0, FrameLayout.LayoutParams(
                current.card.width, current.card.height, Gravity.TOP or Gravity.LEFT,
            ))
        }
    }

    fun restoreOptics() = panel?.restoreOpticalSceneForAdapter()

    fun clear() {
        initialProbes = 0
        visible = false
        shadow?.let { (it.parent as? ViewGroup)?.removeView(it) }
        shadow = null
        compactInput?.let { (it.parent as? ViewGroup)?.removeView(it) }
        compactInput = null
        panel?.let { (it.parent as? ViewGroup)?.removeView(it) }
        panel = null
        panelConfig = null
        sourceConfig = null
        binding?.restore()
        binding = null
        layoutObserver?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(layoutChanged)
        layoutObserver = null
        content = null
        nextProbeAt = 0L
    }

    private inner class Binding(
        val root: ViewGroup,
        val coordinator: ViewGroup,
        val sheet: ViewGroup,
        val fragment: ViewGroup,
        val playerRoot: ViewGroup,
        val touch: FrameLayout,
        val card: ViewGroup,
        val title: TextView,
        val scene: View,
        val blur: View,
    ) {
        private var originalBackground: Drawable? = card.background
        private var nativeTranslation = sheet.translationY
        private var appliedTranslation = nativeTranslation
        private var restingBottom: Int? = null
        private var restingCorrection = 0
        private val next = (card.uniqueChild(controlsId) as ViewGroup).uniqueChild(nextId)!!
        private val expandedWidth = card.layoutParams.width
        private var fullWidth = card.width
        private val nextAlpha = next.alpha
        private val nextVisibility = next.visibility
        var atRest = false
            private set

        fun isCurrent() = blur.parent === root && scene.parent === root && coordinator.parent === root &&
            sheet.parent === coordinator && fragment.parent === sheet && sheet.childCount == 1 &&
            playerRoot.parent === fragment && touch.parent === playerRoot && card.parent === touch &&
            (title.parent as? View)?.parent === card && card.isAttachedToWindow

        fun align(navigationPresented: Boolean, compact: Float) {
            if (sheet.translationY != appliedTranslation) {
                nativeTranslation = sheet.translationY
                appliedTranslation = nativeTranslation
            }
            if (!blur.isShown || !coordinator.isShown || coordinator.alpha <= 0.001f || card.height <= 0) {
                atRest = false
                restingBottom = null
                moveSheet(0f)
                return
            }
            card.getLocationInWindow(position)
            val nativeBottom = position[1] + card.height - (appliedTranslation - nativeTranslation).roundToInt()
            blur.getLocationInWindow(position)
            val distanceFromNativeRest = abs(nativeBottom - position[1] - blur.height)
            atRest = touch.alpha >= 0.999f && distanceFromNativeRest <= 2
            if (navigationPresented && bottom.isShown && bottom.height > 0 && atRest) {
                // The original sheet is at rest. Use the actual glass bar top
                // so the gap also survives bottom-bar height and inset changes.
                bottom.getLocationInWindow(position)
                restingBottom = nativeBottom
                restingCorrection = position[1] - bottom.resources.getDimensionPixelSize(gapId) - nativeBottom
            }
            val rest = restingBottom ?: return
            val progress = (1f - abs(nativeBottom - rest).toFloat() / card.height).coerceIn(0f, 1f)
            val drop = bottom.resources.getDimensionPixelSize(gapId) + (bottom.height + card.height) / 2f
            val collapse = if (atRest && navigationPresented) compact else 0f
            moveSheet((restingCorrection + drop * collapse) * progress)
        }

        fun resize(progress: Float, gap: Int) {
            val fraction = if (atRest) progress else 0f
            val params = card.layoutParams
            if (params.width == expandedWidth) fullWidth = card.width
            val middle = bottom.width - 2 * (card.height + gap)
            val width = if (fraction > 0f && middle > card.height * 2 && fullWidth > 0)
                (fullWidth + (middle - fullWidth) * fraction).roundToInt() else expandedWidth
            if (params.width != width) {
                params.width = width
                card.layoutParams = params
            }
            if (width != expandedWidth) {
                next.alpha = nextAlpha * (1f - fraction).coerceIn(0f, 1f)
                if (nextVisibility == View.VISIBLE) {
                    val visibility = if (fraction >= 1f) View.GONE else View.VISIBLE
                    if (next.visibility != visibility) next.visibility = visibility
                }
            } else if (next.alpha != nextAlpha || next.visibility != nextVisibility) {
                next.alpha = nextAlpha
                next.visibility = nextVisibility
            }
        }

        private fun moveSheet(offset: Float) {
            appliedTranslation = nativeTranslation + offset
            if (sheet.translationY != appliedTranslation) sheet.translationY = appliedTranslation
        }

        fun hideNativeMaterial() {
            card.background?.let {
                originalBackground = it
                card.background = null
            }
            NativeMiniPlayerBlur.mask(blur, true)
        }

        fun restore() {
            resize(0f, 0)
            if (sheet.translationY == appliedTranslation) moveSheet(0f)
            if (card.background == null) card.background = originalBackground
            NativeMiniPlayerBlur.mask(blur, false)
        }
    }

    /** Route the shifted card through its native touch hierarchy without the
     * original sheet's hit region intercepting dispatch. */
    private class CompactInput(private val nativeTouch: View) : View(nativeTouch.context) {
        private val nativePosition = IntArray(2)
        private val position = IntArray(2)

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        fun place(left: Int, top: Int, width: Int, height: Int) {
            val params = layoutParams
            if (params.width != width || params.height != height) {
                params.width = width
                params.height = height
                layoutParams = params
                measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
                layout(0, 0, width, height)
            }
            translationX = (left - this.left).toFloat()
            translationY = (top - this.top).toFloat()
            visibility = VISIBLE
        }

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (!nativeTouch.isAttachedToWindow || !nativeTouch.isShown) return false
            nativeTouch.getLocationInWindow(nativePosition)
            getLocationInWindow(position)
            val forwarded = MotionEvent.obtain(event)
            return try {
                forwarded.offsetLocation((position[0] - nativePosition[0]).toFloat(),
                    (position[1] - nativePosition[1]).toFloat())
                nativeTouch.dispatchTouchEvent(forwarded)
            } finally {
                forwarded.recycle()
            }
        }
    }

    /** The glass view is card-sized. Give its external shadow its own padded drawing area. */
    private class PlayerShadow(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val padding = (28f * density).roundToInt()
        private val contour = Path()
        private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.TRANSPARENT }
        private var smoothing = Float.NaN
        private var dark = false

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        fun place(left: Int, top: Int, width: Int, height: Int, smoothing: Float, dark: Boolean, opacity: Float) {
            val w = width + 2 * padding
            val h = height + 2 * padding
            if (this.width != w || this.height != h || this.smoothing != smoothing || this.dark != dark) {
                val params = layoutParams
                if (params.width != w || params.height != h) {
                    params.width = w
                    params.height = h
                    layoutParams = params
                    measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
                    layout(0, 0, w, h)
                }
                this.smoothing = smoothing
                this.dark = dark
                contour.setBottomBarSquircle(RectF(padding.toFloat(), padding.toFloat(),
                    (padding + width).toFloat(), (padding + height).toFloat()), height / 2f, smoothing)
                shadowPaint.setShadowLayer(8f * density, 0f, density,
                    if (dark) 0x18ffffff else 0x28000000)
                invalidate()
            }
            translationX = (left - this.left - padding).toFloat()
            translationY = (top - this.top - padding).toFloat()
            alpha = opacity
            visibility = VISIBLE
        }

        override fun onDraw(canvas: Canvas) {
            val save = canvas.save()
            canvas.clipOutPath(contour)
            canvas.drawPath(contour, shadowPaint)
            canvas.restoreToCount(save)
        }
    }
}

private object NativeMiniPlayerBlur {
    private val hooks = HashSet<Method>()
    private val masked = WeakHashMap<View, Unit>()

    fun install(draw: Method): Boolean {
        if (draw in hooks) return true
        return runCatching {
            XposedBridge.hookMethod(draw, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (masked.containsKey(param.thisObject)) param.result = null
                }
            })
            hooks.add(draw)
        }.onFailure {
            XposedBridge.log("[OfflineGlass][AppleMusic] mini player blur draw binding: ${it.message}")
        }.getOrDefault(false)
    }

    fun mask(view: View, enabled: Boolean) {
        val changed = if (enabled) masked.put(view, Unit) == null else masked.remove(view) != null
        if (changed) view.invalidate()
    }
}
