package io.github.offlineglass.rendering

import android.view.View
import java.util.WeakHashMap
import kotlin.math.*

/** Optical motion only. One root state, existing sensor, no content capture. */
internal object LiquidMotion {
    internal class State {
        val pose = FloatArray(3)
        val targetScratch = FloatArray(3)
        var active = false
        var rippleAmplitude = 0f
        var rippleAge = 0f
        var rippleX = 1f
        var rippleY = 0f
        var reboundX = 0f
        var reboundY = 0f
        private val velocity = FloatArray(2)
        private val input = FloatArray(2)
        private val inputVelocity = FloatArray(2)
        private var lastTime = 0L
        private var lastInputTime = 0L
        private var rippleAt = -1000L
        private var peakSpeed = 0f
        private var travel = 0f

        fun advance(now: Long, x: Float, y: Float, z: Float, viscosity: Float) {
            val v = (if (viscosity.isFinite()) viscosity else 25f).coerceIn(0f, 100f) / 100f
            if (lastTime == 0L || now - lastTime > 500L) {
                pose[0] = x; pose[1] = y; pose[2] = z
                input[0] = x; input[1] = y
                velocity.fill(0f); inputVelocity.fill(0f)
                lastTime = now; lastInputTime = now
                peakSpeed = 0f; travel = 0f; rippleAmplitude = 0f; active = false
                reboundX = 0f; reboundY = 0f
                return
            }
            val elapsed = (now - lastTime).coerceAtLeast(0L)
            if (elapsed == 0L) return
            val dx = x - input[0]; val dy = y - input[1]
            val movement = hypot(dx, dy)
            if (movement > 0.0002f) {
                val inputDt = ((now - lastInputTime).coerceIn(8L, 120L) / 1000f)
                val vx = (dx / inputDt).coerceIn(-4f, 4f)
                val vy = (dy / inputDt).coerceIn(-4f, 4f)
                val speed = hypot(vx, vy)
                val previousSpeed = hypot(inputVelocity[0], inputVelocity[1])
                val reversal = speed > 0.4f && previousSpeed > 0.4f &&
                    vx * inputVelocity[0] + vy * inputVelocity[1] < -0.25f * speed * previousSpeed
                if (reversal && travel > 0.025f) trigger(now, x, y, vx, vy, max(speed, peakSpeed), v)
                peakSpeed = max(peakSpeed, speed)
                travel += movement
                inputVelocity[0] = vx; inputVelocity[1] = vy
                input[0] = x; input[1] = y; lastInputTime = now
            } else if (now - lastInputTime > 55L && peakSpeed > 0f) {
                if (peakSpeed > 0.4f && travel > 0.025f)
                    trigger(now, x, y, inputVelocity[0], inputVelocity[1], peakSpeed, v)
                peakSpeed = 0f; travel = 0f; inputVelocity.fill(0f)
            }

            // Stable small integration steps at every display refresh rate.
            val frequency = 22f - 18f * v
            val damping = 0.24f + 1.76f * v * v
            var remaining = min(elapsed / 1000f, 0.05f)
            while (remaining > 0f) {
                val dt = min(remaining, 0.004f)
                for (axis in 0..1) {
                    val target = if (axis == 0) x else y
                    val acceleration = frequency * frequency * (target - pose[axis]) -
                        2f * damping * frequency * velocity[axis]
                    velocity[axis] = (velocity[axis] + acceleration * dt).coerceIn(-4f, 4f)
                    pose[axis] = (pose[axis] + velocity[axis] * dt).coerceIn(-0.7f, 0.7f)
                }
                remaining -= dt
            }
            pose[2] = z
            rippleAge = (now - rippleAt) / 1000f
            if (rippleAge > 0.90f || v >= 0.95f) rippleAmplitude = 0f
            val unsettled = abs(x - pose[0]) + abs(y - pose[1]) > 0.0005f ||
                abs(velocity[0]) + abs(velocity[1]) > 0.003f
            if (!unsettled) { pose[0] = x; pose[1] = y; velocity.fill(0f) }
            // Keep recoil visible even after the base thickness gain saturates.
            // It changes optical sampling, never the View or source artwork.
            val lowViscosityBoost = (1f - v / 0.15f).coerceIn(0f, 1f).pow(2)
            val reboundWeight = (1f - v) * (1f - v) * (1f + 0.25f * lowViscosityBoost)
            reboundX = (pose[0] - x) * reboundWeight
            reboundY = (pose[1] - y) * reboundWeight
            active = unsettled || rippleAmplitude > 0f ||
                (peakSpeed > 0.4f && travel > 0.025f && now - lastInputTime <= 55L)
            lastTime = now
        }

        private fun trigger(now: Long, x: Float, y: Float, vx: Float, vy: Float, speed: Float, viscosity: Float) {
            if (now - rippleAt < 300L || viscosity >= 0.95f) return
            val magnitude = hypot(x, y)
            val directionX = if (magnitude > 0.015f) -x else -vx
            val directionY = if (magnitude > 0.015f) y else vy
            val length = hypot(directionX, directionY).coerceAtLeast(0.0001f)
            rippleX = directionX / length; rippleY = directionY / length
            rippleAmplitude = ((speed - 0.25f) / 1.2f).coerceIn(0.18f, 0.85f) *
                (1f - viscosity).pow(2)
            rippleAge = 0f; rippleAt = now
        }
    }

    private val states = WeakHashMap<View, State>()
    private val axisStrengths = WeakHashMap<View, FloatArray>()
    fun configure(view: View, horizontal: Float, vertical: Float) {
        val root = view.rootView
        val x = (if (horizontal.isFinite()) horizontal else 100f).coerceIn(0f, 100f) / 100f
        val y = (if (vertical.isFinite()) vertical else 100f).coerceIn(0f, 100f) / 100f
        val old = axisStrengths[root]
        if (old == null || old[0] != x || old[1] != y) {
            axisStrengths[root] = floatArrayOf(x, y)
            states.remove(root) // no stale recoil on a direction just disabled
        }
    }
    private val disabled = State()
    fun scalePose(view: View, target: FloatArray): FloatArray {
        val axes = axisStrengths[view.rootView]
        if (axes != null) { target[0] *= axes[0]; target[1] *= axes[1] }
        return target
    }
    fun verifyAxes(view: View) {
        for (x in listOf(0f, 50f, 100f)) for (y in listOf(0f, 50f, 100f)) {
            configure(view, x, y)
            val p = scalePose(view, floatArrayOf(0.4f, -0.2f, -1f))
            check(abs(p[0] - 0.4f * x / 100f) < 0.00001f)
            check(abs(p[1] + 0.2f * y / 100f) < 0.00001f)
            check(p[2] == -1f)
        }
        axisStrengths.remove(view.rootView)
    }
    fun clear(view: View) { states.remove(view.rootView) }
    fun sample(view: View, viscosity: Float, enabled: Boolean = true): State {
        val root = view.rootView
        if (!enabled) { states.remove(root); return disabled }
        val state = states.getOrPut(root) { State() }
        val target = scalePose(view, PostureGlass.pose(view.display?.rotation ?: 0, state.targetScratch))
        state.advance(android.os.SystemClock.uptimeMillis(), target[0], target[1], target[2], viscosity)
        val focused = (view as? io.github.offlineglass.hook.GlassHostLayout)?.hasOpticalWindowFocus()
            ?: view.hasWindowFocus()
        if (state.active && view.isAttachedToWindow && view.isShown && focused)
            view.postInvalidateOnAnimation()
        return state
    }

    /** Pure dynamics checks run by the existing shell-only debug verifier. */
    fun verify() {
        for (viscosity in listOf(0f, 25f, 100f)) {
            val state = State()
            state.advance(1000, 0f, 0f, -1f, viscosity)
            var maxX = 0f
            var maxRipple = 0f
            var maxRebound = 0f
            var responseAt300ms = 0f
            for (step in 1..1600) {
                state.advance(1000L + step * 8L, 0.15f, 0f, -1f, viscosity)
                check(state.pose.all(Float::isFinite))
                maxX = max(maxX, state.pose[0])
                maxRipple = max(maxRipple, state.rippleAmplitude)
                maxRebound = max(maxRebound, state.reboundX)
                if (step == 38) responseAt300ms = state.pose[0]
            }
            check(!state.active && abs(state.pose[0] - 0.15f) < 0.001f)
            if (viscosity == 0f) check(maxX > 0.20f && maxRipple > 0f && maxRebound > 0.04f)
            if (viscosity == 100f) check(maxX <= 0.151f && maxRipple == 0f && responseAt300ms < 0.04f)
        }
        val still = State()
        for (step in 0..100) still.advance(1000L + step * 8L, 0f, 0f, -1f, 0f)
        check(!still.active && still.rippleAmplitude == 0f)
        val stalled = State()
        stalled.advance(1000, 0f, 0f, -1f, 0f)
        stalled.advance(1008, 0.15f, 0f, -1f, 0f)
        stalled.advance(1208, 0.15f, 0f, -1f, 0f)
        check(abs(stalled.pose[0] - 0.15f) > 0.001f) // a short stall must not snap
    }

    const val RIPPLE_FIELD = """
uniform float4 flowRipple;
uniform float2 flowRebound;
uniform float flowMotionEnabled;
float flowingMotionCurve(float x) {
    float t = clamp(x, 0.0, 1.0);
    // Same endpoints as the circle lens, finite slope at the outer edge.
    // Restrict this regularization to the motion trial.
    return (1.0 - sqrt(max(1.0 - 0.96 * t * t, 0.04))) / 0.8;
}
float2 flowingReboundOffset(float2 p, float2 halfSize) {
    if (flowMotionEnabled < 0.5) return float2(0.0);
    // Uniform rest state: the original positive scale factors multiply zero.
    // Keep its signed-zero direction without evaluating the spatial envelope.
    if (flowRebound.x == 0.0 && flowRebound.y == 0.0) return flowingDownhill(flowRebound);
    float h = min(halfSize.x, halfSize.y);
    float edge = 0.35 + 0.65 * smoothstep(0.25, 0.9, max(abs(p.x), abs(p.y)));
    return flowingDownhill(flowRebound) * float2(min(halfSize.x, h * 2.0), h * 0.30) * 0.9 * edge;
}
float flowingRipple(float2 p) {
    if (flowRipple.x <= 0.0001) return 0.0;
    float2 direction = flowRipple.zw;
    float projection = dot(p, direction) / max(abs(direction.x) + abs(direction.y), 0.0001);
    float distance = max(1.0 - projection, 0.0);
    float edge = smoothstep(0.35, 0.90, max(abs(p.x), abs(p.y)));
    float front = distance - flowRipple.y * 2.4;
    float envelope = exp(-flowRipple.y * 4.5 - front * front * 2.0);
    return flowRipple.x * 0.22 * edge * envelope * sin(front * 6.28318531);
}
float flowingRippleGain(float gain, float2 p) {
    if (flowMotionEnabled < 0.5) return gain;
    // Use the ripple function's existing cutoff, not a new motion threshold.
    // Preserve the original clamp even for a caller outside the usual rim range.
    if (flowRipple.x <= 0.0001 && flowRebound.x == 0.0 && flowRebound.y == 0.0)
        return clamp(gain, 0.15, 4.5);
    // The uphill film retains a stable band instead of pulsating into a
    // subpixel optical edge. Ripple remains visible on the thicker liquid.
    float influence = smoothstep(0.55, 1.25, gain);
    float strain = dot(clamp(p, float2(-1.0), float2(1.0)), flowingDownhill(flowRebound));
    float rebound = 0.38 * strain / (0.04 + abs(strain));
    return clamp(gain + (gain - 0.28) * (flowingRipple(p) + rebound) * influence, 0.15, 4.5);
}
"""
}
