package io.github.offlineglass.rendering

/** Shared Flowing Liquid thickness field.
 * The field changes sampling only; it never transforms a control or its artwork.
 */
internal object FlowingLiquid {
    private class FramePose {
        val value = FloatArray(3)
        val targetScratch = FloatArray(3)
        var time = 0L
        var settling = false
    }
    private val framePoses = java.util.WeakHashMap<android.view.View, FramePose>()

    /** Interpolate the shared GAME-rate sensor on display frames, then stop. */
    fun poseForFrame(view: android.view.View): FloatArray {
        val state = framePoses.getOrPut(view) { FramePose() }
        val target = LiquidMotion.scalePose(view, PostureGlass.pose(view.display?.rotation ?: 0, state.targetScratch))
        val now = android.os.SystemClock.uptimeMillis()
        if (state.time == 0L) target.copyInto(state.value)
        else {
            val dt = (now - state.time).coerceIn(0L, 50L).toFloat()
            val blend = 1f - kotlin.math.exp(-dt / 18f)
            for (i in 0..2) state.value[i] += (target[i] - state.value[i]) * blend
        }
        state.time = now
        state.settling = (0..2).any { kotlin.math.abs(target[it] - state.value[it]) > 0.0006f }
        if (state.settling)
            view.postInvalidateOnAnimation()
        return state.value
    }

    fun isSettling(view: android.view.View): Boolean = framePoses[view]?.settling == true

    /** Material-local redistribution; source geometry and sampling passes stay fixed. */
    fun materialSource(source: String): String {
        val main = Regex("half4\\s+main\\s*\\(float2\\s+coord\\)\\s*\\{").find(source)
            ?: error("Flowing material requires main(float2 coord)")
        val hasHeight = source.contains("uniform float refractionHeight;")
        val body = source.substring(main.range.last + 1)
            .replace(Regex("\\brefractionAmount\\b"), "flowMaterialAmount")
            .let { if (hasHeight) it.replace(Regex("\\brefractionHeight\\b"), "flowMaterialHeight") else it }
            .replace("circleMap(", "flowingMotionCurve(")
            .replace("(1.0 - sqrt(max(1.0 - t * t, 0.0)))", "flowingMotionCurve(t)")
        val geometry = """
            float2 flowMaterialHalf = max(size * 0.5, float2(1.0));
            float2 flowMaterialPosition = (coord + offset - flowMaterialHalf) / flowMaterialHalf;
            float flowMaterialGain = flowingRippleGain(
                flowingRimGain(flowMaterialPosition, postureTilt, flowMaterialHalf), flowMaterialPosition);
            float flowMaterialAmount = refractionAmount * flowMaterialGain;
        """
        val height = if (hasHeight) """
            float flowMaterialHeight = mix(refractionHeight,
                min(refractionHeight * max(sqrt(flowMaterialGain), 0.70),
                    min(flowMaterialHalf.x, flowMaterialHalf.y) * 0.92), flowingActivation(postureTilt));
        """ else ""
        return source.substring(0, main.range.last + 1) + geometry + height + body
    }

    const val MATERIAL_OPTICS = """
float2 postureOffset(float2 coord) {
    if (refractionAmount == 0.0) return float2(0.0);
    float2 halfSize = max(size * 0.5, float2(1.0));
    float2 p = (coord + offset - halfSize) / halfSize;
    float gain = flowingRippleGain(flowingRimGain(p, postureTilt, halfSize), p);
    float2 interior = flowingInteriorRefraction(p, refractionAmount, gain) *
        flowingInteriorEnvelope(p) * fullSurfaceWeight;
    return flowingLiquidOffset(p, postureTilt, halfSize) + flowingReboundOffset(p, halfSize) + interior;
}
"""

    // One continuous directional field covers all 2*pi tilt angles.
    const val FIELD = """
float2 flowingDownhill(float2 opticalTilt) {
    // The legacy optical bias negates device X for its view-ray sampling.
    // Thickness follows gravity: screen X is device X, screen Y is -device Y.
    return float2(-opticalTilt.x, opticalTilt.y);
}
float flowingDirection(float2 p, float2 tilt) {
    float projection = dot(clamp(p, float2(-1.0), float2(1.0)), flowingDownhill(tilt)) /
        max(abs(tilt.x) + abs(tilt.y), 0.0001);
    // Expand the gradient near either edge while preserving its sign and
    // a smooth derivative at the center. Diagonal edges no longer average
    // their contrast away with the perpendicular axis.
    return projection * 1.6 / (0.6 + abs(projection));
}
float flowingLowerSide(float2 p, float2 tilt) {
    float lower = max(flowingDirection(p, tilt), 0.0);
    return lower * lower;
}
float flowingActivation(float2 tilt) {
    float t = clamp((length(tilt) - 0.008) / 0.09, 0.0, 1.0);
    return t * t * (3.0 - 2.0 * t);
}
float2 flowingLiquidOffset(float2 p, float2 tilt, float2 halfSize) {
    float activation = flowingActivation(tilt);
    if (activation < 0.0001) return float2(0.0);
    float2 downhill = flowingDownhill(tilt) / max(length(tilt), 0.0001);
    float lower = flowingLowerSide(p, tilt);
    float envelope = clamp(1.0 - 0.12 * dot(p, p), 0.72, 1.0);
    float halfHeight = min(halfSize.x, halfSize.y);
    // Vertical flow is gentler for upright handheld use; horizontal travel
    // and the continuous direction of the thickness field stay unchanged.
    float2 travel = float2(min(halfSize.x, halfHeight * 2.0), halfHeight * 0.30);
    return downhill * activation * travel * 0.16 * lower * envelope;
}
float flowingRimGain(float2 p, float2 tilt, float2 halfSize) {
    float activation = flowingActivation(tilt);
    // The final mix is exactly 1 when activation is zero. Keep its existing
    // threshold, but avoid per-pixel direction, division and pow work in the
    // static material (including posture-disabled controls).
    if (activation == 0.0) return 1.0;
    float verticalWeight = tilt.y * tilt.y / max(dot(tilt, tilt), 0.000001);
    activation *= 1.0 - 0.70 * verticalWeight;
    float direction = flowingDirection(p, tilt);
    float lower = max(direction, 0.0);
    float upper = max(-direction, 0.0);
    // Redistribute thickness, retaining an uphill film for surface tension.
    float gain = (1.0 + 3.0 * lower * lower) / (1.0 + 8.0 * upper * upper);
    float horizontalWeight = abs(tilt.x) / max(abs(tilt.x) + abs(tilt.y), 0.0001);
    float widePanel = clamp(halfSize.x / max(halfSize.y, 1.0) - 1.0, 0.0, 1.0);
    gain = min(pow(gain, 1.0 + 0.55 * horizontalWeight * widePanel), 4.5);
    // Preserve the downhill response. Uphill attenuation approaches 15%
    // smoothly rather than flattening into a hard clamp or disappearing.
    if (upper > 0.0) gain = 0.15 + 0.85 * gain;
    return mix(1.0, gain, activation);
}
// A smooth convex interior slope. Unlike a constant translation, it refracts
// the whole surface and joins the rim without a flat strip or a new cutoff.
float2 flowingInteriorRefraction(float2 p, float amount, float gain) {
    float2 bounded = clamp(p, float2(-1.0), float2(1.0));
    float curvature = 0.85 + 0.15 * dot(bounded, bounded);
    return amount * 0.18 * gain * bounded * curvature;
}
float flowingInteriorEnvelope(float2 p) {
    float2 q = clamp(p, float2(-1.0), float2(1.0));
    float2 q2 = q * q;
    return 1.0 - smoothstep(0.15, 1.0, dot(q2, q2));
}
float flowingCentreDecay(float x) {
    float t = clamp(x, 0.0, 1.0);
    // Quintic smootherstep: value and first two derivatives join smoothly
    // at both ends, avoiding a steep outer-edge falloff or a central kink.
    return t * t * t * (10.0 + t * (-15.0 + 6.0 * t));
}
float flowingSmoothDistance(float2 point, float2 halfSize, float radius) {
    // Smooth abs around the medial axes, normalized to retain the value at
    // the footprint edges. This changes optical distance, not the clip shape.
    float epsilon = max(min(halfSize.x, halfSize.y) * 0.35, 1.0);
    float2 smoothAbs = (sqrt(point * point + float2(epsilon * epsilon)) - epsilon) *
        halfSize / max(sqrt(halfSize * halfSize + float2(epsilon * epsilon)) - epsilon, float2(0.001));
    float2 corner = smoothAbs - (halfSize - float2(radius));
    return length(max(corner, float2(0.0))) + min(max(corner.x, corner.y), 0.0) - radius;
}
// Gravity-local trial mapping with a strictly monotone transverse coordinate.
// Strength changes along gravity; across it the bow is a single quadratic arc.
float2 flowingMonotoneShift(float2 point, float2 tilt, float2 recoil,
                         float2 halfSize, float amount) {
    float2 gravity = flowingDownhill(tilt);
    float2 axis = gravity / max(length(gravity), 0.0001);
    float2 crossAxis = float2(-axis.y, axis.x);
    float h = min(halfSize.x, halfSize.y);
    float extent = max((halfSize.x - h) * abs(axis.x) + h, 1.0);
    float crossExtent = max((halfSize.x - h) * abs(crossAxis.x) + h, 1.0);
    float along = clamp(dot(point, axis) / extent, -1.0, 1.0);
    float across = clamp(dot(point, crossAxis) / crossExtent, -1.0, 1.0);
    float phase = flowingCentreDecay((along + 1.0) * 0.5);
    float vertical = tilt.y * tilt.y / max(dot(tilt, tilt), 0.000001);
    float amplitude = min(abs(amount) * 2.0, extent * 0.35) * (1.0 - 0.70 * vertical);
    float recoilAlong = clamp(dot(flowingDownhill(recoil), axis), -0.2, 0.2);
    amplitude *= 1.0 + recoilAlong * 0.6;
    float bow = 1.0 - 0.55 * across * across;
    float alongShift = -amplitude * (0.6 + 0.4 * phase) * bow;
    float compression = 0.35 + 0.20 * phase;
    // derivative >= 1-compression >= .45 (phase is independent of across).
    float acrossShift = -dot(point, crossAxis) * compression * exp(-2.0 * across * across);
    return axis * alongShift + crossAxis * acrossShift;
}
// A rounded optical front in gravity-local coordinates. The vertex sits
// inside the fixed footprint, not at its physical corner. No abs(t) cusp.
float2 flowingRoundedFront(float2 p, float2 tilt, float2 halfSize, float amount, float gain) {
    float2 gravity = flowingDownhill(tilt);
    float2 axis = gravity / max(length(gravity), 0.0001);
    float2 crossAxis = float2(-axis.y, axis.x);
    float h = min(halfSize.x, halfSize.y);
    float support = max((halfSize.x - h) * abs(axis.x) + h, 1.0);
    float inset = min(h * 2.0, support * 0.5);
    float2 point = p * halfSize;
    float along = dot(point, axis);
    float across = dot(point, crossAxis);
    float curvature = 0.65 / max(h, 1.0);
    float front = support - inset - curvature * across * across;
    float slope = 2.0 * curvature * across;
    float normalScale = sqrt(1.0 + slope * slope);
    float distance = (along - front) / normalScale;
    float band = max(min(h * 0.6, support * 0.35), 1.0);
    float amplitude = min(abs(amount) * 0.35 * max(gain - 1.0, 0.0), band * 0.45);
    float profile = exp(-distance * distance / (band * band));
    float crossFade = exp(-0.35 * across * across / max(h * h, 1.0));
    float2 normal = (axis + crossAxis * slope) / normalScale;
    return -normal * amplitude * profile * crossFade * flowingActivation(tilt);
}
// Pinned, volume-neutral asymmetric cap: the directional term is odd over
// the symmetric footprint. Its analytic gradient includes BOTH cap slope
// and redistribution slope, rather than scaling an old edge normal.
float2 unifiedDropletRefraction(float2 p, float2 tilt, float2 recoil,
                              float2 halfSize, float amount) {
    float2 q = clamp(p, float2(-1.0), float2(1.0));
    float2 gravity = flowingDownhill(tilt + recoil * 0.18);
    gravity.y *= 0.30;
    float magnitude = length(gravity);
    float2 direction = gravity / max(magnitude, 0.0001);
    float strength = 0.65 * smoothstep(0.008, 0.18, magnitude);
    float cap = sqrt(max(1.0 - 0.45 * dot(q, q), 0.10));
    float redistribution = 1.0 + strength * dot(q, direction);
    float2 slope = (-0.45 * q / cap) * redistribution + cap * strength * direction;
    float h = min(halfSize.x, halfSize.y);
    slope *= float2(h / max(min(halfSize.x, h * 2.0), 1.0), h / max(halfSize.y, 1.0));
    float3 normal = normalize(float3(-slope, 1.0));
    float3 incident = normalize(float3(tilt * 0.25, -1.0));
    float eta = 0.75;
    float ci = dot(normal, incident);
    float3 ray = eta * incident -
        (eta * ci + sqrt(max(1.0 - eta * eta * (1.0 - ci * ci), 0.0))) * normal;
    return ray.xy / max(-ray.z, 0.20) * abs(amount) * 1.5;
}
"""

    /** Build a Spotify-only variant without changing Kyant's shared source. */
    fun kyantLensSource(base: String): String {
        val uniforms = "uniform float depthEffect;"
        val flatSample = "if (-sd >= refractionHeight) return content.eval(coord);"
        val rim = "float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;"
        val edgeSample = "return content.eval(coord + d * grad);"
        require(listOf(uniforms, flatSample, rim, edgeSample).all(base::contains)) {
            "Kyant lens source changed; update the Flowing Liquid variant explicitly"
        }
        return base
            .replace(uniforms, "$uniforms\nuniform float2 flowTilt;\nuniform float fullSurfaceWeight;\n$FIELD\n${LiquidMotion.RIPPLE_FIELD}")
            .replace(flatSample, """
                float2 flowPosition = centeredCoord / max(halfSize, float2(1.0));
                float2 flowShift = flowingLiquidOffset(flowPosition, flowTilt, halfSize)
                    + flowingReboundOffset(flowPosition, halfSize);
                float flowGain = flowingRippleGain(flowingRimGain(flowPosition, flowTilt, halfSize), flowPosition);
                flowShift += flowingInteriorRefraction(flowPosition, refractionAmount, flowGain) *
                    flowingInteriorEnvelope(flowPosition) * fullSurfaceWeight;
                float flowHeight = min(refractionHeight * max(sqrt(flowGain), 0.70),
                    min(halfSize.x, halfSize.y) * 0.92);
                if (-sd >= flowHeight) return content.eval(coord + flowShift);
            """.trimIndent())
            .replace(rim, "float d = flowingMotionCurve(1.0 - -sd / max(flowHeight, 0.001)) * refractionAmount * flowGain;")
            .replace(edgeSample, "return content.eval(coord + d * grad + flowShift);")
            .replace("uniform shader content;", "uniform shader content;\nuniform float4 safeSampleBounds;")
            .replace("content.eval(", "sampleFilteredScene(")
    }
}
