package io.github.offlineglass.hook.adapters.bilibili

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import java.lang.ref.WeakReference

/** Shared Bilibili search feedback; the native search action keeps touch ownership. */
internal class BilibiliSearchFeedback(private val wholeView: Boolean = false) {
    var progress = 0f; private set
    private var held = false
    private var host = WeakReference<View>(null)
    private var animator: ValueAnimator? = null
    private val bounds = RectF()
    private val drawnBounds = RectF()
    private val lightMatrix = Matrix()
    private val light = RadialGradient(0f, 0f, 1f, intArrayOf(0x66FFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = light }
    private var lightX = .5f
    private var lightY = .5f

    fun touch(view: View, event: MotionEvent, enabled: Boolean = true) {
        if (!enabled) { reset(); return }
        bounds.set(if (wholeView) 0f else (view.width - view.height).toFloat(), 0f,
            view.width.toFloat(), view.height.toFloat())
        val inside = bounds.contains(event.x, event.y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!inside) return
                host = WeakReference(view)
                lightX = ((event.x - bounds.left) / bounds.width()).coerceIn(0f, 1f)
                lightY = (event.y / bounds.height()).coerceIn(0f, 1f)
                held = true
                animate(true)
            }
            MotionEvent.ACTION_MOVE -> if (held && !inside) { held = false; animate(false) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (held) { held = false; animate(false) }
        }
    }

    private fun animate(pressed: Boolean) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(progress, if (pressed) 1f else 0f).apply {
            duration = if (pressed) 100L else 260L
            interpolator = if (pressed) DecelerateInterpolator() else OvershootInterpolator(1.1f)
            addUpdateListener {
                progress = it.animatedValue as Float
                host.get()?.let { view ->
                    if (wholeView) {
                        view.scaleX = 1f + .035f * progress
                        view.scaleY = view.scaleX
                    }
                    view.invalidate()
                }
            }
            start()
        }
    }

    fun drawLight(canvas: Canvas, edge: Path) {
        if (progress <= 0f || bounds.isEmpty) return
        edge.computeBounds(drawnBounds, true)
        // The compound host calls this for both panels. Only illuminate the search panel.
        if (kotlin.math.abs(drawnBounds.left - bounds.left) > 1f ||
            kotlin.math.abs(drawnBounds.right - bounds.right) > 1f) return
        val radius = maxOf(bounds.width() * .65f, bounds.height())
        lightMatrix.setScale(radius, radius)
        lightMatrix.postTranslate(bounds.left + lightX * bounds.width(), lightY * bounds.height())
        light.setLocalMatrix(lightMatrix)
        paint.alpha = (progress.coerceIn(0f, 1f) * 255f).toInt()
        val saved = canvas.save()
        canvas.clipPath(edge)
        canvas.drawRect(bounds, paint)
        canvas.restoreToCount(saved)
    }

    fun reset() {
        if (!held && progress == 0f && animator?.isRunning != true) return
        animator?.cancel()
        animator = null
        held = false
        progress = 0f
        host.get()?.let {
            if (wholeView) { it.scaleX = 1f; it.scaleY = 1f }
            it.invalidate()
        }
        host.clear()
    }
}
