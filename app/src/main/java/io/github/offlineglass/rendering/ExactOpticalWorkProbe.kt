package io.github.offlineglass.rendering

import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.os.Bundle
import io.github.offlineglass.hook.setBottomBarSquircle

/** Shell-only diagnostic: no app settings, navigation or business state changed. */
internal fun verifyExactOpticalWork(): Bundle = runCatching {
    val cache = ExactOpticalGeometryCache(8)
    fun sameContour(expected: Path, actual: Path) {
        val a = PathMeasure(expected, true)
        val b = PathMeasure(actual, true)
        val p = FloatArray(2)
        val q = FloatArray(2)
        // Float arithmetic under translation can change its last bits; no
        // coordinate snapping is allowed. Check physical contour error instead.
        check(kotlin.math.abs(a.length - b.length) < .01f)
        for (i in 0..128) {
            check(a.getPosTan(a.length * i / 128f, p, null))
            check(b.getPosTan(b.length * i / 128f, q, null))
            check(kotlin.math.abs(p[0] - q[0]) < .003f &&
                kotlin.math.abs(p[1] - q[1]) < .003f) { "Translated contour changed" }
        }
    }
    var cases = 0
    for (curve in listOf(-1.2f, -1f, -.5f, 0f, 1f)) {
        for (height in listOf(50f, 56f, 64f)) {
            for (left in listOf(0f, 41.125f, -13.5f)) {
                val rect = RectF(left, 3.25f, left + 240.5f, 3.25f + height)
                for (fill in Path.FillType.values()) {
                    val expected = Path().apply { fillType = fill; setBottomBarSquircle(rect, height / 2, curve) }
                    val actual = Path().apply { fillType = fill }
                    cache.copyInto(actual, rect, height / 2, curve)
                    cache.copyInto(actual, rect, height / 2, curve)
                    sameContour(expected, actual)
                    check(actual.fillType == fill)
                    actual.rewind() // Caller mutation must never corrupt the stored contour.
                    cache.copyInto(actual, rect, height / 2, curve)
                    sameContour(expected, actual)
                    cases++
                }
            }
        }
    }
    val moving = ExactOpticalGeometryCache(8)
    repeat(240) { i ->
        val left = i * .125f - 10f
        val rect = RectF(left, 3.25f, left + 240.5f, 59.25f)
        val actual = Path()
        moving.copyInto(actual, rect, 28f, -1f)
        sameContour(Path().apply { setBottomBarSquircle(rect, 28f, -1f) }, actual)
    }
    check(moving.builds == 1 && moving.hits == 239)
    val shader = RuntimeShader("uniform float2 p; layout(color) uniform half4 tint; half4 main(float2 c) { return tint * half(p.x); }")
    val uniforms = ExactOpticalUniformCache(shader)
    repeat(10) { uniforms.floats("p", .5f, .25f); uniforms.color("tint") }
    check(uniforms.updates == 2)
    uniforms.floats("p", .6f, .25f)
    check(uniforms.updates == 3)
    val mutable = floatArrayOf(.6f, .25f)
    repeat(240) { uniforms.array("p", mutable) }
    check(uniforms.updates == 3)
    mutable[0] = .7f
    uniforms.array("p", mutable)
    check(uniforms.updates == 4) // caller mutation must not mutate cache ownership
    Bundle().apply {
        putString("result", "PASS"); putInt("cases", cases)
        putInt("builds", cache.builds); putInt("hits", cache.hits)
        putInt("uniformWrites", uniforms.updates)
        putInt("movingBuilds", moving.builds); putInt("movingHits", moving.hits)
    }
}.getOrElse { Bundle().apply { putString("error", it.stackTraceToString()) } }
