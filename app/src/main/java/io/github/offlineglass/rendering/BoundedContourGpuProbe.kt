package io.github.offlineglass.rendering

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Bundle
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.setBottomBarSquircle
import kotlin.math.abs
import kotlin.math.max

/** Debug-only caller: synthetic offscreen pixels, never a captured app/user image. */
internal object BoundedContourGpuProbe {
    private data class Case(
        val name: String,
        val width: Int,
        val height: Int,
        val points: FloatArray,
        val offsetX: Float = 0f,
        val offsetY: Float = 0f,
        val invalidBounds: Boolean = false,
        val forceDisabled: Boolean = false,
    )

    fun run(): Bundle = runCatching {
        val reference = GlassHostLayout.ROUNDED_RECT_SDF
        val optimized = checkNotNull(BoundedContourShader.specialize(reference))
        check(reference != optimized)
        // Compile the actual material entry too; pixel tests below isolate the changed geometry.
        RuntimeShader(checkNotNull(BoundedContourShader.specialize(
            GlassHostLayout.ROUNDED_RECT_DISPERSION_SHADER)))
        val cases = geometryCases()
        val sharp = checkerboard()
        val blurred = renderNode(sharp.width, sharp.height,
            RenderEffect.createBlurEffect(4f, 4f, Shader.TileMode.CLAMP)) { canvas ->
            canvas.drawBitmap(sharp, 0f, 0f, Paint())
        }
        var maxDelta = 0
        var comparisons = 0
        var blurredComparisons = 0
        var fallbackCases = 0
        var worstCase = ""
        try {
            cases.forEachIndexed { index, case ->
                val bounds = ContourSearchBounds()
                bounds.update(case.points)
                if (case.invalidBounds) {
                    val invalid = case.points.copyOf()
                    invalid[0] = Float.NaN
                    check(!bounds.update(invalid))
                }
                val enabled = bounds.isEnabled && !case.forceDisabled
                if (!enabled) fallbackCases++
                // Both uniform sets originate from the same geometry case. Failed bounds are disabled.
                repeat(2) { mode ->
                    val useBlur = mode == 1 && index % 2 == 1
                    val content = if (useBlur) blurred else sharp
                    val a = render(reference, case, bounds, false, mode, content)
                    try {
                        val b = render(optimized, case, bounds, true, mode, content)
                        try {
                            val delta = maximumChannelDifference(a, b)
                            if (delta > maxDelta) {
                                maxDelta = delta
                                worstCase = "${case.name}/mode=$mode/blur=$useBlur"
                            }
                        } finally { b.recycle() }
                    } finally { a.recycle() }
                    comparisons++
                    if (useBlur) blurredComparisons++
                }
            }
        } finally {
            sharp.recycle()
            blurred.recycle()
        }
        check(maxDelta == 0) { "Bounded contour GPU pixels changed: delta=$maxDelta case=$worstCase" }
        Bundle().apply {
            putString("result", "PASS")
            putInt("cases", comparisons)
            putInt("geometryCases", cases.size)
            putInt("blurredComparisons", blurredComparisons)
            putInt("fallbackCases", fallbackCases)
            putInt("maxChannelDelta", maxDelta)
        }
    }.getOrElse { Bundle().apply { putString("error", it.stackTraceToString()) } }

    private fun geometryCases(): List<Case> {
        fun shape(name: String, w: Int, h: Int, radius: Float, smoothing: Float): Case {
            val path = Path().apply {
                setBottomBarSquircle(RectF(0f, 0f, w.toFloat(), h.toFloat()), radius, smoothing)
            }
            val measure = PathMeasure(path, true)
            check(measure.length.isFinite() && measure.length > 0f)
            val points = FloatArray(128)
            val point = FloatArray(2)
            repeat(64) { i ->
                check(measure.getPosTan(measure.length * i / 64f, point, null))
                points[i * 2] = point[0]
                points[i * 2 + 1] = point[1]
            }
            return Case(name, w, h, points)
        }
        fun translated(base: Case, name: String, x: Float, y: Float): Case {
            val points = base.points.copyOf()
            repeat(64) { i -> points[2 * i] += x; points[2 * i + 1] += y }
            return base.copy(name = name, points = points, offsetX = x, offsetY = y)
        }
        fun reordered(base: Case, name: String, reverse: Boolean): Case {
            val points = FloatArray(128)
            repeat(64) { i ->
                val source = if (reverse) 63 - i else (i + 17) % 64
                points[2 * i] = base.points[2 * source]
                points[2 * i + 1] = base.points[2 * source + 1]
            }
            return base.copy(name = name, points = points)
        }
        val circle = shape("circle", 64, 64, 32f, -1f)
        val capsule = shape("capsule-wide", 288, 50, 25f, -1f)
        return listOf(
            circle,
            shape("pressed", 120, 56, 28f, -1.2f),
            capsule,
            shape("portrait", 72, 112, 36f, -.5f),
            shape("small-radius", 160, 56, 12f, 0f),
            shape("near-square", 96, 96, 4f, .5f),
            shape("positive-smoothing", 300, 64, 32f, .75f),
            shape("max-smoothing", 210, 80, 40f, 1f),
            translated(capsule, "fractional-offset", 25.125f, -13.75f),
            translated(circle, "negative-offset", -512.0625f, -128.125f),
            reordered(capsule, "reversed-wide", true),
            reordered(circle, "reversed-circle", true),
            reordered(capsule, "shifted-closure", false),
            capsule.copy(name = "invalid-bounds-fallback", invalidBounds = true),
            translated(circle, "large-coordinate-fallback", 2_000_000f, 2_000_000f),
            capsule.copy(name = "explicit-disabled-fallback", forceDisabled = true),
        )
    }

    private fun render(
        sdf: String,
        case: Case,
        bounds: ContourSearchBounds,
        specialized: Boolean,
        mode: Int,
        content: Bitmap,
    ): Bitmap {
        val shader = RuntimeShader(sdf + """
            uniform float2 probeOffset;
            uniform float probeMode;
            uniform shader probeContent;
            half4 main(float2 coord) {
                if (continuousShape < 0.5) return half4(0.0);
                float2 local = coord - float2(8.0);
                float3 v = continuousContour(local + probeOffset);
                if (probeMode < 0.5)
                    return half4(0.5 + v.x / 128.0, 0.5 + v.y * 0.45, 0.5 + v.z * 0.45, 1.0);
                // A high-contrast synthetic refractive proxy catches normal and distance changes.
                float rim = clamp(1.0 + v.x / 12.0, 0.0, 1.0);
                float2 mapped = local + v.yz * (rim * 10.0);
                return probeContent.eval(mapped);
            }
        """)
        shader.setFloatUniform("shapePoints", case.points)
        var area = 0f
        repeat(64) { i ->
            val next = (i + 1) % 64
            area += case.points[2 * i] * case.points[2 * next + 1] -
                case.points[2 * next] * case.points[2 * i + 1]
        }
        shader.setFloatUniform("shapeOrientation", if (area >= 0f) 1f else -1f)
        shader.setFloatUniform("continuousShape", 1f)
        shader.setFloatUniform("probeOffset", case.offsetX, case.offsetY)
        shader.setFloatUniform("probeMode", mode.toFloat())
        shader.setInputShader("probeContent", BitmapShader(content, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR))
        if (specialized) {
            shader.setFloatUniform("contourGroupBounds", bounds.boxes)
            shader.setFloatUniform("contourSearchEnabled", if (bounds.isEnabled && !case.forceDisabled) 1f else 0f)
        }
        val width = case.width + 16
        val height = case.height + 16
        return renderNode(width, height) { canvas ->
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply { this.shader = shader })
        }
    }

    private fun checkerboard(): Bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply {
        val pixels = IntArray(width * height)
        repeat(height) { y -> repeat(width) { x ->
            pixels[y * width + x] = when (((x / 4) + (y / 4)) % 4) {
                0 -> Color.rgb(237, 41, 57)
                1 -> Color.rgb(12, 193, 244)
                2 -> Color.rgb(245, 224, 29)
                else -> Color.rgb(17, 25, 56)
            }
        } }
        setPixels(pixels, 0, width, 0, 0, width, height)
    }

    private fun maximumChannelDifference(a: Bitmap, b: Bitmap): Int {
        check(a.width == b.width && a.height == b.height)
        val first = IntArray(a.width * a.height)
        val second = IntArray(first.size)
        a.getPixels(first, 0, a.width, 0, 0, a.width, a.height)
        b.getPixels(second, 0, b.width, 0, 0, b.width, b.height)
        var delta = 0
        for (i in first.indices) {
            for (shift in 0..24 step 8) {
                delta = max(delta, abs((first[i] ushr shift and 255) - (second[i] ushr shift and 255)))
            }
        }
        return delta
    }

    internal fun renderNode(
        width: Int,
        height: Int,
        effect: RenderEffect? = null,
        draw: (Canvas) -> Unit,
    ): Bitmap {
        val root = RenderNode("BoundedContourProbe")
        root.setPosition(0, 0, width, height)
        root.setRenderEffect(effect)
        val canvas = root.beginRecording(width, height)
        try { draw(canvas) } finally { root.endRecording() }
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
        val renderer = HardwareRenderer()
        try {
            renderer.setSurface(reader.surface)
            renderer.setContentRoot(root)
            renderer.setOpaque(false)
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            var image = reader.acquireLatestImage()
            repeat(50) { if (image == null) { Thread.sleep(10); image = reader.acquireLatestImage() } }
            checkNotNull(image).use { frame -> checkNotNull(frame.hardwareBuffer).use { buffer ->
                val bitmap = checkNotNull(Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB)))
                try { return checkNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, false)) }
                finally { bitmap.recycle() }
            } }
        } finally {
            renderer.destroy()
            reader.close()
            root.discardDisplayList()
        }
    }
}
