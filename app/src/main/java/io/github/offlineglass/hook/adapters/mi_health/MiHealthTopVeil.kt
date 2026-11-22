package io.github.offlineglass.hook.adapters.mi_health


import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView

/** Paints a blurred preview of the health list behind its fixed title. */
internal class MiHealthTopVeil {
    private var header: ViewGroup? = null
    private var originalHeaderBackground: Drawable? = null
    private var overlay: VeilView? = null
    private var overlayParent: ViewGroup? = null
    private var originalClipToPadding: Boolean? = null
    private var scrollObserver: ViewTreeObserver? = null
    private var scrollListener: ViewTreeObserver.OnScrollChangedListener? = null
    private var drawObserver: ViewTreeObserver? = null
    private var drawListener: ViewTreeObserver.OnPreDrawListener? = null
    private var lastProbeAt = 0L
    private var rawScene: View? = null

    fun currentRawScene(): View? = rawScene?.takeIf { it.isAttachedToWindow && it.isShown }
    fun currentGlassScene(): View? = overlay?.takeIf { it.isShown && it.isAttachedToWindow }
    fun currentHeader(): ViewGroup? = header?.takeIf { it.isShown && it.isAttachedToWindow }

    fun update(root: View, now: Long) {
        if (Build.VERSION.SDK_INT < 33 || now - lastProbeAt < 600L) return
        lastProbeAt = now
        val current = overlay
        if (current != null && current.isAttachedToWindow && header?.isShown == true) {
            val wantedHeight = header?.bottom ?: 0
            if (wantedHeight > 0 && current.layoutParams.height != wantedHeight) {
                current.layoutParams = current.layoutParams.apply { height = wantedHeight }
                current.enableBlur(wantedHeight)
            }
            current.translationY = -(overlayParent?.paddingTop ?: 0).toFloat()
            return
        }

        val main = findVisibleGroup(root, "main_cl_root") ?: return
        val title = findVisibleGroup(main, "header") ?: return
        val swipe = findVisibleGroup(main, "swipe_container") ?: return
        val parent = title.parent as? ViewGroup ?: return
        if (swipe.parent !== parent || title.bottom <= 0) return
        val scroll = findVisibleGroup(swipe, "scrollView") ?: return
        // fragment_data_v2: the direct child contains DataHeaderV2View,
        // SummaryCardView and the later content_view cards. Sampling only
        // content_view skips the first two sections and shifts every card.
        val content = scroll.getChildAt(0) as? ViewGroup ?: return
        val dataHeader = content.getChildAt(0) as? ViewGroup ?: return
        if (dataHeader.javaClass.name != "com.xiaomi.fitness.homepage.view.DataHeaderV2View") return
        val headerContent = dataHeader.getChildAt(0) as? ViewGroup ?: return
        val background = headerContent.getChildAt(0) as? ImageView ?: return
        if (background.background == null) return

        dispose()
        val veil = VeilView(title.context, scroll, content, background)
        if (!veil.enableBlur(title.bottom)) return
        rawScene = content
        originalHeaderBackground = title.background
        header = title
        overlay = veil
        overlayParent = parent
        originalClipToPadding = parent.clipToPadding
        parent.addView(veil, 0, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, title.bottom))
        parent.clipToPadding = false
        veil.translationY = -parent.paddingTop.toFloat()
        title.background = null
        val observer = scroll.viewTreeObserver
        val listener = ViewTreeObserver.OnScrollChangedListener { veil.invalidate() }
        if (observer.isAlive) {
            observer.addOnScrollChangedListener(listener)
            scrollObserver = observer
            scrollListener = listener
            // SpringBackLayout can move the scroll subtree without changing
            // NestedScrollView.scrollY. Track its actual screen position too.
            var previousGeometry = Long.MIN_VALUE
            val beforeDraw = ViewTreeObserver.OnPreDrawListener {
                if (!scroll.isShown) return@OnPreDrawListener true
                val geometry = veil.geometryKey()
                if (geometry != previousGeometry || content.isDirty) {
                    previousGeometry = geometry
                    veil.invalidate()
                }
                true
            }
            observer.addOnPreDrawListener(beforeDraw)
            drawObserver = observer
            drawListener = beforeDraw
        }
    }

    fun dispose() {
        rawScene = null
        val observer = scrollObserver
        val listener = scrollListener
        if (observer?.isAlive == true && listener != null) observer.removeOnScrollChangedListener(listener)
        scrollObserver = null
        scrollListener = null
        val beforeDraw = drawListener
        drawObserver?.let { if (it.isAlive && beforeDraw != null) it.removeOnPreDrawListener(beforeDraw) }
        drawObserver = null
        drawListener = null
        overlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
        overlay = null
        overlayParent?.let { parent -> originalClipToPadding?.let { parent.clipToPadding = it } }
        overlayParent = null
        originalClipToPadding = null
        header?.let { title ->
            if (title.background == null) title.background = originalHeaderBackground
        }
        header = null
        originalHeaderBackground = null
    }

    private fun findVisibleGroup(root: View, name: String): ViewGroup? {
        val stack = ArrayDeque<View>()
        stack += root
        var visited = 0
        while (stack.isNotEmpty() && visited++ < 600) {
            val view = stack.removeLast()
            if (view !is ViewGroup || !view.isShown) continue
            if (runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull() == name) return view
            for (index in view.childCount - 1 downTo 0) stack += view.getChildAt(index)
        }
        return null
    }

    private class VeilView(
        context: android.content.Context,
        private val scroll: ViewGroup,
        private val source: View,
        private val previewBackground: ImageView,
    ) : View(context) {
        private val sourceLocation = IntArray(2)
        private val veilLocation = IntArray(2)
        private val backgroundLocation = IntArray(2)

        init {
            setWillNotDraw(false)
            isClickable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        fun geometryKey(): Long {
            if (!source.isAttachedToWindow || !isAttachedToWindow) return Long.MIN_VALUE
            source.getLocationOnScreen(sourceLocation)
            getLocationOnScreen(veilLocation)
            var key = sourceLocation[1].toLong()
            key = key * 31 + sourceLocation[0]
            key = key * 31 + veilLocation[1]
            key = key * 31 + scroll.scrollY
            return key
        }

        fun enableBlur(height: Int): Boolean = runCatching {
            val radii = floatArrayOf(32f, 18f, 8f, 2f, 0f)
            var combined: RenderEffect? = null
            radii.forEachIndexed { index, radius ->
                val mask = RuntimeShader(LEVEL_MASK)
                mask.setFloatUniform("veilHeight", height.toFloat())
                mask.setFloatUniform("level", index.toFloat())
                val masking = RenderEffect.createRuntimeShaderEffect(mask, "content")
                val effect = if (radius == 0f) masking else {
                    val pixels = radius * resources.displayMetrics.density
                    RenderEffect.createChainEffect(
                        masking,
                        RenderEffect.createBlurEffect(pixels, pixels, Shader.TileMode.MIRROR),
                    )
                }
                combined = combined?.let { RenderEffect.createBlendModeEffect(it, effect, BlendMode.PLUS) }
                    ?: effect
            }
            // The sampled pixels remain opaque so the page background cannot
            // appear as a separate strip.
            setRenderEffect(combined ?: return@runCatching false)
            true
        }.getOrDefault(false)

        override fun onDraw(canvas: Canvas) {
            if (!source.isAttachedToWindow) return
            source.getLocationOnScreen(sourceLocation)
            getLocationOnScreen(veilLocation)
            val sourceTop = (sourceLocation[1] - veilLocation[1]).toFloat()
            // The SpringBackLayout scrolls independently of NestedScrollView.
            // Use one coordinate system for both the transition and drawing.
            val reveal = height - sourceTop
            val progress = (reveal / height.coerceAtLeast(1)).coerceIn(0f, 1f)
            val horizontalOffset = (sourceLocation[0] - veilLocation[0]).toFloat()
            if (progress < 1f && previewBackground.isAttachedToWindow) {
                // The native 400dp bg_data_headerview is separate from the
                // ThreeTargetView ring. Stretch only its leading gradient up
                // to the fixed title. Its lower row matches the background
                // drawn by Xiaomi at the scroll boundary.
                previewBackground.getLocationOnScreen(backgroundLocation)
                val boundaryScreenY = veilLocation[1] + height
                val visiblePrefix = (boundaryScreenY - backgroundLocation[1])
                    .coerceIn(1, previewBackground.height.coerceAtLeast(1))
                val verticalScale = height.toFloat() / visiblePrefix
                val saved = canvas.save()
                canvas.translate(
                    (backgroundLocation[0] - veilLocation[0]).toFloat(), 0f,
                )
                canvas.scale(1f, verticalScale)
                previewBackground.draw(canvas)
                canvas.restoreToCount(saved)
            }
            if (reveal > 0f) {
                // Newly revealed pixels must be fully opaque at the native
                // scroll boundary. Fade only their leading edge into the
                // decorative preview, otherwise the ring is visibly dimmer
                // above the fixed header's bottom edge.
                val visibleHeight = (height - sourceTop).coerceAtLeast(0f)
                val feather = minOf(
                    24f * resources.displayMetrics.density,
                    (visibleHeight * 0.5f).coerceAtLeast(1f),
                )
                val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
                // fragment_data_v2 gives the NestedScrollView a different
                // background from the fragment root in light mode. The
                // content LinearLayout is transparent between cards, so its
                // background must travel with it into the title preview.
                scroll.background?.let { background ->
                    val originalBounds = Rect(background.bounds)
                    background.setBounds(0, 0, width, height)
                    background.draw(canvas)
                    background.setBounds(originalBounds)
                }
                val contentSave = canvas.save()
                canvas.translate(
                    horizontalOffset,
                    sourceTop,
                )
                source.draw(canvas)
                canvas.restoreToCount(contentSave)
                val mask = Paint().apply {
                    shader = LinearGradient(
                        0f, sourceTop, 0f, sourceTop + feather,
                        0x00FFFFFF, 0xFFFFFFFF.toInt(), Shader.TileMode.CLAMP,
                    )
                    blendMode = BlendMode.DST_IN
                }
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), mask)
                canvas.restoreToCount(layer)
            }
        }
    }

    private companion object {
        const val LEVEL_MASK = """
            uniform shader content;
            uniform float veilHeight;
            uniform float level;
            half4 main(float2 coord) {
                float t = clamp(coord.y / max(veilHeight, 1.0), 0.0, 1.0);
                float position = smoothstep(0.0, 1.0, t) * 4.0;
                float weight = max(1.0 - abs(position - level), 0.0);
                return content.eval(coord) * half(weight);
            }
        """
    }
}
