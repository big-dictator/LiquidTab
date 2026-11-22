package io.github.offlineglass.rendering

import android.graphics.*
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Bundle
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.setBottomBarSquircle

/** GPU compares the old and optimized exact distance/normal at every probe pixel. */
internal object ContourGpuProbe {
    fun run(): Bundle = runCatching {
        val optimized = GlassHostLayout.ROUNDED_RECT_SDF
        val reference = optimized.replace("float2 winningEdge = float2(-1.0, 0.0);", "float2 outward = float2(0.0, 1.0);")
            .replace("winningEdge = edge;", "outward = shapeOrientation * normalize(float2(edge.y, -edge.x));")
            .replace("float2 outward = shapeOrientation * normalize(float2(winningEdge.y, -winningEdge.x));", "")
        check(reference != optimized)
        var cases = 0
        var maxDelta = 0
        for (w in listOf(120f, 300f)) for (curve in listOf(-1.2f, -1f, -.5f)) {
            val h = 64f
            val path = Path().apply { setBottomBarSquircle(RectF(0f, 0f, w, h), h / 2f, curve) }
            val measure = PathMeasure(path, true)
            val points = FloatArray(128)
            val point = FloatArray(2)
            repeat(64) { i ->
                measure.getPosTan(measure.length * i / 64f, point, null)
                points[i * 2] = point[0]; points[i * 2 + 1] = point[1]
            }
            val a = render(reference, points, w.toInt(), h.toInt())
            val b = render(optimized, points, w.toInt(), h.toInt())
            for (y in 0 until a.height) for (x in 0 until a.width) {
                val p = a.getPixel(x, y); val q = b.getPixel(x, y)
                for (shift in listOf(0, 8, 16, 24)) maxDelta = maxOf(maxDelta,
                    kotlin.math.abs((p ushr shift and 255) - (q ushr shift and 255)))
            }
            a.recycle(); b.recycle(); cases++
        }
        check(maxDelta == 0) { "Contour GPU output changed: $maxDelta" }
        Bundle().apply { putString("result", "PASS"); putInt("cases", cases); putInt("maxChannelDelta", maxDelta) }
    }.getOrElse { Bundle().apply { putString("error", it.stackTraceToString()) } }

    private fun render(sdf: String, points: FloatArray, w: Int, h: Int): Bitmap {
        val shader = RuntimeShader(sdf + """
            half4 main(float2 p) {
                if (continuousShape < 0.5) return half4(0.0);
                float3 v = continuousContour(p - float2(8.0));
                return half4(0.5 + v.x / 128.0, 0.5 + v.y * 0.45, 0.5 + v.z * 0.45, 1.0);
            }
        """)
        shader.setFloatUniform("shapePoints", points)
        shader.setFloatUniform("shapeOrientation", 1f)
        shader.setFloatUniform("continuousShape", 1f)
        val width = w + 16; val height = h + 16
        val root = RenderNode("ContourProbe")
        root.setPosition(0, 0, width, height)
        root.beginRecording(width, height).drawRect(0f, 0f, width.toFloat(), height.toFloat(),
            Paint().apply { this.shader = shader })
        root.endRecording()
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
        val renderer = HardwareRenderer()
        try {
            renderer.setSurface(reader.surface); renderer.setContentRoot(root); renderer.setOpaque(false)
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            var image = reader.acquireLatestImage()
            repeat(50) { if (image == null) { Thread.sleep(10); image = reader.acquireLatestImage() } }
            checkNotNull(image).use { frame -> checkNotNull(frame.hardwareBuffer).use { buffer ->
                val bitmap = checkNotNull(Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB)))
                return bitmap.copy(Bitmap.Config.ARGB_8888, false).also { bitmap.recycle() }
            } }
        } finally { renderer.destroy(); reader.close(); root.discardDisplayList() }
    }
}
