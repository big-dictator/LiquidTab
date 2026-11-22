package io.github.offlineglass.hook.adapters.home_glass


import android.content.Context
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.View
import kotlin.math.abs
import kotlin.math.ceil

/** Home-only equivalent of ManagerGradientTopBar's Gaussian pyramid. */
internal open class HomeTopGradientVeil(context: Context, private val scene: View) : View(context) {
    // The stable native viewport owns the sampling edge, not recycled rows.
    // Our injected overlays are siblings outside this native branch.
    private val viewport = run {
        var view = scene
        while (true) {
            val parent = view.parent as? View ?: break
            if (parent.id == android.R.id.content) break
            view = parent
        }
        view
    }
    private val band = RenderNode("HomeStatusBlur")
    private val solidGradient = HomeEdgeColorGradient(top = true)
    private var solidMode = true
    private var samplingSource: View = viewport
    private val nodeField = runCatching {
        View::class.java.getDeclaredField("mRenderNode").apply { isAccessible = true }
    }.getOrNull()
    private val sourceLocation = IntArray(2)
    private val veilLocation = IntArray(2)
    private val backgroundValue = TypedValue()
    private var effectWidth = 0
    private var effectHeight = 0
    private var effectDensity = 0f
    private var sampleHeight = 0
    private var sampleTop = 0
    private var recordedSource: RenderNode? = null
    private var recordedX = Int.MIN_VALUE
    private var recordedY = Int.MIN_VALUE
    private var recordedBackground = 0
    private var unclippedSource: RenderNode? = null
    private var originalSourceClip = true

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        band.setClipToBounds(true)
        background = solidGradient
    }

    fun setAppearance(useSolid: Boolean, dark: Boolean) {
        solidGradient.setDark(dark)
        if (solidMode == useSolid) return
        if (useSolid) release()
        solidMode = useSolid
        background = if (useSolid) solidGradient else null
        invalidate()
    }

    fun bindFeed(feed: View?) {
        if (feed != null) samplingSource = feed
    }

    fun updateFrame() {
        if (solidMode) return
        if (width <= 0 || height <= 1 || !scene.isShown) return
        val source = runCatching { nodeField?.get(samplingSource) as? RenderNode }.getOrNull() ?: return
        // Retain the existing scene reference on a transient probe miss.
        if (!source.hasDisplayList()) return
        if (samplingSource !== viewport) {
            if (unclippedSource !== source) {
                restoreSourceClipping()
                originalSourceClip = source.clipToBounds
                unclippedSource = source
            }
            // The parent's clipChildren flag otherwise clips this RenderNode
            // before our padded sampler can see the retained offscreen rows.
            if (source.clipToBounds) source.setClipToBounds(false)
        }
        val density = resources.displayMetrics.density
        val resized = effectWidth != width || effectHeight != height || effectDensity != density
        val parent = samplingSource.parent as? View ?: return
        parent.getLocationInWindow(sourceLocation)
        getLocationInWindow(veilLocation)
        val x = sourceLocation[0] - parent.scrollX - veilLocation[0]
        val y = sourceLocation[1] - parent.scrollY - veilLocation[1]
        val background = nativeBackground()
        if (!resized && recordedSource === source && abs(x - recordedX) < 3 &&
            abs(y - recordedY) < 3 && background == recordedBackground && band.hasDisplayList()) return
        if (resized) {
            sampleTop = ceil(96f * density * SAMPLE_SCALE).toInt()
            val effect = runCatching { effect(height * SAMPLE_SCALE, density * SAMPLE_SCALE, sampleTop.toFloat()) }.getOrNull() ?: return
            // Three maximum Gaussian radii on BOTH sides. The top kernel
            // sees retained native rows above screen zero before they recycle.
            sampleHeight = ceil(height * SAMPLE_SCALE).toInt() + sampleTop * 2
            band.setPosition(0, 0, ceil(width * SAMPLE_SCALE).toInt(), sampleHeight)
            band.setRenderEffect(effect)
            effectWidth = width; effectHeight = height; effectDensity = density
        }
        val recording = band.beginRecording(ceil(width * SAMPLE_SCALE).toInt(), sampleHeight)
        recording.drawColor(background)
        recording.translate(0f, sampleTop.toFloat())
        recording.scale(SAMPLE_SCALE, SAMPLE_SCALE)
        // A View RenderNode already contains its own parent-space position.
        // Replay the native display list; do not traverse or snapshot the feed.
        recording.translate(
            x.toFloat(), y.toFloat(),
        )
        recording.drawRenderNode(source)
        band.endRecording()
        recordedSource = source; recordedX = x; recordedY = y; recordedBackground = background
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (solidMode) return
        if (!canvas.isHardwareAccelerated) return
        updateFrame()
        if (!band.hasDisplayList()) return
        val saved = canvas.save()
        canvas.clipRect(0, 0, width, height)
        canvas.scale(1f / SAMPLE_SCALE, 1f / SAMPLE_SCALE)
        canvas.translate(0f, -sampleTop.toFloat())
        canvas.drawRenderNode(band)
        canvas.restoreToCount(saved)
    }

    fun release() {
        restoreSourceClipping()
        band.discardDisplayList()
        band.setRenderEffect(null)
        effectWidth = 0; effectHeight = 0
        recordedSource = null
    }

    fun restoreSourceClipping() {
        unclippedSource?.setClipToBounds(originalSourceClip)
        unclippedSource = null
    }

    private fun nativeBackground(): Int {
        (viewport.rootView.background as? ColorDrawable)?.color?.takeIf { Color.alpha(it) == 255 }?.let { return it }
        val value = backgroundValue
        if (context.theme.resolveAttribute(android.R.attr.colorBackground, value, true) &&
            value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) return value.data or Color.BLACK
        return Color.BLACK
    }

    private fun effect(height: Float, density: Float, top: Float): RenderEffect {
        // Same Gaussian weights as the module. Leave the zero-radius weight
        // transparent: the original full-resolution page already supplies it
        // under this overlay, preserving a sharp and seamless clear boundary.
        var combined: RenderEffect? = null
        floatArrayOf(32f, 18f, 8f, 2f).forEachIndexed { index, radius ->
            val mask = RuntimeShader(LEVEL_MASK)
            mask.setFloatUniform("veilHeight", height)
            mask.setFloatUniform("sampleTop", top)
            mask.setFloatUniform("level", index.toFloat())
            val masking = RenderEffect.createRuntimeShaderEffect(mask, "content")
            val level = if (radius == 0f) masking else RenderEffect.createChainEffect(
                masking, RenderEffect.createBlurEffect(radius * density, radius * density, Shader.TileMode.MIRROR),
            )
            combined = combined?.let { RenderEffect.createBlendModeEffect(it, level, BlendMode.PLUS) } ?: level
        }
        return checkNotNull(combined)
    }

    private companion object {
        const val SAMPLE_SCALE = .25f
        const val LEVEL_MASK = """
uniform shader content;
uniform float veilHeight;
uniform float sampleTop;
uniform float level;
half4 main(float2 coord) {
    float t = clamp((coord.y - sampleTop) / max(veilHeight, 1.0), 0.0, 1.0);
    float position = smoothstep(0.0, 1.0, t) * 4.0;
    float weight = max(1.0 - abs(position - level), 0.0);
    return content.eval(coord) * half(weight);
}
"""
    }
}
