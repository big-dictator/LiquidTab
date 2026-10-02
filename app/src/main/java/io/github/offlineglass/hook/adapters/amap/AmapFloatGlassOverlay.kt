package io.github.offlineglass.hook.adapters.amap


import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.view.View
import android.view.ViewGroup
import kotlin.math.roundToInt

/**
 * Amap POI-detail floating action bar → liquid glass. One overlay View fields
 * the whole bar rectangle but only draws three liquid glass "buttons":
 *
 *  - a wide rounded-rect glass block covering the left four cells
 *    (搜周边 / 收藏 / 分享 / 打车);
 *  - a capsule glass button for 导航;
 *  - a capsule glass button for 路线.
 *
 * The native icon/text cells are NOT reordered or re-parented; this overlay is
 * inserted as a *lower* sibling behind the bar, so the native chrome (icons,
 * labels, badges, click targets) keeps rendering on top of the glass. It never
 * intercepts touch (isClickable=false, focusable=false).
 *
 * The glass blurs the *live* background content
 * by referencing the content branch's RenderNode (no software snapshot), so it
 * re-samples whatever is behind the bar on every draw. The source must never
 * be (or contain) this overlay or the bar itself, otherwise the blur recurses.
 *
 * Zone rectangles are in this overlay's local coordinates and come from the
 * injector, which derives them from the scanned cell geometry at install time.
 */
internal class AmapFloatGlassOverlay(
    private val source: View,
    zones: Array<AmapGlassZone>,
) : View(source.context) {

    private val glass = RenderNode("LiquidTab-Amap-FloatGlass")
    private val cutout = Path()
    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f
        isAntiAlias = true
    }
    private val sourceNode = runCatching {
        View::class.java.getDeclaredField("mRenderNode").apply { isAccessible = true }
    }.getOrNull()

    private var zones: Array<AmapGlassZone> = zones
    private var effectWidth = 0
    private var effectHeight = 0
    private var blurRadiusPx = 0f
    private var configuredBlurRadius = 2f
    private var solidBarEnabled = false
    private var lastConfigRead = Long.MIN_VALUE
    private var recordedSource: RenderNode? = null
    private var recordedWidth = 0
    private var recordedHeight = 0
    private var recordedX = Float.NaN
    private var recordedY = Float.NaN

    private val sourceLocation = IntArray(2)
    private val overlayLocation = IntArray(2)

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = INVISIBLE
    }

    fun setZones(zones: Array<AmapGlassZone>) {
        this.zones = zones
        effectWidth = 0
        effectHeight = 0
        invalidate()
    }

    fun update(active: Boolean) {
        if (!active || !source.isAttachedToWindow || source.visibility != VISIBLE ||
            source.width <= 0 || source.height <= 0
        ) {
            if (visibility != INVISIBLE) visibility = INVISIBLE
            return
        }
        // The sampled branch must never contain this overlay (recursive backdrop).
        var ancestor: View? = parent as? View ?: return
        while (ancestor != null) {
            if (ancestor === source) return
            ancestor = ancestor.parent as? View
        }
        if (visibility != VISIBLE) visibility = VISIBLE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (!canvas.isHardwareAccelerated || width <= 0 || height <= 0) return
        if (zones.isEmpty()) return
        val field = sourceNode ?: return
        val srcNode = runCatching { field.get(source) as? RenderNode }.getOrNull() ?: return
        if (!srcNode.hasDisplayList()) return

        val density = resources.displayMetrics.density
        val now = android.os.SystemClock.uptimeMillis()
        if (lastConfigRead == Long.MIN_VALUE || now - lastConfigRead >= 2_000L) {
            val config = io.github.offlineglass.hook.HookConfigReader.read(
                context, targetSpec.packageName,
            )
            configuredBlurRadius = config.blurRadius
            solidBarEnabled = config.solidBarEnabled
            lastConfigRead = now
        }
        if (solidBarEnabled) {
            if (blurRadiusPx != 0f) {
                glass.setRenderEffect(null)
                blurRadiusPx = 0f
                effectWidth = 0
                effectHeight = 0
            }
            return
        }
        val baseBlur = configuredBlurRadius * density
        val padding = io.github.offlineglass.rendering.NativeBackdropBlur.padding(baseBlur, 24f * density).roundToInt()

        if (effectWidth != width || effectHeight != height || blurRadiusPx != baseBlur) {
            glass.setRenderEffect(io.github.offlineglass.rendering.NativeBackdropBlur.effect(baseBlur))
            effectWidth = width
            effectHeight = height
            blurRadiusPx = baseBlur
        }

        source.getLocationInWindow(sourceLocation)
        getLocationInWindow(overlayLocation)
        val sceneWidth = width + padding * 2
        val sceneHeight = height + padding * 2
        val sceneX = sourceLocation[0] - overlayLocation[0] - source.left - source.translationX + padding
        val sceneY = sourceLocation[1] - overlayLocation[1] - source.top - source.translationY + padding
        // Referenced child nodes update with the native scene; rebuild this
        // wrapper only for new geometry or a replaced source.
        if (!glass.hasDisplayList() || recordedSource !== srcNode || recordedWidth != sceneWidth ||
            recordedHeight != sceneHeight || recordedX != sceneX || recordedY != sceneY) {
            val recording = glass.beginRecording(sceneWidth, sceneHeight)
            recording.clipRect(0, 0, sceneWidth, sceneHeight)
            recording.translate(sceneX, sceneY)
            recording.drawRenderNode(srcNode)
            glass.endRecording()
            recordedSource = srcNode
            recordedWidth = sceneWidth
            recordedHeight = sceneHeight
            recordedX = sceneX
            recordedY = sceneY
        }
        glass.setPosition(-padding, -padding, width + padding, height + padding)

        val accent = if (isBarDark()) 0xFFFFFFFF.toInt() else 0xFF222222.toInt()
        for (zone in zones) {
            if (zone.width() <= 0 || zone.height() <= 0) continue
            val left = (zone.left - zone.pad).toFloat()
            val top = (zone.top - zone.pad).toFloat()
            val right = (zone.right + zone.pad).toFloat()
            val bottom = (zone.bottom + zone.pad).toFloat()
            val radius = zone.radiusPx.toFloat()
            val save = canvas.save()
            canvas.clipRect(left, top, right, bottom)
            cutout.reset()
            cutout.addRoundRect(
                left, top, right, bottom,
                radius, radius, Path.Direction.CW,
            )
            canvas.clipPath(cutout)
            canvas.drawRenderNode(glass)
            // Optional soft white-blue tint to read as "glass" over busy maps.
            glassPaint.color = if (isBarDark())
                0x9936FFFFFF.toInt() else 0x33FFFFFF.toInt()
            canvas.drawRect(left, top, right, bottom, glassPaint)
            // Edge highlight (Kyant0-style capsule bevel).
            borderPaint.color = (accent and 0x28FFFFFF.toInt())
            canvas.drawRoundRect(left, top, right, bottom, radius, radius, borderPaint)
            canvas.restoreToCount(save)
        }
    }

    fun setDark(dark: Boolean) {
        // Called by the injector when the bar's dark/light theme flips.
        this.isDark = dark
        invalidate()
    }

    private var isDark = false
    private fun isBarDark(): Boolean = isDark

    fun dispose() {
        (parent as? ViewGroup)?.removeView(this)
        glass.discardDisplayList()
    }

    /** One liquid-glass button region, in overlay-local coordinates. */
    class AmapGlassZone(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val radiusPx: Int = computeRadius(bottom - top),
        val pad: Int = 0,
    ) {
        fun width() = right - left
        fun height() = bottom - top
        companion object {
            private fun computeRadius(height: Int) = (height / 2f).roundToInt().coerceAtLeast(8)
        }
    }
}
