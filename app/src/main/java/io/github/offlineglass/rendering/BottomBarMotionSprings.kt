package io.github.offlineglass.rendering

import io.github.offlineglass.hook.SpringFloat

/** Canonical injected-bar spring bank, shared with the manager. Adapter/page
 * routing stays outside; retargeting never restarts an in-flight spring. */
internal class BottomBarMotionSprings(
    initialPosition: Float,
    private val slotCount: () -> Int,
    private val tracking: () -> Boolean,
    onPosition: (Float, Float) -> Unit,
    onVelocity: (Float) -> Unit,
    onPress: (Float) -> Unit,
    onScaleX: (Float) -> Unit,
    onScaleY: (Float) -> Unit,
    onPanelOffset: (Float) -> Unit = {},
) {
    companion object {
        private val followEase = android.view.animation.PathInterpolator(0f, 0f, 0.58f, 1f)
        fun panelTranslation(offset: Float, width: Float, density: Float): Float {
            if (width <= 0f) return 0f
            val fraction = (offset / width).coerceIn(-1f, 1f)
            val eased = followEase.getInterpolation(kotlin.math.abs(fraction))
            return 1.5f * density * if (fraction < 0f) -eased else eased
        }
    }
    val velocity = SpringFloat(0f, 300f, 0.5f, 0.01f) { value, _ -> onVelocity(value) }
    val position = SpringFloat(initialPosition, 1_000f, 1f, 0.001f) { value, speed ->
        onPosition(value, speed)
        if (tracking()) velocity.animateTo(speed / (slotCount() - 1).coerceAtLeast(1))
    }
    val press = SpringFloat(0f, 1_000f, 1f, 0.001f) { value, _ -> onPress(value.coerceIn(0f, 1f)) }
    val scaleX = SpringFloat(1f, 250f, 0.6f, 0.001f) { value, _ -> onScaleX(value) }
    val scaleY = SpringFloat(1f, 250f, 0.7f, 0.001f) { value, _ -> onScaleY(value) }
    val panelOffset = SpringFloat(0f, 300f, 0.5f, 0.01f) { value, _ -> onPanelOffset(value) }

    fun press(pressedScale: Float, slots: Int) {
        scaleX.resumeOscillation()
        scaleY.resumeOscillation()
        velocity.resumeOscillation()
        press.animateTo(1f)
        scaleX.animateTo(BottomBarIndicatorStyle.pressedScaleX(slots, pressedScale))
        scaleY.animateTo(pressedScale)
    }
    fun resetPress() { press.snapTo(0f); scaleX.snapTo(1f); scaleY.snapTo(1f) }
    fun release() {
        press.animateTo(0f)
        scaleX.returnTo(1f)
        scaleY.returnTo(1f)
        velocity.returnTo(0f)
    }
    fun cancel() { listOf(position, velocity, press, scaleX, scaleY, panelOffset).forEach(SpringFloat::cancel) }
}
