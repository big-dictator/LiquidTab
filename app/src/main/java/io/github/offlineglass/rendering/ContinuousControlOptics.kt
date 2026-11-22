package io.github.offlineglass.rendering

import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.RuntimeShader
import io.github.offlineglass.hook.GlassHostLayout
import kotlin.math.*

/** Composer geometry only. Shared posture, blur and optical policy are retained. */
internal object ContinuousControlOptics {
    // A nearest polygon edge/vertex gives discontinuous inward ray directions.
    // Interpolate the original curve tangents instead; adjacent spans share
    // identical endpoint normals, including the wrap-around span.
    val source: String = GlassHostLayout.ROUNDED_RECT_REFRACTION_SHADER
        .replace("uniform float2 shapePoints[64];", "uniform float2 shapePoints[64];\nuniform float2 shapeNormals[64];\nuniform float2 contourFlatBounds;")
        .replace("float3 continuousContour(float2 p) {", """float3 continuousContour(float2 p) {
            if (p.x >= contourFlatBounds.x && p.x <= contourFlatBounds.y) {
                float y = p.y - size.y * 0.5;
                return float3(abs(y) - size.y * 0.5, 0.0, y < 0.0 ? -1.0 : 1.0);
            }
        """)
        .replace("float2 halfSize = size * 0.5;", """float2 local = coord + offset;
            if (local.x < 0.0 || local.y < 0.0 || local.x > size.x || local.y > size.y) return content.eval(coord);
            float2 halfSize = size * 0.5;""")
        .replace("outward = shapeOrientation * normalize(float2(edge.y, -edge.x));",
            "outward = normalize(mix(shapeNormals[i], shapeNormals[i == 63 ? 0 : i + 1], t));")
        .replace("float2 normal = distance > 0.001 ? delta * side / distance : outward;", "float2 normal = outward;")

    fun bind(shader: RuntimeShader, path: Path, radius: Float) {
        val measure = PathMeasure(path, true)
        val length = measure.length
        val pos = FloatArray(2)
        val tangent = FloatArray(2)
        val weights = FloatArray(1025)
        var lastX = 0f; var lastY = 0f
        for (i in 0..1024) {
            measure.getPosTan(length*i/1024f, pos, tangent)
            if (i > 0) {
                val angle = acos((lastX*tangent[0]+lastY*tangent[1]).coerceIn(-1f,1f))
                weights[i] = weights[i-1] + length/1024f + radius * 3f * angle
            }
            lastX = tangent[0]; lastY = tangent[1]
        }
        val points = FloatArray(128)
        val normals = FloatArray(128)
        var segment = 1
        for (i in 0 until 64) {
            val target = weights[1024]*i/64f
            while (segment < 1024 && weights[segment] < target) segment++
            val t = ((target-weights[segment-1]) / (weights[segment]-weights[segment-1]).coerceAtLeast(.0001f)).coerceIn(0f,1f)
            measure.getPosTan(length*(segment-1+t)/1024f, pos, tangent)
            points[i*2] = pos[0]; points[i*2+1] = pos[1]
            normals[i*2] = tangent[1]; normals[i*2+1] = -tangent[0]
        }
        var area = 0f
        for (i in 0 until 64) {
            val j = (i+1)%64
            area += points[i*2]*points[j*2+1] - points[j*2]*points[i*2+1]
        }
        val orientation = if (area >= 0f) 1f else -1f
        for (i in normals.indices) normals[i] *= orientation
        val bounds = RectF().also { path.computeBounds(it,true) }
        var leftFlat = bounds.left
        var rightFlat = bounds.right
        // Exact straight-edge extent from the original curve tangents.
        val top = ArrayList<Float>()
        val bottom = ArrayList<Float>()
        for (i in 0..1024) {
            measure.getPosTan(length*i/1024f,pos,tangent)
            if (abs(tangent[1]) < .00001f) {
                if (pos[1] <= bounds.top+.01f) top.add(pos[0])
                if (pos[1] >= bounds.bottom-.01f) bottom.add(pos[0])
            }
        }
        if (top.isNotEmpty() && bottom.isNotEmpty()) {
            leftFlat = maxOf(top.min(),bottom.min())+.5f
            rightFlat = minOf(top.max(),bottom.max())-.5f
        } else { leftFlat = bounds.right; rightFlat = bounds.left }
        shader.setFloatUniform("contourFlatBounds",leftFlat,rightFlat)
        shader.setFloatUniform("shapePoints", points)
        shader.setFloatUniform("shapeNormals", normals)
        shader.setFloatUniform("shapeOrientation", orientation)
        shader.setFloatUniform("continuousShape", 1f)
    }
}

