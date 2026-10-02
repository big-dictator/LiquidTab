package io.github.offlineglass.ui

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.util.fastCoerceAtMost
import top.yukonga.miuix.kmp.blur.BackdropEffectScope
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.runtimeShaderEffect

internal fun BackdropEffectScope.managerLens(
    refractionHeight: Float,
    refractionAmount: Float,
    depthEffect: Boolean = false,
    chromaticAberration: Float = 0f,
) {
    if (!isRuntimeShaderSupported() || refractionHeight <= 0f || refractionAmount <= 0f) return
    if (padding < refractionAmount) padding = refractionAmount
    val cornerShape = shape as? CornerBasedShape ?: return
    val maxRadius = size.minDimension / 2f
    val isLtr = layoutDirection == LayoutDirection.Ltr
    val radii = floatArrayOf(
        (if (isLtr) cornerShape.topStart else cornerShape.topEnd).toPx(size, this),
        (if (isLtr) cornerShape.topEnd else cornerShape.topStart).toPx(size, this),
        (if (isLtr) cornerShape.bottomEnd else cornerShape.bottomStart).toPx(size, this),
        (if (isLtr) cornerShape.bottomStart else cornerShape.bottomEnd).toPx(size, this),
    )
    val sf = downscaleFactor.coerceAtLeast(1).toFloat()
    val dispersion = chromaticAberration > 0f
    runtimeShaderEffect(
        key = if (dispersion) "ManagerLiquidLensDispersion" else "ManagerLiquidLens",
        shaderString = if (dispersion) DISPERSION_SHADER else LENS_SHADER,
        uniformShaderName = "content",
    ) {
        setFloatUniform("size", size.width / sf, size.height / sf)
        setFloatUniform("offset", -padding / sf, -padding / sf)
        setFloatUniform("cornerRadii", FloatArray(4) { radii[it].fastCoerceAtMost(maxRadius) / sf })
        setFloatUniform("refractionHeight", refractionHeight / sf)
        setFloatUniform("refractionAmount", -refractionAmount / sf)
        setFloatUniform("depthEffect", if (depthEffect) 1f else 0f)
        if (dispersion) setFloatUniform("chromaticAberration", chromaticAberration)
    }
}

private const val SDF = """
float radiusAt(float2 coord, float4 radii) {
    if (coord.x >= 0.0) { if (coord.y <= 0.0) return radii.y; else return radii.z; }
    else { if (coord.y <= 0.0) return radii.x; else return radii.w; }
}
float sdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    float outside = length(max(cornerCoord, 0.0)) - radius;
    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
    return outside + inside;
}
float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) return sign(coord) * normalize(max(cornerCoord, 0.0));
    float gradX = step(cornerCoord.y, cornerCoord.x);
    return sign(coord) * float2(gradX, 1.0 - gradX);
}
float circleMap(float x) { return 1.0 - sqrt(1.0 - x * x); }
"""

private const val LENS_SHADER = """
uniform shader content;
uniform float2 size; uniform float2 offset; uniform float4 cornerRadii;
uniform float refractionHeight; uniform float refractionAmount; uniform float depthEffect;
$SDF
half4 main(float2 coord) {
    float2 halfSize = size * 0.5; float2 centeredCoord = (coord + offset) - halfSize;
    float radius = radiusAt(coord, cornerRadii); float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    if (-sd >= refractionHeight) return content.eval(coord); sd = min(sd, 0.0);
    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius) + depthEffect * normalize(centeredCoord));
    return content.eval(coord + d * grad);
}
"""

private const val DISPERSION_SHADER = """
uniform shader content;
uniform float2 size; uniform float2 offset; uniform float4 cornerRadii;
uniform float refractionHeight; uniform float refractionAmount; uniform float depthEffect;
uniform float chromaticAberration;
$SDF
half4 main(float2 coord) {
    float2 halfSize = size * 0.5; float2 centeredCoord = (coord + offset) - halfSize;
    float radius = radiusAt(coord, cornerRadii); float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    if (-sd >= refractionHeight) return content.eval(coord); sd = min(sd, 0.0);
    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius) + depthEffect * normalize(centeredCoord));
    float2 refractedCoord = coord + d * grad;
    float dispersionIntensity = chromaticAberration * ((centeredCoord.x * centeredCoord.y) / (halfSize.x * halfSize.y));
    float2 dispersedCoord = d * grad * dispersionIntensity; half4 color = half4(0.0);
    half4 red = content.eval(refractedCoord + dispersedCoord); color.r += red.r/3.5; color.a += red.a/7.0;
    half4 orange = content.eval(refractedCoord + dispersedCoord*0.6666667); color.r += orange.r/3.5; color.g += orange.g/7.0; color.a += orange.a/7.0;
    half4 yellow = content.eval(refractedCoord + dispersedCoord*0.3333333); color.r += yellow.r/3.5; color.g += yellow.g/3.5; color.a += yellow.a/7.0;
    half4 green = content.eval(refractedCoord); color.g += green.g/3.5; color.a += green.a/7.0;
    half4 cyan = content.eval(refractedCoord - dispersedCoord*0.3333333); color.g += cyan.g/3.5; color.b += cyan.b/3.0; color.a += cyan.a/7.0;
    half4 blue = content.eval(refractedCoord - dispersedCoord*0.6666667); color.b += blue.b/3.0; color.a += blue.a/7.0;
    half4 purple = content.eval(refractedCoord - dispersedCoord); color.r += purple.r/7.0; color.b += purple.b/3.0; color.a += purple.a/7.0;
    return color;
}
"""
