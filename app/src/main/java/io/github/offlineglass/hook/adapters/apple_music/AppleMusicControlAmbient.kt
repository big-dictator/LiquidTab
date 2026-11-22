package io.github.offlineglass.hook.adapters.apple_music

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import io.github.offlineglass.hook.GlassHostLayout
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

private fun ambientAlpha(dark: Boolean, opacity: Float) =
    ((if (dark) 12 else 28) * opacity.coerceIn(0f, 1f)).roundToInt()

/** Paint just outside the actual surface in a padded overlay, beyond the control's clip. */
internal class AppleMusicControlAmbient(
    private val owner: View,
    private val target: View = owner,
) : Drawable() {
    private val density = owner.resources.displayMetrics.density
    private val position = IntArray(2)
    private val outline = Path()
    private val extent = RectF()
    private val paint = AppleMusicAmbientPaint(density)
    private var shapeGeneration = -1
    private var faceWidth = 0f
    private var faceHeight = 0f
    private var dark: Boolean? = null
    private var x = 0f
    private var y = 0f
    private var scaleX = 1f
    private var scaleY = 1f
    private var strength = 0

    fun attach() { owner.overlay.add(this) }
    fun close() { owner.overlay.remove(this) }

    fun refresh(face: RectF?, dark: Boolean, scale: Float = 1f, shape: Path? = null) {
        if (face == null || face.isEmpty) {
            setVisible(false, false)
            return
        }
        owner.getLocationOnScreen(position)
        locate(face.centerX() - position[0] - face.width() * scale / 2f,
            face.centerY() - position[1] - face.height() * scale / 2f,
            face.width(), face.height(), dark, scale, scale, shape, 1f)
    }

    fun refreshPanel(panel: GlassHostLayout, dark: Boolean, alpha: Float) {
        if (panel.clipPath.isEmpty) return
        val sx = panel.scaleX
        val sy = panel.scaleY
        val parent = panel.parent as? View
        if (parent != null && (parent === owner || parent.parent === owner)) {
            // The panel can move by RenderNode translation without re-recording its draw.
            val parentX = if (parent === owner) 0f else parent.x - parent.scrollX
            val parentY = if (parent === owner) 0f else parent.y - parent.scrollY
            locate(parentX + panel.x + panel.pivotX * (1f - sx), parentY + panel.y + panel.pivotY * (1f - sy),
                panel.width.toFloat(), panel.height.toFloat(), dark, sx, sy, panel.clipPath, alpha * panel.alpha)
        } else {
            panel.getLocationOnScreen(position)
            val screenX = position[0]
            val screenY = position[1]
            owner.getLocationOnScreen(position)
            locate((screenX - position[0]).toFloat(), (screenY - position[1]).toFloat(),
                panel.width.toFloat(), panel.height.toFloat(), dark, sx, sy, panel.clipPath, alpha * panel.alpha)
        }
    }

    private fun locate(left: Float, top: Float, width: Float, height: Float, dark: Boolean,
        sx: Float, sy: Float, shape: Path?, opacity: Float) {
        val level = ambientAlpha(dark, opacity)
        val generation = shape?.generationId ?: -1
        val shapeChanged = generation != shapeGeneration || width != faceWidth || height != faceHeight
        if (isVisible && !shapeChanged && x == left && y == top && scaleX == sx && scaleY == sy &&
            this.dark == dark && strength == level) return
        invalidateSelf()
        if (shapeChanged) {
            outline.rewind()
            if (shape == null) outline.addOval(0f, 0f, width, height, Path.Direction.CW) else outline.set(shape)
            outline.computeBounds(extent, true)
            shapeGeneration = generation
            faceWidth = width
            faceHeight = height
        }
        if (this.dark != dark || strength != level) {
            paint.update(dark, level)
            this.dark = dark
            strength = level
        }
        x = left
        y = top
        scaleX = sx
        scaleY = sy
        val pad = 22f * density
        setBounds(floor(x + extent.left * sx - pad).toInt(), floor(y + extent.top * sy - pad).toInt(),
            ceil(x + extent.right * sx + pad).toInt(), ceil(y + extent.bottom * sy + pad).toInt())
        setVisible(true, false)
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        if (!isVisible || !target.isShown || target.alpha <= 0f || strength == 0 || outline.isEmpty) return
        val checkpoint = canvas.save()
        try {
            canvas.translate(x, y)
            canvas.scale(scaleX, scaleY)
            paint.draw(canvas, outline)
        } finally { canvas.restoreToCount(checkpoint) }
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(filter: ColorFilter?) = Unit
    @Deprecated("Android Drawable API")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

internal class AppleMusicAmbientPaint(private val density: Float) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.TRANSPARENT }
    private var previousDark: Boolean? = null
    private var previousLevel = -1

    fun update(dark: Boolean, level: Int) {
        if (previousDark == dark && previousLevel == level) return
        previousDark = dark
        previousLevel = level
        val color = if (dark) Color.argb(level, 235, 243, 255) else Color.argb(level, 0, 0, 0)
        paint.setShadowLayer((if (dark) 6f else 6.5f) * density, 0f,
            if (dark) 0f else .45f * density, color)
    }

    fun draw(canvas: Canvas, path: Path, dark: Boolean, opacity: Float) {
        update(dark, ambientAlpha(dark, opacity))
        draw(canvas, path)
    }

    fun draw(canvas: Canvas, path: Path) {
        if (previousLevel <= 0 || path.isEmpty) return
        val save = canvas.save()
        try {
            canvas.clipOutPath(path)
            canvas.drawPath(path, paint)
        } finally { canvas.restoreToCount(save) }
    }
}

/** A panel owns its ambient drawable for as long as it is attached. */
internal class AppleMusicPanelAmbient(private val overlayOwner: View? = null) {
    private var panel: GlassHostLayout? = null
    private var drawable: AppleMusicControlAmbient? = null

    fun bind(panel: GlassHostLayout) {
        this.panel = panel
        panel.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) {
                drawable?.close()
                drawable = null
            }
        })
    }

    fun refresh(dark: Boolean, alpha: Float) {
        val view = panel?.takeIf { it.isAttachedToWindow && it.isShown } ?: return
        val effect = drawable ?: AppleMusicControlAmbient(overlayOwner ?: view.rootView, view).also {
            it.attach()
            drawable = it
        }
        effect.refreshPanel(view, dark, alpha)
    }
}
