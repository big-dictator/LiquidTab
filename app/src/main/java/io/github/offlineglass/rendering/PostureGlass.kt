package io.github.offlineglass.rendering

import android.graphics.*
import android.view.View
import android.view.ViewTreeObserver
import io.github.offlineglass.hook.BloomTiltTracker
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.*

/** Shared optical policy. No package identity, sampling, network or app layout. */
internal object PostureGlass {
    // Reduce the posture-driven horizontal and vertical sampling shift while
    // leaving the material's static refraction scale unchanged.
    const val STRENGTH = 0.45f
    const val REFRACTION_SCALE = 1.25f
    const val SHADOW_STRENGTH = 1.8f
    private class Owner {
        val poseScratch = FloatArray(3)
        var active = false
        var wanted = false
        var referenceX = Float.NaN
        var referencePitch = Float.NaN
        // A root observer can outlive this control. Neither the observer callback
        // nor this weak-map value may retain the control (or its old window).
        var focusObserver = WeakReference<ViewTreeObserver>(null)
        var focusListener: ViewTreeObserver.OnWindowFocusChangeListener? = null
    }
    private val owners = WeakHashMap<View, Owner>()
    private val uniforms = WeakHashMap<RuntimeShader, String>()
    private val fullSurfaceShaders = WeakHashMap<RuntimeShader, Boolean>()
    private class EffectPose {
        val current = FloatArray(2)
        val applied = floatArrayOf(Float.NaN, Float.NaN)
        var motionWasActive = false
        var forceRefresh = false
        var surfaceWeight = Float.NaN
    }
    private val flowingShaders = WeakHashMap<RuntimeShader, EffectPose>()
    fun isTracking(view: View) = owners[view]?.active == true
    fun track(view: View, enabled: Boolean = true) {
        if (!enabled && !owners.containsKey(view)) return
        val owner = owners.getOrPut(view) {
            val value = Owner()
            val target = WeakReference(view)
            value.focusListener = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
                target.get()?.let { current ->
                    visibilityChanged(current)
                    if (focused && value.wanted && current.isShown) current.postInvalidateOnAnimation()
                }
            }
            view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    bindFocusObserver(v, value)
                    visibilityChanged(v)
                }
                override fun onViewDetachedFromWindow(v: View) {
                    unbindFocusObserver(value)
                    value.active = false
                    BloomTiltTracker.unregister(v)
                }
            })
            value
        }
        owner.wanted = enabled
        bindFocusObserver(view, owner)
        visibilityChanged(view)
    }
    private fun bindFocusObserver(view: View, owner: Owner) {
        if (!view.isAttachedToWindow) return
        val current = view.viewTreeObserver
        if (owner.focusObserver.get() === current) return
        unbindFocusObserver(owner)
        if (current.isAlive) {
            owner.focusListener?.let(current::addOnWindowFocusChangeListener)
            owner.focusObserver = WeakReference(current)
        }
    }
    private fun unbindFocusObserver(owner: Owner) {
        val previous = owner.focusObserver.get()
        if (previous?.isAlive == true) {
            owner.focusListener?.let(previous::removeOnWindowFocusChangeListener)
        }
        owner.focusObserver.clear()
    }
    fun visibilityChanged(view: View) {
        val owner = owners[view] ?: return
        val active = owner.wanted && view.isAttachedToWindow && view.isShown && view.hasWindowFocus() &&
            view.windowVisibility == View.VISIBLE
        if (active && !owner.active) { owner.active = true; BloomTiltTracker.register(view) }
        else if (!active && owner.active) { owner.active = false; BloomTiltTracker.unregister(view) }
    }
    fun pose(rotation: Int, destination: FloatArray): FloatArray {
        PoseRotation.write(rotation, BloomTiltTracker.refractionX, BloomTiltTracker.refractionY,
            BloomTiltTracker.refractionZ, STRENGTH, destination)
        return destination
    }
    fun revision(view: View, enabled: Boolean = true): Long {
        track(view, enabled)
        if (!enabled) return 0L
        val p = pose(view.display?.rotation ?: 0, owners.getValue(view).poseScratch)
        return ((p[0] * 200).roundToInt().toLong() and 0xffff) shl 32 xor
            (((p[1] * 200).roundToInt().toLong() and 0xffff) shl 16) xor
            ((p[2] * 200).roundToInt().toLong() and 0xffff)
    }
    fun shader(source: String, flowing: Boolean = true): RuntimeShader {
        val native = source.contains("uniform float2 tiltBias")
        val material = if (native) source else wrap(source, flowing)
        return RuntimeShader(material).also {
            if (material.contains("uniform float fullSurfaceWeight;")) {
                fullSurfaceShaders[it] = true
                it.setFloatUniform("fullSurfaceWeight", 0f)
            }
            uniforms[it] = if (native) "tiltBias" else "postureTilt"
            if (flowing && material.contains("uniform float flowMotionEnabled;")) {
                flowingShaders[it] = EffectPose()
                it.setFloatUniformExact("flowMotionEnabled", 1f)
                if (native && source.contains("uniform float flowingLiquidEnabled"))
                    it.setFloatUniformExact("flowingLiquidEnabled", 1f)
            }
        }
    }
    fun sync(shader: RuntimeShader, view: View, enabled: Boolean = true, viscosity: Float = 25f,
        horizontalStrength: Float? = null, verticalStrength: Float? = null) {
        if (horizontalStrength != null && verticalStrength != null)
            LiquidMotion.configure(view, horizontalStrength, verticalStrength)
        track(view, enabled)
        val state = flowingShaders[shader]
        if (fullSurfaceShaders[shader] == true) {
            val weight = if (enabled && state != null) 1f else 0f
            shader.setFloatUniform("fullSurfaceWeight", weight)
            if (state != null && state.surfaceWeight != weight) {
                state.surfaceWeight = weight
                state.forceRefresh = true
            }
        }
        val motion = if (state != null) LiquidMotion.sample(view, viscosity, enabled) else null
        val p = if (enabled) {
            motion?.pose ?: pose(view.display?.rotation ?: 0, owners.getValue(view).poseScratch)
        } else null
        val x = p?.get(0) ?: 0f
        val y = p?.get(1) ?: 0f
        val name = uniforms[shader] ?: run {
            val native = runCatching { shader.setFloatUniform("tiltBias",x,y) }.isSuccess
            (if (native) "tiltBias" else "postureTilt").also { uniforms[shader] = it }
        }
        shader.setFloatUniform(name, x, y)
        state?.let { it.current[0] = x; it.current[1] = y }
        if (state != null && motion != null) {
            shader.setFloatUniform("flowRipple", motion.rippleAmplitude, motion.rippleAge, motion.rippleX, motion.rippleY)
            shader.setFloatUniform("flowRebound", motion.reboundX, motion.reboundY)
            state.forceRefresh = state.forceRefresh || motion.active || state.motionWasActive
            state.motionWasActive = motion.active
        }
    }

    /** RenderEffect snapshots uniforms; refresh only when their optical value changes. */
    fun effectChanged(shader: RuntimeShader): Boolean {
        val state = flowingShaders[shader] ?: return false
        if (!state.forceRefresh && state.applied[0].isFinite() &&
            abs(state.current[0] - state.applied[0]) + abs(state.current[1] - state.applied[1]) < 0.0005f)
            return false
        state.current.copyInto(state.applied)
        state.forceRefresh = false
        return true
    }
    private val mask by lazy { RuntimeShader("""
        uniform float4 bounds; uniform float2 tilt;
        half4 main(float2 coord) {
            float2 side = clamp((coord - (bounds.xy + bounds.zw) * 0.5) /
                max((bounds.zw - bounds.xy) * 0.5, float2(1.0)), -1.0, 1.0);
            float h = 1.0 - max(tilt.x,0.0)*max(-side.x,0.0) - max(-tilt.x,0.0)*max(side.x,0.0);
            float v = 1.0 - max(tilt.y,0.0)*max(-side.y,0.0) - max(-tilt.y,0.0)*max(side.y,0.0);
            return half4(1.0,1.0,1.0,clamp(h*v,0.0,1.0));
        }
    """) }
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { blendMode = BlendMode.DST_IN }
    fun shadow(canvas: Canvas, view: View, bounds: RectF, padding: Float = 48f,
        draw: () -> Unit) {
        if (!canvas.isHardwareAccelerated || bounds.isEmpty) { draw(); return }
        val owner = owners[view] ?: run { draw(); return }
        if (!owner.wanted) { draw(); return }
        val p = pose(view.display?.rotation ?: 0, owner.poseScratch)
        val gx = -p[0] / STRENGTH
        val gy = -p[1] / STRENGTH
        val pitch = atan2(-gy, -p[2])
        if (!owner.referenceX.isFinite()) { owner.referenceX = gx; owner.referencePitch = pitch }
        val tx = ((gx - owner.referenceX) * 2.4f).coerceIn(-1f,1f)
        val delta = pitch - owner.referencePitch
        val ty = (atan2(sin(delta), cos(delta)) * 3f).coerceIn(-1f,1f)
        if (abs(tx)+abs(ty) < 0.004f) { draw(); return }
        val save = canvas.saveLayer(bounds.left-padding,bounds.top-padding,
            bounds.right+padding,bounds.bottom+padding,null)
        draw()
        mask.setFloatUniform("bounds",bounds.left,bounds.top,bounds.right,bounds.bottom)
        mask.setFloatUniform("tilt",tx,ty)
        maskPaint.shader = mask
        canvas.drawRect(bounds.left-padding,bounds.top-padding,bounds.right+padding,
            bounds.bottom+padding,maskPaint)
        canvas.restoreToCount(save)
    }
    fun wrap(source: String, flowing: Boolean = true): String {
        if (source.contains("uniform float2 postureTilt")) return source
        val useFlowing = flowing && !source.contains("uniform float2 flowTilt;")
        val material = if (useFlowing) FlowingLiquid.materialSource(source) else source
        val modified = material.replace("content.eval(", "content.eval(postureOffset(coord) + ")
        val main = Regex("half4\\s+main\\s*\\(float2\\s+coord\\)").find(modified)
            ?: error("Posture optics requires a material main(float2 coord)")
        return "uniform float2 postureTilt;\n" +
            (if (useFlowing) "uniform float fullSurfaceWeight;\n" else "") + modified.substring(0, main.range.first) +
            (if (useFlowing) FlowingLiquid.FIELD + LiquidMotion.RIPPLE_FIELD + FlowingLiquid.MATERIAL_OPTICS else OPTICS) +
            (if (source.contains("uniform float4 safeSampleBounds;")) FILTERED_SAMPLING else "") +
            modified.substring(main.range.first)
    }
    private const val FILTERED_SAMPLING = """
half4 sampleFilteredScene(float2 coord) {
    if (safeSampleBounds.z > safeSampleBounds.x && safeSampleBounds.w > safeSampleBounds.y)
        coord = clamp(coord, safeSampleBounds.xy, safeSampleBounds.zw);
    return content.eval(coord);
}
"""
    const val OPTICS = """
float2 postureOffset(float2 coord) {
    if (dot(postureTilt, postureTilt) < 0.000001) return float2(0.0);
    float2 halfSize = max(size * 0.5, float2(1.0));
    float2 p = (coord + offset - halfSize) / halfSize;
    float3 n = normalize(float3(p * 0.55, 1.0));
    float3 incident = normalize(float3(postureTilt, -1.0));
    float eta = 0.75;
    float ci = dot(n, incident);
    float cf = -n.z;
    float3 ray = eta * incident - (eta * ci + sqrt(max(1.0 - eta * eta * (1.0 - ci * ci), 0.0))) * n;
    float3 front = float3(0.0, 0.0, -eta) - (eta * cf + sqrt(max(1.0 - eta * eta * (1.0 - cf * cf), 0.0))) * n;
    float2 delta = ray.xy / max(-ray.z, 0.15) - front.xy / max(-front.z, 0.15);
    return delta * min(halfSize.x, halfSize.y) * 0.95 * clamp(1.0 - 0.3 * dot(p, p), 0.4, 1.0);
}
"""
}
