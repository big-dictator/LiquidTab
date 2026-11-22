package io.github.offlineglass.ui


import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.util.fastCoerceAtMost
import top.yukonga.miuix.kmp.blur.BackdropEffectScope
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.runtimeShaderEffect

internal var managerRefractionScale by androidx.compose.runtime.mutableFloatStateOf(1f)

internal fun BackdropEffectScope.managerLens(
    refractionHeight: Float,
    refractionAmount: Float,
    depthEffect: Boolean = false,
    chromaticAberration: Float = 0f,
    insetIndicator: ManagerInsetIndicatorShape? = null,
    postureBlend: Float = 1f,
) {
    val amount = refractionAmount * managerRefractionScale
    if (!isRuntimeShaderSupported() || refractionHeight <= 0f || amount <= 0f) return
    val lensPadding = maxOf(refractionAmount, amount)
    if (padding < lensPadding) padding = lensPadding
    val cornerShape = shape as? CornerBasedShape
    if (cornerShape == null && insetIndicator == null) return
    val maxRadius = size.minDimension / 2f
    val isLtr = layoutDirection == LayoutDirection.Ltr
    val radii = if (cornerShape == null) FloatArray(4) else floatArrayOf(
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
        setFloatUniform("refractionAmount", -amount / sf)
        setFloatUniform("depthEffect", if (depthEffect) 1f else 0f)
        val pose = io.github.offlineglass.rendering.PostureCompose.current
        setFloatUniform("postureTilt", pose.x * postureBlend, pose.y * postureBlend)
        setFloatUniform("flowMotionEnabled", 1f)
        val root = io.github.offlineglass.rendering.PostureCompose.view.get()
        setFloatUniform("fullSurfaceWeight", if (root != null &&
            io.github.offlineglass.rendering.PostureGlass.isTracking(root)) postureBlend else 0f)
        setFloatUniform("flowRipple", pose.rippleAmplitude * postureBlend, pose.rippleAge, pose.rippleX, pose.rippleY)
        setFloatUniform("flowRebound", pose.reboundX * postureBlend, pose.reboundY * postureBlend)
        setFloatUniform("continuousShape", if (insetIndicator != null) 1f else 0f)
        if (insetIndicator != null) {
            val contour = insetIndicator.contour(size, sf)
            setFloatUniform("shapeOrientation", contour.orientation)
            setFloatUniform("shapePoints", contour.points)
        }
        if (dispersion) setFloatUniform("chromaticAberration", chromaticAberration)
    }
}

private const val SDF = """
${io.github.offlineglass.hook.GlassHostLayout.ROUNDED_RECT_SDF}
float circleMap(float x) { return 1.0 - sqrt(1.0 - x * x); }
"""

private val LENS_SHADER = io.github.offlineglass.rendering.PostureGlass.wrap("""
uniform shader content;
uniform float2 size; uniform float2 offset; uniform float4 cornerRadii;
uniform float refractionHeight; uniform float refractionAmount; uniform float depthEffect;
$SDF
half4 main(float2 coord) {
    float2 halfSize = size * 0.5; float2 centeredCoord = (coord + offset) - halfSize;
    float radius = radiusAt(coord, cornerRadii); float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    float3 contour = float3(sd, 0.0, 0.0);
    if (continuousShape > 0.5) { contour = continuousContour(coord + offset); sd = contour.x; }
    if (-sd >= refractionHeight) return content.eval(coord); sd = min(sd, 0.0);
    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = normalize((continuousShape > 0.5 ? contour.yz : gradSdRoundedRect(centeredCoord, halfSize, gradRadius)) + depthEffect * normalize(centeredCoord));
    return content.eval(coord + d * grad);
}
""")

private val DISPERSION_SHADER = io.github.offlineglass.rendering.PostureGlass.wrap("""
uniform shader content;
uniform float2 size; uniform float2 offset; uniform float4 cornerRadii;
uniform float refractionHeight; uniform float refractionAmount; uniform float depthEffect;
uniform float chromaticAberration;
$SDF
half4 main(float2 coord) {
    float2 halfSize = size * 0.5; float2 centeredCoord = (coord + offset) - halfSize;
    float radius = radiusAt(coord, cornerRadii); float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    float3 contour = float3(sd, 0.0, 0.0);
    if (continuousShape > 0.5) { contour = continuousContour(coord + offset); sd = contour.x; }
    if (-sd >= refractionHeight) return content.eval(coord); sd = min(sd, 0.0);
    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = normalize((continuousShape > 0.5 ? contour.yz : gradSdRoundedRect(centeredCoord, halfSize, gradRadius)) + depthEffect * normalize(centeredCoord));
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
""")

internal fun verifyManagerPostureShaders() {
    android.graphics.RuntimeShader(LENS_SHADER)
    android.graphics.RuntimeShader(DISPERSION_SHADER)
}

