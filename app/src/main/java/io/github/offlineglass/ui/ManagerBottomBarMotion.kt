package io.github.offlineglass.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import io.github.offlineglass.hook.SpringFloat
import io.github.offlineglass.rendering.BottomBarMotionSprings
import io.github.offlineglass.rendering.BottomBarIndicatorStyle
import kotlin.math.abs

/** Compose bridge to the SAME SpringFloat engine and phases used by
 * GlassHostLayout (including Bilibili). No coroutine spring is restarted on MOVE.
 * Finger translation stays direct; the native position spring drives shape velocity.
 */
@Stable
internal class ManagerBottomBarMotion(initial: Float, private val slots: Int, private val expands: Boolean = true) {
    var position by mutableFloatStateOf(initial); private set
    var pressProgress by mutableFloatStateOf(0f); private set
    var sliderOnlyPress by androidx.compose.runtime.mutableStateOf(false); private set
    var scaleX by mutableFloatStateOf(1f); private set
    var scaleY by mutableFloatStateOf(1f); private set
    var velocity by mutableFloatStateOf(0f); private set
    var panelDragOffset by mutableFloatStateOf(0f); private set
    var restingTintProgress by mutableFloatStateOf(1f); private set
    private var tintAnimator: android.animation.ValueAnimator? = null
    private var target = initial
    private var held = false
    private var dragging = false
    private var releasePending = false
    private var disposed = false
    private val springs = BottomBarMotionSprings(initial, { slots }, { expands && held && dragging },
        onPosition = { value, _ -> position = value; releaseIfSettled() },
        onVelocity = { velocity = it }, onPress = { pressProgress = it },
        onScaleX = { scaleX = it }, onScaleY = { scaleY = it },
        onPanelOffset = { panelDragOffset = it })
    private val positionSpring get() = springs.position
    private val velocitySpring get() = springs.velocity

    fun press(enabled: Boolean, sliderOnly: Boolean = false) {
        if (disposed) return
        sliderOnlyPress = sliderOnly
        held = true; dragging = false; releasePending = false
        if (!expands) return
        tintAnimator?.cancel(); tintAnimator = null; restingTintProgress = 0f
        if (!enabled) {
            springs.resetPress()
        } else {
            springs.press(1f + (BottomBarIndicatorStyle.PRESS_SCALE - 1f) *
                BottomBarIndicatorStyle.PRESS_GROWTH_GAIN, slots)
        }
    }
    fun dragTo(value: Float, deltaPixels: Float) {
        if (disposed) return
        dragging = true; target = value
        positionSpring.animateTo(value)
        if (expands) springs.panelOffset.snapTo(panelDragOffset + deltaPixels)
    }
    fun releaseTo(value: Float, fingerPosition: Float? = null) {
        if (disposed) return
        held = false; dragging = false; target = value; releasePending = expands
        // Same release-at-UP return as GlassHostLayout.startRestingTintReturn.
        if (restingTintProgress < 1f && tintAnimator == null) {
            tintAnimator = android.animation.ValueAnimator.ofFloat(restingTintProgress, 1f).apply {
                duration = BottomBarIndicatorStyle.RESTING_TINT_RETURN_MS
                interpolator = android.view.animation.DecelerateInterpolator()
                addUpdateListener { restingTintProgress = it.animatedValue as Float }
                start()
            }
        }
        // The release must start at the last displayed finger position, not the
        // lagged shape-velocity probe's position.
        fingerPosition?.let(positionSpring::snapTo)
        positionSpring.animateTo(value)
        if (expands) {
            velocitySpring.animateTo(0f)
            springs.panelOffset.animateTo(0f)
        }
        releaseIfSettled()
    }
    fun followSelection(value: Float) {
        if (disposed || held) return
        target = value
        positionSpring.animateTo(value)
    }
    fun selectByTap(value: Float, rich: Boolean) {
        if (rich && !disposed && !held && abs(position - value) > 0.001f) {
            press(true, sliderOnly = true)
            releaseTo(value)
        } else followSelection(value)
    }
    private fun releaseIfSettled() {
        if (held || !releasePending || abs(position - target) > slots.coerceAtLeast(1) * 0.025f) return
        releasePending = false
        springs.release()
    }
    fun dispose() {
        disposed = true
        tintAnimator?.cancel(); tintAnimator = null
        springs.cancel()
    }
}
