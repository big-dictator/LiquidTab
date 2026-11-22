package io.github.offlineglass.hook.adapters.xhs


import android.graphics.*
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import kotlin.math.abs
import kotlin.math.sqrt

/** XHS-only observer; never consumes events or replaces native click listeners.
 * Response follows Kyant0 InteractiveHighlight (damping .5, stiffness 300).
 */
internal class XhsPressFeedback(
    private val row: ViewGroup,
    private val targets: List<View>,
    private val groupTrailingActions: Boolean = true,
    private val changed: () -> Unit,
) : Choreographer.FrameCallback {
    private val values = FloatArray(targets.size)
    private val velocities = FloatArray(targets.size)
    private var active = -1
    private var queued = false
    private var lastFrame = 0L
    private var downTime = 0L
    private var releaseAt = 0L
    var x = 0f; private set
    var y = 0f; private set
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shader = RuntimeShader("""
        layout(color) uniform half4 color;
        uniform float radius;
        uniform float2 position;
        half4 main(float2 coord) {
            float dist = distance(coord, position);
            return color * (1.0 - smoothstep(radius * 0.5, radius, dist));
        }
    """)
    fun progress(index: Int) = values.getOrElse(index) { 0f }.coerceIn(0f, 1.2f)
    fun scale(index: Int): Float = 1f + 4f * row.resources.displayMetrics.density /
        (48f * row.resources.displayMetrics.density).coerceAtLeast(1f) *
        values.getOrElse(index) { 0f }.coerceIn(-.2f, 1.2f)

    // The shared glass plate and the three actions use the same spring,
    // including the release undershoot.
    fun actionScale(): Float {
        val index = (1 until values.size).maxByOrNull { abs(values[it]) } ?: return 1f
        return scale(index)
    }
    fun observe(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downTime = android.os.SystemClock.uptimeMillis()
                releaseAt = 0L
                active = targets.indexOfFirst {
                    it.isShown && event.x >= it.left && event.x < it.right &&
                        event.y >= it.top && event.y < it.bottom
                }
                x = event.x; y = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                x = event.x; y = event.y
                targets.getOrNull(active)?.let {
                    if (x < it.left || x >= it.right || y < it.top || y >= it.bottom) active = -1
                }
            }
            MotionEvent.ACTION_UP -> {
                // A tap shorter than one display frame still gets visible feedback.
                releaseAt = maxOf(android.os.SystemClock.uptimeMillis(), downTime + 60L)
            }
            MotionEvent.ACTION_CANCEL -> { active = -1; releaseAt = 0L }
            else -> return
        }
        schedule()
    }
    private fun schedule() {
        if (!queued) {
            queued = true
            Choreographer.getInstance().postFrameCallback(this)
        }
    }
    override fun doFrame(frameTimeNanos: Long) {
        queued = false
        if (releaseAt != 0L && android.os.SystemClock.uptimeMillis() >= releaseAt) {
            active = -1; releaseAt = 0L
        }
        val dt = if (lastFrame == 0L) 1f / 60f else
            ((frameTimeNanos - lastFrame) / 1e9f).coerceIn(0f, 0.032f)
        lastFrame = frameTimeNanos
        var moving = false
        // Small integration steps keep the same spring stable at varying refresh rates.
        for (i in values.indices) {
            val target = if (i == active) 1f else 0f
            repeat(4) {
                velocities[i] += (300f * (target - values[i]) - sqrt(300f) * velocities[i]) * dt / 4f
                values[i] += velocities[i] * dt / 4f
            }
            if (abs(target - values[i]) < .001f && abs(velocities[i]) < .01f) {
                values[i] = target; velocities[i] = 0f
            } else moving = true
        }
        changed()
        if (moving || releaseAt != 0L) schedule() else lastFrame = 0L
    }
    /** Called within the capsule clip, with coordinates in the toolbar's space. */
    fun drawLight(canvas: Canvas, rect: RectF, index: Int) {
        val progress = if (index == 0 || !groupTrailingActions) progress(index) else
            (1 until values.size).maxOfOrNull { progress(it) } ?: 0f
        if (progress <= 0f) return
        paint.blendMode = BlendMode.PLUS
        paint.shader = null
        paint.color = Color.argb((255 * .08f * progress).toInt(), 255, 255, 255)
        canvas.drawRect(rect, paint)
        shader.setColorUniform("color", Color.argb((255 * .15f * progress).toInt(), 255, 255, 255))
        shader.setFloatUniform("radius", minOf(rect.width(), rect.height()) * 1.5f)
        shader.setFloatUniform("position", x.coerceIn(rect.left, rect.right), y.coerceIn(rect.top, rect.bottom))
        paint.shader = shader
        canvas.drawRect(rect, paint)
        paint.shader = null
        paint.blendMode = BlendMode.SRC_OVER
    }
    fun reset() {
        Choreographer.getInstance().removeFrameCallback(this)
        queued = false; lastFrame = 0L; active = -1; releaseAt = 0L
        values.fill(0f); velocities.fill(0f)
        changed()
    }
}
